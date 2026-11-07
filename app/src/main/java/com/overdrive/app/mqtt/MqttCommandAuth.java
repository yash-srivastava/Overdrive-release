package com.overdrive.app.mqtt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SECURITY (V60-08): per-device HMAC for inbound MQTT control commands.
 * sig = HMAC-SHA256(deviceSecret, topic + "\n" + payload). The deviceSecret
 * never leaves the car, so only principals that legitimately hold it (the
 * paired app / owner) can produce valid sigs; a broker client with mere
 * publish rights cannot.
 */
public final class MqttCommandAuth {
    private MqttCommandAuth() {}

    public static boolean verify(String topic, String payload, String sig) {
        if (sig == null || sig.isEmpty()) return false;
        try {
            com.overdrive.app.auth.AuthManager.AuthState st = com.overdrive.app.auth.AuthManager.getState();
            if (st == null || st.deviceSecret == null || st.deviceSecret.isEmpty()) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(st.deviceSecret.getBytes("UTF-8"), "HmacSHA256"));
            byte[] out = mac.doFinal((topic + "\n" + payload).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return java.security.MessageDigest.isEqual(sb.toString().getBytes("UTF-8"), sig.getBytes("UTF-8"));
        } catch (Exception e) {
            return false;
        }
    }
}
