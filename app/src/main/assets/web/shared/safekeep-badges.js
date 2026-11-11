// ES5-only — Chrome 58 / Android 7.1 head-unit floor.
// SafeKeep status badge on each Recordings card (saved / uploading / queued / waiting / failed),
// with a tap sheet for Upload now, Lock and Open in cloud. Silent when SafeKeep is off.
window.SafeKeepBadges = (function () {
    'use strict';

    var enabled = null;
    var statusPromise = null;
    var metered = false;
    var states = {};
    var pending = null;
    var refreshTimer = null;

    function t(key, fallback, vars) {
        var s = null;
        try { s = (window.BYD && BYD.i18n) ? BYD.i18n.t(key, vars) : null; } catch (e) { s = null; }
        if (!s || s === key) {
            s = fallback;
            if (vars) for (var k in vars) if (vars.hasOwnProperty(k)) s = s.split('{' + k + '}').join(String(vars[k]));
        }
        return s;
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function post(url, body) {
        return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) })
            .then(function (r) {
                return r.json().then(function (j) {
                    if (!j || typeof j !== 'object') j = {};
                    j.httpStatus = r.status;
                    if (!r.ok && j.success === true) j.success = false;
                    return j;
                }, function () {
                    return { success: false, httpStatus: r.status, error: 'invalid_response' };
                });
            })
            .catch(function () {
                // Keep callers on their normal failure path when the daemon is
                // temporarily unreachable; do not leave action sheets hanging.
                return { success: false, httpStatus: 0, error: 'network_error' };
            });
    }

    function checkEnabled() {
        if (enabled !== null) return Promise.resolve(enabled);
        if (statusPromise) return statusPromise;

        statusPromise = fetch('/api/safekeep/status').then(function (r) {
            if (!r.ok) throw new Error('SafeKeep status HTTP ' + r.status);
            return r.json();
        }).then(function (s) {
            if (!s || typeof s.enabled !== 'boolean') {
                throw new Error('Invalid SafeKeep status response');
            }
            enabled = !!(s.enabled && s.destinations && s.destinations.length);
            metered = !!(s.network && s.network.metered);
            return enabled;
        }).catch(function () {
            // A transient error must not be cached as "SafeKeep disabled".
            // The next recordings render can retry the status request.
            return false;
        }).then(function (result) {
            statusPromise = null;
            return result;
        });

        return statusPromise;
    }

    /** Called after every Recordings render; coalesced so re-renders don't fan out requests. */
    function decorate() {
        if (pending) return;
        pending = setTimeout(function () {
            pending = null;
            checkEnabled().then(function (on) { if (on) fetchStates(); });
        }, 250);
    }

    function badgeEls() {
        return document.querySelectorAll('.sk-badge[data-sk-file]');
    }

    function fetchStates() {
        var els = badgeEls();
        var files = [];
        for (var i = 0; i < els.length && files.length < 200; i++) {
            var f = els[i].getAttribute('data-sk-file');
            if (f && files.indexOf(f) < 0) files.push(f);
        }
        if (!files.length) return;
        post('/api/safekeep/clips', { files: files }).then(function (r) {
            if (r.httpStatus === 409) { enabled = false; hideAll(); return; }
            if (!r.success) return;
            states = r.states || {};
            render();
            scheduleRefresh();
        });
    }

    function scheduleRefresh() {
        if (refreshTimer) clearTimeout(refreshTimer);
        var busy = false;
        for (var k in states) {
            if (!states.hasOwnProperty(k)) continue;
            var s = states[k].state;
            if (s === 'uploading' || s === 'queued') busy = true;
        }
        if (!busy || document.hidden) return;
        refreshTimer = setTimeout(fetchStates, 4000);
    }

    function hideAll() {
        var els = badgeEls();
        for (var i = 0; i < els.length; i++) els[i].setAttribute('hidden', '');
    }

    var LOCK_ICON = '<svg class="r-inline-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>';

    function label(st) {
        var lock = '';
        switch (st.state) {
            case 'saved': return lock + '✓ ' + t('safekeep.b_saved', 'Saved');
            case 'uploading': return lock + '↑ ' + (st.progress || 0) + '%';
            case 'queued': return lock + t('safekeep.b_queued', 'Queued');
            case 'waiting': return lock + t('safekeep.b_waiting', 'Waiting');
            case 'failed': return lock + '! ' + t('safekeep.b_failed', 'Failed');
            default: return lock + t('safekeep.b_none', 'Not saved');
        }
    }

    function render() {
        var els = badgeEls();
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            var f = el.getAttribute('data-sk-file');
            var st = states[f];
            if (!st) { el.setAttribute('hidden', ''); continue; }
            el.className = 'sk-badge sk-' + (st.state === 'uploading' ? 'up' : st.state);
            el.textContent = label(st);
            if (st.locked) el.insertAdjacentHTML('afterbegin', LOCK_ICON);
            el.title = st.reason ? String(st.reason) : '';
            el.removeAttribute('hidden');
            el.onclick = (function (file) {
                return function (e) {
                    // In select mode the tap belongs to the card (toggle selection), not the sheet.
                    if (window.BYD && BYD.events && BYD.events.selectMode) return;
                    if (e) { e.stopPropagation(); e.preventDefault(); }
                    openSheet(file);
                };
            })(f);
        }
    }

    function reasonText(code) {
        var m = {
            waiting_wifi: t('safekeep.r_waiting_wifi', 'Waiting for Wi-Fi'),
            waiting_home_wifi: t('safekeep.r_waiting_home_wifi', 'Waiting for home Wi-Fi'),
            waiting_parked: t('safekeep.r_waiting_parked', 'Waiting until the car is parked'),
            waiting_charging: t('safekeep.r_waiting_charging', 'Waiting for charging'),
            outside_window: t('safekeep.r_outside_window', 'Outside the upload time window'),
            manual_only: t('safekeep.r_manual_only', 'Uploads only when you ask'),
            mobile_cap: t('safekeep.r_mobile_cap', 'Monthly mobile data allowance used'),
            live_view: t('safekeep.r_live_view', 'Paused while live view is open'),
            event_recording: t('safekeep.r_event', 'Paused while an event is recording'),
            recorder_busy: t('safekeep.r_recorder_busy', 'Paused: the recorder needs the card'),
            needs_auth: t('safekeep.r_needs_auth', 'Sign-in expired — reconnect the storage'),
            quota_full: t('safekeep.r_quota_full', 'Storage is full'),
            paused_by_user: t('safekeep.r_paused', 'Paused by you')
        };
        return m[code] || (code ? String(code).replace(/_/g, ' ') : '');
    }

    function openSheet(file) {
        var st = states[file] || { state: 'none' };
        var statusLine = {
            saved: t('safekeep.s_saved', 'Saved to your storage.'),
            uploading: t('safekeep.s_uploading', 'Uploading — {p}% done.', { p: st.progress || 0 }),
            queued: t('safekeep.s_queued', 'Queued; it uploads when your rules allow.'),
            waiting: reasonText(st.reason) || t('safekeep.s_waiting', 'Waiting for your upload rules.'),
            failed: t('safekeep.s_failed', 'Upload failed: {why}', { why: st.reason || '' }),
            none: t('safekeep.s_none', 'Not backed up. This kind of clip isn\'t in your SafeKeep rules.')
        }[st.state] || '';
        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.style.display = 'flex';
        var card = document.createElement('div');
        card.className = 'modal-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');
        card.innerHTML = '<h3 class="soh-modal-title">' + esc(file) + '</h3><p>' + esc(statusLine) + '</p>'
            + '<div class="soh-modal-actions" style="flex-wrap:wrap;"></div>';
        backdrop.appendChild(card);
        document.body.appendChild(backdrop);
        var actions = card.querySelector('.soh-modal-actions');
        function close() { if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop); }
        function add(labelText, cls, fn) {
            var b = document.createElement('button');
            b.className = 'btn ' + (cls || 'btn-secondary');
            b.textContent = labelText;
            b.onclick = function () { fn(b); };
            actions.appendChild(b);
        }
        backdrop.addEventListener('click', function (e) { if (e.target === backdrop) close(); });
        if (st.link && /^https?:\/\//i.test(st.link)) add(t('safekeep.open_cloud', 'Open in cloud'), 'btn-secondary', function () { window.open(st.link, '_blank'); close(); });
        if (st.state !== 'saved' && st.state !== 'uploading') {
            add(t('safekeep.upload_now', 'Upload now'), 'btn-primary', function () { upload(file, false, close); });
            if (metered) add(t('safekeep.upload_mobile', 'Upload now on mobile data'), 'btn-secondary', function () { upload(file, true, close); });
        }
        add(st.locked ? t('safekeep.unlock', 'Unlock') : t('safekeep.lock', 'Lock'), 'btn-secondary', function () {
            post('/api/safekeep/lock', { file: file, locked: !st.locked }).then(function (r) {
                if (!r.success) {
                    toast((r.error === 'lock_quota' || r.error === 'lock_full') ? t('safekeep.lock_full', 'Locked-clip space is full — unlock something or raise the limit in SafeKeep.') : (r.message || r.error || 'Error'), 'error');
                } else {
                    toast(st.locked ? t('safekeep.unlocked', 'Unlocked') : t('safekeep.locked_toast', 'Locked — kept on the car and uploaded first'), 'success');
                }
                close();
                fetchStates();
            });
        });
        add(t('common.close', 'Close'), 'btn-secondary', close);
    }

    function upload(file, mobileOk, done) {
        post('/api/safekeep/upload', { file: file, mobileOk: mobileOk }).then(function (r) {
            if (r.success) toast(t('safekeep.upload_queued', 'Uploading as soon as possible'), 'success');
            else toast(r.error === 'no_destination' ? t('safekeep.no_storage', 'No storage connected yet') : (r.message || r.error || 'Error'), 'error');
            done();
            setTimeout(fetchStates, 800);
        });
    }

    function toast(msg, type) {
        try { BYD.core.toast(msg, type); } catch (e) { /* no toast container */ }
    }

    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && enabled) scheduleRefresh();
    });

    return { decorate: decorate };
})();
