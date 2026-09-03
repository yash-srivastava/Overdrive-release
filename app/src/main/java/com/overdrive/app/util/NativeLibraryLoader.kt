package com.overdrive.app.util

import android.util.Log
import java.io.File
import com.overdrive.app.util.ScratchPaths

/**
 * Loads native libraries for daemon processes.
 * 
 * Extracted from CameraDaemon for reuse across daemons.
 */
object NativeLibraryLoader {
    
    private const val TAG = "NativeLibraryLoader"
    
    /**
     * Load system libraries required for camera operations.
     * 
     * @return true if all critical libraries loaded successfully
     */
    fun loadSystemLibraries(): Boolean {
        var success = true
        
        val systemLibs = listOf(
            "nativehelper",
            "cutils",
            "utils",
            "binder",
            "gui",
            "bmmcamera"
        )
        
        for (lib in systemLibs) {
            try {
                System.loadLibrary(lib)
                Log.d(TAG, "Loaded system library: $lib")
            } catch (e: Throwable) {
                Log.w(TAG, "Could not load system library $lib: ${e.message}")
                // Non-critical - continue
            }
        }
        
        return success
    }
    
    /**
     * Load native encoder library.
     * 
     * @return true if native encoder is available
     */
    fun loadNativeEncoder(): Boolean {
        return try {
            System.loadLibrary("nativeencoder")
            Log.i(TAG, "Native encoder loaded")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Native encoder NOT available: ${e.message}")
            false
        }
    }
    
    /**
     * Load native encoder from a specific path.
     * 
     * @param nativeLibDir Directory containing native libraries
     * @return true if native encoder is available
     */
    fun loadNativeEncoderFromPath(nativeLibDir: String): Boolean {
        // Try OpenH264 first
        val openh264Paths = listOf(
            "$nativeLibDir/libopenh264.so",
            ScratchPaths.path("libopenh264.so"),
            "/system/lib64/libopenh264.so",
            "/system/lib/libopenh264.so"
        )
        
        for (path in openh264Paths) {
            if (File(path).exists()) {
                try {
                    System.load(path)
                    Log.i(TAG, "Loaded OpenH264 from: $path")
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to load OpenH264 from $path: ${e.message}")
                }
            }
        }
        
        // Try native encoder
        val encoderPaths = listOf(
            "$nativeLibDir/libnativeencoder.so",
            ScratchPaths.path("libnativeencoder.so")
        )
        
        for (path in encoderPaths) {
            if (File(path).exists()) {
                try {
                    System.load(path)
                    Log.i(TAG, "Loaded native encoder from: $path")
                    return true
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to load native encoder from $path: ${e.message}")
                }
            }
        }
        
        // Fallback to loadLibrary
        return loadNativeEncoder()
    }
    
    /**
     * Check if sing-box binary is available.
     * 
     * @return true if sing-box is available at expected path
     */
    fun isSingBoxAvailable(): Boolean {
        val paths = listOf(
            ScratchPaths.path("sing-box"),
            "/system/bin/sing-box"
        )
        
        for (path in paths) {
            if (File(path).exists()) {
                Log.i(TAG, "Found sing-box at: $path")
                return true
            }
        }
        
        Log.w(TAG, "sing-box binary not found")
        return false
    }
    
    /**
     * Install sing-box binary from jniLibs to /data/local/tmp.
     * Similar to cloudflared installation pattern.
     * 
     * @param nativeLibDir The app's native library directory
     * @return true if installation successful or already installed
     */
    fun installSingBox(nativeLibDir: String): Boolean {
        val destPath = ScratchPaths.path("sing-box")
        val srcPath = "$nativeLibDir/libsingbox.so"
        
        // Check if already installed
        if (File(destPath).exists()) {
            Log.i(TAG, "sing-box already installed at $destPath")
            return true
        }
        
        // Check source exists
        if (!File(srcPath).exists()) {
            Log.e(TAG, "libsingbox.so not found at $srcPath")
            return false
        }
        
        Log.i(TAG, "sing-box source found at $srcPath, needs ADB shell to install")
        return false // Needs ADB shell to copy to /data/local/tmp
    }
}
