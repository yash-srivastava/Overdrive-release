package com.overdrive.app.camera.dilink5;

/**
 * Shell lines that retire the DiLink 5 capture children (fast_cam_capture,
 * qcarcam_test) from the app's daemon kill cascades (stop, cleanup,
 * post-update hard reset, updater).
 *
 * fast_cam_capture stops its QCarCam streams cleanly on SIGTERM (~120 ms on a
 * Shark 6), and its release guard requests the same stop on its own once the
 * camera daemon is gone. SIGKILL skips that stop, and an abrupt exit can leave
 * the QNX camera service writing into freed ION buffers. So: SIGTERM, wait up
 * to 2 s for the processes to leave, and SIGKILL only what is still there.
 *
 * Callers kill the camera daemon and its watchdog first, so nothing respawns
 * a capture child while these lines wait.
 */
public final class DiLink5CaptureStopScript {

    static final String[] CAPTURE_PROCESSES = {"fast_cam_capture", "qcarcam_test"};
    /** 20 × 0.1 s. */
    static final int CLEAN_STOP_WAIT_TICKS = 20;

    private DiLink5CaptureStopScript() {}

    public static String lines() {
        StringBuilder script = new StringBuilder();
        for (String process : CAPTURE_PROCESSES) {
            script.append(signalLine(process, "TERM"));
        }
        script.append("W=0; while [ $W -lt ").append(CLEAN_STOP_WAIT_TICKS)
                .append(" ] && ps -A -o ARGS | grep -E '")
                .append(String.join("|", CAPTURE_PROCESSES))
                .append("' | grep -v grep >/dev/null; do sleep 0.1; W=$((W+1)); done\n");
        for (String process : CAPTURE_PROCESSES) {
            script.append(signalLine(process, "KILL"));
            script.append("killall -9 ").append(process).append(" 2>/dev/null\n");
        }
        return script.toString();
    }

    private static String signalLine(String pattern, String signal) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -" + signal
                + " $pid 2>/dev/null; fi; done\n";
    }
}
