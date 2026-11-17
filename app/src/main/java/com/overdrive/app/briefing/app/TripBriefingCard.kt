package com.overdrive.app.briefing.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.overdrive.app.navmap.ui.HudColors
import com.overdrive.app.navmap.ui.HudPanelView
import com.overdrive.app.navmap.ui.HudShadow
import com.overdrive.app.navmap.ui.HudShadows
import com.overdrive.app.navmap.ui.HudShell
import com.overdrive.app.navmap.ui.HudType
import com.overdrive.app.overlay.OverlayPermissionChecker
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassPaths
import com.overdrive.app.overlay.glass.GlassTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the card shows. Key ids are app ids, [TripBriefingCard.KEY_NO] or [TripBriefingCard.KEY_NAME]. */
internal class CardSpec(
    val question: String,
    val title: String,
    val subtitle: String?,
    val subtitleWarn: Boolean,
    val keys: List<CardKey>,
    val night: Boolean,
)

internal class CardKey(val id: String, val label: String, val primary: Boolean)

/** The glass prompt window, top-centre on the head unit's main display. Main thread only. */
internal class TripBriefingCard private constructor(
    private val wm: WindowManager,
    private val view: TripBriefingCardView,
) {
    private val main = Handler(Looper.getMainLooper())
    private val removeNow = Runnable { remove() }
    private var closing = false
    private var removed = false

    /** Out of the way (and of touches) while reversing; the countdown waits too. */
    fun setHidden(hidden: Boolean) {
        if (closing) return
        view.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) view.pauseCountdown()
    }

    /** Back after reversing, with [ms] left on the countdown. */
    fun unhide(ms: Long) {
        if (closing) return
        view.visibility = View.VISIBLE
        view.resumeCountdown(ms)
    }

    /** Fades out and removes the window. */
    fun close() {
        if (closing) return
        closing = true
        view.answered = true
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-8f * view.resources.displayMetrics.density)
            .setDuration(OUT_MS).setInterpolator(AccelerateInterpolator()).withLayer()
            .withEndAction(removeNow).start()
        // animations stall while the screen is off
        main.postDelayed(removeNow, OUT_MS + 250L)
    }

    private fun remove() {
        main.removeCallbacks(removeNow)
        if (removed) return
        removed = true
        runCatching { wm.removeView(view) }
    }

    companion object {
        const val KEY_NO = "no"
        const val KEY_NAME = "name"
        private const val IN_MS = 180L
        private const val OUT_MS = 150L
        private const val MAX_W = 600f
        private const val TOP = 72f

        /** Null without the draw-over permission or when the window can't be added. */
        fun show(app: Context, ctx: Context, spec: CardSpec, showMs: Long, onKey: (String) -> Unit): TripBriefingCard? {
            if (!OverlayPermissionChecker.isGranted(app)) return null
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
            val dm = ctx.resources.displayMetrics
            val view = TripBriefingCardView(ctx, spec, onKey)
            val screenW = dm.widthPixels / dm.density
            view.panelW = min(MAX_W, screenW - view.shell.padL - view.shell.padR - 24f).coerceAtLeast(280f)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = ((TOP - view.shell.padT) * dm.density).roundToInt()
                title = "TripBriefing"
            }
            try {
                wm.addView(view, lp)
            } catch (t: Throwable) {
                return null
            }
            view.alpha = 0f
            view.translationY = -12f * dm.density
            view.animate().alpha(1f).translationY(0f).setDuration(IN_MS)
                .setInterpolator(PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).withLayer().start()
            view.startCountdown(showMs)
            return TripBriefingCard(wm, view)
        }
    }
}

/**
 * The card: a glass panel (the HUD's [HudShell]) with a direction badge, the question, the destination,
 * its duration or extra, then the keys. Up to three keys share one row; with more, the first row is the
 * preferred app and No. Layout is in dp from the glass's top-left, mirrored for right-to-left languages.
 */
