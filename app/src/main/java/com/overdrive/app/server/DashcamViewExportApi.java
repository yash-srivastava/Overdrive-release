package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.timeline.RecordingSpanHints;
import com.overdrive.app.server.timeline.SpanRules;
import com.overdrive.app.server.timeline.TimelineData;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.storage.removable.RemovableStorageFs;
import com.overdrive.app.surveillance.DashcamViewExporter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Export Dashcam view": renders a grid + windshield range into one shareable replay_*.mp4 in the
 * recordings folder (listed, downloadable and backed up like an instant replay). One job at a time,
 * only on request, only while parked with the cameras idle; cancelled as soon as that stops holding.
 *
 * <pre>
 * GET  /api/recordings/dashcam-view/available?ids=a,b  ids whose clip can be exported
 * POST /api/recordings/dashcam-view/export {"id"} | {"from","to"}  start (202) or refuse (409 + code)
 * GET  /api/recordings/dashcam-view/export             job status and progress
 * POST /api/recordings/dashcam-view/cancel             cancel the running job
 * </pre>
 */
public final class DashcamViewExportApi {
    private static final DaemonLogger logger = DaemonLogger.getInstance("DashcamViewExport");

    static final String PREFIX = "/api/recordings/dashcam-view/";
    public static final long MAX_RANGE_MS = 5L * 60_000L;
    private static final long MIN_RANGE_MS = 1_000L;
    // Room kept free for the recorders after the export (same as an instant replay).
    private static final long RESERVE_BYTES = 200L * 1024L * 1024L;
    // The job stops when the volume drops below this while writing.
    private static final long MIN_FREE_WHILE_WRITING = 100L * 1024L * 1024L;
    private static final int MAX_IDS = 50;
    private static final long AVAILABLE_TTL_MS = 30_000L;
    private static final Set<String> LEADER_TYPES =
            new HashSet<>(Arrays.asList("normal", "sentry", "proximity"));
    private static final List<String> ROLES = Arrays.asList("front", "right", "rear", "left");

    private DashcamViewExportApi() {}

