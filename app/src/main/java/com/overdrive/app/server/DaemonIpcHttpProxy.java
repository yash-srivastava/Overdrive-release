package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.Iterator;

/** Shared IPC response handling for HTTP handlers; routing and authorization stay with callers. */
final class DaemonIpcHttpProxy {
    private static final int READ_TIMEOUT_MS = 3000;

    private final DaemonLogger logger;
    private final String logPrefix;

    DaemonIpcHttpProxy(DaemonLogger logger, String logPrefix) {
        this.logger = logger;
        this.logPrefix = logPrefix;
    }

    static JSONObject requestFor(String command, JSONObject fields) throws Exception {
        JSONObject request = new JSONObject();
        request.put("command", command);
        if (fields != null) {
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                // The selected HTTP route, not its request body, determines the IPC command.
                if (!"command".equals(key)) request.put(key, fields.get(key));
            }
        }
        return request;
    }

    void send(OutputStream out, String command, JSONObject fields, String failureWarning)
            throws Exception {
        JSONObject response = null;
        try {
            response = DaemonIpcClient.sendOrThrow(requestFor(command, fields), READ_TIMEOUT_MS);
        } catch (Exception e) {
            logger.error(logPrefix + "IPC communication error: " + e.getMessage());
        }
        if (response != null) {
            HttpResponse.sendJson(out, response.toString());
        } else {
            if (failureWarning != null) logger.warn(logPrefix + failureWarning);
            HttpResponse.sendJsonError(out, Messages.get("errors.ipc_communication_failed"));
        }
    }
}
