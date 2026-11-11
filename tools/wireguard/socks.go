package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// Minimal SOCKS5 server (RFC 1928): no authentication, CONNECT only. That is
// all the app's ProxiedSocketFactory and OkHttp need. It listens on loopback
// only; the port is not reachable from the car's LAN or the hotspot.

const (
	socksVersion = 5

	cmdConnect = 1

	atypIPv4   = 1
	atypDomain = 3
	atypIPv6   = 4

	repSuccess          = 0
	repGeneralFailure   = 1
	repNetUnreachable   = 3
	repHostUnreachable  = 4
	repConnRefused      = 5
	repCmdNotSupported  = 7
	repAtypNotSupported = 8
)

// dialFunc connects to host:port, where host is an IP literal or a name from
// the SOCKS request. Resolution is left to the dialer because the right
// resolver depends on the route (tunnel DNS, sing-box, system).
type dialFunc func(ctx context.Context, host string, port uint16) (net.Conn, error)

type socksServer struct {
	dial dialFunc
	max  int64 // concurrent connection cap, 0 = unlimited

	active       atomic.Int64  // currently open SOCKS connections
	acceptErrors atomic.Uint64 // accept failures survived so far
	rejected     atomic.Uint64 // connections closed because the cap was reached
}

const (
	acceptBackoffMin = 5 * time.Millisecond
	acceptBackoffMax = time.Second
	acceptLogEvery   = 10 * time.Second
)

// acceptLoop calls accept until the listener is closed. An accept error is
// never fatal: EMFILE, ENFILE, ECONNABORTED and similar conditions pass, and
// ending the loop would leave the listener open with nobody accepting, so
// every later connect would hang in the kernel backlog. Errors are retried
// with a doubling delay (5 ms up to 1 s) and logged at a limited rate.
// count, if not nil, is incremented per failed accept. stopped, if not nil,
// reports that the owner is shutting down: listeners that are not plain
// net listeners (the netstack one) do not return net.ErrClosed when closed.
func acceptLoop(ln net.Listener, name string, count *atomic.Uint64, stopped func() bool, handle func(net.Conn)) error {
	var (
		delay      time.Duration
		lastLog    time.Time
		suppressed int
	)
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) || (stopped != nil && stopped()) {
				return nil
			}
			if count != nil {
				count.Add(1)
			}
			if time.Since(lastLog) >= acceptLogEvery {
				if suppressed > 0 {
					log.Printf("%s: accept: %v (%d similar errors suppressed)", name, err, suppressed)
				} else {
					log.Printf("%s: accept: %v", name, err)
				}
				lastLog, suppressed = time.Now(), 0
			} else {
				suppressed++
			}
			if delay == 0 {
				delay = acceptBackoffMin
			} else if delay *= 2; delay > acceptBackoffMax {
				delay = acceptBackoffMax
			}
			time.Sleep(delay)
			continue
		}
		delay = 0
		handle(c)
	}
}

func (s *socksServer) serve(ln net.Listener) error {
	var (
		lastLog    time.Time
		suppressed int
	)
	return acceptLoop(ln, "socks", &s.acceptErrors, nil, func(c net.Conn) {
		// The accept-loop fix only survives EMFILE; the cap keeps the daemon
		// away from the fd limit in the first place (a direct SOCKS session
		// costs two fds, a tunnel session one).
		if !s.reserveConnection() {
			_ = c.Close()
			s.rejected.Add(1)
			if time.Since(lastLog) >= acceptLogEvery {
				if suppressed > 0 {
					log.Printf("socks: connection limit %d reached, closing new connections (%d similar suppressed)", s.max, suppressed)
				} else {
					log.Printf("socks: connection limit %d reached, closing new connections", s.max)
				}
				lastLog, suppressed = time.Now(), 0
			} else {
				suppressed++
			}
			return
		}
		go func() {
			defer s.active.Add(-1)
			s.handle(c)
		}()
	})
}

// reserveConnection atomically enforces the connection cap even when
// multiple accept loops share the same server. A rejected connection does
// not change the active count.
func (s *socksServer) reserveConnection() bool {
	if s.max <= 0 {
		s.active.Add(1)
		return true
	}
	for {
		active := s.active.Load()
		if active >= s.max {
			return false
		}
		if s.active.CompareAndSwap(active, active+1) {
			return true
		}
	}
}

func (s *socksServer) handle(c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(30 * time.Second))

	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil || hdr[0] != socksVersion {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	noAuth := false
	for _, m := range methods {
		if m == 0 {
			noAuth = true
		}
	}
	if !noAuth {
		_ = writeAll(c, []byte{socksVersion, 0xff})
		return
	}
	if err := writeAll(c, []byte{socksVersion, 0}); err != nil {
		return
	}

	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil || req[0] != socksVersion {
		return
	}
	if req[1] != cmdConnect {
		reply(c, repCmdNotSupported)
		return
	}
	host, port, ok := readAddr(c, req[3])
	if !ok {
		reply(c, repAtypNotSupported)
		return
	}

	if !validHost(host) {
		reply(c, repHostUnreachable)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	remote, err := s.dial(ctx, host, port)
	if err != nil {
		log.Printf("socks: connect %s: %v", net.JoinHostPort(host, strconv.Itoa(int(port))), err)
		reply(c, errToReply(err))
		return
	}
	defer remote.Close()

	if err := reply(c, repSuccess); err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	pipe(c, remote)
}

func readAddr(r io.Reader, atyp byte) (string, uint16, bool) {
	var host string
	switch atyp {
	case atypIPv4:
		var b [4]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", 0, false
		}
		host = netip.AddrFrom4(b).String()
	case atypIPv6:
		var b [16]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return "", 0, false
		}
		host = netip.AddrFrom16(b).String()
	case atypDomain:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return "", 0, false
		}
		b := make([]byte, l[0])
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, false
		}
		host = string(b)
	default:
		return "", 0, false
	}
	var p [2]byte
	if _, err := io.ReadFull(r, p[:]); err != nil {
		return "", 0, false
	}
	return host, binary.BigEndian.Uint16(p[:]), true
}

