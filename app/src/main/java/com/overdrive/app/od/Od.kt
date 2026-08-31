package com.overdrive.app.od

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import com.overdrive.app.util.ScratchPaths

/**
 * Binding to libod.so. Call [authorize] once with a Context, then [resolve] to
 * obtain the coefficient set (in[11] → out[20]; the 2 extra inputs are the
 * rear-tap rRoll/rPitch, the 4 extra outputs the rear-tap rearRc/rearRs/rearPitch/pad).
 */
object Od {

    @Volatile private var loaded = false
    @Volatile private var ready = false

    init {
        loaded = try { System.loadLibrary("od"); true } catch (_: Throwable) { false }
    }

    /** Explicit path load for the app_process daemon (UID 2000), where
     *  System.loadLibrary can't resolve by name — mirrors NativeMotion.tryLoadLibrary.
     *  CameraDaemon calls this with its nativeLibDir. Idempotent. */
    @JvmStatic
    fun tryLoadLibrary(nativeLibDir: String): Boolean {
        if (loaded) return true
        loaded = try {
            System.load("$nativeLibDir/libod.so"); true
        } catch (_: Throwable) {
            try { System.load(ScratchPaths.path("libod.so")); true } catch (_: Throwable) { false }
        }
        return loaded
    }

    @JvmStatic
    fun authorize(context: Context): Boolean {
        if (!loaded) return false
        if (ready) return true
        // Development builds skip the host check. This branch is compiled out of
        // release (BuildConfig.DEBUG is a release-time false constant → dead code
        // removed by R8), so the shipped binary has no bypass path.
        if (com.overdrive.app.BuildConfig.DEBUG) { ready = true; return true }
        try {
            val d = hostKey(context)
            if (d == null) {
                // Diagnostic: signature unreadable → coefficients will zero-fill → BLACK
                // BS card. (Was silent; this is the one line that makes "loaded but black"
                // triageable from the log.)
                android.util.Log.w("Od", "authorize: hostKey null (no readable signing cert) → od NOT ready → BS will render black")
                return false
            }
            var hi = 0L; var lo = 0L
            for (i in 0 until 8) hi = (hi shl 8) or (d[i].toLong() and 0xFF)
            for (i in 8 until 16) lo = (lo shl 8) or (d[i].toLong() and 0xFF)
            ready = nativeAuthorize(hi, lo) == 1
            android.util.Log.i("Od", "authorize: nativeAuthorize → ready=" + ready)
        } catch (t: Throwable) {
            ready = false
            android.util.Log.w("Od", "authorize: exception → od NOT ready: " + t.message)
        }
        return ready
    }

    @JvmStatic
    fun resolve(input: FloatArray, output: FloatArray) {
        if (!loaded || !ready) { java.util.Arrays.fill(output, 0f); return }
        try { nativeResolve(input, output) } catch (_: Throwable) { java.util.Arrays.fill(output, 0f) }
    }

    val isReady: Boolean get() = loaded && ready

    private fun hostKey(context: Context): ByteArray? {
        return try {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val pi = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = pi.signatures ?: return null
            if (sigs.isEmpty()) return null
            MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
        } catch (_: Throwable) { null }
    }

    private external fun nativeAuthorize(a: Long, b: Long): Int
    private external fun nativeResolve(input: FloatArray, output: FloatArray): Int
}
