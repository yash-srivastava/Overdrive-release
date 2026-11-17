package com.overdrive.app.navmap.cluster;

import android.os.Bundle;

/** Shell-daemon to app contract for the DI5 OEM cluster communication service. */
public final class ClusterCommContract {
    public static final String ACTION = "com.overdrive.app.action.CLUSTER_COMM";
    public static final String RECEIVER = "com.overdrive.app.navmap.cluster.ClusterCommReceiver";
    public static final String EXTRA_COMMANDS = "commands";
    public static final String EXTRA_DEADLINE = "deadline";
    public static final String EXTRA_REPLY = "reply";
    public static final String REPLY_TOKEN = "token";
    public static final String REPLY_SENT = "sent";

    public static final int RESULT_SENT = 0;
    public static final int RESULT_EXPIRED = 1;
    public static final int RESULT_BAD_REQUEST = 2;
    public static final int RESULT_SERVICE_UNAVAILABLE = 3;
    public static final int RESULT_BIND_TIMEOUT = 4;
    public static final int RESULT_BIND_DENIED = 5;
    public static final int RESULT_UNSUPPORTED_INTERFACE = 6;
    public static final int RESULT_SERVICE_DIED = 7;
    public static final int RESULT_FAILED = 8;

    static final String SERVICE_PACKAGE = "com.ts.appservice.cluster";
    static final String SERVICE_CLASS = "com.ts.appservice.cluster.service.ClusterCoreService";
    static final String DESCRIPTOR = "com.ts.lib.cluster.common.IClusterCommService";
    static final int TRANSACTION_SEND_COMMAND = 1;
    static final int COMMAND_NAVI = 16;
    static final int MAX_COMMANDS = 16;

    private ClusterCommContract() {}

    static boolean validCommand(Bundle command) {
        if (command == null || command.getInt("msgType", -1) != 3) return false;
        switch (command.getInt("DataType", -1)) {
            case 30: case 31: case 32: case 33: case 34: case 35: case 37: return true;
            default: return false;
        }
    }

    public static String resultName(int code) {
        switch (code) {
            case RESULT_SENT: return "sent";
            case RESULT_EXPIRED: return "expired";
            case RESULT_BAD_REQUEST: return "bad_request";
            case RESULT_SERVICE_UNAVAILABLE: return "service_unavailable";
            case RESULT_BIND_TIMEOUT: return "bind_timeout";
            case RESULT_BIND_DENIED: return "bind_denied";
            case RESULT_UNSUPPORTED_INTERFACE: return "unsupported_interface";
            case RESULT_SERVICE_DIED: return "service_died";
            default: return "failed";
        }
    }
}
