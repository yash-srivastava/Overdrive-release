/**
 * Overdrive — Collapsible cards.
 *
 * Turns every `.card` with a `.card-header` + `.card-body` into a tappable
 * accordion section. Lightweight: no animation library, just CSS transitions
 * via `max-height` (capped to a generous default) and an `aria-expanded` flip
 * on the header for screen-readers.
 *
 * Cards opt OUT by adding `data-collapse="off"` to the .card element.
 * Cards opt IN to default-collapsed by adding `data-collapse-default="closed"`.
 *
 * Per-card state is persisted to localStorage under
 * `ot-card-collapsed:<page>:<id-or-index>` so the user's groupings stick across
 * page loads.
 *
 * ES5-only (Chrome 58 / Android 7.1 head-unit floor).
 */
(function () {
    'use strict';

    var STORAGE_PREFIX = 'ot-card-collapsed:';
    var expansionTimers = new WeakMap();

    function pageKey() {
        var path = window.location.pathname || '';
        var idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : (path || 'index');
    }

    // Build a stable id for a card so localStorage keys survive DOM reorders.
    // Prefer an explicit id on the card, then a data-collapse-id, then the
    // first data-i18n key found inside the title, finally the index.
    function cardKey(card, index) {
        if (card.id) return card.id;
        var explicit = card.getAttribute('data-collapse-id');
        if (explicit) return explicit;
        var titleI18n = card.querySelector('.card-title [data-i18n]');
        if (titleI18n) {
            var k = titleI18n.getAttribute('data-i18n');
            if (k) return k;
        }
        return 'card-' + index;
    }

    function readState(key) {
        try { return window.localStorage.getItem(STORAGE_PREFIX + pageKey() + ':' + key); }
        catch (e) { return null; }
    }

    function writeState(key, val) {
        try { window.localStorage.setItem(STORAGE_PREFIX + pageKey() + ':' + key, val ? '1' : '0'); }
        catch (e) { /* quota / private mode — ignore */ }
    }

    function makeChevron() {
        // Inline SVG so we don't need an external icon font. Stroke uses
        // currentColor so it inherits the header's text colour.
        var span = document.createElement('span');
        span.className = 'card-collapse-chevron';
        span.setAttribute('aria-hidden', 'true');
        span.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
        return span;
    }

    function setCollapsed(card, collapsed, animate) {
        var body = card.querySelector(':scope > .card-body');
        if (!body) return;
        // A previous expansion must not clear the height during a newer one.
        if (expansionTimers.has(card)) {
            clearTimeout(expansionTimers.get(card));
            expansionTimers.delete(card);
        }
        var header = card.querySelector(':scope > .card-header');
        if (collapsed) {
            // Browsers can't transition out of `max-height: none` — they need
            // a concrete numeric start. If we're animating closed, lock the
            // current rendered height first, then on the next frame let CSS
            // animate it to 0.
            if (animate && (body.style.maxHeight === '' || body.style.maxHeight === 'none')) {
                body.style.maxHeight = body.scrollHeight + 'px';
                // Force a reflow so the layout engine acknowledges the
                // pinned height before we change it again.
                /* eslint-disable-next-line no-unused-expressions */
                body.offsetHeight;
            }
            card.classList.add('is-collapsed');
            if (header) header.setAttribute('aria-expanded', 'false');
            // Drive the CSS transition.
            body.style.maxHeight = '0px';
            body.style.opacity = '0';
        } else {
            card.classList.remove('is-collapsed');
            if (header) header.setAttribute('aria-expanded', 'true');
            if (animate) {
                // Pin the destination height for the duration of the slide,
                // then clear it so subsequent DOM growth reflows naturally.
                body.style.maxHeight = (body.scrollHeight + 64) + 'px';
                body.style.opacity = '1';
                expansionTimers.set(card, setTimeout(function () {
                    expansionTimers.delete(card);
                    if (!card.classList.contains('is-collapsed')) {
                        body.style.maxHeight = 'none';
                    }
                }, 320));
            } else {
                body.style.maxHeight = 'none';
                body.style.opacity = '1';
            }
        }
    }

    function wireCard(card, index) {
        if (card.getAttribute('data-collapse') === 'off') return;
        var header = card.querySelector(':scope > .card-header');
        var body = card.querySelector(':scope > .card-body');
        if (!header || !body) return;

        // Don't double-wire on hot-reload / re-runs.
        if (card.hasAttribute('data-collapse-wired')) return;
        card.setAttribute('data-collapse-wired', '1');

        var key = cardKey(card, index);
        var saved = readState(key);
        var startCollapsed;
        if (saved === '1') startCollapsed = true;
        else if (saved === '0') startCollapsed = false;
        else startCollapsed = card.getAttribute('data-collapse-default') === 'closed';

        // Tag the header so CSS can show the chevron + cursor pointer.
        header.classList.add('card-header-collapsible');
        header.setAttribute('role', 'button');
        header.setAttribute('tabindex', '0');
        header.setAttribute('aria-expanded', startCollapsed ? 'false' : 'true');
        if (!header.querySelector(':scope > .card-collapse-chevron')) {
            header.appendChild(makeChevron());
        }

        // Apply initial state without animation so the page paints in its
        // settled position (no jank on load).
        setCollapsed(card, startCollapsed, false);

        function toggle(ev) {
            // Don't hijack clicks on interactive children inside the header
            // (status badges with their own onclick, links, buttons).
            var t = ev && ev.target;
            while (t && t !== header) {
                var tag = t.tagName ? t.tagName.toLowerCase() : '';
                if (tag === 'a' || tag === 'button' || tag === 'input' || tag === 'select' || tag === 'label') return;
                t = t.parentNode;
            }
            var nowCollapsed = !card.classList.contains('is-collapsed');
            setCollapsed(card, nowCollapsed, true);
            writeState(key, nowCollapsed);
        }

        header.addEventListener('click', toggle);
        header.addEventListener('keydown', function (ev) {
            // Key events from child controls bubble here. Let those controls
            // handle their own keys without cancelling their default action.
            if (ev.target !== header) return;
            if (ev.key === 'Enter' || ev.key === ' ' || ev.keyCode === 13 || ev.keyCode === 32) {
                ev.preventDefault();
                if (!ev.repeat) toggle(ev);
            }
        });
    }

    function wireAll() {
        var cards = document.querySelectorAll('.card');
        for (var i = 0; i < cards.length; i++) wireCard(cards[i], i);
    }

    function start() {
        wireAll();
        // Re-wire when bottom-tabs swap visible cards (the cards aren't
        // removed, just toggled with [hidden]) — in case a future change adds
        // cards dynamically. Cheap to call: wireCard short-circuits on
        // already-wired elements.
        document.addEventListener('app-shell:ready', wireAll);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
}());
