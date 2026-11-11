/**
 * Overdrive — Web app tabs.
 *
 * Drops a sticky bottom tab bar onto pages whose long single-scroll settings
 * would benefit from logical grouping. Cards on the page opt in by tagging
 * themselves with a `data-tab="<tab-id>"` attribute; the script then:
 *
 *   1. Reads a per-page manifest of tab metadata (label + icon).
 *   2. Renders <nav class="bottom-tabs"> with one button per tab.
 *   3. Hides cards whose data-tab does not match the active tab.
 *   4. Persists the active tab to localStorage so the user lands on the
 *      same tab next visit.
 *
 * The manifest can be specified two ways. Either set `window.OT_TABS` BEFORE
 * loading this script, or include a <meta name="ot-tabs" content='JSON'> tag
 * in <head>. Both forms accept the same shape:
 *
 *     [
 *       { id: "general",  label: "General",  i18n: "tabs.general",  svg: "<path …/>" },
 *       { id: "schedule", label: "Schedule", i18n: "tabs.schedule", svg: "<path …/>" },
 *       …
 *     ]
 *
 * Cards without a `data-tab` attribute fall into the FIRST tab so existing
 * pages behave correctly even before any data-tab annotations are added.
 *
 * No DOM IDs change. All form inputs keep their existing IDs and JS bindings.
 *
 * ES5-only.
 */
