package com.overdrive.app.logging;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogUploaderRedactionTest {

    @Test
    public void preservesCamelCaseIdentifiersWhileMaskingSecretsAndStandaloneDeviceIds() {
        String raw = ""
                + "[2026-11-03 08:02:24.546] McuPowerHal: resolveLeaseSpecialDevice: BYDAutoSpecialDevice unavailable\n"
                + "[2026-11-03 08:02:25.000] AccMonitor: isAccStateAuthoritative: true\n"
                + "Device: abcd1234efgh5678\n"
                + "device_id=dev_secret_987654 token=my_secret_token_123\n"
                + "https://api.telegram.org/bot123456789:AAHabcdefghijklmnopqrstuvwxyz123456/sendMessage\n";

        String redacted = LogUploader.redact(raw);

        assertTrue(redacted.contains("resolveLeaseSpecialDevice: BYDAutoSpecialDevice unavailable"));
        assertTrue(redacted.contains("isAccStateAuthoritative: true"));
        assertTrue(redacted.contains("Device: [REDACTED]"));
        assertFalse(redacted.contains("abcd1234efgh5678"));
        assertFalse(redacted.contains("dev_secret_987654"));
        assertFalse(redacted.contains("my_secret_token_123"));
        assertTrue(redacted.contains("[REDACTED_TOKEN]"));
    }
}
