package com.overdrive.app.navmap.cluster;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative notification parser: unknown maneuvers never invent a direction. */
public final class ClusterNavigationParser {
    // No UNICODE_CHARACTER_CLASS: Android rejects it ("Unsupported flags: 256"). Digits are already
    // normalized to ASCII; the Unicode word end, spaces and case are spelled out so JVM and ART agree.
    private static final String WORD_END = "(?![\\p{L}\\p{N}_])";
    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)[\\s\\p{Z}]*(kilometers?|kilometres?|km|км|كم|miles?|mi|meters?|metres?|mt|m|м|م|feet|ft|yards?|yd)"
                    + WORD_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)[\\s\\p{Z}]*(hours?|hrs?|h|heures?|час[^\\s\\p{Z}]*|ч|ساعة|س|minutes?|mins?|min|мин[^\\s\\p{Z}]*|دقيقة|د)"
                    + WORD_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ROUND_EXIT = Pattern.compile(
            "(?:(\\d+)(?:st|nd|rd|th|er|e|ème)?\\s+(?:exit|sortie|salida|uscita)"
                    + "|(?:exit|sortie|salida|uscita|ausfahrt|выезд|יציאה|مخرج)\\s+(\\d+))",
            Pattern.CASE_INSENSITIVE);
    private ClusterNavigationParser() {}

    public static ClusterGuidanceFrame parse(String source, String title, String text,
            String subText, String resourceName) {
        String instruction = (safe(title) + " " + safe(text)).toLowerCase(Locale.ROOT);
        int canIcon = canIconFromResource(safe(resourceName).toLowerCase(Locale.ROOT));
        if (canIcon < 0) canIcon = ClusterNavigationPhrases.canIcon(instruction);
        int type = canIcon >= 0 ? typeForCanIcon(canIcon) : typeFromText(instruction);
        int distance = distance(instruction);
        if (type < 0 || distance < 0) return null;
        int exit = 0;
        if (type == 26) {
            Matcher roundabout = ROUND_EXIT.matcher(instruction);
            if (roundabout.find()) {
                try {
                    exit = Integer.parseInt(roundabout.group(1) == null ? roundabout.group(2) : roundabout.group(1));
                    if (exit < 1 || exit > 10) exit = 0;
                } catch (NumberFormatException ignored) {}
            }
            if (exit > 0 && canIcon >= 25 && canIcon <= 44) canIcon = (canIcon < 35 ? 24 : 34) + exit;
        }
        // Extract only unambiguous road labels; other translated instructions stay intact.
        // Never use an ETA/destination subtext as the road to turn onto.
        String label = ClusterGuidanceText.roadLabel(null,
                safe(text).isEmpty() ? safe(title) : safe(text), type);
        return new ClusterGuidanceFrame(source, type, distance, label,
                distance(safe(subText)), duration(safe(subText)), exit, Math.max(0, canIcon));
    }

    public static int duration(String text) {
        Matcher match = DURATION.matcher(normalizeDigits(safe(text)));
        long seconds = 0;
        boolean found = false;
        while (match.find()) {
            found = true;
            String unit = match.group(2).toLowerCase(Locale.ROOT);
            long value;
            try { value = Long.parseLong(match.group(1)); }
            catch (NumberFormatException invalid) { return -1; }
            if (value > Integer.MAX_VALUE) return -1;
            seconds += value * (unit.startsWith("h") || unit.startsWith("ч")
                    || unit.equals("ساعة") || unit.equals("س") ? 3600 : 60);
        }
        return found && seconds <= Integer.MAX_VALUE ? (int) seconds : -1;
    }

    public static int distance(String text) {
        Matcher match = DISTANCE.matcher(normalizeDigits(safe(text)));
        if (!match.find()) return -1;
        try {
            double value = Double.parseDouble(match.group(1).replace(',', '.'));
            String unit = match.group(2).toLowerCase(Locale.ROOT);
            double scale = unit.startsWith("k") || unit.equals("км") || unit.equals("كم") ? 1000
                    : unit.startsWith("mi") ? 1609.344
                    : unit.equals("feet") || unit.equals("ft") ? 0.3048
                    : unit.equals("yd") || unit.startsWith("yard") ? 0.9144 : 1;
            double meters = value * scale;
            return meters <= Integer.MAX_VALUE ? (int) Math.round(meters) : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    public static int typeFromResource(String name) {
        int icon = canIconFromResource(name);
        return icon < 0 ? -1 : typeForCanIcon(icon);
    }

    private static int canIconFromResource(String name) {
        boolean right = name.contains("right"), left = name.contains("left");
        if (name.contains("uturn") || name.contains("u_turn")) return right ? 10 : 9;
        if (name.contains("roundabout")) return name.contains("ccw") ? 25 : 35;
        if (name.contains("arriv") || name.contains("destination") || name.contains("finish")) return 48;
        if (name.contains("sharp")) return right ? 8 : left ? 7 : -1;
        if (name.contains("slight") || name.contains("keep") || name.contains("fork")
                || name.contains("merge") || name.contains("ramp") || name.contains("exit"))
            return right ? 5 : left ? 3 : -1;
        if (name.contains("turn") || name.contains("arrow")) return right ? 2 : left ? 1 : -1;
        if (name.contains("straight") || name.contains("continue")) return 11;
        if (name.contains("tollbooth")) return 47;
        if (name.contains("tunnel")) return 49;
        return -1;
    }

    private static int typeForCanIcon(int icon) {
        switch (icon) {
            case 1: return 15;
            case 2: return 10;
            case 3: case 4: case 14: return 16;
            case 5: case 6: case 13: return 9;
            case 7: return 14;
            case 8: return 11;
            case 9: return 13;
            case 10: return 12;
            case 48: return 4;
            default: return icon >= 15 && icon <= 44 ? 26 : 8;
        }
    }

    public static int typeFromText(String text) {
        text = safe(text).toLowerCase(Locale.ROOT);
        int phrase = ClusterNavigationPhrases.canIcon(text);
        if (phrase >= 0) return typeForCanIcon(phrase);
        text = ClusterNavigationPhrases.instructionText(text);
        // Specific instructions precede the generic turn/left/right entries.
        if (contains(text, "u-turn", "u turn", "demi-tour", "развернит")) return 13;
        if (contains(text, "roundabout", "rond-point", "rotonda", "кольцев")) return 26;
        boolean right = words(text, "right", "droite", "derecha", "destra", "rechts", "направо", "ימינה");
        boolean left = words(text, "left", "gauche", "izquierda", "sinistra", "links", "налево", "שמאלה");
        if (right == left) return contains(text, "continue", "straight", "tout droit", "прямо") ? 8 : -1;
        if (contains(text, "sharp", "hard", "круто")) return right ? 11 : 14;
        if (contains(text, "slight", "keep", "bear", "держитесь")) return right ? 9 : 16;
        return words(text, "turn", "tournez", "gira", "gire", "dobla", "svolta",
                "abbiegen", "biegen", "поверните", "פנה", "פנו") ? right ? 10 : 15
                : contains(text, "continue", "straight", "tout droit", "прямо") ? 8 : -1;
    }

    private static boolean words(String text, String... words) {
        for (String word : words) {
            if (Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(word)
                    + "(?![\\p{L}\\p{N}_])").matcher(text).find()) return true;
        }
        return false;
    }
    private static boolean contains(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String normalizeDigits(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.digit(c, 10);
            out.append(digit >= 0 ? (char) ('0' + digit) : c);
        }
        return out.toString();
    }
}
