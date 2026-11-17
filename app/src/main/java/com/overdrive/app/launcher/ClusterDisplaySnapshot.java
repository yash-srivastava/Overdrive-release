package com.overdrive.app.launcher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logical display evidence from dumpsys, including displays hidden from the shell's DisplayManager.
 * A device entry, a display ID alone, or a guessed layer stack cannot authorize projection.
 */
public final class ClusterDisplaySnapshot {
    private static final Pattern INFO = Pattern.compile("DisplayInfo\\{\"([^\"\\r\\n]+)\"");
    private static final Pattern ID = Pattern.compile("(?i)\\bdisplayId[ =]+(\\d+)\\b");
    private static final Pattern NAME_ID = Pattern.compile("(?i),\\s*displayId[ =]+\\d+\\s*$");
    private static final Pattern UNIQUE = Pattern.compile("\\buniqueId\\s+\"([^\"]*)\"");
    private static final Pattern SIZE = Pattern.compile("\\breal\\s+(\\d+)\\s*x\\s*(\\d+)\\b");
    private static final Pattern STACK = Pattern.compile("(?i)\\blayerStack[ =]+(\\d+)\\b");
    private static final Pattern DENSITY = Pattern.compile("\\bdensity[ =]+(\\d+)\\b");
    private static final Pattern STATE = Pattern.compile("(?i)\\bstate[ =]+([A-Z_]+)\\b");

    public final int displayId, width, height, layerStack, densityDpi;
    public final String name, uniqueId, state;

    private ClusterDisplaySnapshot(int displayId, String name, String uniqueId,
            int width, int height, int layerStack, int densityDpi, String state) {
        this.displayId = displayId;
        this.name = name;
        this.uniqueId = uniqueId;
        this.width = width;
        this.height = height;
        this.layerStack = layerStack;
        this.densityDpi = densityDpi;
        this.state = state;
    }

    /** Returns null for an absent, ambiguous or default display. */
    public static ClusterDisplaySnapshot find(String dump, int displayId) {
        if (dump == null || displayId <= 0) return null;
        ClusterDisplaySnapshot found = null;
        Matcher infos = INFO.matcher(dump);
        while (infos.find()) {
            int end = entryEnd(dump, infos.start());
            if (end < 0) continue;
            ClusterDisplaySnapshot next = parse(infos.group(1), dump.substring(infos.start(), end));
            infos.region(end, dump.length());
            if (next == null || next.displayId != displayId) continue;
            if (found != null) {
                if (!found.name.equals(next.name)
                        || (!found.uniqueId.isEmpty() && !next.uniqueId.isEmpty()
                            && !found.uniqueId.equals(next.uniqueId))
                        || (found.layerStack >= 0 && next.layerStack >= 0
                            && found.layerStack != next.layerStack)) return null;
                // Base and override entries describe the same logical display. Keep the
                // later geometry without discarding a stable ID absent from that entry.
                next = new ClusterDisplaySnapshot(displayId, next.name,
                        next.uniqueId.isEmpty() ? found.uniqueId : next.uniqueId,
                        next.width > 0 ? next.width : found.width,
                        next.height > 0 ? next.height : found.height,
                        next.layerStack >= 0 ? next.layerStack : found.layerStack,
                        next.densityDpi > 0 ? next.densityDpi : found.densityDpi,
                        next.state.isEmpty() ? found.state : next.state);
            }
            found = next;
        }
        return found;
    }

    /** DI3 only: a live fission display with complete compositor geometry. */
    public boolean usableFission() {
        String lower = name.toLowerCase(Locale.ROOT);
        return displayId > 0 && lower.contains("fission") && !lower.contains("shared_fission")
                && width > 1 && height > 1 && layerStack > 0 && state.equals("ON");
    }

    public boolean sameIdentity(ClusterDisplaySnapshot other) {
        return other != null && displayId == other.displayId && name.equals(other.name)
                && (uniqueId.isEmpty() || uniqueId.equals(other.uniqueId));
    }

    public boolean sameCompositorTarget(ClusterDisplaySnapshot other) {
        return sameIdentity(other) && other.usableFission() && layerStack == other.layerStack
                && width == other.width && height == other.height;
    }

    private static ClusterDisplaySnapshot parse(String rawName, String text) {
        int id = number(ID, text, -1);
        if (id <= 0) return null;
        String name = NAME_ID.matcher(rawName).replaceFirst("").trim();
        if (name.isEmpty()) return null;
        Matcher unique = UNIQUE.matcher(text);
        String uniqueId = unique.find() ? unique.group(1) : "";
        if (uniqueId.equals("null")) uniqueId = "";
        Matcher size = SIZE.matcher(text);
        int width = 0, height = 0;
        if (size.find()) {
            width = bounded(size.group(1), 16_384);
            height = bounded(size.group(2), 16_384);
            if (width <= 0 || height <= 0) { width = 0; height = 0; }
        }
        Matcher state = STATE.matcher(text);
        return new ClusterDisplaySnapshot(id, name, uniqueId, width, height,
                number(STACK, text, -1), number(DENSITY, text, 0),
                state.find() ? state.group(1).toUpperCase(Locale.ROOT) : "");
    }

    /** HDR capabilities contain nested braces; stopping at the first brace loses state/stack. */
    private static int entryEnd(String text, int start) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return -1;
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        return -1;
    }

    private static int number(Pattern pattern, String text, int missing) {
        Matcher match = pattern.matcher(text);
        if (!match.find()) return missing;
        try { return Integer.parseInt(match.group(1)); }
        catch (NumberFormatException malformed) { return missing; }
    }

    private static int bounded(String text, int maximum) {
        try {
            int value = Integer.parseInt(text);
            return value > 0 && value <= maximum ? value : 0;
        } catch (NumberFormatException malformed) { return 0; }
    }
}
