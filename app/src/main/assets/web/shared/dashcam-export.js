// ES5-only — Chrome 58 / Android 7.1 head-unit floor.
// "Save as one video" (Dashcam view export) action on Recordings cards whose clip can be recomposed
// from the grid + windshield recordings. Silent when nothing is available.
window.DashcamExport = (function () {
    'use strict';

    var API = '/api/recordings/dashcam-view/';
    var ID_RE = /^[a-f0-9]{32}$/i;
    var avail = {};
    var capped = {};
    var asking = false;
    var job = null;
    var pollTimer = 0;
    var resumed = false;
    var ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">' +
        '<rect x="3" y="3" width="18" height="11" rx="1"/><rect x="3" y="16" width="5" height="5" rx="1"/>' +
        '<rect x="9.5" y="16" width="5" height="5" rx="1"/><rect x="16" y="16" width="5" height="5" rx="1"/></svg>';

    function t(key, fallback, vars) {
        var s = null;
        try { s = (window.BYD && BYD.i18n) ? BYD.i18n.t(key, vars) : null; } catch (e) { s = null; }
        if (!s || s === key) {
            s = fallback;
            if (vars) for (var k in vars) if (vars.hasOwnProperty(k)) s = s.split('{' + k + '}').join(String(vars[k]));
        }
        return s;
    }

    function toast(msg, type) {
        if (window.BYD && BYD.core && BYD.core.toast) BYD.core.toast(msg, type || 'info', 6000);
    }

    function errorText(j, stopped) {
        var code = j && j.code ? String(j.code).replace(/[^a-z_]/g, '') : '';
        var fallback = (j && j.error) || t('events.dv_failed', "Couldn't save the Dashcam view video");
        if (stopped) return code ? t('events.dv_stopped_' + code, fallback) : fallback;
        return t('events.dv_err_' + code, fallback);
    }

    function getJson(url, opts) {
        return fetch(url, opts).then(function (r) {
            return r.json().then(function (j) { return j || {}; }, function () { return { success: false }; });
        }).catch(function () { return { success: false, error: t('errors.network', 'Network error') }; });
    }

    function cardId(card) {
        var id = card.getAttribute('data-recording-key') || '';
        return ID_RE.test(id) ? id.toLowerCase() : null;
    }

    function decorate() {
        var cards = document.querySelectorAll('.recording-card[data-recording-key]');
        var ask = [];
        for (var i = 0; i < cards.length; i++) {
            var id = cardId(cards[i]);
            if (!id) continue;
            if (avail[id] === true) addButton(cards[i], id);
            else if (avail[id] === undefined && ask.length < 50) ask.push(id);
        }
        if (!resumed) {
            resumed = true;
            getJson(API + 'export').then(function (j) {
                if (j && j.job && j.job.state === 'running') { job = j.job; schedulePoll(); }
            });
        }
        if (!ask.length || asking) return;
        asking = true;
        getJson(API + 'available?ids=' + ask.join(',')).then(function (j) {
            asking = false;
            if (!j || j.success !== true) return;
            for (var a = 0; a < ask.length; a++) avail[ask[a]] = false;
            var ids = j.ids || [];
            for (var b = 0; b < ids.length; b++) if (ID_RE.test(ids[b])) avail[String(ids[b]).toLowerCase()] = true;
            var cap = j.capped || [];
            for (var c = 0; c < cap.length; c++) if (ID_RE.test(cap[c])) capped[String(cap[c]).toLowerCase()] = true;
            if (ids.length) decorate();
        });
    }

    function addButton(card, id) {
        var bar = card.querySelector('.recording-actions');
        if (!bar || bar.querySelector('[data-dv-export]')) return;
        var btn = document.createElement('button');
        btn.className = 'action-btn';
        btn.setAttribute('data-dv-export', id);
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            onTap(id);
        });
        bar.insertBefore(btn, bar.firstChild);
        paint(btn, id);
    }

    function paint(btn, id) {
        var mine = job && job.sourceId === id;
        var title;
        if (mine && job.state === 'running') {
            btn.innerHTML = '<span style="font-size:10px;font-weight:600">' + (job.paused ? '||' : Math.floor((job.progress || 0) * 100) + '%') + '</span>';
            title = job.paused ? t('events.dv_export_paused', 'Paused while the cameras are busy')
                : t('events.dv_export_running', 'Saving Dashcam view… tap to cancel');
        } else if (mine && job.state === 'done' && job.videoUrl) {
            btn.innerHTML = ICON;
            btn.classList.add('dv-ready');
            title = t('events.dv_export_download', 'Download the Dashcam view video');
        } else {
            btn.innerHTML = ICON;
            btn.classList.remove('dv-ready');
            title = t('events.dv_export', 'Save as one video') + ' — ' + hintFor(id);
        }
        btn.title = title;
        btn.setAttribute('aria-label', title);
    }

    function hintFor(id) {
        var hint = t('events.dv_export_hint', 'Combines the windshield camera and the 360° view into a single file you can share. Runs on the car while parked and may take a few minutes.');
        if (capped[id]) hint += ' ' + t('events.dv_export_capped', 'This clip is longer than 5 minutes: only the first 5 minutes are saved.');
        return hint;
    }

    function paintAll() {
        var btns = document.querySelectorAll('[data-dv-export]');
        for (var i = 0; i < btns.length; i++) paint(btns[i], btns[i].getAttribute('data-dv-export'));
    }

    function download(url) {
        var a = document.createElement('a');
        a.href = url;
        a.download = '';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    function onTap(id) {
        if (job && job.sourceId === id && job.state === 'running') {
            getJson(API + 'cancel', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
            return;
        }
        if (job && job.sourceId === id && job.state === 'done' && job.videoUrl) {
            download(job.videoUrl);
            return;
        }
        if (job && job.state === 'running') {
            toast(t('events.dv_err_busy', 'An export is already running.'), 'warning');
            return;
        }
        var hint = hintFor(id);
        var ask = (window.BYD && BYD.utils && BYD.utils.confirmDialog)
            ? BYD.utils.confirmDialog({
                title: t('events.dv_export', 'Save as one video'),
                body: hint,
                confirmLabel: t('events.dv_export_confirm', 'Save video'),
                cancelLabel: t('common.cancel', 'Cancel')
            })
            : Promise.resolve(window.confirm(t('events.dv_export', 'Save as one video') + '\n\n' + hint));
        Promise.resolve(ask).then(function (ok) {
            if (ok) start(id);
        });
    }

    function start(id) {
        getJson(API + 'export', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: id })
        }).then(function (j) {
            if (!j || j.success !== true || !j.job) { toast(errorText(j), 'error'); return; }
            job = j.job;
            toast(t('events.dv_export_started', 'Saving the Dashcam view. This may take a few minutes.'), 'info');
            paintAll();
            schedulePoll();
        });
    }

    function schedulePoll() {
        if (pollTimer) return;
        pollTimer = setTimeout(function () {
            pollTimer = 0;
            getJson(API + 'export').then(function (j) {
                var was = job;
                if (j && j.success === true && j.job) job = j.job;
                paintAll();
                if (!job) return;
                if (job.state === 'running') {
                    if (job.paused && was && was.state === 'running' && !was.paused) {
                        toast(t('events.dv_export_paused', 'Paused while the cameras are busy'), 'info');
                    }
                    schedulePoll();
                    return;
                }
                if (!was || was.state !== 'running') return;
                if (job.state === 'done') {
                    toast(t('events.dv_export_ready', 'Dashcam view saved to Replays. Tap the button again to download.'), 'success');
                } else if (job.state === 'cancelled') {
                    toast(t('events.dv_export_cancelled', 'Export cancelled.'), 'info');
                } else {
                    toast(errorText(job, true), 'error');
                }
            });
        }, 1500);
    }

    return { decorate: decorate };
})();
