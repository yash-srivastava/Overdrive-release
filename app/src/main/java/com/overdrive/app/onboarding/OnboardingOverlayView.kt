package com.overdrive.app.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.overdrive.app.R

/**
 * The single native primitive every onboarding mechanism renders through:
 * a full-screen translucent scrim with an optional rounded-rect CUTOUT punched over an
 * anchored control, plus a tip CARD (title + body + buttons) with a caret pointing at
 * the cutout. The cutout MORPHS between targets so the spotlight glides rather than
 * jumps — the signature moment of the guide.
 *
 * Mechanisms are just configurations of this one view:
 *   - first-run-modal      : centered card, no cutout, full scrim
 *   - spotlight-tour-stop  : cutout + caret + Next; morphs to the next anchor
 *   - guided-wizard-step   : cutout on a control the user must touch (touch passes through)
 *   - jit-inline-tip       : cutout + dismiss-on-tap; for destructive anchors the first
 *                            touch is CONSUMED (see [consumeCutoutTouch])
 *   - empty-state-hint     : card anchored to an empty container, scrim de-emphasized
 *   - what-is-this         : small card, dismiss-on-tap
 *
 * PERF (Adreno 610, shared DDR bus with the H.265 encoder):
 *   - exactly one scrim path fill + one optional stroked ring + one caret per frame,
 *     touching only this view's own hardware layer — never the live camera surface
 *   - cutout morph is a manual RectF lerp (NO ChangeBounds/MotionLayout re-measure)
 *   - HW layer is promoted only for the duration of a transition, demoted at rest
 *   - the decorative emphasis ring is suppressed whenever [encoderBusy] returns true;
 *     the mandatory feedback (scrim + cutout morph + card spring) always plays
 *   - no infinite invalidate loops — every animator is a bounded one-shot
 */
