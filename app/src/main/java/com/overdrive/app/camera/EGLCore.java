package com.overdrive.app.camera;

import android.opengl.EGL14;
import com.overdrive.app.logging.DaemonLogger;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * EGLCore - Manages EGL display, context, and surfaces for headless OpenGL rendering.
 * 
 * This class provides a wrapper around EGL (Embedded-System Graphics Library) for
 * creating and managing OpenGL ES contexts without a display window. It's designed
 * for the GPU Zero-Copy Pipeline where camera frames are processed entirely in VRAM.
 * 
 * Key features:
 * - Headless OpenGL context (no window required)
 * - EGL_RECORDABLE_ANDROID flag for MediaCodec encoder compatibility
 * - Window surface creation from Android Surface objects
 * - Context switching and buffer swapping
 */
public class EGLCore {
    private static final String TAG = "EGLCore";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig = null;
    
    /**
     * Creates and initializes an EGL context with RECORDABLE flag.
     * 
     * The RECORDABLE flag is critical for MediaCodec encoder compatibility,
     * allowing the encoder to receive frames directly from GPU surfaces.
     * 
     * @throws RuntimeException if EGL initialization fails
     */
    public EGLCore() {
        // Get default display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }
        
        // Initialize EGL
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL14");
        }
        
        logger.debug( "EGL initialized: version " + version[0] + "." + version[1]);
        
        // Choose config with RECORDABLE flag (required for encoder!)
        int[] configAttribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,  // Critical for encoder compatibility
            EGL14.EGL_NONE
        };
        
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0,
                configs, 0, configs.length, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGL config");
        }
        
        if (numConfigs[0] == 0) {
            throw new RuntimeException("No EGL configs found");
        }
        
        eglConfig = configs[0];
        logger.debug( "EGL config chosen with RECORDABLE flag");
        
        // Create OpenGL ES 2.0 context
        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        };
        
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig,
                EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Failed to create EGL context");
        }
        
        logger.debug( "EGL context created successfully");
        
        // Verify EGL state
        checkEglError("EGLCore constructor");
    }
    
    /**
     * Creates a window surface from an Android Surface.
     * 
     * This is used to create an EGL surface from MediaCodec's input surface,
     * allowing GPU to render directly to the encoder.
     * 
     * @param surface Android Surface (typically from MediaCodec.createInputSurface())
     * @return EGLSurface that can be used for rendering
     */
    public EGLSurface createWindowSurface(Surface surface) {
        if (surface == null) {
            throw new IllegalArgumentException("Surface cannot be null");
        }
        
        int[] surfaceAttribs = {
            EGL14.EGL_NONE
        };
        
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, surfaceAttribs, 0);
        
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Failed to create window surface");
        }
        
        checkEglError("createWindowSurface");
        logger.debug( "Window surface created");
        
        return eglSurface;
    }
    
    /**
     * Creates an offscreen pbuffer surface for headless rendering.
     * 
     * This is useful when you need an OpenGL context but don't have a window
     * or encoder surface yet. The pbuffer acts as a dummy surface.
     * 
     * @param width Width of the pbuffer (typically 1)
     * @param height Height of the pbuffer (typically 1)
     * @return EGLSurface for the pbuffer
     */
    public EGLSurface createPbufferSurface(int width, int height) {
        int[] surfaceAttribs = {
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        };
        
        EGLSurface eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig, surfaceAttribs, 0);
        
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Failed to create pbuffer surface");
        }
        
        checkEglError("createPbufferSurface");
        logger.debug( String.format("Pbuffer surface created: %dx%d", width, height));
        
        return eglSurface;
    }
    
    /**
     * Makes the specified surface current for rendering.
     * 
     * All subsequent OpenGL calls will render to this surface until
     * another surface is made current.
     * 
     * @param surface EGLSurface to make current
     */
    public void makeCurrent(EGLSurface surface) {
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalArgumentException("Invalid surface");
        }
        
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw new RuntimeException("Failed to make surface current");
        }
        
        checkEglError("makeCurrent");
    }
    
    /**
     * Makes no surface current (unbinds current surface).
     * Useful for cleanup or switching contexts.
     */
    public void makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, 
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
            logger.warn( "Failed to make nothing current");
        }
    }
    
    /**
     * Swaps buffers for the specified surface.
     * 
     * This presents the rendered content to the surface (e.g., encoder).
     * For encoder surfaces, this triggers frame submission to MediaCodec.
     * 
     * @param surface EGLSurface to swap buffers for
     */
    public void swapBuffers(EGLSurface surface) {
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalArgumentException("Invalid surface");
        }
        
        if (!EGL14.eglSwapBuffers(eglDisplay, surface)) {
            int error = EGL14.eglGetError();
            String errorMsg = String.format("swapBuffers: EGL error 0x%x", error);
            logger.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        checkEglError("swapBuffers");
    }
    
    /**
     * Set the presentation timestamp on an EGL surface and swap buffers.
     * This stamps the exact nanosecond timestamp onto the encoder's input surface,
     * ensuring the MediaCodec produces frames with accurate, monotonic PTS values
     * derived from the physical camera sensor — not from the jittery system clock.
     *
     * @param surface The EGL surface (encoder input surface)
     * @param timestampNs Presentation time in nanoseconds (from SurfaceTexture.getTimestamp())
     */
    public void swapBuffersWithTimestamp(EGLSurface surface, long timestampNs) {
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalArgumentException("Invalid surface");
        }
        
        // Stamp the exact camera sensor timestamp onto the encoder surface
        EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, timestampNs);
        
        if (!EGL14.eglSwapBuffers(eglDisplay, surface)) {
            int error = EGL14.eglGetError();
            String errorMsg = String.format("swapBuffers: EGL error 0x%x", error);
            logger.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        checkEglError("swapBuffersWithTimestamp");
    }
    
    /**
     * Destroys the specified surface.
     * 
     * @param surface EGLSurface to destroy
     */
    public void destroySurface(EGLSurface surface) {
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, surface);
            // Don't throw on EGL_BAD_SURFACE (0x3008) - surface may already be destroyed
            int error = EGL14.eglGetError();
            if (error != EGL14.EGL_SUCCESS && error != 0x3008) {
                logger.warn("destroySurface: EGL error 0x" + Integer.toHexString(error));
            }
        }
    }
    
    /**
     * Releases all EGL resources.
     * 
     * This should be called when the EGL context is no longer needed.
     * After calling this, the EGLCore instance cannot be reused.
     */
    public void release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            // Unbind current context
            EGL14.eglMakeCurrent(eglDisplay, 
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            
            // Destroy context
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            
            // Terminate display
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        
        logger.debug( "EGL resources released");
    }
    
    /**
     * Checks for EGL errors and throws if any are found.
     * 
     * @param operation Description of the operation being checked
     */
    private void checkEglError(String operation) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            String errorMsg = String.format("%s: EGL error 0x%x", operation, error);
            logger.error( errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }
    
    /**
     * Gets the current EGL display.
     * 
     * @return EGLDisplay instance
     */
    public EGLDisplay getDisplay() {
        return eglDisplay;
    }
    
    /**
     * Gets the current EGL context.
     * 
     * @return EGLContext instance
     */
    public EGLContext getContext() {
        return eglContext;
    }
    
    /**
     * Gets the current EGL config.
     * 
     * @return EGLConfig instance
     */
    public EGLConfig getConfig() {
        return eglConfig;
    }
}
