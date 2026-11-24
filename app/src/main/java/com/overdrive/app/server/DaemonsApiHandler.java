package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.ui.model.DaemonType;
import com.overdrive.app.ui.model.ParkedShutdown;
import com.overdrive.app.util.ScratchRoot;
import com.overdrive.app.wireguard.WireGuardStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only daemon status for the WebUI.
 *
 * <pre>
 *   GET /api/daemons
 * </pre>
 *
 * Start/stop/enable live in the UI process, which the daemon cannot call, so this only reports
 * what the daemon's UID can read: the process table, the cross-UID {@code .disabled} sentinels,
 * the parked-shutdown marker and each running tunnel's own log, status file or CLI.
 * "enabled" is an app-private preference and is not reported.
 */
public final class DaemonsApiHandler {

    /** One {@code ps} fork serves every poller inside this window. */
    private static final long SNAPSHOT_TTL_MS = 2_000L;
    private static final long PS_TIMEOUT_MS = 3_000L;
    private static final long CLI_TIMEOUT_MS = 4_000L;
    private static final long URL_RETRY_MS = 10_000L;
    private static final long UNDECIDED_RETRY_MS = 5 * 60_000L;
    private static final long NAMED_TUNNEL_TTL_MS = 5 * 60_000L;
    private static final long TAILSCALE_TTL_MS = 60_000L;
    private static final long TAILSCALE_RETRY_MS = 15_000L;
    private static final int CLOUDFLARED_LOG_HEAD_BYTES = 512 * 1024;
    private static final long ZROK_LOG_SCAN_BYTES = 4L * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final String DASHBOARD_PORT = "8080";
    private static final String DASHBOARD_ORIGIN = "127.0.0.1:" + DASHBOARD_PORT;
    private static final String ZROK_SESSION_MARKER = "Starting zrok share...";

    private static final Pattern QUICK_TUNNEL_URL =
            Pattern.compile("https://[a-z0-9]+-[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Pattern NAMED_TUNNEL_HOST = Pattern.compile(
            "hostname[\\\\\"':= ]{1,6}([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})");
    private static final Pattern ZROK_SHARE_URL =
            Pattern.compile("https://([a-z0-9-]+)\\.share\\.zrok\\.io", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZROK_TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]*:[0-9A-Fa-f:]*");

    private static final Object SNAPSHOT_LOCK = new Object();
    private static boolean snapshotCached;
    private static List<Proc> cachedSnapshot;
    private static long cachedAtMs;

    private static final Object URL_LOCK = new Object();
    private static final UrlCache CLOUDFLARED_URL = new UrlCache();
    private static final UrlCache ZROK_URL = new UrlCache();
    private static final UrlCache TAILSCALE_URL = new UrlCache();

    // Tunnel sets change rarely; a slower refresh keeps parked polling from forking ps every 30 s.
    private static final long STABLE_HOSTS_TTL_MS = 5L * 60_000L;
    private static final long RECENT_STABLE_MS = 30L * 60_000L;
    private static final long BOOT_GRACE_MS = 10L * 60_000L;
    private static final long STARTED_MS = monotonicMs();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> RECENT_STABLE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicBoolean STABLE_HOSTS_REFRESHING = new AtomicBoolean();
    /** Null until the first background read completes. */
    private static volatile StableHosts stableHosts;

