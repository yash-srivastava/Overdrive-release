package com.overdrive.app.parking;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import com.overdrive.app.ai.AssetContext;
import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.camera.CameraConfigResolver;
import com.overdrive.app.camera.CameraPreviewHelper;
import com.overdrive.app.camera.CameraRole;
import com.overdrive.app.camera.CameraVirtualView;
import com.overdrive.app.camera.PanoramicSlice;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.geo.GeocodingResolver;
import com.overdrive.app.geo.PlaceResult;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.parking.signage.TextOcrBackend;
import com.overdrive.app.parking.signage.TfliteTextOcrBackend;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.DetectionBaseline;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.SafeLocationManager;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;
import com.overdrive.app.surveillance.SurveillanceSchedule;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Production {@link ParkingEnvironment}: thin, read-only adapters over the
 * daemon's existing singletons. Every method is defensive — the feature must
 * degrade (a null snapshot, an unknown GPS quality) rather than throw into
 * the parking worker.
 */
public final class DaemonParkingEnvironment implements ParkingEnvironment {

    /** Small footprint (≤ storageCapMb), always on internal storage so a USB swap never orphans sessions. */
    static final String PARKING_DIR = "/storage/emulated/0/Overdrive/parking";

    private final Context context;
    private volatile BydDataCollector.DoorStateListener doorListener;
    private final ImageOps imageOps = new AndroidImageOps();

    public DaemonParkingEnvironment(Context context) {
        this.context = context;
    }

    // ==================== CLOCKS ====================

    @Override public long nowMs() { return System.currentTimeMillis(); }

    @Override public long nowElapsedMs() { return SystemClock.elapsedRealtime(); }

    // ==================== LOCATION ====================

