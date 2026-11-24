package com.overdrive.app.ui.timeline.view

import android.animation.ValueAnimator
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.animation.PathInterpolator
import com.overdrive.app.ui.timeline.ClipSpec
import com.overdrive.app.ui.view.PlaybackDewarp
import com.overdrive.app.ui.view.ZoomableVideoView.Quadrant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GL thread behind [TimelineVideoView]. Decoder frames land in an OES texture owned here, which outlives
 * the view's surface; crop, zoom, lens correction, hold and crossfade are drawn in the shader.
 * Plain EGL14 like PlaybackDewarp: the daemon's EGLCore carries process-restart breakers.
 */
internal class TimelineGlRenderer(private val host: Host) {

    /** Called on the GL thread. */
    interface Host {
        fun onDecoderSurface(surface: Surface)
        fun onDecoderSurfaceLost()
        fun onCameraTapped(camera: Quadrant)
        fun onGeometry(camera: Quadrant, tiles: List<CameraTile>)
    }

    private class Prog(val id: Int) {
        val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(id, "uTex")
        val uStMatrix = GLES20.glGetUniformLocation(id, "uStMatrix")
        val uAlpha = GLES20.glGetUniformLocation(id, "uAlpha")
        val uK = GLES20.glGetUniformLocation(id, "uK")
        val uAspect = GLES20.glGetUniformLocation(id, "uAspect")
        val uHalfTexel = GLES20.glGetUniformLocation(id, "uHalfTexel")
        val uStrip = GLES20.glGetUniformLocation(id, "uStrip")
        val uClamp = GLES20.glGetUniformLocation(id, "uClamp")
    }

    private val thread = HandlerThread("TimelineGl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var released = false

    /** Presented zoom is above 1x; read on the UI thread for touch interception. */
    @Volatile var zoomed = false
        private set

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var bound: EGLSurface? = null
    private var windowTarget: SurfaceTexture? = null
    private var viewW = 0
    private var viewH = 0
    private val sizeQuery = IntArray(1)

    private var oesTex = 0
    private var input: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var pendingFrames = 0
    private var hasFrame = false
    private var lastFrameTs = 0L
    private var latchFailures = 0
    private val stMatrix = FloatArray(16)

    private var dewarpProg: Prog? = null
    private var plainProg: Prog? = null
    private var heldProg: Prog? = null
    private var fallback = false

    // Held pictures (texture + FBO); the slot that is not fromSlot receives the next capture.
    private val slotTex = IntArray(2)
    private val slotFbo = IntArray(2)
    private val slotW = IntArray(2)
    private val slotH = IntArray(2)
    private var fromSlot = 0

    private val schedule = FrameSchedule<ClipSpec>()
    private val viewport = ViewportState()
    private var lens: PlaybackDewarp.Spec? = null
    private var strength = -1
    private var videoW = 0
    private var videoH = 0
    private var pendingVideoW = -1
    private var pendingVideoH = -1

    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }
    private val applyPendingSize = Runnable {
        if (!released && pendingVideoW >= 0) {
            applyVideoSize(pendingVideoW, pendingVideoH)
            requestRender()
        }
    }

    private val vertices: FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quad = FloatArray(8)
    private val fit = FloatArray(2)
    private val k = FloatArray(2)
    private val placement = Placement()
    private val geometryKey = FloatArray(10)
    private var geometryCamera: Quadrant? = null
    private var geometryLayout: TileLayout? = null

    private var eglFailures = 0
    private var recoveries = 0
    private var recoveryWindowStartMs = 0L
    private var recoveryPending = false
    private val retired = ArrayList<Pair<SurfaceTexture, Surface?>>()

    fun start() = post { init() }

    fun surfaceAvailable(st: SurfaceTexture, width: Int, height: Int) = post { onWindowAvailable(st, width, height) }

    fun surfaceSizeChanged(width: Int, height: Int) = post {
        setViewSize(width, height)
        requestRender()
    }

    /** True when the GL thread takes [st] over and releases it after dropping its EGL surface. */
    fun surfaceDestroyed(st: SurfaceTexture): Boolean {
        if (released) return false
        return handler.post {
            if (!released && windowTarget === st) {
                destroyWindow()
                windowTarget = null
            }
            try { st.release() } catch (_: Throwable) {}
        }
    }

