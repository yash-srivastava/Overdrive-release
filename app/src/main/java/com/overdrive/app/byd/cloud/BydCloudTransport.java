package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodec;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP transport for BYD cloud API.
 * 
 * Handles Bangcle envelope wrapping/unwrapping and cookie management.
 * 
 * Port of: Niek/BYD-re/client.js (postSecure function)
 */
public final class BydCloudTransport {

    private static final String TAG = "BydCloudTransport";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=UTF-8");
    /**
     * Resolve the local sing-box/Tailscale route for every new HTTP call.
     *
     * <p>The cloud client is intentionally long-lived and shared by MQTT,
     * realtime polling, remote commands, and the parked DI5 heartbeat. Freezing
     * a {@link java.net.Proxy} in the constructor would strand that client on
     * the old route when a proxy starts or stops later in the same daemon
     * process. ProxyHelper already caches probes, so per-call selection is
     * inexpensive while still reacting promptly to route changes.
     *
     * <p>The selection is a chain — the live proxy first, then a DIRECT
     * fallback. The probe behind {@code ProxyHelper.getHttpProxy()} is a blind
     * loopback TCP connect: it cannot distinguish a general-egress proxy
     * (sing-box, or Tailscale fronting an exit node) from the tailnet-only
     * Tailscale SOCKS listener that the MQTT settings expose for private
     * brokers. BYD's cloud hosts are public internet, so when the selected
     * proxy cannot carry public traffic every cloud operation (login, remote
     * commands, realtime polling, the DI5 parked heartbeat) used to fail for
     * as long as the listener was up — re-probing couldn't help because the
     * listener itself stays healthy. With the chain, OkHttp attempts the
     * routes in order and its route database remembers failures, so traffic
     * settles on the working route instead of re-paying the dead one on every
     * call, while sing-box/exit-node setups keep their proxy-first behavior.
     */
    private static final java.net.ProxySelector DYNAMIC_PROXY_SELECTOR =
            new java.net.ProxySelector() {
                @Override
                public List<java.net.Proxy> select(java.net.URI uri) {
                    return proxyRouteChain(
                            com.overdrive.app.mqtt.ProxyHelper.getHttpProxy());
                }

                @Override
                public void connectFailed(
                        java.net.URI uri,
                        java.net.SocketAddress address,
                        IOException failure) {
                    // Only proxy legs are reported here (OkHttp never calls
                    // connectFailed for DIRECT routes). A cached positive
                    // probe can outlive a proxy process. Re-probe on the next
                    // request instead of pinning a dead route for the
                    // remainder of the cache window.
                    com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
                }
            };

    /**
     * Route chain for one request: the selected proxy first, then a DIRECT
     * fallback — or just DIRECT when no proxy is up. Delegates to the
     * canonical {@link com.overdrive.app.mqtt.ProxyHelper#proxyRouteChain}
     * shared with AppUpdater so every public-internet consumer falls back
     * identically.
     */
    static List<java.net.Proxy> proxyRouteChain(java.net.Proxy selected) {
        return com.overdrive.app.mqtt.ProxyHelper.proxyRouteChain(selected);
    }

    private final BydCloudConfig config;
    private final EnvelopeCodec codec;
    private final Call.Factory httpClient;
    private final ConcurrentMap<Thread, Call> activeCalls = new ConcurrentHashMap<>();

    public BydCloudTransport(BydCloudConfig config, EnvelopeCodec codec) {
        this.config = config;
        this.codec = codec;

        // Simple in-memory cookie jar (no external dependency needed)
        final Map<String, List<Cookie>> cookieStore = new HashMap<>();
        CookieJar cookieJar = new CookieJar() {
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                cookieStore.put(url.host(), new ArrayList<>(cookies));
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : Collections.emptyList();
            }
        };

