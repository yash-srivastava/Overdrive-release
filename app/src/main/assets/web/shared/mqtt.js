/**
 * OverDrive - MQTT Connections Module
 * Manages multiple MQTT broker connections, configuration, and live status display.
 */

const MQTT = {
    connections: [],
    maxConnections: 5,
    refreshInterval: null,
    expandedId: null,
    editingId: null,

    init() {
        // Render once up front so the Connections tab shows the empty state +
        // CTA on first paint, even before the API call lands. Otherwise the
        // user sees a blank panel until /api/mqtt/connections resolves.
        this.render();
        this.loadConnections();
        this.loadTelemetry();
        this.startAutoRefresh();
    },

    // ==================== DATA LOADING ====================

    async loadConnections() {
        try {
            const resp = await fetch('/api/mqtt/connections');
            const data = await resp.json();
            if (data && data.success) {
                this.connections = data.connections || [];
                this.maxConnections = data.maxConnections || 5;
            } else {
                this.connections = [];
            }
        } catch (e) {
            console.warn('[MQTT] Failed to load connections:', e);
            this.connections = [];
        }
        // Always render — even on failure — so the empty state appears
        // instead of a blank panel that looks broken.
        this.render();
    },

    async loadStatus() {
        try {
            const resp = await fetch('/api/mqtt/status');
            const data = await resp.json();
            if (data && data.success && data.connections) {
                this.connections = data.connections;
                this.render();
            }
        } catch (e) {
            console.warn('[MQTT] Failed to load status:', e);
        }
    },

    async loadTelemetry() {
        try {
            const resp = await fetch('/api/mqtt/telemetry');
            const data = await resp.json();
            if (data.success && data.telemetry) {
                this.updateTelemetryTable(data.telemetry);
            }
        } catch (e) {
            console.warn('[MQTT] Failed to load telemetry:', e);
        }
    },

    startAutoRefresh() {
        this.refreshInterval = setInterval(() => {
            this.loadStatus();
            this.loadTelemetry();
        }, 5000);
    },

    // ==================== RENDERING ====================

    render() {
        const list = document.getElementById('connectionList');
        const empty = document.getElementById('emptyState');
        if (!list || !empty) return;

        if (this.connections.length === 0) {
            list.innerHTML = '';
            empty.style.display = 'block';
            return;
        }

        empty.style.display = 'none';
        list.innerHTML = this.connections.map(conn => this.renderConnection(conn)).join('');

        // Disable the empty-state CTA's "Add" button if the user already has
        // the max number of connections — they can still hit it from this
        // page only via the Add tab, where showAddForm() will toast instead.
        // (No persistent Add button anywhere else now.)
    },

    renderConnection(conn) {
        const s = conn.status || {};
        const isConnected = s.connected || false;
        const isRunning = s.running || false;
        const isExpanded = this.expandedId === conn.id;

        let dotClass = 'stopped';
        let statusText = BYD.i18n.t('mqtt.status_stopped');
        if (conn.enabled && isConnected) {
            dotClass = 'connected';
            statusText = BYD.i18n.t('mqtt.status_connected');
        } else if (conn.enabled && isRunning && !isConnected) {
            dotClass = 'reconnecting';
            statusText = BYD.i18n.t('mqtt.status_reconnecting');
        } else if (conn.enabled && !isRunning) {
            dotClass = 'disconnected';
            statusText = BYD.i18n.t('mqtt.status_disconnected');
        }

        const totalPub = s.totalPublishes || 0;
        const failedPub = s.failedPublishes || 0;
        const lastPub = s.lastPublishTime && s.lastPublishTime > 0
            ? new Date(s.lastPublishTime).toLocaleTimeString(BYD.i18n.getLang()) : BYD.i18n.t('mqtt.never');
        const lastErr = s.lastError || '';

        const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
        const deleteIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';
        const enabledHtml = conn.enabled
            ? '<span class="status-dot connected"></span><span>' + BYD.i18n.t('common.enabled') + '</span>'
            : '<span class="status-dot off"></span><span>' + BYD.i18n.t('common.disabled') + '</span>';
        return `
        <div class="card conn-card" style="${!conn.enabled ? 'opacity:0.6;' : ''}">
            <div class="conn-header" onclick="MQTT.toggleExpand('${conn.id}')">
                <span class="conn-dot ${dotClass}"></span>
                <div class="conn-info">
                    <div class="conn-name">${this.esc(conn.name || BYD.i18n.t('mqtt.unnamed'))}</div>
                    <div class="conn-broker">${this.esc(conn.brokerUrl || '')}:${conn.port} → ${this.esc(conn.topic || '')}</div>
                </div>
                <div class="conn-actions" onclick="event.stopPropagation()">
                    <button class="icon-btn" onclick="MQTT.editConnection('${conn.id}')" title="${BYD.i18n.t('common.edit')}" aria-label="${BYD.i18n.t('common.edit')}">${editIcon}</button>
                    <button class="icon-btn danger" onclick="MQTT.deleteConnection('${conn.id}')" title="${BYD.i18n.t('common.delete')}" aria-label="${BYD.i18n.t('common.delete')}">${deleteIcon}</button>
                </div>
            </div>
            <div style="display:flex;align-items:center;justify-content:space-between;padding:0 16px 12px;gap:8px;" onclick="event.stopPropagation()">
                <div style="display:inline-flex;align-items:center;gap:8px;font-size:13px;color:var(--text-secondary);">${enabledHtml}</div>
                <label class="toggle-switch">
                    <input type="checkbox" ${conn.enabled ? 'checked' : ''} onchange="MQTT.toggleEnabled('${conn.id}', this.checked)">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div class="conn-detail ${isExpanded ? 'open' : ''}" id="detail-${conn.id}">
                <div class="conn-stats">
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_status')}</div><div class="value">${statusText}</div></div>
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_last_publish')}</div><div class="value">${lastPub}</div></div>
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_published')}</div><div class="value">${totalPub}</div></div>
                    <div class="conn-stat"><div class="label">${BYD.i18n.t('mqtt.label_failed')}</div><div class="value" style="${failedPub > 0 ? 'color:var(--danger)' : ''}">${failedPub}</div></div>
                </div>
                ${lastErr ? `<div style="font-size:12px;color:var(--danger);padding:8px 0;">${this.esc(lastErr)}</div>` : ''}
                <div style="font-size:12px;color:var(--text-muted);display:flex;gap:16px;flex-wrap:wrap;">
                    <span>${BYD.i18n.t('mqtt.label_qos')}: ${conn.qos}</span>
                    <span>${BYD.i18n.t('mqtt.label_interval')}: ${this.fmtInterval(conn.minIntervalSeconds || conn.publishIntervalSeconds || 5)}–${this.fmtInterval(conn.maxIntervalSeconds || 300)}${conn.changeOnly !== false ? ' (' + BYD.i18n.t('mqtt.change_short') + ')' : ''}</span>
                    ${(conn.parkedIntervalSeconds > 0 || conn.chargingIntervalSeconds > 0) ? '<span>' + BYD.i18n.t('mqtt.label_state_interval') + ': ' + (conn.parkedIntervalSeconds > 0 ? BYD.i18n.t('mqtt.state_parked_short') + ' ' + this.fmtInterval(conn.parkedIntervalSeconds) : '') + (conn.parkedIntervalSeconds > 0 && conn.chargingIntervalSeconds > 0 ? ' · ' : '') + (conn.chargingIntervalSeconds > 0 ? BYD.i18n.t('mqtt.state_charging_short') + ' ' + this.fmtInterval(conn.chargingIntervalSeconds) : '') + '</span>' : ''}
                    <span>${BYD.i18n.t('mqtt.label_retain')}: ${conn.retainMessages ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')}</span>
                    ${conn.homeAssistantDiscovery ? '<span>' + BYD.i18n.t('mqtt.ha_short') + (conn.allowControl ? ' + ' + BYD.i18n.t('mqtt.control_short') : '') + '</span>' : ''}
                    <span>${BYD.i18n.t('mqtt.label_proxy')}: ${s.proxyActive ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no')}</span>
                </div>
            </div>
        </div>`;
    },

    toggleExpand(id) {
        this.expandedId = this.expandedId === id ? null : id;
        this.render();
    },

    // ==================== FORM ====================

    // The form is rendered on its own tab now (data-tab="add"), so showing /
    // hiding maps onto a tab switch instead of an inline display toggle.
    _switchTab(id) {
        if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab(id);
    },

    showAddForm() {
        if (this.connections.length >= this.maxConnections) {
            this.toast(BYD.i18n.t('mqtt.max_reached', {n: this.maxConnections}), 'error');
            return;
        }
        this.editingId = null;
        var titleEl = document.getElementById('formTitle');
        if (titleEl) titleEl.textContent = BYD.i18n.t('mqtt.add_connection');
        document.getElementById('formId').value = '';
        document.getElementById('formName').value = '';
        document.getElementById('formBrokerUrl').value = '';
        document.getElementById('formPort').value = '1883';
        document.getElementById('formTopic').value = 'overdrive/vehicle/telemetry';
        document.getElementById('formUsername').value = '';
        document.getElementById('formPassword').value = '';
        document.getElementById('formClientId').value = '';
        document.getElementById('formQos').value = '0';
        document.getElementById('formMinInterval').value = '5';
        document.getElementById('formMaxInterval').value = '300';
        var pk0 = document.getElementById('formParkedInterval'); if (pk0) pk0.value = '0';
        var ch0 = document.getElementById('formChargingInterval'); if (ch0) ch0.value = '0';
        document.getElementById('formChangeOnly').checked = true;
        document.getElementById('formHaDiscovery').checked = false;
        document.getElementById('formDiscoveryPrefix').value = 'homeassistant';
        var hb0 = document.getElementById('formHeartbeatSendAll'); if (hb0) hb0.checked = false;
        var fs0 = document.getElementById('formFlushOnStateChange'); if (fs0) fs0.checked = true;
        document.getElementById('formAllowControl').checked = false;
        document.getElementById('formRetain').checked = false;
        document.getElementById('formEnabled').checked = true;
        this.onSlider();
        this.onHaToggle();
        this._switchTab('add');
        var nameEl = document.getElementById('formName');
        if (nameEl) nameEl.focus();
    },

    editConnection(id) {
        const conn = this.connections.find(c => c.id === id);
        if (!conn) return;

        this.editingId = id;
        var titleEl = document.getElementById('formTitle');
        if (titleEl) titleEl.textContent = BYD.i18n.t('mqtt.edit_connection');
        document.getElementById('formId').value = conn.id;
        document.getElementById('formName').value = conn.name || '';
        document.getElementById('formBrokerUrl').value = conn.brokerUrl || '';
        document.getElementById('formPort').value = conn.port || 1883;
        document.getElementById('formTopic').value = conn.topic || '';
        document.getElementById('formUsername').value = conn.username || '';
        document.getElementById('formPassword').value = '';  // Don't prefill password
        document.getElementById('formClientId').value = conn.clientId || '';
        document.getElementById('formQos').value = conn.qos || 0;
        document.getElementById('formMinInterval').value = conn.minIntervalSeconds || conn.publishIntervalSeconds || 5;
        document.getElementById('formMaxInterval').value = conn.maxIntervalSeconds || 300;
        var pkE = document.getElementById('formParkedInterval'); if (pkE) pkE.value = conn.parkedIntervalSeconds || 0;
        var chE = document.getElementById('formChargingInterval'); if (chE) chE.value = conn.chargingIntervalSeconds || 0;
        document.getElementById('formChangeOnly').checked = conn.changeOnly !== false;
        document.getElementById('formHaDiscovery').checked = conn.homeAssistantDiscovery || false;
        document.getElementById('formDiscoveryPrefix').value = conn.discoveryPrefix || 'homeassistant';
        var hbE = document.getElementById('formHeartbeatSendAll'); if (hbE) hbE.checked = conn.heartbeatSendAll || false;
        var fsE = document.getElementById('formFlushOnStateChange'); if (fsE) fsE.checked = conn.flushOnStateChange !== false;
        document.getElementById('formAllowControl').checked = conn.allowControl || false;
        document.getElementById('formRetain').checked = conn.retainMessages || false;
        document.getElementById('formEnabled').checked = conn.enabled || false;
        this.onSlider();
        this.onHaToggle();
        this._switchTab('add');
        var nameEl = document.getElementById('formName');
        if (nameEl) nameEl.focus();
    },

    hideForm() {
        // Cancel returns to the Connections list. editingId is reset so a
        // subsequent tap on the empty-state CTA opens a fresh form.
        this.editingId = null;
        this._switchTab('connections');
    },

    // Human-friendly interval label: 45 -> "45s", 300 -> "5m".
    fmtInterval(sec) {
        sec = parseInt(sec) || 0;
        if (sec < 60) return sec + BYD.units.secondSuffix(true);
        var s = BYD.units.minuteSuffix(true, true);
        return (sec % 60 === 0) ? (sec / 60) + s : (sec / 60).toFixed(1) + s;
    },

    // Live-update the min/max slider value labels.
    onSlider() {
        var mn = document.getElementById('formMinInterval');
        var mx = document.getElementById('formMaxInterval');
        var mnl = document.getElementById('formMinLabel');
        var mxl = document.getElementById('formMaxLabel');
        if (mn && mnl) mnl.textContent = this.fmtInterval(mn.value);
        if (mx && mxl) mxl.textContent = this.fmtInterval(mx.value);
        // Per-state overrides show "Off" at 0 (inherit the max interval) rather than "0s".
        var pk = document.getElementById('formParkedInterval');
        var pkl = document.getElementById('formParkedLabel');
        var ch = document.getElementById('formChargingInterval');
        var chl = document.getElementById('formChargingLabel');
        var offLabel = BYD.i18n.t('mqtt.interval_off');
        if (pk && pkl) pkl.textContent = (parseInt(pk.value) || 0) === 0 ? offLabel : this.fmtInterval(pk.value);
        if (ch && chl) chl.textContent = (parseInt(ch.value) || 0) === 0 ? offLabel : this.fmtInterval(ch.value);
    },

    // Show the discovery-prefix field and the vehicle-control toggle only when
    // HA discovery is enabled (control requires the discovery machinery).
    onHaToggle() {
        var ha = document.getElementById('formHaDiscovery');
        var on = ha && ha.checked;
        var prefixRow = document.getElementById('formDiscoveryPrefixRow');
        var controlRow = document.getElementById('formAllowControlRow');
        var hbRow = document.getElementById('formHeartbeatSendAllRow');
        var fsRow = document.getElementById('formFlushOnStateChangeRow');
        if (prefixRow) prefixRow.style.display = on ? '' : 'none';
        if (controlRow) controlRow.style.display = on ? 'flex' : 'none';
        if (hbRow) hbRow.style.display = on ? 'flex' : 'none';
        if (fsRow) fsRow.style.display = on ? 'flex' : 'none';
    },

    async saveForm() {
        const data = {
            name: document.getElementById('formName').value.trim(),
            brokerUrl: document.getElementById('formBrokerUrl').value.trim(),
            port: parseInt(document.getElementById('formPort').value) || 1883,
            topic: document.getElementById('formTopic').value.trim(),
            username: document.getElementById('formUsername').value.trim(),
            password: document.getElementById('formPassword').value,
            clientId: document.getElementById('formClientId').value.trim(),
            qos: parseInt(document.getElementById('formQos').value) || 0,
            minIntervalSeconds: parseInt(document.getElementById('formMinInterval').value) || 5,
            maxIntervalSeconds: parseInt(document.getElementById('formMaxInterval').value) || 300,
            parkedIntervalSeconds: (function(){ var e=document.getElementById('formParkedInterval'); return e ? (parseInt(e.value) || 0) : 0; })(),
            chargingIntervalSeconds: (function(){ var e=document.getElementById('formChargingInterval'); return e ? (parseInt(e.value) || 0) : 0; })(),
            changeOnly: document.getElementById('formChangeOnly').checked,
            homeAssistantDiscovery: document.getElementById('formHaDiscovery').checked,
            discoveryPrefix: (document.getElementById('formDiscoveryPrefix').value || 'homeassistant').trim(),
            heartbeatSendAll: (function(){ var e=document.getElementById('formHeartbeatSendAll'); return e ? e.checked : false; })(),
            flushOnStateChange: (function(){ var e=document.getElementById('formFlushOnStateChange'); return e ? e.checked : true; })(),
            allowControl: document.getElementById('formAllowControl').checked,
            retainMessages: document.getElementById('formRetain').checked,
            enabled: document.getElementById('formEnabled').checked
        };
        // Keep the window coherent: max must be >= min.
        if (data.maxIntervalSeconds < data.minIntervalSeconds) {
            data.maxIntervalSeconds = data.minIntervalSeconds;
        }

        // The edit form never prefills the stored password (see editConnection).
        // A blank field on edit therefore means "leave the password as-is", so
        // drop the key entirely rather than sending "" and wiping the saved
        // secret on the backend. On add, a blank password is sent through as an
        // empty string (broker with no auth), which is the correct behaviour.
        if (this.editingId && data.password === '') {
            delete data.password;
        }

        if (!data.name) { this.toast(BYD.i18n.t('mqtt.err_name_required'), 'error'); return; }
        if (!data.brokerUrl) { this.toast(BYD.i18n.t('mqtt.err_broker_required'), 'error'); return; }
        if (!data.topic) { this.toast(BYD.i18n.t('mqtt.err_topic_required'), 'error'); return; }

        try {
            let resp;
            if (this.editingId) {
                resp = await fetch('/api/mqtt/connections/' + this.editingId, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
            } else {
                resp = await fetch('/api/mqtt/connections', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
            }

            const result = await resp.json();
            if (result.success) {
                this.toast(this.editingId ? BYD.i18n.t('mqtt.toast_updated') : BYD.i18n.t('mqtt.toast_added'), 'success');
                this.editingId = null;
                this.loadConnections();
                // Return to the Connections tab so the user sees the new entry
                // immediately. The list re-renders via loadConnections().
                this._switchTab('connections');
            } else {
                this.toast(result.error || BYD.i18n.t('errors.save_failed'), 'error');
            }
        } catch (e) {
            this.toast(BYD.i18n.t('mqtt.network_error', {message: e.message}), 'error');
        }
    },

    // ==================== ACTIONS ====================

    async toggleEnabled(id, enabled) {
        try {
            await fetch('/api/mqtt/connections/' + id, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
            // Refresh after a short delay to show updated status
            setTimeout(() => this.loadStatus(), 1000);
        } catch (e) {
            this.toast(BYD.i18n.t('mqtt.toggle_failed'), 'error');
        }
    },

    async deleteConnection(id) {
        const conn = this.connections.find(c => c.id === id);
        const name = conn ? conn.name : id;
        if (!confirm(BYD.i18n.t('mqtt.confirm_delete', {name: name}))) return;

        try {
            const resp = await fetch('/api/mqtt/connections/' + id, { method: 'DELETE' });
            const result = await resp.json();
            if (result.success) {
                this.toast(BYD.i18n.t('mqtt.toast_deleted'), 'success');
                this.loadConnections();
            } else {
                this.toast(result.error || BYD.i18n.t('errors.delete_failed'), 'error');
            }
        } catch (e) {
            this.toast(BYD.i18n.t('mqtt.network_error', {message: e.message}), 'error');
        }
    },

    // ==================== TELEMETRY ====================

    // Per-key formatters for the live preview. Keys not listed fall through
    // to the generic formatter (numbers get .toFixed(2), arrays/objects get
    // JSON.stringify, booleans → yes/no, strings/ints unchanged).
    // Only entries actually emitted by MqttConnectionManager.collectTelemetry
    // need to be here; missing keys silently use the generic path.
    _tlmFormat(key, v) {
        if (v == null) return '--';
        const yesNo = (x) => x ? BYD.i18n.t('common.yes') : BYD.i18n.t('common.no');
        switch (key) {
            // Time
            case 'utc':
            case 'vd_timestamp':
                return new Date(v * 1000).toLocaleTimeString(BYD.i18n.getLang());

            // Booleans encoded as 0/1
            case 'is_charging': case 'is_dcfc': case 'is_parked':
            case 'charging_v2l': case 'drift_mode': case 'speed_limit_warning':
            case 'light_low_beam': case 'light_high_beam': case 'light_rear_fog':
            case 'light_front_fog': case 'light_hazard': case 'light_drl':
                return yesNo(v);

            // Percentages
            case 'soc': case 'soc_hev': case 'soh': case 'soh_oem':
            case 'fuel_pct': case 'charging_pct':
            case 'accel_pct': case 'brake_pct': case 'sunshade_pct':
                return (+v).toFixed(1) + '%';

            // Power / energy
            case 'power': case 'trip_kwh': case 'consumption_50km':
            case 'charging_capacity_kwh': case 'capacity':
                return (+v).toFixed(1) + (key === 'consumption_50km' ? ' kWh/100km' : ' kWh');

            // Voltages
            case 'volt_12v':
            case 'cell_v_max': case 'cell_v_min': case 'cell_v_delta':
                return (+v).toFixed(3) + ' V';
            case 'hv_pack_v':
                return (+v).toFixed(1) + ' V';

            // Temperatures
            case 'ext_temp': case 'batt_temp': case 'cabin_temp': case 'inside_temp':
            case 'cell_t_max': case 'cell_t_min': case 'cell_t_avg': case 'cell_t_delta':
            case 'coolant_temp': case 'bodywork_batt_temp':
            case 'tyre_t_fl': case 'tyre_t_fr': case 'tyre_t_rl': case 'tyre_t_rr':
                return (+v).toFixed(1) + ' °C';

            // Distance
            case 'odometer': case 'trip_km':
                return BYD.units.dist(+v, 1);
            case 'ev_range_km': case 'fuel_range_km': case 'bodywork_range_km':
            case 'ev_mileage_km':
                return (+v).toFixed(0) + ' km';

            // Speed
            case 'speed':
                return BYD.units.speed(+v);

            // Tyre pressure (kPa raw) — preview honours the display-unit
            // preference like dist/speed above. The published MQTT payload
            // stays kPa (Home Assistant converts via device_class).
            case 'tyre_p_fl': case 'tyre_p_fr': case 'tyre_p_rl': case 'tyre_p_rr':
                return BYD.units.pressure ? BYD.units.pressure(+v) : v + ' kPa';

            // Coordinates
            case 'lat': case 'lon':
                return (+v).toFixed(6);

            // Capacity / charge current
            case 'capacity_ah':
                return (+v).toFixed(1) + ' Ah';

            // Range / time
            case 'elevation':         return (+v).toFixed(1) + ' m';
            case 'heading':           return (+v).toFixed(0) + '°';
            case 'steering_deg':      return (+v).toFixed(1) + '°';
            case 'slope_deg':         return (+v).toFixed(1) + '°';
            case 'trip_hours':
            case 'driving_time_hours':
            case 'charging_eta_hours':
                return (+v).toFixed(2) + BYD.units.hourSuffix(false);
            case 'charging_eta_minutes':
                return v + BYD.units.minuteSuffix(false, false);
            case 'motor_front_rpm': case 'motor_rear_rpm': case 'engine_rpm':
                return v + ' rpm';
            case 'motor_front_torque':
                return (+v).toFixed(1) + ' Nm';
            case 'pm25_inside': case 'pm25_outside':
                return v + ' µg/m³';

            default: {
                // Generic: arrays/objects → JSON; numbers → 2dp; strings → unchanged
                if (Array.isArray(v)) return JSON.stringify(v);
                if (typeof v === 'object') return JSON.stringify(v);
                if (typeof v === 'number') {
                    return Number.isInteger(v) ? String(v) : v.toFixed(2);
                }
                return String(v);
            }
        }
    },

    // Stable order for known keys; everything else gets appended alphabetically
    // afterwards. Keeping the original 14 fields at the top so the table doesn't
    // visually reshuffle when the new BYD parity fields land.
    _tlmKeyOrder: [
        'utc', 'soc', 'power', 'speed', 'lat', 'lon',
        'is_charging', 'is_dcfc', 'is_parked',
        'elevation', 'heading', 'gear',
        'ext_temp', 'batt_temp', 'cabin_temp', 'inside_temp',
        'odometer', 'soh', 'soh_oem', 'capacity', 'capacity_ah',
        // Extended (existing)
        'ev_range_km', 'trip_km', 'trip_hours', 'trip_kwh',
        'consumption_50km', 'driving_time_hours',
        'charging_eta_hours', 'charging_eta_minutes', 'key_battery',
        // Identity
        'vin',
        // HV battery
        'hv_pack_v', 'cell_v_max', 'cell_v_min', 'cell_v_delta',
        'soc_hev',
        'cell_t_max', 'cell_t_min', 'cell_t_avg', 'cell_t_delta',
        'coolant_temp', 'bodywork_batt_temp',
        // 12V
        'volt_12v', 'volt_12v_level', 'batt_12v_level',
        // Drivetrain
        'motor_front_rpm', 'motor_rear_rpm', 'motor_front_torque',
        'engine_rpm', 'accel_pct', 'brake_pct',
        'steering_deg', 'slope_deg',
        // Energy
        'energy_mode', 'op_mode', 'total_elec_con', 'total_fuel_con',
        'fuel_range_km', 'fuel_pct', 'bodywork_range_km', 'ev_mileage_km',
        // Charging
        'charging_state', 'charger_state', 'charging_mode', 'charging_gun',
        'charging_type', 'charging_pct', 'charging_capacity_kwh', 'charging_v2l',
        'wireless_charging_left', 'wireless_charging_right', 'wireless_charging_status',
        // Tyres
        'tyre_p_fl', 'tyre_p_fr', 'tyre_p_rl', 'tyre_p_rr',
        'tyre_p_state_fl', 'tyre_p_state_fr', 'tyre_p_state_rl', 'tyre_p_state_rr',
        'tyre_leak_fl', 'tyre_leak_fr', 'tyre_leak_rl', 'tyre_leak_rr',
        'tyre_signal_fl', 'tyre_signal_fr', 'tyre_signal_rl', 'tyre_signal_rr',
        'tyre_t_fl', 'tyre_t_fr', 'tyre_t_rl', 'tyre_t_rr',
        'tyre_system_state', 'tyre_temp_state',
        // Body
        'door_lock', 'window_open',
        'light_left_turn', 'light_right_turn',
        'light_low_beam', 'light_high_beam', 'light_rear_fog',
        'light_front_fog', 'light_hazard', 'light_drl',
        // Climate
        'ac_on', 'ac_cycle', 'ac_wind', 'ac_fan', 'temp_unit',
        // Seats
        'seatbelt', 'seat_heat', 'seat_cool',
        // Bodywork
        'wiper_state', 'sunroof_state', 'sunroof_pos', 'sunshade_pct',
        'drift_mode',
        // Engine (PHEV)
        'engine_coolant_level', 'oil_level', 'engine_code',
        // Safety / radar
        'passenger_detection', 'emergency_alarm',
        'power_level', 'mcu_status', 'radar_distances',
        'speed_limit_warning',
        // Air
        'pm25_inside', 'pm25_outside',
        // Key
        'key_start_state', 'key_missing', 'key_bt_low_power',
        'key_power_low', 'key_detection_reminder', 'smart_key_warn',
        // Snapshot meta
        'vd_timestamp'
    ],

    updateTelemetryTable(t) {
        const tbody = document.getElementById('telemetryBody');
        if (!tbody) return;

        const seen = new Set();
        const ordered = [];
        for (const k of this._tlmKeyOrder) {
            if (k in t) { ordered.push(k); seen.add(k); }
        }
        // Append any unknown keys (e.g. fields added on the Java side without
        // updating _tlmKeyOrder) so nothing silently disappears.
        const extras = Object.keys(t).filter(k => !seen.has(k)).sort();
        for (const k of extras) ordered.push(k);

        if (ordered.length === 0) {
            tbody.innerHTML = '<tr><td colspan="2" style="text-align:center;color:var(--text-muted);padding:24px;">--</td></tr>';
            return;
        }

        // Build with textContent (no innerHTML key/value injection). Safer and
        // avoids re-parsing the whole tree if a string ever contains '<'.
        const rows = [];
        for (const k of ordered) {
            const tr = document.createElement('tr');
            const tdK = document.createElement('td');
            tdK.textContent = k;
            const tdV = document.createElement('td');
            tdV.textContent = this._tlmFormat(k, t[k]);
            tr.appendChild(tdK);
            tr.appendChild(tdV);
            rows.push(tr);
        }
        tbody.innerHTML = '';
        for (const r of rows) tbody.appendChild(r);
    },

    // ==================== UTILITIES ====================

    esc(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    },

    toast(message, type) {
        if (BYD.utils && BYD.utils.toast) {
            BYD.utils.toast(message, type === 'error' ? 'error' : 'success');
        } else {
            console.log('[MQTT] ' + type + ': ' + message);
        }
    }
};
