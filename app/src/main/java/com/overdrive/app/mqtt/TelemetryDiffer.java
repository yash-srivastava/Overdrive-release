package com.overdrive.app.mqtt;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Report-by-exception change detector for a single transmit destination
 * (one MQTT connection, or the ABRP uploader).
 *
 * Holds the last-transmitted value of every key and decides, each cycle:
 *   - which keys changed beyond their per-key deadband ({@link TelemetryFieldCatalog}), and
 *   - whether a transmit should happen at all, given a min-interval floor
 *     ("never faster than") and a max-interval heartbeat ("never slower than").
 *
 * Time / monotonic keys ({@link TelemetryFieldCatalog#EXCLUDED}) are ignored for
 * change purposes — otherwise the ever-incrementing {@code utc} would make every
 * payload look "changed" and defeat the whole feature.
 *
 * Not thread-safe; each instance is owned by a single publisher/uploader thread.
 */
public class TelemetryDiffer {

    /** key -> numeric bucket or stable text representation of the last sent value. */
    private final Map<String, Object> lastSent = new HashMap<>();
    private long lastSendTimeMs = 0;
    // Time of the last FULL resync (every discoverable key sent), tracked separately from
    // lastSendTimeMs so the heartbeat can fire on its own fixed cadence without being reset
    // by intervening change-only partial publishes.
    private long lastFullSyncMs = 0;

    /** Forget all history — forces a full resend on the next cycle. */
    public void reset() {
        lastSent.clear();
        lastSendTimeMs = 0;
        lastFullSyncMs = 0;
    }

    public long lastSendTimeMs() { return lastSendTimeMs; }

    public long elapsedMs(long nowMs) {
        return lastSendTimeMs == 0 ? Long.MAX_VALUE : nowMs - lastSendTimeMs;
    }

    /** Time since the last full resync (every key sent). MAX_VALUE if never. */
    public long fullSyncElapsedMs(long nowMs) {
        return lastFullSyncMs == 0 ? Long.MAX_VALUE : nowMs - lastFullSyncMs;
    }

    /** Record that a full resync (every discoverable key) was just transmitted. */
    public void markFullSync(long nowMs) { lastFullSyncMs = nowMs; }

    /** Keys whose quantized value differs from what we last sent (excludes time keys). */
    public Set<String> changedKeys(JSONObject snapshot) {
        Set<String> changed = new HashSet<>();
        Iterator<String> it = snapshot.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (TelemetryFieldCatalog.EXCLUDED.contains(k)) continue;
            if (!matches(k, lastSent.get(k), snapshot.opt(k))) changed.add(k);
        }
        return changed;
    }

    /**
     * Should we transmit this cycle?
     *
     * @param anyChanged    whether at least one trigger field changed
     * @param changeOnly    if false, behave like a plain interval publisher (always at min cadence)
     */
    public boolean shouldPublish(boolean anyChanged, boolean changeOnly,
                                 long nowMs, long minIntervalMs, long maxIntervalMs) {
        long elapsed = elapsedMs(nowMs);
        if (lastSendTimeMs == 0) return true;                 // first publish
        if (elapsed >= maxIntervalMs) return true;            // heartbeat ceiling
        if (elapsed < minIntervalMs) return false;            // rate-limit floor
        if (!changeOnly) return true;                         // plain interval mode
        return anyChanged;                                    // change within [min, max)
    }

    /** Record that the entire snapshot was just transmitted. */
    public void markAllSent(JSONObject snapshot, long nowMs) {
        Iterator<String> it = snapshot.keys();
        while (it.hasNext()) {
            String k = it.next();
            lastSent.put(k, rememberedValue(k, snapshot.opt(k)));
        }
        lastSendTimeMs = nowMs;
    }

    /** Record that a specific subset of keys was just transmitted (per-field mode). */
    public void markKeysSent(JSONObject snapshot, Set<String> keys, long nowMs) {
        for (String k : keys) {
            lastSent.put(k, rememberedValue(k, snapshot.opt(k)));
        }
        lastSendTimeMs = nowMs;
    }

    // Compare numeric deadband buckets without formatting a new String for every
    // field on every poll. Text (including null and arrays) keeps the original
    // representation semantics; cross-type comparisons still use the old text
    // representation so, for example, a string "q10" matches numeric bucket 10.
    private static boolean matches(String key, Object previous, Object value) {
        if (previous == null) return false;
        if (previous instanceof NumericBucket && value instanceof Number) {
            NumericBucket bucket = (NumericBucket) previous;
            Number number = (Number) value;
            double step = TelemetryFieldCatalog.precisionFor(key);
            return bucket.quantized == isQuantized(number, step)
                    && bucket.value == numericValue(number, step);
        }
        return previous.toString().equals(repr(key, value));
    }

    private static Object rememberedValue(String key, Object value) {
        if (!(value instanceof Number)) return repr(key, value);
        Number number = (Number) value;
        double step = TelemetryFieldCatalog.precisionFor(key);
        return new NumericBucket(numericValue(number, step), isQuantized(number, step));
    }

    private static boolean isQuantized(Number number, double step) {
        return step > 0 || !(number instanceof Integer || number instanceof Long
                || number instanceof Short || number instanceof Byte);
    }

    private static long numericValue(Number number, double step) {
        if (step > 0) return Math.round(number.doubleValue() / step);
        return isQuantized(number, step) ? Math.round(number.doubleValue() * 100)
                : number.longValue();
    }

    private static String numericText(long value, boolean quantized) {
        return quantized ? "q" + value : Long.toString(value);
    }

    private static final class NumericBucket {
        final long value;
        final boolean quantized;

        NumericBucket(long value, boolean quantized) {
            this.value = value;
            this.quantized = quantized;
        }

        @Override public String toString() {
            return numericText(value, quantized);
        }
    }

    /**
     * Stable, equality-friendly representation of a value after quantization.
     * Numeric values are bucketed by the catalog deadband (or 2 dp / exact-int by
     * default) so floating-point noise does not register as a change.
     */
    static String repr(String key, Object v) {
        if (v == null || v == JSONObject.NULL) return "∅";
        if (v instanceof Number) {
            Number number = (Number) v;
            double step = TelemetryFieldCatalog.precisionFor(key);
            return numericText(numericValue(number, step), isQuantized(number, step));
        }
        return v.toString(); // boolean / string / JSONArray
    }
}
