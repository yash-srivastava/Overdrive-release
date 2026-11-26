package com.overdrive.app.notifications.sinks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TelegramSinkRemoteAccessLeadTest {

    @Test
    public void leadEmojiDependsOnProximityAction() {
        assertEquals("🔒🔴 ", TelegramSink.remoteAccessTelegramLead("lock"));
        assertEquals("🔑🟢 ", TelegramSink.remoteAccessTelegramLead("unlock"));
        assertEquals("🔑 ", TelegramSink.remoteAccessTelegramLead(""));
        assertEquals("🔑 ", TelegramSink.remoteAccessTelegramLead("unknown"));
    }
}