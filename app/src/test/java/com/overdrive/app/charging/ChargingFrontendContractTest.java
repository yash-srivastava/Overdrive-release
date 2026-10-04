package com.overdrive.app.charging;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guards for dashboard/session behavior exercised in the embedded WebView. */
public class ChargingFrontendContractTest {

    @Test
    public void dashboardClearsMissingStateAndPrioritizesActiveTaper() throws IOException {
        String dashboard = readRepositoryFile("app/src/main/assets/web/local/index.html");

        assertTrue(dashboard.contains("c.charging ? 'charge.state_charging'"));
        assertTrue(dashboard.contains("c.full ? 'charge.state_full'"));
        assertTrue(dashboard.indexOf("c.charging ? 'charge.state_charging'")
                < dashboard.indexOf("c.full ? 'charge.state_full'"));
        assertTrue(dashboard.contains("idleDot.className = 'ic_status_dot dot-idle'"));
        assertTrue(dashboard.contains("setText('dashChargePower', '--')"));
        assertTrue(dashboard.contains(
                "c.powerSource !== 'nominalPlaceholder'"));
        assertTrue(dashboard.contains(
                "(c.isEstimated ? '~' : '') + dashboardPower.toFixed(1) + ' kW'"));
        assertTrue(dashboard.contains(
                "c.sessionEnergyIncomplete === true"));
        assertTrue(dashboard.contains(
                "c.sessionEnergyEstimated === true"));
        assertTrue(dashboard.contains(
                "c.sessionEnergySource !== 'metered_counter'"));
        assertTrue(dashboard.contains(
                "(dashboardEnergyApproximate ? '~' : '')"));
        assertTrue(dashboard.contains("setText('dashChargeKwh', '--')"));
        assertTrue(dashboard.contains("setText('dashChargeTtf', '--')"));
    }

