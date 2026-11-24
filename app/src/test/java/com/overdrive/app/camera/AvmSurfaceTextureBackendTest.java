package com.overdrive.app.camera;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class AvmSurfaceTextureBackendTest {
    private PreviewCamera camera;

    @Before
    public void resetFactories() {
        camera = new PreviewCamera();
        AvmFactory.result = camera;
        AvmFactory.failure = null;
        AvmFactory.ids.clear();
        AvmFactory.constructors = 0;
        NormalFactory.result = null;
        NormalFactory.ids.clear();
        LegacyCamera.instance = null;
        TwoArgumentCamera.instance = null;
        RefusedLegacyCamera.instance = null;
    }

    @Test
    public void staticFactoryUsesOnlyRequestedIdAndDoesNotOpenNormalOnSuccess() throws Exception {
        AvmSurfaceTextureBackend backend = open();
        assertSame(camera, backend.getCamera());
        assertTrue(backend.owns(camera));
        assertFalse(backend.owns(new PreviewCamera()));
        assertEquals(Arrays.asList(4), AvmFactory.ids);
        assertTrue(NormalFactory.ids.isEmpty());
        assertEquals(0, AvmFactory.constructors);
    }

    @Test
    public void nullAvmFactoryFallsBackToNormalWithSameId() throws Exception {
        AvmFactory.result = null;
        VoidNormalCamera normal = new VoidNormalCamera();
        NormalFactory.result = normal;
        AvmSurfaceTextureBackend backend = open();
        assertSame(normal, backend.getCamera());
        assertEquals(Arrays.asList(4), AvmFactory.ids);
        assertEquals(Arrays.asList(4), NormalFactory.ids);
        FakeSurface surface = new FakeSurface();
        backend.attachAndStart(surface, FakeSurface.class, 0);
        assertTrue(backend.close());
        assertSame(surface, normal.removed);
        assertEquals(Arrays.asList("add", "start", "remove", "stop", "close"), normal.calls);
    }

    @Test
    public void throwingFactoryDoesNotMaskErrorWithNormalOrConstructorFallback() {
        RuntimeException failure = new RuntimeException("HAL refused acquisition");
        AvmFactory.failure = failure;
        assertSame(failure, assertThrows(RuntimeException.class, this::open));
        assertEquals(Arrays.asList(4), AvmFactory.ids);
        assertTrue(NormalFactory.ids.isEmpty());
        assertEquals(0, AvmFactory.constructors);
    }

    @Test
    public void nullFactoriesDoNotProbeOtherIdsOrBypassWithConstructor() {
        AvmFactory.result = null;
        assertThrows(IllegalStateException.class, this::open);
        assertEquals(Arrays.asList(4), AvmFactory.ids);
        assertEquals(Arrays.asList(4), NormalFactory.ids);
        assertEquals(0, AvmFactory.constructors);
    }

    @Test
    public void constructorFallbackOnlyAppliesWhenFactoriesAreAbsent() throws Exception {
        AvmSurfaceTextureBackend single =
            AvmSurfaceTextureBackend.open(2, LegacyCamera.class, null);
        assertSame(LegacyCamera.instance, single.getCamera());
        assertEquals(2, LegacyCamera.instance.id);
        assertEquals(Arrays.asList("open"), LegacyCamera.instance.calls);
        assertTrue(single.close());

        AvmSurfaceTextureBackend two =
            AvmSurfaceTextureBackend.open(3, TwoArgumentCamera.class, null);
        assertSame(TwoArgumentCamera.instance, two.getCamera());
        assertEquals(3, TwoArgumentCamera.instance.id);
        assertEquals(0, TwoArgumentCamera.instance.deviceType);
        assertTrue(two.close());
    }

    @Test
    public void refusedConstructorOpenClosesPartialHandle() {
        assertThrows(IllegalStateException.class,
            () -> AvmSurfaceTextureBackend.open(1, RefusedLegacyCamera.class, null));
        assertEquals(Arrays.asList("open", "close"), RefusedLegacyCamera.instance.calls);
    }

    @Test
    public void negativeIdDoesNotTouchFactories() {
        assertThrows(IllegalArgumentException.class,
            () -> AvmSurfaceTextureBackend.open(-1, AvmFactory.class, NormalFactory.class));
        assertTrue(AvmFactory.ids.isEmpty());
        assertTrue(NormalFactory.ids.isEmpty());
    }

    @Test
    public void fpsAndSizeRefusalsAreReportedWithoutPreventingPreview() throws Exception {
        camera.fpsAccepted = false;
        camera.sizeAccepted = false;
        AvmSurfaceTextureBackend backend = open();
        String diagnostic = backend.configurePreview(15, 5120, 960);
        assertTrue(diagnostic.contains("setFps=false"));
        assertTrue(diagnostic.contains("setSize=false"));
        assertTrue(diagnostic.contains("declared=5120x960"));
        backend.attachAndStart(new FakeSurface(), FakeSurface.class, 0);
        assertEquals(Arrays.asList("fps", "size", "width", "height", "add", "start"),
            camera.calls);
    }

    @Test
    public void falseOrNullAttachFailsAndStillRetiresExactAttemptedBinding() throws Exception {
        for (Boolean accepted : new Boolean[]{false, null}) {
            resetFactories();
            camera.attachAccepted = accepted;
            AvmSurfaceTextureBackend backend = open();
            FakeSurface surface = new FakeSurface();
            assertThrows(IllegalStateException.class,
                () -> backend.attachAndStart(surface, FakeSurface.class, 3));
            assertEquals(Arrays.asList("add"), camera.calls);
            assertTrue(backend.close());
            assertSame(surface, camera.removed);
            assertEquals(3, camera.removedIndex);
            assertFalse(camera.calls.contains("start"));
        }
    }

    @Test
    public void falseOrNullStartDoesNotClaimSuccessfulPreview() throws Exception {
        for (Boolean accepted : new Boolean[]{false, null}) {
            resetFactories();
            camera.startAccepted = accepted;
            AvmSurfaceTextureBackend backend = open();
            FakeSurface surface = new FakeSurface();
            assertThrows(IllegalStateException.class,
                () -> backend.attachAndStart(surface, FakeSurface.class, 0));
            assertEquals(Arrays.asList("add", "start"), camera.calls);
            assertTrue(backend.close());
            assertSame(surface, camera.removed);
        }
    }

    @Test
    public void cleanupRemovesBindingAndCallbacksBeforeStopAndClose() throws Exception {
        AvmSurfaceTextureBackend backend = open();
        FakeSurface surface = new FakeSurface();
        backend.attachAndStart(surface, FakeSurface.class, 2);
        assertThrows(IllegalStateException.class,
            () -> backend.attachAndStart(new FakeSurface(), FakeSurface.class, 0));
        assertTrue(backend.close());
        assertSame(surface, camera.removed);
        assertEquals(2, camera.removedIndex);
        assertEquals(Arrays.asList("add", "start", "remove", "previewCallback",
            "eventCallback", "disable", "stop", "close"), camera.calls);
        assertTrue(backend.close());
        assertEquals(1, camera.closeCalls);
        assertThrows(IllegalStateException.class,
            () -> backend.attachAndStart(surface, FakeSurface.class, 0));
    }

    @Test
    public void removalAndStopFailuresStillCloseVendorHandle() throws Exception {
        camera.removeFailure = true;
        camera.stopFailure = true;
        AvmSurfaceTextureBackend backend = open();
        backend.attachAndStart(new FakeSurface(), FakeSurface.class, 0);
        assertTrue(backend.close());
        assertEquals(1, camera.closeCalls);
        assertTrue(camera.calls.indexOf("remove") < camera.calls.indexOf("stop"));
        assertTrue(camera.calls.indexOf("stop") < camera.calls.indexOf("close"));
    }

    @Test
    public void refusedCloseRetainsFailureAndCanBeRetried() throws Exception {
        camera.closeAccepted = false;
        AvmSurfaceTextureBackend backend = open();
        backend.attachAndStart(new FakeSurface(), FakeSurface.class, 0);
        assertFalse(backend.close());
        camera.closeAccepted = true;
        assertTrue(backend.close());
        assertEquals(2, camera.closeCalls);
        assertEquals(1, camera.removeCalls);
    }

    @Test
    public void lateCloseCannotRemoveReplacementSurface() throws Exception {
        AvmSurfaceTextureBackend old = open();
        FakeSurface retiredSurface = new FakeSurface();
        old.attachAndStart(retiredSurface, FakeSurface.class, 0);
        PreviewCamera replacementCamera = new PreviewCamera();
        AvmFactory.result = replacementCamera;
        AvmSurfaceTextureBackend replacement = open();
        FakeSurface replacementSurface = new FakeSurface();
        replacement.attachAndStart(replacementSurface, FakeSurface.class, 1);
        assertTrue(old.close());
        assertSame(retiredSurface, camera.removed);
        assertNull(replacementCamera.removed);
        assertEquals(Arrays.asList("add", "start"), replacementCamera.calls);
        assertTrue(replacement.close());
        assertSame(replacementSurface, replacementCamera.removed);
    }

    private AvmSurfaceTextureBackend open() throws Exception {
        return AvmSurfaceTextureBackend.open(4, AvmFactory.class, NormalFactory.class);
    }

    public static final class FakeSurface {}

    public static final class AvmFactory {
        static final List<Integer> ids = new ArrayList<>();
        static Object result;
        static RuntimeException failure;
        static int constructors;
        public AvmFactory(int id) { constructors++; }
        public static Object open(int id) {
            ids.add(id);
            if (failure != null) throw failure;
            return result;
        }
    }

    public static final class NormalFactory {
        static final List<Integer> ids = new ArrayList<>();
        static Object result;
        public static Object open(int id) { ids.add(id); return result; }
    }

    public static class PreviewCamera {
        final List<String> calls = new ArrayList<>();
        Boolean attachAccepted = true;
        Boolean startAccepted = true;
        boolean fpsAccepted = true;
        boolean sizeAccepted = true;
        boolean closeAccepted = true;
        boolean removeFailure;
        boolean stopFailure;
        FakeSurface removed;
        int removedIndex = -1;
        int closeCalls;
        int removeCalls;

        public boolean setCameraFps(int fps) { calls.add("fps"); return fpsAccepted; }
        public boolean setPreviewSize(int width, int height) { calls.add("size"); return sizeAccepted; }
        public int getPreviewWidth() { calls.add("width"); return 5120; }
        public int getPreviewHeight() { calls.add("height"); return 960; }
        public Boolean addPreviewSurface(FakeSurface surface, int index) {
            calls.add("add"); return attachAccepted;
        }
        public Boolean startPreview() { calls.add("start"); return startAccepted; }
        public void rmPreviewSurface(FakeSurface surface, int index) {
            calls.add("remove"); removeCalls++; removed = surface; removedIndex = index;
            if (removeFailure) throw new IllegalStateException("remove failed");
        }
        public void setPreviewCallback(Object callback) {
            assertNull(callback); calls.add("previewCallback");
        }
        public void setEventCallback(Object callback) {
            assertNull(callback); calls.add("eventCallback");
        }
        public void disablePreviewCallback(int index) { calls.add("disable"); }
        public void stopPreview() {
            calls.add("stop");
            if (stopFailure) throw new IllegalStateException("stop failed");
        }
        public boolean close() { calls.add("close"); closeCalls++; return closeAccepted; }
    }

    public static final class VoidNormalCamera {
        final List<String> calls = new ArrayList<>();
        FakeSurface removed;
        public void addPreviewSurface(FakeSurface surface) { calls.add("add"); }
        public void startPreview() { calls.add("start"); }
        public void rmPreviewSurface(FakeSurface surface) { removed = surface; calls.add("remove"); }
        public void stopPreview() { calls.add("stop"); }
        public void close() { calls.add("close"); }
    }

    public static final class LegacyCamera extends PreviewCamera {
        static LegacyCamera instance;
        final int id;
        public LegacyCamera(int id) { this.id = id; instance = this; }
        public boolean open() { calls.add("open"); return true; }
    }

    public static final class TwoArgumentCamera extends PreviewCamera {
        static TwoArgumentCamera instance;
        final int id;
        final int deviceType;
        public TwoArgumentCamera(int id, int deviceType) {
            this.id = id; this.deviceType = deviceType; instance = this;
        }
        public boolean open() { calls.add("open"); return true; }
    }

    public static final class RefusedLegacyCamera {
        static RefusedLegacyCamera instance;
        final List<String> calls = new ArrayList<>();
        public RefusedLegacyCamera(int id) { instance = this; }
        public boolean open() { calls.add("open"); return false; }
        public boolean close() { calls.add("close"); return true; }
    }
}
