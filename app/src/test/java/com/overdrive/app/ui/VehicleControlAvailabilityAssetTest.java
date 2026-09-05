package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the Vehicle screen's cloud availability and partial-door-state UI. */
public class VehicleControlAvailabilityAssetTest {

    @Test
    public void dynamicStatusLabelsSurviveI18nHydration() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(html.contains("<span id=\"lockStatus\">NO DATA</span>"));
        assertTrue(html.contains("<span id=\"cloudStatusText\">Checking...</span>"));
        assertFalse(html.contains("id=\"lockStatus\" data-i18n="));
        assertFalse(html.contains("id=\"cloudStatusText\" data-i18n="));
        assertTrue(script.contains("self.updateCloudIndicator();"));
        assertTrue(script.contains("self.updateCloudControlAvailability();"));
        assertTrue(script.contains("cloudState = 'unavailable'"));
        assertTrue(script.contains("startCloudStatusSync"));
    }

    @Test
    public void partialDoorReadingIsNotPresentedAsWholeVehicleState() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        String english = readRepositoryFile("app/src/main/assets/web/i18n/en.json");

        assertTrue(api.contains("doors.put(\"scope\", \"driver_door\")"));
        assertTrue(api.contains("doors.put(\"scope\", \"vehicle\")"));
        assertTrue(script.contains("d.source === 'ota' ? 'driver_door' : 'vehicle'"));
        assertTrue(script.contains("scope === 'driver_door'"));
        assertTrue(script.contains("vehicle.driver_door_unlocked"));
        assertTrue(english.contains("\"driver_door_unlocked\": \"Driver door unlocked\""));
    }

    @Test
    public void cloudOnlyControlsAreMarkedWithoutDisablingLocalControls() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        // 8 original cloud tiles + the vent preset and the two remote-preconditioning
        // buttons. The steering-wheel heater is deliberately absent: it is SDK-first
        // with a working local leg, like the seat tiles beside it.
        assertEquals(11, count(html, "data-requires-cloud=\"true\""));
        assertFalse("steering heat is dual-path — marking it dashes out a working control",
                html.contains("id=\"btnSteeringHeat\" title=\"Steering wheel heater\""
                        + " data-i18n-attr=\"title:vehicle.steering_heat\""
                        + " data-requires-cloud=\"true\""));
        assertTrue(html.contains(
                "id=\"btnLock\" title=\"Lock\" data-requires-cloud=\"true\""));
        assertFalse(html.contains(
                "id=\"btnTrunkOpen\" title=\"Open Trunk\" data-requires-cloud=\"true\""));
        assertTrue(html.contains(
                "id=\"btnStartCharging\" title=\"Start charging now\" data-requires-cloud=\"true\""));
        assertFalse(html.contains(
                "id=\"btnTrunkClose\" title=\"Close Trunk\" data-requires-cloud=\"true\""));
        assertFalse(html.contains(
                "id=\"btnDRL\" title=\"Daytime running lights\" data-requires-cloud=\"true\""));
        assertTrue(css.contains(".vc-tile[data-requires-cloud=\"true\"]::after"));
        assertTrue(css.contains(".vc-tile[data-cloud-state=\"not_configured\"]"));
        assertTrue(script.contains("querySelectorAll('[data-requires-cloud=\"true\"]')"));
        assertTrue(script.contains("this.showCloudModal();"));
    }

    /**
     * Every cloud-ONLY control must both carry the marker and guard its handler:
     * the marker alone still lets a tap fail opaquely instead of explaining that
     * an account is needed. The vent preset had the guard missing.
     */
    @Test
    public void cloudOnlyControlsGuardTheirHandlers() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        // Vent is CLOUD_ONLY (BYD OPENWINDOW); its 0/100 neighbours are SDK paths.
        assertTrue(html.contains("id=\"btnWinAllVent\""));
        assertTrue(html.contains("id=\"btnWinAllVent\" title=\"Vent all windows\""
                + " data-i18n-attr=\"title:vehicle.vent_all_windows\""
                + " data-requires-cloud=\"true\""));
        assertFalse(html.contains("id=\"btnWinAllOpen\" title=\"All Windows Open\""
                + " data-requires-cloud=\"true\""));
        // The marker must render on a preset, not only on a tile.
        assertTrue(css.contains(".vc-preset[data-requires-cloud=\"true\"]::after"));
        assertTrue(script.contains("this.bindBtn('btnWinAllVent', function() {\n"
                + "            if (!self.requireCloud()) return;"));
        // Remote preconditioning (BOOKINGAIR) is cloud-only in both directions.
        assertTrue(script.contains("this.bindBtn('btnClimateScheduleSave', function() {\n"
                + "            if (!self.requireCloud()) return;"));
        assertTrue(script.contains("this.bindBtn('btnClimateScheduleClear', function() {\n"
                + "            if (!self.requireCloud()) return;"));
    }

    /**
     * Remote preconditioning must not invent state, but it also must not hide
     * itself when the car simply could not be reached. Capability follows the
     * ACCOUNT; only an explicit supported=false hides the row. Gating on a
     * completed booking-list probe made the row never appear at all when that
     * read failed (asleep car / rate limit / network blip).
     */
    @Test
    public void remotePreconditioningUiOnlyShowsConfirmedState() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");

        assertTrue(html.contains("id=\"climateScheduleSection\" style=\"display:none;\""));
        // Visibility keys off the account, not off a successful list read.
        assertTrue(script.contains("var canSchedule = s.supported !== false\n"
                + "                    && (s.supported === true || this.vehicleState.cloudConfigured);"));
        assertFalse("must not gate the row on a completed probe",
                script.contains("section.style.display = (s.supported === true) ? '' : 'none';"));
        // Server reports capability before attempting the reachability-dependent read.
        assertTrue(api.contains("response.put(\"bookingsUnavailable\", true);"));
        // An unreachable car must not be reconciled as "no booking".
        assertTrue(script.contains("if (data.bookingsUnavailable === true) {"));
        // Booking ids are 64-bit; they must stay decimal text end to end.
        assertTrue(script.contains("if (typeof result.bookingId === 'string' && result.bookingId) {"));
        assertFalse(script.contains("parseInt(first.bookingId"));
        // A stale fetch must never overwrite a pending write or a user edit.
        assertTrue(script.contains("|| self._climateScheduleDirty\n"
                + "                    || self._climateSchedulePending) return;"));
        // Clear is only offered against a known booking.
        assertTrue(script.contains("clearBtn.style.display = s.bookingId ? '' : 'none';"));
    }

    /**
     * Battery preconditioning is cloud-only in BOTH directions and has no SDK
     * readback. Without a state channel the tile was write-only: after a reload
     * every tap re-sent "on", so the heater could never be switched off.
     */
    @Test
    public void batteryHeatHasAReadbackSoItCanBeSwitchedOff() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");

        // Server publishes it from a FRESH cloud snapshot, and omits the key otherwise.
        assertTrue(api.contains("response.put(\"batteryHeat\", cs.batteryHeatState > 0);"));
        assertTrue(api.contains("cs.isTelemetryFresh() && cs.hasBatteryHeatState()"));
        // Client renders it and reconciles it, with an in-flight guard.
        assertTrue(script.contains("updateBatteryHeatUI: function()"));
        assertTrue(script.contains("typeof data.batteryHeat === 'boolean'"));
        assertTrue(script.contains("!self._batteryHeatPending"));
        assertTrue(script.contains("self.updateBatteryHeatUI();"));
    }

    /**
     * An empty BOOKINGAIR list clears a known booking id ONLY when the server did
     * not flag the response as possibly-stale — BYD returns empty objects for live
     * bookings, so the flag has to be load-bearing rather than merely transmitted.
     */
    @Test
    public void emptyBookingListOnlyClearsIdWhenAuthoritative() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");

        assertTrue(api.contains("response.put(\"emptyBookingsMayBeStale\", true);"));
        assertTrue(script.contains(
                "if (!first && s.bookingId && data.emptyBookingsMayBeStale !== true) {"));
    }

    /**
     * The steering-wheel heater is on/off only, and an unread state must not be
     * presented as "off" — the payload omits the key when nothing answered.
     */
    @Test
    public void steeringWheelHeatDoesNotPresentUnknownStateAsOff() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");

        assertTrue(script.contains("steeringHeat: null"));
        assertTrue(script.contains("typeof data.seats.steeringHeat === 'boolean'"));
        assertTrue(script.contains("if (this.vehicleState.steeringHeat === true) btn.classList.add('on2');"));
        // Server: local read (2=on/1=off) first, then a FRESH cloud snapshot only.
        assertTrue(api.contains("if (wheelHeat == 1 || wheelHeat == 2) {"));
        assertTrue(api.contains("cs.isTelemetryFresh() && cs.hasSteeringWheelHeatState()"));
    }

    @Test
    public void scheduleAndTrunkUiDoNotPresentStaleOrInventedState() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("s.startChargeTime = null;"));
        assertTrue(script.contains("s.endChargeTime = null;"));
        assertTrue(script.contains("s.smartJourneyDto = null;"));
        assertTrue(script.contains("s.supported = data.supported !== false;"));
        assertTrue(script.contains("if (data.supported === false) {"));
        assertTrue(script.contains("startInput.value = s.startChargeTime || ''"));
        assertTrue(script.contains("btn.disabled = unsupported || pending;"));
        assertTrue(script.contains("saveBtn.disabled = unsupported || pending;"));
        assertTrue(script.contains("this.removeStateGlow('trunk');"));
        assertFalse(script.contains("trunkVal === 2"));
    }

    @Test
    public void chargeCapSliderOnlyCommitsVerifiedValuesAndRestoresOnFailure()
            throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertFalse(script.contains("self.vehicleState.chargeCap.percent = v;"));
        assertTrue(script.contains("var revision = ++capRevision;"));
        assertTrue(script.contains(
                "if (revision !== capRevision || stateRevision !== self._chargeCapRevision) return;"));
        assertTrue(script.contains("self.updateChargeCapUI();\n                            self.fetchChargeCap();"));
        int rejectionCatch = script.indexOf("}).catch(function(e) {");
        int rejectionGuard = script.indexOf(
                "if (revision !== capRevision || stateRevision !== self._chargeCapRevision) return;",
                rejectionCatch);
        int rejectionRestore = script.indexOf("self.updateChargeCapUI();", rejectionGuard);
        assertTrue("POST rejection must retain the revision guard", rejectionGuard > rejectionCatch);
        assertTrue("POST rejection must restore the verified UI value", rejectionRestore > rejectionGuard);
    }

    private static int count(String text, String needle) {
        int result = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            result++;
            from += needle.length();
        }
        return result;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
