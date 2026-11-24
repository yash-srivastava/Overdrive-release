package com.overdrive.app.notifications.sinks;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.CategoryRegistry;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.notifications.push.PushPayloadEncoder;
import com.overdrive.app.notifications.push.PushSubscription;
import com.overdrive.app.notifications.push.PushTransport;
import com.overdrive.app.notifications.push.SubscriptionStore;
import com.overdrive.app.notifications.push.VapidKeyStore;
import com.overdrive.app.notifications.push.VapidSigner;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The push sink. For each notification:
 *   - filters subscriptions by per-device preferences
 *   - signs a VAPID JWT scoped to the endpoint origin
 *   - encrypts the payload with aes128gcm
 *   - POSTs to the push service
 *   - removes the subscription on 404/410 (Gone)
 *
 * <p>Sends are dispatched to a small executor so a slow push service
 * doesn't back up the {@link NotificationBus} executor.
 */
public final class PushSink implements NotificationBus.Sink {

    private static final DaemonLogger logger = DaemonLogger.getInstance("PushSink");
    private static final int TTL_SECONDS = 86_400; // 24h

    private final SubscriptionStore subs;
    private final CategoryRegistry registry;
    private final VapidKeyStore keyStore;
    private final VapidSigner signer;

    // Lazy: the executor (and its 2 worker threads) only spin up when we
    // actually need to send a push. A car with no registered phones never
    // pays this cost — the early-return below short-circuits before this
    // is touched.
    private final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();

    public PushSink(SubscriptionStore subs, CategoryRegistry registry,
                    VapidKeyStore keyStore, VapidSigner signer) {
        this.subs = subs;
        this.registry = registry;
        this.keyStore = keyStore;
        this.signer = signer;
    }

    @Override
    public void onNotification(NotificationEvent event) {
        // Source asked us to skip Web Push for this event (e.g. surveillance
        // motion below its per-tier push toggle). The event still flows to the
        // OTHER sinks — HistorySink persists it to the Log, LogSink logs it,
        // TelegramSink forwards it — so suppression is push-only, not a drop.
        if (event.isPushSuppressed()) return;
        if (com.overdrive.app.remoteaccess.RemoteAccessNotifications.isRemoteAccess(event)
                && !com.overdrive.app.remoteaccess.RemoteAccessNotifications
                        .canDeliver(event, "push")) return;
        // Cheapest possible early-out: zero phones registered means no push
        // work, ever. Don't touch the registry, don't allocate, don't spin
        // up the executor.
        List<PushSubscription> all = subs.all();
        if (all.isEmpty()) return;

        // An unregistered category must NOT silently vanish. This path is hit by
        // the /api/push test endpoint (user-supplied category) and would be hit
        // by any future publisher whose category isn't yet in the registry JSON.
        // Fall back to safe defaults (generic click URL, no quiet-hours bypass)
        // so the push still delivers — the worst outcome here is "delivered with
        // a home-page click target", far better than "nothing arrives". Muting/
        // severity/quiet-hours below still apply, gated on the event's own
        // category string (prefix-mute still works for unregistered subtrees).
        CategoryRegistry.Entry meta = registry.get(event.category);
        final String fallbackClickUrl;
        final boolean categoryBypassesQuiet;
        if (meta == null) {
            logger.warn("unregistered category (delivering with defaults): " + event.category);
            fallbackClickUrl = "/";
            categoryBypassesQuiet = false;
        } else {
            fallbackClickUrl = meta.defaultClickUrl;
            // Categories may opt out of the quiet-hours block at the registry
            // level (e.g. charging complete — the whole point is to wake the
            // user so they unplug). CRITICAL severity also bypasses, as before.
            categoryBypassesQuiet = meta.bypassQuietHours;
        }
        // Resolve the click URL: event override > registry default (> "/"). We
        // only allocate a wrapped event when an override is actually needed.
        final NotificationEvent enriched = event.clickUrl != null
                ? event
                : new NotificationEvent(event.category, event.severity, event.title, event.body,
                        event.tag, fallbackClickUrl, event.data);

        // User-initiated diagnostics ("Send test") bypass all per-device
        // filtering — the point is to verify push reaches THIS device at all,
        // so gating it on the very mute/floor/quiet-hours prefs under test would
        // make the button look broken (e.g. a fresh device where the test's
        // category is muted-by-default, or a raised severity floor). Always
        // paired with a target id (below) so the bypass can only ever affect the
        // one device that asked — never fans an unfiltered push to other phones.
        final boolean bypassPrefs = event.isPreferencesBypassed();
        // When set, deliver ONLY to this subscription (the requester's device).
        final String targetId = event.getPushTargetId();

        long now = System.currentTimeMillis();
        ExecutorService exec = null; // lazy
        for (PushSubscription sub : all) {
            if (targetId != null && !targetId.equals(sub.id)) continue;
            if (!bypassPrefs) {
                if (sub.isMuted(event.category)) continue;
                if (event.severity.ordinal() < sub.minSeverity.ordinal()) continue;
                if (sub.inQuietHours(now)
                        && event.severity != NotificationEvent.Severity.CRITICAL
                        && !categoryBypassesQuiet) {
                    continue;
                }
            }
            if (exec == null) exec = executor();
            final PushSubscription target = sub;
            exec.execute(() -> sendOne(target, enriched));
        }
    }

