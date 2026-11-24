package com.overdrive.app.ui.view

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
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Display-time lens correction for clips the CPU engine recorded raw:
 * MediaPlayer → our OES SurfaceTexture → per-tile dewarp shader → [output].
 * The math is GpuMosaicRecorder's rectifyTile applied tile-locally, so a raw
 * clip shows exactly what the GPU engine would have recorded at the same slider.
 * Plain EGL14 on purpose: the daemon's EGLCore carries process-restart breakers.
 */
class PlaybackDewarp(
    private val output: Surface,
    private val spec: Spec,
    private val onFailure: (PlaybackDewarp) -> Unit,
) {
    /**
     * Per-clip dewarp parameters: live slider coefficients + the tag's geometry.
     * [stripFrac]: the opaque telemetry strip at the top, as a fraction of the frame; 0 = none.
     */
    data class Spec(
        val k1: Float,
        val k2: Float,
        val aspect: Float,
        val frameWidth: Int,
        val frameHeight: Int,
        val stripFrac: Float = 0f,
    )

    private val thread = HandlerThread("PlaybackDewarp").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var released = false
    @Volatile private var failed = false
    @Volatile private var tornDown = false

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTex = 0
    private var input: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var quad: FloatBuffer? = null
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTex = -1
    private var uStMatrix = -1
    private var uK = -1
    private var uAspect = -1
    private var uHalfTexel = -1
    private var uStrip = -1
    private val stMatrix = FloatArray(16)
    private val surfaceSize = IntArray(1)

    /** Builds the GL stack (bounded wait) and returns the Surface MediaPlayer renders into. */
    @Throws(Exception::class)
    fun start(timeoutMs: Long = INIT_TIMEOUT_MS): Surface {
        if (released) throw IllegalStateException("dewarp released")
        val done = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("dewarp init timed out")
        }
        error?.let { throw IllegalStateException("dewarp init failed: ${it.message}", it) }
        return inputSurface ?: throw IllegalStateException("dewarp input surface missing")
    }

    /**
     * Tears down on the GL thread. True when the output surface is already free again
     * (teardown done within [timeoutMs]); otherwise [onReleased] runs on the GL thread once it is.
     */
    fun release(timeoutMs: Long = RELEASE_TIMEOUT_MS, onReleased: (() -> Unit)? = null): Boolean {
        if (released) return tornDown
        released = true
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                tearDownGl()
            } finally {
                tornDown = true
                done.countDown()
                onReleased?.invoke()
            }
        }
        thread.quitSafely()
        if (!posted) {
            tornDown = true
            return true
        }
        if (Thread.currentThread() === thread) return false
        return try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun initGl() {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")
        display = dpy
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            throw IllegalStateException("no EGL config")
        }
        val config = configs[0] ?: throw IllegalStateException("no EGL config")
        val ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
        context = ctx
        val surface = EGL14.eglCreateWindowSurface(dpy, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")
        eglSurface = surface
        if (!EGL14.eglMakeCurrent(dpy, surface, surface, ctx)) throw IllegalStateException("eglMakeCurrent failed")

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uAspect = GLES20.glGetUniformLocation(program, "uAspect")
        uHalfTexel = GLES20.glGetUniformLocation(program, "uHalfTexel")
        uStrip = GLES20.glGetUniformLocation(program, "uStrip")

        quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(QUAD).apply { position(0) }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(spec.frameWidth, spec.frameHeight)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        input = st
        inputSurface = Surface(st)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
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
            throw IllegalStateException("dewarp link failed: $log")
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
            throw IllegalStateException("dewarp shader failed: $log")
        }
        return shader
    }

    private fun drawFrame() {
        if (released || failed) return
        val st = input ?: return
        val surface = eglSurface
        if (surface == EGL14.EGL_NO_SURFACE) return
        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("eglMakeCurrent 0x" + Integer.toHexString(EGL14.eglGetError()))
            }
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            // The host may resize the output buffer (view resize); draw at its current size.
            val w = if (EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, surfaceSize, 0)) surfaceSize[0] else spec.frameWidth
            val h = if (EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, surfaceSize, 0)) surfaceSize[0] else spec.frameHeight
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val vb = quad ?: return
            vb.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vb)
            vb.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vb)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform2f(uK, spec.k1, spec.k2)
            GLES20.glUniform1f(uAspect, spec.aspect)
            GLES20.glUniform2f(uHalfTexel, 0.5f / spec.frameWidth, 0.5f / spec.frameHeight)
            GLES20.glUniform1f(uStrip, spec.stripFrac)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
            if (!EGL14.eglSwapBuffers(display, surface)) {
                throw IllegalStateException("eglSwapBuffers 0x" + Integer.toHexString(EGL14.eglGetError()))
            }
        } catch (t: Throwable) {
            if (!failed) {
                failed = true
                Log.w(TAG, "dewarp frame failed: ${t.message}")
                onFailure(this)
            }
        }
    }

    private fun tearDownGl() {
        try { input?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { input?.release() } catch (_: Throwable) {}
        input = null
        val dpy = display
        if (dpy == EGL14.EGL_NO_DISPLAY) return
        try {
            if (context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(dpy, eglSurface, eglSurface, context)
            ) {
                if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
        } catch (_: Throwable) {}
        oesTex = 0
        program = 0
        // Unbind first so destroying the window surface disconnects it from the output now.
        try { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) {}
        try { if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, eglSurface) } catch (_: Throwable) {}
        eglSurface = EGL14.EGL_NO_SURFACE
        try { if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, context) } catch (_: Throwable) {}
        context = EGL14.EGL_NO_CONTEXT
        try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
        // No eglTerminate: the default display is shared with the app's own rendering.
        display = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private const val TAG = "PlaybackDewarp"
        private const val INIT_TIMEOUT_MS = 1000L
        private const val RELEASE_TIMEOUT_MS = 500L

        // x, y, u, v. Texture (0,0) is the frame's bottom-left, as SurfaceTexture expects.
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = aTexCoord;
}
"""

        // The dewarp is point-symmetric about each tile centre, so working in
        // texture space (y up) matches the recorder's image-space result.
        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uStMatrix;
uniform vec2 uK;
uniform float uAspect;
uniform vec2 uHalfTexel;
uniform float uStrip;
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
    // The telemetry strip (top of the frame) is shown as recorded, never sampled for the picture.
    if (vTex.y > 1.0 - uStrip) {
        gl_FragColor = texture2D(uTex, (uStMatrix * vec4(vTex, 0.0, 1.0)).xy);
        return;
    }
    vec2 tile = step(vec2(0.5), vTex) * 0.5;
    vec2 cam = rectifyTile((vTex - tile) * 2.0);
    vec2 src = clamp(tile + cam * 0.5, tile + uHalfTexel, tile + 0.5 - uHalfTexel);
    src.y = min(src.y, 1.0 - uStrip - uHalfTexel.y);
    gl_FragColor = texture2D(uTex, (uStMatrix * vec4(src, 0.0, 1.0)).xy);
}
"""

        /**
         * Spec for a clip's sidecar, or null when it isn't a raw clip or the
         * live slider is 0 (play the file as-is). Reads config: not on the UI thread.
         */
        fun specFor(sidecar: org.json.JSONObject?): Spec? {
            val lens = sidecar?.optJSONObject("lens") ?: return null
            if (!lens.optBoolean("raw", false) || lens.optString("model") != "divide") return null
            val frame = lens.optJSONObject("frame") ?: return null
            val w = frame.optInt("width", 0)
            val h = frame.optInt("height", 0)
            if (w < 2 || h < 2 || w > 8192 || h > 8192) return null
            val aspect = lens.optDouble("aspect", 0.75).toFloat()
            if (!(aspect in 0.25f..1.0f)) return null
            val t = liveStrength().coerceIn(0, 100) / 100f
            if (t <= 0f) return null
            val strip = lens.optInt("strip", 0)
            val stripFrac = if (strip > 0 && strip * 2 < h) strip.toFloat() / h else 0f
            return Spec(0.30f * t, 0.10f * t, aspect, w, h, stripFrac)
        }

        /** Live lens slider (recording.rectifyStrength), 0 when unreadable. */
        fun liveStrength(): Int = try {
            com.overdrive.app.config.UnifiedConfigManager.getRectifyStrength()
        } catch (_: Throwable) {
            0
        }
    }
}
