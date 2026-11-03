package com.overdrive.app.telegram;

import com.overdrive.app.server.LocaleManager;
import com.overdrive.app.server.Messages;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localized, user-facing copy used by the Telegram bot.
 *
 * <p>The bot intentionally follows the app's global locale so commands and
 * asynchronous notifications use the same language.</p>
 */
public final class TelegramMessages {

    private static final String PREFIX = "telegram.";
    private static final Pattern HTTP_STATUS =
            Pattern.compile("(?i).*\\bHTTP\\s*(\\d{3})\\b.*");

    private TelegramMessages() {}

    public static String get(String key, Object... args) {
        return Messages.get(PREFIX + key, args);
    }

    public static boolean isPortuguese() {
        String language = LocaleManager.get();
        return language != null
                && language.toLowerCase(Locale.ROOT).startsWith("pt");
    }

    public static boolean isHebrew() {
        String language = LocaleManager.get();
        if (language == null) return false;
        String l = language.toLowerCase(Locale.ROOT);
        return l.startsWith("he") || l.startsWith("iw");
    }

    /**
     * Locales whose chat must not fall back to raw English backend text:
     * known failures are mapped to catalog strings instead (PT-BR and Hebrew).
     */
    public static boolean mapsTechnicalDetails() {
        return isPortuguese() || isHebrew();
    }

    /**
     * Keep backend/exception details from reintroducing English into a PT-BR or
     * Hebrew chat. English keeps the original diagnostic detail; PT-BR and Hebrew map
     * known failures and fall back to a localized pointer to the device logs.
     */
    public static String technicalDetail(String detail) {
        String value = detail == null ? "" : detail.trim();
        if (value.isEmpty()) return get("common.unknown_error");
        if (!mapsTechnicalDetails()) return escapeMarkdown(value);

        // Codes, percentages, paths, versions, and numeric values are
        // language-neutral and remain useful as-is.
        if (value.matches("[\\d\\s%+./:_-]+")) return escapeMarkdown(value);

        String lower = value.toLowerCase(Locale.ROOT);
        // Already in the chat's language (a localized server message): pass through.
        if (isPortuguese() && lower.matches(".*[áàâãéêíóôõúç].*")) {
            return escapeMarkdown(value);
        }
        if (isHebrew() && value.matches("(?s).*[\\u05D0-\\u05EA].*")) {
            return escapeMarkdown(value);
        }
        if (lower.equals("unknown") || lower.startsWith("unknown (")) {
            return get("common.unknown_error");
        }
        if (lower.contains("app context not ready")) {
            return get("error_details.app_context_not_ready");
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return get("error_details.timeout");
        }
        if (lower.contains("public mode")) {
            return get("error_details.public_mode");
        }
        if (lower.contains("invalid update channel")) {
            return get("error_details.invalid_channel");
        }
        if (lower.contains("could not save") && lower.contains("channel")) {
            return get("error_details.channel_save_failed");
        }
        if (lower.contains("already in progress")) {
            return get("error_details.update_in_progress");
        }
        if (lower.contains("not on the active channel")) {
            return get("error_details.inactive_channel");
        }
        if (lower.contains("no update available")) {
            return get("error_details.no_update");
        }
        if (lower.contains("not available in this build")) {
            return get("error_details.unavailable_build");
        }
        if (lower.contains("permission denied") || lower.contains("eacces")) {
            return get("error_details.permission_denied");
        }
        if (lower.contains("no space") || lower.contains("enospc")
                || lower.contains("storage full")) {
            return get("error_details.storage_full");
        }
        Matcher http = HTTP_STATUS.matcher(value);
        if (http.matches()) {
            return get("error_details.http_status", http.group(1));
        }
        if (lower.contains("unable to resolve") || lower.contains("failed to connect")
                || lower.contains("network") || lower.contains("connection")
                || lower.contains("socket") || lower.contains("resolve failed")) {
            return get("error_details.network");
        }
        if (lower.contains("download")) {
            return get("error_details.download_failed");
        }
        if (lower.contains("verify") || lower.contains("verification")
                || lower.contains("signature")) {
            return get("error_details.verification_failed");
        }
        if (lower.contains("install")) {
            return get("error_details.install_failed");
        }
        if (lower.contains("unknown daemon")) {
            return get("error_details.unknown_daemon");
        }
        return get("error_details.generic");
    }

    private static String escapeMarkdown(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[' || c == ']') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
