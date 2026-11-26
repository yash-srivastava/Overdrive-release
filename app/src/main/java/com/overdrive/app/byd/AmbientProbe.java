package com.overdrive.app.byd;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Interior-ambient (atmosphere) light state as a capturable, applicable bundle — the
 * ambient half of a saved position, alongside {@link BodyworkSeatProbe}'s geometry.
 *
 * <p>Everything here is modelled on how BYD's own ambient screen
 * ({@code Di4IviAmbientLightFragment}) drives the HAL, because that screen is the
 * definition of "what the car is set to" and a capture that disagrees with it is
 * wrong by construction:
 *
 * <ul>
 *   <li><b>Zones are real and independent.</b> {@code setIALColor}/{@code setIALBrightness}
 *       take the area as their first argument and the car stores a separate value per
 *       zone. Area 1 = front, 2 = rear. Area 3 ("all rows") is a UI convenience that
 *       writes both, and is NOT readable — asking for area 3 returns a HAL error, which
 *       is why reads here only ever ask for 1 and 2.</li>
 *   <li><b>Brightness is a 0..5 level, not a percentage.</b> BYD's slider max is 5 and
 *       the value goes to the HAL unscaled, so a level captured here re-applies exactly.
 *       {@code BydDataCollector}'s percent-based API converts, which is right for a user
 *       typing a percentage and wrong for a round-trip.</li>
 *   <li><b>The colour count varies by trim</b> ({@code SET_IAL_COLOR_CONFIG} selects 6,
 *       30, 63 or 126). The bound is read rather than assumed, so a car with a bigger
 *       palette is not silently clamped to someone else's range.</li>
 *   <li><b>The star-ring zone is not implemented on this firmware.</b> Its feature ids
 *       exist, but BYD's own accessor has a "to be added" placeholder branch where the
 *       read should be. Not captured; it would only ever record a zero.</li>
 * </ul>
 *
 * <p>Modes matter for apply ORDER, not just content. Music mode and dynamic colours both
 * drive the colour continuously, so applying a fixed colour while either is on means the
 * car overwrites it immediately. Apply therefore turns the modes off first, writes the
 * static state, and only then restores whichever modes the position actually wants.
 */
public final class AmbientProbe {

    public static final String SETTING_DEVICE =
            "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    public static final String LIGHT_DEVICE =
            "android.hardware.bydauto.light.BYDAutoLightDevice";

    /** Zones the car actually implements. Star-ring (5) is declared but unimplemented. */
    public static final int AREA_FRONT = 1;
    public static final int AREA_REAR = 2;
    /** "All rows" — a write-only convenience. Never read with this. */
    public static final int AREA_ALL = 3;

    /**
     * On is always 1. Off is NOT uniform across these features — execute-style ones use 2,
     * the Light-device main switch uses 0 — so OFF here is only for the mode switches, and the
     * main switch goes through {@link #setMainSwitch} instead.
     */
    private static final int ON = 1;
    private static final int OFF = 2;

    private static final int BRIGHTNESS_MAX = 5;

    /** Highest colour BydDataCollector.setAmbientLightZoned will write. */
    private static final int WRITABLE_COLOUR_MAX = 31;

    // Switch/mode feature ids, cross-checked against BYD's own Lights/Setting constants.
    private static final int MAIN_SWITCH_STATUS = 0x3F300046;
    private static final int MUSIC_MODE_STATE = 0x42E00040;
    private static final int MUSIC_MODE_SET = 0x4C109019;
    private static final int DYNAMIC_COLOURS_STATE = 0x2880014A;
    private static final int DYNAMIC_COLOURS_SET = 0x4C10901E;
    private static final int NIGHT_DIM_FEEDBACK = 0x2EB0003D;
    private static final int NIGHT_DIM_SET = 0x4EF42044;
    private static final int CUSTOM_MODE = 0x2730001A;
    private static final int CUSTOM_MODE_SET = 0x4C11302D;
    /** How many colours this trim exposes; maps 3/5/6 to 6/63/126, anything else to 30. */
    private static final int IAL_COLOUR_CONFIG = 0x3FF0000A;
    /** BYD's default SEEKBAR max — one less than the colour count (see colourMax). */
    private static final int DEFAULT_SEEKBAR_MAX = 30;

    private AmbientProbe() {}

    private static boolean useDiLink5AppProcessBridge() {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                && !"com.overdrive.app".equals(android.app.Application.getProcessName());
    }