    fun setVideoSize(w: Int, h: Int) = post { onVideoSize(w, h) }

    fun setClipSpecNow(spec: ClipSpec?) = post {
        applySpec(spec)
        requestRender()
    }

    fun scheduleClipSpec(atNs: Long, spec: ClipSpec?) = post { onScheduleSpec(atNs, spec) }

    fun scheduleCrossfade(atNs: Long, durationMs: Long) = post { schedule.scheduleFade(atNs, durationMs) }

    fun holdFrame() = post { onHold() }

    fun setDewarpStrength(value: Int) = post {
        strength = value.coerceIn(0, 100)
        requestRender()
    }

    fun setCamera(q: Quadrant, animate: Boolean) = post {
        val reduced = animate && reducedMotion()
        if (reduced) fadeNow()
        viewport.setCamera(q, animate && !reduced, System.nanoTime())
        requestRender()
    }

    fun resetZoom(animate: Boolean) = post {
        val reduced = animate && reducedMotion()
        if (reduced) fadeNow()
        viewport.resetZoom(animate && !reduced, System.nanoTime())
        requestRender()
    }

    fun gestureBegin() = post {
        viewport.gestureBegin(System.nanoTime())
        requestRender()
    }

    fun scale(factor: Float, focusX: Float, focusY: Float) = post {
        viewport.scale(factor, focusX, focusY, System.nanoTime())
        requestRender()
    }

    fun pan(dx: Float, dy: Float) = post {
        viewport.pan(dx, dy, System.nanoTime())
        requestRender()
    }

    fun gestureEnd(vx: Float, vy: Float) = post {
        viewport.gestureEnd(vx, vy, System.nanoTime())
        requestRender()
    }

    fun tap(x: Float, y: Float) = post {
        val reduced = reducedMotion()
        if (reduced) fadeNow()
        val q = viewport.tap(x, y, !reduced, System.nanoTime())
        host.onCameraTapped(q)
        requestRender()
    }

    fun doubleTap(x: Float, y: Float) = post {
        val reduced = reducedMotion()
        if (reduced) fadeNow()
        viewport.doubleTap(x, y, !reduced, System.nanoTime())
        requestRender()
    }

