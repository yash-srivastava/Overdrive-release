/**
 * BYD Champ - ABRP Telemetry Module
 * Manages ABRP token configuration, service control, and live telemetry display
 */

const ABRP = {
    refreshInterval: null,
    enabled: false,
    _hydrated: false,
    _writeQueue: Promise.resolve(),
    _configWriteVersion: 0,
    _configWritesPending: 0,

    async init() {
        this._hydrated = await this.loadConfig();
        if (BYD.utils && BYD.utils.unlockSettingsHydration) {
            BYD.utils.unlockSettingsHydration();
        }
        this.loadStatus();
        this.startAutoRefresh();
    },

    _enqueueWrite(task) {
        const run = () => task();
        const next = this._writeQueue.then(run, run);
        this._writeQueue = next.catch(() => {});
        return next;
    },

    async _ensureHydrated() {
        if (this._hydrated) return true;
        this._hydrated = await this.loadConfig();
        return this._hydrated;
    },

    async _reloadConfigWhenIdle() {
        let queue;
        do {
            queue = this._writeQueue;
            await queue;
        } while (queue !== this._writeQueue);
        return this.loadConfig();
    },

    async _postConfig(data) {
        this._configWriteVersion++;
        this._configWritesPending++;
        try {
            return await this._enqueueWrite(async () => {
                try {
                    const resp = await fetch('/api/abrp/config', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(data)
                    });
                    const result = await resp.json();
                    if (!resp.ok || !result || result.success !== true) {
                        throw new Error(result && result.error ? result.error : 'ABRP config rejected');
                    }
                    return result;
                } catch (e) {
                    console.warn('[ABRP] Failed to save config:', e);
                    return null;
                }
            });
        } finally {
            this._configWritesPending--;
        }
    },

    _setDataSavingDisabled(disabled) {
        [
            'abrpChangeOnly', 'abrpMinInterval', 'abrpMaxInterval',
            'abrpGateOnApp', 'abrpAppMode', 'abrpAppGrace'
        ].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.disabled = disabled;
        });
    },

    _toastSaveFailed() {
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(BYD.i18n.t('common.error'), 'error');
        }
    },

    async loadConfig() {
        if (this._configWritesPending > 0) return false;
        const writeVersion = this._configWriteVersion;
        try {
            const resp = await fetch('/api/abrp/config');
            const data = await resp.json();
            if (this._configWritesPending > 0
                    || writeVersion !== this._configWriteVersion) return false;
            if (data.success && data.config) {
                const cfg = data.config;
                const hasToken = cfg.user_token && cfg.user_token.length > 0;

                if (hasToken) {
                    document.getElementById('tokenDisplay').style.display = 'block';
                    document.getElementById('tokenInput').style.display = 'none';
                    document.getElementById('maskedToken').textContent = cfg.user_token;
                } else {
                    document.getElementById('tokenDisplay').style.display = 'none';
                    document.getElementById('tokenInput').style.display = 'block';
                }

                document.getElementById('abrpEnabled').checked = cfg.enabled || false;
                this.enabled = !!cfg.enabled;

                // Data-saving + app-gate controls
                var set = function (id, v) { var el = document.getElementById(id); if (el) el.value = v; };
                var chk = function (id, v) { var el = document.getElementById(id); if (el) el.checked = v; };
                chk('abrpChangeOnly', cfg.change_only !== false);
                set('abrpMinInterval', cfg.min_interval_seconds || 5);
                set('abrpMaxInterval', cfg.max_interval_seconds || 120);
                chk('abrpGateOnApp', cfg.gate_on_app || false);
                set('abrpAppMode', cfg.app_active_mode || 'foreground');
                set('abrpAppGrace', cfg.app_grace_seconds || 90);
                this.onSlider();
                this.onGateToggle();
                return true;
            }
        } catch (e) {
            console.warn('[ABRP] Failed to load config:', e);
        }
        return false;
    },

    // Human-friendly interval label: 45 -> "45s", 120 -> "2m".
    fmtInterval(sec) {
        sec = parseInt(sec) || 0;
        if (sec < 60) return sec + BYD.units.secondSuffix(true);
        var s = BYD.units.minuteSuffix(true, true);
        return (sec % 60 === 0) ? (sec / 60) + s : (sec / 60).toFixed(1) + s;
    },

    onSlider() {
        var pairs = [['abrpMinInterval', 'abrpMinLabel'], ['abrpMaxInterval', 'abrpMaxLabel'], ['abrpAppGrace', 'abrpGraceLabel']];
        for (var i = 0; i < pairs.length; i++) {
            var inp = document.getElementById(pairs[i][0]);
            var lab = document.getElementById(pairs[i][1]);
            if (inp && lab) lab.textContent = this.fmtInterval(inp.value);
        }
    },

    onGateToggle() {
        var gate = document.getElementById('abrpGateOnApp');
        var opts = document.getElementById('abrpGateOptions');
        if (gate && opts) opts.style.display = gate.checked ? '' : 'none';
    },

    async saveDataSaving() {
        var data = {
            change_only: document.getElementById('abrpChangeOnly').checked,
            min_interval_seconds: parseInt(document.getElementById('abrpMinInterval').value) || 5,
            max_interval_seconds: parseInt(document.getElementById('abrpMaxInterval').value) || 120,
            gate_on_app: document.getElementById('abrpGateOnApp').checked,
            app_active_mode: document.getElementById('abrpAppMode').value,
            app_grace_seconds: parseInt(document.getElementById('abrpAppGrace').value) || 90
        };
        if (data.max_interval_seconds < data.min_interval_seconds) {
            data.max_interval_seconds = data.min_interval_seconds;
        }
        this._setDataSavingDisabled(true);
        try {
            if (!await this._ensureHydrated()) {
                this._toastSaveFailed();
                return;
            }
            const saved = await this._postConfig(data);
            await this._reloadConfigWhenIdle();
            if (!saved) this._toastSaveFailed();
        } finally {
            this._setDataSavingDisabled(false);
        }
    },

    async loadStatus() {
        try {
            const resp = await fetch('/api/abrp/status');
            const data = await resp.json();
            if (data.success && data.status) {
                const s = data.status;

                // Connection status — render a status-dot + label so the
                // disclosure is glanceable without leaning on emoji glyphs.
                const statusEl = document.getElementById('connectionStatus');
                if (statusEl) {
                    const cls = s.running ? 'connected' : 'off';
                    const label = s.running ? BYD.i18n.t('abrp.connected') : BYD.i18n.t('abrp.disconnected');
                    statusEl.innerHTML = '<span class="status-dot ' + cls + '"></span> <span>' + label + '</span>';
                }

                // Last upload
                const lastEl = document.getElementById('lastUpload');
                if (lastEl) {
                    if (s.lastUploadTime && s.lastUploadTime > 0) {
                        lastEl.textContent = new Date(s.lastUploadTime).toLocaleTimeString(BYD.i18n.getLang());
                    } else {
                        lastEl.textContent = BYD.i18n.t('abrp.never');
                    }
                }

                // Upload counts
                const countEl = document.getElementById('uploadCount');
                if (countEl) {
                    countEl.textContent = BYD.i18n.t('abrp.upload_count', {total: s.totalUploads || 0, failed: s.failedUploads || 0});
                }

                // ABRP app presence (when the app gate is enabled)
                const presEl = document.getElementById('abrpAppPresence');
                if (presEl) {
                    if (s.appGate && s.abrp_app_state) {
                        presEl.textContent = s.abrp_app_state + (s.abrp_app_active ? ' ✓' : '');
                    } else {
                        presEl.textContent = '--';
                    }
                }

                // Telemetry table
                if (s.lastTelemetry) {
                    this.updateTelemetryTable(s.lastTelemetry);
                }
            }
        } catch (e) {
            console.warn('[ABRP] Failed to load status:', e);
        }
    },

    async saveToken() {
        const field = document.getElementById('abrpTokenField');
        const token = field ? field.value.trim() : '';
        if (!token) {
            this.setTokenStatus(BYD.i18n.t('abrp.err_enter_token'), 'error');
            return;
        }

        try {
            const data = await this._postConfig({ token: token, enabled: true });
            if (data && data.success !== false) {
                this.setTokenStatus(BYD.i18n.t('abrp.token_saved'), 'success');
                if (field) field.value = '';
                await this._reloadConfigWhenIdle();
            } else {
                this.setTokenStatus(
                    (data && data.error) || BYD.i18n.t('abrp.token_save_failed'),
                    'error'
                );
            }
        } catch (e) {
            this.setTokenStatus(BYD.i18n.t('abrp.token_network_save'), 'error');
        }
    },

    async deleteToken() {
        this._configWriteVersion++;
        this._configWritesPending++;
        let data = null;
        try {
            data = await this._enqueueWrite(async () => {
                const resp = await fetch('/api/abrp/token', { method: 'DELETE' });
                const result = await resp.json();
                if (!resp.ok || !result || result.success !== true) {
                    throw new Error(result && result.error ? result.error : 'ABRP token delete rejected');
                }
                return result;
            });
        } catch (e) {
            this.setTokenStatus(BYD.i18n.t('abrp.token_network_delete'), 'error');
            return;
        } finally {
            this._configWritesPending--;
        }
        try {
            if (data.success) {
                this.setTokenStatus(BYD.i18n.t('abrp.token_deleted'), 'success');
                await this._reloadConfigWhenIdle();
            } else {
                this.setTokenStatus(data.error || BYD.i18n.t('abrp.token_delete_failed'), 'error');
            }
        } catch (e) {
            this.setTokenStatus(BYD.i18n.t('abrp.token_network_delete'), 'error');
        }
    },

    async toggleEnabled() {
        const toggle = document.getElementById('abrpEnabled');
        if (!toggle) return;
        const checked = toggle.checked;
        toggle.disabled = true;
        try {
            if (!await this._ensureHydrated()) {
                toggle.checked = this.enabled;
                this._toastSaveFailed();
                return;
            }
            const previous = this.enabled;
            const data = await this._postConfig({ enabled: checked });
            if (data) {
                this.enabled = checked;
                toggle.checked = checked;
            } else {
                toggle.checked = previous;
                this._toastSaveFailed();
            }
        } finally {
            toggle.disabled = false;
        }
    },

    showTokenInput() {
        document.getElementById('tokenInput').style.display = 'block';
        document.getElementById('tokenDisplay').style.display = 'none';
        const field = document.getElementById('abrpTokenField');
        if (field) field.focus();
    },

    startAutoRefresh() {
        this.refreshInterval = setInterval(() => this.loadStatus(), 5000);
    },

    updateTelemetryTable(t) {
        const fields = {
            tlm_utc:         t.utc != null ? new Date(t.utc * 1000).toLocaleTimeString(BYD.i18n.getLang()) : '--',
            tlm_soc:         t.soc != null ? t.soc.toFixed(1) + '%' : '--%',
            tlm_power:       t.power != null ? t.power.toFixed(1) + ' kW' : '-- kW',
            tlm_speed:       t.speed != null ? BYD.units.speed(t.speed) : '-- ' + BYD.units.speedLabel(),
            tlm_lat:         t.lat != null ? t.lat.toFixed(6) : '--',
            tlm_lon:         t.lon != null ? t.lon.toFixed(6) : '--',
            tlm_is_charging: t.is_charging != null ? (t.is_charging ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')) : '--',
            tlm_is_dcfc:     t.is_dcfc != null ? (t.is_dcfc ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')) : '--',
            tlm_is_parked:   t.is_parked != null ? (t.is_parked ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')) : '--',
            tlm_elevation:   t.elevation != null ? t.elevation.toFixed(1) + ' m' : '-- m',
            tlm_heading:     t.heading != null ? t.heading.toFixed(1) + '°' : '--°',
            tlm_ext_temp:    t.ext_temp != null ? t.ext_temp.toFixed(1) + ' °C' : '-- °C',
            tlm_batt_temp:   t.batt_temp != null ? t.batt_temp.toFixed(1) + ' °C' : '-- °C',
            tlm_odometer:    t.odometer != null ? BYD.units.dist(t.odometer, 1) : '-- ' + BYD.units.distLabel(),
            tlm_soh:         t.soh != null ? t.soh.toFixed(1) + '%' : '--%',
            tlm_capacity:    t.capacity != null ? t.capacity.toFixed(2) + ' kWh' : '-- kWh'
        };

        for (const [id, value] of Object.entries(fields)) {
            const el = document.getElementById(id);
            if (el) el.textContent = value;
        }

        // Update vehicle card
        this.updateVehicleCard(t);
    },

    updateVehicleCard(t) {
        const setEl = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };

        // SOC + battery bar
        if (t.soc != null) {
            setEl('vehicleSoc', t.soc.toFixed(1) + '%');
            const bar = document.getElementById('vehicleBatteryBar');
            if (bar) bar.style.width = Math.min(100, Math.max(0, t.soc)) + '%';
        }

        // Power
        setEl('vehiclePower', t.power != null ? t.power.toFixed(1) + ' kW' : '-- kW');

        // Speed
        setEl('vehicleSpeed', t.speed != null ? BYD.units.speed(t.speed) : '-- ' + BYD.units.speedLabel());

        // Ext temp
        setEl('vehicleTemp', t.ext_temp != null ? t.ext_temp.toFixed(1) + ' °C' : '-- °C');

        // Battery temp
        setEl('vehicleBattTemp', t.batt_temp != null ? t.batt_temp.toFixed(1) + ' °C' : '-- °C');

        // Odometer
        setEl('vehicleOdometer', t.odometer != null ? BYD.units.dist(t.odometer) : '-- ' + BYD.units.distLabel());

        // SOH
        setEl('vehicleSoh', t.soh != null ? BYD.i18n.t('abrp.soh_value', {value: t.soh.toFixed(1)}) : BYD.i18n.t('abrp.soh_unknown'));

        // Vehicle status badge
        const statusEl = document.getElementById('vehicleStatus');
        if (statusEl) {
            if (t.is_charging) {
                statusEl.textContent = '⚡ ' + BYD.i18n.t('abrp.charging');
                statusEl.style.color = 'var(--brand-primary)';
            } else if (t.is_parked) {
                statusEl.textContent = BYD.i18n.t('abrp.parked');
                statusEl.style.color = 'var(--text-secondary)';
            } else {
                statusEl.textContent = BYD.i18n.t('abrp.driving');
                statusEl.style.color = '#22c55e';
            }
        }

        // Charging indicator
        const chargingEl = document.getElementById('vehicleCharging');
        if (chargingEl) {
            chargingEl.style.display = t.is_charging ? 'inline' : 'none';
            if (t.is_dcfc) chargingEl.textContent = '⚡ ' + BYD.i18n.t('abrp.dc_fast_charging');
            else if (t.is_charging) chargingEl.textContent = '⚡ ' + BYD.i18n.t('abrp.charging');
        }
    },

    setTokenStatus(message, type) {
        const el = document.getElementById('tokenStatus');
        if (el) {
            el.textContent = message;
            el.style.color = type === 'error' ? 'var(--danger)' : 'var(--success)';
        }
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
        }
    }
};
