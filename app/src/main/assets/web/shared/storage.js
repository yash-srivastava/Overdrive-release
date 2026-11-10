/**
 * OverDrive - Storage settings page (storage.html)
 * Every storage option in one place: surveillance events, drive recordings,
 * trip telemetry, factory dashcam (BYD DVR) clean-up and trip recovery.
 * Ported from surveillance.js, recording.js and trips.js: same endpoints,
 * availability rules, toasts and revert-on-failure. ES2017 / Chrome 58.
 */

window.BYD = window.BYD || {};

BYD.storagePage = {
    // Staged picks. Apply commits only the fields that differ from `saved`.
    config: {
        surveillanceStorageType: 'INTERNAL',
        surveillanceLimitMb: 500,
        recordingsStorageType: 'INTERNAL',
        recordingsLimitMb: 500,
        tripsStorageType: 'INTERNAL',
        tripsLimitMb: 500
    },
    saved: {},
    _fields: {
        surveillance: ['surveillanceStorageType', 'surveillanceLimitMb'],
        recordings: ['recordingsStorageType', 'recordingsLimitMb'],
        trips: ['tripsStorageType', 'tripsLimitMb']
    },
    // Nothing is editable before the payload that owns it has landed.
    _loaded: { settings: false, trips: false },
    volumes: {
        sdCardAvailable: false,
        sdCardFreeSpace: 0,
        sdCardTotalSpace: 0,
        usbAvailable: false,
        usbFreeSpace: 0,
        usbTotalSpace: 0,
        internalFreeSpace: 0,
        internalTotalSpace: 0,
        // Per-volume slider ceilings for surveillance + recordings (live StatFs).
        maxLimitMb: 100000,
        maxLimitMbSdCard: 100000,
        maxLimitMbUsb: 100000
    },
    survInfo: { activeType: null, path: '' },
    recInfo: { activeType: null, path: '', effectiveLimitMb: 0 },
    // Last full /api/trips/storage payload (slider ceilings) and its usage figures.
    tripMeta: null,
    tripUsage: null,
    stats: null,
    // Combined-limit advisory (shared/storage-budget.js): rendered, never enforced.
    budget: [],
    // SD and USB share the USB power rail: with it asleep while parked,
    // surveillance can't write to either.
    keepUsbPowerOnAccOff: true,

    cdrInfo: null,
    cdrConfig: {
        enabled: false,
        reservedSpaceMb: 2000,
        protectedHours: 24,
        minFilesKeep: 10
    },
    _cdrReady: false,
    _cdrWritesPending: 0,
    _cdrWriteVersion: 0,
    _cdrTogglePending: false,
    _cdrCleanupPending: false,
    _cdrSaveTimer: null,
    _cdrDirty: {},
    _cdrLoading: null,

    _hydrated: false,
    _writeQueue: Promise.resolve(),
    _applying: false,
    _applyVersion: 0,
    _readSeq: {},
    _inflight: {},
    _indexDown: false,
    _statsSkip: 0,
    _warmingPollTimer: null,
    _warmingPollAttempt: 0,
    _pollTimer: null,
    _lastResume: 0,
    _recoverRunning: false,

    // ==================== Helpers ====================

    _el(id) {
        return document.getElementById(id);
    },

    _setText(id, text) {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
    },

    _setFill(id, pct) {
        const el = document.getElementById(id);
        if (el) el.style.width = pct + '%';
    },

    /** t() with a fallback for missing keys (t returns the key itself then). */
    _tr(key, vars, fallback) {
        const v = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, vars) : null;
        if (v != null && v !== key) return v;
        if (fallback != null) return fallback;
        return v == null ? '' : v;
    },

    _toast(msg, type) {
        if (BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type);
    },

    formatSize(bytes) {
        if (bytes >= 1000000000) return (bytes / 1000000000).toFixed(1) + ' GB';
        if (bytes >= 1000000) return (bytes / 1000000).toFixed(1) + ' MB';
        if (bytes >= 1000) return (bytes / 1000).toFixed(1) + ' KB';
        return bytes + ' B';
    },

    _fmtMb(mb) {
        return mb >= 1000
            ? this._tr('recording.unit_gb', { n: mb / 1000 })
            : this._tr('recording.unit_mb', { n: mb });
    },

    _enqueueWrite(task) {
        const run = () => task();
        const next = this._writeQueue.then(run, run);
        this._writeQueue = next.catch(() => {});
        return next;
    },

    async _postJson(url, body) {
        return this._enqueueWrite(async () => {
            try {
                const resp = await fetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await resp.json();
                if (!resp.ok || !data || data.success !== true) {
                    throw new Error(data && data.error ? data.error : 'Request rejected');
                }
                return data;
            } catch (e) {
                console.warn('Storage settings POST failed (' + url + '):', e);
                return null;
            }
        });
    },

    /**
     * GET + apply. Only the newest read of an endpoint paints; the periodic
     * tick shares an in-flight read instead of stacking requests on a slow daemon.
     */
    _read(name, url, apply, shareInflight) {
        if (shareInflight && this._inflight[name]) return this._inflight[name];
        const seq = (this._readSeq[name] || 0) + 1;
        this._readSeq[name] = seq;
        const version = this._applyVersion;
        const p = fetch(url)
            .then(resp => resp.json())
            .then(data => seq === this._readSeq[name] && apply.call(this, data, version) === true)
            .catch(e => {
                console.warn('[Storage] ' + url + ' failed:', e);
                return false;
            })
            .then(ok => {
                if (this._inflight[name] === p) this._inflight[name] = null;
                return ok;
            });
        this._inflight[name] = p;
        return p;
    },

    // ==================== Dirty tracking ====================

    _sectionLoaded(section) {
        return section === 'trips' ? this._loaded.trips : this._loaded.settings;
    },

    _isDirty(section) {
        if (!this._sectionLoaded(section)) return false;
        const keys = this._fields[section];
        for (let i = 0; i < keys.length; i++) {
            if (this.config[keys[i]] !== this.saved[keys[i]]) return true;
        }
        return false;
    },

    _anyDirty() {
        return this._isDirty('surveillance') || this._isDirty('recordings') || this._isDirty('trips');
    },

    _commit(section) {
        const keys = this._fields[section];
        for (let i = 0; i < keys.length; i++) this.saved[keys[i]] = this.config[keys[i]];
    },

    _revert(section) {
        const keys = this._fields[section];
        for (let i = 0; i < keys.length; i++) {
            if (this.saved[keys[i]] !== undefined) this.config[keys[i]] = this.saved[keys[i]];
        }
    },

    /**
     * A read may adopt server picks only for a clean section, and only if no
     * Apply started since the read did — otherwise a stale response would snap
     * a just-saved or in-progress value back (see recording.js loadStorageSettings).
     */
    _canAdopt(section, version) {
        if (!this._sectionLoaded(section)) return true;
        return version === this._applyVersion && !this._applying && !this._isDirty(section);
    },

    /** Edits wait for hydration and for any Apply in flight (snap back to the model). */
    _editable(source) {
        if (!this._hydrated || !this._loaded[source]) return false;
        if (this._applying) {
            this.renderAll();
            return false;
        }
        return true;
    },

    markChanged() {
        const dirty = this._anyDirty();
        const btn = this._el('btnApply');
        if (btn) {
            btn.disabled = !dirty || this._applying;
            btn.classList.toggle('has-changes', dirty);
        }
        const markers = {
            storageUnsaved: 'surveillance',
            recStorageUnsaved: 'recordings',
            tripStorageUnsaved: 'trips'
        };
        Object.keys(markers).forEach(id => {
            const el = this._el(id);
            if (el) el.classList.toggle('show', this._isDirty(markers[id]));
        });
    },

    // ==================== Init + refresh ====================

    async init() {
        this.renderAll();
        // Usage figures can lag while the recordings index warms up; they paint
        // when they land and don't hold the editable controls.
        this.loadStorageStats();
        const results = await Promise.all([
            this.loadStorageSettings(),
            this.loadTripStorage(),
            this.loadKeepUsbPower()
        ]);
        this._hydrated = true;
        this.renderAll();
        if (!results[0] || !results[1]) {
            this._toast(this._tr('errors.load_failed'), 'error');
        }
        // DVR status can recursively scan a slow microSD card; its controls have
        // their own _cdrReady gate, so it loads after hydration.
        if (this._cdrVisible()) this.loadCdrConfig();
        this._startPolling();
        // hydrate() rewrites [data-i18n] text on a language switch; repaint live values.
        if (BYD.i18n && typeof BYD.i18n.onChange === 'function') {
            BYD.i18n.onChange(() => {
                this.renderAll();
                this.updateCdrUI();
            });
        }
    },

    _startPolling() {
        if (this._pollTimer) return;
        this._pollTimer = setInterval(() => {
            if (document.visibilityState === 'hidden') return;
            this._tick();
        }, 10000);
        const resume = () => {
            if (document.visibilityState === 'hidden') return;
            // visibilitychange and focus fire together on a tab switch.
            const now = Date.now();
            if (now - this._lastResume < 2000) return;
            this._lastResume = now;
            this.loadKeepUsbPower();
            this._tick();
        };
        document.addEventListener('visibilitychange', resume);
        window.addEventListener('focus', resume);
        window.addEventListener('overdrive:resume', resume);
    },

    _tick() {
        this.loadStorageSettings(true);
        this.loadTripStorage(true);
        // Every 3rd tick while the recordings index is down (see recording.js).
        this._statsSkip = (this._statsSkip || 0) + 1;
        if (!this._indexDown || this._statsSkip % 3 === 0) {
            this.loadStorageStats(true);
        }
        if (this._cdrVisible()) this.loadCdrConfig(true);
    },

    loadStorageSettings(fromTick) {
        return this._read('settings', '/api/settings/storage', this._applySettingsPayload, fromTick);
    },

    _applySettingsPayload(data, version) {
        if (!data || !data.success) return false;
        if (this._canAdopt('surveillance', version)) {
            this.config.surveillanceLimitMb = data.surveillanceLimitMb || 500;
            this.config.surveillanceStorageType = data.surveillanceStorageType || 'INTERNAL';
            this._commit('surveillance');
        }
        if (this._canAdopt('recordings', version)) {
            this.config.recordingsLimitMb = data.recordingsLimitMb || 500;
            this.config.recordingsStorageType = data.recordingsStorageType || 'INTERNAL';
            this._commit('recordings');
        }
        this._loaded.settings = true;
        // Active = where clips land right now: internal while the picked SD/USB
        // is unmounted (often USB power off) or full.
        this.survInfo.activeType = data.surveillanceStorageTypeActive || this.saved.surveillanceStorageType;
        this.survInfo.path = data.surveillancePath || '';
        this.recInfo.activeType = data.recordingsStorageTypeActive || this.saved.recordingsStorageType;
        this.recInfo.path = data.recordingsPath || '';
        this.recInfo.effectiveLimitMb = data.recordingsEffectiveLimitMb || 0;

        const v = this.volumes;
        v.sdCardAvailable = data.sdCardAvailable || false;
        v.sdCardFreeSpace = data.sdCardFreeSpace || 0;
        v.sdCardTotalSpace = data.sdCardTotalSpace || 0;
        v.usbAvailable = data.usbAvailable || false;
        v.usbFreeSpace = data.usbFreeSpace || 0;
        v.usbTotalSpace = data.usbTotalSpace || 0;
        v.internalFreeSpace = data.internalFreeSpace || 0;
        v.internalTotalSpace = data.internalTotalSpace || 0;
        v.maxLimitMb = data.maxLimitMb || 100000;
        v.maxLimitMbSdCard = data.maxLimitMbSdCard || 100000;
        v.maxLimitMbUsb = data.maxLimitMbUsb || 100000;
        this.budget = data.storageBudget || [];
        this.renderAll();
        return true;
    },

    loadTripStorage(fromTick) {
        return this._read('trips', '/api/trips/storage', this._applyTripPayload, fromTick);
    },

    _applyTripPayload(data, version) {
        if (!data || !data.storage) return false;
        const s = data.storage;
        if (this._canAdopt('trips', version)) {
            this.config.tripsStorageType = s.storageType || 'INTERNAL';
            this.config.tripsLimitMb = s.limitMb || 500;
            this._commit('trips');
            // Ceilings follow full reads only: a volume remounting mid-response
            // can report a wrong ceiling (see trips.js _refreshBudgetOnly).
            this.tripMeta = s;
        }
        this._loaded.trips = true;
        this.tripUsage = {
            usedMb: Number(s.usedMb) || 0,
            unit: s.usedUnit || 'MB',
            count: s.tripsCount || 0,
            path: s.storagePath || ''
        };
        if (!this._loaded.settings) {
            const v = this.volumes;
            v.sdCardAvailable = !!s.sdCardAvailable;
            v.usbAvailable = !!s.usbAvailable;
            v.sdCardFreeSpace = s.sdCardFreeSpace || 0;
            v.sdCardTotalSpace = s.sdCardTotalSpace || 0;
            v.usbFreeSpace = s.usbFreeSpace || 0;
            v.usbTotalSpace = s.usbTotalSpace || 0;
        }
        if (s.storageBudget) this.budget = s.storageBudget;
        this.renderAll();
        return true;
    },

    loadKeepUsbPower() {
        return this._read('survConfig', '/api/surveillance/config', this._applySurvConfig, true);
    },

    _applySurvConfig(data) {
        if (!data || !data.success || !data.config) return false;
        this.keepUsbPowerOnAccOff = data.config.keepUsbPowerOnAccOff !== false;
        this.renderSurveillance();
        return true;
    },

    loadStorageStats(fromTick) {
        return this._read('stats', '/api/recordings/stats', this._applyStats, fromTick);
    },

    _applyStats(data) {
        if (!data) return false;
        // Index down: the zeroed counters aren't authoritative, so show "--"
        // and let the poller back off.
        if (data.indexUnavailable) {
            this._indexDown = true;
            this.stats = null;
            ['survStorageUsed', 'survStorageLimit', 'storageUsed', 'storageLimit'].forEach(id => this._setText(id, '--'));
            this._setFill('survStorageFill', 0);
            this._setFill('storageFill', 0);
            return true;
        }
        this._indexDown = false;
        if (!data.success) return false;
        // Structured byType first; legacy flat fields for an older daemon.
        const t = data.byType;
        const sentry = t ? (t.sentry || {}) : { bytes: data.sentrySize, bytesFormatted: data.sentrySizeFormatted };
        const normal = t ? (t.normal || {}) : { bytes: data.normalSize };
        const proximity = t ? (t.proximity || {}) : { bytes: data.proximitySize };
        const replay = t ? (t.replay || {}) : { bytes: data.replaySize || 0 };
        const sentryBytes = sentry.bytes || 0;
        this.stats = {
            sentryBytes: sentryBytes,
            sentryFormatted: sentry.bytesFormatted || data.sentrySizeFormatted || this.formatSize(sentryBytes),
            // The recordings quota holds cam_*/dvr_* + replay_* + proximity_*,
            // matching the server-side reaper.
            recBytes: (normal.bytes || 0) + (replay.bytes || 0) + (proximity.bytes || 0),
            warming: (data.indexState && data.indexState.warming) ? data.indexState : null
        };
        this.renderUsage();
        // Index still building: one self-refresh timer with backoff (2s → 10s cap).
        if (this._warmingPollTimer) {
            clearTimeout(this._warmingPollTimer);
            this._warmingPollTimer = null;
        }
        if (this.stats.warming) {
            const attempt = Math.min(this._warmingPollAttempt || 0, 8);
            const delay = Math.min(2000 * Math.pow(2, attempt), 10000);
            this._warmingPollAttempt = (this._warmingPollAttempt || 0) + 1;
            this._warmingPollTimer = setTimeout(() => {
                this._warmingPollTimer = null;
                this.loadStorageStats();
            }, delay);
        } else {
            this._warmingPollAttempt = 0;
        }
        return true;
    },

    // ==================== Rendering ====================

    renderAll() {
        this.renderVolumes();
        this.renderSurveillance();
        this.renderRecordings();
        this.renderTrips();
        this.renderUsage();
        this.updateCdrVisibility();
        this.markChanged();
    },

    /** Summary tile: free / total per volume. */
    renderVolumes() {
        const v = this.volumes;
        const known = this._loaded.settings || this._loaded.trips;
        // Internal figures only come with /api/settings/storage.
        this._paintVolume('Int', this._loaded.settings, true, v.internalFreeSpace, v.internalTotalSpace, null);
        this._paintVolume('Sd', known, v.sdCardAvailable, v.sdCardFreeSpace, v.sdCardTotalSpace,
            !known ? 'recording.sd_checking'
                : (v.sdCardAvailable ? 'recording.sd_card_available' : 'recording.sd_card_not_detected'));
        this._paintVolume('Usb', known, v.usbAvailable, v.usbFreeSpace, v.usbTotalSpace,
            !known ? 'recording.usb_checking'
                : (v.usbAvailable ? 'recording.usb_available' : 'recording.usb_not_detected'));
    },

    _paintVolume(name, known, available, free, total, textKey) {
        const dot = this._el('stVol' + name + 'Dot');
        if (dot) dot.className = 'sd-status-dot ' + (known && available ? 'online' : 'offline');
        if (textKey) this._setText('stVol' + name + 'Text', this._tr(textKey));
        const show = !!(known && available && total > 0);
        const freeEl = this._el('stVol' + name + 'Free');
        if (freeEl) {
            freeEl.textContent = show ? this._tr('recording.size_free', { size: this.formatSize(free) }) : '--';
            freeEl.style.display = (show || !known) ? '' : 'none';
        }
        this._setText('stVol' + name + 'Total', show ? this._tr('recording.size_total', { size: this.formatSize(total) }) : '');
        const bar = this._el('stVol' + name + 'Bar');
        if (bar) bar.style.display = show ? '' : 'none';
        if (show) {
            this._setFill('stVol' + name + 'Fill', Math.min(100, Math.max(0, Math.round((total - free) * 100 / total))));
        }
    },

    /** Selected segment, availability gating and tooltips for one location picker. */
    _paintTypeGroup(groupId, type, loaded, sdId, usbId, blockedTitle) {
        const group = this._el(groupId);
        if (group) {
            const btns = group.querySelectorAll('.btn-toggle');
            for (let i = 0; i < btns.length; i++) {
                const val = btns[i].getAttribute('data-value');
                btns[i].classList.toggle('active', val === type);
                btns[i].setAttribute('aria-pressed', val === type ? 'true' : 'false');
                if (val === 'INTERNAL') btns[i].disabled = !loaded;
            }
        }
        const v = this.volumes;
        const sd = this._el(sdId);
        if (sd) {
            sd.disabled = !loaded || !v.sdCardAvailable || !!blockedTitle;
            sd.title = blockedTitle || (v.sdCardAvailable ? '' : this._tr('recording.sd_card_unavailable'));
        }
        const usb = this._el(usbId);
        if (usb) {
            usb.disabled = !loaded || !v.usbAvailable || !!blockedTitle;
            usb.title = blockedTitle || (v.usbAvailable ? '' : this._tr('recording.usb_unavailable'));
        }
    },

    _paintSlider(sliderId, valueId, minId, maxId, limitMb, maxMb, loaded) {
        const slider = this._el(sliderId);
        if (slider) {
            slider.max = maxMb;
            slider.value = Math.min(limitMb, maxMb);
            slider.disabled = !loaded;
        }
        this._setText(valueId, this._fmtMb(limitMb));
        this._setText(minId, this._fmtMb(100));
        this._setText(maxId, this._fmtMb(maxMb));
    },

    _maxFor(type) {
        switch (type) {
            case 'SD_CARD': return this.volumes.maxLimitMbSdCard;
            case 'USB': return this.volumes.maxLimitMbUsb;
            default: return this.volumes.maxLimitMb;
        }
    },

    /** Trips report their own ceilings (older daemons name the internal one maxLimitMbInternal). */
    _tripMaxFor(type) {
        const m = this.tripMeta;
        if (!m) return 100000;
        switch (type) {
            case 'SD_CARD': return m.maxLimitMbSdCard || 100000;
            case 'USB': return m.maxLimitMbUsb || 100000;
            default: return m.maxLimitMbInternal || m.maxLimitMb || 100000;
        }
    },

    _usbOffTitle() {
        if (this.keepUsbPowerOnAccOff !== false) return null;
        return this._tr('surveillance.storage_blocked_usb_off', null,
            'Turn "Keep USB powered" on to use SD/USB while parked');
    },

    renderSurveillance() {
        const c = this.config;
        const loaded = this._loaded.settings;
        this._paintTypeGroup('survStorageTypeBtns', c.surveillanceStorageType, loaded,
            'btnSurvSdCard', 'btnSurvUsb', this._usbOffTitle());

        const pathEl = this._el('survStoragePath');
        if (pathEl && this.survInfo.path) {
            pathEl.textContent = this._tr('surveillance.events_saved_to',
                { path: this.survInfo.path.replace('/storage/emulated/0/', '') });
        }

        // The saved SD/USB isn't where clips land (unmounted or full): say so,
        // or they silently go to internal.
        const fallbackEl = this._el('survStorageFallbackWarning');
        if (fallbackEl) {
            const configured = this.saved.surveillanceStorageType || 'INTERNAL';
            const active = this.survInfo.activeType || configured;
            if (loaded && configured !== 'INTERNAL' && active === 'INTERNAL') {
                const volName = configured === 'SD_CARD'
                    ? this._tr('events.storage_sd_card', null, 'microSD card')
                    : this._tr('events.storage_usb', null, 'USB');
                fallbackEl.textContent = this._tr('surveillance.storage_fallback_warning', { volume: volName },
                    volName + ' unavailable — saving to internal storage');
                fallbackEl.style.display = 'block';
            } else {
                fallbackEl.style.display = 'none';
            }
        }

        this._paintSlider('survLimitSlider', 'survLimitValue', 'survLimitMin', 'survLimitMax',
            c.surveillanceLimitMb, this._maxFor(c.surveillanceStorageType), loaded);
        this.renderBudget('surveillance');
    },

    renderRecordings() {
        const c = this.config;
        const loaded = this._loaded.settings;
        this._paintTypeGroup('recStorageTypeBtns', c.recordingsStorageType, loaded,
            'btnRecSdCard', 'btnRecUsb', null);

        const banner = this._el('recStorageFallbackBanner');
        if (banner) {
            const configured = this.saved.recordingsStorageType;
            const active = this.recInfo.activeType || configured;
            const fellBack = loaded && (configured === 'SD_CARD' || configured === 'USB') && active === 'INTERNAL';
            banner.style.display = fellBack ? 'flex' : 'none';
            // While on the internal fallback the limit is clamped to what internal holds.
            const textEl = this._el('recStorageFallbackText');
            if (textEl && fellBack) {
                const eff = this.recInfo.effectiveLimitMb || 0;
                const cfgMb = this.saved.recordingsLimitMb || 0;
                textEl.textContent = (eff > 0 && cfgMb > 0 && eff < cfgMb)
                    ? this._tr('recording.storage_fallback_enforcing', { mb: eff })
                    : this._tr('recording.storage_fallback_internal');
            }
        }

        const pathEl = this._el('recStoragePath');
        if (pathEl && this.recInfo.path) {
            pathEl.textContent = this._tr('recording.saved_to',
                { path: this.recInfo.path.replace('/storage/emulated/0/', '') });
        }

        this._paintSlider('recLimitSlider', 'recLimitValue', 'recLimitMin', 'recLimitMax',
            c.recordingsLimitMb, this._maxFor(c.recordingsStorageType), loaded);
        this.renderBudget('recordings');
    },

    renderTrips() {
        const c = this.config;
        const loaded = this._loaded.trips;
        this._paintTypeGroup('tripStorageTypeBtns', c.tripsStorageType, loaded,
            'storageSdCard', 'storageUsb', null);

        const pathEl = this._el('tripStoragePath');
        const u = this.tripUsage;
        if (pathEl && u) {
            if (u.path) {
                pathEl.textContent = u.path;
            } else {
                const type = this.saved.tripsStorageType;
                pathEl.textContent = type === 'SD_CARD' ? this._tr('trip.sd_path_default')
                    : (type === 'USB' ? this._tr('trip.usb_path_default') : this._tr('trip.internal_path_default'));
            }
        }

        this._paintSlider('storageLimitSlider', 'storageLimitValue', 'tripLimitMin', 'tripLimitMax',
            c.tripsLimitMb, this._tripMaxFor(c.tripsStorageType), loaded);
        this.renderTripUsage();
        this.renderBudget('trips');
    },

    renderUsage() {
        const s = this.stats;
        if (!s) return;
        const pct = (bytes, mb) => Math.min(100, Math.round(bytes * 100 / (mb * 1024 * 1024)));
        const survMb = this.config.surveillanceLimitMb || 500;
        const recMb = this.config.recordingsLimitMb || 500;
        let survUsed = this._tr('surveillance.size_used', { size: s.sentryFormatted });
        let recUsed = this._tr('recording.storage_used', { size: this.formatSize(s.recBytes) });
        // Index still building: show progress instead of a stale 0 MB.
        if (s.warming) {
            const done = s.warming.done || 0;
            const total = s.warming.total || 0;
            const p = total > 0 ? Math.round(done * 100 / total) : 0;
            const fb = 'Indexing — ' + done + ' / ' + total + ' (' + p + '%)';
            survUsed = this._tr('surveillance.storage_indexing', { done: done, total: total, pct: p }, fb);
            recUsed = this._tr('recording.storage_indexing', { done: done, total: total, pct: p }, fb);
        }
        this._setText('survStorageUsed', survUsed);
        this._setText('survStorageLimit', this._tr('surveillance.size_limit_mb', { mb: survMb }));
        this._setFill('survStorageFill', pct(s.sentryBytes, survMb));
        this._setText('storageUsed', recUsed);
        this._setText('storageLimit', this._tr('recording.storage_limit_mb', { mb: recMb }));
        this._setFill('storageFill', pct(s.recBytes, recMb));
    },

    renderTripUsage() {
        const u = this.tripUsage;
        if (!u) return;
        const limitMb = this.config.tripsLimitMb || 500;
        const fill = this._el('storageUsageFill');
        if (fill) {
            const pct = u.unit === 'KB' ? ((u.usedMb / 1024) / limitMb * 100) : (u.usedMb / limitMb * 100);
            fill.style.width = Math.min(100, Math.max(pct, u.count > 0 ? 0.5 : 0)) + '%';
        }
        let used;
        if (u.unit === 'KB') used = u.usedMb + ' KB / ' + limitMb + ' MB';
        else if (u.usedMb === 0 && u.count > 0) used = '< 1 MB / ' + limitMb + ' MB';
        else used = u.usedMb.toFixed(1) + ' MB / ' + limitMb + ' MB';
        this._setText('storageUsedText', used);
        if (BYD.i18n && BYD.i18n.plural) this._setText('storageTripsCount', BYD.i18n.plural('trip.stored', u.count));
    },

    /**
     * Combined-limit advisory against the live (unsaved) slider value. Only a
     * genuine unsaved pick counts as a volume move (see trips.js updateBudgetBanner).
     */
    renderBudget(section) {
        if (!BYD.storageBudget) return;
        const c = this.config;
        const keys = this._fields[section];
        const type = c[keys[0]] !== this.saved[keys[0]] ? c[keys[0]] : null;
        const target = {
            surveillance: ['survBudgetBanner', 'surveillance'],
            recordings: ['recBudgetBanner', 'recordings'],
            trips: ['tripBudgetBanner', 'trips']
        }[section];
        BYD.storageBudget.render(target[0], this.budget, target[1], c[keys[1]], type);
    },

    // ==================== Edits (staged until Apply) ====================

    setSurvStorageType(type) {
        if (!this._editable('settings')) return;
        if (type === 'SD_CARD' && !this.volumes.sdCardAvailable) {
            this._toast(this._tr('recording.sd_card_unavailable'), 'error');
            return;
        }
        if (type === 'USB' && !this.volumes.usbAvailable) {
            this._toast(this._tr('recording.usb_unavailable'), 'error');
            return;
        }
        // Never stage a target a parked session can't write to.
        if ((type === 'SD_CARD' || type === 'USB') && this.keepUsbPowerOnAccOff === false) {
            this._toast(this._usbOffTitle(), 'warning');
            return;
        }
        this.config.surveillanceStorageType = type;
        // Re-clamp to the destination volume's ceiling.
        const max = this._maxFor(type);
        if (this.config.surveillanceLimitMb > max) this.config.surveillanceLimitMb = max;
        this.renderSurveillance();
        this.renderUsage();
        this.updateCdrVisibility();
        this.markChanged();
    },

    updateSurvLimit(value) {
        if (!this._editable('settings')) return;
        const v = parseInt(value, 10);
        if (isNaN(v)) return;
        this.config.surveillanceLimitMb = v;
        this._setText('survLimitValue', this._fmtMb(v));
        // Live advisory while dragging.
        this.renderBudget('surveillance');
        this.renderUsage();
        this.markChanged();
    },

    setRecStorageType(type) {
        if (!this._editable('settings')) return;
        if (type === 'SD_CARD' && !this.volumes.sdCardAvailable) {
            this._toast(this._tr('recording.sd_card_unavailable'), 'error');
            return;
        }
        if (type === 'USB' && !this.volumes.usbAvailable) {
            this._toast(this._tr('recording.usb_unavailable'), 'error');
            return;
        }
        this.config.recordingsStorageType = type;
        const max = this._maxFor(type);
        if (this.config.recordingsLimitMb > max) this.config.recordingsLimitMb = max;
        this.renderRecordings();
        this.renderUsage();
        this.updateCdrVisibility();
        this.markChanged();
    },

    updateRecLimit(value) {
        if (!this._editable('settings')) return;
        const v = parseInt(value, 10);
        if (isNaN(v)) return;
        this.config.recordingsLimitMb = v;
        this._setText('recLimitValue', this._fmtMb(v));
        this.renderBudget('recordings');
        this.renderUsage();
        this.markChanged();
    },

    setTripStorageType(type) {
        if (!this._editable('trips')) return;
        if (type === 'SD_CARD' && !this.volumes.sdCardAvailable) {
            this._toast(this._tr('recording.sd_card_unavailable'), 'error');
            return;
        }
        if (type === 'USB' && !this.volumes.usbAvailable) {
            this._toast(this._tr('recording.usb_unavailable'), 'error');
            return;
        }
        this.config.tripsStorageType = type;
        const max = this._tripMaxFor(type);
        if (this.config.tripsLimitMb > max) this.config.tripsLimitMb = max;
        this.renderTrips();
        this.updateCdrVisibility();
        this.markChanged();
    },

    updateTripLimit(value) {
        if (!this._editable('trips')) return;
        const v = parseInt(value, 10);
        if (isNaN(v)) return;
        this.config.tripsLimitMb = v;
        this._setText('storageLimitValue', this._fmtMb(v));
        this.renderBudget('trips');
        this.renderTripUsage();
        this.markChanged();
    },

    // ==================== Apply ====================

    applySettings() {
        return this._enqueueWrite(() => this._applyNow());
    },

    async _applyNow() {
        const sections = ['surveillance', 'recordings', 'trips'].filter(s => this._isDirty(s));
        const btn = this._el('btnApply');
        if (!sections.length || this._applying) {
            this.markChanged();
            return;
        }
        const origHtml = btn ? btn.innerHTML : '';
        this._applying = true;
        this._applyVersion++;
        if (btn) {
            btn.disabled = true;
            btn.textContent = this._tr('common.saving', null, 'Saving…');
        }
        const errors = [];
        let rejected = null;
        let deleting = null;
        try {
            // One request per category, each with its own revert, so a rejected
            // volume on one never strands another half-applied.
            for (let i = 0; i < sections.length; i++) {
                const section = sections[i];
                try {
                    if (section === 'trips') {
                        rejected = await this._commitTrips();
                    } else {
                        const data = await this._commitSettingsSection(section);
                        const cleanup = data && data.cleanup;
                        if (cleanup && section === 'surveillance' && cleanup.surveillanceToDelete) {
                            deleting = this._tr('surveillance.settings_applied_deleting',
                                { files: cleanup.surveillanceFilesEstimate, size: cleanup.surveillanceToDelete });
                        } else if (cleanup && section === 'recordings' && cleanup.recordingsToDelete) {
                            deleting = this._tr('recording.settings_applied_deleting',
                                { files: cleanup.recordingsFilesEstimate, size: cleanup.recordingsToDelete });
                        }
                    }
                } catch (e) {
                    console.error('[Storage] apply ' + section + ' failed:', e);
                    errors.push({ section: section, error: e });
                }
            }
        } finally {
            this._applying = false;
        }

        this.renderAll();
        // Cleanup may run after a limit change; refresh what the server now holds.
        setTimeout(() => this.loadStorageStats(), 1000);
        if (sections.indexOf('surveillance') !== -1 || sections.indexOf('recordings') !== -1) this.loadStorageSettings();
        if (sections.indexOf('trips') !== -1) this.loadTripStorage();

        if (errors.length) {
            const first = errors[0];
            const msg = first.section === 'surveillance'
                ? this._tr('surveillance.save_failed',
                    { error: (first.error && first.error.message) || this._tr('errors.generic') })
                : this._tr('recording.save_settings_failed');
            this._toast(msg, 'error');
            if (btn) btn.innerHTML = origHtml;
        } else if (rejected) {
            const fields = rejected.map(r => r.field).join(', ');
            this._toast(this._tr('trip.values_rejected', { fields: fields }, 'Some values rejected: ' + fields), 'warning');
            if (btn) btn.innerHTML = origHtml;
        } else {
            this._toast(deleting || this._tr('recording.settings_applied'), 'success');
            if (btn) {
                btn.textContent = this._tr('surveillance.saved_check');
                setTimeout(() => {
                    btn.innerHTML = origHtml;
                    this.markChanged();
                }, 1500);
            }
        }
        this.markChanged();
    },

    /** POST one category's changed fields to /api/settings/storage (partial writer). */
    async _commitSettingsSection(section) {
        const keys = this._fields[section];
        const typeKey = keys[0];
        const limitKey = keys[1];
        const body = {};
        if (this.config[typeKey] !== this.saved[typeKey]) body[typeKey] = this.config[typeKey];
        if (this.config[limitKey] !== this.saved[limitKey]) body[limitKey] = this.config[limitKey];

        // USB power may have been turned off on another page since the pick.
        if (section === 'surveillance' && body.surveillanceStorageType && body.surveillanceStorageType !== 'INTERNAL') {
            await this.loadKeepUsbPower();
            if (this.keepUsbPowerOnAccOff === false) {
                this._revert(section);
                throw new Error(this._usbOffTitle());
            }
        }

        const resp = await fetch('/api/settings/storage', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        let data = null;
        try { data = await resp.json(); } catch (e) { data = null; }
        // {success:false} = a rejected storage TYPE; the server checks types
        // before limits, so nothing was committed: back to the baseline.
        if (!resp.ok || !data || data.success !== true) {
            this._revert(section);
            throw new Error((data && data.error) || ('Storage save failed: ' + resp.status));
        }
        // Baseline off the committed (possibly clamped) values.
        if (typeof data[limitKey] === 'number') this.config[limitKey] = data[limitKey];
        if (data[typeKey]) this.config[typeKey] = data[typeKey];
        this._commit(section);
        const info = section === 'surveillance' ? this.survInfo : this.recInfo;
        if (data[typeKey + 'Active']) info.activeType = data[typeKey + 'Active'];
        const path = data[section === 'surveillance' ? 'surveillancePath' : 'recordingsPath'];
        if (path) info.path = path;
        return data;
    },

    /** POST the changed trip fields; returns the server's per-field rejections, if any. */
    async _commitTrips() {
        const body = {};
        if (this.config.tripsStorageType !== this.saved.tripsStorageType) body.storageType = this.config.tripsStorageType;
        if (this.config.tripsLimitMb !== this.saved.tripsLimitMb) body.storageLimitMb = this.config.tripsLimitMb;
        const resp = await fetch('/api/trips/storage', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        let data = null;
        try { data = await resp.json(); } catch (e) { data = null; }
        if (!resp.ok || !data || data.success !== true) {
            this._revert('trips');
            throw new Error((data && data.error) || ('Trip storage save failed: ' + resp.status));
        }
        // An unavailable volume keeps the old type and a limit may be clamped:
        // adopt what the server kept.
        if (data.appliedType) this.config.tripsStorageType = data.appliedType;
        if (typeof data.appliedLimitMb === 'number') this.config.tripsLimitMb = data.appliedLimitMb;
        this._commit('trips');
        return (data.rejected && data.rejected.length) ? data.rejected : null;
    },

    // ==================== Factory dashcam (DVR) clean-up ====================

    /** SD-only: shown while any category is (or is being set) on the microSD card. */
    _cdrWanted() {
        const c = this.config;
        const sd = this.volumes.sdCardAvailable;
        return (this._loaded.settings && sd
                && (c.surveillanceStorageType === 'SD_CARD' || c.recordingsStorageType === 'SD_CARD'))
            || (this._loaded.trips && c.tripsStorageType === 'SD_CARD');
    },

    _cdrVisible() {
        const card = this._el('cdrCleanupCard');
        return !!card && card.style.display !== 'none';
    },

    updateCdrVisibility() {
        const card = this._el('cdrCleanupCard');
        if (!card) return false;
        const wasVisible = card.style.display !== 'none';
        const show = this._cdrWanted();
        card.style.display = show ? 'block' : 'none';
        if (show && !wasVisible) {
            this._cdrReady = false;
            this.updateCdrUI();
            if (this._hydrated) return this.loadCdrConfig();
        }
        return false;
    },

    loadCdrConfig(fromTick) {
        if (fromTick && this._cdrLoading) return this._cdrLoading;
        const p = this._loadCdrConfigNow();
        this._cdrLoading = p;
        p.then(() => { if (this._cdrLoading === p) this._cdrLoading = null; });
        return p;
    },

    async _loadCdrConfigNow() {
        if (this._cdrWritesPending > 0) return false;
        const writeVersion = this._cdrWriteVersion;
        try {
            const resp = await fetch('/api/storage/external');
            const data = await resp.json();
            if (data.success
                    && this._cdrWritesPending === 0
                    && writeVersion === this._cdrWriteVersion) {
                this._cdrReady = true;
                this.cdrConfig.enabled = !!data.cleanupEnabled;
                if (typeof data.reservedSpaceMb === 'number') {
                    this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb;
                }
                if (typeof data.protectedHours === 'number') {
                    this.cdrConfig.protectedHours = data.protectedHours;
                }
                if (typeof data.minFilesKeep === 'number') {
                    this.cdrConfig.minFilesKeep = data.minFilesKeep;
                }
                this.cdrInfo = {
                    cdrPath: data.cdrPath,
                    cdrUsage: data.cdrUsageFormatted,
                    cdrFileCount: data.cdrFileCount,
                    cdrProtected: data.cdrProtectedFormatted,
                    cdrDeletable: data.cdrDeletableFormatted,
                    totalFreed: data.totalBytesFreedFormatted,
                    totalDeleted: data.totalFilesDeleted,
                    monitoringActive: !!data.monitoringActive,
                    lastCleanupTime: data.lastCleanupTime || 0,
                    recommendAutoCleanup: !!data.recommendAutoCleanup
                };
                this.updateCdrUI();
                return true;
            }
        } catch (e) {
            console.warn('Failed to load CDR config:', e);
        }
        return false;
    },

    updateCdrUI() {
        const busy = Object.keys(this._cdrDirty).length > 0;
        const toggle = this._el('cdrCleanupEnabled');
        if (toggle) {
            if (!this._cdrTogglePending) toggle.checked = this.cdrConfig.enabled;
            toggle.disabled = !this._cdrReady
                || this._cdrTogglePending || this._cdrCleanupPending
                || this._cdrWritesPending > 0 || !!this._cdrSaveTimer || busy;
        }
        const cleanupButton = this._el('cdrCleanupNow');
        if (cleanupButton) {
            cleanupButton.disabled = !this._cdrReady || !this.cdrConfig.enabled
                || this._cdrCleanupPending || this._cdrWritesPending > 0
                || !!this._cdrSaveTimer || busy;
        }

        const badge = this._el('cdrCleanupBadge');
        if (badge) {
            badge.textContent = this.cdrConfig.enabled ? this._tr('status.on') : this._tr('status.off');
            badge.className = 'status-badge ' + (this.cdrConfig.enabled ? 'active' : 'inactive');
        }

        const slidersLocked = !this._cdrReady || this._cdrCleanupPending
            || this._cdrTogglePending || this._cdrWritesPending > 0;
        const reservedSlider = this._el('cdrReservedSlider');
        if (reservedSlider) {
            reservedSlider.value = this.cdrConfig.reservedSpaceMb;
            reservedSlider.disabled = slidersLocked;
        }
        this._setText('cdrReservedValue', this._fmtMb(this.cdrConfig.reservedSpaceMb));

        const protectedSlider = this._el('cdrProtectedSlider');
        if (protectedSlider) {
            protectedSlider.value = this.cdrConfig.protectedHours;
            protectedSlider.disabled = slidersLocked;
        }
        this._setText('cdrProtectedValue', this._tr('recording.unit_hours', { n: this.cdrConfig.protectedHours }));

        const minKeepSlider = this._el('cdrMinKeepSlider');
        if (minKeepSlider) {
            minKeepSlider.value = this.cdrConfig.minFilesKeep;
            minKeepSlider.disabled = slidersLocked;
        }
        this._setText('cdrMinKeepValue', this.cdrConfig.minFilesKeep);

        const info = this.cdrInfo;
        if (!info) return;
        this._setText('cdrPath', info.cdrPath || this._tr('recording.not_found'));
        this._setText('cdrUsage', info.cdrUsage || '--');
        this._setText('cdrFileCount', info.cdrFileCount == null ? '--' : info.cdrFileCount);
        this._setText('cdrProtected', info.cdrProtected || '--');
        this._setText('cdrDeletable', info.cdrDeletable || '--');

        const monEl = this._el('cdrMonitoring');
        if (monEl) {
            if (!this.cdrConfig.enabled) {
                monEl.textContent = this._tr('common.disabled');
                monEl.style.color = '';
            } else if (info.monitoringActive) {
                monEl.textContent = this._tr('common.running');
                monEl.style.color = 'var(--status-success)';
            } else {
                monEl.textContent = this._tr('common.idle');
                monEl.style.color = 'var(--on-surface-variant)';
            }
        }

        this._setText('cdrLastCleanup', this._formatRelativeTime(info.lastCleanupTime));

        const banner = this._el('cdrRecommendBanner');
        if (banner) banner.style.display = info.recommendAutoCleanup ? 'block' : 'none';

        this._setText('cdrTotalFreed', info.totalFreed || '0 B');
        this._setText('cdrTotalDeleted', info.totalDeleted == null ? '--' : info.totalDeleted);
    },

    _formatRelativeTime(ts) {
        if (!ts || ts <= 0) return this._tr('recording.never');
        const diffSec = Math.floor((Date.now() - ts) / 1000);
        if (diffSec < 0) return this._tr('recording.just_now');
        if (diffSec < 60) return this._tr('recording.seconds_ago', { n: diffSec });
        if (diffSec < 3600) return this._tr('recording.minutes_ago', { n: Math.floor(diffSec / 60) });
        if (diffSec < 86400) return this._tr('recording.hours_ago', { n: Math.floor(diffSec / 3600) });
        return this._tr('recording.days_ago', { n: Math.floor(diffSec / 86400) });
    },

    async toggleCdrCleanup() {
        const toggle = this._el('cdrCleanupEnabled');
        if (!toggle || !this._hydrated || !this._cdrReady
                || this._cdrTogglePending || this._cdrCleanupPending
                || this._cdrWritesPending > 0 || this._cdrSaveTimer
                || Object.keys(this._cdrDirty).length > 0) return;
        const enabled = toggle.checked;
        const previous = this.cdrConfig.enabled;
        let saved = false;
        this._cdrTogglePending = true;
        this._cdrWriteVersion++;
        this._cdrWritesPending++;
        this.updateCdrUI();
        try {
            const data = await this._postJson('/api/storage/external/config', { enabled: enabled });
            if (!data) {
                this.cdrConfig.enabled = previous;
                this._toast(this._tr('recording.cdr_toggle_failed'), 'error');
                return;
            }
            saved = true;
            this.cdrConfig.enabled = data.cleanupEnabled == null ? enabled : !!data.cleanupEnabled;
            if (typeof data.reservedSpaceMb === 'number') {
                this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb;
            }
            if (typeof data.protectedHours === 'number') {
                this.cdrConfig.protectedHours = data.protectedHours;
            }
            if (typeof data.minFilesKeep === 'number') {
                this.cdrConfig.minFilesKeep = data.minFilesKeep;
            }
            this.updateCdrUI();
            this._toast(this.cdrConfig.enabled
                ? this._tr('recording.cdr_enabled')
                : this._tr('recording.cdr_disabled'), 'success');
        } finally {
            this._cdrWritesPending--;
            this._cdrTogglePending = false;
            if (!saved) this._cdrReady = false;
            this.updateCdrUI();
            if (!this._cdrReady && this._cdrWritesPending === 0 && !this._cdrSaveTimer) {
                this.loadCdrConfig();
            }
        }
    },

    _cdrSlidersLocked() {
        return !this._hydrated || !this._cdrReady || this._cdrCleanupPending
            || this._cdrTogglePending || this._cdrWritesPending > 0;
    },

    updateCdrReserved(value) {
        if (this._cdrSlidersLocked()) return;
        const v = parseInt(value, 10);
        this.cdrConfig.reservedSpaceMb = v;
        this._cdrDirty.reservedSpaceMb = v;
        this._cdrWriteVersion++;
        this._setText('cdrReservedValue', this._fmtMb(v));
        this.saveCdrConfig();
    },

    updateCdrProtected(value) {
        if (this._cdrSlidersLocked()) return;
        const v = parseInt(value, 10);
        this.cdrConfig.protectedHours = v;
        this._cdrDirty.protectedHours = v;
        this._cdrWriteVersion++;
        this._setText('cdrProtectedValue', this._tr('recording.unit_hours', { n: v }));
        this.saveCdrConfig();
    },

    updateCdrMinKeep(value) {
        if (this._cdrSlidersLocked()) return;
        const v = parseInt(value, 10);
        this.cdrConfig.minFilesKeep = v;
        this._cdrDirty.minFilesKeep = v;
        this._cdrWriteVersion++;
        this._setText('cdrMinKeepValue', v);
        this.saveCdrConfig();
    },

    /** DVR sliders save on their own (debounced), as on the source pages. */
    saveCdrConfig() {
        if (this._cdrSaveTimer) clearTimeout(this._cdrSaveTimer);
        this._cdrSaveTimer = setTimeout(() => {
            this._cdrSaveTimer = null;
            this._flushCdrConfig();
        }, 200);
        this.updateCdrUI();
    },

    async _flushCdrConfig() {
        if (!this._hydrated || !this._cdrReady) return;
        const body = this._cdrDirty;
        this._cdrDirty = {};
        if (Object.keys(body).length === 0) return;
        const writeVersion = this._cdrWriteVersion;
        this._cdrWritesPending++;
        this.updateCdrUI();
        let saved = false;
        try {
            const data = await this._postJson('/api/storage/external/config', body);
            if (!data) return;
            saved = true;
            if (writeVersion !== this._cdrWriteVersion) return;
            if (data.cleanupEnabled != null) {
                this.cdrConfig.enabled = !!data.cleanupEnabled;
            }
            if (typeof data.reservedSpaceMb === 'number') this.cdrConfig.reservedSpaceMb = data.reservedSpaceMb;
            if (typeof data.protectedHours === 'number') this.cdrConfig.protectedHours = data.protectedHours;
            if (typeof data.minFilesKeep === 'number') this.cdrConfig.minFilesKeep = data.minFilesKeep;
            this.updateCdrUI();
        } finally {
            this._cdrWritesPending--;
            if (!saved) this._cdrReady = false;
            this.updateCdrUI();
            if (!this._cdrReady && this._cdrWritesPending === 0 && !this._cdrSaveTimer) {
                this.loadCdrConfig();
            }
        }
    },

    _cdrCleanupBlocked() {
        const button = this._el('cdrCleanupNow');
        return !this._hydrated || !this._cdrReady || !this.cdrConfig.enabled
            || this._cdrCleanupPending || this._cdrWritesPending > 0
            || !!this._cdrSaveTimer || Object.keys(this._cdrDirty).length > 0
            || !!(button && button.disabled);
    },

    /** Deletes factory dashcam files, so it asks first (as the Trips page did). */
    _confirmCdrCleanup() {
        const body = this._tr('trip.cdr.delete_confirm', null, 'Delete old BYD dashcam files to free space?');
        if (BYD.utils && BYD.utils.confirmDialog) {
            return Promise.resolve(BYD.utils.confirmDialog({
                title: this._tr('recording.cdr_title'),
                body: body,
                confirmLabel: this._tr('recording.cdr_cleanup_now_v2'),
                cancelLabel: this._tr('common.cancel'),
                danger: true
            })).catch(() => false);
        }
        return Promise.resolve(window.confirm(body));
    },

    async triggerCdrCleanup() {
        if (this._cdrCleanupBlocked()) return;
        if (!await this._confirmCdrCleanup()) return;
        if (this._cdrCleanupBlocked()) return;
        this._cdrCleanupPending = true;
        this.updateCdrUI();
        try {
            this._toast(this._tr('recording.cdr_cleaning'), 'info');
            const resp = await fetch('/api/storage/external/cleanup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data.success) {
                const msg = data.filesDeleted > 0
                    ? this._tr('recording.cdr_freed', { size: data.freedFormatted, files: data.filesDeleted })
                    : this._tr('recording.cdr_no_cleanup');
                this._toast(msg, 'success');
                this.loadCdrConfig();
            } else {
                this._toast(data.error || this._tr('recording.cdr_cleanup_failed'), 'error');
            }
        } catch (e) {
            this._toast(this._tr('recording.cdr_trigger_failed'), 'error');
        } finally {
            this._cdrCleanupPending = false;
            this.updateCdrUI();
        }
    },

    // ==================== Recover missing trips ====================

    /**
     * Rebuild trip rows from telemetry still on disk. The scan runs server-side
     * in the background, so start it and poll /api/trips/recover/status.
     */
    async recoverTrips() {
        if (this._recoverRunning) return;
        const btn = this._el('tripRecoverBtn');
        const out = this._el('tripRecoverResult');
        const t = (k, fb) => this._tr(k, null, fb);
        this._recoverRunning = true;
        if (btn) {
            btn.disabled = true;
            btn.textContent = t('trip.recover.scanning', 'Scanning…');
        }
        if (out) out.style.display = 'none';

        const finish = (data) => {
            const msg = (data && data.message) || (data && data.success
                ? t('trip.recover.done', 'Recovery complete.')
                : t('trip.recover.failed', 'Recovery failed.'));
            if (out) {
                out.textContent = msg;
                out.style.display = 'block';
            }
            this._toast(msg, (data && data.success) ? (data.recovered > 0 ? 'success' : 'info') : 'error');
            // Recovered telemetry counts against the trip limit.
            if (data && data.success && data.recovered > 0) this.loadTripStorage();
            this._recoverRunning = false;
            if (btn) {
                btn.disabled = false;
                btn.textContent = t('trip.recover.button', 'Scan & Recover Trips');
            }
        };

        const poll = async (attempt) => {
            // Up to ~10 min (200 × 3s) for a large FUSE-bridged SD.
            if (attempt > 200) {
                finish({ success: false, message: t('trip.recover.timeout', 'Recovery is taking too long. Check the Trips tab shortly.') });
                return;
            }
            try {
                const r = await fetch('/api/trips/recover/status');
                const s = await r.json();
                if (s && s.done) {
                    finish(s);
                    return;
                }
                setTimeout(() => poll(attempt + 1), 3000);
            } catch (e) {
                setTimeout(() => poll(attempt + 1), 3000);
            }
        };

        try {
            const resp = await fetch('/api/trips/recover', { method: 'POST' });
            const data = await resp.json();
            if (!resp.ok || (data && data.error)) {
                finish({ success: false, message: (data && (data.message || data.error)) || t('trip.recover.failed', 'Recovery failed.') });
                return;
            }
            // started:false + running = another run is going; poll that one.
            poll(0);
        } catch (e) {
            console.warn('[Storage] recover start failed:', e);
            finish({ success: false, message: t('trip.recover.failed', 'Recovery failed.') });
        }
    }
};

window.StoragePage = BYD.storagePage;
