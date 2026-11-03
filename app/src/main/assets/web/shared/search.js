/**
 * Overdrive — Unified search (command palette).
 *
 * One box to jump to any page, tab, or individual setting across the whole
 * web UI. The app is multi-page (each local/*.html is its own document), so a
 * palette mounted on one page can't see the others' DOM — instead we crawl the
 * pages once, cache a compact index in localStorage keyed by app version, and
 * resolve every display label live through BYD.i18n so results are localized
 * and we add no new i18n strings.
 *
 * Loaded lazily by app-shell.js (ensureSearchScript) on idle, so the index
 * pre-warms and deep-link arrivals work on every shell page. app-shell wires
 * the triggers (sidebar box, mobile-header icon, Ctrl/Cmd+K, '/') and, if the
 * user triggers before this script lands, sets window.__odSearchPendingOpen,
 * which we drain on load.
 *
 * Index sources:
 *   pages    — OverdriveAppShell.getPages()  (NAV_ITEMS: href + i18n + label)
 *   tabs     — distinct [data-tab] ids in each crawled page; labels harvested
 *              from the page's OT_TABS object literals (handles the ternary in
 *              notifications.html by scanning globally)
 *   settings — .setting-name[data-i18n] inside .card[data-tab]
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor): no const/let, no arrow
 * functions, no template strings, no async/await. XHR + DOMParser +
 * localStorage are all used elsewhere in this codebase.
 */
