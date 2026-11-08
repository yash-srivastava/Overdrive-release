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

    var UPD_ICONS = {
        clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
        rotate: '<path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M16 16h5v5"/>',
        link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
        home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/>',
        down: '<circle cx="12" cy="12" r="10"/><path d="M12 8v8M8 12l4 4 4-4"/>',
        warn: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>'
    };
    function updSvg(body) {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + body + '</svg>';
    }
    function updIcon(name) {
        return '<span class="upd-icon" aria-hidden="true">' + updSvg(UPD_ICONS[name]) + '</span>';
    }
    var UPD_DONE_ICON = '<div class="upd-done-ico" aria-hidden="true">' + updSvg('<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="M22 4 12 14.01l-3-3"/>') + '</div>';

    // ─────────────────────────── Styles ───────────────────────────

    function injectStyles() {
        if (STYLE_INJECTED) return;
        STYLE_INJECTED = true;
        var css = `
        .upd-modal-bg { position:fixed; top:0; right:0; bottom:0; left:0; background:rgba(0,0,0,0.6); z-index:9000; display:flex; align-items:center; justify-content:center; padding:16px; }
        .upd-modal { background:var(--surface-container-high); color:var(--on-surface); padding:24px; max-width:560px; width:100%; border-radius:28px; border:0; box-shadow:0 24px 64px rgba(0,0,0,0.45); max-height:90vh; overflow:auto; }
        .upd-modal h2 { margin:0 0 6px; font-size:22px; font-weight:700; line-height:1.3; color:var(--on-surface); }
        .upd-modal h2 span.upd-newv { color:var(--primary); }
        .upd-modal .upd-current { color:var(--on-surface-variant); font-size:13px; margin-bottom:16px; }
        .upd-rel-notes { background:var(--well); border:0; border-radius:var(--radius-md); padding:12px 14px; color:var(--on-surface-variant); font-size:13px; line-height:1.55; max-height:160px; overflow:auto; white-space:pre-wrap; margin-bottom:16px; }
        .upd-warn { background:var(--well); border:0; border-radius:var(--radius-lg); padding:4px 16px; }
        .upd-row { display:flex; align-items:flex-start; padding:12px 0; font-size:13px; line-height:1.5; }
        .upd-row > * + * { margin-left:12px; }
        [dir="rtl"] .upd-row > * + * { margin-left:0; margin-right:12px; }
        .upd-row + .upd-row { border-top:1px solid var(--hairline); }
        .upd-row .upd-icon { flex:none; display:flex; width:20px; height:20px; color:var(--on-surface-variant); }
        .upd-row .upd-icon svg { width:20px; height:20px; }
        .upd-row.upd-rec .upd-icon { color:var(--warning); }
        .upd-done-ico { display:flex; align-items:center; justify-content:center; width:48px; height:48px; margin-bottom:12px; border-radius:50%; background:var(--primary-soft); color:var(--primary); }
        .upd-done-ico svg { width:26px; height:26px; }
        .upd-row strong { color:var(--on-surface); font-weight:600; }
        .upd-row code { background:var(--well-2); padding:1px 6px; border-radius:var(--radius-xs); font-family:ui-monospace,monospace; font-size:12px; }
        .upd-row.upd-rec { background:var(--warning-soft); margin:0 -16px; padding:12px 16px; border-radius:var(--radius-md); }
        .upd-row.upd-rec strong { color:var(--warning); }
        .upd-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; margin-top:20px; }
        .upd-actions > * + * { margin-left:8px; }
        [dir="rtl"] .upd-actions > * + * { margin-left:0; margin-right:8px; }
        .upd-btn { min-height:48px; padding:0 22px; border-radius:var(--radius-full); font-weight:600; font-size:14px; border:0; cursor:pointer; font-family:inherit; }
        .upd-btn-cancel { background:transparent; color:var(--primary); }
        .upd-btn-cancel:hover { background:var(--state-hover); }
        .upd-btn-primary { background:var(--primary); color:var(--on-primary); }
        .upd-btn-primary:hover { background:var(--primary); box-shadow:0 6px 18px var(--primary-glow); }
        .upd-btn-primary:disabled { background:var(--well-2); color:var(--on-surface-variant); box-shadow:none; cursor:not-allowed; }

        /* Progress card */
        .upd-progress { padding:8px 0 4px; }
        .upd-progress-phase { font-size:14px; color:var(--on-surface); margin-bottom:10px; min-height:18px; }
        .upd-progress-bar { height:8px; background:var(--well-2); border-radius:var(--radius-full); overflow:hidden; margin-bottom:8px; }
        .upd-progress-fill { height:100%; background:var(--primary); border-radius:var(--radius-full); transition:width 0.4s ease; width:0%; }
        .upd-progress-fill.indeterminate { background:linear-gradient(90deg,var(--well-2) 0%,var(--primary) 50%,var(--well-2) 100%); background-size:200% 100%; animation:updIndet 1.4s linear infinite; width:100%; }
        @keyframes updIndet { 0% { background-position:200% 0; } 100% { background-position:-200% 0; } }
        .upd-progress-msg { font-size:12px; color:var(--on-surface-variant); min-height:14px; }
        .upd-disconnect { background:var(--well); border:1px dashed var(--outline-variant); border-radius:var(--radius-md); padding:14px; margin-top:14px; font-size:13px; color:var(--on-surface-variant); line-height:1.55; }
        .upd-disconnect strong { color:var(--warning); }

        /* Sidebar entry — match existing .nav-link visual treatment */
        .nav-link.nav-link-update .upd-badge { margin-left:auto; background:var(--primary); color:var(--on-primary); font-size:11px; font-weight:700; padding:2px 8px; border-radius:var(--radius-full); line-height:1.2; }
        .nav-link.nav-link-update.has-update svg { color:var(--primary); }
        /* Quiet dot for the alpha "newer exists" hint — subtler than the badge. */
        .nav-link.nav-link-update .upd-dot { margin-left:auto; width:8px; height:8px; border-radius:50%; background:var(--primary); flex:none; }
        [dir="rtl"] .nav-link.nav-link-update .upd-badge,
        [dir="rtl"] .nav-link.nav-link-update .upd-dot { margin-left:0; margin-right:auto; }

        /* Channel switcher (segmented control) */
        .upd-channel { display:flex; border:0; border-radius:var(--radius-full); background:var(--well); padding:4px; overflow:hidden; margin-bottom:16px; }
        .upd-channel-seg { flex:1; min-height:44px; padding:8px; background:transparent; color:var(--on-surface-variant); font-size:13px; font-weight:600; text-align:center; cursor:pointer; border:0; border-radius:var(--radius-full); font-family:inherit; }
        .upd-channel-seg + .upd-channel-seg { border-left:0; margin-left:4px; }
        [dir="rtl"] .upd-channel-seg + .upd-channel-seg { margin-left:0; margin-right:4px; }
        .upd-channel-seg.active { background:var(--secondary-container); color:var(--on-secondary-container); }
        .upd-channel-seg:disabled { cursor:default; opacity:0.7; }
        .upd-channel-desc { font-size:12px; color:var(--on-surface-variant); line-height:1.5; margin:-8px 0 16px; }

        /* Report-a-bug block (Braveheart beta channel) */
        .upd-report { background:var(--warning-soft); border:0; border-radius:var(--radius-md); padding:12px 14px; margin:0 0 16px; }
        .upd-report-how { font-size:13px; color:var(--on-surface); line-height:1.5; margin-bottom:10px; }
        .upd-report-links { display:flex; flex-wrap:wrap; }
        .upd-report-link { font-size:13px; font-weight:600; color:var(--warning); text-decoration:none; min-height:40px; display:inline-flex; align-items:center; padding:0 14px; border:1px solid var(--warning); border-radius:var(--radius-full); }
        /* Margin-based gap (Chrome 58 head unit lacks flex gap) */
        .upd-report-link + .upd-report-link { margin-left:8px; }
        [dir="rtl"] .upd-report-link + .upd-report-link { margin-left:0; margin-right:8px; }

        /* Alpha version catalog */
        .upd-catalog { display:block; max-height:48vh; overflow:auto; margin:4px 0 4px; }
        .upd-ver { display:block; width:100%; text-align:left; background:var(--well); border:1px solid transparent; border-radius:var(--radius-md); padding:12px 14px; color:var(--on-surface); font-family:inherit; cursor:pointer; }
        [dir="rtl"] .upd-ver { text-align:right; }
        /* Sibling spacing via margin (NOT flex gap — Chrome 58 head-unit). */
        .upd-ver + .upd-ver { margin-top:8px; }
        .upd-ver:hover { background:var(--well-2); border-color:var(--outline-variant); }
        .upd-ver-top { display:flex; align-items:center; }
        .upd-ver-name { font-size:14px; font-weight:600; color:var(--on-surface); }
        .upd-ver-pill { margin-left:8px; font-size:11px; font-weight:700; padding:1px 8px; border-radius:var(--radius-full); line-height:1.4; }
        .upd-ver-pill.current { background:var(--primary-soft); color:var(--primary); }
        .upd-ver-pill.older { background:var(--warning-soft); color:var(--warning); }
        .upd-ver-date { margin-left:auto; font-size:12px; color:var(--on-surface-variant); }
        [dir="rtl"] .upd-ver-pill { margin-left:0; margin-right:8px; }
        [dir="rtl"] .upd-ver-date { margin-left:0; margin-right:auto; }
        .upd-ver-notes { font-size:12px; color:var(--on-surface-variant); line-height:1.5; margin-top:6px; max-height:54px; overflow:hidden; white-space:pre-wrap; }
        .upd-catalog-empty { padding:18px 4px; font-size:13px; color:var(--on-surface-variant); text-align:center; }
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
        // aria-label doubles as the tooltip text in collapsed-rail variants.
        a.setAttribute('aria-label', 'Check for Updates');
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
            // re-evaluate later. BYD.i18n.t() returns null while the catalog
            // is still loading — guard with the English fallback so we never
            // render the literal "null" while the user waits for the first
            // hydrate() pass.
            '<span data-i18n="update.check_for_updates">' + ((window.BYD && BYD.i18n && BYD.i18n.t('update.check_for_updates')) || 'Check for Updates') + '</span>';
        a.addEventListener('click', function (e) {
            e.preventDefault();
            startCheckFlow();
        });

        // Place the link UNDER the About entry so the two share the About
        // group — "Check for Updates" is conceptually meta information about
        // the app, not a top-level destination. Falls back to appendChild
        // for pages that don't render an About link.
        var aboutLink = nav.querySelector('.nav-link[href="about.html"]');
        if (aboutLink && aboutLink.parentNode) {
            // insertBefore(a, aboutLink.nextSibling) lands the new link
            // immediately after About, which is where the About group ends
            // (no further dividers after the About header in NAV_ITEMS).
            aboutLink.parentNode.insertBefore(a, aboutLink.nextSibling);
        } else {
            nav.appendChild(a);
        }

        // app-shell.js wired the About group BEFORE we injected this link,
        // so the new sibling isn't tracked as a member yet. Ask the shell
        // to rewire — the call is idempotent (headers stay wired once,
        // the collapse state is reapplied to current siblings).
        if (window.OverdriveAppShell && typeof window.OverdriveAppShell.rewireNavGroups === 'function') {
            window.OverdriveAppShell.rewireNavGroups();
        }
        var sidebar = document.getElementById('sidebar');
        if (sidebar && sidebar.classList.contains('open') &&
            window.OverdriveAppShell &&
            typeof window.OverdriveAppShell.revealAboutMenuActions === 'function') {
            window.OverdriveAppShell.revealAboutMenuActions(sidebar);
            requestAnimationFrame(function () {
                window.OverdriveAppShell.revealAboutMenuActions(sidebar);
            });
        }
    }

    // ─────────────────────── Check + Modal ───────────────────────

    function startCheckFlow() {
        var link = $('navUpdateLink');
        if (link) link.style.opacity = '0.6';
        toast(BYD.i18n.t('update.checking'), 'info');

        // Defensive: parse body as text first so we can surface a useful
        // error when the server returns an empty body (the previous "JSON
        // parse: unexpected end of data" crash) or non-JSON content. The
        // common cause is a 20s update-check that hits an upstream
        // exception and is swallowed by the HTTP loop's outer catch — the
        // socket closes with no body. We treat that as a generic "check
        // failed" and surface it cleanly instead of crashing the toast.
        fetch('/api/update/check').then(function (r) {
            return r.text().then(function (text) {
                return { status: r.status, ok: r.ok, body: text };
            });
        }).then(function (resp) {
            if (link) link.style.opacity = '';
            var res;
            try {
                res = resp.body ? JSON.parse(resp.body) : null;
            } catch (e) {
                res = null;
            }
            if (!res) {
                var hint = !resp.body
                    ? BYD.i18n.t('errors.network')
                    : ('HTTP ' + resp.status);
                toast(BYD.i18n.t('update.check_failed', {error: hint}), 'error');
                return;
            }
            if (res.error) {
                toast(BYD.i18n.t('update.check_failed', {error: res.error}), 'error');
                return;
            }
            // Alpha (archive): "Check for Updates" opens the version catalog
            // rather than a single install prompt. The /check response tells
            // us the channel so we don't need a second round-trip to decide.
            if (res.channel === 'alpha') {
                startCatalogFlow();
                return;
            }
            if (!res.available) {
                // Braveheart, up to date. Show a small modal (not just a toast)
                // so the channel switcher is reachable — this is where a
                // braveheart user goes to switch back to Alpha.
                showUpToDateModal(res.currentVersion || '', res.channel || 'braveheart');
                return;
            }
            // Braveheart update available — fetch preview metadata + show modal.
            // Same defensive body parse on the preview endpoint.
            fetch('/api/update/preview').then(function (r) {
                return r.text().then(function (text) { return text; });
            }).then(function (text) {
                var preview = null;
                try { preview = text ? JSON.parse(text) : null; } catch (e) {}
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
        // Replace any existing modal (e.g. the alpha catalog) rather than
        // stacking a second #updModalBg — duplicate ids would make $() and
        // the Cancel/close handlers target the wrong element.
        closeModal();
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
              updIcon('rotate') +
              '<div>' + BYD.i18n.t('update.rotation_warning') + '</div>' +
            '</div>' : (
                tunnelType !== 'unknown' && tunnelType !== 'cloudflared' ?
                '<div class="upd-row">' +
                  updIcon('link') +
                  '<div>' + BYD.i18n.t('update.tunnel_stable', {type: escapeHtml(tunnelType)}) + '</div>' +
                '</div>' : ''
            );

        var lanRowHtml = lanIps ?
            '<div class="upd-row">' +
              updIcon('home') +
              '<div>' + BYD.i18n.t('update.lan_hint', {ips: escapeHtml(lanIps), seconds: Math.round((downSec - localSec))}) + '</div>' +
            '</div>' : '';

        var recommendRowHtml = recommend ?
            '<div class="upd-row upd-rec">' +
              updIcon('warn') +
              '<div>' + BYD.i18n.t('update.recommend_inapp_intro', {reason: escapeHtml(recommendReason || BYD.i18n.t('update.recommend_inapp_default'))}) +
              '</div>' +
            '</div>' : '';

        // Downgrade note — only for an alpha pick that's older than installed.
        var downgradeRowHtml = res._isDowngrade ?
            '<div class="upd-row">' +
              updIcon('down') +
              '<div>' + BYD.i18n.t('update.downgrade_note') + '</div>' +
            '</div>' : '';

        bg.innerHTML =
            '<div class="upd-modal" role="dialog" aria-labelledby="updTitle">' +
              '<h2 id="updTitle">' + BYD.i18n.t('update.title_update_to', {version: escapeHtml(res.remoteVersion || '')}) + '</h2>' +
              '<div class="upd-current">' + escapeHtml(BYD.i18n.t('update.currently_on', {version: res.currentVersion || ''})) + '</div>' +
              (res.releaseNotes ? '<div class="upd-rel-notes">' + escapeHtml(res.releaseNotes) + '</div>' : '') +
              '<div class="upd-warn">' +
                '<div class="upd-row">' +
                  updIcon('clock') +
                  '<div>' + BYD.i18n.t('update.downtime_warning', {minutes: Math.round(downSec / 60)}) + '</div>' +
                '</div>' +
                rotationRowHtml +
                lanRowHtml +
                downgradeRowHtml +
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
            startInstall(res.currentVersion, res.remoteVersion, res._targetTag);
        });
        bg.addEventListener('click', function (e) { if (e.target === bg) closeModal(); });
    }

    function closeModal() {
        var bg = $('updModalBg');
        if (bg) bg.remove();
    }

    // Braveheart "up to date" — a small modal carrying the channel switcher so
    // the user can drop back to Alpha (the switcher is otherwise only in the
    // alpha catalog / braveheart confirm).
    function showUpToDateModal(currentVersion, channel) {
        injectStyles();
        closeModal();
        var bg = document.createElement('div');
        bg.className = 'upd-modal-bg';
        bg.id = 'updModalBg';
        bg.innerHTML =
            '<div class="upd-modal" role="dialog">' +
              UPD_DONE_ICON +
              '<h2>' + escapeHtml(BYD.i18n.t('update.latest_version', {version: currentVersion})) + '</h2>' +
              '<div id="updChannelMount"></div>' +
              '<div class="upd-channel-desc">' + escapeHtml(BYD.i18n.t('update.channel_braveheart_desc')) + '</div>' +
              '<div id="updReportMount"></div>' +
              '<div class="upd-actions">' +
                '<button class="upd-btn upd-btn-cancel" id="updCatClose">' + escapeHtml(BYD.i18n.t('common.close')) + '</button>' +
              '</div>' +
            '</div>';
        document.body.appendChild(bg);
        $('updCatClose').addEventListener('click', closeModal);
        bg.addEventListener('click', function (e) { if (e.target === bg) closeModal(); });
        var mount = $('updChannelMount');
        if (mount) {
            mount.appendChild(buildChannelSwitcher(channel, function (newCh) {
                closeModal();
                // Re-run the check on the newly-selected channel.
                startCheckFlow();
            }));
        }
        // Report-a-bug links (braveheart only).
        var reportMount = $('updReportMount');
        var report = buildReportBlock(channel);
        if (reportMount && report) reportMount.appendChild(report);
    }

    // ─────────────────────── Channel switcher ───────────────────────

    // Build the segmented channel control. onSwitch(newChannel) is called
    // after the server confirms the change. Rendered at the top of the alpha
    // catalog and the braveheart confirm modal so the user can flip channels
    // from either surface.
    function buildChannelSwitcher(activeChannel, onSwitch) {
        var wrap = document.createElement('div');
        wrap.className = 'upd-channel';
        var channels = [
            { id: 'alpha', label: BYD.i18n.t('update.channel_alpha') },
            { id: 'braveheart', label: BYD.i18n.t('update.channel_braveheart') }
        ];
        channels.forEach(function (c) {
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'upd-channel-seg' + (c.id === activeChannel ? ' active' : '');
            b.textContent = c.label;
            b.addEventListener('click', function () {
                if (c.id === activeChannel) return;
                // Disable both segments while the switch is in flight.
                var segs = wrap.querySelectorAll('.upd-channel-seg');
                for (var i = 0; i < segs.length; i++) segs[i].disabled = true;
                fetch('/api/update/channel?value=' + encodeURIComponent(c.id), { method: 'POST' })
                    .then(function (r) { return r.json(); })
                    .then(function (res) {
                        if (res && res.channel === c.id) {
                            // Braveheart gets an instability heads-up instead of
                            // the bland "switched" confirmation.
                            if (c.id === 'braveheart') {
                                toast(BYD.i18n.t('update.switched_to_braveheart_warn'), 'info');
                            } else {
                                toast(BYD.i18n.t('update.switched_to_channel', {channel: c.label}), 'success');
                            }
                            onSwitch(c.id);
                        } else {
                            for (var i = 0; i < segs.length; i++) segs[i].disabled = false;
                            toast(BYD.i18n.t('update.switch_channel_failed', {error: (res && res.error) || BYD.i18n.t('common.unknown')}), 'error');
                        }
                    })
                    .catch(function (e) {
                        for (var i = 0; i < segs.length; i++) segs[i].disabled = false;
                        toast(BYD.i18n.t('update.switch_channel_failed', {error: (e && e.message) ? e.message : BYD.i18n.t('errors.network')}), 'error');
                    });
            });
            wrap.appendChild(b);
        });
        return wrap;
    }

    // Report-a-bug block for the Braveheart (beta) channel: the "send logs →
    // share the code" instruction + the community links. Returns null for
    // non-braveheart channels (no clutter on the stable channel).
    function buildReportBlock(channel) {
        if (channel !== 'braveheart') return null;
        var wrap = document.createElement('div');
        wrap.className = 'upd-report';
        var how = document.createElement('div');
        how.className = 'upd-report-how';
        how.textContent = BYD.i18n.t('update.report_how');
        wrap.appendChild(how);

        var links = document.createElement('div');
        links.className = 'upd-report-links';
        var defs = [
            { key: 'report_discord', url: 'report_discord_url', fb: 'https://discord.gg/PZutk9fg4h' },
            { key: 'report_github', url: 'report_github_url', fb: 'https://github.com/yash-srivastava/Overdrive-release/issues' },
            { key: 'report_whatsapp', url: 'report_whatsapp_url', fb: 'https://chat.whatsapp.com/HChmriCWgr9KwAtE6OEkiM' }
        ];
        defs.forEach(function (d) {
            var url = BYD.i18n.t('update.' + d.url);
            if (!url || url === ('update.' + d.url)) url = d.fb; // fall back if key missing
            if (!url) return;
            var a = document.createElement('a');
            a.className = 'upd-report-link';
            a.href = url;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = BYD.i18n.t('update.' + d.key);
            links.appendChild(a);
        });
        wrap.appendChild(links);
        return wrap;
    }

    // ─────────────────────── Alpha version catalog ───────────────────────

    function startCatalogFlow() {
        injectStyles();
        var bg = document.createElement('div');
        bg.className = 'upd-modal-bg';
        bg.id = 'updModalBg';
        bg.innerHTML =
            '<div class="upd-modal" role="dialog" aria-labelledby="updCatTitle">' +
              '<h2 id="updCatTitle">' + escapeHtml(BYD.i18n.t('update.catalog_title')) + '</h2>' +
              '<div id="updChannelMount"></div>' +
              '<div class="upd-channel-desc">' + escapeHtml(BYD.i18n.t('update.channel_alpha_desc')) + '</div>' +
              '<div class="upd-catalog" id="updCatalog">' +
                '<div class="upd-catalog-empty">' + escapeHtml(BYD.i18n.t('update.checking')) + '</div>' +
              '</div>' +
              '<div class="upd-actions">' +
                '<button class="upd-btn upd-btn-cancel" id="updCatClose">' + escapeHtml(BYD.i18n.t('common.close') || 'Close') + '</button>' +
              '</div>' +
            '</div>';
        document.body.appendChild(bg);
        $('updCatClose').addEventListener('click', closeModal);
        bg.addEventListener('click', function (e) { if (e.target === bg) closeModal(); });

        var mount = $('updChannelMount');
        if (mount) {
            mount.appendChild(buildChannelSwitcher('alpha', function (newCh) {
                // Switched to braveheart from the catalog — close and run the
                // braveheart check so the user immediately sees its flow.
                closeModal();
                if (newCh === 'braveheart') startCheckFlow();
            }));
        }
        loadCatalog();
    }

    function loadCatalog() {
        fetch('/api/update/versions').then(function (r) {
            return r.text().then(function (t) { return t; });
        }).then(function (text) {
            var data = null;
            try { data = text ? JSON.parse(text) : null; } catch (e) {}
            var cat = $('updCatalog');
            if (!cat) return;
            if (!data || data.error) {
                renderCatalogMessage(cat, BYD.i18n.t('update.check_failed', {error: (data && data.error) || BYD.i18n.t('errors.network')}), true);
                return;
            }
            var versions = data.versions || [];
            if (!versions.length) {
                renderCatalogMessage(cat, BYD.i18n.t('update.catalog_empty'), false);
                return;
            }
            renderCatalog(cat, versions, data.currentVersion || '');
        }).catch(function (e) {
            var cat = $('updCatalog');
            if (cat) renderCatalogMessage(cat, BYD.i18n.t('update.check_failed', {error: (e && e.message) ? e.message : BYD.i18n.t('errors.network')}), true);
        });
    }

    function renderCatalogMessage(cat, msg, withRetry) {
        cat.innerHTML = '';
        var empty = document.createElement('div');
        empty.className = 'upd-catalog-empty';
        empty.textContent = msg;
        cat.appendChild(empty);
        if (withRetry) {
            var retry = document.createElement('button');
            retry.type = 'button';
            retry.className = 'upd-btn upd-btn-cancel';
            retry.style.marginTop = '10px';
            retry.textContent = BYD.i18n.t('update.catalog_retry') || BYD.i18n.t('common.retry') || 'Retry';
            retry.addEventListener('click', function () {
                cat.innerHTML = '<div class="upd-catalog-empty">' + escapeHtml(BYD.i18n.t('update.checking')) + '</div>';
                loadCatalog();
            });
            cat.appendChild(retry);
        }
    }

    function renderCatalog(cat, versions, currentVersion) {
        cat.innerHTML = '';
        versions.forEach(function (v) {
            var row = document.createElement('button');
            row.type = 'button';
            row.className = 'upd-ver';

            var top = document.createElement('div');
            top.className = 'upd-ver-top';

            var name = document.createElement('span');
            name.className = 'upd-ver-name';
            name.textContent = v.version || v.tag || '';
            top.appendChild(name);

            // Relation pill: only "current" and "older" get a chip; "newer"
            // and "unknown" stay unmarked to avoid clutter.
            if (v.relation === 'current') {
                top.appendChild(makePill('current', BYD.i18n.t('update.pill_installed')));
            } else if (v.relation === 'older') {
                top.appendChild(makePill('older', BYD.i18n.t('update.pill_older') || BYD.i18n.t('update.install_downgrade')));
            }

            if (v.publishedAt) {
                var date = document.createElement('span');
                date.className = 'upd-ver-date';
                date.textContent = formatDate(v.publishedAt);
                top.appendChild(date);
            }
            row.appendChild(top);

            if (v.releaseNotes) {
                var notes = document.createElement('div');
                notes.className = 'upd-ver-notes';
                notes.textContent = firstLines(v.releaseNotes, 3);
                row.appendChild(notes);
            }

            row.addEventListener('click', function () {
                // Installing the currently-installed version is a no-op for
                // the user; still allow it (re-install) but make downgrades
                // explicit via the confirm modal's downgrade note.
                var isDowngrade = v.relation === 'older';
                showCatalogConfirm(v, currentVersion, isDowngrade);
            });
            cat.appendChild(row);
        });
    }

    function makePill(cls, text) {
        var pill = document.createElement('span');
        pill.className = 'upd-ver-pill ' + cls;
        pill.textContent = text;
        return pill;
    }

    // A specific archived version was tapped — reuse the confirm modal shape,
    // then install with &version=<tag>. Fetches preview for the tunnel/LAN
    // warnings, same as the braveheart path.
    function showCatalogConfirm(v, currentVersion, isDowngrade) {
        fetch('/api/update/preview').then(function (r) {
            return r.text().then(function (t) { return t; });
        }).then(function (text) {
            var preview = null;
            try { preview = text ? JSON.parse(text) : null; } catch (e) {}
            var res = {
                currentVersion: currentVersion,
                remoteVersion: v.version || v.tag,
                releaseNotes: v.releaseNotes || '',
                _targetTag: v.tag,
                _isDowngrade: isDowngrade
            };
            showConfirmModal(res, preview || {});
        }).catch(function () {
            showConfirmModal({
                currentVersion: currentVersion,
                remoteVersion: v.version || v.tag,
                releaseNotes: v.releaseNotes || '',
                _targetTag: v.tag,
                _isDowngrade: isDowngrade
            }, {});
        });
    }

    // ─────────────────────── Install + Progress ───────────────────────

    // targetTag is set for alpha picks (a specific archived version) and
    // omitted for braveheart (rolling head). When present it is appended as
    // &version=<tag> so the server resolves that exact release.
    function startInstall(currentVersion, newVersion, targetTag) {
        var btn = $('updConfirm');
        if (btn) { btn.disabled = true; btn.textContent = BYD.i18n.t('update.starting'); }

        // Remember the pre-install version so we can detect the bump on
        // reconnect even if the install marker is gone by then. For a
        // downgrade the target is "older" than current — store it so the
        // reconnect banner fires on ANY version change, not just an increase.
        try { localStorage.setItem('upd_preInstallVersion', currentVersion || ''); } catch (e) {}
        try { localStorage.setItem('upd_targetVersion', newVersion || ''); } catch (e) {}

        var url = '/api/update/install?confirm=true';
        if (targetTag) url += '&version=' + encodeURIComponent(targetTag);
        fetch(url, { method: 'POST' })
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
                // The idle sentinel is what both progress readers return for a
                // torn / empty / malformed / stale-recovered read (the r1/r4
                // alignment so a torn read is a harmless keep-polling no-op, not
                // a connection failure). The non-atomic writeProgress truncates-
                // then-rewrites every ~500ms during download, so a poll can land
                // mid-install on the zero-length file. Skip the render for idle
                // (mirrors MainActivity's `"idle" -> {}` branch): keep the prior
                // phase label and bar fill instead of flashing the untranslated
                // word "idle" and collapsing a held-100 bar to indeterminate for
                // one tick. The poll already counted as healthy above.
                if (p.phase === 'idle') { return; }
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
                    enterReconnectMode(true);  // install was underway → reconnect = success
                } else if (consecutiveFailures >= 4) {
                    clearInterval(pollTimer); pollTimer = null;
                    enterReconnectMode(sawTerminalPhase);
                }
            });
        }, 1500);
    }

    // Stall detection: track when the download percent last changed so we
    // can replace the message with a stall hint after 60s of no progress.
    // Reset when phase changes off downloading (so verify/install don't
    // count their own pauses as stalls).
    var _lastDlPercent = -2;
    var _lastDlPercentAt = 0;
    // Bar latch: once the download hits 100, hold the bar at 100 across
    // the verify → stopping → installing transitions even though the
    // backend reports percent=-1 for those phases. Without the latch, the
    // bar visibly snaps from 100% to indeterminate-shimmer the moment the
    // phase flips, which reads as "the bar broke" right after a successful
    // download. The latch is dropped if we ever come back to a
    // determinate downloading state (retry case).
    var _holdAt100 = false;

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

        var msg = p.message || '';
        // Stall hint while downloading. The poll runs every 1.5s so a
        // genuine slow download still ticks; only a stuck transfer goes
        // 60s without a percent change.
        if (p.phase === 'downloading') {
            var now = Date.now();
            if (typeof p.percent === 'number' && p.percent !== _lastDlPercent) {
                _lastDlPercent = p.percent;
                _lastDlPercentAt = now;
            } else if (_lastDlPercentAt === 0) {
                _lastDlPercentAt = now;
            }
            if (now - _lastDlPercentAt > 60000) {
                msg = BYD.i18n.t('update.download_stalled') ||
                      'Download seems stuck — check the head unit’s connection or retry.';
            }
        } else {
            // Reset the watch so a slow verify/install phase doesn't
            // inherit the download's stall timer.
            _lastDlPercent = -2;
            _lastDlPercentAt = 0;
        }
        if (msgEl) msgEl.textContent = msg;

        // Track whether we should latch at 100% for the post-download phases.
        if (p.phase === 'downloading' && typeof p.percent === 'number' && p.percent >= 100) {
            _holdAt100 = true;
        } else if (p.phase === 'downloading' && typeof p.percent === 'number' && p.percent >= 0 && p.percent < 100) {
            // Came back to determinate downloading (e.g. user clicked Retry).
            _holdAt100 = false;
        }

        if (fillEl) {
            if (typeof p.percent === 'number' && p.percent >= 0) {
                fillEl.classList.remove('indeterminate');
                fillEl.style.width = p.percent + '%';
            } else if (_holdAt100 && p.phase !== 'error' && p.phase !== 'idle') {
                fillEl.classList.remove('indeterminate');
                fillEl.style.width = '100%';
            } else {
                fillEl.classList.add('indeterminate');
            }
        }
    }

    // ─────────────────────── Reconnect ───────────────────────

    // installWasUnderway: true when the progress poll saw stopping_daemons /
    // installing before the daemon died — i.e. the install actually started, so
    // a successful reconnect means SUCCESS even if the version string is
    // unchanged. This matters for the braveheart rolling channel: it replaces
    // the APK on the same tag, and if the re-uploaded asset filename keeps the
    // same version label (or has no parseable version) the VERSION_FILE-derived
    // appVersion does NOT change across a successful in-place update. (appVersion
    // comes from getDisplayVersionFromFile() = VERSION_FILE-first, so it usually
    // DOES advance when the asset filename version bumps — we just can't RELY on
    // it.) Keying success on a version change would falsely report "install may
    // have failed" on every same-label braveheart update, so we don't.
    function enterReconnectMode(installWasUnderway) {
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
                    // The daemon is back online.
                    if (installWasUnderway) {
                        // Install had started (we saw stopping_daemons/installing)
                        // and the daemon came back. On the braveheart rolling
                        // channel a successful in-place update keeps the same
                        // versionName, so a version compare can't confirm success
                        // — BUT the daemon's detached install script relaunches
                        // the daemon on FAILURE too (old APK + phase=error written
                        // to /api/update/progress), so "reachable again" alone is
                        // not proof of success. Re-read the progress file once:
                        // phase=error means the pm install actually failed and we
                        // must surface it (matching the app's consumeFailedUpdateError
                        // toast), not falsely report "✅ updated". Anything else
                        // (idle / install kept polling but progress already cleared)
                        // falls through to the optimistic success path.
                        clearInterval(reconnectTimer); reconnectTimer = null;
                        fetch('/api/update/progress', { cache: 'no-store' })
                            .then(function (pr) { return pr.ok ? pr.json() : null; })
                            .then(function (p) {
                                if (p && p.phase === 'error') {
                                    toast(BYD.i18n.t('update.install_failed', {error: p.error || p.message || BYD.i18n.t('common.unknown')}), 'error');
                                    return;
                                }
                                showSuccessAndReload(newV || preInstallVersion);
                            })
                            .catch(function () {
                                // Progress unreadable on the race (relaunched
                                // activity may have already cleared it). The
                                // version-unchanged hint below can't run from here,
                                // so keep the existing optimistic behaviour.
                                showSuccessAndReload(newV || preInstallVersion);
                            });
                        return;
                    }
                    // Install never reached a terminal phase. A version change
                    // still proves something landed; otherwise it's a rejected /
                    // no-op install — stop the spinner with the unchanged note.
                    if (newV && (newV !== preInstallVersion || preInstallVersion === '')) {
                        clearInterval(reconnectTimer); reconnectTimer = null;
                        showSuccessAndReload(newV);
                    } else if (newV && preInstallVersion && newV === preInstallVersion && attempts > 6) {
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
                UPD_DONE_ICON +
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
                // Passive update notification — piggybacks on the status poll
                // so we don't add another timer. ACC state from /status gates
                // the auto-modal (never interrupt while driving).
                maybePassiveCheck(status);
            } catch (e) {}
            return status;
        };
    }

    // ─────────────────────── Passive notify ───────────────────────
    //
    // Channel decides the posture (the channel toggle IS the opt-in):
    //   braveheart → throttled notify-only check: light the sidebar badge,
    //                fire a one-shot toast per new build, and auto-open the
    //                install modal when next foregrounded AND parked (ACC off).
    //   alpha      → pull-only: only a quiet dot when a newer alpha version
    //                exists. No toast, no modal.
    // NOTE: a passive check NEVER installs — it stops at "available" and the
    // user still taps to install, so the disabled-auto-update footgun (which
    // auto-installed) does not recur.

    var CHECK_THROTTLE_MS = 6 * 60 * 60 * 1000; // 6h

    function maybePassiveCheck(status) {
        var now = Date.now();
        var last = 0;
        try { last = parseInt(localStorage.getItem('upd_lastAutoCheck') || '0', 10) || 0; } catch (e) {}
        if (now - last < CHECK_THROTTLE_MS) return;
        try { localStorage.setItem('upd_lastAutoCheck', String(now)); } catch (e) {}

        var accOff = !(status && status.acc);
        fetch('/api/update/check').then(function (r) { return r.text(); }).then(function (text) {
            var res = null;
            try { res = text ? JSON.parse(text) : null; } catch (e) {}
            if (!res || res.error) return;
            if (res.channel === 'braveheart') {
                if (res.available) {
                    setBadge(true, false);
                    notifyBraveheartAvailable(res, accOff);
                } else {
                    setBadge(false, false);
                }
            } else {
                // Alpha — quiet dot only when a newer version exists. We must
                // enumerate to know "newer", but only do so cheaply when the
                // throttle just elapsed (we're already here).
                checkAlphaNewer();
            }
        }).catch(function () { /* offline — try again next throttle window */ });
    }

    function notifyBraveheartAvailable(res, accOff) {
        var target = res.remoteVersion || '';
        var lastNotified = '', lastModal = '';
        try { lastNotified = localStorage.getItem('upd_lastNotifiedVersion') || ''; } catch (e) {}
        try { lastModal = localStorage.getItem('upd_lastModalVersion') || ''; } catch (e) {}

        // Toast: one-shot per build, fired whether parked or driving.
        if (lastNotified !== target) {
            try { localStorage.setItem('upd_lastNotifiedVersion', target); } catch (e) {}
            toast(BYD.i18n.t('update.notify_available', {version: target}), 'info');
        }

        // Auto-open the install modal the FIRST time we're parked for this
        // build — tracked by a SEPARATE key so a first check that happens
        // while driving (modal correctly skipped) doesn't permanently suppress
        // the modal on the next parked check. While driving (ACC on) the badge
        // is the only surface.
        if (accOff && lastModal !== target && !$('updModalBg')) {
            try { localStorage.setItem('upd_lastModalVersion', target); } catch (e) {}
            fetch('/api/update/preview').then(function (r) { return r.text(); }).then(function (t) {
                var preview = null;
                try { preview = t ? JSON.parse(t) : null; } catch (e) {}
                if (!$('updModalBg')) showConfirmModal(res, preview || {});
            }).catch(function () {
                if (!$('updModalBg')) showConfirmModal(res, {});
            });
        }
    }

    function checkAlphaNewer() {
        fetch('/api/update/versions').then(function (r) { return r.text(); }).then(function (text) {
            var data = null;
            try { data = text ? JSON.parse(text) : null; } catch (e) {}
            if (!data || !data.versions) { setBadge(false, false); return; }
            var hasNewer = false;
            for (var i = 0; i < data.versions.length; i++) {
                if (data.versions[i].relation === 'newer') { hasNewer = true; break; }
            }
            setBadge(false, hasNewer); // dot, not badge
        }).catch(function () {});
    }

    // badge = prominent braveheart "update available"; dot = quiet alpha hint.
    function setBadge(showBadge, showDot) {
        var link = $('navUpdateLink');
        if (!link) return;
        link.classList.toggle('has-update', !!(showBadge || showDot));
        // Remove any prior indicator.
        var prev = link.querySelector('.upd-badge, .upd-dot');
        if (prev) prev.parentNode.removeChild(prev);
        if (showBadge) {
            var b = document.createElement('span');
            b.className = 'upd-badge';
            b.textContent = BYD.i18n.t('update.badge_new') || 'NEW';
            link.appendChild(b);
        } else if (showDot) {
            var d = document.createElement('span');
            d.className = 'upd-dot';
            link.appendChild(d);
        }
    }

    // ─────────────────────── Utility ───────────────────────

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // "2026-05-29T10:00:00Z" → a short locale date; falls back to the raw
    // date portion if Date parsing isn't reliable on this WebView.
    function formatDate(iso) {
        if (!iso) return '';
        try {
            var d = new Date(iso);
            if (!isNaN(d.getTime())) {
                return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
            }
        } catch (e) {}
        return String(iso).split('T')[0];
    }

    // First N non-empty lines of release notes, joined — keeps the catalog
    // row compact without rendering full markdown.
    function firstLines(text, n) {
        if (!text) return '';
        var lines = String(text).split('\n');
        var out = [];
        for (var i = 0; i < lines.length && out.length < n; i++) {
            var t = lines[i].trim().replace(/^#+\s*/, '').replace(/^[-*]\s*/, '• ');
            if (t) out.push(t);
        }
        return out.join('\n');
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