        java.net.Proxy proxy =
                com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        logger.info("BYD Cloud transport: baseUrl=" + config.getBaseUrl()
                + " proxy=dynamic, direct fallback (current="
                + (proxy.equals(java.net.Proxy.NO_PROXY)
                        ? "direct" : proxy.address())
                + ")");

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .cookieJar(cookieJar)
                .proxySelector(DYNAMIC_PROXY_SELECTOR)
                .build();
    }

    BydCloudTransport(BydCloudConfig config, EnvelopeCodec codec, Call.Factory httpClient) {
        this.config = config;
        this.codec = codec;
        this.httpClient = httpClient;
    }

    /**
     * Send a secure POST request with Bangcle envelope wrapping.
     * 
     * @param endpoint API endpoint path (e.g., "/app/account/login")
     * @param outerPayload The outer JSON payload to encrypt and send
     * @return Decoded outer response JSON
     * @throws IOException on network or protocol errors
     */
    public JSONObject postSecure(String endpoint, JSONObject outerPayload) throws IOException {
        return postSecure(endpoint, outerPayload, 0L);
    }

    /**
     * Send a secure POST with an optional whole-call deadline.
     *
     * <p>The normal cloud flows retain their existing connect/read/write
     * timeouts by passing {@code 0}. The parked keep-alive uses a shorter
     * whole-call deadline so one degraded network request cannot consume the
     * entire heartbeat cadence.
     */
    public JSONObject postSecure(
            String endpoint,
            JSONObject outerPayload,
            long callTimeoutMs) throws IOException {
        return postSecure(endpoint, outerPayload, callTimeoutMs, null);
    }

    public JSONObject postSecure(
            String endpoint,
            JSONObject outerPayload,
            long callTimeoutMs,
            BooleanSupplier authority) throws IOException {
        throwIfRequestCancelled();
        requireCurrentAuthority(authority);
        // Encode the outer payload into a Bangcle envelope
        String requestEnvelope = codec.encodeEnvelope(outerPayload.toString());

        // Wrap in {"request": "<envelope>"}
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("request", requestEnvelope);
        } catch (Exception e) {
            throw new IOException("Failed to build request body", e);
        }

        String url = config.getBaseUrl() + endpoint;
        logger.debug("POST " + endpoint);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody.toString(), JSON_TYPE))
                .addHeader("accept-encoding", "identity")
                .addHeader("content-type", "application/json; charset=UTF-8")
                .addHeader("user-agent", config.getUserAgent());
        // China stack carries three extra headers (BYD-re client.js / pyBYD transport).
        if (config.isChinaRegion()) {
            requestBuilder
                    .addHeader("version", BydCloudConfig.CN_APP_INNER_VERSION)
                    .addHeader("platform", "ANDROID")
                    .addHeader("BrandFlag", BydCloudConfig.CN_BRAND_FLAG);
        }
        Request request = requestBuilder.build();

        Thread owner = Thread.currentThread();
        Call call = httpClient.newCall(request);
        if (callTimeoutMs > 0L) {
            call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS);
        }
        activeCalls.put(owner, call);
        try {
            // A router timeout can race the Call registration above. Re-check
            // after publishing the Call so a timeout cannot leave a newly
            // registered request free to dispatch.
            try {
                throwIfRequestCancelled();
                requireCurrentAuthority(authority);
            } catch (InterruptedIOException cancelled) {
                call.cancel();
                throw cancelled;
            }
            try (Response response = call.execute()) {
                logger.debug("  HTTP " + response.code() + " " + endpoint);
                if (!response.isSuccessful()) {
                    logger.warn("  HTTP error " + response.code() + " " + endpoint);
                    throw new IOException("HTTP " + response.code() + " " + endpoint);
                }

                String bodyText = response.body() != null ? response.body().string() : "";
                logger.debug("  Response length: " + bodyText.length() + " bytes");
                JSONObject body;
                try {
                    body = new JSONObject(bodyText);
                } catch (Exception e) {
                    logger.warn("  Invalid JSON response from " + endpoint + " (length=" + bodyText.length() + ")");
                    throw new IOException("Invalid JSON response from " + endpoint);
                }

                String responseEnvelope = body.optString("response", "");
                if (responseEnvelope.isEmpty()) {
                    logger.warn("  Missing 'response' field in body from " + endpoint);
                    throw new IOException("Missing response payload for " + endpoint);
                }

                // Decode the Bangcle envelope
                logger.debug("  Decoding Bangcle envelope (" + responseEnvelope.length() + " chars)");
                String decodedText = codec.decodeEnvelope(responseEnvelope);

                // Handle edge case where decoded text starts with "F{" or "F["
                String normalized = decodedText;
                if (normalized.startsWith("F{") || normalized.startsWith("F[")) {
                    normalized = normalized.substring(1);
                }

                try {
                    JSONObject parsed = new JSONObject(normalized);
                    String code = parsed.optString("code", "?");
                    String message = parsed.optString("message", "");
                    logger.debug("  Decoded: code=" + code + " message=" + message);
                    return parsed;
                } catch (Exception e) {
                    logger.warn("  Decoded response is not valid JSON from " + endpoint);
                    throw new IOException("Decoded response is not JSON from " + endpoint);
                }
            }
        } finally {
            activeCalls.remove(owner, call);
        }
    }

    /** Cancel the one request currently owned by a router worker thread. */
    public void cancelCallForThread(Thread owner) {
        if (owner == null) return;
        Call call = activeCalls.get(owner);
        if (call != null) {
            logger.info("Cancelling timed-out BYD cloud HTTP call");
            call.cancel();
        }
    }

    private static void throwIfRequestCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("BYD cloud request cancelled");
        }
    }

    static void requireCurrentAuthority(BooleanSupplier authority) throws InterruptedIOException {
        if (authority == null) return;
        final boolean current;
        try {
            current = authority.getAsBoolean();
        } catch (Throwable unavailable) {
            InterruptedIOException cancelled = new InterruptedIOException(
                    "Cloud command authority is unavailable");
            cancelled.initCause(unavailable);
            throw cancelled;
        }
        if (!current) throw new InterruptedIOException("Cloud command cancelled by its owner");
    }

    /**
     * Decrypt a respondData hex string using the given content key.
     */
    public static JSONObject decryptRespondData(String respondDataHex, String contentKeyHex) {
        String plain = BydCryptoUtils.aesDecryptUtf8(respondDataHex, contentKeyHex);
        try {
            return new JSONObject(plain);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse decrypted respondData", e);
        }
    }
}