class OnboardingOverlayView @JvmOverloads constructor(
    context: Context,
    /** Synchronous "is the H.265 encoder active?" supplier; host caches the async probe. */
    private val encoderBusy: () -> Boolean = { false },
) : FrameLayout(context) {

    // ---- geometry --------------------------------------------------------------------

    private val cutoutRect = RectF()
    private var cutoutRadius = 0f
    private var hasCutout = false

    // emphasis ring (0..1 expand + alpha), one-shot
    private var ringProgress = 0f
    private var ringAlpha = 0f

    // ---- paints ----------------------------------------------------------------------

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveScrimColor()
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = resolveAccentColor()
    }
    private val scrimPath = Path()

    // ---- card ------------------------------------------------------------------------

    /** The tip card child. */
    val card: MaterialCardView = MaterialCardView(context).apply {
        radius = dp(20f)          // matches the app's canonical card radius
        cardElevation = dp(6f)
        useCompatPadding = true
        val lp = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt()
                .coerceAtMost(dp(420f).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.CENTER
        layoutParams = lp
    }

    // Card content (inflated from view_onboarding_card.xml).
    private val content: View =
        LayoutInflater.from(context).inflate(R.layout.view_onboarding_card, card, false)
    private val stepIndicator: TextView = content.findViewById(R.id.onboardingStepIndicator)
    private val titleView: TextView = content.findViewById(R.id.onboardingTitle)
    private val bodyView: TextView = content.findViewById(R.id.onboardingBody)
    private val progressBar: ProgressBar = content.findViewById(R.id.onboardingProgress)
    private val primaryButton: MaterialButton = content.findViewById(R.id.onboardingPrimary)
    private val secondaryButton: MaterialButton = content.findViewById(R.id.onboardingSecondary)

    // ---- animators -------------------------------------------------------------------

    private var morphAnim: ValueAnimator? = null
    private var ringAnim: ValueAnimator? = null
    private var cardSpringX: SpringAnimation? = null
    private var cardSpringY: SpringAnimation? = null

    init {
        // ViewGroup needs this to get onDraw called for the scrim.
        setWillNotDraw(false)
        // Consume/pass-through is decided in onTouchEvent, NOT via isClickable — a
        // clickable ViewGroup would swallow every touch even when we want pass-through.
        card.addView(content)
        addView(card)

        // SAFE AREA: the overlay fills android.R.id.content, which spans BEHIND the car's
        // nav header/footer (system bars + display cutout). Capture those insets so the
        // tip card is never positioned under the header/footer where it'd be clipped.
        // fitsSystemWindows=false so we receive raw insets and lay the card out ourselves.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            )
            safeInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            // Re-place against the new safe area if a step is already showing.
            if (hasCutout && !cutoutRect.isEmpty) post { positionCardFor(cutoutRect) }
            else if (centered) post { centerCard() }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The overlay is added to the content root (or a dialog decor) LONG after the
        // Activity's initial window-insets traversal, and a plain addView does NOT
        // re-dispatch insets to the new child. Without this, safeInsets would stay (0,0,0,0)
        // and the FIRST card would clamp across the full window — under the car nav
        // header/footer, the exact clipping this guards against. Seed synchronously from
        // rootWindowInsets (covers OEM ROMs that won't re-dispatch), then request a proper
        // pass. Mirrors RoadSenseMapActivity's requestApplyInsets fix.
        seedInsetsFromRoot()
        androidx.core.view.ViewCompat.requestApplyInsets(this)
    }

    private fun seedInsetsFromRoot() {
        val raw = androidx.core.view.ViewCompat.getRootWindowInsets(this) ?: return
        val bars = raw.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
        )
        safeInsets.set(bars.left, bars.top, bars.right, bars.bottom)
    }

    /** System-bar + cutout insets (the car nav header/footer); the card stays inside these. */
    private val safeInsets = Rect()
    private var centered = false
    // Last spotlight anchor, so rotation can re-resolve its (new) on-screen bounds. The
    // anchor View survives rotation because MainActivity uses configChanges (no recreate).
    private var lastAnchor: View? = null
    private var lastAnchorPadding = 0f

    /**
     * Re-layout after a rotation/config change. The card width is sized off the launch
     * orientation's display width, and a spotlight cutout is captured at point-in-time —
     * both go stale on rotate. Recompute the card width, re-request insets, and either
     * re-resolve the live anchor's new bounds or recenter. Mirrors RoadSenseMapActivity's
     * onConfigurationChanged → applyResponsiveLayout + requestApplyInsets pattern.
     */
    fun onConfigChanged() {
        // Recompute responsive card width for the new orientation.
        val lp = card.layoutParams as LayoutParams
        lp.width = (resources.displayMetrics.widthPixels * 0.7f).toInt()
            .coerceAtMost(dp(420f).toInt())
        card.layoutParams = lp
        androidx.core.view.ViewCompat.requestApplyInsets(this)
        // Re-place once the new width/insets settle.
        post {
            val anchor = lastAnchor
            if (hasCutout && anchor != null && anchor.isAttachedToWindow && anchor.width > 0) {
                spotlight(anchor, lastAnchorPadding, animate = false)
            } else if (centered) {
                centerCard()
            }
        }
    }

    // ---- public API ------------------------------------------------------------------

    /**
     * Bind the card content for one step. [stepLabel] (e.g. "STEP 2 OF 6") is shown only
     * when non-null. The primary button always shows; the secondary (skip/re-pick) shows
     * only when [secondaryText] is non-null. A null [onPrimary] hides the primary button
     * entirely (used for the passive "applying…" state).
     */
    fun bindStep(
        title: CharSequence,
        body: CharSequence,
        primaryText: CharSequence?,
        onPrimary: (() -> Unit)?,
        secondaryText: CharSequence? = null,
        onSecondary: (() -> Unit)? = null,
        stepLabel: CharSequence? = null,
    ) {
        stepIndicator.text = stepLabel ?: ""
        stepIndicator.visibility = if (stepLabel != null) View.VISIBLE else View.GONE
        titleView.text = title
        bodyView.text = body

        if (primaryText != null && onPrimary != null) {
            primaryButton.visibility = View.VISIBLE
            primaryButton.text = primaryText
            primaryButton.setOnClickListener { onPrimary() }
        } else {
            primaryButton.visibility = View.GONE
            primaryButton.setOnClickListener(null)
        }

        if (secondaryText != null) {
            secondaryButton.visibility = View.VISIBLE
            secondaryButton.text = secondaryText
            secondaryButton.setOnClickListener { onSecondary?.invoke() }
        } else {
            secondaryButton.visibility = View.GONE
            secondaryButton.setOnClickListener(null)
        }
        // Content size may change → re-place the card against the current cutout.
        if (hasCutout && !cutoutRect.isEmpty) post { positionCardFor(cutoutRect) }
    }

    /** Show/update the determinate progress bar (0..100); pass null to hide it. */
    fun setProgress(percent: Int?) {
        if (percent == null) {
            progressBar.visibility = View.GONE
        } else {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = percent.coerceIn(0, 100)
        }
    }

    /**
     * Show as a centered modal (no cutout). Full scrim. Used for first-run-modal and
     * completion moments.
     */
    fun showCentered() {
        hasCutout = false
        centered = true
        cancelMorph()
        centerCard()
        invalidate()
    }

    /** Center the card within the SAFE area (between the nav header and footer insets). */
    private fun centerCard() {
        if (card.width == 0) { post { centerCard() }; return }
        clampCardHeight()
        val top = safeInsets.top.toFloat()
        val bottom = (height - safeInsets.bottom).toFloat()
        val left = safeInsets.left.toFloat()
        val right = (width - safeInsets.right).toFloat()
        springCardTo(
            x = (left + right - card.width) / 2f,
            y = (top + bottom - card.height) / 2f,
        )
    }

    /**
     * Bound the card's height to the safe band so a long body (or a verbose locale) can
     * never push the card taller than the screen — the inner NestedScrollView then scrolls
     * the content while the pinned Next/Skip row stays on-screen. Recomputed each placement
     * (handles rotation / inset changes).
     */
    private fun clampCardHeight() {
        if (width == 0 || height == 0) return
        val margin = dp(16f).toInt()
        val maxH = (height - safeInsets.top - safeInsets.bottom - 2 * margin).coerceAtLeast(dp(120f).toInt())
        val lp = card.layoutParams as LayoutParams
        // Only override when the natural (wrap) height would exceed the band; otherwise
        // leave WRAP_CONTENT so short cards keep their natural size.
        val desired = if (card.measuredHeight > maxH || card.height > maxH) maxH
        else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height != desired) {
            lp.height = desired
            card.layoutParams = lp
        }
    }

    /**
     * Spotlight an anchor view: morph the cutout onto its on-screen bounds (expanded by
     * [padding]) and reposition the card above or below it. If [animate] is false (first
     * stop), the cutout snaps; otherwise it glides with the emphasized curve.
     */
    fun spotlight(anchor: View, padding: Float = dp(8f), animate: Boolean = true) {
        lastAnchor = anchor
        lastAnchorPadding = padding
        val target = boundsInOverlay(anchor)
        target.inset(-padding, -padding)
        val targetRadius = dp(16f)
        hasCutout = true
        centered = false
        if (!animate || morphAnim == null && cutoutRect.isEmpty) {
            cutoutRect.set(target)
            cutoutRadius = targetRadius
            invalidate()
            positionCardFor(target)
            maybePulseRing()
        } else {
            morphCutoutTo(target, targetRadius)
        }
    }

    /**
     * For destructive-adjacent JIT tips: when true, the overlay swallows the first touch
     * inside the cutout (so reading the warning can't fire e.g. a pm-disable/reboot).
     * When false (default), touches inside the cutout pass through to the live control
     * so guided-wizard steps can drive the real dialog underneath.
     */
    var consumeCutoutTouch: Boolean = false

    /** Set the encoder-suppression-aware emphasis on/off without re-morphing. */
    fun pulseAttention() = maybePulseRing()

    // ---- drawing ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Scrim with an even-odd hole — no offscreen layer, no PorterDuff.
        scrimPath.reset()
        scrimPath.fillType = Path.FillType.EVEN_ODD
        scrimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (hasCutout && !cutoutRect.isEmpty) {
            scrimPath.addRoundRect(cutoutRect, cutoutRadius, cutoutRadius, Path.Direction.CW)
        }
        canvas.drawPath(scrimPath, scrimPaint)

        // One-shot emphasis ring just outside the cutout.
        if (hasCutout && ringAlpha > 0f && !cutoutRect.isEmpty) {
            val grow = dp(8f) * ringProgress
            ringPaint.alpha = (ringAlpha * 255).toInt().coerceIn(0, 255)
            val r = RectF(cutoutRect)
            r.inset(-grow, -grow)
            canvas.drawRoundRect(r, cutoutRadius + grow, cutoutRadius + grow, ringPaint)
        }
    }

    // ---- touch -----------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Let touches on the card itself reach its buttons (don't steal from children).
        if (isPointInChild(card, ev.x, ev.y)) return false
        // Everything else routes to our onTouchEvent, which decides consume vs pass-through.
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // A spotlight-hole touch on a GUIDED step must reach the live control underneath
        // (a sibling of this overlay in the dialog/decor view tree). Returning false here
        // makes dispatchTouchEvent return false, so the parent forwards the event to the
        // next sibling. We do NOT rely on isClickable for the consume decision — this
        // explicit return is the single source of truth and fixes the bug where a
        // ViewGroup with isClickable=true swallowed every event regardless of intercept.
        if (hasCutout && !cutoutRect.isEmpty &&
            cutoutRect.contains(event.x, event.y) && !consumeCutoutTouch
        ) {
            return false   // pass through to the real control (Prev/Next, radios, spinner)
        }
        // Scrim taps and consumed-cutout (destructive-guard) taps are absorbed.
        return true
    }

    // ---- internals -------------------------------------------------------------------

    private fun morphCutoutTo(target: RectF, targetRadius: Float) {
        cancelMorph()
        val from = RectF(cutoutRect)
        val fromR = if (cutoutRect.isEmpty) targetRadius else cutoutRadius
        if (cutoutRect.isEmpty) cutoutRect.set(target)  // first paint baseline
        val travel = Math.hypot(
            (target.centerX() - from.centerX()).toDouble(),
            (target.centerY() - from.centerY()).toDouble(),
        )
        val longTravel = travel > width * 0.4
        promoteHardwareLayer()
        morphAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (longTravel) OnboardingMotion.DURATION_MORPH_MAX else OnboardingMotion.DURATION_MEDIUM
            interpolator = OnboardingMotion.EMPHASIZED
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                cutoutRect.set(
                    lerp(from.left, target.left, t),
                    lerp(from.top, target.top, t),
                    lerp(from.right, target.right, t),
                    lerp(from.bottom, target.bottom, t),
                )
                cutoutRadius = lerp(fromR, targetRadius, t)
                invalidate()
            }
            addListener(onEnd = {
                demoteHardwareLayer()
                positionCardFor(target)
                maybePulseRing()
            })
            start()
        }
        // Card travels concurrently so the hole + its caption move as one object.
        positionCardFor(target)
    }

    private fun maybePulseRing() {
        // Decorative — the FIRST thing dropped under bus pressure.
        if (encoderBusy()) {
            ringAlpha = 0f
            invalidate()
            return
        }
        ringAnim?.cancel()
        ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OnboardingMotion.DURATION_EMPHASIS
            interpolator = OnboardingMotion.EMPHASIZED
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                ringProgress = t
                ringAlpha = 1f - t   // expand out + fade away — a single pulse
                invalidate()
            }
            addListener(onEnd = { ringAlpha = 0f; invalidate() })
            start()
        }
    }

    /**
     * Place the card above the cutout if there's room below the top half, else below —
     * clamped to the SAFE area so it never lands under the car's nav header/footer.
     */
    private fun positionCardFor(target: RectF) {
        if (card.width == 0) {
            // Not measured yet — defer one layout pass.
            post { positionCardFor(target) }
            return
        }
        val margin = dp(16f)
        // Safe bounds inset by the system bars / cutout (the nav header & footer).
        val safeLeft = safeInsets.left + margin
        val safeTop = safeInsets.top + margin
        val safeRight = width - safeInsets.right - margin
        val safeBottom = height - safeInsets.bottom - margin
        val minX = safeLeft
        val maxX = (safeRight - card.width).coerceAtLeast(minX)
        val minY = safeTop
        val maxY = (safeBottom - card.height).coerceAtLeast(minY)

        val placeBelow = target.centerY() < height / 2f
        val cardX = (target.centerX() - card.width / 2f).coerceIn(minX, maxX)
        val cardY = if (placeBelow) {
            (target.bottom + margin).coerceIn(minY, maxY)
        } else {
            (target.top - card.height - margin).coerceIn(minY, maxY)
        }
        springCardTo(cardX, cardY)
    }

    private fun springCardTo(x: Float, y: Float) {
        cardSpringX?.cancel()
        cardSpringY?.cancel()
        cardSpringX = SpringAnimation(card, DynamicAnimation.X, x).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            start()
        }
        cardSpringY = SpringAnimation(card, DynamicAnimation.Y, y).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            start()
        }
    }

    private fun cancelMorph() {
        morphAnim?.cancel()
        morphAnim = null
    }

    private fun promoteHardwareLayer() = setLayerType(LAYER_TYPE_HARDWARE, null)
    private fun demoteHardwareLayer() = setLayerType(LAYER_TYPE_NONE, null)

    /** Map an anchor view's bounds into this overlay's coordinate space. */
    private fun boundsInOverlay(anchor: View): RectF {
        val anchorLoc = IntArray(2)
        val selfLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        getLocationOnScreen(selfLoc)
        val left = (anchorLoc[0] - selfLoc[0]).toFloat()
        val top = (anchorLoc[1] - selfLoc[1]).toFloat()
        return RectF(left, top, left + anchor.width, top + anchor.height)
    }

    private fun isPointInChild(child: View, x: Float, y: Float): Boolean {
        val r = Rect()
        child.getHitRect(r)
        return r.contains(x.toInt(), y.toInt())
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics,
    )

    private fun resolveScrimColor(): Int {
        // M3 has no dedicated scrim attr in this material version; the scrim is a
        // ~72% black dim, matching the app's other full-screen dimming surfaces.
        return (0xB8 shl 24) or (Color.BLACK and 0x00FFFFFF)
    }

    private fun resolveAccentColor(): Int {
        // colorPrimary lives under appcompat's attr namespace in this project, not material's.
        val c = resolveAttr(androidx.appcompat.R.attr.colorPrimary)
        return if (c != 0) c else Color.WHITE
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelMorph()
        ringAnim?.cancel(); ringAnim = null
        cardSpringX?.cancel(); cardSpringY?.cancel()
    }
}

/** Small adapter so we can use a lambda for ValueAnimator end without the full listener. */
private fun ValueAnimator.addListener(onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
    })
}