    /** Tears down on the GL thread; true when it finished within [timeoutMs]. */
    fun release(timeoutMs: Long): Boolean {
        if (released) return true
        released = true
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                teardown()
            } finally {
                done.countDown()
            }
        }
        thread.quitSafely()
        if (!posted) return true
        if (Thread.currentThread() === thread) return false
        return try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private inline fun post(crossinline block: () -> Unit) {
        if (released) return
        handler.post { if (!released) block() }
    }

    // ---- GL thread ----

    private fun init() {
        choreographer = Choreographer.getInstance()
        if (!initEgl()) {
            Log.w(TAG, "EGL init failed")
            releaseGl()
            return
        }
        if (bindOffscreen()) createGlObjects()
    }

    private fun initEgl(): Boolean {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == null || dpy == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) return false
        display = dpy
        val cfg = chooseConfig(dpy, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
            ?: chooseConfig(dpy, EGL14.EGL_WINDOW_BIT)
            ?: return false
        config = cfg
        val ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return false
        context = ctx
        val type = IntArray(1)
        if (EGL14.eglGetConfigAttrib(dpy, cfg, EGL14.EGL_SURFACE_TYPE, type, 0) &&
            (type[0] and EGL14.EGL_PBUFFER_BIT) != 0
        ) {
            val pb = EGL14.eglCreatePbufferSurface(dpy, cfg,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (pb != null && pb != EGL14.EGL_NO_SURFACE) pbuffer = pb
        }
        return true
    }

    private fun chooseConfig(dpy: EGLDisplay, surfaceType: Int): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) return null
        return configs[0]
    }

    /** Programs, the OES texture and the decoder Surface; needs a current context. */
    private fun createGlObjects() {
        if (input != null) return
        if (plainProg == null) plainProg = buildOrNull(PLAIN_SHADER)
        if (dewarpProg == null) dewarpProg = buildOrNull(DEWARP_SHADER)
        if (heldProg == null) heldProg = buildOrNull(HELD_SHADER)
        try {
            if (oesTex == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                oesTex = ids[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
                texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            }
            val st = SurfaceTexture(oesTex)
            if (videoW > 0 && videoH > 0) st.setDefaultBufferSize(videoW, videoH)
            // Messages already posted for a replaced SurfaceTexture still arrive here; only the current one counts.
            st.setOnFrameAvailableListener({ t -> if (t === input) onFrameAvailable() }, handler)
            input = st
            val s = Surface(st)
            inputSurface = s
            host.onDecoderSurface(s)
        } catch (t: Throwable) {
            Log.w(TAG, "decoder surface failed: ${t.message}")
        }
    }

    private fun onFrameAvailable() {
        if (released) return
        if (pendingFrames < MAX_PENDING_FRAMES) pendingFrames++
        requestRender()
    }

    private fun requestRender() {
        if (released || frameScheduled) return
        val c = choreographer ?: return
        frameScheduled = true
        c.postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNs: Long) {
        frameScheduled = false
        if (released || context == EGL14.EGL_NO_CONTEXT) return
        if (!bindForWork()) return
        if (input == null) createGlObjects()
        if (pendingFrames > 0) latch(frameTimeNs)
        val moving = viewport.update(frameTimeNs)
        schedule.fadeProgress(frameTimeNs)
        zoomed = viewport.zoomed()
        // Not drawable: stop ticking; surface callbacks restart it and the time-based animations catch up.
        if (window == EGL14.EGL_NO_SURFACE || viewW <= 0 || viewH <= 0) return
        drawToWindow(frameTimeNs)
        publishGeometry()
        if (moving || schedule.fading) requestRender()
    }

    /** Latches every queued frame and applies what their timestamps make due. */
    private fun latch(frameTimeNs: Long) {
        val st = input ?: return
        val n = pendingFrames.coerceAtMost(MAX_LATCH)
        val snap = 1 - fromSlot
        if (schedule.wantsSnapshot(hasFrame, lastFrameTs) && capture(snap, frameTimeNs)) schedule.onSnapshot()
        var dueFade = -1L
        var specDue = false
        var latched = 0
        var failed = false
        try {
            while (latched < n) {
                st.updateTexImage()
                latched++
                val ts = st.timestamp
                lastFrameTs = ts
                if (schedule.takeDueSpec(ts)) specDue = true
                val f = schedule.takeDueFade(ts)
                if (f >= 0L) dueFade = f
            }
            latchFailures = 0
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed: ${t.message}")
            failed = true
            if (++latchFailures >= MAX_LATCH_FAILURES) scheduleRecovery()
        }
        // A queue that does not drop frames drains over the next vsyncs instead of lagging behind.
        pendingFrames = if (failed) 0 else pendingFrames - latched
        if (pendingFrames > 0) requestRender()
        // Entries popped for frames that did latch still apply to the picture now on the texture.
        if (latched == 0) return
        try { st.getTransformMatrix(stMatrix) } catch (_: Throwable) {}
        hasFrame = true
        if (specDue) applySpec(schedule.dueSpec)
        if (pendingVideoW >= 0) applyVideoSize(pendingVideoW, pendingVideoH)
        if (schedule.endLatch(dueFade, frameTimeNs)) fromSlot = snap
    }

    private fun onScheduleSpec(atNs: Long, spec: ClipSpec?) {
        schedule.scheduleSpec(atNs, spec)
        // Scheduled after its frame was latched: correct the picture now.
        if (hasFrame && atNs <= lastFrameTs + FrameSchedule.TOLERANCE_NS && schedule.takeDueSpec(lastFrameTs)) {
            applySpec(schedule.dueSpec)
            requestRender()
        }
    }

    private fun applySpec(next: ClipSpec?) {
        val layout = TileLayout.of(next)
        lens = next?.lens?.takeIf { layout != TileLayout.SINGLE && CameraRegions.lensUsable(it) }
        viewport.applyLayout(layout)
        updateViewportVideoSize()
    }

    /** A size reported while the last frame is still on screen waits for the next frame (or 250 ms). */
    private fun onVideoSize(w: Int, h: Int) {
        val ok = w in 1..MAX_DIM && h in 1..MAX_DIM
        val nw = if (ok) w else 0
        val nh = if (ok) h else 0
        if (nw == videoW && nh == videoH) {
            pendingVideoW = -1
            pendingVideoH = -1
            handler.removeCallbacks(applyPendingSize)
            return
        }
        if (!hasFrame || schedule.holdShown) {
            applyVideoSize(nw, nh)
            requestRender()
            return
        }
        pendingVideoW = nw
        pendingVideoH = nh
        handler.removeCallbacks(applyPendingSize)
        handler.postDelayed(applyPendingSize, SIZE_GRACE_MS)
    }

    private fun applyVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        pendingVideoW = -1
        pendingVideoH = -1
        handler.removeCallbacks(applyPendingSize)
        updateViewportVideoSize()
    }

    private fun updateViewportVideoSize() {
        val l = lens
        when {
            videoW > 0 && videoH > 0 -> viewport.setVideoSize(videoW, videoH)
            l != null -> viewport.setVideoSize(l.frameWidth, l.frameHeight)
            else -> viewport.setVideoSize(0, 0)
        }
    }

    private fun onHold() {
        if (!hasFrame || fallback || schedule.holdShown || !bindForWork()) return
        val slot = 1 - fromSlot
        if (capture(slot, System.nanoTime())) {
            fromSlot = slot
            schedule.hold()
            requestRender()
        }
    }

    /** Reduced motion: a short crossfade from the current picture replaces the spring. */
    private fun fadeNow() {
        if (!hasFrame || fallback || schedule.holdShown || !bindForWork()) return
        val slot = 1 - fromSlot
        val now = System.nanoTime()
        if (capture(slot, now)) {
            fromSlot = slot
            schedule.fadeNow(now, REDUCED_FADE_MS)
        }
    }

    private fun reducedMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

    /** Renders what is on screen now into [slot]. */
    private fun capture(slot: Int, nowNs: Long): Boolean {
        val w = viewW
        val h = viewH
        if (w <= 0 || h <= 0 || heldProg == null || fallback) return false
        return try {
            if (!ensureSlot(slot, w, h)) return false
            clearGlErrors()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slotFbo[slot])
            drawPresentation(w, h, nowNs)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glGetError() == GLES20.GL_NO_ERROR
        } catch (t: Throwable) {
            try { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) } catch (_: Throwable) {}
            false
        }
    }

    private fun ensureSlot(slot: Int, w: Int, h: Int): Boolean {
        if (slotTex[slot] != 0 && slotW[slot] == w && slotH[slot] == h) return true
        freeSlot(slot)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        slotTex[slot] = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        texParams(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, ids, 0)
        slotFbo[slot] = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, slotTex[slot], 0)
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        slotW[slot] = w
        slotH[slot] = h
        if (!complete) freeSlot(slot)
        return complete
    }

    private fun freeSlot(slot: Int) {
        if (slotFbo[slot] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(slotFbo[slot]), 0)
        if (slotTex[slot] != 0) GLES20.glDeleteTextures(1, intArrayOf(slotTex[slot]), 0)
        slotFbo[slot] = 0
        slotTex[slot] = 0
        slotW[slot] = 0
        slotH[slot] = 0
    }

    private fun drawToWindow(frameTimeNs: Long) {
        if (viewW <= 0 || viewH <= 0) return
        // Nothing latched yet (start, or after a rebuild): the view keeps its last picture instead of an empty one.
        if (!hasFrame && !schedule.holdShown && !schedule.fading) return
        val bw = querySize(EGL14.EGL_WIDTH, viewW)
        val bh = querySize(EGL14.EGL_HEIGHT, viewH)
        if (!drawChecked(bw, bh, frameTimeNs)) {
            if (fallback) return
            enterFallback()
            if (!drawChecked(bw, bh, frameTimeNs)) return
        }
        if (EGL14.eglSwapBuffers(display, window)) eglFailures = 0 else onEglFailure("eglSwapBuffers")
    }

    /** Unswapped on failure, so the view keeps its previous picture. */
    private fun drawChecked(w: Int, h: Int, nowNs: Long): Boolean = try {
        clearGlErrors()
        drawPresentation(w, h, nowNs)
        GLES20.glGetError() == GLES20.GL_NO_ERROR
    } catch (t: Throwable) {
        Log.w(TAG, "draw failed: ${t.message}")
        false
    }

    private fun enterFallback() {
        fallback = true
        schedule.clearPresentation()
        Log.w(TAG, "GL error, drawing plain frames")
    }

    private fun drawPresentation(w: Int, h: Int, nowNs: Long) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (fallback) {
            drawPlainFit()
            return
        }
        val p = schedule.fadeProgress(nowNs)
        when {
            schedule.fading -> {
                val e = FADE_EASE.getInterpolation(p)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                drawHeld(1f - e)
                drawLive(e)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            schedule.holdShown -> drawHeld(1f)
            else -> drawLive(1f)
        }
    }

    private fun drawLive(alpha: Float) {
        if (!hasFrame) return
        val r = viewport.place(placement)
        if (!CameraRegions.visibleQuad(viewW.toFloat(), viewH.toFloat(), r, placement, quad)) return
        val l = lens
        var dewarp = false
        if (l != null && dewarpProg != null) {
            CameraRegions.lensK(l, strength, k)
            dewarp = k[0] != 0f || k[1] != 0f
        }
        val prog = (if (dewarp) dewarpProg else plainProg) ?: return
        putQuad(quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], quad[6], quad[7])
        GLES20.glUseProgram(prog.id)
        bindOes(prog)
        GLES20.glUniform1f(prog.uAlpha, alpha)
        if (dewarp && l != null) {
            GLES20.glUniform2f(prog.uK, k[0], k[1])
            GLES20.glUniform1f(prog.uAspect, l.aspect)
            GLES20.glUniform2f(prog.uHalfTexel, 0.5f / l.frameWidth, 0.5f / l.frameHeight)
            GLES20.glUniform1f(prog.uStrip, l.stripFrac)
        } else {
            setClamp(prog, r)
        }
        drawQuad(prog)
    }

    private fun drawHeld(alpha: Float) {
        val prog = heldProg ?: return
        val tex = slotTex[fromSlot]
        if (tex == 0) return
        putQuad(0f, 0f, viewW.toFloat(), viewH.toFloat(), 0f, 0f, 1f, 1f)
        GLES20.glUseProgram(prog.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(prog.uTex, 0)
        GLES20.glUniform1f(prog.uAlpha, alpha)
        drawQuad(prog)
    }

    /** Fallback: whole frame aspect-fitted, no lens, no crop. */
    private fun drawPlainFit() {
        if (!hasFrame) return
        val prog = plainProg ?: return
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        CameraRegions.fitSize(w, h, viewport.videoW, viewport.videoH, CameraRegions.FULL, fit)
        val x0 = (w - fit[0]) / 2f
        val y0 = (h - fit[1]) / 2f
        putQuad(x0, y0, x0 + fit[0], y0 + fit[1], 0f, 0f, 1f, 1f)
        GLES20.glUseProgram(prog.id)
        bindOes(prog)
        GLES20.glUniform1f(prog.uAlpha, 1f)
        GLES20.glUniform4f(prog.uClamp, 0f, 0f, 1f, 1f)
        drawQuad(prog)
    }

    /** Keeps passthrough sampling inside the crop so neighbouring cameras never bleed in. */
    private fun setClamp(prog: Prog, r: Region) {
        val hx = if (viewport.videoW > 0) 0.5f / viewport.videoW else 0f
        val hy = if (viewport.videoH > 0) 0.5f / viewport.videoH else 0f
        var s0 = r.x + hx
        var s1 = r.x + r.w - hx
        var t0 = 1f - (r.y + r.h) + hy
        var t1 = 1f - r.y - hy
        if (s0 > s1) {
            s0 = r.x + r.w / 2f
            s1 = s0
        }
        if (t0 > t1) {
            t0 = 1f - (r.y + r.h / 2f)
            t1 = t0
        }
        GLES20.glUniform4f(prog.uClamp, s0, t0, s1, t1)
    }

    /** View-px rect with frame-normalized (y down) corners, as a strip in PlaybackDewarp's vertex order. */
    private fun putQuad(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float) {
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        val left = 2f * x0 / w - 1f
        val right = 2f * x1 / w - 1f
        val top = 1f - 2f * y0 / h
        val bottom = 1f - 2f * y1 / h
        val tTop = 1f - v0
        val tBottom = 1f - v1
        vertices.position(0)
        vertices.put(left).put(bottom).put(u0).put(tBottom)
        vertices.put(right).put(bottom).put(u1).put(tBottom)
        vertices.put(left).put(top).put(u0).put(tTop)
        vertices.put(right).put(top).put(u1).put(tTop)
    }

    private fun bindOes(prog: Prog) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(prog.uTex, 0)
        GLES20.glUniformMatrix4fv(prog.uStMatrix, 1, false, stMatrix, 0)
    }

    private fun drawQuad(prog: Prog) {
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(prog.aPosition)
        GLES20.glVertexAttribPointer(prog.aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(prog.aTexCoord)
        GLES20.glVertexAttribPointer(prog.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(prog.aPosition)
        GLES20.glDisableVertexAttribArray(prog.aTexCoord)
    }

    private fun publishGeometry() {
        val r = viewport.place(placement)
        val key = geometryKey
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        if (geometryCamera == viewport.camera && geometryLayout == viewport.layout &&
            key[0] == r.x && key[1] == r.y && key[2] == r.w && key[3] == r.h &&
            key[4] == placement.left && key[5] == placement.top &&
            key[6] == placement.width && key[7] == placement.height && key[8] == w && key[9] == h
        ) return
        key[0] = r.x
        key[1] = r.y
        key[2] = r.w
        key[3] = r.h
        key[4] = placement.left
        key[5] = placement.top
        key[6] = placement.width
        key[7] = placement.height
        key[8] = w
        key[9] = h
        geometryCamera = viewport.camera
        geometryLayout = viewport.layout
        host.onGeometry(viewport.camera, CameraRegions.tiles(viewport.layout, w, h, r, placement))
    }

    private fun onWindowAvailable(st: SurfaceTexture, width: Int, height: Int) {
        if (windowTarget !== st) destroyWindow()
        windowTarget = st
        eglFailures = 0
        setViewSize(width, height)
        if (context == EGL14.EGL_NO_CONTEXT && !recoveryPending && !initEgl()) {
            releaseGl()
            return
        }
        createWindow()
        requestRender()
    }

    private fun createWindow() {
        val st = windowTarget ?: return
        val cfg = config ?: return
        if (window != EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT) return
        val s = try {
            EGL14.eglCreateWindowSurface(display, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (t: Throwable) {
            null
        }
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            return
        }
        window = s
        if (bind(s) && input == null) createGlObjects()
    }

    private fun destroyWindow() {
        val s = window
        if (s == EGL14.EGL_NO_SURFACE) return
        window = EGL14.EGL_NO_SURFACE
        if (bound === s && !bindOffscreen()) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            bound = null
        }
        EGL14.eglDestroySurface(display, s)
    }

    private fun setViewSize(w: Int, h: Int) {
        viewW = w.coerceAtLeast(0)
        viewH = h.coerceAtLeast(0)
        viewport.setViewSize(viewW.toFloat(), viewH.toFloat())
    }

    private fun bind(surface: EGLSurface): Boolean {
        if (bound === surface) return true
        val ok = EGL14.eglMakeCurrent(display, surface, surface, context)
        bound = if (ok) surface else null
        return ok
    }

    /** Pbuffer, else surfaceless where the driver allows it. */
    private fun bindOffscreen(): Boolean =
        if (pbuffer != EGL14.EGL_NO_SURFACE) bind(pbuffer) else bind(EGL14.EGL_NO_SURFACE)

    private fun bindForWork(): Boolean {
        if (window != EGL14.EGL_NO_SURFACE) {
            if (bind(window)) return true
            onEglFailure("eglMakeCurrent")
            return false
        }
        if (pbuffer == EGL14.EGL_NO_SURFACE) return bind(EGL14.EGL_NO_SURFACE)
        if (bind(pbuffer)) return true
        onEglFailure("eglMakeCurrent")
        return false
    }

    private fun querySize(attr: Int, fallbackPx: Int): Int =
        if (EGL14.eglQuerySurface(display, window, attr, sizeQuery, 0) && sizeQuery[0] > 0) sizeQuery[0] else fallbackPx

    private fun onEglFailure(where: String) {
        val err = EGL14.eglGetError()
        Log.w(TAG, "$where failed: 0x" + Integer.toHexString(err))
        when (err) {
            EGL14.EGL_CONTEXT_LOST -> scheduleRecovery()
            EGL14.EGL_BAD_SURFACE, EGL14.EGL_BAD_NATIVE_WINDOW, EGL14.EGL_BAD_ALLOC -> {
                destroyWindow()
                if (++eglFailures <= MAX_WINDOW_RETRIES) {
                    createWindow()
                    requestRender()
                }
            }
            else -> if (++eglFailures > MAX_WINDOW_RETRIES) {
                eglFailures = 0
                scheduleRecovery()
            }
        }
    }

    private fun scheduleRecovery() {
        if (recoveryPending || released) return
        recoveryPending = true
        handler.post { recover() }
    }

    /** Context loss: rebuild everything; the decoder gets a new Surface. */
    private fun recover() {
        recoveryPending = false
        if (released) return
        val now = SystemClock.elapsedRealtime()
        if (now - recoveryWindowStartMs > RECOVERY_WINDOW_MS) {
            recoveryWindowStartMs = now
            recoveries = 0
        }
        if (++recoveries > MAX_RECOVERIES) {
            Log.w(TAG, "GL recovery limit reached")
            return
        }
        Log.w(TAG, "rebuilding GL")
        if (inputSurface != null) host.onDecoderSurfaceLost()
        retireInput()
        releaseGl()
        if (!initEgl()) {
            releaseGl()
            return
        }
        if (bindOffscreen()) createGlObjects()
        createWindow()
        requestRender()
    }

    /** The decoder renders into the old Surface until the player switches outputs; free its queue a little later. */
    private fun retireInput() {
        val st = input ?: return
        val s = inputSurface
        input = null
        inputSurface = null
        try { st.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        retired.add(st to s)
        handler.postDelayed({ freeRetired(st) }, RETIRED_SURFACE_MS)
    }

    private fun freeRetired(st: SurfaceTexture) {
        val i = retired.indexOfFirst { it.first === st }
        if (i < 0) return
        val s = retired.removeAt(i).second
        try { s?.release() } catch (_: Throwable) {}
        try { st.release() } catch (_: Throwable) {}
    }

    private fun teardown() {
        choreographer?.removeFrameCallback(frameCallback)
        frameScheduled = false
        handler.removeCallbacks(applyPendingSize)
        releaseGl()
        while (retired.isNotEmpty()) freeRetired(retired[0].first)
    }

    private fun releaseGl() {
        try { input?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { input?.release() } catch (_: Throwable) {}
        input = null
        pendingFrames = 0
        hasFrame = false
        schedule.clearPresentation()
        val dpy = display
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            try {
                if (context != EGL14.EGL_NO_CONTEXT && bindAny()) deleteGlObjects()
            } catch (_: Throwable) {}
            try {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            } catch (_: Throwable) {}
            try { if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, window) } catch (_: Throwable) {}
            try { if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuffer) } catch (_: Throwable) {}
            try { if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, context) } catch (_: Throwable) {}
            try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
        }
        // No eglTerminate: the default display is shared with the app's own rendering.
        oesTex = 0
        for (i in 0..1) {
            slotTex[i] = 0
            slotFbo[i] = 0
            slotW[i] = 0
            slotH[i] = 0
        }
        dewarpProg = null
        plainProg = null
        heldProg = null
        fallback = false
        window = EGL14.EGL_NO_SURFACE
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        config = null
        bound = null
    }

    private fun bindAny(): Boolean =
        (window != EGL14.EGL_NO_SURFACE && bind(window)) || bindOffscreen()

    private fun deleteGlObjects() {
        if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
        freeSlot(0)
        freeSlot(1)
        dewarpProg?.let { GLES20.glDeleteProgram(it.id) }
        plainProg?.let { GLES20.glDeleteProgram(it.id) }
        heldProg?.let { GLES20.glDeleteProgram(it.id) }
    }

    private fun clearGlErrors() {
        var n = 0
        while (n++ < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildOrNull(fragment: String): Prog? = try {
        Prog(buildProgram(VERTEX_SHADER, fragment))
    } catch (t: Throwable) {
        Log.w(TAG, "shader unavailable: ${t.message}")
        null
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        } catch (t: Throwable) {
            GLES20.glDeleteShader(vs)
            throw t
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw IllegalStateException("link failed: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val TAG = "TimelineGl"
        private const val MAX_LATCH = 4
        private const val MAX_PENDING_FRAMES = 8
        private const val MAX_LATCH_FAILURES = 3
        private const val RETIRED_SURFACE_MS = 3_000L
        private const val MAX_DIM = 8192
        private const val SIZE_GRACE_MS = 250L
        private const val REDUCED_FADE_MS = 150L
        private const val MAX_WINDOW_RETRIES = 2
        private const val MAX_RECOVERIES = 3
        private const val RECOVERY_WINDOW_MS = 60_000L
        private val FADE_EASE = PathInterpolator(0.23f, 1f, 0.32f, 1f)

        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = aTexCoord;
}
"""

        // PlaybackDewarp's fragment shader; vTex is the frame coordinate after crop/zoom, uAlpha premultiplies.
        private const val DEWARP_SHADER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uStMatrix;
uniform vec2 uK;
uniform float uAspect;
uniform vec2 uHalfTexel;
uniform float uStrip;
uniform float uAlpha;
varying vec2 vTex;
vec2 rectifyTile(vec2 t) {
    vec2 nxy = t * 2.0 - 1.0;
    vec2 nxyAspect = vec2(nxy.x, nxy.y * uAspect);
    float r2 = dot(nxyAspect, nxyAspect);
    float invDenom = 1.0 / (1.0 + uK.x * r2 + uK.y * r2 * r2);
    float aspect2 = uAspect * uAspect;
    float zoom = 1.0 + uK.x * aspect2 + uK.y * aspect2 * aspect2;
    vec2 srcAspect = nxyAspect * invDenom * zoom;
    vec2 camUV = vec2(srcAspect.x, srcAspect.y / uAspect) * 0.5 + 0.5;
    return clamp(camUV, vec2(0.0), vec2(1.0));
}
void main() {
    if (vTex.y > 1.0 - uStrip) {
        gl_FragColor = texture2D(uTex, (uStMatrix * vec4(vTex, 0.0, 1.0)).xy) * uAlpha;
        return;
    }
    vec2 tile = step(vec2(0.5), vTex) * 0.5;
    vec2 cam = rectifyTile((vTex - tile) * 2.0);
    vec2 src = clamp(tile + cam * 0.5, tile + uHalfTexel, tile + 0.5 - uHalfTexel);
    src.y = min(src.y, 1.0 - uStrip - uHalfTexel.y);
    gl_FragColor = texture2D(uTex, (uStMatrix * vec4(src, 0.0, 1.0)).xy) * uAlpha;
}
"""

        private const val PLAIN_SHADER = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uTex;
uniform mat4 uStMatrix;
uniform vec4 uClamp;
uniform float uAlpha;
varying vec2 vTex;
void main() {
    vec2 src = clamp(vTex, uClamp.xy, uClamp.zw);
    gl_FragColor = texture2D(uTex, (uStMatrix * vec4(src, 0.0, 1.0)).xy) * uAlpha;
}
"""

        private const val HELD_SHADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform float uAlpha;
varying vec2 vTex;
void main() {
    gl_FragColor = texture2D(uTex, vTex) * uAlpha;
}
"""
    }
}
