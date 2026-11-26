package com.overdrive.app.storage.removable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Raw directory-listing metadata for the recording index poller (not live file stats). */
public final class RecordingListingSnapshot {
    private final Map<String, Stamp> entries = new HashMap<>();
    private boolean hasMp4;

    static boolean accepts(String name) {
        return name != null && (name.endsWith(".mp4") || name.endsWith(".json"));
    }

    void add(String name, long length, long mtime, RecordingListingSnapshot previous) {
        if (!accepts(name)) return;
        Stamp old = previous == null ? null : previous.entries.get(name);
        entries.put(name, old != null && old.length == length && old.mtime == mtime
                ? old : new Stamp(length, mtime));
        hasMp4 |= name.endsWith(".mp4");
    }

    /**
     * Emit changed names and missing MP4 names. Returns false for a suspect empty-clip
     * listing; callers must retain the prior snapshot. Missing names still need a stat.
     * The first successful listing establishes a baseline without emitting changes.
     */
    public boolean dispatchChanges(RecordingListingSnapshot previous,
                                   Consumer<String> refresh, Consumer<String> missing) {
        if (previous == null) return true;
        if (previous.hasMp4 && !hasMp4) return false;
        for (Map.Entry<String, Stamp> entry : entries.entrySet()) {
            Stamp old = previous.entries.get(entry.getKey());
            Stamp now = entry.getValue();
            if (old == now || (old != null && old.length == now.length && old.mtime == now.mtime)) {
                continue;
            }
            refresh.accept(entry.getKey());
        }
        for (String name : previous.entries.keySet()) {
            if (name.endsWith(".mp4") && !entries.containsKey(name)) missing.accept(name);
        }
        return true;
    }

    private static final class Stamp {
        final long length, mtime;

        Stamp(long length, long mtime) {
            this.length = length;
            this.mtime = mtime;
        }
    }
}