// validHost accepts IP literals and DNS names. The name may be passed on to
// sing-box in an HTTP CONNECT line, so nothing else may get through.
func validHost(h string) bool {
	if _, err := netip.ParseAddr(h); err == nil {
		return true
	}
	if h == "" || len(h) > 253 {
		return false
	}
	for _, ch := range h {
		switch {
		case ch >= 'a' && ch <= 'z', ch >= 'A' && ch <= 'Z', ch >= '0' && ch <= '9',
			ch == '.', ch == '-', ch == '_':
		default:
			return false
		}
	}
	return true
}

// writeAll sends the entire protocol message, including when a writer reports
// a short write without an error. A zero-progress write must not spin forever.
func writeAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if n < 0 || n > len(data) {
			return io.ErrShortWrite
		}
		data = data[n:]
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
	}
	return nil
}

func reply(w io.Writer, code byte) error {
	return writeAll(w, []byte{socksVersion, code, 0, atypIPv4, 0, 0, 0, 0, 0, 0})
}

func errToReply(err error) byte {
	var dnsErr *net.DNSError
	switch {
	case err == nil:
		return repSuccess
	case errors.Is(err, syscall.ECONNREFUSED), strings.Contains(err.Error(), "connection refused"):
		return repConnRefused
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &dnsErr):
		return repHostUnreachable
	case errors.Is(err, syscall.ENETUNREACH):
		return repNetUnreachable
	}
	return repGeneralFailure
}

// pipeIdle bounds how long the second direction of a pipe may stay silent
// once the first one has finished. Without it a peer that vanished after
// half-closing would keep both sockets and both goroutines forever.
var pipeIdle = exposeIdle

// Reuse transfer buffers across SOCKS sessions to reduce allocation churn.
var pipeBuffers = sync.Pool{New: func() interface{} {
	return make([]byte, 32*1024)
}}

// pipe copies in both directions until both are done, then closes both
// connections. A finished direction half-closes its destination when
// possible. A write error means the destination is gone, so both conns are
// closed at once to unblock the other direction.
func pipe(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	var (
		wg         sync.WaitGroup
		halfClosed atomic.Bool
	)
	wg.Add(2)
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		buf := pipeBuffers.Get().([]byte)
		defer pipeBuffers.Put(buf)
		for {
			n, err := src.Read(buf)
			if n > 0 {
				// A short write without an error must not silently drop data.
				written, werr := dst.Write(buf[:n])
				if werr != nil || written != n {
					_ = a.Close()
					_ = b.Close()
					return
				}
			}
			if err != nil {
				break
			}
			if halfClosed.Load() {
				_ = src.SetReadDeadline(time.Now().Add(pipeIdle))
			}
		}
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
			_ = src.Close()
		}
		// Reads of the opposite direction are on dst; start its idle clock.
		halfClosed.Store(true)
		_ = dst.SetReadDeadline(time.Now().Add(pipeIdle))
	}
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
}

// dialViaConnect opens an HTTP CONNECT tunnel through a local proxy. Used for
// destinations outside AllowedIPs when sing-box is the car's internet path.
// HTTP CONNECT (not SOCKS) is the path ProxyHelper has always used for
// sing-box, and the host name is passed through so sing-box resolves it:
// in the states where sing-box is needed, local DNS may not work either.
func dialViaConnect(ctx context.Context, proxy, target string) (net.Conn, error) {
	c, err := direct.DialContext(ctx, "tcp", proxy)
	if err != nil {
		return nil, err
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	req := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n"
	if err := writeAll(c, []byte(req)); err != nil {
		c.Close()
		return nil, err
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		c.Close()
		return nil, err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		c.Close()
		return nil, fmt.Errorf("upstream proxy: CONNECT %s: %s", target, resp.Status)
	}
	_ = c.SetDeadline(time.Time{})
	if br.Buffered() > 0 {
		// ReadResponse may have read tunnel bytes beyond the CONNECT headers.
		// Preserve those bytes before reading directly from the connection.
		return &bufferedConn{Conn: c, reader: br}, nil
	}
	return c, nil
}

// bufferedConn preserves data already read by the HTTP response parser.
// Other net.Conn operations (including deadlines) pass
// through to the underlying connection.
type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) {
	return c.reader.Read(p)
}

// Keep TCP half-close behavior when the buffered wrapper is used.
func (c *bufferedConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return c.Conn.Close()
}
