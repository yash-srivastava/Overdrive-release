package com.overdrive.app.telegram.impl;

import android.content.Context;

import androidx.annotation.Nullable;

import com.overdrive.app.telegram.IBotTokenConfig;
import com.overdrive.app.telegram.config.UnifiedTelegramConfig;
import com.overdrive.app.telegram.model.BotInfo;
import com.overdrive.app.telegram.model.ValidationResult;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Bot token configuration backed by {@link UnifiedTelegramConfig}.
 *
 * Token + bot identity live in the unified JSON config (encrypted with the
 * same {@code CredentialCipher} we use for BYD Cloud credentials). Both the
 * app process and the shell-UID daemon read from the same place — no more
 * ADB-shell hop, no more split-brain between {@code SharedPreferences} and
 * the daemon's properties file.
 *
 * The {@link Context} parameter is kept for API stability but is no longer
 * used; the unified config is process-global.
 */
public class BotTokenConfig implements IBotTokenConfig {

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";

    // BotTokenConfig is frequently constructed for cheap preference/config
    // reads (including the Integrations status poll). Keep one default client
    // so those instances do not each allocate a dispatcher and connection pool.
    // The holder also avoids constructing an unused default client when callers
    // always supply their own proxy-aware client.
    private static final class DefaultHttpClientHolder {
        private static final OkHttpClient INSTANCE = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    private final OkHttpClient httpClient;

    @SuppressWarnings("unused")
    public BotTokenConfig(Context context) {
        this.httpClient = DefaultHttpClientHolder.INSTANCE;
    }

    /** Constructor with custom OkHttpClient (for proxy support). */
    @SuppressWarnings("unused")
    public BotTokenConfig(Context context, OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void saveToken(String token) {
        // Bot identity is written separately on the next saveBotInfo() call.
        // We need to preserve any existing identity here; setBotToken with
        // -1/"" would overwrite it.
        BotInfo cached = getCachedBotInfo();
        long botId = cached != null ? cached.getBotId() : -1;
        String username = cached != null ? cached.getUsername() : "";
        String firstName = cached != null ? cached.getFirstName() : "";
        UnifiedTelegramConfig.setBotToken(token, botId, username, firstName);
    }

    @Override
    @Nullable
    public String getToken() {
        String t = UnifiedTelegramConfig.getBotToken();
        return (t == null || t.isEmpty()) ? null : t;
    }

    @Override
    public boolean hasToken() {
        return UnifiedTelegramConfig.hasBotToken();
    }

    @Override
    public void clearToken() {
        UnifiedTelegramConfig.setBotToken("", -1, "", "");
    }

    @Override
    public ValidationResult validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return ValidationResult.failure("Token is empty");
        }

        // Basic format check: should be like "123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
        if (!token.contains(":") || token.length() < 30) {
            return ValidationResult.failure("Invalid token format");
        }

        try {
            String url = TELEGRAM_API_BASE + token + "/getMe";
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ValidationResult.failure("HTTP " + response.code());
                }

                String body = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(body);

                if (!json.optBoolean("ok", false)) {
                    String desc = json.optString("description", "Unknown error");
                    return ValidationResult.failure(desc);
                }

                JSONObject result = json.getJSONObject("result");
                BotInfo botInfo = new BotInfo(
                        result.getLong("id"),
                        result.optString("username", ""),
                        result.optString("first_name", "")
                );

                return ValidationResult.success(botInfo);
            }
        } catch (Exception e) {
            return ValidationResult.failure("Network error: " + e.getMessage());
        }
    }

    @Override
    @Nullable
    public BotInfo getCachedBotInfo() {
        long botId = UnifiedTelegramConfig.getBotId();
        if (botId <= 0) return null;
        return new BotInfo(
                botId,
                UnifiedTelegramConfig.getBotUsername(),
                UnifiedTelegramConfig.getBotFirstName()
        );
    }

    @Override
    public void saveTokenAndBotInfo(String token, BotInfo botInfo) {
        // Single underlying write. Prefer this over the saveBotInfo +
        // saveToken pair when both are known together (validate-and-save
        // flow): one updateSection avoids the empty-identity race window
        // and halves the disk I/O — important because the unified config
        // file is a full JSON rewrite per call.
        UnifiedTelegramConfig.setBotToken(
                token,
                botInfo.getBotId(),
                botInfo.getUsername(),
                botInfo.getFirstName()
        );
    }

    @Override
    public void saveBotInfo(BotInfo botInfo) {
        // Single updateSection regardless of whether the token is already
        // set — bundle id/username/first_name into one delta. With a token,
        // we re-encrypt to preserve it (cheap; ~1ms AES) so we don't have
        // to read+write twice.
        String token = UnifiedTelegramConfig.getBotToken();
        if (token == null || token.isEmpty()) {
            org.json.JSONObject delta = new org.json.JSONObject();
            try {
                delta.put(UnifiedTelegramConfig.K_BOT_ID, botInfo.getBotId());
                delta.put(UnifiedTelegramConfig.K_BOT_USERNAME, botInfo.getUsername());
                delta.put(UnifiedTelegramConfig.K_BOT_FIRST_NAME, botInfo.getFirstName());
            } catch (Exception ignored) {}
            com.overdrive.app.config.UnifiedConfigManager.updateSection(
                    UnifiedTelegramConfig.SECTION, delta);
            return;
        }
        UnifiedTelegramConfig.setBotToken(token, botInfo.getBotId(),
                botInfo.getUsername(), botInfo.getFirstName());
    }
}
