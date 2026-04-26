/**
 * BYD Champ - Core Module
 * Shared utilities, status polling, and toast notifications
 */

window.BYD = window.BYD || {};

BYD.core = {
    deviceId: null,
    pollInterval: null,
    lastStatus: null,

    /**
     * Initialize core module
     */
    init() {
        this.startStatusPolling();
        this.startClock();
        console.log('[Core] Initialized');
    },

    /**
     * Start clock update (if element exists)
     */
    startClock() {
        const update = () => {
            const el = document.getElementById('currentTime');
            if (el) {
                el.textContent = new Date().toLocaleTimeString('en-US', { 
                    hour: '2-digit', 
                    minute: '2-digit', 
                    hour12: false 
                });
            }
        };
        update();
        setInterval(update, 1000);
    },

    /**
     * Start status polling
     */
    startStatusPolling() {
        this.refreshStatus();
        this.pollInterval = setInterval(() => this.refreshStatus(), 5000);
    },

    /**
     * Refresh status from server (consolidated - includes GPS)
     */
    async refreshStatus() {
        try {
            const res = await fetch('/status');
            const status = await res.json();
            this.lastStatus = status;

            // Device ID
            if (status.deviceId) {
                this.deviceId = status.deviceId;
                const el = document.getElementById('deviceId');
                if (el) el.textContent = status.deviceId;
            }

            // 12V Battery
            if (status.battery) {
                const el = document.getElementById('batteryValue');
                if (el) el.textContent = (status.battery.voltage || 0).toFixed(1) + 'V';
            }

            // ACC status
            const accEl = document.getElementById('accValue');
            if (accEl) {
                accEl.textContent = status.acc ? 'ON' : 'OFF';
                accEl.className = 'status-value ' + (status.acc ? 'on' : 'off');
            }

            // Surveillance status
            const survEl = document.getElementById('survStatus');
            if (survEl) {
                if (status.safeZoneSuppressed || status.inSafeZone) {
                    survEl.textContent = '🏠 Safe';
                    survEl.className = 'status-value safe';
                } else {
                    const active = status.gpuSurveillance || false;
                    survEl.textContent = active ? 'ON' : 'OFF';
                    survEl.className = 'status-value ' + (active ? 'on' : 'off');
                }
            }

            // Network status (WiFi SSID + IP or Mobile Data)
            this.updateNetworkStatus(status);

            // Connection dot
            const connDot = document.getElementById('connDot');
            if (connDot) {
                connDot.classList.add('connected');
            }

            // EV Battery SOC
            this.updateEvStatus(status);

            // GPS data is now in status.gps - notify map module if exists
            if (status.gps && BYD.map && BYD.map.updateFromStatus) {
                BYD.map.updateFromStatus(status.gps);
            }

            // Notify surveillance module if exists
            if (BYD.surveillance && BYD.surveillance.updateFromStatus) {
                BYD.surveillance.updateFromStatus(status);
            }

            return status;
        } catch (e) {
            console.error('[Core] Status refresh error:', e);
            // Remove connected indicator on error
            const connDot = document.getElementById('connDot');
            if (connDot) connDot.classList.remove('connected');
            return null;
        }
    },

    /**
     * Update EV battery and charging status - White rims with flow animation
     */
    updateEvStatus(status) {
        const evCard = document.getElementById('evCard');
        if (!evCard) return;

        // Get SOC percentage from status.soc.percent
        let soc = null;
        if (status.soc && status.soc.percent !== undefined) {
            soc = status.soc.percent;
        }

        // Update elements
        const evPercentValue = document.getElementById('evPercentValue');
        const evBatteryFill = document.getElementById('evBatteryFill');
        const evChargeFlow = document.getElementById('evChargeFlow');
        const evRange = document.getElementById('evRange');

        if (soc !== null) {
            const socRounded = Math.round(soc);
            
            // Update percentage text
            if (evPercentValue) {
                evPercentValue.textContent = `${socRounded}%`;
            }

            // Max Width = 120
            const maxBarWidth = 120;
            const currentWidth = maxBarWidth * (soc / 100);
            
            // Update BOTH the main bar and the flow overlay
            if (evBatteryFill) evBatteryFill.setAttribute('width', currentWidth);
            if (evChargeFlow) evChargeFlow.setAttribute('width', currentWidth);

            // Color Logic (Teal -> Cyan -> Blue)
            const gradStart = document.querySelector('.grad-start');
            const gradMid = document.querySelector('.grad-mid');
            const gradEnd = document.querySelector('.grad-end');
            if (gradStart && gradEnd) {
                if (soc <= 20) {
                    gradStart.setAttribute('stop-color', '#ef4444');
                    if (gradMid) gradMid.setAttribute('stop-color', '#dc2626');
                    gradEnd.setAttribute('stop-color', '#991b1b');
                } else if (soc <= 40) {
                    gradStart.setAttribute('stop-color', '#fbbf24');
                    if (gradMid) gradMid.setAttribute('stop-color', '#f59e0b');
                    gradEnd.setAttribute('stop-color', '#d97706');
                } else {
                    // SOTA Liquid Energy
                    gradStart.setAttribute('stop-color', '#2dd4bf');
                    if (gradMid) gradMid.setAttribute('stop-color', '#06b6d4');
                    gradEnd.setAttribute('stop-color', '#3b82f6');
                }
            }
        }

        // Update range from actual API data (electric range only)
        if (evRange) {
            if (status.range && status.range.elecRangeKm !== undefined) {
                // Use electric range from BYD API
                const rangeKm = status.range.elecRangeKm;
                evRange.textContent = rangeKm + ' km';
                
                // Add warning styling if range is low
                if (status.range.isCritical) {
                    evRange.classList.add('critical');
                    evRange.classList.remove('low');
                } else if (status.range.isLow) {
                    evRange.classList.add('low');
                    evRange.classList.remove('critical');
                } else {
                    evRange.classList.remove('low', 'critical');
                }
            } else if (soc !== null) {
                // Fallback: estimate range (~4km per %)
                const estimatedRange = Math.round(soc * 4);
                evRange.textContent = '~' + estimatedRange + ' km';
                evRange.classList.remove('low', 'critical');
            }
        }

        // Charging state
        const evPower = document.getElementById('evPower');
        const pattern = document.getElementById('chargeFlowPattern');

        let isCharging = false;
        let powerKW = 0;

        if (status.charging) {
            var stateName = status.charging.stateName || '';
            powerKW = status.charging.chargingPowerKW || 0;
            var isEstimated = status.charging.isEstimated || false;
            
            // Determine if actively charging
            var chargingStates = ['Charging', 'DC Charging', 'AC Charging', 'Fast Charging'];
            isCharging = chargingStates.some(function(s) { return stateName.toLowerCase().indexOf(s.toLowerCase()) >= 0; }) || powerKW > 0;
        }

        // Update power display
        if (evPower) {
            if (isCharging) {
                if (powerKW > 0) {
                    var prefix = isEstimated ? '~' : '';
                    evPower.textContent = prefix + powerKW.toFixed(1) + ' kW';
                } else {
                    evPower.textContent = '0.0 kW';
                }
            } else {
                evPower.textContent = powerKW > 0 ? powerKW.toFixed(1) + ' kW' : '-- kW';
            }
        }

        // Charging Animation Logic
        if (isCharging) {
            evCard.classList.add('charging');
            // SOTA: Animate the pattern x position using requestAnimationFrame
            // This creates the "Moving Belt" effect left-to-right
            if (!evCard.dataset.animating) {
                evCard.dataset.animating = "true";
                let offset = 0;
                const animateFlow = () => {
                    if (!evCard.classList.contains('charging')) {
                        evCard.dataset.animating = "";
                        return;
                    }
                    offset -= 1; // Move left (creates rightward visual flow for stripes)
                    if (pattern) pattern.setAttribute('x', offset);
                    requestAnimationFrame(animateFlow);
                };
                requestAnimationFrame(animateFlow);
            }
        } else {
            evCard.classList.remove('charging');
        }

        // SOH display
        const evSohEl = document.getElementById('evSohValue');
        const evSohRow = document.getElementById('evSohRow');
        if (evSohEl && status.soh && status.soh.percent > 0) {
            evSohEl.textContent = status.soh.percent.toFixed(1) + '%';
            evSohEl.style.color = status.soh.percent >= 90 ? '#22c55e' : status.soh.percent >= 80 ? '#00D4AA' : status.soh.percent >= 70 ? '#fbbf24' : '#ef4444';
            if (evSohRow) evSohRow.style.display = '';
        }

        // Personalized range from trip analytics
        this.updatePersonalizedRange();
    },

    /**
     * Update network status indicator in sidebar.
     * Shows WiFi SSID + IP, or "Mobile Data", or "No Network".
     */
    updateNetworkStatus(status) {
        const netEl = document.getElementById('networkValue');
        const netIcon = document.getElementById('networkIcon');
        if (!netEl) return;

        const net = status.network;
        if (!net) {
            netEl.textContent = '--';
            netEl.className = 'status-value';
            if (netIcon) netIcon.innerHTML = this._wifiSvg();
            return;
        }

        if (net.type === 'wifi') {
            const ssid = net.ssid || 'WiFi';
            const ip = net.ip || '';
            // Show SSID on first line, IP smaller below
            netEl.innerHTML = '<span class="net-ssid">' + this._esc(ssid) + '</span>' +
                (ip ? '<span class="net-ip">' + this._esc(ip) + '</span>' : '');
            netEl.className = 'status-value on net-info';
            if (netIcon) netIcon.innerHTML = this._wifiSvg();
        } else if (net.type === 'cellular') {
            const ip = net.ip || '';
            netEl.innerHTML = '<span class="net-ssid">Mobile Data</span>' +
                (ip ? '<span class="net-ip">' + this._esc(ip) + '</span>' : '');
            netEl.className = 'status-value on net-info';
            if (netIcon) netIcon.innerHTML = this._cellSvg();
        } else {
            netEl.textContent = 'No Network';
            netEl.className = 'status-value off';
            if (netIcon) netIcon.innerHTML = this._wifiOffSvg();
        }
    },

    /** Escape HTML */
    _esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; },

    /** WiFi SVG icon */
    _wifiSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>';
    },

    /** Cellular SVG icon */
    _cellSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="17" width="4" height="5"/><rect x="7" y="12" width="4" height="10"/><rect x="12" y="7" width="4" height="15"/><rect x="17" y="2" width="4" height="20"/></svg>';
    },

    /** WiFi-off SVG icon */
    _wifiOffSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"/><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/><path d="M10.71 5.05A16 16 0 0 1 22.56 9"/><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>';
    },

    /**
     * Fetch and display personalized range estimate from trip analytics
     */
    async updatePersonalizedRange() {
        const pRow = document.getElementById('evPersonalizedRow');
        const pVal = document.getElementById('evPersonalizedRange');
        if (!pRow || !pVal) return;

        // Only fetch once per session, cache the result
        if (this._personalizedRangeFetched) {
            if (this._personalizedRangeKm > 0) {
                pRow.style.display = 'flex';
                pVal.textContent = this._personalizedRangeKm + ' km';
            }
            return;
        }

        try {
            const resp = await fetch('/api/trips/range');
            const data = await resp.json();
            this._personalizedRangeFetched = true;
            if (data.success && data.range) {
                const predicted = Math.round(data.range.predictedRangeKm || data.range.predicted_range_km || 0);
                if (predicted > 0) {
                    this._personalizedRangeKm = predicted;
                    pRow.style.display = 'flex';
                    pVal.textContent = predicted + ' km';
                }
            }
        } catch (e) {
            this._personalizedRangeFetched = true;
        }
    },

    /**
     * Show toast notification
     */
    toast(message, type = 'info', duration = 3000) {
        const container = document.getElementById('toastContainer');
        if (!container) return;

        const toast = document.createElement('div');
        toast.className = 'toast ' + type;
        toast.textContent = message;
        container.appendChild(toast);

        setTimeout(() => {
            toast.style.animation = 'slideIn 0.4s ease reverse';
            setTimeout(() => toast.remove(), 400);
        }, duration);
    }
};

// Expose toast globally for convenience
BYD.utils = BYD.utils || {};
BYD.utils.toast = (msg, type) => BYD.core.toast(msg, type);
