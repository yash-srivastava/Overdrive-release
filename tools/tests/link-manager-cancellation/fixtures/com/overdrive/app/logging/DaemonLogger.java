package com.overdrive.app.logging;

public final class DaemonLogger {
    public static DaemonLogger getInstance(String tag) { return new DaemonLogger(); }
    public void warn(String message) { com.overdrive.app.safekeep.LinkManagerCancellationTest.recordWarning(message); }
}
