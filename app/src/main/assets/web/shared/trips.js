/**
 * OverDrive - Trip Analytics Module v2
 * Modern trip list, interactive timeline slider, route map with marker,
 * radar hover tooltips, score descriptions, speed distribution details.
 */

const TRIPS = {
    // State
    currentOffset: 0,
    currentCursor: null,
    currentDays: 7,
    pageSize: 20,
    trips: [],
    currentTripId: null,
    _detailRequestSequence: 0,
    _activeDetailRequest: null,
    _detailAbortController: null,
    _listRequestSequence: 0,
    _activeListKey: null,
    _listAbortController: null,
    _listLoadMoreInFlight: false,
    _summaryRequestSequence: 0,
    _activeSummaryKey: null,
    _summaryAbortController: null,
    _lastSummaryPayload: null,
    leafletMap: null,
    routeLayer: null,
    sliderMarker: null,
    telemetryCache: null,
    radarScoresCache: null,
    rangeCache: null,
    pendingStorageType: null,
    pendingStorageLimit: null,
    electricityRate: 0,
    currency: '$',
    // Pack capacity from SohEstimator (user override or auto-detected).
    // Used as the fallback nominal when a trip's kwhStart wasn't recorded.
    // 0 means the daemon hasn't surfaced one yet; falls through to the
    // legacy 82.56 default per estimateNominalKwh() below.
    nominalKwh: 0,
    // PHEV / fuel state. tankCapacityL is always stored in litres internally;
    // the gallon UI mode converts on read/write. fuelPricePerL is in the
    // currently-selected currency, also always per-litre. isPhev is the
    // server-side drivetrain probe — gates fuel rows + per-trip breakdown.
    tankCapacityL: 0,
    fuelPricePerL: 0,
    fuelUnit: 'L',
    isPhev: false,
    // 1 US gallon — used to convert UI-side gallon entry to/from litres.
    LITRES_PER_GAL: 3.785411784,

    // Canvas paint palette. text / grid / textStrong / dotStroke /
    // arcTrack flip with [data-theme="light"] via _refreshPalette() —
    // they're seeded with the dark defaults and replaced by reading
    // the --chart-* CSS variables (same pattern as performance.js).
    // Brand and tier colours stay theme-independent.
    colors: {
        brand: '#00D4AA',
        brandRgba: 'rgba(0, 212, 170, 0.25)',
        accent: '#0EA5E9',
        danger: '#EF4444',
        warning: '#F59E0B',
        text: 'rgba(255, 255, 255, 0.7)',
        textMuted: 'rgba(255, 255, 255, 0.5)',
        textStrong: '#FFFFFF',
        grid: 'rgba(255, 255, 255, 0.08)',
        // Inner stroke around radar dots / timeline pucks. In dark
        // theme this matches the page background so the dot reads
        // crisp; in light theme it flips to the light surface.
        dotStroke: '#0F0F12',
        // Faint background ring under score / range circle gauges.
        arcTrack: 'rgba(255, 255, 255, 0.06)',
        speedGreen: '#22C55E',
        speedYellow: '#F59E0B',
        speedRed: '#EF4444',
    },

    // Re-read the palette from CSS custom properties whenever the
    // <html data-theme> flips. Called once at init and on every
    // attribute mutation, then renderers re-read this.colors.
    _refreshPalette: function () {
        try {
            var s = getComputedStyle(document.documentElement);
            var pick = function (name, fallback) {
                var v = (s.getPropertyValue(name) || '').trim();
                return v || fallback;
            };
            this.colors.text       = pick('--chart-text',        this.colors.text);
            this.colors.textStrong = pick('--chart-text-strong', this.colors.textStrong);
            this.colors.grid       = pick('--chart-grid',        this.colors.grid);
            // dotStroke / arcTrack track the page surface, not the
            // chart palette — read --bg-base which the design-tokens
            // layer already flips per theme.
            this.colors.dotStroke = pick('--bg-base',     this.colors.dotStroke);
            this.colors.arcTrack  = pick('--border-subtle', this.colors.arcTrack);
            this.colors.textMuted = this.colors.text;
        } catch (e) { /* keep dark defaults */ }
    },

    _setupThemeObserver: function () {
        if (this._themeObserver) return;
        var self = this;
        try {
            this._themeObserver = new MutationObserver(function () {
                self._refreshPalette();
                // Re-paint everything that draws to a canvas. Each
                // call is wrapped because the canvas may not be in
                // the DOM (detail view collapsed, settings tab
                // active, etc.) and a missing element shouldn't
                // abort the rest.
                try {
                    var radar = document.getElementById('radarChart');
                    if (radar && self.radarScoresCache) self.renderRadar(radar, self.radarScoresCache);
                } catch (e) {}
                try {
                    if (self.radarScoresCache && self.radarScoresCache.overall !== undefined) {
                        self.renderScoreCircle(self.radarScoresCache.overall);
                    }
                } catch (e) {}
                try {
                    if (self.telemetryCache) {
                        var tl = document.getElementById('timelineChart');
                        if (tl) self.renderTimeline(tl, self.telemetryCache);
                        var hist = document.getElementById('speedHistogram');
                        if (hist) self.renderSpeedHistogram(hist, self.telemetryCache);
                    }
                } catch (e) {}
            });
            this._themeObserver.observe(document.documentElement, {
                attributes: true,
                attributeFilter: ['data-theme']
            });
        } catch (e) { /* MutationObserver unavailable — leave palette static */ }
    },

    // Criteria metadata for tooltips and descriptions.
    // Strings come from BYD.i18n.t('trip.criteria.<key>.{label,desc,tip}'); the
    // emoji icons stay inline because they're locale-neutral pictograms.
    // The 'speed_discipline' key remaps from 'speedDiscipline' so JS callers
    // that index by camelCase still work — see _criteriaKey() helper.
    criteriaInfo: {
        anticipation:    { icon: '🔮', i18n: 'anticipation' },
        smoothness:      { icon: '🌊', i18n: 'smoothness' },
        speedDiscipline: { icon: '🎯', i18n: 'speed_discipline' },
        efficiency:      { icon: '⚡', i18n: 'efficiency' },
        consistency:     { icon: '📐', i18n: 'consistency' }
    },

    // ── Energy-leg icons (Material-Symbols-outlined shapes) ─────────────────
    // Inline stroke SVG, matching the battery / tank / odometer capsule icons
    // already used in the trip rows: 24-unit viewBox, stroke=currentColor so the
    // glyph inherits the surrounding colour. Deliberately NOT the Material
    // Symbols webfont — that would add a network fetch the head unit may not
    // have, and ligature names render as raw text until the font loads.
    // `bolt` (electric) and `local_gas_station` (petrol).
    ICON_ELECTRIC: '<svg class="trip-leg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>',
    ICON_PETROL: '<svg class="trip-leg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 22h13M5 22V8a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v14"/><path d="M15 6V4a1 1 0 0 1 1-1h0a1 1 0 0 1 1 1v10a2 2 0 0 0 2 2h0a2 2 0 0 0 2-2V8.5"/><path d="M7 10h6"/></svg>',

    /** Resolve a criterion field (label/desc/tip) through i18n. */
    criterion: function (key, field) {
        var info = this.criteriaInfo[key];
        if (!info) return key;
        var i18nKey = 'trip.criteria.' + info.i18n + '.' + field;
        var translated = BYD.i18n.t(i18nKey);
        if (field === 'tip' && info.i18n === 'speed_discipline') {
            // Inject the user's actual speed unit so the tip reads naturally
            // in metric or imperial without a second translation pass.
            return BYD.i18n.t(i18nKey, { limit: '5 ' + BYD.units.speedLabel() });
        }
        return translated;
    },

    // ==================== INIT ====================

    async init() {
        console.log('[Trips] Initializing v2...');
        // Resolve canvas palette from CSS variables before first paint
        // and listen for theme flips so the radar / score circle /
        // charts repaint in the new theme without a page reload.
        this._refreshPalette();
        this._setupThemeObserver();
        // app-tabs.js drives the bottom tab bar (Trips / Stats / Storage)
        // by toggling [data-tab] visibility. The trip-detail drill-in
        // (#tripDetail) has no data-tab — when the user opens a trip
        // and then taps another bottom tab, app-tabs hides the list
        // view's [data-tab] cards but leaves the active drill-in on
        // top, so the detail layout sticks across tabs. Close the
        // detail back to the list whenever the active tab changes.
        var self = this;
        document.addEventListener('ot-tabs:active-changed', function () {
            if (self.currentTripId != null || self._activeDetailRequest) self.hideDetail();
        });

        // Render skeletons immediately so the user sees something while the
        // bootstrap fetch is in flight. The storage card especially used to
        // sit blank for the entire 10-20 minute SD-card walk; even though
        // the DB-backed getTripsSize() removed that, the skeleton is still
        // the right "I'm working on it" affordance during the single RTT.
        this._renderInitialSkeletons();

        // Single composite call replaces 6 sequential RTTs (config / storage
        // / dna / summary / range / trips). Falls back to the legacy
        // sequential loaders if the bootstrap endpoint is unavailable or
        // returns an unsuccessful payload — worst-case behaviour is
        // unchanged.
        let usedBootstrap = false;
        // The bootstrap trip page participates in the same generation scheme as
        // filter/list requests. If the user changes the filter while this
        // composite request is in flight, its config/storage slices may still be
        // useful, but its stale trip page must not replace the chosen window.
        const bootstrapListRequest = this._beginListRequest(
            this._daysListKey(this.currentDays), 0, false, false);
        const bootstrapSummaryRequest = this._beginSummaryRequest(
            this._daysSummaryKey(this.currentDays), false);
        try {
            const bootstrapResp = await fetch('/api/trips/bootstrap');
            const bootstrapData = await bootstrapResp.json();
            if (bootstrapData && bootstrapData.success && bootstrapData.bootstrap) {
                const b = bootstrapData.bootstrap;
                if (b.config)  this._applyConfigPayload(b.config);
                if (b.storage) this._applyStoragePayload(b.storage);
                if (b.dna)     this._applyDnaPayload(b.dna);
                if (b.summary) this._applySummaryPayload(b.summary, bootstrapSummaryRequest);
                if (b.range)   this._applyRangePayload(b.range);
                if (b.trips)   this._applyTripsPayload(b.trips, 0, bootstrapListRequest);
                usedBootstrap = true;
            }
        } catch (e) {
            console.warn('[Trips] Bootstrap failed, falling back to sequential loaders:', e);
        }

        if (!usedBootstrap) {
            await this.loadConfig();
            await this.loadStorageSettings();
            await this.loadDna();
            if (this._isCurrentSummaryRequest(bootstrapSummaryRequest)) {
                await this.loadSummary(this.currentDays);
            }
            await this.loadRange();
            // A user-selected filter/range owns a newer list generation.
            if (this._isCurrentListRequest(bootstrapListRequest)) {
                await this.loadTrips(this.currentDays, 0);
            }
        } else {
            this._finishListRequest(bootstrapListRequest);
            this._finishSummaryRequest(bootstrapSummaryRequest);
        }

        // CDR info is a separate /api/storage/external endpoint, not part
        // of the trips bootstrap. Small enough that one extra RTT after
        // first paint doesn't matter.
        await this.loadCdrInfo();
        this._startStorageRefresh();
        // Repaint when the unit preference changes elsewhere (core.js's /status
        // poll, or another page). Idempotent guard: init() can run more than once.
        if (!this._unitListenerBound) {
            this._unitListenerBound = true;
            var self = this;
            window.addEventListener('byd:units-changed', function (event) {
                self._repaintForUnitChange(
                    event && event.detail ? event.detail.mode : null);
            });
        }
        console.log('[Trips] Initialized (bootstrap=' + usedBootstrap + ')');
    },

    /**
     * Periodic + on-focus refresh of the storage payload.
     *
     * WHY trips needs one (recording.js and surveillance.js already had theirs):
     * the combined-limit banner is a function of the OTHER categories' limits —
     * `configuredMb` is Σ over every category on this volume — so it goes stale from
     * actions this page cannot see. Lower the recordings limit on another tab and,
     * without this, the trips banner asserts an overcommit the user already fixed,
     * with no way out but a manual reload. The inverse is worse: raise a peer limit
     * until the volume is overcommitted and the trips page silently keeps claiming
     * everything is fine.
     *
     * Refreshes the BANNER + volume availability ONLY — see _refreshBudgetOnly. It
     * must not go through
     * loadStorageSettings()/_applyStoragePayload(), which rewrite the whole storage
     * card: on a 10s cadence that reset the shared Apply button (silently killing
     * unsaved COST edits, whose inputs only call showApplyNeeded and so are invisible
     * to any storage-dirty guard), re-ran loadCdrInfo() (snapping the three CDR
     * sliders back under the user's finger), and rewrote slider.value. Reading a
     * payload and touching nothing but the advisory has none of those side effects,
     * so it needs no dirty-guard at all.
     */
    _startStorageRefresh() {
        if (this._storageRefreshTimer) return;   // idempotent
        var self = this;
        this._storageRefreshTimer = setInterval(function () {
            if (document.visibilityState !== 'visible') return;
            self._refreshBudgetOnly();
        }, 10000);
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'visible') self._refreshBudgetOnly();
        });
    },

    /**
     * Re-read the storage budget + volume availability and re-render both,
     * touching NO user-editable control.
     *
     * `storageMeta` is patched key-by-key rather than replaced: the fields left
     * alone (limitMb, storageType) back the slider value and the selected
     * button, and overwriting those mid-edit would fight the user. The banner
     * tracks the OTHER categories' limits, so it must follow changes made on the
     * recording / surveillance pages; availability and the per-volume ceilings
     * are server truth that a card insert/eject can change at any moment. The
     * user's own picks stay theirs until they Apply.
     */
    async _refreshBudgetOnly() {
        try {
            const resp = await fetch('/api/trips/storage');
            const data = await resp.json();
            const s = data && data.storage;
            if (!s) return;
            if (!this.storageMeta) this.storageMeta = {};
            // Volume availability + its capacity ceilings are server truth, not
            // user edits, so they refresh every tick — a card inserted after page
            // load has to be able to re-enable its button (see
            // _paintVolumeAvailability). The ceilings ride along because
            // setStorageType/tripsMaxFor clamp the slider against them, and a
            // freshly-mounted volume's real max only arrives with this payload.
            this.storageMeta.sdCardAvailable   = s.sdCardAvailable;
            this.storageMeta.usbAvailable      = s.usbAvailable;
            this.storageMeta.sdCardFreeSpace   = s.sdCardFreeSpace;
            this.storageMeta.sdCardTotalSpace  = s.sdCardTotalSpace;
            this.storageMeta.usbFreeSpace      = s.usbFreeSpace;
            this.storageMeta.usbTotalSpace     = s.usbTotalSpace;
            // Deliberately NOT patching maxLimitMbSdCard/maxLimitMbUsb. The server
            // reads the ceilings and the availability flags at different instants,
            // so a volume that remounts mid-response yields available=true beside a
            // ceiling computed as if it were absent (which collapses to internal's
            // ~8GB). Adopting that pair would make the NEXT setStorageType() click
            // clamp the slider — and Apply then persists ~8GB over the user's real
            // retention cap. The full-render path owns these; nothing the poll
            // repaints reads them.
            this._paintVolumeAvailability(s);
            if (!s.storageBudget) return;
            this.storageMeta.storageBudget = s.storageBudget;
            this.updateBudgetBanner();
        } catch (e) { /* advisory only — a failed poll just leaves the last render */ }
    },

    /**
     * Show skeleton placeholders for the storage/range/list cards while
     * the bootstrap fetch is in flight. Idempotent — safe to call before
     * the first paint or after a hot-reload. The trip-list skeleton is
     * already in the markup; this just ensures it isn't hidden by a prior
     * render before {@code _applyTripsPayload} fires.
     */
    _renderInitialSkeletons() {
        try {
            const skel = document.getElementById('tripListSkeleton');
            if (skel) skel.style.display = '';
        } catch (e) { /* harmless */ }
    },

    // ==================== CONFIG ====================

    async loadConfig() {
        try {
            const resp = await fetch('/api/trips/config');
            const data = await resp.json();
            this._applyConfigPayload(data);
        } catch (e) { console.warn('[Trips] Config load failed:', e); }
    },

    /**
     * Apply a /api/trips/config response (or the matching slice of the
     * /api/trips/bootstrap response) to the live UI state. Accepts the
     * raw handler payload (the {@code data} from {@code resp.json()}); a
     * missing or unsuccessful payload is a no-op so the bootstrap path
     * can pass through partial responses without guarding here.
     */
    _applyConfigPayload(data) {
        if (!data || !data.config) return;
        const el = document.getElementById('tripsEnabled');
        if (el) el.checked = data.config.enabled || false;
        // Remember it so the Trips-tab empty state can distinguish "feature is
        // off" from "you haven't driven enough yet" — the switch itself is on
        // the Storage tab and is otherwise undiscoverable from here.
        this.tripsEnabled = !!data.config.enabled;
        this._applyEnabledHint();
        // Load electricity rate
        this.electricityRate = data.config.electricityRate || 0;
        this.currency = data.config.currency || '$';
        this.nominalKwh = data.config.nominalKwh || 0;
        const rateInput = document.getElementById('rateInput');
        const currSelect = document.getElementById('currencySelect');
        if (rateInput && this.electricityRate > 0) rateInput.value = this.electricityRate;
        if (currSelect) currSelect.value = this.currency;
        // PHEV — server probe drives row visibility. Tank input is
        // shown in user's chosen unit; we convert L↔gal at the I/O
        // boundary so internal storage stays in litres.
        this.isPhev = !!data.config.isPhev;
        this.tankCapacityL = data.config.tankCapacityL || 0;
        this.fuelPricePerL = data.config.fuelPricePerL || 0;
        this.fuelUnit = data.config.fuelUnit === 'gal' ? 'gal' : 'L';
        this.applyPhevVisibility();
        this.applyFuelInputs();
        // Last-charge pricing note (names the actual rate the next trip will use).
        this.lastChargeRate = data.config.lastChargeRate || 0;
        this.lastChargeCurrency = data.config.lastChargeCurrency || '';
        this.lastChargeTariffLabel = data.config.lastChargeTariffLabel || '';
        this.applyRateSourceNote();
        // Load distance unit preference, refresh button + all labels
        var distUnit = data.config.distanceUnit || 'km';
        BYD.units.mode = distUnit;
        this.updateDistanceUnitButtons(distUnit);
        // If trips/summary already rendered before config arrived,
        // re-render so values pick up the persisted unit on first load.
        if (this.trips && this.trips.length > 0) {
            this.renderTripList(this.trips);
        }
        this.updatePeriodSummary();
        if (this.rangeFromMs == null && this._lastSummaryPayload) {
            this._applySummaryPayload(this._lastSummaryPayload);
        }
        this.updateCostHero();
        // Update currency icons
        this.updateCurrencyIcons();
    },

    async toggleEnabled() {
        const checked = document.getElementById('tripsEnabled').checked;
        this.tripsEnabled = checked;
        this._applyEnabledHint();
        try {
            await fetch('/api/trips/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: checked })
            });
        } catch (e) { console.warn('[Trips] Toggle failed:', e); }
    },

    /** Show the "turn it on" affordance inside the Trips-tab empty state only
     *  while the feature is actually disabled. Purely additive: when enabled
     *  (the new default) this hides the button and nothing else changes. */
    _applyEnabledHint() {
        const btn = document.getElementById('tripEnableFromEmpty');
        if (!btn) return;
        btn.style.display = (this.tripsEnabled === false) ? '' : 'none';
    },

    /** Enable trip recording straight from the empty state, then refresh. */
    async enableFromEmptyState() {
        const el = document.getElementById('tripsEnabled');
        if (el) el.checked = true;
        this.tripsEnabled = true;
        this._applyEnabledHint();
        try {
            await fetch('/api/trips/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: true })
            });
        } catch (e) { console.warn('[Trips] Enable failed:', e); }
        try {
            if (this.rangeFromMs != null) {
                this.loadTripsBetween(
                    this.rangeFromMs, this.rangeToMs, 0);
            } else {
                this.loadTrips(this.currentDays, 0);
            }
        } catch (e) { /* list refresh is best-effort */ }
    },

    async saveCostConfig() {
        const rateInput = document.getElementById('rateInput');
        const currSelect = document.getElementById('currencySelect');
        const tankInput = document.getElementById('tankCapacityInput');
        const fuelPriceInput = document.getElementById('fuelPriceInput');
        const rate = rateInput ? parseFloat(rateInput.value) || 0 : 0;
        const currency = currSelect ? currSelect.value : '$';
        this.electricityRate = rate;
        this.currency = currency;

        // Tank entry is in user's chosen unit; persist as litres so the
        // backend math stays SI. Gallon entry × 3.78541 = litres.
        const tankRaw = tankInput ? parseFloat(tankInput.value) || 0 : 0;
        const fuelPriceRaw = fuelPriceInput ? parseFloat(fuelPriceInput.value) || 0 : 0;
        const tankCapacityL = this.fuelUnit === 'gal' ? tankRaw * this.LITRES_PER_GAL : tankRaw;
        const fuelPricePerL = this.fuelUnit === 'gal' ? fuelPriceRaw / this.LITRES_PER_GAL : fuelPriceRaw;
        this.tankCapacityL = tankCapacityL;
        this.fuelPricePerL = fuelPricePerL;

        const body = {
            electricityRate: rate,
            currency: currency,
            tankCapacityL: tankCapacityL,
            fuelPricePerL: fuelPricePerL,
            fuelUnit: this.fuelUnit
        };
        try {
            await fetch('/api/trips/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            this.updateCurrencyIcons();
            this.updatePeriodSummary();
            this.updateCostHero();
            // Reload the visible trips so detail breakdowns reflect new
            // tank/price values via the server's enrichTripEnergy path.
            if (this.trips && this.trips.length > 0) {
                this.renderTripList(this.trips);
            }
        } catch (e) { console.warn('[Trips] Save cost config failed:', e); }
    },

    /** Toggle visibility of PHEV-only setting rows. Idempotent. */
    applyPhevVisibility() {
        const ids = ['phevTankRow', 'phevFuelPriceRow'];
        ids.forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = this.isPhev ? '' : 'none';
        });
    },

    /**
     * Explain which rate the next trip will be costed at.
     *
     * Three states, and the note only appears when it has something real to say:
     *  - a charge has been recorded → name that rate (and its tariff, if any), so
     *    the number on the card is traceable to a specific charge;
     *  - no charge yet but a global rate is set → say the global rate is standing
     *    in until the first charge is logged;
     *  - nothing configured at all → hide the note entirely rather than lecture
     *    about a mechanism the user hasn't given any inputs to.
     */
    applyRateSourceNote() {
        const box = document.getElementById('tripRateSourceNote');
        const txt = document.getElementById('tripRateSourceText');
        if (!box || !txt) return;

        // Re-compose after any locale change. hydrate() rewrites [data-i18n] nodes
        // and core.js fires onChange right after each hydrate, so without this a
        // language switch would leave the generic sentence in place of the concrete
        // last-charge rate. Subscribed once; the node itself no longer carries
        // data-i18n, so nothing else can overwrite it.
        if (!this._rateNoteI18nHooked
                && window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            this._rateNoteI18nHooked = true;
            const self = this;
            BYD.i18n.onChange(function () { self.applyRateSourceNote(); });
        }

        const tRC = (k, fb, vars) => {
            if (window.BYD && BYD.i18n && BYD.i18n.t) {
                const v = BYD.i18n.t(k, vars);
                if (v && v !== k) return v;
            }
            return fb;
        };

        if (this.lastChargeRate > 0) {
            const cur = this.lastChargeCurrency || this.currency || '$';
            const rate = cur + this.lastChargeRate.toFixed(2);
            const label = this.lastChargeTariffLabel;
            txt.textContent = label
                ? tRC('trip.settings.rate_from_charge_named',
                      'Trips are costed at your last charge: ' + rate + '/kWh (' + label + ').',
                      { rate: rate, label: label })
                : tRC('trip.settings.rate_from_charge',
                      'Trips are costed at your last charge: ' + rate + '/kWh.',
                      { rate: rate });
            box.style.display = '';
            return;
        }

        if (this.electricityRate > 0) {
            txt.textContent = tRC('trip.settings.rate_source_note',
                'Trips are costed at the rate of your last charge at a saved tariff location. This rate applies until then.');
            box.style.display = '';
            return;
        }

        // Nothing to price with and no history — say nothing.
        box.style.display = 'none';
    },

    /** Populate tank/fuel inputs in the user's chosen unit. */
    applyFuelInputs() {
        const tankInput = document.getElementById('tankCapacityInput');
        const fuelPriceInput = document.getElementById('fuelPriceInput');
        const fuelUnitL = document.getElementById('fuelUnitL');
        const fuelUnitGal = document.getElementById('fuelUnitGal');
        const priceUnitLbl = document.getElementById('fuelPriceUnitLabel');
        if (tankInput && this.tankCapacityL > 0) {
            tankInput.value = this.fuelUnit === 'gal'
                ? (this.tankCapacityL / this.LITRES_PER_GAL).toFixed(1)
                : this.tankCapacityL.toFixed(1);
        }
        if (fuelPriceInput && this.fuelPricePerL > 0) {
            fuelPriceInput.value = this.fuelUnit === 'gal'
                ? (this.fuelPricePerL * this.LITRES_PER_GAL).toFixed(2)
                : this.fuelPricePerL.toFixed(2);
        }
        if (fuelUnitL && fuelUnitGal) {
            if (this.fuelUnit === 'gal') {
                fuelUnitL.classList.remove('active');
                fuelUnitGal.classList.add('active');
            } else {
                fuelUnitL.classList.add('active');
                fuelUnitGal.classList.remove('active');
            }
        }
        if (priceUnitLbl) priceUnitLbl.textContent = this.fuelUnit === 'gal' ? '/gal' : '/L';
    },

    /** Switch tank/price unit between L and gal — preserves stored litres. */
    setFuelUnit(unit) {
        const next = unit === 'gal' ? 'gal' : 'L';
        if (next === this.fuelUnit) return;
        this.fuelUnit = next;
        this.applyFuelInputs();
        this.showApplyNeeded();
    },

    async setDistanceUnit(unit) {
        BYD.units.mode = unit;
        this.updateDistanceUnitButtons(unit);
        try {
            await fetch('/api/trips/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ distanceUnit: unit })
            });
            // Refresh all displays that show distance/speed values
            this.updatePeriodSummary();
            if (this.rangeFromMs == null && this._lastSummaryPayload) {
                this._applySummaryPayload(this._lastSummaryPayload);
            }
            this.updateCostHero();
            if (this.trips && this.trips.length > 0) {
                this.renderTripList(this.trips);
            }
            // Force a status refresh so the left nav range updates immediately
            if (BYD.core) BYD.core.refreshStatus();
        } catch (e) { console.warn('[Trips] Set distance unit failed:', e); }
    },

    /**
     * Repaint everything that renders a distance/speed value or unit label.
     *
     * Called when the unit changes from OUTSIDE this page — core.js's ~1 Hz
     * /status poll adopts the server's preference, which would otherwise flip
     * every value while the labels (written only by updateDistanceUnitButtons)
     * kept the old unit, and leave already-rendered trip cards stale until reload.
     * Read-only w.r.t. user state: it re-renders from cached payloads and touches
     * no editable control.
     */
    _repaintForUnitChange(unit) {
        try {
            this.updateDistanceUnitButtons(unit || BYD.units.mode);
            if (this.trips && this.trips.length > 0) {
                this.renderTripList(this.trips);
            }
            this.updatePeriodSummary();
            if (this.rangeFromMs == null && this._lastSummaryPayload) {
                this._applySummaryPayload(this._lastSummaryPayload);
            }
            this.updateCostHero();
        } catch (e) {
            console.warn('[Trips] Unit repaint failed:', e);
        }
    },

    updateDistanceUnitButtons(unit) {
        var kmBtn = document.getElementById('unitKm');
        var miBtn = document.getElementById('unitMi');
        if (kmBtn && miBtn) {
            if (unit === 'mi') {
                kmBtn.classList.remove('active');
                miBtn.classList.add('active');
            } else {
                kmBtn.classList.add('active');
                miBtn.classList.remove('active');
            }
        }

        var distLbl = BYD.units.distLabel();
        var speedLbl = BYD.units.speedLabel();
        var consLbl = BYD.units.consumptionLabel();
        var effLbl = BYD.units.efficiencyLabel();
        var perDistLbl = BYD.units.perDistLabel();

        // Cost card
        var costUnitEl = document.getElementById('costPerKmUnit');
        if (costUnitEl) costUnitEl.textContent = perDistLbl;
        var costTitleEl = document.getElementById('costPerDistLabel');
        if (costTitleEl) costTitleEl.textContent = distLbl;

        // Summary card
        var summaryDistLbl = document.getElementById('summaryDistanceLabel');
        if (summaryDistLbl) summaryDistLbl.textContent = distLbl;
        var summaryConsLbl = document.getElementById('summaryConsumptionLabel');
        if (summaryConsLbl) summaryConsLbl.textContent = consLbl;
        var summaryEffLbl = document.getElementById('summaryEfficiency2Label');
        if (summaryEffLbl) summaryEffLbl.textContent = effLbl;

        // Trip detail card
        var detailDistLbl = document.getElementById('detailDistanceLabel');
        if (detailDistLbl) detailDistLbl.textContent = distLbl;
        var detailConsLbl = document.getElementById('detailConsumptionLabel');
        if (detailConsLbl) detailConsLbl.textContent = consLbl;
        var detailEffLbl = document.getElementById('detailEfficiency2Label');
        if (detailEffLbl) detailEffLbl.textContent = effLbl;
        // For Avg/Max speed labels we keep the localized prefix (Avg / Max) and
        // swap only the unit. The HTML default ("Avg km/h") works as a template
        // we can derive from — fall back to data-i18n value, then replace the
        // unit suffix.
        this._setSpeedLabel('detailAvgSpeedLabel', 'trip.detail.avg_kmh', speedLbl);
        this._setSpeedLabel('detailMaxSpeedLabel', 'trip.detail.max_kmh', speedLbl);

        // Timeline slider speed unit
        var sliderSpeedUnit = document.getElementById('sliderSpeedUnit');
        if (sliderSpeedUnit) sliderSpeedUnit.textContent = speedLbl;

        // Route map speed legend (thresholds + label)
        var lowEl = document.getElementById('routeSpeedLow');
        var midEl = document.getElementById('routeSpeedMid');
        var highEl = document.getElementById('routeSpeedHigh');
        var lowT = BYD.units.speedThreshold(40);
        var highT = BYD.units.speedThreshold(80);
        if (lowEl) lowEl.textContent = '<' + lowT + ' ' + speedLbl;
        if (midEl) midEl.textContent = lowT + '–' + highT + ' ' + speedLbl;
        if (highEl) highEl.textContent = '>' + highT + ' ' + speedLbl;

        // Speed-distribution histogram legend — keep the localized "Low/Normal/High"
        // prefix (everything before the threshold parens) and swap only the
        // numeric thresholds.
        this._setBucketLabel('speedDistLow', 'trip.speed_dist.low', '<' + lowT);
        this._setBucketLabel('speedDistNormal', 'trip.speed_dist.normal', lowT + '–' + highT);
        this._setBucketLabel('speedDistHigh', 'trip.speed_dist.high', '>' + highT);
    },

    /**
     * Replace the unit suffix on a "Avg km/h" / "Max km/h" style label.
     * Falls back to the i18n value if the element's current text doesn't
     * end in a recognized unit.
     */
    _setSpeedLabel: function(elementId, i18nKey, unitLabel) {
        var el = document.getElementById(elementId);
        if (!el) return;
        var base = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(i18nKey) : el.textContent;
        // Strip any trailing "km/h" / "mph" (case-insensitive, may have leading space)
        var stripped = base.replace(/\s*(km\/h|kmh|kph|mph)\s*$/i, '').trim();
        el.textContent = stripped + ' ' + unitLabel;
    },

    /**
     * Replace the threshold inside parens on a "Low (<40)" style label.
     */
    _setBucketLabel: function(elementId, i18nKey, threshold) {
        var el = document.getElementById(elementId);
        if (!el) return;
        var base = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(i18nKey) : el.textContent;
        // Replace whatever is inside the parens with our threshold
        if (/\(.*?\)/.test(base)) {
            el.textContent = base.replace(/\(.*?\)/, '(' + threshold + ')');
        } else {
            el.textContent = base + ' (' + threshold + ')';
        }
    },

    updateCurrencyIcons() {
        // Cost circle icons show the user's selected currency symbol
        const c = this.currency || '$';
        const icon1 = document.getElementById('costCircleIcon');
        const icon2 = document.getElementById('costCircleIconActive');
        if (icon1) icon1.textContent = c;
        if (icon2) icon2.textContent = c;
    },

    // ==================== STORAGE ====================

    async loadStorageSettings() {
        try {
            const resp = await fetch('/api/trips/storage');
            const data = await resp.json();
            this._applyStoragePayload(data);
        } catch (e) { console.warn('[Trips] Storage load failed:', e); }
    },

    /**
     * Apply a /api/trips/storage response to the storage card. Accepts the
     * raw handler payload ({@code {success, storage: {...}}}) or the
     * bootstrap-stripped slice ({@code {storage: {...}}}); both have a
     * top-level {@code storage} key.
     */
    _applyStoragePayload(data) {
        if (!data || !data.storage) return;
        const s = data.storage;
        this.storageMeta = s;  // cached for setStorageType / clamp logic
        const intBtn = document.getElementById('storageInternal');
        const sdBtn = document.getElementById('storageSdCard');
        const usbBtn = document.getElementById('storageUsb');
        if (intBtn) intBtn.classList.toggle('active', s.storageType === 'INTERNAL');
        if (sdBtn) sdBtn.classList.toggle('active', s.storageType === 'SD_CARD');
        if (usbBtn) usbBtn.classList.toggle('active', s.storageType === 'USB');
        this._paintVolumeAvailability(s);
        const slider = document.getElementById('storageLimitSlider');
        const sliderMax = this.tripsMaxFor(s.storageType, s);
        if (slider) {
            slider.max = sliderMax;
            slider.value = Math.min(s.limitMb || 500, sliderMax);
        }
        this.updateLimitLabel(s.limitMb || 500);
        this.renderStorageUsage(s.usedMb || 0, s.limitMb || 500, s.tripsCount || 0, s.usedUnit || 'MB');

        const pathEl = document.getElementById('tripStoragePath');
        if (pathEl && s.storagePath) {
            pathEl.textContent = s.storagePath;
        } else if (pathEl) {
            if (s.storageType === 'SD_CARD') pathEl.textContent = BYD.i18n.t('trip.sd_path_default');
            else if (s.storageType === 'USB') pathEl.textContent = BYD.i18n.t('trip.usb_path_default');
            else pathEl.textContent = BYD.i18n.t('trip.internal_path_default');
        }

        // CDR cleanup card is SD-only (BYD's built-in dashcam)
        const cdrCard = document.getElementById('tripCdrCleanupCard');
        if (cdrCard) cdrCard.style.display = s.storageType === 'SD_CARD' ? 'block' : 'none';
        if (s.storageType === 'SD_CARD') this.loadCdrInfo();

        this.pendingStorageType = null;
        this.pendingStorageLimit = null;
        this.resetApplyButton();
        // AFTER clearing pending*: the render inside updateLimitLabel above ran while
        // a stale pending volume was still set, so it evaluated fresh server limits
        // against the volume the user had picked but not applied. Re-render now that
        // the baseline is the server's truth, or the banner keeps naming a volume the
        // buttons no longer show as selected.
        this.updateBudgetBanner();
    },

    /**
     * Paint the SD/USB availability chrome: the two picker buttons' enabled
     * state plus the always-visible status rows (dot, label, free/total).
     *
     * Split out of _applyStoragePayload so the 10s poll can refresh it too.
     * It writes only display state and the `disabled` flag — never a slider
     * value, the Apply button, or the CDR sliders — so unlike the rest of the
     * storage card it needs no dirty-guard and can run under the user's hands.
     * That matters because the SD button starts disabled when the card isn't
     * detected, and a disabled button cannot POST the type change whose mount
     * attempt would make it available: without a poll-driven repaint, a card
     * inserted after page load stayed unselectable until a manual reload.
     */
    _paintVolumeAvailability(s) {
        if (!s) return;
        const sdBtn = document.getElementById('storageSdCard');
        const usbBtn = document.getElementById('storageUsb');
        if (sdBtn) {
            sdBtn.disabled = !s.sdCardAvailable;
            sdBtn.title = s.sdCardAvailable ? '' : BYD.i18n.t('recording.sd_card_unavailable');
        }
        if (usbBtn) {
            usbBtn.disabled = !s.usbAvailable;
            usbBtn.title = s.usbAvailable ? '' : BYD.i18n.t('recording.usb_unavailable');
        }

        // SD card status row — always visible so the user sees the
        // alternative volume's status (matches recording/surveillance
        // pages). Online/offline dot reflects availability regardless
        // of which type is currently selected.
        const sdStatus = document.getElementById('tripSdCardStatus');
        const sdDot = document.getElementById('tripSdStatusDot');
        const sdText = document.getElementById('tripSdStatusText');
        const sdSpaceInfo = document.getElementById('tripSdSpaceInfo');
        const sdFree = document.getElementById('tripSdFree');
        const sdTotal = document.getElementById('tripSdTotal');
        if (sdStatus) {
            sdStatus.style.display = 'block';
            if (s.sdCardAvailable) {
                if (sdDot) { sdDot.classList.add('online'); sdDot.classList.remove('offline'); }
                if (sdText) sdText.textContent = BYD.i18n.t('trip.sd_card_available');
                if (sdSpaceInfo && s.sdCardTotalSpace) {
                    sdSpaceInfo.style.display = 'block';
                    if (sdFree) sdFree.textContent = this.formatBytes(s.sdCardFreeSpace) + ' free';
                    if (sdTotal) sdTotal.textContent = this.formatBytes(s.sdCardTotalSpace) + ' total';
                }
            } else {
                if (sdDot) { sdDot.classList.add('offline'); sdDot.classList.remove('online'); }
                if (sdText) sdText.textContent = BYD.i18n.t('trip.sd_card_not_detected');
                if (sdSpaceInfo) sdSpaceInfo.style.display = 'none';
            }
        }

        // USB status row — always visible (see comment above).
        const usbStatus = document.getElementById('tripUsbStatus');
        const usbDot = document.getElementById('tripUsbStatusDot');
        const usbText = document.getElementById('tripUsbStatusText');
        const usbSpaceInfo = document.getElementById('tripUsbSpaceInfo');
        const usbFree = document.getElementById('tripUsbFree');
        const usbTotal = document.getElementById('tripUsbTotal');
        if (usbStatus) {
            usbStatus.style.display = 'block';
            if (s.usbAvailable) {
                if (usbDot) { usbDot.classList.add('online'); usbDot.classList.remove('offline'); }
                if (usbText) usbText.textContent = BYD.i18n.t('recording.usb_available');
                if (usbSpaceInfo && s.usbTotalSpace) {
                    usbSpaceInfo.style.display = 'block';
                    if (usbFree) usbFree.textContent = this.formatBytes(s.usbFreeSpace) + ' free';
                    if (usbTotal) usbTotal.textContent = this.formatBytes(s.usbTotalSpace) + ' total';
                }
            } else {
                if (usbDot) { usbDot.classList.add('offline'); usbDot.classList.remove('online'); }
                if (usbText) usbText.textContent = BYD.i18n.t('recording.usb_not_detected');
                if (usbSpaceInfo) usbSpaceInfo.style.display = 'none';
            }
        }
    },

    /**
     * Resolve the slider max for a given storage type from the cached
     * /api/trips/storage payload. Accepts both the trips-historical
     * `maxLimitMbInternal` and the recording/surveillance-aligned
     * `maxLimitMb` (server emits both). Falls back to 100GB if neither
     * is reported (older server).
     */
    tripsMaxFor(type, meta) {
        if (!meta) return 100000;
        switch (type) {
            case 'SD_CARD': return meta.maxLimitMbSdCard   || 100000;
            case 'USB':     return meta.maxLimitMbUsb      || 100000;
            default:        return meta.maxLimitMbInternal || meta.maxLimitMb || 100000;
        }
    },

    formatBytes(bytes) {
        if (!bytes) return '0 B';
        if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
        if (bytes >= 1e6) return (bytes / 1e6).toFixed(1) + ' MB';
        if (bytes >= 1e3) return (bytes / 1e3).toFixed(1) + ' KB';
        return bytes + ' B';
    },

    setStorageType(type) {
        const meta = this.storageMeta || {};
        if (type === 'SD_CARD' && !meta.sdCardAvailable) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.sd_card_unavailable'), 'error');
            return;
        }
        if (type === 'USB' && !meta.usbAvailable) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('recording.usb_unavailable'), 'error');
            return;
        }
        this.pendingStorageType = type;
        const intBtn = document.getElementById('storageInternal');
        const sdBtn = document.getElementById('storageSdCard');
        const usbBtn = document.getElementById('storageUsb');
        if (intBtn) intBtn.classList.toggle('active', type === 'INTERNAL');
        if (sdBtn) sdBtn.classList.toggle('active', type === 'SD_CARD');
        if (usbBtn) usbBtn.classList.toggle('active', type === 'USB');

        // Re-clamp slider to new volume's max
        const slider = document.getElementById('storageLimitSlider');
        const newMax = this.tripsMaxFor(type, meta);
        if (slider) {
            slider.max = newMax;
            const cur = parseInt(slider.value, 10);
            if (cur > newMax) {
                slider.value = newMax;
                this.pendingStorageLimit = newMax;
                this.updateLimitLabel(newMax);
            }
        }

        const cdrCard = document.getElementById('tripCdrCleanupCard');
        if (cdrCard) cdrCard.style.display = type === 'SD_CARD' ? 'block' : 'none';
        // The destination volume changed, so re-evaluate against ITS capacity.
        this.updateBudgetBanner();
        this.showApplyNeeded();
    },

    setStorageLimit(limitMb) {
        this.pendingStorageLimit = parseInt(limitMb);
        this.updateLimitLabel(limitMb);
        this.showApplyNeeded();
    },

    updateLimitLabel(val) {
        // #storageLimitValue only. A second #storageLimitDesc sink was dropped:
        // the adjacent desc line is a translated sentence, and writing the raw
        // "500 MB" label into it would clobber that copy.
        const el = document.getElementById('storageLimitValue');
        const v = parseInt(val);
        const label = v >= 1000 ? (v / 1000) + ' GB' : v + ' MB';
        if (el) el.textContent = label;
        // Hooked here rather than in setStorageLimit: the slider fires oninput →
        // updateLimitLabel on every drag frame but onchange → setStorageLimit only
        // on release, and the advisory should track the drag. Safe to call from the
        // programmatic paths too — the background refresh only swaps storageBudget and
        // never writes a control, so there is no drag state to protect.
        this.updateBudgetBanner(v);
    },

    /**
     * Render the combined-limit advisory. `pendingMb` defaults to the pending or
     * saved limit so callers that aren't mid-drag get the right baseline; the
     * storage-type pick is passed so switching volumes retargets the warning.
     */
    updateBudgetBanner(pendingMb) {
        if (!BYD.storageBudget) return;
        const meta = this.storageMeta || {};
        // Baseline, most-live first. The SLIDER's DOM value is the authority when no
        // explicit value was passed: the slider only assigns pendingStorageLimit on
        // release (onchange), so during a drag both pendingStorageLimit and
        // meta.limitMb still hold the OLD number — a background refresh firing then
        // would re-render against it and hide the very warning the user is reading
        // with the slider still parked past the limit. recording/surveillance don't
        // need this because their oninput handler commits to this.config first.
        let mb = pendingMb;
        if (mb == null) {
            const slider = document.getElementById('storageLimitSlider');
            const live = slider ? parseInt(slider.value, 10) : NaN;
            mb = !isNaN(live) ? live
               : (this.pendingStorageLimit != null ? this.pendingStorageLimit : meta.limitMb);
        }
        // ONLY a genuine unsaved pick counts as pending. Passing meta.storageType here
        // made every render look like a volume switch whenever storageMeta was stale
        // relative to storageBudget (the background refresh updates only the budget):
        // render() would compare the stale type against the fresh grouping, conclude
        // the category had moved, and warn about the volume it just left.
        const type = this.pendingStorageType || null;
        BYD.storageBudget.render('tripBudgetBanner', meta.storageBudget, 'trips', mb, type);
    },

    showApplyNeeded() {
        const btn = document.getElementById('storageApplyBtn');
        if (btn) { btn.disabled = false; btn.textContent = BYD.i18n.t('trip.apply_changes'); }
    },

    resetApplyButton() {
        const btn = document.getElementById('storageApplyBtn');
        if (btn) { btn.disabled = true; btn.textContent = BYD.i18n.t('trip.apply_changes'); }
    },

    async applyStorageSettings() {
        const btn = document.getElementById('storageApplyBtn');
        const body = {};
        if (this.pendingStorageType !== null) body.storageType = this.pendingStorageType;
        if (this.pendingStorageLimit !== null) body.storageLimitMb = this.pendingStorageLimit;

        if (btn) { btn.disabled = true; btn.textContent = BYD.i18n.t('trip.applying'); }

        let rejected = null;
        try {
            // Save storage settings
            if (Object.keys(body).length > 0) {
                const resp = await fetch('/api/trips/storage', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                // Surface rejections so the user sees when SD/USB wasn't
                // available (server keeps the prior storage type) instead of
                // the prior silent-success behavior. The server now echoes
                // appliedType/appliedLimitMb so loadStorageSettings can
                // refresh the UI to the actual committed state.
                try {
                    const data = await resp.clone().json();
                    if (data && data.rejected && data.rejected.length) {
                        rejected = data.rejected;
                    }
                } catch (e) { /* response parse non-fatal */ }
            }

            // Save cost config
            await this.saveCostConfig();

            this.pendingStorageType = null;
            this.pendingStorageLimit = null;
            if (btn) {
                btn.textContent = rejected ? BYD.i18n.t('trip.apply_changes') : BYD.i18n.t('trip.applied_check');
                setTimeout(() => { btn.textContent = BYD.i18n.t('trip.apply_changes'); btn.disabled = true; }, 2000);
            }
            // Re-sync the UI to the actual committed state. With rejections
            // the UI may have shown the user picking SD_CARD but the server
            // kept INTERNAL — loadStorageSettings re-renders the toggle to
            // match the truth.
            await this.loadStorageSettings();

            if (rejected && BYD.utils && BYD.utils.toast) {
                const fields = rejected.map(function (r) { return r.field; }).join(', ');
                BYD.utils.toast('Some values rejected: ' + fields, 'warn');
            }
        } catch (e) {
            console.warn('[Trips] Apply storage failed:', e);
            if (btn) { btn.disabled = false; btn.textContent = BYD.i18n.t('trip.apply_changes'); }
        }
    },

    // ==================== CDR CLEANUP ====================

    async toggleCdrCleanup() {
        const el = document.getElementById('tripCdrEnabled');
        const enabled = el ? el.checked : false;
        try {
            await fetch('/api/storage/external/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: enabled })
            });
            const badge = document.getElementById('tripCdrBadge');
            if (badge) {
                badge.textContent = enabled ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
                badge.className = 'status-badge ' + (enabled ? 'active' : 'inactive');
            }
        } catch (e) { console.warn('[Trips] CDR toggle failed:', e); }
    },

    updateCdrReserved(val) {
        const el = document.getElementById('tripCdrReservedValue');
        if (el) el.textContent = (val / 1000).toFixed(1) + ' GB';
    },

    updateCdrProtected(val) {
        const el = document.getElementById('tripCdrProtectedValue');
        if (el) el.textContent = val + 'h';
    },

    updateCdrMinKeep(val) {
        const el = document.getElementById('tripCdrMinKeepValue');
        if (el) el.textContent = val;
    },

    async triggerCdrCleanup() {
        if (!confirm(BYD.i18n.t('trip.cdr.delete_confirm'))) return;
        try {
            const resp = await fetch('/api/storage/external/cleanup', { method: 'POST' });
            const data = await resp.json();
            if (data.success) {
                this.setEl('tripCdrTotalFreed', data.freedFormatted || '--');
                this.setEl('tripCdrTotalDeleted', (data.deletedCount || 0) + ' files');
                this.loadCdrInfo();
            }
        } catch (e) { console.warn('[Trips] CDR cleanup failed:', e); }
    },

    // Rebuild trip rows from telemetry files still on disk (history lost when
    // the storage volume was briefly undetected). User-triggered only. The scan
    // runs server-side on a background thread (can take minutes on a large SD),
    // so we START it then POLL /api/trips/recover/status instead of holding one
    // long request open (which would tie up an HTTP pool thread + hang the UI).
    async recoverTrips() {
        const btn = document.getElementById('tripRecoverBtn');
        const out = document.getElementById('tripRecoverResult');
        const t = (k, fb) => (window.BYD && BYD.i18n && BYD.i18n.t(k) && BYD.i18n.t(k) !== k) ? BYD.i18n.t(k) : fb;
        if (btn) { btn.disabled = true; btn.textContent = t('trip.recover.scanning', 'Scanning…'); }
        if (out) out.style.display = 'none';

        const finish = (data) => {
            const msg = (data && data.message) || (data && data.success
                ? t('trip.recover.done', 'Recovery complete.')
                : t('trip.recover.failed', 'Recovery failed.'));
            if (out) { out.textContent = msg; out.style.display = 'block'; }
            if (window.BYD && BYD.utils && BYD.utils.toast) {
                BYD.utils.toast(msg, (data && data.success) ? (data.recovered > 0 ? 'success' : 'info') : 'error');
            }
            if (data && data.success && data.recovered > 0) {
                // Recovered trips are OLD history, so widen the window + switch
                // to the Trips tab so they're actually visible.
                const RECOVER_WINDOW_DAYS = 3650;
                this.currentDays = RECOVER_WINDOW_DAYS;
                this.currentOffset = 0;
                this.currentCursor = null;
                this.rangeFromMs = null;
                this.rangeToMs = null;
                this._invalidateSummaryRequests();
                const rangeRow = document.getElementById('tripRangeRow');
                if (rangeRow) rangeRow.classList.remove('open');
                try { document.querySelectorAll('.filter-tab').forEach(el => el.classList.remove('active')); } catch (e) {}
                Promise.resolve()
                    .then(() => this.loadTrips(RECOVER_WINDOW_DAYS, 0)).catch(() => {})
                    .then(() => this.loadSummary && this.loadSummary(RECOVER_WINDOW_DAYS)).catch(() => {})
                    .then(() => this.loadStorageSettings()).catch(() => {})
                    .then(() => {
                        try {
                            const tripsTabBtn = document.querySelector('[data-tab-target="trips"]');
                            if (tripsTabBtn) tripsTabBtn.click();
                        } catch (e) {}
                    });
            }
            if (btn) { btn.disabled = false; btn.textContent = t('trip.recover.button', 'Scan & Recover Trips'); }
        };

        const poll = async (attempt) => {
            // Up to ~10 min (200 * 3s) — generous for a large FUSE-bridged SD.
            if (attempt > 200) { finish({ success: false, message: t('trip.recover.timeout', 'Recovery is taking too long. Check the Trips tab shortly.') }); return; }
            try {
                const r = await fetch('/api/trips/recover/status');
                const s = await r.json();
                if (s && s.done) { finish(s); return; }
                setTimeout(() => poll(attempt + 1), 3000);
            } catch (e) {
                setTimeout(() => poll(attempt + 1), 3000);   // transient — keep polling
            }
        };

        try {
            const resp = await fetch('/api/trips/recover', { method: 'POST' });
            const data = await resp.json();
            if (!resp.ok || (data && data.error)) {
                finish({ success: false, message: (data && (data.message || data.error)) || t('trip.recover.failed', 'Recovery failed.') });
                return;
            }
            if (data && data.started === false && data.running) {
                // Another run is already going — just start polling it.
                poll(0);
                return;
            }
            poll(0);
        } catch (e) {
            console.warn('[Trips] recover start failed:', e);
            finish({ success: false, message: t('trip.recover.failed', 'Recovery failed.') });
        }
    },

    async loadCdrInfo() {
        try {
            const resp = await fetch('/api/storage/external');
            const data = await resp.json();
            if (!data.success) return;

            // SD status row (tripSdCardStatus/tripSdStatusDot/tripSdStatusText/
            // tripSdSpaceInfo) is owned by _paintVolumeAvailability() — reached
            // from both the initial load and the 10s poll — and reads from
            // /api/trips/storage → StorageManager.isSdCardAvailable(). Don't
            // paint it here: ExternalStorageCleaner keeps its own cached
            // sdCardAvailable flag and on some firmwares diverges from
            // StorageManager (the cleaner won't refresh once cached true,
            // even after StorageManager remounts the volume), which would
            // flip the row to "SD Card: Not detected" right after Apply.

            // CDR info
            this.setEl('tripCdrPath', data.cdrPath || '--');
            this.setEl('tripCdrUsage', data.cdrUsageFormatted || '--');
            this.setEl('tripCdrFileCount', data.cdrFileCount || '--');
            this.setEl('tripCdrProtected', data.cdrProtectedFormatted || '--');
            this.setEl('tripCdrDeletable', data.cdrDeletableFormatted || '--');
            this.setEl('tripCdrTotalFreed', data.totalBytesFreedFormatted || '--');
            this.setEl('tripCdrTotalDeleted', data.totalFilesDeleted || '--');

            // Background monitor + last cleanup + recommend banner
            const monEl = document.getElementById('tripCdrMonitoring');
            if (monEl) {
                if (!data.cleanupEnabled) {
                    monEl.textContent = BYD.i18n.t('common.disabled');
                    monEl.style.color = '';
                } else if (data.monitoringActive) {
                    monEl.textContent = BYD.i18n.t('common.running');
                    monEl.style.color = '#22c55e';
                } else {
                    monEl.textContent = BYD.i18n.t('common.idle');
                    monEl.style.color = '#94a3b8';
                }
            }

            const lastEl = document.getElementById('tripCdrLastCleanup');
            if (lastEl) lastEl.textContent = this._formatCdrRelativeTime(data.lastCleanupTime || 0);

            const banner = document.getElementById('tripCdrRecommendBanner');
            if (banner) banner.style.display = data.recommendAutoCleanup ? 'block' : 'none';

            // Config
            const cdrEnabled = document.getElementById('tripCdrEnabled');
            const cdrBadge = document.getElementById('tripCdrBadge');
            if (cdrEnabled) cdrEnabled.checked = data.cleanupEnabled || false;
            if (cdrBadge) {
                cdrBadge.textContent = data.cleanupEnabled ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
                cdrBadge.className = 'status-badge ' + (data.cleanupEnabled ? 'active' : 'inactive');
            }
            if (data.reservedSpaceMb) {
                const rs = document.getElementById('tripCdrReservedSlider');
                if (rs) rs.value = data.reservedSpaceMb;
                this.setEl('tripCdrReservedValue', (data.reservedSpaceMb / 1000).toFixed(1) + ' GB');
            }
            if (data.protectedHours !== undefined) {
                const ps = document.getElementById('tripCdrProtectedSlider');
                if (ps) ps.value = data.protectedHours;
                this.setEl('tripCdrProtectedValue', data.protectedHours + 'h');
            }
            if (data.minFilesKeep !== undefined) {
                const ms = document.getElementById('tripCdrMinKeepSlider');
                if (ms) ms.value = data.minFilesKeep;
                this.setEl('tripCdrMinKeepValue', data.minFilesKeep);
            }
        } catch (e) { /* CDR info not critical */ }
    },

    // Custom date-range state (epoch-ms). When rangeFromMs != null the trip
    // list + period summary query by [from,to] instead of currentDays.
    // Mirrors the charging-page picker (From → To pills + Apply + shared
    // calendar popup) so the two pages share one interaction model.
    rangeFromMs: null,
    rangeToMs: null,
    // Calendar popup working state: which field ('from'|'to') is being picked,
    // the visible month, and the picked endpoints as "YYYY-MM-DD" local keys.
    _calTarget: null,
    _calMonth: null,
    _calFromKey: null,
    _calToKey: null,

    filterByDays(days) {
        document.querySelectorAll('#tripFilters .filter-tab').forEach(btn => {
            btn.classList.toggle('active', parseInt(btn.dataset.days) === days);
        });
        this.currentDays = days;
        this.currentOffset = 0;
        this.currentCursor = null;
        this.trips = [];
        this.rangeFromMs = null;   // leaving custom-range mode
        this.rangeToMs = null;
        const row = document.getElementById('tripRangeRow');
        if (row) row.classList.remove('open');
        const btn = document.getElementById('loadMoreBtn');
        if (btn) btn.style.display = 'none';
        this.loadTrips(days, 0);
        this.loadSummary(days);
    },

    quickFilter(days, btn) {
        document.querySelectorAll('#tripFilters .filter-tab').forEach(b => b.classList.remove('active'));
        if (btn) btn.classList.add('active');
        this.filterByDays(days);
    },

    // Reveal/hide the custom From → To range row (height+fade via .open).
    // Seeds a sensible default span (last ~30 days) the first time.
    toggleCustomRange(btn) {
        const row = document.getElementById('tripRangeRow');
        if (!row) return;
        if (row.classList.contains('open')) { row.classList.remove('open'); return; }
        row.classList.add('open');
        if (this._calFromKey == null) this._calFromKey = this._dateKey(new Date(Date.now() - 30 * 86400000));
        if (this._calToKey == null) this._calToKey = this._dateKey(new Date());
        this._updateRangeButtons();
        document.querySelectorAll('#tripFilters .filter-tab').forEach(b => b.classList.remove('active'));
        if (btn) btn.classList.add('active');
    },

    // Apply the picked From/To range (From = start of day, To = end of day
    // inclusive). Either side may be unset → open-ended.
    applyCustomRange() {
        const fromMs = this._calFromKey ? this._keyToMs(this._calFromKey, false) : null;
        const toMs = this._calToKey ? this._keyToMs(this._calToKey, true) : null;
        if (fromMs == null && toMs == null) return;
        if (fromMs != null && toMs != null && fromMs > toMs) return;
        this.rangeFromMs = fromMs != null ? fromMs : 0;
        this.rangeToMs = toMs;   // null = open-ended (daemon treats as no upper bound)
        this.currentOffset = 0;
        this.currentCursor = null;
        this.trips = [];
        this._invalidateSummaryRequests();
        this.renderTripList([]);
        const empty = document.getElementById('tripEmptyState');
        if (empty) empty.style.display = 'none';
        // No loadSummary() here: the weekly/monthly rollups are keyed to
        // calendar periods, not arbitrary ranges, and would describe the wrong
        // window. updatePeriodSummary() (invoked from renderTripList off the
        // loaded range trips) is the correct, range-accurate source instead.
        this.loadTripsBetween(this.rangeFromMs, this.rangeToMs, 0);
    },

    // Fetch one page of the active custom range. New servers use the stable
    // cursor; offset remains in the request so an older daemon can still page.
    async loadTripsBetween(fromMs, toMs, offset, cursor) {
        const off = offset || 0;
        const pageCursor = typeof cursor === 'string' && cursor ? cursor : null;
        const isLoadMore = pageCursor != null || off > 0;
        if (isLoadMore && this._listLoadMoreInFlight) return;
        const key = this._rangeListKey(fromMs, toMs);
        if (isLoadMore && this._activeListKey !== key) return;
        if (!isLoadMore) this.currentCursor = null;

        const request = this._beginListRequest(
            key, off, isLoadMore, true, pageCursor);
        try {
            let q = '/api/trips?from=' + fromMs;
            if (toMs != null) q += '&to=' + toMs;
            q += '&limit=' + this.pageSize + '&offset=' + off;
            if (pageCursor != null) q += '&cursor=' + pageCursor;
            const resp = request.controller
                ? await fetch(q, { signal: request.controller.signal })
                : await fetch(q);
            const data = await resp.json();
            if (!this._isCurrentListRequest(request)) return;
            this._applyTripsPayload(data, off, request);
        } catch (e) {
            if (!this._isCurrentListRequest(request) || this._isAbortError(e)) return;
            console.warn('[Trips] Load trips for range failed:', e);
            const skel = document.getElementById('tripListSkeleton');
            if (skel) skel.style.display = 'none';
            const empty = document.getElementById('tripEmptyState');
            if (empty) empty.style.display = 'flex';
        } finally {
            this._finishListRequest(request);
        }
    },

    // ---- Shared calendar (range picker) — mirrors charging.js -------------

    // Open the calendar to pick the 'from' or 'to' endpoint.
    openCalendar(which) {
        this._calTarget = which;   // 'from' | 'to'
        const seed = (which === 'to' ? this._calToKey : this._calFromKey);
        this._calMonth = seed ? new Date(seed + 'T00:00:00') : new Date();
        this._calMonth.setDate(1);
        this.renderCalendar();
        const pop = document.getElementById('calendarPopup');
        if (pop) pop.classList.add('active');
    },

    closeCalendar() {
        const pop = document.getElementById('calendarPopup');
        if (pop) pop.classList.remove('active');
    },

    prevMonth() { this._calMonth.setMonth(this._calMonth.getMonth() - 1); this.renderCalendar(); },
    nextMonth() { this._calMonth.setMonth(this._calMonth.getMonth() + 1); this.renderCalendar(); },

    renderCalendar() {
        const grid = document.getElementById('calendarGrid');
        const title = document.getElementById('calendarTitle');
        if (!grid || !this._calMonth) return;
        const lang = BYD.i18n.getLang();
        const year = this._calMonth.getFullYear(), month = this._calMonth.getMonth();
        const monthDate = new Date(year, month, 1);
        try { title.textContent = new Intl.DateTimeFormat(lang, { month: 'long' }).format(monthDate) + ' ' + year; }
        catch (e) { title.textContent = monthDate.toLocaleDateString(lang, { month: 'long' }) + ' ' + year; }

        grid.innerHTML = '';
        let wkFmt; try { wkFmt = new Intl.DateTimeFormat(lang, { weekday: 'short' }); } catch (e) { wkFmt = null; }
        for (let w = 0; w < 7; w++) {
            const dd = new Date(2024, 0, 7 + w);   // 2024-01-07 is a Sunday
            const el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = wkFmt ? wkFmt.format(dd) : dd.toLocaleDateString(lang, { weekday: 'short' });
            grid.appendChild(el);
        }

        const firstDay = new Date(year, month, 1).getDay();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        const daysInPrev = new Date(year, month, 0).getDate();
        const todayKey = this._dateKey(new Date());
        for (let i = firstDay - 1; i >= 0; i--) this._calDayCell(grid, daysInPrev - i, this._dateKey(new Date(year, month - 1, daysInPrev - i)), true, todayKey);
        for (let day = 1; day <= daysInMonth; day++) this._calDayCell(grid, day, this._dateKey(new Date(year, month, day)), false, todayKey);
        for (let d2 = 1; grid.children.length - 7 + d2 <= 42; d2++) this._calDayCell(grid, d2, this._dateKey(new Date(year, month + 1, d2)), true, todayKey);

        // Overlay dots on days that have trips (trips-specific enhancement).
        this.loadCalendarDots();
    },

    _calDayCell(grid, day, dateKey, otherMonth, todayKey) {
        const self = this;
        const el = document.createElement('div');
        el.className = 'calendar-day';
        el.textContent = day;
        el.dataset.date = dateKey;
        if (otherMonth) el.classList.add('other-month');
        if (dateKey === todayKey) el.classList.add('today');
        if (dateKey === this._calFromKey || dateKey === this._calToKey) el.classList.add('selected');
        else if (this._calFromKey && this._calToKey && dateKey > this._calFromKey && dateKey < this._calToKey) el.classList.add('in-range');
        // Disable future dates.
        const today = new Date(); today.setHours(0, 0, 0, 0);
        if (new Date(dateKey + 'T00:00:00') > today) el.classList.add('disabled');
        else el.addEventListener('click', function () { self._calPick(dateKey); });
        grid.appendChild(el);
    },

    _calPick(dateKey) {
        if (this._calTarget === 'to') {
            this._calToKey = dateKey;
            // Keep order sane: if To precedes From, pull From back.
            if (this._calFromKey && this._calToKey < this._calFromKey) this._calFromKey = dateKey;
        } else {
            this._calFromKey = dateKey;
            if (this._calToKey && this._calFromKey > this._calToKey) this._calToKey = dateKey;
        }
        this._updateRangeButtons();
        this.closeCalendar();
    },

    _updateRangeButtons() {
        const lang = BYD.i18n.getLang();
        const fromTxt = document.getElementById('tripFromText');
        const toTxt = document.getElementById('tripToText');
        const fmt = (key) => {
            try { return new Date(key + 'T00:00:00').toLocaleDateString(lang, { month: 'short', day: 'numeric', year: 'numeric' }); }
            catch (e) { return key; }
        };
        const fromLabel = BYD.i18n.t('trip.daterange.from');
        const toLabel = BYD.i18n.t('trip.daterange.to');
        if (fromTxt) fromTxt.textContent = this._calFromKey ? (fromLabel + ': ' + fmt(this._calFromKey)) : fromLabel;
        if (toTxt) toTxt.textContent = this._calToKey ? (toLabel + ': ' + fmt(this._calToKey)) : toLabel;
    },

    // Overlay "has-trips" dots on the currently rendered month (best-effort).
    async loadCalendarDots() {
        if (!this._calMonth) return;
        try {
            const year = this._calMonth.getFullYear(), month = this._calMonth.getMonth();
            const startOfMonth = new Date(year, month, 1);
            const days = Math.ceil((Date.now() - startOfMonth) / 86400000) + 1;
            if (days <= 0) return;
            const resp = await fetch('/api/trips?days=' + days + '&limit=300');
            const data = await resp.json();
            if (data.success && data.trips) {
                const tripDays = new Set();
                data.trips.forEach(t => {
                    const d = new Date(t.startTime || t.start_time);
                    if (d.getMonth() === month && d.getFullYear() === year) tripDays.add(this._dateKey(d));
                });
                document.querySelectorAll('#calendarGrid .calendar-day').forEach(el => {
                    if (tripDays.has(el.dataset.date)) el.classList.add('has-trips');
                });
            }
        } catch (e) { /* silent — dots are cosmetic */ }
    },

    // "YYYY-MM-DD" local date key.
    _dateKey(d) {
        const m = d.getMonth() + 1, day = d.getDate();
        return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
    },
    // date key → epoch-ms at local 00:00 (or 23:59:59.999 when endOfDay).
    _keyToMs(key, endOfDay) {
        const p = key.split('-');
        if (p.length !== 3) return null;
        const y = parseInt(p[0], 10), mo = parseInt(p[1], 10) - 1, da = parseInt(p[2], 10);
        if (isNaN(y) || isNaN(mo) || isNaN(da)) return null;
        return (endOfDay ? new Date(y, mo, da, 23, 59, 59, 999) : new Date(y, mo, da, 0, 0, 0, 0)).getTime();
    },

    renderStorageUsage(usedMb, limitMb, count, unit) {
        const fill = document.getElementById('storageUsageFill');
        const usedText = document.getElementById('storageUsedText');
        const countText = document.getElementById('storageTripsCount');
        const u = unit || 'MB';

        if (fill) {
            const pct = u === 'KB' ? ((usedMb / 1024) / limitMb * 100) : (usedMb / limitMb * 100);
            fill.style.width = Math.min(100, Math.max(pct, count > 0 ? 0.5 : 0)) + '%';
        }

        if (usedText) {
            if (u === 'KB') {
                usedText.textContent = usedMb + ' KB / ' + limitMb + ' MB';
            } else if (usedMb === 0 && count > 0) {
                usedText.textContent = '< 1 MB / ' + limitMb + ' MB';
            } else {
                usedText.textContent = usedMb.toFixed(1) + ' MB / ' + limitMb + ' MB';
            }
        }

        if (countText) countText.textContent = BYD.i18n.plural('trip.stored', count);
    },

    // ==================== TRIP LIST ====================

    _daysListKey(days) {
        return 'days:' + String(days);
    },

    _rangeListKey(fromMs, toMs) {
        return 'range:' + String(fromMs) + ':' + (toMs == null ? '' : String(toMs));
    },

    _newAbortController() {
        try {
            return typeof AbortController !== 'undefined' ? new AbortController() : null;
        } catch (e) {
            return null;
        }
    },

    _isAbortError(error) {
        return !!(error && error.name === 'AbortError');
    },

    /**
     * Start a list request and invalidate every older page/filter response.
     * AbortController is optional because the head-unit's legacy WebView does
     * not expose it; the generation/key check remains the correctness guard.
     */
    _beginListRequest(key, offset, loadMore, cancellable, cursor) {
        if (this._listAbortController) {
            try { this._listAbortController.abort(); } catch (e) {}
        }

        const controller = cancellable === false ? null : this._newAbortController();
        const request = {
            sequence: ++this._listRequestSequence,
            key: key,
            offset: offset || 0,
            cursor: cursor || null,
            loadMore: !!loadMore,
            controller: controller
        };
        this._activeListKey = key;
        this._listAbortController = controller;
        this._listLoadMoreInFlight = request.loadMore;

        const btn = document.getElementById('loadMoreBtn');
        if (btn) btn.disabled = request.loadMore;
        return request;
    },

    _isCurrentListRequest(request) {
        return !!request
            && request.sequence === this._listRequestSequence
            && request.key === this._activeListKey;
    },

    _finishListRequest(request) {
        if (!this._isCurrentListRequest(request)) return;
        if (this._listAbortController === request.controller) {
            this._listAbortController = null;
        }
        this._listLoadMoreInFlight = false;
        const btn = document.getElementById('loadMoreBtn');
        if (btn) btn.disabled = false;
    },

    _invalidateListRequests() {
        if (this._listAbortController) {
            try { this._listAbortController.abort(); } catch (e) {}
        }
        this._listRequestSequence++;
        this._listAbortController = null;
        this._listLoadMoreInFlight = false;
        const btn = document.getElementById('loadMoreBtn');
        if (btn) btn.disabled = false;
    },

    _tripIdKey(trip) {
        if (!trip || trip.id == null) return null;
        const raw = String(trip.id).trim();
        if (!raw) return null;
        const numeric = Number(raw);
        return isFinite(numeric) ? 'n:' + String(numeric) : 's:' + raw;
    },

    _mergeTripsById(existing, incoming, reset) {
        const merged = [];
        const seen = Object.create(null);
        const append = (trip) => {
            const key = this._tripIdKey(trip);
            // Preserve legacy/malformed rows without IDs; there is no stable
            // identity by which they can safely be collapsed.
            if (key == null || !seen[key]) {
                merged.push(trip);
                if (key != null) seen[key] = true;
            }
        };
        if (!reset && existing) existing.forEach(append);
        if (incoming) incoming.forEach(append);
        return merged;
    },

    async loadTrips(days, offset, cursor) {
        const requestedDays = days == null ? this.currentDays : days;
        const off = offset || 0;
        const pageCursor = typeof cursor === 'string' && cursor ? cursor : null;
        const isLoadMore = pageCursor != null || off > 0;
        if (isLoadMore && this._listLoadMoreInFlight) return;
        const key = this._daysListKey(requestedDays);
        if (isLoadMore && this._activeListKey !== key) return;
        if (!isLoadMore) this.currentCursor = null;

        const request = this._beginListRequest(
            key, off, isLoadMore, true, pageCursor);
        try {
            let url = '/api/trips?days=' + requestedDays
                + '&limit=' + this.pageSize
                + '&offset=' + off;
            if (pageCursor != null) url += '&cursor=' + pageCursor;
            const resp = request.controller
                ? await fetch(url, { signal: request.controller.signal })
                : await fetch(url);
            const data = await resp.json();
            if (!this._isCurrentListRequest(request)) return;
            this._applyTripsPayload(data, off, request);
        } catch (e) {
            if (!this._isCurrentListRequest(request) || this._isAbortError(e)) return;
            console.warn('[Trips] Load trips failed:', e);
            const skel = document.getElementById('tripListSkeleton');
            if (skel) skel.style.display = 'none';
        } finally {
            this._finishListRequest(request);
        }
    },

    /**
     * Apply a /api/trips list response. Cursor-aware requests use the request's
     * loadMore flag; offset remains the fallback consumed-row count for older
     * servers.
     */
    _applyTripsPayload(data, offset, request) {
        if (request && !this._isCurrentListRequest(request)) return false;
        const off = offset || 0;
        const skel = document.getElementById('tripListSkeleton');
        if (skel) skel.style.display = 'none';

        const btn = document.getElementById('loadMoreBtn');
        const empty = document.getElementById('tripEmptyState');
        if (!data || data.error || !Array.isArray(data.trips)) {
            console.warn('[Trips] Trip list response failed:',
                data && data.error ? data.error : 'invalid response');
            return false;
        }
        const page = data.trips;
        const reset = request ? !request.loadMore : off === 0;
        const hasMore = typeof data.hasMore === 'boolean'
            ? data.hasMore : page.length >= this.pageSize;
        this.currentCursor = typeof data.nextCursor === 'string'
            && data.nextCursor ? data.nextCursor : null;

        if (page.length > 0) {
            this.trips = this._mergeTripsById(this.trips, page, reset);
            // The server offset tracks consumed rows, not rendered unique
            // cards. Otherwise an overlapping page makes the next request
            // repeat rows forever after client-side deduplication.
            this.currentOffset = off + page.length;
            this.renderTripList(this.trips);
            if (btn) btn.style.display = hasMore ? 'block' : 'none';
            if (empty) empty.style.display = 'none';
        } else if (reset) {
            // No trips for this period — clear the list and show empty state.
            this.trips = [];
            this.currentOffset = 0;
            this.currentCursor = null;
            this.renderTripList([]);
            if (empty) empty.style.display = 'flex';
            if (btn) btn.style.display = 'none';
        } else {
            // Paginating past end-of-data (data.success but no more rows).
            // Don't touch this.trips; just hide the button.
            this.currentCursor = null;
            if (btn) btn.style.display = 'none';
        }
        return true;
    },

    loadMore() {
        if (this._listLoadMoreInFlight) return;
        // Paginate through the same time window — don't widen `currentDays`,
        // that would re-fetch the same head rows under a larger cutoff and
        // produce duplicates relative to what we already have. When a custom
        // range is active, page through it instead of the days window.
        if (this.rangeFromMs != null) {
            this.loadTripsBetween(
                this.rangeFromMs, this.rangeToMs,
                this.currentOffset, this.currentCursor);
        } else {
            this.loadTrips(
                this.currentDays, this.currentOffset, this.currentCursor);
        }
    },

    renderTripList(trips) {
        const container = document.getElementById('tripList');
        if (!container) return;
        const skel = document.getElementById('tripListSkeleton');
        container.innerHTML = '';
        if (skel) container.appendChild(skel);

        const groups = {};
        trips.forEach(t => {
            const day = new Date(t.startTime || t.start_time).toLocaleDateString(BYD.i18n.getLang(), {
                weekday: 'long', month: 'short', day: 'numeric'
            });
            if (!groups[day]) groups[day] = [];
            groups[day].push(t);
        });

        Object.keys(groups).forEach(day => {
            const header = document.createElement('div');
            header.className = 'day-header';
            header.textContent = day;
            container.appendChild(header);
            groups[day].forEach(trip => container.appendChild(this.createTripCard(trip)));
        });

        // Update period summary and cost from loaded trips
        this.updatePeriodSummary();
        this.updateCostHero();
    },

    // Resolve the per-trip nominal pack kWh used for SoC→energy fallbacks.
    // Order of precedence:
    //   1. trip.kwhStart / (socStart/100) — exact recorded value when present
    //   2. this.nominalKwh — surfaced from /api/trips/config (user/auto)
    //   3. 82.56 — last-resort default (Atto 3, mid-pack baseline)
    estimateNominalKwh(trip) {
        const ss = (trip && (trip.socStart || trip.soc_start)) || 0;
        const ks = (trip && (trip.kwhStart || trip.kwh_start)) || 0;
        if (ks > 0 && ss > 5) return ks / (ss / 100);
        if (this.nominalKwh > 0) return this.nominalKwh;
        return 82.56;
    },

    /**
     * Signed net energy for a trip (kWh) — negative when the pack ended fuller
     * than it started (regen-dominant descent). The backend's energyUsedKwh is
     * clamped to 0 in that case because it feeds cost and rollup totals, so
     * display paths use this instead to stay consistent with SoC Used.
     * Derives from the kWh pair when the key is missing (legacy payloads).
     */
    signedEnergy(trip) {
        if (!trip) return 0;
        var signed = trip.signedEnergyKwh != null ? trip.signedEnergyKwh
            : (trip.signed_energy_kwh != null ? trip.signed_energy_kwh : null);
        if (typeof signed === 'number' && signed !== 0) return signed;
        var ks = trip.kwhStart || trip.kwh_start || 0;
        var ke = trip.kwhEnd || trip.kwh_end || 0;
        if (ks > 0 && ke > 0 && ks !== ke) return ks - ke;
        // No kWh pair (legacy row, or a HAL without remaining-energy). SoC alone
        // is integer-resolution, so a 1% "rise" is indistinguishable from jitter
        // and would fabricate ~0.8 kWh of regen — hence getSignedEnergyKwh stays
        // consumption-only here. Require MORE than one quantisation step (same
        // margin as the daemon's SOC_OVERRIDE_MIN_DROP_PCT) so only an
        // unmistakable gain reports, and noise still reads 0.
        var ss = trip.socStart || trip.soc_start || 0;
        var se = trip.socEnd || trip.soc_end || 0;
        if (ss > 0 && (se - ss) > 1.0) return ((ss - se) / 100) * this.estimateNominalKwh(trip);
        return 0;
    },

    /**
     * HTML-escape interpolated text. Needed anywhere a user-supplied string
     * (a tariff label) is concatenated into innerHTML — numeric .toFixed values
     * elsewhere on the card can't carry metacharacters, but labels can.
     */
    esc(s) {
        if (s == null) return '';
        // textContent->innerHTML escapes & < > but NOT quotes, and this output is
        // interpolated into title="..." on the rate-provenance capsule. Escape
        // quotes explicitly so a tariff label containing one can't break out.
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },

    createTripCard(trip) {
        const card = document.createElement('div');
        card.className = 'trip-card';
        card.onclick = () => this.showDetail(trip.id);

        const startTime = new Date(trip.startTime || trip.start_time);
        const timeStr = startTime.toLocaleTimeString(BYD.i18n.getLang(), { hour: '2-digit', minute: '2-digit' });
        const dist = (trip.distanceKm || trip.distance_km || 0).toFixed(2);
        const dur = this.formatDuration(trip.durationSeconds || trip.duration_seconds || 0);
        const avgScore = this.getAvgScore(trip);
        const scoreClass = avgScore >= 70 ? '' : avgScore >= 40 ? 'mid' : 'low';
        // A trip rebuilt from on-disk GPS telemetry (the "Recover Missing Trips"
        // path) has no battery/energy/score data — those were never written to
        // the telemetry file. Distance/speed/duration/elevation ARE real. Detect
        // it so the card omits the misleading 0.00 SoC / 0 score readings instead
        // of rendering them as if the trip genuinely scored zero.
        const recovered = this.isRecoveredTrip(trip);
        const tRC = (k, fb) => (window.BYD && BYD.i18n && BYD.i18n.t(k) && BYD.i18n.t(k) !== k) ? BYD.i18n.t(k) : fb;
        // efficiency is "% per km" stored — convert to per-mi when needed
        const effRaw = trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0;
        const eff = (BYD.units.mode === 'mi' ? effRaw / BYD.units.KM_TO_MI : effRaw).toFixed(2);
        const avgSpd = (trip.avgSpeedKmh || trip.avg_speed_kmh || 0).toFixed(2);
        const socStart = (trip.socStart || trip.soc_start || 0).toFixed(2);
        const socEnd = (trip.socEnd || trip.soc_end || 0).toFixed(2);
        const tripId = trip.id;
        const energyUsed = trip.energyUsedKwh || trip.energy_used_kwh || 0;
        const tripCost = trip.tripCost || trip.trip_cost || 0;
        // Build cost string: prefer stored cost, then compute from energy, then from SoC
        let costStr = '';
        const cur = trip.currency || this.currency || '$';
        if (tripCost > 0) {
            costStr = cur + tripCost.toFixed(2);
        } else if (energyUsed > 0 && this.electricityRate > 0) {
            costStr = cur + (energyUsed * this.electricityRate).toFixed(2);
        } else if (this.electricityRate > 0) {
            // Fallback: estimate from SoC delta for old trips without kWh data
            const socStart = trip.socStart || trip.soc_start || 0;
            const socEnd = trip.socEnd || trip.soc_end || 0;
            if (socStart > socEnd && socStart > 0) {
                const socDelta = socStart - socEnd;
                // Prefer per-trip kwhStart, then user/auto nominal, then 82.56 default
                const nominal = this.estimateNominalKwh(trip);
                const estEnergy = (socDelta / 100) * nominal;
                costStr = '~' + cur + (estEnergy * this.electricityRate).toFixed(2);
            }
        }

        // ── Cost breakdown on the CARD (PHEV) ───────────────────────────────
        // A PHEV trip that ran the engine has two cost legs, and a single total
        // hides which one dominated. Show "EV + petrol = total" inline so the
        // split is readable without opening the detail view. BEV trips (and PHEV
        // trips that stayed full-EV) render only the total capsule, exactly as
        // before — no extra row, no layout change.
        const electricCost = trip.electricCost || trip.electric_cost || 0;
        const fuelCostVal = trip.fuelCost || trip.fuel_cost || 0;
        const litresVal = trip.litresUsed || trip.litres_used || 0;
        let breakdownStr = '';
        if (!recovered && fuelCostVal > 0 && electricCost > 0) {
            breakdownStr = this.ICON_ELECTRIC + ' ' + cur + electricCost.toFixed(2)
                + '  +  ' + this.ICON_PETROL + ' ' + cur + fuelCostVal.toFixed(2);
        } else if (!recovered && fuelCostVal > 0) {
            // Petrol-only leg (charge-sustain / empty battery): label it so the
            // cost isn't mistaken for an electricity charge.
            // Respect the fuel-unit preference, as the detail tile already does —
            // otherwise the same trip reads "4.20 L" on its card and "1.11 gal"
            // when opened.
            breakdownStr = this.ICON_PETROL + ' ' + cur + fuelCostVal.toFixed(2)
                + (litresVal > 0
                    ? ' · ' + (this.fuelUnit === 'gal'
                        ? (litresVal / this.LITRES_PER_GAL).toFixed(2) + ' gal'
                        : litresVal.toFixed(2) + ' L')
                    : '');
        }

        // Rate provenance: name the tariff the electricity was priced at, since a
        // trip is now costed at the LAST CHARGE's rate rather than one global
        // number. Empty on trips recorded before this existed → capsule omitted.
        const rateLabel = trip.rateLabel || trip.rate_label || '';
        const rateSource = trip.rateSource || trip.rate_source || '';

        const elevGain = trip.elevationGainM || trip.elevation_gain_m || 0;
        const gradProfile = trip.gradientProfile || trip.gradient_profile || '';
        const gradIcons = { FLAT: '🛣️', HILLY: '⛰️', MOUNTAIN_CLIMB: '🏔️', MOUNTAIN_DESCENT: '⬇️' };
        const elevStr = elevGain > 0 ? (gradIcons[gradProfile] || '') + ' +' + Math.round(elevGain) + 'm' : '';

        // PHEV fuel% capsule — mirrors the SoC capsule, only when both
        // fuel readings are present (>= 0). Identical icon shape (battery /
        // tank) so the row stays visually balanced. BEV trips have
        // fuelPctStart/End at -1 and skip this entirely.
        const fuelStart = (trip.fuelPctStart != null) ? trip.fuelPctStart
            : (trip.fuel_pct_start != null) ? trip.fuel_pct_start : -1;
        const fuelEnd = (trip.fuelPctEnd != null) ? trip.fuelPctEnd
            : (trip.fuel_pct_end != null) ? trip.fuel_pct_end : -1;
        let fuelStr = '';
        if (fuelStart >= 0 && fuelEnd >= 0) {
            fuelStr = '<span class="trip-capsule" style="color:var(--warning);"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 22h13M5 22V8a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v14"/><path d="M15 6V4a1 1 0 0 1 1-1h0a1 1 0 0 1 1 1v10a2 2 0 0 0 2 2h0a2 2 0 0 0 2-2V8.5"/></svg> '
                + fuelStart.toFixed(2) + '→' + fuelEnd.toFixed(2) + '%</span>';
        }

        // Energy capsule: real kWh > SoC-per-km efficiency. On a recovered trip
        // neither exists, so drop the capsule rather than print "0.00 %/km".
        // A regen-dominant trip shows its NEGATIVE net kWh (energyUsed is clamped
        // to 0 for costing) so the capsule agrees with the SoC capsule beside it.
        const signedEnergyVal = this.signedEnergy(trip);
        const capsuleEnergy = signedEnergyVal < 0 ? signedEnergyVal : energyUsed;
        const energyCapsule = recovered
            ? ''
            : '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg> ' + (capsuleEnergy !== 0 ? capsuleEnergy.toFixed(2) + ' kWh' : eff + BYD.units.socPerDistLabel()) + '</span>';
        // SoC capsule: omit on recovered (would read 0.00→0.00%).
        const socCapsule = recovered
            ? ''
            : '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="6" y="7" width="12" height="10" rx="1"/><path d="M18 10h2a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-2"/></svg> ' + socStart + '→' + socEnd + '%</span>';
        // Odometer capsule: absolute start→end readings (unit-aware). Gated on
        // both being present (>0) — recovered trips and HALs that don't report
        // the odometer leave these at 0, so the capsule is dropped rather than
        // showing a bogus "0→0 km". Gauge icon distinguishes it from distance.
        const odoStart = trip.odometerStartKm || trip.odometer_start_km || 0;
        const odoEnd = trip.odometerEndKm || trip.odometer_end_km || 0;
        const odoCapsule = (odoStart > 0 && odoEnd > 0)
            ? '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="14" r="7"/><path d="M12 14l3-3"/><path d="M12 3v2"/></svg> ' + BYD.units.dist(odoStart) + '→' + BYD.units.dist(odoEnd) + '</span>'
            : '';
        // Score badge: a recovered trip has no driving score, so show a neutral
        // "recovered" glyph instead of a misleading red 0.
        const scoreBadge = recovered
            ? '<div class="trip-score-badge recovered" title="' + tRC('trip.recovered.badge_title', 'Recovered from telemetry — no driving score available') + '">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:18px;height:18px;"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg></div>'
            : '<div class="trip-score-badge ' + scoreClass + '">' + avgScore + '</div>';
        const recoveredTag = recovered
            ? '<span class="trip-recovered-tag">' + tRC('trip.recovered.tag', 'Recovered') + '</span>'
            : '';

        card.innerHTML =
            '<div class="trip-card-top">' +
                '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding-right:48px;">' +
                    '<span class="trip-time" style="font-size: 18px;">' + timeStr + '</span>' +
                    recoveredTag +
                '</div>' +
            '</div>' +
            scoreBadge +
            '<div class="trip-capsules">' +
                '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/><circle cx="12" cy="12" r="10"/></svg> ' + BYD.units.dist(parseFloat(dist)) + '</span>' +
                '<span class="trip-capsule"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> ' + dur + '</span>' +
                energyCapsule +
                socCapsule +
                odoCapsule +
                fuelStr +
                (elevStr ? '<span class="trip-capsule" style="color:#0EA5E9;">' + elevStr + '</span>' : '') +
                (!recovered && costStr ? '<span class="trip-capsule trip-capsule-cost" style="color:var(--warning);"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg> ' + costStr +
                    (breakdownStr ? '<span class="trip-cost-split">' + breakdownStr + '</span>' : '') + '</span>' : '') +
                // Where the electricity price came from. Only for a named tariff —
                // a trip on the global rate has nothing worth a capsule.
                (!recovered && rateSource === 'charge' && rateLabel
                    ? '<span class="trip-capsule trip-rate-src" title="' + this.esc(tRC('trip.rate_from_charge_title', 'Priced at the rate of your last charge')) + '">'
                        + '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-6-5.5-6-10a6 6 0 0 1 12 0c0 4.5-6 10-6 10z"/><circle cx="12" cy="11" r="2"/></svg> '
                        + this.esc(rateLabel) + '</span>'
                    : '') +
            '</div>' +
            '<button class="trip-delete-btn" onclick="event.stopPropagation(); TRIPS.deleteTrip(\'' + tripId + '\')" title="' + BYD.i18n.t('trip.delete_trip_title') + '">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
            '</button>';

        return card;
    },

    // ==================== DNA & SUMMARY ====================

    async loadDna() {
        try {
            const resp = await fetch('/api/trips/dna?days=30');
            const data = await resp.json();
            this._applyDnaPayload(data);
        } catch (e) { console.warn('[Trips] DNA load failed:', e); }
    },

    /**
     * Apply a /api/trips/dna response (or the matching slice of the
     * bootstrap payload) to the radar + score-circle. {@code data.dna}
     * may be null when the user has no scored trips yet — we then leave
     * the cache alone instead of clobbering it with null.
     */
    _applyDnaPayload(data) {
        if (!data || !data.dna) return;
        this.radarScoresCache = data.dna;
        const canvas = document.getElementById('radarChart');
        if (canvas) this.renderRadar(canvas, data.dna);
        if (data.dna.overall !== undefined) this.renderScoreCircle(data.dna.overall);
    },

    _daysSummaryKey(days) {
        return 'days:' + String(days);
    },

    _beginSummaryRequest(key, cancellable) {
        if (this._summaryAbortController) {
            try { this._summaryAbortController.abort(); } catch (e) {}
        }
        if (key !== this._activeSummaryKey) {
            this._lastSummaryPayload = null;
            this._resetCoreSummary();
        }
        const controller = cancellable === false ? null : this._newAbortController();
        const request = {
            sequence: ++this._summaryRequestSequence,
            key: key,
            controller: controller
        };
        this._activeSummaryKey = key;
        this._summaryAbortController = controller;
        return request;
    },

    _isCurrentSummaryRequest(request) {
        return !!request
            && request.sequence === this._summaryRequestSequence
            && request.key === this._activeSummaryKey;
    },

    _finishSummaryRequest(request) {
        if (!this._isCurrentSummaryRequest(request)) return;
        if (this._summaryAbortController === request.controller) {
            this._summaryAbortController = null;
        }
    },

    _invalidateSummaryRequests() {
        if (this._summaryAbortController) {
            try { this._summaryAbortController.abort(); } catch (e) {}
        }
        this._summaryRequestSequence++;
        this._activeSummaryKey = null;
        this._summaryAbortController = null;
        this._lastSummaryPayload = null;
    },

    async loadSummary(days) {
        const d = days == null ? 7 : days;
        const request = this._beginSummaryRequest(this._daysSummaryKey(d), true);
        try {
            const url = '/api/trips/summary?days=' + d;
            const resp = request.controller
                ? await fetch(url, { signal: request.controller.signal })
                : await fetch(url);
            const data = await resp.json();
            if (!this._isCurrentSummaryRequest(request)) return;
            this._applySummaryPayload(data, request);
        } catch (e) {
            if (!this._isCurrentSummaryRequest(request) || this._isAbortError(e)) return;
            console.warn('[Trips] Summary load failed:', e);
        } finally {
            this._finishSummaryRequest(request);
        }
    },

    /**
     * Apply a /api/trips/summary response to the period-summary tiles.
     * Server returns an array of weekly rollups; we only render the most
     * recent one (matching the legacy loadSummary behaviour).
     */
    _applySummaryPayload(data, request) {
        if (request && !this._isCurrentSummaryRequest(request)) return false;
        if (!data || !Array.isArray(data.summary)) return false;
        this._lastSummaryPayload = data;
        if (data.summary.length === 0) {
            this.setEl('summaryTrips', 0);
            this.setEl('summaryDistance', '0.0');
            this.setEl('summaryTime', '0.0');
            this.setEl('summaryEfficiency', '--');
            // Clear the energy/cost half of the card too. Writing only the four
            // fields above left the previous window's numbers on screen, so
            // narrowing the range to one with no trips showed "0 trips / 0.0 km"
            // beside a stale "47.2 kWh / $6.80".
            this.setEl('summaryEnergy', '--');
            this.setEl('summaryConsumption', '--');
            this.setEl('summaryEfficiency2', '--');
            this.setEl('summaryCost', '--');
            const emptyFuelTile = document.getElementById('summaryFuelTile');
            if (emptyFuelTile) emptyFuelTile.style.display = 'none';
            return true;
        }
        const s = data.summary[0];
        this.setEl('summaryTrips', s.tripCount || s.trip_count || 0);
        this.setEl('summaryDistance', BYD.units.distVal(s.totalDistanceKm || s.total_distance_km || 0).toFixed(1));
        this.setEl('summaryTime', ((s.totalDurationSeconds || s.total_duration_seconds || 0) / 3600).toFixed(1));
        // Compute overall from 5 sub-scores (matching backend integer division)
        const sA = s.avgAnticipation || s.avg_anticipation || 0;
        const sS = s.avgSmoothness || s.avg_smoothness || 0;
        const sSD = s.avgSpeedDiscipline || s.avg_speed_discipline || 0;
        const sE = s.avgEfficiencyScore || s.avg_efficiency_score || 0;
        const sC = s.avgConsistency || s.avg_consistency || 0;
        this.setEl('summaryEfficiency', Math.floor((sA + sS + sSD + sE + sC) / 5));
        return true;
    },

    _resetCoreSummary() {
        this.setEl('summaryTrips', '--');
        this.setEl('summaryDistance', '--');
        this.setEl('summaryTime', '--');
        this.setEl('summaryEfficiency', '--');
    },

    // ==================== CLIENT-SIDE SUMMARY ====================

    updatePeriodSummary() {
        const trips = this.trips;
        // An empty window is legitimate for a custom range (a span with no
        // driving), unlike the day-presets. Zero the tiles rather than leaving
        // stale values from the previously-viewed window. Day-preset callers
        // that early-returned before still behave the same (they never reached
        // here with an empty list mid-session).
        if (!trips || trips.length === 0) {
            if (this.rangeFromMs != null) {
                this.setEl('summaryTrips', 0);
                this.setEl('summaryDistance', '--');
                this.setEl('summaryTime', '--');
                this.setEl('summaryEfficiency', '--');
                this.setEl('summaryEnergy', '--');
                this.setEl('summaryConsumption', '--');
                this.setEl('summaryEfficiency2', '--');
                this.setEl('summaryCost', '--');
                const fuelTile = document.getElementById('summaryFuelTile');
                if (fuelTile) fuelTile.style.display = 'none';
            }
            return;
        }

        let totalDist = 0, totalDur = 0, totalEnergy = 0, totalCost = 0;
        let scoreSum = 0;
        let totalSocDelta = 0;
        // PHEV aggregates: total petrol burned across the window in litres,
        // and a flag tracking whether any trip in scope was PHEV (gates the
        // tile visibility — BEV-only windows hide it entirely).
        let totalLitres = 0;
        let totalFuelCost = 0;
        let anyPhev = false;
        trips.forEach(t => {
            totalDist += t.distanceKm || t.distance_km || 0;
            totalDur += t.durationSeconds || t.duration_seconds || 0;
            let energy = t.energyUsedKwh || t.energy_used_kwh || 0;
            // Fallback: estimate energy from SoC delta for trips without kWh data
            if (energy <= 0) {
                const ss = t.socStart || t.soc_start || 0;
                const se = t.socEnd || t.soc_end || 0;
                if (ss > se && ss > 0) {
                    const nom = this.estimateNominalKwh(t);
                    energy = ((ss - se) / 100) * nom;
                }
            }
            totalEnergy += energy;
            totalCost += t.tripCost || t.trip_cost || 0;
            scoreSum += this.getAvgScore(t);
            const socStart = t.socStart || t.soc_start || 0;
            const socEnd = t.socEnd || t.soc_end || 0;
            if (socStart > socEnd) totalSocDelta += (socStart - socEnd);
            // Per-trip PHEV roll-up. Trip records persist their own
            // litresUsed/fuelCost snapshots (computed at trip end), so
            // aggregation is a pure sum — no live config dependency.
            if (t.isPhev || t.is_phev) anyPhev = true;
            totalLitres += t.litresUsed || t.litres_used || 0;
            totalFuelCost += t.fuelCost || t.fuel_cost || 0;
        });

        // Preset-day core totals come from /api/trips/summary and represent the
        // complete server-side period. Only a custom range is client-owned;
        // otherwise pagination would replace full totals with the loaded subset.
        if (this.rangeFromMs != null) {
            this.setEl('summaryTrips', trips.length);
            this.setEl('summaryDistance', BYD.units.distVal(totalDist).toFixed(1));
            this.setEl('summaryTime', (totalDur / 3600).toFixed(1));
            this.setEl('summaryEfficiency', trips.length > 0 ? Math.floor(scoreSum / trips.length) : '--');
        }
        this.setEl('summaryEnergy', totalEnergy > 0 ? totalEnergy.toFixed(1) : '--');

        // Average consumption: kWh/100km or kWh/100mi (works for BEV and PHEV)
        // Prefer direct kWh measurement, fall back to SOC-based estimate.
        if (totalDist > 0.5) {
            if (totalEnergy > 0) {
                const kwhPer100km = (totalEnergy / totalDist) * 100;
                this.setEl('summaryConsumption', BYD.units.per100Val(kwhPer100km).toFixed(1));
            } else if (totalSocDelta > 0) {
                const socPer100km = (totalSocDelta / totalDist) * 100;
                this.setEl('summaryConsumption', BYD.units.per100Val(socPer100km).toFixed(1) + '%');
            } else {
                this.setEl('summaryConsumption', '--');
            }
        } else {
            this.setEl('summaryConsumption', '--');
        }

        // Distance-per-energy efficiency: km/kWh (or mi/kWh). The intuitive
        // "how far per unit of energy" metric — only meaningful with measured
        // kWh, so no SoC fallback (that lives in the consumption tile above).
        if (totalDist > 0.5 && totalEnergy > 0) {
            const kmPerKwh = totalDist / totalEnergy;
            this.setEl('summaryEfficiency2', BYD.units.effVal(kmPerKwh).toFixed(1));
        } else {
            this.setEl('summaryEfficiency2', '--');
        }

        if (totalCost > 0) {
            this.setEl('summaryCost', (this.currency || '$') + totalCost.toFixed(1));
        } else if (totalEnergy > 0 && this.electricityRate > 0) {
            const computed = totalEnergy * this.electricityRate;
            this.setEl('summaryCost', (this.currency || '$') + computed.toFixed(1));
        } else {
            this.setEl('summaryCost', '--');
        }

        // Petrol roll-up — visible only when at least one PHEV trip is in
        // scope. Shows aggregate litres (or gallons in user's chosen unit)
        // with a sub-line of total petrol cost when known.
        var fuelTile = document.getElementById('summaryFuelTile');
        if (fuelTile) {
            if (anyPhev && totalLitres > 0) {
                var label;
                if (this.fuelUnit === 'gal') {
                    label = (totalLitres / this.LITRES_PER_GAL).toFixed(1) + ' gal';
                } else {
                    label = totalLitres.toFixed(1) + ' L';
                }
                this.setEl('summaryFuel', label);
                fuelTile.style.display = '';
            } else {
                fuelTile.style.display = 'none';
            }
        }
    },

    updateCostHero() {
        const noRate = document.getElementById('costNoRate');
        const dataDiv = document.getElementById('costHeroData');
        if (!noRate || !dataDiv) return;

        // Draw empty circle for no-rate state
        this.renderCircleGauge('costCircleCanvas', 0, 'rgba(245,158,11,0.2)');
        this.updateCurrencyIcons();

        if (this.electricityRate <= 0) {
            noRate.style.display = 'block';
            dataDiv.style.display = 'none';
            return;
        }

        const trips = this.trips;
        let totalEnergy = 0, totalDist = 0;
        if (trips && trips.length > 0) {
            trips.forEach(t => {
                let energy = t.energyUsedKwh || t.energy_used_kwh || 0;
                // Fallback: estimate from SoC delta for trips without kWh data
                if (energy <= 0) {
                    const ss = t.socStart || t.soc_start || 0;
                    const se = t.socEnd || t.soc_end || 0;
                    if (ss > se && ss > 0) {
                        const nom = this.estimateNominalKwh(t);
                        energy = ((ss - se) / 100) * nom;
                    }
                }
                totalEnergy += energy;
                totalDist += t.distanceKm || t.distance_km || 0;
            });
        }

        if (totalDist > 0 && totalEnergy > 0) {
            const kwhPerKm = totalEnergy / totalDist;
            const costPerKm = kwhPerKm * this.electricityRate;
            // Cost-per-distance shown in user's unit: cost/km × KM_TO_MI gives cost/mi
            const costPerDist = BYD.units.mode === 'mi' ? costPerKm / BYD.units.KM_TO_MI : costPerKm;
            const kwhPerDist = BYD.units.mode === 'mi' ? kwhPerKm / BYD.units.KM_TO_MI : kwhPerKm;
            noRate.style.display = 'none';
            dataDiv.style.display = 'block';
            this.setEl('costPerKmValue', this.currency + costPerDist.toFixed(2));
            this.setEl('costPerKmUnit', BYD.units.perDistLabel());
            // Formula capsule below circle (single source of truth — no duplicate text)
            const formulaCapsule = document.getElementById('costFormulaCapsule');
            if (formulaCapsule) {
                formulaCapsule.textContent = BYD.i18n.t('trip.card.cost_formula', {
                    kwh: kwhPerDist.toFixed(3),
                    rate: this.currency + this.electricityRate
                });
                formulaCapsule.style.display = '';
            }
            const infoEl = document.getElementById('costPerKwhInfo');
            if (infoEl) infoEl.style.display = 'none';
            // Gauge: lower cost = better. Map 0-5 currency/distance to 100-0%
            const pct = Math.max(0, Math.min(100, (1 - costPerDist / 5) * 100));
            this.renderCircleGauge('costCircleCanvasActive', pct, '#F59E0B');
        } else {
            noRate.style.display = 'none';
            dataDiv.style.display = 'block';
            this.setEl('costPerKmValue', '--');
            this.setEl('costPerKwhInfo', BYD.i18n.t('trip.card.drive_more_to_calc'));
            const infoEl = document.getElementById('costPerKwhInfo');
            if (infoEl) infoEl.style.display = '';
            const formulaCapsule = document.getElementById('costFormulaCapsule');
            if (formulaCapsule) formulaCapsule.style.display = 'none';
            this.renderCircleGauge('costCircleCanvasActive', 0, 'rgba(245,158,11,0.2)');
        }
    },

    /**
     * Generic circle gauge renderer — used by range and cost cards.
     */
    renderCircleGauge(canvasId, percent, color) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;
        const dpr = window.devicePixelRatio || 1;
        const size = 140;
        canvas.width = size * dpr;
        canvas.height = size * dpr;
        canvas.style.width = size + 'px';
        canvas.style.height = size + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const cx = size / 2, cy = size / 2, radius = 58, lineWidth = 8;

        // Background ring
        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.strokeStyle = this.colors.arcTrack;
        ctx.lineWidth = lineWidth;
        ctx.stroke();

        if (percent > 0) {
            const startAngle = -Math.PI / 2;
            const endAngle = startAngle + (percent / 100) * Math.PI * 2;

            // Glow
            ctx.beginPath();
            ctx.arc(cx, cy, radius, startAngle, endAngle);
            ctx.strokeStyle = color.replace(')', ',0.3)').replace('rgb', 'rgba');
            ctx.lineWidth = lineWidth + 6;
            ctx.lineCap = 'round';
            ctx.stroke();

            // Main arc
            ctx.beginPath();
            ctx.arc(cx, cy, radius, startAngle, endAngle);
            ctx.strokeStyle = color;
            ctx.lineWidth = lineWidth;
            ctx.lineCap = 'round';
            ctx.stroke();
        }
    },

    async loadRange() {
        try {
            const resp = await fetch('/api/trips/range');
            const data = await resp.json();
            this._applyRangePayload(data);
        } catch (e) {
            console.warn('[Trips] Range load failed:', e);
            this.renderCircleGauge('rangeCircleCanvas', 0, 'rgba(14,165,233,0.2)');
            this.renderPetrolRange(null);
        }
    },

    /**
     * Apply a /api/trips/range response to the range hero card. Accepts
     * the raw handler payload or the bootstrap-stripped slice; both keep
     * {@code range} / {@code fuelRange} / {@code totalRangeKm} at the top
     * level. Empty / no-data responses fall through to the no-data
     * rendering at the bottom.
     */
    _applyRangePayload(data) {
        try {
            const content = document.getElementById('rangeHeroContent');
            if (!content) return;

            if (data && data.range && data.range !== null) {
                const r = data.range;
                this.rangeCache = r;
                // Backend always returns km; convert to user's display unit.
                const predictedKm = r.predictedRangeKm || r.predicted_range_km || 0;
                const lowerKm = r.lowerBoundKm || r.lower_bound_km || 0;
                const upperKm = r.upperBoundKm || r.upper_bound_km || 0;
                const builtInKm = r.builtInRangeKm || r.built_in_range_km || 0;
                const predicted = BYD.units.distVal(predictedKm);
                const lower = BYD.units.distVal(lowerKm);
                const upper = BYD.units.distVal(upperKm);
                const builtIn = BYD.units.distVal(builtInKm);
                const distLbl = BYD.units.distLabel();

                content.innerHTML = '';

                // Update circle value. The unit label ships as a literal "km" in
                // the markup, so set it too — `predicted` is already converted.
                this.setEl('rangeCircleValue', predicted);
                this.setEl('rangeCircleUnit', distLbl);
                // Fill = personalized vs projected (built-in). Full ring when you
                // match or beat the factory baseline; partial when below it.
                const rangePct = builtInKm > 0
                    ? Math.min(100, (predictedKm / builtInKm) * 100)
                    : Math.min(100, (predictedKm / 500) * 100);
                this.renderCircleGauge('rangeCircleCanvas', rangePct, '#0EA5E9');

                // Delta capsule below circle — shows personalized vs built-in
                const capsule = document.getElementById('rangeDeltaCapsule');
                if (capsule && builtIn > 0) {
                    const delta = predicted - builtIn;
                    const deltaSign = delta >= 0 ? '+' : '';
                    capsule.innerHTML = predicted + ' vs ' + builtIn + ' ' + distLbl + ' <span style="opacity:0.7;margin-left:2px;">(' + deltaSign + delta + ')</span>';
                    capsule.className = 'range-delta-capsule ' + (delta >= 0 ? 'better' : 'worse');
                    capsule.style.display = '';
                } else if (capsule) {
                    capsule.textContent = lower + ' – ' + upper + ' ' + distLbl + ' range';
                    capsule.className = 'range-delta-capsule neutral';
                    capsule.style.display = '';
                }

                // Build hover tooltip matching score-hero-tooltip design
                const tooltip = document.getElementById('rangeHeroTooltip');
                if (tooltip) {
                    tooltip.style.display = '';
                    let tt = '<div class="range-tooltip-title">' + BYD.i18n.t('trip.range_tooltip_title') + '</div>';

                    tt += '<div class="range-tooltip-row"><span class="range-tooltip-label">' + BYD.i18n.t('trip.range_confidence') + '</span><span class="range-tooltip-value">' + lower + ' – ' + upper + ' ' + distLbl + '</span></div>';

                    // Conditions pills.
                    // Backend bucketKey formats (see RangeEstimator.java):
                    //   "suburban_mild_low"          — exact bucket: 3 pills
                    //   "suburban_mild_low(blend)"   — neighbor blend: 3 pills + mode note
                    //   "city(profile)"              — speed-profile fallback: 1 pill + mode note
                    //   "overall"                    — global average: no pills, mode note only
                    const bucketKey = r.bucketKey || r.bucket_key || '';
                    const samples = r.sampleCount || r.sample_count || 0;
                    if (bucketKey) {
                        const modeMatch = bucketKey.match(/\(([^)]+)\)$/);
                        const mode = modeMatch ? modeMatch[1] : (bucketKey === 'overall' ? 'overall' : 'exact');
                        const cleanKey = bucketKey.replace(/\([^)]+\)$/, '');
                        const parts = cleanKey === 'overall' ? [] : cleanKey.split('_');

                        // Canonical category sets — must match RangeEstimator.computeBucketKey().
                        // The bucket key is always English in the DB (e.g. "city_mild_low");
                        // we look up display strings here at render time so the same row
                        // localises per user.
                        const SPEEDS = { city: 1, suburban: 1, highway: 1 };
                        const TEMPS  = { cold: 1, mild: 1, hot: 1 };
                        const STYLES = { low: 1, mid: 1, high: 1 };

                        const speedLabels = {
                            city: BYD.i18n.t('trip.speed_label.city'),
                            suburban: BYD.i18n.t('trip.speed_label.suburban'),
                            highway: BYD.i18n.t('trip.speed_label.highway')
                        };
                        const tempLabels = { cold: BYD.i18n.t('trip.temp_label.cold'), mild: BYD.i18n.t('trip.temp_label.mild'), hot: BYD.i18n.t('trip.temp_label.hot') };
                        const styleLabels = { low: BYD.i18n.t('trip.style_label.low'), mid: BYD.i18n.t('trip.style_label.mid'), high: BYD.i18n.t('trip.style_label.high') };
                        const speedColors = { city: 'rgba(99,102,241,0.15);color:#6366F1', suburban: 'rgba(0,212,170,0.15);color:var(--brand-primary)', highway: 'rgba(245,158,11,0.15);color:var(--warning)' };
                        const tempColors = { cold: 'rgba(14,165,233,0.15);color:#0EA5E9', mild: 'rgba(34,197,94,0.15);color:#22C55E', hot: 'rgba(239,68,68,0.15);color:var(--danger)' };
                        const styleColors = { low: 'rgba(34,197,94,0.15);color:#22C55E', mid: 'rgba(245,158,11,0.15);color:var(--warning)', high: 'rgba(239,68,68,0.15);color:var(--danger)' };
                        const neutralPill = 'rgba(148,163,184,0.18);color:var(--text-muted)';

                        // Pull the speed/temp/style tokens out of the cleaned key. The
                        // canonical layout is "<speed>_<temp>_<style>" but legacy or
                        // partial data could have fewer/non-canonical tokens; we slot
                        // each token into the dimension whose vocabulary it matches.
                        // Anything that doesn't match a known category is dropped so
                        // we never render the literal string "undefined".
                        let sp = '', tp = '', st = '';
                        for (let i = 0; i < parts.length; i++) {
                            const p = parts[i];
                            if (!p) continue;
                            if (!sp && SPEEDS[p]) sp = p;
                            else if (!tp && TEMPS[p]) tp = p;
                            else if (!st && STYLES[p]) st = p;
                        }

                        tt += '<div class="range-tooltip-conditions">';
                        tt += '<div class="range-tooltip-conditions-label">' + BYD.i18n.t('trip.range_current_conditions') + '</div>';
                        tt += '<div class="range-tooltip-pills">';

                        if (sp) {
                            tt += '<span class="range-tooltip-pill" style="background:' + speedColors[sp] + ';">' + speedLabels[sp] + '</span>';
                        }
                        if (tp) {
                            tt += '<span class="range-tooltip-pill" style="background:' + tempColors[tp] + ';">' + tempLabels[tp] + '</span>';
                        }
                        if (st) {
                            tt += '<span class="range-tooltip-pill" style="background:' + styleColors[st] + ';">' + styleLabels[st] + '</span>';
                        }
                        // Surface non-canonical bucket keys so we can spot stale
                        // rows without having to scrape the DB. Quiet for the
                        // overall/profile/blend forms which legitimately have
                        // fewer than 3 dimensions.
                        if (cleanKey !== 'overall' && parts.length >= 1
                                && (!sp || (parts.length >= 3 && (!tp || !st)))) {
                            console.warn('[Trips] Range bucket key has unexpected tokens:', bucketKey);
                        }

                        tt += '</div>';

                        // Explain how the estimate was produced when we fell back
                        let modeNote = '';
                        if (mode === 'blend') modeNote = BYD.i18n.t('trip.range_mode_blend');
                        else if (mode === 'profile') modeNote = BYD.i18n.t('trip.range_mode_profile');
                        else if (mode === 'overall') modeNote = BYD.i18n.t('trip.range_mode_overall');
                        if (modeNote) {
                            tt += '<div class="range-tooltip-samples" style="color:var(--text-muted);font-style:italic;">' + modeNote + '</div>';
                        }

                        tt += '<div class="range-tooltip-samples">' + BYD.i18n.t('trip.range_based_on', {count: samples}) + '</div>';
                        tt += '</div>';
                    }

                    tooltip.innerHTML = tt;
                }
            } else {
                content.innerHTML = '<div class="range-hero-no-data"><div>' + BYD.i18n.t('trip.drive_more_unlock') + '</div></div>';
                this.renderCircleGauge('rangeCircleCanvas', 0, 'rgba(14,165,233,0.2)');
                const capsule = document.getElementById('rangeDeltaCapsule');
                if (capsule) capsule.style.display = 'none';
            }

            // PHEV petrol leg — data.fuelRange is the LEARNED estimate (needs
            // tankCapacityL + seeded buckets); data.halFuelRangeKm is the car's
            // own figure, always present on a PHEV. Pass both so the sub-line
            // shows a real number immediately and upgrades to the learned one
            // later. BEV → both absent → tile hidden.
            this.renderPetrolRange((data && data.fuelRange) || null,
                                   data && data.totalRangeKm,
                                   data && data.halFuelRangeKm);
        } catch (e) {
            console.warn('[Trips] Range apply failed:', e);
            this.renderCircleGauge('rangeCircleCanvas', 0, 'rgba(14,165,233,0.2)');
            this.renderPetrolRange(null);
        }
    },

    /**
     * Render the petrol-range sub-line under the range hero circle. Lazy-
     * mounts a div on first PHEV trip and toggles via display:none for
     * subsequent BEV / no-data refreshes — same pattern as renderCostBreakdown.
     */
    renderPetrolRange(fuelRange, totalRangeKm, halFuelRangeKm) {
        var existing = document.getElementById('petrolRangeSubline');
        // Learned estimate first, else the HAL fuel range. Only a PHEV with
        // neither hides the row entirely.
        var petrolKm = 0;
        if (fuelRange) {
            petrolKm = fuelRange.predictedRangeKm || fuelRange.predicted_range_km || 0;
        }
        if (petrolKm <= 0 && typeof halFuelRangeKm === 'number' && halFuelRangeKm > 0) {
            petrolKm = halFuelRangeKm;
        }
        if (petrolKm <= 0) {
            if (existing) existing.style.display = 'none';
            return;
        }

        var capsule = document.getElementById('rangeDeltaCapsule');
        if (!capsule) return;

        var container = existing;
        if (!container) {
            container = document.createElement('div');
            container.id = 'petrolRangeSubline';
            container.className = 'range-petrol-subline';
            container.style.cssText = 'margin-top:6px;display:flex;flex-direction:column;align-items:center;gap:2px;font-size:11px;';
            capsule.parentNode.insertBefore(container, capsule.nextSibling);
        }

        var petrolDisplay = BYD.units.distVal(petrolKm);
        var distLbl = BYD.units.distLabel();
        var totalDisplay = (totalRangeKm != null && totalRangeKm > 0)
            ? BYD.units.distVal(totalRangeKm) : null;

        var html = '<span style="color:var(--warning);">' + this.ICON_PETROL + ' '
            + BYD.i18n.t('trip.range_petrol_label', { km: petrolDisplay, unit: distLbl })
            + '</span>';
        if (totalDisplay != null) {
            html += '<span style="color:var(--text-muted);">'
                + BYD.i18n.t('trip.range_total_label', { km: totalDisplay, unit: distLbl })
                + '</span>';
        }
        container.innerHTML = html;
        container.style.display = '';
    },

    // ==================== TRIP DETAIL ====================

    _beginDetailRequest(tripId) {
        if (this._detailAbortController) {
            try { this._detailAbortController.abort(); } catch (e) {}
        }

        const controller = this._newAbortController();
        const request = {
            sequence: ++this._detailRequestSequence,
            tripId: String(tripId),
            controller: controller
        };
        this._activeDetailRequest = request;
        this._detailAbortController = controller;
        // currentTripId is reserved for the summary actually on screen. It is
        // assigned only after this request's summary has rendered completely.
        this.currentTripId = null;
        this._resetDetailViewState();
        return request;
    },

    _isCurrentDetailRequest(request) {
        return !!request
            && !!this._activeDetailRequest
            && request.sequence === this._detailRequestSequence
            && request.sequence === this._activeDetailRequest.sequence
            && request.tripId === this._activeDetailRequest.tripId;
    },

    _cancelDetailRequest() {
        if (this._detailAbortController) {
            try { this._detailAbortController.abort(); } catch (e) {}
        }
        this._detailAbortController = null;
        this._activeDetailRequest = null;
        this._detailRequestSequence++;
        this.currentTripId = null;
    },

    _clearDetailCanvas(id) {
        const canvas = document.getElementById(id);
        if (!canvas) return;
        canvas.onmousemove = null;
        canvas.onmouseleave = null;
        canvas.ontouchstart = null;
        canvas.ontouchmove = null;
        canvas.ontouchend = null;
        // Assigning width resets pixels plus the complete Canvas2D state and is
        // supported by the legacy WebView.
        canvas.width = canvas.width;
    },

    _clearDetailTimer(name) {
        if (this[name] != null) {
            clearTimeout(this[name]);
            this[name] = null;
        }
    },

    _resetDetailTelemetryState(request) {
        if (request && !this._isCurrentDetailRequest(request)) return;

        this.telemetryCache = null;
        this.currentTripData = null;
        this._timelineAxis = null;
        this.routeLayer = null;
        this.sliderMarker = null;
        this._mapRetries = 0;
        this._layoutRetries = 0;

        this._clearDetailTimer('_detailMapTimer');
        this._clearDetailTimer('_mapRetryTimer');
        this._clearDetailTimer('_mapHeadingTimer');
        if (this._mapInvalidateTimers) {
            this._mapInvalidateTimers.forEach(function(timer) { clearTimeout(timer); });
        }
        this._mapInvalidateTimers = [];

        if (this._sliderMarkerReadyHandler) {
            try {
                document.removeEventListener('app-shell:ready', this._sliderMarkerReadyHandler);
            } catch (e) {}
            this._sliderMarkerReadyHandler = null;
        }
        if (this._sliderMarker3d) {
            try { this._sliderMarker3d.dispose(); } catch (e) {}
            this._sliderMarker3d = null;
        }
        if (this.leafletMap) {
            try { this.leafletMap.remove(); } catch (e) {}
            this.leafletMap = null;
        }

        const mapContainer = document.getElementById('tripMap');
        if (mapContainer) mapContainer.innerHTML = '';

        this._clearDetailCanvas('timelineChart');
        this._clearDetailCanvas('speedHistogram');
        this.setEl('tlAccelPct', '--%');
        this.setEl('tlCoastPct', '--%');
        this.setEl('tlBrakePct', '--%');
        const histSummary = document.getElementById('speedHistSummary');
        if (histSummary) histSummary.innerHTML = '';

        const sliderCard = document.getElementById('timelineSliderCard');
        if (sliderCard) sliderCard.style.display = 'none';
        const slider = document.getElementById('timelineSlider');
        if (slider) {
            slider.min = 0;
            slider.max = 0;
            slider.value = 0;
            if (slider.parentElement) slider.parentElement.onmousemove = null;
        }
        this.setEl('sliderSpeed', '--');
        this.setEl('sliderAccel', '--');
        this.setEl('sliderBrake', '--');
        this.setEl('sliderSoc', '--');
        this.setEl('sliderStartTime', '0:00');
        this.setEl('sliderCurrentTime', '--:--');
        this.setEl('sliderEndTime', '--:--');
    },

    _resetRouteComparisonState() {
        this._routeMapRequestSequence = (this._routeMapRequestSequence || 0) + 1;
        this._clearDetailTimer('_routeSparklineTimer');
        if (this.routeCompareMapInstance) {
            try { this.routeCompareMapInstance.remove(); } catch (e) {}
            this.routeCompareMapInstance = null;
        }
        const card = document.getElementById('routeComparisonCard');
        const content = document.getElementById('routeComparisonContent');
        const overlay = document.getElementById('routeMapOverlay');
        const map = document.getElementById('routeCompareMap');
        const legend = document.getElementById('routeMapLegend');
        if (card) card.style.display = 'none';
        if (content) content.innerHTML = '';
        if (overlay) overlay.style.display = 'none';
        if (map) map.innerHTML = '';
        if (legend) legend.innerHTML = '';
    },

    _resetDetailViewState() {
        this._resetDetailTelemetryState();
        this._resetRouteComparisonState();

        [
            'detailTitle', 'detailSubtitle', 'detailDistance', 'detailDuration',
            'detailSocDelta', 'detailEfficiency', 'detailConsumption',
            'detailEfficiency2', 'detailAvgSpeed', 'detailMaxSpeed',
            'detailSocStart', 'detailSocEnd', 'detailOdoStart', 'detailOdoEnd',
            'detailFuelStart', 'detailFuelEnd', 'detailLitresUsed', 'detailTemp',
            'detailCost', 'detailElevGain', 'detailElevLoss'
        ].forEach((id) => this.setEl(id, '--'));

        [
            'detailOdoStartTile', 'detailOdoEndTile', 'detailFuelStartTile',
            'detailFuelEndTile', 'detailLitresUsedTile'
        ].forEach(function(id) {
            const el = document.getElementById(id);
            if (el) el.style.display = 'none';
        });
        const grad = document.getElementById('detailGradientPill');
        if (grad) {
            grad.style.display = 'none';
            grad.textContent = '';
        }
        const cost = document.getElementById('costBreakdown');
        if (cost) {
            cost.style.display = 'none';
            cost.innerHTML = '';
        }
        const moments = document.getElementById('microMomentsList');
        if (moments) moments.innerHTML = '';

        [
            ['scoreAnticipation', 'scoreAnticipationVal'],
            ['scoreSmoothness', 'scoreSmoothnessVal'],
            ['scoreSpeedDisc', 'scoreSpeedDiscVal'],
            ['scoreEfficiency', 'scoreEfficiencyVal'],
            ['scoreConsistency', 'scoreConsistencyVal']
        ].forEach(function(ids) {
            const fill = document.getElementById(ids[0]);
            const value = document.getElementById(ids[1]);
            if (fill) {
                fill.style.width = '0%';
                fill.classList.remove('low', 'mid');
            }
            if (value) value.textContent = '--';
        });
        this.applyRecoveredDetailState(false);

        const deleteBtn = document.getElementById('detailDeleteBtn');
        if (deleteBtn) deleteBtn.disabled = true;
    },

    _hasTelemetryArtifact(trip) {
        if (!trip) return false;
        const path = trip.telemetryFilePath || trip.telemetry_file_path || '';
        // Backup imports intentionally carry stats only. The non-empty sentinel
        // keeps database recovery from reaping them; it is not a fetchable file.
        return !!path && path.indexOf('imported://') !== 0;
    },

    _isUsableTelemetry(samples) {
        if (!Array.isArray(samples) || samples.length === 0) return false;
        const axis = this._selectTimelineAxis(samples);
        if (axis.key === 'e') return true;
        // Legacy telemetry has no monotonic field and remains valid as long as
        // its original wall timestamps are numeric. A malformed wall timeline
        // cannot be plotted safely and is treated as unavailable.
        for (let i = 0; i < samples.length; i++) {
            if (!samples[i] || typeof samples[i].t !== 'number'
                    || !isFinite(samples[i].t)) return false;
        }
        return true;
    },

    async showDetail(tripId) {
        const detailRequest = this._beginDetailRequest(tripId);
        let summaryBound = false;
        document.getElementById('tripListView').classList.add('hidden');
        document.getElementById('tripDetail').classList.add('active');
        window.scrollTo(0, 0);

        try {
            const tripUrl = '/api/trips/' + tripId;
            const tripResp = detailRequest.controller
                ? await fetch(tripUrl, { signal: detailRequest.controller.signal })
                : await fetch(tripUrl);
            const tripData = await tripResp.json();
            if (!this._isCurrentDetailRequest(detailRequest)) return;
            if (!tripData.success || !tripData.trip) return;
            const trip = tripData.trip;

            const recovered = this.isRecoveredTrip(trip);
            // A recovered trip normally has no SoC/energy data (never written to
            // the GPS-only telemetry file) — but one enriched from a surviving
            // live checkpoint (TripDatabase.enrichRecoveredTripFromCheckpoint)
            // DOES have real readings despite still being "recovered" (no
            // driving scores). Check the actual values below rather than
            // blanket-hiding on the recovered flag, same as the trip-card fix.
            const detailHasRealSoc = (trip.socStart || trip.soc_start || 0) > 0
                    || (trip.socEnd || trip.soc_end || 0) > 0;
            const start = new Date(trip.startTime || trip.start_time);
            const lang = BYD.i18n.getLang();
            this.setEl('detailTitle', start.toLocaleDateString(lang, { weekday: 'long', month: 'long', day: 'numeric' }));
            this.setEl('detailSubtitle', start.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' }) +
                ' – ' + new Date(trip.endTime || trip.end_time).toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' }));
            this.setEl('detailDuration', this.formatDuration(trip.durationSeconds || trip.duration_seconds || 0));
            this.setEl('detailSocDelta', (recovered && !detailHasRealSoc) ? '--' : ((trip.socStart || trip.soc_start || 0) - (trip.socEnd || trip.soc_end || 0)).toFixed(2) + '%');
            // Show energy kWh or efficiency
            const detailEnergy = trip.energyUsedKwh || trip.energy_used_kwh || 0;
            // Signed net energy: negative when the pack ended FULLER than it
            // started (regen-dominant descent). energyUsedKwh is deliberately
            // clamped to 0 there because it feeds cost, but displaying that 0
            // next to a negative "SoC Used" reads as a bug — so the tiles below
            // prefer the signed figure whenever it's negative.
            const detailSignedEnergy = this.signedEnergy(trip);
            const displayEnergy = detailSignedEnergy < 0 ? detailSignedEnergy : detailEnergy;
            // True when energy came from the vehicle's own metered counter, so a
            // near-zero figure is a real measurement of a very short trip rather
            // than missing data. Absent on legacy rows → falsy → old behaviour.
            const energyMetered = !!(trip.energyMetered || trip.energy_metered);
            // Metered trips get 3 decimals: a sub-km hop draws ~0.1 kWh, which
            // 2 decimals would round toward a misleading "0.00".
            const detailHasRealEnergy = displayEnergy !== 0
                    || (trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0) !== 0;
            this.setEl('detailEfficiency', (recovered && !detailHasRealEnergy) ? '--'
                : (displayEnergy !== 0 ? (energyMetered ? displayEnergy.toFixed(3) : displayEnergy.toFixed(2)) + ' kWh'
                : (energyMetered ? '0.000 kWh' : (trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0).toFixed(2))));
            // Average consumption: kWh/100km or %/100km — convert per-100 rate
            // when the user is on miles (kWh/100mi = kWh/100km / KM_TO_MI).
            const tripDist = trip.distanceKm || trip.distance_km || 0;
            // No blanket recovered check here — a checkpoint-enriched recovered
            // trip has real displayEnergy/socDelta, and the branches below
            // already fall through to '--' correctly when neither is present
            // (the unenriched recovered case), same reasoning as the fields above.
            if (tripDist > 0.1 && displayEnergy !== 0) {
                const per100km = (displayEnergy / tripDist) * 100;
                this.setEl('detailConsumption', BYD.units.per100Val(per100km).toFixed(2));
            } else if (tripDist > 0.1 && energyMetered) {
                // Metered zero over a real distance — report the rate as 0, not
                // "--": the vehicle measured it and it genuinely drew nothing
                // (e.g. a PHEV leg driven entirely on the engine).
                this.setEl('detailConsumption', (0).toFixed(2));
            } else if (tripDist > 0.1) {
                const socDelta = (trip.socStart || trip.soc_start || 0) - (trip.socEnd || trip.soc_end || 0);
                if (socDelta > 0) {
                    const socPer100km = (socDelta / tripDist) * 100;
                    this.setEl('detailConsumption', BYD.units.per100Val(socPer100km).toFixed(2) + '%');
                } else {
                    this.setEl('detailConsumption', '--');
                }
            } else {
                this.setEl('detailConsumption', '--');
            }
            // Distance-per-energy efficiency (km/kWh or mi/kWh). Measured-kWh
            // only — mirrors the period-summary tile.
            // No recovered check — detailEnergy > 0 already only holds for real
            // measured energy (checkpoint-enriched or normal), same reasoning
            // as detailConsumption above.
            if (tripDist > 0.1 && detailEnergy > 0) {
                const kmPerKwh = tripDist / detailEnergy;
                this.setEl('detailEfficiency2', BYD.units.effVal(kmPerKwh).toFixed(1));
            } else {
                this.setEl('detailEfficiency2', '--');
            }
            this.setEl('detailDistance', BYD.units.distVal(trip.distanceKm || trip.distance_km || 0).toFixed(2));
            this.setEl('detailAvgSpeed', BYD.units.speedVal(trip.avgSpeedKmh || trip.avg_speed_kmh || 0).toFixed(2));
            this.setEl('detailMaxSpeed', BYD.units.speedVal(trip.maxSpeedKmh || trip.max_speed_kmh || 0).toFixed(2));
            this.setEl('detailSocStart', (recovered && !detailHasRealSoc) ? '--' : (trip.socStart || trip.soc_start || 0).toFixed(2) + '%');
            this.setEl('detailSocEnd', (recovered && !detailHasRealSoc) ? '--' : (trip.socEnd || trip.soc_end || 0).toFixed(2) + '%');

            // Odometer tiles — absolute start/end readings, unit-aware. Only
            // shown when both are present (>0); recovered trips and HALs that
            // don't report the odometer leave these at 0, so the tiles hide
            // rather than show "--"/"0" (mirrors the PHEV fuel-tile gating).
            const detailOdoStart = trip.odometerStartKm || trip.odometer_start_km || 0;
            const detailOdoEnd = trip.odometerEndKm || trip.odometer_end_km || 0;
            const odoStartTile = document.getElementById('detailOdoStartTile');
            const odoEndTile = document.getElementById('detailOdoEndTile');
            if (detailOdoStart > 0 && detailOdoEnd > 0) {
                // Show a decimal when the two readings are less than 10 units
                // apart, otherwise whole numbers stay readable. Without this a
                // short trip displays the same value twice, which reads as a bug.
                // The span is measured in DISPLAY units so the threshold means
                // the same thing in km and miles.
                const odoSpan = Math.abs(BYD.units.distVal(detailOdoEnd) - BYD.units.distVal(detailOdoStart));
                const odoDecimals = odoSpan < 10 ? 1 : null;
                this.setEl('detailOdoStart', BYD.units.dist(detailOdoStart, odoDecimals));
                this.setEl('detailOdoEnd', BYD.units.dist(detailOdoEnd, odoDecimals));
                if (odoStartTile) odoStartTile.style.display = '';
                if (odoEndTile) odoEndTile.style.display = '';
            } else {
                if (odoStartTile) odoStartTile.style.display = 'none';
                if (odoEndTile) odoEndTile.style.display = 'none';
            }

            // PHEV fuel tiles — only show when both start and end readings
            // are present. Mirrors how Start SoC tile is unconditional but
            // these are gated on data availability (BEV trips fall through).
            const detailFuelStart = (trip.fuelPctStart != null) ? trip.fuelPctStart
                : (trip.fuel_pct_start != null) ? trip.fuel_pct_start : -1;
            const detailFuelEnd = (trip.fuelPctEnd != null) ? trip.fuelPctEnd
                : (trip.fuel_pct_end != null) ? trip.fuel_pct_end : -1;
            const detailLitres = trip.litresUsed || trip.litres_used || 0;
            const fuelStartTile = document.getElementById('detailFuelStartTile');
            const fuelEndTile = document.getElementById('detailFuelEndTile');
            const litresTile = document.getElementById('detailLitresUsedTile');
            if (detailFuelStart >= 0 && detailFuelEnd >= 0) {
                this.setEl('detailFuelStart', detailFuelStart.toFixed(2) + '%');
                this.setEl('detailFuelEnd', detailFuelEnd.toFixed(2) + '%');
                if (fuelStartTile) fuelStartTile.style.display = '';
                if (fuelEndTile) fuelEndTile.style.display = '';
                if (detailLitres > 0) {
                    var litresLabel = this.fuelUnit === 'gal'
                        ? (detailLitres / this.LITRES_PER_GAL).toFixed(2) + ' gal'
                        : detailLitres.toFixed(2) + ' L';
                    this.setEl('detailLitresUsed', litresLabel);
                    if (litresTile) litresTile.style.display = '';
                } else if (litresTile) {
                    litresTile.style.display = 'none';
                }
            } else {
                if (fuelStartTile) fuelStartTile.style.display = 'none';
                if (fuelEndTile) fuelEndTile.style.display = 'none';
                if (litresTile) litresTile.style.display = 'none';
            }

            this.setEl('detailTemp', (trip.extTempC || trip.ext_temp_c || '--') + (trip.extTempC || trip.ext_temp_c ? '°C' : ''));
            // Elevation data
            const elevGain = trip.elevationGainM || trip.elevation_gain_m || 0;
            const elevLoss = trip.elevationLossM || trip.elevation_loss_m || 0;
            this.setEl('detailElevGain', elevGain > 0 ? '+' + Math.round(elevGain) + 'm' : '--');
            this.setEl('detailElevLoss', elevLoss > 0 ? '-' + Math.round(elevLoss) + 'm' : '--');
            // Gradient profile pill
            const gradProfile = trip.gradientProfile || trip.gradient_profile || '';
            const gradEl = document.getElementById('detailGradientPill');
            if (gradEl && gradProfile) {
                const gradLabels = { FLAT: BYD.i18n.t('trip.grad_profile.flat'), HILLY: BYD.i18n.t('trip.grad_profile.hilly'), MOUNTAIN_CLIMB: BYD.i18n.t('trip.grad_profile.climb'), MOUNTAIN_DESCENT: BYD.i18n.t('trip.grad_profile.descent') };
                const gradColors = { FLAT: 'rgba(34,197,94,0.15);color:#22C55E', HILLY: 'rgba(245,158,11,0.15);color:#F59E0B', MOUNTAIN_CLIMB: 'rgba(239,68,68,0.15);color:#EF4444', MOUNTAIN_DESCENT: 'rgba(14,165,233,0.15);color:#0EA5E9' };
                gradEl.innerHTML = gradLabels[gradProfile] || gradProfile;
                gradEl.style.cssText = 'display:inline-flex;padding:3px 10px;border-radius:20px;font-size:11px;font-weight:600;background:' + (gradColors[gradProfile] || gradColors.FLAT);
            } else if (gradEl) {
                gradEl.style.display = 'none';
            }
            // Trip cost — total stays in the existing detail tile, with a
            // separate breakdown card for PHEV trips that ran ICE.
            const detailCost = trip.tripCost || trip.trip_cost || 0;
            const detailCurrency = trip.currency || this.currency || '$';
            this.setEl('detailCost', (!recovered && detailCost > 0) ? detailCurrency + detailCost.toFixed(2) : '--');
            if (recovered) { this.renderCostBreakdown(null, detailCurrency); } else { this.renderCostBreakdown(trip, detailCurrency); }

            // A recovered trip has no driving-DNA scores (not in telemetry), so
            // hide the whole breakdown card + recovered banner instead of drawing
            // five empty 0/100 bars that read as a genuine zero-score trip.
            this.applyRecoveredDetailState(recovered);
            if (!recovered) {
                this.renderScoreBar('scoreAnticipation', 'scoreAnticipationVal', trip.anticipationScore || trip.anticipation_score || 0);
                this.renderScoreBar('scoreSmoothness', 'scoreSmoothnessVal', trip.smoothnessScore || trip.smoothness_score || 0);
                this.renderScoreBar('scoreSpeedDisc', 'scoreSpeedDiscVal', trip.speedDisciplineScore || trip.speed_discipline_score || 0);
                this.renderScoreBar('scoreEfficiency', 'scoreEfficiencyVal', trip.efficiencyScore || trip.efficiency_score || 0);
                this.renderScoreBar('scoreConsistency', 'scoreConsistencyVal', trip.consistencyScore || trip.consistency_score || 0);
            }

            this.renderMicroMoments(trip.microMomentsJson || trip.micro_moments_json);
            if (!this._isCurrentDetailRequest(detailRequest)) return;

            // Bind deletion only after this request's complete summary is on
            // screen. JavaScript runs this synchronous render atomically, so a
            // click can never observe trip B's summary with trip A's ID.
            this.currentTripId = trip.id != null ? trip.id : tripId;
            summaryBound = true;
            const deleteBtn = document.getElementById('detailDeleteBtn');
            if (deleteBtn) deleteBtn.disabled = false;

            this.loadRouteComparison(trip, detailRequest);

            // Fetch telemetry (may be unavailable for older trips)
            console.log('[Trips] Trip telemetry path:', trip.telemetryFilePath || trip.telemetry_file_path || 'NONE');
            if (!this._hasTelemetryArtifact(trip)) {
                this._resetDetailTelemetryState(detailRequest);
                return;
            }

            try {
                console.log('[Trips] Fetching telemetry for trip ' + tripId);
                const telemetryUrl = '/api/trips/' + tripId + '/telemetry';
                const telResp = detailRequest.controller
                    ? await fetch(telemetryUrl, { signal: detailRequest.controller.signal })
                    : await fetch(telemetryUrl);
                if (!this._isCurrentDetailRequest(detailRequest)) return;
                console.log('[Trips] Telemetry response status:', telResp.status);
                if (!telResp.ok) {
                    this._resetDetailTelemetryState(detailRequest);
                    return;
                }

                const telData = await telResp.json();
                if (!this._isCurrentDetailRequest(detailRequest)) return;
                console.log('[Trips] Telemetry data: success=' + telData.success + ' samples=' + (telData.telemetry ? telData.telemetry.length : 0));
                if (!telData.success || !this._isUsableTelemetry(telData.telemetry)) {
                    this._resetDetailTelemetryState(detailRequest);
                    return;
                }

                const samples = telData.telemetry;
                this.telemetryCache = samples;
                this.currentTripData = trip;

                // Setup timeline slider
                this.setupTimelineSlider(samples);

                // Isolate each canvas renderer so a throw in one (e.g. an
                // unsupported Canvas2D API on the old WebView) can't abort the
                // others OR prevent the route map below from being scheduled —
                // they all used to share this one try-block, so a single
                // exception blanked the timeline, the histogram AND the map.
                const ribbonCanvas = document.getElementById('timelineChart');
                if (ribbonCanvas) {
                    try { this.renderTimeline(ribbonCanvas, samples); }
                    catch (e) {
                        this._clearDetailCanvas('timelineChart');
                        this.setEl('tlAccelPct', '--%');
                        this.setEl('tlCoastPct', '--%');
                        this.setEl('tlBrakePct', '--%');
                        console.error('[Trips] renderTimeline failed:', e.message || e);
                    }
                }
                const histCanvas = document.getElementById('speedHistogram');
                if (histCanvas) {
                    try { this.renderSpeedHistogram(histCanvas, samples); }
                    catch (e) {
                        this._clearDetailCanvas('speedHistogram');
                        const summary = document.getElementById('speedHistSummary');
                        if (summary) summary.innerHTML = '';
                        console.error('[Trips] renderSpeedHistogram failed:', e.message || e);
                    }
                }
                const mapContainer = document.getElementById('tripMap');
                console.log('[Trips] Map container:', mapContainer ? (mapContainer.offsetWidth + 'x' + mapContainer.offsetHeight) : 'NOT FOUND');
                console.log('[Trips] Leaflet available:', typeof L !== 'undefined');
                if (mapContainer) {
                    // Delay map render to ensure container is visible and has dimensions.
                    // renderRouteMap has its own retry logic for Leaflet loading and
                    // container layout, so we just need a small initial delay.
                    this._detailMapTimer = setTimeout(() => {
                        this._detailMapTimer = null;
                        if (!this._isCurrentDetailRequest(detailRequest)) return;
                        console.log('[Trips] Calling renderRouteMap with ' + samples.length + ' samples');
                        this.renderRouteMap(mapContainer, samples, detailRequest);
                    }, 150);
                }
            } catch (e) {
                if (!this._isCurrentDetailRequest(detailRequest) || this._isAbortError(e)) return;
                this._resetDetailTelemetryState(detailRequest);
                console.error('[Trips] Telemetry/map error:', e.message || e);
            }
        } catch (e) {
            if (!this._isCurrentDetailRequest(detailRequest) || this._isAbortError(e)) return;
            if (summaryBound) this._resetDetailTelemetryState(detailRequest);
            else this._resetDetailViewState();
            console.warn('[Trips] Detail load failed:', e);
        }
    },

    hideDetail() {
        this._cancelDetailRequest();
        this._resetDetailViewState();
        const detail = document.getElementById('tripDetail');
        const list = document.getElementById('tripListView');
        if (detail) detail.classList.remove('active');
        if (list) list.classList.remove('hidden');
    },

    // ==================== TIMELINE SLIDER ====================

    _selectTimelineAxis(samples) {
        let useElapsed = !!(samples && samples.length);
        let previousElapsed = -1;
        for (let i = 0; useElapsed && i < samples.length; i++) {
            const elapsed = samples[i] && samples[i].e;
            if (typeof elapsed !== 'number' || !isFinite(elapsed)
                    || elapsed < 0 || (i > 0 && elapsed < previousElapsed)) {
                useElapsed = false;
                break;
            }
            previousElapsed = elapsed;
        }

        const key = useElapsed ? 'e' : 't';
        const start = samples && samples.length ? samples[0][key] : 0;
        const end = samples && samples.length ? samples[samples.length - 1][key] : start;
        return {
            samples: samples,
            key: key,
            start: start,
            end: end,
            range: end - start || 1
        };
    },

    _timelineAxisFor(samples) {
        if (!this._timelineAxis || this._timelineAxis.samples !== samples) {
            this._timelineAxis = this._selectTimelineAxis(samples);
        }
        return this._timelineAxis;
    },

    setupTimelineSlider(samples) {
        const card = document.getElementById('timelineSliderCard');
        if (!card || samples.length < 2) return;
        card.style.display = 'block';

        const slider = document.getElementById('timelineSlider');
        if (!slider) return;
        slider.max = samples.length - 1;
        slider.value = 0;

        const axis = this._timelineAxisFor(samples);
        const durMin = Math.round((axis.end - axis.start) / 60000);
        this.setEl('sliderStartTime', '0:00');
        this.setEl('sliderEndTime', durMin + ' min');

        // Hover scrub — moving mouse over slider area scrubs the position
        const self = this;
        const wrap = slider.parentElement;
        if (wrap) {
            wrap.onmousemove = function(e) {
                const rect = wrap.getBoundingClientRect();
                const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
                const idx = Math.round(pct * (samples.length - 1));
                slider.value = idx;
                self.updateSliderDisplay(idx);
            };
        }

        this.updateSliderDisplay(0);
    },

    onSliderInput(val) {
        const idx = parseInt(val);
        this.updateSliderDisplay(idx);
    },

    /**
     * Compute a smoothed bearing for the sample at `idx` by averaging
     * the great-circle bearings of pairs taken from a small window
     * ahead and behind. Single-pair bearings on noisy GPS produce a
     * jittery rotation; the windowed average plus circular-mean math
     * (sum sines and cosines, then atan2) gives a stable heading even
     * when individual bearings straddle the ±180° wrap.
     *
     * Returns degrees in [-180, 180], or null if there's no usable
     * pair (e.g. trip ended at idx 0, GPS hadn't moved yet).
     */
    _smoothedHeading: function (samples, idx) {
        if (!samples || samples.length < 2 || idx == null) return null;
        var sumSin = 0;
        var sumCos = 0;
        var pairs = 0;
        // Window: average up to 5 forward + 5 backward bearings around
        // idx. Pairs separated by < ~3.3m (lat/lon delta < 3e-5°) are
        // skipped because the GPS rounding noise dominates over real
        // motion at that scale.
        var minDelta = 3e-5;
        var window = 5;
        var lo = Math.max(0, idx - window);
        var hi = Math.min(samples.length - 1, idx + window);
        for (var i = lo; i < hi; i++) {
            var a = samples[i];
            var b = samples[i + 1];
            if (!a || !b || !a.la || !a.lo || !b.la || !b.lo) continue;
            if (Math.abs(b.la - a.la) < minDelta && Math.abs(b.lo - a.lo) < minDelta) continue;
            var dLon = (b.lo - a.lo) * Math.PI / 180;
            var lat1 = a.la * Math.PI / 180;
            var lat2 = b.la * Math.PI / 180;
            var y = Math.sin(dLon) * Math.cos(lat2);
            var x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
            var bearingRad = Math.atan2(y, x);
            sumSin += Math.sin(bearingRad);
            sumCos += Math.cos(bearingRad);
            pairs++;
        }
        if (pairs === 0) return null;
        // Circular mean — handles wraparound naturally.
        return Math.atan2(sumSin / pairs, sumCos / pairs) * 180 / Math.PI;
    },

    updateSliderDisplay(idx) {
        const samples = this.telemetryCache;
        if (!samples || idx >= samples.length) return;
        const s = samples[idx];
        const axis = this._timelineAxisFor(samples);
        const elapsed = (s[axis.key] - axis.start) / 1000;
        const min = Math.floor(elapsed / 60);
        const sec = Math.floor(elapsed % 60);

        // 1. Sync Text HUD — speed sample is km/h, convert to user's unit
        this.setEl('sliderSpeed', Math.round(BYD.units.speedVal(s.s || 0)));
        this.setEl('sliderAccel', s.a || 0);
        this.setEl('sliderBrake', s.b || 0);
        this.setEl('sliderCurrentTime', min + ':' + (sec < 10 ? '0' : '') + sec);

        // SoC interpolated
        const tripData = this.currentTripData;
        if (tripData) {
            const socS = parseFloat(tripData.socStart || tripData.soc_start || 0);
            const socE = parseFloat(tripData.socEnd || tripData.soc_end || 0);
            const totalSamples = samples.length - 1;
            const socAtIdx = totalSamples > 0 ? socS + (socE - socS) * (idx / totalSamples) : socS;
            this.setEl('sliderSoc', socAtIdx.toFixed(1));
        }

        // 2. Sync Map Marker with heading rotation
        if (this.leafletMap && this.sliderMarker && s.la && s.lo) {
            this.sliderMarker.setLatLng([s.la, s.lo]);
            // Compute a smoothed heading.
            //
            // Two sources of jitter the user reported:
            //   (a) GPS noise on consecutive samples flips the bearing
            //       30-120° in a single tick at slow / standstill speed.
            //   (b) Crossing the ±180° wraparound rotates the long way
            //       around (the wrapper transform interpolates through
            //       359° instead of -1°).
            //
            // Fix: average a window of forward + backward samples
            // (smoother than a single pair) and, when assigning the
            // new wrapper transform, normalise to the shortest arc
            // from the previous angle so the CSS transition tweens
            // through 0° and not the long way.
            var telSamples = this.telemetryCache || [];
            var heading = this._smoothedHeading(telSamples, idx);
            if (heading !== null) {
                var wrapper = this.sliderMarker.getElement();
                if (wrapper) {
                    var img = wrapper.querySelector('.car-icon-wrapper');
                    if (img) {
                        // Continuity: pick the equivalent angle that
                        // is within ±180° of the previously applied
                        // rotation so the transition is always the
                        // short way around the circle. Without this,
                        // 170° → -170° animates as +340° instead of
                        // +20° and reads as a violent spin.
                        var prev = (img._lastHeading == null) ? heading : img._lastHeading;
                        var delta = heading - prev;
                        while (delta >  180) delta -= 360;
                        while (delta < -180) delta += 360;
                        var next = prev + delta;
                        img._lastHeading = next;
                        // CSS transition (set in stylesheet on
                        // .car-icon-wrapper) tweens this delta over
                        // the scrub-tick interval so the icon glides
                        // between samples instead of snapping.
                        img.style.transform = 'rotate(' + next + 'deg)';
                    }
                }
            }
        }

        // 3. Sync Timeline chart scrubber
        const tlCanvas = document.getElementById('timelineChart');
        if (tlCanvas && samples.length > 1) {
            this.renderTimeline(tlCanvas, samples, idx);
        }
    },

    // ==================== SCORE DETAIL TOGGLE ====================

    toggleScoreDetail(row) {
        row.classList.toggle('expanded');
    },

    // ==================== ROUTE COMPARISON ====================

    async loadRouteComparison(trip, detailRequest) {
        if (!this._isCurrentDetailRequest(detailRequest)) return;
        const card = document.getElementById('routeComparisonCard');
        const content = document.getElementById('routeComparisonContent');
        if (!card || !content) return;

        const startLat = trip.startLat || trip.start_lat || 0;
        if (startLat === 0) { card.style.display = 'none'; return; }

        try {
            const tripId = trip.id;
            const url = '/api/trips/' + tripId + '/similar';
            const resp = detailRequest.controller
                ? await fetch(url, { signal: detailRequest.controller.signal })
                : await fetch(url);
            const data = await resp.json();
            if (!this._isCurrentDetailRequest(detailRequest)) return;
            if (!data.success || data.count === 0) { card.style.display = 'none'; return; }

            card.style.display = 'block';
            const stats = data.stats;
            const similar = data.similar || [];
            const tripEnergy = trip.energyUsedKwh || trip.energy_used_kwh || 0;
            const tripCost = trip.tripCost || trip.trip_cost || 0;
            const avgCost = stats.avgCost || 0;
            const currency = trip.currency || this.currency || '$';
            const tripDur = trip.durationSeconds || trip.duration_seconds || 0;
            const avgDur = stats.avgDurationSeconds || 0;
            const tripDist = trip.distanceKm || trip.distance_km || 0;

            // Compute avg energy from similar trips
            var sumEnergy = 0, energyCount = 0;
            similar.forEach(function(t) {
                var e = t.energyUsedKwh || t.energy_used_kwh || 0;
                if (e > 0) { sumEnergy += e; energyCount++; }
            });
            var avgEnergy = energyCount > 0 ? sumEnergy / energyCount : 0;

            // Route rank
            var rank = 1;
            var currentEff = trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0;
            similar.forEach(function(t) {
                if ((t.efficiencySocPerKm || t.efficiency_soc_per_km || 999) < currentEff) rank++;
            });
            var totalOnRoute = data.count + 1;

            let html = '';

            // Summary banner
            var energyDelta = tripEnergy - avgEnergy;
            var energyPct = avgEnergy > 0 ? Math.abs(energyDelta / avgEnergy * 100).toFixed(0) : 0;
            var isBetter = energyDelta < 0;

            if (avgEnergy > 0 && tripEnergy > 0) {
                if (isBetter) {
                    html += '<div style="padding:12px 14px;background:rgba(34,197,94,0.08);border:1px solid rgba(34,197,94,0.2);border-radius:12px;margin-bottom:12px;">';
                    html += '<div style="font-size:14px;font-weight:600;color:#22C55E;">🎉 Used ' + energyPct + '% less energy than usual</div>';
                    html += '<div style="font-size:12px;color:var(--text-secondary);margin-top:4px;">#' + rank + ' of ' + totalOnRoute + ' trips on this route</div>';
                    html += '</div>';
                } else {
                    html += '<div style="padding:12px 14px;background:rgba(245,158,11,0.08);border:1px solid rgba(245,158,11,0.2);border-radius:12px;margin-bottom:12px;">';
                    html += '<div style="font-size:14px;font-weight:600;color:var(--warning);">📊 Used ' + energyPct + '% more energy than usual</div>';
                    html += '<div style="font-size:12px;color:var(--text-secondary);margin-top:4px;">#' + rank + ' of ' + totalOnRoute + ' trips on this route</div>';
                    html += '</div>';
                }
            }

            // Modern comparison cards — this trip vs route avg
            html += '<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px;">';
            // This trip
            html += '<div style="padding:12px;background:var(--bg-elevated);border-radius:10px;border:1px solid var(--border-subtle);">';
            html += '<div style="font-size:10px;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;">This trip</div>';
            if (tripEnergy > 0) html += '<div style="font-size:13px;color:var(--text-primary);margin-bottom:4px;">' + this.ICON_ELECTRIC + ' ' + tripEnergy.toFixed(1) + ' kWh</div>';
            html += '<div style="font-size:13px;color:var(--text-primary);margin-bottom:4px;">⏱ ' + Math.round(tripDur/60) + ' min</div>';
            if (tripCost > 0) html += '<div style="font-size:13px;color:var(--text-primary);">💰 ' + currency + tripCost.toFixed(1) + '</div>';
            html += '</div>';
            // Route avg
            html += '<div style="padding:12px;background:var(--bg-elevated);border-radius:10px;border:1px solid var(--border-subtle);">';
            html += '<div style="font-size:10px;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;">Route average</div>';
            if (avgEnergy > 0) html += '<div style="font-size:13px;color:var(--text-secondary);margin-bottom:4px;">' + this.ICON_ELECTRIC + ' ' + avgEnergy.toFixed(1) + ' kWh</div>';
            html += '<div style="font-size:13px;color:var(--text-secondary);margin-bottom:4px;">⏱ ' + Math.round(avgDur/60) + ' min</div>';
            if (avgCost > 0) html += '<div style="font-size:13px;color:var(--text-secondary);">💰 ' + currency + avgCost.toFixed(1) + '</div>';
            html += '</div>';
            html += '</div>';

            // Sparkline
            if (similar.length >= 2) {
                html += '<div style="font-size:11px;color:var(--text-muted);margin-bottom:4px;">Energy trend (oldest → newest)</div>';
                html += '<canvas id="routeSparkline" class="sparkline-container" style="width:100%;height:40px;"></canvas>';
            }

            // Compare on Map button
            if (stats.bestTripId > 0) {
                html += '<button onclick="TRIPS.showRouteMapComparison(' + tripId + ',' + stats.bestTripId + ',' + (stats.worstTripId > 0 ? stats.worstTripId : -1) + ')" style="width:100%;padding:10px;margin:8px 0;background:var(--bg-elevated);border:1px solid var(--border-subtle);border-radius:8px;color:var(--brand-primary);font-size:13px;font-weight:600;cursor:pointer;">🗺️ Compare on Map</button>';
            }

            // Recent trips — clickable links
            html += '<div style="font-size:11px;color:var(--text-muted);margin:8px 0 6px;text-transform:uppercase;letter-spacing:0.5px;">Trips on this route (' + data.count + ')</div>';
            similar.slice(0, 5).forEach(function(t) {
                var date = new Date(t.startTime || t.start_time).toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
                var energy = (t.energyUsedKwh || t.energy_used_kwh || 0);
                var cost = t.tripCost || t.trip_cost || 0;
                var isBest = t.id === stats.bestTripId;
                html += '<div class="route-comparison-item" style="cursor:pointer;" onclick="TRIPS.showDetail(' + t.id + ')">';
                html += '<span class="route-comparison-date">' + date + (isBest ? ' 🏆' : '') + '</span>';
                html += '<span class="route-comparison-eff">' + (energy > 0 ? energy.toFixed(1) + ' kWh' : '--') + (cost > 0 ? ' · ' + currency + cost.toFixed(0) : '') + '</span>';
                html += '<span style="color:var(--brand-primary);font-size:12px;">→</span>';
                html += '</div>';
            });

            content.innerHTML = html;

            // Draw sparkline (energy-based)
            if (similar.length >= 2) {
                var energyPoints = similar.slice().reverse().map(function(t) { return t.energyUsedKwh || t.energy_used_kwh || 0; });
                energyPoints.push(tripEnergy);
                this._routeSparklineTimer = setTimeout(function() {
                    TRIPS._routeSparklineTimer = null;
                    if (!TRIPS._isCurrentDetailRequest(detailRequest)) return;
                    TRIPS.drawRouteSparkline(energyPoints);
                }, 50);
            }
        } catch (e) {
            if (!this._isCurrentDetailRequest(detailRequest) || this._isAbortError(e)) return;
            console.warn('[Trips] Route comparison error:', e);
            card.style.display = 'none';
        }
    },

    drawRouteSparkline(points) {
        const canvas = document.getElementById('routeSparkline');
        if (!canvas || points.length < 2) return;
        const ctx = canvas.getContext('2d');
        const dpr = window.devicePixelRatio || 1;
        canvas.width = canvas.offsetWidth * dpr;
        canvas.height = 40 * dpr;
        ctx.scale(dpr, dpr);
        const w = canvas.offsetWidth, h = 40;

        const min = Math.min.apply(null, points) * 0.9;
        const max = Math.max.apply(null, points) * 1.1;
        const range = max - min || 1;

        ctx.beginPath();
        ctx.strokeStyle = 'rgba(99,102,241,0.6)';
        ctx.lineWidth = 2;
        points.forEach(function(v, i) {
            var x = (i / (points.length - 1)) * (w - 8) + 4;
            var y = h - 4 - ((v - min) / range) * (h - 8);
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.stroke();

        // Highlight current trip (last point)
        var lastVal = points[points.length - 1];
        var lastX = w - 4;
        var lastY = h - 4 - ((lastVal - min) / range) * (h - 8);
        ctx.beginPath();
        ctx.arc(lastX, lastY, 4, 0, Math.PI * 2);
        ctx.fillStyle = 'var(--brand-primary)';
        ctx.fill();
    },

    routeCompareMapInstance: null,

    async showRouteMapComparison(currentId, bestId, worstId) {
        const detailRequest = this._activeDetailRequest;
        if (!this._isCurrentDetailRequest(detailRequest)
                || this.currentTripId == null
                || String(this.currentTripId) !== String(currentId)) return;
        const routeMapSequence = (this._routeMapRequestSequence || 0) + 1;
        this._routeMapRequestSequence = routeMapSequence;
        const isCurrentRouteMap = () => {
            return routeMapSequence === this._routeMapRequestSequence
                && this._isCurrentDetailRequest(detailRequest)
                && this.currentTripId != null
                && String(this.currentTripId) === String(currentId);
        };

        const overlay = document.getElementById('routeMapOverlay');
        const mapDiv = document.getElementById('routeCompareMap');
        const legend = document.getElementById('routeMapLegend');
        if (!overlay || !mapDiv) return;

        // Destroy previous map instance
        if (this.routeCompareMapInstance) {
            this.routeCompareMapInstance.remove();
            this.routeCompareMapInstance = null;
        }

        overlay.style.display = 'block';
        mapDiv.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--text-muted);">' + BYD.i18n.t('trip.loading_routes') + '</div>';

        try {
            // Fetch GPS traces in parallel
            const fetchGps = (id) => {
                const url = '/api/trips/' + id + '/gps';
                return detailRequest.controller
                    ? fetch(url, { signal: detailRequest.controller.signal })
                    : fetch(url);
            };
            const fetches = [fetchGps(currentId)];
            if (bestId > 0) fetches.push(fetchGps(bestId));
            if (worstId > 0 && worstId !== bestId) fetches.push(fetchGps(worstId));

            const responses = await Promise.all(fetches);
            const data = await Promise.all(responses.map(function(r) { return r.json(); }));
            if (!isCurrentRouteMap()) return;

            const currentGps = data[0].success ? data[0].gps : [];
            const bestGps = data[1] && data[1].success ? data[1].gps : [];
            const worstGps = data[2] && data[2].success ? data[2].gps : [];

            if (currentGps.length === 0) {
                mapDiv.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);">' + BYD.i18n.t('trip.no_gps_data') + '</div>';
                return;
            }

            // Create Leaflet map
            if (typeof L === 'undefined') {
                mapDiv.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);">' + BYD.i18n.t('trip.map_unavailable') + '</div>';
                return;
            }
            mapDiv.innerHTML = '';
            const map = L.map(mapDiv, { zoomControl: false, attributionControl: false });
            this.routeCompareMapInstance = map;
            // Shared keyless OSM.de tiles follow the current day/night theme.
            BYD.theme.attachMapTiles(map);

            const bounds = L.latLngBounds();

            // Draw worst first (bottom layer)
            if (worstGps.length > 0) {
                var worstLine = L.polyline(worstGps, { color: '#EF4444', weight: 5, opacity: 0.5, dashArray: '10,8' }).addTo(map);
                bounds.extend(worstLine.getBounds());
            }
            // Best
            if (bestGps.length > 0) {
                var bestLine = L.polyline(bestGps, { color: '#22C55E', weight: 5, opacity: 0.7 }).addTo(map);
                bounds.extend(bestLine.getBounds());
            }
            // Current on top
            var currentLine = L.polyline(currentGps, { color: '#6366F1', weight: 5, opacity: 1.0 }).addTo(map);
            bounds.extend(currentLine.getBounds());

            // Start marker (same as trip detail)
            var startPoint = currentGps[0];
            var startIcon = L.divIcon({
                className: '',
                html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#22C55E;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="16" height="16"><polygon points="5,3 19,12 5,21"/></svg></div>',
                iconSize: [32, 32],
                iconAnchor: [16, 16]
            });
            L.marker(startPoint, { icon: startIcon }).bindTooltip(BYD.i18n.t('trip.marker_start'), { permanent: false, direction: 'top' }).addTo(map);

            // End marker (same as trip detail)
            var endPoint = currentGps[currentGps.length - 1];
            var endIcon = L.divIcon({
                className: '',
                html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#EF4444;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="14" height="14"><rect x="6" y="6" width="12" height="12" rx="2"/></svg></div>',
                iconSize: [32, 32],
                iconAnchor: [16, 16]
            });
            L.marker(endPoint, { icon: endIcon }).bindTooltip(BYD.i18n.t('trip.marker_end'), { permanent: false, direction: 'top' }).addTo(map);

            map.fitBounds(bounds, { padding: [20, 20] });

            // Legend
            if (!isCurrentRouteMap()) {
                map.remove();
                return;
            }
            legend.innerHTML = '<span style="display:flex;align-items:center;gap:4px;"><span style="width:20px;height:4px;background:#6366F1;border-radius:2px;"></span>' + BYD.i18n.t('trip.route_legend_current') + '</span>' +
                '<span style="display:flex;align-items:center;gap:4px;"><span style="width:20px;height:4px;background:#22C55E;border-radius:2px;"></span>' + BYD.i18n.t('trip.route_legend_best') + '</span>' +
                (worstGps.length > 0 ? '<span style="display:flex;align-items:center;gap:4px;"><span style="width:20px;height:4px;background:#EF4444;border-radius:2px;"></span>' + BYD.i18n.t('trip.route_legend_worst') + '</span>' : '');

        } catch (e) {
            if (!isCurrentRouteMap() || this._isAbortError(e)) return;
            console.warn('[Trips] Route map error:', e);
            mapDiv.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);">' + BYD.i18n.t('trip.routes_load_failed') + '</div>';
        }
    },

    // ==================== TRIP BREAKDOWN ====================

    renderScoreBar(fillId, valId, score) {
        const fill = document.getElementById(fillId);
        const val = document.getElementById(valId);
        if (fill) {
            fill.style.width = score + '%';
            fill.classList.remove('low', 'mid');
            if (score < 40) fill.classList.add('low');
            else if (score < 70) fill.classList.add('mid');
        }
        if (val) val.textContent = score;
    },

    /**
     * Toggle the detail view's recovered-trip presentation: show the
     * explanatory banner and hide the Driving-DNA breakdown card (it would
     * otherwise render five empty 0/100 bars that read as a genuine zero
     * score). Idempotent and symmetric — passing false on the next (live)
     * trip restores the normal layout.
     */
    applyRecoveredDetailState(recovered) {
        const banner = document.getElementById('detailRecoveredBanner');
        if (banner) banner.style.display = recovered ? 'flex' : 'none';
        const scoreCard = document.getElementById('scoreBreakdownCard');
        if (scoreCard) scoreCard.style.display = recovered ? 'none' : '';
    },

    /**
     * Cost breakdown card. Visible only for PHEV trips with at least one
     * non-zero leg. Container is created lazily and inserted right after
     * #detailCost's grid row so it adopts the existing detail-card look.
     * BEV trips (or PHEV trips that stayed full-EV) hide the container —
     * no regression vs. the pre-PHEV detail layout.
     */
    renderCostBreakdown(trip, currency) {
        // A null trip (recovered: no cost data) collapses the card, same as BEV.
        var isPhev = !!(trip && (trip.isPhev || trip.is_phev));
        var fuelCost = (trip && (trip.fuelCost || trip.fuel_cost)) || 0;
        var electricCost = (trip && (trip.electricCost || trip.electric_cost)) || 0;

        // Bail early on BEV / pure-EV-mode PHEV before touching the DOM,
        // so we never mount an empty phantom card on non-PHEV detail views.
        if (!isPhev || (fuelCost <= 0 && electricCost <= 0)) {
            var existing = document.getElementById('costBreakdown');
            if (existing) existing.style.display = 'none';
            return;
        }

        var container = document.getElementById('costBreakdown');
        if (!container) {
            // Anchor explicitly to #detailSummary so a future class-rename of
            // .detail-summary-grid doesn't silently shift the placement.
            var anchor = document.getElementById('detailCost');
            if (!anchor) return;
            var summaryHost = document.getElementById('detailSummary')
                || (anchor.closest ? anchor.closest('.detail-summary-grid') : null)
                || anchor.parentNode;
            var card = document.createElement('div');
            card.id = 'costBreakdown';
            card.className = 'detail-card cost-breakdown';
            card.style.cssText = 'margin-top:12px;padding:12px 14px;background:var(--bg-elevated);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);display:none;';
            // Insert at the end of #detailSummary so the card sits below the
            // grid and the gradient pill, matching existing detail-card flow.
            summaryHost.appendChild(card);
            container = card;
        }

        var litres = trip.litresUsed || trip.litres_used || 0;
        var fuelPrice = trip.fuelPricePerL || trip.fuel_price_per_l || 0;
        var energyKwh = trip.energyUsedKwh || trip.energy_used_kwh || 0;
        var rate = trip.electricityRate || trip.electricity_rate || 0;
        var iceSec = trip.iceSeconds || trip.ice_seconds || 0;
        var dur = trip.durationSeconds || trip.duration_seconds || 0;
        var distKm = trip.distanceKm || trip.distance_km || 0;
        var totalCost = trip.tripCost || trip.trip_cost || 0;

        // Escape the currency symbol — every other interpolated value is a
        // numeric .toFixed string that can't contain HTML metacharacters.
        var esc = (function () {
            var d = document.createElement('div');
            return function (s) { d.textContent = s == null ? '' : String(s); return d.innerHTML; };
        })();
        var curEsc = esc(currency);

        var rows = [];
        var title = '<div style="font-size:11px;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.6px;margin-bottom:8px;">'
            + esc(BYD.i18n.t('trip.cost_breakdown_title')) + '</div>';

        if (electricCost > 0) {
            rows.push('<div style="display:flex;justify-content:space-between;font-size:13px;color:var(--text-secondary);margin-bottom:4px;">'
                + '<span>' + this.ICON_ELECTRIC + ' ' + esc(BYD.i18n.t('trip.cost_electric_line', {
                    kwh: energyKwh.toFixed(1),
                    rate: currency + rate.toFixed(2)
                  })) + '</span>'
                + '<span style="color:var(--text-primary);">' + curEsc + electricCost.toFixed(2) + '</span>'
                + '</div>');
        }
        if (fuelCost > 0) {
            rows.push('<div style="display:flex;justify-content:space-between;font-size:13px;color:var(--text-secondary);margin-bottom:4px;">'
                + '<span>' + this.ICON_PETROL + ' ' + esc(BYD.i18n.t('trip.cost_petrol_line', {
                    litres: litres.toFixed(2),
                    rate: currency + fuelPrice.toFixed(2)
                  })) + '</span>'
                + '<span style="color:var(--text-primary);">' + curEsc + fuelCost.toFixed(2) + '</span>'
                + '</div>');
        } else if (isPhev && litres <= 0 && this.tankCapacityL <= 0) {
            // PHEV with no metered fuel and no tank size set — surface a
            // one-line nudge so users know why the petrol leg is absent.
            // (When litres > 0 the burn is metered from the HAL accumulator
            // and tank capacity is irrelevant, so we don't nag here.)
            rows.push('<div style="font-size:11px;color:var(--text-muted);margin-top:4px;">'
                + esc(BYD.i18n.t('trip.cost_set_tank_hint')) + '</div>');
        }
        rows.push('<div style="display:flex;justify-content:space-between;font-size:13px;color:var(--text-primary);font-weight:600;border-top:1px solid var(--border-subtle);padding-top:6px;margin-top:6px;">'
            + '<span>' + esc(BYD.i18n.t('trip.cost_total_line')) + '</span>'
            + '<span>' + curEsc + totalCost.toFixed(2) + '</span>'
            + '</div>');

        // l/100km capsule + HEV mode share — these are useful diagnostics
        // for a PHEV trip and only render when meaningful.
        var foot = [];
        if (litres > 0 && distKm > 0.1) {
            var lp100 = (litres * 100) / distKm;
            foot.push(BYD.i18n.t('trip.consumption_l_per_100km', { value: lp100.toFixed(1) }));
        }
        // HEV mode share. Prefer DISTANCE: the engine idles in traffic and runs at low speed, so
        // its share of the trip's TIME badly understates how far the car actually moved on it —
        // a time-based "HEV mode 18%" on a 9 km trip reads as 1.6 km when ~6 km was driven on the
        // engine. iceDistanceKm is 0 on trips finalised before it was recorded; those fall back to
        // the time share, LABELLED as time so it can't be misread as distance.
        var iceKm = trip.iceDistanceKm || trip.ice_distance_km || 0;
        if (iceKm > 0 && distKm > 0.1) {
            var iceKmShown = Math.min(iceKm, distKm);
            var kmPct = Math.round(iceKmShown / distKm * 100);
            // The distance and the percentage round INDEPENDENTLY (BYD.units.distVal rounds to a
            // whole km/mi), so either can hit 0 while the other doesn't — "0 km (6%)" on a short
            // trip, "1 km (0%)" on a long one. Both are self-contradictory, so the combined line
            // requires BOTH to be >= 1, and each single-value fallback is used only when THAT
            // value is the meaningful one.
            var kmShownVal = BYD.units.distVal(iceKmShown);
            if (kmShownVal >= 1 && kmPct >= 1) {
                foot.push(BYD.i18n.t('trip.ice_share_distance_label', {
                    pct: kmPct,
                    km: kmShownVal,
                    unit: BYD.units.distLabel()
                }));
            } else if (kmShownVal >= 1) {
                // A whole unit or more, but under 1% of a long trip — state the DISTANCE only.
                foot.push(BYD.i18n.t('trip.ice_share_distance_km_only', {
                    km: kmShownVal,
                    unit: BYD.units.distLabel()
                }));
            } else if (kmPct >= 1) {
                // A meaningful share but under a whole unit — state the PERCENTAGE only.
                // "<1%" would be false here (0.5 km of 9 km is 6%), and the distance reads "0".
                foot.push(BYD.i18n.t('trip.ice_share_distance_pct_only', { pct: kmPct }));
            } else {
                // Under a whole unit AND under 1% — a brief engine burst on a hard acceleration.
                // Say "<1%" rather than printing a zero: the engine DID run, so staying silent
                // would make this read as a pure-EV trip.
                foot.push(BYD.i18n.t('trip.ice_share_distance_minimal'));
            }
        } else if (iceSec > 0 && dur > 0) {
            var pct = Math.round((iceSec / dur) * 100);
            if (pct > 0) foot.push(BYD.i18n.t('trip.ice_share_time_label', { pct: pct }));
        }
        var footHtml = foot.length > 0
            ? '<div style="font-size:11px;color:var(--text-muted);margin-top:8px;">' + foot.join(' · ') + '</div>'
            : '';

        container.innerHTML = title + rows.join('') + footHtml;
        container.style.display = '';
    },

    renderMicroMoments(json) {
        const list = document.getElementById('microMomentsList');
        if (!list) return;
        list.innerHTML = '';

        let moments = null;
        if (typeof json === 'string' && json) {
            try { moments = JSON.parse(json); } catch (e) { /* ignore */ }
        } else if (typeof json === 'object') {
            moments = json;
        }

        if (!moments) {
            list.innerHTML = '<div style="color:var(--text-muted);font-size:13px;padding:12px 0;">' + BYD.i18n.t('trip.no_micro_data') + '</div>';
            return;
        }

        const items = [];
        const tooltips = {
            'Launch Events': 'Number of hard accelerations from standstill (0→30+ km/h). Fewer = more efficient starts.',
            'Coast-Brake Events': 'Times you went directly from coasting to braking. More events = less anticipation of stops.',
            'Avg Coast Gap': 'Average time between lifting the accelerator and pressing the brake. Longer gaps = better anticipation.',
            'Pedal Smoothness (σ)': 'Standard deviation of pedal input changes. Lower σ = smoother, more consistent pedal work.'
        };
        if (moments.launchProfiles) items.push({ icon: '🚀', label: 'Launch Events', value: moments.launchProfiles.length });
        if (moments.coastBrakeEvents) {
            items.push({ icon: '🛑', label: 'Coast-Brake Events', value: moments.coastBrakeEvents.length });
            if (moments.coastBrakeEvents.length > 0) {
                const avgGap = moments.coastBrakeEvents.reduce((s, e) => s + (e.coastGapMs || e.coast_gap_ms || 0), 0) / moments.coastBrakeEvents.length / 1000;
                items.push({ icon: '⏳', label: 'Avg Coast Gap', value: avgGap.toFixed(1) + 's' });
            }
        }
        if (moments.pedalSmoothnessWindows) {
            const avgSmooth = moments.pedalSmoothnessWindows.length > 0
                ? moments.pedalSmoothnessWindows.reduce((s, w) => s + (w.stdDev || w.std_dev || 0), 0) / moments.pedalSmoothnessWindows.length
                : 0;
            items.push({ icon: '📊', label: 'Pedal Smoothness (σ)', value: avgSmooth.toFixed(1) });
        }

        items.forEach(item => {
            const el = document.createElement('div');
            el.className = 'moment-item';
            el.style.position = 'relative';
            const tip = tooltips[item.label] || '';
            el.innerHTML = '<span class="moment-icon">' + item.icon + '</span>' +
                '<div style="flex:1;"><span>' + item.label + '</span>' +
                (tip ? '<div style="font-size:10px;color:var(--text-muted);margin-top:2px;line-height:1.3;">' + tip + '</div>' : '') +
                '</div>' +
                '<span class="moment-value">' + item.value + '</span>';
            list.appendChild(el);
        });
    },

    // ==================== SCORE CIRCLE ====================

    renderScoreCircle(score) {
        const canvas = document.getElementById('scoreCircleCanvas');
        if (!canvas) return;

        const dpr = window.devicePixelRatio || 1;
        const size = 140;
        canvas.width = size * dpr;
        canvas.height = size * dpr;
        canvas.style.width = size + 'px';
        canvas.style.height = size + 'px';

        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const cx = size / 2, cy = size / 2, radius = 58, lineWidth = 8;

        let color, glowColor;
        if (score >= 80) { color = '#22C55E'; glowColor = 'rgba(34,197,94,0.3)'; }
        else if (score >= 60) { color = '#00D4AA'; glowColor = 'rgba(0,212,170,0.3)'; }
        else if (score >= 40) { color = '#F59E0B'; glowColor = 'rgba(245,158,11,0.3)'; }
        else { color = '#EF4444'; glowColor = 'rgba(239,68,68,0.3)'; }

        // Background ring
        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        ctx.strokeStyle = this.colors.arcTrack;
        ctx.lineWidth = lineWidth;
        ctx.stroke();

        const startAngle = -Math.PI / 2;
        const endAngle = startAngle + (score / 100) * Math.PI * 2;

        // Glow
        ctx.beginPath();
        ctx.arc(cx, cy, radius, startAngle, endAngle);
        ctx.strokeStyle = glowColor;
        ctx.lineWidth = lineWidth + 8;
        ctx.lineCap = 'round';
        ctx.stroke();

        // Main arc
        ctx.beginPath();
        ctx.arc(cx, cy, radius, startAngle, endAngle);
        ctx.strokeStyle = color;
        ctx.lineWidth = lineWidth;
        ctx.lineCap = 'round';
        ctx.stroke();

        // Update text
        const numberEl = document.getElementById('scoreHeroNumber');
        const starEl = document.getElementById('scoreStar');
        const labelEl = document.getElementById('scoreHeroLabel');
        if (numberEl) numberEl.textContent = score;
        if (starEl) starEl.style.color = color;

        let label, cls, tierKey;
        if (score >= 80) { tierKey = 'excellent'; cls = 'excellent'; }
        else if (score >= 60) { tierKey = 'good'; cls = 'good'; }
        else if (score >= 40) { tierKey = 'average'; cls = 'average'; }
        else { tierKey = 'needs_improvement'; cls = 'poor'; }
        label = BYD.i18n.t('trip.tier.' + tierKey);

        if (labelEl) {
            labelEl.textContent = label;
            labelEl.className = 'score-hero-label ' + cls;
        }

        // Dynamic card background based on score tier
        const card = document.getElementById('scoreHeroCard');
        if (card) {
            if (score >= 80) {
                card.style.background = 'linear-gradient(135deg, rgba(34,197,94,0.1) 0%, rgba(34,197,94,0.04) 100%)';
                card.style.borderColor = 'rgba(34,197,94,0.2)';
            } else if (score >= 60) {
                card.style.background = 'linear-gradient(135deg, rgba(0,212,170,0.08) 0%, rgba(14,165,233,0.06) 100%)';
                card.style.borderColor = 'rgba(0,212,170,0.15)';
            } else if (score >= 40) {
                card.style.background = 'linear-gradient(135deg, rgba(245,158,11,0.1) 0%, rgba(245,158,11,0.04) 100%)';
                card.style.borderColor = 'rgba(245,158,11,0.2)';
            } else {
                card.style.background = 'linear-gradient(135deg, rgba(239,68,68,0.1) 0%, rgba(239,68,68,0.04) 100%)';
                card.style.borderColor = 'rgba(239,68,68,0.2)';
            }
        }

        // Update tooltip content based on score and DNA breakdown
        const tooltipTitle = document.getElementById('scoreTooltipTitle');
        const tooltipDesc = document.getElementById('scoreTooltipDesc');
        const tooltipTip = document.getElementById('scoreTooltipTip');
        const tierPills = document.querySelectorAll('.score-tier-pill');

        if (tooltipTitle) tooltipTitle.textContent = BYD.i18n.t('trip.score_summary', { label: label, score: score });

        // Highlight active tier
        tierPills.forEach(pill => {
            pill.classList.remove('active');
            if (score >= 80 && pill.classList.contains('t-excellent')) pill.classList.add('active');
            else if (score >= 60 && score < 80 && pill.classList.contains('t-good')) pill.classList.add('active');
            else if (score >= 40 && score < 60 && pill.classList.contains('t-average')) pill.classList.add('active');
            else if (score < 40 && pill.classList.contains('t-poor')) pill.classList.add('active');
        });

        // Dynamic description based on score tier
        if (tooltipDesc) {
            var descKey;
            if (score >= 80) descKey = 'excellent';
            else if (score >= 60) descKey = 'good';
            else if (score >= 40) descKey = 'average';
            else descKey = 'poor';
            tooltipDesc.textContent = BYD.i18n.t('trip.tier_desc.' + descKey);
        }

        // Dynamic tip based on actual weakest DNA score. Score keys are read
        // from the API (snake_case server, camelCase legacy alias); both shapes
        // are supported so we don't break older daemons mid-rollout.
        if (tooltipTip && this.radarScoresCache) {
            const dna = this.radarScoresCache;
            const scores = [
                { key: 'anticipation',    val: dna.anticipation || dna.anticipation_score || 0 },
                { key: 'smoothness',      val: dna.smoothness || dna.smoothness_score || 0 },
                { key: 'speedDiscipline', val: dna.speedDiscipline || dna.speed_discipline || 0 },
                { key: 'efficiency',      val: dna.efficiency || dna.efficiency_score || 0 },
                { key: 'consistency',     val: dna.consistency || dna.consistency_score || 0 }
            ];
            const weakest = scores.reduce((min, s) => s.val < min.val ? s : min, scores[0]);
            const weakLabel = this.criterion(weakest.key, 'label');
            const weakTip = this.criterion(weakest.key, 'tip');
            tooltipTip.textContent = BYD.i18n.t('trip.weakest_tip', {
                area: weakLabel,
                val: weakest.val,
                tip: weakTip
            });
        } else if (tooltipTip) {
            tooltipTip.textContent = BYD.i18n.t('trip.drive_more_for_tips');
        }
    },

    // ==================== RADAR CHART with hover ====================

    renderRadar(canvas, scores) {
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        // Old WebView (Chrome <88) doesn't support CSS aspect-ratio, so the
        // container may have zero height. Fall back to width for a square canvas.
        const w = rect.width || 300;
        const h = rect.height > 0 ? rect.height : w;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        // Also set explicit CSS size so the canvas is visible
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const cx = w / 2, cy = h / 2;
        // Reserve ~70px on each side for the longest labels
        // ("Speed Discipline" / "Consistency"). Without this margin
        // the radar polygon eats up almost the whole canvas and the
        // outer labels at `radius + labelOffset` get clipped on the
        // canvas edge ("Smoothness" → "hness", "Consistency" → "Cn",
        // etc.). 0.42 of the half-axis leaves enough horizontal
        // budget at common card widths (300-460px).
        const radius = Math.min(cx, cy) * 0.42;
        const labelOffset = 22;

        const axes = [
            { label: this.criterion('anticipation', 'label'),    key: 'anticipation' },
            { label: this.criterion('smoothness', 'label'),      key: 'smoothness' },
            { label: this.criterion('speedDiscipline', 'label'), key: 'speedDiscipline' },
            { label: this.criterion('efficiency', 'label'),      key: 'efficiency' },
            { label: this.criterion('consistency', 'label'),     key: 'consistency' }
        ];
        const n = axes.length;
        const angleStep = (Math.PI * 2) / n;
        const startAngle = -Math.PI / 2;

        ctx.clearRect(0, 0, w, h);

        // Grid rings
        for (let ring = 1; ring <= 5; ring++) {
            const r = (ring / 5) * radius;
            ctx.beginPath();
            for (let i = 0; i <= n; i++) {
                const angle = startAngle + i * angleStep;
                const x = cx + Math.cos(angle) * r;
                const y = cy + Math.sin(angle) * r;
                if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            }
            ctx.closePath();
            ctx.strokeStyle = this.colors.grid;
            ctx.lineWidth = 1;
            ctx.stroke();
        }

        // Axis lines
        for (let i = 0; i < n; i++) {
            const angle = startAngle + i * angleStep;
            ctx.beginPath();
            ctx.moveTo(cx, cy);
            ctx.lineTo(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius);
            ctx.strokeStyle = this.colors.grid;
            ctx.lineWidth = 1;
            ctx.stroke();
        }

        // Score polygon
        const values = axes.map(a => (scores[a.key] || scores[a.key.replace(/([A-Z])/g, '_$1').toLowerCase()] || 0) / 100);
        ctx.beginPath();
        values.forEach((v, i) => {
            const angle = startAngle + i * angleStep;
            const x = cx + Math.cos(angle) * v * radius;
            const y = cy + Math.sin(angle) * v * radius;
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.closePath();
        ctx.fillStyle = this.colors.brandRgba;
        ctx.fill();
        ctx.strokeStyle = this.colors.brand;
        ctx.lineWidth = 2;
        ctx.stroke();

        // Dots
        values.forEach((v, i) => {
            const angle = startAngle + i * angleStep;
            const x = cx + Math.cos(angle) * v * radius;
            const y = cy + Math.sin(angle) * v * radius;
            ctx.beginPath();
            ctx.arc(x, y, 5, 0, Math.PI * 2);
            ctx.fillStyle = this.colors.brand;
            ctx.fill();
            ctx.strokeStyle = this.colors.dotStroke;
            ctx.lineWidth = 2;
            ctx.stroke();
        });

        // Labels — clamp to canvas so long words don't get clipped
        // at the edges. After the polygon shrunk we still need to
        // make sure the rendered text rectangle fits, so measure
        // each label and pull it inside if it would overflow.
        ctx.font = '13px Inter, sans-serif';
        ctx.fillStyle = this.colors.text;
        ctx.textBaseline = 'middle';
        const edgePad = 4; // gap from canvas edge
        axes.forEach((a, i) => {
            const angle = startAngle + i * angleStep;
            const labelR = radius + labelOffset;
            let x = cx + Math.cos(angle) * labelR;
            const y = cy + Math.sin(angle) * labelR;

            const cosA = Math.cos(angle);
            const align = Math.abs(cosA) > 0.3 ? (cosA > 0 ? 'left' : 'right') : 'center';
            ctx.textAlign = align;

            const tw = ctx.measureText(a.label).width;
            // Compute the text bbox under the chosen alignment and
            // shift x inward if it'd cross the canvas edge.
            let leftEdge, rightEdge;
            if (align === 'left')        { leftEdge = x;            rightEdge = x + tw; }
            else if (align === 'right')  { leftEdge = x - tw;       rightEdge = x; }
            else                         { leftEdge = x - tw / 2;   rightEdge = x + tw / 2; }
            if (rightEdge > w - edgePad) x -= (rightEdge - (w - edgePad));
            if (leftEdge  < edgePad)     x += (edgePad - leftEdge);

            ctx.fillText(a.label, x, y);
        });

        // Store axis positions for hover
        this._radarAxes = axes.map((a, i) => {
            const angle = startAngle + i * angleStep;
            const v = values[i];
            return {
                key: a.key,
                score: Math.round(v * 100),
                dotX: cx + Math.cos(angle) * v * radius,
                dotY: cy + Math.sin(angle) * v * radius,
                labelX: cx + Math.cos(angle) * radius,
                labelY: cy + Math.sin(angle) * radius
            };
        });

        // Setup hover
        this.setupRadarHover(canvas);
    },

    setupRadarHover(canvas) {
        const self = this;
        const tooltip = document.getElementById('radarTooltip');
        const wrap = canvas.parentElement;
        if (!tooltip || !wrap) return;

        canvas.onmousemove = function(e) {
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;

            if (!self._radarAxes) return;

            let closest = null;
            let minDist = 40; // pixel threshold
            self._radarAxes.forEach(ax => {
                const dx = mx - ax.dotX;
                const dy = my - ax.dotY;
                const dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < minDist) { minDist = dist; closest = ax; }
            });

            if (closest) {
                const info = self.criteriaInfo[closest.key];
                if (!info) return;
                document.getElementById('radarTooltipTitle').textContent = info.icon + ' ' + self.criterion(closest.key, 'label');
                document.getElementById('radarTooltipScore').textContent = closest.score + '/100';
                document.getElementById('radarTooltipDesc').textContent = self.criterion(closest.key, 'desc');
                document.getElementById('radarTooltipTip').textContent = '💡 ' + self.criterion(closest.key, 'tip');

                // Position tooltip — always below and centered on dot
                const wrapRect = wrap.getBoundingClientRect();
                let tx = closest.dotX - 100; // center the 200px tooltip
                let ty = closest.dotY + 15;  // below the dot
                // Keep within bounds
                if (tx < 5) tx = 5;
                if (tx + 210 > wrapRect.width) tx = wrapRect.width - 215;
                if (ty + 100 > wrapRect.height) ty = closest.dotY - 115; // flip above

                tooltip.style.left = tx + 'px';
                tooltip.style.top = ty + 'px';
                tooltip.classList.add('visible');
            } else {
                tooltip.classList.remove('visible');
            }
        };

        canvas.onmouseleave = function() {
            tooltip.classList.remove('visible');
        };
    },

    // ==================== TIMELINE CHART ====================

    renderTimeline(canvas, telemetry, highlightIdx) {
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        // Fallback for zero-height containers on old WebView without aspect-ratio
        const w = rect.width || 300;
        const h = rect.height > 0 ? rect.height : 160;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const pad = { top: 10, right: 40, bottom: 25, left: 50 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;

        ctx.clearRect(0, 0, w, h);
        if (!telemetry || telemetry.length < 2) return;

        const axis = this._timelineAxisFor(telemetry);
        const axisKey = axis.key;
        const tStart = axis.start;
        const tRange = axis.range;
        const maxSpeed = Math.max(10, ...telemetry.map(s => s.s || 0));

        // Grid lines
        ctx.strokeStyle = this.colors.grid;
        ctx.lineWidth = 1;
        for (let i = 0; i <= 4; i++) {
            const y = pad.top + (ch * i / 4);
            ctx.beginPath();
            ctx.moveTo(pad.left, y);
            ctx.lineTo(w - pad.right, y);
            ctx.stroke();
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(Math.round(maxSpeed * (1 - i / 4)), pad.left - 6, y + 3);
        }

        // Brake area (red, from bottom)
        ctx.beginPath();
        ctx.moveTo(pad.left, pad.top + ch);
        telemetry.forEach((s, i) => {
            const x = pad.left + ((s[axisKey] - tStart) / tRange) * cw;
            const y = pad.top + ch - ((s.b || 0) / 100) * ch;
            ctx.lineTo(x, y);
        });
        ctx.lineTo(pad.left + cw, pad.top + ch);
        ctx.closePath();
        ctx.fillStyle = 'rgba(239, 68, 68, 0.2)';
        ctx.fill();

        // Accel pedal area (blue, from bottom)
        ctx.beginPath();
        ctx.moveTo(pad.left, pad.top + ch);
        telemetry.forEach((s, i) => {
            const x = pad.left + ((s[axisKey] - tStart) / tRange) * cw;
            const y = pad.top + ch - ((s.a || 0) / 100) * ch;
            ctx.lineTo(x, y);
        });
        ctx.lineTo(pad.left + cw, pad.top + ch);
        ctx.closePath();
        ctx.fillStyle = 'rgba(14, 165, 233, 0.15)';
        ctx.fill();

        // Speed line
        ctx.beginPath();
        telemetry.forEach((s, i) => {
            const x = pad.left + ((s[axisKey] - tStart) / tRange) * cw;
            const y = pad.top + ch - ((s.s || 0) / maxSpeed) * ch;
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.strokeStyle = this.colors.brand;
        ctx.lineWidth = 1.5;
        ctx.stroke();

        // X-axis labels
        ctx.fillStyle = this.colors.text;
        ctx.font = '10px Inter, sans-serif';
        ctx.textAlign = 'center';
        const durMin = tRange / 60000;
        ctx.fillText('0 min', pad.left, h - 5);
        ctx.fillText(Math.round(durMin / 2) + ' min', pad.left + cw / 2, h - 5);
        ctx.fillText(Math.round(durMin) + ' min', w - pad.right, h - 5);

        // SoC% interpolated line (right Y-axis, zoomed to actual range)
        const tripData = this.currentTripData;
        const socStart = tripData ? parseFloat(tripData.socStart || tripData.soc_start || 0) : 0;
        const socEnd = tripData ? parseFloat(tripData.socEnd || tripData.soc_end || 0) : 0;
        const hasSoc = socStart > 0 || socEnd > 0;

        if (hasSoc) {
            // Use a zoomed Y range: pad 5% above and below the actual SoC range
            const socMin = Math.max(0, Math.min(socStart, socEnd) - 5);
            const socMax = Math.min(100, Math.max(socStart, socEnd) + 5);
            const socRange = socMax - socMin || 1;

            // Right Y-axis labels
            ctx.fillStyle = 'rgba(245,158,11,0.7)';
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            ctx.fillText(socStart.toFixed(1) + '%', w - 2, pad.top + ch - ((socStart - socMin) / socRange) * ch + 3);
            ctx.fillText(socEnd.toFixed(1) + '%', w - 2, pad.top + ch - ((socEnd - socMin) / socRange) * ch + 3);

            // SoC line
            ctx.beginPath();
            telemetry.forEach((s, i) => {
                const x = pad.left + ((s[axisKey] - tStart) / tRange) * cw;
                const progress = (s[axisKey] - tStart) / tRange;
                const soc = socStart + (socEnd - socStart) * progress;
                const y = pad.top + ch - ((soc - socMin) / socRange) * ch;
                if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            });
            ctx.strokeStyle = 'rgba(245,158,11,0.6)';
            ctx.lineWidth = 1.5;
            ctx.setLineDash([6, 4]);
            ctx.stroke();
            ctx.setLineDash([]);
        }

        // Compute pedal stats on first render
        if (highlightIdx === undefined) {
            let accelCount = 0, brakeCount = 0, coastCount = 0;
            telemetry.forEach(s => {
                if ((s.b || 0) > 0) brakeCount++;
                else if ((s.a || 0) > 0) accelCount++;
                else coastCount++;
            });
            const total = accelCount + brakeCount + coastCount;
            if (total > 0) {
                this.setEl('tlAccelPct', Math.round((accelCount / total) * 100) + '%');
                this.setEl('tlCoastPct', Math.round((coastCount / total) * 100) + '%');
                this.setEl('tlBrakePct', Math.round((brakeCount / total) * 100) + '%');
            }
        }

        // Highlight scrubber
        if (highlightIdx !== undefined && highlightIdx < telemetry.length) {
            const s = telemetry[highlightIdx];
            const x = pad.left + ((s[axisKey] - tStart) / tRange) * cw;

            ctx.beginPath();
            ctx.moveTo(x, pad.top);
            ctx.lineTo(x, pad.top + ch);
            ctx.strokeStyle = 'rgba(0,212,170,0.6)';
            ctx.lineWidth = 1.5;
            ctx.setLineDash([4, 4]);
            ctx.stroke();
            ctx.setLineDash([]);

            const sy = pad.top + ch - ((s.s || 0) / maxSpeed) * ch;
            ctx.beginPath();
            ctx.arc(x, sy, 5, 0, Math.PI * 2);
            ctx.fillStyle = this.colors.brand;
            ctx.fill();
            ctx.strokeStyle = '#0F0F12';
            ctx.lineWidth = 2;
            ctx.stroke();

            // Tooltip with SoC
            const tw = 140;
            const tx = x + 12 + tw > pad.left + cw ? x - tw - 12 : x + 12;
            const ty = Math.max(pad.top, sy - 70);
            ctx.fillStyle = 'rgba(15,15,20,0.92)';
            ctx.beginPath();
            this._roundRectPath(ctx, tx, ty, tw, 70, 6);
            ctx.fill();
            ctx.strokeStyle = 'rgba(0,212,170,0.3)';
            ctx.lineWidth = 1;
            ctx.stroke();

            ctx.fillStyle = '#fff';
            ctx.font = '11px Inter, sans-serif';
            ctx.textAlign = 'left';
            // Convert: telemetry samples are km/h, and speedLabel() follows the
            // user's unit — printing the raw value under an "mph" label disagreed
            // with the HUD above by ~61% for the same sample.
            ctx.fillText('Speed: '
                    + Math.round(BYD.units.speedVal(s.s || 0))
                    + ' ' + BYD.units.speedLabel(), tx + 8, ty + 15);
            ctx.fillText('Accel: ' + (s.a || 0) + '%', tx + 8, ty + 30);
            ctx.fillText('Brake: ' + (s.b || 0) + '%', tx + 8, ty + 45);
            // SoC interpolated
            if (hasSoc) {
                const progress = (s[axisKey] - tStart) / tRange;
                const socAtPoint = socStart + (socEnd - socStart) * progress;
                ctx.fillStyle = 'rgba(245,158,11,0.8)';
                ctx.fillText('SoC: ' + socAtPoint.toFixed(1) + '%', tx + 8, ty + 60);
            }
        } else {
            this.setupChartHover(canvas, telemetry, axisKey, tStart, tRange, cw, pad);
        }
    },

    setupChartHover(canvas, telemetry, axisKey, tStart, tRange, cw, pad) {
        const self = this;

        // Desktop: mousemove
        canvas.style.pointerEvents = 'auto';
        canvas.onmousemove = function(e) {
            const rect = canvas.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            if (mx < pad.left || mx > pad.left + cw) return;
            const relX = mx - pad.left;
            const targetT = tStart + (relX / cw) * tRange;
            let closest = 0, minDiff = Infinity;
            for (let i = 0; i < telemetry.length; i++) {
                const diff = Math.abs(telemetry[i][axisKey] - targetT);
                if (diff < minDiff) { minDiff = diff; closest = i; }
            }
            self.renderTimeline(canvas, telemetry, closest);
            const slider = document.getElementById('timelineSlider');
            if (slider) { slider.value = closest; self.updateSliderDisplay(closest); }
        };
        canvas.onmouseleave = function() {
            self.renderTimeline(canvas, telemetry);
        };

        // Mobile: touch scrub (horizontal drag on chart moves scrubber)
        canvas.ontouchstart = function(e) {
            if (e.touches.length === 1) {
                canvas._touchStartX = e.touches[0].clientX;
                canvas._touchStartY = e.touches[0].clientY;
                canvas._isScrubbing = false;
            }
        };
        canvas.ontouchmove = function(e) {
            if (e.touches.length !== 1) return;
            const dx = Math.abs(e.touches[0].clientX - canvas._touchStartX);
            const dy = Math.abs(e.touches[0].clientY - canvas._touchStartY);
            // If horizontal movement > vertical, it's a scrub — prevent scroll
            if (dx > dy && dx > 10) {
                canvas._isScrubbing = true;
                e.preventDefault();
                const rect = canvas.getBoundingClientRect();
                const mx = e.touches[0].clientX - rect.left;
                if (mx < pad.left || mx > pad.left + cw) return;
                const relX = mx - pad.left;
                const targetT = tStart + (relX / cw) * tRange;
                let closest = 0, minDiff = Infinity;
                for (let i = 0; i < telemetry.length; i++) {
                    const diff = Math.abs(telemetry[i][axisKey] - targetT);
                    if (diff < minDiff) { minDiff = diff; closest = i; }
                }
                self.renderTimeline(canvas, telemetry, closest);
                const slider = document.getElementById('timelineSlider');
                if (slider) { slider.value = closest; self.updateSliderDisplay(closest); }
            }
        };
        canvas.ontouchend = function() {
            if (canvas._isScrubbing) {
                self.renderTimeline(canvas, telemetry);
            }
            canvas._isScrubbing = false;
        };
    },

    /**
     * Append a rounded-rectangle subpath. CanvasRenderingContext2D.roundRect
     * is Chrome 99+; the BYD head-unit WebView (Chrome 58 / Android 7.1)
     * lacks it, so calling ctx.roundRect there throws "roundRect is not a
     * function". That exception was aborting renderTimeline / renderSpeed
     * Histogram mid-draw AND — because showDetail() runs the histogram inside
     * the same try-block that later schedules the route map — it was killing
     * the trip-detail map too (the map's setTimeout was never reached). This
     * mirrors performance.js's _drawRoundRect so trip canvases degrade
     * gracefully on the old WebView. Caller still owns beginPath/fill/stroke.
     */
    _roundRectPath(ctx, x, y, w, h, r) {
        if (typeof ctx.roundRect === 'function') { ctx.roundRect(x, y, w, h, r); return; }
        // Clamp the radius so thin bars (w/h < 2r) don't produce inverted arcs.
        var rr = Math.min(r, w / 2, h / 2);
        if (rr < 0) rr = 0;
        ctx.moveTo(x + rr, y);
        ctx.lineTo(x + w - rr, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + rr);
        ctx.lineTo(x + w, y + h - rr);
        ctx.quadraticCurveTo(x + w, y + h, x + w - rr, y + h);
        ctx.lineTo(x + rr, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - rr);
        ctx.lineTo(x, y + rr);
        ctx.quadraticCurveTo(x, y, x + rr, y);
        ctx.closePath();
    },

    /**
     * Route-ribbon speed band color — matches the MAP speed legend in
     * trips.html (routeSpeedLow/Mid/High): green < 40 km/h, yellow 40–80,
     * red > 80. `kmh` is the raw telemetry `.s` value (km/h) compared
     * directly; the legend converts to mph for display only. NOTE this is
     * the ROUTE-legend mapping (green = slow), deliberately the inverse of
     * renderSpeedHistogram's bar colors which match the histogram legend.
     */
    _speedColorForRoute(kmh) {
        if (kmh > 80) return this.colors.speedRed;
        if (kmh >= 40) return this.colors.speedYellow;
        return this.colors.speedGreen;
    },

    // ==================== SPEED HISTOGRAM ====================

    renderSpeedHistogram(canvas, telemetry) {
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        const w = rect.width || 300;
        const h = rect.height > 0 ? rect.height : 160;
        canvas.width = w * dpr;
        canvas.height = h * dpr;
        canvas.style.width = w + 'px';
        canvas.style.height = h + 'px';
        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);

        const pad = { top: 10, right: 40, bottom: 25, left: 80 };
        const cw = w - pad.left - pad.right;
        const ch = h - pad.top - pad.bottom;

        ctx.clearRect(0, 0, w, h);
        if (!telemetry || telemetry.length === 0) return;

        const bucketSize = 10;
        const buckets = {};
        const labels = [];
        for (let i = 0; i <= 130; i += bucketSize) {
            const label = i + '-' + (i + bucketSize);
            labels.push(label);
            buckets[label] = 0;
        }
        labels.push('140+');
        buckets['140+'] = 0;

        let totalSamples = 0;
        telemetry.forEach(s => {
            const speed = s.s || 0;
            totalSamples++;
            if (speed >= 140) { buckets['140+']++; return; }
            const idx = Math.floor(speed / bucketSize) * bucketSize;
            const label = idx + '-' + (idx + bucketSize);
            if (buckets[label] !== undefined) buckets[label]++;
        });

        const filteredLabels = labels.filter(l => buckets[l] > 0);
        if (filteredLabels.length === 0) return;

        const maxCount = Math.max(...filteredLabels.map(l => buckets[l]));
        const barH = Math.min(22, ch / filteredLabels.length - 4);

        filteredLabels.forEach((label, i) => {
            const count = buckets[label];
            const barW = (count / maxCount) * cw;
            const y = pad.top + i * (barH + 4);
            const pct = totalSamples > 0 ? Math.round(count / totalSamples * 100) : 0;

            const speedVal = parseInt(label);
            let color = this.colors.speedGreen;
            if (speedVal < 40) color = this.colors.speedYellow;
            else if (speedVal > 80) color = this.colors.speedRed;

            // Bar with rounded ends
            ctx.fillStyle = color;
            ctx.globalAlpha = 0.75;
            ctx.beginPath();
            this._roundRectPath(ctx, pad.left, y, Math.max(barW, 4), barH, 3);
            ctx.fill();
            ctx.globalAlpha = 1;

            // Label — buckets are km/h ranges; convert to user's unit if mi
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Inter, sans-serif';
            ctx.textAlign = 'right';
            var displayLabel;
            if (BYD.units.mode === 'mi') {
                if (label === '140+') {
                    displayLabel = Math.round(140 * BYD.units.KM_TO_MI) + '+';
                } else {
                    var lo = parseInt(label.split('-')[0]);
                    var hi = parseInt(label.split('-')[1]);
                    displayLabel = Math.round(lo * BYD.units.KM_TO_MI) + '-' + Math.round(hi * BYD.units.KM_TO_MI);
                }
            } else {
                displayLabel = label;
            }
            ctx.fillText(displayLabel + ' ' + BYD.units.speedLabel(), pad.left - 6, y + barH / 2 + 3);

            // Percentage
            ctx.fillStyle = this.colors.text;
            ctx.textAlign = 'left';
            ctx.fillText(pct + '%', pad.left + barW + 6, y + barH / 2 + 3);
        });

        // Summary stats — use trip-level values for consistency with card
        const tripData = this.currentTripData;
        if (tripData || telemetry.length > 0) {
            const avg = tripData ? Math.round(tripData.avgSpeedKmh || tripData.avg_speed_kmh || 0) : 0;
            const max = tripData ? Math.round(tripData.maxSpeedKmh || tripData.max_speed_kmh || 0) : 0;
            const allSpeeds = telemetry.map(s => s.s || 0);
            const movingSamples = allSpeeds.filter(s => s > 0);
            const lowPct = movingSamples.length > 0 ? Math.round(movingSamples.filter(s => s < 40).length / movingSamples.length * 100) : 0;
            const highPct = movingSamples.length > 0 ? Math.round(movingSamples.filter(s => s > 80).length / movingSamples.length * 100) : 0;

            const summaryEl = document.getElementById('speedHistSummary');
            if (summaryEl) {
                summaryEl.innerHTML =
                    '<span class="speed-hist-stat">Avg: <span class="shval">' + BYD.units.speed(avg) + '</span></span>' +
                    '<span class="speed-hist-stat">Max: <span class="shval">' + BYD.units.speed(max) + '</span></span>' +
                    '<span class="speed-hist-stat">Low speed: <span class="shval">' + lowPct + '%</span></span>' +
                    '<span class="speed-hist-stat">High speed: <span class="shval">' + highPct + '%</span></span>';
            }
        }
    },

    // ==================== ROUTE MAP ====================

    renderRouteMap(container, telemetry, detailRequest) {
        if (detailRequest && !this._isCurrentDetailRequest(detailRequest)) return;
        const isCurrentMap = () => {
            return !detailRequest || this._isCurrentDetailRequest(detailRequest);
        };
        console.log('[Trips] renderRouteMap called, telemetry=' + (telemetry ? telemetry.length : 'null'));
        if (this.leafletMap) { this.leafletMap.remove(); this.leafletMap = null; }
        // Dispose the previous slider-marker 3D scene before the
        // Leaflet remove() detaches its canvas from the DOM. dispose()
        // releases the WebGL context cleanly; without this the trip-
        // switcher would leak one context per opened trip and Chrome
        // 58 caps at 8-16 before old contexts get force-killed (which
        // breaks the sidebar EV-card and the live-view canvas too).
        if (this._sliderMarker3d) {
            try { this._sliderMarker3d.dispose(); } catch (e) {}
            this._sliderMarker3d = null;
        }
        if (!telemetry || telemetry.length < 2) {
            console.warn('[Trips] renderRouteMap: not enough telemetry');
            return;
        }

        // Guard: Leaflet may not be loaded (CDN fetch can fail on old WebView)
        if (typeof L === 'undefined') {
            console.warn('[Trips] Leaflet not loaded, retrying in 1s... (attempt ' + (this._mapRetries || 0) + ')');
            if (!this._mapRetries) this._mapRetries = 0;
            if (this._mapRetries < 5) {
                this._mapRetries++;
                this._mapRetryTimer = setTimeout(() => {
                    this._mapRetryTimer = null;
                    if (isCurrentMap()) this.renderRouteMap(container, telemetry, detailRequest);
                }, 1000);
            } else {
                console.error('[Trips] Leaflet never loaded after 5 retries');
            }
            return;
        }
        this._mapRetries = 0;

        // Guard: container must have real dimensions (old WebView is slow to layout
        // after display:none → display:block transition)
        var rect = container.getBoundingClientRect();
        console.log('[Trips] Map container rect:', rect.width + 'x' + rect.height);
        if (rect.width < 10 || rect.height < 10) {
            console.warn('[Trips] Map container has no dimensions, retrying... (attempt ' + (this._layoutRetries || 0) + ')');
            if (!this._layoutRetries) this._layoutRetries = 0;
            if (this._layoutRetries < 10) {
                this._layoutRetries++;
                this._mapRetryTimer = setTimeout(() => {
                    this._mapRetryTimer = null;
                    if (isCurrentMap()) this.renderRouteMap(container, telemetry, detailRequest);
                }, 300);
            } else {
                console.error('[Trips] Map container never got dimensions after 10 retries');
            }
            return;
        }
        this._layoutRetries = 0;

        const points = telemetry.filter(s => s.la && s.lo && s.la !== 0 && s.lo !== 0);
        console.log('[Trips] GPS points with coordinates:', points.length);
        if (points.length < 2) {
            console.warn('[Trips] renderRouteMap: not enough GPS points with coordinates');
            return;
        }

        // Compute bounds FIRST
        let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
        for (let i = 0; i < points.length; i++) {
            if (points[i].la < minLat) minLat = points[i].la;
            if (points[i].la > maxLat) maxLat = points[i].la;
            if (points[i].lo < minLon) minLon = points[i].lo;
            if (points[i].lo > maxLon) maxLon = points[i].lo;
        }

        // Create map and SET VIEW before adding any layers
        this.leafletMap = L.map(container, {
            zoomControl: true,
            attributionControl: false,
            scrollWheelZoom: false,
            dragging: true,
            tap: true,
            touchZoom: true,
            bounceAtZoomLimits: false
        });

        if (isFinite(minLat) && isFinite(maxLat) && isFinite(minLon) && isFinite(maxLon) &&
            maxLat > minLat && maxLon > minLon) {
            this.leafletMap.fitBounds([[minLat, minLon], [maxLat, maxLon]], { padding: [30, 30] });
        } else {
            this.leafletMap.setView([points[0].la, points[0].lo], 14);
        }

        // Shared keyless OSM.de tiles follow the current day/night theme.
        BYD.theme.attachMapTiles(this.leafletMap);

        console.log('[Trips] Map created successfully, tiles added, bounds set');

        // Route ribbon, colored by speed (matches the map legend: green <40,
        // yellow 40–80, red >80 km/h). Leaflet can't gradient a single polyline,
        // so we walk the points and emit one polyline per RUN of same-colored
        // samples — merging consecutive same-band points keeps the layer count
        // near the number of band transitions instead of one-per-sample, which
        // matters on the old WebView for long/dense trips. Each run is seeded
        // with the previous point so the bands stay visually joined (no gaps).
        //
        // Fallback: only color when EVERY retained point carries a numeric speed.
        // A trip with any missing `.s` would otherwise mix in the brand color and
        // show an undocumented 4th band, so in that case we draw the original
        // single flat-brand ribbon exactly as before.
        var coords = points.map(function (p) { return [p.la, p.lo]; });
        var hasAllSpeeds = true;
        for (var si = 0; si < points.length; si++) {
            if (typeof points[si].s !== 'number' || !isFinite(points[si].s)) { hasAllSpeeds = false; break; }
        }
        if (!hasAllSpeeds) {
            L.polyline(coords, { color: this.colors.brand, weight: 4, opacity: 0.85 }).addTo(this.leafletMap);
        } else {
            var runStart = 0;
            var runColor = this._speedColorForRoute(points[0].s);
            for (var i = 1; i <= points.length; i++) {
                var segColor = (i < points.length) ? this._speedColorForRoute(points[i].s) : null;
                // Flush the run when the color changes or we reach the end.
                if (i === points.length || segColor !== runColor) {
                    L.polyline(coords.slice(runStart, i), {
                        color: runColor,
                        weight: 4,
                        opacity: 0.85
                    }).addTo(this.leafletMap);
                    // Start the next run one point back so it connects to this one.
                    runStart = i - 1;
                    runColor = segColor;
                }
            }
        }

        // Start marker
        var startIcon = L.divIcon({
            className: '',
            html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#22C55E;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="16" height="16"><polygon points="5,3 19,12 5,21"/></svg></div>',
            iconSize: [32, 32],
            iconAnchor: [16, 16]
        });
        L.marker([points[0].la, points[0].lo], { icon: startIcon }).addTo(this.leafletMap).bindPopup('<b>' + BYD.i18n.t('trip.marker_start') + '</b>');

        // End marker
        var endIcon = L.divIcon({
            className: '',
            html: '<div style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;background:#EF4444;border-radius:50%;border:3px solid #fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);"><svg viewBox="0 0 24 24" fill="#fff" width="14" height="14"><rect x="6" y="6" width="12" height="12" rx="2"/></svg></div>',
            iconSize: [32, 32],
            iconAnchor: [16, 16]
        });
        L.marker([points[points.length - 1].la, points[points.length - 1].lo], { icon: endIcon }).addTo(this.leafletMap).bindPopup('<b>' + BYD.i18n.t('trip.marker_end') + '</b>');

        // Slider marker — same top-down 3D vehicle as the live-view
        // map marker, rendered into a per-marker canvas. Wrapper id is
        // unique to this map (live-view's marker also uses
        // `.car-icon-wrapper` and a global selector would race).
        const carIcon = L.divIcon({
            className: 'car-map-marker',
            html: '<div class="car-icon-wrapper" id="tripSliderCarWrapper">'
                + '<canvas class="car-icon-img" id="tripSliderCarCanvas" aria-hidden="true"></canvas>'
                + '</div>',
            iconSize: [24, 50],
            iconAnchor: [12, 25]
        });
        this.sliderMarker = L.marker([points[0].la, points[0].lo], { icon: carIcon }).addTo(this.leafletMap);

        // Mount the 3D vehicle render onto the new canvas now that
        // Leaflet has injected the divIcon into the map pane. Capture
        // the instance so we can dispose() it when the user opens a
        // different trip and we tear down this map.
        var self3d = this;
        var mountTripsCar3d = function () {
            if (!isCurrentMap()) return;
            var canvas = document.getElementById('tripSliderCarCanvas');
            if (!canvas) return;
            var shell = window.OverdriveAppShell;
            if (!shell || typeof shell.mountVehicleCanvas !== 'function') return;
            self3d._sliderMarker3d = shell.mountVehicleCanvas(canvas, { view: 'top' });
        };
        if (window.OverdriveAppShell && window.OverdriveAppShell.mountVehicleCanvas) {
            mountTripsCar3d();
        } else {
            this._sliderMarkerReadyHandler = mountTripsCar3d;
            document.addEventListener('app-shell:ready', mountTripsCar3d, { once: true });
        }

        // Set initial heading from first GPS points with meaningful distance
        if (points.length >= 2) {
            // Find the first pair of points with enough separation for a reliable heading
            var initHeading = null;
            for (var hi = 1; hi < Math.min(points.length, 20); hi++) {
                var dLat = points[hi].la - points[0].la;
                var dLonRaw = points[hi].lo - points[0].lo;
                // Rough distance check — need at least ~10m separation
                if (Math.abs(dLat) > 0.00005 || Math.abs(dLonRaw) > 0.00005) {
                    var dLon = dLonRaw * Math.PI / 180;
                    var lat1 = points[0].la * Math.PI / 180;
                    var lat2 = points[hi].la * Math.PI / 180;
                    var y = Math.sin(dLon) * Math.cos(lat2);
                    var x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
                    initHeading = Math.atan2(y, x) * 180 / Math.PI;
                    break;
                }
            }
            // Fallback to first two points if no good pair found
            if (initHeading === null && points[1]) {
                var dLon = (points[1].lo - points[0].lo) * Math.PI / 180;
                var lat1 = points[0].la * Math.PI / 180;
                var lat2 = points[1].la * Math.PI / 180;
                var y = Math.sin(dLon) * Math.cos(lat2);
                var x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
                initHeading = Math.atan2(y, x) * 180 / Math.PI;
            }
            if (initHeading !== null) {
                this._mapHeadingTimer = setTimeout(function() {
                    self3d._mapHeadingTimer = null;
                    if (!isCurrentMap() || self3d.leafletMap !== mapRef) return;
                    // Target the trip-map wrapper specifically. A
                    // global '.car-icon-wrapper' selector would also
                    // match the live-view map's marker on pages where
                    // both are mounted, leaving its heading wrong.
                    var el = document.getElementById('tripSliderCarWrapper');
                    if (el) el.style.transform = 'rotate(' + initHeading + 'deg)';
                }, 100);
            }
        }

        // Force Leaflet to recalculate container size (old WebView may report
        // stale dimensions right after display:none → block transition)
        var mapRef = this.leafletMap;
        this._mapInvalidateTimers = [
            setTimeout(function() {
                if (isCurrentMap() && self3d.leafletMap === mapRef) mapRef.invalidateSize();
            }, 200),
            setTimeout(function() {
                if (isCurrentMap() && self3d.leafletMap === mapRef) mapRef.invalidateSize();
            }, 800)
        ];

        // Click/tap on map to jump to nearest point
        const self = this;
        this.leafletMap.on('click', function(e) {
            if (!isCurrentMap()) return;
            const clickLat = e.latlng.lat;
            const clickLon = e.latlng.lng;
            let closestIdx = 0, minDist = Infinity;
            const allTel = self.telemetryCache || [];
            for (let i = 0; i < allTel.length; i++) {
                if (!allTel[i].la || !allTel[i].lo) continue;
                const dLat = allTel[i].la - clickLat;
                const dLon = allTel[i].lo - clickLon;
                const dist = dLat * dLat + dLon * dLon;
                if (dist < minDist) { minDist = dist; closestIdx = i; }
            }
            const slider = document.getElementById('timelineSlider');
            if (slider) { slider.value = closestIdx; self.updateSliderDisplay(closestIdx); }
        });
    },

    // ==================== DELETE ====================

    async deleteTrip(tripId) {
        if (!confirm(BYD.i18n.t('trip.delete_confirm'))) return;
        try {
            const resp = await fetch('/api/trips/' + tripId, { method: 'DELETE' });
            const data = await resp.json();
            if (data.success) {
                this._invalidateListRequests();
                this.currentCursor = null;
                const deletedKey = this._tripIdKey({ id: tripId });
                const previousLength = this.trips.length;
                this.trips = this.trips.filter((trip) => {
                    return deletedKey == null || this._tripIdKey(trip) !== deletedKey;
                });
                const removedCount = previousLength - this.trips.length;
                if (removedCount > 0) {
                    this.currentOffset = Math.max(0, this.currentOffset - removedCount);
                }
                this.renderTripList(this.trips);
                const currentKey = this.currentTripId == null
                    ? null : this._tripIdKey({ id: this.currentTripId });
                if (currentKey != null && currentKey === deletedKey) this.hideDetail();
                if (this.rangeFromMs == null) {
                    this.loadTrips(this.currentDays, 0);
                    this.loadSummary(this.currentDays);
                } else {
                    this._invalidateSummaryRequests();
                    this.loadTripsBetween(
                        this.rangeFromMs, this.rangeToMs, 0);
                }
            }
        } catch (e) { console.warn('[Trips] Delete failed:', e); }
    },

    deleteCurrentTrip() {
        if (this.currentTripId != null) this.deleteTrip(this.currentTripId);
    },

    // ==================== HELPERS ====================

    setEl(id, val) {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
    },

    _formatCdrRelativeTime(ts) {
        if (!ts || ts <= 0) return 'Never';
        const diffSec = Math.floor((Date.now() - ts) / 1000);
        if (diffSec < 0) return 'Just now';
        if (diffSec < 60) return diffSec + 's ago';
        if (diffSec < 3600) return Math.floor(diffSec / 60) + ' min ago';
        if (diffSec < 86400) return Math.floor(diffSec / 3600) + 'h ago';
        return Math.floor(diffSec / 86400) + 'd ago';
    },

    getAvgScore(trip) {
        const a = trip.anticipationScore || trip.anticipation_score || 0;
        const s = trip.smoothnessScore || trip.smoothness_score || 0;
        const sd = trip.speedDisciplineScore || trip.speed_discipline_score || 0;
        const e = trip.efficiencyScore || trip.efficiency_score || 0;
        const c = trip.consistencyScore || trip.consistency_score || 0;
        return Math.floor((a + s + sd + e + c) / 5);
    },

    /**
     * True when a trip was rebuilt from on-disk GPS telemetry (the "Recover
     * Missing Trips" path) rather than recorded live. Such a row has real
     * distance/speed/duration/elevation but NO battery, energy, cost, or
     * driving-score data — those are not in the telemetry stream, so they sit
     * at 0. We detect that signature so the card/detail can hide the otherwise
     * misleading 0.00 readings.
     *
     * Heuristic (all must hold):
     *  - every one of the 5 driving sub-scores is 0
     *  - no SoC reading (start and end both 0)
     *  - no energy reading (kWh used and SoC-per-km efficiency both 0)
     * A genuine live trip always carries at least a SoC pair or a non-zero
     * score, so this won't false-positive a real short trip. The IMPORTED_PATH
     * sentinel ("imported://...") is a backup restore, which DOES carry real
     * data, and is excluded.
     */
    isRecoveredTrip(trip) {
        if (!trip) return false;
        const path = trip.telemetryFilePath || trip.telemetry_file_path || '';
        if (path.indexOf('imported://') === 0) return false;   // backup restore keeps real data
        const a = trip.anticipationScore || trip.anticipation_score || 0;
        const s = trip.smoothnessScore || trip.smoothness_score || 0;
        const sd = trip.speedDisciplineScore || trip.speed_discipline_score || 0;
        const e = trip.efficiencyScore || trip.efficiency_score || 0;
        const c = trip.consistencyScore || trip.consistency_score || 0;
        if (a || s || sd || e || c) return false;              // any real score → live trip
        const socStart = trip.socStart || trip.soc_start || 0;
        const socEnd = trip.socEnd || trip.soc_end || 0;
        if (socStart || socEnd) return false;                  // any SoC → live trip
        const energy = trip.energyUsedKwh || trip.energy_used_kwh || 0;
        const effSoc = trip.efficiencySocPerKm || trip.efficiency_soc_per_km || 0;
        if (energy || effSoc) return false;                    // any energy → live trip
        return true;
    },

    formatDuration(seconds) {
        if (!seconds || seconds <= 0) return '--';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        if (h > 0) return h + 'h ' + m + 'm';
        return m + ' min';
    }
};
