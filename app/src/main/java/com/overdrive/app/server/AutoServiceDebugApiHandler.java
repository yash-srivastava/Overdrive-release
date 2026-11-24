package com.overdrive.app.server;

import com.overdrive.app.byd.AutoServiceBridge;
import com.overdrive.app.byd.AutoServiceProtocol;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Debug-only sweep tool for the BYD <code>autoservice</code> AIDL surface.
 *
 * <p>The bridge writes the {@code android.gui.BYDAutoServer} interface token
 * before each transact, which {@code service call} can't do — meaning even
 * shell-uid HTTP probes via this endpoint yield real data where raw
 * {@code service call} returns INVALID_VALUE for everything.
 *
 * <p>Endpoints (all GET, JSON):
 * <ul>
 *   <li>{@code /api/debug/autoservice/status} — connection status, useShellCall mode</li>
 *   <li>{@code /api/debug/autoservice/get-int?area=N&cmd=M} — single getInt probe</li>
 *   <li>{@code /api/debug/autoservice/get-buffer?area=N&cmd=M} — single getBuffer probe (hex + ASCII)</li>
 *   <li>{@code /api/debug/autoservice/sweep?from=N&to=M&cmd=K} — getInt across an area range, returns
 *       only non-INVALID values</li>
 *   <li>{@code /api/debug/autoservice/known} — read every signal from BYDAutoFeatureIds + the door/window cmds</li>
 * </ul>
 *
 * <p>Read-only. Never invokes {@code remoteControl} or {@code sendCanViaService}
 * — those need a separate, gated endpoint.
 */
public final class AutoServiceDebugApiHandler {
    private static final String TAG = "AutoServiceDebug";
    private static final int SWEEP_MAX_RANGE = 4096;

    interface Backend {
        AutoServiceProtocol.ModeSnapshot currentMode();
        AutoServiceBridge.DiagnosticStatus status(AutoServiceProtocol.ModeSnapshot expected);
        AutoServiceBridge.DiLink5Read typedRead(
                AutoServiceProtocol.ModeSnapshot expected,
                int transaction,
                int device,
                int featureId);
        int getInt(AutoServiceProtocol.ModeSnapshot expected, int area, int command);
        byte[] getBuffer(AutoServiceProtocol.ModeSnapshot expected, int area, int command);
        int remoteControl(AutoServiceProtocol.ModeSnapshot expected, int opcode);
        boolean isConnected(AutoServiceProtocol.ModeSnapshot expected);
        boolean isUsingShellCall(AutoServiceProtocol.ModeSnapshot expected);
    }

    interface DirectAccess {
        Object collector();
        Class<?> findClass(String name) throws ClassNotFoundException;
        Object fieldValue(java.lang.reflect.Field field, Object target) throws Exception;
        Object multimediaDevice(Object collector);
        Object invoke(java.lang.reflect.Method method, Object target, Object... args) throws Exception;
        Object speedLimitWarning(Object collector);
    }

    private static final DirectAccess SYSTEM_DIRECT_ACCESS = new DirectAccess() {
        @Override public Object collector() {
            return com.overdrive.app.byd.BydDataCollector.getInstance();
        }

        @Override public Class<?> findClass(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        @Override public Object fieldValue(java.lang.reflect.Field field, Object target)
                throws Exception {
            return field.get(target);
        }

        @Override public Object multimediaDevice(Object collector) {
            return ((com.overdrive.app.byd.BydDataCollector) collector).getMultimediaDevice();
        }

        @Override public Object invoke(
                java.lang.reflect.Method method, Object target, Object... args) throws Exception {
            return method.invoke(target, args);
        }

        @Override public Object speedLimitWarning(Object collector) {
            return ((com.overdrive.app.byd.BydDataCollector) collector)
                    .dumpSpeedLimitWarningRaw();
        }
    };

    private static final class ModeFence {
        private final Backend backend;
        private final AutoServiceProtocol.ModeSnapshot expected;
        private final String path;

        ModeFence(
                Backend backend,
                AutoServiceProtocol.ModeSnapshot expected,
                String path) {
            this.backend = backend;
            this.expected = expected;
            this.path = path;
        }

        boolean rejectIfChanged(OutputStream out) throws Exception {
            if (expected.equals(backend.currentMode())) return false;
            handleUnsupported(out, path, "active vehicle mode changed during request");
            return true;
        }
    }

    private static final Backend SYSTEM_BACKEND = new Backend() {
        @Override public AutoServiceProtocol.ModeSnapshot currentMode() {
            DiLink5Platform.ActiveModeSnapshot value = DiLink5Platform.currentActiveModeSnapshot();
            return value == null ? null
                    : new AutoServiceProtocol.ModeSnapshot(value.mode, value.generation);
        }

        @Override public AutoServiceBridge.DiagnosticStatus status(
                AutoServiceProtocol.ModeSnapshot expected) {
            return AutoServiceBridge.INSTANCE.diagnosticStatus(expected);
        }

        @Override public AutoServiceBridge.DiLink5Read typedRead(
                AutoServiceProtocol.ModeSnapshot expected,
                int transaction,
                int device,
                int featureId) {
            return AutoServiceBridge.INSTANCE.readDiLink5(
                    expected, transaction, device, featureId);
        }

        @Override public int getInt(
                AutoServiceProtocol.ModeSnapshot expected, int area, int command) {
            return AutoServiceBridge.INSTANCE.getInt(expected, area, command);
        }

        @Override public byte[] getBuffer(
                AutoServiceProtocol.ModeSnapshot expected, int area, int command) {
            return AutoServiceBridge.INSTANCE.getBuffer(expected, area, command);
        }

        @Override public int remoteControl(
                AutoServiceProtocol.ModeSnapshot expected, int opcode) {
            return AutoServiceBridge.INSTANCE.remoteControl(expected, opcode);
        }

        @Override public boolean isConnected(AutoServiceProtocol.ModeSnapshot expected) {
            return AutoServiceBridge.INSTANCE.isConnected(expected);
        }

        @Override public boolean isUsingShellCall(AutoServiceProtocol.ModeSnapshot expected) {
            return AutoServiceBridge.INSTANCE.isUsingShellCall(expected);
        }
    };

    private AutoServiceDebugApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        return handle(method, path, body, out, SYSTEM_BACKEND);
    }