(function () {
    'use strict';

    var SCHEMA_VERSION = 2;
    var CACHE_KEY = 'overdrive.searchIndex.v1';
    var MAX_RESULTS = 30;
    // Cross-navigation handoff for a setting deep-link. We can't put the target
    // in the URL: the OD static file server 404s any HTML path that carries a
    // query string (trips.html?x → 404), and the hash is reserved for the tab
    // id (app-tabs.js matches it exactly). sessionStorage survives the same-tab
    // navigation and is read + cleared on arrival.
    var FIND_KEY = 'od-search-find';

    // ============================================================
    // Small helpers
    // ============================================================

    function trim(s) { return (s || '').replace(/^\s+|\s+$/g, ''); }

    // Lowercase + strip diacritics so a mark-less query matches accented text
    // (Vietnamese users commonly type without marks: "bat" → "Bật", "do xe" →
    // "Đỗ xe"). NFD decomposes most accents into combining marks we drop;
    // đ/Đ is a distinct base letter NFD leaves alone, so map it explicitly.
    // Applied to BOTH the query and the indexed text (every match goes through
    // norm), so folding stays symmetric. normalize() exists on the Chrome 58
    // head-unit floor.
    function norm(s) {
        var x = (s || '').toLowerCase();
        try { x = x.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
        return x.replace(/đ/g, 'd');
    }

    function t(key, fallback) {
        try {
            if (window.BYD && window.BYD.i18n && typeof window.BYD.i18n.t === 'function') {
                var v = window.BYD.i18n.t(key);
                if (v != null && v !== key) return v;
            }
        } catch (e) { /* ignore */ }
        return fallback != null ? fallback : key;
    }

    function escapeHtml(s) {
        return (s || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function humanize(id) {
        return (id || '').replace(/[-_]/g, ' ').replace(/\b\w/g, function (c) {
            return c.toUpperCase();
        });
    }

    function basename(href) {
        var h = href || '';
        var q = h.indexOf('?'); if (q >= 0) h = h.substring(0, q);
        var hs = h.indexOf('#'); if (hs >= 0) h = h.substring(0, hs);
        var idx = h.lastIndexOf('/');
        h = idx >= 0 ? h.substring(idx + 1) : h;
        return h || 'index.html';
    }

    function currentBasename() {
        var p = window.location.pathname || '';
        var idx = p.lastIndexOf('/');
        var b = idx >= 0 ? p.substring(idx + 1) : p;
        return b || 'index.html';
    }

    function getPages() {
        try {
            if (window.OverdriveAppShell &&
                typeof window.OverdriveAppShell.getPages === 'function') {
                return window.OverdriveAppShell.getPages() || [];
            }
        } catch (e) { /* ignore */ }
        return [];
    }

    function appVersion() {
        var el = document.getElementById('appVersion');
        return el && el.textContent ? trim(el.textContent) : '';
    }

    // ============================================================
    // Index extraction
    // ============================================================

    // Harvest id -> i18nKey from a page's OT_TABS object literals. We scan each
    // inline <script> block that mentions OT_TABS (rather than slicing to the
    // next </script>, which breaks when OT_TABS also appears in a comment
    // before the defining script, as in notifications.html). Scanning the full
    // block also picks up the __OT_ALL_TABS / ternary form and both of its
    // branches. [^{}] between the two fields keeps a tab's own i18n from
    // binding to a nested action:{…} block or a sibling object.
    function extractTabLabels(html) {
        var map = {};
        var tabObj = /id\s*:\s*'([^']+)'[^{}]*?i18n\s*:\s*'([^']+)'/g;
        var scriptRe = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
        var block;
        while ((block = scriptRe.exec(html))) {
            var body = block[1];
            if (!body || body.indexOf('OT_TABS') < 0) continue;
            tabObj.lastIndex = 0;
            var m;
            while ((m = tabObj.exec(body))) {
                if (!map[m[1]]) map[m[1]] = m[2];
            }
        }
        return map;
    }

    function nearestAttr(el, root, attr) {
        var n = el;
        while (n && n !== root && n.getAttribute) {
            if (n.getAttribute(attr) != null && n.getAttribute(attr) !== '') {
                return n.getAttribute(attr);
            }
            n = n.parentNode;
        }
        return null;
    }

    function extractFromDoc(page, html, doc) {
        var entries = [];
        var tabKeys = extractTabLabels(html);

        // Distinct data-tab ids, in document order.
        var seen = {};
        var order = [];
        var tagged = doc.querySelectorAll('[data-tab]');
        for (var i = 0; i < tagged.length; i++) {
            var id = tagged[i].getAttribute('data-tab');
            if (id && !seen[id]) { seen[id] = true; order.push(id); }
        }
        var firstTab = order.length ? order[0] : null;

        for (var k = 0; k < order.length; k++) {
            var tid = order[k];
            entries.push({
                type: 'tab', href: page.href, tab: tid,
                i18n: tabKeys[tid] || null, label: humanize(tid),
                pageI18n: page.i18n, pageLabel: page.label
            });
        }

        var names = doc.querySelectorAll('.setting-name[data-i18n]');
        for (var s = 0; s < names.length; s++) {
            var el = names[s];
            var key = el.getAttribute('data-i18n');
            if (!key) continue;
            var tabId = nearestAttr(el, doc, 'data-tab') || firstTab;
            entries.push({
                type: 'setting', href: page.href, tab: tabId,
                i18n: key, label: trim((el.textContent || '').replace(/\s+/g, ' ')),
                pageI18n: page.i18n, pageLabel: page.label,
                tabI18n: (tabId && tabKeys[tabId]) ? tabKeys[tabId] : null,
                tabLabel: tabId ? humanize(tabId) : null
            });
        }
        return entries;
    }

    // ============================================================
    // Index build + cache
    // ============================================================

    var _index = null;
    var _building = false;
    var _buildWaiters = [];

    function cacheVer() { return SCHEMA_VERSION + ':' + appVersion(); }

    function loadCacheRaw() {
        try {
            var raw = window.localStorage.getItem(CACHE_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch (e) { return null; }
    }
    function saveCache(entries) {
        // Never persist an index keyed on an empty app version. #appVersion is
        // filled asynchronously from /status, so a cache written as "2:" would
        // still equal "2:" after an app update and defeat invalidation. Keep it
        // in memory for this session; once the version is known a later load
        // persists it, and a stale on-disk cache (real version) simply mismatches
        // the empty current key and triggers a rebuild in ensureIndex.
        if (!appVersion()) return;
        try {
            window.localStorage.setItem(CACHE_KEY,
                JSON.stringify({ ver: cacheVer(), entries: entries }));
        } catch (e) { /* quota / private mode */ }
    }

    // Max concurrent page fetches during a crawl. The OD daemon's embedded HTTP
    // server is low-concurrency and also serving the /status telemetry poll, so
    // we cap in-flight requests rather than firing one per nav page at once.
    var CRAWL_CONCURRENCY = 4;

    function fetchPage(page, collected, done) {
        var xhr;
        try { xhr = new XMLHttpRequest(); xhr.open('GET', page.href, true); }
        catch (e) { done(); return; }
        xhr.timeout = 8000;
        xhr.onload = function () {
            if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText) {
                try {
                    var doc = new DOMParser().parseFromString(xhr.responseText, 'text/html');
                    var ex = extractFromDoc(page, xhr.responseText, doc);
                    for (var n = 0; n < ex.length; n++) collected.push(ex[n]);
                } catch (e) { /* skip unparseable page */ }
            }
            done();
        };
        xhr.onerror = function () { done(); };
        xhr.ontimeout = function () { done(); };
        try { xhr.send(); } catch (e) { done(); }
    }

    function buildIndex(cb) {
        var pages = getPages();
        var collected = [];
        for (var i = 0; i < pages.length; i++) {
            // No svg — results render iconSvg(type), so persisting per-page SVG
            // path strings to the localStorage cache would just waste quota.
            collected.push({
                type: 'page', href: pages[i].href,
                i18n: pages[i].i18n, label: pages[i].label
            });
        }
        var total = pages.length;
        if (!total) { cb(collected); return; }
        // Bounded worker pool: keep at most CRAWL_CONCURRENCY fetches in flight.
        var next = 0, active = 0, finished = 0;
        function pump() {
            while (active < CRAWL_CONCURRENCY && next < total) {
                active++;
                fetchPage(pages[next++], collected, function () {
                    active--; finished++;
                    if (finished === total) cb(collected);
                    else pump();
                });
            }
        }
        pump();
    }

    function rebuild(cb) {
        if (_building) { if (cb) _buildWaiters.push(cb); return; }
        _building = true;
        buildIndex(function (entries) {
            // Overlay the current page's live-DOM harvest so a background crawl
            // never clobbers the accurate (runtime-filtered + JS-rendered)
            // entries for the page the user is on.
            entries = applyHarvest(entries);
            _index = entries;
            saveCache(entries);
            _building = false;
            var waiters = _buildWaiters.slice();
            _buildWaiters = [];
            if (cb) { try { cb(entries); } catch (e) {} }
            for (var i = 0; i < waiters.length; i++) {
                try { waiters[i](entries); } catch (e) {}
            }
        });
    }

    // Serve the freshest index we can immediately; refresh stale caches in the
    // background so the next open is current.
    function ensureIndex(cb) {
        if (_index) { if (cb) cb(_index); return; }
        var cached = loadCacheRaw();
        if (cached && cached.entries && cached.entries.length) {
            _index = cached.entries;
            if (cb) cb(_index);
            if (cached.ver !== cacheVer()) rebuild(null);
            return;
        }
        rebuild(cb);
    }

    // ============================================================
    // Live-DOM harvest of the CURRENT page
    // ============================================================
    //
    // The crawl parses STATIC HTML, so it (a) includes tabs that a page filters
    // out at runtime (e.g. notifications.html drops push/devices on the head
    // unit) and (b) misses settings rendered by JS after load (e.g. mqtt
    // connections). search.js runs on every page, so we harvest THIS page's
    // live rendered DOM — the bottom-tab bar reflects the real runtime tabs, and
    // querySelectorAll sees JS-rendered rows — and let it replace this page's
    // crawled tab/setting entries. The index self-heals per page as the user
    // navigates; unvisited pages keep their crawled baseline.

    var liveHarvest = null;   // { href, entries: [...tab/setting entries...] }

    // Overlay liveHarvest onto an entries array: drop this page's crawled
    // tab/setting entries (keep its page entry + every other page) and append
    // the harvested ones. Idempotent — keyed on href, not on prior harvests.
    function applyHarvest(entries) {
        if (!liveHarvest) return entries;
        var out = [];
        for (var i = 0; i < entries.length; i++) {
            var e = entries[i];
            if (e.href === liveHarvest.href && (e.type === 'tab' || e.type === 'setting')) continue;
            out.push(e);
        }
        for (var j = 0; j < liveHarvest.entries.length; j++) out.push(liveHarvest.entries[j]);
        return out;
    }

    function pagesMap() {
        if (pagesMap._m) return pagesMap._m;
        var m = {}, ps = getPages();
        for (var i = 0; i < ps.length; i++) m[basename(ps[i].href)] = ps[i];
        pagesMap._m = m;
        return m;
    }

    function extractLiveEntries() {
        var href = currentBasename();
        // Nothing indexable on this page (e.g. the dashboard) — skip.
        if (!document.querySelector('.setting-name[data-i18n]') &&
            !document.querySelector('.bottom-tab[data-tab-target]')) {
            return null;
        }
        var pm = pagesMap()[href] || {};
        var pageI18n = pm.i18n || null;
        var pageLabel = pm.label || humanize((href || '').replace(/\.html$/, ''));
        var entries = [];

        // Live tabs — the rendered bottom-tab bar is exactly the runtime OT_TABS.
        var tabKeyById = {};
        var firstTab = null;
        var btns = document.querySelectorAll('.bottom-tab[data-tab-target]');
        for (var i = 0; i < btns.length; i++) {
            var id = btns[i].getAttribute('data-tab-target');
            if (!id) continue;
            if (!firstTab) firstTab = id;
            var le = btns[i].querySelector('.bottom-tab-label');
            var k = le ? le.getAttribute('data-i18n') : null;
            tabKeyById[id] = k || null;
            entries.push({
                type: 'tab', href: href, tab: id, i18n: k || null,
                label: humanize(id), pageI18n: pageI18n, pageLabel: pageLabel
            });
        }

        // Live settings — includes JS-rendered rows the static crawl can't see.
        var names = document.querySelectorAll('.setting-name[data-i18n]');
        for (var s = 0; s < names.length; s++) {
            var el = names[s];
            var key = el.getAttribute('data-i18n');
            if (!key) continue;
            var tabId = nearestAttr(el, document, 'data-tab') || firstTab;
            entries.push({
                type: 'setting', href: href, tab: tabId, i18n: key,
                label: trim((el.textContent || '').replace(/\s+/g, ' ')),
                pageI18n: pageI18n, pageLabel: pageLabel,
                tabI18n: (tabId && tabKeyById[tabId]) ? tabKeyById[tabId] : null,
                tabLabel: tabId ? humanize(tabId) : null
            });
        }
        return { href: href, entries: entries };
    }

    function harvestLivePage() {
        var h = extractLiveEntries();
        if (!h) return;
        liveHarvest = h;
        ensureIndex(function (entries) {
            _index = applyHarvest(entries);
            saveCache(_index);
        });
    }

    // ============================================================
    // Label resolution + scoring
    // ============================================================

    function resolveLabel(e) {
        return e.i18n ? t(e.i18n, e.label) : e.label;
    }
    function resolveCrumb(e) {
        if (e.type === 'page') return '';
        var page = e.pageI18n ? t(e.pageI18n, e.pageLabel) : (e.pageLabel || basename(e.href));
        if (e.type === 'tab') return page;
        var tab = e.tabI18n ? t(e.tabI18n, e.tabLabel) : (e.tabLabel || '');
        return tab ? (page + ' › ' + tab) : page;
    }

    function isSubseq(q, h) {
        var qi = 0;
        for (var i = 0; i < h.length && qi < q.length; i++) {
            if (h.charAt(i) === q.charAt(qi)) qi++;
        }
        return qi === q.length;
    }

    function score(q, hay) {
        if (!hay) return 0;
        var h = norm(hay);
        var idx = h.indexOf(q);
        if (idx === 0) return 100;              // prefix
        if (idx > 0) {
            var prev = h.charAt(idx - 1);
            if (prev === ' ' || prev === '-' || prev === '_' ||
                prev === '›' || prev === '.') return 80;   // word start
            return 60;                           // substring
        }
        if (q.length >= 3 && isSubseq(q, h)) return 30;  // fuzzy subsequence
        return 0;
    }

    function scoreEntry(q, e) {
        var best = score(q, resolveLabel(e));
        var c = score(q, resolveCrumb(e)) - 12;
        if (c > best) best = c;
        if (e.i18n) {
            var kt = score(q, e.i18n.split('.').pop().replace(/_/g, ' ')) - 18;
            if (kt > best) best = kt;
        }
        // Always also match the stored English label (and English page/tab
        // names for tabs/settings), so a non-English user can still find things
        // by their English term. When the active locale IS English these equal
        // resolveLabel/resolveCrumb above, so this only adds reach elsewhere.
        var enl = score(q, e.label) - 4;
        if (enl > best) best = enl;
        if (e.type !== 'page') {
            var enc = score(q, (e.pageLabel || '') + ' ' + (e.tabLabel || '')) - 14;
            if (enc > best) best = enc;
        }
        if (best <= 0) return 0;
        if (e.type === 'page') best += 10;
        else if (e.type === 'tab') best += 5;
        return best;
    }

    // ============================================================
    // Overlay UI
    // ============================================================

    var dom = null;
    var currentResults = [];
    var activeIndex = -1;
    var prevFocus = null;
    var openRequested = false;
    var openRequest = 0;
    var focusTimer = null;
    var fullscreenExit = null;

    function fullscreenElement() {
        return document.fullscreenElement || document.webkitFullscreenElement || null;
    }

    function canHostOverlay(el) {
        // Replaced elements (canvas/video/iframe, etc.) do not paint ordinary
        // children. Use known HTML containers; leave other fullscreen hosts
        // before showing search rather than opening an invisible modal.
        return el.namespaceURI === 'http://www.w3.org/1999/xhtml' &&
            /^(HTML|BODY|DIV|SECTION|MAIN|ARTICLE|ASIDE|HEADER|FOOTER|NAV)$/.test(el.tagName) &&
            el !== dom.backdrop && !dom.backdrop.contains(el);
    }

    function cancelFocus() {
        if (focusTimer !== null) clearTimeout(focusTimer);
        focusTimer = null;
    }

    function syncOverlay(selectInput) {
        if (!openRequested) return;
        var fullscreen = fullscreenElement();
        if (fullscreen && !canHostOverlay(fullscreen)) {
            dom.backdrop.setAttribute('hidden', '');
            cancelFocus();
            if (fullscreenExit && fullscreenExit.element === fullscreen &&
                    fullscreenExit.request === openRequest) return;
            var exit = document.exitFullscreen || document.webkitExitFullscreen;
            if (!exit) { close(); return; }
            var pending = { element: fullscreen, request: openRequest };
            fullscreenExit = pending;
            var failed = function () {
                if (fullscreenExit !== pending) return;
                fullscreenExit = null;
                if (openRequested && openRequest === pending.request) close();
            };
            try {
                var result = exit.call(document);
                if (result && typeof result.catch === 'function') result.catch(failed);
                // Older WebKit may return void. Only expose search once the
                // fullscreen element has actually cleared, here or on change.
                if (fullscreenElement() !== fullscreen) syncOverlay(selectInput);
            } catch (e) { failed(); }
            return;
        }
        fullscreenExit = null;
        var host = fullscreen || document.body;
        var moved = dom.backdrop.parentNode !== host;
        var wasHidden = dom.backdrop.hasAttribute('hidden');
        var start = dom.input.selectionStart, end = dom.input.selectionEnd;
        if (moved) host.appendChild(dom.backdrop);
        dom.backdrop.removeAttribute('hidden');
        if (selectInput || moved || wasHidden || document.activeElement === document.body) {
            cancelFocus();
            var request = openRequest;
            focusTimer = setTimeout(function () {
                focusTimer = null;
                if (!openRequested || openRequest !== request || !isOpen()) return;
                try {
                    dom.input.focus();
                    if (selectInput) dom.input.select();
                    else dom.input.setSelectionRange(start, end);
                } catch (e) {}
            }, 0);
        }
    }

    document.addEventListener('fullscreenchange', function () { syncOverlay(false); });
    document.addEventListener('webkitfullscreenchange', function () { syncOverlay(false); });

    function iconSvg(type) {
        if (type === 'page') {
            return '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>';
        }
        if (type === 'tab') {
            return '<rect x="3" y="4" width="18" height="16" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/>';
        }
        // setting — gear
        return '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>';
    }

    function buildOverlay() {
        if (dom) return dom;
        var backdrop = document.createElement('div');
        backdrop.className = 'od-search-backdrop';
        backdrop.setAttribute('hidden', '');
        backdrop.setAttribute('role', 'dialog');
        backdrop.setAttribute('aria-modal', 'true');

        var panel = document.createElement('div');
        panel.className = 'od-search-panel';

        var row = document.createElement('div');
        row.className = 'od-search-inputrow';
        row.innerHTML = '<svg class="od-search-leadicon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>';

        var input = document.createElement('input');
        input.className = 'od-search-input';
        input.type = 'text';
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('autocorrect', 'off');
        input.setAttribute('autocapitalize', 'off');
        input.setAttribute('spellcheck', 'false');
        input.setAttribute('aria-label', t('common.search', 'Search'));
        input.placeholder = t('common.search', 'Search') + '…';

        var clear = document.createElement('button');
        clear.type = 'button';
        clear.className = 'od-search-clear';
        clear.setAttribute('aria-label', t('common.clear', 'Clear'));
        clear.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>';

        row.appendChild(input);
        row.appendChild(clear);

        var results = document.createElement('ul');
        results.className = 'od-search-results';
        results.setAttribute('role', 'listbox');

        panel.appendChild(row);
        panel.appendChild(results);
        backdrop.appendChild(panel);
        document.body.appendChild(backdrop);

        dom = { backdrop: backdrop, panel: panel, input: input, clear: clear, results: results };
        wireOverlay();
        return dom;
    }

    function itemHtml(e, i) {
        var label = escapeHtml(resolveLabel(e));
        var crumb = escapeHtml(resolveCrumb(e));
        return '<li class="od-search-item" role="option" data-i="' + i + '">'
            + '<span class="od-search-item-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + iconSvg(e.type) + '</svg></span>'
            + '<span class="od-search-item-text">'
            +   '<span class="od-search-item-label">' + label + '</span>'
            +   (crumb ? '<span class="od-search-item-crumb">' + crumb + '</span>' : '')
            + '</span>'
            + '</li>';
    }

    function render(list) {
        currentResults = list;
        activeIndex = list.length ? 0 : -1;
        if (!list.length) {
            dom.results.innerHTML = '<div class="od-search-empty">' +
                escapeHtml(t('errors.not_found', 'No results')) + '</div>';
            return;
        }
        var html = '';
        for (var i = 0; i < list.length; i++) html += itemHtml(list[i], i);
        dom.results.innerHTML = html;
        updateActive();
    }

    function runQuery(raw) {
        var q = norm(trim(raw));
        if (dom) {
            if (q) dom.panel.classList.add('has-query');
            else dom.panel.classList.remove('has-query');
        }
        ensureIndex(function (entries) {
            var list, i;
            if (!q) {
                list = [];
                for (i = 0; i < entries.length; i++) {
                    if (entries[i].type === 'page') list.push(entries[i]);
                }
            } else {
                var scored = [];
                for (i = 0; i < entries.length; i++) {
                    var sc = scoreEntry(q, entries[i]);
                    // Stash the resolved-label length now so the comparator
                    // doesn't re-run resolveLabel (an i18n.t lookup) O(N log N).
                    if (sc > 0) scored.push({ e: entries[i], s: sc, n: resolveLabel(entries[i]).length });
                }
                scored.sort(function (a, b) {
                    if (b.s !== a.s) return b.s - a.s;
                    return a.n - b.n;
                });
                list = [];
                for (i = 0; i < scored.length && i < MAX_RESULTS; i++) list.push(scored[i].e);
            }
            render(list);
        });
    }

    function updateActive() {
        var items = dom.results.querySelectorAll('.od-search-item');
        for (var i = 0; i < items.length; i++) {
            if (i === activeIndex) {
                items[i].classList.add('is-active');
                try { items[i].scrollIntoView({ block: 'nearest' }); } catch (e) {}
            } else {
                items[i].classList.remove('is-active');
            }
        }
    }

    function move(delta) {
        if (!currentResults.length) return;
        activeIndex += delta;
        if (activeIndex < 0) activeIndex = currentResults.length - 1;
        if (activeIndex >= currentResults.length) activeIndex = 0;
        updateActive();
    }

    var queryTimer = null;
    function wireOverlay() {
        // Debounce keystrokes: scoring resolves i18n labels for every entry, so
        // re-running on each keypress lags fast typing on the head-unit WebView.
        dom.input.addEventListener('input', function () {
            var v = dom.input.value;
            if (queryTimer) clearTimeout(queryTimer);
            queryTimer = setTimeout(function () { runQuery(v); }, 110);
        });
        dom.input.addEventListener('keydown', function (ev) {
            var k = ev.key;
            if (k === 'ArrowDown' || ev.keyCode === 40) { ev.preventDefault(); move(1); }
            else if (k === 'ArrowUp' || ev.keyCode === 38) { ev.preventDefault(); move(-1); }
            else if (k === 'Enter' || ev.keyCode === 13) {
                ev.preventDefault();
                if (activeIndex >= 0 && currentResults[activeIndex]) go(currentResults[activeIndex]);
            } else if (k === 'Escape' || ev.keyCode === 27) {
                ev.preventDefault(); close();
            }
        });
        dom.clear.addEventListener('click', function () {
            if (queryTimer) clearTimeout(queryTimer);
            dom.input.value = ''; runQuery(''); try { dom.input.focus(); } catch (e) {}
        });
        dom.backdrop.addEventListener('click', function (ev) {
            if (ev.target === dom.backdrop) close();
        });
        dom.results.addEventListener('click', function (ev) {
            // Walk up to the <li> carrying data-i. We key on the attribute, not
            // the class name: the child spans are od-search-item-icon/-label/
            // -text/-crumb, which all CONTAIN the substring "od-search-item", so
            // a substring class match would stop on a child that has no data-i
            // and the click would do nothing.
            var li = ev.target;
            while (li && li !== dom.results &&
                   !(li.getAttribute && li.getAttribute('data-i') !== null)) {
                li = li.parentNode;
            }
            if (li && li.getAttribute && li.getAttribute('data-i') !== null) {
                var i = parseInt(li.getAttribute('data-i'), 10);
                if (!isNaN(i) && currentResults[i]) go(currentResults[i]);
            }
        });
    }

    function open() {
        buildOverlay();
        if (!openRequested) {
            prevFocus = document.activeElement;
            openRequest++;
        }
        openRequested = true;
        runQuery(dom.input.value || '');
        syncOverlay(true);
    }
    function close() {
        if (!dom || !openRequested) return;
        openRequested = false;
        openRequest++;
        fullscreenExit = null;
        cancelFocus();
        dom.backdrop.setAttribute('hidden', '');
        if (dom.backdrop.parentNode !== document.body) document.body.appendChild(dom.backdrop);
        var fullscreen = fullscreenElement();
        try {
            if (prevFocus && prevFocus.focus && document.documentElement.contains(prevFocus) &&
                    (!fullscreen || fullscreen.contains(prevFocus))) prevFocus.focus();
        } catch (e) {}
        prevFocus = null;
    }
    function isOpen() { return !!(dom && !dom.backdrop.hasAttribute('hidden')); }

    // ============================================================
    // Navigation + deep-link
    // ============================================================

    function selectTab(id) {
        if (!id) return;
        if (!/^[\w-]+$/.test(id)) return;
        var btn = document.querySelector('.bottom-tab[data-tab-target="' + id + '"]');
        if (btn) { try { btn.click(); } catch (e) {} }
        else { try { window.location.hash = id; } catch (e) {} }
    }

    function findSettingEl(key) {
        var nodes = document.querySelectorAll('.setting-name[data-i18n]');
        var first = null;
        for (var i = 0; i < nodes.length; i++) {
            if (nodes[i].getAttribute('data-i18n') !== key) continue;
            if (!first) first = nodes[i];
            // Some pages (e.g. recording.html) repeat a data-i18n key across
            // tabs. The right target is the one in the tab we just switched to;
            // a hidden tab's rows have offsetParent === null. Prefer a visible
            // match, falling back to the first if none is shown.
            if (nodes[i].offsetParent !== null) return nodes[i];
        }
        return first;
    }

    function scrollFlash(nameEl) {
        var row = nameEl, n = nameEl;
        while (n && n !== document.body) {
            if (n.className && ('' + n.className).indexOf('setting-row') >= 0) { row = n; break; }
            n = n.parentNode;
        }
        try { row.scrollIntoView({ block: 'center' }); }
        catch (e) { try { row.scrollIntoView(); } catch (e2) {} }
        row.classList.add('od-setting-flash');
        setTimeout(function () { row.classList.remove('od-setting-flash'); }, 2100);
    }

    function flashSetting(key) {
        if (!key) return;
        var el = findSettingEl(key);
        if (el) { scrollFlash(el); return; }
        // The target tab may have just been revealed — retry once.
        setTimeout(function () { var el2 = findSettingEl(key); if (el2) scrollFlash(el2); }, 150);
    }

    function go(e) {
        close();
        var targetBase = basename(e.href);
        var samePage = targetBase === currentBasename();

        if (e.type === 'page') {
            if (!samePage) window.location.href = e.href;
            return;
        }
        if (e.type === 'tab') {
            if (samePage) selectTab(e.tab);
            else window.location.href = targetBase + '#' + e.tab;
            return;
        }
        // setting
        if (samePage) {
            selectTab(e.tab);
            flashSetting(e.i18n);
            return;
        }
        // Stash the setting key for the destination page (no query string —
        // the server 404s those), then navigate with only the tab hash.
        try { window.sessionStorage.setItem(FIND_KEY, e.i18n || ''); } catch (err) {}
        window.location.href = targetBase + (e.tab ? '#' + e.tab : '');
    }

    // On arrival: app-tabs selects the tab from the #hash, revealing the card;
    // we read the stashed setting key, scroll to + flash it, then clear the key
    // so a later manual reload doesn't re-trigger.
    function handleArrival() {
        var key;
        try { key = window.sessionStorage.getItem(FIND_KEY); } catch (e) { key = null; }
        if (!key) return;
        try { window.sessionStorage.removeItem(FIND_KEY); } catch (e) {}
        function attempt(tries) {
            var el = findSettingEl(key);
            if (el) { scrollFlash(el); return; }
            if (tries > 0) setTimeout(function () { attempt(tries - 1); }, 150);
        }
        setTimeout(function () { attempt(12); }, 150);
    }

    // ============================================================
    // Public API + init
    // ============================================================

    window.OverdriveSearch = { open: open, close: close, ready: true };

    // app-shell may have queued an open if the user triggered before load.
    if (window.__odSearchPendingOpen) { window.__odSearchPendingOpen = false; open(); }

    // Re-resolve labels live when the language changes.
    try {
        if (window.BYD && window.BYD.i18n && typeof window.BYD.i18n.onChange === 'function') {
            window.BYD.i18n.onChange(function () {
                if (dom) {
                    try {
                        dom.input.placeholder = t('common.search', 'Search') + '…';
                        dom.input.setAttribute('aria-label', t('common.search', 'Search'));
                    } catch (e) {}
                }
                if (isOpen()) runQuery(dom.input.value);
            });
        }
    } catch (e) {}

    function initOnReady() {
        handleArrival();
        if (typeof window.requestIdleCallback === 'function') {
            window.requestIdleCallback(function () { ensureIndex(null); }, { timeout: 3000 });
        } else {
            setTimeout(function () { ensureIndex(null); }, 1200);
        }
        // Harvest this page's live DOM into the index. Two passes catch async
        // lists that render after load; a tab switch re-harvests in case
        // switching tabs is what renders that tab's content.
        setTimeout(harvestLivePage, 1500);
        setTimeout(harvestLivePage, 4000);
        document.addEventListener('ot-tabs:active-changed', function () {
            setTimeout(harvestLivePage, 300);
        });
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initOnReady);
    } else {
        initOnReady();
    }
}());
