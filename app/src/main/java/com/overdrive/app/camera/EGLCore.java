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
    private final int glesVersion;       // requested
    private final boolean recordable;
    // The GLES version we actually got (may differ from glesVersion if the
    // driver rejected the requested GLES3+RECORDABLE combo and we fell back
    // to GLES2 inside the constructor). AI-lane PBO code reads this so it
    // can degrade gracefully on a fallback device.
    private final java.util.concurrent.atomic.AtomicInteger actualGlesVersionRef =
        new java.util.concurrent.atomic.AtomicInteger(0);
    // True if we initialized eglDisplay ourselves; false if we inherit it from
    // a parent (share group). Determines whether release() should call
    // eglTerminate — terminating a shared display would break the parent.
    private final boolean ownsDisplay;

    /**
     * Default ctor — GLES 3 + RECORDABLE. The encoder context uses this.
     */
    public EGLCore() {
        this(3, true, null);
    }

    /**
     * Create a context that shares textures, FBOs, shader programs, and PBOs
     * with {@code parent}. Used by the AI-lane GL thread so it can sample the
     * camera OES texture allocated by the encoder thread without copying.
     *
     * The new context lives on the same {@code EGLDisplay} as {@code parent}
     * (we do NOT call {@code eglInitialize} again — calling it twice on the
     * same display is fine but {@code release()} of the child must not call
     * {@code eglTerminate}, which would tear down the parent's display).
     *
     * @param parent encoder/main EGLCore. Must outlive the returned core.
     * @return a fresh EGLCore in the same share group, GLES 3, non-recordable
     *         (AI lane never feeds MediaCodec).
     */
    public static EGLCore createShared(EGLCore parent) {
        return createShared(parent, false);
    }

    /**
     * As {@link #createShared(EGLCore)} but with explicit control over
     * the {@code EGL_RECORDABLE_ANDROID} bit. Pass {@code true} when the
     * shared context will be used to drive a MediaCodec encoder Surface
     * (the OEM dashcam pipeline does this when sharing context with
     * pano's camera EGL); pass {@code false} for non-encoder consumers
     * (AI-lane PBO/fence-sync, etc.).
     */
    public static EGLCore createShared(EGLCore parent, boolean recordable) {
        if (parent == null) throw new IllegalArgumentException("parent EGLCore is null");
        if (parent.eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("parent EGL context is not initialized");
        }
        // Use the parent's ACTUAL GLES version, not the requested one. If
        // the parent's constructor fell back from GLES3 to GLES2 because
        // the driver rejected the GLES3+RECORDABLE config combo, the
        // requested-version field still reads 3 — and asking the child
        // for GLES3 against a GLES2 parent produces a cross-version share
        // group that most Adreno drivers refuse with EGL_NO_CONTEXT.
        return new EGLCore(parent.getActualGlesVersion(), recordable, parent);
    }

    /**
     * @param glesVersion 2 or 3. Tier 2 PBO/fence-sync requires 3.
     * @param recordable adds {@code EGL_RECORDABLE_ANDROID} to the config.
     *                   Required for the MediaCodec encoder surface; harmful
     *                   for other paths because it constrains the chosen
     *                   config (some Adreno builds drop GLES3 configs when
     *                   RECORDABLE is also requested).
     * @param parent     null = create a fresh display + ungrouped context.
     *                   non-null = inherit display + share textures/programs.
     */
    /**
     * Constructor-failure cleanup for a display initialized by this instance.
     *
     * A Java constructor that throws never publishes the partially-created
     * object, so callers cannot invoke release(). Shared child contexts must
     * not terminate the parent's display.
     */
    private void terminateOwnedDisplayAfterConstructorFailure() {
        if (ownsDisplay && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglTerminate(eglDisplay);
            } catch (Throwable t) {
                logger.warn("EGL display cleanup after constructor failure: " + t.getMessage());
            } finally {
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        }
    }

    private EGLCore(int glesVersion, boolean recordable, EGLCore parent) {
        if (glesVersion != 2 && glesVersion != 3) {
            throw new IllegalArgumentException("glesVersion must be 2 or 3, got " + glesVersion);
        }
        this.glesVersion = glesVersion;
        this.recordable = recordable;

        if (parent != null) {
            // Inherit the parent's display so the share group is valid. We do
            // NOT take ownership; release() must not eglTerminate.
            eglDisplay = parent.eglDisplay;
            ownsDisplay = false;
        } else {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("Unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw new RuntimeException("Unable to initialize EGL14");
            }
            logger.debug("EGL initialized: version " + version[0] + "." + version[1]);
            ownsDisplay = true;
        }

        // Build the config attrib list. RECORDABLE only on the encoder context
        // — adding it to the AI-lane shared context would constrain config
        // selection for no benefit, since the AI lane never feeds MediaCodec.
        //
        // Driver fallback: a few Adreno builds reject "GLES3 + RECORDABLE"
        // configs even though GLES 3.2 is otherwise supported. If chooseConfig
        // fails for the requested version we fall back to GLES 2 — Tier 2's
        // PBO/fence-sync calls are GLES 3 entry points, so the AI-lane code
        // gates on the GLES version at runtime via this field.
        int actualGles = glesVersion;
        eglConfig = chooseConfigOrFallback(eglDisplay, actualGles, recordable);
        if (eglConfig == null && actualGles == 3) {
            logger.warn("GLES3 EGL config unavailable (recordable=" + recordable
                    + "); falling back to GLES2");
            actualGles = 2;
            eglConfig = chooseConfigOrFallback(eglDisplay, actualGles, recordable);
        }
        if (eglConfig == null) {
            terminateOwnedDisplayAfterConstructorFailure();
            throw new RuntimeException("No EGL configs found (gles=" + glesVersion
                + ", recordable=" + recordable + ")");
        }
        logger.debug("EGL config chosen (gles=" + actualGles + ", recordable=" + recordable + ")");

        int[] contextAttribs = {
            EGL14.EGL_CONTEXT_CLIENT_VERSION, actualGles,
            EGL14.EGL_NONE
        };

        EGLContext shareWith = (parent != null) ? parent.eglContext : EGL14.EGL_NO_CONTEXT;
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, shareWith, contextAttribs, 0);

        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            // FIX (EGL-leak audit): capture eglGetError() IMMEDIATELY — the
            // fleet crash loop ("eglCreateContext failed" x77 until reboot)
            // never recorded the symbolic error, so context-table exhaustion
            // (EGL_BAD_ALLOC) could not be proven from logs.
            int eglError = EGL14.eglGetError();
            logger.error("eglCreateContext FAILED: error=" + eglErrorString(eglError)
                + ", requestedGles=" + glesVersion + ", actualGles=" + actualGles
                + ", recordable=" + recordable + ", shared=" + (parent != null));

            // EGL_BAD_MATCH/BAD_CONFIG: some Adreno builds accept a GLES3
            // config at chooseConfig time but refuse the GLES3 context
            // against it. Retry ONCE with a GLES2 config+context. Root
            // (non-shared) contexts only — a child must match its parent's
            // actual version or the share group is invalid.
            if (actualGles == 3 && parent == null
                    && (eglError == EGL14.EGL_BAD_MATCH || eglError == EGL14.EGL_BAD_CONFIG)) {
                logger.warn("Retrying EGL context creation with GLES2 "
                    + "(BAD_MATCH/BAD_CONFIG fallback)");
                EGLConfig gles2Config = chooseConfigOrFallback(eglDisplay, 2, recordable);
                if (gles2Config != null) {
                    int[] gles2Attribs = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE
                    };
                    eglContext = EGL14.eglCreateContext(eglDisplay, gles2Config,
                        shareWith, gles2Attribs, 0);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        eglConfig = gles2Config;
                        actualGles = 2;
                        logger.warn("GLES2 fallback context created");
                    } else {
                        eglError = EGL14.eglGetError();
                        logger.error("GLES2 fallback context creation also failed: "
                            + eglErrorString(eglError));
                    }
                }
            }

            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                recordContextCreateFailure(eglError);
                terminateOwnedDisplayAfterConstructorFailure();
                throw new RuntimeException("Failed to create EGL context (gles=" + actualGles
                    + ", recordable=" + recordable + ", shared=" + (parent != null)
                    + ", error=" + eglErrorString(eglError) + ")");
            }
        }
        // Context creation succeeded — reset the consecutive-failure breaker.
        CONSECUTIVE_CONTEXT_CREATE_FAILURES.set(0);
        // Mutate field so getGlesVersion() reports what we actually got.
        actualGlesVersionRef.set(actualGles);
        logger.debug("EGL context created (gles=" + actualGles + ", shared=" + (parent != null) + ")");

        checkEglError("EGLCore constructor");
    }

    // EGL extension token — not in the public EGL14 constants, but defined by
    // the KHR_create_context extension and accepted by every Adreno driver
    // since Adreno 3xx. The numeric value is part of the EGL spec.
    private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

    // EGL 1.4 core error not exposed by the EGL14 Java constants.
    private static final int EGL_CONTEXT_LOST = 0x300E;

    // ==================== EGL FAILURE CIRCUIT BREAKER ====================
    // FIX (EGL-leak audit): leaked EGL/KGSL contexts are PER-PROCESS kernel
    // resources. When context creation starts failing, no amount of in-process
    // retrying can recover — the daemon's global uncaught-exception handler
    // ("NEVER let the daemon die") swallows the GL-thread crash, so without an
    // explicit escalation the daemon retried eglCreateContext forever (77
    // recorded failures on one vehicle) until a full reboot. A process restart
    // frees every context the kernel attributes to us and is a supported
    // recovery path here (the shell watchdog wrapper relaunches the daemon;
    // the GL stall watchdog already exits the same way).
    //
    // ONLY the exhaustion/state-loss class (EGL_BAD_ALLOC, EGL_CONTEXT_LOST)
    // escalates to a process restart: those are exactly the errors a fresh
    // process fixes (leaked per-process KGSL contexts / lost GPU state).
    // Deterministic configuration errors (EGL_BAD_CONFIG, EGL_BAD_MATCH that
    // survived the GLES2 fallback, EGL_BAD_ATTRIBUTE, ...) reproduce
    // identically in a fresh process — and since this counter is per-process
    // and resets on relaunch, restarting on them would loop forever
    // (restart → same failure x N → restart ...). For those we only fail
    // upward: the pipeline rollback releases partial state and the daemon's
    // bounded-backoff retry ladder (5s/30s/120s/300s) owns recovery, leaving
    // the process — and the rest of the daemon's functions — alive.
    private static final java.util.concurrent.atomic.AtomicInteger
            CONSECUTIVE_CONTEXT_CREATE_FAILURES =
                new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int EXHAUSTION_FAILURE_RESTART_THRESHOLD = 2;

    private static void recordContextCreateFailure(int eglError) {
        boolean exhaustionClass =
            (eglError == EGL14.EGL_BAD_ALLOC || eglError == EGL_CONTEXT_LOST);
        if (!exhaustionClass) {
            logger.error("EGL context-create failure: " + eglErrorString(eglError)
                + " (deterministic/config class — NOT counted toward process-restart"
                + " threshold; a fresh process would fail identically)");
            return;
        }
        int failures = CONSECUTIVE_CONTEXT_CREATE_FAILURES.incrementAndGet();
        logger.error("Consecutive exhaustion-class EGL context-create failures: "
            + failures + "/" + EXHAUSTION_FAILURE_RESTART_THRESHOLD
            + " (last=" + eglErrorString(eglError) + ")");
        if (failures >= EXHAUSTION_FAILURE_RESTART_THRESHOLD) {
            logger.error("EGL context creation failed " + failures
                + " consecutive times — requesting trip-safe daemon process restart "
                + "(leaked KGSL contexts are per-process; only a process exit "
                + "reclaims them without a vehicle reboot)");
            try {
                com.overdrive.app.daemon.CameraDaemon.requestProcessRestartPreservingTrip(
                    "EGL context exhaustion: " + failures
                        + " consecutive eglCreateContext failures, last error "
                        + eglErrorString(eglError));
            } catch (Throwable t) {
                // Never let breaker plumbing mask the original failure
                // (also keeps EGLCore usable in isolation/tests).
                logger.error("Failed to request daemon restart: " + t.getMessage());
            }
        }
    }

    /**
     * Human-readable name for an EGL error code. Public so peer EGL owners
     * (GpuDownscaler's private context, AiLaneGl, etc.) log the same symbols.
     */
    public static String eglErrorString(int error) {
        switch (error) {
            case EGL14.EGL_SUCCESS:             return "EGL_SUCCESS";
            case EGL14.EGL_NOT_INITIALIZED:     return "EGL_NOT_INITIALIZED";
            case EGL14.EGL_BAD_ACCESS:          return "EGL_BAD_ACCESS";
            case EGL14.EGL_BAD_ALLOC:           return "EGL_BAD_ALLOC";
            case EGL14.EGL_BAD_ATTRIBUTE:       return "EGL_BAD_ATTRIBUTE";
            case EGL14.EGL_BAD_CONFIG:          return "EGL_BAD_CONFIG";
            case EGL14.EGL_BAD_CONTEXT:         return "EGL_BAD_CONTEXT";
            case EGL14.EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
            case EGL14.EGL_BAD_DISPLAY:         return "EGL_BAD_DISPLAY";
            case EGL14.EGL_BAD_MATCH:           return "EGL_BAD_MATCH";
            case EGL14.EGL_BAD_NATIVE_PIXMAP:   return "EGL_BAD_NATIVE_PIXMAP";
            case EGL14.EGL_BAD_NATIVE_WINDOW:   return "EGL_BAD_NATIVE_WINDOW";
            case EGL14.EGL_BAD_PARAMETER:       return "EGL_BAD_PARAMETER";
            case EGL14.EGL_BAD_SURFACE:         return "EGL_BAD_SURFACE";
            case EGL_CONTEXT_LOST:              return "EGL_CONTEXT_LOST";
            default:
                return String.format("EGL error 0x%x", error);
        }
    }

    /** @return the actual GLES version this context was created with (2 or 3). */
    public int getActualGlesVersion() {
        int v = actualGlesVersionRef.get();
        return v != 0 ? v : glesVersion;
    }

    private static EGLConfig chooseConfigOrFallback(EGLDisplay display,
                                                    int glesVersion,
                                                    boolean recordable) {
        int rType = (glesVersion == 3) ? EGL_OPENGL_ES3_BIT_KHR : EGL14.EGL_OPENGL_ES2_BIT;
        java.util.List<Integer> attribs = new java.util.ArrayList<>(20);
        attribs.add(EGL14.EGL_RED_SIZE);   attribs.add(8);
        attribs.add(EGL14.EGL_GREEN_SIZE); attribs.add(8);
        attribs.add(EGL14.EGL_BLUE_SIZE);  attribs.add(8);
        attribs.add(EGL14.EGL_ALPHA_SIZE); attribs.add(8);
        attribs.add(EGL14.EGL_RENDERABLE_TYPE); attribs.add(rType);
        if (recordable) {
            attribs.add(EGLExt.EGL_RECORDABLE_ANDROID); attribs.add(1);
        }
        attribs.add(EGL14.EGL_NONE);
        int[] configAttribs = new int[attribs.size()];
        for (int i = 0; i < attribs.size(); i++) configAttribs[i] = attribs.get(i);

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, configAttribs, 0,
                configs, 0, configs.length, numConfigs, 0)) {
            return null;
        }
        return numConfigs[0] > 0 ? configs[0] : null;
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
            // Tolerate EGL_BAD_SURFACE (already destroyed) and
            // EGL_BAD_DISPLAY (parent terminated the shared display
            // before this child's release reached destroySurface).
            int error = EGL14.eglGetError();
            if (error != EGL14.EGL_SUCCESS
                    && error != EGL14.EGL_BAD_SURFACE
                    && error != EGL14.EGL_BAD_DISPLAY
                    && error != EGL14.EGL_NOT_INITIALIZED) {
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
            // Unbind current context. When this is a shared (child) core
            // whose parent already eglTerminate'd the display, this call
            // returns EGL_BAD_DISPLAY or EGL_NOT_INITIALIZED. Both are
            // expected when the parent went first — silence them, log
            // anything else.
            EGL14.eglMakeCurrent(eglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            int err = EGL14.eglGetError();
            if (err != EGL14.EGL_SUCCESS
                    && err != EGL14.EGL_BAD_DISPLAY
                    && err != EGL14.EGL_NOT_INITIALIZED) {
                logger.warn(String.format("release: eglMakeCurrent error 0x%x", err));
            }

            // Destroy our own context. The display itself is only torn down by
            // the owner — child cores in the same share group must NOT call
            // eglTerminate or the parent's textures/programs disappear too.
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                int err2 = EGL14.eglGetError();
                if (err2 != EGL14.EGL_SUCCESS
                        && err2 != EGL14.EGL_BAD_DISPLAY
                        && err2 != EGL14.EGL_NOT_INITIALIZED) {
                    logger.warn(String.format("release: eglDestroyContext error 0x%x", err2));
                }
                eglContext = EGL14.EGL_NO_CONTEXT;
            }

            if (ownsDisplay) {
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        logger.debug("EGL resources released" + (ownsDisplay ? "" : " (shared display)"));
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
