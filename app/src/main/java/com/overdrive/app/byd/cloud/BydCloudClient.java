package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodec;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodecFactory;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * High-level BYD cloud API client.
 * 
 * Handles login, vehicle list, control PIN verification, and remote commands.
 * 
 * Port of: pyBYD/src/pybyd/client.py (BydClient)
 * Also matches: Niek/BYD-re/client.js (login, remote control flow)
 */
public final class BydCloudClient {

    private static final String TAG = "BydCloudClient";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private final BydCloudConfig config;
    private final EnvelopeCodec codec;
    private BydCloudTransport transport;
    private BydCloudSession session;
    private boolean commandsVerified = false;

    private static final long CAPABILITY_CACHE_TTL_MS = 30L * 60L * 1000L;
    private volatile CloudCapabilities cloudCapabilities;
    static final String SMART_CHARGE_REQUEST_ORDER_KEY =
            "__overdriveSmartChargeRequestOrder";
    public static final String DRIVING_SAFETY_BLOCKED_CODE =
            "OVERDRIVE_BLOCKED_DRIVING";
    private static final AtomicLong smartChargeRequestOrder = new AtomicLong();

    // Overseas realtime and remote-control requests from an account the car is shared with must
    // carry a signed outer userType=1; the cloud refuses a wrong declaration before the car hears it.
    private static final String USER_TYPE_SHARED = "1";
    private static final String CODE_SHARED_NOT_DECLARED = "1001";
    private static final String CODE_NOT_SHARED = "8003";
    /** "userId|vin" → whether that account reaches the car as a shared user. */
    private static final ConcurrentMap<String, Boolean> SHARED_ACCOUNT_VINS =
            new ConcurrentHashMap<>();

    public BydCloudClient(BydCloudConfig config) {
        this.config = config;
        // Region selects the transport codec: WBSK for China, Bangcle otherwise.
        this.codec = EnvelopeCodecFactory.createCodec(config.isChinaRegion());
    }

    BydCloudClient(BydCloudConfig config, EnvelopeCodec codec, BydCloudTransport transport) {
        this.config = config;
        this.codec = codec;
        this.transport = transport;
    }

    /**
     * Initialize the codec by loading its white-box tables (Bangcle or WBSK,
     * per region). Must be called before any API operations.
     */
    public void init(InputStream tablesStream) throws IOException {
        codec.loadTables(tablesStream);
        transport = new BydCloudTransport(config, codec);
    }

    /**
     * Check if the client is initialized and ready.
     */
    public boolean isReady() {
        return codec.isReady() && transport != null;
    }

    // ── Authentication ──────────────────────────────────────────────────

    /**
     * Login to BYD cloud API and obtain session tokens.
     * Synchronized so the connect path and the periodic refresh path can't
     * race — concurrent logins invalidate each other's tokens, which surfaces
     * as a "broker lookup 1005 → force re-login" loop in MQTT subscriber logs.
     */
    public synchronized void login() throws IOException {
        if (!isReady()) throw new IllegalStateException("Client not initialized");

        long nowMs = System.currentTimeMillis();
        boolean cn = config.isChinaRegion();
        JSONObject outer = cn ? buildCnLoginRequest(nowMs) : buildLoginRequest(nowMs);
        JSONObject response = transport.postSecure(
                cn ? "/app/auth/login" : "/app/account/login", outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "Unknown error");
            // 1009 = server temporarily unavailable (rate limit or overload)
            // Don't throw a hard error — the session will be retried on next command
            if ("1009".equals(code)) {
                logger.warn("Login got server error 1009 — BYD cloud temporarily unavailable");
                throw new IOException("BYD cloud temporarily unavailable (1009)");
            }
            throw new IOException("Login failed: code=" + code + " message=" + msg);
        }

        String respondData = response.optString("respondData", "");
        JSONObject loginInner = BydCloudTransport.decryptRespondData(respondData, config.loginKey);
        JSONObject token = loginInner.optJSONObject("token");
        if (token == null) {
            throw new IOException("Login response missing token");
        }

        String signToken = token.optString("signToken", "");
        String encryToken = token.optString("encryToken", "");
        if (encryToken.isEmpty()) {
            encryToken = token.optString("encryptToken", "");
        }

        String userId;
        String superId = "";
        if (cn) {
            // CN: token carries superId + a per-brand relation map. The
            // effective userId is the brand-specific one (targetBrand), falling
            // back to superId. superId is also used for MQTT / outer identifier.
            superId = token.optString("superId", "");
            String brandUserId = "";
            JSONObject rel = token.optJSONObject("superBindRelationDtoMap");
            if (rel != null) {
                JSONObject entry = rel.optJSONObject(BydCloudConfig.CN_TARGET_BRAND);
                if (entry != null) {
                    String uid = entry.optString("userId", "");
                    if (!uid.isEmpty() && !"null".equals(uid)) {
                        brandUserId = uid;
                    }
                }
            }
            userId = !brandUserId.isEmpty() ? brandUserId : superId;
        } else {
            userId = token.optString("userId", "");
        }

        if (userId.isEmpty() || signToken.isEmpty() || encryToken.isEmpty()) {
            throw new IOException("Login response missing token fields");
        }

