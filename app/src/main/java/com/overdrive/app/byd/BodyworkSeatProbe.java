package com.overdrive.app.byd;

import android.content.Context;
import android.content.ContextWrapper;

import com.overdrive.app.byd.routing.DrivingSafetyGuard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * READ-ONLY probe for {@code BYDAutoBodyworkDevice} feature-id getters — the
 * absolute seat/steering geometry (System B, the profile/account positions the
 * DiLink app applies) and the keyfob-identity ids.
 *
 * <p>WHY a new endpoint. Neither existing reach worked for these ids:
 * <ul>
 *   <li>the CarProperty bridge ({@code /api/debug/car-property/get}) goes through
 *       DiCarServer's {@code ICarPropertyService}, which enforces the read
 *       permission and returns statusCode 20004 "Permission not granted for
 *       android.permission.BYDAUTO_BODYWORK_GET" with no permissive bypass on
 *       that path;</li>
 *   <li>the {@code sdk-getter} path only invokes no-arg methods, so a feature id
 *       can't be passed.</li>
 * </ul>
 * The lever that DOES reach them is the same one the light/hazard write probe
 * uses ({@link HazardLightProbe}): obtain the device singleton, swap a
 * {@link PermissiveContext} into its cached {@code mContext} so the in-process
 * gate-3 permission check is neutralised, then call the generic
 * {@code get(int[], Class)} through {@link BydDeviceHelper}.
 *
 * <p>NOTHING here writes. There is no {@code set()} call and no {@code confirm}
 * gate because there is nothing to actuate — every method reads. The seat does
 * not move.
 *
 * <p>Feature ids are from the sub-41 decompile of CarSetting.apk (see
 * {@code byd-projects} KB {@code interfaces.md} / {@code dead-ends.md}). Read ids
 * differ from the write ids for the same axis; only READ ids belong here.
 */
public final class BodyworkSeatProbe {

    public static final String BODYWORK_DEVICE =
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";

    /**
     * Stands in for "set() gave us no result code" (void setter, or a non-numeric return).
     * Deliberately not 0: 0 is the HAL's success code, so defaulting to it turned an
     * unconfirmable write into a confirmed one.
     */
    private static final int UNKNOWN_CODE = Integer.MIN_VALUE;

    /** Not-equipped filler the HAL ignores; also what an unreadable axis is stored as. */
    private static final float SENTINEL = 127.5f;

    /** Axis values live in 0..127.5; anything else is an error code or a rail, not a position. */
    private static final float AXIS_MAX = 127.5f;

