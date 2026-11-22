package com.overdrive.app.remoteaccess;

import com.overdrive.app.server.Messages;

import java.util.Map;

/**
 * Localizes proximity lock/unlock diagnostic {@code reason} strings for Telegram
 * using {@link RemoteAccessMessageCodes} and {@code server-i18n/remote_access.server.*}.
 * English source text is preserved when no catalog entry matches.
 */
public final class RemoteAccessReasonText {

    private static final String PREFIX = "remote_access.server.";

    private RemoteAccessReasonText() {}

    /** Returns localized copy when a code exists; otherwise the original English. */
    public static String localize(String english) {
        if (english == null || english.isEmpty()) return "";
        try {
            RemoteAccessMessageCodes.Code code = RemoteAccessMessageCodes.classify(english);
            if (code == null) return english;
            String template = Messages.get(PREFIX + code.key);
            if (template == null || template.startsWith(PREFIX)) return english;
            return applyNamedArgs(template, code.args, code.argKeys);
        } catch (Throwable ignored) {
            return english;
        }
    }

    private static String applyNamedArgs(String template, Map<String, String> args,
                                         Map<String, String> argKeys) {
        if (args == null || args.isEmpty()) return template;
        String out = template;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            String phraseKey = argKeys != null ? argKeys.get(name) : null;
            if (phraseKey != null) {
                String translated = Messages.get(PREFIX + phraseKey);
                if (translated != null && !translated.startsWith(PREFIX)) {
                    value = translated;
                }
            } else if (!value.isEmpty()) {
                String nested = localize(value);
                if (nested != null && !nested.isEmpty()) value = nested;
            }
            out = out.replace("{" + name + "}", value);
        }
        return out;
    }
}