@SuppressLint("ViewConstructor")
internal class TripBriefingCardView(
    context: Context,
    private val spec: CardSpec,
    private val onKey: (String) -> Unit,
) : ViewGroup(context) {

    private val u = resources.displayMetrics.density
    private val theme = GlassTheme.of(spec.night)
    val shell = HudShell(SHADOWS).apply { tint = 0.95f; radiusTop = RADIUS; radiusBottom = RADIUS }
    var panelW = 600f
    private var panelH = 0f
    /** Set once a key was pressed or the card is closing; later taps are ignored. */
    var answered = false

    private val keys = spec.keys.map { k ->
        BriefingKeyView(context, k).also { v ->
            v.setNight(spec.night)
            v.setOnClickListener {
                if (!answered) {
                    answered = true
                    onKey(k.id)
                }
            }
        }
    }
    private val rows: List<List<BriefingKeyView>> =
        if (keys.size <= 3) listOf(keys) else listOf(listOf(keys.first(), keys.last()), keys.subList(1, keys.size - 1))

    private val bar = View(context).apply {
        background = GradientDrawable().apply { cornerRadius = BAR_H / 2f * u; setColor(theme.accent) }
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassColor.alpha(theme.text, 0.12f) }
    private val trackRect = RectF()

    private val question = Line(HudType.paint(context, 600, 17f, 0.01f).apply { color = theme.text2 }, 17f, 14f)
    private val title = Line(HudType.paint(context, 700, 31f).apply { color = theme.text }, 31f, 22f)
    private val subtitle = Line(HudType.paint(context, 500, 21f).apply {
        color = if (spec.subtitleWarn) theme.warn else theme.text2
    }, 21f, 16f)

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudColors.WHITE }
    private var badgeX = 0f
    private var badgeY = 0f
    private var textTop = 0f

    init {
        setWillNotDraw(false)
        clipChildren = false
        for (k in keys) addView(k)
        addView(bar)
        contentDescription = listOfNotNull(spec.question, spec.title, spec.subtitle).joinToString(". ")
    }

    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun headerH() = LINE_Q + 2f + LINE_T + (if (spec.subtitle != null) 3f + LINE_S else 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textW = panelW - PAD_X * 2f - BADGE - BADGE_GAP
        question.fit(spec.question, textW)
        title.fit(spec.title, textW)
        spec.subtitle?.let { subtitle.fit(it, textW) }
        val head = headerH()
        textTop = PAD_TOP
        badgeY = PAD_TOP + (head - BADGE) / 2f
        badgeX = PAD_X
        val rowW = panelW - PAD_X * 2f
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (row in rows) {
            val kw = (rowW - KEY_GAP * (row.size - 1)) / row.size
            for (k in row) {
                k.glassW = kw
                k.measure(unspecified, unspecified)
            }
        }
        panelH = PAD_TOP + head + HEADER_GAP + rows.size * BriefingKeyView.KEY_H + (rows.size - 1) * ROW_GAP + PAD_BOTTOM
        bar.measure(
            MeasureSpec.makeMeasureSpec((rowW * u).roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((BAR_H * u).roundToInt(), MeasureSpec.EXACTLY),
        )
        trackRect.set(PAD_X, panelH - BAR_BOTTOM - BAR_H, panelW - PAD_X, panelH - BAR_BOTTOM)
        setMeasuredDimension(
            ceil((panelW + shell.padL + shell.padR) * u).toInt(),
            ceil((panelH + shell.padT + shell.padB) * u).toInt(),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gx = shell.padL * u
        val gy = shell.padT * u
        val rowW = panelW - PAD_X * 2f
        var top = PAD_TOP + headerH() + HEADER_GAP
        for (row in rows) {
            val kw = (rowW - KEY_GAP * (row.size - 1)) / row.size
            for ((i, k) in row.withIndex()) {
                val left = mirror(PAD_X + i * (kw + KEY_GAP), kw)
                val vx = (gx + left * u - k.padLeftPx).roundToInt()
                val vy = (gy + top * u - k.padTopPx).roundToInt()
                k.layout(vx, vy, vx + k.measuredWidth, vy + k.measuredHeight)
            }
            top += BriefingKeyView.KEY_H + ROW_GAP
        }
        val bx = (gx + trackRect.left * u).roundToInt()
        val by = (gy + trackRect.top * u).roundToInt()
        bar.layout(bx, by, bx + bar.measuredWidth, by + bar.measuredHeight)
        bar.pivotX = if (rtl) bar.measuredWidth.toFloat() else 0f
    }

    // x of a box [w] wide that sits at [x] in left-to-right layout
    private fun mirror(x: Float, w: Float) = if (rtl) panelW - x - w else x

    private var countdownMs = 0L

    fun startCountdown(ms: Long) {
        countdownMs = ms
        bar.scaleX = 1f
        resumeCountdown(ms)
    }

    fun pauseCountdown() {
        bar.animate().cancel()
    }

    /** From where the bar stands, or wider when more time is left than it shows. */
    fun resumeCountdown(ms: Long) {
        if (countdownMs > 0L) bar.scaleX = max(bar.scaleX, (ms.toFloat() / countdownMs).coerceIn(0f, 1f))
        bar.animate().scaleX(0f).setDuration(max(1L, ms)).setInterpolator(LinearInterpolator()).start()
    }

    override fun onDraw(c: Canvas) {
        if (panelH <= 0f) return
        shell.update(width, height, panelW, panelH, u, theme)
        shell.drawBack(c)
        c.save()
        c.clipPath(shell.path)
        c.translate(shell.panel.left, shell.panel.top)
        c.scale(u, u)
        drawBadge(c)
        val textX = PAD_X + BADGE + BADGE_GAP
        var y = textTop
        question.draw(c, textX, y, LINE_Q); y += LINE_Q + 2f
        title.draw(c, textX, y, LINE_T); y += LINE_T + 3f
        if (spec.subtitle != null) subtitle.draw(c, textX, y, LINE_S)
        c.drawRoundRect(trackRect, BAR_H / 2f, BAR_H / 2f, trackPaint)
        c.restore()
    }

    override fun dispatchDraw(c: Canvas) {
        c.save()
        c.clipPath(shell.path)
        super.dispatchDraw(c)
        c.restore()
        shell.drawFront(c)
    }

    private fun drawBadge(c: Canvas) {
        val x = mirror(badgeX, BADGE)
        if (badgePaint.shader == null) {
            badgePaint.shader = LinearGradient(0f, badgeY, 0f, badgeY + BADGE,
                HudColors.ctaTop(theme), HudColors.ctaBot(theme), Shader.TileMode.CLAMP)
        }
        c.drawCircle(x + BADGE / 2f, badgeY + BADGE / 2f, BADGE / 2f, badgePaint)
        BriefingKeyView.drawArrow(c, x + (BADGE - 26f) / 2f, badgeY + (BADGE - 26f) / 2f, 26f, glyphPaint)
    }

    private inner class Line(val paint: TextPaint, val size: Float, val minSize: Float) {
        var text: CharSequence = ""

        fun fit(s: String, width: Float) {
            paint.textSize = size
            val w = paint.measureText(s)
            if (w > width && w > 0f) paint.textSize = max(minSize, size * width / w)
            text = if (paint.measureText(s) > width) TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END) else s
        }

        // [x] is the text start in left-to-right layout
        fun draw(c: Canvas, x: Float, top: Float, lineH: Float) {
            paint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            c.drawText(text, 0, text.length, if (rtl) panelW - x else x, HudType.baseline(paint, top, lineH), paint)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            shell.place(panelW, panelH, u)
            if (!shell.panel.contains(e.x, e.y)) return false
        }
        return super.dispatchTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = true

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bar.animate().cancel()
        shell.release()
        for (k in keys) k.releaseLayers()
    }

    companion object {
        private val SHADOWS = arrayOf(HudShadow(0f, 10f, 24f, 0.85f), HudShadow(0f, 2f, 6f, 0.5f))
        private const val RADIUS = 30f
        private const val PAD_X = 26f
        private const val PAD_TOP = 22f
        private const val PAD_BOTTOM = 28f
        private const val BADGE = 52f
        private const val BADGE_GAP = 18f
        private const val HEADER_GAP = 20f
        private const val KEY_GAP = 12f
        private const val ROW_GAP = 12f
        private const val LINE_Q = 21f
        private const val LINE_T = 37f
        private const val LINE_S = 26f
        private const val BAR_H = 3f
        private const val BAR_BOTTOM = 11f
    }
}