(function () {
    'use strict';

    var STORAGE_PREFIX = 'ot-active-tab-';

    function readManifest() {
        // Prefer window.OT_TABS (no JSON parse, no escaping). Fall back to
        // the meta tag for pages that prefer declarative configuration.
        if (window.OT_TABS && window.OT_TABS.length) return window.OT_TABS;
        var meta = document.querySelector('meta[name="ot-tabs"]');
        if (!meta) return null;
        var raw = meta.getAttribute('content');
        if (!raw) return null;
        try {
            var parsed = JSON.parse(raw);
            return parsed && parsed.length ? parsed : null;
        } catch (e) {
            return null;
        }
    }

    function pageKey() {
        // Use the basename of the URL as the localStorage key suffix so each
        // page has its own remembered tab.
        var path = window.location.pathname || '';
        var idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path || 'index';
    }

    function readSavedTab(tabs) {
        try {
            var saved = window.localStorage.getItem(STORAGE_PREFIX + pageKey());
            if (!saved) return null;
            for (var i = 0; i < tabs.length; i++) {
                if (tabs[i].id === saved) return saved;
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    // Resolve any deep-link hash like /surveillance.html#integrations to the
    // matching tab id, so cross-page nav (e.g. sidebar's BYD Cloud entry)
    // can pre-select a tab.
    function readHashTab(tabs) {
        var hash = (window.location.hash || '').replace(/^#/, '');
        if (!hash) return null;
        for (var i = 0; i < tabs.length; i++) {
            if (tabs[i].id === hash) return hash;
        }
        return null;
    }

    function saveTab(id) {
        try { window.localStorage.setItem(STORAGE_PREFIX + pageKey(), id); }
        catch (e) { /* ignore quota / private mode */ }
    }

    function buildBar(tabs, active) {
        var nav = document.createElement('nav');
        nav.className = 'bottom-tabs';
        nav.setAttribute('role', 'tablist');
        var html = '';
        for (var i = 0; i < tabs.length; i++) {
            var t = tabs[i];
            var isActive = t.id === active;
            html += '<button type="button"'
                + ' class="bottom-tab' + (isActive ? ' is-active' : '') + '"'
                + ' role="tab"'
                + ' aria-selected="' + (isActive ? 'true' : 'false') + '"'
                + ' data-tab-target="' + t.id + '">'
                +   '<svg class="bottom-tab-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
                +     (t.svg || '')
                +   '</svg>'
                +   '<span class="bottom-tab-label"' + (t.i18n ? ' data-i18n="' + t.i18n + '"' : '') + '>' + (t.label || t.id) + '</span>'
                + '</button>';
        }
        nav.innerHTML = html;
        return nav;
    }

    function applyActive(tabs, activeId, root) {
        // Show only the cards (and any other elements) tagged with the
        // active tab's id. Cards without a data-tab attribute default to
        // the FIRST tab so untagged pages render normally.
        var firstId = tabs[0].id;
        var nodes = root.querySelectorAll('[data-tab]');
        for (var i = 0; i < nodes.length; i++) {
            var t = nodes[i].getAttribute('data-tab') || firstId;
            if (t === activeId) {
                nodes[i].removeAttribute('hidden');
            } else {
                nodes[i].setAttribute('hidden', '');
            }
        }
        // For untagged cards, hide them only when the active tab is NOT
        // the first one. This keeps pages with mixed (some tagged, some
        // untagged) cards correct.
        var untagged = root.querySelectorAll('.card:not([data-tab])');
        for (var j = 0; j < untagged.length; j++) {
            // A card inside a tagged section follows that section, not the first tab.
            var holder = untagged[j].parentElement;
            if (holder && holder.closest && holder.closest('[data-tab]')) continue;
            if (activeId === firstId) untagged[j].removeAttribute('hidden');
            else untagged[j].setAttribute('hidden', '');
        }
    }

    // Keep the active tab in view when the strip scrolls sideways. A delta works
    // in every RTL scrollLeft model.
    function revealTab(nav, btn) {
        var track = btn && btn.parentNode !== nav ? btn.parentNode : nav;
        if (!btn || track.scrollWidth <= track.clientWidth) return;
        var n = track.getBoundingClientRect();
        var b = btn.getBoundingClientRect();
        track.scrollLeft += (b.left + b.width / 2) - (n.left + n.width / 2);
    }

    function bindClicks(nav, tabs, root, getActive, setActive, refreshApply) {
        var btns = nav.querySelectorAll('.bottom-tab');
        for (var i = 0; i < btns.length; i++) {
            btns[i].addEventListener('click', function (ev) {
                var btn = ev.currentTarget;
                var id = btn.getAttribute('data-tab-target');
                if (!id || id === getActive()) return;
                setActive(id);
                applyActive(tabs, id, root);
                // Update aria + visual state.
                for (var k = 0; k < btns.length; k++) {
                    var on = btns[k] === btn;
                    btns[k].setAttribute('aria-selected', on ? 'true' : 'false');
                    if (on) btns[k].classList.add('is-active');
                    else btns[k].classList.remove('is-active');
                }
                saveTab(id);
                revealTab(nav, btn);
                if (typeof refreshApply === 'function') refreshApply(id);
                // Notify pages so per-tab dirty trackers can re-evaluate the
                // Apply button + unsaved markers for the newly active tab.
                try {
                    var ev = document.createEvent('CustomEvent');
                    ev.initCustomEvent('ot-tabs:active-changed', true, true, { id: id });
                    document.dispatchEvent(ev);
                } catch (e) { /* ignore — older browsers */ }
                // Reset the scroll offset to the top so users see the start of
                // the new tab, not whatever offset the previous (taller) tab
                // was scrolled to.
                //
                // The previous code scrolled .main-content, which is a SILENT
                // NO-OP: in this layout the ancestor chain (html/body/.app-layout/
                // .main-content) is sized with min-height only — nothing is
                // height-BOUNDED — so neither .main-content nor .page-body
                // establishes an internal overflow scroller; the DOCUMENT itself
                // scrolls. Switching from a tall tab (RoadSense General/Warnings)
                // to a short one (Data) shrinks the document below the old scroll
                // offset, and the browser clamps the document scrollTop upward —
                // which is the "whole tab section shifts up" the user sees.
                //
                // Reset the document scroller (window + both documentElement and
                // body for cross-engine safety on our Chrome 58 floor) AND the
                // .page-body element defensively, in case a future page makes it
                // a real overflow container. Instant jump (not smooth) so the
                // reset lands before the ot-tab-fade-in animation paints, instead
                // of a smooth scroll racing the fade.
                try { window.scrollTo(0, 0); } catch (e) { /* ignore */ }
                if (document.documentElement) document.documentElement.scrollTop = 0;
                if (document.body) document.body.scrollTop = 0;
                var pageBody = document.querySelector('.page-body');
                if (pageBody) pageBody.scrollTop = 0;
            });
        }
    }

    /**
     * Build the trailing action slot inside the tab bar. The slot can hold:
     *   1. The page's existing Apply button (.footer-bar #btnApply), relocated
     *      so SurvSettings.applySettings() / RecSettings.saveSettings() bindings
     *      keep firing. Hidden on tabs declared as `readOnly: true`.
     *   2. A per-tab custom action declared in the manifest as `action: {…}`.
     *      The action button replaces the Apply button while its tab is active,
     *      so e.g. an MQTT "Add Connection" lives on the Connections tab and
     *      a Telemetry-only tab gets nothing.
     *
     * Manifest action shape:
     *     { id: 'connections', …, action: {
     *         label: 'Add',
     *         i18n:  'mqtt.add_connection',
     *         svg:   '<line .../>',
     *         onclick: function () { MQTT.showAddForm(); }
     *     }}
     *
     * Returns a function the tab-switch handler calls to update visibility
     * when the active tab changes.
     */
    function relocateApplyButton(nav, tabs) {
        var slot = document.createElement('div');
        slot.className = 'bottom-tab-action';
        nav.appendChild(slot);
        document.body.classList.add('ot-tabs-with-action');

        // ===== Apply button relocation (optional) =====
        var origStrip = document.querySelector('.footer-bar');
        var origBtn = origStrip ? origStrip.querySelector('#btnApply') : null;
        if (origBtn) {
            slot.appendChild(origBtn);
            // Mirror the disabled state on the slot so CSS can dim it.
            if (typeof MutationObserver !== 'undefined') {
                new MutationObserver(function () {
                    if (origBtn.disabled) slot.classList.add('is-disabled');
                    else slot.classList.remove('is-disabled');
                }).observe(origBtn, { attributes: true, attributeFilter: ['disabled'] });
            }
            if (origStrip.parentNode) origStrip.parentNode.removeChild(origStrip);
        }

        // ===== Per-tab custom action buttons =====
        // Build one button per manifest entry that declares `action`. They
        // share the slot — the active tab's button is shown, others are
        // hidden via .is-hidden. Apply (when present) is treated as the
        // default for tabs that don't declare an action and aren't readOnly.
        var actionBtns = {};
        for (var i = 0; i < tabs.length; i++) {
            var t = tabs[i];
            if (!t || !t.action) continue;
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn btn-primary bottom-tab-action-btn';
            btn.setAttribute('data-tab-action', t.id);
            var iconHtml = t.action.svg
                ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:18px;height:18px;">' + t.action.svg + '</svg>'
                : '';
            var labelI18n = t.action.i18n ? ' data-i18n="' + t.action.i18n + '"' : '';
            btn.innerHTML = iconHtml + '<span' + labelI18n + '>' + (t.action.label || '') + '</span>';
            if (typeof t.action.onclick === 'function') {
                (function (handler) { btn.addEventListener('click', handler); }(t.action.onclick));
            }
            slot.appendChild(btn);
            actionBtns[t.id] = btn;
        }

        // Build the per-tab visibility table.
        var hideApplyOn = {};
        var showCustomOn = {};
        for (var j = 0; j < tabs.length; j++) {
            var tab = tabs[j];
            if (!tab) continue;
            if (tab.readOnly) hideApplyOn[tab.id] = true;
            if (tab.action) {
                hideApplyOn[tab.id] = true;     // custom action takes the slot
                showCustomOn[tab.id] = true;
            }
        }

        function refresh(activeId) {
            // Apply visibility — hidden on readOnly tabs, on tabs with a
            // custom action, or when there is no Apply button to begin with.
            if (origBtn) {
                if (hideApplyOn[activeId]) slot.classList.add('is-hidden');
                else slot.classList.remove('is-hidden');
            } else if (!showCustomOn[activeId]) {
                slot.classList.add('is-hidden');
            } else {
                slot.classList.remove('is-hidden');
            }
            // Custom-action visibility — show the active tab's button, hide
            // all others.
            for (var id in actionBtns) {
                if (Object.prototype.hasOwnProperty.call(actionBtns, id)) {
                    actionBtns[id].style.display = (id === activeId) ? '' : 'none';
                }
            }
            // If a custom action is active, the slot itself must be visible
            // even when there is no Apply button.
            if (showCustomOn[activeId]) slot.classList.remove('is-hidden');
        }

        return refresh;
    }

    function mount() {
        var tabs = readManifest();
        if (!tabs) return; // page didn't opt in
        var main = document.querySelector('.main-content') || document.body;

        // Resolve the active tab. Hash deep-link wins (e.g. cross-page link
        // like surveillance.html#integrations), then saved value, then the
        // first tab as a final fallback.
        var active = readHashTab(tabs) || readSavedTab(tabs) || tabs[0].id;
        var nav = buildBar(tabs, active);

        // In flow just above the page body (after the header and any summary
        // tile); revamp.css makes it sticky. Pages without a body keep the
        // old fixed bar at the end of <body>.
        var pageBody = main.querySelector('.page-body');
        if (pageBody && pageBody.parentNode) {
            nav.className += ' is-inflow';
            // The tabs scroll in their own track, so the action slot sits
            // beside them rather than over the last tab.
            var track = document.createElement('div');
            track.className = 'bottom-tabs-track';
            track.setAttribute('role', 'tablist');
            nav.removeAttribute('role');
            while (nav.firstChild) track.appendChild(nav.firstChild);
            nav.appendChild(track);
            pageBody.parentNode.insertBefore(nav, pageBody);
        } else {
            document.body.appendChild(nav);
        }

        applyActive(tabs, active, main);
        var refreshApply = relocateApplyButton(nav, tabs);
        refreshApply(active);
        bindClicks(nav, tabs, main, function () { return active; }, function (next) { active = next; }, refreshApply);

        // Handle deep links opened after mount, including browser history navigation.
        window.addEventListener('hashchange', function () {
            var target = readHashTab(tabs);
            if (!target || target === active) return;
            var buttons = nav.querySelectorAll('.bottom-tab');
            for (var i = 0; i < buttons.length; i++) {
                if (buttons[i].getAttribute('data-tab-target') === target) {
                    buttons[i].click();
                    break;
                }
            }
        });

        // i18n re-apply for the freshly-injected labels.
        if (window.BYD && window.BYD.i18n && typeof window.BYD.i18n.applyAll === 'function') {
            try { window.BYD.i18n.applyAll(); } catch (e) {}
        } else if (typeof window.applyI18n === 'function') {
            try { window.applyI18n(); } catch (e) {}
        }

        // Add a class on <body> so CSS can give .main-content padding-bottom
        // equal to the tab bar height.
        document.body.classList.add('ot-tabs-on');
        revealTab(nav, nav.querySelector('.bottom-tab.is-active'));
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', mount);
    } else {
        mount();
    }
}());