    // ── read ────────────────────────────────────────────────────────────────────

    /**
     * Capture the ambient state as it stands, or null when the setting device cannot be
     * reached at all.
     *
     * <p>Individual fields are omitted rather than defaulted when the HAL will not answer
     * — an absent key means "this car did not tell us", and apply skips what it cannot
     * see. Writing a plausible default here would make a capture assert something about
     * the car that was never read.
     */
    public static JSONObject read(Context ctx) {
        if (useDiLink5AppProcessBridge()) {
            JSONObject bridged = VehicleActuatorBridge.readDiLink5Ambient(ctx);
            return bridged.has("error") ? null : bridged;
        }
        Object setting = device(ctx, SETTING_DEVICE);
        Object light = device(ctx, LIGHT_DEVICE);
        if (setting == null) return null;
        boolean diLink5 = com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        // An unbound DI5 adapter answers 0 for every getter; that capture would apply as "off".
        if (diLink5 && !BydDeviceHelper.isDiLink5AdapterReady(
                ctx, setting, "getSettingAdapterManager")) {
            return null;
        }

        try {
            JSONObject r = new JSONObject();
            putIf(r, "area", intOrNull(BydDeviceHelper.callGetter(setting, "getIALArea")));
            // Only store a zone the car actually reported. An unconditional put left {} behind
            // on trims with no zone getters, and every "did we capture ambient?" guard
            // downstream tests length()>0 — so an empty zone read as a real captured one.
            JSONObject front = readZone(setting, AREA_FRONT);
            JSONObject rear = readZone(setting, AREA_REAR);
            // DI5 0 is IAL_COLOR_INVALID / IAL_BRIGHTNESS_INVALID. A zone whose colour reads 0 is
            // not reporting, so its brightness 0 is not a real "off" and would apply as one.
            for (JSONObject zone : new JSONObject[]{front, rear}) {
                if (diLink5 && zone.optInt("colour", 1) < 1) {
                    zone.remove("colour");
                    if (zone.optInt("brightness", 1) < 1) zone.remove("brightness");
                }
            }
            // Nothing reported: an area-only block would overwrite a good saved one.
            if (diLink5 && front.length() == 0 && rear.length() == 0) return null;
            if (front.length() > 0) r.put("front", front);
            if (rear.length() > 0) r.put("rear", rear);

            // Modes. Stored as booleans because 1/2 is BYD's wire encoding, not a concept
            // the position store or the UI should have to carry.
            // MAIN_SWITCH is the Light-device 0/1 feature (see BydDataCollector's tier ladder);
            // the mode switches are the execute-style 1/2 ones.
            putBool(r, "mainSwitch", readSwitch(light, MAIN_SWITCH_STATUS, 0));
            // DI5: only "on" has a verified write value, so an off switch is left uncaptured.
            if (diLink5 && BydDataCollector.getInstance().diLink5AmbientMainState() == 1) {
                r.put("mainSwitch", true);
            }
            putBool(r, "musicMode", readSwitch(setting, MUSIC_MODE_STATE, OFF));
            putBool(r, "dynamicColours", readSwitch(light, DYNAMIC_COLOURS_STATE, OFF));
            putBool(r, "nightAutoDim", readSwitch(light, NIGHT_DIM_FEEDBACK, OFF));
            putIf(r, "customMode", validOrNull(BydDeviceHelper.callGetSingle(light, CUSTOM_MODE)));
            return r;
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject readZone(Object setting, int area) throws JSONException {
        JSONObject z = new JSONObject();
        putIf(z, "colour", intOrNull(BydDeviceHelper.callGetter(setting, "getIALColor", area)));
        putIf(z, "brightness", intOrNull(BydDeviceHelper.callGetter(setting, "getIALBrightness", area)));
        return z;
    }

    /**
     * The HIGHEST valid colour on this trim, so the UI offers the colours the car actually
     * has rather than a hardcoded range.
     *
     * <p>Note the {@code +1}. BYD's {@code getAmbientColorMax()} returns a SEEKBAR MAX, and
     * its slider writes {@code progress + 1} — so a seekbar max of 30 means 31 colours,
     * numbered 1..31. Measured on the car 2026-08-20 by dragging BYD's own slider to each
     * end: it pins at 31, and the app's 31-entry palette table matches exactly.
     *
     * <p>Reading the config rather than assuming still matters, because the seekbar max is
     * 6, 30, 63 or 126 depending on trim — this car is simply the 30 one.
     */
    public static int colourMax(Context ctx) {
        if (useDiLink5AppProcessBridge()) {
            return VehicleActuatorBridge.readDiLink5AmbientColourMax(ctx);
        }
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            // DI5 reports its palette through getAmbientLightColorSupport, not the legacy id.
            int count = BydDataCollector.getInstance().diLink5AmbientColourCount();
            return count == BydVehicleData.UNAVAILABLE
                    ? DEFAULT_SEEKBAR_MAX + 1 : Math.min(count, WRITABLE_COLOUR_MAX);
        }
        return colourMaxFor(device(ctx, SETTING_DEVICE));
    }

    /** {@link #colourMax} for a setting-device handle the caller already holds. */
    public static int colourMaxFor(Object setting) {
        if (setting == null) return DEFAULT_SEEKBAR_MAX + 1;
        // A feature-id read, not a named getter: BYD reads SET_IAL_COLOR_CONFIG off the HAL
        // and maps it to a seekbar max. There is no getIALColorConfig() method to call.
        Integer cfg = validOrNull(BydDeviceHelper.callGetSingle(setting, IAL_COLOUR_CONFIG));
        int seekbarMax;
        switch (cfg == null ? -1 : cfg) {
            case 3:  seekbarMax = 6; break;
            case 5:  seekbarMax = 63; break;
            case 6:  seekbarMax = 126; break;
            default: seekbarMax = DEFAULT_SEEKBAR_MAX; break;
        }
        // Never advertise more colours than the write path accepts: setAmbientLightZoned
        // rejects anything above 31, so a 63/126 trim would offer swatches that validate
        // on save and then silently fail to apply.
        return Math.min(seekbarMax + 1, WRITABLE_COLOUR_MAX);
    }

    // ── apply ───────────────────────────────────────────────────────────────────

    /**
     * Apply a captured ambient bundle. Returns a per-step result rather than a single
     * boolean: ambient is several independent writes, and "something did not land" is
     * only actionable if you can see which.
     *
     * <p>Order is deliberate. Music mode and dynamic colours are switched OFF before the
     * static colour is written, because either of them running means the car is driving
     * the colour itself and would overwrite what we just set. They are restored last,
     * only if the captured state actually had them on.
     */
    public static JSONObject apply(Context ctx, JSONObject ambient) throws JSONException {
        if (useDiLink5AppProcessBridge()) {
            return VehicleActuatorBridge.applyDiLink5Ambient(ctx, ambient);
        }
        JSONObject result = new JSONObject();
        if (ambient == null) {
            result.put("applied", false);
            result.put("reason", "no ambient state on this position");
            return result;
        }
        Object setting = device(ctx, SETTING_DEVICE);
        Object light = device(ctx, LIGHT_DEVICE);
        if (setting == null) {
            result.put("applied", false);
            result.put("reason", "setting device unavailable");
            return result;
        }

        JSONObject steps = new JSONObject();

        // 1. Main switch on first when the capture had it on: the zone writes below are
        //    pointless against lights that are off.
        if (ambient.has("mainSwitch") && ambient.getBoolean("mainSwitch")) {
            steps.put("mainSwitch", setMainSwitch(true));
        }

        // 2. Silence the dynamic drivers before writing a static colour.
        boolean wantMusic = ambient.optBoolean("musicMode", false);
        boolean wantDynamic = ambient.optBoolean("dynamicColours", false);
        if (ambient.has("musicMode")) steps.put("musicModeOff", writeSwitch(setting, MUSIC_MODE_SET, false));
        if (ambient.has("dynamicColours")) steps.put("dynamicColoursOff", writeSwitch(light, DYNAMIC_COLOURS_SET, false));

        // 3. Per-zone colour + brightness. Writing a zone selects it as a side effect, so the
        //    zone the capture had selected goes LAST and the selector ends where it started —
        //    no extra call, and nothing left pointing at whichever zone happened to be written
        //    second. (An earlier attempt restored the selection explicitly afterwards, but the
        //    only selection call reachable from here is the 2-arg setIALArea, which reports
        //    success without selecting anything.)
        boolean rearLast = ambient.optInt("area", AREA_FRONT) == AREA_REAR;
        int first = rearLast ? AREA_FRONT : AREA_REAR;
        int second = rearLast ? AREA_REAR : AREA_FRONT;
        steps.put(first == AREA_FRONT ? "front" : "rear",
                applyZone(first, ambient.optJSONObject(first == AREA_FRONT ? "front" : "rear")));
        settle();
        steps.put(second == AREA_FRONT ? "front" : "rear",
                applyZone(second, ambient.optJSONObject(second == AREA_FRONT ? "front" : "rear")));

        // 4. Standing preferences that do not interact with colour.
        if (ambient.has("nightAutoDim")) {
            steps.put("nightAutoDim", writeSwitch(light, NIGHT_DIM_SET, ambient.getBoolean("nightAutoDim")));
        }
        if (ambient.has("customMode")) {
            steps.put("customMode", BydDeviceHelper.sendSetCommand(light, CUSTOM_MODE_SET, ambient.getInt("customMode")));
        }

        // 4b. Restore the dynamic modes last, so they take over from a known static state
        //    rather than fighting the writes above.
        if (wantMusic) steps.put("musicModeOn", writeSwitch(setting, MUSIC_MODE_SET, true));
        if (wantDynamic) steps.put("dynamicColoursOn", writeSwitch(light, DYNAMIC_COLOURS_SET, true));

        // 7. Main switch OFF last when the capture had it off — doing it first would make
        //    every write above a no-op against dark lights.
        if (ambient.has("mainSwitch") && !ambient.getBoolean("mainSwitch")) {
            steps.put("mainSwitch", setMainSwitch(false));
        }

        result.put("applied",
                !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                        || allAttemptedStepsSucceeded(steps));
        result.put("steps", steps);
        return result;
    }

    static boolean allAttemptedStepsSucceeded(JSONObject steps) {
        return Boolean.TRUE.equals(stepResult(steps));
    }

    private static Boolean stepResult(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (!(value instanceof JSONObject)) return null;
        Boolean result = null;
        JSONObject object = (JSONObject) value;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Boolean nested = stepResult(object.opt(keys.next()));
            if (nested == null) continue;
            if (!nested) return false;
            result = true;
        }
        return result;
    }

