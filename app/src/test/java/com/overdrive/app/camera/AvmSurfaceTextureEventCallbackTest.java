package com.overdrive.app.camera;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class AvmSurfaceTextureEventCallbackTest {
    @Test
    public void normalCameraCanUseInheritedEventCallbackInterface() {
        BydCameraCoordinator coordinator = new BydCameraCoordinator();
        List<Integer> errors = collectErrors(coordinator);
        NormalCamera camera = new NormalCamera();
        coordinator.setupSurfaceTextureEventCallback(camera);
        assertTrue(coordinator.isEventCallbackActive());
        assertNotNull(camera.callback);
        camera.callback.onEvent(4, 1002);
        assertEquals(Arrays.asList(1002), errors);
        assertTrue(camera.callback.equals(camera.callback));
        assertFalse(camera.callback.equals(new Object()));
        assertEquals(System.identityHashCode(camera.callback), camera.callback.hashCode());
        assertNotNull(camera.callback.toString());
    }

    @Test
    public void replacedAndRetiredCallbacksCannotReportErrorsForNewOwner() {
        BydCameraCoordinator coordinator = new BydCameraCoordinator();
        List<Integer> errors = collectErrors(coordinator);
        NormalCamera retired = new NormalCamera();
        NormalCamera replacement = new NormalCamera();
        coordinator.setupSurfaceTextureEventCallback(retired);
        CameraApi.EventCallback queuedCallback = retired.callback;
        coordinator.setupSurfaceTextureEventCallback(replacement);
        queuedCallback.onEvent(4, 8);
        coordinator.retireSurfaceTextureEventCallback(retired);
        assertTrue(coordinator.isEventCallbackActive());
        replacement.callback.onEvent(4, 1002);
        assertEquals(Arrays.asList(1002), errors);
        coordinator.retireSurfaceTextureEventCallback(replacement);
        replacement.callback.onEvent(4, 8);
        assertFalse(coordinator.isEventCallbackActive());
        assertEquals(Arrays.asList(1002), errors);
    }

    @Test
    public void missingOrRefusedCallbackApiLeavesFrameDeliveryAvailable() {
        BydCameraCoordinator coordinator = new BydCameraCoordinator();
        coordinator.setupSurfaceTextureEventCallback(new Object());
        assertFalse(coordinator.isEventCallbackActive());
        RefusedCamera camera = new RefusedCamera();
        List<Integer> errors = collectErrors(coordinator);
        coordinator.setupSurfaceTextureEventCallback(camera);
        assertFalse(coordinator.isEventCallbackActive());
        camera.callback.onEvent(4, 8);
        assertTrue(errors.isEmpty());
    }

    private static List<Integer> collectErrors(BydCameraCoordinator coordinator) {
        List<Integer> errors = new ArrayList<>();
        coordinator.setYieldCallback(new BydCameraCoordinator.CameraYieldCallback() {
            public boolean onYieldCamera() { return true; }
            public void onReacquireCamera() {}
            public void onCameraError(int eventType) { errors.add(eventType); }
        });
        return errors;
    }

    public static class CameraApi {
        public interface EventCallback { void onEvent(int id, int event); }
        EventCallback callback;
        public void setEventCallback(EventCallback value) { callback = value; }
    }

    public static final class NormalCamera extends CameraApi {}

    public static final class RefusedCamera {
        CameraApi.EventCallback callback;
        public boolean setEventCallback(CameraApi.EventCallback value) {
            callback = value;
            return false;
        }
    }
}
