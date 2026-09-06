package com.overdrive.app.monitor;

/**
 * Classifies a DiLink 5 {@code dumpsys car_service} Power Mute State
 * {@code current} line as in-use vs parked. Kept free of Android types so
 * unit tests can cover the ACC-on mapping without Robolectric.
 */
public final class DiLink5PowerMode {

    private static final java.util.regex.Pattern CURRENT_MODE =
            java.util.regex.Pattern.compile("current:?\\s*(\\d+)=PowerMode");

    private DiLink5PowerMode() {}

    /**
     * The live value is {@code current N=PowerMode ...}. dumpsys also prints
     * {@code All items {0=PowerMode Off, 1=PowerMode Pre StartUp, ...}} on the
     * previous line; the first {@code N=PowerMode} in that enum list is always
     * Off, which must never be treated as the car's state.
     */
    public static Integer extractCurrentMode(String text) {
        if (text == null) return null;
        java.util.regex.Matcher match = CURRENT_MODE.matcher(text);
        if (!match.find()) return null;
        try {
            return Integer.parseInt(match.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * @return {@link Boolean#TRUE} if the vehicle is in use (ACC / Ready),
     *         {@link Boolean#FALSE} if parked or asleep, or {@code null} if unknown
     */
    public static Boolean classifyCurrentLine(String line) {
        if (line == null) return null;
        String text = line.trim();
        if (text.isEmpty()) return null;

        Integer mode = extractCurrentMode(text);
        if (mode != null) return classifyMode(mode);
        // No {@code current N=PowerMode} line: the All items enum list contains
        // every token and must not be classified.
        return null;
    }

    /**
     * Map a Power Mute State {@code current} line onto the bodywork power
     * levels AccSentryDaemon uses: 0=OFF, 1=ACC, 2=ON. {@code -1} if unknown.
     */
    public static int classifyBodyworkPowerLevel(String line) {
        if (line == null) return -1;
        String text = line.trim();
        if (text.isEmpty()) return -1;

        Integer mode = extractCurrentMode(text);
        if (mode != null) return bodyworkLevelForMode(mode);

        Boolean inUse = classifyCurrentLine(text);
        if (Boolean.TRUE.equals(inUse)) return 2;
        if (Boolean.FALSE.equals(inUse)) return 0;
        return -1;
    }

    /**
     * Live DiLink 5 capture:
     * <ul>
     *   <li>ACC on + HU awake is {@code current 10=DisPlay on}</li>
     *   <li>car off, panel still reachable is {@code current 4=Standby}</li>
     *   <li>car off, HU held awake (keep-alive / USB rail) is
     *       {@code current 1=Pre StartUp} — pre-ignition, not accessory on</li>
     * </ul>
     * Standby, Pre StartUp, Sleep, Off are parked. Display-on and StartUp are
     * in use. Never read the All items list.
     */
    static Boolean classifyMode(int mode) {
        switch (mode) {
            case 2:  // StartUp
            case 3:  // Degraded
            case 6:  // Reflash
            case 7:  // Remote Fota
            case 10: // DisPlay on
                return Boolean.TRUE;
            case 0:  // Off
            case 1:  // Pre StartUp — pre-ignition; HU may still be awake
            case 4:  // Standby — car off / panel asleep
            case 5:  // Str
            case 8:  // Sleep
            case 9:  // Str Suspending
            case 11: // Half Hour Mode
            case 12: // Tod
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    static int bodyworkLevelForMode(int mode) {
        switch (mode) {
            case 2:  // StartUp
            case 3:  // Degraded
            case 6:  // Reflash
            case 7:  // Remote Fota
            case 10: // DisPlay on
                return 2;
            case 0:  // Off
            case 1:  // Pre StartUp — pre-ignition, not accessory
            case 4:  // Standby
            case 5:  // Str
            case 8:  // Sleep
            case 9:  // Str Suspending
            case 11: // Half Hour Mode
            case 12: // Tod
                return 0;
            default:
                return -1;
        }
    }
}
