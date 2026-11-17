package com.overdrive.app.parking.signage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two-stage scene-text OCR on TFLite / XNNPACK (CPU only, 2 threads):
 *
 * <ol>
 *   <li><b>Detector</b> — a DBNet-style model (PaddleOCR PP-OCRv4 mobile det
 *       exported by {@code dev/export_signage_ocr.py}); input NHWC RGB float
 *       with ImageNet normalisation, output a text probability map. Boxes are
 *       recovered with {@link ConnectedComponents} (no OpenCV).</li>
 *   <li><b>Recogniser</b> — a CRNN/SVTR-style CTC model (PP-OCRv4 mobile rec,
 *       Latin + Chinese); input {@code [1,48,W,3]} normalised to [-1,1], output
 *       {@code [1,T,C]} per-step class distribution decoded greedily by
 *       {@link CtcDecoder} with the dictionary in
 *       {@code signage_charset.txt} (blank at 0).</li>
 * </ol>
 *
 * The models SHIP IN THE APK ({@code assets/models/signage_*}: en_PP-OCRv3
 * mobile detector + PP-OCRv4 mobile recogniser, fp16 weights, ≈6.4 MB together, converted by
 * {@code dev/export_signage_ocr.py}). They are looked up first in
 * {@link #EXTERNAL_MODEL_DIR} (so a user can side-load a newer export on the
 * unit without a rebuild) and then in the APK assets. Only if neither has all
 * three files does {@link #openIfAvailable} return null and the session get
 * marked {@code signageState = unavailable}.
 *
 * <p>This class never runs on a camera / AI / ACC thread: the parking worker
 * is the only caller, and only at the deferred times the controller picks.
 */
public final class TfliteTextOcrBackend implements TextOcrBackend {

    public static final String EXTERNAL_MODEL_DIR = "/storage/emulated/0/Overdrive/models";
    public static final String DET_FILE = "signage_det.tflite";
    public static final String REC_FILE = "signage_rec.tflite";
    public static final String CHARSET_FILE = "signage_charset.txt";
    static final String ASSET_PREFIX = "models/";

    static final int DET_DEFAULT_SIDE = 640;
    static final float DET_BIN_THRESH = 0.30f;
    static final float DET_BOX_THRESH = 0.50f;
    static final float DET_UNCLIP_RATIO = 1.6f;
    /**
     * Extra margin around each detected box, as a fraction of the box height.
     * The CTC recogniser drops leading/trailing glyphs when a crop is tight
     * (measured on the fp16 export: "ZONE C" → "ZNE" tight, "ZONE" with 15%).
     */
    static final float BOX_MARGIN_FRAC = 0.15f;
    static final int DET_MIN_AREA = 8;
    static final int MAX_BOXES = 12;
    /** Native-resolution pass: detector tiles per frame, and boxes it may add beyond the whole-frame pass. */
    static final int MAX_FINE_TILES = 12;
    static final int MAX_FINE_BOXES = 8;
    /** A native-resolution box mostly inside a whole-frame box is the same text. */
    static final float FINE_COVERED_FRAC = 0.5f;
    static final int REC_MAX_W = 320;
    static final int REC_MIN_W = 32;
    static final int NUM_THREADS = 2;

    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    /** Minimal logging seam so the daemon logger stays out of this class. */
    public interface Log { void log(String msg); }

    private final Interpreter det;
    private final Interpreter rec;
    private final String[] charset;
    private final Log log;

    private final boolean detNchw;
    private final int detH, detW;
    private final boolean recNchw;
    private final int recH;
    private final int recFixedW;      // <= 0 when the width is dynamic
    private final int recClasses;

    private ByteBuffer detInput;
    private ByteBuffer detOutput;
    private int[] tilePixels;
    private ByteBuffer recInput;
    private ByteBuffer recOutput;
    private float[] recFlat;
    private int recInputW = -1;

    private TfliteTextOcrBackend(Interpreter det, Interpreter rec, String[] charset, Log log,
                                 boolean detNchw, int detH, int detW,
                                 boolean recNchw, int recH, int recFixedW, int recClasses) {
        this.det = det; this.rec = rec; this.charset = charset; this.log = log;
        this.detNchw = detNchw; this.detH = detH; this.detW = detW;
        this.recNchw = recNchw; this.recH = recH; this.recFixedW = recFixedW; this.recClasses = recClasses;
    }

    /** True when all three model files exist externally or as assets. */
    public static boolean modelsPresent(Context ctx) {
        if (externalFile(DET_FILE).isFile() && externalFile(REC_FILE).isFile()
                && externalFile(CHARSET_FILE).isFile()) return true;
        if (ctx == null) return false;
        try {
            for (String f : new String[] {DET_FILE, REC_FILE, CHARSET_FILE}) {
                try (InputStream in = ctx.getAssets().open(ASSET_PREFIX + f)) {
                    if (in == null) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when det + rec + charset are ALL present in the side-load folder. */
    static boolean externalSetPresent() {
        return externalFile(DET_FILE).isFile() && externalFile(REC_FILE).isFile()
                && externalFile(CHARSET_FILE).isFile();
    }

    /**
     * Load both models; null when they are absent or fail to initialise.
     *
     * <p>A side-loaded set (a language-specific recogniser) is used only when
     * all three files are present together — a recogniser paired with the
     * wrong dictionary decodes garbage — and if it fails to load or its class
     * count does not match its dictionary, the bundled set takes over.
     */
    public static TfliteTextOcrBackend openIfAvailable(Context ctx, Log log) {
        Log l = log != null ? log : msg -> {};
        if (!modelsPresent(ctx)) return null;
        if (externalSetPresent()) {
            TfliteTextOcrBackend ext = open(ctx, l, true);
            if (ext != null) return ext;
            l.log("Signage OCR: side-loaded models in " + EXTERNAL_MODEL_DIR + " unusable — using the bundled set");
        } else if (externalFile(DET_FILE).isFile() || externalFile(REC_FILE).isFile()
                || externalFile(CHARSET_FILE).isFile()) {
            l.log("Signage OCR: incomplete side-load set in " + EXTERNAL_MODEL_DIR
                    + " (need " + DET_FILE + ", " + REC_FILE + ", " + CHARSET_FILE + ") — using the bundled set");
        }
        return open(ctx, l, false);
    }

    private static TfliteTextOcrBackend open(Context ctx, Log l, boolean external) {
        Interpreter det = null, rec = null;
        try {
            try { System.loadLibrary("tensorflowlite_jni"); } catch (Throwable ignored) {}
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(NUM_THREADS);
            det = new Interpreter(loadModel(ctx, DET_FILE, external), opts);
            rec = new Interpreter(loadModel(ctx, REC_FILE, external), opts);

            // ---- detector geometry
            int[] din = det.getInputTensor(0).shape();
            boolean detNchw = din.length == 4 && din[1] == 3 && din[3] != 3;
            int dh = detNchw ? din[2] : din[1];
            int dw = detNchw ? din[3] : din[2];
            if (dh <= 0 || dw <= 0) {
                dh = DET_DEFAULT_SIDE; dw = DET_DEFAULT_SIDE;
                det.resizeInput(0, detNchw ? new int[] {1, 3, dh, dw} : new int[] {1, dh, dw, 3});
            }
            det.allocateTensors();

            // ---- recogniser geometry
            int[] rin = rec.getInputTensor(0).shape();
            boolean recNchw = rin.length == 4 && rin[1] == 3 && rin[3] != 3;
            int rh = recNchw ? rin[2] : rin[1];
            int rw = recNchw ? rin[3] : rin[2];
            if (rh <= 0) rh = 48;
            int fixedW = rw > 0 ? rw : -1;
            if (fixedW < 0) {
                rec.resizeInput(0, recNchw ? new int[] {1, 3, rh, REC_MIN_W} : new int[] {1, rh, REC_MIN_W, 3});
            }
            rec.allocateTensors();
            int[] rout = rec.getOutputTensor(0).shape();
            int classes = rout[rout.length - 1];
            if (dh <= 0 || dw <= 0 || rh <= 0 || classes < 2) {
                throw new IllegalStateException("unexpected tensor geometry det=" + java.util.Arrays.toString(din)
                        + " rec=" + java.util.Arrays.toString(rin) + " out=" + java.util.Arrays.toString(rout));
            }

            List<String> lines = loadCharset(ctx, external);
            // PaddleOCR layout: blank + one class per dictionary line (+ space).
            if (classes != lines.size() + 1 && classes != lines.size() + 2) {
                throw new IllegalStateException("charset/model mismatch: " + lines.size()
                        + " dictionary lines vs " + classes + " output classes");
            }
            String[] cs = CtcDecoder.charsetFromLines(lines, classes);
            l.log("Signage OCR ready (" + (external ? "side-loaded" : "bundled") + "): det=" + dw + "x" + dh
                    + (detNchw ? " NCHW" : " NHWC") + " rec=h" + rh + (fixedW > 0 ? " w" + fixedW : " wDyn")
                    + " classes=" + classes + " dict=" + lines.size());
            return new TfliteTextOcrBackend(det, rec, cs, l, detNchw, dh, dw, recNchw, rh, fixedW, classes);
        } catch (Throwable t) {
            l.log("Signage OCR init failed (" + (external ? "side-loaded" : "bundled") + "): " + t);
            try { if (det != null) det.close(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    @Override
    public boolean isReady() {
        return det != null && rec != null && charset != null && charset.length > 1;
    }

    @Override
    public synchronized List<TextLine> read(byte[] jpeg) {
        List<TextLine> out = new ArrayList<>();
        if (!isReady() || jpeg == null || jpeg.length == 0) return out;
        Bitmap src = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            if (src == null) return out;
            List<int[]> boxes = detect(src);
            int n = 0;
            for (int[] b : boxes) {
                if (n++ >= MAX_BOXES) break;
                recogniseBox(src, b, out);
            }
            List<int[]> fine;
            try {
                fine = detectFine(src, boxes);
            } catch (Throwable t) {
                log.log("Signage OCR fine pass failed: " + t);
                fine = Collections.emptyList();
            }
            for (int[] b : fine) recogniseBox(src, b, out);
        } catch (Throwable t) {
            log.log("Signage OCR read failed: " + t);
        } finally {
            if (src != null) src.recycle();
        }
        return out;
    }

    private void recogniseBox(Bitmap src, int[] b, List<TextLine> out) {
        int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
        if (bw < 8 || bh < 8) return;
        Bitmap crop = Bitmap.createBitmap(src, b[0], b[1], bw, bh);
        try {
            CtcDecoder.Decoded d = recognise(crop);
            if (d.text != null && !d.text.trim().isEmpty()) {
                out.add(new TextLine(d.text.trim(), d.confidence,
                        (b[0] + bw / 2f) / src.getWidth(), (b[1] + bh / 2f) / src.getHeight(),
                        (float) bw / src.getWidth(), (float) bh / src.getHeight()));
            }
        } finally {
            if (crop != src) crop.recycle();
        }
    }

    @Override
    public synchronized void close() {
        try { det.close(); } catch (Throwable ignored) {}
        try { rec.close(); } catch (Throwable ignored) {}
        detInput = null;
        detOutput = null;
        tilePixels = null;
        recInput = null;
        recOutput = null;
        recFlat = null;
    }

    // ==================== DETECTION ====================

    /** @return source-pixel boxes {x0,y0,x1,y1}, reading order (top→bottom, left→right). */
    private List<int[]> detect(Bitmap src) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, detW, detH, true);
        try {
            if (detInput == null) {
                detInput = ByteBuffer.allocateDirect(4 * 3 * detH * detW).order(ByteOrder.nativeOrder());
            }
            fillNormalised(scaled, detInput, detNchw, IMAGENET_MEAN, IMAGENET_STD, detW);
            int[] oshape = det.getOutputTensor(0).shape();
            int oh, ow;
            if (oshape.length == 4 && oshape[1] == 1) { oh = oshape[2]; ow = oshape[3]; }        // NCHW
            else if (oshape.length == 4) { oh = oshape[1]; ow = oshape[2]; }                      // NHWC
            else if (oshape.length == 3) { oh = oshape[1]; ow = oshape[2]; }
            else { oh = detH; ow = detW; }
            ByteBuffer outBuf = ByteBuffer.allocateDirect(4 * oh * ow).order(ByteOrder.nativeOrder());
            detInput.rewind();
            det.run(detInput, outBuf);
            outBuf.rewind();
            float[] prob = new float[oh * ow];
            outBuf.asFloatBuffer().get(prob);
            List<ConnectedComponents.Component> comps =
                    ConnectedComponents.label(prob, ow, oh, DET_BIN_THRESH, DET_MIN_AREA);
            float sx = (float) src.getWidth() / ow, sy = (float) src.getHeight() / oh;
            List<int[]> boxes = new ArrayList<>();
            for (ConnectedComponents.Component c : comps) {
                if (c.meanScore() < DET_BOX_THRESH) continue;
                int[] u = ConnectedComponents.unclip(c, DET_UNCLIP_RATIO, ow, oh);
                int x0 = Math.round(u[0] * sx);
                int y0 = Math.round(u[1] * sy);
                int x1 = Math.round((u[2] + 1) * sx) - 1;
                int y1 = Math.round((u[3] + 1) * sy) - 1;
                int margin = Math.round((y1 - y0 + 1) * BOX_MARGIN_FRAC);
                x0 = clamp(x0 - margin, 0, src.getWidth() - 1);
                y0 = clamp(y0 - margin, 0, src.getHeight() - 1);
                x1 = clamp(x1 + margin, 0, src.getWidth() - 1);
                y1 = clamp(y1 + margin, 0, src.getHeight() - 1);
                if (x1 <= x0 || y1 <= y0) continue;
                boxes.add(new int[] {x0, y0, x1, y1, c.area});
            }
            // Largest text first (signage is big), then reading order.
            Collections.sort(boxes, (a, b) -> Integer.compare(b[4], a[4]));
            if (boxes.size() > MAX_BOXES) boxes = new ArrayList<>(boxes.subList(0, MAX_BOXES));
            Collections.sort(boxes, (a, b) -> {
                int rowA = a[1] / 24, rowB = b[1] / 24;
                return rowA != rowB ? Integer.compare(rowA, rowB) : Integer.compare(a[0], b[0]);
            });
            return boxes;
        } finally {
            if (scaled != src) scaled.recycle();
        }
    }

    /**
     * Native-resolution pass for text too small for {@link #detect}'s whole-frame resize (pillar
     * placards a few pixels tall after it): detector tiles stitched into one map. Only boxes the
     * whole-frame pass did not already cover are returned, so its reads are unchanged.
     */
    private List<int[]> detectFine(Bitmap src, List<int[]> coarse) {
        List<int[]> out = new ArrayList<>();
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= detW && sh <= detH) return out;
        float s = 1f;
        while (s > 0.25f && tileCount(Math.round(sw * s), detW) * tileCount(Math.round(sh * s), detH) > MAX_FINE_TILES) {
            s *= 0.8f;
        }
        int fw = Math.max(1, Math.round(sw * s)), fh = Math.max(1, Math.round(sh * s));
        Bitmap img = s < 1f ? Bitmap.createScaledBitmap(src, fw, fh, true) : src;
        try {
            int[] oshape = det.getOutputTensor(0).shape();
            int oh, ow;
            if (oshape.length == 4 && oshape[1] == 1) { oh = oshape[2]; ow = oshape[3]; }
            else if (oshape.length == 4) { oh = oshape[1]; ow = oshape[2]; }
            else if (oshape.length == 3) { oh = oshape[1]; ow = oshape[2]; }
            else { oh = detH; ow = detW; }
            if (detInput == null) {
                detInput = ByteBuffer.allocateDirect(4 * 3 * detH * detW).order(ByteOrder.nativeOrder());
            }
            if (detOutput == null || detOutput.capacity() != 4 * oh * ow) {
                detOutput = ByteBuffer.allocateDirect(4 * oh * ow).order(ByteOrder.nativeOrder());
            }
            if (tilePixels == null || tilePixels.length != detW * detH) tilePixels = new int[detW * detH];
            float[] map = new float[fw * fh];
            float[] tileProb = new float[oh * ow];
            for (int ty : tileStarts(fh, detH)) {
                for (int tx : tileStarts(fw, detW)) {
                    int tw = Math.min(detW, fw - tx), th = Math.min(detH, fh - ty);
                    img.getPixels(tilePixels, 0, detW, tx, ty, tw, th);
                    fillTile(tilePixels, tw, th, detInput, detNchw, detW, detH);
                    detInput.rewind();
                    detOutput.rewind();
                    det.run(detInput, detOutput);
                    detOutput.rewind();
                    detOutput.asFloatBuffer().get(tileProb);
                    for (int y = 0; y < th; y++) {
                        int orow = Math.min(oh - 1, y * oh / detH) * ow;
                        int row = (ty + y) * fw + tx;
                        for (int x = 0; x < tw; x++) {
                            float v = tileProb[orow + Math.min(ow - 1, x * ow / detW)];
                            if (v > map[row + x]) map[row + x] = v;
                        }
                    }
                }
            }
            List<ConnectedComponents.Component> comps =
                    ConnectedComponents.label(map, fw, fh, DET_BIN_THRESH, DET_MIN_AREA);
            float inv = 1f / s;
            for (ConnectedComponents.Component c : comps) {
                if (c.meanScore() < DET_BOX_THRESH) continue;
                int[] u = ConnectedComponents.unclip(c, DET_UNCLIP_RATIO, fw, fh);
                int x0 = Math.round(u[0] * inv);
                int y0 = Math.round(u[1] * inv);
                int x1 = Math.round((u[2] + 1) * inv) - 1;
                int y1 = Math.round((u[3] + 1) * inv) - 1;
                int margin = Math.round((y1 - y0 + 1) * BOX_MARGIN_FRAC);
                x0 = clamp(x0 - margin, 0, sw - 1);
                y0 = clamp(y0 - margin, 0, sh - 1);
                x1 = clamp(x1 + margin, 0, sw - 1);
                y1 = clamp(y1 + margin, 0, sh - 1);
                if (x1 <= x0 || y1 <= y0) continue;
                int[] box = {x0, y0, x1, y1, c.area};
                if (!covered(box, coarse)) out.add(box);
            }
            Collections.sort(out, (a, b) -> Integer.compare(b[4], a[4]));
            if (out.size() > MAX_FINE_BOXES) out = new ArrayList<>(out.subList(0, MAX_FINE_BOXES));
            Collections.sort(out, (a, b) -> {
                int rowA = a[1] / 24, rowB = b[1] / 24;
                return rowA != rowB ? Integer.compare(rowA, rowB) : Integer.compare(a[0], b[0]);
            });
            return out;
        } finally {
            if (img != src) img.recycle();
        }
    }

    static int tileCount(int n, int tile) {
        return n <= tile ? 1 : (n + tile - 1) / tile;
    }

    /** Evenly spread tile origins covering {@code n} with the fewest {@code tile}-sized tiles. */
    static int[] tileStarts(int n, int tile) {
        int c = tileCount(n, tile);
        int[] s = new int[c];
        for (int i = 1; i < c; i++) s[i] = Math.round((float) i * (n - tile) / (c - 1));
        return s;
    }

    /** True when most of {@code box} lies inside one of {@code others}. */
    static boolean covered(int[] box, List<int[]> others) {
        long area = (long) (box[2] - box[0] + 1) * (box[3] - box[1] + 1);
        if (area <= 0) return true;
        for (int[] o : others) {
            long iw = Math.min(box[2], o[2]) - Math.max(box[0], o[0]) + 1;
            long ih = Math.min(box[3], o[3]) - Math.max(box[1], o[1]) + 1;
            if (iw > 0 && ih > 0 && iw * ih >= FINE_COVERED_FRAC * area) return true;
        }
        return false;
    }

    /** ImageNet-normalised {@code tw×th} pixels (row stride {@code w}), padded with 0 (the mean) to {@code w×h}. */
    private static void fillTile(int[] px, int tw, int th, ByteBuffer buf, boolean nchw, int w, int h) {
        buf.rewind();
        if (!nchw) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (x < tw && y < th) {
                        int p = px[y * w + x];
                        buf.putFloat((((p >> 16) & 0xFF) / 255f - IMAGENET_MEAN[0]) / IMAGENET_STD[0]);
                        buf.putFloat((((p >> 8) & 0xFF) / 255f - IMAGENET_MEAN[1]) / IMAGENET_STD[1]);
                        buf.putFloat(((p & 0xFF) / 255f - IMAGENET_MEAN[2]) / IMAGENET_STD[2]);
                    } else {
                        buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f);
                    }
                }
            }
        } else {
            for (int c = 0; c < 3; c++) {
                int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        buf.putFloat(x < tw && y < th
                                ? (((px[y * w + x] >> shift) & 0xFF) / 255f - IMAGENET_MEAN[c]) / IMAGENET_STD[c]
                                : 0f);
                    }
                }
            }
        }
    }

    // ==================== RECOGNITION ====================

    private CtcDecoder.Decoded recognise(Bitmap crop) {
        int targetW;
        float aspect = (float) crop.getWidth() / Math.max(1, crop.getHeight());
        int natural = Math.round(recH * aspect);
        if (recFixedW > 0) {
            targetW = recFixedW;
        } else {
            targetW = clamp(natural, REC_MIN_W, REC_MAX_W);
            targetW = ((targetW + 7) / 8) * 8;    // keep the CNN stride happy
            if (targetW != recInputW) {
                rec.resizeInput(0, recNchw ? new int[] {1, 3, recH, targetW} : new int[] {1, recH, targetW, 3});
                rec.allocateTensors();
                recInputW = targetW;
                recInput = null;
            }
        }
        int contentW = Math.min(targetW, Math.max(1, natural));
        Bitmap scaled = Bitmap.createScaledBitmap(crop, contentW, recH, true);
        try {
            if (recInput == null || recInput.capacity() != 4 * 3 * recH * targetW) {
                recInput = ByteBuffer.allocateDirect(4 * 3 * recH * targetW).order(ByteOrder.nativeOrder());
            }
            recInput.rewind();
            // Pad on the right with normalised 0 (= mid-grey), PaddleOCR style.
            fillSignedNormalised(scaled, recInput, recNchw, targetW);
            int[] oshape = rec.getOutputTensor(0).shape();
            int steps = oshape.length >= 3 ? oshape[oshape.length - 2] : 1;
            int classes = oshape[oshape.length - 1];
            if (recOutput == null || recOutput.capacity() != 4 * steps * classes) {
                recOutput = ByteBuffer.allocateDirect(4 * steps * classes).order(ByteOrder.nativeOrder());
                recFlat = new float[steps * classes];
            }
            ByteBuffer outBuf = recOutput;
            outBuf.rewind();
            recInput.rewind();
            rec.run(recInput, outBuf);
            outBuf.rewind();
            float[] flat = recFlat;
            outBuf.asFloatBuffer().get(flat);
            boolean isProb = true;
            for (float v : flat) { if (v > 1.0001f || v < -0.0001f) { isProb = false; break; } }
            return CtcDecoder.decodeFlat(flat, steps, classes, charset, 0, isProb);
        } finally {
            if (scaled != crop) scaled.recycle();
        }
    }

    // ==================== PIXEL PLUMBING ====================

    /** (x/255 - mean) / std, RGB, into an NHWC or NCHW float buffer of width {@code bufW}. */
    private static void fillNormalised(Bitmap bmp, ByteBuffer buf, boolean nchw,
                                       float[] mean, float[] std, int bufW) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        buf.rewind();
        if (!nchw) {
            for (int i = 0; i < w * h; i++) {
                int p = px[i];
                buf.putFloat((((p >> 16) & 0xFF) / 255f - mean[0]) / std[0]);
                buf.putFloat((((p >> 8) & 0xFF) / 255f - mean[1]) / std[1]);
                buf.putFloat(((p & 0xFF) / 255f - mean[2]) / std[2]);
            }
        } else {
            for (int c = 0; c < 3; c++) {
                int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
                for (int i = 0; i < w * h; i++) {
                    buf.putFloat((((px[i] >> shift) & 0xFF) / 255f - mean[c]) / std[c]);
                }
            }
        }
    }

    /** (x/255 - 0.5) / 0.5 with right padding to {@code targetW}. */
    private static void fillSignedNormalised(Bitmap bmp, ByteBuffer buf, boolean nchw, int targetW) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        buf.rewind();
        if (!nchw) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < targetW; x++) {
                    if (x < w) {
                        int p = px[y * w + x];
                        buf.putFloat((((p >> 16) & 0xFF) / 255f - 0.5f) / 0.5f);
                        buf.putFloat((((p >> 8) & 0xFF) / 255f - 0.5f) / 0.5f);
                        buf.putFloat(((p & 0xFF) / 255f - 0.5f) / 0.5f);
                    } else {
                        buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f);
                    }
                }
            }
        } else {
            for (int c = 0; c < 3; c++) {
                int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < targetW; x++) {
                        buf.putFloat(x < w ? ((((px[y * w + x] >> shift) & 0xFF) / 255f - 0.5f) / 0.5f) : 0f);
                    }
                }
            }
        }
    }

    // ==================== LOADING ====================

    private static File externalFile(String name) {
        return new File(EXTERNAL_MODEL_DIR, name);
    }

    private static MappedByteBuffer loadModel(Context ctx, String name, boolean external) throws Exception {
        if (external) {
            File ext = externalFile(name);
            try (FileInputStream in = new FileInputStream(ext); FileChannel ch = in.getChannel()) {
                return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            }
        }
        return FileUtil.loadMappedFile(ctx, ASSET_PREFIX + name);
    }

    private static List<String> loadCharset(Context ctx, boolean external) throws Exception {
        InputStream in = external
                ? new FileInputStream(externalFile(CHARSET_FILE))
                : ctx.getAssets().open(ASSET_PREFIX + CHARSET_FILE);
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // PaddleOCR dictionaries have exactly one symbol per line; an
                // empty line at the end is a trailing newline, not a symbol.
                if (line.isEmpty()) continue;
                lines.add(line);
            }
        }
        return lines;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
