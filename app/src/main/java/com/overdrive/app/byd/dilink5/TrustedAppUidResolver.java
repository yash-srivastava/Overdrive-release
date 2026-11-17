package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;

/** Resolves the installed app UID for the Android user that owns the daemon process. */
public final class TrustedAppUidResolver {
    public static final String APP_PACKAGE = "com.overdrive.app";
    private static final int PER_USER_RANGE = 100000;

    private final Context context;

    public TrustedAppUidResolver(Context context) {
        this.context = context;
    }

    /** Returns -1 when package identity or user ownership cannot be established. */
    public int resolve() {
        if (context == null) return -1;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(APP_PACKAGE, 0);
            int uid = info != null ? info.uid : -1;
            if (uid < 0 || userId(uid) != userId(Process.myUid())) return -1;
            return uid;
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    static int userId(int uid) {
        return uid < 0 ? -1 : uid / PER_USER_RANGE;
    }
}
