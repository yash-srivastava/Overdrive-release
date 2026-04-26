package com.overdrive.app.telemetry;

import android.graphics.Bitmap;

/**
 * Double-buffer for overlay bitmap rendering.
 * Background thread writes to the back bitmap, GL thread reads the front bitmap.
 * Thread safety: swapReady is volatile, swap operation uses synchronized block.
 */
class OverlayDoubleBuffer {

    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private volatile boolean swapReady = false;

    /**
     * Creates both front and back bitmaps at the specified dimensions.
     */
    public OverlayDoubleBuffer(int width, int height) {
        frontBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        backBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * Returns the back bitmap for Canvas rendering by the background thread.
     */
    public Bitmap getBackForWriting() {
        return backBitmap;
    }

    /**
     * Signals that the back bitmap has been fully rendered and is ready to swap.
     */
    public void markBackReady() {
        swapReady = true;
    }

    /**
     * If swapReady, atomically swaps front/back references and returns the new front.
     * If not ready, returns null (no new content to upload).
     * 
     * Returning null when no swap occurred allows the GL thread to skip the
     * expensive texImage2D upload — the previously uploaded texture is still valid.
     */
    public synchronized Bitmap swapAndGetFront() {
        if (swapReady) {
            Bitmap temp = frontBitmap;
            frontBitmap = backBitmap;
            backBitmap = temp;
            swapReady = false;
            return frontBitmap;
        }
        return null;
    }

    /**
     * Returns the current front bitmap without swapping.
     * Used when the GL thread needs the bitmap reference but no new content is available.
     */
    public Bitmap getFront() {
        return frontBitmap;
    }

    /**
     * Recycles both bitmaps and sets references to null.
     */
    public void release() {
        if (frontBitmap != null) {
            frontBitmap.recycle();
            frontBitmap = null;
        }
        if (backBitmap != null) {
            backBitmap.recycle();
            backBitmap = null;
        }
    }
}