    /** @return true when handled */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!path.startsWith(PREFIX)) return false;
        String rest = path.substring(PREFIX.length());
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        if (rest.equals("available") && "GET".equals(method)) {
            serveAvailable(out, query);
            return true;
        }
        if (rest.equals("export") && "GET".equals(method)) {
            JSONObject res = new JSONObject();
            res.put("success", true);
            Job job = current;
            res.put("job", job != null ? job.json() : JSONObject.NULL);
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if (rest.equals("export") && "POST".equals(method)) {
            startExport(out, body);
            return true;
        }
        if (rest.equals("cancel") && "POST".equals(method)) {
            Job job = current;
            if (job != null && job.running()) job.cancel = true;
            JSONObject res = new JSONObject();
            res.put("success", true);
            res.put("job", job != null ? job.json() : JSONObject.NULL);
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        HttpResponse.sendJson(out, 404, error("not_found", "Unknown endpoint").toString());
        return true;
    }

    private static JSONObject error(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false);
            o.put("code", code);
            o.put("error", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ gate

    /** Null when an export may run now, else the refusal code. Cheap; polled while a job runs. */
    static String busyReason() {
        if (!CameraDaemon.isRunning() || CameraDaemon.isProcessRestartPending()) return "busy";
        // Parked only: decoders + encoder must never compete with recording (DiLink 3 codec/GPU budget).
        if (!CameraDaemon.isAccOffAdmitted()) return "acc_on";
        com.overdrive.app.surveillance.GpuSurveillancePipeline pano = CameraDaemon.getGpuPipeline();
        if (pano != null && (pano.isRunning() || pano.isRecording() || pano.getPendingRecordingPrefix() != null)) {
            return "cameras_busy";
        }
        com.overdrive.app.camera.OemDashcamPipeline oem = CameraDaemon.getOemDashcamPipeline();
        if (oem != null && (oem.isRunning() || oem.isRecording())) return "cameras_busy";
        return null;
    }

    private static String gateMessage(String code) {
        if ("acc_on".equals(code)) return "Export runs only while the car is parked (ACC off).";
        if ("cameras_busy".equals(code)) {
            return "Export waits until the cameras are idle (Sentry, live view and recording off).";
        }
        return "The camera service is busy. Try again in a moment.";
    }

    // ------------------------------------------------------------------ planning

    static final class Plan {
        final long from;
        final long to;
        final List<DashcamViewExporter.Item> leaders = new ArrayList<>();
        final List<DashcamViewExporter.Item> followers = new ArrayList<>();

        Plan(long from, long to) {
            this.from = from;
            this.to = to;
        }
    }

    static final class Refusal extends Exception {
        final String code;

        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Leader (grid) and follower (windshield) clips for [from, to); throws when the view isn't available. */
    static Plan plan(long from, long to, String prefer) throws Refusal {
        if (to - from < MIN_RANGE_MS) throw new Refusal("range", "The range is too short.");
        if (to - from > MAX_RANGE_MS) throw new Refusal("range", "Export at most 5 minutes at a time.");
        List<TimelineData.PlanItem> grid;
        List<TimelineData.PlanItem> oem;
        try {
            grid = TimelineData.playbackPlan(from, to, LEADER_TYPES, prefer);
            oem = TimelineData.playbackPlan(from, to, null, null, true);
        } catch (Throwable t) {
            throw new Refusal("index", "The recordings index is not ready yet. Try again shortly.");
        }
        Plan plan = new Plan(from, to);
        DashcamViewExporter.Grid first = null;
        long gridMs = 0;
        for (TimelineData.PlanItem p : grid) {
            TimelineData.ClipRef c = p.clip;
            if (c == null || isOem(c)) continue;
            File file = c.absPath != null ? new File(c.absPath) : null;
            DashcamViewExporter.Grid g = file != null && c.available
                    && SpanRules.SRC_WRITER.equals(c.spanSrc) ? gridFor(file) : null;
            if (g == null) throw new Refusal("unavailable", "Dashcam view isn't available for this range.");
            if (first == null) first = g;
            else if (g.width != first.width || g.height != first.height) {
                throw new Refusal("unavailable", "The range mixes recordings of different sizes.");
            }
            plan.leaders.add(new DashcamViewExporter.Item(file, c.start, p.playFromWall, p.playToWall, g));
            gridMs += p.playToWall - p.playFromWall;
        }
        if (gridMs < MIN_RANGE_MS) throw new Refusal("unavailable", "Dashcam view isn't available for this range.");
        long oemMs = 0;
        for (TimelineData.PlanItem p : oem) {
            TimelineData.ClipRef c = p.clip;
            if (c == null || !c.available || c.absPath == null || !SpanRules.SRC_WRITER.equals(c.spanSrc)) continue;
            plan.followers.add(new DashcamViewExporter.Item(new File(c.absPath), c.start, p.playFromWall,
                    p.playToWall, null));
            oemMs += p.playToWall - p.playFromWall;
        }
        if (oemMs < MIN_RANGE_MS) {
            throw new Refusal("no_dashcam", "No windshield camera recording covers this range.");
        }
        return plan;
    }

    private static boolean isOem(TimelineData.ClipRef c) {
        return "oemDashcam".equals(c.type) || "single".equals(c.layout);
    }

    private static final Map<String, Object> GRID_CACHE = new LinkedHashMap<String, Object>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > 256;
        }
    };
    private static final Object NO_GRID = new Object();

    /** The clip's sidecar grid block per the #1414 contract parsing rule, or null. */
    static DashcamViewExporter.Grid gridFor(File mp4) {
        File parent = mp4.getParentFile();
        if (parent == null || !mp4.getName().endsWith(".mp4")) return null;
        File json = new File(parent, mp4.getName().substring(0, mp4.getName().length() - 4) + ".json");
        RemovableStorageFs.FileStamp stamp;
        try {
            stamp = RemovableStorageFs.fileStamp(json);
        } catch (Throwable t) {
            return null;
        }
        if (stamp == null || stamp.length <= 0) return null;
        String key = json.getAbsolutePath() + "|" + stamp.length + "|" + stamp.mtime;
        synchronized (GRID_CACHE) {
            Object hit = GRID_CACHE.get(key);
            if (hit != null) return hit == NO_GRID ? null : (DashcamViewExporter.Grid) hit;
        }
        DashcamViewExporter.Grid g = null;
        try {
            if (stamp.length <= 1_048_576) {
                JSONObject root = new JSONObject(new String(RemovableStorageFs.readAllBytes(json),
                        StandardCharsets.UTF_8));
                g = parseGrid(root.optJSONObject("grid"));
            }
        } catch (Throwable ignored) {
            g = null;
        }
        synchronized (GRID_CACHE) {
            GRID_CACHE.put(key, g != null ? g : NO_GRID);
        }
        return g;
    }

    static DashcamViewExporter.Grid parseGrid(JSONObject grid) {
        if (grid == null || grid.optInt("v", 0) != 1) return null;
        JSONObject frame = grid.optJSONObject("frame");
        JSONArray tiles = grid.optJSONArray("tiles");
        JSONObject dash = grid.optJSONObject("dashcam");
        if (frame == null || tiles == null || dash == null || tiles.length() != 4) return null;
        DashcamViewExporter.Grid g = new DashcamViewExporter.Grid();
        g.width = frame.optInt("width", 0);
        g.height = frame.optInt("height", 0);
        if (g.width < 2 || g.width > 8192 || g.height < 2 || g.height > 8192) return null;
        List<String> order = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String role = tiles.optString(i, "");
            if (!ROLES.contains(role) || order.contains(role)) return null;
            order.add(role);
        }
        double aspect = grid.optDouble("aspect", Double.NaN);
        if (!(aspect >= 0.25 && aspect <= 1.0)) return null;
        double split = dash.optDouble("split", Double.NaN);
        if (!(split > 0.0 && split < 1.0)) return null;
        JSONArray cells = dash.optJSONArray("cells");
        if (cells == null || cells.length() != 3) return null;
        for (int i = 0; i < 3; i++) {
            int tile = order.indexOf(cells.optString(i, ""));
            if (tile < 0) return null;
            g.cellTiles[i] = tile;
        }
        g.aspect = (float) aspect;
        g.split = (float) split;
        g.raw = grid.optBoolean("raw", false);
        int strip = grid.optInt("strip", 0);
        g.strip = strip > 0 && strip * 2 < g.height ? strip : 0;
        return g;
    }

    // ------------------------------------------------------------------ availability

    private static final Map<String, long[]> AVAILABLE_CACHE = new LinkedHashMap<String, long[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
            return size() > 512;
        }
    };

    private static void serveAvailable(OutputStream out, String query) throws Exception {
        String ids = null;
        for (String part : query.split("&")) {
            if (part.startsWith("ids=")) ids = java.net.URLDecoder.decode(part.substring(4), "UTF-8");
        }
        JSONArray ok = new JSONArray();
        if (ids != null) {
            int n = 0;
            for (String id : ids.split(",")) {
                if (!id.matches("[a-fA-F0-9]{32}") || n++ >= MAX_IDS) continue;
                if (availableFor(id)) ok.put(id);
            }
        }
        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("ids", ok);
        res.put("maxSeconds", MAX_RANGE_MS / 1000L);
        HttpResponse.sendJson(out, res.toString());
    }

    private static boolean availableFor(String id) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (AVAILABLE_CACHE) {
            long[] hit = AVAILABLE_CACHE.get(id);
            if (hit != null && now - hit[0] < AVAILABLE_TTL_MS) return hit[1] != 0;
        }
        boolean ok;
        try {
            TimelineData.ClipRef c = TimelineData.clip(id);
            ok = c != null && rangeFor(c) != null && plan(c.start, rangeFor(c)[1], c.type) != null;
        } catch (Refusal r) {
            ok = false;
        } catch (Throwable t) {
            return false; // index unavailable: not cached
        }
        synchronized (AVAILABLE_CACHE) {
            AVAILABLE_CACHE.put(id, new long[]{now, ok ? 1 : 0});
        }
        return ok;
    }

    /** The clip's own span, capped to {@link #MAX_RANGE_MS} from its start; null when not exportable. */
    private static long[] rangeFor(TimelineData.ClipRef c) {
        if (c == null || c.end == null || !LEADER_TYPES.contains(c.type) || isOem(c)) return null;
        long end = Math.min(c.end, c.start + MAX_RANGE_MS);
        return end - c.start >= MIN_RANGE_MS ? new long[]{c.start, end} : null;
    }

    // ------------------------------------------------------------------ jobs

    static final class Job {
        final String sourceId;
        final long from;
        final long to;
        final long startedAt = System.currentTimeMillis();
        volatile String state = "running";
        volatile float progress;
        volatile String code;
        volatile String error;
        volatile String filename;
        volatile String recordingId;
        volatile boolean cancel;
        volatile Thread thread;

        Job(String sourceId, long from, long to) {
            this.sourceId = sourceId;
            this.from = from;
            this.to = to;
        }

        boolean running() {
            return "running".equals(state);
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("progress", Math.round(progress * 1000f) / 1000.0);
            o.put("sourceId", sourceId != null ? sourceId : JSONObject.NULL);
            o.put("from", from);
            o.put("to", to);
            o.put("startedAt", startedAt);
            o.put("code", code != null ? code : JSONObject.NULL);
            o.put("error", error != null ? error : JSONObject.NULL);
            o.put("filename", filename != null ? filename : JSONObject.NULL);
            o.put("id", recordingId != null ? recordingId : JSONObject.NULL);
            o.put("videoUrl", recordingId != null ? "/video/id/" + recordingId : JSONObject.NULL);
            return o;
        }
    }

    private static volatile Job current;
    private static final Object START_LOCK = new Object();

    private static void startExport(OutputStream out, String body) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
        } catch (Throwable t) {
            HttpResponse.sendJson(out, 400, error("bad_request", "Invalid request").toString());
            return;
        }
        Job job;
        synchronized (START_LOCK) {
            Job running = current;
            if (running != null && running.running()) {
                HttpResponse.sendJson(out, 409, error("busy", "An export is already running.").toString());
                return;
            }
            String gate = busyReason();
            if (gate != null) {
                HttpResponse.sendJson(out, 409, error(gate, gateMessage(gate)).toString());
                return;
            }
            String sourceId = null;
            long from;
            long to;
            String prefer = null;
            try {
                String id = req.optString("id", "");
                if (!id.isEmpty()) {
                    if (!id.matches("[a-fA-F0-9]{32}")) throw new Refusal("bad_request", "Invalid recording id");
                    TimelineData.ClipRef c;
                    try {
                        c = TimelineData.clip(id);
                    } catch (Throwable t) {
                        throw new Refusal("index", "The recordings index is not ready yet. Try again shortly.");
                    }
                    long[] range = rangeFor(c);
                    if (range == null) throw new Refusal("unavailable", "Dashcam view isn't available for this recording.");
                    sourceId = id;
                    from = range[0];
                    to = range[1];
                    prefer = c.type;
                } else {
                    from = req.optLong("from", 0L);
                    to = req.optLong("to", 0L);
                    if (from <= 0 || to <= from) throw new Refusal("bad_request", "Invalid range");
                }
                Plan plan = plan(from, to, prefer);
                File output = prepareOutput(plan);
                job = new Job(sourceId, from, to);
                job.filename = output.getName();
                launch(job, plan, output);
            } catch (Refusal r) {
                HttpResponse.sendJson(out, "bad_request".equals(r.code) ? 400 : 409,
                        error(r.code, r.getMessage()).toString());
                return;
            }
            current = job;
        }
        JSONObject res = new JSONObject();
        res.put("success", true);
        res.put("job", job.json());
        HttpResponse.sendJson(out, 202, res.toString());
    }

    /** Estimated output bytes: encoder bitrate (upper bound) plus audio and container slack. */
    static long estimateBytes(Plan plan) {
        DashcamViewExporter.Grid g = plan.leaders.get(0).grid;
        int w = Math.min(g.width, DashcamViewExporter.MAX_OUTPUT_WIDTH);
        int h = Math.round((float) w * g.height / g.width);
        long seconds = (plan.to - plan.from + 999) / 1000;
        long video = (long) DashcamViewExporter.bitrateFor(w, h, 30) / 8L * seconds;
        return video + video / 10 + 24_000L * seconds + 1_048_576L;
    }

    /** replay_<range end>.mp4 in the live recordings folder; refuses when the volume can't take it. */
    private static File prepareOutput(Plan plan) throws Refusal {
        long need = estimateBytes(plan);
        File dir;
        long free;
        try {
            StorageManager storage = StorageManager.getInstance();
            dir = storage.getLiveRecordingsDirIfMounted();
            // Secondary writer: never flips the recorder's fallback state, never deletes recordings for room.
            dir = storage.resolveTargetWithEnospcFallback(dir, need + RESERVE_BYTES, false);
            if (dir == null) throw new Refusal("storage", "No recordings storage is available.");
            if (!RemovableStorageFs.exists(dir) && !RemovableStorageFs.mkdirs(dir) && !RemovableStorageFs.exists(dir)) {
                throw new Refusal("storage", "The recordings folder can't be written.");
            }
            free = RemovableStorageFs.statFsAvailableBytes(dir.getAbsolutePath());
        } catch (Refusal r) {
            throw r;
        } catch (Throwable t) {
            throw new Refusal("storage", "The recordings storage can't be read.");
        }
        if (free < need + RESERVE_BYTES) {
            throw new Refusal("storage_full", "Not enough free space: the export needs about "
                    + StorageManager.formatSize(need + RESERVE_BYTES) + " free (including room kept for recording), "
                    + StorageManager.formatSize(Math.max(0L, free)) + " is free. Delete some recordings and try again.");
        }
        // Named by the range end like an instant replay (stamped at save time = clip end).
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(plan.to));
        for (int suffix = 0; suffix < 100; suffix++) {
            String name = "replay_" + stamp + (suffix == 0 ? "" : "_" + suffix) + ".mp4";
            File candidate = new File(dir, name);
            if (!RemovableStorageFs.exists(candidate) && !RemovableStorageFs.exists(tmpOf(candidate))) {
                return candidate;
            }
        }
        throw new Refusal("storage", "Couldn't pick an output file name.");
    }

    private static File tmpOf(File output) {
        return new File(output.getAbsolutePath() + ".tmp");
    }

    private static void launch(final Job job, final Plan plan, final File output) {
        final File tmp = tmpOf(output);
        final float strength;
        float s;
        try {
            s = com.overdrive.app.config.UnifiedConfigManager.getRectifyStrength() / 100f;
        } catch (Throwable t) {
            s = 0f;
        }
        strength = s;
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
            } catch (Throwable ignored) {
            }
            run(job, plan, output, tmp, strength);
        }, "DashcamViewExport");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        job.thread = t;
        t.start();
    }

    private static void run(Job job, Plan plan, File output, File tmp, float strength) {
        final long[] nextCheck = {0L, 0L};
        final String[] stop = {null};
        DashcamViewExporter.Listener listener = new DashcamViewExporter.Listener() {
            @Override
            public String stopReason() {
                if (job.cancel) return "cancelled";
                if (stop[0] != null) return stop[0];
                long now = android.os.SystemClock.elapsedRealtime();
                if (now < nextCheck[0]) return null;
                nextCheck[0] = now + 250L;
                String gate = busyReason();
                if (gate != null) {
                    stop[0] = gate;
                    return gate;
                }
                if (now < nextCheck[1]) return null;
                nextCheck[1] = now + 2_000L;
                try {
                    File dir = tmp.getParentFile();
                    if (dir != null && RemovableStorageFs.statFsAvailableBytes(dir.getAbsolutePath())
                            < MIN_FREE_WHILE_WRITING) {
                        stop[0] = "storage_full";
                    }
                } catch (Throwable ignored) {
                }
                return stop[0];
            }

            @Override
            public void onProgress(float fraction) {
                job.progress = Math.max(0f, Math.min(0.99f, fraction));
            }
        };
        boolean ok = false;
        try {
            logger.info("Dashcam view export " + job.from + ".." + job.to + " (" + plan.leaders.size()
                    + " grid, " + plan.followers.size() + " windshield clips) -> " + output.getName());
            DashcamViewExporter.Result r = DashcamViewExporter.run(plan.leaders, plan.followers,
                    plan.from, plan.to, strength, tmp, listener);
            finish(job, output, tmp, plan);
            ok = true;
            logger.info("Dashcam view export saved " + output.getName() + " " + r.width + "x" + r.height
                    + " frames=" + r.frames + " audio=" + r.audio);
        } catch (DashcamViewExporter.ExportException e) {
            job.code = e.code;
            job.error = failureMessage(e.code, e.getMessage());
            logger.warn("Dashcam view export failed (" + e.code + "): " + e.getMessage());
        } catch (Throwable t) {
            job.code = "failed";
            job.error = failureMessage("failed", t.getMessage());
            logger.warn("Dashcam view export failed: " + t);
        } finally {
            if (!ok) {
                RemovableStorageFs.delete(tmp);
                job.state = "cancelled".equals(job.code) ? "cancelled" : "failed";
            }
        }
    }

    private static String failureMessage(String code, String detail) {
        if ("cancelled".equals(code)) return "Export cancelled.";
        if ("acc_on".equals(code) || "cameras_busy".equals(code) || "busy".equals(code)) {
            return "Export stopped: " + gateMessage(code);
        }
        if ("storage_full".equals(code)) return "Export stopped: the storage is almost full.";
        return "Export failed" + (detail != null && !detail.isEmpty() ? ": " + detail : ".");
    }

    private static void finish(Job job, File output, File tmp, Plan plan) throws Exception {
        writeSidecar(output);
        if (!RemovableStorageFs.renameTo(tmp, output)) {
            deleteSidecar(output);
            throw new DashcamViewExporter.ExportException("storage", "Couldn't save the exported file.");
        }
        try {
            RecordingSpanHints.record(output.getName(), "replay", plan.from);
        } catch (Throwable ignored) {
        }
        try {
            StorageManager.getInstance().onFileSaved(output);
        } catch (Throwable t) {
            logger.warn("Export storage accounting failed: " + t.getMessage());
        }
        try {
            RecordingsIndex index = RecordingsIndex.getInstance();
            index.upsert(output);
            RecordingsIndex.RecordingRef ref = index.resolveByFilename(output.getName());
            if (ref != null) job.recordingId = ref.id;
        } catch (Throwable t) {
            logger.warn("Export index update failed: " + t.getMessage());
        }
        job.progress = 1f;
        job.state = "done";
    }

    /** Same shape as a dashcam-layout clip's layout-only sidecar, so players use the dashcam zoom regions. */
    private static void writeSidecar(File mp4) {
        File json = sidecarOf(mp4);
        File tmp = new File(json.getAbsolutePath() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("version", 3);
            root.put("layout", "dashcam");
            try (java.io.Writer w = new java.io.OutputStreamWriter(RemovableStorageFs.openOutput(tmp),
                    StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            if (!RemovableStorageFs.renameTo(tmp, json)) RemovableStorageFs.delete(tmp);
            else RemovableStorageFs.setReadable(json, true, false);
        } catch (Throwable t) {
            RemovableStorageFs.delete(tmp);
            logger.warn("Export sidecar write failed: " + t.getMessage());
        }
    }

    private static void deleteSidecar(File mp4) {
        RemovableStorageFs.delete(sidecarOf(mp4));
    }

    private static File sidecarOf(File mp4) {
        String name = mp4.getName();
        return new File(mp4.getParentFile(), name.substring(0, name.length() - 4) + ".json");
    }
}
