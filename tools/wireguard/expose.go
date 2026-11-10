package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Inbound exposure: a tunnel port is forwarded to a loopback backend. This is
// the WireGuard counterpart of `tailscale serve --proxy-protocol=1 --tcp=...`.
// Every forwarded connection starts with a PROXY protocol v1 line carrying the
// real tunnel peer address. The backend (HttpServer) honours it only from a
// loopback socket and then requires normal authentication; without the line a
// remote caller would look like 127.0.0.1 and bypass the loopback auth
// fallback. Nothing is exposed unless -expose is given.

const (
	maxExposeConns    = 128
	exposeDialTimeout = 5 * time.Second
	// Idle limit per direction. Live view and WebSocket traffic is continuous
	// or pinged; a peer that vanished without FIN is reaped after this.
	exposeIdle            = 5 * time.Minute
	exposeWrite           = 30 * time.Second
	exposeDeadlineRefresh = 5 * time.Second
	exposeCopyBufferSize  = 64 * 1024
)

type exposeSpec struct {
	Port    uint16
	Backend string // loopback host:port
}

// parseExpose parses "<tunnelport>=<loopback host:port>".
func parseExpose(s string) (exposeSpec, error) {
	ps, backend, ok := strings.Cut(s, "=")
	if !ok {
		return exposeSpec{}, fmt.Errorf("-expose %q: want <port>=<host:port>", s)
	}
	port, err := strconv.ParseUint(strings.TrimSpace(ps), 10, 16)
	if err != nil || port == 0 {
		return exposeSpec{}, fmt.Errorf("-expose %q: bad tunnel port", s)
	}
	h, p, err := net.SplitHostPort(strings.TrimSpace(backend))
	if err != nil {
		return exposeSpec{}, fmt.Errorf("-expose %q: %v", s, err)
	}
	if bp, err := strconv.ParseUint(p, 10, 16); err != nil || bp == 0 {
		return exposeSpec{}, fmt.Errorf("-expose %q: bad backend port", s)
	}
	if ip, err := netip.ParseAddr(h); err != nil || !ip.IsLoopback() {
		return exposeSpec{}, fmt.Errorf("-expose %q: backend must be a loopback address", s)
	}
	return exposeSpec{Port: uint16(port), Backend: net.JoinHostPort(h, p)}, nil
}

// proxyV1Header builds the PROXY v1 line for a connection from src to dst.
func proxyV1Header(src, dst netip.AddrPort) (string, error) {
	s, d := src.Addr().Unmap(), dst.Addr().Unmap()
	if !s.IsValid() || !d.IsValid() || s.Is4() != d.Is4() {
		return "", errors.New("proxy header: address family mismatch")
	}
	proto := "TCP6"
	if s.Is4() {
		proto = "TCP4"
	}
	return fmt.Sprintf("PROXY %s %s %s %d %d\r\n", proto, s, d, src.Port(), dst.Port()), nil
}

type exposeServer struct {
	spec exposeSpec
	sem  chan struct{}

	mu     sync.Mutex
	lns    []net.Listener
	conns  map[net.Conn]struct{}
	closed bool
	wg     sync.WaitGroup
}

func newExposeServer(spec exposeSpec) *exposeServer {
	return &exposeServer{spec: spec, sem: make(chan struct{}, maxExposeConns), conns: map[net.Conn]struct{}{}}
}

// serve accepts on ln until it is closed.
func (e *exposeServer) serve(ln net.Listener) {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		ln.Close()
		return
	}
	e.lns = append(e.lns, ln)
	e.wg.Add(1)
	e.mu.Unlock()
	defer e.wg.Done()
	_ = acceptLoop(ln, "expose "+strconv.Itoa(int(e.spec.Port)), nil, e.isClosed, func(c net.Conn) {
		select {
		case e.sem <- struct{}{}:
		default:
			c.Close()
			return
		}
		if !e.track(c) {
			<-e.sem
			return
		}
		e.wg.Add(1)
		go func() {
			defer e.wg.Done()
			defer func() { <-e.sem }()
			e.handle(c)
		}()
	})
}

func (e *exposeServer) isClosed() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.closed
}

func (e *exposeServer) track(c net.Conn) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		c.Close()
		return false
	}
	e.conns[c] = struct{}{}
	return true
}

func (e *exposeServer) untrack(c net.Conn) {
	e.mu.Lock()
	delete(e.conns, c)
	e.mu.Unlock()
	c.Close()
}

// close stops accepting, drops open connections and waits for the goroutines.
func (e *exposeServer) close() {
	e.mu.Lock()
	e.closed = true
	for _, ln := range e.lns {
		ln.Close()
	}
	for c := range e.conns {
		c.Close()
	}
	e.mu.Unlock()
	e.wg.Wait()
}

func addrPortOf(a net.Addr) (netip.AddrPort, bool) {
	t, ok := a.(*net.TCPAddr)
	if !ok {
		return netip.AddrPort{}, false
	}
	ip, ok := netip.AddrFromSlice(t.IP)
	if !ok {
		return netip.AddrPort{}, false
	}
	return netip.AddrPortFrom(ip.Unmap(), uint16(t.Port)), true
}

func (e *exposeServer) handle(c net.Conn) {
	defer e.untrack(c)
	src, ok1 := addrPortOf(c.RemoteAddr())
	dst, ok2 := addrPortOf(c.LocalAddr())
	if !ok1 || !ok2 {
		return
	}
	hdr, err := proxyV1Header(src, dst)
	if err != nil {
		log.Printf("expose %d: %v", e.spec.Port, err)
		return
	}
	b, err := net.DialTimeout("tcp", e.spec.Backend, exposeDialTimeout)
	if err != nil {
		log.Printf("expose %d: backend: %v", e.spec.Port, err)
		return
	}
	if !e.track(b) {
		return
	}
	defer e.untrack(b)
	_ = b.SetWriteDeadline(time.Now().Add(exposeWrite))
	if _, err := io.WriteString(b, hdr); err != nil {
		return
	}
	spliceIdle(c, b)
}

// spliceIdle copies both directions without buffering whole bodies. A finished
// direction half-closes its destination; the connections are closed by the
// caller once both directions are done.
func spliceIdle(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); copyIdle(a, b) }()
	go func() { defer wg.Done(); copyIdle(b, a) }()
	wg.Wait()
}

func copyIdle(dst, src net.Conn) {
	buf := make([]byte, exposeCopyBufferSize)
	var nextReadRefresh, nextWriteRefresh time.Time
	for {
		// Avoid resetting the socket timer for every chunk on busy streams.
		now := time.Now()
		if !now.Before(nextReadRefresh) {
			_ = src.SetReadDeadline(now.Add(exposeIdle))
			nextReadRefresh = now.Add(exposeDeadlineRefresh)
		}
		n, err := src.Read(buf)
		if n > 0 {
			now = time.Now()
			if !now.Before(nextWriteRefresh) {
				_ = dst.SetWriteDeadline(now.Add(exposeWrite))
				nextWriteRefresh = now.Add(exposeDeadlineRefresh)
			}
			// A larger buffer must not drop bytes on a short write.
			for sent := 0; sent < n; {
				written, werr := dst.Write(buf[sent:n])
				sent += written
				if werr != nil || written == 0 {
					// Destination is gone: unblock the opposite copy.
					_ = src.Close()
					return
				}
			}
		}
		if err != nil {
			break
		}
	}
	if cw, ok := dst.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	} else {
		_ = dst.Close()
	}
}
