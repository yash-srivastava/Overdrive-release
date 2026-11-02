// ES5-only — Chrome 58 / Android 7.1 head-unit floor.
// SafeKeep page: master toggle, storage (destinations + sign-in), what & when (lanes/rules),
// limits, activity (queue, dry run, restore). Talks to /api/safekeep/*.
window.SK = (function () {
    'use strict';

    var SK_LOCK_ICON = '<svg class="r-inline-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>';

    var state = {
        cfg: null,
        status: null,
        apps: {},
        tab: 'overview',
        pollTimer: null,
        queueFilter: '',
        lanes: {},
        ssids: [],
        webhooks: [],
        busy: false
    };

    var LANES = [
        { id: 'evidence', preset: 'wifi_any', backfill: true },
        { id: 'archive', preset: 'wifi_home', backfill: true },
        { id: 'trips', preset: 'wifi_any', backfill: false },
        { id: 'tripExports', preset: 'wifi_any', backfill: false },
        { id: 'settings', preset: 'wifi_any', backfill: false }
    ];
    var PRESETS = ['wifi_any', 'wifi_home', 'wifi_or_mobile', 'home_charging', 'home_night', 'manual', 'custom'];
    var PROVIDERS = ['gdrive', 'onedrive', 'dropbox', 'nextcloud', 'webdav', 'sftp', 's3'];
    var CONSUMER = { gdrive: 1, onedrive: 1, dropbox: 1 };

    var S3_PRESETS = [
        { id: 'r2', name: 'Cloudflare R2', endpoint: 'https://ACCOUNT_ID.r2.cloudflarestorage.com', region: 'auto', path: true },
        { id: 'b2', name: 'Backblaze B2', endpoint: 'https://s3.REGION.backblazeb2.com', region: 'us-west-004', path: true },
        { id: 'aws', name: 'Amazon S3', endpoint: 'https://s3.REGION.amazonaws.com', region: 'eu-central-1', path: false },
        { id: 'wasabi', name: 'Wasabi', endpoint: 'https://s3.REGION.wasabisys.com', region: 'eu-central-1', path: true },
        { id: 'idrive', name: 'IDrive e2', endpoint: 'https://ENDPOINT.idrivee2.com', region: 'us-east-1', path: true },
        { id: 'hetzner', name: 'Hetzner Object Storage', endpoint: 'https://fsn1.your-objectstorage.com', region: 'fsn1', path: true },
        { id: 'synology', name: 'Synology C2', endpoint: 'https://eu-002.s3.synologyc2.net', region: 'eu-002', path: true },
        { id: 'minio', name: 'MinIO / Garage (self-hosted)', endpoint: 'https://192.168.1.20:9000', region: 'us-east-1', path: true },
        { id: 'custom', name: '', endpoint: '', region: 'us-east-1', path: true }
    ];

    // Advanced-condition catalogue: the automation signals SafeKeep evaluates itself.
    var COND = {
        power: { kind: 'enum', opts: [['off', 'Off'], ['acc', 'Accessory'], ['on', 'On']] },
        gear: { kind: 'enum', opts: [['p', 'P'], ['r', 'R'], ['n', 'N'], ['d', 'D']] },
        speed: { kind: 'int', min: 0, max: 200, unit: 'km/h', vars: { units: 'kmph' } },
        chargingState: { kind: 'enum', opts: [['on', 'Charging'], ['off', 'Not charging']] },
        wifiState: { kind: 'enum', opts: [['on', 'Connected'], ['off', 'Disconnected']] },
        wifiSsid: { kind: 'string' },
        locationZone: { kind: 'string' },
        time: { kind: 'time' },
        day: { kind: 'enum', opts: [['monday', 'Monday'], ['tuesday', 'Tuesday'], ['wednesday', 'Wednesday'], ['thursday', 'Thursday'], ['friday', 'Friday'], ['saturday', 'Saturday'], ['sunday', 'Sunday']] },
        aux12vVoltage: { kind: 'int', min: 90, max: 160, unit: '×0.1 V' },
        batteryLevel: { kind: 'int', min: 0, max: 100, unit: '%' }
    };
    var DAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

    // ── utilities ───────────────────────────────────────────────────────────

    function t(key, fallback, vars) {
        var s = null;
        try { s = (window.BYD && BYD.i18n) ? BYD.i18n.t(key, vars) : null; } catch (e) { s = null; }
        if (!s || s === key) {
            s = fallback == null ? key : fallback;
            if (vars) {
                for (var k in vars) {
                    if (vars.hasOwnProperty(k)) s = s.split('{' + k + '}').join(String(vars[k]));
                }
            }
        }
        return s;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /** Links from providers or servers are shown only when they are plain web links. */
    function safeHref(u) {
        u = String(u || '');
        return /^https?:\/\//i.test(u) ? u : '#';
    }

    /** Buttons carry data-sk (method) + data-a/data-b (args): no user text inside inline handlers. */
    function onDelegatedClick(ev) {
        var el = ev.target;
        while (el && el.nodeType === 1 && !el.getAttribute('data-sk')) el = el.parentNode;
        if (!el || el.nodeType !== 1) return;
        var fn = window.SK && window.SK[el.getAttribute('data-sk')];
        if (typeof fn !== 'function') return;
        var b = el.getAttribute('data-b');
        fn(el.getAttribute('data-a'), b === null ? undefined : b);
    }

    function $(id) { return document.getElementById(id); }

    function toast(msg, type) {
        try { BYD.core.toast(msg, type || 'info'); } catch (e) { /* toast container missing */ }
    }

    function api(method, url, body) {
        var opts = { method: method, headers: {} };
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        return fetch(url, opts).then(function (r) {
            return r.text().then(function (txt) {
                var j = null;
                try { j = txt ? JSON.parse(txt) : {}; } catch (e) { j = { success: false, error: 'bad_response', message: txt }; }
                if (!r.ok && j && j.success === undefined) j.success = false;
                j.httpStatus = r.status;
                return j;
            });
        });
    }

    function fmtBytes(n) {
        n = Number(n) || 0;
        if (n < 1024) return n + ' B';
        var u = ['KB', 'MB', 'GB', 'TB'];
        var i = -1;
        do { n = n / 1024; i++; } while (n >= 1024 && i < u.length - 1);
        return (n >= 100 ? n.toFixed(0) : n >= 10 ? n.toFixed(1) : n.toFixed(2)) + ' ' + u[i];
    }

    function fmtAgo(ts) {
        if (!ts) return t('safekeep.never', 'never');
        var s = Math.max(0, Math.round((Date.now() - ts) / 1000));
        if (s < 60) return t('safekeep.just_now', 'just now');
        if (s < 3600) return t('safekeep.min_ago', '{n} min ago', { n: Math.round(s / 60) });
        if (s < 86400) return t('safekeep.h_ago', '{n} h ago', { n: Math.round(s / 3600) });
        return t('safekeep.d_ago', '{n} d ago', { n: Math.round(s / 86400) });
    }

    function fmtTime(ts) {
        var d = new Date(ts);
        function p(x) { return (x < 10 ? '0' : '') + x; }
        return p(d.getHours()) + ':' + p(d.getMinutes());
    }

    function typeLabel(type) {
        var m = {
            gdrive: t('safekeep.p_gdrive', 'Google Drive'), onedrive: t('safekeep.p_onedrive', 'OneDrive'),
            dropbox: t('safekeep.p_dropbox', 'Dropbox'), nextcloud: t('safekeep.p_nextcloud', 'Nextcloud'),
            webdav: t('safekeep.p_webdav', 'WebDAV / NAS'), sftp: t('safekeep.p_sftp', 'SFTP'),
            s3: t('safekeep.p_s3', 'S3-compatible')
        };
        return m[type] || type;
    }

    function typeDesc(type) {
        var m = {
            gdrive: state.apps.gdrive
                ? t('safekeep.pd_gdrive', 'Sign in on your phone with a code. 15 GB free, shared with Gmail and Photos.')
                : t('safekeep.pd_gdrive_own', 'Advanced: uses your own free Google Cloud project, set up once (about 15 minutes).'),
            onedrive: t('safekeep.pd_onedrive', 'Personal Microsoft account; SafeKeep gets its own app folder.'),
            dropbox: t('safekeep.pd_dropbox', 'Sign in on your phone and paste the code Dropbox shows.'),
            nextcloud: t('safekeep.pd_nextcloud', 'Your Nextcloud server. Approve SafeKeep on your phone.'),
            webdav: t('safekeep.pd_webdav', 'Synology, QNAP, Hetzner Storage Box, Koofr, pCloud and other WebDAV servers.'),
            sftp: t('safekeep.pd_sftp', 'Any SSH server or NAS with SFTP; resumable.'),
            s3: t('safekeep.pd_s3', 'Cloudflare R2, Backblaze B2, AWS, Wasabi, MinIO… Best for evidence-grade retention.')
        };
        return m[type] || '';
    }

    function typeIcon(type) {
        var cloud = '<path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/>';
        var server = '<rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>';
        var bucket = '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>';
        var terminal = '<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>';
        var body = (type === 's3') ? bucket : (type === 'sftp') ? terminal : (type === 'webdav' || type === 'nextcloud') ? server : cloud;
        return '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + body + '</svg>';
    }

    function laneTitle(id) {
        var m = {
            evidence: t('safekeep.lane_evidence', 'Events & saved clips'),
            archive: t('safekeep.lane_archive', 'Dashcam recordings'),
            trips: t('safekeep.lane_trips', 'Trip history'),
            tripExports: t('safekeep.lane_tripExports', 'Trip GPX files'),
            settings: t('safekeep.lane_settings', 'Settings backup')
        };
        return m[id] || id;
    }

    function laneDesc(id) {
        var m = {
            evidence: t('safekeep.lane_evidence_desc', 'Sentry events, proximity alerts and clips you save. The snapshot and timeline go first, then the video.'),
            archive: t('safekeep.lane_archive_desc', 'Continuous driving footage (cam_*.mp4) — about 1.3 GB per hour at default quality. Best for a NAS or S3 bucket on home Wi-Fi.'),
            trips: t('safekeep.lane_trips_desc', 'Trip summaries and GPS tracks. Small, and restorable onto a new head unit.'),
            tripExports: t('safekeep.lane_tripExports_desc', 'A GPX track per trip that opens in any maps app.'),
            settings: t('safekeep.lane_settings_desc', 'Your OverDrive settings with every password, token and key removed.')
        };
        return m[id] || '';
    }

    function presetTitle(id) {
        var m = {
            wifi_any: t('safekeep.preset_wifi_any', 'Any Wi-Fi'),
            wifi_home: t('safekeep.preset_wifi_home', 'Home Wi-Fi, parked'),
            wifi_or_mobile: t('safekeep.preset_wifi_or_mobile', 'Wi-Fi + mobile data'),
            home_charging: t('safekeep.preset_home_charging', 'Home, while charging'),
            home_night: t('safekeep.preset_home_night', 'Home, overnight'),
            manual: t('safekeep.preset_manual', 'Only when I ask'),
            custom: t('safekeep.preset_custom', 'Custom')
        };
        return m[id] || id;
    }

    function presetDesc(id) {
        var m = {
            wifi_any: t('safekeep.preset_wifi_any_desc', 'Whenever the car is on Wi-Fi that isn\'t a phone hotspot.'),
            wifi_home: t('safekeep.preset_wifi_home_desc', 'Only on your home network while parked.'),
            wifi_or_mobile: t('safekeep.preset_wifi_or_mobile_desc', 'Also over mobile data, within your monthly allowance.'),
            home_charging: t('safekeep.preset_home_charging_desc', 'Parked at home and charging — no battery cost.'),
            home_night: t('safekeep.preset_home_night_desc', 'Parked at home between 00:00 and 06:00.'),
            manual: t('safekeep.preset_manual_desc', 'Nothing uploads by itself; use Upload now on a clip.'),
            custom: t('safekeep.preset_custom_desc', 'Choose network, vehicle state, time window and conditions.')
        };
        return m[id] || '';
    }

    function reasonText(code) {
        if (!code) return '';
        var m = {
            offline: t('safekeep.r_offline', 'No internet connection'),
            waiting_wifi: t('safekeep.r_waiting_wifi', 'Waiting for Wi-Fi'),
            waiting_home_wifi: t('safekeep.r_waiting_home_wifi', 'Waiting for home Wi-Fi'),
            hotspot_is_mobile: t('safekeep.r_hotspot', 'Connected to a phone hotspot (counts as mobile data)'),
            waiting_mobile: t('safekeep.r_waiting_mobile', 'Wi-Fi uploads are turned off for this item'),
            waiting_parked: t('safekeep.r_waiting_parked', 'Waiting until the car is parked'),
            waiting_driving: t('safekeep.r_waiting_driving', 'Waiting until the car is driving'),
            waiting_charging: t('safekeep.r_waiting_charging', 'Waiting for charging'),
            outside_window: t('safekeep.r_outside_window', 'Outside the upload time window'),
            manual_only: t('safekeep.r_manual_only', 'Uploads only when you ask'),
            conditions_not_met: t('safekeep.r_conditions', 'Waiting for your custom conditions'),
            conditions_invalid: t('safekeep.r_conditions_invalid', 'Custom conditions are invalid'),
            clock_not_set: t('safekeep.r_clock', 'Waiting for the car clock to be set'),
            on_only_mode: t('safekeep.r_on_only', 'Car is off ("Vehicle on only" mode)'),
            low_12v: t('safekeep.r_low_12v', 'Paused to protect the 12 V battery'),
            parked_time_cap: t('safekeep.r_parked_cap', 'Parked upload time used up for this stop'),
            too_hot: t('safekeep.r_too_hot', 'Paused: head unit is hot'),
            recorder_busy: t('safekeep.r_recorder_busy', 'Paused: the recorder needs the card'),
            event_recording: t('safekeep.r_event', 'Paused while an event is recording'),
            live_view: t('safekeep.r_live_view', 'Paused while live view is open'),
            interactive: t('safekeep.r_interactive', 'Paused for blind spot / voice / remote view'),
            mobile_cap: t('safekeep.r_mobile_cap', 'Monthly mobile data allowance used'),
            paused_by_user: t('safekeep.r_paused', 'Paused by you'),
            backoff: t('safekeep.r_backoff', 'Retrying shortly'),
            rate_limited: t('safekeep.r_rate_limited', 'Storage asked us to slow down; retrying'),
            dest_needs_auth: t('safekeep.r_needs_auth', 'Sign-in expired — reconnect the storage'),
            needs_auth: t('safekeep.r_needs_auth', 'Sign-in expired — reconnect the storage'),
            dest_quota_full: t('safekeep.r_quota_full', 'Storage is full'),
            quota_full: t('safekeep.r_quota_full', 'Storage is full'),
            LOCAL_IO: t('safekeep.r_local_io', 'Waiting for the SD card / file'),
            paused: t('safekeep.r_paused_generic', 'Paused'),
            yield: t('safekeep.r_yield', 'Waiting behind a higher-priority upload'),
            stopping: t('safekeep.r_paused_generic', 'Paused'),
            disabled: t('safekeep.disabled', 'Disabled'),
            dest_unavailable: t('safekeep.r_dest_unavailable', 'Storage settings need attention'),
            NETWORK: t('safekeep.r_network', 'Connection problem; retrying'),
            SESSION_GONE: t('safekeep.r_restarting', 'Restarting the upload'),
            CLOCK_SKEW: t('safekeep.r_clock', 'Waiting for the car clock to be set'),
            PERMANENT: t('safekeep.r_refused', 'The storage refused the upload'),
            CONFLICT: t('safekeep.r_conflict', 'The file changed on the storage; retrying'),
            unrouted: t('safekeep.r_skipped_unrouted', 'No longer in your SafeKeep rules'),
            empty: t('safekeep.r_skipped_empty', 'The file was empty'),
            user: t('safekeep.r_skipped_user', 'Skipped by you')
        };
        return m[code] || code.replace(/_/g, ' ');
    }

    function destState(id) {
        var ds = (state.status && state.status.destinations) || [];
        for (var i = 0; i < ds.length; i++) if (ds[i].id === id) return ds[i];
        return null;
    }

    function destinations() {
        return (state.cfg && state.cfg.destinations) || [];
    }

    // ── modal ───────────────────────────────────────────────────────────────

    function openModal(opts) {
        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.style.display = 'flex';
        var card = document.createElement('div');
        card.className = 'modal-card sk-modal-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');
        var html = '<h3 class="soh-modal-title">' + esc(opts.title || '') + '</h3>'
            + '<div class="sk-modal-body">' + (opts.body || '') + '</div>'
            + '<div class="soh-modal-actions"></div>';
        card.innerHTML = html;
        backdrop.appendChild(card);
        document.body.appendChild(backdrop);
        var actionsEl = card.querySelector('.soh-modal-actions');
        var closed = false;
        var m = {
            el: card,
            body: card.querySelector('.sk-modal-body'),
            close: function () {
                if (closed) return;
                closed = true;
                if (opts.onClose) { try { opts.onClose(); } catch (e) { /* ignore */ } }
                if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
                document.removeEventListener('keydown', onKey);
            },
            setActions: function (list) {
                actionsEl.innerHTML = '';
                (list || []).forEach(function (a) {
                    var b = document.createElement('button');
                    b.className = 'btn ' + (a.cls || 'btn-secondary');
                    b.textContent = a.label;
                    if (a.id) b.id = a.id;
                    b.onclick = function () { a.onClick(m, b); };
                    actionsEl.appendChild(b);
                });
            }
        };
        function onKey(e) { if (e.key === 'Escape' || e.keyCode === 27) m.close(); }
        document.addEventListener('keydown', onKey);
        backdrop.addEventListener('click', function (e) { if (e.target === backdrop && !opts.sticky) m.close(); });
        m.setActions(opts.actions || [{ label: t('common.close', 'Close'), onClick: function (mm) { mm.close(); } }]);
        try { if (BYD.i18n && BYD.i18n.hydrate) BYD.i18n.hydrate(card); } catch (e) { /* ignore */ }
        return m;
    }

    function confirm(title, body, label, danger) {
        if (BYD.utils && BYD.utils.confirmDialog) {
            return BYD.utils.confirmDialog({ title: title, body: body, confirmLabel: label, danger: !!danger });
        }
        return Promise.resolve(window.confirm(title + '\n\n' + body));
    }

    function field(id, label, value, type, extra) {
        return '<div class="md-field sk-field"><div class="md-field-box">'
            + '<input class="md-input" id="' + id + '" type="' + (type || 'text') + '" placeholder=" " value="' + esc(value || '') + '" ' + (extra || '') + '>'
            + '<label class="md-label" for="' + id + '">' + esc(label) + '</label></div></div>';
    }

    function textarea(id, label, value) {
        return '<div class="md-field sk-field"><div class="md-field-box">'
            + '<textarea class="md-input" id="' + id + '" rows="4" placeholder=" " style="font-family:JetBrains Mono,monospace;font-size:12px;">' + esc(value || '') + '</textarea>'
            + '<label class="md-label" for="' + id + '">' + esc(label) + '</label></div></div>';
    }

    function select(id, options, value) {
        var h = '<select class="select-control" id="' + id + '">';
        options.forEach(function (o) {
            h += '<option value="' + esc(o[0]) + '"' + (String(o[0]) === String(value) ? ' selected' : '') + '>' + esc(o[1]) + '</option>';
        });
        return h + '</select>';
    }

    function settingRow(name, desc, control) {
        return '<div class="setting-row"><div class="setting-info"><div class="setting-name">' + esc(name) + '</div>'
            + (desc ? '<div class="setting-desc">' + esc(desc) + '</div>' : '') + '</div>'
            + '<div class="setting-control">' + control + '</div></div>';
    }

    function toggle(id, checked, onchange) {
        return '<label class="toggle-switch"><input type="checkbox" id="' + id + '"' + (checked ? ' checked' : '')
            + (onchange ? ' onchange="' + onchange + '"' : '') + '><span class="toggle-slider"></span></label>';
    }

    function val(id) {
        var el = $(id);
        return el ? el.value : '';
    }

    function checked(id) {
        var el = $(id);
        return !!(el && el.checked);
    }

    function proxyOptions() {
        return [['auto', t('safekeep.proxy_auto', 'Automatic (proxy, then direct)')],
            ['proxy', t('safekeep.proxy_always', 'Always through the proxy')],
            ['direct', t('safekeep.proxy_direct', 'Always direct')]];
    }

    function copyText(text) {
        function done() { toast(t('network.copied', 'Copied'), 'success'); }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, function () { fallback(); });
        } else {
            fallback();
        }
        function fallback() {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            try { document.execCommand('copy'); done(); } catch (e) { /* ignore */ }
            document.body.removeChild(ta);
        }
    }

    // ── load & polling ──────────────────────────────────────────────────────

    function load() {
        return Promise.all([api('GET', '/api/safekeep/config'), api('GET', '/api/safekeep/status')]).then(function (r) {
            var c = r[0] || {};
            state.cfg = c.config || {};
            state.apps = c.oauthApps || {};
            state.status = r[1] || { enabled: false };
            buildLaneState();
            renderAll();
        }).catch(function () {
            toast(t('safekeep.load_failed', 'Could not load SafeKeep'), 'error');
        });
    }

    function refreshStatus() {
        return api('GET', '/api/safekeep/status').then(function (s) {
            state.status = s || { enabled: false };
            renderOverview();
            renderDestinations();
        }).catch(function () { /* transient */ });
    }

    function schedulePoll() {
        if (state.pollTimer) clearTimeout(state.pollTimer);
        var hidden = document.hidden;
        var enabled = state.status && state.status.enabled;
        var fast = !hidden && enabled && (state.tab === 'overview' || state.tab === 'storage');
        var delay = hidden ? 60000 : (fast ? 3000 : 15000);
        // Off means off: the page stops asking until the toggle is turned on here.
        if (!enabled) {
            state.pollTimer = null;
            return;
        }
        state.pollTimer = setTimeout(function () {
            var p = refreshStatus();
            if (state.tab === 'activity' && !document.hidden && enabled) loadQueue();
            p.then(schedulePoll, schedulePoll);
        }, delay);
    }

    function init() {
        document.addEventListener('ot-tabs:active-changed', function (ev) {
            state.tab = ev && ev.detail ? ev.detail.id : state.tab;
            if (state.tab === 'activity') { loadQueue(); renderRestoreDests(); }
            schedulePoll();
        });
        document.addEventListener('visibilitychange', schedulePoll);
        document.addEventListener('click', onDelegatedClick);
        var hash = (window.location.hash || '').replace('#', '');
        if (hash) state.tab = hash;
        syncActiveTab();
        var days = $('skResetDay');
        if (days) {
            for (var d = 1; d <= 28; d++) {
                var o = document.createElement('option');
                o.value = String(d);
                o.textContent = String(d);
                days.appendChild(o);
            }
        }
        load().then(function () {
            syncActiveTab();
            if (state.tab === 'activity') { loadQueue(); renderRestoreDests(); }
            schedulePoll();
        });
    }

    /** The tab bar restores the last tab without an event: read it from the bar itself. */
    function syncActiveTab() {
        var act = document.querySelector('.bottom-tabs .bottom-tab.is-active');
        var id = act && act.getAttribute('data-tab-target');
        if (id) state.tab = id;
    }

    function renderAll() {
        renderOverview();
        renderDestinations();
        renderLanes();
        renderLimits();
        renderRestoreDests();
    }

    // ── overview ─────────────────────────────────────────────────────────────

    function renderOverview() {
        var s = state.status || {};
        var on = !!s.enabled;
        var master = $('skEnabled');
        if (master && !state.busy) master.checked = on;
        var badge = $('skBadge');
        var anyBad = false;
        (s.destinations || []).forEach(function (d) { if (d.state === 'needs_auth' || d.state === 'quota_full') anyBad = true; });
        if (badge) {
            badge.className = 'status-badge ' + (!on ? 'inactive' : (anyBad ? 'errored' : 'active'));
            badge.textContent = !on ? t('safekeep.badge_off', 'OFF') : (anyBad ? t('safekeep.badge_attention', 'CHECK') : t('safekeep.badge_on', 'ON'));
        }
        var hasDest = destinations().length > 0;
        $('skSetupCard').classList.toggle('sk-hidden', !(on && !hasDest));
        $('skStatusCard').classList.toggle('sk-hidden', !(on && hasDest));
        $('skOnOnlyNote').style.display = s.onOnlyMode ? 'flex' : 'none';
        if (!on || !hasDest) return;

        var dot = $('skStateDot');
        var text = $('skStateText');
        var reason = $('skReason');
        var st = s.state;
        var label, dotCls;
        if (st === 'uploading') { label = t('safekeep.st_uploading', 'Uploading'); dotCls = 'live'; }
        else if (st === 'paused') {
            label = s.pausedUntil ? t('safekeep.st_paused_until', 'Paused until {time}', { time: fmtTime(s.pausedUntil) }) : t('safekeep.st_paused', 'Paused');
            dotCls = 'off';
        } else if (st === 'waiting') { label = t('safekeep.st_waiting', 'Waiting'); dotCls = 'off'; }
        else { label = t('safekeep.st_idle', 'Up to date'); dotCls = 'connected'; }
        dot.className = 'status-dot ' + dotCls;
        text.textContent = label;
        var r = s.reason ? reasonText(s.reason) : '';
        if (!r && s.lastOkAt) r = t('safekeep.last_upload', 'Last upload {ago}', { ago: fmtAgo(s.lastOkAt) });
        reason.textContent = r;

        var cur = s.current || [];
        var h = '';
        cur.forEach(function (c) {
            var pct = c.size > 0 ? Math.min(100, Math.round(c.sent * 100 / c.size)) : 0;
            var dn = c.dest;
            destinations().forEach(function (d) { if (d.id === c.dest) dn = d.name; });
            h += '<div class="sk-transfer"><div class="sk-transfer-top"><span class="sk-transfer-name">' + esc(c.name) + ' → ' + esc(dn) + '</span>'
                + '<span class="sk-transfer-meta">' + fmtBytes(c.sent) + ' / ' + fmtBytes(c.size)
                + (c.kbps ? ' · ' + (c.kbps >= 1000 ? (c.kbps / 1000).toFixed(1) + ' Mbps' : c.kbps + ' kbps') : '') + '</span></div>'
                + '<div class="storage-bar"><div class="storage-fill" style="width:' + pct + '%"></div></div></div>';
        });
        $('skCurrent').innerHTML = h;

        var b = s.backlog || {};
        var cells = '';
        ['evidence', 'archive', 'trips', 'settings'].forEach(function (lane) {
            var x = b[lane] || { count: 0, bytes: 0, failed: 0 };
            cells += '<div class="sk-stat"><div class="sk-stat-label">' + esc(laneTitle(lane)) + '</div>'
                + '<div class="sk-stat-value">' + (x.count || 0) + '</div>'
                + '<div class="sk-stat-sub">' + (x.count ? esc(t('safekeep.to_upload', '{size} to upload', { size: fmtBytes(x.bytes) })) : esc(t('safekeep.all_saved', 'All saved')))
                + (x.failed ? ' · <span class="sk-warn">' + esc(t('safekeep.n_failed', '{n} failed', { n: x.failed })) + '</span>' : '') + '</div></div>';
        });
        $('skBacklog').innerHTML = cells;

        var mob = s.mobile || {};
        var cap = (mob.capMb || 0) * 1024 * 1024;
        var used = mob.usedBytes || 0;
        var mh = '';
        if (cap > 0 || used > 0) {
            var pctm = cap > 0 ? Math.min(100, Math.round(used * 100 / cap)) : 0;
            mh = '<div class="sk-sub">' + esc(t('safekeep.mobile_month', 'Mobile data this month')) + '</div>'
                + '<div class="storage-bar"><div class="storage-fill" style="width:' + pctm + '%"></div></div>'
                + '<div class="storage-info"><span>' + esc(fmtBytes(used)) + '</span><span>'
                + esc(cap > 0 ? t('safekeep.of_cap_resets', 'of {cap} · resets {date}', { cap: fmtBytes(cap), date: mob.resetsOn || '' }) : t('safekeep.no_mobile', 'mobile data off')) + '</span></div>';
        }
        $('skMobile').innerHTML = mh;

        var n = s.network || {};
        var net;
        if (n.transport === 'wifi') net = n.phoneHotspot ? t('safekeep.net_hotspot', 'Phone hotspot (treated as mobile data)') : t('safekeep.net_wifi', 'Wi-Fi') + (n.ssid ? ' “' + n.ssid + '”' : '');
        else if (n.transport === 'cellular') net = t('safekeep.net_mobile', 'Mobile data');
        else if (n.transport === 'ethernet') net = t('safekeep.net_ethernet', 'Ethernet');
        else if (n.transport === 'none') net = t('safekeep.net_offline', 'Offline');
        else net = t('safekeep.net_other', 'Other network (treated as mobile data)');
        if (n.route) net += ' · ' + (n.route === 'proxy' ? t('safekeep.via_proxy', 'via proxy') : t('safekeep.direct', 'direct'));
        $('skNet').textContent = net;

        var paused = !!s.paused;
        $('skPauseBtn').classList.toggle('sk-hidden', paused);
        $('skPauseFor').classList.toggle('sk-hidden', paused);
        $('skResumeBtn').classList.toggle('sk-hidden', !paused);
    }

    function toggleMaster(on) {
        state.busy = true;
        var master = $('skEnabled');
        if (master) master.disabled = true;
        function done() {
            state.busy = false;
            if (master) master.disabled = false;
        }
        api('POST', '/api/safekeep/enable', { enabled: on }).then(function (r) {
            done();
            if (!r.success) toast(t('safekeep.toggle_failed', 'Could not change SafeKeep'), 'error');
            else toast(on ? t('safekeep.turned_on', 'SafeKeep is on') : t('safekeep.turned_off', 'SafeKeep is off — nothing runs in the background'), 'success');
            return load();
        }).then(function () {
            if (on && destinations().length === 0 && window.OT_setActiveTab) window.OT_setActiveTab('storage');
            schedulePoll();
        }, done);
    }

    function requireOn() {
        if (state.status && state.status.enabled) return true;
        toast(t('safekeep.turn_on_first', 'Turn SafeKeep on first'), 'warning');
        if (window.OT_setActiveTab) window.OT_setActiveTab('overview');
        return false;
    }

    function syncNow() {
        api('POST', '/api/safekeep/run', {}).then(function (r) {
            toast(r.success ? t('safekeep.syncing', 'Checking for uploads now') : (r.message || 'Error'), r.success ? 'success' : 'error');
            setTimeout(refreshStatus, 800);
        });
    }

    function pause() {
        var m = parseInt(val('skPauseFor'), 10) || 0;
        api('POST', '/api/safekeep/pause', { minutes: m }).then(function () { refreshStatus(); });
    }

    function resume() {
        api('POST', '/api/safekeep/resume', {}).then(function () { refreshStatus(); });
    }

    // ── storage ──────────────────────────────────────────────────────────────

    function renderDestinations() {
        var list = $('skDestList');
        if (!list) return;
        var ds = destinations();
        if (!ds.length) {
            list.innerHTML = '<div class="safe-zone-empty">' + esc(t('safekeep.no_storage', 'No storage connected yet')) + '</div>';
            return;
        }
        var h = '';
        ds.forEach(function (d) {
            var st = destState(d.id) || {};
            var ok = d.enabled !== false && (!st.state || st.state === 'ok');
            var meta = [typeLabel(d.type === 'webdav' && d.params && d.params.mode === 'nextcloud' ? 'nextcloud' : d.type)];
            if (st.quota && st.quota.total > 0) meta.push(t('safekeep.quota_used', '{used} of {total} used', { used: fmtBytes(st.quota.used), total: fmtBytes(st.quota.total) }));
            if (d.encryption && d.encryption.mode === 'age') meta.push(t('safekeep.encrypted', 'Encrypted'));
            if (d.enabled === false) meta.push(t('safekeep.disabled', 'Disabled'));
            else if (st.state === 'needs_auth') meta.push(t('safekeep.needs_auth', 'Sign-in expired'));
            else if (st.state === 'quota_full') meta.push(t('safekeep.full', 'Storage full'));
            else if (st.lastOkAt) meta.push(t('safekeep.last_upload', 'Last upload {ago}', { ago: fmtAgo(st.lastOkAt) }));
            h += '<div class="safe-zone-row' + (ok ? '' : ' is-disabled') + '">'
                + '<span class="safe-zone-indicator ' + (ok ? 'is-enabled' : 'is-disabled') + '" aria-hidden="true"></span>'
                + '<div class="safe-zone-info"><div class="safe-zone-name">' + esc(d.name) + '</div>'
                + '<div class="safe-zone-meta">' + esc(meta.join(' · ')) + '</div>'
                + (st.lastError && !ok ? '<div class="sk-queue-err">' + esc(destErrorText(st.lastError)) + '</div>' : '') + '</div>'
                + '<div class="safe-zone-actions">'
                + (st.state === 'needs_auth' && (CONSUMER[d.type] || (d.params && d.params.mode === 'nextcloud'))
                    ? '<button class="btn btn-primary" data-sk="reconnect" data-a="' + esc(d.id) + '">' + esc(t('safekeep.reconnect', 'Reconnect')) + '</button>' : '')
                + '<button class="safe-zone-icon-btn" title="' + esc(t('safekeep.test', 'Test')) + '" data-sk="testDest" data-a="' + esc(d.id) + '"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg></button>'
                + '<button class="safe-zone-icon-btn" title="' + esc(t('safekeep.edit', 'Edit')) + '" data-sk="editDest" data-a="' + esc(d.id) + '"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg></button>'
                + '<button class="safe-zone-icon-btn is-danger" title="' + esc(t('safekeep.remove', 'Remove')) + '" data-sk="removeDest" data-a="' + esc(d.id) + '"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg></button>'
                + '</div></div>';
        });
        list.innerHTML = h;
    }

    function destErrorText(e) {
        if (e === 'google_testing_expired') {
            return t('safekeep.google_testing_expired', 'Google signed the car out because your Google Cloud project is still in Testing. In the console go to Audience → Publish app, then tap Reconnect.');
        }
        return e;
    }

    function findDest(id) {
        var ds = destinations();
        for (var i = 0; i < ds.length; i++) if (ds[i].id === id) return ds[i];
        return null;
    }

    /** Google Drive leads only when this build signs in for the user; otherwise it is the advanced option. */
    function providerOrder() {
        if (state.apps.gdrive) return PROVIDERS;
        return PROVIDERS.filter(function (p) { return p !== 'gdrive'; }).slice(0, 2).concat(['gdrive'])
            .concat(PROVIDERS.filter(function (p) { return p !== 'gdrive'; }).slice(2));
    }

    function openAddStorage() {
        if (!requireOn()) return;
        var h = '<div class="mode-selector sk-provider-grid" role="radiogroup">';
        providerOrder().forEach(function (p, i) {
            h += '<label class="mode-option"><input type="radio" name="skProvider" value="' + p + '"' + (i === 0 ? ' checked' : '') + '>'
                + '<div class="mode-card"><span class="mode-icon" aria-hidden="true">' + typeIcon(p) + '</span>'
                + '<span class="mode-body"><span class="mode-name">' + esc(typeLabel(p)) + '</span><span class="mode-desc">' + esc(typeDesc(p)) + '</span></span>'
                + '<span class="mode-radio" aria-hidden="true"></span></div></label>';
        });
        h += '</div>';
        openModal({
            title: t('safekeep.add_storage', 'Add storage'),
            body: h,
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (m) { m.close(); } },
                {
                    label: t('safekeep.continue', 'Continue'), cls: 'btn-primary', onClick: function (m) {
                        var sel = m.el.querySelector('input[name="skProvider"]:checked');
                        var p = sel ? sel.value : 'gdrive';
                        m.close();
                        startProvider(p);
                    }
                }
            ]
        });
    }

    function startProvider(p) {
        if (p === 'gdrive' || p === 'onedrive' || p === 'dropbox') return oauthIntro(p, null);
        if (p === 'nextcloud') return nextcloudForm(null);
        if (p === 'webdav') return webdavForm();
        if (p === 's3') return s3Form();
        if (p === 'sftp') return sftpForm();
    }

    /** One-time walkthrough for a user's own Google sign-in (this build ships none for Google). */
    function googleGuide() {
        var steps = [
            t('safekeep.gd_step1', 'On a computer or phone, open the Google Cloud console and create a project (any name).'),
            t('safekeep.gd_step2', 'APIs & Services → Library: enable “Google Drive API”.'),
            t('safekeep.gd_step3', 'Google Auth Platform → Get started: choose External, then enter an app name and your email.'),
            t('safekeep.gd_step4', 'Data access → Add or remove scopes: tick “…/auth/drive.file”, then Save.'),
            t('safekeep.gd_step5', 'Audience → Publish app. Don\'t skip this: while the project is in Testing, Google signs the car out every 7 days.'),
            t('safekeep.gd_step6', 'Clients → Create client → “TVs and Limited Input devices”. Paste the client ID and secret below.')
        ];
        return '<div class="sk-sub">' + esc(t('safekeep.gd_guide_title', 'Set up your own Google sign-in (once, about 15 minutes)')) + '</div>'
            + '<ol class="sk-steps">' + steps.map(function (x, i) {
                return '<li' + (i === 4 ? ' class="sk-warn"' : '') + '>' + esc(x) + '</li>';
            }).join('') + '</ol>'
            + '<a class="sk-link" href="https://console.cloud.google.com/" target="_blank" rel="noopener">'
            + esc(t('safekeep.gd_open_console', 'Open Google Cloud console')) + ' — console.cloud.google.com</a>';
    }

    /** Consumer clouds: if this build has no app registration, the user can bring their own. */
    function oauthIntro(type, relinkId) {
        var configured = !!state.apps[type];
        // A reconnect reuses the app registration the storage was set up with.
        var needApp = !configured && !relinkId;
        var body = '<p class="setting-desc">' + esc(type === 'dropbox'
            ? t('safekeep.dropbox_intro', 'You\'ll sign in to Dropbox on your phone and paste back a short code. SafeKeep only gets its own app folder.')
            : t('safekeep.device_intro', 'You\'ll scan a QR code with your phone and enter a short code to approve SafeKeep. It can only see files it creates.')) + '</p>'
            + (type === 'gdrive' && needApp ? googleGuide() : '')
            + '<details class="recommend-callout" style="margin-top:12px;"' + (needApp ? ' open' : '') + '><summary class="recommend-callout-summary"><span class="recommend-callout-text"><span class="recommend-callout-title">'
            + esc(t('safekeep.own_app', 'Use my own app registration')) + '</span><span class="recommend-callout-sub">'
            + esc(needApp ? t('safekeep.own_app_needed', 'This build has no sign-in app for this provider — enter your own')
                : (relinkId ? t('safekeep.own_app_keep', 'Optional — leave empty to keep the one you used before') : t('safekeep.own_app_opt', 'Optional'))) + '</span></span></summary>'
            + field('skAppId', type === 'dropbox' ? t('safekeep.app_key', 'App key') : t('safekeep.client_id', 'Client ID'), '')
            + (type === 'gdrive' ? field('skAppSecret', t('safekeep.client_secret', 'Client secret'), '', 'password') : '')
            + '</details>'
            + '<div class="sk-field" style="margin-top:12px;">' + settingRow(t('safekeep.proxy', 'Connection'), '', select('skLinkProxy', proxyOptions(), 'auto')) + '</div>';
        openModal({
            title: t('safekeep.connect_x', 'Connect {name}', { name: typeLabel(type) }),
            body: body,
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (m) { m.close(); } },
                {
                    label: t('safekeep.continue', 'Continue'), cls: 'btn-primary', onClick: function (m) {
                        var params = { proxyMode: val('skLinkProxy') };
                        var id = (val('skAppId') || '').trim();
                        if (id) params.app = { clientId: id, clientSecret: (val('skAppSecret') || '').trim() };
                        else if (needApp) { toast(t('safekeep.app_required', 'Enter a client ID'), 'warning'); return; }
                        if (relinkId) params.relinkId = relinkId;
                        m.close();
                        startLink(type, params);
                    }
                }
            ]
        });
    }

    function startLink(type, params) {
        var m = openModal({
            title: t('safekeep.connect_x', 'Connect {name}', { name: typeLabel(type) }),
            body: '<div class="sk-wait"><span class="sk-spinner"></span><span>' + esc(t('safekeep.starting', 'Starting sign-in…')) + '</span></div>',
            sticky: true,
            actions: [{ label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } }]
        });
        var link = null;
        var timer = null;
        var closed = false;
        var cancelling = false;
        var polling = false;
        var origClose = m.close;
        function closeNow() {
            closed = true;
            if (timer) clearTimeout(timer);
            origClose();
        }
        function cancelLink(id) {
            return api('POST', '/api/safekeep/link/cancel', { id: id }).then(function (r) {
                if (!r.success) toast(linkError(r), 'warning');
                return r;
            }, function () {
                toast(t('safekeep.err_cancel', 'Could not confirm cancellation. Check the sign-in status.'), 'warning');
                return { success: false };
            });
        }
        m.close = function () {
            if (closed || cancelling) return;
            if (link && link.state === 'pending') {
                cancelling = true;
                cancelLink(link.linkId).then(function (r) {
                    cancelling = false;
                    if (r.success) closeNow();
                    // A claimed commit cannot be revoked: retain the modal and its status poll.
                    else poll(); // Paste mode also needs a status loop after a lost finish response.
                });
                return;
            }
            closeNow();
        };
        m.setActions([{ label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } }]);
        api('POST', '/api/safekeep/link/start', { type: type, params: params }).then(function (r) {
            // Cancelled while starting: stop the sign-in the daemon just opened, and never poll it.
            if (closed) {
                if (r && r.success && r.linkId) cancelLink(r.linkId).then(function (c) {
                    if (!c.success) load(); // A late accepted commit may appear in storage.
                });
                return;
            }
            if (!r.success) {
                m.body.innerHTML = '<p class="sk-queue-err">' + esc(linkError(r)) + '</p>';
                return;
            }
            link = r;
            var qr = '/api/safekeep/link/qr.png?id=' + encodeURIComponent(r.linkId) + '&t=' + Date.now();
            var h = '<div class="sk-qr"><img src="' + qr + '" alt="QR">';
            if (r.mode === 'device') {
                h += '<div class="setting-desc">' + esc(t('safekeep.device_steps', 'Scan with your phone, or open the link and enter this code:')) + '</div>'
                    + '<div class="sk-code">' + esc(r.userCode || '') + '</div>'
                    + '<a class="sk-link" href="' + esc(safeHref(r.verifyUrl)) + '" target="_blank" rel="noopener">' + esc(r.verifyUrl) + '</a>';
            } else if (r.mode === 'paste') {
                h += '<div class="setting-desc">' + esc(t('safekeep.paste_steps', 'Scan with your phone (or open the link), allow SafeKeep, then paste the code here:')) + '</div>'
                    + '<a class="sk-link" href="' + esc(safeHref(r.verifyUrl)) + '" target="_blank" rel="noopener">' + esc(t('safekeep.open_dropbox', 'Open Dropbox')) + '</a>'
                    + '<div style="width:100%;">' + field('skPasteCode', t('safekeep.code', 'Code'), '') + '</div>';
            } else {
                h += '<div class="setting-desc">' + esc(t('safekeep.browser_steps', 'Scan with your phone (or open the link) and approve SafeKeep on your server:')) + '</div>'
                    + '<a class="sk-link" href="' + esc(safeHref(r.verifyUrl)) + '" target="_blank" rel="noopener">' + esc(t('safekeep.open_login', 'Open login page')) + '</a>';
            }
            h += '</div><div class="sk-wait" id="skLinkWait"' + (r.mode === 'paste' ? ' style="display:none"' : '') + '><span class="sk-spinner"></span><span>'
                + esc(t('safekeep.waiting_approval', 'Waiting for approval…')) + '</span></div>';
            m.body.innerHTML = h;
            if (r.mode === 'paste') {
                m.setActions([
                    { label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } },
                    {
                        label: t('safekeep.connect', 'Connect'), cls: 'btn-primary', onClick: function (mm, btn) {
                            btn.disabled = true;
                            api('POST', '/api/safekeep/link/finish', { id: r.linkId, code: val('skPasteCode') }).then(function (f) {
                                btn.disabled = false;
                                if (closed) return;
                                if (f.success) { link.state = 'done'; linked(mm, f.destId); }
                                else { toast(linkError(f), 'error'); poll(); }
                            }, function () {
                                btn.disabled = false;
                                if (closed) return;
                                toast(t('safekeep.err_link_status', 'Could not confirm sign-in. Checking its status.'), 'warning');
                                poll();
                            });
                        }
                    }
                ]);
            } else {
                poll();
            }
        });
        function poll() {
            if (closed || timer || polling || !link || link.state !== 'pending') return;
            timer = setTimeout(function () {
                timer = null;
                if (closed || link.state !== 'pending') return;
                polling = true;
                api('GET', '/api/safekeep/link/status?id=' + encodeURIComponent(link.linkId)).then(function (s) {
                    polling = false;
                    if (closed) return;
                    link.state = s.state;
                    if (s.state === 'done') return linked(m, s.destId);
                    if (s.state === 'pending') return poll();
                    m.body.innerHTML = '<p class="sk-queue-err">' + esc(s.state === 'expired' ? t('safekeep.code_expired', 'The code expired. Try again.') : linkError(s)) + '</p>';
                    m.setActions([{ label: t('common.close', 'Close'), onClick: function (mm) { mm.close(); } }]);
                }, function () { polling = false; poll(); });
            }, 3000);
        }
    }

    function linkError(r) {
        var e = (r && (r.error || r.message)) || '';
        if (e === 'oauth_app_missing') return t('safekeep.err_app_missing', 'No sign-in app is configured for this provider.');
        if (e === 'access_denied') return t('safekeep.err_denied', 'Access was declined.');
        if (e === 'link_commit_started') return t('safekeep.err_cancel_late', 'Sign-in is already being saved. Check its status before removing the storage.');
        if (e === 'link_in_progress') return t('safekeep.err_link_busy', 'Sign-in is already in progress.');
        if (e === 'server_url_required') return t('safekeep.err_server', 'Enter your server address (https://…).');
        if (e.indexOf('provider_error:') === 0) return e.substring(15);
        return e || t('safekeep.err_generic', 'Something went wrong');
    }

    function linked(m, destId) {
        m.body.innerHTML = '<div class="sk-wait"><span class="sk-ok">✓</span><span>' + esc(t('safekeep.connected', 'Connected. Checking the connection…')) + '</span></div>';
        m.setActions([{ label: t('common.close', 'Close'), onClick: function (mm) { mm.close(); } }]);
        load().then(function () {
            if (destId) {
                api('POST', '/api/safekeep/dest/test', { id: destId }).then(function (r) {
                    m.body.innerHTML = testResultHtml(r);
                    refreshStatus();
                });
            }
        });
    }

    function testResultHtml(r) {
        if (r && r.success) {
            var x = r.result || {};
            var parts = [];
            if (x.account) parts.push(t('safekeep.account', 'Account: {a}', { a: x.account }));
            if (x.quota && x.quota.total) parts.push(t('safekeep.quota_used', '{used} of {total} used', { used: fmtBytes(x.quota.used), total: fmtBytes(x.quota.total) }));
            if (x.latencyMs) parts.push(t('safekeep.latency', 'Round trip {ms} ms', { ms: x.latencyMs }));
            if (x.route) parts.push(x.route === 'proxy' ? t('safekeep.via_proxy', 'via proxy') : t('safekeep.direct', 'direct'));
            if (x.note) parts.push(x.note);
            return '<div class="sk-wait"><span class="sk-ok">✓</span><span>' + esc(t('safekeep.test_ok', 'Works — test file written and removed.')) + '</span></div>'
                + '<div class="sk-result">' + esc(parts.join(' · ')) + '</div>';
        }
        return '<p class="sk-queue-err">' + esc((r && (r.message || r.error)) || t('safekeep.err_generic', 'Something went wrong')) + '</p>';
    }

    function isTlsError(msg) {
        msg = String(msg || '');
        return /SSLHandshake|Trust anchor|CertPath|certificate|PKIX|hostname/i.test(msg);
    }

    function testDest(id) {
        var d = findDest(id);
        var m = openModal({
            title: t('safekeep.test_x', 'Test {name}', { name: d ? d.name : id }),
            body: '<div class="sk-wait"><span class="sk-spinner"></span><span>' + esc(t('safekeep.testing', 'Writing a small test file…')) + '</span></div>'
        });
        api('POST', '/api/safekeep/dest/test', { id: id }).then(function (r) {
            if (r && r.success && r.result && r.result.hostKey && !r.result.hostKeyPinned) {
                return trustHostKey(m, d, r.result.hostKey, r.result.hostKeyBlob, !!r.result.hostKeyChanged);
            }
            if (!(r && r.success) && d && isTlsError(r && r.message) && d.params && (d.params.url || d.params.endpoint)) {
                return offerCertTrust(m, d, d.params.url || d.params.endpoint);
            }
            m.body.innerHTML = testResultHtml(r);
            refreshStatus();
        });
    }

    function offerCertTrust(m, d, url) {
        m.body.innerHTML = '<div class="sk-wait"><span class="sk-spinner"></span><span>' + esc(t('safekeep.reading_cert', 'Reading the server certificate…')) + '</span></div>';
        api('POST', '/api/safekeep/dest/cert', { url: url, proxyMode: d.proxyMode || 'auto' }).then(function (c) {
            if (!c.success) { m.body.innerHTML = '<p class="sk-queue-err">' + esc(c.message || c.error) + '</p>'; return; }
            m.body.innerHTML = '<p class="setting-desc">' + esc(t('safekeep.cert_untrusted', 'This server uses a certificate the car doesn\'t trust (common for a home NAS). Trust it only if this fingerprint matches your server:')) + '</p>'
                + '<div class="sk-kit">' + esc(c.subject || '') + '<br>SHA-256 ' + esc(c.sha256) + '</div>';
            m.setActions([
                { label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } },
                {
                    label: t('safekeep.trust', 'Trust this certificate'), cls: 'btn-primary', onClick: function (mm) {
                        api('POST', '/api/safekeep/dest/update', { id: d.id, params: { certSha256: c.sha256 } }).then(function () {
                            mm.close();
                            load().then(function () { testDest(d.id); });
                        });
                    }
                }
            ]);
        });
    }

    function trustHostKey(m, d, fp, blob, changed) {
        var intro = changed
            ? '<p class="sk-queue-err">' + esc(t('safekeep.hostkey_changed', 'This server\'s host key has CHANGED since you trusted it. That is expected only if the server was reinstalled or its keys were replaced; otherwise someone may be intercepting the connection. Trust the new key only if you know why it changed:')) + '</p>'
            : '<p class="setting-desc">' + esc(t('safekeep.hostkey_confirm', 'First connection to this SFTP server. Trust it only if this host key matches your server:')) + '</p>';
        m.body.innerHTML = intro + '<div class="sk-kit">' + esc(fp) + '</div>';
        m.setActions([
            { label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } },
            {
                label: t('safekeep.trust_key', 'Trust this host key'), cls: 'btn-primary', onClick: function (mm) {
                    api('POST', '/api/safekeep/dest/update', { id: d.id, params: { hostKeySha256: fp, hostKeyBlob: blob || '' } }).then(function () {
                        mm.close();
                        load().then(function () { testDest(d.id); });
                    });
                }
            }
        ]);
    }

    function reconnect(id) {
        var d = findDest(id);
        if (!d) return;
        if (d.params && d.params.mode === 'nextcloud') return nextcloudForm(id, d.params);
        oauthIntro(d.type, id);
    }

    function nextcloudForm(relinkId, params) {
        var server = '';
        if (params && params.url) server = String(params.url).split('/remote.php')[0];
        openModal({
            title: t('safekeep.connect_x', 'Connect {name}', { name: typeLabel('nextcloud') }),
            body: field('skNcServer', t('safekeep.server_url', 'Server address (https://…)'), server, 'url')
                + settingRow(t('safekeep.proxy', 'Connection'), '', select('skNcProxy', proxyOptions(), 'auto')),
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (m) { m.close(); } },
                {
                    label: t('safekeep.continue', 'Continue'), cls: 'btn-primary', onClick: function (m) {
                        var p = { server: val('skNcServer').trim(), proxyMode: val('skNcProxy') };
                        if (relinkId) p.relinkId = relinkId;
                        m.close();
                        startLink('nextcloud', p);
                    }
                }
            ]
        });
    }

    function saveNewDest(m, payload) {
        api('POST', '/api/safekeep/dest/add', payload).then(function (r) {
            if (!r.success) { toast(r.message || r.error || 'Error', 'error'); return; }
            m.close();
            load().then(function () { testDest(r.id); });
        });
    }

    function webdavForm() {
        openModal({
            title: t('safekeep.connect_x', 'Connect {name}', { name: typeLabel('webdav') }),
            body: field('skDavUrl', t('safekeep.webdav_url', 'WebDAV address (https://nas:5006/folder)'), '', 'url')
                + field('skDavUser', t('safekeep.username', 'Username'), '')
                + field('skDavPass', t('safekeep.password', 'Password or app password'), '', 'password')
                + settingRow(t('safekeep.proxy', 'Connection'), '', select('skDavProxy', proxyOptions(), 'auto')),
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (m) { m.close(); } },
                {
                    label: t('safekeep.save_test', 'Save & test'), cls: 'btn-primary', onClick: function (m) {
                        var url = val('skDavUrl').trim();
                        if (!/^https?:\/\//.test(url)) { toast(t('safekeep.err_url', 'Enter an address starting with https://'), 'warning'); return; }
                        saveNewDest(m, {
                            type: 'webdav', proxyMode: val('skDavProxy'),
                            params: { url: url, username: val('skDavUser').trim() },
                            secrets: { username: val('skDavUser').trim(), password: val('skDavPass') }
                        });
                    }
                }
            ]
        });
    }

    function s3Form() {
        var presetOpts = S3_PRESETS.map(function (p) { return [p.id, p.name || t('safekeep.s3_custom', 'Other S3-compatible')]; });
        var m = openModal({
            title: t('safekeep.connect_x', 'Connect {name}', { name: typeLabel('s3') }),
            body: settingRow(t('safekeep.s3_provider', 'Provider'), '', select('skS3Preset', presetOpts, 'r2'))
                + field('skS3Endpoint', t('safekeep.s3_endpoint', 'Endpoint URL'), S3_PRESETS[0].endpoint, 'url')
                + field('skS3Region', t('safekeep.s3_region', 'Region'), S3_PRESETS[0].region)
                + field('skS3Bucket', t('safekeep.s3_bucket', 'Bucket'), '')
                + field('skS3Prefix', t('safekeep.s3_prefix', 'Folder prefix (optional)'), '')
                + field('skS3Key', t('safekeep.s3_key', 'Access key ID'), '')
                + field('skS3Secret', t('safekeep.s3_secret', 'Secret access key'), '', 'password')
                + settingRow(t('safekeep.s3_path', 'Path-style URLs'), '', toggle('skS3Path', true))
                + settingRow(t('safekeep.s3_writeonly', 'Write-only key'), t('safekeep.s3_writeonly_desc', 'The key can add files but not read or delete them — evidence can\'t be erased from the car. Retention then needs a bucket lifecycle rule.'), toggle('skS3WriteOnly', false))
                + '<div class="info-box-note" style="margin-top:8px;"><svg class="info-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>'
                + '<div class="info-note">' + esc(t('safekeep.s3_least_priv', 'Create a key limited to this one bucket. Then a key taken from the car can\'t touch anything else in your account.')) + '</div></div>'
                + settingRow(t('safekeep.proxy', 'Connection'), '', select('skS3Proxy', proxyOptions(), 'auto')),
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } },
                {
                    label: t('safekeep.save_test', 'Save & test'), cls: 'btn-primary', onClick: function (mm) {
                        var ep = val('skS3Endpoint').trim();
                        if (!/^https?:\/\//.test(ep) || /ACCOUNT_ID|REGION|ENDPOINT/.test(ep)) { toast(t('safekeep.err_endpoint', 'Fill in the endpoint URL'), 'warning'); return; }
                        if (!val('skS3Bucket').trim()) { toast(t('safekeep.err_bucket', 'Enter the bucket name'), 'warning'); return; }
                        saveNewDest(mm, {
                            type: 's3', proxyMode: val('skS3Proxy'),
                            params: {
                                endpoint: ep, region: val('skS3Region').trim() || 'us-east-1', bucket: val('skS3Bucket').trim(),
                                prefix: val('skS3Prefix').trim(), pathStyle: checked('skS3Path'), writeOnly: checked('skS3WriteOnly'),
                                provider: val('skS3Preset')
                            },
                            secrets: { accessKeyId: val('skS3Key').trim(), secretAccessKey: val('skS3Secret') }
                        });
                    }
                }
            ]
        });
        $('skS3Preset').onchange = function () {
            var id = this.value;
            S3_PRESETS.forEach(function (p) {
                if (p.id !== id) return;
                $('skS3Endpoint').value = p.endpoint;
                $('skS3Region').value = p.region;
                $('skS3Path').checked = p.path;
            });
        };
        return m;
    }

    function sftpForm() {
        openModal({
            title: t('safekeep.connect_x', 'Connect {name}', { name: typeLabel('sftp') }),
            body: field('skSftpHost', t('safekeep.host', 'Host (NAS address)'), '')
                + field('skSftpPort', t('safekeep.port', 'Port'), '22', 'number')
                + field('skSftpUser', t('safekeep.username', 'Username'), '')
                + field('skSftpPass', t('safekeep.password_opt', 'Password (or use a key below)'), '', 'password')
                + textarea('skSftpKey', t('safekeep.private_key', 'Private key (RSA or ECDSA, optional)'), '')
                + field('skSftpPassphrase', t('safekeep.passphrase', 'Key passphrase (optional)'), '', 'password')
                + field('skSftpRoot', t('safekeep.root_path', 'Folder on the server (e.g. /volume1/dashcam)'), ''),
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (m) { m.close(); } },
                {
                    label: t('safekeep.save_test', 'Save & test'), cls: 'btn-primary', onClick: function (m) {
                        if (!val('skSftpHost').trim()) { toast(t('safekeep.err_host', 'Enter the host'), 'warning'); return; }
                        var sec = { username: val('skSftpUser').trim() };
                        if (val('skSftpPass')) sec.password = val('skSftpPass');
                        if (val('skSftpKey').trim()) { sec.privateKey = val('skSftpKey').trim(); sec.passphrase = val('skSftpPassphrase'); }
                        saveNewDest(m, {
                            type: 'sftp',
                            params: { host: val('skSftpHost').trim(), port: parseInt(val('skSftpPort'), 10) || 22, rootPath: val('skSftpRoot').trim() },
                            secrets: sec
                        });
                    }
                }
            ]
        });
    }

    function editDest(id) {
        var d = findDest(id);
        if (!d) return;
        var ret = d.retention || {};
        var enc = d.encryption || {};
        var encOn = enc.mode === 'age' && enc.recipients && enc.recipients.length;
        var body = field('skEdName', t('safekeep.name', 'Name'), d.name)
            + settingRow(t('safekeep.enabled', 'Enabled'), '', toggle('skEdEnabled', d.enabled !== false))
            + settingRow(t('safekeep.proxy', 'Connection'), '', select('skEdProxy', proxyOptions(), d.proxyMode || 'auto'))
            + settingRow(t('safekeep.layout', 'Folder layout'), t('safekeep.layout_desc', 'Readable: dated folders you can browse. Compact: lifecycle-friendly prefixes.'),
                select('skEdLayout', [['human', t('safekeep.layout_human', 'Readable')], ['machine', t('safekeep.layout_machine', 'Compact')]], d.layout || (d.type === 's3' ? 'machine' : 'human')))
            + '<div class="sk-sub">' + esc(t('safekeep.retention', 'Keep in storage')) + '</div>'
            + settingRow(t('safekeep.keep_events', 'Events & saved clips'), '', select('skEdEvents', [['30', '30 ' + t('safekeep.days', 'days')], ['90', '90 ' + t('safekeep.days', 'days')], ['180', '180 ' + t('safekeep.days', 'days')], ['365', '1 ' + t('safekeep.year', 'year')], ['0', t('safekeep.forever', 'Forever')]], ret.eventsDays == null ? 90 : ret.eventsDays))
            + settingRow(t('safekeep.keep_loop', 'Dashcam recordings'), '', select('skEdArchive', [['7', '7 ' + t('safekeep.days', 'days')], ['14', '14 ' + t('safekeep.days', 'days')], ['30', '30 ' + t('safekeep.days', 'days')], ['90', '90 ' + t('safekeep.days', 'days')], ['0', t('safekeep.forever', 'Forever')]], ret.archiveDays == null ? 14 : ret.archiveDays))
            + settingRow(t('safekeep.max_quota', 'Make room when storage is'), t('safekeep.max_quota_desc', 'Oldest dashcam footage goes first; events only if their retention allows.'), select('skEdQuota', [['70', '70% full'], ['80', '80% full'], ['85', '85% full'], ['90', '90% full'], ['95', '95% full']], ret.maxQuotaPct || 85))
            + '<div class="sk-sub">' + esc(t('safekeep.encryption', 'Encryption')) + '</div>'
            + settingRow(t('safekeep.encrypt_uploads', 'Encrypt uploads (age)'), t('safekeep.encrypt_desc', 'Only your secret key can open them; the car keeps just the public key. Encrypted videos can\'t be previewed in the storage app.'), toggle('skEdEncrypt', encOn))
            + '<div id="skEdEncBox" style="' + (encOn ? '' : 'display:none;') + '">'
            + field('skEdRecipient', t('safekeep.recipient', 'Public key (age1…)'), encOn ? enc.recipients[0] : '')
            + '<div class="byd-action-row"><button class="btn btn-secondary" id="skEdGen">' + esc(t('safekeep.generate_key', 'Generate a new key')) + '</button></div></div>'
            + credentialFields(d);
        var m = openModal({
            title: t('safekeep.edit_x', 'Edit {name}', { name: d.name }),
            body: body,
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (mm) { mm.close(); } },
                {
                    label: t('safekeep.save', 'Save'), cls: 'btn-primary', onClick: function (mm) {
                        var patch = {
                            id: d.id, name: val('skEdName').trim() || d.name, enabled: checked('skEdEnabled'),
                            proxyMode: val('skEdProxy'), layout: val('skEdLayout'),
                            retention: { eventsDays: parseInt(val('skEdEvents'), 10), archiveDays: parseInt(val('skEdArchive'), 10), maxQuotaPct: parseInt(val('skEdQuota'), 10), settingsKeep: (ret.settingsKeep || 10) }
                        };
                        if (checked('skEdEncrypt')) {
                            var rcp = val('skEdRecipient').trim();
                            if (rcp.indexOf('age1') !== 0) { toast(t('safekeep.err_recipient', 'Enter or generate an age public key'), 'warning'); return; }
                            patch.encryption = { mode: 'age', recipients: [rcp] };
                        } else {
                            patch.encryption = { mode: 'off' };
                        }
                        var sec = collectCredentials(d);
                        if (sec) patch.secrets = sec;
                        api('POST', '/api/safekeep/dest/update', patch).then(function (r) {
                            if (!r.success) { toast(r.message || r.error || 'Error', 'error'); return; }
                            mm.close();
                            toast(t('safekeep.saved', 'Saved'), 'success');
                            load();
                        });
                    }
                }
            ]
        });
        $('skEdEncrypt').onchange = function () { $('skEdEncBox').style.display = this.checked ? '' : 'none'; };
        $('skEdGen').onclick = function () { generateKey(function (recipient) { $('skEdRecipient').value = recipient; }); };
        return m;
    }

    function credentialFields(d) {
        var h = '';
        if (d.type === 's3') {
            h = field('skEdS3Key', t('safekeep.s3_key', 'Access key ID') + (d.accessKeyHint ? ' (' + d.accessKeyHint + ')' : ''), '')
                + field('skEdS3Secret', t('safekeep.s3_secret_keep', 'Secret access key (leave empty to keep)'), '', 'password');
        } else if (d.type === 'webdav' && !(d.params && d.params.mode === 'nextcloud')) {
            h = field('skEdPass', t('safekeep.password_keep', 'Password (leave empty to keep)'), '', 'password');
        } else if (d.type === 'sftp') {
            h = field('skEdPass', t('safekeep.password_keep', 'Password (leave empty to keep)'), '', 'password')
                + textarea('skEdKey', t('safekeep.private_key_keep', 'Private key (leave empty to keep)'), '');
        }
        return h ? '<div class="sk-sub">' + esc(t('safekeep.credentials', 'Credentials')) + '</div>' + h : '';
    }

    function collectCredentials(d) {
        var sec = {};
        var any = false;
        if (d.type === 's3') {
            if (val('skEdS3Key').trim()) { sec.accessKeyId = val('skEdS3Key').trim(); any = true; }
            if (val('skEdS3Secret')) { sec.secretAccessKey = val('skEdS3Secret'); any = true; }
        } else if ($('skEdPass') && val('skEdPass')) { sec.password = val('skEdPass'); any = true; }
        if ($('skEdKey') && val('skEdKey').trim()) { sec.privateKey = val('skEdKey').trim(); any = true; }
        return any ? sec : null;
    }

    /** Recovery kit: the secret key is shown once and never stored on the car. */
    function generateKey(onRecipient) {
        api('POST', '/api/safekeep/keys/generate', {}).then(function (r) {
            if (!r.success) { toast(r.message || 'Error', 'error'); return; }
            var body = '<p class="setting-desc">' + esc(t('safekeep.kit_intro', 'This is your secret key. It is shown once and is NOT kept on the car. Without it, encrypted backups can never be opened — save it in a password manager or print it.')) + '</p>'
                + '<div class="sk-kit" id="skKitId">' + esc(r.identity) + '</div>'
                + '<div class="byd-action-row" style="margin:10px 0;"><button class="btn btn-secondary" id="skKitCopy">' + esc(t('safekeep.copy', 'Copy')) + '</button>'
                + '<button class="btn btn-secondary" id="skKitSave">' + esc(t('safekeep.download_kit', 'Download recovery kit')) + '</button></div>'
                + '<div class="sk-muted">' + esc(t('safekeep.kit_public', 'Public key (stored on the car):')) + '</div><div class="sk-kit">' + esc(r.recipient) + '</div>'
                + '<label class="sk-row-inline" style="margin-top:12px;"><input type="checkbox" id="skKitAck"> <span class="setting-desc">' + esc(t('safekeep.kit_ack', 'I have saved my secret key')) + '</span></label>';
            var m = openModal({
                title: t('safekeep.kit_title', 'Your recovery kit'),
                body: body,
                sticky: true,
                actions: [{
                    label: t('safekeep.done', 'Done'), cls: 'btn-primary', onClick: function (mm) {
                        if (!checked('skKitAck')) { toast(t('safekeep.kit_ack_needed', 'Confirm you saved the key first'), 'warning'); return; }
                        mm.close();
                        onRecipient(r.recipient);
                    }
                }]
            });
            $('skKitCopy').onclick = function () { copyText(r.identity); };
            $('skKitSave').onclick = function () {
                var text = '# OverDrive SafeKeep recovery kit\n# Keep this private. Decrypt with: age -d -i this-file backup.age\n# public key: ' + r.recipient + '\n' + r.identity + '\n';
                // In the app a blob link does nothing: the bridge writes the file to Download/OverDrive.
                if (window.AndroidBridge && typeof window.AndroidBridge.saveTextFile === 'function') {
                    var path = '';
                    try { path = window.AndroidBridge.saveTextFile('overdrive-safekeep-key.txt', text) || ''; } catch (e) { path = ''; }
                    if (path) toast(t('safekeep.kit_saved', 'Saved to {path}', { path: path }), 'success');
                    else { copyText(r.identity); toast(t('safekeep.kit_save_failed', 'Could not save the file'), 'warning'); }
                    return;
                }
                try {
                    var blob = new Blob([text], { type: 'text/plain' });
                    var a = document.createElement('a');
                    a.href = URL.createObjectURL(blob);
                    a.download = 'overdrive-safekeep-key.txt';
                    document.body.appendChild(a);
                    a.click();
                    setTimeout(function () { URL.revokeObjectURL(a.href); a.parentNode.removeChild(a); }, 1000);
                } catch (e) {
                    copyText(r.identity);
                }
            };
            return m;
        });
    }

    function removeDest(id) {
        var d = findDest(id);
        if (!d) return;
        confirm(t('safekeep.remove_x', 'Remove {name}?', { name: d.name }),
            t('safekeep.remove_body', 'SafeKeep stops uploading there and forgets its sign-in. Files already uploaded stay in your storage.'),
            t('safekeep.remove', 'Remove'), true).then(function (yes) {
            if (!yes) return;
            api('POST', '/api/safekeep/dest/remove', { id: id }).then(function (r) {
                toast(r.success ? t('safekeep.removed', 'Removed') : (r.message || 'Error'), r.success ? 'success' : 'error');
                load();
            });
        });
    }

    // ── what & when ─────────────────────────────────────────────────────────

    function buildLaneState() {
        var routes = (state.cfg && state.cfg.routes) || [];
        state.lanes = {};
        LANES.forEach(function (l) {
            var r = null;
            for (var i = 0; i < routes.length; i++) if (routes[i].lane === l.id) { r = routes[i]; break; }
            state.lanes[l.id] = r ? JSON.parse(JSON.stringify(r)) : null;
        });
    }

    function laneDefaults(l) {
        var ids = destinations().map(function (d) { return d.id; });
        if (l.id === 'archive') {
            var non = destinations().filter(function (d) { return !CONSUMER[d.type]; }).map(function (d) { return d.id; });
            if (non.length) ids = non;
        }
        return { lane: l.id, dest: ids, rule: { preset: l.preset }, backfillDays: l.id === 'evidence' ? 7 : 0 };
    }

    function renderLanes() {
        var root = $('skLanes');
        if (!root) return;
        var h = '';
        LANES.forEach(function (l) {
            var r = state.lanes[l.id];
            var on = !!r;
            var rule = (r && r.rule) || { preset: l.preset };
            var preset = rule.preset || l.preset;
            h += '<div class="card" id="skLane_' + l.id + '"><div class="card-body">'
                + '<div class="setting-row"><div class="setting-info"><div class="setting-name" style="font-size:15px;font-weight:700;">' + esc(laneTitle(l.id)) + '</div>'
                + '<div class="setting-desc">' + esc(laneDesc(l.id)) + '</div></div>'
                + '<div class="setting-control">' + toggle('skLaneOn_' + l.id, on, 'SK._laneToggle(\'' + l.id + '\', this.checked)') + '</div></div>';
            if (on) {
                h += '<div class="sk-sub">' + esc(t('safekeep.send_to', 'Send to')) + '</div><div class="sk-chip-row">';
                destinations().forEach(function (d) {
                    var active = (r.dest || []).indexOf(d.id) >= 0;
                    h += '<span class="sk-chip' + (active ? ' active' : '') + '" data-sk="_laneDest" data-a="' + l.id + '" data-b="' + esc(d.id) + '">' + esc(d.name) + '</span>';
                });
                if (!destinations().length) h += '<span class="sk-muted">' + esc(t('safekeep.no_storage', 'No storage connected yet')) + '</span>';
                h += '</div>';
                if (l.id === 'archive' && (r.dest || []).some(function (id) { var d = findDest(id); return d && CONSUMER[d.type]; })) {
                    h += '<div class="info-box-warning" style="margin-top:10px;"><svg class="info-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>'
                        + '<div class="info-note">' + esc(t('safekeep.archive_warning', 'Dashcam footage is about 1.3 GB per hour — a free 15 GB account fills in a day or two of driving. A NAS or S3 bucket is a better home for it.')) + '</div></div>';
                }
                if (l.id === 'evidence') {
                    h += '<div class="sk-sub">' + esc(t('safekeep.include', 'Include')) + '</div>'
                        + settingRow(t('safekeep.inc_sentry', 'Sentry events'), '', toggle('skInc_sentry', r.includeSentry !== false))
                        + settingRow(t('safekeep.inc_proximity', 'Proximity alerts'), '', toggle('skInc_proximity', r.includeProximity !== false))
                        + settingRow(t('safekeep.inc_replay', 'Clips you save'), '', toggle('skInc_replay', r.includeReplay !== false));
                }
                if (l.id === 'archive') {
                    h += settingRow(t('safekeep.inc_dvr', 'Include OEM Dashcam clips (dvr_*.mp4)'), '', toggle('skInc_dvr', r.includeOemDashcam !== false));
                }
                h += '<div class="sk-sub">' + esc(t('safekeep.when', 'When')) + '</div>'
                    + '<div class="mode-selector" role="radiogroup">';
                PRESETS.forEach(function (p) {
                    h += '<label class="mode-option"><input type="radio" name="skPreset_' + l.id + '" value="' + p + '"' + (p === preset ? ' checked' : '')
                        + ' onchange="SK._lanePreset(\'' + l.id + '\', this.value)"><div class="mode-card"><span class="mode-body"><span class="mode-name">' + esc(presetTitle(p)) + '</span>'
                        + '<span class="mode-desc">' + esc(presetDesc(p)) + '</span></span><span class="mode-radio" aria-hidden="true"></span></div></label>';
                });
                h += '</div>';
                if (l.id === 'evidence' && preset !== 'custom' && preset !== 'manual' && preset !== 'wifi_or_mobile') {
                    var snap = rule.network && rule.network.mobile === 'snapshots';
                    h += settingRow(t('safekeep.snapshots_mobile', 'Send snapshots over mobile data'), t('safekeep.snapshots_mobile_desc', 'The event photo and timeline (a few KB) go right away; the video waits for Wi-Fi.'), toggle('skSnapMobile', snap));
                }
                if (preset === 'custom') h += customRuleHtml(l.id, rule);
                if (l.backfill) {
                    h += settingRow(t('safekeep.backfill', 'Also back up existing clips from the last'), '',
                        select('skBackfill_' + l.id, [['0', t('safekeep.none', 'None')], ['1', '1 ' + t('safekeep.day', 'day')], ['7', '7 ' + t('safekeep.days', 'days')], ['30', '30 ' + t('safekeep.days', 'days')]], r.backfillDays == null ? (l.id === 'evidence' ? 7 : 0) : r.backfillDays));
                }
            }
            h += '</div></div>';
        });
        root.innerHTML = h;
        LANES.forEach(function (l) { if (state.lanes[l.id] && state.lanes[l.id].rule && state.lanes[l.id].rule.preset === 'custom') renderConditions(l.id); });
    }

    function customRuleHtml(laneId, rule) {
        var net = rule.network || {};
        var w = rule.window || null;
        var days = (w && w.days) || [];
        var h = '<div class="sk-sub">' + esc(t('safekeep.custom_rule', 'Custom rule')) + '</div>'
            + settingRow(t('safekeep.wifi', 'Wi-Fi'), '', select('skCWifi_' + laneId, [['any', t('safekeep.wifi_any', 'Any Wi-Fi')], ['home', t('safekeep.wifi_home', 'Home Wi-Fi only')], ['off', t('safekeep.wifi_off', 'Never')]], net.wifi || 'any'))
            + settingRow(t('safekeep.mobile', 'Mobile data'), '', select('skCMobile_' + laneId, [['off', t('safekeep.mobile_off', 'Never')], ['snapshots', t('safekeep.mobile_snapshots', 'Snapshots only')], ['on', t('safekeep.mobile_on', 'Yes, within the allowance')]], net.mobile || 'off'))
            + settingRow(t('safekeep.vehicle', 'Car'), '', select('skCVehicle_' + laneId, [['any', t('safekeep.vehicle_any', 'Any time')], ['parked', t('safekeep.vehicle_parked', 'Parked')], ['driving', t('safekeep.vehicle_driving', 'Driving')]], rule.vehicle || 'any'))
            + settingRow(t('safekeep.charging_only', 'Only while charging'), '', toggle('skCCharge_' + laneId, rule.charging === 'only'))
            + settingRow(t('safekeep.window', 'Time window'), '', toggle('skCWin_' + laneId, !!w, 'SK._winToggle(\'' + laneId + '\', this.checked)'))
            + '<div id="skCWinBox_' + laneId + '" style="' + (w ? '' : 'display:none;') + '">'
            + '<div class="sk-row-inline">' + field('skCFrom_' + laneId, t('safekeep.from', 'From'), minToTime(w ? w.from : 0), 'time')
            + field('skCTo_' + laneId, t('safekeep.to', 'To'), minToTime(w ? w.to : 360), 'time') + '</div>'
            + '<div class="sk-chip-row" id="skCDays_' + laneId + '">';
        DAYS.forEach(function (d) {
            h += '<span class="sk-chip' + (days.length === 0 || days.indexOf(d) >= 0 ? ' active' : '') + '" data-day="' + d + '" onclick="this.classList.toggle(\'active\')">' + esc(t('safekeep.day_' + d, d.charAt(0).toUpperCase() + d.slice(1, 3))) + '</span>';
        });
        h += '</div></div>'
            + settingRow(t('safekeep.max_mbps', 'Speed limit for this item (Mbps, 0 = none)'), '', '<input type="number" class="text-input" id="skCMbps_' + laneId + '" min="0" max="1000" step="0.5" value="' + esc(String(Number(rule.maxMbps) || 0)) + '" style="width:96px;min-width:96px;">')
            + '<div class="sk-sub">' + esc(t('safekeep.conditions', 'Extra conditions')) + '</div>'
            + '<div class="setting-desc" style="margin-bottom:8px;">' + esc(t('safekeep.conditions_desc', 'The same signals automations use, e.g. location is Home and battery is above 30%.')) + '</div>'
            + settingRow(t('safekeep.match', 'Match'), '', select('skCLogic_' + laneId, [['AND', t('safekeep.match_all', 'All conditions')], ['OR', t('safekeep.match_any', 'Any condition')]], (rule.advanced && rule.advanced.logic) || 'AND'))
            + '<div id="skConds_' + laneId + '"></div>'
            + '<div class="byd-action-row"><button class="btn btn-secondary" onclick="SK._addCond(\'' + laneId + '\')">' + esc(t('safekeep.add_condition', 'Add condition')) + '</button></div>';
        return h;
    }

    function minToTime(m) {
        m = Math.max(0, Math.min(1439, Number(m) || 0));
        var hh = Math.floor(m / 60), mm = m % 60;
        return (hh < 10 ? '0' : '') + hh + ':' + (mm < 10 ? '0' : '') + mm;
    }

    function timeToMin(s) {
        var p = String(s || '0:0').split(':');
        return (parseInt(p[0], 10) || 0) * 60 + (parseInt(p[1], 10) || 0);
    }

    function condLabel(type) {
        var m = {
            power: t('safekeep.c_power', 'Power'), gear: t('safekeep.c_gear', 'Gear'), speed: t('safekeep.c_speed', 'Speed'),
            chargingState: t('safekeep.c_charging', 'Charging'), wifiState: t('safekeep.c_wifi_state', 'Wi-Fi'),
            wifiSsid: t('safekeep.c_wifi_ssid', 'Wi-Fi name'), locationZone: t('safekeep.c_zone', 'Location zone'),
            time: t('safekeep.c_time', 'Time of day'), day: t('safekeep.c_day', 'Day'),
            aux12vVoltage: t('safekeep.c_12v', '12 V battery (×0.1 V)'), batteryLevel: t('safekeep.c_battery', 'Battery %')
        };
        return m[type] || type;
    }

    function renderConditions(laneId) {
        var box = $('skConds_' + laneId);
        if (!box) return;
        var r = state.lanes[laneId];
        var conds = (r && r.rule && r.rule.advanced && r.rule.advanced.conditions) || [];
        var h = '';
        conds.forEach(function (c, i) {
            var def = COND[c.type] || COND.power;
            var typeOpts = Object.keys(COND).map(function (k) { return [k, condLabel(k)]; });
            var cmpOpts = (def.kind === 'enum' || def.kind === 'string')
                ? [['eq', t('safekeep.is', 'is')], ['neq', t('safekeep.is_not', 'is not')]]
                : [['eq', '='], ['neq', '≠'], ['gt', '>'], ['gte', '≥'], ['lt', '<'], ['lte', '≤']];
            var valHtml;
            if (def.kind === 'enum') valHtml = select('skCV_' + laneId + '_' + i, def.opts, c.value);
            else if (def.kind === 'time') valHtml = '<input type="time" class="text-input" id="skCV_' + laneId + '_' + i + '" value="' + minToTime(c.value) + '">';
            else if (def.kind === 'int') valHtml = '<input type="number" class="text-input" id="skCV_' + laneId + '_' + i + '" min="' + def.min + '" max="' + def.max + '" value="' + esc(c.value == null ? def.min : c.value) + '">';
            else valHtml = '<input class="text-input" id="skCV_' + laneId + '_' + i + '" value="' + esc(c.value || '') + '">';
            h += '<div class="sk-cond">'
                + '<span class="sk-cond-type">' + select('skCT_' + laneId + '_' + i, typeOpts, c.type).replace('<select ', '<select onchange="SK._condType(\'' + laneId + '\',' + i + ', this.value)" ') + '</span>'
                + '<span class="sk-cond-cmp">' + select('skCC_' + laneId + '_' + i, cmpOpts, c.comparator || 'eq') + '</span>'
                + '<span class="sk-cond-val">' + valHtml + '</span>'
                + '<button class="safe-zone-icon-btn is-danger" data-sk="_removeCond" data-a="' + laneId + '" data-b="' + i + '" title="' + esc(t('safekeep.remove', 'Remove')) + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>'
                + '</div>';
        });
        box.innerHTML = h;
    }

    function captureConditions(laneId) {
        var r = state.lanes[laneId];
        if (!r || !r.rule || !r.rule.advanced) return;
        var conds = r.rule.advanced.conditions || [];
        conds.forEach(function (c, i) {
            var def = COND[c.type] || COND.power;
            c.comparator = val('skCC_' + laneId + '_' + i) || 'eq';
            var raw = val('skCV_' + laneId + '_' + i);
            if (def.kind === 'int') c.value = parseInt(raw, 10) || 0;
            else if (def.kind === 'time') c.value = timeToMin(raw);
            else c.value = raw;
            if (def.vars) c.variables = def.vars;
            else delete c.variables;
        });
        r.rule.advanced.logic = val('skCLogic_' + laneId) || 'AND';
    }

    function captureLane(laneId) {
        var r = state.lanes[laneId];
        if (!r) return;
        r.rule = r.rule || {};
        var preset = r.rule.preset;
        if (laneId === 'evidence') {
            if ($('skInc_sentry')) r.includeSentry = checked('skInc_sentry');
            if ($('skInc_proximity')) r.includeProximity = checked('skInc_proximity');
            if ($('skInc_replay')) r.includeReplay = checked('skInc_replay');
            if ($('skSnapMobile')) {
                r.rule.network = r.rule.network || {};
                r.rule.network.mobile = checked('skSnapMobile') ? 'snapshots' : 'off';
            }
        }
        if (laneId === 'archive' && $('skInc_dvr')) r.includeOemDashcam = checked('skInc_dvr');
        if ($('skBackfill_' + laneId)) r.backfillDays = parseInt(val('skBackfill_' + laneId), 10) || 0;
        if (preset === 'custom' && $('skCWifi_' + laneId)) {
            r.rule.network = { wifi: val('skCWifi_' + laneId), mobile: val('skCMobile_' + laneId) };
            r.rule.vehicle = val('skCVehicle_' + laneId);
            r.rule.charging = checked('skCCharge_' + laneId) ? 'only' : 'any';
            if (checked('skCWin_' + laneId)) {
                var days = [];
                var chips = document.querySelectorAll('#skCDays_' + laneId + ' .sk-chip.active');
                for (var i = 0; i < chips.length; i++) days.push(chips[i].getAttribute('data-day'));
                r.rule.window = { from: timeToMin(val('skCFrom_' + laneId)), to: timeToMin(val('skCTo_' + laneId)), days: days.length === 7 ? [] : days };
            } else {
                delete r.rule.window;
            }
            r.rule.maxMbps = parseFloat(val('skCMbps_' + laneId)) || 0;
            captureConditions(laneId);
            if (r.rule.advanced && (!r.rule.advanced.conditions || !r.rule.advanced.conditions.length)) delete r.rule.advanced;
        }
    }

    function captureAllLanes() {
        LANES.forEach(function (l) { if (state.lanes[l.id]) captureLane(l.id); });
    }

    function saveLanes() {
        if (!requireOn()) return;
        captureAllLanes();
        var routes = [];
        LANES.forEach(function (l) {
            var r = state.lanes[l.id];
            if (r && r.dest && r.dest.length) routes.push(r);
        });
        api('POST', '/api/safekeep/config', { routes: routes }).then(function (r) {
            if (!r.success) {
                toast(r.error === 'bad_conditions' ? t('safekeep.err_conditions', 'One of the conditions is incomplete') : (r.message || r.error || 'Error'), 'error');
                return;
            }
            toast(t('safekeep.saved', 'Saved'), 'success');
            load();
        });
    }

    // ── limits ───────────────────────────────────────────────────────────────

    function renderLimits() {
        var c = state.cfg || {};
        var l = c.limits || {};
        var n = c.notify || {};
        state.ssids = (l.homeSsids || []).slice();
        state.webhooks = JSON.parse(JSON.stringify(c.webhooks || []));
        renderSsids();
        setv('skMobileCap', l.mobileCapMb == null ? 500 : l.mobileCapMb);
        setv('skResetDay', l.mobileResetDay || 1);
        setv('skDrivingMbps', l.drivingMaxMbps == null ? 2 : l.drivingMaxMbps);
        setv('skMobileMbps', l.mobileMaxMbps == null ? 1 : l.mobileMaxMbps);
        setv('skWifiMbps', l.wifiMaxMbps || 0);
        setc('skPauseLive', l.pauseForLiveView !== false);
        setToggle('skConcurrency', String(l.maxConcurrent || 2));
        setv('skParkedMinutes', l.parkedMaxMinutes == null ? 60 : l.parkedMaxMinutes);
        setv('skMin12v', l.min12vDeci || 122);
        setv('skVehicleName', c.vehicleName || 'My Car');
        setv('skPinGb', l.pinMaxGb || 4);
        setv('skLockGb', l.lockMaxGb || 8);
        setc('skNotifyAuth', n.needsAuth !== false);
        setc('skNotifyQuota', n.quota !== false);
        setc('skNotifyMobile', n.mobileCap !== false);
        setc('skNotifySaved', !!n.evidenceSaved);
        renderWebhooks();
    }

    function setv(id, v) { var el = $(id); if (el) el.value = String(v); }
    function setc(id, v) { var el = $(id); if (el) el.checked = !!v; }

    function setToggle(groupId, value) {
        var g = $(groupId);
        if (!g) return;
        var btns = g.querySelectorAll('.btn-toggle');
        for (var i = 0; i < btns.length; i++) btns[i].classList.toggle('active', btns[i].getAttribute('data-value') === String(value));
    }

    function toggleValue(groupId) {
        var a = document.querySelector('#' + groupId + ' .btn-toggle.active');
        return a ? a.getAttribute('data-value') : null;
    }

    function renderSsids() {
        var box = $('skHomeSsids');
        if (!box) return;
        if (!state.ssids.length) { box.innerHTML = '<span class="sk-muted">' + esc(t('safekeep.no_ssids', 'No home networks yet')) + '</span>'; return; }
        box.innerHTML = state.ssids.map(function (s, i) {
            return '<span class="sk-chip active" onclick="SK._removeSsid(' + i + ')">' + esc(s) + ' <span class="sk-x">×</span></span>';
        }).join('');
    }

    function addSsid() {
        var v = (val('skSsidInput') || '').trim();
        if (!v) return;
        if (state.ssids.indexOf(v) < 0) state.ssids.push(v);
        setv('skSsidInput', '');
        renderSsids();
    }

    function addCurrentSsid() {
        var n = (state.status && state.status.network) || {};
        if (n.transport !== 'wifi' || !n.ssid) { toast(t('safekeep.not_on_wifi', 'The car isn\'t on Wi-Fi right now'), 'warning'); return; }
        if (state.ssids.indexOf(n.ssid) < 0) state.ssids.push(n.ssid);
        renderSsids();
    }

    function renderWebhooks() {
        var box = $('skWebhooks');
        if (!box) return;
        if (!state.webhooks.length) { box.innerHTML = '<span class="sk-muted">' + esc(t('safekeep.no_webhooks', 'No webhooks')) + '</span>'; return; }
        box.innerHTML = state.webhooks.map(function (w, i) {
            var ev = w.events || [];
            return '<div class="sk-queue-row"><div class="sk-queue-info"><div class="sk-queue-name">' + esc(w.url) + '</div><div class="sk-queue-meta">'
                + esc(ev.map(function (e) { return e === 'trip.finished' ? t('safekeep.hook_trip', 'Trip finished') : t('safekeep.hook_clip', 'Clip saved'); }).join(' · ')) + '</div></div>'
                + '<button class="safe-zone-icon-btn is-danger" onclick="SK._removeWebhook(' + i + ')" title="' + esc(t('safekeep.remove', 'Remove')) + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button></div>';
        }).join('');
    }

    function addWebhook() {
        openModal({
            title: t('safekeep.add_webhook', 'Add webhook'),
            body: field('skHookUrl', t('safekeep.hook_url', 'URL (https://…)'), '', 'url')
                + settingRow(t('safekeep.hook_trip', 'Trip finished'), '', toggle('skHookTrip', true))
                + settingRow(t('safekeep.hook_clip', 'Clip saved'), '', toggle('skHookClip', true))
                + field('skHookSecret', t('safekeep.hook_secret', 'Signing secret (optional)'), '', 'password'),
            actions: [
                { label: t('common.cancel', 'Cancel'), onClick: function (m) { m.close(); } },
                {
                    label: t('safekeep.add', 'Add'), cls: 'btn-primary', onClick: function (m) {
                        var url = val('skHookUrl').trim();
                        if (!/^https?:\/\//.test(url)) { toast(t('safekeep.err_url', 'Enter an address starting with https://'), 'warning'); return; }
                        var ev = [];
                        if (checked('skHookTrip')) ev.push('trip.finished');
                        if (checked('skHookClip')) ev.push('evidence.saved');
                        var hook = { url: url, events: ev };
                        var secret = val('skHookSecret');
                        // Sent with Save and stored encrypted by the car; the config never holds it.
                        if (secret) hook.secret = secret;
                        state.webhooks.push(hook);
                        m.close();
                        renderWebhooks();
                        toast(t('safekeep.hook_added', 'Added — tap Save to keep it'), 'info');
                    }
                }
            ]
        });
    }

    function saveLimits() {
        var limits = {
            mobileCapMb: Math.max(0, parseInt(val('skMobileCap'), 10) || 0),
            mobileResetDay: parseInt(val('skResetDay'), 10) || 1,
            drivingMaxMbps: Math.max(0, parseFloat(val('skDrivingMbps')) || 0),
            mobileMaxMbps: Math.max(0, parseFloat(val('skMobileMbps')) || 0),
            wifiMaxMbps: Math.max(0, parseFloat(val('skWifiMbps')) || 0),
            pauseForLiveView: checked('skPauseLive'),
            maxConcurrent: parseInt(toggleValue('skConcurrency'), 10) || 2,
            parkedMaxMinutes: parseInt(val('skParkedMinutes'), 10) || 0,
            min12vDeci: parseInt(val('skMin12v'), 10) || 122,
            homeSsids: state.ssids,
            pinMaxGb: Math.max(0.5, parseFloat(val('skPinGb')) || 4),
            lockMaxGb: Math.max(0.5, parseFloat(val('skLockGb')) || 8)
        };
        var notify = { needsAuth: checked('skNotifyAuth'), quota: checked('skNotifyQuota'), mobileCap: checked('skNotifyMobile'), evidenceSaved: checked('skNotifySaved') };
        api('POST', '/api/safekeep/config', { limits: limits, notify: notify, vehicleName: val('skVehicleName').trim() || 'My Car', webhooks: state.webhooks }).then(function (r) {
            toast(r.success ? t('safekeep.saved', 'Saved') : (r.message || r.error || 'Error'), r.success ? 'success' : 'error');
            if (r.success) load();
        });
    }

    // ── activity ─────────────────────────────────────────────────────────────

    function queueFilter(f) {
        state.queueFilter = f;
        setToggle('skQueueFilter', f);
        loadQueue();
    }

    function loadQueue() {
        var box = $('skQueue');
        if (!box || !(state.status && state.status.enabled)) {
            if (box) box.innerHTML = '<span class="sk-muted">' + esc(t('safekeep.turn_on_first', 'Turn SafeKeep on first')) + '</span>';
            return;
        }
        api('GET', '/api/safekeep/queue?state=' + encodeURIComponent(state.queueFilter) + '&limit=100').then(function (r) {
            if (!r.success) { box.innerHTML = ''; return; }
            var items = r.items || [];
            if (!items.length) { box.innerHTML = '<div class="safe-zone-empty">' + esc(t('safekeep.queue_empty', 'Nothing here')) + '</div>'; return; }
            box.innerHTML = items.map(function (it) {
                var dn = it.dest;
                destinations().forEach(function (d) { if (d.id === it.dest) dn = d.name; });
                var st = { PENDING: t('safekeep.q_pending', 'Queued'), ACTIVE: t('safekeep.q_active', 'Uploading'), FAILED: t('safekeep.q_failed', 'Failed'), DONE: t('safekeep.q_done', 'Saved') }[it.state] || it.state;
                var meta = [laneTitle(it.lane), dn, fmtBytes(it.size), st];
                if (it.state === 'ACTIVE' && it.size) meta.push(Math.round(it.sent * 100 / it.size) + '%');
                if (it.nextAttemptAt) meta.push(t('safekeep.next_try', 'next try {time}', { time: fmtTime(it.nextAttemptAt) }));
                var err = it.state === 'FAILED' || it.errorClass ? (it.errorClass && reasonText(it.errorClass) !== it.errorClass.replace(/_/g, ' ') ? reasonText(it.errorClass) : (it.error || '')) : '';
                return '<div class="sk-queue-row"><div class="sk-queue-info"><div class="sk-queue-name">' + esc(it.name) + '</div>'
                    + '<div class="sk-queue-meta">'
                    + (it.encrypted ? '<span title="' + esc(t('safekeep.encrypted', 'Encrypted')) + '">' + SK_LOCK_ICON + '</span>' : '')
                    + esc(meta.join(' · ')) + '</div>'
                    + (err ? '<div class="sk-queue-err">' + esc(err) + '</div>' : '') + '</div>'
                    + (it.state === 'FAILED' || it.state === 'PENDING'
                        ? '<button class="btn btn-secondary" onclick="SK._retry(' + Number(it.id) + ')">' + esc(t('safekeep.retry', 'Retry')) + '</button>'
                        + '<button class="safe-zone-icon-btn" onclick="SK._skip(' + Number(it.id) + ')" title="' + esc(t('safekeep.skip', 'Skip')) + '"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 4 15 12 5 20 5 4"/><line x1="19" y1="5" x2="19" y2="19"/></svg></button>'
                        : '') + '</div>';
            }).join('');
        });
    }

    function retryAll() {
        api('POST', '/api/safekeep/queue/retry', { all: true }).then(function (r) {
            toast(t('safekeep.retrying_n', 'Retrying {n} item(s)', { n: r.count || 0 }), 'info');
            loadQueue();
        });
    }

    function dryRun() {
        if (!requireOn()) return;
        var box = $('skDryRun');
        box.innerHTML = '<div class="sk-wait"><span class="sk-spinner"></span><span>' + esc(t('safekeep.checking', 'Checking…')) + '</span></div>';
        api('POST', '/api/safekeep/dryrun', {}).then(function (r) {
            if (!r.success) { box.innerHTML = '<p class="sk-queue-err">' + esc(r.message || r.error) + '</p>'; return; }
            var n = r.now || {};
            var h = '<div class="sk-muted" style="margin-bottom:6px;">'
                + esc((n.transport === 'wifi' ? (n.phoneHotspot ? t('safekeep.net_hotspot', 'Phone hotspot (treated as mobile data)') : t('safekeep.net_wifi', 'Wi-Fi') + (n.ssid ? ' “' + n.ssid + '”' : '')) : n.transport === 'none' ? t('safekeep.net_offline', 'Offline') : t('safekeep.net_mobile', 'Mobile data'))
                    + ' · ' + (n.parked ? t('safekeep.vehicle_parked', 'Parked') : t('safekeep.vehicle_driving', 'Driving')) + (n.charging ? ' · ' + t('safekeep.charging', 'Charging') : '')) + '</div>';
            (r.routes || []).forEach(function (x) {
                var what = x.mediaAllowed ? '<span class="sk-ok">' + esc(t('safekeep.would_upload', 'Uploads now')) + '</span>'
                    : x.smallAllowed ? '<span class="sk-warn">' + esc(t('safekeep.small_only', 'Snapshots only')) + '</span>'
                        : '<span class="sk-warn">' + esc(reasonText(x.reason)) + '</span>';
                h += '<div class="sk-result-row"><span>' + esc(laneTitle(x.lane)) + ' → ' + esc(x.destName) + (x.pendingCount ? ' (' + x.pendingCount + ', ' + fmtBytes(x.pendingBytes) + ')' : '') + '</span><span>' + what + '</span></div>';
            });
            if (!(r.routes || []).length) h += '<div class="sk-muted">' + esc(t('safekeep.no_routes', 'Nothing is set to back up yet — see What & when.')) + '</div>';
            box.innerHTML = h;
        });
    }

    function renderRestoreDests() {
        var sel = $('skRestoreDest');
        if (!sel) return;
        var cur = sel.value;
        sel.innerHTML = destinations().filter(function (d) { return !(d.params && d.params.writeOnly); }).map(function (d) {
            return '<option value="' + esc(d.id) + '"' + (d.id === cur ? ' selected' : '') + '>' + esc(d.name) + '</option>';
        }).join('');
    }

    function restoreTrips() {
        if (!requireOn()) return;
        var dest = val('skRestoreDest');
        if (!dest) { toast(t('safekeep.pick_storage', 'Pick a storage first'), 'warning'); return; }
        var out = $('skRestoreResult');
        out.innerHTML = '<div class="sk-wait"><span class="sk-spinner"></span><span>' + esc(t('safekeep.restoring', 'Downloading trips…')) + '</span></div>';
        api('POST', '/api/safekeep/restore/trips', { dest: dest, identity: val('skRestoreIdentity') }).then(function (r) {
            setv('skRestoreIdentity', '');
            if (!r.success) {
                out.innerHTML = '<p class="sk-queue-err">' + esc(r.error === 'identity_required' ? t('safekeep.need_identity', 'These backups are encrypted — enter your secret key.') : (r.message || r.error)) + '</p>';
                return;
            }
            out.innerHTML = esc(t('safekeep.restored_trips', 'Found {found} trips, added {added} new (already-present trips are skipped).', { found: r.found, added: r.imported }))
                + (r.failed ? ' <span class="sk-warn">' + esc(t('safekeep.n_failed', '{n} failed', { n: r.failed })) + '</span>' : '');
        });
    }

    function restoreSettings() {
        if (!requireOn()) return;
        var dest = val('skRestoreDest');
        if (!dest) { toast(t('safekeep.pick_storage', 'Pick a storage first'), 'warning'); return; }
        var identity = val('skRestoreIdentity');
        var out = $('skRestoreResult');
        out.innerHTML = '<div class="sk-wait"><span class="sk-spinner"></span><span>' + esc(t('safekeep.listing', 'Looking for backups…')) + '</span></div>';
        api('POST', '/api/safekeep/restore/list', { dest: dest, what: 'settings' }).then(function (l) {
            var files = (l.files || []);
            if (!l.success || !files.length) { out.innerHTML = '<p class="sk-muted">' + esc(t('safekeep.no_settings_backup', 'No settings backups found there.')) + '</p>'; return; }
            var f = files[0];
            return api('POST', '/api/safekeep/restore/settings', { dest: dest, ref: f.ref, name: f.name, identity: identity, apply: false }).then(function (p) {
                if (!p.success) { out.innerHTML = '<p class="sk-queue-err">' + esc(p.error === 'identity_required' ? t('safekeep.need_identity', 'These backups are encrypted — enter your secret key.') : (p.message || p.error)) + '</p>'; return; }
                out.innerHTML = '';
                var body = t('safekeep.settings_confirm', 'Apply {name}? Your current settings are replaced (SafeKeep\'s own setup and any passwords stay as they are).', { name: f.name })
                    + ((p.warnings || []).length ? '\n\n' + p.warnings.join('\n') : '');
                return confirm(t('safekeep.restore_settings', 'Restore settings'), body, t('safekeep.apply', 'Apply')).then(function (yes) {
                    if (!yes) return;
                    return api('POST', '/api/safekeep/restore/settings', { dest: dest, ref: f.ref, name: f.name, identity: identity, apply: true }).then(function (a) {
                        setv('skRestoreIdentity', '');
                        out.innerHTML = esc(a.message || (a.success ? t('safekeep.saved', 'Saved') : 'Error')) + (a.restartRequired ? ' ' + esc(t('safekeep.restart_needed', 'Restart OverDrive to finish.')) : '');
                    });
                });
            });
        });
    }

    function backfillTrips() {
        if (!requireOn()) return;
        api('POST', '/api/safekeep/backfill/trips', { days: parseInt(val('skBackfillDays'), 10) || 0 }).then(function (r) {
            toast(r.success ? t('safekeep.trips_queued', 'Past trips are being queued') : (r.message || 'Error'), r.success ? 'success' : 'error');
        });
    }

    // ── inline handlers (lanes, conditions, lists) ──────────────────────────────

    function _laneToggle(laneId, on) {
        captureAllLanes();
        var l = null;
        LANES.forEach(function (x) { if (x.id === laneId) l = x; });
        state.lanes[laneId] = on ? (state.lanes[laneId] || laneDefaults(l)) : null;
        if (on && !state.lanes[laneId].dest.length && destinations().length) state.lanes[laneId].dest = [destinations()[0].id];
        renderLanes();
    }

    function _laneDest(laneId, destId) {
        captureAllLanes();
        var r = state.lanes[laneId];
        if (!r) return;
        var i = (r.dest || []).indexOf(destId);
        if (i >= 0) r.dest.splice(i, 1);
        else (r.dest = r.dest || []).push(destId);
        renderLanes();
    }

    function _lanePreset(laneId, preset) {
        captureAllLanes();
        var r = state.lanes[laneId];
        if (!r) return;
        var keepMobile = r.rule && r.rule.network && r.rule.network.mobile === 'snapshots';
        r.rule = { preset: preset };
        if (preset === 'custom') r.rule.network = { wifi: 'any', mobile: keepMobile ? 'snapshots' : 'off' };
        else if (keepMobile) r.rule.network = { mobile: 'snapshots' };
        renderLanes();
    }

    function _winToggle(laneId, on) {
        var b = $('skCWinBox_' + laneId);
        if (b) b.style.display = on ? '' : 'none';
    }

    function _addCond(laneId) {
        captureAllLanes();
        var r = state.lanes[laneId];
        if (!r) return;
        r.rule.advanced = r.rule.advanced || { logic: 'AND', conditions: [] };
        r.rule.advanced.conditions.push({ type: 'locationZone', comparator: 'eq', value: 'Home' });
        renderConditions(laneId);
    }

    function _removeCond(laneId, i) {
        captureConditions(laneId);
        var r = state.lanes[laneId];
        r.rule.advanced.conditions.splice(parseInt(i, 10), 1);
        renderConditions(laneId);
    }

    function _condType(laneId, i, type) {
        captureConditions(laneId);
        var c = state.lanes[laneId].rule.advanced.conditions[i];
        var def = COND[type];
        c.type = type;
        c.comparator = 'eq';
        c.value = def.kind === 'enum' ? def.opts[0][0] : def.kind === 'int' ? def.min : def.kind === 'time' ? 0 : '';
        renderConditions(laneId);
    }

    function _removeSsid(i) {
        state.ssids.splice(i, 1);
        renderSsids();
    }

    function _removeWebhook(i) {
        state.webhooks.splice(i, 1);
        renderWebhooks();
    }

    function _retry(id) {
        api('POST', '/api/safekeep/queue/retry', { id: id }).then(loadQueue);
    }

    function _skip(id) {
        api('POST', '/api/safekeep/queue/skip', { id: id }).then(loadQueue);
    }

    return {
        init: init,
        toggleMaster: toggleMaster,
        openAddStorage: openAddStorage,
        syncNow: syncNow,
        pause: pause,
        resume: resume,
        testDest: testDest,
        editDest: editDest,
        removeDest: removeDest,
        reconnect: reconnect,
        saveLanes: saveLanes,
        addSsid: addSsid,
        addCurrentSsid: addCurrentSsid,
        setToggle: setToggle,
        addWebhook: addWebhook,
        saveLimits: saveLimits,
        queueFilter: queueFilter,
        retryAll: retryAll,
        dryRun: dryRun,
        restoreTrips: restoreTrips,
        restoreSettings: restoreSettings,
        backfillTrips: backfillTrips,
        _laneToggle: _laneToggle,
        _laneDest: _laneDest,
        _lanePreset: _lanePreset,
        _winToggle: _winToggle,
        _addCond: _addCond,
        _removeCond: _removeCond,
        _condType: _condType,
        _removeSsid: _removeSsid,
        _removeWebhook: _removeWebhook,
        _retry: _retry,
        _skip: _skip
    };
})();
