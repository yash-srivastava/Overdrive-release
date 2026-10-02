package com.overdrive.app.power;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Durable record of which parked keep-alive levers THIS install acquired.
 *
 * <p>The in-memory sentry generation does not survive a SIGKILL. Without a
 * durable record, a restarted {@code acc_sentry_daemon} could either leave
 * {@code 782237711/782237728 = 1} outstanding forever, or — worse — "clean up"
 * flags it never set (the car's native sentry mode, or another app, may own
 * them). The marker is written BEFORE the first HAL write and removed AFTER the
 * last release, so start-up hygiene releases exactly the recorded levers and
 * nothing else.
 *
 * <p>File format is a small JSON object:
 * <pre>{"generation":12,"levers":["mcu","camera"],"since":1758300000000,"pid":4321}</pre>
 *
 * <p>Writes are atomic (temp file + rename) so a crash mid-write cannot leave a
 * half-written marker that hygiene would misread.
 */
public final class Di5KeepAliveOwnershipMarker {

    public static final String DEFAULT_PATH = com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/overdrive_di5_keepalive.owner");

    public static final String LEVER_MCU = "mcu";
    /** BYDAutoPowerDevice MCU power hold (-1442840502←1); released with 0 only when recorded. */
    public static final String LEVER_MCU_POWER = "mcupower";
    public static final String LEVER_CAMERA = "camera";
    public static final String LEVER_AP = "ap";

    /** Parsed marker contents. */
    public static final class Record {
        public final long generation;
        public final List<String> levers;
        public final long sinceMs;
        public final int pid;

        Record(long generation, List<String> levers, long sinceMs, int pid) {
            this.generation = generation;
            this.levers = Collections.unmodifiableList(new ArrayList<>(levers));
            this.sinceMs = sinceMs;
            this.pid = pid;
        }

        public boolean has(String lever) {
            return levers.contains(lever);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("generation", generation);
                o.put("levers", new JSONArray(levers));
                o.put("since", sinceMs);
                o.put("pid", pid);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private final File file;

    public Di5KeepAliveOwnershipMarker() {
        this(new File(DEFAULT_PATH));
    }

    /** Test seam: any path. */
    public Di5KeepAliveOwnershipMarker(File file) {
        this.file = file;
    }

    public File file() {
        return file;
    }

    public boolean exists() {
        return file.isFile();
    }

    /**
     * Record (or update) the set of acquired levers. Returns false when the
     * marker could not be durably written — callers must then NOT proceed with
     * HAL writes, because a later hygiene pass would have no record of them.
     */
    public boolean record(long generation, List<String> levers, long nowMs, int pid) {
        Record r = new Record(generation, levers, nowMs, pid);
        byte[] bytes = r.toJson().toString().getBytes(StandardCharsets.UTF_8);
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return false;
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp." + pid + "." + nowMs);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(bytes);
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // Some tmpfs/FUSE mounts refuse ATOMIC_MOVE; a plain replace is
                // still a single rename on the same directory.
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // Read back: the marker is load-bearing for hygiene, so a write that
            // did not land must be reported as a failure, not assumed.
            Record back = read();
            return back != null && back.generation == generation
                    && back.levers.containsAll(levers) && levers.containsAll(back.levers);
        } catch (Throwable t) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            return false;
        }
    }

    /** Parse the marker, or null when absent/unreadable/malformed. */
    public Record read() {
        if (!file.isFile()) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length == 0 || bytes.length > 4096) return null;
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            long generation = o.optLong("generation", -1L);
            JSONArray arr = o.optJSONArray("levers");
            List<String> levers = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String lever = arr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (lever.equals(LEVER_MCU) || lever.equals(LEVER_MCU_POWER)
                            || lever.equals(LEVER_CAMERA) || lever.equals(LEVER_AP)) {
                        if (!levers.contains(lever)) levers.add(lever);
                    }
                }
            }
            return new Record(generation, levers, o.optLong("since", 0L), o.optInt("pid", -1));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Remove the marker. True when it is gone afterwards (absent counts). */
    public boolean clear() {
        try {
            if (!file.exists()) return true;
            return file.delete() || !file.exists();
        } catch (Throwable t) {
            return !file.exists();
        }
    }
}
