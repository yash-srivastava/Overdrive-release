package com.overdrive.app.telemetry;

import android.os.SystemClock;

/**
 * Immutable value object holding a single point-in-time telemetry reading.
 * Thread-safe by design — all fields are final.
 */
public class TelemetrySnapshot {

    public final int speedKmh;
    public final int accelPedalPercent;     // 0-100
    public final int brakePedalPercent;     // 0-100
    public final boolean brakePedalPressed;
    public final int gearMode;              // 1-6 matching BYDAutoGearboxDevice constants
    public final boolean leftTurnSignal;
    public final boolean rightTurnSignal;
    public final boolean[] seatbeltBuckled; // indexed by seat position
    public final long timestampMs;
    public final long elapsedRealtimeMs;
    public final boolean gearValid;
    public final long gearReadElapsedRealtimeMs;
    public final boolean speedValid;
    public final long speedReadElapsedRealtimeMs;
    public final boolean accelPedalValid;
    public final long accelPedalReadElapsedRealtimeMs;
    public final boolean brakePedalValid;
    public final long brakePedalReadElapsedRealtimeMs;
    public final boolean brakePedalPressedValid;
    public final long brakePedalPressedReadElapsedRealtimeMs;

    public TelemetrySnapshot(
                             int speedKmh, int accelPedalPercent,
                             int brakePedalPercent,
                             boolean brakePedalPressed, int gearMode,
                             boolean leftTurnSignal, boolean rightTurnSignal,
                             boolean[] seatbeltBuckled, long timestampMs,
                             long elapsedRealtimeMs,
                             boolean speedValid,
                             long speedReadElapsedRealtimeMs,
                             boolean accelPedalValid,
                             long accelPedalReadElapsedRealtimeMs,
                             boolean brakePedalValid,
                             long brakePedalReadElapsedRealtimeMs,
                             boolean brakePedalPressedValid,
                             long brakePedalPressedReadElapsedRealtimeMs,
                             boolean gearValid,
                             long gearReadElapsedRealtimeMs) {
        this.speedKmh = speedKmh;
        this.accelPedalPercent = accelPedalPercent;
        this.brakePedalPercent = brakePedalPercent;
        this.brakePedalPressed = brakePedalPressed;
        this.gearMode = gearMode;
        this.leftTurnSignal = leftTurnSignal;
        this.rightTurnSignal = rightTurnSignal;
        // Defensive copy to preserve immutability
        this.seatbeltBuckled = seatbeltBuckled != null ? seatbeltBuckled.clone() : new boolean[0];
        this.timestampMs = timestampMs;
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.gearValid = gearValid
                && isValidGearMode(gearMode)
                && gearReadElapsedRealtimeMs >= 0L;
        this.gearReadElapsedRealtimeMs =
                this.gearValid ? gearReadElapsedRealtimeMs : -1L;
        this.speedValid = speedValid;
        this.speedReadElapsedRealtimeMs = speedReadElapsedRealtimeMs;
        this.accelPedalValid = accelPedalValid;
        this.accelPedalReadElapsedRealtimeMs =
                accelPedalReadElapsedRealtimeMs;
        this.brakePedalValid = brakePedalValid;
        this.brakePedalReadElapsedRealtimeMs =
                brakePedalReadElapsedRealtimeMs;
        this.brakePedalPressedValid = brakePedalPressedValid;
        this.brakePedalPressedReadElapsedRealtimeMs =
                brakePedalPressedReadElapsedRealtimeMs;
    }

    private static boolean isValidGearMode(int gearMode) {
        return gearMode >= 1 && gearMode <= 6;
    }

    /**
     * Maps gear mode constant to display character.
     * 1→'P', 2→'R', 3→'N', 4→'D', 5→'M', 6→'S', default→'?'
     */
    public char getGearChar() {
        switch (gearMode) {
            case 1: return 'P';
            case 2: return 'R';
            case 3: return 'N';
            case 4: return 'D';
            case 5: return 'M';
            case 6: return 'S';
            default: return '?';
        }
    }

    /**
     * Creates a default snapshot with safe values:
     * speed=0, nominal gear=P, signals off, belts unbuckled.
     * Speed, pedals and gear are marked invalid until real readings arrive.
     */
    public static TelemetrySnapshot createDefault() {
        return new TelemetrySnapshot(
                0,                          // speedKmh
                0,                          // accelPedalPercent
                0,                          // brakePedalPercent
                false,                      // brakePedalPressed
                1,                          // gearMode = P
                false,                      // leftTurnSignal
                false,                      // rightTurnSignal
                // seatbeltBuckled — NOT buckled-by-default. This snapshot is what the overlay
                // renders in the window between startPolling() and the first published poll, so
                // `true` painted a green ALL-CLEAR on a safety glyph from zero real data (for up
                // to one worker frame, burned into the recording). Matches
                // TelemetryDataCollector.lastSeatbelts, which is false/false for the same reason.
                new boolean[]{false, false},
                System.currentTimeMillis(), // timestampMs
                SystemClock.elapsedRealtime(),
                false, -1,
                false, -1,
                false, -1,
                false, -1,
                false, -1
        );
    }
}
