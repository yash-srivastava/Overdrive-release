package com.overdrive.app.power;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * DiLink 5 IVI backlight / display-power client for {@code CarPowerService}.
 *
 * <p>Shell {@code app_process} can load {@code CarPowerManager} but never gets a
 * live {@code ICarPowerBinder} (sets return false; logs {@code mICarPowerBinder : null}).
 * The real app process can bind {@code com.ts.intent.action.ts.carpower} the same
 * way SystemUI / mycar already do. This class is the probe for that bind — do not
 * treat a successful {@code Class.forName} as proof the panel moved.
 *
 * <p>Live {@code CarPowerConst} on this HU (do not invert these):
 * <ul>
 *   <li>{@code BRIGHTNESS_STATE_ON = 0}, {@code BRIGHTNESS_STATE_OFF = 1}</li>
 *   <li>{@code BRIGHTNESS_ID_BACKLIGHT = 0} (not a display panel id)</li>
 *   <li>{@code DISPLAYPANEL_CENTRAL_IVI = 1} ({@code 0} is INVALID)</li>
 *   <li>{@code SET_DISPLAY_POWER_STATE_ON = 1}, {@code OFF = 2}</li>
 * </ul>
 */
public final class TsCarPowerClient {

    private static final String TAG = "TsCarPower";

    private static final String MANAGER = "com.ts.lib.power.manager.CarPowerManager";
    private static final String LISTENER = "com.ts.lib.power.listener.CarPowerEventListener";

    // CarPowerConst — copied from the live ts-framework dump, not guessed.
    private static final int BRIGHTNESS_ID_BACKLIGHT = 0;
    private static final int BRIGHTNESS_ID_BACKLIGHT_HOME = 1;
    private static final int BRIGHTNESS_STATE_ON = 0;
    private static final int BRIGHTNESS_STATE_OFF = 1;
    private static final int DISPLAYPANEL_CENTRAL_IVI = 1;
    private static final int SET_DISPLAY_POWER_STATE_ON = 1;
    private static final int SET_DISPLAY_POWER_STATE_OFF = 2;

    private TsCarPowerClient() {}

