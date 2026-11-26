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

    val dewarpShader = dewarpFragment(withAlpha = false)
    val timelineDewarpShader = dewarpFragment(withAlpha = true)

    // Timeline blends frames with premultiplied opacity; standalone playback does not.
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