    @Override
    public GpsFix readGpsFix() {
        try {
            GpsMonitor.GpsFixSnapshot f = GpsMonitor.getInstance().getFixSnapshot();
            if (f == null) return null;
            return new GpsFix(f.latitude, f.longitude, f.accuracy, f.fixElapsedMs, f.lastUpdate, f.loadedFromCache);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void resolvePlaceAsync(double lat, double lng, Consumer<Place> callback) {
        String flow = geocodingFlow();
        if (flow == null) return;
        try {
            GeocodingResolver.getInstance().resolveAsync(lat, lng, flow, new GeocodingResolver.ResolveCallback() {
                @Override public void onResolved(PlaceResult r) {
                    if (r == null) return;
                    String src = r.source != null ? r.source.name() : null;
                    callback.accept(new Place(r.shortLabel(), r.displayName, src));
                }
            });
        } catch (Throwable t) {
            log("Parking geocode dispatch failed: " + t.getMessage());
        }
    }

    /**
     * Which geocoding flow (and therefore which enabled / allowOnline pair) a
     * parking lookup runs under, or null for "no lookup":
     * <ul>
     *   <li>{@code geocoding.parking} present ⇒ the user chose explicitly on the
     *       Parking settings card: "parking" when enabled, else nothing.</li>
     *   <li>absent ⇒ inherit the sentry or dashcam choice, whichever is on
     *       (the pre-toggle behaviour, so existing installs see no change).</li>
     * </ul>
     * Mirrors QualitySettingsApiHandler.parkingGeocodingView.
     */
    static String geocodingFlow() {
        try {
            JSONObject park = UnifiedConfigManager.getGeocoding().optJSONObject("parking");
            if (park != null) return park.optBoolean("enabled", false) ? "parking" : null;
            if (UnifiedConfigManager.isGeocodingEnabledForFlow("surveillance")) return "surveillance";
            if (UnifiedConfigManager.isGeocodingEnabledForFlow("recording")) return "recording";
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public String currentSafeZoneName() {
        try {
            SafeLocationManager m = SafeLocationManager.getInstance();
            return m.isInSafeZone() ? m.getCurrentZoneName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== SENTRY STATE ====================

    @Override
    public String sentryState() {
        try {
            if (!UnifiedConfigManager.isSurveillanceEnabled()) return ParkingSession.SENTRY_SURVEILLANCE_OFF;
            if (UnifiedConfigManager.isVehicleOnOnlyMode()) return ParkingSession.SENTRY_VEHICLE_ON_ONLY;
            if (SafeLocationManager.getInstance().isInSafeZone()) return ParkingSession.SENTRY_SUPPRESSED_SAFE_ZONE;
            SurveillanceSchedule schedule = UnifiedConfigManager.getSurveillanceSchedule();
            if (schedule != null && !schedule.isActiveNow()) return ParkingSession.SENTRY_SUPPRESSED_SCHEDULE;
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            if (p == null || !p.isRunning()) {
                return CameraDaemon.isSurveillanceEnabled()
                        ? ParkingSession.SENTRY_PIPELINE_DOWN
                        : ParkingSession.SENTRY_LOCK_WAIT;
            }
            SurveillanceEngineGpu sentry = p.getSentry();
            if (p.isSurveillanceMode() && sentry != null && sentry.isActive()) return ParkingSession.SENTRY_ARMED;
            return ParkingSession.SENTRY_LOCK_WAIT;
        } catch (Throwable t) {
            return ParkingSession.SENTRY_UNKNOWN;
        }
    }

    @Override
    public boolean isPipelineRunning() {
        try {
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            return p != null && p.isRunning() && p.getCamera() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isSentryArmed() {
        try {
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            if (p == null || !p.isSurveillanceMode()) return false;
            SurveillanceEngineGpu s = p.getSentry();
            return s != null && s.isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isEventRecording() {
        try {
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            SurveillanceEngineGpu s = p == null ? null : p.getSentry();
            return s != null && s.isRecording();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isAccOn() {
        try { return AccMonitor.isAccOn(); } catch (Throwable t) { return false; }
    }

    @Override
    public boolean isAccStateAuthoritative() {
        try { return AccMonitor.isAccStateAuthoritative(); } catch (Throwable t) { return true; }
    }

    @Override
    public boolean isCharging() {
        try { return ChargingDetector.getInstance().isCharging(); } catch (Throwable t) { return false; }
    }

    @Override
    public double readSocPercent() {
        try {
            com.overdrive.app.byd.BydVehicleData d = BydDataCollector.getInstance().getData();
            double soc = d != null ? d.socPercent : Double.NaN;
            // Same validity convention the charging/SoH code uses: 0 and out-of-range
            // readings mean "no data", never a real state of charge.
            return (soc > 0 && soc <= 100) ? soc : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    @Override
    public double readRemainKwh() {
        // DiLink 5's remainKwh also carries SoC % and SoC / 10 readings, so its bookends use SoC.
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) return Double.NaN;
        try {
            com.overdrive.app.byd.BydVehicleData d = BydDataCollector.getInstance().getData();
            double kwh = d != null ? d.remainKwh : Double.NaN;
            // The collector's own admission window for this channel (see its
            // getEVRemainingBatteryPower / getBatteryRemainPowerEV gates).
            return (kwh >= 0.5 && kwh <= 200.0) ? kwh : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    @Override
    public double estimateEnergyKwh(double socPercent) {
        try {
            // Same conversion the charging session pricing uses (nominal × SOH,
            // SOH floored, NaN when the pack capacity is unknown).
            com.overdrive.app.abrp.SohEstimator soh = com.overdrive.app.monitor
                    .SocHistoryDatabase.getInstance().getSohEstimator();
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot cap =
                    soh != null ? soh.getCapacitySohSnapshot() : null;
            return com.overdrive.app.charging.SessionEnergyResolver.socEstimateKwh(
                    socPercent,
                    cap != null ? cap.getNominalCapacityKwh() : 0,
                    cap != null && cap.hasDisplaySoh() ? cap.getDisplaySoh() : Double.NaN);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    @Override
    public Boolean gearInPark() {
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                    com.overdrive.app.monitor.GearMonitor.getInstance();
            // isActive() is the freshness gate: the 200 ms poller is running
            // (and, on DiLink5, the bridged observation is recent). Without it
            // getCurrentGear() can return its cold GEAR_P default — which must
            // read as "unknown", not "in P".
            if (!gm.isActive()) return null;
            int g = gm.getCurrentGear();
            if (g < com.overdrive.app.monitor.GearMonitor.GEAR_P
                    || g > com.overdrive.app.monitor.GearMonitor.GEAR_S) return null;
            return g == com.overdrive.app.monitor.GearMonitor.GEAR_P;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public List<Actor> lastActors() {
        try {
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            SurveillanceEngineGpu s = p == null ? null : p.getSentry();
            List<Actor> a = s == null ? null : s.getLastActors();
            return a == null ? Collections.<Actor>emptyList() : a;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // ==================== CAMERA ====================

    @Override
    public byte[] captureQuadrantJpeg(int quadrant) {
        PanoramicSlice slice = sliceForQuadrant(quadrant);
        if (slice == null) return null;
        return CameraPreviewHelper.capturePanoramicSliceJpeg(slice);
    }

    /** Logical quadrant (0=front,1=right,2=rear,3=left) → physical slice under the active mapping. */
    static PanoramicSlice sliceForQuadrant(int quadrant) {
        CameraRole role;
        CameraVirtualView view;
        switch (quadrant) {
            case 0: role = CameraRole.PANO_FRONT; view = CameraVirtualView.FRONT; break;
            case 1: role = CameraRole.PANO_RIGHT; view = CameraVirtualView.RIGHT; break;
            case 2: role = CameraRole.PANO_REAR;  view = CameraVirtualView.REAR;  break;
            case 3: role = CameraRole.PANO_LEFT;  view = CameraVirtualView.LEFT;  break;
            default: return null;
        }
        try {
            PanoramicSlice s = CameraConfigResolver.resolve().getSliceForRole(role);
            if (s != null) return s;
        } catch (Throwable ignored) {}
        return PanoramicSlice.fromLegacyView(view);
    }

    @Override
    public int rectifyStrength() {
        try { return UnifiedConfigManager.getRectifyStrength(); } catch (Throwable t) { return 0; }
    }

    @Override
    public File parkingBaseDir() {
        File d = new File(PARKING_DIR);
        if (!RemovableStorageFs.isDirectory(d) && RemovableStorageFs.mkdirs(d)) {
            // Other UIDs (Telegram bot daemon) read the stills: keep the tree traversable.
            try { RemovableStorageFs.setReadable(d, true, false); RemovableStorageFs.setExecutable(d, true, false); } catch (Throwable ignored) {}
        }
        File nomedia = new File(d, ".nomedia");
        if (!RemovableStorageFs.exists(nomedia)) {
            try { RemovableStorageFs.createNewFile(nomedia); } catch (Throwable ignored) {}
        }
        return d;
    }

    // ==================== OBSERVERS ====================

    @Override
    public void setDoorListener(DoorListener listener) {
        BydDataCollector collector;
        try { collector = BydDataCollector.getInstance(); } catch (Throwable t) { return; }
        BydDataCollector.DoorStateListener old = doorListener;
        if (old != null) {
            try { collector.removeDoorStateListener(old); } catch (Throwable ignored) {}
            doorListener = null;
        }
        if (listener == null) return;
        BydDataCollector.DoorStateListener l = (area, state) -> {
            if (state != BodyworkConstants.STATE_OPEN && state != BodyworkConstants.STATE_CLOSED) return;
            if (isAccOn()) return;
            try { listener.onDoor(state == BodyworkConstants.STATE_OPEN); } catch (Throwable ignored) {}
        };
        doorListener = l;
        try { collector.addDoorStateListener(l); } catch (Throwable ignored) {}
    }

    @Override
    public void setBaselineListener(DetectionBaseline.Listener listener) {
        DetectionBaseline.setGlobalListener(listener);
    }

    @Override
    public void publish(NotificationEvent event) {
        NotificationBus.get().publish(event);
    }

    @Override
    public int countSentryEvents(long fromMs, long toMs, boolean criticalOnly) {
        try {
            RecordingsIndex idx = RecordingsIndex.getInstance();
            if (!idx.isAvailable()) return -1;
            RecordingsIndex.Filter f = new RecordingsIndex.Filter();
            f.type = "sentry";
            f.fromMs = fromMs;
            f.toMs = toMs;
            if (criticalOnly) {
                f.severities = new HashSet<>();
                f.severities.add("CRITICAL");
            }
            return idx.queryCount(f);
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public String signAssetToken(String subject, long ttlSec) {
        try { return AuthManager.signThumbToken(subject, ttlSec); } catch (Throwable t) { return null; }
    }

    @Override
    public ImageOps imageOps() { return imageOps; }

    @Override
    public void log(String message) {
        try { CameraDaemon.log("[Parking] " + message); } catch (Throwable ignored) {}
    }

    // ==================== v2 SIGNAGE ====================

    @Override
    public TextOcrBackend openSignageOcr() {
        return TfliteTextOcrBackend.openIfAvailable(modelContext(context), this::log);
    }

    /**
     * A Context whose assets are OUR APK's. Under app_process the daemon's
     * shared context can be a com.android.shell package context (its assets
     * are the shell's, not ours), so — exactly like the YOLO detector — prefer
     * the APK-backed AssetManager the daemon builds from its CLASSPATH, then
     * the live shared context (it may have been recreated after ACC-on), then
     * whatever was handed to us at construction.
     */
    public static Context modelContext(Context fallback) {
        try {
            AssetManager apk = CameraDaemon.getApkAssets();
            if (apk != null) return new AssetContext(apk);
        } catch (Throwable ignored) {}
        try {
            Context live = CameraDaemon.getAppContext();
            if (live != null) return live;
        } catch (Throwable ignored) {}
        return fallback;
    }

    @Override
    public File recentDriveClip(long sessionStartMs) {
        try {
            RecordingsIndex idx = RecordingsIndex.getInstance();
            if (!idx.isAvailable()) return null;
            RecordingsIndex.Filter f = new RecordingsIndex.Filter();
            f.types = new HashSet<>(java.util.Arrays.asList("normal", "oemDashcam", "proximity"));
            f.fromMs = sessionStartMs - 20L * 60_000L;
            f.toMs = sessionStartMs + 60_000L;
            for (JSONObject row : idx.queryRecordings(f, 3, 0)) {
                if (!row.optBoolean("available", true)) continue;
                String path = row.optString("path", null);
                if (path == null) continue;
                File file = new File(path);
                if (!RemovableStorageFs.isFile(file) || RemovableStorageFs.length(file) == 0) continue;
                // A fresh fix in the newest clip = open sky after any older clip: no garage approach.
                return recordedUnderSky(sidecarOf(file)) ? null : file;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public List<byte[]> extractTailFrames(File mp4, int count, long tailMs) {
        List<byte[]> out = new ArrayList<>();
        if (mp4 == null || !RemovableStorageFs.isFile(mp4) || count <= 0) return out;
        boolean grid = isCameraGrid(mp4);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            RemovableStorageFs.setDataSource(mmr, mp4);
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durStr == null ? 0 : Long.parseLong(durStr);
            if (durationMs <= 0) return out;
            long span = Math.min(tailMs, durationMs);
            long start = durationMs - span;
            long lastSig = 0L;
            for (int i = 0; i < count; i++) {
                long tMs = start + (span * (i + 1)) / (count + 1);
                Bitmap frame = null;
                try {
                    frame = mmr.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) continue;
                    // Nearby times can resolve to the same sync frame; a repeat would double its votes.
                    long sig = pixelSignature(frame);
                    if (i > 0 && sig == lastSig) continue;
                    lastSig = sig;
                    if (grid && frame.getWidth() >= 1280 && frame.getHeight() >= 720) {
                        int qw = frame.getWidth() / 2, qh = frame.getHeight() / 2;
                        for (int q = 0; q < 4; q++) {
                            Bitmap view = Bitmap.createBitmap(frame, (q % 2) * qw, (q / 2) * qh, qw, qh);
                            try {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream(300_000);
                                view.compress(Bitmap.CompressFormat.JPEG, 92, bos);
                                out.add(bos.toByteArray());
                            } finally {
                                if (view != frame) view.recycle();
                            }
                        }
                        continue;
                    }
                    Bitmap use = frame;
                    if (frame.getWidth() > 1600) {
                        float s = 1600f / frame.getWidth();
                        use = Bitmap.createScaledBitmap(frame, 1600, Math.round(frame.getHeight() * s), true);
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(200_000);
                    use.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                    out.add(bos.toByteArray());
                    if (use != frame) use.recycle();
                } finally {
                    if (frame != null) frame.recycle();
                }
            }
        } catch (Throwable t) {
            log("Drive-clip frame extract failed: " + t.getMessage());
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Our 4-camera recordings are a 2×2 grid unless recorded in the dashcam layout. */
    static boolean isCameraGrid(File mp4) {
        String name = mp4.getName();
        if (!name.startsWith("cam_") && !name.startsWith("proximity_")) return false;
        String layout = null;
        JSONObject j = sidecarOf(mp4);
        if (j != null) {
            if (j.has("lens")) return true;
            layout = j.optString("layout", "standard");
        }
        if (layout == null) {
            try { layout = UnifiedConfigManager.getRecording().optString("recordingLayout", "standard"); }
            catch (Throwable t) { layout = "standard"; }
        }
        return !"dashcam".equals(layout);
    }

    /** The clip's JSON sidecar, or null. */
    static JSONObject sidecarOf(File mp4) {
        try {
            File sidecar = new File(mp4.getParentFile(), mp4.getName().replace(".mp4", ".json"));
            if (!RemovableStorageFs.isFile(sidecar) || RemovableStorageFs.length(sidecar) >= 512_000L) return null;
            return new JSONObject(new String(
                    RemovableStorageFs.readAllBytes(sidecar), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the clip's last GPS fix was under a minute old: open sky, not a garage. */
    static boolean recordedUnderSky(JSONObject sidecar) {
        JSONObject geo = sidecar == null ? null : sidecar.optJSONObject("geo");
        if (geo == null) return false;
        JSONObject fix = geo.optJSONObject("end");
        if (fix == null) fix = geo.optJSONObject("start");
        if (fix == null || !fix.has("ageMs")) return false;
        long age = fix.optLong("ageMs", -1L);
        return age >= 0L && age < 60_000L;
    }

    /** Cheap identity for a decoded frame: a sparse pixel sample. */
    private static long pixelSignature(Bitmap b) {
        long h = 1125899906842597L;
        int w = b.getWidth(), ht = b.getHeight();
        for (int y = 1; y < 24; y++) {
            for (int x = 1; x < 32; x++) {
                h = 31 * h + b.getPixel(x * w / 32, y * ht / 24);
            }
        }
        return h;
    }

    // ==================== IMAGE OPS ====================

    /** Bitmap-backed image helpers; all decode failures return null / 0. */
    static final class AndroidImageOps implements ImageOps {

        @Override
        public byte[] composeMosaic(byte[][] quadrantJpegs, int tileW, int tileH, int quality) {
            if (quadrantJpegs == null || tileW <= 0 || tileH <= 0) return null;
            Bitmap canvasBmp = Bitmap.createBitmap(tileW * 2, tileH * 2, Bitmap.Config.RGB_565);
            try {
                Canvas canvas = new Canvas(canvasBmp);
                canvas.drawColor(0xFF101418);
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                boolean any = false;
                for (int q = 0; q < 4; q++) {
                    int col = (q == 0 || q == 2) ? 0 : 1;   // front/rear left column
                    int row = (q == 0 || q == 1) ? 0 : 1;   // front/right top row
                    Rect dst = new Rect(col * tileW, row * tileH, (col + 1) * tileW, (row + 1) * tileH);
                    byte[] jpeg = q < quadrantJpegs.length ? quadrantJpegs[q] : null;
                    Bitmap tile = null;
                    if (jpeg != null && jpeg.length > 0) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;   // 1280×960 → 640×480: exactly the tile size on legacy
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        tile = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                    }
                    if (tile == null) {
                        // No frame for this camera: a marked placeholder, so the
                        // photo reads "not captured" rather than "camera saw black".
                        drawMissingTile(canvas, dst);
                        continue;
                    }
                    try {
                        canvas.drawBitmap(tile, null, dst, paint);
                        any = true;
                    } finally {
                        tile.recycle();
                    }
                }
                if (!any) return null;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(300_000);
                canvasBmp.compress(Bitmap.CompressFormat.JPEG, Math.max(50, Math.min(95, quality)), bos);
                return bos.toByteArray();
            } catch (Throwable t) {
                return null;
            } finally {
                canvasBmp.recycle();
            }
        }

        /**
         * Placeholder for a camera that yielded no frame: neutral grey with a
         * crossed-out lens glyph, drawn with primitives only (no text — the
         * daemon has no default typeface and Canvas.drawText can abort it).
         */
        private static void drawMissingTile(Canvas canvas, Rect dst) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF2A3038);
            canvas.drawRect(dst, p);
            float cx = dst.exactCenterX(), cy = dst.exactCenterY();
            float r = Math.min(dst.width(), dst.height()) * 0.12f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, r * 0.18f));
            p.setColor(0xFF6B7683);
            // Lens body + ring, then the diagonal slash.
            canvas.drawRoundRect(cx - r * 1.6f, cy - r * 1.1f, cx + r * 1.6f, cy + r * 1.1f, r * 0.3f, r * 0.3f, p);
            canvas.drawCircle(cx, cy, r * 0.55f, p);
            p.setColor(0xFFB0433D);
            canvas.drawLine(cx - r * 2.0f, cy + r * 1.6f, cx + r * 2.0f, cy - r * 1.6f, p);
        }

        /** Mean luma below this AND no pixel brighter than {@link #BLANK_MAX_LUMA} ⇒ blank. */
        static final int BLANK_MEAN_LUMA = 6;
        static final int BLANK_MAX_LUMA = 24;

        @Override
        public boolean isBlank(byte[] jpeg) {
            if (jpeg == null || jpeg.length == 0) return false;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;   // 1280×960 → 160×120: 19 k pixels, sub-millisecond
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = null;
            try {
                b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                if (b == null) return false;
                int w = b.getWidth(), h = b.getHeight();
                int[] px = new int[w * h];
                b.getPixels(px, 0, w, 0, 0, w, h);
                long sum = 0;
                int max = 0;
                for (int p : px) {
                    int l = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
                    sum += l;
                    if (l > max) max = l;
                }
                return px.length > 0 && sum / px.length < BLANK_MEAN_LUMA && max < BLANK_MAX_LUMA;
            } catch (Throwable t) {
                return false;
            } finally {
                if (b != null) b.recycle();
            }
        }

        @Override
        public double frameScore(byte[] jpeg, float[] regionNorm) {
            if (jpeg == null || jpeg.length == 0) return 0.0;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = null;
            try {
                b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
                if (b == null) return 0.0;
                int w = b.getWidth(), h = b.getHeight();
                int x0 = 0, y0 = 0, x1 = w, y1 = h;
                if (regionNorm != null && regionNorm.length == 4 && regionNorm[2] > 0f && regionNorm[3] > 0f) {
                    x0 = clamp(Math.round(regionNorm[0] * w), 0, w - 2);
                    y0 = clamp(Math.round(regionNorm[1] * h), 0, h - 2);
                    x1 = clamp(Math.round((regionNorm[0] + regionNorm[2]) * w), x0 + 2, w);
                    y1 = clamp(Math.round((regionNorm[1] + regionNorm[3]) * h), y0 + 2, h);
                }
                int rw = x1 - x0, rh = y1 - y0;
                if (rw < 4 || rh < 4) return 0.0;
                int[] px = new int[rw * rh];
                b.getPixels(px, 0, rw, x0, y0, rw, rh);
                // Luma plane
                int[] lum = new int[rw * rh];
                int clipped = 0;
                for (int i = 0; i < px.length; i++) {
                    int p = px[i];
                    int l = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
                    lum[i] = l;
                    if (l < 16 || l > 239) clipped++;
                }
                // Gradient energy (mean |dx| + |dy|) ⇒ sharpness proxy, cheap and monotonic.
                double energy = 0.0;
                long n = 0;
                for (int y = 1; y < rh - 1; y++) {
                    int row = y * rw;
                    for (int x = 1; x < rw - 1; x++) {
                        int idx = row + x;
                        energy += Math.abs(lum[idx + 1] - lum[idx - 1]) + Math.abs(lum[idx + rw] - lum[idx - rw]);
                        n++;
                    }
                }
                if (n == 0) return 0.0;
                double sharp = energy / n / 255.0;                    // ~0..1
                double exposure = 1.0 - (double) clipped / px.length; // 1 = no clipping
                return sharp * exposure * 100.0;
            } catch (Throwable t) {
                return 0.0;
            } finally {
                if (b != null) b.recycle();
            }
        }

        private static int clamp(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }
}
