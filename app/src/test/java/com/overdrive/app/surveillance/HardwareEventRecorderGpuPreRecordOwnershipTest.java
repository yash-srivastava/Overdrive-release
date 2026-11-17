package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

public class HardwareEventRecorderGpuPreRecordOwnershipTest {
    private static final int RING_BUDGET_BYTES = 8 * 1024 * 1024;

    private DaemonLogger.Config originalLoggerConfig;

    @Before
    public void setUp() throws Exception {
        originalLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        resetSharedRing();
    }

    @After
    public void tearDown() throws Exception {
        resetSharedRing();
        DaemonLogger.configure(originalLoggerConfig);
    }

    @Test
    public void perInstancePreInitDurationDoesNotResizeSharedRing() throws Exception {
        H264ByteRingBuffer sharedRing = installSharedRing(62);
        HardwareEventRecorderGpu oemEncoder = newEncoder();

        oemEncoder.setUseInstancePreRecordBuffer(true);
        oemEncoder.setPreRecordDuration(5);

        assertEquals(62_000_000L, sharedRing.getMaxDurationUs());
        assertEquals(5, getInstanceIntField(oemEncoder, "preRecordDurationSeconds"));
    }

    @Test
    public void sharedEncoderDurationStillResizesSharedRing() throws Exception {
        H264ByteRingBuffer sharedRing = installSharedRing(62);
        HardwareEventRecorderGpu sharedEncoder = newEncoder();

        sharedEncoder.setPreRecordDuration(10);

        // 10 s window plus the GOP it starts in.
        assertEquals(12_000_000L, sharedRing.getMaxDurationUs());
    }

    @Test
    public void initializedPerInstanceDurationOnlyResizesPrivateRing() throws Exception {
        H264ByteRingBuffer sharedRing = installSharedRing(62);
        H264ByteRingBuffer privateRing = new H264ByteRingBuffer(RING_BUDGET_BYTES, 5);
        HardwareEventRecorderGpu oemEncoder = newEncoder();
        oemEncoder.setUseInstancePreRecordBuffer(true);
        setInstanceField(oemEncoder, "preRecordBuffer", privateRing);
        setInstanceField(oemEncoder, "preRecordBufferIsInstance", true);

        oemEncoder.setPreRecordDuration(10);

        assertEquals(62_000_000L, sharedRing.getMaxDurationUs());
        assertEquals(12_000_000L, privateRing.getMaxDurationUs());
    }

    private static HardwareEventRecorderGpu newEncoder() throws Exception {
        // Local JVM tests use the Android stub jar, whose MediaCodec.BufferInfo
        // constructor throws. Allocate without running Android field initializers;
        // these tests exercise only the pre-record ownership state machine.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        return (HardwareEventRecorderGpu) unsafeClass
                .getMethod("allocateInstance", Class.class)
                .invoke(unsafe, HardwareEventRecorderGpu.class);
    }

    private static H264ByteRingBuffer installSharedRing(int durationSeconds) throws Exception {
        H264ByteRingBuffer ring = new H264ByteRingBuffer(RING_BUDGET_BYTES, durationSeconds);
        setStaticField("sharedPreRecordBuffer", ring);
        setStaticField("sharedPreRecordBudgetBytes", RING_BUDGET_BYTES);
        return ring;
    }

    private static void resetSharedRing() throws Exception {
        setStaticField("sharedPreRecordBuffer", null);
        setStaticField("sharedPreRecordBudgetBytes", 0);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setInstanceField(Object target, String name, Object value) throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int getInstanceIntField(Object target, String name) throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
