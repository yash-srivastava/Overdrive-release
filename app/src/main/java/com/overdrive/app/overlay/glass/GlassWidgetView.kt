package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Base for every glass widget: a. baked soft shadow · b. tinted glass body · c. the live object ·
 * d. front glass (lit top edge, darker bottom edge, thin outline). Static art is baked into bitmaps
 * that are redrawn only when it changes; [drawLive] (and [drawLiveTop], above the optional mid layer)
 * run per frame, and frames only run while something moves. Subclasses draw in design units (the
 * mock's px): the canvas is pre-scaled and clipped to the shell.
 */
@SuppressLint("ViewConstructor")
abstract class GlassWidgetView(
    context: Context,
    protected val env: GlassEnv,
    private val ticker: GlassTicker,
    val baseW: Float,
    val baseH: Float,
) : View(context) {

    protected val density = resources.displayMetrics.density
    protected val d get() = density * env.scale * sizeK
    /** Design units to px for this widget: the global scale times its own [sizeK]. */
    val unitPx get() = d

    /** This widget's own size on top of the global scale; an arranged widget can be resized on its own. */
    var sizeK = 1f
        set(v) { if (field != v) { field = v; requestLayout(); layersChanged(); envChanged() } }

    // shell size in design units; animates for shape changes (RoadSense pill)
    private val wS = Spring.shape(baseW)
    private val hS = Spring.shape(baseH)
    private val rS = Spring.shape(RADIUS)
    protected val shellW get() = wS.value
    protected val shellH get() = hS.value
    /** True while the shell animates; everything draws live then. */
    protected val shaping get() = wS.moving || hS.moving || rS.moving
    val morphing get() = shaping
    // the larger of the sizes a morph runs between, which the host keeps room for until it ends
    private var morphW = baseW
    private var morphH = baseH
    fun reserveWidthPx() = ((if (shaping) morphW else wS.value) + PAD_L + PAD_R) * d
    fun reserveHeightPx() = ((if (shaping) morphH else hS.value) + PAD_T + PAD_B) * d
    /** The glass in px at the shape it is heading to (RoadSense may be a pill), and its corner radius now. */
    val glassWPx get() = wS.target * d
    val glassHPx get() = hS.target * d
    val glassRadiusPx get() = rS.value * d
    /** The shell's origin in view px, for mapping touches into design units. */
    protected val shellLeftPx get() = PAD_L * d
    protected val shellTopPx get() = PAD_T * d

    /** No fresh data: the object dims and desaturates behind fogged glass. Loops stop, so it all bakes. */
    var stale = false
        set(v) { if (field != v) { field = v; layersChanged(); envChanged() } }

    // baked layers: back covers the view (shadows, body, drawBack); mid and front cover the shell
    private var back: Bitmap? = null
    private var mid: Bitmap? = null
    private var front: Bitmap? = null
    private var version = 0
    private var bakedVersion = -1
    private var bakedW = 0
    private var bakedH = 0
    private var bakedNight = false
    private var bakedTint = -1f
    private var bakedScale = -1f
    private var bakedStale = false
    private var bakedShellW = -1f
    private var bakedShellH = -1f
    private var layerX = 0
    private var layerY = 0

    private val shellRect = RectF()
    private val shellPath = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stalePaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.08f)
            postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.38f, 0f)))
        })
    }

    // shadows: blurred once per shell size and shared by every widget of that size
    private val nearShadow = ShadowSlot(2f, 3f, 7f, 0.55f)
    private val farShadow = ShadowSlot(8f, 12f, 24f, 1f)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowDst = RectF()

    // unit shaders placed with local matrices: re-placed only when the shell changes (a bake, or a morph)
    private var shaderNight: Boolean? = null
    private var shaderTint = -1f
    private lateinit var bodyLinear: LinearGradient
    private lateinit var bodyRadial: RadialGradient
    private lateinit var hiLinear: LinearGradient
    private lateinit var hiRadial: RadialGradient
    private lateinit var thickLinear: LinearGradient
    private lateinit var outline: LinearGradient
    private lateinit var fogShader: RadialGradient
    private lateinit var sheenShader: LinearGradient
    private val gm = Matrix()

    private var sheenStart = -1L
    private val startSheen = Runnable { if (!env.hidden) { sheenStart = SystemClock.uptimeMillis(); ticker.kick() } }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenMode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val sweepEase = PathInterpolator(0.35f, 0.1f, 0.25f, 1f)

    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fogPlate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fogText = GlassFonts.paint(context, 600, 16f, 0)
    private val fogIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND }
    private var fogMin = Int.MIN_VALUE
    private var fogLabel = ""
    private val tmpRect = RectF()

    init {
        setLayerType(LAYER_TYPE_NONE, null)
        ticker.add(this)
    }

    // ── measuring ────────────────────────────────────────────────────────────

    // the view follows the animated shell, so neighbours slide and an aligned edge stays put while it morphs
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (wS.value + PAD_L + PAD_R) * d
        val h = (hS.value + PAD_T + PAD_B) * d
        setMeasuredDimension(w.roundToInt(), h.roundToInt())
    }

    // past the larger size by a few percent of the step (the overshoot), and a couple of units more
    private fun morphSlack(step: Float) = 2f + abs(step) * 0.02f

    /** Animate the shell to a new size (a pill when [pill]), or jump there when [instant]. */
    protected fun setShellSize(w: Float, h: Float, pill: Boolean, instant: Boolean) {
        if (w == wS.target && h == hS.target) return
        val now = instant || env.reduceMotion
        // the shape spring settles with a slight overshoot; the reserve covers it so the window doesn't resize
        morphW = max(wS.value, w) + morphSlack(w - wS.value); morphH = max(hS.value, h) + morphSlack(h - hS.value)
        wS.set(w, now); hS.set(h, now); rS.set(if (pill) h / 2f else RADIUS, now)
        if (now) layersChanged()
        requestLayout()
        ticker.kick()
    }

    /** The glass's corner radius (half its size makes a circle), animated unless [instant]. */
    protected fun setShellRadius(r: Float, instant: Boolean) {
        if (r == rS.target) return
        rS.set(r, instant || env.reduceMotion)
        layersChanged()
        ticker.kick()
    }

    // ── per-frame ────────────────────────────────────────────────────────────

    private var lastDrawnAt = 0L
    private var wasBusy = false

    /** A widget whose motion is slow (a scrolling trace) redraws at most this often; 0 draws every frame. */
    protected open val minFrameMs: Long get() = 0L

    /** Called by the ticker each frame; returns true while this widget still needs frames. */
    fun tick(nowMs: Long, dt: Float): Boolean {
        var busy = false
        var smooth = false
        if (shaping) {
            wS.step(dt); hS.step(dt); rS.step(dt)
            busy = true; smooth = true
            requestLayout()
            if (!shaping) layersChanged()
        }
        if (sheenStart >= 0) {
            if (env.hidden || nowMs - sheenStart > SHEEN_MS) sheenStart = -1 else { busy = true; smooth = true }
        }
        if (animate(nowMs, dt)) busy = true
        // the tick that ends an animation changed something too (a spring's last step): draw it
        if ((busy && (smooth || minFrameMs <= 0L || nowMs - lastDrawnAt >= minFrameMs)) || (!busy && wasBusy)) { lastDrawnAt = nowMs; invalidate() }
        wasBusy = busy
        return busy
    }

    protected fun requestFrames() = ticker.kick()

    /** Re-bake the static layers: something [drawBack], [drawMid] or [drawFront] paints has changed. */
    protected fun layersChanged() { version++; invalidate() }

    protected open fun animate(nowMs: Long, dt: Float): Boolean = false
    protected abstract fun drawBack(c: Canvas)
    protected abstract fun drawLive(c: Canvas, nowMs: Long)
    protected abstract fun drawFront(c: Canvas)
    /** A widget with baked art between two live passes paints it here and draws the upper pass in [drawLiveTop]. */
    protected open val hasMid: Boolean get() = false
    protected open fun drawMid(c: Canvas) {}
    protected open fun drawLiveTop(c: Canvas, nowMs: Long) {}

    // ── entry / exit ─────────────────────────────────────────────────────────

    fun enter(delayMs: Long) {
        animate().cancel()
        removeCallbacks(startSheen)
        if (env.reduceMotion) {
            alpha = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f
            animate().alpha(1f).setDuration(150).setStartDelay(0).start()
            return
        }
        alpha = 0f; translationY = 10f * density; scaleX = 0.985f; scaleY = 0.985f
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(delayMs).setDuration(ENTER_MS)
            .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()
        postDelayed(startSheen, delayMs + 100)
    }

    fun exit() {
        animate().cancel()
        removeCallbacks(startSheen)
        sheenStart = -1
        val a = animate().alpha(0f).setStartDelay(0).setDuration(if (env.reduceMotion) 150 else EXIT_MS)
        if (!env.reduceMotion) a.translationY(6f * density).scaleX(0.985f).scaleY(0.985f)
        a.start()
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        updateShellPath()
        ensureShaders()
        if (shaping) {
            drawShadows(c)
            drawBody(c)
            drawContent(c, 0f, 0f, now)
            drawFrontGlass(c)
        } else {
            ensureLayers(now)
            back?.let { c.drawBitmap(it, 0f, 0f, bitmapPaint) }
            if (!stale) {
                inShell(c, 0f, 0f) { drawLive(it, now) }
                if (hasMid) {
                    mid?.let { c.drawBitmap(it, layerX.toFloat(), layerY.toFloat(), bitmapPaint) }
                    inShell(c, 0f, 0f) { drawLiveTop(it, now) }
                }
            }
            front?.let { c.drawBitmap(it, layerX.toFloat(), layerY.toFloat(), bitmapPaint) }
        }
        if (stale) drawFog(c)
        if (sheenStart >= 0) drawSheen(c, now)
    }

    /** Clip to the shell and switch to design units; ([ox], [oy]) is the canvas origin in view px. */
    private inline fun inShell(c: Canvas, ox: Float, oy: Float, block: (Canvas) -> Unit) {
        c.save()
        c.translate(-ox, -oy)
        c.clipPath(shellPath)
        c.translate(shellRect.left, shellRect.top)
        c.scale(d, d)
        block(c)
        c.restore()
    }

    /** Every content layer in order, desaturated and dimmed as one group when stale. */
    private fun drawContent(c: Canvas, ox: Float, oy: Float, now: Long) = inShell(c, ox, oy) { k ->
        if (stale) k.saveLayer(null, stalePaint)
        drawBack(k)
        drawLive(k, now)
        if (hasMid) { drawMid(k); drawLiveTop(k, now) }
        drawFront(k)
        if (stale) k.restore()
    }

    private fun updateShellPath() {
        shellRect.set(PAD_L * d, PAD_T * d, (PAD_L + wS.value) * d, (PAD_T + hS.value) * d)
        shellPath.rewind()
        shellPath.addRoundRect(shellRect, rS.value * d, rS.value * d, Path.Direction.CW)
    }

    private fun ensureLayers(now: Long) {
        if (width <= 0 || height <= 0) return
        val night = env.theme.night
        if (back != null && bakedVersion == version && bakedW == width && bakedH == height && bakedNight == night &&
            bakedTint == env.tint && bakedScale == d && bakedStale == stale && bakedShellW == wS.value && bakedShellH == hS.value) return
        bakedVersion = version; bakedW = width; bakedH = height; bakedNight = night
        bakedTint = env.tint; bakedScale = d; bakedStale = stale; bakedShellW = wS.value; bakedShellH = hS.value

        back = reuse(back, width, height).also { bmp ->
            val c = Canvas(bmp)
            drawShadows(c)
            drawBody(c)
            if (stale) drawContent(c, 0f, 0f, now) else inShell(c, 0f, 0f) { drawBack(it) }
        }
        // mid and front only cover the shell, from a whole-pixel origin so they blit without resampling
        layerX = floor(shellRect.left).toInt(); layerY = floor(shellRect.top).toInt()
        val lw = ceil(shellRect.right).toInt() - layerX
        val lh = ceil(shellRect.bottom).toInt() - layerY
        val ox = layerX.toFloat(); val oy = layerY.toFloat()
        mid = if (hasMid && !stale) reuse(mid, lw, lh).also { bmp -> inShell(Canvas(bmp), ox, oy) { drawMid(it) } }
        else { mid?.recycle(); null }
        front = reuse(front, lw, lh).also { bmp ->
            val c = Canvas(bmp)
            if (!stale) inShell(c, ox, oy) { drawFront(it) }
            c.save(); c.translate(-ox, -oy); drawFrontGlass(c); c.restore()
        }
    }

    private fun reuse(b: Bitmap?, w: Int, h: Int): Bitmap {
        if (b != null && !b.isRecycled && b.width == w && b.height == h) { b.eraseColor(0); return b }
        b?.recycle()
        return Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    }

    // a. two shadows cast down-right, because the light is top-left
    private inner class ShadowSlot(val dx: Float, val dy: Float, val blur: Float, val alphaK: Float) {
        var mask: GlassShadows.Mask? = null
        fun draw(c: Canvas) {
            val tw = wS.target * d; val th = hS.target * d; val tr = rS.target * d; val bl = blur * d
            val m = mask?.takeIf { it.matches(tw, th, tr, bl) } ?: GlassShadows.get(tw, th, tr, bl).also { mask = it }
            // the mask is built for the target shell; while the shell animates it stretches to fit
            val kx = shellRect.width() / tw; val ky = shellRect.height() / th
            val l = shellRect.left + dx * d - m.pad * kx
            val t = shellRect.top + dy * d - m.pad * ky
            shadowDst.set(l, t, l + m.bitmap.width * m.down * kx, t + m.bitmap.height * m.down * ky)
            shadowPaint.color = GlassColor.alpha(env.theme.shadow, env.theme.shadowA * alphaK)
            c.drawBitmap(m.bitmap, null, shadowDst, shadowPaint)
        }
    }

    private fun drawShadows(c: Canvas) { nearShadow.draw(c); farShadow.draw(c) }

    private fun ensureShaders() {
        val t = env.theme
        if (shaderNight == t.night && shaderTint == env.tint) return
        shaderNight = t.night; shaderTint = env.tint
        val white = 0xFFFFFFFF.toInt()
        bodyLinear = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(GlassColor.alpha(t.glassTop, env.tint), GlassColor.alpha(t.glassBot, env.tint)), floatArrayOf(0f, 0.72f), Shader.TileMode.CLAMP)
        bodyRadial = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(t.glassTop, env.tint * 0.75f), GlassColor.clear(t.glassTop)), floatArrayOf(0f, 0.62f), Shader.TileMode.CLAMP)
        hiLinear = LinearGradient(0f, 0f, 0f, 1f, GlassColor.alpha(white, 0.075f), GlassColor.clear(white), Shader.TileMode.CLAMP)
        hiRadial = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(white, 0.07f), GlassColor.clear(white)), floatArrayOf(0f, 0.56f), Shader.TileMode.CLAMP)
        thickLinear = LinearGradient(0f, 0f, 0f, 1f, GlassColor.clear(t.edgeDark), GlassColor.alpha(t.edgeDark, t.thickA * 0.45f), Shader.TileMode.CLAMP)
        outline = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(
            GlassColor.alpha(white, t.outlineHi), GlassColor.alpha(white, t.outlineHi * 0.22f),
            GlassColor.alpha(t.edgeDark, t.outlineLo * 0.5f), GlassColor.alpha(t.edgeDark, t.outlineLo)), floatArrayOf(0f, 0.34f, 0.7f, 1f), Shader.TileMode.CLAMP)
        fogShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(t.fog, t.fogA * 0.85f), GlassColor.alpha(t.fog, t.fogA * 1.25f)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        sheenShader = LinearGradient(0f, 0f, 1f, 0f,
            intArrayOf(GlassColor.clear(white), GlassColor.alpha(white, t.sheenA * 0.35f), GlassColor.alpha(white, t.sheenA), GlassColor.alpha(white, t.sheenA * 0.35f), GlassColor.clear(white)),
            floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP)
    }

    // b. tinted body: a lit radial at the top-left over a 166° top-to-bottom gradient
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun drawBody(c: Canvas) {
        val r = shellRect
        c.drawPath(shellPath, bodyPaint.shade(angle(bodyLinear, 166f, r)))
        c.drawPath(shellPath, bodyPaint.shade(ellipse(bodyRadial, r.left + r.width() * 0.06f, r.top - r.height() * 0.12f, r.width() * 1.3f, r.height() * 0.95f)))
    }

    // d. front glass: soft top highlight, lit top edge, thick bottom edge, graded outline
    private val frontPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePath = Path()
    private val offsetPath = Path()
    private fun drawFrontGlass(c: Canvas) {
        val t = env.theme
        val r = shellRect
        c.save()
        c.clipPath(shellPath)
        frontPaint.style = Paint.Style.FILL
        gm.setScale(1f, r.height() * 0.36f); gm.postTranslate(0f, r.top); hiLinear.setLocalMatrix(gm)
        c.drawRect(r, frontPaint.shade(hiLinear))
        c.drawRect(r, frontPaint.shade(ellipse(hiRadial, r.left, r.top, r.width() * 0.85f, r.height() * 0.7f)))
        gm.setScale(1f, 12f * d); gm.postTranslate(0f, r.bottom - 12f * d); thickLinear.setLocalMatrix(gm)
        c.drawRect(r, frontPaint.shade(thickLinear))
        frontPaint.shader = null
        insetEdge(c, 1.2f * d, GlassColor.alpha(0xFFFFFFFF.toInt(), t.litA))
        insetEdge(c, -3f * d, GlassColor.alpha(t.edgeDark, t.thickA))
        c.restore()

        frontPaint.style = Paint.Style.STROKE
        frontPaint.strokeWidth = 1f * density
        tmpRect.set(r.left + 0.5f * density, r.top + 0.5f * density, r.right - 0.5f * density, r.bottom - 0.5f * density)
        c.drawRoundRect(tmpRect, rS.value * d, rS.value * d, frontPaint.shade(angle(outline, 150f, r)))
        frontPaint.shader = null
        frontPaint.style = Paint.Style.FILL
    }

    /** The CSS inset-shadow trick: the part of the shell not covered by itself moved by [dy]. */
    private fun insetEdge(c: Canvas, dy: Float, color: Int) {
        offsetPath.set(shellPath); offsetPath.offset(0f, dy)
        edgePath.set(shellPath)
        edgePath.op(offsetPath, Path.Op.DIFFERENCE)
        frontPaint.color = color
        c.drawPath(edgePath, frontPaint)
    }

    private fun drawFog(c: Canvas) {
        val t = env.theme
        val r = shellRect
        c.save(); c.clipPath(shellPath)
        c.drawRect(r, fogPaint.shade(ellipse(fogShader, r.left + r.width() * 0.28f, r.top + r.height() * 0.18f, r.width() * 1.2f, r.height() * 1.1f)))
        c.restore()
        if (env.lastSeenMin != fogMin) { fogMin = env.lastSeenMin; fogLabel = lastSeenText(fogMin) }
        c.save()
        c.translate(r.centerX(), r.bottom - 12f * d)
        c.scale(d, d)
        fogText.color = t.text
        val tw = fogText.measureText(fogLabel)
        val w = tw + 18f + 8f + 27f
        tmpRect.set(-w / 2f, -34f, w / 2f, 0f)
        fogPlate.color = GlassColor.alpha(t.fogPlate, 0.82f)
        c.drawRoundRect(tmpRect, 17f, 17f, fogPlate)
        fogIcon.color = GlassColor.alpha(t.text, 0.75f)
        val ix = tmpRect.left + 12f + 9f; val iy = -17f
        c.drawCircle(ix, iy, 7.6f, fogIcon)
        c.drawLine(ix, iy - 4.4f, ix, iy, fogIcon); c.drawLine(ix, iy, ix + 3f, iy + 2f, fogIcon)
        c.drawText(fogLabel, ix + 9f + 8f, iy + 5.5f, fogText)
        c.restore()
    }

    // the band moves with the canvas, not the shader, so the sweep doesn't rebuild it every frame
    private fun drawSheen(c: Canvas, now: Long) {
        val r = shellRect
        val k = sweepEase.getInterpolation(((now - sheenStart).toFloat() / SHEEN_MS).coerceIn(0f, 1f))
        val band = r.width() * 0.42f
        sheenPaint.shade(sheenShader)
        sheenPaint.xfermode = if (env.theme.night) sheenMode else null
        c.save()
        c.clipPath(shellPath)
        c.translate(r.left + band * (-1.4f + 4.8f * k), 0f)
        c.skew(-0.287f, 0f)
        c.scale(band, 1f)
        c.drawRect(-1f, -1e4f, 2f, 1e4f, sheenPaint)
        c.restore()
    }

    /** Places a unit (0→1 along x) gradient as CSS linear-gradient([deg]) across [r]: 0° points up, 90° right. */
    private fun <S : Shader> angle(s: S, deg: Float, r: RectF): S {
        val a = Math.toRadians(deg.toDouble())
        val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
        val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
        gm.setScale(half * 2f, 1f)
        gm.postRotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        gm.postTranslate(r.centerX() - dx * half, r.centerY() - dy * half)
        s.setLocalMatrix(gm)
        return s
    }

    /** Places a unit radial gradient as an ellipse (CSS radial-gradient(rx ry at cx cy)). */
    private fun <S : Shader> ellipse(s: S, cx: Float, cy: Float, rx: Float, ry: Float): S {
        gm.setScale(max(1e-3f, rx), max(1e-3f, ry)); gm.postTranslate(cx, cy)
        s.setLocalMatrix(gm)
        return s
    }

    fun themeChanged() {
        onThemeChanged()
        layersChanged()
    }

    protected open fun onThemeChanged() {}

    /** The host changed [env] (driving, reduce motion, visibility); widgets with gated loops react here. */
    open fun envChanged() { requestFrames(); invalidate() }

    /** Detach keeps the baked layers: the host re-adds a kept widget without re-baking it. */
    fun release() {
        ticker.remove(this)
        removeCallbacks(startSheen)
        back?.recycle(); mid?.recycle(); front?.recycle()
        back = null; mid = null; front = null
        bakedVersion = -1
    }

    companion object {
        const val PAD_L = 22f
        const val PAD_T = 18f
        const val PAD_R = 40f
        const val PAD_B = 46f
        /** Shell-to-shell spacing when widgets sit side by side (their shadow margins overlap). */
        const val GAP = 16f
        const val RADIUS = 26f
        const val ENTER_MS = 420L
        const val EXIT_MS = 280L
        const val SHEEN_MS = 950L

        fun lastSeenText(min: Int): String = when {
            min < 1 -> gs(R.string.glass_last_seen_now)
            min < 60 -> gs(R.string.glass_last_seen_min, min)
            else -> gs(R.string.glass_last_seen_h, min / 60)
        }

        /** CSS linear-gradient(angle) across [r]: 0° points up, 90° right. For baking only (allocates). */
        fun angleGradient(deg: Float, r: RectF, colors: IntArray, stops: FloatArray): LinearGradient {
            val a = Math.toRadians(deg.toDouble())
            val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
            val half = (abs(r.width() * dx) + abs(r.height() * dy)) / 2f
            return LinearGradient(r.centerX() - dx * half, r.centerY() - dy * half, r.centerX() + dx * half, r.centerY() + dy * half, colors, stops, Shader.TileMode.CLAMP)
        }

        /** Elliptical radial gradient (CSS radial-gradient(rx ry at cx cy)). For baking only (allocates). */
        fun ellipseGradient(cx: Float, cy: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray): RadialGradient {
            val g = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)
            g.setLocalMatrix(Matrix().apply { setScale(max(1e-3f, rx), max(1e-3f, ry)); postTranslate(cx, cy) })
            return g
        }
    }
}

