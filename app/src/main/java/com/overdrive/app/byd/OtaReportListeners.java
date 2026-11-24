package com.overdrive.app.byd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/** Adds one generic OTA listener without replacing another client's native feature filter. */
public final class OtaReportListeners {
    public static final int DYNAMIC_REPORT = 0x9900001e;

    private OtaReportListeners() {}

    private static Object listenerMap(Object device) throws ReflectiveOperationException {
        for (Class<?> type = device.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mIBYDAutoListenerMap");
                field.setAccessible(true);
                return field.get(device);
            } catch (NoSuchFieldException ignored) {
                // The map is normally owned by the base SDK device.
            }
        }
        throw new NoSuchFieldException("mIBYDAutoListenerMap");
    }

    public static boolean contains(Object device, Object listener) throws ReflectiveOperationException {
        Object map = listenerMap(device);
        synchronized (map) {
            return containsInMap(map, listener);
        }
    }

    private static boolean containsInMap(Object map, Object listener) throws ReflectiveOperationException {
        Method listeners = map.getClass().getMethod("getIdListeners", int.class);
        listeners.setAccessible(true);
        Object values = listeners.invoke(map, DYNAMIC_REPORT);
        return values instanceof Collection && ((Collection<?>) values).contains(listener);
    }

    public static boolean register(Object device, Class<?> type, Object listener)
            throws ReflectiveOperationException {
        Object map = listenerMap(device);
        synchronized (map) {
            Method empty = map.getClass().getMethod("isListenerEmpty");
            Method allEmpty = map.getClass().getMethod("isListenerAllIdsEmpty");
            empty.setAccessible(true);
            allEmpty.setAccessible(true);
            // Enabling an independent narrow filter can replace prior native filters.
            // A pre-existing subscribe-all listener already enables every feature.
            if (!Boolean.TRUE.equals(empty.invoke(map)) && !Boolean.FALSE.equals(allEmpty.invoke(map))) {
                return false;
            }
            device.getClass().getMethod("registerListener", type, int[].class)
                    .invoke(device, listener, new int[]{DYNAMIC_REPORT});
            return containsInMap(map, listener);
        }
    }
}
