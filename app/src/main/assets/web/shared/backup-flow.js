/**
 * Backup & Restore flow — settings export/import (same-device).
 *
 * Mounts a "Backup & Restore" section into #backupMount (about.html). Mirrors
 * update-flow.js conventions: toast, modal, escapeHtml, Chrome-58-safe DOM.
 *
 *   Export  → GET /api/backup/export → download a versioned .json bundle.
 *   Restore → file picker → POST /api/backup/import/preview (warnings) →
 *             confirm modal → POST /api/backup/import?confirm=true → reload.
 *
 * The bundle contains the device key + encrypted credentials. Both endpoints
 * are JWT-gated in every mode (tunnel included) and work over the tunnel by
 * design — the UI warns the user to keep the file private, and restore is
 * same-device only.
 */
(function () {
    'use strict';

    var STYLE_INJECTED = false;
    function $(id) { return document.getElementById(id); }
    function t(key, fb) {
        if (window.BYD && BYD.i18n && BYD.i18n.t) {
            var v = BYD.i18n.t(key);
            if (v && v !== key) return v;
        }
        return fb;
    }
    function toast(msg, type) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type || 'info');
        else console.log('[Backup] ' + msg);
    }
    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // Render the ⚠️ warning rows. Defensive: the server SHOULD send an array,
    // but a serialization quirk could hand us a string (has .length, would pass
    // a truthy check then throw on .map) or some other type — coerce to a safe
    // string[] first so a bad payload can't crash the modal and strand the user.
    function buildWarnRows(warnings) {
        var list = Array.isArray(warnings) ? warnings
                 : (typeof warnings === 'string' && warnings) ? [warnings]
                 : [];
        list = list.filter(function (w) { return typeof w === 'string' && w; });
        if (!list.length) return '';
        return '<div class="bk-warn">' + list.map(function (w) {
            return '<div class="bk-row"><span class="bk-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></svg></span><div>' + escapeHtml(w) + '</div></div>';
        }).join('') + '</div>';
    }

    function injectStyles() {
        if (STYLE_INJECTED) return;
        STYLE_INJECTED = true;
        var css = `
        .bk-modal-bg { position:fixed; inset:0; background:rgba(0,0,0,0.72); z-index:9000; display:flex; align-items:center; justify-content:center; padding:16px; }
        .bk-modal { background:#0e1218; color:#e8eef5; padding:24px; max-width:520px; width:100%; border-radius:14px; border:1px solid #232a35; box-shadow:0 24px 60px rgba(0,0,0,0.6); max-height:90vh; overflow:auto; }
        .bk-modal h2 { margin:0 0 6px; font-size:18px; font-weight:600; color:#fff; }
        .bk-modal .bk-sub { color:#9aa6b3; font-size:12px; margin-bottom:14px; line-height:1.5; }
        .bk-warn { background:#1a1f28; border:1px solid #2a3340; border-radius:10px; padding:4px 14px; margin-bottom:6px; }
        .bk-row { display:flex; gap:12px; align-items:flex-start; padding:12px 0; font-size:13px; line-height:1.5; }
        .bk-row + .bk-row { border-top:1px solid #232a35; }
        .bk-row .bk-icon { flex:none; display:flex; width:20px; height:20px; color:var(--warning); }
        .bk-row .bk-icon svg { width:20px; height:20px; }
        .bk-actions { display:flex; gap:10px; justify-content:flex-end; margin-top:18px; }
        .bk-btn { padding:10px 20px; border-radius:8px; font-weight:600; font-size:13px; border:0; cursor:pointer; font-family:inherit; }
        .bk-btn-cancel { background:transparent; color:#cdd6e0; }
        .bk-btn-cancel:hover { background:#1a1f28; }
        .bk-btn-primary { background:#3b82f6; color:#fff; }
        .bk-btn-primary:hover { background:#2563eb; }
        .bk-btn-primary:disabled { background:#374151; cursor:not-allowed; }
        .bk-btn-danger { background:#dc2626; color:#fff; }
        .bk-btn-danger:hover { background:#b91c1c; }
        .bk-opt { display:flex; align-items:flex-start; gap:10px; padding:10px 14px; margin-top:8px;
                  background:var(--bg-elevated,#0e1218); border:1px solid var(--border-subtle,#232a35);
                  border-radius:10px; cursor:pointer; }
        .bk-opt > * + * { margin-left:10px; }
        .bk-opt input { position:absolute; width:1px; height:1px; opacity:0; overflow:hidden; }
        .bk-check { position:relative; width:20px; height:20px; margin-top:1px; flex:none;
                    border:1px solid var(--border-default,#394150); border-radius:5px;
                    background:var(--bg-surface,#111720); box-sizing:border-box; }
        .bk-opt input:checked + .bk-check { background:var(--brand-primary,#00d4aa); border-color:var(--brand-primary,#00d4aa); }
        .bk-opt input:checked + .bk-check::after { content:""; position:absolute; left:6px; top:2px;
                    width:5px; height:10px; border:solid var(--on-primary,#06130f);
                    border-width:0 2px 2px 0; transform:rotate(45deg); }
        .bk-opt input:focus + .bk-check { outline:2px solid var(--primary,#00d4aa); outline-offset:2px; }
        .bk-opt-text { font-size:13px; color:var(--text-secondary,#9aa6b3); line-height:1.4; }
        .bk-opt-text b { color:var(--text-primary,#e8eef5); font-weight:600; display:block; margin-bottom:2px; }
        `;
        var s = document.createElement('style');
        s.textContent = css;
        document.head.appendChild(s);
    }

    // ─────────────────────── Section render ───────────────────────

    function renderSection(mount) {
        // Two tier-cards reusing about.html's .tier-card visuals, plus a
        // hidden file input for restore.
        var sec = document.createElement('section');
        sec.className = 'about-section';
        sec.innerHTML =
            '<h3 class="about-subhead" data-i18n="backup.section_title">Backup &amp; Restore</h3>' +
            '<p class="about-section-sub" data-i18n="backup.section_sub">' +
                escapeHtml(t('backup.section_sub',
                    'Save your Overdrive settings to a file, or restore them on this same head unit. The file includes your credentials — keep it private.')) +
            '</p>' +
            '<div class="support-tier-list">' +
              '<button type="button" class="tier-card" id="bkExport">' +
                '<svg class="tier-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                  '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>' +
                '</svg>' +
                '<div class="tier-text">' +
                  '<p class="tier-title" data-i18n="backup.export_title">Export settings</p>' +
                  '<p class="tier-value" data-i18n="backup.export_value">Download a backup file of your current settings.</p>' +
                '</div>' +
                '<svg class="tier-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>' +
              '</button>' +
              '<button type="button" class="tier-card" id="bkImport">' +
                '<svg class="tier-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                  '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>' +
                '</svg>' +
                '<div class="tier-text">' +
                  '<p class="tier-title" data-i18n="backup.import_title">Restore settings</p>' +
                  '<p class="tier-value" data-i18n="backup.import_value">Load a backup file made on this head unit.</p>' +
                '</div>' +
                '<svg class="tier-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>' +
              '</button>' +
            '</div>' +
            '<label class="bk-opt" for="bkInclTrips">' +
              '<input type="checkbox" id="bkInclTrips">' +
              '<span class="bk-check" aria-hidden="true"></span>' +
              '<span class="bk-opt-text">' +
                '<b data-i18n="backup.trips_opt_title">Include trip history</b>' +
                '<span data-i18n="backup.trips_opt_desc">' +
                  escapeHtml(t('backup.trips_opt_desc',
                    'Adds your trip stats (distance, energy, cost, scores, start/end locations) to the export. Detailed per-trip telemetry is not included. The file will contain your location history — keep it private.')) +
                '</span>' +
              '</span>' +
            '</label>' +
            '<input type="file" id="bkFile" accept="application/json,.json" style="display:none">';
        mount.appendChild(sec);

        $('bkExport').addEventListener('click', doExport);
        $('bkImport').addEventListener('click', function () { $('bkFile').click(); });
        $('bkFile').addEventListener('change', onFileChosen);
    }

    // ─────────────────────── Export ───────────────────────

    var exportInFlight = false;

    function doExport() {
        if (exportInFlight) return;   // ignore rapid re-clicks while a build runs
        exportInFlight = true;
        var btn = $('bkExport');
        if (btn) { btn.disabled = true; btn.style.opacity = '0.6'; }
        function done() {
            exportInFlight = false;
            if (btn) { btn.disabled = false; btn.style.opacity = ''; }
        }
        var inclTrips = $('bkInclTrips') && $('bkInclTrips').checked;
        var url = '/api/backup/export' + (inclTrips ? '?trips=true' : '');
        fetch(url, { credentials: 'same-origin' })
            .then(function (r) { return r.text().then(function (txt) { return { ok: r.ok, status: r.status, body: txt }; }); })
            .then(function (resp) {
                done();
                var data;
                try { data = resp.body ? JSON.parse(resp.body) : null; } catch (e) { data = null; }
                if (!resp.ok || !data || data.error) {
                    toast(t('backup.export_failed', 'Export failed') + ': ' +
                        ((data && data.error) || ('HTTP ' + resp.status)), 'error');
                    return;
                }
                triggerDownload(resp.body, buildFilename(data));
                toast(t('backup.export_done', 'Backup downloaded'), 'success');
            })
            .catch(function (e) {
                done();
                toast(t('backup.export_failed', 'Export failed') + ': ' +
                    ((e && e.message) || t('errors.network', 'network error')), 'error');
            });
    }

    function buildFilename(bundle) {
        var ver = '', model = '';
        try {
            var m = bundle.manifest || {};
            ver = (m.appVersion || '').replace(/[^a-zA-Z0-9._-]/g, '');
            model = (m.deviceModel || '').replace(/[^a-zA-Z0-9._-]/g, '');
        } catch (e) {}
        var stamp = new Date().toISOString().slice(0, 10);
        return 'overdrive-backup' + (model ? '-' + model : '') + (ver ? '-' + ver : '') + '-' + stamp + '.json';
    }

    // Blob download — supported on the Chrome 58 head-unit WebView.
    function triggerDownload(text, filename) {
        try {
            var blob = new Blob([text], { type: 'application/json' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            setTimeout(function () {
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            }, 1000);
        } catch (e) {
            // Fallback: open the JSON in a new tab so the user can save manually.
            try {
                var w = window.open('', '_blank');
                if (w) { w.document.title = filename; w.document.body.textContent = text; }
            } catch (e2) {
                toast(t('backup.download_unsupported',
                    'Could not start the download on this browser.'), 'error');
            }
        }
    }

    // ─────────────────────── Restore ───────────────────────

    // Trip-history bundles can be much larger than settings-only backups.
    var MAX_IMPORT_BYTES = 32 * 1024 * 1024;   // matches native cap + backup HTTP cap

    function onFileChosen(ev) {
        var file = ev.target && ev.target.files && ev.target.files[0];
        // Reset the input so re-choosing the same file fires change again.
        if (ev.target) ev.target.value = '';
        if (!file) return;
        // Reject oversized files BEFORE readAsText pulls the whole thing into
        // memory (a real bundle is a few KB; a 100MB pick would balloon the
        // Chrome-58 WebView heap on the head unit). file.size is bytes.
        if (typeof file.size === 'number' && file.size > MAX_IMPORT_BYTES) {
            toast(t('backup.invalid_file', 'That file is not a valid Overdrive backup.'), 'error');
            return;
        }
        var reader = new FileReader();
        reader.onload = function () {
            var text = reader.result;
            var parsed;
            try { parsed = JSON.parse(text); } catch (e) {
                toast(t('backup.invalid_file', 'That file is not a valid Overdrive backup.'), 'error');
                return;
            }
            previewRestore(text, parsed);
        };
        reader.onerror = function () {
            toast(t('backup.read_failed', 'Could not read the file.'), 'error');
        };
        reader.readAsText(file);
    }

    function previewRestore(rawText, parsed) {
        fetch('/api/backup/import/preview', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: rawText
        })
        .then(function (r) { return r.text().then(function (txt) { return { ok: r.ok, body: txt }; }); })
        .then(function (resp) {
            var res;
            try { res = resp.body ? JSON.parse(resp.body) : null; } catch (e) { res = null; }
            if (!resp.ok || !res) { toast(t('backup.preview_failed', 'Could not validate the backup.'), 'error'); return; }
            if (!res.valid) {
                toast(res.message || t('backup.invalid_file', 'That file is not a valid Overdrive backup.'), 'error');
                return;
            }
            showRestoreConfirm(rawText, res.warnings || []);
        })
        .catch(function (e) {
            toast(t('backup.preview_failed', 'Could not validate the backup.') + ': ' +
                ((e && e.message) || ''), 'error');
        });
    }

    function showRestoreConfirm(rawText, warnings) {
        injectStyles();
        closeModal();
        var bg = document.createElement('div');
        bg.className = 'bk-modal-bg';
        bg.id = 'bkModalBg';

        var warnRows = buildWarnRows(warnings);

        bg.innerHTML =
            '<div class="bk-modal" role="dialog" aria-labelledby="bkTitle">' +
              '<h2 id="bkTitle">' + escapeHtml(t('backup.restore_title', 'Restore settings?')) + '</h2>' +
              '<div class="bk-sub">' + escapeHtml(t('backup.restore_sub',
                  'This replaces your current Overdrive settings with the ones in this backup. Services will reload. This only works on the same head unit the backup was made on.')) + '</div>' +
              warnRows +
              '<div class="bk-actions">' +
                '<button class="bk-btn bk-btn-cancel" id="bkCancel">' + escapeHtml(t('common.cancel', 'Cancel')) + '</button>' +
                '<button class="bk-btn bk-btn-danger" id="bkConfirm">' + escapeHtml(t('backup.restore_confirm', 'Restore')) + '</button>' +
              '</div>' +
            '</div>';
        document.body.appendChild(bg);
        $('bkCancel').addEventListener('click', closeModal);
        $('bkConfirm').addEventListener('click', function () { doRestore(rawText); });
        bg.addEventListener('click', function (e) { if (e.target === bg) closeModal(); });
    }

    function doRestore(rawText) {
        var btn = $('bkConfirm');
        if (btn) { btn.disabled = true; btn.textContent = t('backup.restoring', 'Restoring…'); }
        fetch('/api/backup/import?confirm=true', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: rawText
        })
        .then(function (r) { return r.text().then(function (txt) { return { ok: r.ok, body: txt }; }); })
        .then(function (resp) {
            var res;
            try { res = resp.body ? JSON.parse(resp.body) : null; } catch (e) { res = null; }
            if (resp.ok && res && res.success) {
                if (btn) btn.textContent = t('backup.restored', 'Restored');
                // Settings are on disk, but already-running daemon pipelines read
                // most config at start — show a restart advisory (plus any
                // warnings the daemon returned) instead of a silent reload.
                showRestartDone(res.warnings || []);
            } else {
                if (btn) { btn.disabled = false; btn.textContent = t('backup.restore_confirm', 'Restore'); }
                toast((res && res.message) || t('backup.restore_failed', 'Restore failed.'), 'error');
            }
        })
        .catch(function (e) {
            if (btn) { btn.disabled = false; btn.textContent = t('backup.restore_confirm', 'Restore'); }
            toast(t('backup.restore_failed', 'Restore failed.') + ': ' +
                ((e && e.message) || t('errors.network', 'network error')), 'error');
        });
    }

    function closeModal() {
        var bg = $('bkModalBg');
        if (bg) bg.remove();
    }

    // Swap the confirm modal for a "restored — restart to apply" panel. Lists
    // any warnings the daemon returned (e.g. firmware-change credential loss).
    // Reload happens when the user dismisses, so they actually read the hint.
    function showRestartDone(warnings) {
        injectStyles();
        var modal = document.querySelector('.bk-modal');
        if (!modal) { setTimeout(function () { window.location.reload(); }, 1200); return; }
        var warnHtml = buildWarnRows(warnings);
        modal.innerHTML =
            '<h2>' + escapeHtml(t('backup.restored', 'Settings restored')) + '</h2>' +
            '<div class="bk-sub">' + escapeHtml(t('backup.restart_hint',
                'Restart the camera daemon (Daemons → Camera → Restart) to make sure every restored setting is applied.')) + '</div>' +
            warnHtml +
            '<div class="bk-actions">' +
              '<button class="bk-btn bk-btn-primary" id="bkDone">' + escapeHtml(t('common.ok', 'OK')) + '</button>' +
            '</div>';
        var done = $('bkDone');
        if (done) done.addEventListener('click', function () {
            closeModal();
            window.location.reload();
        });
    }

    // ─────────────────────── Bootstrap ───────────────────────

    function init() {
        var mount = $('backupMount');
        if (!mount) return;       // only renders on pages that opt in (about.html)
        injectStyles();
        renderSection(mount);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        setTimeout(init, 0);
    }
})();
