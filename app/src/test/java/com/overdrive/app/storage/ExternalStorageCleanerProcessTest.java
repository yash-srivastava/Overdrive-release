package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public class ExternalStorageCleanerProcessTest {

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void completedProcessReturnsExitCodeWithoutDestroying() {
        FakeProcess process = FakeProcess.completed(7);

        int result = ExternalStorageCleaner.waitForBounded(process, 100L);

        assertEquals(7, result);
        assertFalse(process.destroyed);
    }

    @Test
    public void timedOutProcessIsDestroyed() {
        FakeProcess process = FakeProcess.running();

        int result = ExternalStorageCleaner.waitForBounded(process, 100L);

        assertEquals(-1, result);
        assertTrue(process.destroyed);
    }

    @Test
    public void interruptedWaitDestroysProcessAndPreservesInterrupt() {
        FakeProcess process = FakeProcess.interrupted();

        int result = ExternalStorageCleaner.waitForBounded(process, 100L);

        assertEquals(-1, result);
        assertTrue(process.destroyed);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private static final class FakeProcess extends Process {
        private final int exitCode;
        private final boolean complete;
        private final boolean interruptOnWait;
        private boolean destroyed;

        private FakeProcess(int exitCode, boolean complete, boolean interruptOnWait) {
            this.exitCode = exitCode;
            this.complete = complete;
            this.interruptOnWait = interruptOnWait;
        }

        static FakeProcess completed(int exitCode) {
            return new FakeProcess(exitCode, true, false);
        }

        static FakeProcess running() {
            return new FakeProcess(0, false, false);
        }

        static FakeProcess interrupted() {
            return new FakeProcess(0, false, true);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptOnWait) throw new InterruptedException("test interrupt");
            return complete || destroyed;
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (interruptOnWait) throw new InterruptedException("test interrupt");
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (!complete && !destroyed) throw new IllegalThreadStateException("still running");
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }
}