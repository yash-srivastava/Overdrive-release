package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection utilities for safely accessing BYD SDK devices.
 * Every method is null-safe and exception-safe — never crashes.
 */
public final class BydDeviceHelper {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BydDeviceHelper");

    /**
     * Get a BYD device singleton via reflection.
     * Returns null if the device class doesn't exist or getInstance fails.
     */
    public static Object getDevice(String className, Context context) {
        try {
            Class<?> cls = Class.forName(className);
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);
            if (device != null) {
                logger.info("Device OK: " + cls.getSimpleName());
            } else {
                logger.info("Device NULL: " + cls.getSimpleName());
            }
            return device;
        } catch (ClassNotFoundException e) {
            logger.debug("Device class not found: " + className);
        } catch (Exception e) {
            logger.debug("Device init failed: " + className + " — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a no-arg getter method on a device. Returns null on failure.
     */
    public static Object callGetter(Object device, String methodName) {
        if (device == null) return null;
        try {
            Method m = device.getClass().getMethod(methodName);
            return m.invoke(device);
        } catch (NoSuchMethodException e) {
            return callGetterDeclared(device, methodName);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // The method itself threw — log the root cause
            Throwable cause = e.getCause();
            logger.debug("Getter " + methodName + " threw: " + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("Getter " + methodName + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a getter with one int parameter.
     */
    public static Object callGetter(Object device, String methodName, int param) {
        if (device == null) return null;
        try {
            Method m = device.getClass().getMethod(methodName, int.class);
            return m.invoke(device, param);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("Getter " + methodName + "(" + param + ") threw: " + 
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("Getter " + methodName + "(" + param + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with two int parameters.
     * Used for SDK methods like setAcWindLevel(int, int), setAcWindMode(int, int),
     * setSeatHeatingState(int, int), setSeatVentilatingState(int, int).
     */
    public static Object callMethod(Object device, String methodName, int param1, int param2) {
        if (device == null) return null;
        try {
            Method m = device.getClass().getMethod(methodName, int.class, int.class);
            return m.invoke(device, param1, param2);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + param1 + ", " + param2 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + param1 + ", " + param2 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with four int parameters.
     * Used for SDK methods like setAcTemperature(int zone, int temp, int, int),
     * setAllWindowState(int lf, int rf, int lr, int rr).
     */
    public static Object callMethod(Object device, String methodName, int p1, int p2, int p3, int p4) {
        if (device == null) return null;
        try {
            Method m = device.getClass().getMethod(methodName, int.class, int.class, int.class, int.class);
            return m.invoke(device, p1, p2, p3, p4);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ", " + p4 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ", " + p4 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    private static Object callGetterDeclared(Object device, String methodName) {
        Class<?> cls = device.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Method m = cls.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(device);
            } catch (NoSuchMethodException e) {
                cls = cls.getSuperclass();
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                logger.debug("DeclaredGetter " + methodName + " threw: " + 
                    (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
                return null;
            } catch (Exception e) {
                logger.debug("DeclaredGetter " + methodName + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Call the generic get(int[], Class) method on a BYD device.
     * This is the correct SDK signature for reading feature ID values.
     * Falls back to get(int, int) if the array signature isn't found.
     */
    public static Object callGet(Object device, int featureId, Class<?> returnType) {
        if (device == null) return null;
        try {
            Method m = findGetMethod(device);
            if (m != null) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[0] == int[].class) {
                    return m.invoke(device, new int[]{featureId}, returnType);
                } else if (params.length == 2 && params[0] == int.class) {
                    return m.invoke(device, featureId, 0);
                }
            }
        } catch (Exception e) {
            logger.debug("callGet failed for id=" + featureId + " — " + e.getMessage());
        }
        return null;
    }

    /**
     * Extract intValue from a BYDAutoEventValue object.
     */
    public static int getIntValue(Object eventValue) {
        if (eventValue == null) return Integer.MIN_VALUE;
        try {
            // Direct field access — BYDAutoEventValue.intValue is public
            Field f = eventValue.getClass().getField("intValue");
            return f.getInt(eventValue);
        } catch (Exception e) {
            // Try as Integer directly (some get() calls return boxed primitives)
            if (eventValue instanceof Integer) return (Integer) eventValue;
            if (eventValue instanceof Number) return ((Number) eventValue).intValue();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Extract doubleValue from a BYDAutoEventValue object.
     */
    public static double getDoubleValue(Object eventValue) {
        if (eventValue == null) return Double.NaN;
        try {
            Field f = eventValue.getClass().getField("doubleValue");
            return f.getDouble(eventValue);
        } catch (Exception e) {
            if (eventValue instanceof Double) return (Double) eventValue;
            if (eventValue instanceof Number) return ((Number) eventValue).doubleValue();
        }
        return Double.NaN;
    }

    /**
     * Extract stringValue from a BYDAutoEventValue object.
     */
    public static String getStringValue(Object eventValue) {
        if (eventValue == null) return null;
        try {
            Field f = eventValue.getClass().getField("stringValue");
            return (String) f.get(eventValue);
        } catch (Exception e) {
            if (eventValue instanceof String) return (String) eventValue;
        }
        return null;
    }

    /**
     * Register a listener on a device using IBYDAutoListener interface.
     * Creates a dynamic proxy that forwards all calls to the callback.
     */
    public static boolean registerListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            Class<?> iListener = Class.forName("android.hardware.IBYDAutoListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                iListener.getClassLoader(),
                new Class<?>[]{iListener},
                (p, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) return System.identityHashCode(p);
                    if ("equals".equals(name)) return p == args[0];
                    if ("toString".equals(name)) return "BydListener";
                    try {
                        callback.onCallback(name, args);
                    } catch (Exception e) {
                        logger.debug("Listener callback error: " + name + " — " + e.getMessage());
                    }
                    return null;
                }
            );

            // Try registerListener(IBYDAutoListener)
            Method register = findRegisterMethod(device.getClass(), iListener);
            if (register != null) {
                register.invoke(device, proxy);
                return true;
            }
        } catch (Exception e) {
            logger.debug("registerListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a listener with specific feature IDs.
     */
    public static boolean registerListener(Object device, int[] featureIds, ListenerCallback callback) {
        if (device == null) return false;
        try {
            Class<?> iListener = Class.forName("android.hardware.IBYDAutoListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                iListener.getClassLoader(),
                new Class<?>[]{iListener},
                (p, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) return System.identityHashCode(p);
                    if ("equals".equals(name)) return p == args[0];
                    if ("toString".equals(name)) return "BydListener";
                    try {
                        callback.onCallback(name, args);
                    } catch (Exception e) {
                        logger.debug("Listener callback error: " + name + " — " + e.getMessage());
                    }
                    return null;
                }
            );

            // Try registerListener(IBYDAutoListener, int[])
            Method register = findRegisterMethodWithIds(device.getClass(), iListener);
            if (register != null) {
                register.invoke(device, proxy, featureIds);
                return true;
            }
            // Fallback to no-filter registration
            return registerListener(device, callback);
        } catch (Exception e) {
            logger.debug("registerListener(ids) failed: " + e.getMessage());
        }
        return false;
    }

    // ==================== EXTENDED GETTER METHODS ====================

    /**
     * Call getDouble(int deviceType, int featureId) on a BYD device.
     * Returns Double.NaN on any failure.
     */
    public static double callGetDouble(Object device, int featureId) {
        if (device == null) return Double.NaN;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return Double.NaN;
            Method m = findMethodCached(device, "getDouble", getDoubleMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof Number) return ((Number) result).doubleValue();
            }
        } catch (Exception e) {
            logger.debug("callGetDouble failed for id=" + featureId + " — " + e.getMessage());
        }
        return Double.NaN;
    }

    /**
     * Call getIntArray(int deviceType, int[] featureIds) on a BYD device.
     * Returns null on any failure.
     */
    public static int[] callGetIntArray(Object device, int[] featureIds) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getIntArray", getIntArrayMethodCache,
                    int.class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds);
                if (result instanceof int[]) return (int[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetIntArray failed — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call getDoubleArray(int deviceType, int[] featureIds) on a BYD device.
     * The underlying SDK returns float[], so this method returns float[].
     * Returns null on any failure.
     */
    public static float[] callGetDoubleArray(Object device, int[] featureIds) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getDoubleArray", getDoubleArrayMethodCache,
                    int.class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds);
                if (result instanceof float[]) return (float[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetDoubleArray failed — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call getBuffer(int deviceType, int featureId) on a BYD device.
     * Returns null on any failure.
     */
    public static byte[] callGetBuffer(Object device, int featureId) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getBuffer", getBufferMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof byte[]) return (byte[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetBuffer failed for id=" + featureId + " — " + e.getMessage());
        }
        return null;
    }

    // ==================== SETTER METHODS ====================

    /**
     * Send a set command using the BYDAutoEventValue pattern.
     * Creates a BYDAutoEventValue, sets intValue, calls device.set(int[], BYDAutoEventValue).
     * Falls back to callSetSingle if BYDAutoEventValue is not available.
     */
    public static boolean sendSetCommand(Object device, int featureId, int value) {
        if (device == null) return false;
        try {
            Class<?> eventValueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object eventValue = eventValueClass.getConstructor(new Class[0]).newInstance(new Object[0]);
            eventValueClass.getField("intValue").setInt(eventValue, value);
            Method setMethod = device.getClass().getMethod("set", int[].class, eventValueClass);
            Object result = setMethod.invoke(device, new int[]{featureId}, eventValue);
            if (result instanceof Integer) {
                return ((Integer) result).intValue() >= 0;
            } else if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue();
            }
            return true; // non-null result, assume success
        } catch (ClassNotFoundException e) {
            // BYDAutoEventValue not available, fall back to base class set()
            logger.debug("BYDAutoEventValue not found, falling back to callSetSingle");
            return callSetSingle(device, featureId, value) >= 0;
        } catch (Exception e) {
            logger.debug("sendSetCommand failed for featureId=0x" + Integer.toHexString(featureId) + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Call set(int deviceType, int featureId, int value) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetSingle(Object device, int featureId, int value) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setSingleMethodCache,
                    int.class, int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId, value);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetSingle permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetSingle failed for id=" + featureId + ", value=" + value + " — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call set(int deviceType, int[] featureIds, int[] values) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetBatch(Object device, int[] featureIds, int[] values) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setBatchMethodCache,
                    int.class, int[].class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds, values);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetBatch permission denied — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetBatch failed — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call set(int deviceType, int featureId, byte[] buffer) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetBuffer(Object device, int featureId, byte[] buffer) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setBufferMethodCache,
                    int.class, int.class, byte[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId, buffer);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetBuffer permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetBuffer failed for id=" + featureId + " — " + e.getMessage());
        }
        return -1;
    }

    // ==================== INTERNAL HELPERS ====================

    private static final java.util.Map<Class<?>, Method> getMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> getDoubleMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> getIntArrayMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> getDoubleArrayMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> getBufferMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> setSingleMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> setBatchMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Method> setBufferMethodCache = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, Integer> deviceTypeCache = new java.util.HashMap<>();

    private static Method findGetMethod(Object device) {
        Class<?> cls = device.getClass();
        if (getMethodCache.containsKey(cls)) return getMethodCache.get(cls);

        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("get", int[].class, Class.class);
                m.setAccessible(true);
                getMethodCache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method m = walk.getDeclaredMethod("get", int.class, int.class);
                m.setAccessible(true);
                getMethodCache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        getMethodCache.put(cls, null);
        return null;
    }

    /**
     * Resolve the deviceType from a BYD device object via getDevicetype() or getType().
     * Caches the result per device class. Returns Integer.MIN_VALUE on failure.
     */
    private static int resolveDeviceType(Object device) {
        Class<?> cls = device.getClass();
        if (deviceTypeCache.containsKey(cls)) return deviceTypeCache.get(cls);

        // Try getDevicetype() first (AbsBYDAutoDevice)
        try {
            Method m = cls.getMethod("getDevicetype");
            Object result = m.invoke(device);
            if (result instanceof Number) {
                int type = ((Number) result).intValue();
                deviceTypeCache.put(cls, type);
                return type;
            }
        } catch (Exception ignored) {}

        // Fallback to getType()
        try {
            Method m = cls.getMethod("getType");
            Object result = m.invoke(device);
            if (result instanceof Number) {
                int type = ((Number) result).intValue();
                deviceTypeCache.put(cls, type);
                return type;
            }
        } catch (Exception ignored) {}

        logger.debug("Could not resolve deviceType for " + cls.getSimpleName());
        deviceTypeCache.put(cls, Integer.MIN_VALUE);
        return Integer.MIN_VALUE;
    }

    /**
     * Find a method by name and parameter types on a device, walking up the class hierarchy.
     * Caches the result per device class in the provided cache map.
     */
    private static Method findMethodCached(Object device, String methodName,
            java.util.Map<Class<?>, Method> cache, Class<?>... paramTypes) {
        Class<?> cls = device.getClass();
        if (cache.containsKey(cls)) return cache.get(cls);

        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                cache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        cache.put(cls, null);
        return null;
    }

    private static Method findRegisterMethod(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("registerListener", listenerInterface);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        return null;
    }

    private static Method findRegisterMethodWithIds(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("registerListener", listenerInterface, int[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        return null;
    }

    /** Callback interface for listener proxies */
    public interface ListenerCallback {
        void onCallback(String methodName, Object[] args);
    }

    private BydDeviceHelper() {}
}
