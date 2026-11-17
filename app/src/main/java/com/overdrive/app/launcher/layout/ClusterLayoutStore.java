package com.overdrive.app.launcher.layout;

import com.overdrive.app.config.UnifiedConfigManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Preset persistence shares the existing config lock and never replaces the projection section. */
public final class ClusterLayoutStore {
    private static final String KEY = "clusterLayoutPresets";
    public static final int MAX_PRESETS = 16;

    private ClusterLayoutStore() {}

    public static List<LayoutPreset> list() {
        JSONObject projection = UnifiedConfigManager.forceReload().optJSONObject("projection");
        return read(projection == null ? null : projection.optJSONArray(KEY));
    }

    public static LayoutPreset find(String id) {
        for (LayoutPreset preset : list()) if (preset.id.equals(id)) return preset;
        return null;
    }

    public static boolean save(LayoutPreset preset) {
        return UnifiedConfigManager.runUnderConfigLock(() -> {
            List<LayoutPreset> saved = new ArrayList<>(list());
            saved.removeIf(existing -> existing.id.equals(preset.id));
            if (saved.size() >= MAX_PRESETS) return false;
            saved.add(preset);
            return persist(saved);
        });
    }

    public static boolean delete(String id) {
        return UnifiedConfigManager.runUnderConfigLock(() -> {
            List<LayoutPreset> saved = new ArrayList<>(list());
            boolean removed = saved.removeIf(existing -> existing.id.equals(id));
            return !removed || persist(saved);
        });
    }

    public static JSONArray jsonList() throws Exception {
        JSONArray result = new JSONArray();
        for (LayoutPreset preset : list()) result.put(toJson(preset));
        return result;
    }

    public static LayoutPreset fromJson(JSONObject json) throws Exception {
        if (json.has("version") && integer(json, "version") != 1)
            throw new IllegalArgumentException("Unsupported layout version");
        JSONArray array = json.optJSONArray("slots");
        if (array == null || array.length() < 1 || array.length() > LayoutPreset.MAX_SLOTS)
            throw new IllegalArgumentException("A layout needs 1–4 apps");
        List<LayoutPreset.Slot> slots = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject slot = array.getJSONObject(i);
            slots.add(new LayoutPreset.Slot(requiredString(slot, "id"), requiredString(slot, "package"),
                    integer(slot, "x"), integer(slot, "y"), integer(slot, "width"),
                    integer(slot, "height"), slot.has("dpi") ? integer(slot, "dpi") : 0));
        }
        return new LayoutPreset(requiredString(json, "id"), requiredString(json, "name"), slots);
    }

    public static JSONObject toJson(LayoutPreset preset) throws Exception {
        JSONArray slots = new JSONArray();
        for (LayoutPreset.Slot slot : preset.slots) {
            slots.put(new JSONObject().put("id", slot.id).put("package", slot.packageName)
                    .put("x", slot.x).put("y", slot.y).put("width", slot.width)
                    .put("height", slot.height).put("dpi", slot.dpi));
        }
        return new JSONObject().put("version", 1).put("id", preset.id)
                .put("name", preset.name).put("slots", slots);
    }

    private static boolean persist(List<LayoutPreset> saved) {
        try {
            JSONArray array = new JSONArray();
            for (LayoutPreset preset : saved) array.put(toJson(preset));
            return UnifiedConfigManager.updateValues("projection", Collections.singletonMap(KEY, array));
        } catch (Exception failure) {
            return false;
        }
    }

    private static List<LayoutPreset> read(JSONArray array) {
        List<LayoutPreset> result = new ArrayList<>();
        if (array == null || array.length() > MAX_PRESETS) return result;
        for (int i = 0; i < array.length(); i++) {
            try {
                LayoutPreset preset = fromJson(array.getJSONObject(i));
                if (result.stream().noneMatch(existing -> existing.id.equals(preset.id))) result.add(preset);
            } catch (Exception invalidPreset) {
                // A corrupt preset cannot admit unsafe displays or app identities.
            }
        }
        return result;
    }

    private static String requiredString(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key);
        return (String) value;
    }

    private static int integer(JSONObject json, String key) throws Exception {
        Object raw = json.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("Invalid " + key);
        Number number = (Number) raw;
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != number.intValue())
            throw new IllegalArgumentException("Invalid " + key);
        return number.intValue();
    }
}
