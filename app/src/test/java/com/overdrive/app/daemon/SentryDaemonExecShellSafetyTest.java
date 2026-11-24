package com.overdrive.app.daemon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

/** Behavioral tests for bounded shell execution in SentryDaemon. */
public class SentryDaemonExecShellSafetyTest {

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void completedShellCommandReturnsTrimmedStdout() {
        FakeProcess process = FakeProcess.completed("ServiceRecord{ok}\n  ");

        String result = SentryDaemon.execShell(cmd -> process, "dumpsys activity services", 1_000L);

        assertEquals("ServiceRecord{ok}", result);
        assertFalse(process.destroyed);
        assertFalse(SentryDaemon.isExecTimeout(result));
    }

    @Test
    public void timedOutShellCommandIsForciblyDestroyed() {
        FakeProcess process = FakeProcess.hung();

        long startMs = System.currentTimeMillis();
        String result = SentryDaemon.execShell(cmd -> process, "am start ...", 100L);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertTrue(SentryDaemon.isExecTimeout(result));
        assertTrue(process.destroyed);
        assertTrue("Expected fast timeout under 2s, was " + elapsedMs + "ms", elapsedMs < 2_000L);
    }

    @Test
    public void interruptedShellCommandKillsChildAndPreservesInterrupt() {
        FakeProcess process = FakeProcess.interrupted();

        String result = SentryDaemon.execShell(cmd -> process, "am start ...", 1_000L);

        assertEquals("ERROR: interrupted", result);
        assertTrue(process.destroyed);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private static final class FakeProcess extends Process {
        private final byte[] stdout;
        private final boolean complete;
        private final boolean interruptOnWait;
        boolean destroyed;

        private FakeProcess(String stdout, boolean complete, boolean interruptOnWait) {
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            this.complete = complete;
            this.interruptOnWait = interruptOnWait;
        }

        static FakeProcess completed(String stdout) {
            return new FakeProcess(stdout, true, false);
        }

        static FakeProcess hung() {
            return new FakeProcess("", false, false);
        }

        static FakeProcess interrupted() {
            return new FakeProcess("", false, true);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptOnWait) throw new InterruptedException("test interrupt");
            return complete || destroyed;
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (interruptOnWait) throw new InterruptedException("test interrupt");
            return 0;
        }

        @Override
        public int exitValue() {
            if (!complete && !destroyed) throw new IllegalThreadStateException("still running");
            return destroyed ? -1 : 0;
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
            return new ByteArrayInputStream(stdout);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }
}