    @Test
    public void activeSessionCannotOfferDeleteAndFailedClearIsNotCelebrated() throws IOException {
        String charging = readRepositoryFile("app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(charging.contains("(inProgress ? '' :"));
        assertTrue(html.contains("id=\"detailDeleteBtn\""));
        assertTrue(charging.contains("this._detailInProgress = true"));
        assertTrue(charging.contains("deleteBtn.style.display = 'none'"));
        assertTrue(charging.contains("deleteBtn.disabled = inProgress"));
        assertTrue(charging.contains("deleteBtn.style.display = inProgress ? 'none' : ''"));
        assertTrue(charging.contains("!this._detailInProgress"));
        assertTrue(charging.contains("body.success !== true"));
        assertTrue(charging.contains("'charge.clear_failed'"));
    }

    @Test
    public void manualCostEditingIsAccessibleValidatedAndRefreshesTheOpenDetail()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/charging.html");
        String core = readRepositoryFile(
                "app/src/main/assets/web/shared/core.js");

        assertTrue(html.contains(
                "<button type=\"button\" class=\"ch-detail-metric-row ch-detail-cost-edit\""));
        assertTrue(html.contains(
                "data-i18n-attr=\"title:charge.edit_cost;aria-label:charge.edit_cost\""));
        assertTrue(charging.contains(
                "var sessionId = this.currentSessionId"));
        assertTrue(charging.contains(
                "var newCost = trimmed === '' ? -1 : Number(trimmed)"));
        assertTrue(charging.contains(
                "!isFinite(newCost) || (newCost < 0 && newCost !== -1)"));
        assertTrue(charging.contains(
                "self._fillDetailHeader(s, sessionId)"));
        assertTrue(core.contains(
                "BYD.utils.promptDialog = function (opts)"));
    }

    @Test
    public void failedBootstrapAndDirectLoadsPreserveRenderedState()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "if (!response.ok) throw new Error"));
        assertTrue(charging.contains(
                "if (!data || data.error || data.success === false) return null"));
        assertTrue(charging.contains(
                "var sessions = self._payload(b.sessions, 'sessions', true, false)"));
        assertTrue(charging.contains(
                "var sessions = self._payload(d, 'sessions', true, true)"));
        assertTrue(charging.contains(
                "if (sessions === null) throw new Error('invalid sessions payload')"));
        assertTrue(charging.contains(
                "self._hideSkeleton();\n"
                        + "                    return false;"));
        assertTrue(charging.contains("self._loadCurrentLivePair()"));
        assertTrue(charging.contains(
                "var summary = self._payload(d, 'summary', false, true)"));
        assertTrue(charging.contains(
                "var soc = self._payload(d, 'soc', true, true)"));
        assertTrue(charging.contains(
                "var config = self._payload(d, 'config', false, true)"));
        assertTrue(!charging.contains(
                "self._applySessions(d.sessions || [], offset)"));
    }

    @Test
    public void asynchronousViewsUseGenerationAndPeriodGuards()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "var generation = ++this._detailGeneration"));
        assertTrue(charging.contains(
                "self._isCurrentDetail(id, generation)"));
        assertTrue(charging.contains(
                "String(this.currentSessionId)\n"
                        + "                === String(this._detailSessionId)"));
        assertTrue(charging.contains(
                "periodKey !== self._periodKey()"));
        assertTrue(charging.contains(
                "generation !== self._sessionsGeneration"));
        assertTrue(charging.contains(
                "this._sessionsLoadMorePending"));
        assertTrue(charging.contains(
                "generation !== self._socGeneration"));
        assertTrue(charging.contains(
                "generation !== self._tariffsGeneration"));
        assertTrue(charging.contains(
                "tariffsGeneration === self._tariffsGeneration"));
    }

    @Test
    public void visibleRefreshIsSingleFlightAndOpenRowsUseLiveVerdict()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String core = readRepositoryFile(
                "app/src/main/assets/web/shared/core.js");

        assertTrue(charging.contains("_liveRefreshInFlight"));
        assertTrue(charging.contains(
                "if (this._liveRefreshInFlight) return this._liveRefreshInFlight"));
        assertTrue(charging.contains(
                "this._summaryGeneration++"));
        assertTrue(charging.contains(
                "this._sessionsGeneration++"));
        assertTrue(charging.contains(
                "'/api/charging/overview?' + periodKey"));
        assertTrue(charging.contains(
                "var summary = self._payload(\n"
                        + "                d, 'summary', false, true)"));
        assertTrue(charging.contains(
                "var sessions = self._payload(\n"
                        + "                d, 'sessions', true, true)"));
        assertTrue(charging.contains(
                "var chargingNow = inProgress && s.chargingNow !== false"));
        assertTrue(charging.contains(
                "this._setText('detailTimeToFull', (chargingNow"));
        assertTrue(charging.contains(
                "String(detailRow.id)\n"
                        + "                            === String(this.currentSessionId)"));
        assertTrue(charging.contains(
                "this._fillDetailHeader(detailRow, detailRow.id)"));
        assertTrue(core.contains("if (!isCharging) powerKW = 0"));
        assertTrue(core.contains(
                "powerSource !== 'nominalPlaceholder'"));
        assertTrue(core.contains(
                "(isEstimated ? '~' : '')"));
        assertTrue(core.contains("evPower.textContent = '-- kW'"));
    }

    @Test
    public void tariffMutationWarnsWhenHistoryCommitIsNotConfirmed()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "d.repricingStatus !== 'complete'"));
        assertTrue(charging.contains(
                "'Tariff saved. Past charges will be re-priced automatically when storage is ready.'"));
        assertTrue(charging.contains(
                "this._toast(warning, warningType)"));
        assertTrue(charging.contains(
                "d.success || d.tariffSaved"));
    }

    @Test
    public void configRefreshAndRejectedSavePreserveDirtyFields()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");

        assertTrue(charging.contains(
                "var body = this._dirtyConfigBody()"));
        assertTrue(charging.contains(
                "if (this._configDirty[key]) body[key] = current[key]"));
        assertTrue(charging.contains(
                "if (!dirtyBeforeResponse[key])"));
        assertTrue(charging.contains(
                "if (!r.ok || !d || d.success !== true)"));
        assertTrue(charging.contains(
                "Keep the rejected values and original durable baseline"));
        assertTrue(!charging.contains(
                "self.resetApplyButton();\n"
                        + "              self.loadConfig();"));
    }

    @Test
    public void bootstrapMatchesInitiallyActiveSevenDayControls()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/charging.html");

        assertTrue(charging.contains("currentDays: 7"));
        assertTrue(charging.contains("socHours: 168"));
        assertTrue(charging.contains(
                "'&hours=' + hours"));
        assertTrue(html.contains(
                "class=\"filter-tab active\" data-days=\"7\""));
        assertTrue(html.contains(
                "class=\"filter-tab active\" data-hours=\"168\""));
    }

    @Test
    public void sessionCardsKeepTierLabelsAndTemperatureChartsHandlePartialData()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/charging.html");

        assertTrue(charging.contains(
                "self._typeIcon(kind) + '<span>'"));
        assertTrue(charging.contains("self._esc(typeLabel)"));
        assertTrue(charging.contains(
                "return this._t('charge.type_dc', 'DC fast')"));
        assertTrue(charging.contains(
                "return this._t('charge.type_fast', 'AC fast')"));
        assertTrue(charging.contains(
                "return this._t('charge.type_slow', 'AC slow')"));
        assertTrue(html.contains(
                "flex: 0 0 auto; white-space: nowrap;"));

        assertTrue(charging.contains(
                "_temperaturePoints: function (samples)"));
        assertTrue(charging.contains(
                "if (center == null) center = hi != null ? hi : lo"));
        assertTrue(charging.contains("if (center > hi) hi = center"));
        assertTrue(charging.contains("if (center < lo) lo = center"));
        assertTrue(charging.contains(
                "var hasTemperatureSamples = this._temperaturePoints(samples).length > 1"));
        assertTrue(charging.contains(
                "var hasSamples = this._powerCurveValueCount(powerSamples) > 1"));
        assertTrue(charging.contains(
                "power: hasPower ? s.power : null"));
        assertTrue(charging.contains(
                "isFinite(s.power) && s.power > 0"));
        assertTrue(charging.contains(
                "var segments = [], segment = []"));
        assertTrue(charging.contains(
                "_energyIsApproximate: function (energy)"));
        assertTrue(charging.contains(
                "energy.sessionEnergyEstimated === true"));
        assertTrue(charging.contains(
                "if (source !== '') return source !== 'metered_counter'"));
        assertTrue(charging.contains(
                "s.periodEstimatedSessions || s.periodIncompleteSessions"));
        assertTrue(charging.contains(
                "s.lifetimeEstimatedSessions || s.lifetimeIncompleteSessions"));
        assertTrue(charging.contains(
                "(periodEnergyApproximate ? '~' : '')"));
        assertTrue(charging.contains(
                "approximate: this._energyIsApproximate(s)"));
        assertTrue(charging.contains(
                "var prefix = p.approximate ? '~' : ''"));
        assertTrue(charging.contains(
                "var approximate = (p.estimated || p.incomplete || 0) > 0"));
        assertTrue(html.contains("id=\"detailTempNoSamples\""));
        assertTrue(html.contains("id=\"statsEstimateDisclosure\""));
        assertTrue(html.contains("id=\"summaryEstimateDisclosure\""));
        assertTrue(html.contains("id=\"detailEstimateDisclosure\""));
        assertTrue(html.contains("<details class=\"ch-estimate-disclosure\""));
        assertTrue(charging.contains(
                "(powerEstimated ? '≈' : '') + livePowerKw.toFixed(1) + ' kW'"));
        assertTrue(charging.contains(
                "'Power & energy estimated'"));
        assertTrue(charging.contains(
                "'Power estimated'"));
        assertTrue(charging.contains(
                "'Energy estimated'"));
        assertTrue(charging.contains(
                "this._t('charge.not_measured', 'Not measured')"));
        assertTrue(html.contains("charging.js?v=46"));
    }

    @Test
    public void importedChargingViewKeepsLabelsAndLegacySocReadable()
            throws IOException {
        String charging = readRepositoryFile(
                "app/src/main/assets/web/shared/charging.js");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/charging.html");
        String english = readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");

        assertTrue(english.contains(
                "\"sessions_title\": \"Charging sessions\""));
        assertTrue(html.contains("data-i18n=\"charge.sessions_title\""));
        assertTrue(html.contains("id=\"socRangeValue\""));
        assertTrue(html.contains("id=\"socSohValue\""));
        assertTrue(html.contains("id=\"completionHeroCard\""));
        assertTrue(html.contains("id=\"sessionSort\""));
        assertTrue(charging.contains("_socRangeText: function (session)"));
        assertTrue(charging.contains(
                "(hasStart ? Math.round(start) + '%' : '--')"));
        assertTrue(charging.contains(
                "(hasEnd ? Math.round(end) + '%' : '--')"));
        assertTrue(charging.contains(
                "this._showCard('statsLowerGrid', hasEfficiency || hasSessions)"));
        assertTrue(charging.contains("_latestBatterySnapshot: function ()"));
        assertTrue(charging.contains(
                "this.summaryCache, this._summaryPeriodKey, true"));
        assertTrue(html.contains(
                "grid-template-areas:\n"
                        + "                \"primary start duration actions\"\n"
                        + "                \"primary range context actions\""));
        assertTrue(html.contains(
                "#chargingDetail { width: 100%; max-width: 1900px;"));
        assertTrue(html.contains(
                ".ch-detail-chart-stat > div { min-width: 0; flex: 1; }"));
        assertTrue(html.contains(
                "white-space: normal; overflow-wrap: break-word;"));
        assertTrue(api.contains("live.put(\"rangeKm\""));
        assertTrue(api.contains("live.put(\"sohPercent\""));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
