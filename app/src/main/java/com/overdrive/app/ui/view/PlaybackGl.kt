package com.overdrive.app.ui.view

import android.opengl.GLES20

/** Shared playback shader sources and GL setup; callers own the current EGL context. */
internal object PlaybackGl {
    private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = aTexCoord;
}
"""

    // GpuMosaicRecorder's rectifyTile (uK = k1, k2; uAspect = tile h/w).
    private const val RECTIFY_TILE = """vec2 rectifyTile(vec2 t) {
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
"""

    val dewarpShader = dewarpFragment(withAlpha = false)
    val timelineDewarpShader = dewarpFragment(withAlpha = true)

    // Timeline blends frames with premultiplied opacity; standalone playback does not.
    // The dewarp is point-symmetric about each tile centre, so working in
    // texture space (y up) matches the recorder's image-space result.
    private fun dewarpFragment(withAlpha: Boolean): String {
        val alphaUniform = if (withAlpha) "uniform float uAlpha;\n" else ""
        val opacity = if (withAlpha) " * uAlpha" else ""
        val stripComment = if (withAlpha) "" else
            "    // The telemetry strip (top of the frame) is shown as recorded, never sampled for the picture.\n"
        return """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uStMatrix;
uniform vec2 uK;
uniform float uAspect;
uniform vec2 uHalfTexel;
uniform float uStrip;
${alphaUniform}varying vec2 vTex;
${RECTIFY_TILE}void main() {
${stripComment}    if (vTex.y > 1.0 - uStrip) {
        gl_FragColor = texture2D(uTex, (uStMatrix * vec4(vTex, 0.0, 1.0)).xy)${opacity};
        return;
    }
    vec2 tile = step(vec2(0.5), vTex) * 0.5;
    vec2 cam = rectifyTile((vTex - tile) * 2.0);
    vec2 src = clamp(tile + cam * 0.5, tile + uHalfTexel, tile + 0.5 - uHalfTexel);
    src.y = min(src.y, 1.0 - uStrip - uHalfTexel.y);
    gl_FragColor = texture2D(uTex, (uStMatrix * vec4(src, 0.0, 1.0)).xy)${opacity};
}
"""
    }

    /**
     * Dashcam view in one pass, as the recorder composes it: top [uSplit] = windshield (uDvr, cropped by
     * uCropY, never dewarped), bottom in thirds = grid tiles at uCells (picture-space origins), dewarped,
     * telemetry strip rows on top. Works in picture space (y down); each input has its own st matrix.
     */
    val dashcamViewShader = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex;
uniform samplerExternalOES uDvr;
uniform mat4 uStMatrix;
uniform mat4 uDvrMatrix;
uniform vec2 uK;
uniform float uAspect;
uniform vec2 uHalfTexel;
uniform float uStrip;
uniform float uSplit;
uniform float uCropY;
uniform vec2 uCells[3];
uniform float uAlpha;
varying vec2 vTex;
${RECTIFY_TILE}vec4 gridAt(vec2 p) {
    return texture2D(uTex, (uStMatrix * vec4(p.x, 1.0 - p.y, 0.0, 1.0)).xy);
}
void main() {
    vec2 p = vec2(vTex.x, 1.0 - vTex.y);
    vec4 c;
    if (p.y < uStrip) {
        c = gridAt(p);
    } else if (p.y < uSplit) {
        float ly = p.y / uSplit;
        vec2 d = vec2(p.x, uCropY + ly * (1.0 - 2.0 * uCropY));
        c = texture2D(uDvr, (uDvrMatrix * vec4(d.x, 1.0 - d.y, 0.0, 1.0)).xy);
    } else {
        float by = (p.y - uSplit) / (1.0 - uSplit);
        float bx = p.x * 3.0;
        float cell = clamp(floor(bx), 0.0, 2.0);
        float lx = bx - cell;
        vec2 o = cell < 0.5 ? uCells[0] : (cell < 1.5 ? uCells[1] : uCells[2]);
        vec2 t = rectifyTile(vec2(lx, by));
        vec2 src = clamp(o + t * 0.5, o + uHalfTexel, o + 0.5 - uHalfTexel);
        if (uStrip > 0.0 && o.y < 0.25) src.y = max(src.y, uStrip + uHalfTexel.y);
        c = gridAt(src);
    }
    gl_FragColor = c * uAlpha;
}
"""

    fun buildProgram(fragment: String, compileFailure: String, linkFailure: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER, compileFailure)
        val fs = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragment, compileFailure)
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
            throw IllegalStateException("$linkFailure: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, source: String, failure: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("$failure: $log")
        }
        return shader
    }

    fun setLinearClamp(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
}
