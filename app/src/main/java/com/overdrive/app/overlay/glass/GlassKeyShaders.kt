package com.overdrive.app.overlay.glass

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** Per-widget key shaders: local matrices are mutable and must not be shared between views. */
internal class GlassKeyShaders {
    private val m = Matrix()
    private var shaderNight: Boolean? = null
    lateinit var keyBody: LinearGradient
        private set
    lateinit var rimSh: LinearGradient
        private set
    lateinit var shadowSh: RadialGradient
        private set
    lateinit var glowAccent: RadialGradient
        private set
    lateinit var glowWarn: RadialGradient
        private set

    fun invalidate() { shaderNight = null }

    fun ensure(t: GlassTheme) {
        if (shaderNight == t.night) return
        shaderNight = t.night
        val night = t.night
        val white = 0xFFFFFFFF.toInt()
        keyBody = LinearGradient(0f, 0f, 0.6f, 1f,
            if (night) intArrayOf(GlassColor.alpha(white, 0.13f), GlassColor.alpha(white, 0.04f), 0x40000000) else intArrayOf(white, 0xFFF1F5F2.toInt(), 0xFFD9E1DC.toInt()),
            floatArrayOf(0f, if (night) 0.45f else 0.5f, 1f), Shader.TileMode.CLAMP)
        rimSh = LinearGradient(0f, 0f, 1f, 1f,
            if (night) intArrayOf(GlassColor.alpha(white, 0.55f), GlassColor.alpha(white, 0.1f), 0x80000000.toInt()) else intArrayOf(white, 0x1A0B281E, 0x4D0B281E),
            floatArrayOf(0f, if (night) 0.4f else 0.5f, 1f), Shader.TileMode.CLAMP)
        shadowSh = RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.shadow, t.shadowA), GlassColor.clear(t.shadow), Shader.TileMode.CLAMP)
        fun glow(c: Int) = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(c, 0.8f), GlassColor.alpha(c, 0.3f), GlassColor.clear(c)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glowAccent = glow(t.glowAccent); glowWarn = glow(t.glowWarn)
    }

    fun mapToBounds(sh: Shader, box: RectF) {
        m.setScale(box.width(), box.height()); m.postTranslate(box.left, box.top)
        sh.setLocalMatrix(m)
    }
}
