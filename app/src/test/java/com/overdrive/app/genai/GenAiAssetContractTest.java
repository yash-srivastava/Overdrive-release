package com.overdrive.app.genai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class GenAiAssetContractTest {

    @Test
    public void assistantIsTextFirstAndSessionOnly() throws Exception {
        String html = readProjectFile(
                "src/main/assets/web/local/assistant.html");
        String javascript = readProjectFile(
                "src/main/assets/web/shared/genai.js");
        String handler = readProjectFile(
                "src/main/java/com/overdrive/app/server/GenAiApiHandler.java");
        String chatSocket = readProjectFile(
                "src/main/java/com/overdrive/app/server/GenAiChatWebSocket.java");
        String voiceSocket = readProjectFile(
                "src/main/java/com/overdrive/app/server/GenAiVoiceWebSocket.java");
        String runtime = readProjectFile(
                "src/main/java/com/overdrive/app/genai/GenAiRuntime.java");

        assertTrue(html.contains("id=\"genAiInput\""));
        assertTrue(html.contains("id=\"genAiVoiceBtn\""));
        assertTrue(html.contains("genAiVoiceBtn\" type=\"button\" disabled"));
        assertTrue(javascript.contains("messages: []"));
        assertFalse(javascript.contains("localStorage"));
        assertFalse(javascript.contains("speechSynthesis"));
        assertFalse(javascript.contains("SpeechRecognition"));
        assertTrue(javascript.contains("/api/genai/automation/draft"));
        assertTrue(javascript.contains("/api/genai/automation/commit"));
        assertTrue(javascript.contains("draft.saved"));
        assertTrue(javascript.contains("Saved as manual-only"));
        assertTrue(handler.contains(
                "saveManualAutomation("));
        assertTrue(handler.contains(
                "UUID.nameUUIDFromBytes("));
        assertTrue(handler.contains(".put(\"saved\", true)"));
        assertTrue(javascript.contains("'/ws/genai'"));
        assertTrue(javascript.contains("'/ws/genai/chat'"));
        assertTrue(javascript.contains("type: 'cancel'"));
        assertTrue(javascript.contains("scheduleStreamRender"));
        assertTrue(javascript.contains("navigator.mediaDevices"));
        assertTrue(javascript.contains("AudioWorkletNode"));
        assertTrue(javascript.contains("createScriptProcessor"));
        assertTrue(javascript.contains("usageLabel"));
        assertFalse(javascript.contains("input_audio_transcription"));
        assertTrue(html.contains("data-mode=\"trip_comparison\""));
        assertTrue(html.contains(
                "data-mode=\"automation_diagnostics\""));
        assertTrue(javascript.contains(
                "mode === 'automation_diagnostics'"));
        assertTrue(html.contains("data-mode=\"vehicle_action\""));
        assertTrue(html.contains("data-mode=\"vehicle_history\""));
        assertTrue(html.contains("id=\"genAiRoutineSuggestions\""));
        assertTrue(html.contains("id=\"genAiIncidentPacks\""));
        assertTrue(html.contains(
                "id=\"genAiRoutineLearningEnabled\""));
        assertTrue(javascript.contains(
                "'/api/genai/action/execute'"));
        assertTrue(javascript.contains(
                "'/api/genai/history/query'"));
        assertTrue(javascript.contains(
                "'/api/genai/routines/decision'"));
        assertTrue(javascript.contains(
                "'/api/genai/incidents?limit=5'"));
        assertTrue(javascript.contains("Confirm and run"));
        assertTrue(javascript.contains("action_proposal"));
        assertTrue(javascript.contains(
                "this.selectedMode = response.needsInput"));
        assertTrue(javascript.contains("context_request"));
        assertTrue(javascript.contains("confirm_context"));
        assertTrue(javascript.contains("Share once"));
        assertTrue(javascript.contains("Voice request:"));
        assertTrue(html.contains("class=\"assistant-page\""));
        assertTrue(html.contains("class=\"ai-composer-dock\""));
        assertTrue(javascript.contains(
                "this.renderRichText(bubble, content)"));
        assertTrue(javascript.contains(
                "document.createTextNode("));
        assertFalse(javascript.contains("bubble.innerHTML"));
        assertTrue(javascript.contains(
                "Review or import them below."));
        assertTrue(html.contains(
                "<select class=\"md-input\" id=\"genAiModelPreset\""));
        assertTrue(html.contains(
                "<select class=\"md-input\" id=\"genAiRealtimeModelPreset\""));
        assertFalse(html.contains("<datalist"));
        assertFalse(html.contains("list=\"genAi"));
        assertTrue(javascript.contains("fillModelSelect"));
        assertTrue(javascript.contains("applyModelPreset"));
        assertTrue(javascript.contains("responseLanguage: function"));
        assertTrue(javascript.contains(
                "language: this.responseLanguage()"));
        assertTrue(javascript.contains(
                "language: self.responseLanguage()"));
        assertTrue(handler.contains(
                "GenAiContext.withResponseLanguage("));
        assertTrue(handler.contains(
                "GenAiVehicleHistory.execute("));
        assertTrue(handler.contains(
                "GenAiRoutineLearner.decision("));
        assertTrue(handler.contains(
                "GenAiIncidentPacks.create("));
        assertTrue(chatSocket.contains(
                "GenAiContext.withResponseLanguage("));
        assertTrue(voiceSocket.contains(
                "GenAiContext.withResponseLanguage("));
        assertTrue(html.contains("@media (max-width: 900px)"));
        assertTrue(html.contains(".ai-insight-card {"));
        assertTrue(html.contains("margin: 0 !important;"));
        assertTrue(html.contains(
                "class=\"card ai-insight-card\""));
        assertTrue(html.contains("core.js?v=23"));
        assertTrue(html.contains("placeholder=\"Ask OverDrive…\""));
        assertTrue(html.contains(
                "min-height: 180px; max-height: 720px"));
        assertFalse(html.contains(
                "bottom: calc(90px + var(--safe-bottom, 0px))"));
        assertTrue(runtime.contains(
                "OverDrive is the app name, not the user's name"));
        assertTrue(html.contains("genai.js?v=11"));
        assertTrue(javascript.contains("BYD.utils.confirmDialog"));
    }

    @Test
    public void daemonKillSwitchDestroysTransport() throws Exception {
        String runtime = readProjectFile(
                "src/main/java/com/overdrive/app/genai/GenAiRuntime.java");

        assertTrue(runtime.contains("call.cancel()"));
        assertFalse(runtime.contains("stopTransportAsync"));
        assertTrue(runtime.contains(
                "synchronized (clientLock)"));
        assertTrue(runtime.contains(
                "ensureGenerationMatches(requestGeneration)"));
        assertTrue(runtime.contains("old.dispatcher().cancelAll()"));
        assertTrue(runtime.contains("old.connectionPool().evictAll()"));
        assertTrue(runtime.contains(
                "old.dispatcher().executorService().shutdownNow()"));
        assertTrue(runtime.contains("ProxyHelper.getFailClosedHttpProxy()"));
        assertTrue(runtime.contains("\"proxy_required_unavailable\""));
    }

    @Test
    public void groundingStripsSensitiveVehicleAndMediaFields() throws Exception {
        String context = readProjectFile(
                "src/main/java/com/overdrive/app/genai/GenAiContext.java");

        assertTrue(context.contains("Exact trip coordinates"));
        assertTrue(context.contains("Recording paths, filenames"));
        assertFalse(context.contains("\"startLat\", \"startLon\""));
        assertFalse(context.contains("\"telemetryFilePath\""));
        assertFalse(context.contains("\"vin\""));
    }

    @Test
    public void insightsAreOptInCardsInsideExistingDashboards() throws Exception {
        String assistant = readProjectFile(
                "src/main/assets/web/local/assistant.html");
        String webDashboard = readProjectFile(
                "src/main/assets/web/local/index.html");
        String javascript = readProjectFile(
                "src/main/assets/web/shared/genai.js");
        String statusServer = readProjectFile(
                "src/main/java/com/overdrive/app/server/HttpServer.java");
        String nativeProvider = readProjectFile(
                "src/main/java/com/overdrive/app/ui/dashboard/DashboardInsight.kt");
        String nativeDashboard = readProjectFile(
                "src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt");
        String insights = readProjectFile(
                "src/main/java/com/overdrive/app/genai/GenAiInsights.java");
        String api = readProjectFile(
                "src/main/java/com/overdrive/app/server/GenAiApiHandler.java");

        assertTrue(assistant.contains("id=\"genAiInsightDashboard\""));
        assertFalse(assistant.contains("id: 'insights'"));
        assertFalse(assistant.contains("data-tab=\"insights\""));
        assertTrue(webDashboard.contains("id=\"dashAiInsight\""));
        assertTrue(webDashboard.contains(
                "<button class=\"dash-ai-insight\""));
        assertTrue(webDashboard.contains("aria-live=\"polite\""));
        assertTrue(webDashboard.contains(".dash-ai-insight::before"));
        assertFalse(webDashboard.contains(
                "id=\"dashAiInsight\" href=\"/assistant\""));
        assertTrue(webDashboard.contains("toggleAiInsight"));
        assertTrue(webDashboard.contains("is-expanded"));
        assertTrue(webDashboard.contains("/api/genai/insights/dashboard"));
        assertTrue(webDashboard.contains(
                "encodeURIComponent(language)"));
        assertTrue(webDashboard.contains(
                "document.getElementById('dashAiInsightText').textContent = text"));
        assertTrue(webDashboard.contains(
                "status.genAiDashboardEnabled === true"));
        assertTrue(webDashboard.contains(
                "if (document.hidden || !this.aiInsightEnabled) return"));
        assertTrue(statusServer.contains(
                "\"genAiDashboardEnabled\""));
        assertTrue(nativeProvider.contains(
                "if (!GenAiConfig.isDashboardPresentationEnabled()) return null"));
        assertTrue(nativeProvider.contains(
                "\"/api/genai/insights/dashboard?lang=${LocaleManager.get()}\""));
        assertTrue(nativeDashboard.contains(
                "aiInsightExpanded = !aiInsightExpanded"));
        assertFalse(nativeDashboard.contains(
                "aiInsightCard.setOnClickListener {\n"
                        + "            findNavController().navigate(R.id.genAiFragment"));
        assertTrue(javascript.contains(
                "language: window.BYD && BYD.i18n"));
        assertTrue(javascript.contains(
                "message.mode === 'community_search'"));
        assertTrue(javascript.contains(
                "No matching community automations were found."));
        assertTrue(insights.contains(
                "\"overdrive_dashboard_insight\""));
        assertTrue(insights.contains(
                ".put(\"language\", language)"));
        assertTrue(insights.contains(
                "latestJson(String requestedLanguage)"));
        assertTrue(api.contains(
                "input.optString(\"language\", \"\")"));
    }

    @Test
    public void assistantIsDefaultVisibleAndUsesExistingNavigationToggle()
            throws Exception {
        String catalog = readProjectFile(
                "src/main/java/com/overdrive/app/ui/navigation/NavigationRailCatalog.kt");
        String preferences = readProjectFile(
                "src/main/java/com/overdrive/app/ui/util/PreferencesManager.kt");
        String appearance = readProjectFile(
                "src/main/java/com/overdrive/app/ui/fragment/settings/SettingsAppearanceFragment.kt");
        String activity = readProjectFile(
                "src/main/java/com/overdrive/app/ui/MainActivity.kt");

        assertTrue(catalog.contains("const val ASSISTANT = \"assistant\""));
        assertTrue(catalog.contains("NavigationRailOption(ASSISTANT"));
        assertTrue(preferences.contains(
                "resolveWithNewDefaults(stored, knownKeys, seen)"));
        assertTrue(appearance.contains(
                "NavigationRailCatalog.customizableOptions.forEach"));
        assertTrue(activity.contains(
                "RailItem(NavigationRailCatalog.ASSISTANT"));
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