    static boolean handle(
            String method, String path, String body, OutputStream out, Backend backend) throws Exception {
        return handle(method, path, body, out, backend, SYSTEM_DIRECT_ACCESS);
    }

    static boolean handle(
            String method,
            String path,
            String body,
            OutputStream out,
            Backend backend,
            DirectAccess directAccess) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

        String pathOnly = path;
        Map<String, String> q = new LinkedHashMap<>();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            pathOnly = path.substring(0, qIdx);
            for (String pair : path.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        AutoServiceProtocol.ModeSnapshot mode = backend.currentMode();
        if (mode == null) {
            return handleUnsupported(out, pathOnly, "active vehicle mode is unavailable");
        }
        if (mode.isDiLink5() && !DiLink5SdkDiagnosticRegistry.isEndpointAllowed(pathOnly)) {
            return handleUnsupported(out, pathOnly, "endpoint is unavailable in the active vehicle mode");
        }
        ModeFence modeFence = new ModeFence(backend, mode, pathOnly);
        if (modeFence.rejectIfChanged(out)) return true;

        if (pathOnly.equals("/api/debug/autoservice/status")) {
            return handleStatus(out, backend, mode);
        }
        if (pathOnly.equals("/api/debug/autoservice/typed-int")) {
            return handleTypedRead(out, q, backend, mode, false);
        }
        if (pathOnly.equals("/api/debug/autoservice/typed-float")) {
            return handleTypedRead(out, q, backend, mode, true);
        }
        if (pathOnly.equals("/api/debug/autoservice/get-int")) {
            return handleGetInt(out, q, backend, mode);
        }
        if (pathOnly.equals("/api/debug/autoservice/get-buffer")) {
            return handleGetBuffer(out, q, backend, mode);
        }
        if (pathOnly.equals("/api/debug/autoservice/sweep")) {
            return handleSweep(out, q, backend, mode);
        }
        if (pathOnly.equals("/api/debug/autoservice/known")) {
            return handleKnown(out, backend, mode);
        }
        if (pathOnly.equals("/api/debug/autoservice/remote-control")) {
            if (!StepUpGate.carOnly(out)) return true;
            return handleRemoteControl(out, q, backend, mode);
        }
        if (pathOnly.equals("/api/debug/autoservice/probe-methods")) {
            return handleProbeMethods(out, modeFence, directAccess);
        }
        if (pathOnly.equals("/api/debug/autoservice/sdk-remote-control")) {
            if (!StepUpGate.carOnly(out)) return true;
            return handleSdkRemoteControl(out, q, modeFence, directAccess);
        }
        if (pathOnly.equals("/api/debug/autoservice/sdk-getter")) {
            if (!StepUpGate.carOnly(out)) return true;
            return handleSdkGetter(out, q, modeFence, directAccess);
        }
        if (pathOnly.equals("/api/debug/autoservice/probe-getters")) {
            if (!StepUpGate.carOnly(out)) return true;
            return handleProbeGetters(out, q, modeFence, directAccess);
        }
        if (pathOnly.equals("/api/debug/autoservice/list-setters")) {
            return handleListSetters(out, modeFence, directAccess);
        }
        if (pathOnly.equals("/api/debug/autoservice/adas-slw")) {
            if (modeFence.rejectIfChanged(out)) return true;
            Object collector = directAccess.collector();
            if (modeFence.rejectIfChanged(out)) return true;
            HttpResponse.sendJson(out, directAccess.speedLimitWarning(collector).toString());
            return true;
        }

        HttpResponse.sendError(out, 404, "Unknown autoservice debug endpoint");
        return true;
    }

