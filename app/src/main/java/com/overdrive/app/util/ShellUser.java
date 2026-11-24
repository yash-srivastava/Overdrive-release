package com.overdrive.app.util;

/**
 * The user pm grant / appops must target. Headless-system-user units (DiLink 100) run the
 * app as user 10, but pm grant defaults to user 0.
 */
public final class ShellUser {
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;

    private ShellUser() {
    }

    /** The app's own user; from a shell daemon the foreground user, or -1 if unreadable. */
    public static int targetUserId() {
        int uid = android.os.Process.myUid();
        if (uid % PER_USER_RANGE >= FIRST_APPLICATION_UID) return uid / PER_USER_RANGE;
        try {
            Object user = Class.forName("android.app.ActivityManager")
                    .getMethod("getCurrentUser").invoke(null);
            return user instanceof Integer && (Integer) user >= 0 ? (Integer) user : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Empty when unresolved, so the command keeps its own default (appops: current user). */
    public static String flag() {
        int user = targetUserId();
        return user >= 0 ? "--user " + user : "";
    }
}
