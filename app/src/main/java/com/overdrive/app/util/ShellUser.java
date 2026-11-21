package com.overdrive.app.util;

/**
 * The Android user that {@code pm grant} / {@code appops set|get} must target for
 * OverDrive. Without {@code --user} those commands apply to user 0, which is the driver
 * only on single-user head units. On headless multi-user units (DiLink 100) the driver,
 * and the app, run in another user (10), so grants made for user 0 never reach the app.
 *
 * <p>From the app process this is the app's own user. From a shell daemon it is the
 * current (foreground) user, falling back to 0. On single-user units both are 0, the
 * same as the commands' default.
 */
public final class ShellUser {
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_APPLICATION_UID = 10000;

    private ShellUser() {
    }

    public static int targetUserId() {
        int uid = android.os.Process.myUid();
        if (uid % PER_USER_RANGE >= FIRST_APPLICATION_UID) return uid / PER_USER_RANGE;
        try {
            Object user = Class.forName("android.app.ActivityManager")
                    .getMethod("getCurrentUser").invoke(null);
            return user instanceof Integer ? (Integer) user : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** {@code "--user <id>"} for pm grant / appops set|get. */
    public static String flag() {
        return "--user " + targetUserId();
    }
}
