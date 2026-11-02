package com.overdrive.app.logging;

public final class DaemonLogger {
    public static DaemonLogger getInstance(String tag) { return new DaemonLogger(); }
    public void warn(String message) { throw new AssertionError("Unexpected poll failure: " + message); }
}
