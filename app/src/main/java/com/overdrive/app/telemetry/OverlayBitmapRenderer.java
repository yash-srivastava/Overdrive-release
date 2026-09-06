package com.overdrive.app.telemetry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.telemetry.TelemetryFields.Field;
import com.overdrive.app.util.DaemonFonts;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Telemetry overlay renderer with solid PNG icons tinted via alpha extraction
 * plus Canvas-drawn vector glyphs for the newer optional fields (battery, 12V,
 * head-beams, location). Dark semi-transparent background, white text, colored
 * icons.
 *
 * <p>Which fields are drawn is driven by {@link #setActiveFields}; each
 * recording flow (ACC-on trip / ACC-off surveillance / OEM dashcam) owns its
 * own renderer instance and pushes its own selection. The default selection is
 * {@link TelemetryFields#legacyDefault()} — the exact eight fields the overlay
 * drew before this feature — so an install that never touches the setting is
 * visually unchanged.
 *
 * <p>The slow-changing new signals (SOC %, 12V, beams, GPS) are NOT part of the
 * hot {@link TelemetrySnapshot}. They are read at raster time (2 Hz) straight
 * from the daemon's already-running {@code BydDataCollector} / {@code GpsMonitor}
 * singletons — lock-free snapshot reads, and ONLY when the corresponding field
 * is enabled — so an unselected field costs nothing.
 *
 * Performance: All geometry objects (Rect, RectF, Date, Path) and ColorFilters
 * are pre-allocated and reused across frames to eliminate per-frame GC pressure.
 */
public class OverlayBitmapRenderer {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 80;
    private static final String ICON_DIR = "/data/local/tmp/overlay/";
    private static final int ICON_SIZE = 40;
    private static final double KM_TO_MI = 0.621371;

    // Bar geometry. The bar auto-sizes to its content but never shrinks below
    // the legacy default width, and stays centered — so the legacy field set
    // reproduces the pre-feature layout, while a larger selection grows the bar
    // symmetrically up to the bitmap edges.
    private static final float DEFAULT_BAR_WIDTH = 880f;
    private static final float BAR_EDGE_MARGIN = 8f;   // min gap bitmap edge → bar
    private static final float SEGMENT_GAP = 8f;       // gap between content segments
    private static final float TURN_SLOT = ICON_SIZE + 20f; // space a framing arrow reserves

    // ---- VIN segment ----
    // A 17-character VIN on ONE line at valuePaint's size 24 measures ~205-245px
    // — by far the widest segment (speed ~126, timestamp 96). With every field
    // enabled that pushes contentW past the squash threshold (barW pins at
    // WIDTH-2*BAR_EDGE_MARGIN, so with turn signals on the ceiling is
    // contentW > 1144), and renderFrame's no-cutoff path would horizontally
    // scale the WHOLE bar ~0.84 — nothing clips, but every glyph narrows.
    // So we stack the VIN on two lines at the same size the other two-line
    // segments use (TIMESTAMP / LOCATION, size 16), which drops the segment to
    // ~90px — on par with the timestamp — and keeps scaleX at 1.0 even with the
    // full field set. VIN_LINE1_CHARS is the first-line length (9+8 for a
    // standard 17-char VIN); a shorter/longer string splits proportionally.
    private static final int VIN_LINE1_CHARS = 9;
    private static final float VIN_TEXT_SIZE = 16f;
    // Hard character cap across BOTH lines. A real VIN is 17, but the fallback
    // getAutoVIN getter returns a hashed wrapper on some trims whose length we
    // don't control — and past ~34 chars the segment gets wide enough to push
    // contentW back over the squash threshold, re-introducing the very
    // whole-bar distortion the two-line split exists to avoid. Cap so an
    // unexpected string degrades to a truncated tag instead of narrowing every
    // other field's glyphs.
    private static final int VIN_MAX_CHARS = 34;

    // Fixed left→right draw order of the content segments (turn signals frame
    // the bar separately and are not in this list). Iterating a constant array
    // keeps the measure/draw passes allocation-free.
    private static final Field[] CONTENT_ORDER = {
        Field.SPEED,
        Field.GEAR,
        Field.BRAKE_PEDAL,
        Field.ACCEL_PEDAL,
        Field.SEATBELT_DRIVER,
        Field.SEATBELT_PASSENGER,
        Field.LOW_BEAM,
        Field.HIGH_BEAM,
        Field.BATTERY_PERCENT,
        Field.VOLTAGE_12V,
        Field.LOCATION,
        Field.VIN,
        Field.TIMESTAMP,
    };

    private final DaemonLogger logger;
    private final OverlayDoubleBuffer doubleBuffer;
    private final Paint bgPaint, speedPaint, unitPaint, gearPaint;
    private final Paint iconPaint, labelPaint, timePaint;
    private final Paint valuePaint, glyphStroke, glyphFill;
    private final SimpleDateFormat dateFmt, timeFmt;

    // Pre-extracted alpha masks (solid icons → clean stencils)
    private Bitmap alphaPedal, alphaLeft, alphaRight, alphaBelt;

    // Pre-allocated geometry objects — reused every frame to avoid GC pressure
    private final RectF bgRect = new RectF();
    private final Rect iconSrcRect = new Rect();
    private final RectF iconDstRect = new RectF();
    private final RectF glyphRect = new RectF();
    private final Path glyphPath = new Path();
    private final Date reusableDate = new Date();

    // Reused per-segment measured widths, indexed by Field.ordinal(). Populated
    // in the measure pass and consumed in the draw pass so measureText runs once
    // per field per frame.
    private final float[] segWidth = new float[Field.values().length];

    // Which fields to draw. Immutable + volatile: the raster worker reads it, an
    // API/config thread swaps it. Defaults to the legacy set so behavior is
    // identical until a caller opts into a different selection.
    private volatile TelemetryFields activeFields = TelemetryFields.legacyDefault();

    // Cached PorterDuffColorFilters — keyed by color int, avoids allocation per icon per frame
    private final Map<Integer, PorterDuffColorFilter> colorFilterCache = new HashMap<>();

    // ==================== OFF-GL-THREAD RASTER WORKER ====================
    // renderFrame() is a software android.graphics.Canvas raster with per-icon
    // setShadowLayer Gaussian blur — the expensive HALF of the overlay pass.
    // It used to run INLINE on the encoder GL thread (every ~8th frame), adding
    // a recurring CPU spike right before eglSwap. We move it onto a dedicated
    // low-priority worker that rasters the BACK bitmap at ~2 Hz; the GL thread
    // then only swapAndGetFront() + texSubImage2D + composites (the cheap half
    // that must touch the GL context). OverlayDoubleBuffer already guarantees
    // the worker writes BACK while GL reads FRONT with a synchronized swap, so
    // there is no shared-bitmap race. start/stop are idempotent + join-safe
    // because setOverlayEnabled is called from IPC/API/daemon threads that can
    // race the recorder's release().
    private static final long WORKER_PERIOD_MS = 500L;  // 2 Hz, matches the old stride cadence
    private Thread workerThread;
    private volatile boolean workerRunning = false;
    private volatile TelemetryDataCollector workerCollector;
    // Monotonic tick owned by the worker, driving the turn-signal/seatbelt blink
    // (blink = (tick/3)%2). Must NOT reuse the GL frame counter — the worker is
    // decoupled from the render cadence now.
    private int workerTick = 0;

    public OverlayBitmapRenderer() {
        logger = DaemonLogger.getInstance("OverlayRenderer");
        doubleBuffer = new OverlayDoubleBuffer(WIDTH, HEIGHT);
        dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

        bgPaint = mp(Color.argb(160, 0, 0, 0), Paint.Style.FILL, 0);
        speedPaint = mp(Color.WHITE, Paint.Style.FILL, 48);
        unitPaint = mp(0xFFCCCCCC, Paint.Style.FILL, 18);
        gearPaint = mp(Color.WHITE, Paint.Style.FILL, 48);
        iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        labelPaint = mp(0xFFCCCCCC, Paint.Style.FILL, 13);
        timePaint = mp(Color.WHITE, Paint.Style.FILL, 20);
        // Value text for the new fields (battery %, 12V, coordinates).
        valuePaint = mp(Color.WHITE, Paint.Style.FILL, 24);
        // Vector-glyph paints for the drawn icons (battery/12V/beams/pin).
        glyphStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyphStroke.setStyle(Paint.Style.STROKE);
        glyphStroke.setStrokeWidth(3f);
        glyphStroke.setStrokeCap(Paint.Cap.ROUND);
        glyphStroke.setStrokeJoin(Paint.Join.ROUND);
        glyphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyphFill.setStyle(Paint.Style.FILL);

        // Typeface initialization on the standalone daemon process. On some
        // automotive BSPs (DiLink 5) the native default typeface is never
        // initialized, so Typeface.create()/DEFAULT_BOLD would leave the paints
        // with a null typeface and the first drawText() would abort the whole
        // process (SIGABRT — "gDefaultTypeface == nullptr"). A Java try/catch
        // cannot catch that native abort, so we must NOT rely on it. Instead we
        // set faces loaded straight from disk (DaemonFonts, seeded at daemon
        // boot) and, if the font system is unusable, gate every drawText on
        // DaemonFonts.canDrawText() below. On DiLink 3/4 these are the same
        // Roboto glyphs as before — no visual change.
        // Preserve the original families: MONOSPACE(BOLD) for the speed + clock
        // (fixed-width digits don't jitter as values change), DEFAULT_BOLD (i.e.
        // sans-serif bold) for the gear + labels. On a broken BSP these degrade
        // to the sans-serif disk face rather than aborting.
        DaemonFonts.apply(speedPaint, Typeface.MONOSPACE, Typeface.BOLD);
        speedPaint.setShadowLayer(4f, 0f, 0f, 0xAAFFFFFF);
        DaemonFonts.apply(gearPaint, Typeface.SANS_SERIF, Typeface.BOLD);
        gearPaint.setShadowLayer(6f, 0f, 0f, 0xAAFFFFFF);
        DaemonFonts.apply(labelPaint, Typeface.SANS_SERIF, Typeface.BOLD);
        // unitPaint had no explicit typeface before (plain sans default) — keep sans.
        DaemonFonts.apply(unitPaint, Typeface.SANS_SERIF, Typeface.NORMAL);
        DaemonFonts.apply(timePaint, Typeface.MONOSPACE, Typeface.BOLD);
        DaemonFonts.apply(valuePaint, Typeface.MONOSPACE, Typeface.BOLD);
        logger.info("Font init: text " + (DaemonFonts.canDrawText() ? "enabled" : "DISABLED (no system font)"));

        loadIcons();
    }

    private Paint mp(int color, Paint.Style style, float ts) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(style);
        if (ts > 0) p.setTextSize(ts);
        return p;
    }

    /**
     * Set which telemetry fields this renderer draws. {@code null} resets to the
     * legacy default. Thread-safe (immutable value published via volatile) — the
     * next 2 Hz raster picks it up; no worker restart needed.
     */
    public void setActiveFields(TelemetryFields fields) {
        this.activeFields = (fields != null) ? fields : TelemetryFields.legacyDefault();
    }

    private void loadIcons() {
        alphaPedal = loadAlpha("pedal.png");
        alphaLeft = loadAlpha("left-arrow.png");
        alphaRight = loadAlpha("right-arrow.png");
        alphaBelt = loadAlpha("seat-belt.png");
        logger.info("Overlay icons: pedal=" + (alphaPedal != null) +
            " left=" + (alphaLeft != null) + " right=" + (alphaRight != null) +
            " belt=" + (alphaBelt != null));
    }

    private Bitmap loadAlpha(String name) {
        try {
            File f = new File(ICON_DIR + name);
            if (f.exists()) {
                Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (src != null) {
                    Bitmap alpha = src.extractAlpha();
                    src.recycle();
                    return alpha;
                }
            }
            logger.warn("Icon not found: " + name);
        } catch (Exception e) {
            logger.warn("Icon load failed: " + name + " " + e.getMessage());
        }
        return null;
    }

    /**
     * Start the off-GL-thread raster worker. Idempotent: a second call while
     * already running is a no-op. The worker rasters the overlay BACK bitmap at
     * ~2 Hz from {@code collector}'s latest snapshot; the GL thread consumes via
     * {@link #swapAndGetFront()}. Safe to call from any thread (IPC/API/daemon).
     */
    public synchronized void startWorker(TelemetryDataCollector collector) {
        this.workerCollector = collector;
        if (workerRunning) return;
        workerRunning = true;
        Thread t = new Thread(() -> {
            // Low priority: the overlay is cosmetic burn-in; never let it
            // contend with the realtime encoder/GL threads on the shared SoC.
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            while (workerRunning) {
                try {
                    TelemetryDataCollector c = workerCollector;
                    if (c != null) {
                        // getLatestSnapshot is a lock-free volatile read of an
                        // immutable snapshot — zero contention with the poller.
                        TelemetrySnapshot snap = c.getLatestSnapshot();
                        renderFrame(snap, workerTick++);
                    }
                } catch (Throwable t2) {
                    // Never let a raster error kill the worker loop.
                }
                try {
                    Thread.sleep(WORKER_PERIOD_MS);
                } catch (InterruptedException ie) {
                    break;  // stopWorker interrupted us — exit promptly
                }
            }
        }, "OverlayRaster");
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    /**
     * Stop and JOIN the raster worker. MUST be called before {@link #release()}
     * recycles the bitmaps, so the worker can never draw into a recycled bitmap.
     * Idempotent and safe to call twice / after release.
     */
    public synchronized void stopWorker() {
        workerRunning = false;
        Thread t = workerThread;
        workerThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        workerCollector = null;
    }

    public boolean renderFrame(TelemetrySnapshot snap, int fc) {
        try {
            final TelemetryFields fields = activeFields;
            Bitmap bmp = doubleBuffer.getBackForWriting();
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

            // When the daemon's font system is unusable (broken BSP), BOTH
            // measureText and drawText would abort the process natively, so we
            // skip every text op and render the icon strip only. Always true on
            // healthy DiLink 3/4/5 devices.
            final boolean canText = DaemonFonts.canDrawText();
            final boolean blink = (fc / 3) % 2 == 0;

            // Slow-changing signals are pulled from the daemon's already-running
            // collectors ONLY when a field that needs them is enabled — an
            // unselected field triggers zero extra reads.
            final boolean needBattery = fields.has(Field.BATTERY_PERCENT);
            final boolean needVolt = fields.has(Field.VOLTAGE_12V);
            final boolean needBeams = fields.hasAny(Field.LOW_BEAM, Field.HIGH_BEAM);
            final boolean needLoc = fields.has(Field.LOCATION);
            final boolean needVin = fields.has(Field.VIN);
            double soc = Double.NaN, volt12 = Double.NaN;
            boolean lowBeam = false, highBeam = false;
            double lat = Double.NaN, lon = Double.NaN;
            boolean hasLoc = false;
            String vin = null;
            if (needBattery || needVolt || needBeams || needVin) {
                try {
                    com.overdrive.app.byd.BydDataCollector col =
                            com.overdrive.app.byd.BydDataCollector.getInstance();
                    if (col != null) {
                        com.overdrive.app.byd.BydVehicleData d = col.getData();
                        if (d != null) {
                            soc = d.socPercent;
                            volt12 = d.voltage12v;
                            lowBeam = d.lowBeam;
                            highBeam = d.highBeam;
                            vin = d.vin;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (needLoc) {
                try {
                    com.overdrive.app.monitor.GpsMonitor gps =
                            com.overdrive.app.monitor.GpsMonitor.getInstance();
                    if (gps != null && gps.hasLocation()) {
                        lat = gps.getLatitude();
                        lon = gps.getLongitude();
                        hasLoc = true;
                    }
                } catch (Throwable ignored) {}
            }

            // ---- Measure pass: content segment widths (turn signals excluded) ----
            boolean milesMode = false;
            try {
                com.overdrive.app.byd.BydDataCollector collector =
                        com.overdrive.app.byd.BydDataCollector.getInstance();
                milesMode = collector != null && collector.isMilesMode();
            } catch (Throwable ignored) {}
            String spd = milesMode
                ? String.valueOf((int) Math.round(snap.speedKmh * KM_TO_MI))
                : String.valueOf(snap.speedKmh);
            String spdUnit = milesMode ? "mph" : "km/h";

            float contentW = 0f;
            int shown = 0;
            for (Field f : CONTENT_ORDER) {
                if (!fields.has(f)) continue;
                float w = measureSegment(f, canText, spd, spdUnit, snap,
                        soc, volt12, lat, lon, hasLoc, vin);
                segWidth[f.ordinal()] = w;
                contentW += w;
                shown++;
            }
            if (shown > 1) contentW += SEGMENT_GAP * (shown - 1);

            final boolean turnOn = fields.has(Field.TURN_SIGNALS);
            // The bar reserves a slot at each end for a framing turn arrow when
            // that field is on; otherwise a small symmetric inner pad.
            float endReserve = turnOn ? TURN_SLOT : SEGMENT_GAP * 2f;
            float barW = contentW + 2f * endReserve;
            if (barW < DEFAULT_BAR_WIDTH) barW = DEFAULT_BAR_WIDTH;
            float maxBarW = WIDTH - 2f * BAR_EDGE_MARGIN;
            if (barW > maxBarW) barW = maxBarW;
            float barL = (WIDTH - barW) / 2f;
            float barR = barL + barW;

            bgRect.set(barL, 2, barR, 78);
            c.drawRoundRect(bgRect, 14, 14, bgPaint);

            int iy = (HEIGHT - ICON_SIZE) / 2;

            // Turn arrows frame the bar at its two edges (fixed, not part of the
            // centered content run) — matching the pre-feature layout.
            if (turnOn) {
                drawIcon(c, alphaLeft, barL + 10, iy,
                        snap.leftTurnSignal && blink ? 0xFFFF8800 : 0xFF555555);
                drawIcon(c, alphaRight, barR - ICON_SIZE - 10, iy,
                        snap.rightTurnSignal && blink ? 0xFFFF8800 : 0xFF555555);
            }

            // ---- Draw pass: center the content run within the inner region ----
            float innerL = barL + endReserve;
            float innerR = barR - endReserve;
            float innerW = innerR - innerL;

            // No-cutoff guarantee: if the measured content is wider than the
            // inner region (only possible when many fields are enabled AND the
            // bar has already hit its max width), scale the whole content run
            // horizontally so every segment stays fully visible instead of
            // clipping at the bar edge. Scale is 1.0 in the common case, so the
            // legacy/typical layouts are pixel-identical.
            // contentW already includes the inter-segment gaps (added above), so
            // use it directly for the fit test.
            float scaleX = 1.0f;
            if (contentW > innerW && contentW > 0f) {
                scaleX = innerW / contentW;
            }

            int saveCount = -1;
            float x;
            if (scaleX < 1.0f) {
                // Scale about innerL so the run fills the inner region exactly.
                saveCount = c.save();
                c.scale(scaleX, 1.0f, innerL, HEIGHT / 2f);
                x = innerL;  // scaled content starts flush-left within inner region
            } else {
                x = innerL + (innerW - contentW) / 2f;  // centered
            }

            boolean first = true;
            for (Field f : CONTENT_ORDER) {
                if (!fields.has(f)) continue;
                if (!first) x += SEGMENT_GAP;
                first = false;
                drawSegment(c, f, x, iy, canText, blink, spd, spdUnit, snap,
                        soc, volt12, lowBeam, highBeam, lat, lon, hasLoc, vin);
                x += segWidth[f.ordinal()];
            }
            if (saveCount >= 0) c.restoreToCount(saveCount);

            doubleBuffer.markBackReady();
            return true;
        } catch (Exception e) {
            logger.error("Overlay render error", e);
            return false;
        }
    }

    /** Measure a single content segment's drawn width (excludes inter-segment gap). */
    private float measureSegment(Field f, boolean canText, String spd, String spdUnit,
                                 TelemetrySnapshot snap, double soc, double volt12,
                                 double lat, double lon, boolean hasLoc, String vin) {
        switch (f) {
            case SPEED:
                return canText ? speedPaint.measureText(spd) + 4 + unitPaint.measureText(spdUnit) : 0f;
            case GEAR:
                return 44f;
            case BRAKE_PEDAL:
            case ACCEL_PEDAL:
            case SEATBELT_DRIVER:
            case SEATBELT_PASSENGER:
                return ICON_SIZE;
            case LOW_BEAM:
            case HIGH_BEAM:
                return ICON_SIZE + 2f;
            case BATTERY_PERCENT: {
                float w = 34f + 4f;  // battery glyph + terminal
                if (canText) w += 6f + valuePaint.measureText(formatSoc(soc));
                return w;
            }
            case VOLTAGE_12V: {
                float w = 40f;  // "12V" box glyph
                if (canText) w += 6f + valuePaint.measureText(formatVolts(volt12));
                return w;
            }
            case LOCATION: {
                if (!canText) return 22f;  // pin glyph only
                // Coordinates draw at size 16 (see drawLocation) — measure at the
                // SAME size so the width doesn't depend on timePaint's leftover
                // size from a prior segment/frame's draw.
                timePaint.setTextSize(16);
                return 22f + 4f + Math.max(
                        timePaint.measureText(formatCoord(lat, hasLoc, true)),
                        timePaint.measureText(formatCoord(lon, hasLoc, false)));
            }
            case VIN: {
                // Text-only segment (no glyph), so with no font there is nothing
                // to draw and nothing to reserve — same as TIMESTAMP below.
                if (!canText) return 0f;
                // Drawn as two stacked lines at VIN_TEXT_SIZE (see drawVin) —
                // measure at the SAME size so the width can't depend on
                // timePaint's leftover size from a prior segment/frame's draw.
                timePaint.setTextSize(VIN_TEXT_SIZE);
                String v = formatVin(vin);
                int split = vinSplitIndex(v);
                return Math.max(timePaint.measureText(v.substring(0, split)),
                                timePaint.measureText(v.substring(split)));
            }
            case TIMESTAMP:
                if (!canText) return 0f;
                // Date line draws at size 16; measure the wider "0000-00-00" at
                // that size for a deterministic width (floor 96 covers the time
                // line drawn at size 20).
                timePaint.setTextSize(16);
                return Math.max(96f, timePaint.measureText("0000-00-00"));
            default:
                return 0f;
        }
    }

    /** Draw a single content segment starting at {@code x} (origin = left edge). */
    private void drawSegment(Canvas c, Field f, float x, int iy, boolean canText,
                             boolean blink, String spd, String spdUnit,
                             TelemetrySnapshot snap, double soc, double volt12,
                             boolean lowBeam, boolean highBeam,
                             double lat, double lon, boolean hasLoc, String vin) {
        switch (f) {
            case SPEED:
                if (canText) {
                    c.drawText(spd, x, 54, speedPaint);
                    float sx = x + speedPaint.measureText(spd) + 4;
                    c.drawText(spdUnit, sx, 54, unitPaint);
                }
                break;
            case GEAR:
                if (canText) {
                    gearPaint.setColor(getGearColorForDarkBg(snap.gearMode));
                    c.drawText(String.valueOf(snap.getGearChar()), x, 54, gearPaint);
                }
                break;
            case BRAKE_PEDAL: {
                int brakeCol = snap.brakePedalPercent > 5 ? 0xFFFF2222 : 0xFF888888;
                drawIcon(c, alphaPedal, x, iy, brakeCol);
                if (canText) {
                    labelPaint.setColor(brakeCol);
                    String t = snap.brakePedalPercent > 5 ? "B " + snap.brakePedalPercent + "%" : "B";
                    c.drawText(t, x, iy + ICON_SIZE + 14, labelPaint);
                }
                break;
            }
            case ACCEL_PEDAL: {
                int accelCol = snap.accelPedalPercent > 5 ? 0xFF22DD22 : 0xFF888888;
                drawIcon(c, alphaPedal, x, iy, accelCol);
                if (canText) {
                    labelPaint.setColor(accelCol);
                    String t = snap.accelPedalPercent > 5 ? "A " + snap.accelPedalPercent + "%" : "A";
                    c.drawText(t, x, iy + ICON_SIZE + 14, labelPaint);
                }
                break;
            }
            case SEATBELT_DRIVER: {
                boolean b = snap.seatbeltBuckled.length > 0 && snap.seatbeltBuckled[0];
                int col = b ? 0xFF22DD22 : (blink ? 0xFFFF2222 : 0xFF552222);
                drawIcon(c, alphaBelt, x, iy, col);
                if (canText) {
                    labelPaint.setColor(b ? 0xFF22DD22 : 0xFFFF2222);
                    c.drawText("D", x + ICON_SIZE / 2f - 4, iy + ICON_SIZE + 14, labelPaint);
                }
                break;
            }
            case SEATBELT_PASSENGER: {
                boolean b = snap.seatbeltBuckled.length > 1 && snap.seatbeltBuckled[1];
                int col = b ? 0xFF22DD22 : (blink ? 0xFFFF2222 : 0xFF552222);
                drawIcon(c, alphaBelt, x, iy, col);
                if (canText) {
                    labelPaint.setColor(b ? 0xFF22DD22 : 0xFFFF2222);
                    c.drawText("P", x + ICON_SIZE / 2f - 3, iy + ICON_SIZE + 14, labelPaint);
                }
                break;
            }
            case LOW_BEAM:
                drawBeamGlyph(c, x, iy, lowBeam, false);
                break;
            case HIGH_BEAM:
                drawBeamGlyph(c, x, iy, highBeam, true);
                break;
            case BATTERY_PERCENT:
                drawBattery(c, x, iy, soc, canText);
                break;
            case VOLTAGE_12V:
                drawVoltage(c, x, iy, volt12, canText);
                break;
            case LOCATION:
                drawLocation(c, x, iy, lat, lon, hasLoc, canText);
                break;
            case VIN:
                drawVin(c, x, vin, canText);
                break;
            case TIMESTAMP:
                if (canText) {
                    // Read wall clock directly rather than the snapshot's polled
                    // timestamp. Snapshots publish only on field-change, so a
                    // static cabin would freeze the burned-in clock for seconds.
                    reusableDate.setTime(System.currentTimeMillis());
                    timePaint.setTextSize(16);
                    c.drawText(dateFmt.format(reusableDate), x, 34, timePaint);
                    timePaint.setTextSize(20);
                    c.drawText(timeFmt.format(reusableDate), x, 60, timePaint);
                }
                break;
            default:
                break;
        }
    }

    // ==================== Value formatters ====================

    private static String formatSoc(double soc) {
        if (Double.isNaN(soc) || soc < 0) return "--%";
        return String.format(Locale.US, "%.0f%%", soc);
    }

    private static String formatVolts(double v) {
        if (Double.isNaN(v) || v <= 0) return "--.-V";
        return String.format(Locale.US, "%.2fV", v);
    }

    private static String formatCoord(double v, boolean hasLoc, boolean isLat) {
        if (!hasLoc || Double.isNaN(v)) return isLat ? "lat --" : "lon --";
        return String.format(Locale.US, "%.5f", v);
    }

    /**
     * VIN for display. Falls back to a placeholder when the bodywork getter
     * hasn't produced one yet (cold boot before the first poll, or a trim that
     * exposes neither VIN getter), so the segment keeps a stable width instead
     * of collapsing to nothing mid-recording.
     *
     * <p>Uppercased and stripped of whitespace/separators: a VIN is defined as
     * 17 uppercase alphanumerics, and some trims pad or hyphenate the raw HAL
     * string. A real VIN is never truncated (17 &lt; {@link #VIN_MAX_CHARS});
     * only an unexpectedly long hash-wrapper string is clipped, to keep the
     * segment from widening the bar into the squash threshold.
     */
    private static String formatVin(String vin) {
        if (vin == null) return "VIN --";
        String v = vin.trim().toUpperCase(Locale.US);
        // Drop separators a trim might inject; keep it allocation-light by
        // skipping the rebuild when the string is already clean.
        boolean clean = true;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9'))) { clean = false; break; }
        }
        if (!clean) {
            StringBuilder sb = new StringBuilder(v.length());
            for (int i = 0; i < v.length(); i++) {
                char ch = v.charAt(i);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) sb.append(ch);
            }
            v = sb.toString();
        }
        if (v.isEmpty()) return "VIN --";
        return v.length() > VIN_MAX_CHARS ? v.substring(0, VIN_MAX_CHARS) : v;
    }

    /**
     * Index at which {@link #formatVin}'s result splits across the two drawn
     * lines. A standard 17-char VIN splits 9+8 ({@link #VIN_LINE1_CHARS}); a
     * shorter or longer string splits down the middle (favouring the first
     * line) so the two lines stay balanced and neither can overflow the bar.
     */
    private static int vinSplitIndex(String v) {
        if (v.length() <= VIN_LINE1_CHARS) return v.length();
        if (v.length() <= 17) return VIN_LINE1_CHARS;
        return (v.length() + 1) / 2;
    }

    // ==================== Vector glyphs (new fields) ====================

    /**
     * Battery outline with a proportional fill bar + a small terminal nub.
     * Green when healthy, amber mid, red low. Draws "NN%" to the right when text
     * is available.
     */
    private void drawBattery(Canvas c, float x, int iy, double soc, boolean canText) {
        float bodyL = x + 1f, bodyT = iy + 9f, bodyR = x + 33f, bodyB = iy + 31f;
        int col = socColor(soc);
        glyphStroke.setColor(col);
        glyphRect.set(bodyL, bodyT, bodyR, bodyB);
        c.drawRoundRect(glyphRect, 3f, 3f, glyphStroke);
        // Terminal nub on the right.
        glyphFill.setColor(col);
        glyphRect.set(bodyR, iy + 15f, bodyR + 4f, iy + 25f);
        c.drawRoundRect(glyphRect, 1.5f, 1.5f, glyphFill);
        // Fill bar proportional to SOC.
        if (!Double.isNaN(soc) && soc > 0) {
            float frac = (float) Math.max(0, Math.min(100, soc)) / 100f;
            float innerL = bodyL + 3f, innerR = bodyR - 3f;
            float innerT = bodyT + 3f, innerB = bodyB - 3f;
            glyphRect.set(innerL, innerT, innerL + (innerR - innerL) * frac, innerB);
            c.drawRoundRect(glyphRect, 1.5f, 1.5f, glyphFill);
        }
        if (canText) {
            valuePaint.setColor(0xFFFFFFFF);
            c.drawText(formatSoc(soc), bodyR + 10f, iy + 28f, valuePaint);
        }
    }

    private static int socColor(double soc) {
        if (Double.isNaN(soc)) return 0xFF888888;
        if (soc < 15) return 0xFFFF3333;
        if (soc < 40) return 0xFFFFAA33;
        return 0xFF33DD44;
    }

    /**
     * A red "12V" battery box with +/- terminals, matching the mockup, then the
     * measured voltage in white.
     */
    private void drawVoltage(Canvas c, float x, int iy, double v, boolean canText) {
        int red = 0xFFE03030;
        float boxL = x + 1f, boxT = iy + 10f, boxR = x + 39f, boxB = iy + 32f;
        glyphStroke.setColor(red);
        glyphRect.set(boxL, boxT, boxR, boxB);
        c.drawRoundRect(glyphRect, 3f, 3f, glyphStroke);
        // Terminal nubs.
        glyphFill.setColor(red);
        glyphRect.set(boxL + 7f, boxT - 3f, boxL + 13f, boxT);
        c.drawRect(glyphRect, glyphFill);
        glyphRect.set(boxR - 13f, boxT - 3f, boxR - 7f, boxT);
        c.drawRect(glyphRect, glyphFill);
        if (canText) {
            // "12V" small inside the box.
            labelPaint.setColor(red);
            float saved = labelPaint.getTextSize();
            labelPaint.setTextSize(14);
            float tw = labelPaint.measureText("12V");
            c.drawText("12V", boxL + (boxR - boxL - tw) / 2f, boxB - 6f, labelPaint);
            labelPaint.setTextSize(saved);
            // Voltage value in white to the right.
            valuePaint.setColor(0xFFFFFFFF);
            c.drawText(formatVolts(v), boxR + 8f, iy + 28f, valuePaint);
        }
    }

    /**
     * Head-beam glyph: a solid "D" reflector with light rays. Low beam rays
     * angle downward; high beam rays run straight. On = colored (low green /
     * high blue, per the mockup), off = dim gray.
     */
    private void drawBeamGlyph(Canvas c, float x, int iy, boolean on, boolean high) {
        int col = on ? (high ? 0xFF3399FF : 0xFF33DD44) : 0xFF666666;
        float cx = x + 8f, cy = iy + ICON_SIZE / 2f;
        // Reflector: filled semicircle-ish D.
        glyphFill.setColor(col);
        glyphPath.reset();
        glyphPath.addArc(cx - 9f, cy - 10f, cx + 5f, cy + 10f, 90f, 180f);
        glyphPath.close();
        c.drawPath(glyphPath, glyphFill);
        // Three light rays.
        glyphStroke.setColor(col);
        glyphStroke.setStrokeWidth(2.5f);
        for (int i = -1; i <= 1; i++) {
            float ry = cy + i * 6f;
            float dy = high ? 0f : (i + 1) * 2.2f;  // low beam tilts down
            float rx0 = cx + 8f;
            c.drawLine(rx0, ry, rx0 + 12f, ry + dy, glyphStroke);
        }
        glyphStroke.setStrokeWidth(3f);
    }

    /** Map-pin glyph + lat/lon coordinates on two stacked lines. */
    private void drawLocation(Canvas c, float x, int iy, double lat, double lon,
                              boolean hasLoc, boolean canText) {
        int col = hasLoc ? 0xFF33DD44 : 0xFF888888;
        float cx = x + 9f, cy = iy + 15f;
        // Pin: circle head + tapered tail.
        glyphFill.setColor(col);
        glyphPath.reset();
        glyphPath.moveTo(cx, iy + 31f);           // tip
        glyphPath.lineTo(cx - 7f, cy + 3f);
        glyphPath.lineTo(cx + 7f, cy + 3f);
        glyphPath.close();
        c.drawPath(glyphPath, glyphFill);
        c.drawCircle(cx, cy, 7f, glyphFill);
        // Punch-out center dot.
        glyphFill.setColor(0xFF101010);
        c.drawCircle(cx, cy, 3f, glyphFill);
        if (canText) {
            timePaint.setColor(0xFFFFFFFF);
            timePaint.setTextSize(16);
            c.drawText(formatCoord(lat, hasLoc, true), x + 22f, 34, timePaint);
            c.drawText(formatCoord(lon, hasLoc, false), x + 22f, 58, timePaint);
            timePaint.setTextSize(20);
        }
    }

    /**
     * VIN on two stacked lines (9+8 for a standard 17-char VIN), using the same
     * baselines and text size as {@link #drawLocation}'s coordinate pair so the
     * two multi-line segments align. One line at size 24 would be ~205-245px —
     * wide enough to trigger renderFrame's whole-bar horizontal squash once the
     * full field set is enabled — so the stack is what keeps scaleX at 1.0.
     *
     * <p>Restores timePaint's size to 20 on the way out, matching the
     * drawLocation/TIMESTAMP convention (the TIMESTAMP segment's time line
     * relies on that leftover size).
     */
    private void drawVin(Canvas c, float x, String vin, boolean canText) {
        if (!canText) return;  // text-only segment; nothing to draw without a font
        String v = formatVin(vin);
        int split = vinSplitIndex(v);
        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextSize(VIN_TEXT_SIZE);
        c.drawText(v.substring(0, split), x, 34, timePaint);
        if (split < v.length()) {
            c.drawText(v.substring(split), x, 58, timePaint);
        }
        timePaint.setTextSize(20);
    }

    /** Gear colors for dark background — bright and readable */
    private int getGearColorForDarkBg(int gearMode) {
        switch (gearMode) {
            case 1: return 0xFFAAAAAA; // P → light gray
            case 2: return 0xFFFF4444; // R → bright red
            case 3: return 0xFF44AAFF; // N → bright blue
            case 4: return 0xFF44FF44; // D → bright green
            case 5: return 0xFFCC66FF; // M → bright purple
            case 6: return 0xFFFFAA44; // S → bright orange
            default: return 0xFFFFFFFF;
        }
    }

    /**
     * Solid fill with neon glow effect via setShadowLayer + SRC_IN.
     * Uses cached ColorFilter instances to avoid per-icon allocation.
     */
    private void drawIcon(Canvas c, Bitmap alpha, float x, float y, int color) {
        if (alpha == null) return;
        iconPaint.setColorFilter(getCachedColorFilter(color));
        iconPaint.setShadowLayer(8f, 0f, 0f, color); // Neon glow
        iconSrcRect.set(0, 0, alpha.getWidth(), alpha.getHeight());
        iconDstRect.set(x, y, x + ICON_SIZE, y + ICON_SIZE);
        c.drawBitmap(alpha, iconSrcRect, iconDstRect, iconPaint);
        iconPaint.setColorFilter(null);
        iconPaint.clearShadowLayer();
    }

    /** Returns a cached PorterDuffColorFilter for the given color, creating one if needed. */
    private PorterDuffColorFilter getCachedColorFilter(int color) {
        PorterDuffColorFilter filter = colorFilterCache.get(color);
        if (filter == null) {
            filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            colorFilterCache.put(color, filter);
        }
        return filter;
    }

    public Bitmap swapAndGetFront() { return doubleBuffer.swapAndGetFront(); }
    public void release() { doubleBuffer.release(); }
}
