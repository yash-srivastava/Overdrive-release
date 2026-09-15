package com.overdrive.app.config

import android.content.Context
import androidx.fragment.app.FragmentActivity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.byd.cloud.crypto.CredentialCipher
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import org.json.JSONObject

/**
 * Manages Cloudflared Paid configuration and provides a setup dialog.
 * Kept in a separate file for easy maintenance/portability.
 * Uses UnifiedConfigManager for cross-process state sync.
 *
 * The tunnel token is a Cloudflare account credential. It is persisted
 * ENCRYPTED via CredentialCipher (mirrors BydCloudConfig / NavMapConfig) —
 * never plaintext — because the unified config lives in world-readable
 * /data/local/tmp/overdrive_config.json. getToken() decrypts on read;
 * legacy plaintext values decrypt as-is (CredentialCipher no-ops without
 * the ENC: marker), so an upgrade does not lose an existing token.
 */
object CloudflaredPaidConfig {

    /** Legitimate cloudflared connector tokens are base64(JSON) — the standard
     *  base64 alphabet (incl. '+' and '/') plus '=' padding, and we also allow
     *  base64url ('-'/'_') and '.' defensively. None of these are shell
     *  metacharacters inside single quotes, so validating on save guarantees a
     *  token can never break out of the single-quoted `sh -c '...'` launch
     *  context (the real goal). Quotes/spaces/backticks/etc. are rejected. */
    private val TOKEN_CHARSET = Regex("^[A-Za-z0-9+/._=-]+$")

    @JvmStatic
    fun isPaidVersion(): Boolean {
        return UnifiedConfigManager.getCloudflared().optBoolean("isPaid", false)
    }

    @JvmStatic
    fun getToken(): String {
        // Stored encrypted; decrypt() returns "" on a tampered/undecryptable
        // value and returns legacy plaintext untouched.
        val stored = UnifiedConfigManager.getCloudflared().optString("token", "")
        return CredentialCipher.decrypt(stored)
    }

    /**
     * Build the command line arguments for cloudflared based on current configuration.
     * Can be called from any process (app or daemon).
     */
    @JvmStatic
    fun getArgs(): String {
        // Ensure fresh read if in daemon process. App process also calls
        // forceReload before launch to ensure it picks up the latest settings
        // saved via IPC to the daemon.
        UnifiedConfigManager.forceReload()
        val isPaid = UnifiedConfigManager.getCloudflared().optBoolean("isPaid", false)
        val token = getToken()

        return if (isPaid && token.isNotEmpty()) {
            // Paid named tunnel. Keep the same resilience flags as the free
            // path so a paid tunnel survives the ~24s ACC-OFF data-interface
            // blackout instead of panicking/restarting faster.
            "tunnel --no-autoupdate --retries 20 --grace-period 45s run --token $token"
        } else {
            "tunnel --url http://127.0.0.1:8080 --edge-ip-version 4 --protocol http2 --no-autoupdate --retries 20 --grace-period 45s"
        }
    }

    @JvmStatic
    fun isConfigured(): Boolean {
        return !isPaidVersion() || getToken().isNotEmpty()
    }

    /**
     * Build the shell command to extract the tunnel URL from the log.
     */
    @JvmStatic
    fun getUrlExtractionCommand(logPath: String): String {
        return if (isPaidVersion() && getToken().isNotEmpty()) {
            "cat $logPath 2>/dev/null | grep -iE 'ingress|hostname'"
        } else {
            "cat $logPath 2>/dev/null"
        }
    }

    /**
     * Parse the tunnel URL from the log content.
     */
    @JvmStatic
    fun parseUrl(logContent: String): String? {
        return if (isPaidVersion() && getToken().isNotEmpty()) {
            // Match a host-like FQDN on an ingress/hostname line, excluding the
            // local origin. Anchored on a real label.tld shape to avoid matching
            // version strings / timestamps in the connector log.
            val cfUrlPattern = Regex("\\b(?!127\\.0\\.0\\.1)[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+\\.[a-zA-Z]{2,}\\b")
            val match = cfUrlPattern.find(logContent)
            if (match != null) "https://" + match.value else null
        } else {
            val cfUrlPattern = Regex("https://([a-z0-9]+-[a-z0-9-]+)\\.trycloudflare\\.com")
            cfUrlPattern.find(logContent)?.value
        }
    }

