package com.overdrive.app.monitor;

import com.overdrive.app.server.Messages;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Localizes {@link ParkedPosition#unavailableReason()} English diagnostics for users. */
public final class ParkedPositionMessages {

    private static final Pattern DRIVEN = Pattern.compile(
            "^car has driven ([0-9.]+) km since its last GPS fix$");

    private ParkedPositionMessages() {}

    public static String localize(String english) {
        if (english == null || english.isEmpty()) return "";
        Matcher m = DRIVEN.matcher(english);
        if (m.matches()) {
            return Messages.get("parked_position.unavailable_driven_since_fix", m.group(1));
        }
        switch (english) {
            case "no GPS fix recorded since OverDrive started":
                return Messages.get("parked_position.unavailable_no_gps");
            case "no odometer reading before the last GPS fix":
                return Messages.get("parked_position.unavailable_no_odometer_before_fix");
            case "odometer unavailable":
                return Messages.get("parked_position.unavailable_odometer_unavailable");
            case "odometer reads lower than at the last GPS fix":
                return Messages.get("parked_position.unavailable_odometer_lower");
            default:
                return english;
        }
    }
}