/**
 * Blurred rounded-rect masks for the widget shadows (ALPHA_8, half resolution), shared by every
 * widget with the same shell size. A CPU box blur: BlurMaskFilter draws wrong on this head unit.
 */
object GlassShadows {
    class Mask(val bitmap: Bitmap, val down: Int, val pad: Float, private val w: Int, private val h: Int, private val r: Int, private val b: Int) {
        fun matches(wPx: Float, hPx: Float, rPx: Float, blurPx: Float) =
            !bitmap.isRecycled && w == wPx.roundToInt() && h == hPx.roundToInt() && r == rPx.roundToInt() && b == blurPx.roundToInt()
    }

    private val cache = ArrayList<Mask>()

    fun get(wPx: Float, hPx: Float, rPx: Float, blurPx: Float): Mask {
        for (m in cache) if (m.matches(wPx, hPx, rPx, blurPx)) return m
        val down = 2
        // blur is the CSS blur radius (2σ); a 3-pass box of radius σ spreads 3σ each side
        val box = max(1, (blurPx / 2f / down).roundToInt())
        val spread = box * 3 + 1
        val mw = ceil(wPx / down).toInt() + spread * 2
        val mh = ceil(hPx / down).toInt() + spread * 2
        val src = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        Canvas(src).drawRoundRect(spread.toFloat(), spread.toFloat(), spread + wPx / down, spread + hPx / down, rPx / down, rPx / down, p)
        val blurred = GlassBlur.blurAlpha(src, box)
        src.recycle()
        val alpha = blurred.extractAlpha()
        blurred.recycle()
        // widgets hold their masks directly, so an evicted one is only dropped, never recycled under them
        if (cache.size >= 12) cache.removeAt(0)
        return Mask(alpha, down, (spread * down).toFloat(), wPx.roundToInt(), hPx.roundToInt(), rPx.roundToInt(), blurPx.roundToInt()).also { cache.add(it) }
    }
}
