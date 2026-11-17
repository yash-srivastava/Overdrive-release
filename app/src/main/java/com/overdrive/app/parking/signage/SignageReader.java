package com.overdrive.app.parking.signage;

import com.overdrive.app.parking.ParkingEnvironment;
import com.overdrive.app.parking.ParkingSession;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads garage signage (level / zone / bay) for ONE parking session from the
 * frames the session already owns: the four "arrived" stills, the four
 * "returned" stills when present, and the last seconds of the drive clip
 * that led into the garage. Runs on the low-priority parking worker, right
 * after the arrived stills (so the "Parked" message can carry the level);
 * the controller only defers it while a sentry clip is being encoded.
 *
 * <p>Output is the {@link SignageGrammar.Result} JSON persisted into
 * {@code parking_sessions.signage_json}, plus a state:
 * {@code done} (grammar ran, found or not), {@code unavailable} (no OCR
 * models installed), {@code skipped} (no frames to read).
 */
public final class SignageReader {

    /** Hard cap on camera views per session (each ≈ 5 detector tiles + n×rec inferences). */
    static final int MAX_FRAMES = 20;
    /** Approach-clip frames to decode; a 2×2 grid recording yields four views each. */
    static final int DRIVE_TAIL_FRAMES = 3;
    static final long DRIVE_TAIL_MS = 60_000L;
    /** The approach clip stops being read once the level is this sure. */
    static final float CLIP_DONE_LEVEL_CONF = 0.6f;
    /** Lines below this recogniser confidence never reach the grammar. */
    static final float MIN_LINE_CONF = 0.30f;

    /** Outcome of one read. */
    public static final class Outcome {
        public final String state;          // ParkingSession.SIGNAGE_*
        public final JSONObject json;       // may be null
        public final int framesRead;
        Outcome(String state, JSONObject json, int framesRead) {
            this.state = state; this.json = json; this.framesRead = framesRead;
        }
        public String label() {
            if (json == null) return null;
            String l = json.optString("label", "");
            return l.isEmpty() ? null : l;
        }
    }

    private final ParkingEnvironment env;

    public SignageReader(ParkingEnvironment env) {
        this.env = env;
    }

    /**
     * @param backend an OPEN backend (caller owns and closes it); when null or
     *                not ready the outcome is {@code unavailable}
     */
    public Outcome read(ParkingSession s, File sessionDir, TextOcrBackend backend) {
        if (backend == null || !backend.isReady()) {
            return new Outcome(ParkingSession.SIGNAGE_UNAVAILABLE, null, 0);
        }
        // Two phases, cheapest first. The stills already on disk are closest to
        // the bay and cost no video decode; the approach clip (a decoder
        // instance + 3 frames) is only opened when the stills leave the LEVEL
        // unknown and the car parked without sky (a garage).
        List<SignageGrammar.Observation> obs = new ArrayList<>();
        int frames = ocr(collectStills(sessionDir), obs, 0, backend);
        SignageGrammar.Result r = SignageGrammar.parse(obs);
        int clipViews = 0;
        if (r.level == null && approachWorthReading(s)) {
            for (byte[] view : collectDriveTail(s, MAX_FRAMES - frames)) {
                if (isEventRecording()) break;
                frames += ocr(java.util.Collections.singletonList(view), obs, frames, backend);
                clipViews++;
                r = SignageGrammar.parse(obs);
                if (r.level != null && r.levelConf >= CLIP_DONE_LEVEL_CONF) break;
            }
        }
        if (frames == 0) {
            return new Outcome(ParkingSession.SIGNAGE_SKIPPED, null, 0);
        }
        JSONObject json = r.toJson();
        try {
            json.put("framesRead", frames);
            json.put("clipFramesRead", clipViews);
            json.put("linesRead", obs.size());
            json.put("readMs", env.nowMs());
        } catch (Exception ignored) {}
        env.log("Signage " + (s != null ? s.sessionId : "?") + ": " + frames + " views (" + clipViews + " from the approach clip), "
                + obs.size() + " lines → " + (r.isEmpty() ? "nothing" : r.label()));
        return new Outcome(ParkingSession.SIGNAGE_DONE, json, frames);
    }

    /** A fix under a minute old when parking means open sky: no garage approach worth decoding. */
    static boolean approachWorthReading(ParkingSession s) {
        return s != null && !ParkingSession.GPS_FRESH.equals(s.gpsQuality);
    }

    private boolean isEventRecording() {
        try { return env.isEventRecording(); } catch (Throwable t) { return false; }
    }

    /** OCR each frame into {@code obs}; returns the number of frames processed. */
    private int ocr(List<byte[]> frames, List<SignageGrammar.Observation> obs, int firstIdx, TextOcrBackend backend) {
        int idx = firstIdx;
        for (byte[] jpeg : frames) {
            List<TextOcrBackend.TextLine> lines;
            try {
                lines = backend.read(jpeg);
            } catch (Throwable t) {
                env.log("Signage OCR frame " + idx + " failed: " + t.getMessage());
                lines = null;
            }
            if (lines != null) {
                for (TextOcrBackend.TextLine l : lines) {
                    if (l == null || l.text == null || l.confidence < MIN_LINE_CONF) continue;
                    obs.add(new SignageGrammar.Observation(l.text, l.confidence, idx, l.cx, l.cy, l.w, l.h));
                }
            }
            idx++;
        }
        return idx - firstIdx;
    }

    /** Stills first (closest to the bay), then the approach footage. Visible for tests. */
    List<byte[]> collectFrames(ParkingSession s, File sessionDir) {
        List<byte[]> out = collectStills(sessionDir);
        out.addAll(collectDriveTail(s, MAX_FRAMES - out.size()));
        return out;
    }

    /** The arrived / returned stills already on disk (no decode needed). */
    List<byte[]> collectStills(File sessionDir) {
        List<byte[]> out = new ArrayList<>();
        if (sessionDir == null || !sessionDir.isDirectory()) return out;
        for (String prefix : new String[] {"arrived", "returned"}) {
            for (String side : new String[] {"front", "right", "rear", "left"}) {
                File f = new File(sessionDir, prefix + "_" + side + ".jpg");
                byte[] b = readQuietly(f);
                if (b != null) out.add(b);
                if (out.size() >= MAX_FRAMES) return out;
            }
        }
        return out;
    }

    /** Up to {@code budget} frames from the last minute of the drive into the garage. */
    List<byte[]> collectDriveTail(ParkingSession s, int budget) {
        List<byte[]> out = new ArrayList<>();
        if (s == null || budget <= 0) return out;
        File clip = null;
        try { clip = env.recentDriveClip(s.startedMs); } catch (Throwable ignored) {}
        if (clip == null || !clip.isFile()) return out;
        List<byte[]> tail = null;
        try { tail = env.extractTailFrames(clip, Math.min(DRIVE_TAIL_FRAMES, budget), DRIVE_TAIL_MS); }
        catch (Throwable t) { env.log("Signage drive-tail extract failed: " + t.getMessage()); }
        if (tail == null) return out;
        for (byte[] b : tail) {
            if (b == null || b.length == 0) continue;
            out.add(b);
            if (out.size() >= budget) break;
        }
        return out;
    }

    private static byte[] readQuietly(File f) {
        if (f == null || !f.isFile() || f.length() == 0 || f.length() > 8_000_000L) return null;
        try { return Files.readAllBytes(f.toPath()); } catch (Throwable t) { return null; }
    }
}
