/**
 * Update Flow — sidebar entry + check + confirmation modal + progress + reconnect.
 *
 * Loads on every page that has core.js. Auto-injects a "Check for Updates"
 * link into .sidebar-nav at DOMContentLoaded. Click → /api/update/check →
 * confirmation modal (with cloudflared rotation warning + LAN-IP hint +
 * in-car-app recommendation) → /api/update/install → polls /api/update/progress
 * → handles the inevitable mid-install daemon disconnect by polling /status
 * until appVersion advances, then shows a "✅ Updated" banner.
 */
(function () {
    'use strict';

    var STYLE_INJECTED = false;
    var pollTimer = null;
    var reconnectTimer = null;

    function $(id) { return document.getElementById(id); }
    function toast(msg, type) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type || 'info');
        else console.log('[Update] ' + msg);
    }

    // ─────────────────────────── Styles ───────────────────────────

    function injectStyles() {
        if (STYLE_INJECTED) return;
        STYLE_INJECTED = true;
        var css = `
        .upd-modal-bg { position:fixed; inset:0; background:rgba(0,0,0,0.72); z-index:9000; display:flex; align-items:center; justify-content:center; padding:16px; }
        .upd-modal { background:#0e1218; color:#e8eef5; padding:24px; max-width:520px; width:100%; border-radius:14px; border:1px solid #232a35; box-shadow:0 24px 60px rgba(0,0,0,0.6); max-height:90vh; overflow:auto; }
        .upd-modal h2 { margin:0 0 6px; font-size:18px; font-weight:600; color:#fff; }
        .upd-modal h2 span.upd-newv { color:#3b82f6; }
        .upd-modal .upd-current { color:#9aa6b3; font-size:12px; margin-bottom:14px; }
        .upd-rel-notes { background:#0a0d12; border:1px solid #1a1f28; border-radius:8px; padding:10px 12px; color:#cdd6e0; font-size:12px; line-height:1.55; max-height:140px; overflow:auto; white-space:pre-wrap; margin-bottom:14px; }
        .upd-warn { background:#1a1f28; border:1px solid #2a3340; border-radius:10px; padding:4px 14px; }
        .upd-row { display:flex; gap:12px; align-items:flex-start; padding:12px 0; font-size:13px; line-height:1.5; }
        .upd-row + .upd-row { border-top:1px solid #232a35; }
        .upd-row .upd-icon { font-size:18px; flex:none; line-height:1.2; }
        .upd-row strong { color:#fff; font-weight:600; }
        .upd-row code { background:#0a0d12; padding:1px 6px; border-radius:4px; font-family:ui-monospace,monospace; font-size:12px; }
        .upd-row.upd-rec { background:linear-gradient(0deg,rgba(245,158,11,0.05),rgba(245,158,11,0.05)); margin:0 -14px; padding:12px 14px; border-radius:8px; }
        .upd-row.upd-rec strong { color:#f59e0b; }
        .upd-actions { display:flex; gap:10px; justify-content:flex-end; margin-top:18px; }
        .upd-btn { padding:10px 20px; border-radius:8px; font-weight:600; font-size:13px; border:0; cursor:pointer; font-family:inherit; }
        .upd-btn-cancel { background:transparent; color:#cdd6e0; }
        .upd-btn-cancel:hover { background:#1a1f28; }
        .upd-btn-primary { background:#3b82f6; color:#fff; }
        .upd-btn-primary:hover { background:#2563eb; }
        .upd-btn-primary:disabled { background:#374151; cursor:not-allowed; }

        /* Progress card */
        .upd-progress { padding:8px 0 4px; }
        .upd-progress-phase { font-size:13px; color:#cdd6e0; margin-bottom:10px; min-height:18px; }
        .upd-progress-bar { height:8px; background:#0a0d12; border-radius:4px; overflow:hidden; margin-bottom:8px; }
        .upd-progress-fill { height:100%; background:linear-gradient(90deg,#3b82f6,#60a5fa); transition:width 0.4s ease; width:0%; }
        .upd-progress-fill.indeterminate { background:linear-gradient(90deg,#1a1f28 0%,#3b82f6 50%,#1a1f28 100%); background-size:200% 100%; animation:updIndet 1.4s linear infinite; width:100%; }
        @keyframes updIndet { 0% { background-position:200% 0; } 100% { background-position:-200% 0; } }
        .upd-progress-msg { font-size:11px; color:#9aa6b3; min-height:14px; }
        .upd-disconnect { background:#0a0d12; border:1px dashed #2a3340; border-radius:8px; padding:14px; margin-top:14px; font-size:12px; color:#cdd6e0; line-height:1.55; }
        .upd-disconnect strong { color:#f59e0b; }

        /* Sidebar entry — match existing .nav-link visual treatment */
        .nav-link.nav-link-update .upd-badge { margin-left:auto; background:#3b82f6; color:#fff; font-size:10px; font-weight:700; padding:2px 7px; border-radius:9px; line-height:1.2; }
        .nav-link.nav-link-update.has-update svg { color:#3b82f6; }
        `;
        var s = document.createElement('style');
        s.textContent = css;
        document.head.appendChild(s);
    }

    // ─────────────────────── Sidebar entry ───────────────────────

    function injectSidebarEntry() {
        var nav = document.querySelector('.sidebar-nav');
        if (!nav) return;
        // Don't double-inject
        if (nav.querySelector('.nav-link-update')) return;

        var a = document.createElement('a');
        a.href = '#';
        a.className = 'nav-link nav-link-update';
        a.id = 'navUpdateLink';
        a.innerHTML =
            '<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
            '<polyline points="23 4 23 10 17 10"/>' +
            '<polyline points="1 20 1 14 7 14"/>' +
            '<path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>' +
            '</svg>' +
            // Mark the span with data-i18n so the runtime re-translates it
            // once the catalog finishes loading. Without this attribute, the
            // initial t() call returns the literal key ("update.check_for_updates")
            // when run before BYD.i18n.init() resolves, and there's nothing to
            // re-evaluate later.
            '<span data-i18n="update.check_for_updates">' + (window.BYD && BYD.i18n ? BYD.i18n.t('update.check_for_updates') : 'Check for Updates') + '</span>';
        a.addEventListener('click', function (e) {
            e.preventDefault();
            startCheckFlow();
        });
        nav.appendChild(a);
    }

    // ─────────────────────── Check + Modal ───────────────────────

    function startCheckFlow() {
        var link = $('navUpdateLink');
        if (link) link.style.opacity = '0.6';
        toast(BYD.i18n.t('update.checking'), 'info');

        fetch('/api/update/check').then(function (r) { return r.json(); }).then(function (res) {
            if (link) link.style.opacity = '';
            if (res.error) {
                toast(BYD.i18n.t('update.check_failed', {error: res.error}), 'error');
                return;
            }
            if (!res.available) {
                toast(BYD.i18n.t('update.latest_version', {version: res.currentVersion || ''}), 'success');
                return;
            }
            // Update available — fetch preview metadata + show modal.
            fetch('/api/update/preview').then(function (r) { return r.json(); }).then(function (preview) {
                showConfirmModal(res, preview || {});
            }).catch(function () {
                showConfirmModal(res, {});
            });
        }).catch(function (e) {
            if (link) link.style.opacity = '';
            toast(BYD.i18n.t('update.check_failed', {error: (e && e.message) ? e.message : BYD.i18n.t('errors.network')}), 'error');
        });
    }

    function showConfirmModal(res, preview) {
        injectStyles();
        var bg = document.createElement('div');
        bg.className = 'upd-modal-bg';
        bg.id = 'updModalBg';

        var rotates = !!preview.tunnelUrlMayChange;
        var tunnelType = preview.tunnelType || 'unknown';
        var lanIps = (preview.localIpAddresses || []).join(', ');
        var downSec = preview.estimatedDowntimeSeconds || 150;
        var localSec = preview.localRecoverySeconds || 60;
        var recommend = preview.recommendInApp !== false;
        var recommendReason = preview.recommendInAppReason || '';

        var rotationRowHtml = rotates ?
            '<div class="upd-row">' +
              '<span class="upd-icon">🔁</span>' +
              '<div>' + BYD.i18n.t('update.rotation_warning') + '</div>' +
            '</div>' : (
                tunnelType !== 'unknown' && tunnelType !== 'cloudflared' ?
                '<div class="upd-row">' +
                  '<span class="upd-icon">🔗</span>' +
                  '<div>' + BYD.i18n.t('update.tunnel_stable', {type: escapeHtml(tunnelType)}) + '</div>' +
                '</div>' : ''
            );

        var lanRowHtml = lanIps ?
            '<div class="upd-row">' +
              '<span class="upd-icon">🏠</span>' +
              '<div>' + BYD.i18n.t('update.lan_hint', {ips: escapeHtml(lanIps), seconds: Math.round((downSec - localSec))}) + '</div>' +
            '</div>' : '';

        var recommendRowHtml = recommend ?
            '<div class="upd-row upd-rec">' +
              '<span class="upd-icon">⚠️</span>' +
              '<div>' + BYD.i18n.t('update.recommend_inapp_intro', {reason: escapeHtml(recommendReason || BYD.i18n.t('update.recommend_inapp_default'))}) +
              '</div>' +
            '</div>' : '';

        bg.innerHTML =
            '<div class="upd-modal" role="dialog" aria-labelledby="updTitle">' +
              '<h2 id="updTitle">' + BYD.i18n.t('update.title_update_to', {version: escapeHtml(res.remoteVersion || '')}) + '</h2>' +
              '<div class="upd-current">' + escapeHtml(BYD.i18n.t('update.currently_on', {version: res.currentVersion || ''})) + '</div>' +
              (res.releaseNotes ? '<div class="upd-rel-notes">' + escapeHtml(res.releaseNotes) + '</div>' : '') +
              '<div class="upd-warn">' +
                '<div class="upd-row">' +
                  '<span class="upd-icon">⏱️</span>' +
                  '<div>' + BYD.i18n.t('update.downtime_warning', {minutes: Math.round(downSec / 60)}) + '</div>' +
                '</div>' +
                rotationRowHtml +
                lanRowHtml +
                recommendRowHtml +
              '</div>' +
              '<div class="upd-actions">' +
                '<button class="upd-btn upd-btn-cancel" id="updCancel">' + escapeHtml(BYD.i18n.t('common.cancel')) + '</button>' +
                '<button class="upd-btn upd-btn-primary" id="updConfirm">' + escapeHtml(recommend ? BYD.i18n.t('update.install_anyway') : BYD.i18n.t('update.install')) + '</button>' +
              '</div>' +
            '</div>';

        document.body.appendChild(bg);
        $('updCancel').addEventListener('click', closeModal);
        $('updConfirm').addEventListener('click', function () {
            startInstall(res.currentVersion, res.remoteVersion);
        });
        bg.addEventListener('click', function (e) { if (e.target === bg) closeModal(); });
    }

    function closeModal() {
        var bg = $('updModalBg');
        if (bg) bg.remove();
    }

    // ─────────────────────── Install + Progress ───────────────────────

    function startInstall(currentVersion, newVersion) {
        var btn = $('updConfirm');
        if (btn) { btn.disabled = true; btn.textContent = BYD.i18n.t('update.starting'); }

        // Remember the pre-install version so we can detect the bump on
        // reconnect even if the install marker is gone by then.
        try { localStorage.setItem('upd_preInstallVersion', currentVersion || ''); } catch (e) {}
        try { localStorage.setItem('upd_targetVersion', newVersion || ''); } catch (e) {}

        fetch('/api/update/install?confirm=true', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (res) {
                if (res && res.status === 'scheduled') {
                    swapToProgressCard(currentVersion, newVersion);
                    startProgressPolling();
                } else if (res && (res.error || res.success === false)) {
                    if (btn) { btn.disabled = false; btn.textContent = BYD.i18n.t('update.install_anyway'); }
                    toast(BYD.i18n.t('update.install_rejected', {error: res.error || BYD.i18n.t('common.unknown')}), 'error');
                } else {
                    if (btn) { btn.disabled = false; btn.textContent = BYD.i18n.t('update.install_anyway'); }
                    toast(BYD.i18n.t('update.install_failed_start'), 'error');
                }
            })
            .catch(function (e) {
                if (btn) { btn.disabled = false; btn.textContent = BYD.i18n.t('update.install_anyway'); }
                toast(BYD.i18n.t('update.install_request_failed', {error: (e && e.message) ? e.message : BYD.i18n.t('errors.network')}), 'error');
            });
    }

    function swapToProgressCard(currentVersion, newVersion) {
        var modal = document.querySelector('.upd-modal');
        if (!modal) return;
        modal.innerHTML =
            '<h2>' + escapeHtml(BYD.i18n.t('update.updating')) + '</h2>' +
            '<div class="upd-current">' + escapeHtml(currentVersion || '') + ' → ' + escapeHtml(newVersion || '') + '</div>' +
            '<div class="upd-progress">' +
              '<div class="upd-progress-phase" id="updPhase">' + escapeHtml(BYD.i18n.t('update.starting')) + '</div>' +
              '<div class="upd-progress-bar"><div class="upd-progress-fill indeterminate" id="updFill"></div></div>' +
              '<div class="upd-progress-msg" id="updMsg"></div>' +
            '</div>' +
            '<div class="upd-disconnect" id="updDisconnect" style="display:none">' +
              '<strong>' + escapeHtml(BYD.i18n.t('update.reconnecting')) + '</strong> ' + escapeHtml(BYD.i18n.t('update.headunit_installing')) +
              '<div id="updReconnectHint" style="margin-top:8px;color:#9aa6b3;"></div>' +
            '</div>';
    }

    function startProgressPolling() {
        if (pollTimer) clearInterval(pollTimer);
        var consecutiveFailures = 0;
        var sawTerminalPhase = false;  // stopping_daemons or installing — daemon dies here

        pollTimer = setInterval(function () {
            fetch('/api/update/progress').then(function (r) { return r.json(); }).then(function (p) {
                consecutiveFailures = 0;
                renderProgress(p);
                if (p.phase === 'stopping_daemons' || p.phase === 'installing') {
                    sawTerminalPhase = true;
                }
                if (p.phase === 'error') {
                    clearInterval(pollTimer); pollTimer = null;
                    toast(BYD.i18n.t('update.install_failed', {error: p.error || p.message || BYD.i18n.t('common.unknown')}), 'error');
                }
            }).catch(function () {
                consecutiveFailures++;
                // After 2 consecutive failures (~3s), assume the daemon is
                // dying mid-install and switch to reconnect-watch mode IF we
                // already saw stopping_daemons or installing. Otherwise wait
                // for 4 failures (~6s) — covers transient network blips
                // without false-positive "lost" state on a healthy daemon.
                if (consecutiveFailures >= 2 && sawTerminalPhase) {
                    clearInterval(pollTimer); pollTimer = null;
                    enterReconnectMode();
                } else if (consecutiveFailures >= 4) {
                    clearInterval(pollTimer); pollTimer = null;
                    enterReconnectMode();
                }
            });
        }, 1500);
    }

    function renderProgress(p) {
        var phaseEl = $('updPhase');
        var msgEl = $('updMsg');
        var fillEl = $('updFill');
        if (!phaseEl) return;

        var phaseLabels = {
            queued:           BYD.i18n.t('update.phase_queued'),
            downloading:      BYD.i18n.t('update.phase_downloading'),
            verifying:        BYD.i18n.t('update.phase_verifying'),
            stopping_daemons: BYD.i18n.t('update.phase_stopping'),
            installing:       BYD.i18n.t('update.phase_installing'),
            error:            BYD.i18n.t('update.phase_error')
        };
        phaseEl.textContent = phaseLabels[p.phase] || p.phase || '';
        if (msgEl) msgEl.textContent = p.message || '';

        if (fillEl) {
            if (typeof p.percent === 'number' && p.percent >= 0) {
                fillEl.classList.remove('indeterminate');
                fillEl.style.width = p.percent + '%';
            } else {
                fillEl.classList.add('indeterminate');
            }
        }
    }

    // ─────────────────────── Reconnect ───────────────────────

    function enterReconnectMode() {
        var disc = $('updDisconnect');
        if (disc) disc.style.display = 'block';
        var phaseEl = $('updPhase');
        if (phaseEl) phaseEl.textContent = BYD.i18n.t('update.waiting_for_headunit');
        var fillEl = $('updFill');
        if (fillEl) fillEl.classList.add('indeterminate');

        var preInstallVersion = '';
        try { preInstallVersion = localStorage.getItem('upd_preInstallVersion') || ''; } catch (e) {}

        var attempts = 0;
        var maxAttempts = 60;  // 60 * 5s = 5 minutes
        var hintEl = $('updReconnectHint');

        if (reconnectTimer) clearInterval(reconnectTimer);
        reconnectTimer = setInterval(function () {
            attempts++;
            if (hintEl) {
                hintEl.textContent = BYD.i18n.t('update.attempt_of', {n: attempts, total: maxAttempts, remaining: Math.max(0, (maxAttempts - attempts) * 5)});
            }
            fetch('/status', { cache: 'no-store' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (s) {
                    if (!s) return;
                    var newV = s.appVersion || '';
                    // Reconnected. If the version actually advanced, show success.
                    if (newV && (newV !== preInstallVersion || preInstallVersion === '')) {
                        clearInterval(reconnectTimer); reconnectTimer = null;
                        showSuccessAndReload(newV);
                    }
                    // Same version reachable = daemon came back without an
                    // install. Could be a rejected install. Stop the spinner.
                    if (newV && preInstallVersion && newV === preInstallVersion && attempts > 6) {
                        clearInterval(reconnectTimer); reconnectTimer = null;
                        var phEl = $('updPhase');
                        if (phEl) phEl.textContent = BYD.i18n.t('update.version_unchanged');
                    }
                })
                .catch(function () { /* still down */ });

            if (attempts >= maxAttempts) {
                clearInterval(reconnectTimer); reconnectTimer = null;
                var phEl = $('updPhase');
                if (phEl) phEl.textContent = BYD.i18n.t('update.cannot_reach_headunit');
            }
        }, 5000);
    }

    function showSuccessAndReload(newVersion) {
        var modal = document.querySelector('.upd-modal');
        if (modal) {
            modal.innerHTML =
                '<h2>' + escapeHtml(BYD.i18n.t('update.updated_to', {version: newVersion})) + '</h2>' +
                '<div class="upd-current">' + escapeHtml(BYD.i18n.t('update.reloading')) + '</div>';
        }
        try {
            localStorage.setItem('upd_lastSeenVersion', newVersion);
            localStorage.removeItem('upd_preInstallVersion');
            localStorage.removeItem('upd_targetVersion');
        } catch (e) {}
        setTimeout(function () { window.location.reload(); }, 1500);
    }

    // ─────────────────────── Version-drift banner ───────────────────────

    /**
     * Watch /status appVersion. If it changes from a previously-seen value
     * (and we weren't actively running an install), show a one-shot toast.
     * This catches sideloads / Play Store updates / out-of-band installs.
     */
    function startVersionDriftWatch() {
        if (!window.BYD || !BYD.core) return;
        var origRefresh = BYD.core.refreshStatus.bind(BYD.core);
        BYD.core.refreshStatus = async function () {
            var status = await origRefresh();
            try {
                if (status && status.appVersion) {
                    var lastSeen = localStorage.getItem('upd_lastSeenVersion') || '';
                    if (lastSeen && lastSeen !== status.appVersion) {
                        toast(BYD.i18n.t('update.updated_to', {version: status.appVersion}), 'success');
                    }
                    localStorage.setItem('upd_lastSeenVersion', status.appVersion);
                }
            } catch (e) {}
            return status;
        };
    }

    // ─────────────────────── Utility ───────────────────────

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // ─────────────────────── Bootstrap ───────────────────────

    function init() {
        injectStyles();
        injectSidebarEntry();
        startVersionDriftWatch();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        // DOM already parsed (script loaded after page) — defer one tick so
        // BYD.core has initialized.
        setTimeout(init, 0);
    }
})();
