package com.overdrive.app.daemon.proxy;

/**
 * Auto-generated encrypted string constants.
 * DO NOT EDIT - regenerate with: python generate_safe_enc.py
 * 
 * Source: secrets.json
 * Decryption: Safe.s() (AES-256-CBC, pure Java)
 */
public final class Enc {
    private Enc() {} // No instantiation

    // ==================== PROXY ====================
    /** VLESS Reality proxy credentials - UPDATE THESE WITH YOUR ACTUAL VALUES */
    public static final String _comment = Safe.s("+8pFEgfG6yvoKU3Gr2BHEXfHmQxvpmJ5oXAlnZN/PKMmjjw+9twIbfFF2kdfM4ECs6INr1rYQV5oC4FwQ0hI/d+dzScifJZKPdK5YTN6DJQ=");

    /** YOUR_SERVER_IP_HERE */
    public static final String PROXY_SERVER_IP = Safe.s("WVJRYpvmp/XFscpjqdmcePpGqLDp4uKlgO+VwJvufCk=");

    /** 443 */
    public static final String PROXY_SERVER_PORT = Safe.s("ZZD4D7Z+dJSdHTjoW8VW7Q==");

    /** YOUR_UUID_HERE */
    public static final String PROXY_UUID = Safe.s("vv2W+bqJQTnU0s9t1THlpg==");

    /** YOUR_SHORT_ID_HERE */
    public static final String PROXY_SHORT_ID = Safe.s("lgWj2tUxBIeuYAVkw5iBY4GcSCCWQ9VfgBFiVw8J6vc=");

    /** YOUR_PUBLIC_KEY_HERE */
    public static final String PROXY_PUBLIC_KEY = Safe.s("F4OAFgiNRz2FNlpdurBjCqnXB5ZP1NjWZN+eJQlkpkA=");

    /** google.com */
    public static final String PROXY_SNI = Safe.s("u7674GDA85JNqEffkyiqRg==");

