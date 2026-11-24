package com.overdrive.app.surveillance;

/**
 * A camera Image lent to the CPU recording thread. The camera thread keeps
 * ownership and alone calls {@link android.media.Image#close()} (ImageReader
 * isn't safe to release from another thread while it acquires). While
 * {@link #inUse()} it parks the image instead of closing it.
 */
public final class CpuFrameSource {

    final android.media.Image image;
    private final java.util.concurrent.atomic.AtomicInteger readers =
        new java.util.concurrent.atomic.AtomicInteger();

    public CpuFrameSource(android.media.Image image) {
        this.image = image;
    }

    /** Camera thread, per frame handed to the recording thread (or the Custom Layouts road model). */
    public void retain() {
        readers.incrementAndGet();
    }

    /** Reader thread, once per {@link #retain()}, when that frame no longer reads the image. */
    public void release() {
        readers.decrementAndGet();
    }

    /** True while a queued or running recording frame may still read the image. */
    public boolean inUse() {
        return readers.get() > 0;
    }

    public android.media.Image image() {
        return image;
    }
}
