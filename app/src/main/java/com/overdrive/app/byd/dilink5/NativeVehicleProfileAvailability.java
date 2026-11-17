package com.overdrive.app.byd.dilink5;

/** Keeps the optional profile absent from every older-generation settings mode. */
public final class NativeVehicleProfileAvailability {
    private NativeVehicleProfileAvailability() {}

    public static boolean visible(boolean diLink5Selected) {
        return diLink5Selected;
    }
}