    /**
     * Build the command for grep -o (used in getTunnelUrl).
     */
    @JvmStatic
    fun getGrepUrlCommand(logPath: String): String {
        return if (isPaidVersion() && getToken().isNotEmpty()) {
            // For paid version, hostnames appear on 'ingress'/'hostname' lines.
            "grep -iE 'ingress|hostname' $logPath 2>/dev/null | grep -oE '[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | grep -vE '127\\.0\\.0\\.1' | head -1"
        } else {
            "grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' $logPath 2>/dev/null | grep -v 'api\\.' | head -1"
        }
    }

    /**
     * Show the Cloudflared settings dialog.
     */
    fun showSettingsDialog(context: Context, daemonsViewModel: DaemonsViewModel) {
        val activity = context as? FragmentActivity
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_cloudflared_settings, null)

        val swPaid = dialogView.findViewById<SwitchMaterial>(R.id.swCloudflarePaid)
        val tilToken = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCloudflareToken)
        val etToken = dialogView.findViewById<EditText>(R.id.etCloudflareToken)

        // Ensure we have latest data
        UnifiedConfigManager.forceReload()
        val currentIsPaid = isPaidVersion()
        val currentToken = getToken()

        swPaid.isChecked = currentIsPaid
        etToken.setText(currentToken)

        // Initial state
        tilToken.isEnabled = currentIsPaid

        swPaid.setOnCheckedChangeListener { _, isChecked ->
            tilToken.isEnabled = isChecked
        }

        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(context.getString(R.string.dialog_cloudflared_settings_title))
            .setMessage(context.getString(R.string.dialog_cloudflared_settings_message))
            .setView(dialogView)
            .setPositiveButton(context.getString(R.string.dialog_save)) { _, _ ->
                val paid = swPaid.isChecked
                // SOTA: Tokens often contain newlines if copied from a terminal or
                // wrapped text. Strip ALL whitespace to prevent breaking the IPC
                // protocol (which is newline-delimited JSON).
                val token = etToken.text?.toString()?.replace("\\s".toRegex(), "") ?: ""

                if (paid && token.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_cloudflared_token_cannot_be_empty), Toast.LENGTH_SHORT).show()
                } else if (paid && !TOKEN_CHARSET.matches(token)) {
                    // Reject any token carrying quotes/shell metacharacters so it
                    // can never escape the single-quoted sh -c launch context.
                    Toast.makeText(context, context.getString(R.string.toast_cloudflared_token_invalid), Toast.LENGTH_LONG).show()
                } else {
                    val newConfig = JSONObject()
                    newConfig.put("isPaid", paid)
                    // Encrypt before persisting; fail-open guard never writes plaintext.
                    val encrypted = CredentialCipher.encrypt(token)
                    if (token.isNotEmpty() && !CredentialCipher.isEncrypted(encrypted)) {
                        Toast.makeText(context, context.getString(R.string.toast_cloudflared_encrypt_failed), Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    newConfig.put("token", encrypted)

                    // Save to unified config (atomic write via daemon IPC if called from app)
                    Thread {
                        val success = UnifiedConfigManager.setCloudflared(newConfig)

                        activity?.runOnUiThread {
                            if (success) {
                                Toast.makeText(context, context.getString(R.string.toast_cloudflared_saved_settings), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_cloudflared_save_failed), Toast.LENGTH_LONG).show()
                            }
                        }

                        if (success) {
                            // Allow write to settle
                            Thread.sleep(200)

                            // RESTART tunnel if it was running to apply changes immediately
                            daemonsViewModel.getState(DaemonType.CLOUDFLARED_TUNNEL)?.let { state ->
                                if (state.status == com.overdrive.app.ui.model.DaemonStatus.RUNNING ||
                                    state.status == com.overdrive.app.ui.model.DaemonStatus.STARTING) {
                                    // Stop then start to force new URL extraction
                                    daemonsViewModel.stopDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                                    Thread.sleep(500)
                                    daemonsViewModel.startDaemon(DaemonType.CLOUDFLARED_TUNNEL)
                                } else {
                                    daemonsViewModel.refreshDaemonStatus(DaemonType.CLOUDFLARED_TUNNEL)
                                }
                            } ?: daemonsViewModel.refreshDaemonStatus(DaemonType.CLOUDFLARED_TUNNEL)
                        }
                    }.start()
                }
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .show()
    }
}
