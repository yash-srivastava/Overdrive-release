/**
 * Language picker — sidebar globe chip + bottom-sheet modal.
 *
 * Self-mounts on DOMContentLoaded if a sidebar-footer is present. Loads its
 * label data from BYD.i18n's display-name table so adding a new locale only
 * requires editing the runtime.
 *
 * UX choices (the SOTA touches discussed at planning):
 *   - Native-script labels ("简体中文", not "Chinese (Simplified)")
 *   - No country flags (a language ≠ a country)
 *   - "Auto (follow system)" pinned at top
 *   - Search box appears when the supported list grows past 10
 *   - 200ms cross-fade animation on the chip when language changes
 *   - Persists via /api/i18n/lang so server-emitted strings come back
 *     in the matching locale on the very next fetch
 */
(function () {
    if (!window.BYD || !BYD.i18n) {
        console.warn('[LangPicker] BYD.i18n not loaded — picker will not mount');
        return;
    }

    var SHORT_LABELS = {
        'en': 'EN', 'zh-CN': '中', 'zh-TW': '繁', 'pt-BR': 'PT', 'es': 'ES',
        'de': 'DE', 'fr': 'FR', 'it': 'IT', 'nb': 'NO', 'nl': 'NL',
        'ja': '日', 'ko': '한', 'th': 'ไทย', 'vi': 'VI', 'hi': 'हि', 'tr': 'TR',
        'ru': 'РУ'
    };

    function shortLabel(lang) {
        return SHORT_LABELS[lang] || lang.slice(0, 2).toUpperCase();
    }

    /** Build the chip element that lives in the sidebar footer. */
    function buildChip() {
        var btn = document.createElement('button');
        btn.className = 'lang-chip';
        btn.type = 'button';
        btn.setAttribute('aria-label', 'Select language');
        btn.innerHTML =
            '<svg class="lang-chip-globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                '<circle cx="12" cy="12" r="10"/>' +
                '<line x1="2" y1="12" x2="22" y2="12"/>' +
                '<path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>' +
            '</svg>' +
            '<span class="lang-chip-text">' + shortLabel(BYD.i18n.getLang()) + '</span>';
        btn.addEventListener('click', openSheet);

        // Cross-fade chip text whenever the language flips. State change comes
        // from setLang() (chip click) OR from the /status poll (when changed
        // via Android settings on another path).
        BYD.i18n.onChange(function (lang) {
            var span = btn.querySelector('.lang-chip-text');
            if (!span) return;
            span.style.transition = 'opacity 0.2s';
            span.style.opacity = '0';
            setTimeout(function () {
                span.textContent = shortLabel(lang);
                span.style.opacity = '1';
            }, 200);
        });
        return btn;
    }

    var sheet = null;
    var sheetVisible = false;

    function openSheet() {
        if (sheetVisible) return;
        sheetVisible = true;
        if (!sheet) sheet = buildSheet();
        document.body.appendChild(sheet);
        // force reflow so the open transition runs
        void sheet.offsetWidth;
        sheet.classList.add('open');
        // Render the list every open in case translations changed
        renderList();
        var search = sheet.querySelector('.lang-sheet-search input');
        if (search) {
            search.value = '';
            // Auto-focus on desktop only — mobile keyboards on a head unit
            // are jarring and cars don't have keyboards anyway
            if (!('ontouchstart' in window)) setTimeout(function () { search.focus(); }, 250);
        }
    }

    function closeSheet() {
        if (!sheetVisible || !sheet) return;
        sheetVisible = false;
        sheet.classList.remove('open');
        setTimeout(function () {
            if (sheet && sheet.parentNode) sheet.parentNode.removeChild(sheet);
        }, 280);
    }

    function buildSheet() {
        var supported = BYD.i18n.supported();
        var s = document.createElement('div');
        s.className = 'lang-sheet-backdrop';
        s.innerHTML =
            '<div class="lang-sheet" role="dialog" aria-modal="true">' +
                '<div class="lang-sheet-handle"></div>' +
                '<div class="lang-sheet-header">' +
                    '<h3 class="lang-sheet-title">' + esc(BYD.i18n.t('common.select_language')) + '</h3>' +
                    '<button class="lang-sheet-close" type="button" aria-label="Close">' +
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>' +
                    '</button>' +
                '</div>' +
                (supported.length > 10 ?
                    '<div class="lang-sheet-search">' +
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>' +
                        '<input type="text" placeholder="' + esc(BYD.i18n.t('common.search')) + '">' +
                    '</div>' : '') +
                '<ul class="lang-sheet-list" role="listbox"></ul>' +
            '</div>';

        // Close on backdrop click (but not list clicks)
        s.addEventListener('click', function (e) {
            if (e.target === s) closeSheet();
        });
        var closeBtn = s.querySelector('.lang-sheet-close');
        if (closeBtn) closeBtn.addEventListener('click', closeSheet);

        var search = s.querySelector('.lang-sheet-search input');
        if (search) search.addEventListener('input', function () { renderList(this.value); });

        // ESC closes the sheet (keyboard users on any web client)
        document.addEventListener('keydown', function (e) {
            if (sheetVisible && e.key === 'Escape') closeSheet();
        });

        return s;
    }

    function renderList(filter) {
        if (!sheet) return;
        var ul = sheet.querySelector('.lang-sheet-list');
        if (!ul) return;
        var supported = BYD.i18n.supported();
        var current = BYD.i18n.getLang();
        var f = (filter || '').trim().toLowerCase();

        var html = '';

        // "Auto (follow system)" pinned at top — clears localStorage so the
        // next page load re-detects from navigator.language.
        var autoMatches = !f ||
            BYD.i18n.t('common.follow_system').toLowerCase().indexOf(f) >= 0 ||
            'auto'.indexOf(f) >= 0;
        if (autoMatches) {
            html += '<li class="lang-sheet-item lang-sheet-auto" data-action="auto" role="option" tabindex="0">' +
                '<span class="lang-sheet-name">' + esc(BYD.i18n.t('common.follow_system')) + '</span>' +
                '<span class="lang-sheet-meta">' + esc(BYD.i18n.t('common.auto')) + '</span>' +
            '</li>';
        }

        for (var i = 0; i < supported.length; i++) {
            var lang = supported[i];
            var name = BYD.i18n.getDisplayName(lang);
            if (f && name.toLowerCase().indexOf(f) < 0 && lang.toLowerCase().indexOf(f) < 0) continue;
            var isCurrent = lang === current;
            html += '<li class="lang-sheet-item' + (isCurrent ? ' active' : '') + '"' +
                ' data-lang="' + esc(lang) + '" role="option" aria-selected="' + isCurrent + '" tabindex="0">' +
                '<span class="lang-sheet-name">' + esc(name) + '</span>' +
                '<span class="lang-sheet-meta">' + esc(lang) + '</span>' +
                (isCurrent ?
                    '<svg class="lang-sheet-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>'
                    : '') +
            '</li>';
        }
        ul.innerHTML = html;

        // Click + Enter both pick the row
        var items = ul.querySelectorAll('.lang-sheet-item');
        for (var j = 0; j < items.length; j++) {
            items[j].addEventListener('click', onPick);
            items[j].addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPick.call(this, e); }
            });
        }
    }

    function onPick(e) {
        var action = this.getAttribute('data-action');
        var lang = this.getAttribute('data-lang');
        if (action === 'auto') {
            try { localStorage.removeItem('overdrive_locale'); } catch (err) {}
            // Re-detect from navigator.language
            var detected = navigator.language || 'en';
            BYD.i18n.setLang(detected).then(closeSheet);
            return;
        }
        if (lang) BYD.i18n.setLang(lang).then(closeSheet);
    }

    function esc(s) {
        var d = document.createElement('div');
        d.textContent = s == null ? '' : String(s);
        return d.innerHTML;
    }

    /**
     * Mount the chip into the sidebar footer when DOM is ready.
     * Idempotent — safe if the script gets included twice.
     *
     * Suppressed inside the Android WebView. The app's Settings → Language
     * panel is the source of truth there; showing a redundant web picker
     * that gets overridden on the next /status poll just confuses users
     * (they pick a language, see it flip, then watch the app push it back).
     * Same in-app gating pattern as theme.js.
     */
    function mount() {
        if (typeof window.AndroidBridge !== 'undefined') return;
        if (document.getElementById('langChip')) return;
        var footer = document.querySelector('.sidebar-footer');
        if (!footer) return;
        var chip = buildChip();
        chip.id = 'langChip';
        // Insert before the first child so the chip sits at the top of the
        // footer above the status card and EV card. Looks more like a control
        // (top of section) than a vehicle metric (bottom).
        footer.insertBefore(chip, footer.firstChild);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', mount);
    } else {
        mount();
    }
})();
