package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.auth.OAuthTokens;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Shared response handling and provider-neutral error mapping for the HTTP drivers. */
public final class Http {

    private Http() {}

    /** Execute with upload cancellation hooks when a job owns the request. */
    public static Response execute(OkHttpClient http, UploadJob job, Request request) throws DestError {
        if (job != null) return job.execute(http, request);
        try {
            return http.newCall(request).execute();
        } catch (IOException e) {
            throw DestError.network(e);
        }
    }

    /** Replayable OAuth API requests only; streamed chunks have their own retry policy. */
    static Response authorized(OkHttpClient http, OAuthTokens tokens, UploadJob job,
                               Request.Builder request) throws DestError {
        for (int attempt = 0; ; attempt++) {
            Request req = request.header("Authorization", "Bearer " + tokens.accessToken(http)).build();
            Response r = execute(http, job, req);
            if (r.code() != 401 || attempt > 0) return r;
            r.close();
            tokens.invalidate();
        }
    }

    public static String body(Response r) {
        ResponseBody b = r.body();
        if (b == null) return "";
        try {
            return b.string();
        } catch (IOException e) {
            return "";
        }
    }

    public static byte[] bytes(Response r, long max) throws IOException {
        ResponseBody b = r.body();
        if (b == null) return new byte[0];
        try (InputStream in = b.byteStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("download larger than " + max + " bytes");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static long retryAfterMs(Response r) {
        String h = r.header("Retry-After");
        if (h == null || h.isEmpty()) return 0;
        try {
            return Math.max(0, Long.parseLong(h.trim()) * 1000L);
        } catch (NumberFormatException e) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                Date d = f.parse(h);
                return d == null ? 0 : Math.max(0, d.getTime() - System.currentTimeMillis());
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    /** Status + body → typed error. Provider quirks are matched by substring on the body. */
    public static DestError error(String context, Response r, String body) {
        int code = r.code();
        String b = body == null ? "" : body;
        String lower = b.toLowerCase(Locale.ROOT);
        String msg = context + ": HTTP " + code + (b.isEmpty() ? "" : " " + shorten(b));
        long ra = retryAfterMs(r);
        if (lower.contains("requesttimetooskewed")) return new DestError(DestError.Kind.CLOCK_SKEW, msg, code, 0);
        // Rate and API-usage limits first: "User Rate Limit Exceeded … project quota" is not a full drive.
        if (code == 429 || lower.contains("ratelimit") || lower.contains("rate_limit") || lower.contains("too_many")
                || lower.contains("slowdown") || lower.contains("dailylimitexceeded") || lower.contains("usagelimits")
                || lower.contains("\"quotaexceeded\"") || (lower.contains("quota") && lower.contains("queries"))) {
            return new DestError(DestError.Kind.RATE_LIMITED, msg, code, ra);
        }
        // Only the providers' explicit storage-full signals (full triggers retention pruning).
        if (code == 507 || lower.contains("storagequotaexceeded") || lower.contains("insufficient_space")
                || lower.contains("insufficient_storage") || lower.contains("quotalimitreached")
                || lower.contains("storage_cap") || lower.contains("storage cap")) {
            return new DestError(DestError.Kind.QUOTA_FULL, msg, code, ra);
        }
        if (code == 401 || lower.contains("invalidaccesskeyid") || lower.contains("signaturedoesnotmatch")
                || lower.contains("expired_access_token") || lower.contains("invalid_access_token")) {
            return new DestError(DestError.Kind.AUTH_EXPIRED, msg, code, 0);
        }
        if (code == 404 || code == 410) return new DestError(DestError.Kind.SESSION_GONE, msg, code, 0);
        if (code == 409 || code == 412) return new DestError(DestError.Kind.CONFLICT, msg, code, 0);
        if (code == 408 || code >= 500) return new DestError(DestError.Kind.NETWORK, msg, code, ra);
        return new DestError(DestError.Kind.PERMANENT, msg, code, 0);
    }

    public static String shorten(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 240 ? t.substring(0, 240) + "…" : t;
    }

    /** Percent-encodes each path segment (RFC 3986 unreserved kept), preserving '/'. */
    public static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    public static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "" : path.substring(0, i);
    }

    public static String name(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