    /**
     * One zone's colour and brightness, through {@link BydDataCollector}'s zoned API.
     *
     * <p>Deliberately NOT a direct {@code setIALColor} call. Selecting the zone first is
     * required — a write to an unselected area returns result code 0 and changes nothing —
     * and the selection itself is fiddly enough to be worth not reimplementing: there are
     * two {@code setIALArea} overloads, the 2-arg one BYD's own UI uses returns 0 without
     * selecting anything on this car, and the working path needs the 1-arg form plus a
     * light-device feature-id fallback for trims that refuse it. All of that already exists
     * behind {@code setAmbientLightZoned} / {@code setAmbientBrightnessZoned}, which are
     * field-proven; a second implementation here just means two things to get wrong.
     *
     * <p>Brightness goes in as a percentage because that is the public signature, and
     * {@code level * 20} round-trips exactly through its {@code round(percent/100*5)} —
     * 0→0 through 5→5 — so the captured 0..5 level is preserved.
     */
    private static JSONObject applyZone(int area, JSONObject zone) throws JSONException {
        JSONObject r = new JSONObject();
        if (zone == null) {
            r.put("skipped", "not captured");
            return r;
        }
        String zoneName = (area == AREA_REAR) ? "rear" : "front";
        BydDataCollector collector;
        try {
            collector = BydDataCollector.getInstance();
        } catch (Throwable t) {
            r.put("skipped", "collector unavailable");
            return r;
        }
        if (zone.has("colour")) {
            r.put("colour", collector.setAmbientLightZoned(zoneName, zone.getInt("colour")));
        }
        if (zone.has("brightness")) {
            if (zone.has("colour")) settle();   // same zone, but still two writes in a row
            int level = clamp(zone.getInt("brightness"), 0, BRIGHTNESS_MAX);
            // DI5 brightness commands switch the cabin on; a restore must not.
            r.put("brightness", com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    ? collector.restoreDiLink5AmbientLevel(zoneName, level)
                    : collector.setAmbientBrightnessZoned(zoneName, level * 20));
        }
        return r;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────

    /**
     * The device handle, preferring {@link BydDataCollector}'s OWN field over building one.
     *
     * <p>The earlier version wrapped the singleton in a PermissiveContext and called
     * {@code swapContext} to install it. That works for reading, but it MUTATES a singleton
     * the rest of the app shares — and the first write issued after the swap is silently
     * lost. Measured on the car: applying two zones back to back, whichever zone was written
     * FIRST never landed and the second always did, regardless of which one it was. A single
     * write from elsewhere in the app, with no swap before it, always worked.
     *
     * <p>So take the collector's handle as-is. It is already built with a context the HAL
     * accepts — that is how every other vehicle command in the app works — and nothing here
     * has to disturb it. The permissive build is kept only as a fallback for the case where
     * the collector has no handle at all, where a mutated singleton is not a concern because
     * there is no shared one to mutate.
     */
    private static Object device(Context ctx, String cls) {
        Object own = collectorField(cls);
        if (own != null) return own;
        if (ctx == null) return null;
        try {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            Context permissive = new BodyworkSeatProbe.PermissiveContext(app);
            Object device = BydDeviceHelper.getDevice(cls, permissive);
            if (device != null) BodyworkSeatProbe.swapContext(device, permissive);
            return device;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read the collector's private device handle without touching it. Same reflection the
     * ambient debug endpoint uses; the collector exposes typed helpers rather than the
     * devices themselves, and this needs the device to reach feature ids it has no helper for.
     */
    private static Object collectorField(String cls) {
        String field = cls.equals(LIGHT_DEVICE) ? "lightDevice"
                : cls.equals(SETTING_DEVICE) ? "settingDevice"
                : null;
        if (field == null) return null;
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            java.lang.reflect.Field f = c.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(c);
        } catch (Throwable t) {
            return null;
        }
    }

    /** null unless the HAL gave a real 0/1-style answer; negatives are error codes. */
    /**
     * Tri-state switch read. The off value is per-feature, not global: the Light-device main
     * switch is on=1/off=0, the execute-style mode features are on=1/off=2. Reading with the
     * wrong one turns a switch that is genuinely OFF into "unreadable", which apply then skips
     * — restoring a lights-off position with the lights still on.
     *
     * @param offValue what off looks like on THIS feature; anything else that is not ON stays
     *                 null, because inventing a state is worse than admitting we do not know.
     */
    private static Boolean readSwitch(Object device, int featureId, int offValue) {
        if (device == null) return null;
        int v = BydDeviceHelper.callGetSingle(device, featureId);
        if (v == ON) return Boolean.TRUE;
        if (v == offValue) return Boolean.FALSE;
        return null;   // negatives and the 65534/65535 rails are errors, not states
    }

    private static boolean writeSwitch(Object device, int featureId, boolean on) {
        return BydDeviceHelper.sendSetCommand(device, featureId, on ? ON : OFF);
    }

    /**
     * The global ambient on/off, via the collector's three-tier ladder. NOT a plain
     * writeSwitch: each tier has its own off value (light main switch 0, bodywork execute 2,
     * carsettings flag 0), so sending this file's OFF=2 to the light switch was out of domain
     * and left the lights on — and a single-tier write has no fallback on signature-gated trims.
     */
    private static boolean setMainSwitch(boolean on) {
        try {
            return BydDataCollector.getInstance().setAmbientLightEnabled(on);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ok(Object result) {
        return result instanceof Integer && (Integer) result == 0;
    }

    private static Integer intOrNull(Object v) {
        if (!(v instanceof Number)) return null;
        return validOrNull(((Number) v).intValue());
    }

    /**
     * Null unless the HAL returned a real value. Negatives are error codes, and 65534/65535 are
     * the "unavailable" rails the other readers in this codebase reject — captured as a value,
     * 65535 clamps to full brightness and then applies as full brightness.
     */
    private static Integer validOrNull(int v) {
        if (v < 0 || v >= 65534) return null;
        return v;
    }

    /**
     * Let one ambient write land before the next.
     *
     * <p>Measured on the car: two zone writes issued back to back in-process lose the FIRST
     * one every time, whichever zone it is, while the same two writes made as separate HTTP
     * requests both land — the round-trip between them was the only difference. So the HAL
     * needs a moment between a zone selection and the next one, and in-process calls are fast
     * enough to outrun it.
     *
     * <p>Same shape as the seat apply, which spaces its two bodywork batches 50 ms apart for
     * the same reason. Erring generously here: the whole apply is a handful of writes on a
     * user action, so a few hundred milliseconds costs nothing anyone can feel, while being
     * too quick costs a silently-dropped setting.
     */
    private static void settle() {
        try {
            Thread.sleep(150L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void putIf(JSONObject o, String key, Integer v) throws JSONException {
        if (v != null) o.put(key, (int) v);
    }

    private static void putBool(JSONObject o, String key, Boolean v) throws JSONException {
        if (v != null) o.put(key, v.booleanValue());
    }
}
