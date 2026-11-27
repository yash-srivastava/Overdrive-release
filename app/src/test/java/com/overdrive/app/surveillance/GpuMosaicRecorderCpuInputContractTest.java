package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class GpuMosaicRecorderCpuInputContractTest {
    @Test
    public void cameraPlanesAreMappedOnlyAfterCodecAdmitsInput() throws Exception {
        String mosaic = readSource("GpuMosaicRecorder.java");
        String codec = readSource("HardwareEventRecorderGpu.java");
        String submit = between(mosaic, "private void writeCpuFrame(", "private final Object cpuFailLock");
        assertFalse(submit.contains("bindSource("));
        assertFalse(submit.contains("bindWindshield("));
        int queue = submit.indexOf("enc.queueCpuFrame(");
        int reset = submit.indexOf("w.sourceRejected = false;");
        int release = submit.indexOf("job.releaseSources();", queue);
        int result = submit.indexOf("switch (result)");
        assertTrue(reset >= 0 && queue > reset && release > queue && result > release);
        assertTrue(submit.contains("RecordingEngine.WHY_FRAMES_UNREADABLE"));

        String write = between(mosaic, "public boolean write(android.media.Image input)",
                "public boolean stripDrawn()");
        int bind = write.indexOf("bindSource(j.source.image)");
        int rejected = write.indexOf("sourceRejected = true;", bind);
        int windshield = write.indexOf("bindWindshield(j.windshield.image)");
        int compose = write.indexOf("CpuMosaicCompositor.nativeCompose(");
        assertTrue(bind >= 0 && rejected > bind && windshield > rejected && compose > windshield);
        assertTrue(write.contains("j.releaseSources();"));

        String admit = between(codec, "public int queueCpuFrame(", "private void closeCpuInput()");
        int busy = admit.indexOf("CPU_FRAME_BUSY");
        int dequeue = admit.indexOf("codec.dequeueInputBuffer(0)");
        int noBuffer = admit.indexOf("CPU_FRAME_NO_BUFFER");
        int writer = admit.indexOf("writer.write(image)");
        assertTrue(busy >= 0 && dequeue > busy && noBuffer > dequeue && writer > noBuffer);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String readSource(String name) throws Exception {
        Path relative = Paths.get("src/main/java/com/overdrive/app/surveillance", name);
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null) {
            for (Path candidate : new Path[]{root.resolve(relative), root.resolve("app").resolve(relative)}) {
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                }
            }
            root = root.getParent();
        }
        throw new AssertionError("Could not locate " + name);
    }
}
