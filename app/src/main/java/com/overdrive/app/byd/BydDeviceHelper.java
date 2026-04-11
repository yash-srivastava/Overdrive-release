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

    // ==================== INTERNAL HELPERS ====================

    private static final java.util.Map<Class<?>, Method> getMethodCache = new java.util.HashMap<>();

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