/** A card key: lit accent glass with a direction arrow for the preferred app, neutral key glass otherwise. */
@SuppressLint("ViewConstructor")
internal class BriefingKeyView(context: Context, private val key: CardKey) : HudPanelView(context) {

    override val shell = HudShell(HudShadows.KEY).apply {
        radiusTop = RADIUS; radiusBottom = RADIUS
        front = HudShell.Front.KEY
        body = if (key.primary) HudShell.Body.CTA else HudShell.Body.KEY
        if (key.primary) halo = 4f
    }

    /** Glass width in dp, set by the card before measuring. */
    var glassW = 160f
    private val paint = HudType.paint(context, if (key.primary) 700 else 600, LABEL, 0.005f)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var text: CharSequence = key.label
    private var textW = 0f

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = key.label
    }

    override fun computePanel() {
        panelW = glassW
        panelH = KEY_H
        val avail = glassW - PAD * 2f - glyphSpace()
        paint.textSize = LABEL
        val w = paint.measureText(key.label)
        if (w > avail && w > 0f) paint.textSize = max(MIN_LABEL, LABEL * avail / w)
        text = if (paint.measureText(key.label) > avail) {
            TextUtils.ellipsize(key.label, paint, max(0f, avail), TextUtils.TruncateAt.END)
        } else key.label
        textW = paint.measureText(text, 0, text.length)
    }

    private fun glyphSpace() = if (key.primary) GLYPH + GLYPH_GAP else 0f

    override fun drawContent(c: Canvas) {
        val color = if (key.primary) HudColors.WHITE else theme.text
        paint.color = color
        glyphPaint.color = color
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val x = (panelW - glyphSpace() - textW) / 2f
        if (key.primary) drawArrow(c, if (rtl) x + textW + GLYPH_GAP else x, (KEY_H - GLYPH) / 2f, GLYPH, glyphPaint)
        val tx = if (rtl || !key.primary) x else x + GLYPH + GLYPH_GAP
        val lh = HudType.normal(paint.textSize)
        c.drawText(text, 0, text.length, tx, HudType.baseline(paint, (KEY_H - lh) / 2f, lh), paint)
    }

    companion object {
        const val KEY_H = 68f
        private const val RADIUS = 22f
        private const val LABEL = 22f
        private const val MIN_LABEL = 15f
        private const val PAD = 14f
        private const val GLYPH = 24f
        private const val GLYPH_GAP = 9f
        // Material "navigation" arrow, 24-unit box
        private const val ARROW = "M12,2L4.5,20.29L5.21,21L12,18L18.79,21L19.5,20.29Z"

        fun drawArrow(c: Canvas, x: Float, y: Float, size: Float, p: Paint) {
            c.save()
            c.translate(x, y)
            c.scale(size / 24f, size / 24f)
            c.drawPath(GlassPaths.svg(ARROW), p)
            c.restore()
        }
    }
}
