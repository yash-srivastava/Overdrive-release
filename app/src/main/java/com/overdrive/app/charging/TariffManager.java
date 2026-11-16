package com.overdrive.app.charging;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.SafeLocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Location-aware electricity tariffs — CRUD + the match that prices a session.
 *
 * <p><b>Why this exists.</b> Before this, charging cost came from exactly two
 * numbers: a global per-kWh rate and one optional DC rate. That is wrong the
 * moment a driver charges in more than one place — home at an off-peak rate, an
 * office wallbox that's free, a motorway DC stall at 4× home. This class lets a
 * rate be attached to a <i>place</i>, so a charge at a known site is priced with
 * that site's tariff automatically and no one has to re-type a rate per session.
 *
 * <p><b>Resolution order</b> (see {@link #resolve(double, double, int)}):
 * <ol>
 *   <li>the nearest enabled profile whose circle contains the charge location
 *       and which prices this gun type — ties broken by <i>smallest radius</i>,
 *       so a specific "Basement L2" inside a broad "Home campus" wins;</li>
 *   <li>the user-pinned default profile, when one is set (covers charging at an
 *       unmapped site on a utility-wide tariff);</li>
 *   <li>{@code null} ⇒ the caller falls back to the global rate, i.e. exactly
 *       the pre-existing behaviour. Nothing regresses for a single-location user
 *       who never creates a profile.</li>
 * </ol>
 *
 * <p><b>Storage.</b> Profiles live in the {@code chargingAnalytics.tariffs} array
 * of {@code /data/local/tmp/overdrive_config.json} via
 * {@link UnifiedConfigManager}, alongside the other charging keys, so they ride
 * the existing config backup/restore with no new file to manage.
 * {@link UnifiedConfigManager#updateSection} MERGES the keys it is given into the
 * existing section under a cross-process file lock, so each writer only sends the
 * keys it owns and siblings are preserved.
 *
 * <p><b>Thread safety.</b> {@code profiles} is a volatile reference to an
 * IMMUTABLE list, replaced by a single atomic swap on every mutation; all writers
 * are {@code synchronized} and build-then-swap. Readers (one per session edge,
 * plus one per row serialized for the UI) take one volatile read, never block on a
 * UI edit, and can never observe a partially-rebuilt list. Do NOT mutate the
 * published list in place — it is unmodifiable and would throw at runtime.
 */
public class TariffManager {

    private static final String TAG = "TariffManager";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String SECTION = "chargingAnalytics";
    private static final String KEY_TARIFFS = "tariffs";
    private static final String KEY_DEFAULT_ID = "defaultTariffId";
    private static final String KEY_PENDING_REPRICES = "pendingTariffReprices";
    private static final String KEY_PENDING_REPRICE_TOKENS =
            "pendingTariffRepriceTokens";
    private static final String REPRICE_ALL = "*";
    private static final String LEGACY_REPRICE_TOKEN = "legacy";

    /** Cap the list so a runaway UI can't bloat the config file. */
    public static final int MAX_PROFILES = 40;

    private static volatile TariffManager instance;

    /**
     * Published profile list, replaced ATOMICALLY on every mutation.
     *
     * <p>Deliberately NOT a CopyOnWriteArrayList mutated in place: {@code clear()}
     * followed by {@code addAll()} publishes a zero-length array in between, and a
     * reader on the SoC sampler thread that lands in that window sees NO tariffs
     * and prices a real charging session at the global rate instead of its own —
     * silently wrong money, with no error. Readers take one volatile read of this
     * reference and always observe either the complete old list or the complete
     * new one. Every writer builds a fresh list and assigns once.
     */
    private volatile List<TariffProfile> profiles = Collections.emptyList();
    /** Profile applied when the charge location matches nothing. Empty = none. */
    private volatile String defaultTariffId = "";
    private volatile List<String> pendingRepriceKeys = Collections.emptyList();
    private volatile Map<String, String> pendingRepriceTokens =
            Collections.emptyMap();
    private volatile boolean loaded = false;
    private volatile String lastLoadFailure = "";
    private boolean replayingPendingReprices = false;
    private TariffManager() {}

    public static TariffManager getInstance() {
        if (instance == null) {
            synchronized (TariffManager.class) {
                if (instance == null) instance = new TariffManager();
            }
        }
        return instance;
    }

    /**
     * Load profiles from config. Safe to call repeatedly (the charging config
     * POST path calls it after every edit so the daemon-side view can't drift
     * from what the UI just wrote).
     */
    public boolean load() {
        synchronized (this) {
            try {
                loadStrict();
            } catch (Exception e) {
                logger.error("Tariff load error: " + e.getMessage());
                // Keep the previous complete snapshot published, but leave the manager UNLOADED.
                // Session-closing callers use loadStrict() and will retry instead of freezing
                // a fallback/global price after a transient config read failure.
                loaded = false;
                lastLoadFailure = e.getMessage() != null
                        ? e.getMessage() : e.getClass().getSimpleName();
                return false;
            }
        }

        // Database repricing is synchronized on SocHistoryDatabase. Never carry
        // this manager's monitor into it: database metadata replay enters
        // TariffManager in the opposite direction.
        replayPendingReprices();
        return true;
    }

    /** Parse and atomically publish one verified durable config snapshot. */
    private synchronized void loadStrict() throws Exception {
        JSONObject cfg = loadVerifiedConfig();
        PublishedImage image =
                imageFromSection(cfg.optJSONObject(SECTION));
        publishImage(image);
        lastLoadFailure = "";
        logger.info("Loaded " + image.profiles.size() + " tariff profiles, default="
                + (defaultTariffId.isEmpty() ? "none" : defaultTariffId));
    }

    /** Return the exact durable root parsed under UnifiedConfigManager's stable lock. */
    public static JSONObject loadVerifiedConfig() throws Exception {
        return UnifiedConfigManager.readDurableConfigStrict();
    }

    /** Caller must hold UnifiedConfigManager's cross-process lock. */
    private static JSONObject readDurableConfigLocked() {
        return UnifiedConfigManager.readDurableConfigStrict();
    }

    private boolean ensureLoaded() {
        // load() serializes publication itself, then releases the tariff monitor
        // before replaying database work.
        if (!loaded && !load()) return false;
        return loaded;
    }

    private interface LockedTariffMutation {
        StagedMutation apply(TariffDocument document) throws Exception;
    }

    private static final class TariffDocument {
        final JSONObject root;
        final JSONObject section;
        JSONArray tariffs;

        TariffDocument(JSONObject root) {
            this.root = root;
            JSONObject existing = root.optJSONObject(SECTION);
            this.section = existing != null ? existing : new JSONObject();
            this.tariffs = tariffsArrayFromSection(section);
        }
    }

    /**
     * Normalize {@code chargingAnalytics.tariffs} for read/mutate paths.
     *
     * <p>Canonical storage is a JSON array. Early builds and hand-edited configs
     * sometimes stored a single profile as a JSON object instead. Treating that
     * as "no tariffs" and starting a fresh array on add wiped the existing profile
     * when a second location was saved — only the newest row survived.
     */
    static JSONArray tariffsArrayFromSection(JSONObject section) {
        if (section == null) return new JSONArray();
        JSONArray arr = section.optJSONArray(KEY_TARIFFS);
        if (arr != null) {
            try {
                return new JSONArray(arr.toString());
            } catch (Exception ignored) {
                return new JSONArray();
            }
        }
        JSONObject lone = section.optJSONObject(KEY_TARIFFS);
        if (lone != null) {
            JSONArray out = new JSONArray();
            out.put(lone);
            return out;
        }
        return new JSONArray();
    }

    private static final class PublishedImage {
        final List<TariffProfile> profiles;
        final String defaultTariffId;
        final List<String> pendingRepriceKeys;
        final Map<String, String> pendingRepriceTokens;

        PublishedImage(
                List<TariffProfile> profiles,
                String defaultTariffId,
                List<String> pendingRepriceKeys,
                Map<String, String> pendingRepriceTokens) {
            this.profiles = profiles;
            this.defaultTariffId = defaultTariffId;
            this.pendingRepriceKeys = pendingRepriceKeys;
            this.pendingRepriceTokens = pendingRepriceTokens;
        }
    }

    private static final class StagedMutation {
        final boolean accepted;
        final TariffProfile profile;
        final String failure;

        private StagedMutation(boolean accepted, TariffProfile profile, String failure) {
            this.accepted = accepted;
            this.profile = profile;
            this.failure = failure;
        }

        static StagedMutation accepted(TariffProfile profile) {
            return new StagedMutation(true, profile, "");
        }

        static StagedMutation rejected(String failure) {
            return new StagedMutation(false, null, failure);
        }
    }

    private static final class MutationCommit {
        final boolean committed;
        final TariffProfile profile;
        final PublishedImage image;
        final String failure;

        private MutationCommit(
                boolean committed,
                TariffProfile profile,
                PublishedImage image,
                String failure) {
            this.committed = committed;
            this.profile = profile;
            this.image = image;
            this.failure = failure;
        }

        static MutationCommit committed(TariffProfile profile, PublishedImage image) {
            return new MutationCommit(true, profile, image, "");
        }

        static MutationCommit rejected(PublishedImage image, String failure) {
            return new MutationCommit(false, null, image, failure);
        }

        static MutationCommit failed(String failure) {
            return new MutationCommit(false, null, null, failure);
        }
    }

    /**
     * Apply one operation-specific tariff patch to a fresh durable root while holding the same
     * cross-process lock for read, mutation and save. The process-local list is never a write base.
     */
    private synchronized MutationCommit commitMutation(LockedTariffMutation mutation) {
        return commitMutation(null, mutation);
    }

    private synchronized MutationCommit commitMutation(
            String repriceKey,
            LockedTariffMutation mutation) {
        MutationCommit result;
        try {
            result = UnifiedConfigManager.runUnderConfigLock(() -> {
                try {
                    TariffDocument document =
                            new TariffDocument(readDurableConfigLocked());
                    PublishedImage base = imageFromSection(document.section);
                    StagedMutation staged = mutation.apply(document);
                    if (!staged.accepted) {
                        return MutationCommit.rejected(base, staged.failure);
                    }

                    document.section.put(KEY_TARIFFS, document.tariffs);
                    normalizeDefaultId(document);
                    if (repriceKey != null) {
                        queuePendingReprice(document.section, repriceKey);
                    }
                    document.root.put(SECTION, document.section);
                    if (!UnifiedConfigManager.saveConfig(document.root)) {
                        return MutationCommit.rejected(
                                base, "config persistence rejected tariff mutation");
                    }
                    return MutationCommit.committed(
                            staged.profile, imageFromSection(document.section));
                } catch (Exception e) {
                    return MutationCommit.failed(
                            e.getMessage() != null
                                    ? e.getMessage() : e.getClass().getSimpleName());
                }
            });
        } catch (Exception e) {
            result = MutationCommit.failed(
                    e.getMessage() != null
                            ? e.getMessage() : e.getClass().getSimpleName());
        }

        if (result.image != null) {
            publishImage(result.image);
        } else {
            loaded = false;
        }
        if (result.committed) {
            lastLoadFailure = "";
        } else {
            lastLoadFailure = result.failure;
            logger.error("Tariff mutation rejected: " + result.failure);
        }
        return result;
    }

    private static PublishedImage imageFromSection(JSONObject section) {
        List<TariffProfile> parsed = new ArrayList<>();
        List<String> pending = pendingRepricesFromSection(section);
        String requestedDefault = "";
        if (section != null) {
            requestedDefault = section.optString(KEY_DEFAULT_ID, "");
            JSONArray tariffs = tariffsArrayFromSection(section);
            for (int i = 0; i < tariffs.length()
                    && parsed.size() < MAX_PROFILES; i++) {
                JSONObject profile = tariffs.optJSONObject(i);
                if (profile != null) parsed.add(new TariffProfile(profile));
            }
        }
        String publishedDefault = "";
        for (TariffProfile profile : parsed) {
            if (requestedDefault.equals(profile.getId())) {
                publishedDefault = requestedDefault;
                break;
            }
        }
        return new PublishedImage(
                Collections.unmodifiableList(parsed),
                publishedDefault,
                Collections.unmodifiableList(pending),
                Collections.unmodifiableMap(
                        pendingRepriceTokensFromSection(section)));
    }

    private void publishImage(PublishedImage image) {
        profiles = image.profiles;
        defaultTariffId = image.defaultTariffId;
        pendingRepriceKeys = image.pendingRepriceKeys;
        pendingRepriceTokens = image.pendingRepriceTokens;
        loaded = true;
    }

    private static List<String> pendingRepricesFromSection(JSONObject section) {
        List<String> pending = new ArrayList<>();
        JSONArray encoded = section != null
                ? section.optJSONArray(KEY_PENDING_REPRICES) : null;
        if (encoded == null) return pending;
        for (int i = 0; i < encoded.length(); i++) {
            String key = encoded.optString(i, "");
            if (key.isEmpty() || pending.contains(key)) continue;
            if (REPRICE_ALL.equals(key)) {
                pending.clear();
                pending.add(REPRICE_ALL);
                break;
            }
            pending.add(key);
        }
        return pending;
    }

    private static Map<String, String> pendingRepriceTokensFromSection(
            JSONObject section) {
        Map<String, String> tokens = new HashMap<>();
        JSONObject encoded = section != null
                ? section.optJSONObject(KEY_PENDING_REPRICE_TOKENS) : null;
        if (encoded != null) {
            java.util.Iterator<String> keys = encoded.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String token = encoded.optString(key, "");
                if (!key.isEmpty() && !token.isEmpty()) {
                    tokens.put(key, token);
                }
            }
        }
        for (String key : pendingRepricesFromSection(section)) {
            if (!tokens.containsKey(key)) {
                tokens.put(key, LEGACY_REPRICE_TOKEN);
            }
        }
        return tokens;
    }

    private static void queuePendingReprice(
            JSONObject section, String tariffId) throws Exception {
        String requested = normalizeRepriceKey(tariffId);
        List<String> pending = pendingRepricesFromSection(section);
        Map<String, String> tokens =
                pendingRepriceTokensFromSection(section);
        String token = UUID.randomUUID().toString();
        if (REPRICE_ALL.equals(requested)) {
            pending.clear();
            pending.add(REPRICE_ALL);
            tokens.clear();
            tokens.put(REPRICE_ALL, token);
        } else if (pending.contains(REPRICE_ALL)) {
            // The wildcard will replay this newer edit too. Rotate its token so
            // an older in-flight wildcard cannot clear the newer obligation.
            tokens.put(REPRICE_ALL, token);
        } else {
            if (!pending.contains(requested)) pending.add(requested);
            tokens.put(requested, token);
        }
        section.put(KEY_PENDING_REPRICES, new JSONArray(pending));
        section.put(
                KEY_PENDING_REPRICE_TOKENS,
                new JSONObject(tokens));
    }

    private static String normalizeRepriceKey(String tariffId) {
        return tariffId == null || tariffId.isEmpty()
                ? REPRICE_ALL : tariffId;
    }

    private static void normalizeDefaultId(TariffDocument document) throws Exception {
        String requested = document.section.optString(KEY_DEFAULT_ID, "");
        if (!requested.isEmpty() && findProfileIndex(document.tariffs, requested) < 0) {
            requested = "";
        }
        document.section.put(KEY_DEFAULT_ID, requested);
    }

    private static int findProfileIndex(JSONArray tariffs, String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tariffs.length(); i++) {
            JSONObject profile = tariffs.optJSONObject(i);
            if (profile != null && id.equals(profile.optString("id", ""))) {
                return i;
            }
        }
        return -1;
    }

    /** Preserve fields added by another process while replacing canonical profile fields. */
    static JSONObject mergeCanonicalProfile(
            JSONObject durableProfile, TariffProfile profile) throws Exception {
        JSONObject merged = durableProfile != null
                ? new JSONObject(durableProfile.toString()) : new JSONObject();
        JSONObject canonical = profile.toJson();
        java.util.Iterator<String> keys = canonical.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            merged.put(key, canonical.get(key));
        }
        return merged;
    }

    // ========================================================================
    // CRUD
    // ========================================================================

    /**
     * @return the created profile, or null when the cap is hit OR the write failed.
     *
     * <p>synchronized, and it rolls the in-memory list back if persistence fails:
     * otherwise the daemon would keep pricing charges with a tariff that isn't in
     * the config file, and the API would have reported success for it.
     */
    public synchronized TariffProfile add(String label, double lat, double lng, int radiusM,
                            double acRate, double dcRate, String currency) {
        TariffProfile p = new TariffProfile(label, lat, lng, radiusM, acRate, dcRate, currency);
        // NOT auto-promoted to default. Auto-promoting the first profile meant that
        // adding one tariff silently made it the catch-all for every charge that
        // matched no circle — including every pre-v3 history row, whose
        // start_lat/start_lng default to 0. Combined with re-pricing that restated
        // the user's entire cost history from one innocuous action. The default is
        // now only ever set by an explicit tap on the star (setDefault).
        MutationCommit commit = commitMutation(p.getId(), document -> {
            if (document.tariffs.length() >= MAX_PROFILES) {
                return StagedMutation.rejected(
                        "tariff cap reached (" + MAX_PROFILES + ")");
            }
            document.tariffs.put(p.toJson());
            return StagedMutation.accepted(p);
        });
        if (!commit.committed) {
            return null;
        }
        logger.info("Added tariff '" + p.getLabel() + "' ac=" + acRate + " dc=" + dcRate
                + " @" + lat + "," + lng + " r=" + p.getRadiusMeters());
        return commit.profile;
    }

    /**
     * Partial update — only the keys present in {@code updates} are applied.
     *
     * <p>Applies the deltas to a COPY and swaps it in, rather than mutating the
     * published profile field-by-field: a reader on the SoC thread matching a
     * charge could otherwise observe a half-moved circle (new latitude with the
     * old longitude) and miss the charge it was meant to price.
     */
    public synchronized boolean update(String id, JSONObject updates) {
        if (id == null || id.isEmpty() || updates == null) return false;
        MutationCommit commit = commitMutation(id, document -> {
            int idx = findProfileIndex(document.tariffs, id);
            if (idx < 0) return StagedMutation.rejected("tariff not found");

            JSONObject durable = document.tariffs.getJSONObject(idx);
            TariffProfile profile = new TariffProfile(durable);
            if (updates.has("label")) profile.setLabel(updates.optString("label"));
            if (updates.has("lat")) profile.setLatitude(updates.optDouble("lat"));
            if (updates.has("lng")) profile.setLongitude(updates.optDouble("lng"));
            if (updates.has("radiusM")) profile.setRadiusMeters(updates.optInt("radiusM"));
            if (updates.has("acRate")) profile.setAcRate(updates.optDouble("acRate"));
            if (updates.has("dcRate")) profile.setDcRate(updates.optDouble("dcRate"));
            if (updates.has("currency")) profile.setCurrency(updates.optString("currency"));
            if (updates.has("enabled")) profile.setEnabled(updates.optBoolean("enabled"));
            if (!profile.hasAnyRate()) {
                return StagedMutation.rejected("tariff has no usable rate");
            }
            document.tariffs.put(
                    idx, mergeCanonicalProfile(durable, profile));
            return StagedMutation.accepted(profile);
        });
        if (!commit.committed) return false;
        logger.info("Updated tariff '" + commit.profile.getLabel() + "'");
        return true;
    }

    public synchronized boolean remove(String id) {
        if (id == null || id.isEmpty()) return false;
        MutationCommit commit = commitMutation(REPRICE_ALL, document -> {
            int idx = findProfileIndex(document.tariffs, id);
            if (idx < 0) return StagedMutation.rejected("tariff not found");
            TariffProfile removed =
                    new TariffProfile(document.tariffs.getJSONObject(idx));
            JSONArray retained = new JSONArray();
            for (int i = 0; i < document.tariffs.length(); i++) {
                if (i != idx) retained.put(document.tariffs.get(i));
            }
            document.tariffs = retained;
            if (id.equals(document.section.optString(KEY_DEFAULT_ID, ""))) {
            // Clear the pin — do NOT promote a survivor. Auto-promotion silently
            // made an unrelated place the catch-all, and because the re-price that
            // follows a delete resolves the orphaned rows leniently, every session
            // the deleted tariff owned was restated at that other place's rate
            // (e.g. 40 home charges re-priced at a motorway DC rate). The pin is
            // only ever set by an explicit setDefault(), same as add().
                document.section.put(KEY_DEFAULT_ID, "");
            }
            return StagedMutation.accepted(removed);
        });
        if (!commit.committed) return false;
        logger.info("Removed tariff '" + commit.profile.getLabel() + "'");
        return true;
    }

    /** Pin the fallback profile. Pass "" to clear. */
    public synchronized boolean setDefault(String id) {
        final String requested = id != null ? id : "";
        return commitMutation(REPRICE_ALL, document -> {
            if (!requested.isEmpty()
                    && findProfileIndex(document.tariffs, requested) < 0) {
                return StagedMutation.rejected("tariff not found");
            }
            document.section.put(KEY_DEFAULT_ID, requested);
            return StagedMutation.accepted(null);
        }).committed;
    }

    public String getDefaultId() {
        ensureLoaded();
        return defaultTariffId;
    }

    public List<TariffProfile> getProfiles() {
        ensureLoaded();
        return new ArrayList<>(profiles);
    }

    public TariffProfile findById(String id) {
        if (id == null || id.isEmpty()) return null;
        ensureLoaded();
        for (TariffProfile p : profiles) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    public boolean isEmpty() {
        ensureLoaded();
        return profiles.isEmpty();
    }

    // ========================================================================
    // MATCHING
    // ========================================================================

    /**
     * The profile that should price a session charged at {@code (lat,lng)} with
     * the given tri-state DC verdict, or {@code null} to use the global rate.
     *
     * <p>Only profiles that actually price THIS gun type are considered: a
     * DC-only profile must not swallow an AC session and leave it at 0. Among
     * candidates containing the point, the SMALLEST radius wins — a specific
     * sub-site beats the broad campus circle it sits inside — with distance as
     * the tiebreak for equal-radius overlaps.
     *
     * <p>A {@code (0,0)} location means "no GPS fix at the charge edge"; we
     * cannot claim a match there, so it goes straight to the default. Callers
     * are expected to pass the session's own stored coordinates.
     */
    public TariffProfile resolve(double lat, double lng, int isDc) {
        TariffProfile inCircle = resolveInCircle(lat, lng, isDc);
        if (inCircle != null) return inCircle;

        // A rate the user SET is honoured for both AC and DC. rateFor() applies the
        // separate dcRate only when they bothered to enter one; otherwise the single
        // rate they typed covers every gun at that place. Entering one number must
        // not leave half their charges priced by something else.
        //
        // This is only reached when no circle matched, i.e. the user explicitly
        // PINNED this profile as the catch-all. A tariff is never auto-promoted to
        // default (see add()), so the global rate stays the fallback until they ask
        // otherwise.
        TariffProfile def = findById(defaultTariffId);
        if (def != null && def.isEnabled() && def.rateFor(isDc) > 0) return def;
        return null;
    }

    /**
     * Monetary-snapshot variant of {@link #resolve}; a config read failure is an error, not "no tariff".
     */
    public synchronized TariffProfile resolveStrict(double lat, double lng, int isDc) {
        try {
            // Monetary snapshots re-verify the durable source even when a cache is already
            // published. A transient read failure must defer the close, not freeze stale pricing.
            loadStrict();
        } catch (Exception e) {
            loaded = false;
            lastLoadFailure = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new IllegalStateException(
                    "tariff config unavailable: " + lastLoadFailure, e);
        }
        TariffProfile inCircle = resolveInCircleLoaded(lat, lng, isDc);
        if (inCircle != null) return inCircle;
        TariffProfile def = findById(defaultTariffId);
        if (def != null && def.isEnabled() && def.rateFor(isDc) > 0) return def;
        return null;
    }

    /**
     * Strict geofence match: the tariff whose circle actually CONTAINS
     * {@code (lat,lng)} and which prices this gun type, or {@code null}. Unlike
     * {@link #resolve} this never falls back to the pinned default.
     *
     * <p>Used where a match must be positively earned by location rather than
     * inherited — specifically when deciding whether a historical session that
     * was priced by the global rate may be ADOPTED by a tariff. Pre-v3 session
     * rows have {@code start_lat/start_lng} defaulted to 0, so the lenient
     * {@link #resolve} would hand every one of them to the default profile and
     * silently restate the whole cost history (see
     * {@code SocHistoryDatabase.repriceSessionsForTariff}).
     */
    public TariffProfile resolveInCircle(double lat, double lng, int isDc) {
        ensureLoaded();
        return resolveInCircleLoaded(lat, lng, isDc);
    }

    public synchronized TariffProfile resolveInCircleStrict(double lat, double lng, int isDc) {
        try {
            loadStrict();
        } catch (Exception e) {
            loaded = false;
            lastLoadFailure = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
            throw new IllegalStateException(
                    "tariff config unavailable: " + lastLoadFailure, e);
        }
        return resolveInCircleLoaded(lat, lng, isDc);
    }

    private TariffProfile resolveInCircleLoaded(double lat, double lng, int isDc) {
        return resolveInCircleSnapshot(profiles, lat, lng, isDc);
    }

    private static TariffProfile resolveInCircleSnapshot(
            List<TariffProfile> snapshot, double lat, double lng, int isDc) {
        return resolveInCircleSnapshot(snapshot, lat, lng, isDc, false);
    }

    private static TariffProfile resolveInCircleSnapshot(
            List<TariffProfile> snapshot, double lat, double lng,
            int isDc, boolean anyRate) {
        if (snapshot.isEmpty()) return null;
        // (0,0) is the "no GPS fix at the charge edge" sentinel, not the Atlantic.
        if (lat == 0 && lng == 0) return null;

        TariffProfile best = null;
        double bestDist = Double.MAX_VALUE;
        for (TariffProfile p : snapshot) {
            if (!p.isEnabled()) continue;
            if (anyRate ? !p.hasAnyRate() : p.rateFor(isDc) <= 0) continue;
            double d = SafeLocationManager.haversine(lat, lng, p.getLatitude(), p.getLongitude());
            // Written as !(d <= r) rather than (d > r) so a NaN distance — from a
            // hand-edited/garbage coordinate in the world-writable config file —
            // fails CLOSED. With (d > r), NaN compares false and the broken
            // profile would "contain" every location on earth.
            if (!(d <= p.getRadiusMeters())) continue;
            if (best == null
                    || p.getRadiusMeters() < best.getRadiusMeters()
                    || (p.getRadiusMeters() == best.getRadiusMeters() && d < bestDist)) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * Remove one config-side repricing intent only after the database operation
     * has completed. A wildcard completion subsumes every targeted intent.
     */
    static final class RepriceIntent {
        final String key;
        final String token;

        RepriceIntent(String key, String token) {
            this.key = key;
            this.token = token;
        }

        String tariffId() {
            return REPRICE_ALL.equals(key) ? "" : key;
        }
    }

    synchronized RepriceIntent pendingRepriceIntent(String tariffId) {
        String requested = normalizeRepriceKey(tariffId);
        String key = pendingRepriceKeys.contains(REPRICE_ALL)
                ? REPRICE_ALL : requested;
        if (!pendingRepriceKeys.contains(key)) return null;
        String token = pendingRepriceTokens.get(key);
        return token == null || token.isEmpty()
                ? null : new RepriceIntent(key, token);
    }

    public synchronized boolean completePendingReprice(String tariffId) {
        RepriceIntent intent = pendingRepriceIntent(tariffId);
        return intent == null || completePendingReprice(intent);
    }

    synchronized boolean completePendingReprice(RepriceIntent intent) {
        if (intent == null) return false;
        try {
            PublishedImage committed = UnifiedConfigManager.runUnderConfigLock(() -> {
                try {
                    JSONObject root = readDurableConfigLocked();
                    JSONObject section = root.optJSONObject(SECTION);
                    if (section == null) return imageFromSection(null);
                    List<String> pending = pendingRepricesFromSection(section);
                    Map<String, String> tokens =
                            pendingRepriceTokensFromSection(section);
                    if (!intent.token.equals(tokens.get(intent.key))) {
                        return null;
                    }
                    boolean changed;
                    if (REPRICE_ALL.equals(intent.key)) {
                        // A wildcard completion subsumes every pending intent.
                        // Clearing the tokens alone is not enough: a "*" key left
                        // in the pending list would be re-issued a legacy token on
                        // the next load (pendingRepriceTokensFromSection backfills
                        // token-less pending keys), resurrecting the intent and
                        // looping the replay forever.
                        changed = !pending.isEmpty();
                        pending.clear();
                        tokens.clear();
                    } else {
                        changed = pending.remove(intent.key);
                        tokens.remove(intent.key);
                    }
                    if (!changed) return imageFromSection(section);
                    section.put(KEY_PENDING_REPRICES, new JSONArray(pending));
                    section.put(
                            KEY_PENDING_REPRICE_TOKENS,
                            new JSONObject(tokens));
                    root.put(SECTION, section);
                    if (!UnifiedConfigManager.saveConfig(root)) return null;
                    return imageFromSection(section);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "tariff repricing intent cleanup failed", e);
                }
            });
            if (committed == null) return false;
            publishImage(committed);
            lastLoadFailure = "";
            return true;
        } catch (Exception e) {
            lastLoadFailure = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
            logger.warn("Could not clear completed tariff repricing intent: "
                    + lastLoadFailure);
            return false;
        }
    }

    private synchronized boolean beginPendingRepriceReplay() {
        if (replayingPendingReprices || pendingRepriceKeys.isEmpty()) return false;
        replayingPendingReprices = true;
        return true;
    }

    private synchronized RepriceIntent nextPendingRepriceIntent() {
        if (pendingRepriceKeys.isEmpty()) return null;
        String key = pendingRepriceKeys.get(0);
        String token = pendingRepriceTokens.get(key);
        return token == null || token.isEmpty()
                ? null : new RepriceIntent(key, token);
    }

    private synchronized void finishPendingRepriceReplay() {
        replayingPendingReprices = false;
    }

    private void replayPendingReprices() {
        if (!beginPendingRepriceReplay()) return;
        String lastCompletedKey = null;
        String lastCompletedToken = null;
        try {
            while (true) {
                RepriceIntent intent = nextPendingRepriceIntent();
                if (intent == null) return;
                // No-progress guard: a completion that reports success must
                // actually retire the intent. If the identical key+token comes
                // back, cleanup is not making durable progress; looping would
                // re-run the full repricing forever. Abort and surface it.
                if (intent.key.equals(lastCompletedKey)
                        && intent.token.equals(lastCompletedToken)) {
                    logger.error("Tariff repricing replay made no progress for "
                            + intent.key + "; aborting replay to avoid a loop");
                    return;
                }
                try {
                    com.overdrive.app.monitor.SocHistoryDatabase database =
                            com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
                    if (database == null) return;
                    // This call takes the database monitor. The tariff monitor was
                    // released after nextPendingRepriceIntent() captured the
                    // generation token.
                    database.repriceSessionsForTariff(intent.tariffId());
                    // Cleanup re-enters the tariff monitor and removes the intent
                    // only if its durable token still matches. A concurrent edit
                    // rotates the token and therefore survives this older replay.
                    if (!completePendingReprice(intent)) {
                        return;
                    }
                    lastCompletedKey = intent.key;
                    lastCompletedToken = intent.token;
                } catch (Throwable deferred) {
                    logger.warn("Pending tariff repricing replay deferred for "
                            + intent.key + ": " + deferred.getMessage());
                    return;
                }
            }
        } finally {
            finishPendingRepriceReplay();
        }
    }

    /**
     * Reconcile display-only usage metadata to the authoritative charging-session rows.
     *
     * <p>This is intentionally an assignment, not an increment. Re-running it after an uncertain
     * charging close produces the same profile image and is therefore idempotent.
     */
    public synchronized boolean reconcileUsage(
            String id, long lastUsedAt, int useCount) {
        if (id == null || id.isEmpty()) return true;
        int normalizedCount = Math.max(0, useCount);
        long normalizedLastUsed = Math.max(0, lastUsedAt);
        UsageReconcileResult result;
        try {
            result = UnifiedConfigManager.runUnderConfigLock(() -> {
                try {
                    // Read the durable root only after taking the same lock used by every
                    // config writer. A pre-lock load can become stale before saveConfig()
                    // acquires its nested lock and overwrite a concurrent tariff/default edit.
                    JSONObject root =
                            UnifiedConfigManager.readDurableConfigStrict();
                    JSONObject section = root.optJSONObject(SECTION);
                    JSONArray tariffs = tariffsArrayFromSection(section);
                    JSONObject target = null;
                    for (int i = 0; i < tariffs.length(); i++) {
                        JSONObject candidate = tariffs.optJSONObject(i);
                        if (candidate != null
                                && id.equals(candidate.optString("id", ""))) {
                            target = candidate;
                            break;
                        }
                    }

                    // A historical row may reference a profile the user later deleted.
                    // The fresh durable snapshot is already the committed result.
                    if (target == null) {
                        return usageSnapshot(section, false);
                    }
                    if (target.optInt("useCount", 0) == normalizedCount
                            && target.optLong("lastUsedAt", 0L) == normalizedLastUsed) {
                        return usageSnapshot(section, false);
                    }

                    // Patch the profile and publish a canonical tariffs array. Legacy
                    // configs stored one profile as an object; writing the normalized
                    // array migrates them without dropping sibling rows.
                    target.put("useCount", normalizedCount);
                    target.put("lastUsedAt", normalizedLastUsed);
                    section.put(KEY_TARIFFS, tariffs);
                    root.put(SECTION, section);
                    if (!UnifiedConfigManager.saveConfig(root)) {
                        return UsageReconcileResult.failure(
                                "config persistence rejected usage replay");
                    }
                    return usageSnapshot(section, true);
                } catch (Exception e) {
                    return UsageReconcileResult.failure(
                            e.getMessage() != null
                                    ? e.getMessage() : e.getClass().getSimpleName());
                }
            });
        } catch (Exception unavailable) {
            loaded = false;
            lastLoadFailure = unavailable.getMessage() != null
                    ? unavailable.getMessage()
                    : unavailable.getClass().getSimpleName();
            return false;
        }
        if (!result.success) {
            loaded = false;
            lastLoadFailure = result.failure;
            return false;
        }

        // Publish only the detached image read (and, when needed, persisted) under
        // the lock. A failed save leaves the previous complete snapshot untouched.
        profiles = result.profiles;
        defaultTariffId = result.defaultTariffId;
        loaded = true;
        lastLoadFailure = "";
        return true;
    }

    /** Build an immutable manager image detached from the root passed to saveConfig(). */
    private static UsageReconcileResult usageSnapshot(
            JSONObject section, boolean persisted) {
        List<TariffProfile> parsed = new ArrayList<>();
        String requestedDefault = "";
        if (section != null) {
            requestedDefault = section.optString(KEY_DEFAULT_ID, "");
            JSONArray tariffs = tariffsArrayFromSection(section);
            for (int i = 0; i < tariffs.length()
                    && parsed.size() < MAX_PROFILES; i++) {
                JSONObject profile = tariffs.optJSONObject(i);
                if (profile != null) parsed.add(new TariffProfile(profile));
            }
        }
        String publishedDefault = "";
        for (TariffProfile profile : parsed) {
            if (requestedDefault.equals(profile.getId())) {
                publishedDefault = requestedDefault;
                break;
            }
        }
        return UsageReconcileResult.success(
                Collections.unmodifiableList(parsed), publishedDefault, persisted);
    }

    private static final class UsageReconcileResult {
        final boolean success;
        final List<TariffProfile> profiles;
        final String defaultTariffId;
        final boolean persisted;
        final String failure;

        private UsageReconcileResult(
                boolean success,
                List<TariffProfile> profiles,
                String defaultTariffId,
                boolean persisted,
                String failure) {
            this.success = success;
            this.profiles = profiles;
            this.defaultTariffId = defaultTariffId;
            this.persisted = persisted;
            this.failure = failure;
        }

        static UsageReconcileResult success(
                List<TariffProfile> profiles,
                String defaultTariffId,
                boolean persisted) {
            return new UsageReconcileResult(
                    true, profiles, defaultTariffId, persisted, "");
        }

        static UsageReconcileResult failure(String failure) {
            return new UsageReconcileResult(
                    false, Collections.emptyList(), "", false, failure);
        }
    }

    /**
     * Note that a tariff priced a session.
     *
     * <p>Current close paths replay authoritative aggregate counts through
     * {@link #reconcileUsage}. This compatibility entry point still performs an
     * increment, but patches only the current durable profile under the config
     * lock; it never serializes the process-local tariff array.
     */
    public synchronized void markUsed(String id, long whenMs) {
        if (id == null || id.isEmpty()) return;
        commitMutation(document -> {
            int idx = findProfileIndex(document.tariffs, id);
            if (idx < 0) return StagedMutation.rejected("tariff not found");
            JSONObject durable = document.tariffs.getJSONObject(idx);
            TariffProfile profile = new TariffProfile(durable);
            profile.markUsed(whenMs);
            document.tariffs.put(
                    idx, mergeCanonicalProfile(durable, profile));
            return StagedMutation.accepted(profile);
        });
    }

    /**
     * Compatibility no-op. Usage metadata is now persisted either immediately by
     * {@link #markUsed} or idempotently from database aggregates by
     * {@link #reconcileUsage}; there is no process-local full-array buffer to flush.
     */
    public synchronized void flushPendingUsage() {
        // Intentionally empty.
    }

    /**
     * Profiles ordered for display: most-recently-used first, then most-used,
     * then newest. Keeps the tariff the driver actually uses at the top of the
     * list instead of whatever order they happened to create things in.
     */
    public List<TariffProfile> getProfilesForDisplay() {
        List<TariffProfile> list = getProfiles();
        Collections.sort(list, new Comparator<TariffProfile>() {
            @Override
            public int compare(TariffProfile a, TariffProfile b) {
                if (a.getLastUsedAt() != b.getLastUsedAt()) {
                    return Long.compare(b.getLastUsedAt(), a.getLastUsedAt());
                }
                if (a.getUseCount() != b.getUseCount()) {
                    return Integer.compare(b.getUseCount(), a.getUseCount());
                }
                return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            }
        });
        return list;
    }

    /**
     * API payload: the ordered profile list plus the default id and, when a
     * location is known, which profile currently matches — that's what lets the
     * UI show "auto-applies here" against the live position.
     */
    public synchronized JSONObject toStatusJson(double lat, double lng)
            throws Exception {
        return toStatusJson(loadVerifiedConfig(), lat, lng);
    }

    /**
     * Build tariff status from the caller's already-verified root without
     * publishing it into manager state. This lets an API derive tariffs and
     * global fallbacks from one revision without letting a delayed GET regress
     * a newer mutation.
     */
    public synchronized JSONObject toStatusJson(
            JSONObject verifiedRoot, double lat, double lng)
            throws Exception {
        PublishedImage image = imageFromSection(
                verifiedRoot != null
                        ? verifiedRoot.optJSONObject(SECTION) : null);
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        List<TariffProfile> ordered = new ArrayList<>(image.profiles);
        Collections.sort(ordered, new Comparator<TariffProfile>() {
            @Override
            public int compare(TariffProfile a, TariffProfile b) {
                if (a.getLastUsedAt() != b.getLastUsedAt()) {
                    return Long.compare(b.getLastUsedAt(), a.getLastUsedAt());
                }
                if (a.getUseCount() != b.getUseCount()) {
                    return Integer.compare(b.getUseCount(), a.getUseCount());
                }
                return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            }
        });
        for (TariffProfile p : ordered) arr.put(p.toJson());
        out.put("tariffs", arr);
        out.put("defaultTariffId", image.defaultTariffId);
        out.put("maxTariffs", MAX_PROFILES);
        if (!(lat == 0 && lng == 0)) {
            out.put("lat", lat);
            out.put("lng", lng);
            // Status answers whether a saved tariff exists here; charger type is not known yet.
            TariffProfile match =
                    resolveInCircleSnapshot(image.profiles, lat, lng, 0, true);
            if (match == null) {
                TariffProfile fallback = null;
                for (TariffProfile candidate : image.profiles) {
                    if (candidate.getId().equals(image.defaultTariffId)) {
                        fallback = candidate;
                        break;
                    }
                }
                if (fallback != null && fallback.isEnabled()
                        && fallback.hasAnyRate()) {
                    match = fallback;
                }
            }
            out.put("matchedTariffId",
                    match != null ? match.getId() : JSONObject.NULL);
        } else {
            out.put("matchedTariffId", JSONObject.NULL);
        }
        return out;
    }
}