        session = new BydCloudSession(userId, signToken, encryToken, superId);
        // The cloud binds control-PIN verification to the session token.
        commandsVerified = false;
        logger.info("Login succeeded: userId=***" + userId.substring(Math.max(0, userId.length() - 4)));
    }

    /**
     * Ensure we have a valid session, re-authenticating if needed.
     * Retries login once with a short backoff on transient server errors (1009).
     * Synchronized so the check-then-login is atomic — without this two
     * concurrent callers can both decide the session is expired and both
     * issue logins, invalidating the first caller's token.
     */
    public synchronized BydCloudSession ensureSession() throws IOException {
        throwIfInterrupted();
        if (session == null || session.isExpired()) {
            try {
                login();
            } catch (IOException e) {
                throwIfInterrupted();
                // On transient server error, retry once after a brief pause
                if (e.getMessage() != null && e.getMessage().contains("1009")) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("BYD cloud session request cancelled");
                    }
                    throwIfInterrupted();
                    login();  // Second attempt — if this fails, propagate the exception
                } else {
                    throw e;
                }
            }
        }
        return session;
    }

    private static void throwIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("BYD cloud session request cancelled");
        }
    }

    // ── Vehicle List ────────────────────────────────────────────────────

    /**
     * Fetch all vehicles and return the first VIN.
     */
    public String fetchFirstVin() throws IOException {
        String[] result = fetchFirstVinAndEnergyType();
        return result[0];
    }

    /**
     * Fetch all vehicles and return [VIN, energyType].
     */
    public String[] fetchFirstVinAndEnergyType() throws IOException {
        JSONArray list = fetchVehicleList();

        for (int i = 0; i < list.length(); i++) {
            JSONObject vehicle = list.optJSONObject(i);
            if (vehicle != null) {
                String vin = vehicle.optString("vin", "");
                if (!vin.isEmpty()) {
                    String energyType = vehicle.optString("energyType", "");
                    logger.info("Found vehicle: VIN=***" + vin.substring(Math.max(0, vin.length() - 4))
                            + " energyType=" + energyType);
                    return new String[]{vin, energyType};
                }
            }
        }

        throw new IOException("No vehicle with VIN found");
    }

    private JSONArray fetchVehicleList() throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        boolean cn = config.isChinaRegion();
        JSONObject inner = buildInner(nowMs);
        if (cn) {
            // CN vehicle-list inner adds appUiName (cn_envelope.build_cn_vehicle_list_inner)
            try { inner.put("appUiName", ""); } catch (Exception ignored) {}
        }
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure(
                cn ? "/app/auth/getAllListByUserId" : "/app/account/getAllListByUserId", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Vehicle list failed: code=" + code);
        }

        String respondDataHex = response.optString("respondData", "");
        if (respondDataHex.isEmpty()) {
            throw new IOException("Vehicle list: empty respondData");
        }

        String plain = BydCryptoUtils.aesDecryptUtf8(respondDataHex, env.contentKey);
        plain = plain.trim();

        JSONArray list = null;
        try {
            list = new JSONArray(plain);
        } catch (Exception e) {
            try {
                JSONObject obj = new JSONObject(plain);
                list = obj.optJSONArray("diLinkAutoInfoList");
            } catch (Exception e2) {
                throw new IOException("Could not parse vehicle list response");
            }
        }

        if (list == null || list.length() == 0) {
            throw new IOException("No vehicles found on account");
        }

        seedSharedVins(s, list);
        return list;
    }

    /** First guess per VIN; never overrides what the cloud's answers already corrected. */
    private void seedSharedVins(BydCloudSession s, JSONArray list) {
        if (config.isChinaRegion()) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject vehicle = list.optJSONObject(i);
            String vin = vehicle == null ? "" : vehicle.optString("vin", "");
            if (vin.isEmpty()) continue;
            boolean shared = isSharedVehicle(vehicle);
            if (SHARED_ACCOUNT_VINS.putIfAbsent(sharedKey(s, vin), shared) == null && shared) {
                logger.info("BYD cloud: VIN ***" + vin.substring(Math.max(0, vin.length() - 4))
                        + " is shared with this account; declaring userType=1");
            }
        }
    }

    /** A non-zero empowerId or a negative empowerType marks a car shared with the account. */
    static boolean isSharedVehicle(JSONObject vehicle) {
        Double empowerId = numberOf(vehicle.opt("empowerId"));
        Double empowerType = numberOf(vehicle.opt("empowerType"));
        return (empowerId != null && empowerId != 0d) || (empowerType != null && empowerType < 0d);
    }

    private static Double numberOf(Object value) {
        double n;
        if (value instanceof Number) {
            n = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                n = Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isNaN(n) ? null : n;
    }

    private static String sharedKey(BydCloudSession s, String vin) {
        return s.userId + "|" + vin;
    }

    private String userTypeFor(BydCloudSession s, String vin) {
        if (config.isChinaRegion() || vin == null || vin.isEmpty()) return null;
        return Boolean.TRUE.equals(SHARED_ACCOUNT_VINS.get(sharedKey(s, vin)))
                ? USER_TYPE_SHARED : null;
    }

    /** Whether {@code first} is the cloud's refusal of the userType it was sent with. */
    private boolean needsOtherUserType(String endpoint, BydCloudSession s, String vin,
                                       String sentUserType, JSONObject first) {
        if (config.isChinaRegion() || vin == null || vin.isEmpty() || first == null) return false;
        String wrongUserType = sentUserType != null ? CODE_NOT_SHARED : CODE_SHARED_NOT_DECLARED;
        return wrongUserType.equals(first.optString("code", ""))
                && !s.userTypeSettled.contains(vin + "|" + endpoint);
    }

    /**
     * Resends a refused request once with the other userType (fresh random and timeStamp) and
     * remembers what worked. Refused both ways, the first answer stands for the rest of the login.
     */
    private JSONObject withUserTypeCorrected(String endpoint, BydCloudSession s, String vin,
                                             JSONObject inner, String sentUserType,
                                             JSONObject first, long callTimeoutMs,
                                             BooleanSupplier authority) throws IOException {
        if (!needsOtherUserType(endpoint, s, vin, sentUserType, first)) return first;
        boolean shared = sentUserType != null;
        long retryMs = System.currentTimeMillis();
        JSONObject retryInner = new JSONObject();
        try {
            for (Iterator<String> it = inner.keys(); it.hasNext(); ) {
                String key = it.next();
                retryInner.put(key, inner.opt(key));
            }
            retryInner.put("random", BydCryptoUtils.randomHex16());
            retryInner.put("timeStamp", String.valueOf(retryMs));
        } catch (Exception e) {
            return first;
        }
        TokenEnvelope retry = buildTokenOuterEnvelope(
                retryMs, s, retryInner, shared ? null : USER_TYPE_SHARED);
        JSONObject second = transport.postSecure(endpoint, retry.outer, callTimeoutMs, authority);
        String refusedBothWays = shared ? CODE_SHARED_NOT_DECLARED : CODE_NOT_SHARED;
        if (refusedBothWays.equals(second.optString("code", ""))) {
            if (s.userTypeSettled.add(vin + "|" + endpoint)) {
                logger.info("BYD cloud " + endpoint + " refused with and without userType: code="
                        + first.optString("code", "") + "/" + refusedBothWays);
            }
            return first;
        }
        SHARED_ACCOUNT_VINS.put(sharedKey(s, vin), !shared);
        logger.info("BYD cloud: VIN ***" + vin.substring(Math.max(0, vin.length() - 4))
                + (shared ? " answers as the owner; userType dropped"
                          : " is shared with this account; declaring userType=1"));
        return second;
    }

    /** Return cached capability data when it still belongs to {@code vin}. */
    public CloudCapabilities getCachedCloudCapabilities(String vin) {
        CloudCapabilities cached = cloudCapabilities;
        if (cached == null || !cached.isForVin(vin)) return null;
        if (System.currentTimeMillis() - cached.getFetchedAtMs() > CAPABILITY_CACHE_TTL_MS) return null;
        return cached;
    }

    /**
     * Fetch BYD's per-VIN cloud command configuration before a router-managed
     * cloud feature is dispatched. A discovery failure leaves SDK-first
     * controls local-only for that attempt and blocks cloud-only controls.
     */
    public synchronized CloudCapabilities fetchCloudCapabilities(String vin) throws IOException {
        CloudCapabilities cached = getCachedCloudCapabilities(vin);
        if (cached != null) return cached;
        if (vin == null || vin.isEmpty()) throw new IOException("vin required");

        JSONObject vehicle = null;
        try {
            JSONArray vehicles = fetchVehicleList();
            for (int i = 0; i < vehicles.length(); i++) {
                JSONObject candidate = vehicles.optJSONObject(i);
                if (candidate != null && vin.equals(candidate.optString("vin", ""))) {
                    vehicle = candidate;
                    break;
                }
            }
        } catch (IOException e) {
            // getLatestConfig still gives the coarse function gate. For
            // OPENWINDOW, pyBYD intentionally falls back to function 1026 when
            // vehicleFunLearnInfo is unavailable; an explicit returned 0/0 is
            // still honored by CloudCapabilities.
            logger.info("Capability vehicle metadata unavailable: " + e.getMessage());
        }

        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("appConfigVersion", "2");
            inner.put("terminalType", "0");
            JSONArray vinList = new JSONArray();
            vinList.put(vin);
            inner.put("vinList", vinList.toString());
        } catch (Exception e) {
            throw new IOException("Failed to build latest-config request", e);
        }
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/vehicle/vehicleswitch/getLatestConfig", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Latest-config fetch failed: code=" + code + " "
                    + response.optString("message", ""));
        }
        JSONObject decoded = decodeRespondData(response, env.contentKey);
        if (decoded == null) throw new IOException("Latest-config response was empty");
        JSONObject perVin = decoded.optJSONObject(vin);
        if (perVin == null) {
            throw new IOException("Latest-config response missing requested VIN");
        }
        CloudCapabilities parsed = CloudCapabilities.fromResponses(
                vin, perVin, vehicle, System.currentTimeMillis());
        cloudCapabilities = parsed;
        logger.info("Cloud window-vent gate: " + parsed.windowVentGateSummary());
        return parsed;
    }

    // ── Control PIN Verification ────────────────────────────────────────

    /**
     * Verify the control PIN. Must be called once before remote commands.
     */
    /** True once {@link #verifyControlPassword(String)} has succeeded this session. */
    public boolean isControlPasswordVerified() {
        return commandsVerified;
    }

    public void verifyControlPassword(String vin) throws IOException {
        // Idempotent — once we've verified the PIN this session, every subsequent
        // /control/remoteControl call piggybacks on the same verification flag.
        // Re-posting wastes a round-trip and adds 2-4s of latency to every command.
        if (commandsVerified) return;
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = new JSONObject();
        try {
            inner.put("commandPwd", config.commandPwd);
            inner.put("deviceType", "0");
            inner.put("functionType", "remoteControl");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("networkType", "wifi");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            inner.put("vin", vin);
        } catch (Exception e) {
            throw new IOException("Failed to build verify request", e);
        }

        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure(
                "/vehicle/vehicleswitch/verifyControlPassword", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            throw new IOException("Control PIN verification failed: code=" + code + " " + msg);
        }

        commandsVerified = true;
        logger.info("Control PIN verified for VIN=***" + vin.substring(Math.max(0, vin.length() - 4)));
    }

    // ── Remote Commands ─────────────────────────────────────────────────

    /**
     * Flash the vehicle's lights.
     */
    public boolean flashLights(String vin) throws IOException {
        return executeRemoteCommand(vin, "FLASHLIGHTNOWHISTLE", true);
    }

    /**
     * Flash lights without waiting for result polling.
     */
    public boolean flashLightsNoWait(String vin) throws IOException {
        return executeRemoteCommand(vin, "FLASHLIGHTNOWHISTLE", false);
    }

    /**
     * Find car (horn + lights).
     */
    public boolean findCar(String vin) throws IOException {
        return executeRemoteCommand(vin, "FINDCAR", true);
    }

    /**
     * Find car without waiting for result polling.
     */
    public boolean findCarNoWait(String vin) throws IOException {
        return executeRemoteCommand(vin, "FINDCAR", false);
    }

    /**
     * Lock the vehicle.
     */
    public boolean lock(String vin) throws IOException {
        return executeRemoteCommand(vin, "LOCKDOOR", true);
    }

    /**
     * Unlock the vehicle.
     */
    public boolean unlock(String vin) throws IOException {
        return executeRemoteCommand(vin, "OPENDOOR", true);
    }

    /**
     * Start remote AC with target temp.
     * BYD OPENAIR: temperature applies to driver+copilot, cycle_mode=2 (auto),
     * remote_mode=4 (cool/heat auto), default time_span=3 (20 min).
     */
    public boolean startClimate(String vin, double tempCelsius) throws IOException {
        return executeRemoteCommand(vin, "OPENAIR", climateStartParams(tempCelsius), true).success;
    }

    /**
     * Stop remote AC.
     */
    public boolean stopClimate(String vin) throws IOException {
        return executeRemoteCommand(vin, "CLOSEAIR", null, true).success;
    }

    /**
     * Close all four windows.
     */
    public boolean closeAllWindows(String vin) throws IOException {
        return executeRemoteCommand(vin, "CLOSEWINDOW", null, true).success;
    }

    /** Cloud OPENWINDOW only cracks all windows for ventilation; it is not a full-open command. */
    public boolean ventAllWindows(String vin) throws IOException {
        return executeRemoteCommand(vin, "OPENWINDOW", null, true).success;
    }

    /**
     * Toggle traction battery preconditioning heat.
     * BATTERYHEAT: batteryHeatSwitch=1 enables, 0 disables.
     */
    public boolean setBatteryHeat(String vin, boolean on) throws IOException {
        JSONObject extra = new JSONObject();
        try {
            extra.put("batteryHeatSwitch", on ? 1 : 0);
        } catch (Exception e) {
            throw new IOException("Failed to build BATTERYHEAT params", e);
        }
        return executeRemoteCommand(vin, "BATTERYHEAT", extra, true).success;
    }

    /** Build pyBYD-compatible OPENAIR controlParamsMap with the OEM default 20-minute session. */
    public static JSONObject climateStartParams(double tempCelsius) throws IOException {
        return climateStartParams(tempCelsius, 20);
    }

    /**
     * Build pyBYD-compatible OPENAIR controlParamsMap.
     *
     * <p>The remote app exposes five fixed session lengths. Keep that discrete BYD
     * wire contract rather than accepting a minute count that would be rounded or ignored.
     */
    public static JSONObject climateStartParams(double tempCelsius, int durationMinutes)
            throws IOException {
        int celsius = (int) Math.round(Math.max(15, Math.min(31, tempCelsius)));
        int rawTemp = celsius - 14; // BYD raw HVAC scale: 15C=1 .. 31C=17
        int timeSpan = climateDurationToTimeSpan(durationMinutes);
        JSONObject params = new JSONObject();
        try {
            params.put("mainSettingTemp", rawTemp);
            params.put("copilotSettingTemp", rawTemp);
            params.put("cycleMode", 2);
            params.put("timeSpan", timeSpan);
            params.put("remoteMode", 4);
            params.put("airAccuracy", 1);
            params.put("airConditioningMode", 1);
            params.put("airSet", JSONObject.NULL);
        } catch (Exception e) {
            throw new IOException("Failed to build OPENAIR control parameters", e);
        }
        return params;
    }

    /**
     * Build a pyBYD-compatible BOOKINGAIR payload.
     *
     * <p>{@code remoteMode}: 1=create, 2=modify, 3=remove. The cloud expects the
     * normal HVAC defaults even for a removal, while temperature/time-span are meaningful only
     * for create/modify.
     */
    public static JSONObject climateScheduleParams(int remoteMode, Long bookingId,
                                                    Long bookingTimeSeconds,
                                                    Double tempCelsius,
                                                    Integer durationMinutes)
            throws IOException {
        if (remoteMode < 1 || remoteMode > 3) {
            throw new IOException("BOOKINGAIR remoteMode must be 1, 2, or 3");
        }
        if ((remoteMode == 2 || remoteMode == 3)
                && (bookingId == null || bookingId.longValue() <= 0L)) {
            throw new IOException("BOOKINGAIR modify/remove requires bookingId");
        }
        if (remoteMode != 3 && (bookingTimeSeconds == null
                || bookingTimeSeconds.longValue() <= 0L)) {
            throw new IOException("BOOKINGAIR create/modify requires bookingTime");
        }
        int timeSpan = 0;
        if (remoteMode != 3) {
            if (tempCelsius == null) throw new IOException("BOOKINGAIR temperature is required");
            if (durationMinutes == null) throw new IOException("BOOKINGAIR duration is required");
            timeSpan = climateDurationToTimeSpan(durationMinutes.intValue());
        }
        try {
            JSONObject params = new JSONObject();
            // ClimateScheduleParams inherits these defaults from ClimateStartParams.
            params.put("cycleMode", 2);
            params.put("remoteMode", remoteMode);
            params.put("airAccuracy", 1);
            params.put("airConditioningMode", 1);
            params.put("acSwitch", 0);
            if (bookingId != null) params.put("bookingId", bookingId.longValue());
            if (bookingTimeSeconds != null) params.put("bookingTime", bookingTimeSeconds.longValue());
            if (remoteMode != 3) {
                int celsius = (int) Math.round(tempCelsius.doubleValue());
                if (celsius < 15 || celsius > 31) {
                    throw new IOException("BOOKINGAIR temperature must be 15..31 C");
                }
                int rawTemp = celsius - 14;
                params.put("mainSettingTemp", rawTemp);
                params.put("copilotSettingTemp", rawTemp);
                params.put("timeSpan", timeSpan);
            }
            return params;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to build BOOKINGAIR control parameters", e);
        }
    }

    private static int climateDurationToTimeSpan(int durationMinutes) throws IOException {
        switch (durationMinutes) {
            case 10: return 1;
            case 15: return 2;
            case 20: return 3;
            case 25: return 4;
            case 30: return 5;
            default: throw new IOException("BOOKINGAIR duration must be 10, 15, 20, 25, or 30 minutes");
        }
    }

    /**
     * Read BOOKINGAIR entries. The endpoint can return an empty object despite an existing booking,
     * so callers must treat an empty response as "none reported", not a deletion confirmation.
     */
    public JSONObject fetchClimateBookingList(String vin) throws IOException {
        BydCloudSession session = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("vin", vin);
        } catch (Exception e) {
            throw new IOException("Failed to build BOOKINGAIR list request", e);
        }
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, session, inner);
        JSONObject response = transport.postSecure("/control/getBookingList", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("BOOKINGAIR list failed: code=" + code
                    + " message=" + response.optString("message", ""));
        }
        String respondData = response.optString("respondData", "");
        if (respondData.isEmpty()) return new JSONObject();
        try {
            return BydCloudTransport.decryptRespondData(respondData, env.contentKey);
        } catch (Exception e) {
            throw new IOException("Failed to decode BOOKINGAIR list response", e);
        }
    }

    /**
     * Set seat heating/ventilation via cloud (commandType VENTILATIONHEATING).
     *
     * <p>BYD's cloud command requires sending the FULL seat-climate snapshot in
     * one POST — there's no "just change one seat" variant. We pass the current
     * state of all known seats (driver+passenger heat+vent), zero out unknown
     * surfaces (rear seats, steering wheel), and let the BMS apply the diff.
     *
     * <p>Level scale is INVERTED on the wire vs. our 0..2 UI convention:
     * <ul>
     *   <li>UI 0 (off) → wire 3</li>
     *   <li>UI 1 (low) → wire 2</li>
     *   <li>UI 2 (high) → wire 1</li>
     *   <li>0 = "not applicable / feature absent" — used for seats we don't track</li>
     * </ul>
     *
     * @param chairType "1"=driver changed, "2"=copilot, "5"=steering wheel
     */
    public boolean setSeatClimate(String vin, String chairType,
                                   int driverHeatUi, int driverVentUi,
                                   int passengerHeatUi, int passengerVentUi) throws IOException {
        // Retained for callers built before the router supplied a confirmed
        // steering-wheel state. New routed calls must use the overload below:
        // wire value 3 is an explicit steering-wheel OFF command.
        return setSeatClimate(vin, chairType, driverHeatUi, driverVentUi,
                passengerHeatUi, passengerVentUi, 3);
    }

    /**
     * Send a complete, state-preserving front-seat command. The steering-wheel
     * field is also part of BYD's composite payload, so it must be a known wire
     * state (1=on or 3=off), never an assumed default.
     */
    public boolean setSeatClimate(String vin, String chairType,
                                   int driverHeatUi, int driverVentUi,
                                   int passengerHeatUi, int passengerVentUi,
                                   int steeringWheelHeatWireState) throws IOException {
        driverHeatUi = normalizeLegacyUiSeatLevel(driverHeatUi);
        driverVentUi = normalizeLegacyUiSeatLevel(driverVentUi);
        passengerHeatUi = normalizeLegacyUiSeatLevel(passengerHeatUi);
        passengerVentUi = normalizeLegacyUiSeatLevel(passengerVentUi);
        if (!isUiSeatLevel(driverHeatUi) || !isUiSeatLevel(driverVentUi)
                || !isUiSeatLevel(passengerHeatUi) || !isUiSeatLevel(passengerVentUi)) {
            throw new IOException("Seat climate levels must be 0=off, 1=low, or 2=high");
        }
        JSONObject extra = seatClimateParams(chairType, driverHeatUi, driverVentUi,
                passengerHeatUi, passengerVentUi, steeringWheelHeatWireState);
        logger.info("VENTILATIONHEATING request extra=" + extra.toString());
        return executeRemoteCommand(vin, "VENTILATIONHEATING", extra, true).success;
    }

    /**
     * Build pyBYD-compatible VENTILATIONHEATING parameters. BYD's current
     * API model declares the level and mode fields as JSON numbers; only
     * chairType is a string selector.
     */
    static JSONObject seatClimateParams(String chairType,
                                        int driverHeatUi, int driverVentUi,
                                        int passengerHeatUi, int passengerVentUi) throws IOException {
        return seatClimateParams(chairType, driverHeatUi, driverVentUi,
                passengerHeatUi, passengerVentUi, 3);
    }

    static JSONObject seatClimateParams(String chairType,
                                        int driverHeatUi, int driverVentUi,
                                        int passengerHeatUi, int passengerVentUi,
                                        int steeringWheelHeatWireState) throws IOException {
        if (!isUiSeatLevel(driverHeatUi) || !isUiSeatLevel(driverVentUi)
                || !isUiSeatLevel(passengerHeatUi) || !isUiSeatLevel(passengerVentUi)) {
            throw new IOException("Seat climate levels must be 0=off, 1=low, or 2=high");
        }
        if (steeringWheelHeatWireState != 1 && steeringWheelHeatWireState != 3) {
            throw new IOException("Steering wheel heat must be a known wire state (1=on or 3=off)");
        }
        JSONObject extra = new JSONObject();
        try {
            extra.put("chairType", chairType);
            extra.put("remoteMode", 1);
            extra.put("mainHeat", uiToWireSeatLevel(driverHeatUi));
            extra.put("mainVentilation", uiToWireSeatLevel(driverVentUi));
            extra.put("copilotHeat", uiToWireSeatLevel(passengerHeatUi));
            extra.put("copilotVentilation", uiToWireSeatLevel(passengerVentUi));
            // Rear seats are not locally modeled. Zero is the documented
            // no-data/no-action value for those fields. Steering-wheel heat
            // differs: 3 explicitly turns it off, so its known current value
            // is supplied by the router above.
            extra.put("lrSeatHeatState", 0);
            extra.put("lrSeatVentilationState", 0);
            extra.put("lrThirdHeatState", 0);
            extra.put("lrThirdVentilationState", 0);
            extra.put("rrSeatHeatState", 0);
            extra.put("rrSeatVentilationState", 0);
            extra.put("rrThirdHeatState", 0);
            extra.put("rrThirdVentilationState", 0);
            extra.put("steeringWheelHeatState", steeringWheelHeatWireState);
        } catch (Exception e) {
            throw new IOException("Failed to build VENTILATIONHEATING params", e);
        }
        return extra;
    }

    /**
     * Translate UI level (0=off, 1=low, 2=high) to BYD wire level
     * (3=off, 2=low, 1=high). Legacy local level 3 is accepted as high.
     */
    static int uiToWireSeatLevel(int uiLevel) {
        switch (uiLevel) {
            case 0: return 3;  // off
            case 1: return 2;  // low
            case 2:
            case 3: return 1;  // high; 3 is the legacy local four-level "high"
            default: throw new IllegalArgumentException("Invalid seat climate level: " + uiLevel);
        }
    }

    private static int normalizeLegacyUiSeatLevel(int uiLevel) {
        return uiLevel == 3 ? 2 : uiLevel;
    }

    private static boolean isUiSeatLevel(int uiLevel) {
        return uiLevel >= 0 && uiLevel <= 2;
    }

    // ── Smart Charging ──────────────────────────────────────────────────
    // Smart-charge endpoints are config writes, not /control/remoteControl
    // commands. They use the same token-envelope path as data fetches and do
    // NOT require commandPwd. Schedule and immediate-charge writes do require
    // a changeResult terminal confirmation.

    /**
     * BYD response code 1001 has overloaded semantics — pyBYD documents this
     * in `_api/control.py:171-172`:
     *   - For data-fetch endpoints: "endpoint not supported on this region/account"
     *   - For write/command endpoints: "generic server-side rejection of the request"
     * We only treat 1001 as "unsupported" on read paths (homePage). On writes
     * (changeChargeStatue, saveOrUpdate, /control/remoteControl) it just means
     * the request was rejected — could be bad payload, missing pre-condition,
     * server-side state issue, etc. — and must surface as a normal failure.
     */
    private static final String CLOUD_CODE_ENDPOINT_NOT_SUPPORTED = "1001";
    /** Smart-charge write accepted by the cloud but the vehicle is currently unreachable. */
    private static final String CLOUD_CODE_VEHICLE_UNREACHABLE = "6002";

    /** Thrown only when a READ endpoint reports 1001 (genuine "not supported on this region/account"). */
    public static final class SmartChargeNotSupportedException extends IOException {
        public SmartChargeNotSupportedException(String msg) { super(msg); }
    }

    /** A retryable smart-charge write failure; callers must not retry physical commands for us. */
    public static final class SmartChargeVehicleUnreachableException extends IOException {
        public SmartChargeVehicleUnreachableException(String operation, String message) {
            super(operation + ": vehicle unreachable (BYD cloud code 6002)"
                    + (message == null || message.isEmpty() ? "" : ": " + message));
        }
    }

    /**
     * Toggle smart charging on/off via cloud.
     * Endpoint: /control/smartCharge/changeChargeStatue
     * Field: smartChargeSwitch="1"|"0". This is the schedule master switch,
     * not the unsupported "stop charging now" operation.
     */
    public boolean toggleSmartCharging(String vin, boolean enable) throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("vin", vin);
            inner.put("smartChargeSwitch", enable ? "1" : "0");
        } catch (Exception e) {
            throw new IOException("Failed to build smartCharge toggle request", e);
        }
        logger.info("smartCharge toggle request inner=" + redactVin(inner));
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/changeChargeStatue", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("smartCharge toggle failed: code=" + code + " message=" + msg
                    + " respondData=" + detail + " fullResponse=" + response.toString());
            if (CLOUD_CODE_VEHICLE_UNREACHABLE.equals(code)) {
                throw new SmartChargeVehicleUnreachableException("smartCharge toggle", msg);
            }
            // 1001 on write endpoints = generic rejection, NOT "unsupported".
            return false;
        }
        // The outer ACK only proves receipt. Confirm the master switch from
        // homePage before updating cache or reporting success.
        for (int attempt = 0; attempt < SMART_CHARGE_CONFIRM_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("smartCharge toggle cancelled");
            }
            try {
                JSONObject homePage = fetchSmartChargingStatus(vin);
                Boolean effective = SmartChargeCache.cloudEnabled(homePage);
                if (effective != null && effective.booleanValue() == enable) {
                    // A confirmed toggle supersedes a prior schedule save's
                    // propagation grace. Otherwise a recent save can reject
                    // this confirmed enabled-state change for two minutes.
                    SmartChargeCache.confirmEnabled(vin, enable);
                    SmartChargeCache.updateFromCloud(vin, homePage);
                    return true;
                }
            } catch (IOException e) {
                logger.info("smartCharge toggle confirmation read failed: " + e.getMessage());
            }
            if (attempt < SMART_CHARGE_CONFIRM_ATTEMPTS - 1) {
                try {
                    Thread.sleep(SMART_CHARGE_CONFIRM_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("smartCharge toggle cancelled", e);
                }
            }
        }
        logger.warn("smartCharge toggle was acknowledged but homePage did not confirm enabled=" + enable);
        return false;
    }

    /**
     * Start charging now. The cloud's status="0" counterpart is not exposed:
     * it can report success while leaving the vehicle charging.
     */
    public boolean startChargingNow(String vin) throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("vin", vin);
            inner.put("timeZone", "");
            inner.put("status", "1");
        } catch (Exception e) {
            throw new IOException("Failed to build immediate-charge request", e);
        }
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/changeChargeStatue", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            logger.warn("Immediate charge start failed: code=" + code + " message=" + msg);
            if (CLOUD_CODE_VEHICLE_UNREACHABLE.equals(code)) {
                throw new SmartChargeVehicleUnreachableException("immediate charge start", msg);
            }
            return false;
        }
        String requestSerial = extractRequestSerial(response, env.contentKey);
        if (requestSerial == null) {
            logger.warn("Immediate charge start accepted without requestSerial; cannot confirm completion");
            return false;
        }
        boolean confirmed = pollSmartChargeResult(vin, requestSerial,
                SMART_CHARGE_CONFIRM_ATTEMPTS, SMART_CHARGE_CONFIRM_SLEEP_MS);
        if (confirmed) {
            try {
                SmartChargeCache.updateFromCloud(vin, fetchSmartChargingStatus(vin));
            } catch (Exception e) {
                logger.info("Immediate charge start confirmed; smart-charge refresh failed: " + e.getMessage());
            }
        }
        return confirmed;
    }

    /**
     * Best-effort decryption of `respondData` for diagnostic logging. Returns the
     * decrypted JSON as a string, or a placeholder describing why we couldn't
     * decode it. Never throws — used in failure paths where we already have an
     * error and just want extra context.
     */
    private static String decodeRespondDataSafe(JSONObject response, String contentKey) {
        try {
            String hex = response.optString("respondData", "");
            if (hex.isEmpty()) return "<empty>";
            return BydCryptoUtils.aesDecryptUtf8(hex, contentKey);
        } catch (Exception e) {
            return "<decrypt-failed:" + e.getMessage() + ">";
        }
    }

    /** Mirror an inner request for logging with VIN redacted. */
    private static JSONObject redactVin(JSONObject src) {
        try {
            JSONObject copy = new JSONObject(src.toString());
            if (copy.has("vin")) {
                String v = copy.optString("vin", "");
                if (v.length() > 4) copy.put("vin", "***" + v.substring(v.length() - 4));
            }
            return copy;
        } catch (Exception e) {
            return src;
        }
    }

    /**
     * Save the smart-charging schedule (window + repeat + on/off).
     * Endpoint: /control/smartCharge/saveOrUpdate
     *
     * <p>Wire payload mirrors pyBYD's {@code trigger_save_charging_schedule}:
     * <pre>
     *   startChargeTime: "HH:MM"
     *   endChargeTime:   "HH:MM" or sentinel "full"
     *   chargeWay:       "s" one-shot | "e" every day | "0,1,2,3,4" weekday list (Mon=0)
     *   status:          "1" enabled | "0" disabled
     *   timeZone:        "" (always empty)
     * </pre>
     *
     * <p>The save endpoint is asynchronous — a successful POST returns a
     * {@code requestSerial} that must be polled against
     * {@code /control/smartCharge/changeResult} until {@code res != 1}. This
     * helper drives the trigger leg and the polling loop, returning true only
     * once the cloud reports terminal success ({@code res == 2}).
     */
    public boolean saveChargingSchedule(String vin,
                                         String startChargeTime,
                                         String endChargeTime,
                                         String chargeWay,
                                         boolean enabled) throws IOException {
        if (vin == null || vin.isEmpty()) throw new IOException("vin required");
        if (startChargeTime == null || !startChargeTime.matches("\\d{2}:\\d{2}")) {
            throw new IOException("startChargeTime must be HH:MM, got: " + startChargeTime);
        }
        if (endChargeTime == null
                || (!endChargeTime.equals("full") && !endChargeTime.matches("\\d{2}:\\d{2}"))) {
            throw new IOException("endChargeTime must be HH:MM or 'full', got: " + endChargeTime);
        }
        if (chargeWay == null || chargeWay.isEmpty()) {
            throw new IOException("chargeWay required");
        }

        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("vin", vin);
            inner.put("startChargeTime", startChargeTime);
            inner.put("endChargeTime", endChargeTime);
            inner.put("chargeWay", chargeWay);
            inner.put("status", enabled ? "1" : "0");
            inner.put("timeZone", "");
        } catch (Exception e) {
            throw new IOException("Failed to build smartCharge save request", e);
        }
        logger.info("smartCharge save request inner=" + redactVin(inner));
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/saveOrUpdate", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("smartCharge save failed: code=" + code + " message=" + msg
                    + " respondData=" + detail + " fullResponse=" + response.toString());
            if (CLOUD_CODE_VEHICLE_UNREACHABLE.equals(code)) {
                throw new SmartChargeVehicleUnreachableException("smartCharge save", msg);
            }
            return false;
        }

        String requestSerial = extractRequestSerial(response, env.contentKey);
        if (requestSerial == null || requestSerial.isEmpty()) {
            logger.warn("smartCharge save accepted without requestSerial; cannot confirm completion");
            return false;
        }
        boolean confirmed = pollSmartChargeResult(vin, requestSerial,
                SMART_CHARGE_CONFIRM_ATTEMPTS, SMART_CHARGE_CONFIRM_SLEEP_MS);
        if (!confirmed) return false;
        SmartChargeCache.setSchedule(vin, startChargeTime, endChargeTime, chargeWay, enabled);
        try {
            JSONObject homePage = fetchSmartChargingStatus(vin);
            if (confirmsSavedSchedule(homePage, startChargeTime, endChargeTime, chargeWay, enabled)) {
                SmartChargeCache.updateFromCloud(vin, homePage);
            } else {
                logger.info("smartCharge save confirmed; homePage has not caught up, preserving confirmed cache");
            }
        } catch (Exception e) {
            logger.info("smartCharge save confirmed; homePage refresh failed: " + e.getMessage());
        }
        return true;
    }

    /** Bounded terminal confirmation for saveOrUpdate and immediate start. */
    private static final int SMART_CHARGE_CONFIRM_ATTEMPTS = 6;
    private static final long SMART_CHARGE_CONFIRM_SLEEP_MS = 2_000L;

    /** True only once homePage reflects the complete confirmed simple schedule. */
    static boolean confirmsSavedSchedule(JSONObject homePage, String start, String end,
                                         String chargeWay, boolean enabled) {
        JSONObject schedule = new JSONObject();
        try {
            schedule.put("startChargeTime", start);
            schedule.put("endChargeTime", end);
            schedule.put("chargeWay", chargeWay);
            schedule.put("enabled", enabled);
        } catch (Exception ignored) {
            return false;
        }
        return SmartChargeCache.homePageMatchesSchedule(homePage, schedule);
    }

    /**
     * Poll /control/smartCharge/changeResult until res != 1 (terminal).
     * Per pyBYD: res == 2 is success; any other terminal int is failure.
     */
    private boolean pollSmartChargeResult(String vin, String requestSerial,
                                          int attempts, long sleepMs) throws IOException {
        BydCloudSession s = ensureSession();
        // The BYD app typically resolves changeResult within ~5–10s. Callers pass
        // a bounded attempts × sleep budget; for the save path this is kept well
        // under the router's cloud timeout since the result is already accepted.
        for (int attempt = 0; attempt < attempts; attempt++) {
            long nowMs = System.currentTimeMillis();
            JSONObject inner = buildInner(nowMs);
            try {
                inner.put("vin", vin);
                inner.put("requestSerial", requestSerial);
            } catch (Exception e) {
                throw new IOException("Failed to build changeResult request", e);
            }
            TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
            JSONObject response = transport.postSecure("/control/smartCharge/changeResult", env.outer);
            String code = response.optString("code", "");
            if (!"0".equals(code)) {
                String msg = response.optString("message", "");
                String detail = decodeRespondDataSafe(response, env.contentKey);
                logger.warn("smartCharge changeResult failed: attempt=" + attempt
                        + " code=" + code + " message=" + msg + " respondData=" + detail);
                return false;
            }
            JSONObject decoded = decodeRespondData(response, env.contentKey);
            if (decoded == null) {
                logger.info("smartCharge changeResult: empty respondData attempt=" + attempt);
            } else {
                int res = decoded.optInt("res", -1);
                if (res != 1) {
                    boolean success = (res == 2);
                    logger.info("smartCharge changeResult terminal: res=" + res
                            + " success=" + success + " attempts=" + (attempt + 1));
                    return success;
                }
            }
            try { Thread.sleep(sleepMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        logger.warn("smartCharge changeResult: timed out after " + attempts
                + " polls (requestSerial=" + requestSerial + ")");
        return false;
    }

    /** Decrypt requestSerial from a saveOrUpdate / changeChargeStatue response. */
    private static String extractRequestSerial(JSONObject response, String contentKey) {
        JSONObject decoded = decodeRespondData(response, contentKey);
        if (decoded == null) return null;
        String rs = decoded.optString("requestSerial", "");
        return rs.isEmpty() ? null : rs;
    }

    /** Best-effort respondData decode → JSON. Returns null on failure. */
    private static JSONObject decodeRespondData(JSONObject response, String contentKey) {
        try {
            String hex = response.optString("respondData", "");
            if (hex.isEmpty()) return null;
            return BydCloudTransport.decryptRespondData(hex, contentKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetch the smart-charging home page (charging-state telemetry).
     * Endpoint: /control/smartCharge/homePage
     *
     * <p>The page also carries smartChargeDto and smartJourneyDto when the
     * account supports scheduled charging.
     */
    public JSONObject fetchSmartChargingStatus(String vin) throws IOException {
        long requestOrder = nextSmartChargeRequestOrder();
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();
        JSONObject inner = buildInner(nowMs);
        try { inner.put("vin", vin); }
        catch (Exception e) { throw new IOException("Failed to build smartCharge fetch request", e); }
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/control/smartCharge/homePage", env.outer);
        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("smartCharge fetch failed: code=" + code + " message=" + msg
                    + " respondData=" + detail);
            if (CLOUD_CODE_ENDPOINT_NOT_SUPPORTED.equals(code)) {
                SmartChargeCache.invalidate(vin, requestOrder);
                throw new SmartChargeNotSupportedException(
                        "smartCharge homePage is unsupported for this account");
            }
            throw new IOException("smartCharge fetch failed: code=" + code + " " + msg);
        }
        String respondData = response.optString("respondData", "");
        if (respondData.isEmpty()) return tagSmartChargeResponse(new JSONObject(), requestOrder);
        JSONObject decoded = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
        // Log the keys (not values — could contain SoC/PII) so we can confirm
        // what fields BYD's homePage actually exposes for this account.
        try {
            java.util.Iterator<String> it = decoded.keys();
            StringBuilder keys = new StringBuilder();
            while (it.hasNext()) {
                if (keys.length() > 0) keys.append(",");
                keys.append(it.next());
            }
            logger.info("smartCharge homePage keys=[" + keys + "]");
        } catch (Exception ignored) {}
        return tagSmartChargeResponse(decoded, requestOrder);
    }

    private static long nextSmartChargeRequestOrder() {
        while (true) {
            long previous = smartChargeRequestOrder.get();
            long next = Math.max(System.currentTimeMillis(), previous + 1L);
            if (smartChargeRequestOrder.compareAndSet(previous, next)) {
                return next;
            }
        }
    }

    private static JSONObject tagSmartChargeResponse(JSONObject homePage, long requestOrder) {
        try {
            homePage.put(SMART_CHARGE_REQUEST_ORDER_KEY, requestOrder);
        } catch (Exception ignored) {
            // A cache-order hint must never invalidate an otherwise usable
            // cloud response.
        }
        return homePage;
    }

    /**
     * Result struct for the router-aware overload of executeRemoteCommand.
     * Surfaces the BYD response code so the router can distinguish failure
     * modes (rate-limit 6024, auth, generic failure) from a hard exception.
     */
    public static final class CloudCommandResult {
        public final boolean success;
        public final String code;       // BYD response code, "0" on success
        public final String message;    // BYD response message
        public CloudCommandResult(boolean success, String code, String message) {
            this.success = success;
            this.code = code != null ? code : "";
            this.message = message != null ? message : "";
        }
    }

    /**
     * Router-facing variant that exposes the BYD response code for failure
     * classification (e.g., 6024 = "previous command in progress" → caller
     * should NOT fall back to SDK).
     */
    public CloudCommandResult executeRemoteCommandWithCode(String vin, String commandType,
                                                           JSONObject extraParams,
                                                           boolean waitForResult) throws IOException {
        return executeRemoteCommand(vin, commandType, extraParams, waitForResult);
    }

    /** Optional authority belongs only to the manual request that supplied it. */
    public CloudCommandResult executeRemoteCommandWithCode(String vin, String commandType,
                                                           JSONObject extraParams,
                                                           boolean waitForResult,
                                                           BooleanSupplier authority) throws IOException {
        return executeRemoteCommand(vin, commandType, extraParams, waitForResult, authority);
    }

    private boolean executeRemoteCommand(String vin, String commandType, boolean waitForResult) throws IOException {
        return executeRemoteCommand(vin, commandType, null, waitForResult).success;
    }

    private CloudCommandResult executeRemoteCommand(String vin, String commandType,
                                                    JSONObject extraParams,
                                                    boolean waitForResult) throws IOException {
        return executeRemoteCommand(vin, commandType, extraParams, waitForResult, null);
    }

    private CloudCommandResult executeRemoteCommand(String vin, String commandType,
                                                    JSONObject extraParams,
                                                    boolean waitForResult,
                                                    BooleanSupplier authority) throws IOException {
        BydCloudTransport.requireCurrentAuthority(authority);
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("remote command cancelled");
        }
        if (!commandsVerified) {
            throw new IOException("Control PIN not verified. Call verifyControlPassword() first.");
        }

        BydCloudSession s = ensureSession();
        BydCloudTransport.requireCurrentAuthority(authority);
        long nowMs = System.currentTimeMillis();

        // Build remote control request
        JSONObject inner = new JSONObject();
        try {
            inner.put("commandPwd", config.commandPwd);
            inner.put("commandType", commandType);
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("networkType", "wifi");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            inner.put("vin", vin);
            // BYD remote-control parameters are a JSON-encoded map. Sending
            // them as top-level fields makes OPENAIR, BATTERYHEAT and seat
            // commands look accepted while the vehicle ignores the payload.
            if (extraParams != null) inner.put("controlParamsMap", extraParams.toString());
        } catch (Exception e) {
            throw new IOException("Failed to build command request", e);
        }

        String userType = userTypeFor(s, vin);
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner, userType);
        String guardKey = drivingSafetyGuardForRemoteCommand(commandType);
        if (guardKey != null) {
            boolean blocked;
            try {
                blocked = DrivingSafetyGuard.isActionBlocked(guardKey);
            } catch (Throwable unavailable) {
                blocked = true;
            }
            if (blocked) {
                logger.warn("Blocked cloud command " + commandType
                        + " at transport boundary");
                return new CloudCommandResult(
                        false, DRIVING_SAFETY_BLOCKED_CODE, "blocked while driving");
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("remote command cancelled");
        }
        JSONObject response = transport.postSecure(
                "/control/remoteControl", env.outer, 0L, authority);
        if (needsOtherUserType("/control/remoteControl", s, vin, userType, response)
                && isRemoteCommandBlocked(guardKey)) {
            logger.warn("Blocked cloud command " + commandType
                    + " at transport boundary");
            return new CloudCommandResult(
                    false, DRIVING_SAFETY_BLOCKED_CODE, "blocked while driving");
        }
        response = withUserTypeCorrected("/control/remoteControl", s, vin, inner, userType,
                response, 0L, authority);
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("remote command cancelled");
        }

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            String msg = response.optString("message", "");
            String detail = decodeRespondDataSafe(response, env.contentKey);
            logger.warn("Remote command " + commandType + " failed: code=" + code
                    + " message=" + msg + " respondData=" + detail);
            return new CloudCommandResult(false, code, msg);
        }

        // Trigger responses are occasionally already terminal. A waited
        // command is successful only after a terminal success result.
        String respondData = response.optString("respondData", "");
        String requestSerial = null;
        JSONObject triggerResult = null;
        if (!respondData.isEmpty()) {
            try {
                triggerResult = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
                requestSerial = triggerResult.optString("requestSerial", null);
            } catch (Exception e) {
                logger.debug("Could not parse remoteControl respondData: " + e.getMessage());
            }
        }

        if (waitForResult) {
            int triggerState = remoteControlTerminalState(triggerResult);
            if (triggerState != 0) {
                return new CloudCommandResult(triggerState == 1, "0", "");
            }
            if (requestSerial == null || requestSerial.isEmpty()) {
                logger.warn("Remote command " + commandType
                        + " did not provide a terminal result or requestSerial");
                return new CloudCommandResult(false, "0", "missing requestSerial");
            }
            boolean ok = pollRemoteControlResult(vin, requestSerial, commandType, s);
            return new CloudCommandResult(ok, "0", "");
        }

        logger.info("Remote command " + commandType + " dispatched (fire-and-forget)");
        return new CloudCommandResult(true, "0", "");
    }

    private static boolean isRemoteCommandBlocked(String guardKey) {
        if (guardKey == null) return false;
        try {
            return DrivingSafetyGuard.isActionBlocked(guardKey);
        } catch (Throwable unavailable) {
            return true;
        }
    }

    private static String drivingSafetyGuardForRemoteCommand(String commandType) {
        if ("LOCKDOOR".equals(commandType) || "OPENDOOR".equals(commandType)) {
            return DrivingSafetyGuard.GUARD_DOOR_LOCKS;
        }
        if ("OPENTRUNK".equals(commandType) || "CLOSETRUNK".equals(commandType)) {
            return DrivingSafetyGuard.GUARD_TRUNK;
        }
        return null;
    }

    /** 0=pending/unknown, 1=success, 2=failure. Supports both BYD result shapes. */
    private static int remoteControlTerminalState(JSONObject result) {
        if (result == null) return 0;
        if (result.has("controlState")) {
            int controlState = result.optInt("controlState", 0);
            if (controlState == 1) return 1;
            if (controlState == 2) return 2;
            return 0;
        }
        if (result.has("res")) {
            int res = result.optInt("res", 1);
            if (res == 1) return 0;
            return res == 2 ? 1 : 2;
        }
        return 0;
    }

    private boolean pollRemoteControlResult(String vin, String requestSerial,
                                            String commandType, BydCloudSession s) throws IOException {
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            long nowMs = System.currentTimeMillis();
            JSONObject inner = new JSONObject();
            try {
                // CRITICAL: The result poll must mirror the trigger request structure.
                // Per pyBYD reference (jkaberg/pyBYD _api/control.py), the poll uses
                // the same _build_control_inner as the trigger — including commandPwd
                // and commandType. Without these, the BYD cloud returns 1009.
                inner.put("commandPwd", config.commandPwd);
                inner.put("commandType", commandType);
                inner.put("deviceType", "0");
                inner.put("imeiMD5", config.imeiMd5);
                inner.put("networkType", "wifi");
                inner.put("random", BydCryptoUtils.randomHex16());
                inner.put("requestSerial", requestSerial);
                inner.put("timeStamp", String.valueOf(nowMs));
                inner.put("version", config.appInnerVersion);
                inner.put("vin", vin);
            } catch (Exception e) {
                continue;
            }

            String userType = userTypeFor(s, vin);
            TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner, userType);
            try {
                JSONObject response = transport.postSecure("/control/remoteControlResult", env.outer);
                response = withUserTypeCorrected("/control/remoteControlResult", s, vin, inner,
                        userType, response, 0L, null);
                String code = response.optString("code", "");
                
                if (!"0".equals(code)) continue;

                String rd = response.optString("respondData", "");
                if (rd.isEmpty()) continue;

                JSONObject result = BydCloudTransport.decryptRespondData(rd, env.contentKey);
                int terminalState = remoteControlTerminalState(result);
                if (terminalState == 1) {
                    logger.info("Remote command succeeded (attempt " + attempt + ")");
                    return true;
                } else if (terminalState == 2) {
                    logger.warn("Remote command failed with terminal result");
                    return false;
                }
                // Pending/unknown → continue polling.
            } catch (Exception e) {
                logger.debug("Poll attempt " + attempt + " failed: " + e.getMessage());
            }
        }

        logger.warn("Remote command polling timed out without terminal success");
        return false;
    }

    /** Cancel a timed-out router worker's active transport call, if any. */
    public void cancelRequestForThread(Thread worker) {
        if (transport != null) transport.cancelCallForThread(worker);
    }

    // ── Vehicle Realtime Data ──────────────────────────────────────────

    /**
     * Fetch vehicle realtime data via request/poll pattern.
     * Wakes the T-Box and polls until data is ready (up to 10 attempts, 1.5s apart).
     */
    public JSONObject fetchVehicleRealtime(String vin) throws IOException {
        return fetchVehicleRealtime(vin, 10, 0L);
    }

    /**
     * Bounded realtime request used only by the parked DiLink 5 heartbeat.
     *
     * <p>One result poll is sufficient for the normal ~2s ready path seen in
     * the field. Capping each HTTP leg at six seconds keeps the trigger plus
     * one poll inside the 15-second heartbeat budget even on a degraded route.
     * The regular UI/poller method above retains its full ten-poll behaviour.
     */
    public JSONObject fetchVehicleRealtimeForParkedKeepAlive(String vin)
            throws IOException {
        return fetchVehicleRealtime(vin, 1, 6_000L);
    }

    private JSONObject fetchVehicleRealtime(
            String vin,
            int maxPollAttempts,
            long callTimeoutMs) throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = buildInner(nowMs);
        try {
            inner.put("energyType", "0");
            inner.put("tboxVersion", "3");
            inner.put("vin", vin);
        } catch (Exception e) {
            throw new IOException("Failed to build realtime request", e);
        }

        String userType = userTypeFor(s, vin);
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner, userType);
        JSONObject response = postRealtimeSecure(
                "/vehicleInfo/vehicle/vehicleRealTimeRequest",
                env.outer,
                callTimeoutMs);
        response = withUserTypeCorrected("/vehicleInfo/vehicle/vehicleRealTimeRequest", s, vin,
                inner, userType, response, callTimeoutMs, null);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Realtime request failed: code=" + code);
        }

        String respondData = response.optString("respondData", "");
        JSONObject vehicleInfo = null;
        String requestSerial = null;

        if (!respondData.isEmpty()) {
            JSONObject decoded = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
            requestSerial = decoded.optString("requestSerial", null);
            if (isRealtimeReady(decoded)) return decoded;
            vehicleInfo = decoded;
        }

        if (requestSerial == null || requestSerial.isEmpty()) return vehicleInfo;

        for (int attempt = 1; attempt <= maxPollAttempts; attempt++) {
            try { Thread.sleep(1500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return vehicleInfo;
            }

            nowMs = System.currentTimeMillis();
            JSONObject pollInner = buildInner(nowMs);
            try {
                pollInner.put("energyType", "0");
                pollInner.put("tboxVersion", "3");
                pollInner.put("vin", vin);
                pollInner.put("requestSerial", requestSerial);
            } catch (Exception e) { continue; }

            String pollUserType = userTypeFor(s, vin);
            TokenEnvelope pollEnv = buildTokenOuterEnvelope(nowMs, s, pollInner, pollUserType);
            try {
                JSONObject pollResp = postRealtimeSecure(
                        "/vehicleInfo/vehicle/vehicleRealTimeResult",
                        pollEnv.outer,
                        callTimeoutMs);
                pollResp = withUserTypeCorrected("/vehicleInfo/vehicle/vehicleRealTimeResult", s,
                        vin, pollInner, pollUserType, pollResp, callTimeoutMs, null);
                if (!"0".equals(pollResp.optString("code", ""))) continue;

                String pollData = pollResp.optString("respondData", "");
                if (pollData.isEmpty()) continue;

                JSONObject decoded = BydCloudTransport.decryptRespondData(pollData, pollEnv.contentKey);
                String newSerial = decoded.optString("requestSerial", null);
                if (newSerial != null && !newSerial.isEmpty()) requestSerial = newSerial;

                if (isRealtimeReady(decoded)) {
                    logger.info("Realtime data ready (attempt " + attempt + ")");
                    return decoded;
                }
                vehicleInfo = decoded;
            } catch (Exception e) {
                logger.debug("Realtime poll " + attempt + " failed: " + e.getMessage());
            }
        }

        return vehicleInfo;
    }

    private JSONObject postRealtimeSecure(
            String endpoint,
            JSONObject outer,
            long callTimeoutMs) throws IOException {
        return callTimeoutMs > 0L
                ? transport.postSecure(endpoint, outer, callTimeoutMs)
                : transport.postSecure(endpoint, outer);
    }

    private boolean isRealtimeReady(JSONObject vi) {
        if (vi == null) return false;
        if (vi.optInt("onlineState", -1) == 2) return false;
        // Lock fields are the highest-value signal for our use case — only
        // consider the response "ready" once at least one lock field is
        // populated.  Without this, an early response with telemetry but
        // no lock state would short-circuit the poll loop and we'd miss
        // the lock data that arrives ~1.5 s later.
        int lfLock = vi.optInt("leftFrontDoorLock", -1);
        int rfLock = vi.optInt("rightFrontDoorLock", -1);
        int lrLock = vi.optInt("leftRearDoorLock", -1);
        int rrLock = vi.optInt("rightRearDoorLock", -1);
        boolean hasLock = lfLock > 0 || rfLock > 0 || lrLock > 0 || rrLock > 0;
        if (!hasLock) return false;
        // Plus a sanity check that telemetry has also landed.
        if (vi.optDouble("leftFrontTirepressure", 0) > 0) return true;
        if (vi.optDouble("rightFrontTirepressure", 0) > 0) return true;
        if (vi.optLong("time", 0) > 0) return true;
        if (vi.optDouble("enduranceMileage", 0) > 0) return true;
        return false;
    }

    // ── Request Builders ────────────────────────────────────────────────

    /**
     * Fetch the EMQ MQTT broker hostname for real-time push subscription.
     */
    public String fetchEmqBrokerHost() throws IOException {
        BydCloudSession s = ensureSession();
        long nowMs = System.currentTimeMillis();

        JSONObject inner = buildInner(nowMs);
        TokenEnvelope env = buildTokenOuterEnvelope(nowMs, s, inner);
        JSONObject response = transport.postSecure("/app/emqAuth/getEmqBrokerIp", env.outer);

        String code = response.optString("code", "");
        if (!"0".equals(code)) {
            throw new IOException("Broker lookup failed: code=" + code
                    + " message=" + response.optString("message", ""));
        }

        String respondData = response.optString("respondData", "");
        if (respondData.isEmpty()) {
            throw new IOException("Broker lookup: empty respondData");
        }

        JSONObject decoded = BydCloudTransport.decryptRespondData(respondData, env.contentKey);
        String broker;
        if (config.isChinaRegion()) {
            // CN: broker field is brand-specific (targetBrand 1..5 → dynasty/ocean/
            // denza/yangwang/fangchengbao). We ship targetBrand=1 (dynasty).
            broker = decoded.optString(cnBrokerField(), "");
        } else {
            // BYD API has a typo: "emqBorker" (sic) — check both spellings
            broker = decoded.optString("emqBorker", "");
            if (broker.isEmpty()) broker = decoded.optString("emqBroker", "");
        }
        if (broker.isEmpty()) {
            throw new IOException("Broker lookup response missing broker hostname");
        }

        logger.info("EMQ broker resolved: " + broker);
        return broker;
    }

    /** CN EMQ broker response field for the configured targetBrand. */
    private static String cnBrokerField() {
        switch (BydCloudConfig.CN_TARGET_BRAND) {
            case "2": return "oceanEmqBroker";
            case "3": return "denzaEmqBroker";
            case "4": return "yangwangEmqBroker";
            case "5": return "fangchengbaoEmqBroker";
            case "1":
            default:  return "dynastyEmqBroker";
        }
    }

    /**
     * Build MQTT credentials for connecting to BYD's EMQ broker.
     * Returns [clientId, username, password].
     *
     * CN uses the "dynasty" client-id prefix + topic root and the effective API
     * identifier (superId preferred); overseas keeps "oversea" + userId. The
     * password derivation (ts + MD5(signToken+clientId+uid+ts)) is identical.
     */
    public String[] buildMqttCredentials() throws IOException {
        BydCloudSession s = ensureSession();
        boolean cn = config.isChinaRegion();
        String prefix = cn ? "dynasty" : "oversea";
        String uid = cn ? s.effectiveApiIdentifier() : s.userId;
        String clientId = prefix + "_" + config.imeiMd5.toUpperCase();
        long tsSeconds = System.currentTimeMillis() / 1000;
        String passwordBase = s.signToken + clientId + uid + tsSeconds;
        String password = tsSeconds + com.overdrive.app.byd.cloud.crypto.BydCryptoUtils.md5Hex(passwordBase);
        return new String[]{clientId, uid, password};
    }

    /**
     * Get the MQTT topic for vehicle push messages.
     */
    public String getMqttTopic() throws IOException {
        BydCloudSession s = ensureSession();
        if (config.isChinaRegion()) {
            return "dynasty/res/" + s.effectiveApiIdentifier();
        }
        return "oversea/res/" + s.userId;
    }

    /**
     * Get the content key for decrypting MQTT messages.
     */
    public String getMqttDecryptKey() throws IOException {
        BydCloudSession s = ensureSession();
        return s.contentKey();
    }

    /**
     * Get the current session (for reconnection credential rebuilding).
     */
    public BydCloudSession getSession() {
        return session;
    }

    private JSONObject buildLoginRequest(long nowMs) {
        try {
            String random = BydCryptoUtils.randomHex16();
            String reqTimestamp = String.valueOf(nowMs);

            // Inner payload (device info)
            JSONObject inner = new JSONObject();
            inner.put("appInnerVersion", config.appInnerVersion);
            inner.put("appVersion", config.appVersion);
            inner.put("deviceName", "XIAOMIPOCO F1");
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("isAuto", "1");
            inner.put("mobileBrand", "XIAOMI");
            inner.put("mobileModel", "POCO F1");
            inner.put("networkType", "wifi");
            inner.put("osType", "15");
            inner.put("osVersion", "35");
            inner.put("random", random);
            inner.put("softType", "0");
            inner.put("timeStamp", reqTimestamp);
            inner.put("timeZone", "Asia/Kolkata");

            String encryData = BydCryptoUtils.aesEncryptHex(
                    inner.toString(), config.loginKey);

            // Sign fields = inner fields + outer context
            JSONObject signFields = new JSONObject(inner.toString());
            signFields.put("countryCode", config.countryCode);
            signFields.put("functionType", "pwdLogin");
            signFields.put("identifier", config.username);
            signFields.put("identifierType", "0");
            signFields.put("language", config.language);
            signFields.put("reqTimestamp", reqTimestamp);

            String sign = BydCryptoUtils.sha1Mixed(
                    BydCryptoUtils.buildSignString(signFields, config.signPassword));

            // Outer payload
            JSONObject outer = new JSONObject();
            outer.put("countryCode", config.countryCode);
            outer.put("encryData", encryData);
            outer.put("functionType", "pwdLogin");
            outer.put("identifier", config.username);
            outer.put("identifierType", "0");
            outer.put("imeiMD5", config.imeiMd5);
            outer.put("isAuto", "1");
            outer.put("language", config.language);
            outer.put("reqTimestamp", reqTimestamp);
            outer.put("sign", sign);
            outer.put("signKey", config.rawPassword);
            // Common device fields
            outer.put("ostype", "and");
            outer.put("imei", "BANGCLE01234");
            outer.put("mac", "00:00:00:00:00:00");
            outer.put("model", "POCO F1");
            outer.put("sdk", "35");
            outer.put("mod", "Xiaomi");
            outer.put("serviceTime", String.valueOf(System.currentTimeMillis()));

            outer.put("checkcode", BydCryptoUtils.computeCheckcode(outer));

            return outer;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build login request", e);
        }
    }

    /**
     * Build the CN login outer payload (/app/auth/login).
     *
     * Differences from overseas {@link #buildLoginRequest}:
     *   - inner carries CN device fields (networkOperator 无, configVersion, etc.)
     *   - sign fields use appChannel/targetBrand/loginType instead of
     *     countryCode/language/functionType
     *   - checkcode is SHA-256 (computeCnCheckcode), not MD5+reorder
     *   - no signKey/rawPassword field on the wire
     *
     * Inner AES key and sign password are the SAME derivations as overseas
     * (loginKey = MD5(MD5(pw)), signPassword = MD5(pw)) — so stored credentials
     * need no CN-specific handling.
     *
     * Port of: pyBYD _api/login_cn.build_cn_login_request.
     */
    private JSONObject buildCnLoginRequest(long nowMs) {
        try {
            String random = BydCryptoUtils.randomHex16();
            String reqTimestamp = String.valueOf(nowMs);

            JSONObject inner = new JSONObject();
            inner.put("appInnerVersion", config.appInnerVersion);
            inner.put("appVersion", config.appVersion);
            inner.put("bluetoothMac", "");
            inner.put("city", "");
            inner.put("configVersion", "10000");
            inner.put("deviceType", "0");
            inner.put("devicename", "XIAOMIPOCO F1");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("isAuto", "0");
            inner.put("latitude", "");
            inner.put("longitude", "");
            inner.put("mobileBrand", "XIAOMI");
            inner.put("mobileModel", "POCO F1");
            inner.put("networkOperator", BydCloudConfig.CN_NETWORK_OPERATOR);
            inner.put("networkType", "wifi");
            inner.put("osType", "Android");
            inner.put("osVersion", "35");
            inner.put("random", random);
            inner.put("softType", "0");
            inner.put("timeStamp", reqTimestamp);

            String encryData = BydCryptoUtils.aesEncryptHex(inner.toString(), config.loginKey);

            // Sign fields = inner + CN outer context. loginType is an int (0).
            JSONObject signFields = new JSONObject(inner.toString());
            signFields.put("appChannel", BydCloudConfig.CN_APP_CHANNEL);
            signFields.put("identifier", config.username);
            signFields.put("loginType", 0);
            signFields.put("reqTimestamp", reqTimestamp);
            signFields.put("targetBrand", BydCloudConfig.CN_TARGET_BRAND);

            String sign = BydCryptoUtils.sha1Mixed(
                    BydCryptoUtils.buildCnSignString(signFields, config.signPassword));

            JSONObject outer = new JSONObject();
            outer.put("appChannel", BydCloudConfig.CN_APP_CHANNEL);
            outer.put("encryData", encryData);
            outer.put("identifier", config.username);
            outer.put("imeiMD5", config.imeiMd5);
            outer.put("isAuto", "0");
            outer.put("loginType", 0);
            outer.put("reqTimestamp", reqTimestamp);
            outer.put("sign", sign);
            outer.put("targetBrand", BydCloudConfig.CN_TARGET_BRAND);
            // Common device fields
            outer.put("ostype", "and");
            outer.put("imei", "BANGCLE01234");
            outer.put("mac", "00:00:00:00:00:00");
            outer.put("model", "POCO F1");
            outer.put("sdk", "35");
            outer.put("mod", "Xiaomi");
            outer.put("serviceTime", String.valueOf(System.currentTimeMillis()));

            outer.put("checkcode", BydCryptoUtils.computeCnCheckcode(outer));

            return outer;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build CN login request", e);
        }
    }

    private JSONObject buildInner(long nowMs) {
        if (config.isChinaRegion()) {
            return buildCnInnerBase(nowMs);
        }
        try {
            JSONObject inner = new JSONObject();
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("networkType", "wifi");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            return inner;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build inner payload", e);
        }
    }

    /**
     * CN common inner fields for post-login requests
     * (cn_envelope.build_cn_inner_base). Richer than the overseas inner: it
     * carries device identity + networkOperator + version=cn_app_inner_version.
     */
    private JSONObject buildCnInnerBase(long nowMs) {
        try {
            JSONObject inner = new JSONObject();
            inner.put("deviceName", "XIAOMIPOCO F1");
            inner.put("deviceType", "0");
            inner.put("imeiMD5", config.imeiMd5);
            inner.put("mobileBrand", "XIAOMI");
            inner.put("mobileModel", "POCO F1");
            inner.put("networkOperator", BydCloudConfig.CN_NETWORK_OPERATOR);
            inner.put("networkType", "wifi");
            inner.put("osType", "Android");
            inner.put("osVersion", "35");
            inner.put("random", BydCryptoUtils.randomHex16());
            inner.put("softType", "0");
            inner.put("timeStamp", String.valueOf(nowMs));
            inner.put("version", config.appInnerVersion);
            return inner;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build CN inner payload", e);
        }
    }

    /** Envelope result: outer payload + content key for decrypting respondData. */
    private static final class TokenEnvelope {
        final JSONObject outer;
        final String contentKey;
        TokenEnvelope(JSONObject outer, String contentKey) {
            this.outer = outer;
            this.contentKey = contentKey;
        }
    }

    private TokenEnvelope buildTokenOuterEnvelope(long nowMs, BydCloudSession s, JSONObject inner) {
        return buildTokenOuterEnvelope(nowMs, s, inner, null);
    }

    /** A non-null overseas {@code userType} is sent and signed; the cloud answers 1007 to an unsigned one. */
    private TokenEnvelope buildTokenOuterEnvelope(long nowMs, BydCloudSession s, JSONObject inner,
                                                  String userType) {
        if (config.isChinaRegion()) {
            return buildCnTokenOuterEnvelope(nowMs, s, inner);
        }
        try {
            String reqTimestamp = String.valueOf(nowMs);
            String contentKey = s.contentKey();
            String signKey = s.signKey();

            String encryData = BydCryptoUtils.aesEncryptHex(inner.toString(), contentKey);

            // Build sign fields: inner + outer context
            JSONObject signFields = new JSONObject(inner.toString());
            signFields.put("countryCode", config.countryCode);
            signFields.put("identifier", s.userId);
            signFields.put("imeiMD5", config.imeiMd5);
            signFields.put("language", config.language);
            signFields.put("reqTimestamp", reqTimestamp);
            if (userType != null) signFields.put("userType", userType);

            String sign = BydCryptoUtils.sha1Mixed(
                    BydCryptoUtils.buildSignString(signFields, signKey));

            JSONObject outer = new JSONObject();
            outer.put("countryCode", config.countryCode);
            outer.put("encryData", encryData);
            outer.put("identifier", s.userId);
            outer.put("imeiMD5", config.imeiMd5);
            outer.put("language", config.language);
            outer.put("reqTimestamp", reqTimestamp);
            outer.put("sign", sign);
            // Common device fields
            outer.put("ostype", "and");
            outer.put("imei", "BANGCLE01234");
            outer.put("mac", "00:00:00:00:00:00");
            outer.put("model", "POCO F1");
            outer.put("sdk", "35");
            outer.put("mod", "Xiaomi");
            outer.put("serviceTime", String.valueOf(System.currentTimeMillis()));
            if (userType != null) outer.put("userType", userType);

            outer.put("checkcode", BydCryptoUtils.computeCheckcode(outer));

            return new TokenEnvelope(outer, contentKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build token envelope", e);
        }
    }

    /**
     * Build the CN signed outer envelope for post-login token requests.
     *
     * Differences from overseas {@link #buildTokenOuterEnvelope}:
     *   - identifier = effective API id (superId preferred, else userId)
     *   - adds identifierType (0 with vin, else 2), appChannel, targetBrand,
     *     vehicleBrand, objective (=vin or null), and explicit null fields
     *     (outModelTypes/softType/version) that the CN server expects present
     *   - sign uses the CN field set + CN sign string (null -> "null")
     *   - checkcode is SHA-256
     *
     * contentKey/signKey derivation is identical to overseas
     * (MD5(encryToken)/MD5(signToken)).
     *
     * Port of: pyBYD _api/cn_envelope.build_cn_token_outer_envelope.
     */
    private TokenEnvelope buildCnTokenOuterEnvelope(long nowMs, BydCloudSession s, JSONObject inner) {
        try {
            String reqTimestamp = String.valueOf(nowMs);
            String contentKey = s.contentKey();
            String signKey = s.signKey();
            String apiId = s.effectiveApiIdentifier();

            String encryData = BydCryptoUtils.aesEncryptHex(inner.toString(), contentKey);

            String vin = inner.optString("vin", "");
            boolean hasVin = !vin.isEmpty();
            int idType = hasVin ? 0 : 2;

            // Sign fields: inner + CN outer context.
            JSONObject signFields = new JSONObject(inner.toString());
            signFields.put("appChannel", BydCloudConfig.CN_APP_CHANNEL);
            signFields.put("identifier", apiId);
            signFields.put("identifierType", idType);
            signFields.put("imeiMD5", config.imeiMd5);
            signFields.put("reqTimestamp", reqTimestamp);
            signFields.put("targetBrand", BydCloudConfig.CN_TARGET_BRAND);
            signFields.put("vehicleBrand", BydCloudConfig.CN_VEHICLE_BRAND);
            if (hasVin) {
                signFields.put("objective", vin);
            }

            String sign = BydCryptoUtils.sha1Mixed(
                    BydCryptoUtils.buildCnSignString(signFields, signKey));

            JSONObject outer = new JSONObject();
            outer.put("appChannel", BydCloudConfig.CN_APP_CHANNEL);
            outer.put("encryData", encryData);
            outer.put("identifier", apiId);
            outer.put("identifierType", idType);
            outer.put("imeiMD5", config.imeiMd5);
            outer.put("objective", hasVin ? vin : JSONObject.NULL);
            outer.put("outModelTypes", JSONObject.NULL);
            outer.put("reqTimestamp", reqTimestamp);
            outer.put("sign", sign);
            outer.put("softType", JSONObject.NULL);
            outer.put("targetBrand", BydCloudConfig.CN_TARGET_BRAND);
            outer.put("vehicleBrand", BydCloudConfig.CN_VEHICLE_BRAND);
            outer.put("version", JSONObject.NULL);
            // Common device fields
            outer.put("ostype", "and");
            outer.put("imei", "BANGCLE01234");
            outer.put("mac", "00:00:00:00:00:00");
            outer.put("model", "POCO F1");
            outer.put("sdk", "35");
            outer.put("mod", "Xiaomi");
            outer.put("serviceTime", String.valueOf(System.currentTimeMillis()));

            outer.put("checkcode", BydCryptoUtils.computeCnCheckcode(outer));

            return new TokenEnvelope(outer, contentKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build CN token envelope", e);
        }
    }
}