    /** True for a value that is safe to command an axis to. */
    private static boolean inRange(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v) && v >= 0f && v <= AXIS_MAX;
    }

    private BodyworkSeatProbe() {}

    private static boolean useDiLink5AppProcessBridge() {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                && !"com.overdrive.app".equals(android.app.Application.getProcessName());
    }

    /** A labelled feature id to read. */
    public static final class Id {
        public final String label;
        public final int featureId;
        Id(String label, int featureId) { this.label = label; this.featureId = featureId; }
    }

    /**
     * Absolute seat-geometry READ ids (doubles). These are the getters the
     * DiLink seat manager ({@code spi.p7}) reads before it applies a stored
     * profile position — the values that describe "where the seat physically is".
     */
    public static Id[] seatIds() {
        return new Id[]{
            new Id("SEAT_HORIZONTAL",   988807176),
            new Id("SEAT_BACKREST",     988807184),
            new Id("SEAT_HEIGHT",       988807192),
            new Id("SEAT_SITPOINT",     988807200),
            new Id("SEAT_LEGHOLDER",    988807208),
            new Id("SEAT_HEADREST_H",   988807216),
            new Id("SEAT_HEADREST_V",   988807224),
        };
    }

    /**
     * Keyfob / unlock-identity READ ids (expected int-valued) from CarSetting's
     * {@code Body.java}. Whether any of these returns a stable, per-key-distinct
     * value is the open question the sub-41 "keyfob-identity" dead-end candidate
     * needs a real-car test to settle — this endpoint is that test's read side.
     */
    public static Id[] keyfobIds() {
        return new Id[]{
            new Id("BODYWORK_BT_KEY_CODE",     0x3E8FA010),
            new Id("NFC_KEY_UNLOCK_SRC",       0x4036B020),
            new Id("KEY_OPERATION_TYPE_LSB",   0x19C3D010),
            new Id("KEY_OPERATION_TYPE_MSB",   0x19C3C010),
            new Id("CARD_NFC_KEY_STATE",       0x40334013),
        };
    }

    /**
     * Read every id in the given list off the bodywork device and return a JSON
     * report. Reads only. Each id is read BOTH as a double and as an int (two
     * typed {@code get(int[], Class)} calls), because a {@code BYDAutoEventValue}
     * fills only the field matching the requested type — reading one interpretation
     * and printing the other would show a default 0 that looks valid (the trap the
     * {@link BydDeviceHelper} javadoc warns about). Reporting both, with the type
     * that actually returned non-null, lets the caller see the real width/scale.
     *
     * @param ctx daemon Context (BYD-capable — see {@code resolveContext} in the handler)
     * @param ids the labelled ids to read
     */
    public static JSONObject readIds(Context ctx, Id[] ids) {
        return readIdsOnDevice(ctx, BODYWORK_DEVICE, ids);
    }

    /**
     * As {@link #readIds} but against an arbitrary BYD device class. Used to hunt the
     * mirror device (`BYDAutoRearViewMirrorDevice`) and its read ids on-car: the exact
     * fqcn and read ids aren't known from the decompile, so `?device=<fqcn>&ids=…`
     * lets us probe candidates live without a rebuild.
     */
    public static JSONObject readIdsOnDevice(Context ctx, String deviceClass, Id[] ids) {
        JSONObject r = new JSONObject();
        JSONArray values = new JSONArray();
        try {
            r.put("uid", android.os.Process.myUid());
            r.put("deviceClass", deviceClass);

            Context permissive = new PermissiveContext(ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx);
            Object device = BydDeviceHelper.getDevice(deviceClass, permissive);
            if (device == null) {
                r.put("error", deviceClass + " null / not present on this firmware");
                return r;
            }
            r.put("deviceClassResolved", device.getClass().getName());
            // The singleton caches the FIRST context it was created with (likely a
            // plain one from BydDataCollector), and the in-process permission gate
            // runs against that cached mContext — so swap ours in, same as the
            // light probe. Harmless for reads; required if any getter is gated.
            r.put("permissiveContextInstalled", swapContext(device, permissive));

            for (Id id : ids) {
                values.put(readOne(device, id));
            }
            r.put("values", values);
        } catch (Throwable t) {
            try { r.put("exception", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Candidate fully-qualified class names for the rear-view-mirror device, tried in
     * order until one resolves. The bodywork device is
     * {@code android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice}, so these follow
     * the same {@code android.hardware.bydauto.<sub>.BYDAuto…Device} shape. The handler
     * reports which one resolved so we can pin it down.
     */
    public static final String[] MIRROR_DEVICE_CANDIDATES = {
        "android.hardware.bydauto.rearviewmirror.BYDAutoRearViewMirrorDevice",
        "android.hardware.bydauto.mirror.BYDAutoRearViewMirrorDevice",
        "android.hardware.bydauto.rearview.BYDAutoRearViewMirrorDevice",
        "android.hardware.bydauto.rvm.BYDAutoRearViewMirrorDevice",
    };

    /**
     * Absolute seat-geometry WRITE ids (floats) — distinct from the read ids. From the
     * sub-41 decompile. Used by {@link #writeAxes}. Steering (ST-H 1276207152 / ST-V
     * 1276207160) is intentionally omitted: Pål's car has no electric steering column,
     * and the HAL silently ignores those writes, so there is nothing to test here.
     */
    public static Id[] seatWriteIds() {
        return new Id[]{
            new Id("SEAT_HORIZONTAL",   1276203024),
            new Id("SEAT_BACKREST",     1276203032),
            new Id("SEAT_HEIGHT",       1276203040),
            new Id("SEAT_SITPOINT",     1276203048),
            new Id("SEAT_LEGHOLDER",    1276203056),
            new Id("SEAT_HEADREST_H",   1276203064),
            new Id("SEAT_HEADREST_V",   1276203072),
        };
    }

    /** Resolve a seat-axis label (case-insensitive, SEAT_ prefix optional) to its WRITE id, or null. */
    public static Integer writeIdForAxis(String axis) {
        if (axis == null) return null;
        String want = axis.trim().toUpperCase();
        if (!want.startsWith("SEAT_")) want = "SEAT_" + want;
        for (Id id : seatWriteIds()) {
            if (id.label.equals(want)) return id.featureId;
        }
        return null;
    }

    /** The rear-view-mirror WRITE device (confirmed fqcn from CarSetting dex). */
    public static final String MIRROR_WRITE_DEVICE =
            "android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice";

    /**
     * Mirror WRITE ids (ints) on the doormirror device — from Body.java `_SET` constants.
     * NOTE mirror READ ids (0x4B3.., 0x4BB.. on the bodywork device, doubles) differ from
     * these WRITE ids, same read≠write split as the seat.
     */
    public static Id[] mirrorWriteIds() {
        return new Id[]{
            new Id("LEFT_H",  0x4C116010),
            new Id("LEFT_V",  0x4C116018),
            new Id("RIGHT_H", 0x4C116020),
            new Id("RIGHT_V", 0x4C116028),
        };
    }

    /** Resolve a mirror-axis label (LEFT_H/LEFT_V/RIGHT_H/RIGHT_V, case-insensitive) to its WRITE id, or null. */
    public static Integer writeIdForMirror(String axis) {
        if (axis == null) return null;
        String want = axis.trim().toUpperCase();
        for (Id id : mirrorWriteIds()) {
            if (id.label.equals(want)) return id.featureId;
        }
        return null;
    }

    /**
     * WRITE mirror geometry on the doormirror device: one batched
     * {@code set(int[] ids, BYDAutoEventValue)} with {@code ev.intArrayValue} — the mirror
     * counterpart of {@link #writeAxes}, matching spi.p7 (mirrors use int array, not float).
     * MOVES THE MIRROR(S). Parked-gated like the seat write.
     */
    public static JSONObject writeMirror(Context ctx, int[] ids, int[] values) {
        JSONObject r = new JSONObject();
        try {
            r.put("uid", android.os.Process.myUid());
            r.put("deviceClass", MIRROR_WRITE_DEVICE);
            JSONArray reqIds = new JSONArray();
            JSONArray reqVals = new JSONArray();
            for (int i = 0; i < ids.length; i++) { reqIds.put(ids[i]); reqVals.put(values[i]); }
            r.put("ids", reqIds);
            r.put("values", reqVals);

            if (positioningBlocked(r)) {
                r.put("skipped", true);
                r.put("reason", "movement gate blocked");
                return r;
            }

            Context permissive = new PermissiveContext(ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx);
            Object device = BydDeviceHelper.getDevice(MIRROR_WRITE_DEVICE, permissive);
            if (device == null) { r.put("error", MIRROR_WRITE_DEVICE + " null / not present"); return r; }
            r.put("permissiveContextInstalled", swapContext(device, permissive));

            Class<?> evClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object ev = evClass.getConstructor().newInstance();
            evClass.getField("intArrayValue").set(ev, values);
            Method setM = device.getClass().getMethod("set", int[].class, evClass);
            r.put("setMethod", setM.getDeclaringClass().getName() + ".set(int[],BYDAutoEventValue[intArrayValue])");

            // Device/reflection setup can take long enough for the car to leave Park.
            if (positioningBlocked(r)) {
                r.put("skipped", true);
                r.put("reason", "movement gate became active before actuation");
                return r;
            }
            Object res = setM.invoke(device, ids, ev);
            int code = (res instanceof Number) ? ((Number) res).intValue()
                     : (res instanceof Boolean) ? (((Boolean) res) ? 0 : -1) : UNKNOWN_CODE;
            r.put("resultCode", code);
            r.put("resultCodeHex", "0x" + Integer.toHexString(code));
            r.put("accepted", code == 0);
            if (code == UNKNOWN_CODE) r.put("unconfirmed", "set() returned no result code");
        } catch (Throwable t) {
            try { r.put("exception", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * WRITE absolute seat geometry: one batched {@code set(int[] ids, float[] values)} on
     * the bodywork device (the DiLink apply pattern), through the PermissiveContext. This
     * MOVES THE SEAT.
     *
     * <p>Parked-gated: refuses unless the positioning guard is disabled or
     * {@link DrivingSafetyGuard#isMovementBlocked()} is
     * false, matching the native app (which won't change a seat position while driving)
     * and the sub-41 {@code s7.T()} gate.
     *
     * @return JSON: the gate decision, the resolved set() method, and the raw SDK result code.
     */
    public static JSONObject writeAxes(Context ctx, int[] ids, float[] values) {
        if (useDiLink5AppProcessBridge()) {
            return VehicleActuatorBridge.writeDiLink5Position(ctx, ids, values);
        }
        JSONObject r = new JSONObject();
        try {
            r.put("uid", android.os.Process.myUid());
            r.put("deviceClass", BODYWORK_DEVICE);
            JSONArray reqIds = new JSONArray();
            JSONArray reqVals = new JSONArray();
            for (int i = 0; i < ids.length; i++) { reqIds.put(ids[i]); reqVals.put(values[i]); }
            r.put("ids", reqIds);
            r.put("values", reqVals);

            if (positioningBlocked(r)) {
                r.put("skipped", true);
                r.put("reason", "movement gate blocked (not parked / unknown state)");
                return r;
            }
            if (positionPowerUnavailable(r)) return r;

            Context permissive = new PermissiveContext(ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx);
            Object device = BydDeviceHelper.getDevice(BODYWORK_DEVICE, permissive);
            if (device == null) { r.put("error", "BYDAutoBodyworkDevice null on this firmware"); return r; }
            r.put("permissiveContextInstalled", swapContext(device, permissive));

            // The vehicle's seat manager applies one batched set(int[] ids,
            // BYDAutoEventValue) where the
            // value is a parallel FLOAT ARRAY in ev.floatArrayValue — NOT per-axis, NOT
            // ev.doubleValue. Gated by s7.T() (parked), no separate EXECUTE step. My earlier
            // per-axis ev.doubleValue write returned code 0 but did NOT actuate — wrong field
            // and wrong shape for these array-typed seat SET ids.
            Class<?> evClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object ev = evClass.getConstructor().newInstance();
            evClass.getField("floatArrayValue").set(ev, values);   // float[] parallel to ids
            Method setM = device.getClass().getMethod("set", int[].class, evClass);
            r.put("setMethod", setM.getDeclaringClass().getName() + ".set(int[],BYDAutoEventValue[floatArrayValue])");

            // Device/reflection setup can take long enough for the car to leave Park.
            if (positioningBlocked(r)) {
                r.put("skipped", true);
                r.put("reason", "movement gate became active before actuation");
                return r;
            }
            if (positionPowerUnavailable(r)) return r;
            Object res = setM.invoke(device, ids, ev);
            int code = (res instanceof Number) ? ((Number) res).intValue()
                     : (res instanceof Boolean) ? (((Boolean) res) ? 0 : -1) : UNKNOWN_CODE;
            r.put("resultCode", code);
            r.put("resultCodeHex", "0x" + Integer.toHexString(code));
            r.put("accepted", code == 0);   // spi.p7: resultCode==0 == success (sa.b)
            if (code == UNKNOWN_CODE) r.put("unconfirmed", "set() returned no result code");
        } catch (Throwable t) {
            try { r.put("exception", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return r;
    }

    private static JSONObject readOne(Object device, Id id) {
        JSONObject o = new JSONObject();
        try {
            o.put("label", id.label);
            o.put("id", id.featureId);
            o.put("idHex", "0x" + Integer.toHexString(id.featureId));

            // The real device exposes get(int[], Class); the Class arg selects the
            // return shape. OverDrive's other reads pass a primitive TYPE (Double.TYPE
            // etc.), while the DiLink decompile that this recipe came from passed the
            // BYDAutoEventValue class itself. Both route through the SAME overload, so
            // try all three and report which one the getter actually answers — that is
            // the whole point of a read-test: settle the convention on the real car.
            Object asDouble = BydDeviceHelper.callGet(device, id.featureId, Double.TYPE);
            Object asInt = BydDeviceHelper.callGet(device, id.featureId, Integer.TYPE);
            Object asEvent = null;
            try {
                Class<?> evc = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
                asEvent = BydDeviceHelper.callGet(device, id.featureId, evc);
            } catch (Throwable ignored) {}

            boolean read = false;
            if (asDouble != null) {
                double d = BydDeviceHelper.getDoubleValue(asDouble);
                if (!Double.isNaN(d)) { o.put("doubleValue", d); read = true; }
            }
            if (asInt != null) {
                int i = BydDeviceHelper.getIntValue(asInt);
                if (i != Integer.MIN_VALUE) { o.put("intValue", i); read = true; }
            }
            if (asEvent != null) {
                // A BYDAutoEventValue carries independent int/double/string fields;
                // surface whichever the HAL filled so the caller sees the native shape.
                double ed = BydDeviceHelper.getDoubleValue(asEvent);
                int ei = BydDeviceHelper.getIntValue(asEvent);
                String es = BydDeviceHelper.getStringValue(asEvent);
                if (!Double.isNaN(ed)) { o.put("eventDoubleValue", ed); read = true; }
                if (ei != Integer.MIN_VALUE) { o.put("eventIntValue", ei); read = true; }
                if (es != null) { o.put("eventStringValue", es); read = true; }
                o.put("eventClass", asEvent.getClass().getName());
            }
            o.put("read", read);
            // Which typed calls returned a (non-null) object at all — independent of
            // whether the extracted primitive was usable. Tells the conventions apart.
            JSONArray accepted = new JSONArray();
            if (asDouble != null) accepted.put("Double.TYPE");
            if (asInt != null) accepted.put("Integer.TYPE");
            if (asEvent != null) accepted.put("BYDAutoEventValue");
            o.put("acceptedBy", accepted);
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return o;
    }

    /**
     * Parse a comma-separated {@code ids=} list of decimal or {@code 0x}-hex
     * feature ids into a labelled array (label = the hex form). Returns an empty
     * array for null/blank input. Unparseable tokens are skipped.
     */
    public static Id[] parseIds(String csv) {
        Map<String, Integer> parsed = new LinkedHashMap<>();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String tok : csv.split(",")) {
                String s = tok.trim();
                if (s.isEmpty()) continue;
                try {
                    int v = (s.startsWith("0x") || s.startsWith("0X"))
                            ? (int) Long.parseLong(s.substring(2), 16)
                            : Integer.parseInt(s);
                    parsed.put("0x" + Integer.toHexString(v), v);
                } catch (NumberFormatException ignored) {}
            }
        }
        Id[] out = new Id[parsed.size()];
        int i = 0;
        for (Map.Entry<String, Integer> e : parsed.entrySet()) {
            out[i++] = new Id(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Read the full driver-position bundle (all {@link #fullAxes()} axes) as a JSON object of
     * label -> current value (double, bodywork device). Axes that read a valid value are
     * included; a not-equipped axis that reads NaN is stored as its 127.5 sentinel so a later
     * apply round-trips faithfully. This is the read side of the capture-on-long-press feature.
     */
    public static JSONObject readFullBundle(Context ctx) {
        if (useDiLink5AppProcessBridge()) {
            JSONObject bridged = VehicleActuatorBridge.readDiLink5Position(ctx);
            return bridged.has("error") ? new JSONObject() : bridged;
        }
        JSONObject axes = new JSONObject();
        try {
            Context permissive = new PermissiveContext(ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx);
            Object device = BydDeviceHelper.getDevice(BODYWORK_DEVICE, permissive);
            if (device == null) return axes;
            swapContext(device, permissive);
            for (Axis a : fullAxes()) {
                Object rd = BydDeviceHelper.callGet(device, a.readId, Double.TYPE);
                double d = BydDeviceHelper.getDoubleValue(rd);
                // Store the sentinel for anything that isn't a real position, so a negative
                // error code can't be captured and later replayed as a setpoint.
                axes.put(a.label, inRange((float) d) ? d : (double) SENTINEL);
            }
        } catch (Throwable ignored) {}
        return axes;
    }

    /** One full-position axis: label + read id + write id + batch group (1=mirror/steering, 2=seat). */
    public static final class Axis {
        public final String label; public final int readId, writeId, group;
        Axis(String l, int r, int w, int g) { label = l; readId = r; writeId = w; group = g; }
    }

    /**
     * The complete driver-position axis table (from Body.java + spi.p7.m()). Group 1 =
     * mirrors + steering (native batch 1); group 2 = the 7 seat axes (native batch 2).
     * Read ids are doubles on the bodywork device; write ids also bodywork, floatArrayValue.
     */
    public static Axis[] fullAxes() {
        return new Axis[]{
            new Axis("LEFT_H",     0x4B300188, 0x4C116010, 1),
            new Axis("LEFT_V",     0x4B300190, 0x4C116018, 1),
            new Axis("RIGHT_H",    0x4BB00110, 0x4C116020, 1),
            new Axis("RIGHT_V",    0x4BB00118, 0x4C116028, 1),
            new Axis("ST_H",       0x4B300198, 0x4C116030, 1),
            new Axis("ST_V",       0x4B3001A0, 0x4C116038, 1),
            new Axis("HORIZONTAL", 0x3AF00008, 0x4C115010, 2),
            new Axis("BACKREST",   0x3AF00010, 0x4C115018, 2),
            new Axis("HEIGHT",     0x3AF00018, 0x4C115020, 2),
            new Axis("SITPOINT",   0x3AF00020, 0x4C115028, 2),
            new Axis("LEGHOLDER",  0x3AF00028, 0x4C115030, 2),
            new Axis("HEADREST_H", 0x3AF00030, 0x4C115038, 2),
            new Axis("HEADREST_V", 0x3AF00038, 0x4C115040, 2),
        };
    }

    /**
     * Apply a FULL driver position, replicating {@code spi.p7.m()} exactly: read current
     * value for every axis, apply the caller's overrides (by label, case-insensitive), then
     * write TWO bodywork floatArrayValue batches — group 1 (mirrors+steering), a 50 ms pause,
     * then group 2 (7 seat axes). This is the real native apply sequence; a standalone mirror
     * batch is accepted but does not actuate. MOVES THE SEAT AND MIRRORS. Parked-gated.
     *
     * @param overrides label -> value (e.g. {"LEFT_H":15}); axes not overridden keep their current read value.
     */
    public static JSONObject applyFull(Context ctx, Map<String, Float> overrides) {
        if (useDiLink5AppProcessBridge()) {
            return VehicleActuatorBridge.applyDiLink5Position(ctx, overrides);
        }
        JSONObject r = new JSONObject();
        try {
            r.put("uid", android.os.Process.myUid());
            if (positioningBlocked(r)) {
                r.put("skipped", true);
                r.put("reason", "movement gate blocked");
                return r;
            }
            if (positionPowerUnavailable(r)) return r;

            Context permissive = new PermissiveContext(ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx);
            Object device = BydDeviceHelper.getDevice(BODYWORK_DEVICE, permissive);
            if (device == null) { r.put("error", "BYDAutoBodyworkDevice null"); return r; }
            r.put("permissiveContextInstalled", swapContext(device, permissive));

            Class<?> evClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Method setM = device.getClass().getMethod("set", int[].class, evClass);

            // Resolve current value per axis (double read), apply override.
            Axis[] axes = fullAxes();
            java.util.Map<String, Float> ov = new LinkedHashMap<>();
            if (overrides != null) for (Map.Entry<String, Float> e : overrides.entrySet()) ov.put(e.getKey().trim().toUpperCase(), e.getValue());
            JSONObject applied = new JSONObject();
            float[] valByIdx = new float[axes.length];
            JSONArray rejected = new JSONArray();
            for (int k = 0; k < axes.length; k++) {
                Float o = ov.get(axes[k].label);
                float v;
                if (o != null) {
                    // An override arrives from a stored position, i.e. off disk, so it can be
                    // NaN or out of range. Sending that to the HAL is a physical command;
                    // fall back to the ignored sentinel instead.
                    v = inRange(o) ? o : SENTINEL;
                    if (!inRange(o)) rejected.put(axes[k].label);
                } else {
                    Object rd = BydDeviceHelper.callGet(device, axes[k].readId, Double.TYPE);
                    double d = BydDeviceHelper.getDoubleValue(rd);
                    // Out-of-range covers both NaN (read failed) and a negative error code
                    // returned as a value — neither is a position to drive to.
                    v = inRange((float) d) ? (float) d : SENTINEL;
                }
                valByIdx[k] = v;
                applied.put(axes[k].label, v);
            }
            r.put("applied", applied);
            if (rejected.length() > 0) r.put("rejectedAxes", rejected);

            // The thirteen live reads above can take long enough for the car to leave Park.
            // Recheck before starting the two-batch native sequence; once batch 1 commits,
            // batch 2 follows 50 ms later as one pose.
            if (positioningBlocked(r)) {
                r.put("skipped", true);
                r.put("reason", "movement gate became active while preparing the position");
                return r;
            }
            if (positionPowerUnavailable(r)) return r;
            // Batch 1: group 1 (mirrors + steering).
            JSONObject b1 = writeGroup(setM, evClass, device, axes, valByIdx, 1);
            r.put("batch1", b1);
            if (b1.optBoolean("movementBlocked", false)) {
                r.put("accepted", false);
                r.put("error", "movement gate became active at the first batch boundary");
                return r;
            }
            try { Thread.sleep(50L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (positioningBlocked(r)) {
                r.put("accepted", false);
                r.put("partialApplied", b1.optBoolean("accepted", false));
                r.put("error", "movement gate became active before the seat batch");
                return r;
            }
            // Batch 2: group 2 (seat).
            JSONObject b2 = writeGroup(setM, evClass, device, axes, valByIdx, 2);
            r.put("batch2", b2);
            if (b2.optBoolean("movementBlocked", false)) {
                r.put("accepted", false);
                r.put("partialApplied", b1.optBoolean("accepted", false));
                r.put("error", "movement gate became active at the seat batch boundary");
                return r;
            }
            // One aggregate verdict. Callers were reading only `error`/`skipped`, so a
            // batch-1-ok / batch-2-failed apply reported success with the seat unmoved.
            boolean ok = b1.optBoolean("accepted", false) && b2.optBoolean("accepted", false);
            r.put("accepted", ok);
        } catch (Throwable t) {
            try { r.put("exception", String.valueOf(t)); r.put("accepted", false); } catch (Exception ignored) {}
        }
        return r;
    }

    private static boolean positionPowerUnavailable(JSONObject result) {
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) return false;
        Boolean accOn = VehicleActuatorBridge.currentDiLink5RequestAccOn();
        if (Boolean.TRUE.equals(accOn)) return false;
        try {
            result.put("accepted", false);
            result.put("skipped", true);
            result.put("inert", true);
            result.put("reason", accOn == null
                    ? "ACC state unavailable: position write not attempted"
                    : "ACC off: position motors are unpowered");
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean positioningBlocked(JSONObject result) {
        try {
            if (VehicleActuatorBridge.isDiLink5RequestExpired()) {
                result.put("movementBlocked", true);
                result.put("requestExpired", true);
                return true;
            }
            boolean blocked = DrivingSafetyGuard.isActionBlocked(
                    DrivingSafetyGuard.GUARD_POSITIONING);
            result.put("movementBlocked", blocked);
            return blocked;
        } catch (Throwable t) {
            try {
                result.put("gateError", String.valueOf(t));
                result.put("movementBlocked", true);
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    private static JSONObject writeGroup(Method setM, Class<?> evClass, Object device, Axis[] axes, float[] valByIdx, int group) throws Exception {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        java.util.List<Float> vals = new java.util.ArrayList<>();
        for (int k = 0; k < axes.length; k++) if (axes[k].group == group) { ids.add(axes[k].writeId); vals.add(valByIdx[k]); }
        int[] idArr = new int[ids.size()]; float[] valArr = new float[vals.size()];
        for (int k = 0; k < ids.size(); k++) { idArr[k] = ids.get(k); valArr[k] = vals.get(k); }
        Object ev = evClass.getConstructor().newInstance();
        evClass.getField("floatArrayValue").set(ev, valArr);
        JSONObject o = new JSONObject();
        o.put("group", group);
        o.put("count", idArr.length);
        if (positioningBlocked(o)) {
            o.put("accepted", false);
            o.put("skipped", true);
            o.put("reason", "movement gate became active at batch boundary");
            return o;
        }
        Object res = setM.invoke(device, idArr, ev);
        int code = (res instanceof Number) ? ((Number) res).intValue()
                 : (res instanceof Boolean) ? (((Boolean) res) ? 0 : -1) : UNKNOWN_CODE;
        o.put("resultCode", code);
        o.put("accepted", code == 0);
        if (code == UNKNOWN_CODE) o.put("unconfirmed", "set() returned no result code");
        return o;
    }

    /**
     * Context that no-ops Android's permission enforcement so the BYD device's
     * in-process gate-3 check passes. Same mechanism as
     * {@link HazardLightProbe.PermissiveContext}; kept local so this read-only
     * probe carries no dependency on the write probe's internals.
     */
    static final class PermissiveContext extends ContextWrapper {
        PermissiveContext(Context base) { super(base); }
        @Override public void enforceCallingOrSelfPermission(String permission, String message) { /* no-op */ }
        @Override public void enforceCallingPermission(String permission, String message) { /* no-op */ }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) { /* no-op */ }
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkCallingPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Reflectively replace {@code AbsBYDAutoDevice.mContext} on the device
     * singleton with our PermissiveContext. Walks up the class hierarchy to find
     * the private {@code mContext} field. Mirrors {@link HazardLightProbe#swapContext}.
     */
    static boolean swapContext(Object device, Context permissive) {
        Class<?> cls = device.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField("mContext");
                f.setAccessible(true);
                f.set(device, permissive);
                return true;
            } catch (NoSuchFieldException nsfe) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }
}