    /**
     * Fires a remote-control opcode through tx=40. Write surface — gated by
     * the {@code confirm=YES} query param to discourage accidental triggering.
     *
     * <p>Opcodes (from {@link AutoServiceBridge}):
     * 5=UNLOCK, 6=TRUNK_OPEN, 7=LOCK, 9=POWER_OFF, 17=CLOSE_WINDOWS, 22=FLASH_LIGHTS.
     */
    private static boolean handleRemoteControl(
            OutputStream out,
            Map<String, String> q,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected) throws Exception {
        Integer opcode = parseIntParam(q, "opcode");
        if (opcode == null) {
            HttpResponse.sendJsonError(out, "Missing 'opcode' parameter (decimal or 0x-hex)");
            return true;
        }
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out,
                "Refusing to fire remote-control opcode " + opcode
                + " without confirm=YES query param");
            return true;
        }

        long t0 = System.nanoTime();
        int reply = backend.remoteControl(expected, opcode);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000;

        JSONObject r = new JSONObject();
        r.put("opcode", opcode);
        r.put("opcodeName", opcodeName(opcode));
        r.put("reply", reply);
        r.put("replyHex", "0x" + String.format(Locale.ROOT, "%08X", reply));
        r.put("isInvalid", reply == AutoServiceBridge.INVALID_VALUE);
        r.put("isError", reply == AutoServiceBridge.ERROR_NO_BINDER
                || reply == AutoServiceBridge.ERROR_TRANSACT_FALSE
                || reply == AutoServiceBridge.ERROR_TRANSACT_EXC
                || reply == AutoServiceBridge.ERROR_SHELL_CALL
                || reply == AutoServiceBridge.ERROR_UNSUPPORTED_MODE);
        r.put("latencyMs", latencyMs);
        HttpResponse.sendJson(out, r.toString());
        try {
            CameraDaemon.log(TAG + ": remoteControl opcode=" + opcode + " (" + opcodeName(opcode)
                    + ") -> reply=" + reply + " in " + latencyMs + "ms");
        } catch (Throwable ignore) {}
        return true;
    }

    /**
     * Probes whether the runtime SDK device classes ({@code BYDAutoSettingDevice},
     * {@code BYDAutoBodyworkDevice}) expose known remote-control methods. These
     * methods are not in our local stub; they live in the platform framework
     * jar at runtime, accessible only via reflection on the device instance.
     *
     * <p>Returns: which classes exist, what {@code (int)}-arg methods they expose,
     * and whether the canonical names ({@code voiceCtlLockCar},
     * {@code remoteControlCar}, {@code setRemoteControl}) are present.
     */
    private static boolean handleProbeMethods(
            OutputStream out,
            ModeFence modeFence,
            DirectAccess directAccess) throws Exception {
        JSONObject r = new JSONObject();

        String[] candidates = {
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
        };
        String[] interestingMethods = {
            "voiceCtlLockCar", "remoteControlCar", "setRemoteControl",
            "lockCar", "unlockCar", "openTrunk", "flashLights", "setFlashLight",
            "remoteControl", "setMoonRoofState", "setAutoExternalRearMirrorFollowUpSwitch"
        };

        for (String className : candidates) {
            JSONObject classInfo = new JSONObject();
            try {
                if (modeFence.rejectIfChanged(out)) return true;
                Class<?> cls = directAccess.findClass(className);
                classInfo.put("found", true);
                JSONArray declared = new JSONArray();
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    JSONObject mInfo = new JSONObject();
                    mInfo.put("name", m.getName());
                    mInfo.put("returnType", m.getReturnType().getSimpleName());
                    JSONArray pTypes = new JSONArray();
                    for (Class<?> p : params) pTypes.put(p.getSimpleName());
                    mInfo.put("params", pTypes);
                    declared.put(mInfo);
                }
                classInfo.put("declaredMethods", declared);

                JSONObject hits = new JSONObject();
                for (String mName : interestingMethods) {
                    JSONArray sigs = new JSONArray();
                    for (java.lang.reflect.Method m : cls.getMethods()) {
                        if (m.getName().equals(mName)) {
                            JSONArray pTypes = new JSONArray();
                            for (Class<?> p : m.getParameterTypes()) pTypes.put(p.getSimpleName());
                            JSONObject sig = new JSONObject();
                            sig.put("returnType", m.getReturnType().getSimpleName());
                            sig.put("params", pTypes);
                            sigs.put(sig);
                        }
                    }
                    if (sigs.length() > 0) hits.put(mName, sigs);
                }
                classInfo.put("interestingMethods", hits);
            } catch (ClassNotFoundException e) {
                classInfo.put("found", false);
                classInfo.put("error", e.getMessage());
            }
            r.put(className, classInfo);
        }

        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /**
     * Calls one of the SDK device classes' remote-control methods reflectively
     * on the live device instance held by {@code BydDataCollector}.
     * The runtime framework may expose a method like
     * {@code voiceCtlLockCar(int)} on the {@code SettingDevice} object that
     * the BYD framework hands them.
     *
     * <p>Caller picks {@code class=Setting|Bodywork}, {@code method=name},
     * {@code arg=opcode}. Gated by {@code confirm=YES}.
     */
    private static boolean handleSdkRemoteControl(
            OutputStream out,
            Map<String, String> q,
            ModeFence modeFence,
            DirectAccess directAccess) throws Exception {
        String klass = q.getOrDefault("class", "Setting");
        String methodName = q.get("method");
        Integer arg = parseIntParam(q, "arg");
        if (methodName == null || arg == null) {
            HttpResponse.sendJsonError(out, "Need 'method' and 'arg' params");
            return true;
        }
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing without confirm=YES");
            return true;
        }

        if (modeFence.rejectIfChanged(out)) return true;
        Object collector = directAccess.collector();
        Object device;
        try {
            java.lang.reflect.Field f;
            if ("Bodywork".equalsIgnoreCase(klass)) {
                f = collector.getClass().getDeclaredField("bodyworkDevice");
            } else {
                f = collector.getClass().getDeclaredField("settingDevice");
            }
            f.setAccessible(true);
            if (modeFence.rejectIfChanged(out)) return true;
            device = directAccess.fieldValue(f, collector);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Device fetch failed: " + e.getMessage());
            return true;
        }
        if (device == null) {
            HttpResponse.sendJsonError(out, klass + " device is null");
            return true;
        }

        JSONObject r = new JSONObject();
        r.put("class", klass);
        r.put("deviceClass", device.getClass().getName());
        r.put("method", methodName);
        r.put("arg", arg);

        try {
            java.lang.reflect.Method m = device.getClass().getMethod(methodName, Integer.TYPE);
            if (modeFence.rejectIfChanged(out)) return true;
            long t0 = System.nanoTime();
            Object result = directAccess.invoke(m, device, arg);
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;
            r.put("ok", true);
            r.put("latencyMs", latencyMs);
            r.put("result", result == null ? JSONObject.NULL : result.toString());
            r.put("returnType", m.getReturnType().getSimpleName());
            CameraDaemon.log(TAG + ": SDK " + klass + "." + methodName + "(" + arg + ") = " + result);
        } catch (NoSuchMethodException e) {
            r.put("ok", false);
            r.put("error", "NoSuchMethod: " + methodName + "(int)");
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", e.getClass().getSimpleName() + ": "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /**
     * Calls a no-arg getter on the SDK device — diagnostic-only, READ path.
     * Lets us probe whether shell-uid daemon can read state that the app-uid
     * collector currently can't (e.g. {@code getDoorLock()}).
     */
    private static boolean handleSdkGetter(
            OutputStream out,
            Map<String, String> q,
            ModeFence modeFence,
            DirectAccess directAccess) throws Exception {
        String klass = q.getOrDefault("class", "Setting");
        String methodName = q.get("method");
        if (methodName == null) {
            HttpResponse.sendJsonError(out, "Need 'method' param");
            return true;
        }

        if (modeFence.rejectIfChanged(out)) return true;
        Object collector = directAccess.collector();
        Object device;
        try {
            // BydDataCollector field names are lowerCamelCase
            // (`otaDevice`, `bodyworkDevice`, …). Caller passes the
            // PascalCase device class shorthand (`Ota`, `Bodywork`) — convert.
            String fname = "Bodywork".equalsIgnoreCase(klass) ? "bodyworkDevice"
                    : "Setting".equalsIgnoreCase(klass) ? "settingDevice"
                    : Character.toLowerCase(klass.charAt(0)) + klass.substring(1) + "Device";
            java.lang.reflect.Field f = collector.getClass().getDeclaredField(fname);
            f.setAccessible(true);
            if (modeFence.rejectIfChanged(out)) return true;
            device = directAccess.fieldValue(f, collector);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Device fetch failed: " + e.getMessage());
            return true;
        }
        if (device == null) {
            HttpResponse.sendJsonError(out, klass + " device is null");
            return true;
        }

        JSONObject r = new JSONObject();
        r.put("class", klass);
        r.put("deviceClass", device.getClass().getName());
        r.put("method", methodName);
        try {
            java.lang.reflect.Method m = device.getClass().getMethod(methodName);
            if (modeFence.rejectIfChanged(out)) return true;
            long t0 = System.nanoTime();
            Object result = directAccess.invoke(m, device);
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;
            r.put("ok", true);
            r.put("latencyMs", latencyMs);
            r.put("result", result == null ? JSONObject.NULL : result.toString());
            r.put("returnType", m.getReturnType().getSimpleName());
        } catch (NoSuchMethodException e) {
            r.put("ok", false);
            r.put("error", "NoSuchMethod: " + methodName + "()");
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", e.getClass().getSimpleName() + ": "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /**
     * Calls every no-arg {@code get*} method on every BYD device the
     * collector has loaded. Returns a categorised JSON of what's readable.
     *
     * <p>Result categories per call:
     * <ul>
     *   <li>{@code live}: returned a non-INVALID, non-error value (real data)</li>
     *   <li>{@code invalid}: returned -10011 (HAL has no cached value — means
     *       the underlying CAN segment is asleep)</li>
     *   <li>{@code threw}: invocation raised; method exists but isn't callable
     *       in this state (security, missing arg, etc.)</li>
     * </ul>
     *
     * <p>Optional query params:
     * <ul>
     *   <li>{@code class=Setting|Bodywork|Tyre|Charging|...} (default: all loaded)</li>
     *   <li>{@code mode=live|all} (default: live — only return non-INVALID hits;
     *       {@code all} dumps every probe regardless of value)</li>
     *   <li>{@code prefix=getX} — filter to method names starting with prefix</li>
     * </ul>
     */
    private static boolean handleProbeGetters(
            OutputStream out,
            Map<String, String> q,
            ModeFence modeFence,
            DirectAccess directAccess) throws Exception {
        String classFilter = q.get("class");      // null = all
        boolean dumpAll = "all".equals(q.get("mode"));
        String prefix = q.getOrDefault("prefix", "get");

        if (modeFence.rejectIfChanged(out)) return true;
        Object collector = directAccess.collector();

        // Walk the collector's reflective device fields. Each Object*Device
        // field on BydDataCollector is a runtime BYD HAL handle.
        Map<String, Object> devices = new LinkedHashMap<>();
        for (java.lang.reflect.Field f : collector.getClass().getDeclaredFields()) {
            String fname = f.getName();
            if (!fname.endsWith("Device")) continue;
            if (fname.equals("multimediaDevice")) continue;  // handled separately
            String label = fname.substring(0, fname.length() - "Device".length());
            label = label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
            if (classFilter != null && !classFilter.equalsIgnoreCase(label)) continue;
            f.setAccessible(true);
            Object dev = null;
            if (modeFence.rejectIfChanged(out)) return true;
            try { dev = directAccess.fieldValue(f, collector); } catch (Exception ignore) {}
            if (dev != null) devices.put(label, dev);
        }

        // Multimedia is a special case
        if (classFilter == null || "Multimedia".equalsIgnoreCase(classFilter)) {
            if (modeFence.rejectIfChanged(out)) return true;
            Object mm = directAccess.multimediaDevice(collector);
            if (mm != null) devices.put("Multimedia", mm);
        }

        JSONObject root = new JSONObject();
        int totalProbed = 0, totalLive = 0, totalInvalid = 0, totalThrew = 0;
        long t0 = System.nanoTime();

        for (Map.Entry<String, Object> e : devices.entrySet()) {
            String label = e.getKey();
            Object dev = e.getValue();
            JSONObject perDevice = new JSONObject();
            perDevice.put("class", dev.getClass().getName());
            JSONObject live = new JSONObject();
            JSONObject invalid = new JSONObject();
            JSONObject threw = new JSONObject();

            // Use Class.getMethods() to include inherited; getDeclaredMethods()
            // misses parent-class getters on some BYD devices.
            for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String name = m.getName();
                if (!name.startsWith(prefix)) continue;
                if (name.equals("getClass") || name.equals("hashCode") || name.equals("toString")) continue;
                Class<?> ret = m.getReturnType();
                if (ret == void.class || ret == Void.class) continue;
                totalProbed++;
                long callT0 = System.nanoTime();
                if (modeFence.rejectIfChanged(out)) return true;
                try {
                    Object v = directAccess.invoke(m, dev);
                    long us = (System.nanoTime() - callT0) / 1000;
                    JSONObject hit = new JSONObject();
                    hit.put("type", ret.getSimpleName());
                    hit.put("us", us);
                    if (v == null) {
                        hit.put("value", JSONObject.NULL);
                        live.put(name, hit);
                        totalLive++;
                    } else if (v instanceof Number) {
                        long lv = ((Number) v).longValue();
                        hit.put("value", v.toString());
                        if (lv == AutoServiceBridge.INVALID_VALUE
                                || lv == AutoServiceBridge.ERROR_NO_BINDER
                                || lv == AutoServiceBridge.ERROR_TRANSACT_FALSE
                                || lv == AutoServiceBridge.ERROR_TRANSACT_EXC) {
                            invalid.put(name, hit);
                            totalInvalid++;
                        } else {
                            live.put(name, hit);
                            totalLive++;
                        }
                    } else if (v instanceof byte[]) {
                        byte[] b = (byte[]) v;
                        hit.put("len", b.length);
                        hit.put("hex", b.length <= 64 ? toHex(b) : toHex(java.util.Arrays.copyOf(b, 64)) + " …");
                        live.put(name, hit);
                        totalLive++;
                    } else {
                        String s = v.toString();
                        hit.put("value", s.length() > 200 ? s.substring(0, 200) + "…" : s);
                        live.put(name, hit);
                        totalLive++;
                    }
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    JSONObject hit = new JSONObject();
                    hit.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    threw.put(name, hit);
                    totalThrew++;
                }
            }

            perDevice.put("live", live);
            if (dumpAll) {
                perDevice.put("invalid", invalid);
                perDevice.put("threw", threw);
            }
            perDevice.put("counts", new JSONObject()
                    .put("live", live.length())
                    .put("invalid", invalid.length())
                    .put("threw", threw.length()));
            root.put(label, perDevice);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        root.put("_summary", new JSONObject()
                .put("totalProbed", totalProbed)
                .put("live", totalLive)
                .put("invalid", totalInvalid)
                .put("threw", totalThrew)
                .put("elapsedMs", elapsedMs));
        HttpResponse.sendJson(out, root.toString());
        try {
            CameraDaemon.log(TAG + ": probe-getters probed=" + totalProbed
                    + " live=" + totalLive + " invalid=" + totalInvalid
                    + " threw=" + totalThrew + " in " + elapsedMs + "ms");
        } catch (Throwable ignore) {}
        return true;
    }

    /**
     * Lists every {@code set*}/{@code voiceCtl*}/{@code factoryReset*} method
     * across every BYD HAL device by category, with parameter signatures.
     * Read-only — does NOT invoke. Use this to hand-pick which controls to
     * test via {@code /sdk-remote-control}.
     */
    private static boolean handleListSetters(
            OutputStream out,
            ModeFence modeFence,
            DirectAccess directAccess) throws Exception {
        if (modeFence.rejectIfChanged(out)) return true;
        Object collector = directAccess.collector();
        JSONObject root = new JSONObject();
        for (java.lang.reflect.Field f : collector.getClass().getDeclaredFields()) {
            String fname = f.getName();
            if (!fname.endsWith("Device")) continue;
            String label = fname.substring(0, fname.length() - "Device".length());
            label = label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
            f.setAccessible(true);
            Object dev = null;
            if (modeFence.rejectIfChanged(out)) return true;
            try { dev = directAccess.fieldValue(f, collector); } catch (Exception ignore) {}
            if (dev == null) continue;

            JSONObject perDevice = new JSONObject();
            perDevice.put("class", dev.getClass().getName());
            JSONArray setters = new JSONArray();
            for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                String name = m.getName();
                boolean interesting = name.startsWith("set")
                        || name.startsWith("voiceCtl")
                        || name.startsWith("factoryReset")
                        || name.startsWith("send")
                        || name.startsWith("force");
                if (!interesting) continue;
                if (name.equals("setAccessible")) continue;
                JSONObject sig = new JSONObject();
                sig.put("name", name);
                JSONArray params = new JSONArray();
                for (Class<?> p : m.getParameterTypes()) params.put(p.getSimpleName());
                sig.put("params", params);
                sig.put("returnType", m.getReturnType().getSimpleName());
                setters.put(sig);
            }
            perDevice.put("setters", setters);
            perDevice.put("count", setters.length());
            root.put(label, perDevice);
        }
        HttpResponse.sendJson(out, root.toString());
        return true;
    }

    private static String opcodeName(int opcode) {
        switch (opcode) {
            case AutoServiceBridge.OP_UNLOCK: return "UNLOCK";
            case AutoServiceBridge.OP_TRUNK_OPEN: return "TRUNK_OPEN";
            case AutoServiceBridge.OP_LOCK: return "LOCK";
            case AutoServiceBridge.OP_POWER_OFF: return "POWER_OFF";
            case AutoServiceBridge.OP_CLOSE_WINDOWS: return "CLOSE_WINDOWS";
            case AutoServiceBridge.OP_FLASH_LIGHTS: return "FLASH_LIGHTS";
            default: return "OPCODE_" + opcode;
        }
    }

    private static boolean handleUnsupported(
            OutputStream out, String path, String reason) throws Exception {
        JSONObject response = new JSONObject();
        response.put("supported", false);
        response.put("path", path);
        response.put("reason", reason);
        HttpResponse.sendJson(out, response.toString());
        return true;
    }

    private static boolean handleStatus(
            OutputStream out,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected) throws Exception {
        AutoServiceBridge.DiagnosticStatus status = backend.status(expected);
        JSONObject response = new JSONObject();
        response.put("mode", status.mode == null ? JSONObject.NULL : status.mode);
        response.put("generation", status.generation == null ? JSONObject.NULL : status.generation);
        response.put("configuredTransport", status.configuredTransport);
        response.put("connected", status.connected);
        response.put("dispatchAvailable", status.dispatchAvailable);
        response.put("readable", status.readable == null ? JSONObject.NULL : status.readable);
        response.put("useShellCall", "shell".equals(status.configuredTransport));
        if (expected.getSupportsLegacy()) {
            response.put("interfaceToken", AutoServiceProtocol.LEGACY_INTERFACE_TOKEN);
        }
        HttpResponse.sendJson(out, response.toString());
        return true;
    }

    private static boolean handleTypedRead(
            OutputStream out,
            Map<String, String> query,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected,
            boolean floatingPoint) throws Exception {
        Integer device = parseIntParam(query, "device");
        Integer featureId = parseIntParam(query, "fid");
        if (device == null || featureId == null) {
            HttpResponse.sendJsonError(out, "Need valid 'device' and 'fid' parameters");
            return true;
        }

        String minimumText = query.get("min");
        String maximumText = query.get("max");
        if ((minimumText == null) != (maximumText == null)) {
            HttpResponse.sendJsonError(out, "Provide both 'min' and 'max', or neither");
            return true;
        }

        Integer integerMinimum = null;
        Integer integerMaximum = null;
        Float floatMinimum = null;
        Float floatMaximum = null;
        if (minimumText != null) {
            if (floatingPoint) {
                floatMinimum = parseFiniteFloat(minimumText);
                floatMaximum = parseFiniteFloat(maximumText);
                if (floatMinimum == null || floatMaximum == null || floatMinimum > floatMaximum) {
                    HttpResponse.sendJsonError(out, "Invalid floating-point plausibility range");
                    return true;
                }
            } else {
                integerMinimum = parseInt(minimumText);
                integerMaximum = parseInt(maximumText);
                if (integerMinimum == null || integerMaximum == null
                        || integerMinimum > integerMaximum) {
                    HttpResponse.sendJsonError(out, "Invalid integer plausibility range");
                    return true;
                }
            }
        }

        int transaction = floatingPoint
                ? AutoServiceProtocol.TX_TYPED_FLOAT
                : AutoServiceProtocol.TX_TYPED_INT;
        AutoServiceBridge.DiLink5Read raw = backend.typedRead(
                expected, transaction, device, featureId);
        JSONObject response = new JSONObject();
        response.put("device", device);
        response.put("fid", featureId);
        response.put("transaction", transaction);
        response.put("type", floatingPoint ? "float" : "int");
        if (raw == null) {
            response.put("transportReply", false);
            response.put("readable", false);
            response.put("plausible", JSONObject.NULL);
            HttpResponse.sendJson(out, response.toString());
            return true;
        }

        AutoServiceProtocol.RawReply reply = new AutoServiceProtocol.RawReply(
                java.util.Arrays.asList(raw.status, raw.valueBits), null, null);
        AutoServiceProtocol.TypedRead decoded =
                AutoServiceProtocol.INSTANCE.decodeTyped(reply, floatingPoint);
        response.put("transportReply", true);
        response.put("status", raw.status);
        response.put("statusOk", raw.status == 0);
        response.put("valueBits", raw.valueBits);
        response.put("valueHex", "0x" + String.format(Locale.ROOT, "%08X", raw.valueBits));
        response.put("sentinel", decoded != null && decoded.getSentinel());
        response.put("readable", decoded != null && decoded.getReadable());
        if (floatingPoint) {
            Float value = decoded == null ? null : decoded.getFloatValue();
            response.put("value", value == null ? JSONObject.NULL : value);
            Boolean plausible = floatMinimum == null ? null
                    : AutoServiceProtocol.INSTANCE.plausibleFloat(
                            decoded, floatMinimum, floatMaximum);
            response.put("plausible", plausible == null ? JSONObject.NULL : plausible);
        } else {
            Integer value = decoded == null ? null : decoded.getIntegerValue();
            response.put("value", value == null ? JSONObject.NULL : value);
            Boolean plausible = integerMinimum == null ? null
                    : AutoServiceProtocol.INSTANCE.plausibleInteger(
                            decoded, integerMinimum, integerMaximum);
            response.put("plausible", plausible == null ? JSONObject.NULL : plausible);
        }
        HttpResponse.sendJson(out, response.toString());
        return true;
    }

    private static boolean handleGetInt(
            OutputStream out,
            Map<String, String> q,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected) throws Exception {
        Integer area = parseIntParam(q, "area");
        Integer cmd = parseIntParam(q, "cmd");
        if (area == null) {
            HttpResponse.sendJsonError(out, "Missing or invalid 'area' parameter (decimal or 0x-hex)");
            return true;
        }
        if (cmd == null) cmd = 0;

        long t0 = System.nanoTime();
        int value = backend.getInt(expected, area, cmd);
        long latencyMicros = (System.nanoTime() - t0) / 1000;

        JSONObject r = new JSONObject();
        r.put("area", area);
        r.put("areaHex", "0x" + Integer.toHexString(area));
        r.put("cmd", cmd);
        r.put("value", value);
        r.put("valueHex", "0x" + String.format(Locale.ROOT, "%08X", value));
        r.put("isInvalid", value == AutoServiceBridge.INVALID_VALUE);
        r.put("isError", value == AutoServiceBridge.ERROR_NO_BINDER
                || value == AutoServiceBridge.ERROR_TRANSACT_FALSE
                || value == AutoServiceBridge.ERROR_TRANSACT_EXC
                || value == AutoServiceBridge.ERROR_SHELL_CALL
                || value == AutoServiceBridge.ERROR_UNSUPPORTED_MODE);
        r.put("latencyMicros", latencyMicros);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    private static boolean handleGetBuffer(
            OutputStream out,
            Map<String, String> q,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected) throws Exception {
        Integer area = parseIntParam(q, "area");
        Integer cmd = parseIntParam(q, "cmd");
        if (area == null) {
            HttpResponse.sendJsonError(out, "Missing or invalid 'area' parameter (decimal or 0x-hex)");
            return true;
        }
        if (cmd == null) cmd = 0;

        long t0 = System.nanoTime();
        byte[] bytes = backend.getBuffer(expected, area, cmd);
        long latencyMicros = (System.nanoTime() - t0) / 1000;

        JSONObject r = new JSONObject();
        r.put("area", area);
        r.put("areaHex", "0x" + Integer.toHexString(area));
        r.put("cmd", cmd);
        r.put("latencyMicros", latencyMicros);
        if (bytes == null) {
            r.put("present", false);
        } else {
            r.put("present", true);
            r.put("length", bytes.length);
            r.put("hex", toHex(bytes));
            r.put("ascii", toAsciiPreview(bytes));
        }
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    private static boolean handleSweep(
            OutputStream out,
            Map<String, String> q,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected) throws Exception {
        Integer from = parseIntParam(q, "from");
        Integer to = parseIntParam(q, "to");
        Integer cmd = parseIntParam(q, "cmd");
        if (from == null || to == null) {
            HttpResponse.sendJsonError(out, "Need 'from' and 'to' (decimal or 0x-hex)");
            return true;
        }
        if (cmd == null) cmd = 0;
        if (to < from) { int t = to; to = from; from = t; }
        if ((long) to - from > SWEEP_MAX_RANGE) {
            HttpResponse.sendJsonError(out, "Range too large (max " + SWEEP_MAX_RANGE + ")");
            return true;
        }

        JSONArray hits = new JSONArray();
        int totalProbed = 0;
        long t0 = System.nanoTime();
        for (long cursor = from; cursor <= to; cursor++) {
            int area = (int) cursor;
            int v = backend.getInt(expected, area, cmd);
            totalProbed++;
            if (v == AutoServiceBridge.INVALID_VALUE) continue;
            if (v == AutoServiceBridge.ERROR_NO_BINDER
                    || v == AutoServiceBridge.ERROR_TRANSACT_FALSE
                    || v == AutoServiceBridge.ERROR_TRANSACT_EXC
                    || v == AutoServiceBridge.ERROR_SHELL_CALL
                    || v == AutoServiceBridge.ERROR_UNSUPPORTED_MODE) continue;
            JSONObject hit = new JSONObject();
            hit.put("area", area);
            hit.put("areaHex", "0x" + Integer.toHexString(area));
            hit.put("value", v);
            hit.put("valueHex", "0x" + String.format(Locale.ROOT, "%08X", v));
            hits.put(hit);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        JSONObject r = new JSONObject();
        r.put("from", from);
        r.put("to", to);
        r.put("cmd", cmd);
        r.put("totalProbed", totalProbed);
        r.put("hits", hits);
        r.put("hitCount", hits.length());
        r.put("elapsedMs", elapsedMs);
        HttpResponse.sendJson(out, r.toString());
        try {
            CameraDaemon.log(TAG + ": sweep [" + from + ".." + to + "] cmd=" + cmd
                    + " hits=" + hits.length() + "/" + totalProbed + " in " + elapsedMs + "ms");
        } catch (Throwable ignore) {}
        return true;
    }

    /**
     * Reads every signal we have a name for, plus all 7 door states and all 4 windows,
     * so the operator gets a one-shot snapshot of what the bridge can actually fetch.
     */
    private static boolean handleKnown(
            OutputStream out,
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected) throws Exception {
        JSONObject r = new JSONObject();

        // Door cmd ids 1..7: the feature key is the area and cmd selects the door.
        JSONObject doors = new JSONObject();
        String[] doorNames = {"left_front", "right_front", "left_rear", "right_rear", "hood", "trunk", "fuel_cap"};
        for (int i = 0; i < doorNames.length; i++) {
            int cmd = i + 1;     // BODYWORK_CMD_DOOR_LEFT_FRONT=1 .. _FUEL_TANK_CAP=7
            int v = backend.getInt(expected, 0, cmd);
            doors.put(doorNames[i], doorJson(0, cmd, v));
        }
        r.put("doors", doors);

        JSONObject windows = new JSONObject();
        String[] windowNames = {"left_front", "right_front", "left_rear", "right_rear"};
        for (int i = 0; i < windowNames.length; i++) {
            int cmd = i + 1;
            int v = backend.getInt(expected, 1, cmd);
            windows.put(windowNames[i], doorJson(1, cmd, v));
        }
        r.put("windows", windows);

        // Named feature IDs from BYDAutoFeatureIds
        JSONObject features = new JSONObject();
        addFeature(backend, expected, features, "STATISTIC_TOTAL_MILEAGE", 4096);
        addFeature(backend, expected, features, "ENGINE_REAR_MOTOR_SPEED", 4097);
        addFeature(backend, expected, features, "ENGINE_FRONT_MOTOR_SPEED", 4098);
        addFeature(backend, expected, features, "INSTRUMENT_DD_MILEAGE_UNIT", 4099);
        addFeature(backend, expected, features, "SPEED_ACCELERATOR_S", 4100);
        addFeature(backend, expected, features, "SPEED_BRAKE_S", 4101);
        addFeature(backend, expected, features, "STATISTIC_FUEL_PERCENTAGE", 4102);
        addFeature(backend, expected, features, "STATISTIC_MILEAGE_EV", 4103);
        addFeature(backend, expected, features, "STATISTIC_MILEAGE_HEV", 4104);
        addFeature(backend, expected, features, "ENGINE_SPEED", 4105);
        addFeature(backend, expected, features, "ENGINE_POWER", 4106);
        addFeature(backend, expected, features, "CHARGING_DISCHARGE_VEHICLE_OUTPUT_VOLTAGE", 4112);
        addFeature(backend, expected, features, "BODYWORK_EMERGENCY_ALARM_STATE", 0);
        addFeature(backend, expected, features, "STATISTIC_HIGHEST_BATTERY_TEMP", 1148190752);
        addFeature(backend, expected, features, "STATISTIC_AVERAGE_BATTERY_TEMP", 1148190776);
        addFeature(backend, expected, features, "STATISTIC_LOWEST_BATTERY_TEMP", 1148190736);
        addFeature(backend, expected, features, "STATISTIC_HIGHEST_BATTERY_VOLTAGE", 1147142192);
        addFeature(backend, expected, features, "STATISTIC_LOWEST_BATTERY_VOLTAGE", 1147142160);
        addFeature(backend, expected, features, "SET_LF_MEMORY_LOCATION_WAKE_SET", 8192);
        r.put("features", features);

        r.put("connected", backend.isConnected(expected));
        r.put("useShellCall", backend.isUsingShellCall(expected));
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static JSONObject doorJson(int area, int cmd, int v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("area", area);
        o.put("cmd", cmd);
        o.put("value", v);
        o.put("valueHex", "0x" + String.format(Locale.ROOT, "%08X", v));
        if (v == AutoServiceBridge.INVALID_VALUE) o.put("status", "INVALID");
        else if (v == 0) o.put("status", "CLOSED");
        else if (v == 1) o.put("status", "OPEN");
        else if (v == 255) o.put("status", "UNDEFINED");
        else o.put("status", "RAW");
        return o;
    }

    private static void addFeature(
            Backend backend,
            AutoServiceProtocol.ModeSnapshot expected,
            JSONObject features,
            String name,
            int id) throws Exception {
        int v = backend.getInt(expected, id, 0);
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("idHex", "0x" + Integer.toHexString(id));
        o.put("value", v);
        o.put("valueHex", "0x" + String.format(Locale.ROOT, "%08X", v));
        o.put("isInvalid", v == AutoServiceBridge.INVALID_VALUE);
        features.put(name, o);
    }

    private static Integer parseIntParam(Map<String, String> q, String key) {
        return parseInt(q.get(key));
    }

    private static Integer parseInt(String value) {
        if (value == null) return null;
        String s = value.trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                long bits = Long.parseLong(s.substring(2), 16);
                if (bits < 0L || bits > 0xffffffffL) return null;
                return (int) bits;
            }
            long parsed = Long.parseLong(s);
            if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) return null;
            return (int) parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Float parseFiniteFloat(String value) {
        if (value == null) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String toAsciiPreview(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            sb.append((c >= 0x20 && c < 0x7F) ? (char) c : '.');
        }
        return sb.toString();
    }
}
