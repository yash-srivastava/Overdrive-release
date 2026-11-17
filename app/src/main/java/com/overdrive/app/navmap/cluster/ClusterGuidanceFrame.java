package com.overdrive.app.navmap.cluster;

/**
 * A vendor-independent guidance snapshot. Types use Valhalla's maneuver namespace (the
 * Google Routes adapter already converts into it). Protocol facts verified against
 * Kiroha/byd-dashcast; see THIRD_PARTY_NOTICES-dashcast.txt.
 */
public final class ClusterGuidanceFrame {
    public final String source;
    public final int maneuverType, distanceMeters, remainingDistanceMeters, remainingTimeSeconds;
    public final String roadName;
    public final int roundaboutExit;
    public final int canIconOverride;

    public ClusterGuidanceFrame(String source, int type, int distance, String road,
            int remainingDistance, int remainingTime, int exit) {
        this(source, type, distance, road, remainingDistance, remainingTime, exit, 0);
    }

    public ClusterGuidanceFrame(String source, int type, int distance, String road,
            int remainingDistance, int remainingTime, int exit, int canIconOverride) {
        if (!validSource(source)) throw new IllegalArgumentException("unsupported guidance source");
        if (distance < 0 || remainingDistance < -1 || remainingTime < -1)
            throw new IllegalArgumentException("invalid guidance distance or duration");
        if (canIconOverride < 0 || canIconOverride > 49)
            throw new IllegalArgumentException("invalid CAN guidance icon");
        this.source = source;
        this.maneuverType = type;
        this.distanceMeters = distance;
        this.roadName = truncateStreet(road == null ? "" : road);
        this.remainingDistanceMeters = remainingDistance;
        this.remainingTimeSeconds = remainingTime;
        this.roundaboutExit = Math.max(0, Math.min(10, exit));
        this.canIconOverride = canIconOverride;
    }

    public static boolean validSource(String source) {
        return "roadsense".equals(source) || "maps".equals(source) || "waze".equals(source);
    }

    private static String truncateStreet(String value) {
        // Keep the CAN buffer bounded without splitting a UTF-16 surrogate pair.
        int end = Math.min(128, value.length());
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).replace('\0', ' ');
    }

    /** CAN icon identifiers and cluster identifiers are separate namespaces. */
    public int canIcon() {
        if (canIconOverride != 0) return canIconOverride;
        switch (maneuverType) {
            case 4: case 5: case 6: return 48;
            case 9: case 18: case 20: case 23: case 37: return 5;
            case 10: return 2;
            case 11: return 8;
            case 12: return 10;
            case 13: return 9;
            case 14: return 7;
            case 15: return 1;
            case 16: case 19: case 21: case 24: case 38: return 3;
            case 26: case 27:
                return roundaboutExit > 0 ? 24 + roundaboutExit : 15;
            default: return 11;
        }
    }

    public int clusterIcon() {
        switch (canIcon()) {
            case 1: return 2;
            case 2: return 3;
            case 3: case 4: case 14: return 4;
            case 5: case 6: case 13: return 5;
            case 7: return 6;
            case 8: return 7;
            case 9: return 8;
            case 10: return 19; // Zero is a cluster NO-OP, not a right U-turn.
            case 12: return 20;
            case 46: return 13;
            case 47: return 14;
            case 48: return 15;
            case 49: return 16;
            default:
                int icon = canIcon();
                if (icon >= 25 && icon <= 34 || icon == 15 || icon == 16 || icon == 19) return 17;
                if (icon >= 15 && icon <= 44) return 11;
                return maneuverType == 26 || maneuverType == 27 ? 17 : 9;
        }
    }
}
