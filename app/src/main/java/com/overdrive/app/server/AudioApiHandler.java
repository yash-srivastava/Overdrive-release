package com.overdrive.app.server;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.speech.tts.TextToSpeech;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio API — head-unit audio playback + AVAS exterior-speaker controls.
 *
 * <p>Two families of endpoints:
 * <ul>
 *   <li><b>Audio library + playback</b> ({@code /api/audio/library*}) — upload / list /
 *       delete user sound files and play them on the interior speakers via
 *       {@link com.overdrive.app.byd.AudioPlaybackController}. Backs the "Play Audio"
 *       automation + key-mapping action.</li>
 *   <li><b>AVAS exterior speaker</b> ({@code /api/audio/test-avas}, {@code avas-tone},
 *       {@code engine-sound}, mic probes) — factory tone / engine-sound control on the
 *       exterior AVAS speaker via BydDataCollector's multimedia device (custom file
 *       audio can't route there — MCU DSP hard-split).</li>
 * </ul>
 */
public class AudioApiHandler {

    private static final String TAG = "AudioApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/audio/avas-state") && method.equals("GET")) {
            handleGetState(out);
            return true;
        }

        if (cleanPath.equals("/api/audio/test-avas") && method.equals("POST")) {
            handleTestAvas(out, body);
            return true;
        }

        if (cleanPath.equals("/api/audio/probe-mic") && method.equals("GET")) {
            handleProbeMic(out);
            return true;
        }

        if (cleanPath.equals("/api/audio/probe-mic-spoof") && method.equals("GET")) {
            handleProbeMicSpoof(out);
            return true;
        }

        if (cleanPath.equals("/api/audio/probe-mic-introspect") && method.equals("GET")) {
            handleProbeMicIntrospect(out);
            return true;
        }

        // ── Audio library (for the Play-Audio automation/keymap action) ──
        // GET  /api/audio/library         — list uploaded sounds [{name,path,size}]
        // POST /api/audio/library         — upload { filename, dataBase64 }
        // DELETE /api/audio/library?name= — delete one uploaded sound
        if (cleanPath.equals("/api/audio/library") && method.equals("GET")) {
            handleAudioLibraryList(out);
            return true;
        }
        if (cleanPath.equals("/api/audio/library") && method.equals("POST")) {
            handleAudioLibraryUpload(out, body);
            return true;
        }
        if (cleanPath.equals("/api/audio/library") && method.equals("DELETE")) {
            handleAudioLibraryDelete(out, path);
            return true;
        }
        // Raw bytes of one uploaded sound, streamed to the app-process media player.
        // The app UID (SELinux untrusted_app) CANNOT read /data/local/tmp/.overdrive
        // directly — daemon-written log/config files there are only reachable from the
        // app via a shell exec, and the locale/device-id managers document the same
        // cross-UID wall. So the app-process MediaPlaybackService/VideoPlaybackActivity
        // cannot open the library file by path; instead it streams the bytes from here
        // (authenticated localhost, same model the recordings player uses for /video/).
        if (cleanPath.equals("/api/audio/library/raw") && method.equals("GET")) {
            // Fallback only — HttpServer routes this path to handleRawRanged() first
            // (with the Range header). Reached here only if that route is bypassed;
            // serve without range so the endpoint still works.
            handleAudioLibraryRaw(out, path, null);
            return true;
        }
        // Preview an uploaded sound from the web UI (plays on the head unit).
        if (cleanPath.equals("/api/audio/library/play") && method.equals("POST")) {
            handleAudioLibraryPlay(out, body);
            return true;
        }
        if (cleanPath.equals("/api/audio/library/stop") && method.equals("POST")) {
            com.overdrive.app.byd.AudioPlaybackController.stop();
            JSONObject r = new JSONObject();
            r.put("success", true);
            HttpResponse.sendJson(out, r.toString());
            return true;
        }

        // AVAS exterior-speaker tone patterns (AVAH tone generator).
        if (cleanPath.equals("/api/audio/avas-tone") && method.equals("POST")) {
            handleAvasTone(out, body);
            return true;
        }

        // Engine-sound simulator (factory "Boombox" presets on the exterior speaker).
        if (cleanPath.equals("/api/audio/engine-sound")) {
            if (method.equals("GET")) { handleEngineSoundState(out); return true; }
            if (method.equals("POST")) { handleEngineSound(out, body); return true; }
        }

        return false;
    }

    // ==================== AUDIO LIBRARY ====================

    // Where uploaded sounds live. World-readable dir so the daemon (UID 2000) can
    // both write (here, running as the daemon) and play them back.
    private static final String AUDIO_DIR = ScratchPaths.path(".overdrive/audio");
    private static final String[] AUDIO_EXTS = { "mp3", "wav", "mp4", "m4a", "aac", "ogg" };
    // 48 MB per file — enough for a short alert/deterrent video clip, while keeping the
    // base64-in-JSON upload's transient memory safe on the daemon (a 48 MB file is a
    // ~64 MB base64 body → ~128 MB UTF-16 String + 48 MB decoded ≈ 190 MB peak). Going
    // materially higher would need a stream-to-disk upload rewrite rather than the
    // current buffer-then-decode path. Must stay <= HttpServer's audio-endpoint body cap.
    private static final long MAX_AUDIO_BYTES = 48L * 1024 * 1024;

    private static boolean isAudioExt(String ext) {
        for (String e : AUDIO_EXTS) if (e.equals(ext)) return true;
        return false;
    }

    /** Sanitize a user filename to a safe basename (no path traversal, bounded). */
    private static String safeAudioName(String filename) {
        if (filename == null) return null;
        // Strip any directory components.
        String base = new java.io.File(filename).getName().trim();
        if (base.isEmpty() || base.length() > 80) return null;
        // Allow letters/digits/space/._- only; collapse anything else to '_'.
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == ' ';
            sb.append(ok ? c : '_');
        }
        String cleaned = sb.toString();
        int dot = cleaned.lastIndexOf('.');
        if (dot <= 0 || dot >= cleaned.length() - 1) return null; // must have an extension
        if (!isAudioExt(cleaned.substring(dot + 1).toLowerCase())) return null;
        return cleaned;
    }

    /** GET /api/audio/library — list uploaded sounds. */
    private static void handleAudioLibraryList(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            java.io.File dir = new java.io.File(AUDIO_DIR);
            java.io.File[] files = dir.exists() ? dir.listFiles() : null;
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (java.io.File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    if (dot < 0 || !isAudioExt(name.substring(dot + 1).toLowerCase())) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", name);
                    o.put("path", f.getAbsolutePath());
                    o.put("size", f.length());
                    arr.put(o);
                }
            }
            resp.put("success", true);
            resp.put("sounds", arr);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    /** POST /api/audio/library — upload { filename, dataBase64 }. Mirrors the
     *  screen-deterrent image upload (base64 body, atomic write, world-readable). */
    private static void handleAudioLibraryUpload(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) { HttpResponse.sendJsonError(out, "Empty request body"); return; }
        JSONObject req;
        try { req = new JSONObject(body); } catch (Exception e) { HttpResponse.sendJsonError(out, "Invalid JSON"); return; }

        String name = safeAudioName(req.optString("filename", ""));
        if (name == null) { HttpResponse.sendJsonError(out, "Invalid filename (need .mp3/.wav/.mp4/.m4a/.aac/.ogg)"); return; }
        String dataB64 = req.optString("dataBase64", "");
        if (dataB64.isEmpty()) { HttpResponse.sendJsonError(out, "Missing dataBase64"); return; }
        int comma = dataB64.indexOf(',');
        if (dataB64.startsWith("data:") && comma > 0) dataB64 = dataB64.substring(comma + 1);

        byte[] data;
        try { data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT); }
        catch (Exception e) { HttpResponse.sendJsonError(out, "Invalid base64"); return; }
        if (data.length == 0) { HttpResponse.sendJsonError(out, "Empty file"); return; }
        if (data.length > MAX_AUDIO_BYTES) { HttpResponse.sendJsonError(out, "File too large (max 48MB)"); return; }

        java.io.File dir = new java.io.File(AUDIO_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            try { dir.setReadable(true, false); dir.setExecutable(true, false); } catch (Exception ignored) {}
        }
        // Sweep stale temp files before writing. A temp orphaned by an earlier
        // interrupted upload (daemon killed, power cut, the rename race below) is
        // INVISIBLE and UNDELETABLE through the API: the list filters on audio
        // extensions so ".tmp" never appears, and delete runs the name through
        // safeAudioName which rejects ".tmp". Without this sweep each orphan is
        // permanent, and they accumulate on the partition that also holds the
        // config, the H2 stores and the logs.
        sweepStaleTempFiles(dir);

        java.io.File outFile = new java.io.File(dir, name);
        // Note whether we are REPLACING an existing sound. Deliberately not an
        // error — re-uploading a corrected file under the same name is a normal
        // thing to do, and rejecting it would be worse. But it is worth telling
        // the caller, because safeAudioName collapses disallowed characters to
        // '_', so `a*b.mp3` and `a?b.mp3` both land on `a_b.mp3`: a user can
        // silently replace a sound they never intended to touch, and every
        // automation or key-mapping bound to that name then plays different audio
        // with no indication anything changed.
        boolean replaced = outFile.exists();

        // UNIQUE temp name. It used to be `name + ".tmp"`, derived purely from the
        // filename — so two concurrent uploads of the same name both opened the
        // SAME temp with O_TRUNC, each wrote from its own offset 0, and the winner
        // renamed a byte-level hybrid of two different files into place and
        // reported success. Worse, either request's failure-path delete() unlinked
        // the other's in-flight temp. A per-request suffix makes both uploads
        // independent; last rename wins cleanly, which is the expected semantic
        // for same-name uploads.
        java.io.File tmpFile = new java.io.File(dir,
            name + "." + java.util.UUID.randomUUID() + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
            fos.write(data);
            fos.getFD().sync();
        } catch (Throwable t) {
            try { tmpFile.delete(); } catch (Exception ignored) {}
            HttpResponse.sendJsonError(out, "Write failed: " + t.getMessage());
            return;
        }
        try { tmpFile.setReadable(true, false); } catch (Exception ignored) {}
        if (!tmpFile.renameTo(outFile)) {
            try { tmpFile.delete(); } catch (Exception ignored) {}
            HttpResponse.sendJsonError(out, "Rename failed");
            return;
        }
        try { outFile.setReadable(true, false); } catch (Exception ignored) {}

        if (replaced) {
            CameraDaemon.log("audio: upload REPLACED existing sound '" + name
                + "' — automations/key-mappings bound to this name now play the new file");
        }

        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("name", name);
        resp.put("path", outFile.getAbsolutePath());
        // Lets the UI warn "replaced an existing sound" instead of the user only
        // discovering it when an automation plays something unexpected.
        resp.put("replaced", replaced);
        HttpResponse.sendJson(out, resp.toString());
    }

    /** Age after which an abandoned {@code *.tmp} in the audio dir is reapable.
     *  Comfortably longer than any real upload write (the bytes are already fully
     *  decoded in memory before the stream is opened, so the write is a single
     *  buffered dump), while still bounded. */
    private static final long STALE_TMP_AGE_MS = 5L * 60L * 1000L;

    /** Delete abandoned {@code *.tmp} uploads older than {@link #STALE_TMP_AGE_MS}.
     *  These are otherwise unreachable: the library listing filters on audio
     *  extensions so they never render, and DELETE routes the name through
     *  {@code safeAudioName}, which rejects a {@code .tmp} extension. Best-effort
     *  and never fatal — an upload must not fail because a cleanup did. */
    private static void sweepStaleTempFiles(java.io.File dir) {
        try {
            java.io.File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - STALE_TMP_AGE_MS;
            for (java.io.File f : files) {
                if (f == null || !f.isFile()) continue;
                if (!f.getName().endsWith(".tmp")) continue;
                if (f.lastModified() > cutoff) continue;   // possibly still being written
                long size = f.length();
                if (f.delete()) {
                    CameraDaemon.log("audio: reaped stale temp " + f.getName()
                        + " (" + size + " bytes)");
                }
            }
        } catch (Throwable ignored) {
            // Cleanup is opportunistic; never let it break an upload.
        }
    }

    /** DELETE /api/audio/library?name=xxx — remove one uploaded sound. */
    private static void handleAudioLibraryDelete(OutputStream out, String path) throws Exception {
        JSONObject resp = new JSONObject();
        String name = null;
        int q = path.indexOf("?name=");
        if (q >= 0) {
            name = java.net.URLDecoder.decode(path.substring(q + "?name=".length()), "UTF-8");
        }
        String safe = safeAudioName(name);
        if (safe == null) { HttpResponse.sendJsonError(out, "Invalid name"); return; }
        java.io.File f = new java.io.File(AUDIO_DIR, safe);
        boolean deleted = f.exists() && f.delete();
        resp.put("success", deleted);
        if (!deleted) resp.put("error", "Not found");
        HttpResponse.sendJson(out, resp.toString());
    }

    /** POST /api/audio/library/play — preview { name, channel? } on the head unit. */
    private static void handleAudioLibraryPlay(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        // Guarded parse, mirroring the upload handler. Unguarded, a malformed body
        // threw JSONException all the way out to HttpServer's catch, which closes
        // the socket WITHOUT writing a response — the client saw a network error
        // instead of a clean {"success":false}.
        JSONObject req;
        try {
            req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }
        String safe = safeAudioName(req.optString("name", ""));
        if (safe == null) { HttpResponse.sendJsonError(out, "Invalid name"); return; }
        String channel = req.optString("channel", "media");
        java.io.File f = new java.io.File(AUDIO_DIR, safe);
        // Verify the sound still EXISTS before claiming success. dispatchPlay is
        // fire-and-forget (it shells `am` and returns true unconditionally), so a
        // sound the user has since deleted — or an automation still holding a stale
        // name — reported {"success":true} while nothing played, with only logcat
        // showing the app-side 404. Fail honestly instead.
        if (!f.isFile() || f.length() == 0) {
            HttpResponse.sendJsonError(out, "Sound not found: " + safe);
            return;
        }
        boolean ok = com.overdrive.app.byd.AudioPlaybackController.play(f.getAbsolutePath(), channel);
        resp.put("success", ok);
        if (!ok) resp.put("error", "Could not play");
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * GET /api/audio/library/raw?name=xxx — stream one uploaded sound/video's raw bytes,
     * with HTTP Range support. Routed directly from {@link HttpServer} (ahead of the
     * generic handler) so the {@code rangeHeader} reaches us. Consumed by the app-process
     * media player (which can't open the file by path; see the route comment). The name is
     * sanitized to a basename, so this can only ever serve a file that already exists
     * inside {@link #AUDIO_DIR} — no traversal.
     *
     * <p>Range matters for VIDEO: a streaming MediaPlayer/VideoView issues Range requests
     * to locate a non-faststart MP4's trailing {@code moov} atom; without a 206 reply the
     * extractor stalls and "Play Video" showed nothing (audio still worked because MP3
     * streams linearly). {@link HttpResponse#sendMediaFileRanged} serves 206 on a Range
     * request and a plain 200 otherwise.
     */
    public static boolean handleRawRanged(String path, String rangeHeader, OutputStream out) throws Exception {
        handleAudioLibraryRaw(out, path, rangeHeader);
        return true;
    }

    private static void handleAudioLibraryRaw(OutputStream out, String path, String rangeHeader) throws Exception {
        String name = null;
        int q = path.indexOf("?name=");
        if (q >= 0) {
            name = java.net.URLDecoder.decode(path.substring(q + "?name=".length()), "UTF-8");
        }
        String safe = safeAudioName(name);
        if (safe == null) { HttpResponse.sendError(out, 400, "Invalid name"); return; }
        java.io.File f = new java.io.File(AUDIO_DIR, safe);
        if (!f.exists() || !f.isFile()) { HttpResponse.sendError(out, 404, "Not found"); return; }
        String ext = safe.substring(safe.lastIndexOf('.') + 1).toLowerCase();
        HttpResponse.sendMediaFileRanged(out, f, mimeForAudioExt(ext), rangeHeader);
    }

    /** MIME type for a validated audio/video extension (used by the raw stream). */
    private static String mimeForAudioExt(String ext) {
        switch (ext) {
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "m4a":  return "audio/mp4";
            case "aac":  return "audio/aac";
            case "ogg":  return "audio/ogg";
            case "mp4":  return "video/mp4";
            default:      return "application/octet-stream";
        }
    }

    // ==================== AVAS EXTERIOR SPEAKER ====================

    /**
     * POST /api/audio/avas-tone — play (or stop) a factory tone pattern on the
     * exterior AVAS speaker via the AVAH tone generator.
     *
     * Body:
     * {
     *   "pattern": 0..7,      // AvasController.PATTERN_* index; omit or -1 = stop
     *   "stop": true          // alternative explicit stop
     * }
     *
     * Note: this is the ONLY way to make sound on the exterior speaker — custom
     * audio (TTS/PCM/files) cannot route there (MCU DSP I2S/AVAH hard-split).
     * Requires AVAS enabled in Vehicle Settings > Notification.
     */
    private static void handleAvasTone(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            boolean available = com.overdrive.app.byd.AvasController.isAvailable();
            response.put("avasAvailable", available);
            if (!available) {
                response.put("success", false);
                response.put("error", "AVAS unreachable — 'auto' service null (car asleep or non-BYD build)");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            int pattern = -1;
            boolean stop = false;
            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                pattern = req.optInt("pattern", -1);
                stop = req.optBoolean("stop", false);
            }

            if (stop || pattern < 0) {
                com.overdrive.app.byd.AvasController.stop();
                response.put("success", true);
                response.put("action", "stopped");
            } else {
                boolean ok = com.overdrive.app.byd.AvasController.playPattern(pattern);
                response.put("success", ok);
                response.put("action", ok ? "playing" : "rejected");
                response.put("pattern", pattern);
                response.put("patternName", com.overdrive.app.byd.AvasController.patternName(pattern));
                if (!ok) response.put("error", "invalid pattern index (0-"
                        + (com.overdrive.app.byd.AvasController.PATTERN_COUNT - 1) + ")");
            }
            logger.info("avas-tone: " + response.optString("action"));
        } catch (Exception e) {
            logger.warn("avas-tone failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/audio/engine-sound — report engine-sound simulator capability +
     * current state.
     */
    private static void handleEngineSoundState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            boolean available = com.overdrive.app.byd.AvasController.isAvailable();
            response.put("avasAvailable", available);
            response.put("success", true);
            if (available) {
                response.put("supported", com.overdrive.app.byd.AvasController.isEngineSoundSupported());
                response.put("on", com.overdrive.app.byd.AvasController.isEngineSoundOn());
                response.put("preset", com.overdrive.app.byd.AvasController.getEngineSoundPreset());
            } else {
                response.put("supported", false);
            }
        } catch (Exception e) {
            logger.warn("engine-sound state failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/audio/engine-sound — enable/disable the simulator or select a
     * preset.
     *
     * Body:
     * {
     *   "on": true|false,     // toggle simulator; omit to only change preset
     *   "preset": 1..N        // preset index to select
     * }
     */
    private static void handleEngineSound(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            boolean available = com.overdrive.app.byd.AvasController.isAvailable();
            response.put("avasAvailable", available);
            if (!available) {
                response.put("success", false);
                response.put("error", "AVAS unreachable — 'auto' service null");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            JSONObject req = (body != null && !body.isEmpty()) ? new JSONObject(body) : new JSONObject();
            boolean hasOn = req.has("on");
            int preset = req.optInt("preset", -1);

            if (hasOn) {
                boolean on = req.getBoolean("on");
                boolean ok = com.overdrive.app.byd.AvasController.setEngineSound(on, preset);
                response.put("success", ok);
                response.put("on", on);
            } else if (preset >= 1) {
                int applied = com.overdrive.app.byd.AvasController.selectEngineSoundPreset(preset);
                response.put("success", applied >= 0);
                response.put("preset", applied);
            } else {
                response.put("success", false);
                response.put("error", "provide 'on' (bool) and/or 'preset' (>=1)");
            }
            logger.info("engine-sound: on=" + req.opt("on") + " preset=" + preset);
        } catch (Exception e) {
            logger.warn("engine-sound failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/audio/probe-mic-introspect — dump every AudioRecord ctor and
     * field, plus what AudioSystem looks like, so we can see what reflection
     * surface is actually available on this firmware. The earlier blind
     * spoof guesses missed because SDK 29 / BYD's AOSP fork uses different
     * field names than mainline.
     */
    private static void handleProbeMicIntrospect(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("uid", android.os.Process.myUid());
        response.put("pid", android.os.Process.myPid());
        response.put("sdkInt", android.os.Build.VERSION.SDK_INT);
        response.put("manufacturer", android.os.Build.MANUFACTURER);
        response.put("model", android.os.Build.MODEL);

        // All AudioRecord ctors (declared, including hidden)
        org.json.JSONArray ctors = new org.json.JSONArray();
        for (java.lang.reflect.Constructor<?> c : AudioRecord.class.getDeclaredConstructors()) {
            StringBuilder sig = new StringBuilder("AudioRecord(");
            Class<?>[] params = c.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(params[i].getName());
            }
            sig.append(")");
            ctors.put(sig.toString());
        }
        response.put("audioRecordCtors", ctors);

        // All AudioRecord fields
        org.json.JSONArray fields = new org.json.JSONArray();
        for (java.lang.reflect.Field f : AudioRecord.class.getDeclaredFields()) {
            String name = f.getName();
            // Filter to interesting fields — ID/state fields are noise
            if (name.toLowerCase().contains("package") || name.toLowerCase().contains("attribution")
                || name.toLowerCase().contains("op") || name.toLowerCase().contains("uid")
                || name.toLowerCase().contains("source") || name.toLowerCase().contains("native")) {
                fields.put(f.getType().getSimpleName() + " " + name);
            }
        }
        response.put("interestingFields", fields);

        // Methods related to native init
        org.json.JSONArray methods = new org.json.JSONArray();
        for (java.lang.reflect.Method m : AudioRecord.class.getDeclaredMethods()) {
            String name = m.getName();
            if (name.startsWith("native_setup") || name.startsWith("native_") && name.contains("init")
                || name.contains("Setup") || name.contains("setup")) {
                StringBuilder sig = new StringBuilder(m.getReturnType().getSimpleName());
                sig.append(" ").append(name).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(params[i].getSimpleName());
                }
                sig.append(")");
                methods.put(sig.toString());
            }
        }
        response.put("setupMethods", methods);

        // AudioSystem class — look for any method to set opPackageName globally
        try {
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            org.json.JSONArray asMethods = new org.json.JSONArray();
            for (java.lang.reflect.Method m : audioSystem.getDeclaredMethods()) {
                String name = m.getName();
                if (name.toLowerCase().contains("uid") || name.toLowerCase().contains("package")
                    || name.toLowerCase().contains("permission")) {
                    asMethods.put(name);
                }
            }
            response.put("audioSystemRelevantMethods", asMethods);
        } catch (Throwable t) {
            response.put("audioSystemError", t.getMessage());
        }

        // ActivityThread for opPackageName context
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method curThread = at.getMethod("currentActivityThread");
            Object atInst = curThread.invoke(null);
            if (atInst != null) {
                try {
                    java.lang.reflect.Field pkg = at.getDeclaredField("mInitialApplication");
                    pkg.setAccessible(true);
                    Object app = pkg.get(atInst);
                    response.put("activityThreadInitialApp", app != null ? app.getClass().getName() : null);
                    if (app != null) {
                        java.lang.reflect.Method getPkg = app.getClass().getMethod("getPackageName");
                        Object pn = getPkg.invoke(app);
                        response.put("activityThreadInitialAppPackage", pn);
                    }
                } catch (Throwable t) {
                    response.put("activityThreadInspectError", t.getMessage());
                }
            } else {
                response.put("activityThread", "currentActivityThread returned null");
            }
        } catch (Throwable t) {
            response.put("activityThreadError", t.getMessage());
        }

        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/audio/probe-mic-spoof — try to construct AudioRecord with
     * opPackageName spoofed to "com.overdrive.app" so AudioFlinger looks up
     * the app's RECORD_AUDIO grant instead of the daemon's UID 2000.
     *
     * Tries three reflection paths in order:
     *   A) AudioRecord(AudioAttributes, AudioFormat, int, int, String) — hidden
     *      ctor present on most AOSP builds; opPackageName is the last arg.
     *   B) AudioRecord ctor + reflective set of mAttributionSource (Android 12+
     *      synthesizes one from opPackageName, but on this Android version the
     *      field may exist alongside the legacy path).
     *   C) AudioRecord ctor + reflective set of mPackageName field (older
     *      ROMs that didn't switch to AttributionSource yet).
     *
     * Each attempt independently captures ~100 ms of PCM and reports RMS dB.
     * If any path returns rmsDb > -70, the spoof works and we can capture
     * cabin audio from the daemon process without an IPC bounce.
     */
    private static void handleProbeMicSpoof(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        org.json.JSONArray attempts = new org.json.JSONArray();

        String spoofPackage = "com.overdrive.app";
        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufSize = Math.max(minBuf * 4, sampleRate / 5);

        // Path A: hidden ctor (AudioAttributes, AudioFormat, int, int, String)
        attempts.put(tryHiddenCtor(spoofPackage, sampleRate, channelConfig, audioFormat, bufSize));
        // Path B: post-construct mAttributionSource override
        attempts.put(tryAttributionSourceOverride(spoofPackage, sampleRate, channelConfig, audioFormat, bufSize));
        // Path C: post-construct mPackageName override
        attempts.put(tryPackageNameOverride(spoofPackage, sampleRate, channelConfig, audioFormat, bufSize));
        // Path D: native_setup direct call with spoofed opPackageName
        attempts.put(tryNativeSetupSpoof(spoofPackage, sampleRate, channelConfig, audioFormat, bufSize));

        boolean anyWorked = false;
        String winnerPath = null;
        double bestRmsDb = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < attempts.length(); i++) {
            JSONObject a = attempts.getJSONObject(i);
            if (a.optBoolean("usable", false)) {
                double db = a.optDouble("rmsDbValue", Double.NEGATIVE_INFINITY);
                if (db > bestRmsDb) {
                    bestRmsDb = db;
                    winnerPath = a.optString("path", null);
                    anyWorked = true;
                }
            }
        }

        response.put("success", true);
        response.put("uid", android.os.Process.myUid());
        response.put("pid", android.os.Process.myPid());
        response.put("spoofPackage", spoofPackage);
        response.put("anyWorked", anyWorked);
        if (winnerPath != null) {
            response.put("winningPath", winnerPath);
            response.put("winningRmsDb",
                Double.isInfinite(bestRmsDb) ? "-inf"
                    : String.format(java.util.Locale.US, "%.2f", bestRmsDb));
        }
        response.put("attempts", attempts);
        response.put("note", "If any path's usable=true, the daemon can capture audio "
            + "by claiming opPackageName=" + spoofPackage + ". If all fail with the "
            + "same 'permission denied for uid 2000' error, AudioFlinger is "
            + "cross-checking calling UID against the spoofed package's UID — "
            + "fall back to in-app capture + IPC.");

        logger.info("Mic spoof probe: anyWorked=" + anyWorked + " winner=" + winnerPath);
        HttpResponse.sendJson(out, response.toString());
    }

    private static JSONObject tryHiddenCtor(String pkg, int sr, int ch, int fmt, int bufSize)
            throws org.json.JSONException {
        JSONObject a = new JSONObject();
        try {
            a.put("path", "hidden_ctor(AudioAttributes,AudioFormat,int,int,String)");

            // setCapturePreset is @hide on the public SDK — reflect through it.
            android.media.AudioAttributes.Builder attrsBuilder = new android.media.AudioAttributes.Builder();
            try {
                java.lang.reflect.Method setCapturePreset =
                    android.media.AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class);
                setCapturePreset.invoke(attrsBuilder, MediaRecorder.AudioSource.MIC);
            } catch (NoSuchMethodException e) {
                a.put("error", "AudioAttributes.Builder.setCapturePreset not available on this SDK");
                return a;
            }
            android.media.AudioAttributes attrs = attrsBuilder.build();
            android.media.AudioFormat format = new android.media.AudioFormat.Builder()
                .setEncoding(fmt)
                .setSampleRate(sr)
                .setChannelMask(ch)
                .build();

            java.lang.reflect.Constructor<AudioRecord> ctor = AudioRecord.class.getDeclaredConstructor(
                android.media.AudioAttributes.class,
                android.media.AudioFormat.class,
                int.class,
                int.class,
                String.class);
            ctor.setAccessible(true);
            AudioRecord rec = ctor.newInstance(attrs, format, bufSize, 0, pkg);
            captureAndScore(rec, a);
        } catch (NoSuchMethodException e) {
            a.put("error", "ctor not present on this Android version");
        } catch (Throwable t) {
            a.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return a;
    }

    private static JSONObject tryAttributionSourceOverride(String pkg, int sr, int ch, int fmt, int bufSize)
            throws org.json.JSONException {
        JSONObject a = new JSONObject();
        a.put("path", "post_ctor_mAttributionSource");
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, ch, fmt, bufSize);
            // Probe for the field — Android 12+ has it; older ROMs don't.
            java.lang.reflect.Field f;
            try {
                f = AudioRecord.class.getDeclaredField("mAttributionSource");
            } catch (NoSuchFieldException nf) {
                a.put("error", "mAttributionSource field not present (pre-A12 ROM)");
                rec.release();
                return a;
            }
            f.setAccessible(true);
            Object as = f.get(rec);
            if (as == null) {
                a.put("error", "mAttributionSource is null");
                rec.release();
                return a;
            }
            // Set packageName via reflection on the AttributionSource
            try {
                java.lang.reflect.Method setPkg = as.getClass().getMethod("setPackageName", String.class);
                setPkg.invoke(as, pkg);
            } catch (NoSuchMethodException nsm) {
                java.lang.reflect.Field pf = as.getClass().getDeclaredField("mPackageName");
                pf.setAccessible(true);
                pf.set(as, pkg);
            }
            captureAndScore(rec, a);
            rec = null;  // captureAndScore released it
        } catch (Throwable t) {
            a.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (rec != null) {
                try { rec.release(); } catch (Exception ignored) {}
            }
        }
        return a;
    }

    private static JSONObject tryPackageNameOverride(String pkg, int sr, int ch, int fmt, int bufSize)
            throws org.json.JSONException {
        JSONObject a = new JSONObject();
        a.put("path", "post_ctor_mPackageName");
        AudioRecord rec = null;
        try {
            // Some pre-12 builds carry an mPackageName field straight on AudioRecord.
            java.lang.reflect.Field f;
            try {
                f = AudioRecord.class.getDeclaredField("mPackageName");
            } catch (NoSuchFieldException nf) {
                a.put("error", "mPackageName field not present");
                return a;
            }
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, ch, fmt, bufSize);
            f.setAccessible(true);
            f.set(rec, pkg);
            captureAndScore(rec, a);
            rec = null;
        } catch (Throwable t) {
            a.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (rec != null) {
                try { rec.release(); } catch (Exception ignored) {}
            }
        }
        return a;
    }

    /**
     * Path D: bypass the public ctor and call native_setup directly with a
     * spoofed opPackageName. The Java AudioRecord ctor on SDK 29 ends up
     * calling:
     *   native_setup(weakThis, attributes, sampleRateArr, channelMask,
     *                channelIndexMask, audioFormat, buffSizeInBytes,
     *                sessionIdArr, opPackageName, nativeRecordInJavaObj)
     * with opPackageName = ActivityThread.currentOpPackageName() — which is
     * empty under the daemon. We construct an AudioRecord via the simple
     * 5-int public ctor (which itself calls native_setup), then immediately
     * release the native side and re-call native_setup with our chosen pkg.
     *
     * If AudioFlinger's permission check is strictly UID-based (no package
     * cross-check), this works. If it cross-checks calling-UID against the
     * opPackageName's owning UID, this fails identically to the other paths.
     */
    private static JSONObject tryNativeSetupSpoof(String pkg, int sr, int ch, int fmt, int bufSize)
            throws org.json.JSONException {
        JSONObject a = new JSONObject();
        a.put("path", "native_setup_direct(opPackageName=" + pkg + ")");
        AudioRecord rec = null;
        try {
            // Build attributes for native_setup arg 2.
            android.media.AudioAttributes.Builder attrsBuilder = new android.media.AudioAttributes.Builder();
            try {
                java.lang.reflect.Method setCapturePreset =
                    android.media.AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class);
                setCapturePreset.invoke(attrsBuilder, MediaRecorder.AudioSource.MIC);
            } catch (NoSuchMethodException e) {
                a.put("error", "setCapturePreset hidden API unavailable");
                return a;
            }
            android.media.AudioAttributes attrs = attrsBuilder.build();

            // Allocate via the simple public ctor — this DOES invoke
            // native_setup with the wrong opPackageName, but we're going to
            // discard that native side and re-create it.
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, ch, fmt, bufSize);
            // If even the unspoofed ctor failed (state==0), there's no native
            // handle to release — but the JNI side may have allocated one
            // anyway. Force a release before re-calling native_setup.
            try { rec.release(); } catch (Exception ignored) {}
            // Reconstruct the Java-side instance shell without going through
            // a public ctor: use Unsafe-style allocation via a no-arg subclass.
            // SDK 29 AudioRecord has no nullary ctor, so we must invoke
            // native_setup directly on the existing (just-released) instance.
            // The instance fields are in a known state after release().

            // Resolve native_setup. Signature from introspect:
            //   int native_setup(Object weakThis, Object attributes, int[] sampleRate,
            //                    int channelMask, int channelIndexMask, int audioFormat,
            //                    int buffSizeInBytes, int[] sessionId, String opPackageName,
            //                    long nativeRecordInJavaObj)
            java.lang.reflect.Method nativeSetup = AudioRecord.class.getDeclaredMethod(
                "native_setup",
                Object.class, Object.class, int[].class, int.class, int.class, int.class,
                int.class, int[].class, String.class, long.class);
            nativeSetup.setAccessible(true);

            int[] sampleRateArr = new int[] { sr };
            int[] sessionIdArr = new int[] { 0 };
            int channelMask = ch;  // already CHANNEL_IN_MONO
            int channelIndexMask = 0;
            // weakThis: AudioRecord uses WeakReference<AudioRecord>
            java.lang.ref.WeakReference<AudioRecord> weak = new java.lang.ref.WeakReference<>(rec);

            Object setupResult = nativeSetup.invoke(rec,
                weak, attrs, sampleRateArr, channelMask, channelIndexMask, fmt,
                bufSize, sessionIdArr, pkg, 0L);
            int initStatus = (Integer) setupResult;
            a.put("native_setup_status", initStatus);

            // Push native_setup's status into mState/mInitializationLooper so
            // public methods don't blow up.
            try {
                java.lang.reflect.Field mState = AudioRecord.class.getDeclaredField("mState");
                mState.setAccessible(true);
                mState.setInt(rec, initStatus == 0 ? AudioRecord.STATE_INITIALIZED : AudioRecord.STATE_UNINITIALIZED);
            } catch (Throwable ignored) {}

            if (initStatus != 0) {
                a.put("error", "native_setup returned " + initStatus + " (non-zero = failed)");
                return a;
            }

            // Wire mNativeBufferSizeInBytes so read() doesn't reject.
            try {
                java.lang.reflect.Field mBuf = AudioRecord.class.getDeclaredField("mNativeBufferSizeInBytes");
                mBuf.setAccessible(true);
                mBuf.setInt(rec, bufSize);
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field mRec = AudioRecord.class.getDeclaredField("mRecordSource");
                mRec.setAccessible(true);
                mRec.setInt(rec, MediaRecorder.AudioSource.MIC);
            } catch (Throwable ignored) {}

            captureAndScore(rec, a);
            rec = null;
        } catch (NoSuchMethodException e) {
            a.put("error", "native_setup signature mismatch: " + e.getMessage());
        } catch (Throwable t) {
            a.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (rec != null) {
                try { rec.release(); } catch (Exception ignored) {}
            }
        }
        return a;
    }

    /**
     * Captures ~100 ms of PCM from a constructed AudioRecord, computes RMS,
     * stuffs results into the attempt JSON. Always releases the recorder.
     */
    private static void captureAndScore(AudioRecord rec, JSONObject attempt)
            throws org.json.JSONException {
        try {
            int state = rec.getState();
            attempt.put("state", state);
            if (state != AudioRecord.STATE_INITIALIZED) {
                attempt.put("error", "STATE_UNINITIALIZED — spoof rejected by AudioFlinger");
                return;
            }
            rec.startRecording();
            int recState = rec.getRecordingState();
            attempt.put("recordingState", recState);
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                attempt.put("error", "RECORDSTATE != RECORDING (got " + recState + ")");
                return;
            }
            short[] samples = new short[4800];
            long t0 = System.nanoTime();
            int totalRead = 0;
            while (totalRead < samples.length) {
                int n = rec.read(samples, totalRead, samples.length - totalRead);
                if (n <= 0) {
                    attempt.put("readError", "read returned " + n);
                    break;
                }
                totalRead += n;
                if (System.nanoTime() - t0 > 500_000_000L) {
                    attempt.put("readTimeout", true);
                    break;
                }
            }
            attempt.put("samplesRead", totalRead);
            if (totalRead > 0) {
                double sumSq = 0.0;
                int peak = 0;
                int nonZero = 0;
                for (int s = 0; s < totalRead; s++) {
                    int v = samples[s];
                    if (v != 0) nonZero++;
                    int abs = v < 0 ? -v : v;
                    if (abs > peak) peak = abs;
                    sumSq += (double) v * v;
                }
                double rms = Math.sqrt(sumSq / totalRead);
                double rmsDb = rms > 0 ? 20.0 * Math.log10(rms / 32768.0) : Double.NEGATIVE_INFINITY;
                attempt.put("nonZeroSamples", nonZero);
                attempt.put("peakAmplitude", peak);
                attempt.put("rms", rms);
                attempt.put("rmsDb", Double.isInfinite(rmsDb) ? "-inf"
                    : String.format(java.util.Locale.US, "%.2f", rmsDb));
                attempt.put("rmsDbValue", Double.isInfinite(rmsDb) ? -999.0 : rmsDb);
                attempt.put("usable", !Double.isInfinite(rmsDb) && rmsDb > -70.0);
            }
            try { rec.stop(); } catch (Exception ignored) {}
            attempt.put("ok", true);
        } catch (Throwable t) {
            attempt.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { rec.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * GET /api/audio/probe-mic — verify whether AudioRecord can capture PCM
     * from the cabin microphone under the daemon process (UID 2000 / shell).
     *
     * Tries three audio sources in order (MIC, VOICE_RECOGNITION, CAMCORDER),
     * captures ~100 ms of 48 kHz mono PCM_16, and reports RMS dB. A working
     * source returns success=true with rmsDb above the silence floor (~-70 dB).
     *
     * Failure modes surfaced:
     *   - getMinBufferSize returns ERROR_BAD_VALUE: format unsupported by HAL
     *   - AudioRecord ctor throws: permission/policy denial
     *   - state != STATE_INITIALIZED: AudioFlinger refused (UID/appops)
     *   - read() returns 0: mic claimed by another client (BT call, voice asst)
     *   - rmsDb == -inf: opened but silent (policy-muted, common DiLink quirk)
     */
    private static void handleProbeMic(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        org.json.JSONArray attempts = new org.json.JSONArray();

        int[] sources = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER
        };
        String[] sourceNames = { "MIC", "VOICE_RECOGNITION", "CAMCORDER" };

        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        boolean anyWorked = false;
        String workingSourceName = null;
        double bestRmsDb = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < sources.length; i++) {
            JSONObject attempt = new JSONObject();
            attempt.put("source", sourceNames[i]);
            attempt.put("sourceId", sources[i]);
            attempt.put("sampleRate", sampleRate);

            AudioRecord rec = null;
            try {
                int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                attempt.put("minBufferSize", minBuf);
                if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
                    attempt.put("error", "getMinBufferSize=" + minBuf);
                    attempts.put(attempt);
                    continue;
                }

                int bufSize = Math.max(minBuf * 4, sampleRate / 5);  // ~200 ms safety
                attempt.put("bufferSize", bufSize);

                rec = new AudioRecord(sources[i], sampleRate, channelConfig, audioFormat, bufSize);
                int state = rec.getState();
                attempt.put("state", state);
                if (state != AudioRecord.STATE_INITIALIZED) {
                    attempt.put("error", "STATE_UNINITIALIZED — AudioFlinger refused (UID/appops/policy)");
                    attempts.put(attempt);
                    continue;
                }

                rec.startRecording();
                int recState = rec.getRecordingState();
                attempt.put("recordingState", recState);
                if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                    attempt.put("error", "RECORDSTATE != RECORDING (got " + recState + ") — mic likely claimed");
                    attempts.put(attempt);
                    continue;
                }

                // Capture ~100 ms = 4800 samples mono
                short[] samples = new short[4800];
                long t0 = System.nanoTime();
                int totalRead = 0;
                while (totalRead < samples.length) {
                    int n = rec.read(samples, totalRead, samples.length - totalRead);
                    if (n <= 0) {
                        attempt.put("readError", "read returned " + n + " after " + totalRead + " samples");
                        break;
                    }
                    totalRead += n;
                    if (System.nanoTime() - t0 > 500_000_000L) {  // 500 ms timeout
                        attempt.put("readTimeout", true);
                        break;
                    }
                }
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                attempt.put("samplesRead", totalRead);
                attempt.put("readElapsedMs", elapsedMs);

                if (totalRead > 0) {
                    double sumSq = 0.0;
                    int peak = 0;
                    int nonZero = 0;
                    for (int s = 0; s < totalRead; s++) {
                        int v = samples[s];
                        if (v != 0) nonZero++;
                        int abs = v < 0 ? -v : v;
                        if (abs > peak) peak = abs;
                        sumSq += (double) v * v;
                    }
                    double rms = Math.sqrt(sumSq / totalRead);
                    double rmsDb = rms > 0 ? 20.0 * Math.log10(rms / 32768.0) : Double.NEGATIVE_INFINITY;
                    attempt.put("nonZeroSamples", nonZero);
                    attempt.put("peakAmplitude", peak);
                    attempt.put("rms", rms);
                    attempt.put("rmsDb", Double.isInfinite(rmsDb) ? "-inf"
                        : String.format(java.util.Locale.US, "%.2f", rmsDb));

                    // -70 dBFS is roughly the floor of usable speech capture.
                    // Below that we're either policy-muted or in a silent room
                    // with a working mic — without a known stimulus we can't
                    // distinguish, so flag both as worth a follow-up listen.
                    boolean usable = !Double.isInfinite(rmsDb) && rmsDb > -70.0;
                    attempt.put("usable", usable);

                    // Pick the strongest source. A usable source always beats
                    // a non-usable one; among same-tier candidates the higher
                    // RMS wins. This way the recommendation and the reported
                    // bestRmsDb always describe the same source.
                    boolean preferThis;
                    if (usable && !anyWorked) {
                        preferThis = true;
                        anyWorked = true;
                    } else if (usable == anyWorked
                            && !Double.isInfinite(rmsDb) && rmsDb > bestRmsDb) {
                        preferThis = true;
                    } else {
                        preferThis = false;
                    }
                    if (preferThis) {
                        workingSourceName = sourceNames[i];
                        bestRmsDb = rmsDb;
                    }
                }

                rec.stop();
                attempt.put("ok", true);
            } catch (SecurityException e) {
                attempt.put("error", "SecurityException: " + e.getMessage()
                    + " — RECORD_AUDIO not granted to UID " + android.os.Process.myUid());
            } catch (IllegalStateException e) {
                attempt.put("error", "IllegalStateException: " + e.getMessage());
            } catch (Throwable t) {
                attempt.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (rec != null) {
                    try { rec.release(); } catch (Exception ignored) {}
                }
            }
            attempts.put(attempt);
        }

        response.put("success", true);
        response.put("uid", android.os.Process.myUid());
        response.put("pid", android.os.Process.myPid());
        response.put("anyUsableSource", anyWorked);
        if (workingSourceName != null) {
            response.put("recommendedSource", workingSourceName);
            response.put("recommendedRmsDb",
                Double.isInfinite(bestRmsDb) ? "-inf"
                    : String.format(java.util.Locale.US, "%.2f", bestRmsDb));
        }
        response.put("attempts", attempts);
        response.put("note", "rmsDb > -70 in a non-silent cabin = mic works. -inf or all-zero "
            + "samples = policy-muted (need appops fix or capture in app process). "
            + "ERROR_INVALID_OPERATION on read = mic claimed by BT/voice service.");

        logger.info("Mic probe: anyUsable=" + anyWorked + " best=" + workingSourceName
            + " bestRmsDb=" + (Double.isInfinite(bestRmsDb) ? "-inf"
                : String.format(java.util.Locale.US, "%.2f", bestRmsDb)));

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/audio/avas-state — read-only state query.
     */
    private static void handleGetState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydDataCollector collector = BydDataCollector.getInstance();

            response.put("multimediaAvailable", collector.isMultimediaAvailable());
            response.put("collectorInitialized", collector.isInitialized());

            if (!collector.isMultimediaAvailable()) {
                // Multimedia device returned null from getInstance() — 
                // this means the IVI audio service isn't running (car off / multimedia subsystem powered down).
                // We can still try playing audio through Android's standard audio path.
                response.put("success", true);
                response.put("warning", "Multimedia device unavailable (getInstance returned null). AVAS routing not possible, but standard audio output may still work.");
                response.put("canPlayStandardAudio", true);
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            Integer speakerState = collector.getExteriorSpeakerState();
            Integer avasSource = collector.getAVASSoundSource();

            response.put("success", true);
            response.put("exteriorSpeakerState", speakerState != null ? speakerState : JSONObject.NULL);
            response.put("exteriorSpeakerEnabled", speakerState != null ? speakerState == 1 : JSONObject.NULL);
            response.put("avasSource", avasSource != null ? avasSource : JSONObject.NULL);

            // Probe the multimedia device for any related methods so we can see what
            // the OEM build actually exposes (method names vary across BYD models).
            java.util.List<String> matches = collector.probeMultimediaMethods(
                    "AVAS|Speaker|Sound|External|Exterior|Outside|Avas");
            org.json.JSONArray probeArr = new org.json.JSONArray();
            for (String s : matches) probeArr.put(s);
            response.put("probedMethods", probeArr);

        } catch (Exception e) {
            logger.warn("avas-state failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/audio/test-avas — force-enable AVAS and play audio.
     * 
     * Body (all optional):
     * {
     *   "mode": "tone" | "tts" | "file",   // default: "tone"
     *   "sourceType": 3,                     // AVAS source type to try (default: 3)
     *   "volume": 20,                        // media volume 0-39 (default: 20)
     *   "duration": 3000,                    // tone duration ms (default: 3000)
     *   "text": "Hello from AVAS",           // TTS text (default: "AVAS speaker test")
     *   "restore": true                      // restore original state after (default: true)
     * }
     */
    private static void handleTestAvas(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        JSONObject log = new JSONObject();

        try {
            // Parse request
            String mode = "tone";
            int sourceType = 3; // media
            int volume = 20;
            int duration = 3000;
            String ttsText = "AVAS speaker test";
            boolean restore = true;

            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                mode = req.optString("mode", "tone");
                sourceType = req.optInt("sourceType", 3);
                volume = Math.min(req.optInt("volume", 20), 30); // safety cap
                duration = Math.min(req.optInt("duration", 3000), 10000); // max 10s
                ttsText = req.optString("text", "AVAS speaker test");
                restore = req.optBoolean("restore", true);
            }

            logger.info("test-avas: mode=" + mode + " source=" + sourceType +
                       " vol=" + volume + " dur=" + duration);

            BydDataCollector collector = BydDataCollector.getInstance();
            boolean multimediaAvailable = collector.isMultimediaAvailable();
            log.put("multimediaAvailable", multimediaAvailable);

            // Step 1: Save original state (only if multimedia device is available)
            Integer origSpeaker = null;
            Integer origSource = null;

            if (multimediaAvailable) {
                origSpeaker = collector.getExteriorSpeakerState();
                origSource = collector.getAVASSoundSource();

                log.put("originalState", new JSONObject()
                    .put("speakerState", origSpeaker != null ? origSpeaker : JSONObject.NULL)
                    .put("avasSource", origSource != null ? origSource : JSONObject.NULL));

                logger.info("test-avas: original: speaker=" + origSpeaker + " source=" + origSource);

                // Step 2: Force-enable AVAS speaker
                boolean speakerOk = collector.setExteriorSpeakerState(1);
                boolean sourceOk = collector.setAVASSoundSource(sourceType);

                log.put("setup", new JSONObject()
                    .put("speakerEnabled", speakerOk)
                    .put("avasSourceSet", sourceOk)
                    .put("sourceType", sourceType));

                logger.info("test-avas: setup: speaker=" + speakerOk + " source=" + sourceOk);
            } else {
                log.put("originalState", "skipped (multimedia device unavailable)");
                log.put("setup", "skipped (playing through standard Android audio output)");
                logger.info("test-avas: multimedia unavailable, playing through standard audio");
            }

            // Step 3: Play audio
            boolean playOk = false;

            switch (mode) {
                case "tts":
                    playOk = playTts(ttsText, duration + 2000);
                    break;
                case "file":
                    playOk = playFile(duration);
                    break;
                case "tone":
                default:
                    playOk = playTone(duration);
                    break;
            }

            log.put("playback", new JSONObject()
                .put("mode", mode)
                .put("success", playOk));

            logger.info("test-avas: playback " + mode + " = " + playOk);

            // Step 4: Restore original state
            if (restore && multimediaAvailable) {
                boolean restoreSpeaker = true;
                boolean restoreSource = true;

                if (origSpeaker != null) {
                    restoreSpeaker = collector.setExteriorSpeakerState(origSpeaker);
                }
                if (origSource != null) {
                    restoreSource = collector.setAVASSoundSource(origSource);
                }

                log.put("restore", new JSONObject()
                    .put("speaker", restoreSpeaker)
                    .put("source", restoreSource));
            }

            // Step 5: Verify final state
            if (multimediaAvailable) {
                Integer finalSpeaker = collector.getExteriorSpeakerState();
                Integer finalSource = collector.getAVASSoundSource();

                log.put("finalState", new JSONObject()
                    .put("speakerState", finalSpeaker != null ? finalSpeaker : JSONObject.NULL)
                    .put("avasSource", finalSource != null ? finalSource : JSONObject.NULL));
            }

            response.put("success", true);
            response.put("log", log);

        } catch (Exception e) {
            logger.warn("test-avas failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("log", log);
        }
        HttpResponse.sendJson(out, response.toString());
    }

    // ==================== AUDIO PLAYBACK ====================

    /**
     * Play a tone via ToneGenerator on STREAM_MUSIC.
     */
    private static boolean playTone(int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationMs);
            Thread.sleep(durationMs + 500);
            tg.release();
            logger.info("playTone: completed (" + durationMs + "ms)");
            return true;
        } catch (Exception e) {
            logger.warn("playTone failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Play TTS via Android TextToSpeech on STREAM_MUSIC.
     */
    private static boolean playTts(String text, int timeoutMs) {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            logger.warn("playTts: no context available");
            return false;
        }

        final CountDownLatch initLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicBoolean initOk = new AtomicBoolean(false);

        try {
            TextToSpeech tts = new TextToSpeech(ctx, status -> {
                initOk.set(status == TextToSpeech.SUCCESS);
                initLatch.countDown();
            });

            if (!initLatch.await(5, TimeUnit.SECONDS)) {
                logger.warn("playTts: TTS init timeout");
                tts.shutdown();
                return false;
            }

            if (!initOk.get()) {
                logger.warn("playTts: TTS init failed");
                tts.shutdown();
                return false;
            }

            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) { doneLatch.countDown(); }
                @Override public void onError(String utteranceId) { doneLatch.countDown(); }
            });

            android.os.Bundle params = new android.os.Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "avas_test");

            boolean completed = doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            tts.shutdown();

            logger.info("playTts: " + (completed ? "completed" : "timed out"));
            return completed;

        } catch (Exception e) {
            logger.warn("playTts failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Play a beep pattern using ToneGenerator (distinguishable from single tone).
     */
    private static boolean playFile(int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            for (int i = 0; i < 3; i++) {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 500);
                Thread.sleep(700);
            }
            tg.release();
            logger.info("playFile (beep pattern): completed");
            return true;
        } catch (Exception e) {
            logger.warn("playFile failed: " + e.getMessage());
            return false;
        }
    }
}
