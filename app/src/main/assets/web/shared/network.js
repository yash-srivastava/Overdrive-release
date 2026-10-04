/**
 * OverDrive — Network & Hotspot page module.
 *
 * The page is a pure CONSUMER of the daemon's /api/hotspot state, which is in
 * turn published by the app-process hotspot owner. There is no AP driving logic
 * here: the page renders state and POSTs intent.
 *
 * Two independent tick rates, matching the native pane: a 1 Hz UI tick that
 * only re-renders the extrapolated uptime (no network), and a slower status
 * poll for everything else.
 */
const NetworkPage = {
    UI_TICK_MS: 1000,
    POLL_MS: 5000,

    uiTimer: null,
    pollTimer: null,
    status: null,
    statusAt: 0,
    // Text inputs are seeded from config once, then left to the user.
    _seeded: false,
    _revealed: false,
    _ssid: '',
    _password: '',
    // Suppresses onchange while restoring persisted values so restoration is
    // never mistaken for a user action.
    binding: false,
    pendingEnable: false,

    init() {
        this.refresh();
        this.pollTimer = setInterval(() => this.refresh(), this.POLL_MS);
        this.uiTimer = setInterval(() => this.renderUptime(), this.UI_TICK_MS);
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) this.refresh();
        });
    },

    // ============== Fetch / render ==============

    async refresh() {
        try {
            const resp = await fetch('/api/hotspot');
            const data = await resp.json();
            if (!data || !data.success || !data.status) return;
            this.status = data.status;
            this.statusAt = Date.now();
            this.render();
        } catch (e) {
            console.warn('[Network] status fetch failed:', e);
        }
    },

    render() {
        const s = this.status;
        if (!s) return;
        this.binding = true;
        try {
            const on = !!s.enabled;
            const transitioning = !!s.transitioning;
            this.chk('hsEnabled', on || transitioning);

            const badge = document.getElementById('hsStateBadge');
            if (badge) {
                badge.textContent = transitioning ? this.t('network.state_starting', 'Starting…')
                    : on ? this.t('network.state_on', 'On')
                    : this.t('network.state_off', 'Off');
            }
            const desc = document.getElementById('hsStateDesc');
            if (desc && s.lastError && !on) desc.textContent = s.lastError;

            // Vehicle-owned credentials: always safe to repaint, nothing editable.
            this._ssid = s.activeSsid || s.ssid || '';
            this._password = s.activePassword || '';
            this.renderCreds();

            // Seed the cap input ONCE: an "is it focused" check is not enough, the
            // poll would wipe typed-but-unsaved text as soon as focus left.
            if (!this._seeded) {
                this.val('hsCap', s.dataCapMb > 0 ? String(s.dataCapMb) : '');
                this._seeded = true;
            }

            this.chk('hsKeepAlive', !!s.keepAlive);
            this.chk('hsAutoStart', !!s.autoStartBoot);
            this.chk('hsProxySystem', !!s.proxySystemWide);
            this.chk('hsProxyClients', !!s.proxyForClients);
            this.chk('hsClientTunnel', !!s.clientTunnel);
            const ctd = document.getElementById('hsClientTunnelDesc');
            if (ctd) {
                ctd.textContent = this.t('network.client_tunnel_desc_fmt',
                    "Sends connected devices' traffic through the encrypted tunnel before it leaves the car's SIM. Point devices at port {tp} instead of {rp}. Slower, since it adds a hop. Turning this off stops that port listening immediately.")
                    .replace('{tp}', s.clientTunnelPort || 8122)
                    .replace('{rp}', s.relayPort || 8121);
            }

            const pcd = document.getElementById('hsProxyClientsDesc');
            if (pcd && s.gateway) {
                // Advertise the cellular-bound relay port, not the outbound tunnel
                // proxy: the relay is the only endpoint that reaches the internet.
                pcd.textContent = this.t('network.proxy_clients_desc_fmt',
                    'This vehicle can\'t route connected devices to the internet on its own, so set a proxy on each device instead: in its Wi-Fi settings choose Manual proxy, host {gw}, port {port}. Browsers then work over the car\'s mobile data. The device will still show "no internet" and keep its own data icon, because that check ignores proxies.')
                    .replace('{gw}', s.gateway).replace('{port}', s.relayPort || 8121);
            }

            this.text('hsRx', this.fmtBytes(s.rxBytes || 0));
            this.text('hsTx', this.fmtBytes(s.txBytes || 0));
            this.text('hsClientCount', String(s.clientCount || 0));
            this.renderClients(s);
            this.renderUsage(s);
            this.renderUptime();

            const warn = document.getElementById('hsWarn');
            if (warn && !this.pendingEnable) warn.style.display = 'none';
        } finally {
            this.binding = false;
        }
    },

    /** 1 Hz: extrapolate from the last status instead of hitting the network. */
    renderUptime() {
        const s = this.status;
        if (!s) return;
        let secs = 0;
        if (s.enabled) {
            const drift = Math.max(0, Math.floor((Date.now() - this.statusAt) / 1000));
            secs = (s.uptimeSeconds || 0) + drift;
        }
        this.text('hsUptime', this.fmtDuration(secs));
    },

    renderUsage(s) {
        // Cumulative persisted usage plus whatever the live session has added,
        // which is what the limit is actually measured against.
        const used = this.fmtBytes((s.dataUsedBytes || 0) + (s.sessionBytes || 0));
        const label = s.dataCapMb > 0
            ? used + ' / ' + s.dataCapMb + ' MB'
            : used + ' — ' + this.t('network.no_limit', 'no limit');
        this.text('hsUsage', label);
    },

    /** Renders the roster when the server sent one; falls back to the count. */
    renderClients(s) {
        const host = document.getElementById('hsClients');
        if (!host) return;
        const list = Array.isArray(s.clients) ? s.clients : null;
        if (list && list.length) {
            host.innerHTML = list.map(c => (
                '<div class="hs-client">' +
                '<span class="hs-client-dot"></span>' +
                '<span class="hs-client-info">' +
                '<span class="hs-client-name"></span>' +
                '<span class="hs-client-meta"></span>' +
                '</span></div>'
            )).join('');
            // Fill via textContent so a client-supplied name can't inject markup.
            const rows = host.querySelectorAll('.hs-client');
            for (let i = 0; i < rows.length && i < list.length; i++) {
                const c = list[i] || {};
                rows[i].querySelector('.hs-client-name').textContent = c.name || c.mac || '';
                rows[i].querySelector('.hs-client-meta').textContent =
                    c.ip ? (c.ip + ' · ' + (c.mac || '')) : (c.mac || '');
            }
            return;
        }
        const n = s.clientCount || 0;
        host.innerHTML = '<div class="hs-empty"></div>';
        host.querySelector('.hs-empty').textContent = n > 0
            ? this.t('network.clients_count', '{n} device(s) connected').replace('{n}', n)
            : this.t('network.clients_empty', 'No devices connected.');
    },

    // ============== Actions ==============

    onToggle() {
        if (this.binding) return;
        const el = document.getElementById('hsEnabled');
        if (!el) return;
        if (!el.checked) {
            this.post('/api/hotspot/disable', null, 'network.stopping');
            return;
        }
        // Single-radio confirm step, once. Ack is persisted server-side.
        if (this.status && this.status.warnAck) {
            this.post('/api/hotspot/enable', null, 'network.starting');
            return;
        }
        this.pendingEnable = true;
        const warn = document.getElementById('hsWarn');
        if (warn) warn.style.display = 'block';
    },

    acceptWarn() {
        this.pendingEnable = false;
        const warn = document.getElementById('hsWarn');
        if (warn) warn.style.display = 'none';
        this.saveSettings({ warnAck: true }, null);
        this.post('/api/hotspot/enable', null, 'network.starting');
    },

    cancelWarn() {
        this.pendingEnable = false;
        const warn = document.getElementById('hsWarn');
        if (warn) warn.style.display = 'none';
        this.binding = true;
        this.chk('hsEnabled', false);
        this.binding = false;
    },

    /** Mask all but the last two characters, so a glance confirms the value. */
    renderCreds() {
        const ssidEl = document.getElementById('hsSsidValue');
        if (ssidEl) ssidEl.textContent = this._ssid || '—';
        const pwEl = document.getElementById('hsPasswordValue');
        if (!pwEl) return;
        const pw = this._password || '';
        if (!pw) { pwEl.textContent = '—'; return; }
        pwEl.textContent = this._revealed
            ? pw
            : '•'.repeat(Math.max(0, pw.length - 2)) + pw.slice(-2);
    },

    toggleReveal() {
        this._revealed = !this._revealed;
        const btn = document.getElementById('hsRevealBtn');
        if (btn) {
            btn.textContent = this._revealed
                ? this.t('network.hide', 'Hide')
                : this.t('network.reveal', 'Show');
        }
        this.renderCreds();
    },

    copyCred(which) {
        const value = which === 'password' ? this._password : this._ssid;
        if (!value) return;
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(value);
            } else {
                // Fallback for the car's older WebView, which lacks the async API.
                const ta = document.createElement('textarea');
                ta.value = value;
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                document.body.removeChild(ta);
            }
            BYD.core.toast(this.t('network.copied', 'Copied'), 'success');
        } catch (e) {
            /* clipboard unavailable — the value is on screen to read anyway */
        }
    },

    saveLimit() {
        const raw = ((document.getElementById('hsCap') || {}).value || '').trim();
        const mb = raw === '' ? 0 : parseInt(raw, 10);
        if (isNaN(mb) || mb < 0) {
            BYD.core.toast(this.t('network.limit_invalid', 'Enter a whole number of megabytes.'), 'error');
            return;
        }
        this.saveSettings({ dataCapMb: mb }, 'common.saved');
    },

    /** Each switch sends ONLY its own key so switches can't overwrite each other. */
    saveSwitch(key, elementId) {
        if (this.binding) return;
        const el = document.getElementById(elementId);
        if (!el) return;
        const body = {};
        body[key] = !!el.checked;
        this.saveSettings(body, 'common.saved');
    },

    resetUsage() {
        this.post('/api/hotspot/reset-usage', null, 'network.usage_reset');
    },

    async saveSettings(body, successKey) {
        try {
            const resp = await fetch('/api/hotspot/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            const data = await resp.json();
            if (data && data.success) {
                if (successKey) BYD.core.toast(this.t(successKey, 'Saved'), 'success');
            } else {
                BYD.core.toast((data && data.error) || this.t('common.error', 'Something went wrong'), 'error');
            }
        } catch (e) {
            BYD.core.toast(this.t('common.error', 'Something went wrong'), 'error');
        }
        this.refresh();
    },

    async post(path, body, successKey) {
        try {
            const resp = await fetch(path, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: body ? JSON.stringify(body) : '{}'
            });
            const data = await resp.json();
            if (data && data.success) {
                if (successKey) BYD.core.toast(this.t(successKey, 'Done'), 'success');
            } else {
                BYD.core.toast((data && data.error) || this.t('common.error', 'Something went wrong'), 'error');
            }
        } catch (e) {
            BYD.core.toast(this.t('common.error', 'Something went wrong'), 'error');
        }
        // Give the radio a moment to transition before re-reading.
        setTimeout(() => this.refresh(), 1500);
    },

    // ============== Helpers ==============

    t(key, fallback) {
        try {
            const v = BYD.i18n && BYD.i18n.t ? BYD.i18n.t(key) : null;
            return (v && v !== key) ? v : fallback;
        } catch (e) {
            return fallback;
        }
    },

    text(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    },

    val(id, value) {
        const el = document.getElementById(id);
        if (el && document.activeElement !== el) el.value = value;
    },

    chk(id, value) {
        const el = document.getElementById(id);
        if (el) el.checked = !!value;
    },

    fmtBytes(bytes) {
        bytes = Number(bytes) || 0;
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    },

    fmtDuration(seconds) {
        seconds = Math.max(0, Math.floor(Number(seconds) || 0));
        if (seconds <= 0) return '--';
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = seconds % 60;
        const pad = n => (n < 10 ? '0' + n : String(n));
        return h > 0 ? (h + ':' + pad(m) + ':' + pad(s)) : (m + ':' + pad(s));
    },

    fmtAge(seconds) {
        seconds = Math.max(0, Math.floor(Number(seconds) || 0));
        function ago(key, n, fallback) {
            var phrase = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, { n: n }) : null;
            if (phrase && phrase !== key) return phrase;
            return fallback;
        }
        if (seconds < 60) return ago('dashboard.time_seconds_ago', seconds, seconds + 's ago');
        return ago('dashboard.time_minutes_ago', Math.floor(seconds / 60), Math.floor(seconds / 60) + 'm ago');
    }
};
