package com.overdrive.app.camera;

import android.hardware.HardwareBuffer;

/**
 * JNI bridge to bind an {@link android.hardware.HardwareBuffer} to a
 * {@code GL_TEXTURE_EXTERNAL_OES} via {@code EGLImageKHR}. Required for the
 * ImageReader-based zero-copy camera path that bypasses SurfaceFlinger
 * throttling on the SurfaceTexture consumer (see CAMERA_FPS_INVESTIGATION.md).
 *
 * Caller MUST hold a current EGL context on the calling thread before
 * invoking {@link #bindHardwareBufferToTexture}. The bind targets
 * {@code GL_TEXTURE_EXTERNAL_OES} on the supplied texture ID.
 *
 * Native impl is in cpp/camera/HardwareBufferTextureBinder.cpp, statically
 * linked into the existing {@code libsurveillance.so}.
 */
public final class HardwareBufferTextureBinder {

    private HardwareBufferTextureBinder() {}

    /**
     * Probe the EGL/GL extensions required for AHardwareBuffer→EGLImage
     * binding on the current display. Result is a human-readable string
     * suitable for one-shot logging at startup. Safe to call from any
     * thread that has a current EGL display.
     */
    public static native String probeExtensionsNative();

    /**
     * Wrap {@code hwBuffer} as an {@code EGLImageKHR} and bind it to
     * {@code textureId} as a {@code GL_TEXTURE_EXTERNAL_OES} target.
     * Returns true on success. The texture retains an internal reference
     * to the gralloc buffer; the EGLImage wrapper itself is destroyed
     * before this method returns.
     *
     * @param hwBuffer the HardwareBuffer obtained from
     *                 {@link android.media.Image#getHardwareBuffer()}
     * @param textureId an OpenGL ES texture ID created with
     *                  {@code GL_TEXTURE_EXTERNAL_OES} target
     * @return true if the EGL/GL bind succeeded
     */
    public static native boolean bindHardwareBufferToTextureNative(
        HardwareBuffer hwBuffer, int textureId);

    /**
     * Re-targets {@code textureId} to a black 1x1 buffer so it no longer imports
     * a camera buffer. Caller's thread holds the context; false = still bound as before.
     */
    public static native boolean bindPlaceholderNative(int textureId);
}
