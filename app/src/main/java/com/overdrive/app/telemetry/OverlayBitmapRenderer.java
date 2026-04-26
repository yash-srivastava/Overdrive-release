package com.overdrive.app.telemetry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Telemetry overlay renderer with solid PNG icons tinted via alpha extraction.
 * Dark semi-transparent background, white text, colored icons.
 *
 * Performance: All geometry objects (Rect, RectF, Date) and ColorFilters are
 * pre-allocated and reused across frames to eliminate per-frame GC pressure.
 */
public class OverlayBitmapRenderer {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 80;
    private static final String ICON_DIR = "/data/local/tmp/overlay/";
    private static final int ICON_SIZE = 40;

    private final DaemonLogger logger;
    private final OverlayDoubleBuffer doubleBuffer;
    private final Paint bgPaint, speedPaint, unitPaint, gearPaint;
    private final Paint iconPaint, labelPaint, timePaint;
    private final SimpleDateFormat dateFmt, timeFmt;

    // Pre-extracted alpha masks (solid icons → clean stencils)
    private Bitmap alphaPedal, alphaLeft, alphaRight, alphaBelt;

    // Pre-allocated geometry objects — reused every frame to avoid GC pressure
    private final RectF bgRect = new RectF();
    private final Rect iconSrcRect = new Rect();
    private final RectF iconDstRect = new RectF();
    private final Date reusableDate = new Date();

    // Cached PorterDuffColorFilters — keyed by color int, avoids allocation per icon per frame
    private final Map<Integer, PorterDuffColorFilter> colorFilterCache = new HashMap<>();

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

        // Typeface initialization can fail on rapid daemon restarts when the
        // Android font cache is in a corrupted state (SystemFonts throws
        // "Failed to create internal object. maybe invalid font data.").
        // Catch and fall back to default paint — no custom fonts is better
        // than crashing the daemon with SIGABRT on the second restart.
        try {
            speedPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            speedPaint.setShadowLayer(4f, 0f, 0f, 0xAAFFFFFF);
            gearPaint.setTypeface(Typeface.DEFAULT_BOLD);
            gearPaint.setShadowLayer(6f, 0f, 0f, 0xAAFFFFFF);
            labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
            timePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        } catch (Throwable t) {
            // Catches both Exception and Error (RuntimeException from native font init)
            logger.warn("Font init failed (will use defaults): " + t.getMessage());
        }

        loadIcons();
    }

    private Paint mp(int color, Paint.Style style, float ts) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(style);
        if (ts > 0) p.setTextSize(ts);
        return p;
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

    public boolean renderFrame(TelemetrySnapshot snap, int fc) {
        try {
            Bitmap bmp = doubleBuffer.getBackForWriting();
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

            boolean blink = (fc / 3) % 2 == 0;
            float barL = 200, barR = 1080;
            bgRect.set(barL, 2, barR, 78);
            c.drawRoundRect(bgRect, 14, 14, bgPaint);

            int iy = (HEIGHT - ICON_SIZE) / 2;

            // LEFT TURN — fixed at left edge of bar
            drawIcon(c, alphaLeft, barL + 10, iy, snap.leftTurnSignal && blink ? 0xFFFF8800 : 0xFF555555);

            // RIGHT TURN — fixed at right edge of bar
            drawIcon(c, alphaRight, barR - ICON_SIZE - 10, iy, snap.rightTurnSignal && blink ? 0xFFFF8800 : 0xFF555555);

            // Calculate content width for centering (between the two arrows)
            String spd = String.valueOf(snap.speedKmh);
            float spdW = speedPaint.measureText(spd) + 4 + unitPaint.measureText("km/h");
            float gearW = 44;
            float brakeW = ICON_SIZE + 8;
            float accelW = ICON_SIZE + 10;
            float belt1W = ICON_SIZE + 4;
            float belt2W = ICON_SIZE + 10;
            float timeW = 100;
            float totalW = spdW + 8 + gearW + 8 + brakeW + accelW + belt1W + belt2W + timeW;

            // Center content between arrows
            float innerL = barL + ICON_SIZE + 24;
            float innerR = barR - ICON_SIZE - 24;
            float innerW = innerR - innerL;
            float x = innerL + (innerW - totalW) / 2;

            // SPEED
            c.drawText(spd, x, 54, speedPaint);
            x += speedPaint.measureText(spd) + 4;
            c.drawText("km/h", x, 54, unitPaint);
            x += unitPaint.measureText("km/h") + 8;

            // GEAR
            gearPaint.setColor(getGearColorForDarkBg(snap.gearMode));
            c.drawText(String.valueOf(snap.getGearChar()), x, 54, gearPaint);
            x += gearW + 8;

            // BRAKE PEDAL
            int brakeCol = snap.brakePedalPercent > 5 ? 0xFFFF2222 : 0xFF888888;
            drawIcon(c, alphaPedal, x, iy, brakeCol);
            labelPaint.setColor(brakeCol);
            String brkTxt = snap.brakePedalPercent > 5 ? "B " + snap.brakePedalPercent + "%" : "B";
            c.drawText(brkTxt, x, iy + ICON_SIZE + 14, labelPaint);
            x += brakeW;

            // ACCEL PEDAL
            int accelCol = snap.accelPedalPercent > 5 ? 0xFF22DD22 : 0xFF888888;
            drawIcon(c, alphaPedal, x, iy, accelCol);
            labelPaint.setColor(accelCol);
            String accTxt = snap.accelPedalPercent > 5 ? "A " + snap.accelPedalPercent + "%" : "A";
            c.drawText(accTxt, x, iy + ICON_SIZE + 14, labelPaint);
            x += accelW;

            // DRIVER SEATBELT
            boolean dB = snap.seatbeltBuckled.length > 0 && snap.seatbeltBuckled[0];
            int dCol = dB ? 0xFF22DD22 : (blink ? 0xFFFF2222 : 0xFF552222);
            drawIcon(c, alphaBelt, x, iy, dCol);
            labelPaint.setColor(dB ? 0xFF22DD22 : 0xFFFF2222);
            c.drawText("D", x + ICON_SIZE / 2 - 4, iy + ICON_SIZE + 14, labelPaint);
            x += belt1W;

            // PASSENGER SEATBELT
            boolean pB = snap.seatbeltBuckled.length > 1 && snap.seatbeltBuckled[1];
            int pCol = pB ? 0xFF22DD22 : (blink ? 0xFFFF2222 : 0xFF552222);
            drawIcon(c, alphaBelt, x, iy, pCol);
            labelPaint.setColor(pB ? 0xFF22DD22 : 0xFFFF2222);
            c.drawText("P", x + ICON_SIZE / 2 - 3, iy + ICON_SIZE + 14, labelPaint);
            x += belt2W;

            // TIMESTAMP
            reusableDate.setTime(snap.timestampMs);
            timePaint.setTextSize(16);
            c.drawText(dateFmt.format(reusableDate), x, 34, timePaint);
            timePaint.setTextSize(20);
            c.drawText(timeFmt.format(reusableDate), x, 60, timePaint);

            doubleBuffer.markBackReady();
            return true;
        } catch (Exception e) {
            logger.error("Overlay render error", e);
            return false;
        }
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