    private DaemonsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!"/api/daemons".equals(clean)) return false;

        if (!"GET".equals(method)) {
            HttpResponse.sendJsonNoCors(out, 405, "{\"status\":\"error\",\"message\":\"method not allowed\"}");
            return true;
        }
        sendStatus(out);
        return true;
    }

    private static void sendStatus(OutputStream out) throws Exception {
        // null means the probe failed: report "unknown", never "stopped".
        List<Proc> procs = processSnapshot();

        JSONArray list = new JSONArray();
        int running = 0;
        DaemonType[] types = DaemonType.values();
        for (DaemonType type : types) {
            JSONObject d = new JSONObject();
            d.put("id", type.name());
            d.put("name", type.getDisplayName());
            d.put("process", type.getProcessName());
            d.put("group", isCore(type) ? "core" : "optional");
            if (procs == null) {
                d.put("running", JSONObject.NULL);
                d.put("starting", false);
            } else {
                boolean alive = findMain(procs, type) != null;
                d.put("running", alive);
                d.put("starting", !alive && supervisorAlive(procs, type));
                if (alive) running++;
            }
            d.put("userStopped", isUserStopped(type));
            list.put(d);
        }

        boolean hideUrls = CameraDaemon.isPublicMode();
        JSONObject root = new JSONObject();
        root.put("status", "ok");
        root.put("ts", System.currentTimeMillis());
        root.put("total", types.length);
        root.put("running", procs == null ? JSONObject.NULL : (Object) running);
        root.put("parkedShutdown", new File(ParkedShutdown.MARKER_PATH).exists());
        root.put("daemons", list);
        root.put("tunnels", buildTunnels(procs, hideUrls));
        root.put("urlsHidden", hideUrls);
        HttpResponse.sendJsonNoCors(out, root.toString());
    }

    private static boolean isCore(DaemonType type) {
        return type == DaemonType.CAMERA_DAEMON
                || type == DaemonType.SENTRY_DAEMON
                || type == DaemonType.ACC_SENTRY_DAEMON;
    }

    // ---- process table -------------------------------------------------------------------

    /** One {@code ps -A -o S,PID,ARGS} row; {@code name} is argv[0] without its path. */
    static final class Proc {
        final String pid;
        final String name;
        final String[] argv;

        Proc(String pid, String name, String[] argv) {
            this.pid = pid;
            this.name = name;
            this.argv = argv;
        }
    }

    static List<Proc> parseSnapshot(String output) {
        List<Proc> rows = new ArrayList<>();
        for (String raw : output.split("\n")) {
            String[] f = raw.trim().split("\\s+");
            if (f.length < 3 || f[0].startsWith("Z") || !isDigits(f[1])) continue;
            String[] argv = Arrays.copyOfRange(f, 2, f.length);
            String argv0 = argv[0];
            rows.add(new Proc(f[1], argv0.substring(argv0.lastIndexOf('/') + 1), argv));
        }
        return rows;
    }

    /**
     * The daemon's own process, matched on argv[0] the way {@code ps -A} NAME shows it, so the
     * watchdog shells, {@code sh -c} wrappers, {@code zrok_health_probe} and a passing
     * {@code grep <name>} never count. Zrok counts only while {@code zrok share} runs.
     */
    static Proc findMain(List<Proc> procs, DaemonType type) {
        String want = type.getProcessName();
        String packaged = "lib" + want + ".so";
        for (Proc p : procs) {
            if (!want.equals(p.name) && !packaged.equals(p.name)) continue;
            if (type == DaemonType.ZROK_TUNNEL && (p.argv.length < 2 || !"share".equals(p.argv[1]))) continue;
            return p;
        }
        return null;
    }

    /** A live watchdog script means the daemon is being respawned, not stopped. */
    static boolean supervisorAlive(List<Proc> procs, DaemonType type) {
        String script = supervisorScript(type);
        if (script == null) return false;
        for (Proc p : procs) {
            if ("sh".equals(p.name) && p.argv.length == 2
                    && (p.argv[1].equals(script) || p.argv[1].endsWith("/" + script))) {
                return true;
            }
        }
        return false;
    }

    private static String supervisorScript(DaemonType type) {
        if (type == DaemonType.CAMERA_DAEMON) return "start_cam_daemon.sh";
        if (type == DaemonType.ACC_SENTRY_DAEMON) return "start_acc_sentry.sh";
        if (type == DaemonType.TELEGRAM_DAEMON) return "start_telegram.sh";
        if (type == DaemonType.ZROK_TUNNEL) return "start_zrok.sh";
        return null;
    }

    /**
     * Same rule as the app's start gates: an empty or unreadable sentinel, or one written by the
     * UI/Telegram, is a user stop; update, killDaemon and ACC markers are the app's own.
     */
    static boolean isUserStopped(DaemonType type) {
        File f = new File(type.getSentinelPath());
        if (!f.isFile()) return false;
        String first = firstLine(f);
        return first == null || first.isEmpty()
                || first.startsWith("disabled by ui")
                || first.startsWith("disabled by telegram")
                || first.startsWith("disabled by user");
    }

    /** One bounded, cached {@code ps}; null when the probe failed. */
    private static List<Proc> processSnapshot() {
        synchronized (SNAPSHOT_LOCK) {
            // A failed probe is cached too (null = unknown), so pollers never queue behind a hung ps.
            if (snapshotCached && monotonicMs() - cachedAtMs < SNAPSHOT_TTL_MS) return cachedSnapshot;
            String out = run(PS_TIMEOUT_MS, "ps", "-A", "-o", "S,PID,ARGS");
            List<Proc> rows = out == null ? null : parseSnapshot(out);
            cachedSnapshot = rows == null || rows.isEmpty() ? null : rows;
            cachedAtMs = monotonicMs();
            snapshotCached = true;
            return cachedSnapshot;
        }
    }

    // ---- tunnels -------------------------------------------------------------------------

    private static JSONArray buildTunnels(List<Proc> procs, boolean hideUrls) throws Exception {
        JSONArray list = new JSONArray();
        if (procs == null) return list;
        synchronized (URL_LOCK) {
            addTunnel(list, "cloudflared", DaemonType.CLOUDFLARED_TUNNEL, procs, hideUrls);
            addTunnel(list, "zrok", DaemonType.ZROK_TUNNEL, procs, hideUrls);
            addTunnel(list, "tailscale", DaemonType.TAILSCALE_TUNNEL, procs, hideUrls);
            addTunnel(list, "wireguard", DaemonType.WIREGUARD_TUNNEL, procs, hideUrls);
        }
        return list;
    }

    private static void addTunnel(JSONArray list, String id, DaemonType type, List<Proc> procs,
                                  boolean hideUrls) throws Exception {
        Proc main = findMain(procs, type);
        if (main == null && !supervisorAlive(procs, type)) return;
        JSONObject t = new JSONObject();
        t.put("type", id);
        t.put("running", main != null);
        if (main != null) {
            String url = null;
            if (type == DaemonType.WIREGUARD_TUNNEL) {
                JSONObject status = WireGuardStore.readStatus();
                if (status != null && main.pid.equals(String.valueOf(status.optInt("pid", 0)))) {
                    String state = status.optString("state", "");
                    if (!state.isEmpty()) t.put("state", state);
                    if (!hideUrls && "connected".equals(state)) url = wireGuardUrl(status);
                }
            } else if (!hideUrls) {
                if (type == DaemonType.CLOUDFLARED_TUNNEL) url = cloudflaredUrl(main);
                else if (type == DaemonType.ZROK_TUNNEL) url = zrokUrl(main);
                else url = tailscaleUrl(main);
            }
            if (url != null) t.put("url", url);
        }
        list.put(t);
    }

    /** cloudflared.log is truncated at every launch, so its head is the running process's. */
    private static String cloudflaredUrl(Proc main) {
        boolean named = Arrays.asList(main.argv).contains("--token");
        long now = monotonicMs();
        if (CLOUDFLARED_URL.fresh(main.pid, now, named ? NAMED_TUNNEL_TTL_MS : Long.MAX_VALUE)) {
            return CLOUDFLARED_URL.url;
        }
        String url = null;
        String head = readHead(new File(ScratchRoot.path("cloudflared.log")), CLOUDFLARED_LOG_HEAD_BYTES);
        if (head != null) {
            if (named) {
                // First hostname of the newest configuration line, as the app shows it.
                String host = null;
                for (String line : head.split("\n")) {
                    Matcher m = NAMED_TUNNEL_HOST.matcher(line);
                    if (m.find()) host = m.group(1);
                }
                if (host != null) url = "https://" + host.toLowerCase(Locale.ROOT);
            } else {
                Matcher m = QUICK_TUNNEL_URL.matcher(head);
                if (m.find()) url = m.group();
            }
        }
        CLOUDFLARED_URL.put(main.pid, url, now, URL_RETRY_MS);
        return url;
    }

    /**
     * A reserved share's URL is its token. A public share's is the one printed after the newest
     * "Starting zrok share..." marker in zrok.log, or in zrok.log.1 when an in-place truncation
     * dropped that marker.
     */
    private static String zrokUrl(Proc share) {
        if (share.argv.length > 3 && "reserved".equals(share.argv[2])) {
            String token = share.argv[3];
            return ZROK_TOKEN.matcher(token).matches() ? "https://" + token + ".share.zrok.io" : null;
        }
        long now = monotonicMs();
        if (ZROK_URL.fresh(share.pid, now, Long.MAX_VALUE)) return ZROK_URL.url;
        File log = new File(ScratchRoot.path("zrok.log"));
        String name = null;
        if (log.isFile()) {
            name = newestZrokShareName(log);
            if (name == null && log.length() <= ZROK_LOG_SCAN_BYTES) {
                name = newestZrokShareName(new File(log.getPath() + ".1"));
            }
        }
        String url = name == null || name.isEmpty()
                ? null
                : "https://" + name.toLowerCase(Locale.ROOT) + ".share.zrok.io";
        // A session still before its URL line is re-read soon; an undecided scan cannot improve.
        ZROK_URL.put(share.pid, url, now, name == null ? UNDECIDED_RETRY_MS : URL_RETRY_MS);
        return url;
    }

    /** Share name of the newest session, "" when that session has none yet, null when undecided. */
    static String newestZrokShareName(File log) {
        if (!log.isFile()) return null;
        return scanNewestFirst(log, ZROK_LOG_SCAN_BYTES, line -> {
            String last = null;
            Matcher m = ZROK_SHARE_URL.matcher(line);
            while (m.find()) last = m.group(1);
            if (last != null) return last;
            return line.contains(ZROK_SESSION_MARKER) ? "" : null;
        });
    }

    /**
     * The address the app itself shows: https://&lt;MagicDNS&gt; when its HTTPS Serve is active,
     * else http://&lt;tailnet IP&gt;:8080 once the PROXY-protocol dashboard Serve is in place.
     */
    private static String tailscaleUrl(Proc tailscaled) {
        if (TAILSCALE_URL.fresh(tailscaled.pid, monotonicMs(), TAILSCALE_TTL_MS)) return TAILSCALE_URL.url;
        String url = resolveTailscaleUrl();
        TAILSCALE_URL.put(tailscaled.pid, url, monotonicMs(), TAILSCALE_RETRY_MS);
        return url;
    }

    private static String resolveTailscaleUrl() {
        String cli = ScratchRoot.path(".tailscale/tailscale");
        if (!new File(cli).canExecute()) return null;
        JSONObject status = jsonFrom(run(CLI_TIMEOUT_MS, cli, "--socket", "127.0.0.1:8532", "status", "--json"));
        if (status == null || !"running".equalsIgnoreCase(status.optString("BackendState", ""))) return null;
        JSONObject self = status.optJSONObject("Self");
        if (self == null) return null;
        String ip = null;
        JSONArray ips = self.optJSONArray("TailscaleIPs");
        for (int i = 0; ips != null && i < ips.length() && ip == null; i++) {
            String candidate = ips.optString(i, "");
            if (IPV4.matcher(candidate).matches()) ip = candidate;
        }
        String dns = trimDot(self.optString("DNSName", ""));
        JSONObject serve = jsonFrom(run(CLI_TIMEOUT_MS, cli, "--socket", "127.0.0.1:8532", "serve", "status", "--json"));
        if (serve == null) return null;
        String httpsFlag = firstLine(new File(ScratchRoot.path(".tailscale/https_enabled")));
        if (HOST_NAME.matcher(dns).matches() && servesHttps(serve, dns)
                && httpsFlag != null && "true".equals(httpsFlag.trim())) {
            return "https://" + dns.toLowerCase(Locale.ROOT);
        }
        JSONObject tcp = serve.optJSONObject("TCP");
        JSONObject dashboard = tcp == null ? null : tcp.optJSONObject(DASHBOARD_PORT);
        if (ip != null && dashboard != null && dashboard.optInt("ProxyProtocol", 0) == 1
                && DASHBOARD_ORIGIN.equals(dashboard.optString("TCPForward", ""))) {
            return "http://" + ip + ":" + DASHBOARD_PORT;
        }
        return null;
    }

    private static boolean servesHttps(JSONObject serve, String dns) {
        JSONObject tcp = serve.optJSONObject("TCP");
        JSONObject https = tcp == null ? null : tcp.optJSONObject("443");
        if (https == null || !https.optBoolean("HTTPS", false)) return false;
        JSONObject web = serve.optJSONObject("Web");
        JSONObject site = web == null ? null : web.optJSONObject(dns + ":443");
        JSONObject handlers = site == null ? null : site.optJSONObject("Handlers");
        JSONObject root = handlers == null ? null : handlers.optJSONObject("/");
        return root != null && ("http://" + DASHBOARD_ORIGIN).equals(trimSlash(root.optString("Proxy", "")));
    }

    /** Only when the running wgproxy publishes the dashboard on its tunnel address. */
    private static String wireGuardUrl(JSONObject status) {
        JSONArray expose = status.optJSONArray("expose");
        boolean exposed = false;
        for (int i = 0; expose != null && i < expose.length(); i++) {
            if (String.valueOf(expose.optInt(i, -1)).equals(DASHBOARD_PORT)) exposed = true;
        }
        JSONArray addresses = status.optJSONArray("addresses");
        if (!exposed || addresses == null) return null;
        String v6 = null;
        for (int i = 0; i < addresses.length(); i++) {
            String a = addresses.optString(i, "");
            int slash = a.indexOf('/');
            if (slash >= 0) a = a.substring(0, slash);
            if (IPV4.matcher(a).matches()) return "http://" + a + ":" + DASHBOARD_PORT;
            if (v6 == null && IPV6.matcher(a).matches()) v6 = a;
        }
        return v6 == null ? null : "http://[" + v6 + "]:" + DASHBOARD_PORT;
    }

    // ---- stable public hosts -------------------------------------------------------------

    public static boolean isStablePublicHost(String host) {
        return Boolean.TRUE.equals(stablePublicHostState(host));
    }

    /**
     * TRUE when host is the fixed public host of a running tunnel (zrok reserved share, Cloudflare
     * named tunnel, Tailscale HTTPS), FALSE when it is not, null while that is not known. Never
     * blocks: answers from a cache refreshed in the background.
     */
    public static Boolean stablePublicHostState(String host) {
        try {
            String h = bareHost(host);
            if (h == null) return Boolean.FALSE;
            StableHosts cached = stableHosts;
            if (cached == null || monotonicMs() - cached.atMs >= STABLE_HOSTS_TTL_MS) refreshStableHosts();
            if (cached == null || cached.hosts == null) return null;
            if (cached.hosts.contains(h)) return Boolean.TRUE;
            if (cached.uncertain) return null;
            // A stable tunnel missing from one scan (e.g. restarting) is unknown, not "not stable".
            Long seen = RECENT_STABLE.get(h);
            if (seen != null && monotonicMs() - seen < RECENT_STABLE_MS) return null;
            // Just after the daemon starts, tunnels may still be coming up.
            return monotonicMs() - STARTED_MS < BOOT_GRACE_MS ? null : Boolean.FALSE;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lower-case host without port; null when empty. */
    static String bareHost(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        int cut = h.startsWith("[") ? h.indexOf(']') + 1 : h.indexOf(':');
        if (cut >= 0 && cut < h.length()) h = h.substring(0, cut);
        return h.isEmpty() ? null : h;
    }

    private static final class StableHosts {
        /** Null until the process table has been read. */
        final Set<String> hosts;
        /** A tunnel that can have a stable host did not resolve one, so other hosts stay unknown. */
        final boolean uncertain;
        final long atMs;

        StableHosts(Set<String> hosts, boolean uncertain, long atMs) {
            this.hosts = hosts;
            this.uncertain = uncertain;
            this.atMs = atMs;
        }
    }

    private static void refreshStableHosts() {
        if (!STABLE_HOSTS_REFRESHING.compareAndSet(false, true)) return;
        try {
            Thread t = new Thread(() -> {
                try {
                    StableHosts fresh = null;
                    try {
                        fresh = readStableHosts();
                    } catch (Throwable ignored) { }
                    if (fresh == null) {
                        // An unreadable process table keeps the previous answer.
                        StableHosts previous = stableHosts;
                        fresh = previous == null
                                ? new StableHosts(null, true, monotonicMs())
                                : new StableHosts(previous.hosts, previous.uncertain, monotonicMs());
                    }
                    stableHosts = fresh;
                } finally {
                    STABLE_HOSTS_REFRESHING.set(false);
                }
            }, "daemons-api-hosts");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            STABLE_HOSTS_REFRESHING.set(false);
        }
    }

    /** Null when the process table could not be read. */
    private static StableHosts readStableHosts() {
        List<Proc> procs = processSnapshot();
        if (procs == null) return null;
        Set<String> hosts = new HashSet<>();
        boolean uncertain = false;
        Proc zrok = findMain(procs, DaemonType.ZROK_TUNNEL);
        Proc cloudflared = findMain(procs, DaemonType.CLOUDFLARED_TUNNEL);
        Proc tailscaled = findMain(procs, DaemonType.TAILSCALE_TUNNEL);
        synchronized (URL_LOCK) {
            if (zrok != null && zrok.argv.length > 3 && "reserved".equals(zrok.argv[2])) {
                if (!addHttpsHost(hosts, zrokUrl(zrok))) uncertain = true;
            }
            if (cloudflared != null && Arrays.asList(cloudflared.argv).contains("--token")) {
                if (!addHttpsHost(hosts, cloudflaredUrl(cloudflared))) uncertain = true;
            }
            if (tailscaled != null) {
                // http:// is a resolved Serve without HTTPS; null is a failed or pending read.
                String url = tailscaleUrl(tailscaled);
                if (url == null) uncertain = true;
                addHttpsHost(hosts, url);
            }
        }
        long now = monotonicMs();
        for (String h : hosts) RECENT_STABLE.put(h, now);
        return new StableHosts(Collections.unmodifiableSet(hosts), uncertain, now);
    }

    /** False when url has no https host. */
    private static boolean addHttpsHost(Set<String> hosts, String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String h = bareHost(url.substring("https://".length()));
        if (h == null) return false;
        hosts.add(h);
        return true;
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Per-process URL, keyed by PID so a restarted tunnel is always re-read. */
    private static final class UrlCache {
        String key;
        String url;
        long atMs;
        long missTtlMs;

        boolean fresh(String k, long now, long foundTtlMs) {
            if (!k.equals(key) || now < atMs) return false;
            return now - atMs < (url != null ? foundTtlMs : missTtlMs);
        }

        void put(String k, String u, long now, long missTtl) {
            key = k;
            url = u;
            atMs = now;
            missTtlMs = missTtl;
        }
    }

    interface LineVisitor {
        /** Non-null ends the scan with that answer. */
        String visit(String line);
    }

    /** Visits lines newest first within the last {@code maxBytes}; null when none answered. */
    static String scanNewestFirst(File file, long maxBytes, LineVisitor visitor) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long pos = raf.length();
            long floor = Math.max(0L, pos - maxBytes);
            byte[] chunk = new byte[64 * 1024];
            byte[] rest = new byte[0];
            while (pos > floor) {
                int n = (int) Math.min(chunk.length, pos - floor);
                pos -= n;
                raf.seek(pos);
                raf.readFully(chunk, 0, n);
                int end = n;
                for (int i = n - 1; i >= 0; i--) {
                    if (chunk[i] != '\n') continue;
                    String answer = visitor.visit(join(chunk, i + 1, end, rest));
                    if (answer != null) return answer;
                    rest = new byte[0];
                    end = i;
                }
                if (rest.length + end <= MAX_LINE_BYTES) {
                    byte[] grown = new byte[end + rest.length];
                    System.arraycopy(chunk, 0, grown, 0, end);
                    System.arraycopy(rest, 0, grown, end, rest.length);
                    rest = grown;
                }
            }
            return floor == 0L && rest.length > 0 ? visitor.visit(new String(rest, StandardCharsets.ISO_8859_1)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String join(byte[] chunk, int from, int to, byte[] rest) {
        String head = new String(chunk, from, to - from, StandardCharsets.ISO_8859_1);
        return rest.length == 0 ? head : head + new String(rest, StandardCharsets.ISO_8859_1);
    }

    private static String readHead(File f, int maxBytes) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(maxBytes, Math.max(0L, f.length()))];
            int total = 0;
            int n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            return new String(buf, 0, total, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstLine(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return null;
        }
    }

    private static JSONObject jsonFrom(String output) {
        if (output == null) return null;
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            return new JSONObject(output.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimDot(String s) {
        String t = s.trim();
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Runs argv with a hard timeout; null on failure, timeout or empty output. */
    private static String run(long timeoutMs, String... argv) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            final InputStream is = p.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] b = new byte[8192];
                try {
                    int n;
                    while ((n = is.read(b)) != -1) {
                        synchronized (buf) { buf.write(b, 0, n); }
                    }
                } catch (Throwable ignored) { }
            }, "daemons-api-exec");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(500);
            synchronized (buf) {
                return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return null;
        }
    }
}
