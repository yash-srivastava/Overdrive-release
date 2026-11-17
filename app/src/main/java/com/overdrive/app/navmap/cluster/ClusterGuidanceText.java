package com.overdrive.app.navmap.cluster;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Road labels for the OEM strip, whose arrow already carries the maneuver direction. */
public final class ClusterGuidanceText {
    // No UNICODE_CHARACTER_CLASS: Android rejects it ("Unsupported flags: 256") and the class
    // fails to load mid-navigation. Unicode spaces and case are spelled out instead.
    private static final Pattern TURN = Pattern.compile(
            "^turn[\\s\\p{Z}]+(left|right)(?:[\\s\\p{Z}]+onto[\\s\\p{Z}]+(.+)|[\\s\\p{Z}]*[.!]?)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ClusterGuidanceText() {}

    public static String roadLabel(List<String> streetNames, String instruction, int maneuverType) {
        // The OEM field is a single road name. Joining aliases needlessly fills its small viewport.
        if (streetNames != null) {
            for (String street : streetNames) {
                String name = trim(street);
                if (!name.isEmpty()) return name;
            }
        }
        String text = trim(instruction);
        Matcher turn = TURN.matcher(text);
        if (turn.matches()) {
            boolean left = "left".equalsIgnoreCase(turn.group(1));
            // Keep ambiguous or conflicting instructions intact; do not infer a new direction.
            if (maneuverType == (left ? 15 : 10)) {
                String road = trim(turn.group(2));
                return road.isEmpty() ? (left ? "Left" : "Right") : road;
            }
        }
        // Other languages and complex instructions retain their existing wording.
        return text;
    }

    private static String trim(String value) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && whitespace(value.charAt(start))) start++;
        while (end > start && whitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean whitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }
}