    /**
     * @param mode {@code read} (default), {@code on}, or {@code off}.
     * @return a single-line summary plus per-call results for logcat / actuator logs
     */
    public static String probe(Context ctx, String mode) {
        StringBuilder out = new StringBuilder();
        String want = mode == null ? "read" : mode.trim().toLowerCase();
        log(out, "mode=" + want + " pkg=" + ctx.getPackageName()
                + " uid=" + android.os.Process.myUid());
        try {
            Class<?> mgrClz = Class.forName(MANAGER);
            Class<?> listenerClz = Class.forName(LISTENER);
            Object listener = Proxy.newProxyInstance(
                    listenerClz.getClassLoader(),
                    new Class<?>[]{listenerClz},
                    (InvocationHandler) (proxy, method, args) -> {
                        log(out, "event " + method.getName());
                        return defaultValue(method.getReturnType());
                    });
            Method getInstance = mgrClz.getMethod("getInstance", Context.class, listenerClz);
            Object mgr = getInstance.invoke(null, ctx.getApplicationContext(), listener);
            log(out, "getInstance=" + (mgr != null ? mgr.getClass().getName() : "null"));
            if (mgr == null) return out.toString();

            waitUntilConnected(mgr, out);
            dumpConnection(mgr, out);
            snapshot(mgr, out);

            if ("on".equals(want)) {
                applyDisplay(mgr, out, true);
            } else if ("off".equals(want)) {
                applyDisplay(mgr, out, false);
            }
        } catch (Throwable t) {
            log(out, "FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            if (c != null) log(out, "cause " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
        Log.i(TAG, out.toString());
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "carpower_probe.txt"), false);
                w.write(out.toString().replace(" | ", "\n"));
                w.write("\n");
                w.close();
            }
        } catch (Throwable ignored) {}
        return out.toString();
    }

    /**
     * Inverse of the live-proven ON sequence. Backlight ON is {@code 0}, OFF
     * is {@code 1}; display-power ON is {@code 1}, OFF is {@code 2}.
     */
    private static void applyDisplay(Object mgr, StringBuilder out, boolean on) {
        int light = on ? BRIGHTNESS_STATE_ON : BRIGHTNESS_STATE_OFF;
        int power = on ? SET_DISPLAY_POWER_STATE_ON : SET_DISPLAY_POWER_STATE_OFF;
        int lvds = on ? 1 : 0;
        String lightLabel = on ? "ON=0" : "OFF=1";
        String powerLabel = on ? "ON=1" : "OFF=2";
        log(out, "setBackLightState(BACKLIGHT, " + lightLabel + ")="
                + invokeBool(mgr, "setBackLightState",
                        BRIGHTNESS_ID_BACKLIGHT, light));
        log(out, "setBackLightState(HOME, " + lightLabel + ")="
                + invokeBool(mgr, "setBackLightState",
                        BRIGHTNESS_ID_BACKLIGHT_HOME, light));
        log(out, "setBrightnessState(BACKLIGHT, " + lightLabel + ")="
                + invokeInt(mgr, "setBrightnessState",
                        BRIGHTNESS_ID_BACKLIGHT, light));
        log(out, "setDisplayPowerState(IVI=1, " + powerLabel + ")="
                + invokeBool(mgr, "setDisplayPowerState",
                        DISPLAYPANEL_CENTRAL_IVI, power));
        log(out, "setDisplayLvdsLinkState(IVI=1, " + lvds + ")="
                + invokeBool(mgr, "setDisplayLvdsLinkState",
                        DISPLAYPANEL_CENTRAL_IVI, lvds));
        try { Thread.sleep(400); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        snapshot(mgr, out);
    }

    private static void snapshot(Object mgr, StringBuilder out) {
        log(out, "getBackLightState=" + invokeInt(mgr, "getBackLightState"));
        log(out, "getBrightness(BACKLIGHT)=" + invokeInt(mgr, "getBrightness", BRIGHTNESS_ID_BACKLIGHT));
        log(out, "getBrightnessState(BACKLIGHT)="
                + invokeInt(mgr, "getBrightnessState", BRIGHTNESS_ID_BACKLIGHT));
        log(out, "getBrightnessState(HOME)="
                + invokeInt(mgr, "getBrightnessState", BRIGHTNESS_ID_BACKLIGHT_HOME));
        log(out, "getDisplayInfo(IVI)=" + invokeInt(mgr, "getDisplayInfo", DISPLAYPANEL_CENTRAL_IVI));
        try {
            Method m = mgr.getClass().getMethod("getCurrentWorkMode");
            Object wm = m.invoke(mgr);
            log(out, "getCurrentWorkMode=" + wm);
        } catch (Throwable t) {
            log(out, "getCurrentWorkMode ERR " + t.getMessage());
        }
    }

    private static void waitUntilConnected(Object mgr, StringBuilder out) {
        for (int i = 0; i < 30; i++) {
            Object connected = readField(mgr, "mIsConnected");
            Object binder = readField(mgr, "mICarPowerBinder");
            if (Boolean.TRUE.equals(connected) || binder != null) {
                log(out, "connected after " + (i * 100) + "ms mIsConnected=" + connected
                        + " binder=" + (binder != null));
                return;
            }
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log(out, "still not connected after 3s mIsConnected="
                + readField(mgr, "mIsConnected")
                + " binder=" + (readField(mgr, "mICarPowerBinder") != null));
    }

    private static void dumpConnection(Object mgr, StringBuilder out) {
        Class<?> c = mgr.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName();
                if (n.contains("onnect") || n.contains("inder") || n.contains("Service")
                        || n.equals("mIsConnected")) {
                    log(out, "field " + c.getSimpleName() + "." + n + "=" + readField(mgr, n));
                }
            }
            c = c.getSuperclass();
        }
    }

    private static Object readField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return t.getClass().getSimpleName();
            }
        }
        return null;
    }

    private static String invokeBool(Object mgr, String name, int a, int b) {
        try {
            Method m = mgr.getClass().getMethod(name, int.class, int.class);
            Object r = m.invoke(mgr, a, b);
            return String.valueOf(r);
        } catch (Throwable t) {
            return "ERR " + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    private static String invokeInt(Object mgr, String name, int... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            Object[] boxed = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = int.class;
                boxed[i] = args[i];
            }
            Method m = mgr.getClass().getMethod(name, types);
            Object r = m.invoke(mgr, boxed);
            return String.valueOf(r);
        } catch (Throwable t) {
            return "ERR " + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == void.class || t == Void.class) return null;
        return null;
    }

    private static void log(StringBuilder out, String line) {
        if (out.length() > 0) out.append(" | ");
        out.append(line);
        Log.i(TAG, line);
    }
}
