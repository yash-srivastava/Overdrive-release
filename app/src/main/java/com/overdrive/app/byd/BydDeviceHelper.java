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
     * Call a method with one int parameter.
     * Used for SDK methods like voiceCtlMoonRoof(int), voiceCtlSunshadePanel(int).
     */
    public static Object callMethod(Object device, String methodName, int param1) {
        if (device == null) return null;
        try {
            Method m = device.getClass().getMethod(methodName, int.class);
            return m.invoke(device, param1);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + param1 + ") threw: " +
                    (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + param1 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
     * Resolve the IBYDAutoListener-derived interface for a given device class
     * by inspecting registerListener parameter types. Some devices (e.g. ADAS)
     * declare a derived interface (IBYDAutoADASListener) instead of the base
     * IBYDAutoListener — Class.forName on the base name would still find a
     * class, but the proxy must implement the device-specific subtype or
     * registerListener.invoke fails with IllegalArgumentException.
     */
    private static Class<?> getListenerInterface(Class<?> cls, String listenerInterfaceName) {
        if (cls != null) {
            Method[] methods = cls.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().equals("registerListener")) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length == 1) {
                        Class<?>[] interfaces = parameterTypes[0].getInterfaces();
                        if (interfaces.length == 1 && interfaces[0].getName().equals(listenerInterfaceName)) {
                            return interfaces[0];
                        }
                    }
                }
            }
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
            String listenerInterfaceName = "android.hardware.IBYDAutoListener";
            Class<?> iListener = getListenerInterface(device.getClass(), listenerInterfaceName);
            if (iListener == null) {
                iListener = Class.forName(listenerInterfaceName);
            }
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
            String listenerInterfaceName = "android.hardware.IBYDAutoListener";
            Class<?> iListener = getListenerInterface(device.getClass(), listenerInterfaceName);
            if (iListener == null) {
                iListener = Class.forName(listenerInterfaceName);
            }
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

    /**
     * Register a typed (device-specific) listener using a hand-rolled concrete
     * subclass of an abstract listener class. The BYD framework provides the
     * actual abstract class at runtime via the system classloader, and our
     * subclass extends it transparently.
     *
     * Why not Proxy.newProxyInstance: java.lang.reflect.Proxy only works with
     * interfaces, but AbsBYDAutoBodyworkListener / AbsBYDAutoDoorLockListener
     * are abstract classes — Proxy throws "is not an interface" at runtime.
     *
     * Two specialized helpers below cover the two known typed-listener cases.
     * Returns true on successful registration.
     */

    /**
     * Register a typed bodywork listener. Captures door state, window state,
     * and window-open percent callbacks.
     */
    public static boolean registerBodyworkListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener listener =
                new android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener() {
                    @Override
                    public void onDoorStateChanged(int area, int state) {
                        invokeCallback(callback, "onDoorStateChanged", new Object[]{area, state});
                    }
                    @Override
                    public void onWindowStateChanged(int area, int state) {
                        invokeCallback(callback, "onWindowStateChanged", new Object[]{area, state});
                    }
                    @Override
                    public void onWindowOpenPercentChanged(int area, int percent) {
                        invokeCallback(callback, "onWindowOpenPercentChanged", new Object[]{area, percent});
                    }
                    @Override
                    public void onPowerLevelChanged(int level) {
                        invokeCallback(callback, "onPowerLevelChanged", new Object[]{level});
                    }
                };
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerBodyworkListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerBodyworkListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerBodyworkListener failed: " + e.getMessage());
        }
        return false;
    }

    public static boolean registerTyreListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.tyre.AbsBYDAutoTyreListener listener =
                new android.hardware.bydauto.tyre.AbsBYDAutoTyreListener() {
                    @Override
                    public void onTyrePressureValueChanged(int wheel, int value) {
                        invokeCallback(callback, "onTyrePressureValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyrePressureStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyrePressureStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreBatteryValueChanged(int wheel, double value) {
                        invokeCallback(callback, "onTyreBatteryValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyreBatteryStateChanged(int state) {
                        invokeCallback(callback, "onTyreBatteryStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onTyreTemperatureStateChanged(int state) {
                        invokeCallback(callback, "onTyreTemperatureStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onTyreAirLeakStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyreAirLeakStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreSignalStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyreSignalStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreSystemStateChanged(int state) {
                        invokeCallback(callback, "onTyreSystemStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onIndirectTyreSystemStateChanged(int state) {
                        invokeCallback(callback, "onIndirectTyreSystemStateChanged", new Object[]{state});
                    }
                    // Generic feature-ID event channel. When the listener is
                    // registered via the 2-arg overload with an int[] filter,
                    // the HAL fires this for each subscribed feature ID
                    // instead of (or alongside) the typed callbacks above.
                    // The Tyre device delegates per-wheel temperature reads
                    // through Instrument-class feature IDs (LF/RF/LB/RB).
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };

            // Log available registerListener overloads for diagnostics
            Method[] allMethods = device.getClass().getMethods();
            StringBuilder overloads = new StringBuilder();
            for (Method m : allMethods) {
                if ("registerListener".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    overloads.append("  registerListener(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) overloads.append(", ");
                        overloads.append(params[i].getSimpleName());
                    }
                    overloads.append(")\n");
                }
            }
            if (overloads.length() > 0) {
                logger.info("TyreDevice registerListener overloads:\n" + overloads);
            }

            // Strategy 1: 2-arg registration with the per-wheel temperature
            // feature IDs. BYDAutoFeatureIds.Instrument exposes the LF/RF/LB/
            // RB tyre temperature property IDs — those are the ones the Tyre
            // device's underlying property tree actually keys on, regardless
            // of which device class hosts the registerListener overload.
            // Filtering on this exact set is what wakes up the temperature
            // event channel on firmwares where the bare typed callbacks
            // (onTyreBatteryValueChanged) stay dormant.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    int[] tyreFeatureIds = com.overdrive.app.byd.BydFeatureIds.INSTRUMENT_TYRE_TEMP_IDS;
                    registerWithIds.invoke(device, listener, tyreFeatureIds);
                    logger.info("Tyre listener registered via 2-arg overload with LF/RF/LB/RB feature IDs");
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.info("Tyre 2-arg registration with LF/RF/LB/RB IDs failed: " + e.getMessage());
                }
                // Fallback: empty int[]. Some HAL implementations interpret
                // this as "subscribe to all features"; others reject it.
                // We only attempt this if the typed-ID registration above
                // failed outright (e.g. method threw on invoke).
                if (!twoArgRegistered) {
                    try {
                        registerWithIds.invoke(device, listener, new int[0]);
                        logger.info("Tyre listener registered via 2-arg overload with empty int[] (subscribe-all fallback)");
                        twoArgRegistered = true;
                    } catch (Exception e) {
                        logger.info("Tyre 2-arg registration with empty int[] failed: " + e.getMessage());
                    }
                }
            }

            // Strategy 2: Also register via single-arg (ensures pressure/leak/signal
            // events still arrive even if the two-arg only subscribes to temp events).
            // If two-arg already succeeded, this is additive — BYD HAL allows multiple
            // registrations. If two-arg wasn't available, this is the only path.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class);
            if (register != null) {
                register.invoke(device, listener);
                if (twoArgRegistered) {
                    logger.info("Tyre listener also registered via 1-arg overload (pressure/state events)");
                } else {
                    logger.info("Tyre listener registered via 1-arg overload only");
                }
                return true;
            }
            // If single-arg failed but two-arg succeeded, still report success
            if (twoArgRegistered) return true;
            logger.debug("registerTyreListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerTyreListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerTyreListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed engine listener so onEngineCoolantLevelChanged and
     * onOilLevelChanged actually dispatch (the bare 1-arg
     * registerListener(IBYDAutoListener) registration succeeds but the HAL
     * never invokes the device-specific callbacks on AbsBYDAutoEngineListener
     * subclasses on most firmware).
     *
     * Mirrors the tyre approach: try the 2-arg overload first (HAL only fires
     * onDataEventChanged with feature-IDs when registered with int[] filter),
     * then 1-arg as a baseline. Both succeed additively where supported.
     */
    public static boolean registerEngineListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.engine.AbsBYDAutoEngineListener listener =
                new android.hardware.bydauto.engine.AbsBYDAutoEngineListener() {
                    @Override
                    public void onEngineSpeedChanged(int value) {
                        invokeCallback(callback, "onEngineSpeedChanged", new Object[]{value});
                    }
                    @Override
                    public void onEngineCoolantLevelChanged(int state) {
                        invokeCallback(callback, "onEngineCoolantLevelChanged", new Object[]{state});
                    }
                    @Override
                    public void onOilLevelChanged(int value) {
                        invokeCallback(callback, "onOilLevelChanged", new Object[]{value});
                    }
                    // Generic feature-ID event channel, same pattern as the
                    // tyre listener. Engine extras (coolant temp, oil temp on
                    // PHEV firmware) tend to land here keyed on feature IDs
                    // we may not have in BYDAutoFeatureIds.Engine.
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };

            // Diagnostic: dump all registerListener overloads so we know what
            // shapes the HAL exposes on this firmware.
            Method[] allMethods = device.getClass().getMethods();
            StringBuilder overloads = new StringBuilder();
            for (Method m : allMethods) {
                if ("registerListener".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    overloads.append("  registerListener(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) overloads.append(", ");
                        overloads.append(params[i].getSimpleName());
                    }
                    overloads.append(")\n");
                }
            }
            if (overloads.length() > 0) {
                logger.info("EngineDevice registerListener overloads:\n" + overloads);
            }

            // Strategy 1: 2-arg with empty int[]. We don't have engine fluid
            // feature IDs in BYDAutoFeatureIds.Engine, so empty-array
            // (subscribe-all) is the only option for the filtered overload.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    logger.info("Engine listener registered via 2-arg overload with empty int[]");
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.info("Engine 2-arg registration failed: " + e.getMessage());
                }
            }

            // Strategy 2: 1-arg typed. Even when the HAL never fires the
            // typed callbacks, this one is harmless and gives us the
            // baseline that several other devices rely on.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class);
            if (register != null) {
                register.invoke(device, listener);
                logger.info(twoArgRegistered
                        ? "Engine listener also registered via 1-arg overload"
                        : "Engine listener registered via 1-arg overload only");
                return true;
            }
            if (twoArgRegistered) return true;
            logger.debug("registerEngineListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerEngineListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerEngineListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed door-lock listener. Captures the canonical
     * onDoorLockStatusChanged(area, state) event the BMS emits when the gun is
     * connected and the lock state transitions.
     */
    public static boolean registerDoorLockListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener listener =
                new android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener() {
                    @Override
                    public void onDoorLockStatusChanged(int area, int state) {
                        invokeCallback(callback, "onDoorLockStatusChanged", new Object[]{area, state});
                    }
                };
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerDoorLockListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerDoorLockListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerDoorLockListener failed: " + e.getMessage());
        }
        return false;
    }

    private static void invokeCallback(ListenerCallback callback, String method, Object[] args) {
        try {
            callback.onCallback(method, args);
        } catch (Exception e) {
            logger.debug("Typed listener callback error: " + method + " — " + e.getMessage());
        }
    }

    // ==================== EXTENDED GETTER METHODS ====================

    /**
     * Call get(int deviceType, int featureId) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callGetSingle(Object device, int featureId) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "get", getSingleMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callGetSingle permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callGetSingle failed for id=" + featureId + " — " + e.getMessage());
        }
        return -1;
    }

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
    private static final java.util.Map<Class<?>, Method> getSingleMethodCache = new java.util.HashMap<>();
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
