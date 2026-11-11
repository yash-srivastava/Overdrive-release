package com.overdrive.app.logging;

import android.util.Log;

import com.overdrive.app.BuildConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Reads a daemon log file, redacts secrets, and uploads it to the Cloudflare
 * log-upload Worker (see cloud/log-upload-worker/), returning a short retrieval
 * code the customer reads back to the maintainer.
 *
 * Only meaningful in the braveheart build, where {@link BuildConfig#LOG_CAPTURE}
 * is true (DaemonLogger calls survive R8 and write to /data/local/tmp/&lt;tag&gt;.log)
 * AND {@link BuildConfig#LOG_UPLOAD_URL} points at the deployed Worker. When the
 * URL is empty (plain release/debug), {@link #isUploadConfigured()} is false and
 * callers fall back to the local share-sheet path.
 *
 * Process-agnostic: a plain {@code File} read works in both the daemon process
 * (UID 2000, owns /data/local/tmp) and the app process (the files are
 * world-readable). Network goes through {@link com.overdrive.app.mqtt.ProxyHelper}
 * so it shares the sing-box/Tailscale proxy the rest of the app uses.
 */
public final class LogUploader {

    private static final String TAG = "LogUploader";

    /** Tail cap: last N bytes of the log. Keeps uploads small + the Worker
     *  body well under its cap. The tail is where a fresh repro lives. */
    private static final long MAX_TAIL_BYTES = 1_500_000; // ~1.5 MB
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");

    private LogUploader() {}

    /** True when this build has a Worker URL configured (braveheart). */
    public static boolean isUploadConfigured() {
        return BuildConfig.LOG_UPLOAD_URL != null && !BuildConfig.LOG_UPLOAD_URL.isEmpty();
    }

    public static final class Result {
        public final boolean ok;
        public final String code;   // e.g. "7F3K-9QX2" on success
        public final String error;  // non-null on failure
        private Result(boolean ok, String code, String error) {
            this.ok = ok; this.code = code; this.error = error;
        }
        static Result success(String code) { return new Result(true, code, null); }
        static Result failure(String error) { return new Result(false, null, error); }
    }

    /**
     * Read → redact → upload, synchronously. Call OFF the main thread.
     *
     * @param logFilePath absolute path of the daemon log (e.g. /data/local/tmp/cam_daemon.log)
     * @param daemonLabel short label for the Worker key/metadata (e.g. "camera")
     * @param appVersion  display version for metadata (may be null)
     */
    public static Result upload(String logFilePath, String daemonLabel, String appVersion) {
        if (!isUploadConfigured()) {
            return Result.failure("Upload not configured in this build");
        }
        String content;
        try {
            content = readTail(logFilePath, MAX_TAIL_BYTES);
        } catch (Exception e) {
            return Result.failure("Could not read log: " + e.getMessage());
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.failure("Log is empty or missing");
        }
        content = redact(content);

        try {
            return doUpload(content, daemonLabel, appVersion);
        } catch (Exception e) {
            Log.w(TAG, "Upload failed: " + e.getMessage());
            return Result.failure(e.getMessage() != null ? e.getMessage() : "upload failed");
        }
    }

    /** Read at most {@code maxBytes} from the END of the file (UTF-8). */
    static String readTail(String path, long maxBytes) throws Exception {
        File f = new File(path);
        if (!f.exists() || f.length() == 0) return null;
        long len = f.length();
        long start = Math.max(0, len - maxBytes);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            int toRead = (int) Math.min(maxBytes, len - start);
            byte[] buf = new byte[toRead];
            raf.readFully(buf);
            // If we started mid-file, drop the partial first line.
            String s = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0 && nl + 1 < s.length()) s = s.substring(nl + 1);
            }
            return s;
        }
    }

    // ---- Redaction ----
    // Strip values that could leak from a log shared on a public channel.
    // Conservative + line-oriented: we replace the VALUE after a known
    // key-ish token, and mask anything shaped like a bot token / bearer.
    private static final Pattern[] REDACTORS = new Pattern[] {
        // Telegram bot token: 8-10 digits : 30+ base64-ish chars. NO leading
        // \b — the token is usually preceded by "bot" in the API URL
        // (https://api.telegram.org/bot123:AAH…), where t→1 is word-to-word so
        // \b never matches. A digit run that long followed by ':' + a 30-char
        // secret is unambiguous enough to mask without an anchor.
        Pattern.compile("\\d{8,10}:[A-Za-z0-9_-]{30,}"),
        // Bearer / Authorization tokens
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._-]{12,}"),
        // key=value or "key":"value" / key: value for sensitive key names.
        // Require a leading word boundary so camelCase identifiers such as
        // `resolveLeaseSpecialDevice: BYDAutoSpecialDevice` or `isAccStateAuthoritative`
        // do not match mid-identifier, while still redacting `Device: <id>` prose lines
        // when `Device` is a standalone token and the value is not a dotted Java FQN.
        Pattern.compile("(?i)\\b(token|secret|password|passwd|apikey|api_key|authorization|auth_?token|deviceid|device_id|did|chat_?id)" +
                "(\"?\\s*[:=]\\s*\"?)([A-Za-z0-9._:\\-]{4,})"),
    };

    private static final Pattern PROSE_DEVICE_ID_REDACTOR =
            Pattern.compile("(?i)(?<![A-Za-z0-9_.])(device)(\\s*:\\s*)([A-Za-z0-9_\\-]{8,})(?![A-Za-z0-9_.])");

    // Signed thumb / parking-asset tokens in a logged request line: /thumb/x.jpg?t=<jws>.
    // The key name is a bare "t" so the sensitive-key pattern above misses it. Length bound
    // keeps a short, harmless t= param readable. Held in its own field rather than appended
    // to REDACTORS: that array is applied by INDEX, so a future insertion would either make
    // this a silent no-op or pair a "$1" template with a group-less pattern and throw.
    private static final Pattern QUERY_TOKEN_REDACTOR =
            Pattern.compile("([?&]t=)[A-Za-z0-9._~-]{20,}");

    static String redact(String s) {
        if (s == null) return null;
        String out = s;
        // Telegram token → mask whole match (covers both bare and bot<token> URL forms).
        out = REDACTORS[0].matcher(out).replaceAll("[REDACTED_TOKEN]");
        out = REDACTORS[1].matcher(out).replaceAll("$1[REDACTED]");
        // key=value → keep the key, mask the value.
        Matcher m = REDACTORS[2].matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + "[REDACTED]"));
        }
        m.appendTail(sb);
        out = sb.toString();
        out = PROSE_DEVICE_ID_REDACTOR.matcher(out).replaceAll("$1$2[REDACTED]");
        // Query-param thumb tokens → keep the key, mask the value.
        return QUERY_TOKEN_REDACTOR.matcher(out).replaceAll("$1[REDACTED]");
    }

    /**
     * Read a bounded local tail and apply the same secret redaction used by
     * support uploads. This performs no upload and opens no network client.
     */
    public static String readRedactedTail(
            String path, long requestedMaxBytes) throws Exception {
        long maxBytes = Math.max(
                1L, Math.min(requestedMaxBytes, MAX_TAIL_BYTES));
        return redact(readTail(path, maxBytes));
    }

    private static Result doUpload(String content, String daemonLabel, String appVersion)
            throws Exception {
        String url = BuildConfig.LOG_UPLOAD_URL;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        url = url + "/upload";

        // Proxy-aware, mirroring AppUpdater.downloadApkOkHttp: try the shared
        // sing-box/Tailscale proxy first (the head unit's egress often REQUIRES
        // it on CN firmware / captive SIMs), then — if that attempt fails —
        // invalidate the 60s proxy probe cache (the proxy may have died
        // mid-flight) and retry once DIRECT. The Worker is a public CF endpoint
        // reachable both ways, so a direct retry is a legitimate fallback.
        java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        boolean usedProxy = proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT;
        try {
            return postOnce(url, content, daemonLabel, appVersion, proxy);
        } catch (Exception primary) {
            if (!usedProxy) throw primary;
            Log.w(TAG, "Upload via proxy failed (" + primary.getMessage() + "); retrying direct");
            com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
            try {
                return postOnce(url, content, daemonLabel, appVersion, java.net.Proxy.NO_PROXY);
            } catch (Exception retry) {
                // Surface the proxy-attempt error (what the user is likelier to
                // recognize) with the direct-retry detail appended.
                throw new java.io.IOException(
                        primary.getMessage() + " (direct retry: " + retry.getMessage() + ")");
            }
        }
    }

    /** Single POST attempt with an explicit proxy (or NO_PROXY). */
    private static Result postOnce(String url, String content, String daemonLabel,
                                   String appVersion, java.net.Proxy proxy) throws Exception {
        // callTimeout bounds the WHOLE attempt (connect+write+read) so the
        // daemon always replies within the IPC client's read window. Two
        // attempts max (proxy → direct retry) → ≤ 2×callTimeout must stay under
        // the IPC read timeout (UPLOAD_IPC_TIMEOUT_MS, 30s). 12s × 2 = 24s < 30s.
        // The per-phase timeouts are kept as inner bounds.
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .callTimeout(12, TimeUnit.SECONDS)
                .connectTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true);
        if (proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT) {
            b.proxy(proxy);
        }
        OkHttpClient client = b.build();

        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(content, TEXT))
                .header("X-Overdrive-Daemon", daemonLabel != null ? daemonLabel : "unknown");
        if (appVersion != null && !appVersion.isEmpty()) {
            rb.header("X-Overdrive-Version", appVersion);
        }

        try (Response resp = client.newCall(rb.build()).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                String err = "HTTP " + resp.code();
                try {
                    String je = new JSONObject(body).optString("error", "");
                    if (!je.isEmpty()) err = je;
                } catch (Exception ignored) {}
                // Non-2xx is a definitive server verdict, not a transport
                // failure — don't trigger the direct-retry for it.
                return Result.failure(err);
            }
            String code = new JSONObject(body).optString("code", "");
            if (code.isEmpty()) return Result.failure("No code returned");
            return Result.success(code);
        }
    }
}
