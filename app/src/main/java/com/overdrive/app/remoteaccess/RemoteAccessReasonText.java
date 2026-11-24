package com.overdrive.app.remoteaccess;

import com.overdrive.app.server.Messages;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localizes proximity lock/unlock diagnostic {@code reason} strings for Telegram and push
 * through {@link RemoteAccessMessageCodes} and {@code server-i18n/remote_access.server.*},
 * the codes the web page renders the same texts with. Text without a code stays as reported.
 */
public final class RemoteAccessReasonText {

    private static final String PREFIX = "remote_access.server.";
    /** Mirrors DOOR_KEYS / doorListText() in web/shared/remote-access.js. */
    private static final Map<String, String> DOOR_KEYS = new HashMap<>();
    private static final Pattern DOOR_ITEM = Pattern.compile("^(.+) (open|unknown)$");

    static {
        DOOR_KEYS.put("front-left door", "front_left");
        DOOR_KEYS.put("front-right door", "front_right");
        DOOR_KEYS.put("rear-left door", "rear_left");
        DOOR_KEYS.put("rear-right door", "rear_right");
        DOOR_KEYS.put("hood", "hood");
        DOOR_KEYS.put("trunk", "trunk");
    }

    private RemoteAccessReasonText() {}

    /** Returns localized copy when a code exists; otherwise the original English. */
    public static String localize(String english) {
        return localize(english, 0);
    }

    private static String localize(String english, int depth) {
        if (english == null || english.isEmpty()) return "";
        try {
            RemoteAccessMessageCodes.Code code = RemoteAccessMessageCodes.classify(english);
            if (code == null) return english;
            String template = text(PREFIX + code.key, null);
            if (template == null) return english;
            for (Map.Entry<String, String> arg : code.args.entrySet()) {
                String name = arg.getKey();
                String value = arg.getValue() == null ? "" : arg.getValue();
                String phraseKey = code.argKeys.get(name);
                if ("doors".equals(name)) value = doorList(value);
                else if (phraseKey != null) value = text(PREFIX + phraseKey, value);
                else if (depth < 2 && !value.isEmpty()) value = localize(value, depth + 1);
                template = template.replace("{" + name + "}", value);
            }
            return template;
        } catch (Throwable ignored) {
            return english;
        }
    }

    private static String doorList(String value) {
        String[] items = value.split(", ", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) out.append(", ");
            Matcher m = DOOR_ITEM.matcher(items[i]);
            String door = m.matches() ? DOOR_KEYS.get(m.group(1)) : null;
            out.append(door == null ? items[i]
                    : text(PREFIX + "door_" + door + "_" + m.group(2), items[i]));
        }
        return out.toString();
    }

    /** Catalog text, or {@code fallback} on a miss (Messages returns the key itself). */
    private static String text(String key, String fallback) {
        String value = Messages.get(key);
        return value == null || value.equals(key) ? fallback : value;
    }
}
