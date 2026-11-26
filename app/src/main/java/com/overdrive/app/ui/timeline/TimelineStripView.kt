package com.overdrive.app.ui.timeline

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Day strip under a fixed centre playhead: ruler, marks row and footage track.
 * The drawing model is rebuilt only in [setData]; onDraw allocates nothing.
 */
class TimelineStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onScrubStart()
        fun onScrub(wallMs: Long)
        fun onScrubEnd(wallMs: Long)
        fun onMarkTapped(wallMs: Long, recId: String?)
        fun onLiveTapped()
        fun onSpanChanged(spanMs: Long)
        fun onVisibleRange(fromMs: Long, toMs: Long)
        fun onSnapped(label: String?)
    }

    var listener: Listener? = null

    /** Presented playhead time (what the strip shows under the centre line). */
    val centerMs: Long get() = Math.round(center)
    val spanMs: Long get() = Math.round(span)

    private val density = resources.displayMetrics.density

    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val cAccent = ContextCompat.getColor(context, R.color.md_sys_color_primary)
    private val cOnAccent = ContextCompat.getColor(context, R.color.md_sys_color_on_primary)
    private val cAmber = ContextCompat.getColor(context, R.color.overdrive_status_warning)
    private val cInk = ContextCompat.getColor(context, R.color.md_sys_color_on_surface)
    private val cInk2 = ContextCompat.getColor(context, R.color.md_sys_color_on_surface_variant)
    private val cPaper = ContextCompat.getColor(context, R.color.md_sys_color_surface)
    private val cRuler = if (night) 0xFF98A49F.toInt() else cInk2
    // The panel colour behind the strip: the edge fades paint it over the content (no offscreen layer).
    private val cFadeBg = MaterialColors.getColor(
        context, com.google.android.material.R.attr.colorSurfaceContainer,
        ContextCompat.getColor(context, R.color.md_sys_color_surface_container),
    )
    private val cMinor = if (night) 0xFF3A4440.toInt() else alpha(cInk, 0.3f)
    private val cGrid = if (night) rgba(255, 255, 255, 0.05f) else alpha(cInk, 0.07f)
    private val cArmed = if (night) rgba(222, 228, 224, 0.085f) else alpha(cInk, 0.085f)
    private val cHatchBase = if (night) 0xFF111614.toInt() else alpha(cInk, 0.05f)
    private val cHatchStripe = if (night) rgba(255, 255, 255, 0.075f) else alpha(cInk, 0.12f)
    private val cGapText = if (night) 0xFFA5B0AC.toInt() else cInk2
    private val cFootage = if (night) rgba(226, 232, 229, 0.92f) else alpha(cInk, 0.86f)
    private val cSeam = if (night) rgba(14, 19, 17, 0.35f) else alpha(cPaper, 0.45f)
    private val cDriveText = if (night) 0xFF1C2321.toInt() else cPaper
    private val cLive = if (night) 0xFFFF5449.toInt() else ContextCompat.getColor(context, R.color.md_sys_color_error)
    private val cMarkFill = if (night) rgba(222, 228, 224, 0.10f) else alpha(cInk, 0.08f)
    private val cMarkAlertFill = alpha(cAmber, 0.16f)
    private val cIcon = if (night) 0xFFE6ECE9.toInt() else cInk
    private val cIconAlert = if (night) 0xFFFFC98F.toInt() else cAmber

    private val topInset = dpx(TOP_INSET)
    private val blockH = dpx(BLOCK_H)
    private val yLabel = dpx(LABEL_BASE)
    private val yMinorTop = dpx(MINOR_TOP)
    private val yMinorBot = dpx(MINOR_BOT)
    private val yPlayTop = dpx(PLAYHEAD_TOP)
    private val yPlayBot = dpx(PLAYHEAD_BOT)
    private val yMark = dpx(MARK_Y)
    private val yGridTop = dpx(GRID_TOP)
    private val yGridBot = dpx(GRID_BOT)
    private val yTrack = dpx(TRACK_TOP)
    private val trackH = dpx(TRACK_H)
    private val seamInset = dpx(5f)
    private val rTrack = dpx(6f)
    private val rEvent = dpx(2f)
    // Short clips (events) read as blocks, not hairlines; releases near one snap to its moment.
    private val minFootW = dpx(MIN_FOOT_W)
    private val minGapW = dpx(2f)
    private val markR = dpx(MARK_R)
    private val markRCur = dpx(MARK_R_CURRENT)
    private val iconPx = dpx(ICON)
    private val strokeCur = dpx(2f)
    private val strokeAlert = dpx(1.4f)
    private val liveDotR = dpx(4.5f)
    private val fadeW = dpx(FADE_W)
    private val edge = dpx(20f)
    private val pillTopIdle = dpx(-6f)
    private val pillTopPressed = dpx(-8f)
    private val pillHIdle = dpx(28f)
    private val pillHPressed = dpx(32f)
    private val pillPadIdle = dpx(14f)
    private val pillPadPressed = dpx(17f)
    private val pillMinIdle = dpx(96f)
    private val pillMinPressed = dpx(116f)
    private val labelMinPx = dpx(LABEL_MIN_PX).toDouble()
    private val labelClear = dpx(LABEL_CLEAR_PX)
    private val gapLabelMin = dpx(GAP_LABEL_MIN_PX)
    private val gapLabelPad = dpx(12f)
    private val driveLabelMin = dpx(DRIVE_LABEL_MIN_PX)
    private val driveInset = dpx(10f)
    private val seamMinPx = dpx(SEAM_MIN_PX).toDouble()
    private val clusterPx = dpx(CLUSTER_PX).toDouble()
    private val currentPx = dpx(CURRENT_PX).toDouble()
    private val snapPx = dpx(SNAP_PX).toDouble()
    private val jumpPx = dpx(JUMP_PX).toDouble()
    private val hitPx = dpx(HIT_PX)
    private val countPad = dpx(6f)

    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private val semibold = Typeface.create(Typeface.DEFAULT, 600, false)
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    // Glanceable at arm's length: 13 sp and up, the hour labels and the playhead time largest.
    private val rulerPaint = textPaint(spx(14f), medium, cRuler, Paint.Align.CENTER, true)
    private val gapPaint = textPaint(spx(13f), Typeface.DEFAULT, cGapText, Paint.Align.CENTER, false)
    private val drivePaint = textPaint(spx(13f), semibold, cDriveText, Paint.Align.LEFT, false)
    private val countPaint = textPaint(spx(13f), bold, cIcon, Paint.Align.CENTER, true)
    private val pillTextIdle = spx(15f)
    private val pillTextPressed = spx(16f)
    private val pillTextPaint = textPaint(pillTextIdle, bold, cOnAccent, Paint.Align.LEFT, true)
    private val minorPaint = strokePaint(cMinor, dpx(1f))
    private val gridPaint = strokePaint(cGrid, dpx(1f))
    private val seamPaint = strokePaint(cSeam, dpx(1.2f))
    private val armedPaint = fillPaint(cArmed)
    private val footPaint = fillPaint(cFootage)
    private val offFillPaint = fillPaint(alpha(cFootage, 0.06f))
    private val offStrokePaint = strokePaint(alpha(cFootage, 0.6f), dpx(1.2f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dpx(4f), dpx(3f)), 0f)
    }
    private val hatchPeriod = max(4, dpx(7f).roundToInt())
    private val hatchShader = stripeShader(hatchPeriod, dpx(2.4f), cHatchBase, cHatchStripe)
    private val hatchPaint = fillPaint(Color.WHITE).apply { shader = hatchShader }
    private val livePeriod = max(4, dpx(8f).roundToInt())
    private val liveShader = stripeShader(livePeriod, dpx(3.5f), alpha(cFootage, 0.24f), alpha(cFootage, 0.62f))
    private val livePaint = fillPaint(Color.WHITE).apply { shader = liveShader }
    private val liveDotPaint = fillPaint(cLive)
    private val markFillPaint = fillPaint(cMarkFill)
    private val markStrokePaint = strokePaint(cAccent, strokeCur)
    private val iconFill = fillPaint(cIcon)
    private val iconStroke = strokePaint(cIcon, 2f).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val playUnder = strokePaint(0x80000000.toInt(), dpx(5f))
    private val playLine = strokePaint(Color.WHITE, dpx(2f))
    private val pillPaint = fillPaint(cAccent)
    private val fadeLeft = Paint()
    private val fadeRight = Paint()
    private val layerPaint = Paint()
    private val hatchMatrix = Matrix()
    private val liveMatrix = Matrix()
    private val rect = RectF()

    private val icons: Array<Icon> = buildIcons()
    private val kindLabel = Array(MarkKind.COUNT) { resources.getString(kindLabelRes(it)) }
    private val driveLabel = resources.getString(R.string.timeline_drive)
    private val driveLabelW = drivePaint.measureText(driveLabel)
    private val rulerHalfW = rulerPaint.measureText("00:00") * 0.5f

    private var data: StripData? = null
    private var model = StripModel.EMPTY
    private var tz: TimeZone = TimeZone.getDefault()
    private var tzKey: String? = null
    private var dataNow = 0.0
    private var dataNowNs = 0L

    private var center = System.currentTimeMillis().toDouble()
    private var raw = center
    private var span = DEFAULT_SPAN_MS

    private var touchMode = T_NONE
    private var anim = A_NONE
    private val centerSpring = StripPhysics.Spring()
    private val spanSpring = StripPhysics.Spring()
    private val plan = StripPhysics.FlingPlan()
    private val omegaUi = StripPhysics.omegaFor(StripPhysics.RESPONSE_S)
    private val omegaFlickMin = StripPhysics.omegaFor(FLICK_MAX_RESPONSE_S)

    private var fadeActive = false
    private var fadeStartNs = 0L
    private var fadeDurNs = 1L
    private var fadeCenter = Double.NaN
    private var fadeSpan = Double.NaN
    private var fadeSwapped = false
    private var fadeEndsScrub = false
    private var contentAlpha = 1f

    private val follow = StripPhysics.Follow()
    private var followOffset = 0.0
    private var everReported = false

    private var scrubbing = false
    private var lastScrubEmit = Long.MIN_VALUE
    private var snapText: String? = null
    private var snapTime = Double.NaN
    private var hoverVis = -1
    private var flingSnapped = false
    private var flingSnapText: String? = null
    private var flingSign = 0.0
    private var flingArrived = true

    private var pillLabel: String? = null
    private var reducedMotion = false
    private var filterKinds: Set<String>? = null
    private var alertsOnly = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val tapMaxMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var tracker: VelocityTracker? = null
    private var activeId = -1
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downStoppedMotion = false
    private var lastX = 0f
    private var lastMoveAt = 0L
    private var grabTime = 0.0
    private var focalTime = 0.0
    private var focalX = 0f
    private var pinchEnded = false
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener()).apply { isQuickScaleEnabled = false }

    private var visIdx = IntArray(0)
    private var visTime = LongArray(0)
    private var visCount = 0
    private var minClusterSpacing = Double.POSITIVE_INFINITY
    private var snapTimes = LongArray(0)
    private var snapMark = IntArray(0)
    private var snapCount = 0
    private var stepTimes = LongArray(0)
    private var cStart = IntArray(0)
    private var cCount = IntArray(0)
    private var cCenter = DoubleArray(0)
    private var cAlert = BooleanArray(0)
    private var cBorn = LongArray(0)
    private val clusterAnimationClock = ClusterAnimationClock()
    private var pStart = IntArray(0)
    private var pCount = IntArray(0)
    private var pBorn = LongArray(0)
    private var clusterN = 0
    private var prevClusterN = 0
    private var clusterSpan = Double.NaN
    private var clusterWidth = 0
    private var clustersStable = true

    private var gapLabel = arrayOfNulls<String>(0)
    private var gapShort = arrayOfNulls<String>(0)
    private var gapLabelW = FloatArray(0)
    private var gapShortW = FloatArray(0)

    private var pressT = 0f
    private var pressTarget = 0f
    private var pillW = 0f
    private var pillTargetW = 0f

    private val choreographer = Choreographer.getInstance()
    private var frameScheduled = false
    private var lastFrameNs = 0L
    private var shownAgg = true
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }
    private val slowTick = Runnable { scheduleFrame() }
    private val a11yTick = Runnable { flushA11y() }

    private var rangeFrom = Long.MIN_VALUE
    private var rangeTo = Long.MIN_VALUE
    private var rangeAt = 0L
    private var rangeDirty = false
    private var lastSpanEmitted = -1L
    private var a11yAt = 0L
    private var a11yDirty = false
    private var a11yPosted = false

    private val timeBuf = CharArray(8)
    private val hmBuf = CharArray(5)
    private val numBuf = CharArray(12)
    private val tickUtc = LongArray(MAX_TICKS)
    private val tickLocal = LongArray(MAX_TICKS)
    private val tickMajor = BooleanArray(MAX_TICKS)

    init {
        isFocusable = true
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.addAccessibilityAction(this, resources.getString(R.string.timeline_action_prev_moment)) { _, _ -> stepMoment(-1) }
        ViewCompat.addAccessibilityAction(this, resources.getString(R.string.timeline_action_next_moment)) { _, _ -> stepMoment(1) }
        ViewCompat.addAccessibilityAction(this, resources.getString(R.string.timeline_action_back_10)) { _, _ -> stepBy(-STEP_MS) }
        ViewCompat.addAccessibilityAction(this, resources.getString(R.string.timeline_action_forward_10)) { _, _ -> stepBy(STEP_MS) }
        contentDescription = describe()
    }

    // ---------------------------------------------------------------- public API

    fun setData(d: StripData) {
        data = d
        if (d.tzId != tzKey) {
            tzKey = d.tzId
            tz = if (d.tzId.isBlank()) TimeZone.getDefault() else TimeZone.getTimeZone(d.tzId)
        }
        model = StripModel.build(d)
        dataNow = d.now.toDouble()
        dataNowNs = System.nanoTime()
        rebuildVisible()
        buildGapLabels(d)
        clustersStable = true
        if (anim == A_FLING || anim == A_BOUNCE) {
            centerSpring.target = centerSpring.target.coerceIn(lowerBound(), upperBound())
        }
        val w = width
        if (w > 0 && (touchMode == T_DRAG || touchMode == T_PINCH)) {
            raw = elasticInverse(center)
            if (touchMode == T_DRAG) grabTime = raw + (lastX - w * 0.5) * span / w
            else focalTime = raw + (focalX - w * 0.5) * span / w
            updateHover()
        }
        a11yDirty = true
        scheduleFrame()
        invalidate()
    }

    fun setPlayback(wallMs: Long, playing: Boolean, speed: Int) {
        if (!follow.report(wallMs, playing, speed, System.nanoTime())) return
        if (userOwnsCenter() || anim == A_GLIDE || anim == A_FADE) return
        val w = width
        if (w <= 0 || !everReported) {
            everReported = true
            followOffset = 0.0
            presentPassive(wallMs.toDouble())
        } else if (abs(wallMs - center) * (w / span) > jumpPx) {
            startGlide(wallMs.toDouble())
        } else {
            followOffset = center - wallMs
        }
        scheduleFrame()
    }

    /** Critically damped glide; long distances and reduced motion cross-fade instead. */
    fun glideTo(wallMs: Long) {
        if (userOwnsCenter()) return
        follow.expect(wallMs.toDouble(), System.nanoTime())
        startGlide(wallMs.toDouble())
    }

    fun setSpan(spanMs: Long, animate: Boolean) {
        if (touchMode == T_PINCH) return
        val target = spanMs.toDouble().coerceIn(StripPhysics.MIN_SPAN_MS, StripPhysics.MAX_SPAN_MS)
        if (!animate || width <= 0) {
            spanSpring.stop()
            fadeSpan = Double.NaN
            applySpan(target)
            emitSpan()
            return
        }
        if (reducedMotion) {
            startFade(Double.NaN, target, false)
            return
        }
        val v0 = if (spanSpring.active) spanSpring.velocity else 0.0
        spanSpring.start(ln(span), v0, ln(target), omegaUi, 1.0)
        scheduleFrame()
    }

    fun setFilter(kinds: Set<String>?, alertsOnly: Boolean) {
        filterKinds = kinds?.toHashSet()
        this.alertsOnly = alertsOnly
        rebuildVisible()
        clustersStable = true
        if (touchMode == T_DRAG || touchMode == T_PINCH) updateHover()
        a11yDirty = true
        invalidate()
    }

    fun setPillLabel(text: String?) {
        if (text == pillLabel) return
        pillLabel = text
        invalidate()
    }

    fun setReducedMotion(on: Boolean) {
        reducedMotion = on
        if (on) {
            pressT = pressTarget
            pillW = pillTargetW
        }
        invalidate()
    }

    // ---------------------------------------------------------------- model derived state

    private fun rebuildVisible() {
        val m = model
        val kinds = filterKinds
        val idx = IntArray(m.markCount)
        var n = 0
        for (i in 0 until m.markCount) {
            if (alertsOnly && !m.markAlert[i]) continue
            if (kinds != null && MarkKind.filterKey(m.markKind[i]) !in kinds) continue
            idx[n++] = i
        }
        visIdx = idx
        visCount = n
        visTime = LongArray(n) { m.markTime[idx[it]] }
        minClusterSpacing = StripPhysics.minClusterSpacing(visTime, n)

        val mom = m.moments
        val st = LongArray(n + mom.size)
        val sm = IntArray(n + mom.size)
        var a = 0
        var b = 0
        var k = 0
        while (a < n || b < mom.size) {
            if (b >= mom.size || (a < n && visTime[a] <= mom[b])) {
                st[k] = visTime[a]
                sm[k] = idx[a]
                a++
                k++
            } else {
                val t = mom[b++]
                if (k > 0 && t - st[k - 1] < MOMENT_DEDUPE_MS) continue
                val near = StripPhysics.nearestWithin(visTime, n, t.toDouble(), MOMENT_LABEL_MS)
                st[k] = t
                sm[k] = if (near >= 0) idx[near] else -1
                k++
            }
        }
        snapTimes = st
        snapMark = sm
        snapCount = k
        stepTimes = if (mom.isNotEmpty()) mom else visTime

        if (cStart.size < n || cStart.isEmpty()) {
            val cap = max(16, n)
            cStart = IntArray(cap); cCount = IntArray(cap); cCenter = DoubleArray(cap)
            cAlert = BooleanArray(cap); cBorn = LongArray(cap)
            pStart = IntArray(cap); pCount = IntArray(cap); pBorn = LongArray(cap)
        }
        clusterN = 0
        clusterAnimationClock.reset()
        prevClusterN = 0
        clusterSpan = Double.NaN
    }

    private fun buildGapLabels(d: StripData) {
        val m = model
        val n = m.gapStart.size
        val full = arrayOfNulls<String>(n)
        val short = arrayOfNulls<String>(n)
        val fw = FloatArray(n)
        val sw = FloatArray(n)
        for (g in 0 until n) {
            val len = m.gapEnd[g] - m.gapStart[g]
            if (len < GAP_LABEL_MIN_MS) continue
            val dur = GapReasons.durationText(resources, len)
            val reason = resources.getString(GapReasons.reasonRes(d, m.gapSource[g], m.gapIndex[g]))
            val text = GapReasons.label(resources, dur, reason)
            full[g] = text
            fw[g] = gapPaint.measureText(text)
            short[g] = dur
            sw[g] = gapPaint.measureText(dur)
        }
        gapLabel = full
        gapShort = short
        gapLabelW = fw
        gapShortW = sw
    }

    /** Re-clusters only when span or width changed; clusters that differ from the last pass pop in. */
    private fun ensureClusters() {
        val w = width
        if (w <= 0 || (clusterSpan == span && clusterWidth == w)) return
        val gap = clusterPx * span / w
        if (StripPhysics.canReuseSingletonClusters(clustersStable, clusterN, visCount, gap, minClusterSpacing)) {
            // Membership, centres, alerts and birth times are unchanged. A stable
            // rebuild (data/filter/size change) still takes the normal path.
            clusterSpan = span
            clusterWidth = w
            if (clusterAnimating()) scheduleFrame()
            return
        }
        val ts = pStart; pStart = cStart; cStart = ts
        val tc = pCount; pCount = cCount; cCount = tc
        val tb = pBorn; pBorn = cBorn; cBorn = tb
        prevClusterN = clusterN
        clusterN = StripPhysics.cluster(visTime, visCount, gap, cStart, cCount, cCenter)
        val now = SystemClock.uptimeMillis()
        clusterAnimationClock.reset()
        var j = 0
        for (k in 0 until clusterN) {
            val s = cStart[k]
            val e = s + cCount[k]
            var alert = false
            for (i in s until e) {
                if (model.markAlert[visIdx[i]]) { alert = true; break }
            }
            cAlert[k] = alert
            if (clustersStable) { cBorn[k] = 0L; continue }
            while (j < prevClusterN && pStart[j] < s) j++
            cBorn[k] = when {
                j < prevClusterN && pStart[j] == s && pCount[j] == cCount[k] -> pBorn[j]
                j < prevClusterN && pStart[j] == s && pBorn[j] != 0L && now - pBorn[j] < CLUSTER_ANIM_MS -> pBorn[j]
                else -> now
            }
            clusterAnimationClock.include(cBorn[k])
        }
        val popped = !clustersStable
        clustersStable = false
        clusterSpan = span
        clusterWidth = w
        if (popped && clusterAnimating()) scheduleFrame()
    }

    // ---------------------------------------------------------------- bounds and time

    private fun lowerBound(): Double = model.lower.toDouble()

    private fun upperBound(): Double {
        val m = model
        if (!m.openEnd) return m.upper.toDouble()
        return min(m.endCap.toDouble(), max(m.lower.toDouble(), nowAt(System.nanoTime())))
    }

    private fun nowAt(ns: Long): Double {
        if (dataNowNs == 0L) return dataNow
        return dataNow + ((ns - dataNowNs) / 1e6).coerceIn(0.0, NOW_EXTRAPOLATE_CAP_MS)
    }

    private fun elastic(v: Double): Double {
        val w = width
        return if (w <= 0) v else StripPhysics.elastic(v, lowerBound(), upperBound(), w / span, w.toDouble())
    }

    private fun elasticInverse(v: Double): Double {
        val w = width
        return if (w <= 0) v else StripPhysics.elasticInverse(v, lowerBound(), upperBound(), w / span, w.toDouble())
    }

    private fun playbackAt(ns: Long): Double = follow.at(ns, center)

    private fun userOwnsCenter(): Boolean =
        touchMode != T_NONE || scrubbing || anim == A_FLING || anim == A_BOUNCE

    private fun canFollow(): Boolean =
        touchMode == T_NONE && !scrubbing && anim == A_NONE && !follow.isPending && follow.hasReport

    // ---------------------------------------------------------------- presenting

    private fun presentUser(t: Double) {
        center = t
        if (scrubbing) {
            val r = Math.round(t)
            if (r != lastScrubEmit) {
                lastScrubEmit = r
                listener?.onScrub(r)
            }
        }
        if (touchMode == T_DRAG || touchMode == T_PINCH) updateHover()
        markA11y()
        invalidate()
    }

    private fun presentPassive(t: Double) {
        if (t == center) return
        center = t
        raw = t
        invalidate()
    }

    private fun applySpan(s: Double) {
        span = s.coerceIn(StripPhysics.MIN_SPAN_MS, StripPhysics.MAX_SPAN_MS)
        val w = width
        if (touchMode == T_DRAG && w > 0) {
            raw = grabTime - (lastX - w * 0.5) * span / w
            presentUser(elastic(raw))
        }
        invalidate()
    }

    private fun updateHover() {
        val w = width
        if (w <= 0) return
        val i = StripPhysics.nearestWithin(visTime, visCount, center, currentPx * span / w)
        hoverVis = i
        if (i >= 0) setSnap(kindLabel[model.markKind[visIdx[i]]], visTime[i].toDouble()) else setSnap(null, Double.NaN)
    }

    private fun setSnap(text: String?, t: Double) {
        snapTime = t
        if (text == snapText) return
        snapText = text
        listener?.onSnapped(text)
        invalidate()
    }

    private fun beginScrub() {
        if (scrubbing) return
        scrubbing = true
        lastScrubEmit = Math.round(center)
        listener?.onScrubStart()
    }

    private fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        follow.expect(center, System.nanoTime())
        listener?.onScrubEnd(Math.round(center))
        emitSpan()
        emitVisibleRange(true)
        markA11y()
    }

    // ---------------------------------------------------------------- animation

    private fun startGlide(target: Double) {
        val w = width
        if (w <= 0) {
            center = target
            raw = target
            invalidate()
            return
        }
        val distPx = abs(target - center) * w / span
        if (distPx < SETTLE_PX) {
            centerSpring.stop()
            if (anim == A_GLIDE) anim = A_NONE
            presentPassive(target)
            return
        }
        if (reducedMotion || distPx > w * LONG_GLIDE_WIDTHS) {
            startFade(target, Double.NaN, false)
            return
        }
        val v0 = if (anim == A_GLIDE) centerSpring.velocity else 0.0
        centerSpring.start(center, v0, target, omegaUi, 1.0)
        anim = A_GLIDE
        scheduleFrame()
    }

    private fun startFade(centerTarget: Double, spanTarget: Double, endsScrub: Boolean) {
        val ns = System.nanoTime()
        if (!centerTarget.isNaN()) {
            centerSpring.stop()
            anim = A_FADE
            fadeCenter = centerTarget
        }
        if (!spanTarget.isNaN()) {
            spanSpring.stop()
            fadeSpan = spanTarget
        }
        if (endsScrub) fadeEndsScrub = true
        fadeDurNs = (if (reducedMotion) FADE_MS else LONG_FADE_MS) * 1_000_000L
        if (!fadeActive) {
            fadeStartNs = ns
        } else if (fadeSwapped) {
            val back = ((1f - contentAlpha) * 0.5f * fadeDurNs).toLong()
            fadeStartNs = ns - back
        }
        fadeSwapped = false
        fadeActive = true
        scheduleFrame()
    }

    private fun cancelFade() {
        if (!fadeActive) return
        fadeActive = false
        fadeCenter = Double.NaN
        fadeSpan = Double.NaN
        fadeEndsScrub = false
        contentAlpha = 1f
        if (anim == A_FADE) anim = A_NONE
    }

    private fun stepFade(ns: Long): Boolean {
        val p = ((ns - fadeStartNs).toDouble() / fadeDurNs).coerceIn(0.0, 1.0)
        if (p >= 0.5 && !fadeSwapped) {
            fadeSwapped = true
            val s = fadeSpan
            fadeSpan = Double.NaN
            if (!s.isNaN()) {
                applySpan(s)
                emitSpan()
            }
            val c = fadeCenter
            fadeCenter = Double.NaN
            if (!c.isNaN()) {
                raw = c
                if (scrubbing) presentUser(c) else presentPassive(c)
            }
        }
        contentAlpha = (if (p < 0.5) 1.0 - p * 2.0 else (p - 0.5) * 2.0).toFloat()
        if (p < 1.0) return true
        fadeActive = false
        contentAlpha = 1f
        if (anim == A_FADE) {
            anim = A_NONE
            if (follow.hasReport) followOffset = center - playbackAt(ns)
        }
        if (fadeEndsScrub) {
            fadeEndsScrub = false
            endScrub()
        }
        return false
    }

    private fun release(velocity: Double) {
        val w = width
        if (w <= 0) {
            endScrub()
            return
        }
        val lo = lowerBound()
        val hi = upperBound()
        val shown = center
        val out = shown < lo || shown > hi
        val inward = (shown < lo && velocity > 0.0) || (shown > hi && velocity < 0.0)
        if (velocity != 0.0 && (!out || inward)) {
            startFling(shown, velocity, lo, hi, w / span)
            return
        }
        if (out) {
            val target = if (shown < lo) lo else hi
            if (reducedMotion) {
                raw = target
                presentUser(target)
                endScrub()
                return
            }
            raw = elasticInverse(shown)
            centerSpring.start(raw, 0.0, target, omegaUi, 1.0)
            anim = A_BOUNCE
            scheduleFrame()
            return
        }
        endScrub()
    }

    private fun startFling(shown: Double, velocity: Double, lo: Double, hi: Double, ppm: Double) {
        StripPhysics.planFling(plan, shown, velocity, snapTimes, snapCount, snapPx / ppm, lo, hi)
        flingSnapped = plan.snapIndex >= 0
        flingSnapText = if (flingSnapped) snapMark[plan.snapIndex].let { if (it >= 0) kindLabel[model.markKind[it]] else null } else null
        setSnap(null, Double.NaN)
        if (reducedMotion) {
            if (flingSnapped) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                setSnap(flingSnapText, plan.target)
            }
            startFade(plan.target, Double.NaN, true)
            return
        }
        val start = if (shown < lo || shown > hi) elasticInverse(shown) else shown
        raw = start
        val toward = if (plan.target >= start) velocity else -velocity
        val omega = StripPhysics.flickOmega(toward, abs(plan.target - start), StripPhysics.FLICK_DAMPING, omegaFlickMin, omegaUi)
        centerSpring.start(start, velocity, plan.target, omega, StripPhysics.FLICK_DAMPING)
        flingSign = if (plan.target >= start) 1.0 else -1.0
        flingArrived = false
        anim = A_FLING
        scheduleFrame()
    }

    private fun onFlingArrived() {
        flingArrived = true
        if (!flingSnapped) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        setSnap(flingSnapText, centerSpring.target)
    }

    private fun stepUserSpring(dt: Double): Boolean {
        val w = width
        if (w <= 0) {
            anim = A_NONE
            endScrub()
            return false
        }
        val ppm = w / span
        centerSpring.step(dt)
        raw = centerSpring.value
        presentUser(elastic(raw))
        if (anim == A_FLING && !flingArrived) {
            val u = centerSpring.value - centerSpring.target
            if (u * flingSign >= 0.0 || abs(u) * ppm < SETTLE_PX) onFlingArrived()
        }
        if (!centerSpring.settled(SETTLE_PX / ppm, SETTLE_PX_PER_S / ppm)) return true
        raw = centerSpring.target
        centerSpring.stop()
        val wasFling = anim == A_FLING
        anim = A_NONE
        presentUser(elastic(raw))
        if (wasFling && !flingArrived) onFlingArrived()
        endScrub()
        return false
    }

    private fun stepGlide(dt: Double, ns: Long): Boolean {
        val w = width
        centerSpring.step(dt)
        presentPassive(centerSpring.value)
        if (w > 0 && !centerSpring.settled(SETTLE_PX * span / w, SETTLE_PX_PER_S * span / w)) return true
        presentPassive(centerSpring.target)
        centerSpring.stop()
        anim = A_NONE
        if (follow.hasReport) followOffset = center - playbackAt(ns)
        return false
    }

    private fun scheduleFrame() {
        if (frameScheduled || !isAttachedToWindow || !shownAgg) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrames() {
        if (frameScheduled) choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        lastFrameNs = 0L
        removeCallbacks(slowTick)
    }

    private fun onFrame(frameNs: Long) {
        frameScheduled = false
        if (!isAttachedToWindow) return
        val dt = if (lastFrameNs == 0L) FRAME_S else ((frameNs - lastFrameNs) / 1e9).coerceIn(0.0, MAX_DT_S)
        lastFrameNs = frameNs
        val w = width
        var more = false
        var delay = Long.MAX_VALUE

        if (spanSpring.active) {
            spanSpring.step(dt)
            if (spanSpring.settled(SPAN_LOG_EPS, SPAN_LOG_VEL_EPS)) {
                spanSpring.stop()
                applySpan(exp(spanSpring.target))
                emitSpan()
            } else {
                applySpan(exp(spanSpring.value))
                more = true
            }
        }
        if (fadeActive && stepFade(frameNs)) more = true
        when (anim) {
            A_FLING, A_BOUNCE -> if (stepUserSpring(dt)) more = true
            A_GLIDE -> if (stepGlide(dt, frameNs)) more = true
        }
        follow.expire(frameNs)
        if (canFollow() && w > 0) {
            val ppm = w / span
            followOffset = StripPhysics.approach(followOffset, 0.0, dt, FOLLOW_TAU_S)
            if (abs(followOffset) * ppm < 0.05) followOffset = 0.0
            presentPassive(playbackAt(frameNs) + followOffset)
            if (followOffset != 0.0) {
                more = true
            } else if (follow.extrapolating(frameNs)) {
                val pxPerS = ppm * 1000.0 * follow.speed
                if (pxPerS >= FAST_PX_PER_S) {
                    more = true
                } else {
                    val toPx = 500.0 / max(pxPerS, 1e-6)
                    val toSecond = (1000.0 - Math.floorMod(Math.round(center), 1000L)) / follow.speed
                    delay = min(delay, min(toPx, toSecond).toLong().coerceIn(16L, 1000L))
                }
            }
        }
        if (snapText != null && touchMode != T_DRAG && touchMode != T_PINCH && anim != A_FLING &&
            (snapTime.isNaN() || w <= 0 || abs(center - snapTime) * w / span > currentPx)
        ) setSnap(null, Double.NaN)

        if (reducedMotion) {
            pressT = pressTarget
        } else {
            pressT = StripPhysics.approach(pressT.toDouble(), pressTarget.toDouble(), dt, PRESS_TAU_S).toFloat()
            if (abs(pressT - pressTarget) < 0.01f) pressT = pressTarget else more = true
        }
        if (pillW != pillTargetW) {
            if (reducedMotion || pillW == 0f) {
                pillW = pillTargetW
            } else {
                pillW = StripPhysics.approach(pillW.toDouble(), pillTargetW.toDouble(), dt, PILL_TAU_S).toFloat()
                if (abs(pillW - pillTargetW) < 0.5f) pillW = pillTargetW else more = true
            }
        }
        if (clusterAnimating()) more = true
        if (w > 0 && model.openEnd) delay = min(delay, liveDelayMs(w))

        emitVisibleRange(false)
        if (rangeDirty) {
            val gap = if (scrubbing || touchMode != T_NONE) RANGE_USER_MS else RANGE_PASSIVE_MS
            delay = min(delay, max(16L, rangeAt + gap - SystemClock.uptimeMillis()))
        }
        if (follow.isPending) delay = min(delay, max(16L, follow.pendingLeftMs(frameNs).toLong()))
        invalidate()
        if (more) {
            removeCallbacks(slowTick)
            scheduleFrame()
        } else {
            lastFrameNs = 0L
            removeCallbacks(slowTick)
            if (delay != Long.MAX_VALUE) postOnAnimationDelayed(slowTick, delay)
        }
    }

    /** Redraw cadence for the growing live edge / open end while it is on screen. */
    private fun liveDelayMs(w: Int): Long {
        val m = model
        val endT = upperBound()
        val ppm = w / span
        val x = w * 0.5 + (endT - center) * ppm
        if (x < -edge || x > w + edge) return Long.MAX_VALUE
        val pxPerS = ppm * 1000.0
        return (500.0 / max(pxPerS, 1e-6)).toLong().coerceIn(16L, if (m.liveSince != Long.MIN_VALUE) 1000L else 5000L)
    }

    private fun clusterAnimating(): Boolean =
        clusterAnimationClock.isAnimating(SystemClock.uptimeMillis(), CLUSTER_ANIM_MS)

    // ---------------------------------------------------------------- callbacks

    private fun emitSpan() {
        val s = Math.round(span)
        if (s == lastSpanEmitted) return
        lastSpanEmitted = s
        listener?.onSpanChanged(s)
        emitVisibleRange(true)
    }

    private fun emitVisibleRange(force: Boolean) {
        val l = listener ?: return
        if (width <= 0) return
        val half = span * 0.5
        val from = Math.round(center - half)
        val to = Math.round(center + half)
        if (from == rangeFrom && to == rangeTo) {
            rangeDirty = false
            return
        }
        val now = SystemClock.uptimeMillis()
        val gap = if (scrubbing || touchMode != T_NONE) RANGE_USER_MS else RANGE_PASSIVE_MS
        if (!force && now - rangeAt < gap) {
            rangeDirty = true
            return
        }
        rangeAt = now
        rangeFrom = from
        rangeTo = to
        rangeDirty = false
        l.onVisibleRange(from, to)
    }

    /** User-driven position change: refresh the live-region description at most once per second. */
    private fun markA11y() {
        a11yDirty = true
        if (a11yPosted || !isAttachedToWindow) return
        a11yPosted = true
        postDelayed(a11yTick, max(0L, a11yAt + A11Y_MIN_MS - SystemClock.uptimeMillis()))
    }

    private fun flushA11y() {
        a11yPosted = false
        if (!a11yDirty) return
        a11yDirty = false
        a11yAt = SystemClock.uptimeMillis()
        val text = describe()
        if (text != contentDescription?.toString()) contentDescription = text
    }

    private fun describe(): String {
        val time = timeString(center)
        val near = StripPhysics.nearestWithin(visTime, visCount, center, Double.MAX_VALUE)
        return if (near >= 0) {
            resources.getString(R.string.timeline_cd_strip_nearest, time, kindLabel[model.markKind[visIdx[near]]])
        } else {
            resources.getString(R.string.timeline_cd_strip, time)
        }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (data == null || width <= 0) return false
        val t = tracker ?: VelocityTracker.obtain().also { tracker = it }
        t.addMovement(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(event, false)
            MotionEvent.ACTION_CANCEL -> onUp(event, true)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun onDown(e: MotionEvent) {
        activeId = e.getPointerId(0)
        downX = e.x
        downY = e.y
        lastX = e.x
        downAt = e.eventTime
        pinchEnded = false
        downStoppedMotion = anim == A_FLING || anim == A_BOUNCE || (anim == A_FADE && fadeEndsScrub)
        if (anim == A_FLING || anim == A_BOUNCE || anim == A_GLIDE) {
            centerSpring.stop()
            anim = A_NONE
        }
        cancelFade()
        spanSpring.stop()
        follow.clearPending()
        touchMode = T_PRESSED
        raw = if (scrubbing) elasticInverse(center) else center
        pressTarget = 1f
        if (reducedMotion) pressT = 1f
        scheduleFrame()
        invalidate()
    }

    private fun onMove(e: MotionEvent) {
        val w = width
        var i = e.findPointerIndex(activeId)
        if (i < 0) {
            i = 0
            activeId = e.getPointerId(0)
            grabTime = raw + (e.getX(0) - w * 0.5) * span / w
        }
        val x = e.getX(i)
        when (touchMode) {
            T_PINCH -> {
                if (!pinchEnded) return
                pinchEnded = false
                touchMode = T_DRAG
                grabTime = raw + (x - w * 0.5) * span / w
            }
            T_PRESSED -> {
                val dx = x - downX
                val dy = e.getY(i) - downY
                if (abs(dx) <= touchSlop || abs(dx) <= abs(dy)) return
                touchMode = T_DRAG
                parent?.requestDisallowInterceptTouchEvent(true)
                beginScrub()
                grabTime = raw + (x - w * 0.5) * span / w
            }
        }
        if (touchMode != T_DRAG) return
        if (x != lastX) lastMoveAt = e.eventTime
        lastX = x
        raw = grabTime - (x - w * 0.5) * span / w
        presentUser(elastic(raw))
    }

    private fun onPointerUp(e: MotionEvent) {
        val w = width
        val upIndex = e.actionIndex
        val keep = if (upIndex == 0) 1 else 0
        if (keep >= e.pointerCount) return
        val keepX = e.getX(keep)
        if (touchMode == T_PINCH && pinchEnded) {
            pinchEnded = false
            touchMode = T_DRAG
            activeId = e.getPointerId(keep)
        } else if (e.getPointerId(upIndex) == activeId) {
            activeId = e.getPointerId(keep)
        } else {
            return
        }
        lastX = keepX
        grabTime = raw + (keepX - w * 0.5) * span / w
        tracker?.clear()
    }

    private fun onUp(e: MotionEvent, cancelled: Boolean) {
        val mode = touchMode
        touchMode = T_NONE
        pressTarget = 0f
        if (reducedMotion) pressT = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        when (mode) {
            T_DRAG, T_PINCH -> {
                var v = 0.0
                val t = tracker
                if (mode == T_DRAG && !cancelled && t != null && e.eventTime - lastMoveAt <= FLING_STALE_MS) {
                    t.computeCurrentVelocity(1000, maxFling)
                    val vx = t.getXVelocity(activeId)
                    if (abs(vx) >= minFling) v = -vx.toDouble() * span / width
                }
                hoverVis = -1
                release(v)
            }
            T_PRESSED -> {
                if (scrubbing) {
                    release(0.0)
                } else {
                    emitSpan()
                    if (!cancelled && !downStoppedMotion && e.eventTime - downAt < tapMaxMs) handleTap(e.x, e.y)
                    if (follow.hasReport && !userOwnsCenter() && anim == A_NONE) {
                        followOffset = center - playbackAt(System.nanoTime())
                    }
                }
            }
        }
        tracker?.recycle()
        tracker = null
        scheduleFrame()
        invalidate()
    }

    private inner class ScaleListener : ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            val w = width
            if (data == null || w <= 0) return false
            if (anim == A_FLING || anim == A_BOUNCE || anim == A_GLIDE) {
                centerSpring.stop()
                anim = A_NONE
            }
            cancelFade()
            spanSpring.stop()
            pinchEnded = false
            touchMode = T_PINCH
            parent?.requestDisallowInterceptTouchEvent(true)
            beginScrub()
            setSnap(null, Double.NaN)
            raw = elasticInverse(center)
            focalX = detector.focusX
            focalTime = raw + (focalX - w * 0.5) * span / w
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val w = width
            if (touchMode != T_PINCH || w <= 0) return false
            val f = detector.scaleFactor
            if (!(f > 0f) || f.isInfinite()) return true
            span = (span / f).coerceIn(StripPhysics.MIN_SPAN_MS, StripPhysics.MAX_SPAN_MS)
            focalX = detector.focusX
            raw = focalTime - (focalX - w * 0.5) * span / w
            presentUser(elastic(raw))
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinchEnded = true
        }
    }

    private fun handleTap(x: Float, y: Float) {
        performClick()
        val w = width
        if (w <= 0) return
        ensureClusters()
        val ppm = w / span
        val oy = blockTop()
        val tapT = center + (x - w * 0.5) / ppm
        if (abs(y - (oy + yMark)) <= hitPx && clusterN > 0) {
            val k = nearestCluster(tapT, hitPx / ppm)
            if (k >= 0) {
                if (cCount[k] == 1) tapMark(k) else tapCluster(k)
                return
            }
        }
        val m = model
        if (m.liveSince == Long.MIN_VALUE) return
        if (y < oy + yTrack - hitPx * 0.5f || y > oy + yTrack + trackH + hitPx * 0.5f) return
        val endT = if (m.openEnd) upperBound() else m.upper.toDouble()
        val x0 = w * 0.5 + (m.liveSince - center) * ppm
        val x1 = w * 0.5 + (endT - center) * ppm
        if (x >= x0 - dpx(8f) && x <= x1 + dpx(16f)) listener?.onLiveTapped()
    }

    private fun nearestCluster(t: Double, maxDist: Double): Int {
        val i = StripPhysics.lowerBound(cCenter, clusterN, t)
        var best = -1
        var bestD = maxDist
        if (i > 0 && t - cCenter[i - 1] <= bestD) { best = i - 1; bestD = t - cCenter[i - 1] }
        if (i < clusterN && cCenter[i] - t < bestD) best = i
        return best
    }

    private fun tapMark(k: Int) {
        val mi = visIdx[cStart[k]]
        val t = model.markTime[mi]
        follow.expect(t.toDouble(), System.nanoTime())
        startGlide(t.toDouble())
        markA11y()
        listener?.onMarkTapped(t, model.markRec[mi])
    }

    private fun tapCluster(k: Int) {
        val s = cStart[k]
        val e = s + cCount[k]
        val first = visTime[s]
        var minGap = Double.MAX_VALUE
        for (i in s + 1 until e) {
            val g = (visTime[i] - visTime[i - 1]).toDouble()
            if (g > 0.0 && g < minGap) minGap = g
        }
        val w = width.toDouble()
        val separate = if (minGap < Double.MAX_VALUE) w * minGap / (clusterPx * 1.25) else span / 4.0
        val fit = (visTime[e - 1] - first) / CLUSTER_FIT
        var target = min(separate, span / 2.0)
        if (fit > target) target = min(fit, span / 2.0)
        target = target.coerceIn(StripPhysics.MIN_SPAN_MS, StripPhysics.MAX_SPAN_MS)
        follow.expect(first.toDouble(), System.nanoTime())
        startGlide(first.toDouble())
        if (target < span - 1.0) setSpan(Math.round(target), true)
        markA11y()
        listener?.onMarkTapped(first, model.markRec[visIdx[s]])
    }

    // ---------------------------------------------------------------- keys and accessibility

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> stepBy(-STEP_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT -> stepBy(STEP_MS)
            KeyEvent.KEYCODE_DPAD_UP -> stepMoment(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> stepMoment(1)
            else -> false
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
            event.actionMasked == MotionEvent.ACTION_SCROLL && data != null && touchMode == T_NONE
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                setSpan(Math.round(span * Math.pow(WHEEL_ZOOM, -v.toDouble())), false)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = describe()
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> stepBy(STEP_MS)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> stepBy(-STEP_MS)
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun stepBy(deltaMs: Long): Boolean {
        if (data == null) return false
        val target = (center + deltaMs).coerceIn(lowerBound(), upperBound())
        if (abs(target - center) < 1.0) return false
        return jumpUser(target)
    }

    private fun stepMoment(dir: Int): Boolean {
        val times = stepTimes
        val n = times.size
        if (data == null || n == 0) return false
        val t = if (dir > 0) {
            val i = StripPhysics.lowerBound(times, n, center + MOMENT_EPS_MS)
            if (i >= n) return false
            times[i]
        } else {
            val i = StripPhysics.lowerBound(times, n, center - MOMENT_EPS_MS) - 1
            if (i < 0) return false
            times[i]
        }
        return jumpUser(t.toDouble())
    }

    /** Discrete steps jump without animation and report as a complete scrub. */
    private fun jumpUser(target: Double): Boolean {
        if (touchMode != T_NONE) return false
        if (anim != A_NONE) {
            centerSpring.stop()
            anim = A_NONE
        }
        cancelFade()
        beginScrub()
        raw = target
        presentUser(target)
        endScrub()
        return true
    }

    // ---------------------------------------------------------------- lifecycle and layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (topInset + blockH).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(max(desired, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Opaque panel colour to its own transparent self, so the ramp has no grey fringe.
        val bg = cFadeBg or 0xFF000000.toInt()
        val clear = bg and 0x00FFFFFF
        fadeLeft.shader = LinearGradient(0f, 0f, fadeW, 0f, bg, clear, Shader.TileMode.CLAMP)
        fadeRight.shader = LinearGradient(w - fadeW, 0f, w.toFloat(), 0f, clear, bg, Shader.TileMode.CLAMP)
        clusterSpan = Double.NaN
        clustersStable = true
        if (touchMode == T_DRAG && w > 0) grabTime = raw + (lastX - w * 0.5) * span / w
        emitVisibleRange(true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        tracker?.recycle()
        tracker = null
        touchMode = T_NONE
        pressTarget = 0f
        pressT = 0f
        if (anim != A_NONE) {
            centerSpring.stop()
            anim = A_NONE
        }
        cancelFade()
        endScrub()
        stopFrames()
        removeCallbacks(a11yTick)
        a11yPosted = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shownAgg = isVisible
        if (isVisible) scheduleFrame() else stopFrames()
    }

    private fun blockTop(): Float {
        val avail = height - paddingTop - paddingBottom
        return paddingTop + max(0f, (avail - topInset - blockH) * 0.5f) + topInset
    }

    // ---------------------------------------------------------------- drawing (no allocation below)

    override fun onDraw(canvas: Canvas) {
        val w = width
        if (w <= 0 || height <= 0) return
        ensureClusters()
        val wf = w.toFloat()
        val hf = height.toFloat()
        val ppm = w / span
        val cx = wf * 0.5f
        val c = center
        val oy = blockTop()
        val m = model
        val endNow = if (m.openEnd) min(m.endCap.toDouble(), max(m.lower.toDouble(), nowAt(System.nanoTime()))) else Double.NaN
        val margin = (edge + fadeW) / ppm
        val t0 = c - cx / ppm - margin
        val t1 = c + cx / ppm + margin

        // Only a cross-fade (long jump, reduced motion) needs an offscreen layer.
        val save = if (contentAlpha < 0.999f) {
            layerPaint.alpha = (contentAlpha * 255f).roundToInt()
            canvas.saveLayer(0f, 0f, wf, hf, layerPaint)
        } else {
            -1
        }

        drawRuler(canvas, c, ppm, cx, oy, t0, t1)
        val top = oy + yTrack
        val bottom = top + trackH
        drawSpans(canvas, m.armStart, m.armEnd, armedPaint, rTrack, 0f, c, ppm, cx, t0, t1, top, bottom, endNow)
        drawGaps(canvas, c, ppm, cx, oy, t0, t1, top, bottom, endNow)
        drawSpans(canvas, m.offStart, m.offEnd, offFillPaint, rTrack, minFootW, c, ppm, cx, t0, t1, top, bottom, Double.NaN)
        drawSpans(canvas, m.offStart, m.offEnd, offStrokePaint, rTrack, minFootW, c, ppm, cx, t0, t1, top, bottom, Double.NaN)
        drawFootage(canvas, c, ppm, cx, t0, t1, top, bottom)
        drawLive(canvas, c, ppm, cx, top, bottom, endNow)
        drawMarks(canvas, c, ppm, cx, oy + yMark, t0, t1)

        canvas.drawRect(0f, 0f, fadeW, hf, fadeLeft)
        canvas.drawRect(wf - fadeW, 0f, wf, hf, fadeRight)
        if (save >= 0) canvas.restoreToCount(save)

        canvas.drawLine(cx, oy + yPlayTop, cx, oy + yPlayBot, playUnder)
        canvas.drawLine(cx, oy + yPlayTop, cx, oy + yPlayBot, playLine)
        drawPill(canvas, cx, oy, c)
    }

    private fun xOf(t: Double, c: Double, ppm: Double, cx: Float): Float = (cx + (t - c) * ppm).toFloat()

    private fun drawRuler(canvas: Canvas, c: Double, ppm: Double, cx: Float, oy: Float, t0: Double, t1: Double) {
        val stepMin = StripPhysics.pickStepMinutes(ppm * 60_000.0, labelMinPx)
        val n = StripPhysics.rulerTicks(
            tz, t0, t1, stepMin * 60_000L, StripPhysics.minorStepMs(stepMin), tickUtc, tickLocal, tickMajor,
        )
        val clear = max(labelClear, pillW * 0.5f + rulerHalfW + dpx(2f))
        for (i in 0 until n) {
            val x = xOf(tickUtc[i].toDouble(), c, ppm, cx)
            if (tickMajor[i]) {
                canvas.drawLine(x, oy + yGridTop, x, oy + yGridBot, gridPaint)
                if (abs(x - cx) > clear) {
                    StripPhysics.formatHm(tickLocal[i], hmBuf)
                    canvas.drawText(hmBuf, 0, 5, x, oy + yLabel, rulerPaint)
                }
            } else {
                canvas.drawLine(x, oy + yMinorTop, x, oy + yMinorBot, minorPaint)
            }
        }
    }

    /** Draws sorted disjoint spans; a span ending at an open "now" is extended to [openEnd]. */
    private fun drawSpans(
        canvas: Canvas, starts: LongArray, ends: LongArray, paint: Paint, radius: Float, minW: Float,
        c: Double, ppm: Double, cx: Float, t0: Double, t1: Double, top: Float, bottom: Float, openEnd: Double,
    ) {
        val n = starts.size
        var i = StripPhysics.lowerBound(ends, n, t0)
        val upper = model.upper
        while (i < n && starts[i] < t1) {
            var e = ends[i].toDouble()
            if (!openEnd.isNaN() && ends[i] == upper && openEnd > e) e = openEnd
            spanRect(starts[i].toDouble(), e, minW, c, ppm, cx, top, bottom)
            val r = min(radius, (rect.right - rect.left) * 0.5f)
            if (rect.right > rect.left) canvas.drawRoundRect(rect, r, r, paint)
            i++
        }
    }

    /** Fills [rect] for [s, e) clipped near the view so huge spans keep sane coordinates. */
    private fun spanRect(s: Double, e: Double, minW: Float, c: Double, ppm: Double, cx: Float, top: Float, bottom: Float) {
        var x0 = xOf(s, c, ppm, cx)
        var x1 = xOf(e, c, ppm, cx)
        if (x1 - x0 < minW) x1 = x0 + minW
        val lim = width + edge
        if (x0 < -edge) x0 = -edge
        if (x1 > lim) x1 = lim
        rect.set(x0, top, max(x0, x1), bottom)
    }

    private fun drawGaps(
        canvas: Canvas, c: Double, ppm: Double, cx: Float, oy: Float, t0: Double, t1: Double,
        top: Float, bottom: Float, openEnd: Double,
    ) {
        val m = model
        val n = m.gapStart.size
        if (n == 0) return
        val period = hatchPeriod * SQRT2
        val anchor = cx + (m.lower - c) * ppm
        hatchMatrix.setRotate(45f)
        hatchMatrix.postTranslate((((anchor % period) + period) % period).toFloat(), 0f)
        hatchShader.setLocalMatrix(hatchMatrix)
        val wf = width.toFloat()
        val small = dpx(12f)
        val baseline = oy + yMark + gapPaint.textSize * CAP_CENTER
        var i = StripPhysics.lowerBound(m.gapEnd, n, t0)
        while (i < n && m.gapStart[i] < t1) {
            var e = m.gapEnd[i].toDouble()
            if (!openEnd.isNaN() && m.gapEnd[i] == m.upper && openEnd > e) e = openEnd
            val fx0 = xOf(m.gapStart[i].toDouble(), c, ppm, cx)
            val fx1 = xOf(e, c, ppm, cx)
            spanRect(m.gapStart[i].toDouble(), e, minGapW, c, ppm, cx, top, bottom)
            val r = if (fx1 - fx0 > small) rTrack else dpx(1f)
            canvas.drawRoundRect(rect, r, r, hatchPaint)
            val visL = max(fx0, 0f)
            val visR = min(fx1, wf)
            val vw = visR - visL
            if (vw > gapLabelMin) {
                val full = gapLabel[i]
                val short = gapShort[i]
                val text = when {
                    full != null && gapLabelW[i] + gapLabelPad <= vw -> full
                    short != null && gapShortW[i] + gapLabelPad <= vw -> short
                    else -> null
                }
                if (text != null) canvas.drawText(text, (visL + visR) * 0.5f, baseline, gapPaint)
            }
            i++
        }
    }

    private fun drawFootage(canvas: Canvas, c: Double, ppm: Double, cx: Float, t0: Double, t1: Double, top: Float, bottom: Float) {
        val m = model
        val n = m.footStart.size
        val first = StripPhysics.lowerBound(m.footEnd, n, t0)
        var i = first
        while (i < n && m.footStart[i] < t1) {
            spanRect(m.footStart[i].toDouble(), m.footEnd[i].toDouble(), minFootW, c, ppm, cx, top, bottom)
            val r = min(if (m.footDrive[i]) rTrack else rEvent, (rect.right - rect.left) * 0.5f)
            canvas.drawRoundRect(rect, r, r, footPaint)
            i++
        }
        if (ppm * SEAM_MS >= seamMinPx) {
            val sn = m.seams.size
            var j = StripPhysics.lowerBound(m.seams, sn, t0)
            while (j < sn && m.seams[j] < t1) {
                val x = xOf(m.seams[j].toDouble(), c, ppm, cx)
                canvas.drawLine(x, top + seamInset, x, bottom - seamInset, seamPaint)
                j++
            }
        }
        val wf = width.toFloat()
        val baseline = (top + bottom) * 0.5f + drivePaint.textSize * CAP_CENTER
        i = first
        while (i < n && m.footStart[i] < t1) {
            if (m.footDrive[i]) {
                val x0 = xOf(m.footStart[i].toDouble(), c, ppm, cx)
                val x1 = xOf(m.footEnd[i].toDouble(), c, ppm, cx)
                val visL = max(x0, 0f)
                val visR = min(x1, wf)
                if (visR - visL > driveLabelMin) {
                    val lx = min(visL + driveInset, x1 - driveInset - driveLabelW)
                    if (lx >= x0 + driveInset * 0.5f) canvas.drawText(driveLabel, lx, baseline, drivePaint)
                }
            }
            i++
        }
    }

    private fun drawLive(canvas: Canvas, c: Double, ppm: Double, cx: Float, top: Float, bottom: Float, openEnd: Double) {
        val m = model
        if (m.liveSince == Long.MIN_VALUE) return
        val e = if (openEnd.isNaN()) m.upper.toDouble() else openEnd
        if (e <= m.liveSince) return
        val anchor = cx + (m.liveSince - c) * ppm
        val period = livePeriod * SQRT2
        liveMatrix.setRotate(45f)
        liveMatrix.postTranslate((((anchor % period) + period) % period).toFloat(), 0f)
        liveShader.setLocalMatrix(liveMatrix)
        spanRect(m.liveSince.toDouble(), e, minFootW, c, ppm, cx, top, bottom)
        if (rect.right > rect.left) {
            val r = min(rTrack, (rect.right - rect.left) * 0.5f)
            canvas.drawRoundRect(rect, r, r, livePaint)
        }
        if (!openEnd.isNaN()) canvas.drawCircle(xOf(e, c, ppm, cx), (top + bottom) * 0.5f, liveDotR, liveDotPaint)
    }

    private fun drawMarks(canvas: Canvas, c: Double, ppm: Double, cx: Float, y: Float, t0: Double, t1: Double) {
        if (clusterN == 0) return
        val interacting = touchMode == T_DRAG || touchMode == T_PINCH || anim == A_FLING
        val cur = currentCluster(c, ppm)
        val now = SystemClock.uptimeMillis()
        var k = StripPhysics.lowerBound(cCenter, clusterN, t0)
        while (k < clusterN && cCenter[k] < t1) {
            drawCluster(canvas, k, xOf(cCenter[k], c, ppm, cx), y, k == cur, interacting, now)
            k++
        }
    }

    private fun currentCluster(c: Double, ppm: Double): Int {
        if (hoverVis in 0 until visCount && (touchMode == T_DRAG || touchMode == T_PINCH)) {
            var lo = 0
            var hi = clusterN - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (cStart[mid] <= hoverVis) lo = mid else hi = mid - 1
            }
            return lo
        }
        val k = nearestCluster(c, currentPx / ppm)
        return k
    }

    private fun drawCluster(canvas: Canvas, k: Int, x: Float, y: Float, current: Boolean, interacting: Boolean, now: Long) {
        val count = cCount[k]
        val alert = cAlert[k]
        var scale = 1f
        var a = 1f
        val born = cBorn[k]
        if (born != 0L && now - born < CLUSTER_ANIM_MS) {
            val p = ((now - born).toFloat() / CLUSTER_ANIM_MS).coerceIn(0f, 1f)
            val inv = 1f - p
            val eased = 1f - inv * inv * inv
            a = eased
            if (!reducedMotion) scale = 0.6f + 0.4f * eased
        }
        val r = (if (current && interacting) markRCur else markR) * scale
        markFillPaint.color = scaleAlpha(if (alert) cMarkAlertFill else cMarkFill, a)
        val ink = scaleAlpha(if (alert) cIconAlert else cIcon, a)
        val ring = current || alert
        if (ring) {
            markStrokePaint.color = scaleAlpha(if (current) cAccent else cAmber, a)
            markStrokePaint.strokeWidth = if (current) strokeCur else strokeAlert
        }
        if (count == 1) {
            canvas.drawCircle(x, y, r, markFillPaint)
            if (ring) canvas.drawCircle(x, y, r, markStrokePaint)
            val icon = icons[model.markKind[visIdx[cStart[k]]]]
            val s = iconPx / 24f * scale
            canvas.save()
            canvas.translate(x - 12f * s, y - 12f * s)
            canvas.scale(s, s)
            val fill = icon.fill
            if (fill != null) {
                iconFill.color = ink
                canvas.drawPath(fill, iconFill)
            }
            val stroke = icon.stroke
            if (stroke != null) {
                iconStroke.color = ink
                iconStroke.strokeWidth = icon.strokeWidth
                canvas.drawPath(stroke, iconStroke)
            }
            canvas.restore()
        } else {
            val n = fillNumber(count)
            countPaint.color = ink
            val half = max(r, countPaint.measureText(numBuf, 0, n) * 0.5f + countPad * scale)
            rect.set(x - half, y - r, x + half, y + r)
            canvas.drawRoundRect(rect, r, r, markFillPaint)
            if (ring) canvas.drawRoundRect(rect, r, r, markStrokePaint)
            canvas.drawText(numBuf, 0, n, x, y + countPaint.textSize * CAP_CENTER, countPaint)
        }
    }

    private fun drawPill(canvas: Canvas, cx: Float, oy: Float, c: Double) {
        val p = pressT
        val h = lerp(pillHIdle, pillHPressed, p)
        val top = oy + lerp(pillTopIdle, pillTopPressed, p)
        pillTextPaint.textSize = lerp(pillTextIdle, pillTextPressed, p)
        StripPhysics.formatHms(tz, Math.round(c), timeBuf)
        val tw = pillTextPaint.measureText(timeBuf, 0, 8)
        val label = pillText()
        val pad = lerp(pillPadIdle, pillPadPressed, p)
        var lw = 0f
        var sepW = 0f
        if (label != null) {
            sepW = pillTextPaint.measureText(SEP)
            lw = sepW + pillTextPaint.measureText(label)
            if (tw + lw + pad * 2f > width - fadeW * 2f) lw = 0f
        }
        val content = tw + lw
        val target = max(content + pad * 2f, lerp(pillMinIdle, pillMinPressed, p))
        if (target != pillTargetW) {
            pillTargetW = target
            if (pillW == 0f || reducedMotion) pillW = target else scheduleFrame()
        }
        val pw = if (pillW > 0f) pillW else target
        rect.set(cx - pw * 0.5f, top, cx + pw * 0.5f, top + h)
        canvas.drawRoundRect(rect, h * 0.5f, h * 0.5f, pillPaint)
        canvas.save()
        canvas.clipRect(rect)
        val baseline = top + h * 0.5f + pillTextPaint.textSize * CAP_CENTER
        var x = cx - content * 0.5f
        canvas.drawText(timeBuf, 0, 8, x, baseline, pillTextPaint)
        if (label != null && lw > 0f) {
            x += tw
            canvas.drawText(SEP, x, baseline, pillTextPaint)
            canvas.drawText(label, x + sepW, baseline, pillTextPaint)
        }
        canvas.restore()
    }

    private fun pillText(): String? {
        val interacting = touchMode == T_DRAG || touchMode == T_PINCH || scrubbing
        return if (interacting) snapText else snapText ?: pillLabel
    }

    // ---------------------------------------------------------------- formatting (allocation-free)

    /** Writes the digits of n to numBuf from index 0 and returns their count. */
    private fun fillNumber(n: Int): Int {
        var v = max(0, n)
        var len = 0
        do {
            numBuf[numBuf.size - 1 - len] = '0' + v % 10
            v /= 10
            len++
        } while (v > 0 && len < numBuf.size)
        System.arraycopy(numBuf, numBuf.size - len, numBuf, 0, len)
        return len
    }

    private fun timeString(t: Double): String {
        StripPhysics.formatHms(tz, Math.round(t), timeBuf)
        return String(timeBuf)
    }

    // ---------------------------------------------------------------- construction helpers

    private class Icon(val fill: Path?, val stroke: Path?, val strokeWidth: Float)

    private fun buildIcons(): Array<Icon> {
        val person = PathParser.createPathFromPathData("M4.8 21c0-4.4 3.2-7.6 7.2-7.6s7.2 3.2 7.2 7.6z").apply {
            addCircle(12f, 6.5f, 3.6f, Path.Direction.CW)
        }
        val vehicle = PathParser.createPathFromPathData(
            "M3.5 16.5v-3.6l2-5.2A2.2 2.2 0 0 1 7.6 6.3h8.8a2.2 2.2 0 0 1 2.1 1.4l2 5.2v3.6a1 1 0 0 1-1 1h-1.2" +
                "a2.3 2.3 0 0 1-4.6 0H10.3a2.3 2.3 0 0 1-4.6 0H4.5a1 1 0 0 1-1-1zM6.6 11.6h10.8l-1.3-3.3H7.9z"
        )
        val bike = PathParser.createPathFromPathData("M5.8 16l4.2-7.5h5.2l3 7.5M10 8.5l3.4 7.5M13.6 5.5h2.6").apply {
            addCircle(5.8f, 16f, 3.6f, Path.Direction.CW)
            addCircle(18.2f, 16f, 3.6f, Path.Direction.CW)
        }
        val animal = PathParser.createPathFromPathData(
            "M12.3 11.2c3.2 0 5.9 3.8 5.9 6.3 0 1.9-1.7 2.8-3.2 2.3-1.1-.3-1.8-.7-2.7-.7s-1.6.4-2.7.7" +
                "c-1.5.5-3.2-.4-3.2-2.3 0-2.5 2.7-6.3 5.9-6.3z"
        ).apply {
            addCircle(6f, 10f, 2.3f, Path.Direction.CW)
            addCircle(10f, 6f, 2.3f, Path.Direction.CW)
            addCircle(14.6f, 6f, 2.3f, Path.Direction.CW)
            addCircle(18.6f, 10f, 2.3f, Path.Direction.CW)
        }
        val motion = PathParser.createPathFromPathData("M2.5 12h3.8l2.4-6 4.6 12 2.4-6h5.8")
        val speaker = PathParser.createPathFromPathData("M3.5 9.2h3.8L12.5 5v14l-5.2-4.2H3.5z")
        val waves = PathParser.createPathFromPathData("M15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12")
        val road = PathParser.createPathFromPathData("M12 3l10 18H2zM11 9.5h2v5.5h-2zM11 16.6h2v2h-2z").apply {
            fillType = Path.FillType.EVEN_ODD
        }
        val replay = PathParser.createPathFromPathData("M12 2.5l7 9.5-7 9.5-7-9.5z")
        val radar = PathParser.createPathFromPathData(
            "M3.5 17a12 12 0 0 1 17 0M6.8 13.6a7.4 7.4 0 0 1 10.4 0M10 10.3a2.9 2.9 0 0 1 4 0"
        )
        return Array(MarkKind.COUNT) { k ->
            when (k) {
                MarkKind.PERSON -> Icon(person, null, 0f)
                MarkKind.VEHICLE -> Icon(vehicle, null, 0f)
                MarkKind.BIKE -> Icon(null, bike, 2.4f)
                MarkKind.ANIMAL -> Icon(animal, null, 0f)
                MarkKind.DETERRENT -> Icon(speaker, waves, 2f)
                MarkKind.POTHOLE, MarkKind.BREAKER, MarkKind.ROUGH -> Icon(road, null, 0f)
                MarkKind.REPLAY -> Icon(replay, null, 0f)
                MarkKind.PROXIMITY -> Icon(null, radar, 2.4f)
                else -> Icon(null, motion, 2.6f)
            }
        }
    }

    private fun kindLabelRes(code: Int): Int = when (code) {
        MarkKind.PERSON -> R.string.timeline_kind_person
        MarkKind.VEHICLE -> R.string.timeline_kind_vehicle
        MarkKind.BIKE -> R.string.timeline_kind_bike
        MarkKind.ANIMAL -> R.string.timeline_kind_animal
        MarkKind.MOTION -> R.string.timeline_kind_motion
        MarkKind.DETERRENT -> R.string.timeline_kind_deterrent
        MarkKind.POTHOLE -> R.string.timeline_road_pothole
        MarkKind.BREAKER -> R.string.timeline_road_breaker
        MarkKind.ROUGH -> R.string.timeline_road_rough
        MarkKind.REPLAY -> R.string.timeline_moment_replay
        MarkKind.PROXIMITY -> R.string.timeline_moment_close_pass
        else -> R.string.timeline_kind_event
    }

    private fun stripeShader(period: Int, stripe: Float, base: Int, stripeColor: Int): BitmapShader {
        val bmp = Bitmap.createBitmap(period, period, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(base)
        c.drawRect(0f, 0f, stripe, period.toFloat(), Paint().apply { color = stripeColor })
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun dpx(v: Float): Float = v * density

    /** sp sizes, capped at 1.3x so the fixed-height strip never clips text. */
    private fun spx(v: Float): Float {
        val dm = resources.displayMetrics
        return min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm), v * dm.density * MAX_FONT_SCALE)
    }

    private fun textPaint(size: Float, face: Typeface, color: Int, align: Paint.Align, tabular: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            typeface = face
            this.color = color
            textAlign = align
            if (tabular) fontFeatureSettings = "tnum"
        }

    private fun strokePaint(color: Int, strokePx: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        this.color = color
    }

    private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun rgba(r: Int, g: Int, b: Int, a: Float): Int = Color.argb((a * 255f).roundToInt(), r, g, b)

    private fun alpha(color: Int, a: Float): Int =
        Color.argb((Color.alpha(color) * a).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun scaleAlpha(color: Int, a: Float): Int {
        if (a >= 1f) return color
        val al = ((color ushr 24) * a).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (al shl 24)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private companion object {
        const val T_NONE = 0
        const val T_PRESSED = 1
        const val T_DRAG = 2
        const val T_PINCH = 3
        const val A_NONE = 0
        const val A_FLING = 1
        const val A_BOUNCE = 2
        const val A_GLIDE = 3
        const val A_FADE = 4

        // Block layout in dp from its top: ruler 0-21, marks row 24-52, track 56-90 (sized for 13-16 sp text).
        const val TOP_INSET = 8f
        const val BLOCK_H = 96f
        const val LABEL_BASE = 14f
        const val MINOR_TOP = 18f
        const val MINOR_BOT = 21f
        const val PLAYHEAD_TOP = 22f
        const val PLAYHEAD_BOT = 94f
        const val MARK_Y = 38f
        const val GRID_TOP = 52f
        const val GRID_BOT = 94f
        const val TRACK_TOP = 56f
        const val TRACK_H = 34f
        const val MIN_FOOT_W = 8f
        const val MARK_R = 12f
        const val MARK_R_CURRENT = 14f
        const val ICON = 15f
        const val FADE_W = 40f
        const val LABEL_MIN_PX = 110f
        const val LABEL_CLEAR_PX = 58f
        const val GAP_LABEL_MIN_PX = 90f
        const val DRIVE_LABEL_MIN_PX = 64f
        const val SEAM_MIN_PX = 14f
        const val CLUSTER_PX = 26f
        const val CURRENT_PX = 9f
        const val SNAP_PX = 24f
        const val JUMP_PX = 48f
        const val HIT_PX = 24f
        const val CAP_CENTER = 0.355f

        const val DEFAULT_SPAN_MS = 6.0 * 3_600_000.0
        const val SEAM_MS = 120_000.0
        const val STEP_MS = 10_000L
        const val MOMENT_EPS_MS = 1_000.0
        /** A finger held still this long before lifting releases without momentum (older platforms keep the stale velocity). */
        const val FLING_STALE_MS = 60L
        const val MOMENT_DEDUPE_MS = 1_000L
        const val MOMENT_LABEL_MS = 5_000.0
        const val GAP_LABEL_MIN_MS = 4_000L
        const val CLUSTER_FIT = 0.45
        const val NOW_EXTRAPOLATE_CAP_MS = 120_000.0
        const val FOLLOW_TAU_S = 0.12
        const val PRESS_TAU_S = 0.04
        const val PILL_TAU_S = 0.06
        const val FLICK_MAX_RESPONSE_S = 3.0
        const val CLUSTER_ANIM_MS = 160L
        const val FADE_MS = 150L
        const val LONG_FADE_MS = 180L
        const val LONG_GLIDE_WIDTHS = 2.0
        const val FAST_PX_PER_S = 15.0
        const val FRAME_S = 1.0 / 60.0
        const val MAX_DT_S = 0.1
        const val SETTLE_PX = 0.5
        const val SETTLE_PX_PER_S = 20.0
        const val SPAN_LOG_EPS = 0.002
        const val SPAN_LOG_VEL_EPS = 0.02
        const val RANGE_USER_MS = 200L
        const val RANGE_PASSIVE_MS = 500L
        const val A11Y_MIN_MS = 1_000L
        const val MAX_TICKS = 512
        const val MAX_FONT_SCALE = 1.3f
        const val WHEEL_ZOOM = 1.25
        const val SQRT2 = 1.4142135623730951
        const val SEP = "  ·  "
    }
}
