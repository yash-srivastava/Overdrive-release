/**
 * BYD Champ - ABRP Telemetry Module
 * Manages ABRP token configuration, service control, and live telemetry display
 */

const ABRP = {
    refreshInterval: null,

    init() {
        this.loadConfig();
        this.loadStatus();
        this.startAutoRefresh();
    },

    async loadConfig() {
        try {
            const resp = await fetch('/api/abrp/config');
            const data = await resp.json();
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
            }
        } catch (e) {
            console.warn('[ABRP] Failed to load config:', e);
        }
    },

    async loadStatus() {
        try {
            const resp = await fetch('/api/abrp/status');
            const data = await resp.json();
            if (data.success && data.status) {
                const s = data.status;

                // Connection status
                const statusEl = document.getElementById('connectionStatus');
                if (statusEl) {
                    statusEl.textContent = s.running ? '🟢 Connected' : '🔴 Disconnected';
                }

                // Last upload
                const lastEl = document.getElementById('lastUpload');
                if (lastEl) {
                    if (s.lastUploadTime && s.lastUploadTime > 0) {
                        lastEl.textContent = new Date(s.lastUploadTime).toLocaleTimeString();
                    } else {
                        lastEl.textContent = 'Never';
                    }
                }

                // Upload counts
                const countEl = document.getElementById('uploadCount');
                if (countEl) {
                    countEl.textContent = (s.totalUploads || 0) + ' / ' + (s.failedUploads || 0) + ' failed';
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
            this.setTokenStatus('Please enter a token', 'error');
            return;
        }

        try {
            const resp = await fetch('/api/abrp/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: token, enabled: true })
            });
            const data = await resp.json();
            if (data.success) {
                this.setTokenStatus('Token saved successfully', 'success');
                if (field) field.value = '';
                this.loadConfig();
            } else {
                this.setTokenStatus(data.error || 'Failed to save token', 'error');
            }
        } catch (e) {
            this.setTokenStatus('Network error saving token', 'error');
        }
    },

    async deleteToken() {
        try {
            const resp = await fetch('/api/abrp/token', { method: 'DELETE' });
            const data = await resp.json();
            if (data.success) {
                this.setTokenStatus('Token deleted', 'success');
                this.loadConfig();
            } else {
                this.setTokenStatus(data.error || 'Failed to delete token', 'error');
            }
        } catch (e) {
            this.setTokenStatus('Network error deleting token', 'error');
        }
    },

    async toggleEnabled() {
        const checked = document.getElementById('abrpEnabled').checked;
        try {
            await fetch('/api/abrp/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: checked })
            });
        } catch (e) {
            console.warn('[ABRP] Failed to toggle enabled:', e);
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
            tlm_utc:         t.utc != null ? new Date(t.utc * 1000).toLocaleTimeString() : '--',
            tlm_soc:         t.soc != null ? t.soc.toFixed(1) + '%' : '--%',
            tlm_power:       t.power != null ? t.power.toFixed(1) + ' kW' : '-- kW',
            tlm_speed:       t.speed != null ? t.speed.toFixed(1) + ' km/h' : '-- km/h',
            tlm_lat:         t.lat != null ? t.lat.toFixed(6) : '--',
            tlm_lon:         t.lon != null ? t.lon.toFixed(6) : '--',
            tlm_is_charging: t.is_charging != null ? (t.is_charging ? 'Yes' : 'No') : '--',
            tlm_is_dcfc:     t.is_dcfc != null ? (t.is_dcfc ? 'Yes' : 'No') : '--',
            tlm_is_parked:   t.is_parked != null ? (t.is_parked ? 'Yes' : 'No') : '--',
            tlm_elevation:   t.elevation != null ? t.elevation.toFixed(1) + ' m' : '-- m',
            tlm_heading:     t.heading != null ? t.heading.toFixed(1) + '°' : '--°',
            tlm_ext_temp:    t.ext_temp != null ? t.ext_temp.toFixed(1) + ' °C' : '-- °C',
            tlm_batt_temp:   t.batt_temp != null ? t.batt_temp.toFixed(1) + ' °C' : '-- °C',
            tlm_odometer:    t.odometer != null ? t.odometer.toFixed(1) + ' km' : '-- km',
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
        setEl('vehicleSpeed', t.speed != null ? t.speed.toFixed(1) + ' km/h' : '-- km/h');

        // Ext temp
        setEl('vehicleTemp', t.ext_temp != null ? t.ext_temp.toFixed(1) + ' °C' : '-- °C');

        // Battery temp
        setEl('vehicleBattTemp', t.batt_temp != null ? t.batt_temp.toFixed(1) + ' °C' : '-- °C');

        // Odometer
        setEl('vehicleOdometer', t.odometer != null ? t.odometer.toFixed(0) + ' km' : '-- km');

        // SOH
        setEl('vehicleSoh', t.soh != null ? 'SOH: ' + t.soh.toFixed(1) + '%' : 'SOH: --%');

        // Vehicle status badge
        const statusEl = document.getElementById('vehicleStatus');
        if (statusEl) {
            if (t.is_charging) {
                statusEl.textContent = '⚡ Charging';
                statusEl.style.color = 'var(--brand-primary)';
            } else if (t.is_parked) {
                statusEl.textContent = 'Parked';
                statusEl.style.color = 'var(--text-secondary)';
            } else {
                statusEl.textContent = 'Driving';
                statusEl.style.color = '#22c55e';
            }
        }

        // Charging indicator
        const chargingEl = document.getElementById('vehicleCharging');
        if (chargingEl) {
            chargingEl.style.display = t.is_charging ? 'inline' : 'none';
            if (t.is_dcfc) chargingEl.textContent = '⚡ DC Fast Charging';
            else if (t.is_charging) chargingEl.textContent = '⚡ Charging';
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