    /**
     * Lazy executor: created on first eligible send. Kept around once
     * created — the same 2 threads service all subsequent pushes.
     */
    private ExecutorService executor() {
        ExecutorService e = executorRef.get();
        if (e != null) return e;
        ExecutorService created = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "PushSink");
            t.setDaemon(true);
            return t;
        });
        if (executorRef.compareAndSet(null, created)) return created;
        // lost the race — shut ours down
        created.shutdown();
        return executorRef.get();
    }

    private void sendOne(PushSubscription sub, NotificationEvent event) {
        try {
            JSONObject payload = event.toPayloadJson();
            byte[] plaintext = payload.toString().getBytes("UTF-8");

            PushPayloadEncoder.Encoded encoded =
                    PushPayloadEncoder.encrypt(plaintext, sub.p256dh, sub.auth);
            String jwt = signer.signFor(sub.endpoint);
            String pubKey = keyStore.publicKeyB64Url();

            // One retry for transient failures (5xx / 408 / 429). FCM occasionally
            // sheds load during real intrusion bursts — losing a single
            // notification at the moment the user cares most is the worst-case
            // failure mode, so we make a single bounded retry with the
            // server-suggested Retry-After when present.
            PushTransport.Result result = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean remoteAccess = com.overdrive.app.remoteaccess.RemoteAccessNotifications
                        .isRemoteAccess(event);
                if (remoteAccess && !com.overdrive.app.remoteaccess.RemoteAccessNotifications
                        .canDeliver(event, "push")) return;
                result = PushTransport.send(sub.endpoint, jwt, pubKey, encoded.body, TTL_SECONDS,
                        remoteAccess);

                if (result.expired()) {
                    // Push service told us the sub is gone at the browser
                    // end — this is NOT a user-driven removal, so don't
                    // tombstone (which would block silent self-heal in
                    // pwa-init.js for 30 minutes). Use removeExpired so
                    // the next page load can re-register cleanly.
                    logger.info("subscription expired (" + result.status + "), removing: " + sub.id);
                    subs.removeExpired(sub.id);
                    return;
                }
                if (result.ok()) break;
                if (!result.transientFailure() || attempt == 1) break;

                long sleepMs = result.retryAfterSeconds > 0
                        ? Math.min(result.retryAfterSeconds * 1000L, 30_000L)
                        : 1500L;  // sensible default for unhinted 5xx
                try { Thread.sleep(sleepMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (result != null && !result.ok()) {
                logger.warn("push failed " + result.status + " for " + sub.id
                        + ": " + result.body);
                return;
            }
            // Persist via touchLastSeen — debounced so we don't IO every
            // send. Direct assignment to sub.lastSeenAt would never reach
            // disk (the previous behavior), so the device list always
            // showed the createdAt across daemon restart.
            subs.touchLastSeen(sub.id, System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("send failed for " + sub.id + ": " + e.getMessage());
        }
    }
}
