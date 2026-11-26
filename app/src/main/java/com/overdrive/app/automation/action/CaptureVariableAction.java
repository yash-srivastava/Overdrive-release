package com.overdrive.app.automation.action;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.StringType;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.AmbientProbe;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Captures a current vehicle value into a named automation variable.
 *
 * <p>Unlike a dynamic reference, the stored value is a snapshot. This lets a
 * rule remember the current media volume or ambient lighting before changing it,
 * or retain the SOC at the point a trip phase starts.
 */
public class CaptureVariableAction extends DeclaredAction {
    private static final String TYPE = "captureVariable";
    private static final String SOURCE_MEDIA_VOLUME = "mediaVolume";
    private static final String SOURCE_BATTERY_SOC = "batterySoc";
    private static final String SOURCE_AMBIENT_COLOUR_FRONT = "ambientColourFront";
    private static final String SOURCE_AMBIENT_COLOUR_REAR = "ambientColourRear";
    private static final String SOURCE_AMBIENT_BRIGHTNESS_FRONT = "ambientBrightnessFront";
    private static final String SOURCE_AMBIENT_BRIGHTNESS_REAR = "ambientBrightnessRear";
    private static final int AMBIENT_LEVEL_MAX = 5;
    private static final long DI5_READ_WINDOW_MS = 2000;
    private static final long DI5_READ_RETRY_MS = 400;

    public CaptureVariableAction(Label label, String description) {
        super(TYPE, label, description, List.of(
                new EnumType(new Label("source", "automation.capture_source"),
                        new Label(SOURCE_MEDIA_VOLUME, "automation.capture_media_volume"),
                        new Label(SOURCE_BATTERY_SOC, "automation.capture_battery_soc"),
                        new Label(SOURCE_AMBIENT_COLOUR_FRONT, "automation.capture_ambient_colour_front"),
                        new Label(SOURCE_AMBIENT_COLOUR_REAR, "automation.capture_ambient_colour_rear"),
                        new Label(SOURCE_AMBIENT_BRIGHTNESS_FRONT, "automation.capture_ambient_brightness_front"),
                        new Label(SOURCE_AMBIENT_BRIGHTNESS_REAR, "automation.capture_ambient_brightness_rear")),
                new StringType(new Label("name", "automation.variable_name"), SetVariableAction.MAX_NAME)));
    }

    @Override
    public void trigger(AutomationAction automationAction) {
        Map<String, Object> vars = automationAction.getVariables();
        String name = string(vars.get("name"));
        if (name == null || name.trim().isEmpty()) {
            logger.warn("CaptureVariableAction: missing variable name, skipping");
            return;
        }
        name = name.trim();
        if (name.length() > SetVariableAction.MAX_NAME) {
            name = name.substring(0, SetVariableAction.MAX_NAME);
        }

        String source = string(vars.get("source"));
        String value;
        if (SOURCE_MEDIA_VOLUME.equals(source)) {
            value = readMediaVolume();
        } else if (SOURCE_BATTERY_SOC.equals(source)) {
            value = readBatterySoc();
        } else if (SOURCE_AMBIENT_COLOUR_FRONT.equals(source)) {
            value = readAmbient("front", false);
        } else if (SOURCE_AMBIENT_COLOUR_REAR.equals(source)) {
            value = readAmbient("rear", false);
        } else if (SOURCE_AMBIENT_BRIGHTNESS_FRONT.equals(source)) {
            value = readAmbient("front", true);
        } else if (SOURCE_AMBIENT_BRIGHTNESS_REAR.equals(source)) {
            value = readAmbient("rear", true);
        } else {
            logger.warn("CaptureVariableAction: unknown source '" + source + "'");
            return;
        }
        if (value == null) {
            logger.warn("CaptureVariableAction: " + source + " is unavailable; leaving '"
                    + name + "' unchanged");
            return;
        }

        Automations.update(SetVariableAction.variableEvent(name), value);
        logger.info("CaptureVariableAction: " + name + " = " + value + " [" + source + "]");
    }

    private static String readMediaVolume() {
        try {
            Context context = CameraDaemon.getAppContext();
            if (context == null) return null;
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) <= 0) {
                return null;
            }
            int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            return volume >= 0 ? Integer.toString(volume) : null;
        } catch (Throwable t) {
            logger.warn("CaptureVariableAction: media volume read failed: " + t.getMessage());
            return null;
        }
    }

    private static String readBatterySoc() {
        try {
            com.overdrive.app.automation.value.Value state =
                    Automations.getStateValue(BydEvent.BATTERY_LEVEL);
            if (state instanceof com.overdrive.app.automation.value.BaseValue<?>) {
                Object value = ((com.overdrive.app.automation.value.BaseValue<?>) state).getValue();
                if (value != null) return value.toString();
            }
        } catch (Throwable ignored) {
            // Fall through to the latest snapshot; it can be present before state is seeded.
        }
        try {
            BydVehicleData data = BydDataCollector.getInstance().getData();
            if (data == null || Double.isNaN(data.socPercent)
                    || data.socPercent < 0 || data.socPercent > 100) {
                return null;
            }
            return Integer.toString((int) data.socPercent);
        } catch (Throwable t) {
            logger.warn("CaptureVariableAction: SOC read failed: " + t.getMessage());
            return null;
        }
    }

    private static String readAmbient(String zone, boolean brightness) {
        try {
            Context context = CameraDaemon.getAppContext();
            if (context == null) return null;
            // DI5 setters wait up to 2 s for the adapter; give a just-woken car's read the same window.
            long deadline = SystemClock.elapsedRealtime() + DI5_READ_WINDOW_MS;
            String value = ambientValue(AmbientProbe.read(context), zone, brightness);
            while (value == null && DiLink5Platform.isSelected()
                    && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(DI5_READ_RETRY_MS);
                value = ambientValue(AmbientProbe.read(context), zone, brightness);
            }
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            logger.warn("CaptureVariableAction: ambient read failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * One zone's colour (palette index) or brightness (percent, the unit Set Ambient
     * Brightness takes) from an {@link AmbientProbe#read} capture. A brightness of 0 counts
     * only when the zone also reports a colour, since a sleeping car answers 0 for both.
     */
    static String ambientValue(JSONObject ambient, String zone, boolean brightness) {
        JSONObject z = ambient == null ? null : ambient.optJSONObject(zone);
        if (z == null) return null;
        int colour = z.optInt("colour", 0);
        if (!brightness) return colour >= 1 ? Integer.toString(colour) : null;
        if (!z.has("brightness")) return null;
        int level = z.optInt("brightness", -1);
        if (level < 0 || level > AMBIENT_LEVEL_MAX || (level == 0 && colour < 1)) return null;
        return Integer.toString(level * 20);
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
