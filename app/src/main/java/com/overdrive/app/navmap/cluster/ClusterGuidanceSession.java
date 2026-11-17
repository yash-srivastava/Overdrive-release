package com.overdrive.app.navmap.cluster;

import java.util.HashMap;

/** Source arbitration and monotonic sequencing; caller supplies uptime and synchronization. */
public final class ClusterGuidanceSession {
    private final HashMap<String, Long> sequences = new HashMap<>();
    private String source = "";
    private long lastUpdate;

    public boolean submit(String incoming, long sequence, long nowMs) {
        if (!rememberSequence(incoming, sequence)) return false;
        if ("roadsense".equals(source) && !"roadsense".equals(incoming)) return false;
        source = incoming;
        lastUpdate = nowMs;
        return true;
    }

    public boolean clear(String incoming, long sequence) {
        if (!rememberSequence(incoming, sequence) || !incoming.equals(source)) return false;
        reset();
        return true;
    }

    private boolean rememberSequence(String incoming, long sequence) {
        if (!ClusterGuidanceFrame.validSource(incoming)
                || sequence <= sequences.getOrDefault(incoming, 0L)) return false;
        sequences.put(incoming, sequence);
        return true;
    }

    public boolean expired(long nowMs) {
        return !source.isEmpty() && nowMs - lastUpdate >
                ("roadsense".equals(source) ? 30_000 : 180_000);
    }

    public void reset() { source = ""; }
    public String source() { return source; }
    public long lastUpdateMs() { return lastUpdate; }
}
