package com.overdrive.app.ai

import android.content.Context
import com.overdrive.app.logging.DaemonLogger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO Detection Result
 */
data class Detection(
    val classId: Int,
    val confidence: Float,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

/**
 * YOLO TensorFlow Lite Detector — CPU-only (XNNPACK).
 *
 * Model-agnostic across the Ultralytics family: init() probes the loaded
 * asset's tensors and routes to the matching parser — legacy raw-anchor
 * heads ([1,4+C,N], host NMS; yolo11n) or end-to-end NMS-free heads
 * ([1,D,6]; YOLO26). Shipped asset: yolo26n dynamic-range-quantized
 * (INT8 weights / float activations — full-integer quantization breaks
 * the e2e decode head, see dev/export_yolo26_int8.py VALIDATED FINDINGS).
 *
 * **Why CPU and not GPU on this hardware.** The Snapdragon 662 / Adreno 610
 * is a unified-memory SoC: the H.265 hardware encoder, the Adreno GPU's
 * compute units, and the CPU all share one DDR4 memory controller. When
 * YOLO ran via the TFLite GPU delegate concurrently with the surveillance
 * recording pipeline, OpenCL kernels saturated Adreno's compute units AND
 * the memory bus simultaneously. The encoder's per-frame input fetch + ref
 * frame access lost bandwidth, which manifested as 200–300 ms eglSwapBuffers
 * stalls on the encoder GL thread — visible in recordings as freeze+skip
 * during event windows where YOLO was busy.
 *
 * Tier 1+2 (separate AI-lane GL thread, PBO async readback) eliminated
 * GL-pipeline contention but did NOT touch the underlying memory-bandwidth
 * contention because YOLO inference itself was still GPU-bound. The only
 * physical bypass is to move inference off the GPU entirely.
 *
 * **Why XNNPACK 4-thread.** TFLite's CPU backend ships XNNPACK by default
 * (since 2.5). On ARM it dispatches to NEON SIMD kernels, and at 4 threads
 * the inference cost on this hardware is ~200–300 ms vs ~50–80 ms via GPU
 * delegate. That is well within {@code AI_COOLDOWN_MS = 500 ms}, so the
 * trigger pathway sees no regression. The 150–200 ms additional latency is
 * invisible to the user-facing trigger contract.
 *
 * **Thread isolation strategy (Android 10/11/12 portable).** The
 * {@code aiExecutor} thread that calls into this detector runs at
 * {@code Process.THREAD_PRIORITY_BACKGROUND} (nice +10). XNNPACK's worker
 * pthreads inherit this nice value at spawn time. The encoder/drainer
 * threads run at {@code THREAD_PRIORITY_FOREGROUND} (nice -2), giving a
 * 12-point CFS priority gradient — the encoder side wins scheduler
 * contention by ~10× weight regardless of which cores either thread lands
 * on. On Android 10 the priority demotion ALSO confines these threads to
 * the {@code background} cpuset (cores 0-3, A53 silver cluster); on
 * Android 11+ EAS scheduling can migrate them under load, but the CFS
 * gradient alone is what's portable and what actually keeps the encoder
 * fed. The aiExecutor ALSO re-applies {@code THREAD_PRIORITY_BACKGROUND}
 * at task entry as a defense against EAS migration that may otherwise
 * reset the thread's priority class on long-lived executors.
 *
 * **Why not NNAPI.** Field-tested on this hardware: ~538 of ~546 ops fall
 * through to XNNPACK on CPU anyway (the NNAPI driver only accelerates a
 * handful of ops). Effective inference time ≈ pure CPU mode minus a small
 * dispatch overhead — no benefit, more code surface, more failure modes.
 *
 * SOTA Implementation properties retained from prior version:
 * - Native C++ ImageProcessor (SIMD-accelerated bilinear resize + normalize)
 * - Pre-allocated buffers (zero GC churn)
 * - Cache-friendly output parsing
 * - Height filter before NMS
 * - Ghost filter (max 50 detections)
 */
class YoloDetector(private val context: Context) {

    private val logger = DaemonLogger.getInstance("YoloDetector")

    private var interpreter: Interpreter? = null

    // Monitor that mutually excludes inference (interp.run) from
    // close() / re-init. Without it, a UI/IPC-thread close() can free
    // the native TFLite interpreter while aiExecutor is mid-detect,
    // causing a SIGSEGV in tensorflowlite_jni. The Java-side null
    // snapshot in the engine guards null-deref but not use-after-free
    // inside the native run.
    private val interpLock = Any()

    // SOTA: Pre-allocate all buffers to avoid GC
    private var inputImageBuffer: TensorImage? = null
    private var outputBuffer: ByteBuffer? = null
    // Cache the FP32 view instead of allocating a FloatBuffer on every inference.
    private var floatOutputView: java.nio.FloatBuffer? = null

    // Reusable shaped input buffer. Re-create only when image dimensions
    // change (rare — quadrant size is fixed at startup). Without this,
    // every detect() allocated a fresh TensorBuffer + ByteBuffer.wrap →
    // ~1 MB short-lived garbage per inference, contradicting the class's
    // "zero GC churn" promise. Class-field allocation + dim guard runs
    // O(1) when dims match.
    private var shapedBufferW: Int = -1
    private var shapedBufferH: Int = -1
    private var shapedBuffer: TensorBuffer? = null
    private var floatOutput: FloatArray? = null

    // Pre-extracted box-coords scratch reused across detect() calls. Sized
    // numBoxes*4 = 33600 floats = 134 KB; allocating it per inference was
    // ~1 MB/s of short-lived heap garbage feeding into the same GC that
    // serves the encoder drainer thread. Detector is called on a single
    // aiExecutor thread; no synchronization needed beyond interpLock.
    private var boxesScratch: FloatArray? = null
    // NMS sort working copy. Replaces sortedByDescending(), which allocated
    // a fresh List + lambda per call.
    private var nmsScratch: Array<Detection?>? = null

    // Model configuration. Exactly ONE model ships in the APK; the first
    // asset found in this list is loaded. Canonical name first (yolo26n —
    // the deployed END2END DRQ model), legacy name second so a rollback
    // (restoring the old yolo11n FP16 asset under its original name) needs
    // no code change. The detector identifies the model's layout/dtype by
    // probing its tensors in init(), never by filename.
    private val modelPathCandidates = listOf(
        "models/yolo26n.tflite",
        "models/yolo11n.tflite",
    )
    // Probed from input tensor 0 in init() (Ultralytics NHWC [1,H,W,3]);
    // 640 is the shipped asset's value and the fallback if the shape is
    // unavailable. Square-enforced in init().
    private var inputSize = 640

    // INT8 / FP32 model auto-detection. The Android side stays compatible
    // with both yolo11n.tflite variants (FP32 default, INT8 produced by
    // dev/quantize_yolo_int8.py) — init() inspects the loaded interpreter
    // and routes preprocessing accordingly. There is no per-detect()
    // overhead from this; the routing decision is cached.
    //
    // FP32 path: ImageProcessor does Resize + Normalize(0..1); output is
    //   already float, no dequant needed.
    // INT8 path: ImageProcessor does Resize only (the int8 input tensor's
    //   embedded scale/zero_point handles the [0,255] -> int8 mapping
    //   inside the interpreter); output is int8 and must be dequantized
    //   to float via (raw - zeroPoint) * scale before parseOutput.
    //
    // outputIsQuantized governs the output post-processing path. For
    // YOLOv11n int8 export the Ultralytics pipeline emits a single output
    // tensor with shape [1, 84, 8400] of dtype UINT8 with non-trivial
    // (scale, zero_point) — same shape as FP32 so parseOutput's iteration
    // is unchanged after dequant.
    private var inputIsQuantized = false
    private var outputIsQuantized = false
    private var outputScale = 0f
    private var outputZeroPoint = 0

    // Output LAYOUT auto-detection (sibling of the dtype auto-detection above).
    // Two supported head formats, probed from output tensor 0's shape in init():
    //
    //   LEGACY   [1, 4+C, N]  (yolo11n: [1, 84, 8400]) — raw anchors, class-score
    //            matrix, host-side NMS. This is the shipped model's format and
    //            its code path below is UNCHANGED — with the current asset the
    //            detector behaves byte-identically to before this field existed.
    //
    //   END2END  [1, D, 6]    (Ultralytics end-to-end exports, e.g. YOLO26:
    //            [1, 300, 6]) — each row is (x1, y1, x2, y2, score, class),
    //            already NMS-resolved inside the graph. Rows are padded with
    //            score=0, which the caller's confThreshold naturally skips.
    //
    // Anything else fails init() loudly rather than parsing garbage — an
    // unrecognized head must never silently produce zero detections forever
    // (that would read downstream as "YOLO saw nothing" on every real event).
    private var outputIsEndToEnd = false
    private var e2eMaxDet = 0
    private var legacyNumBoxes = 8400
    private var legacyNumChannels = 84
    private var outputElements = 84 * 8400
    private var int8OutputBuffer: ByteArray? = null  // raw output for int8 path

    // SOTA: Native C++ image processor (SIMD-accelerated bilinear resize
    // + optional normalize). Built lazily in init() once we know the
    // input tensor dtype.
    private var imageProcessor: ImageProcessor? = null

    // Non-square crops (the 320×240 mosaic quadrant) are letterboxed instead of
    // stretched to square; the 4:3→1:1 stretch made painted pillars read as people.
    // Square crops never touch these. Cached per crop size; guarded by interpLock.
    private class LetterboxGeometry(val padX: Float, val padY: Float, val contentW: Int, val contentH: Int)
    private var normalizeProcessor: ImageProcessor? = null
    private var letterboxProcessor: ImageProcessor? = null
    private var letterboxGeom: LetterboxGeometry? = null
    private var letterboxSrcW = -1
    private var letterboxSrcH = -1
    private var letterboxBytes: ByteArray? = null
    private var letterboxBuffer: TensorBuffer? = null
    private var letterboxImage: TensorImage? = null

    // COCO class IDs
    companion object {
        // Ultralytics' letterbox fill value (the grey the model was trained with).
        private const val LETTERBOX_PAD: Byte = 114

        const val CLASS_PERSON = 0
        const val CLASS_BICYCLE = 1
        const val CLASS_CAR = 2
        const val CLASS_MOTORCYCLE = 3
        const val CLASS_AIRPLANE = 4
        const val CLASS_BUS = 5
        const val CLASS_TRAIN = 6
        const val CLASS_TRUCK = 7
        const val CLASS_BOAT = 8
        const val CLASS_BIRD = 14
        const val CLASS_CAT = 15
        const val CLASS_DOG = 16
        const val CLASS_HORSE = 17
        const val CLASS_SHEEP = 18
        const val CLASS_COW = 19
        const val CLASS_ELEPHANT = 20
        const val CLASS_BEAR = 21
        const val CLASS_ZEBRA = 22
        const val CLASS_GIRAFFE = 23

        // No-capture lambda → static singleton; reused across all nms() calls
        // so the comparator instance never allocates per inference.
        private val NMS_COMPARATOR: java.util.Comparator<Detection?> =
            java.util.Comparator { a, b ->
                val ac = a?.confidence ?: Float.NEGATIVE_INFINITY
                val bc = b?.confidence ?: Float.NEGATIVE_INFINITY
                bc.compareTo(ac)
            }
    }
    
    /**
     * Initialize the detector. CPU-only (XNNPACK 4-thread). See class
     * doc for the rationale on why GPU/NNAPI tiers were removed.
     */
    fun init(): Boolean {
        try {
            // Load TFLite's CPU JNI library explicitly — daemon-mode
            // processes don't always run JVM-side static linking
            // automatically. tensorflowlite_gpu_jni is intentionally not
            // loaded; the GPU delegate is no longer a dependency.
            try {
                System.loadLibrary("tensorflowlite_jni")
                logger.info("TFLite native library loaded (CPU-only)")
            } catch (e: UnsatisfiedLinkError) {
                logger.error("Failed to load TFLite native library: ${e.message}")
                return false
            }

            // Load the first bundled model asset (canonical name first,
            // legacy fallback — see modelPathCandidates).
            var loaded: java.nio.MappedByteBuffer? = null
            var loadedPath: String? = null
            for (candidate in modelPathCandidates) {
                try {
                    loaded = FileUtil.loadMappedFile(context, candidate)
                    loadedPath = candidate
                    break
                } catch (e: Exception) {
                    // Asset absent under this name — try the next candidate.
                }
            }
            if (loaded == null) {
                logger.error("No model asset found (tried: $modelPathCandidates)")
                return false
            }
            logger.info("Loading model asset: $loadedPath")
            val modelFile = loaded

            // CPU XNNPACK, 4 threads. Worker pthreads inherit nice +10
            // from the calling aiExecutor thread; the 12-point CFS gradient
            // versus the encoder/drainer (nice -2) keeps the encoder fed
            // even when YOLO threads happen to land on the same physical
            // core.
            try {
                val cpuOptions = Interpreter.Options()
                cpuOptions.setNumThreads(4)
                interpreter = Interpreter(modelFile, cpuOptions)
                interpreter!!.allocateTensors()
            } catch (e: Exception) {
                logger.error("Failed to initialize TFLite CPU interpreter: ${e.message}", e)
                // Audit fix: allocateTensors() can throw AFTER the native
                // interpreter was created — close it so a failed init never
                // leaks native memory or leaves a live interpreter on a
                // half-initialized instance.
                try { interpreter?.close() } catch (ignored: Exception) {}
                interpreter = null
                return false
            }

            // Auto-detect FP32 vs INT8 model. Probe input tensor 0 + output
            // tensor 0 dtype. yolo11n's standard export uses FLOAT32; the
            // dev/quantize_yolo_int8.py script produces a UINT8/UINT8 variant.
            val interp = interpreter!!
            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            val inputDtype = inputTensor.dataType()
            val outputDtype = outputTensor.dataType()
            inputIsQuantized = (inputDtype == DataType.UINT8 || inputDtype == DataType.INT8)
            outputIsQuantized = (outputDtype == DataType.UINT8 || outputDtype == DataType.INT8)
            if (outputIsQuantized) {
                val q = outputTensor.quantizationParams()
                outputScale = q.scale
                outputZeroPoint = q.zeroPoint
            }

            // Probe the model's INPUT size instead of assuming 640. Ultralytics
            // TFLite exports are NHWC [1, H, W, 3]; the pipeline assumes a square
            // input (single scale factor per axis derived from inputSize), so a
            // non-square export is rejected loudly rather than silently skewing
            // every box. With the shipped 640×640 asset this is a no-op.
            val inShape = inputTensor.shape()
            if (inShape.size == 4 && inShape[1] == inShape[2] && inShape[1] > 0) {
                inputSize = inShape[1]
            } else if (inShape.size == 4 && inShape[1] != inShape[2]) {
                logger.error("Unsupported non-square model input ${inShape.contentToString()} — refusing to init")
                interpreter?.close()
                interpreter = null
                return false
            }

            // Probe the model's OUTPUT layout (see field docs above):
            //   [1, 4+C, N] (N > 4+C)  → LEGACY raw-anchor head + host NMS
            //   [1, D, 6]              → END2END (already NMS-resolved rows)
            // Unknown layouts fail init() loudly: a mis-parsed head would read
            // downstream as "YOLO saw nothing" on every real event — the exact
            // silent false-negative mode this pipeline treats as a defect.
            val outShape = outputTensor.shape()
            if (outShape.size == 3 && outShape[2] == 6 && outShape[1] > 0) {
                outputIsEndToEnd = true
                e2eMaxDet = outShape[1]
                outputElements = e2eMaxDet * 6
            } else if (outShape.size == 3 && outShape[1] >= 5 && outShape[2] > outShape[1]) {
                outputIsEndToEnd = false
                legacyNumChannels = outShape[1]
                legacyNumBoxes = outShape[2]
                outputElements = legacyNumChannels * legacyNumBoxes
            } else {
                logger.error("Unsupported model output layout ${outShape.contentToString()} — refusing to init")
                interpreter?.close()
                interpreter = null
                return false
            }

            // Build the preprocessing pipeline that matches the model's
            // expected input dtype:
            //   - FP32 model: resize + normalize (0..255 -> 0.0..1.0)
            //   - INT8 model: resize only; the interpreter's embedded
            //     input quantization params handle the uint8 -> int8 mapping
            //     internally with no host-side normalize step.
            inputImageBuffer = TensorImage(DataType.UINT8)
            imageProcessor = if (inputIsQuantized) {
                ImageProcessor.Builder()
                    .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                    .build()
            } else {
                ImageProcessor.Builder()
                    .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                    .add(NormalizeOp(0f, 255f))
                    .build()
            }
            // Letterbox path: same normalize step, applied after padding.
            normalizeProcessor = if (inputIsQuantized) null else
                ImageProcessor.Builder().add(NormalizeOp(0f, 255f)).build()
            letterboxGeom = null
            letterboxSrcW = -1
            letterboxSrcH = -1

            // Pre-allocate output buffer sized to the actual tensor layout+dtype
            // (outputElements was computed from the probed shape above).
            // FP32: 4 bytes/element. INT8/UINT8: 1 byte/element.
            val outputBytes = outputElements * if (outputIsQuantized) 1 else 4
            outputBuffer = ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
            floatOutputView = if (outputIsQuantized) null else outputBuffer!!.asFloatBuffer()
            if (outputIsQuantized) {
                int8OutputBuffer = ByteArray(outputElements)
            }

            val mode = if (inputIsQuantized && outputIsQuantized) "INT8"
                       else if (!inputIsQuantized && !outputIsQuantized) "FP32"
                       else "MIXED($inputDtype/$outputDtype)"
            val layout = if (outputIsEndToEnd) "END2END[$e2eMaxDet,6]"
                         else "LEGACY[$legacyNumChannels,$legacyNumBoxes]+hostNMS"
            logger.info("CPU XNNPACK initialized (4 threads, $mode model, $layout, in=${inputSize}x$inputSize, " +
                    (if (outputIsQuantized) "outScale=$outputScale outZp=$outputZeroPoint, " else "") +
                    "encoder-isolated via nice gradient)")

            logger.info("Model loaded successfully ($mode)")
            return true
        } catch (e: Exception) {
            logger.error("Failed to load model: ${e.message}", e)
            // Audit fix: any throw between interpreter creation and the
            // successful return (tensor probes, ImageProcessor build) must
            // not leak the live native interpreter.
            try { interpreter?.close() } catch (ignored: Exception) {}
            interpreter = null
            return false
        }
    }
    
    /**
     * SOTA Detection with native C++ preprocessing
     * 
     * @param rgbData RGB888 byte array (vertically flipped for OpenGL)
     * @param width Image width
     * @param height Image height
     * @param confThreshold Confidence threshold
     * @param detectPerson Detect person class
     * @param detectCar Detect vehicle classes
     * @param detectAnimal Detect animal classes
     * @param detectBike Detect bicycle/motorcycle
     * @param minRelativeHeight Minimum object height relative to QUADRANT (SOTA: 15% rule)
     *                          This is applied per-quadrant in 2x2 mosaic grid
     *
     * Non-square crops are letterboxed (aspect kept, grey padding); boxes are
     * returned in the crop's own pixel space either way.
     */
    fun detect(
        rgbData: ByteArray,
        width: Int,
        height: Int,
        confThreshold: Float = 0.25f,
        detectPerson: Boolean = true,
        detectCar: Boolean = true,
        detectAnimal: Boolean = false,
        detectBike: Boolean = false,
        minRelativeHeight: Float = 0.15f  // SOTA: 15% of QUADRANT height (~5m for person)
    ): List<Detection> {

        // FIX (Bug B): if the caller has disabled every detectable class, skip the
        // entire inference path. This is the belt-and-braces defence behind the
        // engine's aiEnabled gate and ensures any future caller path benefits too.
        if (!detectPerson && !detectCar && !detectAnimal && !detectBike) {
            return emptyList()
        }

        if (width <= 0 || height <= 0) return emptyList()

        // CRITICAL: Color channel handling
        // GpuDownscaler outputs RGB from OpenGL (RGBA_8888 with A dropped)
        // The data is already in RGB format - NO SWAP NEEDED
        // Image is now correctly oriented (vertical flip applied in GpuDownscaler)
        val processedData = rgbData  // Use directly - already RGB from GpuDownscaler

        // Synchronize against close(). Inside the lock we're guaranteed the
        // native interpreter is alive for the duration of run(). Lock cost
        // on the single-thread aiExecutor is uncontended steady-state; the
        // brief contention with close() is fine — close happens rarely
        // (toggle off, daemon shutdown).
        synchronized(interpLock) {
            val interp = interpreter ?: return emptyList()

            // Reuse the shaped TensorBuffer across calls. Re-allocate only on
            // dimension change (rare). Same for the float output array.
            var sb = shapedBuffer
            if (sb == null || shapedBufferW != width || shapedBufferH != height) {
                sb = TensorBuffer.createFixedSize(intArrayOf(height, width, 3), DataType.UINT8)
                shapedBuffer = sb
                shapedBufferW = width
                shapedBufferH = height
            }
            sb.loadBuffer(ByteBuffer.wrap(processedData))

            inputImageBuffer!!.load(sb)

            // SOTA: Process with native C++ ops. Pipeline differs by model
            // dtype: FP32 path normalizes 0..255 -> 0.0..1.0; INT8 path is
            // resize-only and the interpreter's input quantization handles
            // the uint8 mapping internally.
            val geom = if (width != height) letterboxGeometry(width, height) else null
            val tensorImage = if (geom != null) letterbox(inputImageBuffer!!, geom)
                              else imageProcessor!!.process(inputImageBuffer)

            // Run inference (CPU XNNPACK). interp.run() blocks until the
            // last layer is computed; there's no async/queue model on the
            // CPU backend (unlike the previous GPU delegate).
            outputBuffer!!.rewind()
            interp.run(tensorImage.buffer, outputBuffer)
            outputBuffer!!.rewind()

            var fo = floatOutput
            if (fo == null || fo.size != outputElements) {
                fo = FloatArray(outputElements)
                floatOutput = fo
            }

            if (outputIsQuantized) {
                // INT8 output path: bulk-copy the byte tensor to a Java
                // byte[] in one JNI hop, then dequantize to float in
                // Java loop. Dequant: f = (raw - zeroPoint) * scale.
                // Cost: 84*8400 = 705,600 multiplications (~3-5 ms on
                // Cortex-A53), still much cheaper than the FP32 model's
                // larger XNNPACK kernel set inside interp.run().
                val raw = int8OutputBuffer!!
                outputBuffer!!.get(raw, 0, raw.size)
                // For UINT8 outputs, raw value is in [0, 255]; for INT8,
                // ByteBuffer.get returns signed [-128, 127] which is
                // already the correct interpretation. The interpreter's
                // quantization params encode which dtype was used.
                val scale = outputScale
                val zp = outputZeroPoint
                val outDtype = interp.getOutputTensor(0).dataType()
                if (outDtype == DataType.UINT8) {
                    var i = 0
                    while (i < raw.size) {
                        // Unsigned read: raw[i] is a Java signed byte; mask
                        // with 0xFF to get the [0, 255] value the model
                        // produced.
                        fo[i] = ((raw[i].toInt() and 0xFF) - zp) * scale
                        i++
                    }
                } else {
                    var i = 0
                    while (i < raw.size) {
                        fo[i] = (raw[i].toInt() - zp) * scale
                        i++
                    }
                }
            } else {
                // FP32 output path: bulk-copy from direct ByteBuffer to the
                // Java float[] in one JNI call.
                floatOutputView!!.apply { rewind(); get(fo) }
            }
            // parseOutput INSIDE the lock, deliberately.
            //
            // It reads/writes `boxesScratch` and `nmsScratch` — shared instance
            // fields, not locals. Their thread-safety previously rested on the
            // implicit invariant that every detect() caller runs on the engine's
            // single-thread aiExecutor. That is true today (all five call sites in
            // SurveillanceEngineGpu dispatch onto it, so calls queue rather than
            // overlap), but it is an undocumented cross-class assumption, and the
            // AI-lane watchdog added to the engine is explicitly premised on the
            // lane possibly being held by work it cannot observe. Making the
            // exclusion explicit here means the detector is safe regardless of who
            // calls it, instead of safe-by-coincidence.
            //
            // It also closes a real (if minor) race with close(), which nulls both
            // scratch fields under this lock: parseOutput would otherwise re-create
            // a 134 KB buffer after close() and retain it until the next init().
            //
            // Cost: parseOutput is ~10-20 ms of pure CPU (no JNI, no I/O) on top of
            // a ~250 ms inference that already holds the lock, and the only other
            // contender is close() — which happens on class-toggle or shutdown, and
            // whose whole purpose is to WAIT for in-flight work anyway.
            return if (outputIsEndToEnd) {
                // END2END head (e.g. YOLO26): rows are already NMS-resolved
                // (x1,y1,x2,y2,score,class). Same post-gates as the legacy
                // path (class mask, implausible-class floor, size gates,
                // ghost cap, funnel diagnostics) — only the anchor decode +
                // host NMS disappear because the graph did them.
                parseOutputEndToEnd(
                    fo, width, height, geom, confThreshold,
                    detectPerson, detectCar, detectAnimal, detectBike, minRelativeHeight
                )
            } else {
                parseOutput(
                    fo, width, height, geom, confThreshold,
                    detectPerson, detectCar, detectAnimal, detectBike, minRelativeHeight
                )
            }
        }
    }

    /** Aspect-preserving fit of a non-square crop into the square input, cached per crop size. */
    private fun letterboxGeometry(width: Int, height: Int): LetterboxGeometry {
        val cached = letterboxGeom
        if (cached != null && letterboxSrcW == width && letterboxSrcH == height) return cached
        val scale = minOf(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val contentW = Math.round(width * scale).coerceIn(1, inputSize)
        val contentH = Math.round(height * scale).coerceIn(1, inputSize)
        val geom = LetterboxGeometry(
            ((inputSize - contentW) / 2).toFloat(), ((inputSize - contentH) / 2).toFloat(), contentW, contentH)
        letterboxProcessor = ImageProcessor.Builder()
            .add(ResizeOp(contentH, contentW, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        letterboxBytes = ByteArray(inputSize * inputSize * 3).also { it.fill(LETTERBOX_PAD) }
        letterboxBuffer = TensorBuffer.createFixedSize(intArrayOf(inputSize, inputSize, 3), DataType.UINT8)
        letterboxImage = TensorImage(DataType.UINT8)
        letterboxSrcW = width
        letterboxSrcH = height
        letterboxGeom = geom
        return geom
    }

    /** Resize into the content box and paste onto the grey canvas (padding bytes are never rewritten). */
    private fun letterbox(src: TensorImage, geom: LetterboxGeometry): TensorImage {
        val resized = letterboxProcessor!!.process(src).buffer
        resized.rewind()
        val canvas = letterboxBytes!!
        val rowBytes = geom.contentW * 3
        val padX = geom.padX.toInt()
        val padY = geom.padY.toInt()
        for (row in 0 until geom.contentH) {
            resized.get(canvas, ((padY + row) * inputSize + padX) * 3, rowBytes)
        }
        val buf = letterboxBuffer!!
        buf.loadBuffer(ByteBuffer.wrap(canvas))
        val img = letterboxImage!!
        img.load(buf)
        return normalizeProcessor?.process(img) ?: img
    }
    
    /**
     * SOTA: Cache-friendly output parsing
     * 
     * Optimized memory access pattern to minimize cache misses.
     * Processes output in channel-major order to keep memory accesses sequential.
     */
    private fun parseOutput(
        output: FloatArray,
        imgWidth: Int,
        imgHeight: Int,
        geom: LetterboxGeometry?,
        confThreshold: Float,
        detectPerson: Boolean,
        detectCar: Boolean,
        detectAnimal: Boolean,
        detectBike: Boolean,
        minRelativeHeight: Float
    ): List<Detection> {

        // Probed from the model in init() — 8400/80 for the shipped yolo11n
        // asset, so this is behavior-identical to the previous hardcoding.
        val numBoxes = legacyNumBoxes
        val numClasses = legacyNumChannels - 4

        val scaleX = imgWidth.toFloat() / (geom?.contentW ?: inputSize)
        val scaleY = imgHeight.toFloat() / (geom?.contentH ?: inputSize)

        // Size-gate reference frame.
        //
        // The `/2` here dated from when this method was handed the FULL 2×2 mosaic
        // and had to derive one quadrant's dims from it. The surveillance caller
        // now passes a SINGLE crop — either a 320×240 mosaic quadrant or a 640×640
        // foveated window — so halving produced a reference of 160×120 (confirmed
        // on-device: "quad=160x120" in the raw-funnel diagnostic). Every relative
        // size was therefore computed against half the true extent, i.e. DOUBLED,
        // making the gate ~2× more permissive than the configured minObjectSize
        // (an effective 0.04 against a configured 0.08) and admitting small
        // shadow/foliage blobs the user's setting was meant to exclude.
        //
        // The crop IS the reference frame — use it directly. This TIGHTENS the
        // gate to the configured value, so it is FP-REDUCING; the tradeoff is that
        // a genuinely tiny distant object now has to clear the bar the user
        // actually set. Callers that really do pass a full mosaic would want
        // halving, but none does: the two call sites pass qW×qH from the crop.
        val quadrantHeight = imgHeight
        val quadrantWidth = imgWidth

        // Class-membership bitmask. Every COCO class we care about has id < 24,
        // so a single Long bit-tests in O(1) without allocating an IntRange or
        // a List inside the per-detection loop.
        // MOTORCYCLE belongs to the Bike toggle (audit R3b Ext-11): the API
        // doc, the engine's classFilter taxonomy (Bike = {1 bicycle, 3
        // motorcycle}), the size gates and the canonical-class collapse all
        // put motorcycle under Bike — this mask was the single inconsistent
        // site, so Bike ON + Car OFF silently dropped every motorcycle (and
        // Car ON + Bike OFF leaked them).
        var wantedMask = 0L
        if (detectPerson) wantedMask = wantedMask or (1L shl CLASS_PERSON)
        if (detectCar) {
            wantedMask = wantedMask or (1L shl CLASS_CAR) or (1L shl CLASS_BUS) or
                    (1L shl CLASS_TRUCK) or (1L shl CLASS_TRAIN) or
                    (1L shl CLASS_BOAT) or (1L shl CLASS_AIRPLANE)
        }
        if (detectBike) wantedMask = wantedMask or (1L shl CLASS_BICYCLE) or
                (1L shl CLASS_MOTORCYCLE)
        if (detectAnimal) {
            // 14..23 inclusive
            for (c in CLASS_BIRD..CLASS_GIRAFFE) wantedMask = wantedMask or (1L shl c)
        }

        // Pre-thresholded distance filter values. minRelativeHeight is the
        // base; cars use 1.33×, bikes 0.7× — compute once, compare in loop.
        val carWidthThreshold = minRelativeHeight * 1.33f
        val bikeHeightThreshold = minRelativeHeight * 0.7f

        // Implausible-class confidence floor.
        //
        // train/boat/airplane are retained in the vehicle mask so that a LARGE
        // close vehicle (van, high-sided truck) which YOLO11n mislabels on a dark,
        // fisheye-warped crop still produces a box here. But a parked car is never
        // actually approached by a train, boat or aeroplane, so a LOW-confidence
        // box of those classes is noise — the project's field record shows an
        // earlier low-light experiment abandoned on-car over phantom
        // `class=4 (airplane) @0.29-0.33` detections, which this mask promoted to
        // real vehicle actors. Requiring high confidence cuts that channel while
        // keeping the genuine large-vehicle case (which scores well precisely
        // because the object is big and close). Applied only to the three
        // implausible classes; the caller's own threshold governs everything else.
        //
        // NOTE ON REACH — do not "complete" this by widening the engine filter.
        // The surveillance consumer drops classes 4/6/8 unconditionally
        // (SurveillanceEngineGpu's class-filter step: setObjectFilters only ever
        // emits 0/1/2/3/5/7/14-23, and the no-filter fallback allows the same
        // set). So a box that survives this floor is still discarded downstream,
        // and the retention here is effectively inert for sentry.
        //
        // That is DELIBERATE, not an oversight. Admitting 4/6/8 into the engine
        // would add a whole detection channel whose only unique yield is a vehicle
        // — the class the system already demotes to NOTICE and suppresses via
        // DetectionBaseline — while re-opening the phantom path the field record
        // above got burned by. NMS is class-aware (`det.classId == res.classId`),
        // so a van scored as BOTH truck and train emits both boxes and the engine
        // keeps the truck one; only a van scored EXCLUSIVELY as train/boat/airplane
        // is lost, and paying for that with new false positives on an unattended
        // parked car is the wrong trade. Keep the mask (it costs nothing and keeps
        // these boxes from cannibalising a real vehicle box) and keep the engine
        // filter narrow.
        val implausibleClassMask = (1L shl CLASS_TRAIN) or (1L shl CLASS_BOAT) or
                (1L shl CLASS_AIRPLANE)
        val implausibleClassMinConf = maxOf(confThreshold, 0.55f)

        // Reuse pre-extracted box-coords scratch (134 KB). Re-allocate only
        // if numBoxes ever changes (which it can't with a fixed YOLO11n
        // model, but the guard costs nothing).
        var boxes = boxesScratch
        if (boxes == null || boxes.size != numBoxes * 4) {
            boxes = FloatArray(numBoxes * 4)
            boxesScratch = boxes
        }
        for (i in 0 until numBoxes) {
            val base = i * 4
            boxes[base] = output[i]                       // cx
            boxes[base + 1] = output[numBoxes + i]        // cy
            boxes[base + 2] = output[2 * numBoxes + i]    // w
            boxes[base + 3] = output[3 * numBoxes + i]    // h
        }

        val detections = ArrayList<Detection>(16)

        // ---- RAW FUNNEL DIAGNOSTICS (no gate changes) ----
        //
        // The summary line below reports max_conf over KEPT detections only, so a
        // frame that keeps nothing logs "max_conf=0.000 class=-1" — which is
        // tautological and tells us nothing about what the model actually saw.
        // That made a real field miss unattributable: a close, fisheye-warped
        // person on the right camera produced 0 objects across a 40s clip, and
        // there was no way to tell whether the model scored them 0.24 (a
        // threshold problem) or 0.02 (a model/crop problem) — which need
        // OPPOSITE fixes. Track the raw pre-threshold peak plus a per-gate
        // rejection tally so the next occurrence names its own cause.
        // Diagnostics only: every value here is observed, never acted on.
        var rawPeakConf = 0f
        var rawPeakClass = -1
        var rejConf = 0      // below caller's confThreshold
        var rejImplausible = 0
        var rejUnwanted = 0  // class not in the user's enabled set
        var rejSize = 0      // failed the quadrant-relative size gate
        var peakPersonConf = 0f   // best person-class score BEFORE any gate

        for (i in 0 until numBoxes) {
            val base = i * 4
            val cx = boxes[base]
            val cy = boxes[base + 1]
            val w = boxes[base + 2]
            val h = boxes[base + 3]

            var bestConf = 0f
            var bestClass = -1
            for (c in 0 until numClasses) {
                val conf = output[(4 + c) * numBoxes + i]
                if (conf > bestConf) {
                    bestConf = conf
                    bestClass = c
                }
            }

            // Raw peak across ALL boxes/classes, before any gate.
            if (bestConf > rawPeakConf) {
                rawPeakConf = bestConf
                rawPeakClass = bestClass
            }
            val personConf = output[(4 + CLASS_PERSON) * numBoxes + i]
            if (personConf > peakPersonConf) peakPersonConf = personConf

            if (bestConf < confThreshold) { rejConf++; continue }
            if (bestClass < 0 || bestClass >= 64) continue
            // Implausible-class floor (train/boat/airplane) — see the mask above.
            if ((implausibleClassMask and (1L shl bestClass)) != 0L
                    && bestConf < implausibleClassMinConf) { rejImplausible++; continue }
            if ((wantedMask and (1L shl bestClass)) == 0L) { rejUnwanted++; continue }

            // Convert to image coordinates
            val cxPx = cx * inputSize
            val cyPx = cy * inputSize
            val wPx = w * inputSize
            val hPx = h * inputSize

            val objX: Int
            val objY: Int
            val objW: Int
            val objH: Int
            if (geom != null) {
                // Letterboxed: clip to the content box, then shift out the padding.
                val bx1 = (cxPx - wPx / 2).coerceIn(geom.padX, geom.padX + geom.contentW) - geom.padX
                val bx2 = (cxPx + wPx / 2).coerceIn(geom.padX, geom.padX + geom.contentW) - geom.padX
                val by1 = (cyPx - hPx / 2).coerceIn(geom.padY, geom.padY + geom.contentH) - geom.padY
                val by2 = (cyPx + hPx / 2).coerceIn(geom.padY, geom.padY + geom.contentH) - geom.padY
                objX = (bx1 * scaleX).toInt().coerceIn(0, imgWidth)
                objY = (by1 * scaleY).toInt().coerceIn(0, imgHeight)
                objW = ((bx2 - bx1) * scaleX).toInt().coerceIn(0, imgWidth - objX)
                objH = ((by2 - by1) * scaleY).toInt().coerceIn(0, imgHeight - objY)
            } else {
                objX = ((cxPx - wPx / 2) * scaleX).toInt().coerceIn(0, imgWidth)
                objY = ((cyPx - hPx / 2) * scaleY).toInt().coerceIn(0, imgHeight)
                objW = (wPx * scaleX).toInt().coerceIn(0, imgWidth - objX)
                objH = (hPx * scaleY).toInt().coerceIn(0, imgHeight - objY)
            }

            // Quadrant-relative distance filter (2×2 mosaic). Inlined to a
            // single `when` over bestClass; thresholds were precomputed above.
            val relH = if (quadrantHeight > 0) objH.toFloat() / quadrantHeight else 0f
            val relW = if (quadrantWidth > 0) objW.toFloat() / quadrantWidth else 0f
            val passes = when (bestClass) {
                CLASS_PERSON -> relH >= minRelativeHeight
                CLASS_CAR, CLASS_BUS, CLASS_TRUCK, CLASS_TRAIN -> relW >= carWidthThreshold
                CLASS_BICYCLE, CLASS_MOTORCYCLE -> relH >= bikeHeightThreshold
                else -> relH >= minRelativeHeight
            }
            if (!passes) {
                rejSize++
                // Log the near-miss geometry: a close, fisheye-warped subject can
                // clear the confidence bar and then die HERE, and without the
                // actual ratios there is no way to tell a genuinely tiny far
                // object from a big partial-body blob whose bbox is short.
                if (bestClass == CLASS_PERSON) {
                    logger.info("YOLO size-gate dropped PERSON conf=%.3f relH=%.3f (need %.3f) box=%dx%d quad=%dx%d"
                        .format(bestConf, relH, minRelativeHeight, objW, objH, quadrantWidth, quadrantHeight))
                }
                continue
            }

            detections.add(Detection(bestClass, bestConf, objX, objY, objW, objH))
        }

        // Apply NMS (in-place sort + culling, no per-call lambda allocation).
        val filtered = nms(detections, 0.45f)

        // Ghost filter. TRUNCATE, never clear.
        //
        // This used to return emptyList() above the cap, so a single pathological
        // frame (glare, rain streaks, a genuinely crowded scene) discarded the
        // real person along with the noise — the worst possible behaviour in the
        // highest-activity case, and a silent false negative. `nms()` returns
        // detections in descending-confidence order, so taking the first 50 keeps
        // the most probable ones and drops only the tail.
        //
        // Measured post-NMS counts on real device crops are 1-3, so this branch is
        // effectively unreachable today; it is insurance, not a hot path.
        val final = if (filtered.size > 50) {
            logger.warn("Ghost filter: ${filtered.size} > 50, keeping top 50 by confidence")
            filtered.take(50)
        } else {
            filtered
        }

        // Class-distribution counts. Single pass with bitmask membership tests
        // — replaces four `final.count { ... }` lambda allocations.
        var personCount = 0
        var carCount = 0
        var bikeCount = 0
        var animalCount = 0
        var bestKeptConf = 0f
        var bestKeptClass = -1
        // Mirrors the vehicle set in `wantedMask` above (motorcycle counts
        // under Bike — R3b Ext-11).
        val carMask = (1L shl CLASS_CAR) or (1L shl CLASS_BUS) or
                (1L shl CLASS_TRUCK) or (1L shl CLASS_TRAIN) or
                (1L shl CLASS_BOAT) or (1L shl CLASS_AIRPLANE)
        var animalMask = 0L
        for (c in CLASS_BIRD..CLASS_GIRAFFE) animalMask = animalMask or (1L shl c)
        for (idx in final.indices) {
            val det = final[idx]
            val cid = det.classId
            if (cid == CLASS_PERSON) personCount++
            if (cid == CLASS_BICYCLE || cid == CLASS_MOTORCYCLE) bikeCount++
            if (cid in 0..63) {
                val bit = 1L shl cid
                if ((bit and carMask) != 0L) carCount++
                if ((bit and animalMask) != 0L) animalCount++
            }
            if (det.confidence > bestKeptConf) {
                bestKeptConf = det.confidence
                bestKeptClass = cid
            }
        }

        logger.info("Detected ${final.size} objects: person=$personCount car=$carCount bike=$bikeCount animal=$animalCount (max_conf=${"%.3f".format(bestKeptConf)} class=$bestKeptClass)")

        // Raw-funnel line — only when NOTHING survived, which is the case that was
        // previously unattributable. `raw` is the model's true peak before any gate,
        // `person` its best person-class score, and the rej* tallies say which gate
        // consumed the boxes. Reading it: raw≈0 ⇒ the model genuinely saw nothing
        // (crop/orientation/exposure problem, look at the crop not the thresholds);
        // raw just under conf=${confThreshold} ⇒ a threshold call; rejSize>0 with a
        // healthy person score ⇒ the partial-body/size-gate case; rejUnwanted>0 ⇒ a
        // class-filter mismatch. Emitted at most once per inference and only on the
        // empty path, so it costs nothing in the common case.
        if (final.isEmpty()) {
            logger.info(("YOLO raw funnel: raw=%.3f class=%d person=%.3f | " +
                    "rej conf=%d implausible=%d unwanted=%d size=%d | " +
                    "confThr=%.2f minRelH=%.3f in=%dx%d quad=%dx%d")
                .format(rawPeakConf, rawPeakClass, peakPersonConf,
                        rejConf, rejImplausible, rejUnwanted, rejSize,
                        confThreshold, minRelativeHeight,
                        imgWidth, imgHeight, quadrantWidth, quadrantHeight))
        }

        return final
    }
    
    /**
     * END2END output parsing (Ultralytics NMS-free exports, e.g. YOLO26).
     *
     * Output rows are (x1, y1, x2, y2, score, class), already NMS-resolved
     * inside the graph; unused rows are zero-padded (score 0) and fall out of
     * the confidence gate. This method applies EXACTLY the same post-model
     * gates as the legacy [parseOutput] — class-membership mask,
     * implausible-class confidence floor, quadrant-relative size gates,
     * ghost cap, and the raw-funnel diagnostics — in the same order, so the
     * two heads differ only in what the graph already did (anchor decode +
     * NMS). Any change to a gate here must be mirrored in [parseOutput].
     *
     * Coordinate units: Ultralytics end-to-end TFLite exports have emitted
     * both normalized [0,1] and input-pixel xyxy depending on version.
     * Decided ONCE PER TENSOR (audit fix — a per-row probe could misroute a
     * confident sub-2px-corner pixel-unit box into a large phantom that
     * SURVIVES the size gate, the opposite of the earlier comment's claim):
     * if ANY conf-passing row has a coordinate > 1.5, the whole tensor is in
     * pixel units; otherwise normalized. A tensor cannot mix units, and the
     * reverse misroute is impossible (normalized coords are ≤ 1.0 ≤ 1.5).
     * Shipped-model ground truth: pixel units (validated offline).
     */
    private fun parseOutputEndToEnd(
        output: FloatArray,
        imgWidth: Int,
        imgHeight: Int,
        geom: LetterboxGeometry?,
        confThreshold: Float,
        detectPerson: Boolean,
        detectCar: Boolean,
        detectAnimal: Boolean,
        detectBike: Boolean,
        minRelativeHeight: Float
    ): List<Detection> {

        val scaleX = imgWidth.toFloat() / (geom?.contentW ?: inputSize)
        val scaleY = imgHeight.toFloat() / (geom?.contentH ?: inputSize)

        // Size-gate reference frame: the crop IS the reference frame — see the
        // rationale block in parseOutput (the /2 mosaic-era bug).
        val quadrantHeight = imgHeight
        val quadrantWidth = imgWidth

        // Class-membership bitmask — mirrors parseOutput exactly (incl. the
        // R3b Ext-11 motorcycle-under-Bike fix; see parseOutput's comment).
        var wantedMask = 0L
        if (detectPerson) wantedMask = wantedMask or (1L shl CLASS_PERSON)
        if (detectCar) {
            wantedMask = wantedMask or (1L shl CLASS_CAR) or (1L shl CLASS_BUS) or
                    (1L shl CLASS_TRUCK) or (1L shl CLASS_TRAIN) or
                    (1L shl CLASS_BOAT) or (1L shl CLASS_AIRPLANE)
        }
        if (detectBike) wantedMask = wantedMask or (1L shl CLASS_BICYCLE) or
                (1L shl CLASS_MOTORCYCLE)
        if (detectAnimal) {
            for (c in CLASS_BIRD..CLASS_GIRAFFE) wantedMask = wantedMask or (1L shl c)
        }

        val carWidthThreshold = minRelativeHeight * 1.33f
        val bikeHeightThreshold = minRelativeHeight * 0.7f

        // Implausible-class floor — mirrors parseOutput exactly (see the full
        // rationale there; the engine drops 4/6/8 downstream regardless).
        val implausibleClassMask = (1L shl CLASS_TRAIN) or (1L shl CLASS_BOAT) or
                (1L shl CLASS_AIRPLANE)
        val implausibleClassMinConf = maxOf(confThreshold, 0.55f)

        val detections = ArrayList<Detection>(16)

        // Raw-funnel diagnostics — same contract as parseOutput. rejNms is
        // structurally absent here (the graph resolved overlaps already).
        var rawPeakConf = 0f
        var rawPeakClass = -1
        var rejConf = 0
        var rejImplausible = 0
        var rejUnwanted = 0
        var rejSize = 0
        var peakPersonConf = 0f

        // Per-TENSOR coordinate-unit decision (see method doc): pixel units
        // iff any conf-passing row has a coordinate beyond the normalized
        // range. Zero-padded rows never pass the conf gate so they can't
        // vote. If NO row passes conf, no coordinates are consumed and the
        // verdict is irrelevant.
        var tensorIsPixelUnits = false
        run {
            for (i in 0 until e2eMaxDet) {
                val base = i * 6
                if (output[base + 4] < confThreshold) continue
                if (output[base + 2] > 1.5f || output[base + 3] > 1.5f) {
                    tensorIsPixelUnits = true
                }
                // One conf-passing row with all coords ≤1.5 in a pixel-unit
                // tensor is theoretically possible only for a sub-2px corner
                // box, so keep scanning until a decisive row is found.
                if (tensorIsPixelUnits) break
            }
        }

        for (i in 0 until e2eMaxDet) {
            val base = i * 6
            val score = output[base + 4]
            // Math.round, not toInt() (audit R2 #8): a quantized E2E head
            // dequantizes class ids as (raw−zp)×scale, so an integral id can
            // arrive as e.g. 1.9999 — truncation would silently decode
            // car(2) as bicycle(1). Unreachable with the shipped float-output
            // DRQ model, but the code structurally supports quantized+E2E.
            val classId = Math.round(output[base + 5])

            if (score > rawPeakConf) {
                rawPeakConf = score
                rawPeakClass = classId
            }
            if (classId == CLASS_PERSON && score > peakPersonConf) peakPersonConf = score

            if (score < confThreshold) { rejConf++; continue }
            if (classId < 0 || classId >= 64) continue
            if ((implausibleClassMask and (1L shl classId)) != 0L
                    && score < implausibleClassMinConf) { rejImplausible++; continue }
            if ((wantedMask and (1L shl classId)) == 0L) { rejUnwanted++; continue }

            var x1 = output[base]
            var y1 = output[base + 1]
            var x2 = output[base + 2]
            var y2 = output[base + 3]
            // Normalized tensor ⇒ scale to input pixels first so both
            // branches share the crop-scale below (per-tensor verdict).
            if (!tensorIsPixelUnits) {
                x1 *= inputSize; y1 *= inputSize
                x2 *= inputSize; y2 *= inputSize
            }
            if (geom != null) {
                // Letterboxed: clip to the content box, then shift out the padding.
                x1 = x1.coerceIn(geom.padX, geom.padX + geom.contentW) - geom.padX
                x2 = x2.coerceIn(geom.padX, geom.padX + geom.contentW) - geom.padX
                y1 = y1.coerceIn(geom.padY, geom.padY + geom.contentH) - geom.padY
                y2 = y2.coerceIn(geom.padY, geom.padY + geom.contentH) - geom.padY
            }

            val objX = (x1 * scaleX).toInt().coerceIn(0, imgWidth)
            val objY = (y1 * scaleY).toInt().coerceIn(0, imgHeight)
            val objW = ((x2 - x1) * scaleX).toInt().coerceIn(0, imgWidth - objX)
            val objH = ((y2 - y1) * scaleY).toInt().coerceIn(0, imgHeight - objY)

            // Quadrant-relative distance filter — mirrors parseOutput exactly.
            val relH = if (quadrantHeight > 0) objH.toFloat() / quadrantHeight else 0f
            val relW = if (quadrantWidth > 0) objW.toFloat() / quadrantWidth else 0f
            val passes = when (classId) {
                CLASS_PERSON -> relH >= minRelativeHeight
                CLASS_CAR, CLASS_BUS, CLASS_TRUCK, CLASS_TRAIN -> relW >= carWidthThreshold
                CLASS_BICYCLE, CLASS_MOTORCYCLE -> relH >= bikeHeightThreshold
                else -> relH >= minRelativeHeight
            }
            if (!passes) {
                rejSize++
                if (classId == CLASS_PERSON) {
                    logger.info("YOLO size-gate dropped PERSON conf=%.3f relH=%.3f (need %.3f) box=%dx%d quad=%dx%d"
                        .format(score, relH, minRelativeHeight, objW, objH, quadrantWidth, quadrantHeight))
                }
                continue
            }

            detections.add(Detection(classId, score, objX, objY, objW, objH))
        }

        // No host NMS (resolved in-graph). Ghost cap retained: TRUNCATE, never
        // clear — same insurance as the legacy path. E2E rows arrive in
        // descending-score order from the export's TopK, so take() keeps the
        // most probable ones.
        val final = if (detections.size > 50) {
            logger.warn("Ghost filter: ${detections.size} > 50, keeping top 50 by confidence")
            detections.take(50)
        } else {
            detections
        }

        // Class-distribution counts — same log contract as parseOutput
        // (motorcycle counts under Bike — R3b Ext-11).
        var personCount = 0
        var carCount = 0
        var bikeCount = 0
        var animalCount = 0
        var bestKeptConf = 0f
        var bestKeptClass = -1
        val carMask = (1L shl CLASS_CAR) or (1L shl CLASS_BUS) or
                (1L shl CLASS_TRUCK) or (1L shl CLASS_TRAIN) or
                (1L shl CLASS_BOAT) or (1L shl CLASS_AIRPLANE)
        var animalMask = 0L
        for (c in CLASS_BIRD..CLASS_GIRAFFE) animalMask = animalMask or (1L shl c)
        for (idx in final.indices) {
            val det = final[idx]
            val cid = det.classId
            if (cid == CLASS_PERSON) personCount++
            if (cid == CLASS_BICYCLE || cid == CLASS_MOTORCYCLE) bikeCount++
            if (cid in 0..63) {
                val bit = 1L shl cid
                if ((bit and carMask) != 0L) carCount++
                if ((bit and animalMask) != 0L) animalCount++
            }
            if (det.confidence > bestKeptConf) {
                bestKeptConf = det.confidence
                bestKeptClass = cid
            }
        }

        logger.info("Detected ${final.size} objects: person=$personCount car=$carCount bike=$bikeCount animal=$animalCount (max_conf=${"%.3f".format(bestKeptConf)} class=$bestKeptClass) [e2e]")

        if (final.isEmpty()) {
            logger.info(("YOLO raw funnel [e2e]: raw=%.3f class=%d person=%.3f | " +
                    "rej conf=%d implausible=%d unwanted=%d size=%d | " +
                    "confThr=%.2f minRelH=%.3f in=%dx%d quad=%dx%d")
                .format(rawPeakConf, rawPeakClass, peakPersonConf,
                        rejConf, rejImplausible, rejUnwanted, rejSize,
                        confThreshold, minRelativeHeight,
                        imgWidth, imgHeight, quadrantWidth, quadrantHeight))
        }

        return final
    }

    /**
     * Non-Maximum Suppression. In-place sort into the reused scratch array
     * + linear cull. Replaces a `sortedByDescending { ... }` allocation
     * (new ArrayList + lambda capture) on every call.
     */
    private fun nms(detections: ArrayList<Detection>, iouThreshold: Float): List<Detection> {
        val n = detections.size
        if (n <= 1) return detections

        // Borrow / grow the scratch array. Capped via cap-doubling so the
        // worst-case 50-detection ghost-filter limit doesn't make it grow
        // unbounded across rare bursts.
        var scratch = nmsScratch
        if (scratch == null || scratch.size < n) {
            val cap = if (scratch == null) maxOf(64, n) else maxOf(scratch.size * 2, n)
            scratch = arrayOfNulls(cap)
            nmsScratch = scratch
        }
        for (i in 0 until n) scratch[i] = detections[i]

        // Sort descending by confidence on the slice [0, n). Java's
        // Arrays.sort with a Comparator is mergesort/Timsort and operates
        // in-place on the array; the Comparator is a singleton lambda
        // (Kotlin compiles the no-capture lambda to a static instance).
        @Suppress("UNCHECKED_CAST")
        java.util.Arrays.sort(scratch as Array<Detection?>, 0, n, NMS_COMPARATOR)

        val results = ArrayList<Detection>(minOf(n, 16))
        for (i in 0 until n) {
            val det = scratch[i] ?: continue
            var keep = true
            for (j in 0 until results.size) {
                val res = results[j]
                if (det.classId == res.classId && iou(det, res) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) results.add(det)
            scratch[i] = null  // help GC release Detection refs after this call
        }
        return results
    }

    
    /**
     * Calculate Intersection over Union
     */
    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.w, b.x + b.w)
        val y2 = min(a.y + a.h, b.y + b.h)
        
        val interW = max(0, x2 - x1)
        val interH = max(0, y2 - y1)
        val interArea = interW * interH
        
        val area1 = a.w * a.h
        val area2 = b.w * b.h
        val unionArea = area1 + area2 - interArea
        
        return if (unionArea > 0) interArea.toFloat() / unionArea else 0f
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        // Acquiring interpLock blocks until any in-flight detect() releases it.
        // Without this, freeing the native interpreter mid-run would SIGSEGV
        // inside tensorflowlite_jni.
        synchronized(interpLock) {
            interpreter?.close()
            interpreter = null
            // Drop the reused buffers too — they'll be re-allocated on next init().
            shapedBuffer = null
            shapedBufferW = -1
            shapedBufferH = -1
            floatOutput = null
            floatOutputView = null
            boxesScratch = null
            nmsScratch = null
            letterboxProcessor = null
            letterboxGeom = null
            letterboxSrcW = -1
            letterboxSrcH = -1
            letterboxBytes = null
            letterboxBuffer = null
            letterboxImage = null
        }
    }
}
