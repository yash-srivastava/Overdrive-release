package com.overdrive.app.power;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tells the owner a battery protection just acted. The power monitors run in AccSentryDaemon,
 * but the notification bus (push, Telegram, history) lives in CameraDaemon, so this hands the
 * notice over its surveillance IPC. Best-effort and bounded: it never delays the protection
 * itself by more than a few seconds, and a daemon that is down just means no notice.
 */
public final class PowerNotice {

    private static final DaemonLogger logger = DaemonLogger.getInstance("PowerNotice");
    private static final int IPC_PORT = 19877;

    public static final String KIND_LOW_12V_STOP = "low_12v_stop";
    public static final String KIND_SOC_CUTOFF = "soc_cutoff";

    private PowerNotice() {}

    /**
     * @param detail short human detail, e.g. "SoC 9% (cutoff 10%)"
     * @return true when the camera daemon acknowledged the notice
     */
    public static boolean send(String kind, String detail) {
        Socket socket = null;
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "POWER_NOTICE");
            cmd.put("kind", kind);
            cmd.put("detail", detail != null ? detail : "");
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", IPC_PORT), 1500);
            socket.setSoTimeout(2500);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println(com.overdrive.app.remote.RemoteDevViewBridgeAuth.sign(cmd));
            String reply = reader.readLine();
            logger.info("notice " + kind + " sent (reply=" + reply + ")");
            return reply != null && new JSONObject(reply).optBoolean("success", false);
        } catch (Throwable t) {
            logger.warn("notice " + kind + " not delivered: " + t.getMessage());
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