    // ==================== PATHS ====================
    /** /data/local/tmp/singbox_config.json */
    public static String SINGBOX_CONFIG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxzwYi8Dqee5TiGPRAGrXFGxQK19NSN/ULRr1XYqE0nHYW"));
    }

    /** /data/local/tmp/singbox.log */
    public static String SINGBOX_LOG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz3vxHXoriO/4/mUU2N2RxN4="));
    }

    /** /data/local/tmp/sing-box */
    public static String SINGBOX_BIN() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz3TnY8grp670bzPyWlLlY9c="));
    }

    /** /data/local/tmp/global_proxy.log */
    public static String PROXY_LOG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz5q9/uqUtW8BShQTy+DvGbRG9DHIjhkEadRt3RoxzdGj"));
    }

    /** /data/local/tmp/telegram_config.properties */
    public static String TELEGRAM_CONFIG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxzwQSn0P1N0jxHygc8N+4Ft+9mlR8XQ+WvEw0ktanrtNx"));
    }

    /** /data/local/tmp/telegram_bot.log */
    public static String TELEGRAM_LOG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz1Vh6sm+brMq1q/o0Wusp6KtnIKKcEFOQmmXMyZ3w/KB"));
    }

    /** /data/local/tmp/tunnel_url.txt */
    public static String TELEGRAM_URL_FILE() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz/kVx51CDNRiQ/Mc5+npiPo="));
    }

    /** /data/system/sentry_daemon.log */
    public static final String SENTRY_LOG_SYSTEM = Safe.s("9tdDgaIWuXyXxqP8qmKWMPvAEj729chJmgA4XiF9VOo=");

    /** /data/local/tmp/sentry_daemon.log */
    public static String SENTRY_LOG_TMP() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz9TTr71BkwVSU1UO8CyXRy7yB1SvKmAAYi99Xx5v11Xa"));
    }

    /** /data/local/tmp/sentry_daemon.pid */
    public static String SENTRY_PID() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxzy1lsQShZtcRseW7dNE1si25na89IOT5cRwBuRuJBcXS"));
    }

    /** /data/local/tmp/sentry_network_diag.log */
    public static String SENTRY_NETWORK_DIAG_LOG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz3F+jKfo+GyPGXQzNQPg1lqNYmt3ujG7x4QjuN3pYK2f"));
    }

    /** /data/local/tmp/acc_sentry.log */
    public static String ACC_SENTRY_LOG() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz4BdefvSzYGU61RsHmJQJ+g="));
    }

    /** /data/local/tmp/cam_stream */
    public static String CAMERA_STREAM_DIR() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxzxuq9ag7mKGoQaOvzuwMDqM="));
    }

    /** /sdcard/DCIM/BYDCam */
    public static final String CAMERA_OUTPUT_DIR = Safe.s("C6E+8XkzSNnhdgOIKBfVSXGyuhqY7qDiNp4pBP/hRuY=");

    /** /data/local/tmp/stream_mode.txt */
    public static String CAMERA_STREAM_MODE_FILE() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz4A79W/sQd0NkqiGs/MIZWo="));
    }

    /** /data/local/tmp/.byd_device_id */
    public static String CAMERA_DEVICE_ID_FILE() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxz8mvs/gQENVv3FEZ6OVKD54="));
    }

    /** /sys/power/wake_lock */
    public static final String WAKE_LOCK_PATH = Safe.s("kb7HnwNgcQAsfjzzZ2HOBMxdOhkMxwXzhyFBtedHnSE=");

    /** /sys/power/wake_unlock */
    public static final String WAKE_UNLOCK_PATH = Safe.s("kb7HnwNgcQAsfjzzZ2HOBFL9LU9wOcz7uvaGd3r+PHU=");

    /** /data/local/tmp */
    public static String DATA_LOCAL_TMP() {
        return com.overdrive.app.util.ScratchPaths.path(Safe.s("vuaMjrmBGBFh07qqnUuL8w=="));
    }

    /** /data/data/com.android.providers.settings */
    public static final String DATA_SYSTEM_SETTINGS = Safe.s("4FWGV7tPhe9614nkUCor4bnqFPfssDPoiHYPJxgenGAPG3xCP+0Cb2Hm04LZxNNJ");

    // ==================== COMMANDS ====================
    /** pkill -f sing-box */
    public static final String KILL_SINGBOX = Safe.s("nmb9om2QcwiF1T7zMRJPyl8AlCmIEFx3vJQ/XJLYoW4=");

    /** pgrep -f sing-box */
    public static final String PGREP_SINGBOX = Safe.s("9ipr8gAWZBFa60ui235T/rrplvZH34LqrU8QEESPJhQ=");

    /** svc power stayon true */
    public static final String SVC_POWER_ON = Safe.s("evL2bKzQb67Tf3KRHg1cMVG8PSjiOvOcAsdtUSiirz4=");

    /** svc power stayon false */
    public static final String SVC_POWER_OFF = Safe.s("evL2bKzQb67Tf3KRHg1cMaUQ0s15R3JRQ4W151UI/Rs=");

    /** svc wifi enable */
    public static final String SVC_WIFI_ENABLE = Safe.s("GzzLDvODRsKARkPOXEZeIA==");

    /** cmd wifi set-wifi-enabled enabled */
    public static final String WIFI_ENABLE_CMD = Safe.s("OHt1ORBfaA6jti9DhL+LSDghCI3qSNr9WYGyb82Ov2DsCnMgXaYKKKOzpoICOnGX");

    /** sing-box */
    public static final String SINGBOX_NAME = Safe.s("DctRKb8o3mNJ2B6M4PEoFg==");

    // ==================== SETTINGS ====================
    /** settings put global http_proxy 127.0.0.1: */
    public static final String SETTINGS_HTTP_PROXY = Safe.s("kSl507BgPZXbv0JUusGzZtZZFBcjQ2R+d3/C7e8ZtUQuYsjHLD46mEwmaY7YR2cV");

    /** settings put global global_http_proxy_host 127.0.0.1 */
    public static final String SETTINGS_PROXY_HOST = Safe.s("kSl507BgPZXbv0JUusGzZnGKkVWqt0LyYis5GQNSnGHkz6qPd6BcemXYwtnBTDajEUEt+3yL2O7fuOSB90onng==");

    /** settings put global global_http_proxy_port  */
    public static final String SETTINGS_PROXY_PORT = Safe.s("kSl507BgPZXbv0JUusGzZnGKkVWqt0LyYis5GQNSnGH6LFCzLqbGFlD2EngMGefa");

    /** settings put global global_http_proxy_exclusion_list " */
    public static final String SETTINGS_PROXY_EXCLUSION = Safe.s("kSl507BgPZXbv0JUusGzZnGKkVWqt0LyYis5GQNSnGGOecWapYNZORy+W9AwDB7WjWi7RBAdzApKbsHlHhozKw==");

    // ==================== SERVICES ====================
    /** accmodemanager */
    public static final String SERVICE_ACCMODE = Safe.s("tr877WU3+MV4zFtCjanWUw==");

    /** byd_datacached */
    public static final String SERVICE_BYD_DATACACHE = Safe.s("JQiIxMJxYlF8spk2fIi8Sg==");

    /** bg_datacache */
    public static final String SERVICE_BG_DATACACHE = Safe.s("m84QJmAGTQpH+XP36MaDpA==");

    /** android.os.IAccModeManager */
    public static final String INTERFACE_ACCMODE = Safe.s("8AsXgmArXEIVQTzlKJxcF6yCBHWM2MoAIE3hnqCQMWM=");

    // ==================== MISC ====================
    /** localhost,127.0.0.1,byd.com,byd.auto,dilink.com */
    public static final String PROXY_EXCLUSIONS = Safe.s("W7U2p2zXmYFyuvgULJIqsoxZjUDWIXt7EPxQTiIZSC2LArvfp3HcABvEZcmXXayC");

    /** https://api.telegram.org/bot */
    public static final String TELEGRAM_API_BASE = Safe.s("FS7R/5I0wopp0qBqyJXzvDKg6eI9UXmD/Oei3NbaaGQ=");

    /** com.overdrive.app */
    public static final String APP_PACKAGE = Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8=");

    // ==================== SINGBOX ====================
    /** vless */
    public static final String PROTO_VLESS = Safe.s("18yIiwvdEyManh8f1OuEaQ==");

    /** xtls-rprx-vision */
    public static final String FLOW_XTLS = Safe.s("NfZtqyYYxEXIP/DQfXbsCTSplSKMeCc8xGR/BRlNRXs=");

    /** chrome */
    public static final String FINGERPRINT_CHROME = Safe.s("Gf88MYMzGZxBFiJtVxW9gg==");

    /** tcp://8.8.8.8 */
    public static final String DNS_GOOGLE = Safe.s("qmTp78S+Di6fTuBLyeiqxw==");

    /** 127.0.0.1 */
    public static final String LOCALHOST = Safe.s("6e8x7uzAzonqK41m43RhgA==");

    /** direct */
    public static final String OUTBOUND_DIRECT = Safe.s("4zoj3R6yChQpby03Brip7A==");

    /** proxy */
    public static final String OUTBOUND_PROXY = Safe.s("4fG5lclj+WGG3xwD0zMQEw==");

}
