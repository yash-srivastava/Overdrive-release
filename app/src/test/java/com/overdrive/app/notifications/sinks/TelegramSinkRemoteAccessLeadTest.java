package com.overdrive.app.notifications.sinks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TelegramSinkRemoteAccessLeadTest {

    @Test
    public void lockIconsOnlyForConfirmedStates() {
        assertEquals("🔒 ", TelegramSink.remoteAccessTelegramLead("lock", "confirmed"));
        assertEquals("🔓 ", TelegramSink.remoteAccessTelegramLead("unlock", "confirmed"));
        assertEquals("⚠️ ", TelegramSink.remoteAccessTelegramLead("lock", "failed"));
        assertEquals("⚠️ ", TelegramSink.remoteAccessTelegramLead("unlock", "unconfirmed"));
        assertEquals("🔑 ", TelegramSink.remoteAccessTelegramLead("", ""));
        assertEquals("🔑 ", TelegramSink.remoteAccessTelegramLead("unknown", "confirmed"));
    }
}
