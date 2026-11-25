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
        try {
            int uid = android.os.Process.myUid();
            if (uid % PER_USER_RANGE >= FIRST_APPLICATION_UID) return uid / PER_USER_RANGE;
            Object user = Class.forName("android.app.ActivityManager")
                    .getMethod("getCurrentUser").invoke(null);
            return user instanceof Integer && (Integer) user >= 0 ? (Integer) user : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * {@code context} for the driver's user when that is not this process's user (a user-0
     * daemon on DiLink 100), so package queries see that user's apps. Otherwise unchanged.
     */
    public static android.content.Context context(android.content.Context context) {
        int user = targetUserId();
        if (context == null || user < 0 || android.os.Build.VERSION.SDK_INT < 30
                || user == android.os.Process.myUid() / PER_USER_RANGE) return context;
        try {
            return (android.content.Context) context.getClass()
                    .getMethod("createContextAsUser", android.os.UserHandle.class, int.class)
                    .invoke(context, android.os.UserHandle.getUserHandleForUid(user * PER_USER_RANGE), 0);
        } catch (Throwable unavailable) {
            return context;
        }
    }

    /** Empty when unresolved, so the command keeps its own default (appops: current user). */
    public static String flag() {
        int user = targetUserId();
        return user >= 0 ? "--user " + user : "";
    }
}
