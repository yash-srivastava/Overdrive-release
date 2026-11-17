package com.overdrive.app.launcher.layout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative readers for scoped recovery; an unfamiliar dump never means "nothing to move". */
public final class ClusterLayoutDisplayEvidence {
    private static final Pattern DISPLAY = Pattern.compile(
            "(?i)\\bdisplayId[ =]+(\\d+)\\b");
    private static final Pattern BLOCK = Pattern.compile(
            "(?m)^\\s*Display\\s+#?(\\d+)(?:\\s|:|$)");
    private static final Pattern OWN_NAME = Pattern.compile(
            "overdrive_layout_([0-9]+)_([0-9]+)_[A-Za-z0-9_.-]{1,40}_([0-9]+)");
    private ClusterLayoutDisplayEvidence() {}

    public static boolean ownedDisplayName(String name, long generation) {
        if (name == null || generation <= 0) return false;
        Matcher owned = OWN_NAME.matcher(name);
        if (!owned.matches()) return false;
        try {
            return Long.parseLong(owned.group(1)) == generation
                    && Integer.parseInt(owned.group(2)) > 0
                    && Long.parseLong(owned.group(3)) > 0;
        } catch (NumberFormatException malformed) { return false; }
    }

    public static int fissionDisplay(String dump) {
        if (dump == null) return -1;
        int result = -1;
        for (String line : dump.split("\\r?\\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("fission") || lower.contains("shared_fission")
                    || !lower.contains("layerstack")
                    || lower.matches(".*\\bstate[ =]+(off|unknown)\\b.*")) continue;
            Matcher id = DISPLAY.matcher(line);
            if (!id.find()) continue;
            int candidate = number(id.group(1));
            if (candidate <= 0) continue;
            if (result > 0 && result != candidate) return -1;
            result = candidate;
        }
        return result;
    }

    /** 1 exact identity, 0 verified absent, -1 ambiguous/recycled identity/unreadable. */
    public static int displayIdentity(String dump, int displayId, String name, String uniqueId) {
        if (dump == null || displayId <= 0 || name == null || name.isEmpty()
                || !dump.contains("DisplayInfo")) return -1;
        boolean seen = false;
        Pattern exactName = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name)
                + "(?![A-Za-z0-9_])");
        for (String line : dump.split("\\r?\\n")) {
            if (!line.contains("DisplayInfo")) continue;
            Matcher id = DISPLAY.matcher(line);
            if (!id.find() || number(id.group(1)) != displayId) continue;
            seen = true;
            if (!exactName.matcher(line).find()) return -1;
        }
        // OEM dumps do not consistently include a unique ID in DisplayInfo. The private
        // name already includes generation/slot/sequence; the ID is never sufficient alone.
        return seen ? 1 : 0;
    }

    /** null unreadable, true any task/activity of this package, false verified no such task. */
    public static Boolean packageOnDisplay(String dump, int displayId, String packageName) {
        if (dump == null || displayId <= 0 || !LayoutPreset.validPackage(packageName)
                || !dump.contains("ACTIVITY MANAGER ACTIVITIES")) return null;
        Matcher blocks = BLOCK.matcher(dump);
        int start = -1, end = dump.length();
        boolean any = false;
        while (blocks.find()) {
            any = true;
            int candidate = number(blocks.group(1));
            if (start >= 0) { end = blocks.start(); break; }
            if (candidate == displayId) start = blocks.end();
        }
        if (!any) return null;
        if (start < 0) return false;
        String section = dump.substring(start, end);
        Pattern component = Pattern.compile("(?<![A-Za-z0-9_.])"
                + Pattern.quote(packageName) + "/[A-Za-z0-9_.$]+");
        return component.matcher(section).find();
    }

    private static int number(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return -1; }
    }
}
