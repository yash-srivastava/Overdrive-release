/**
 * BYD Champ - Core Module
 * Shared utilities, status polling, and toast notifications
 */

window.BYD = window.BYD || {};

/**
 * i18n runtime — ES5-compatible (Chrome 58 / Android 7.1 head-unit).
 *
 * Usage:
 *   BYD.i18n.t('nav.live_view')                 // → "Live View"
 *   BYD.i18n.t('trip.tier_score', {score: 85})  // → "Score: 85"
 *   BYD.i18n.plural('trip.stored', count)       // pluralized
 *   <span data-i18n="nav.events">Events</span>  // hydrated by BYD.i18n.hydrate(root)
 *   <input data-i18n-attr="placeholder:auth.code_placeholder">
 *
 * Why a custom runtime instead of i18next:
 *   - APK perf budget; i18next + Intl.PluralRules polyfill ~80KB minified.
 *   - Chrome 58 lacks Intl.PluralRules and modern template strings; we hand-roll
 *     plural rules from CLDR for our supported langs (~3KB total).
 *   - One synchronous load before first paint avoids the flash-of-English.
 */
BYD.i18n = (function () {
    var SUPPORTED = [
        'en', 'zh-CN', 'zh-TW', 'pt-BR', 'es', 'de', 'fr', 'it',
        'nb', 'nl', 'ja', 'ko', 'th', 'vi', 'hi', 'tr', 'ru', 'ar'
    ];
    var DEFAULT_LANG = 'en';
    var STORAGE_KEY = 'overdrive_locale';

    // Right-to-left locales. Drives <html dir="rtl">. Arabic is the only RTL
    // language we ship; add he/fa/ur here if they're ever onboarded.
    var RTL_LANGS = { 'ar': true };

    // Native-script display labels (sidebar picker shows these — no flags by design).
    var DISPLAY_NAMES = {
        'en':    'English',
        'zh-CN': '简体中文',
        'zh-TW': '繁體中文',
        'pt-BR': 'Português (Brasil)',
        'es':    'Español',
        'de':    'Deutsch',
        'fr':    'Français',
        'it':    'Italiano',
        'nb':    'Norsk',
        'nl':    'Nederlands',
        'ja':    '日本語',
        'ko':    '한국어',
        'th':    'ไทย',
        'vi':    'Tiếng Việt',
        'hi':    'हिन्दी',
        'tr':    'Türkçe',
        'ru':    'Русский',
        'ar':    'العربية'
    };

    // CLDR plural rules condensed to two-form (one/other) and language-specific quirks.
    // Returns 'one', 'few', 'many', or 'other' so translation files can carry a
    // matching nested object for any plural-aware key.
    function pluralRule(lang, n) {
        n = Math.abs(n);
        var i = Math.floor(n);
        switch (lang) {
            case 'zh-CN': case 'zh-TW': case 'ja': case 'ko': case 'th': case 'vi':
                return 'other';                            // no plural distinction
            case 'fr': case 'pt-BR':
                return n < 2 ? 'one' : 'other';            // 0 and 1 are singular
            case 'tr':
                return 'other';                            // optional plural marker, treat all as other
            case 'hi':
                return n === 0 || n === 1 ? 'one' : 'other';
            case 'ru':
                // Russian / Slavic three-form plural per CLDR:
                //   one  → ends in 1 but not 11           (1, 21, 31, ...; not 11)
                //   few  → ends in 2-4 but not 12-14      (2-4, 22-24, ...; not 12-14)
                //   many → ends in 0, 5-9, or 11-14       (0, 5-20, 25-30, ...)
                var mod10 = i % 10;
                var mod100 = i % 100;
                if (mod10 === 1 && mod100 !== 11) return 'one';
                if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'few';
                return 'many';
            case 'ar':
                // Arabic six-form plural per CLDR. If a catalog entry only
                // carries one/other (the common case), plural()'s lookup falls
                // back to `other`, so these extra forms are harmless until a
                // translator supplies zero/two/few/many for a key.
                //   zero → 0        two → 2
                //   few  → n%100 in 3..10        many → n%100 in 11..99
                //   one  → 1        other → everything else (incl. fractions)
                if (n === 0) return 'zero';
                if (n === 1) return 'one';
                if (n === 2) return 'two';
                var arMod100 = i % 100;
                if (arMod100 >= 3 && arMod100 <= 10) return 'few';
                if (arMod100 >= 11 && arMod100 <= 99) return 'many';
                return 'other';
            default:
                // en, es, de, it, nb, nl
                return n === 1 ? 'one' : 'other';
        }
    }

    var state = {
        lang: DEFAULT_LANG,
        catalog: {},          // flat key → string OR { one, other, ... }
        // English fallback catalog — populated once on first non-en load
        // so t() can fall back when a key is missing from the active
        // locale. Keeps the UI legible between feature ship and NLLB
        // translation pass landing.
        enCatalog: null,
        loaded: false,
        loadingPromise: null,
        listeners: []
    };

    /** Normalise an arbitrary BCP-47 tag to our supported set, with sensible fallbacks. */
    function resolveLang(raw) {
        if (!raw) return DEFAULT_LANG;
        // Exact match first
        for (var i = 0; i < SUPPORTED.length; i++) {
            if (SUPPORTED[i].toLowerCase() === raw.toLowerCase()) return SUPPORTED[i];
        }
        // Region fallback: zh-Hans → zh-CN, zh-Hant → zh-TW, pt → pt-BR, etc.
        var lower = raw.toLowerCase();
        if (lower.indexOf('zh-hans') === 0 || lower === 'zh-cn' || lower === 'zh') return 'zh-CN';
        if (lower.indexOf('zh-hant') === 0 || lower === 'zh-tw' || lower === 'zh-hk') return 'zh-TW';
        if (lower.indexOf('pt') === 0) return 'pt-BR';
        if (lower.indexOf('no') === 0 || lower.indexOf('nn') === 0) return 'nb';
        // Bare-language fallback
        var bare = lower.split('-')[0];
        for (var j = 0; j < SUPPORTED.length; j++) {
            if (SUPPORTED[j].toLowerCase().split('-')[0] === bare) return SUPPORTED[j];
        }
        return DEFAULT_LANG;
    }

    function detectFromBrowser() {
        if (navigator.languages && navigator.languages.length) {
            for (var i = 0; i < navigator.languages.length; i++) {
                var resolved = resolveLang(navigator.languages[i]);
                if (resolved !== DEFAULT_LANG || navigator.languages[i].indexOf('en') === 0) {
                    return resolved;
                }
            }
        }
        return resolveLang(navigator.language);
    }

    function getStored() {
        try { return localStorage.getItem(STORAGE_KEY); } catch (e) { return null; }
    }
    function setStored(lang) {
        try { localStorage.setItem(STORAGE_KEY, lang); } catch (e) { /* private mode */ }
    }

    /** Fetch the catalog JSON for `lang`. Falls back to en on failure. */
    function fetchCatalog(lang) {
        return fetch('/i18n/' + lang + '.json', { cache: 'no-cache' })
            .then(function (r) {
                if (!r.ok) throw new Error('catalog ' + lang + ' http ' + r.status);
                return r.json();
            })
            .catch(function () {
                if (lang === DEFAULT_LANG) return {};
                // Same { cache: 'no-cache' } as the primary fetch above. The daemon
                // serves /i18n/*.json with `public, max-age=86400`, so without it a
                // browser could hold a day-old English catalog — and since init()
                // now WAITS on this catalog to supply the fallback for keys missing
                // from a locale, a stale copy means missing dropdown labels persist
                // for up to 24h after an app update that added them.
                return fetch('/i18n/' + DEFAULT_LANG + '.json', { cache: 'no-cache' })
                    .then(function (r) { return r.json(); });
            });
    }

    /** Look up a dotted key inside a nested catalog. */
    function lookup(catalog, key) {
        if (catalog[key] != null) return catalog[key];   // flat hit
        var parts = key.split('.');
        var cur = catalog;
        for (var i = 0; i < parts.length; i++) {
            if (cur == null) return null;
            cur = cur[parts[i]];
        }
        return cur == null ? null : cur;
    }

    /** {var} interpolation. Missing vars are left as-is so missing data is visible. */
    function interpolate(str, vars) {
        if (!vars || typeof str !== 'string') return str;
        return str.replace(/\{(\w+)\}/g, function (match, name) {
            return vars[name] != null ? vars[name] : match;
        });
    }

    function t(key, vars) {
        var val = lookup(state.catalog, key);
        if (val == null) {
            // No translation available in the active locale. Try the en
            // catalog as a fallback — without this, a key newly added to
            // en.json (e.g. a feature added between translation passes)
            // would render as the raw key string in every non-English
            // locale until the NLLB run lands. The en catalog is the only
            // one guaranteed to have every key by convention.
            //
            // Distinct sentinel returns:
            //   - null  : nothing yet (catalog still loading) → hydrate
            //             keeps the existing default text in the DOM.
            //   - key   : catalog loaded, key missing in BOTH active and
            //             en catalogs → dev-visible miss indicator.
            if (!state.loaded) return null;
            if (state.lang !== 'en' && state.enCatalog) {
                var enVal = lookup(state.enCatalog, key);
                if (enVal != null) {
                    if (typeof enVal === 'object' && enVal.other) enVal = enVal.other;
                    return interpolate(enVal, vars);
                }
            }
            return key;
        }
        if (typeof val === 'object' && val.other) val = val.other;
        return interpolate(val, vars);
    }

    /**
     * Resolve a manifest model name through the active locale when a market
     * uses a different badge. The manifest id remains canonical, so persisted
     * selections and downloaded GLBs are unaffected by localized branding.
     */
    function modelName(modelId, fallback) {
        var rawId = modelId == null ? '' : String(modelId);
        var normalized = rawId.toLowerCase().replace(/[^a-z0-9]+/g, '_');
        if (normalized) {
            var key = 'vehicle.model_name_' + normalized;
            var translated = t(key);
            if (translated != null && translated !== key) return translated;
        }
        return fallback || rawId;
    }

    function plural(key, count, vars) {
        var val = lookup(state.catalog, key);
        if (val == null) return key;
        if (typeof val === 'string') return interpolate(val, vars);
        var rule = pluralRule(state.lang, count);
        var pick = val[rule] != null ? val[rule] : (val.other != null ? val.other : val.one);
        if (pick == null) return key;
        var merged = { count: count };
        if (vars) for (var k in vars) if (vars.hasOwnProperty(k)) merged[k] = vars[k];
        return interpolate(pick, merged);
    }

    /**
     * Walk `root` and rewrite element text per [data-i18n] / attribute per
     * [data-i18n-attr="attr1:key1;attr2:key2"]. Idempotent — safe to call
     * many times. Stores the original key in dataset so subsequent language
     * switches re-translate from the catalog rather than the previous render.
     */
    function hydrate(root) {
        root = root || document;
        var nodes = root.querySelectorAll('[data-i18n]');
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            var key = n.getAttribute('data-i18n');
            var translated = t(key);
            // null = catalog not loaded yet → leave existing text alone, the
            // listener fired on catalog-ready will re-hydrate. Don't write
            // raw keys to DOM.
            if (translated == null) continue;
            // If the node has children other than the original text, only replace its
            // first text node so we don't blow away nested icons/SVGs (e.g. nav links).
            if (n.children.length > 0) {
                var replaced = false;
                for (var c = 0; c < n.childNodes.length; c++) {
                    var child = n.childNodes[c];
                    if (child.nodeType === 3 && child.nodeValue.replace(/\s/g, '').length > 0) {
                        child.nodeValue = translated;
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) n.appendChild(document.createTextNode(translated));
            } else {
                n.textContent = translated;
            }
        }
        var attrNodes = root.querySelectorAll('[data-i18n-attr]');
        for (var j = 0; j < attrNodes.length; j++) {
            var an = attrNodes[j];
            var spec = an.getAttribute('data-i18n-attr').split(';');
            for (var s = 0; s < spec.length; s++) {
                var pair = spec[s].split(':');
                if (pair.length === 2) {
                    var translatedAttr = t(pair[1].trim());
                    if (translatedAttr != null) {
                        an.setAttribute(pair[0].trim(), translatedAttr);
                    }
                }
            }
        }
        // Update <html lang="..."> so screen readers and CSS :lang() work.
        if (document.documentElement) {
            document.documentElement.setAttribute('lang', state.lang);
            // RTL scripts need <html dir="rtl"> so the browser mirrors the
            // (start/end-based) layout. Only Arabic is RTL in our set; every
            // other locale stays 'ltr'. Set it explicitly (not just for 'ar')
            // so switching AWAY from Arabic restores 'ltr' in the same WebView.
            document.documentElement.setAttribute('dir', RTL_LANGS[state.lang] ? 'rtl' : 'ltr');
        }
    }

    function onChange(fn) { state.listeners.push(fn); }
    function notify() {
        for (var i = 0; i < state.listeners.length; i++) {
            try { state.listeners[i](state.lang); } catch (e) { console.error('[i18n]', e); }
        }
    }

    // True when the page is loaded inside the Android WebView (the app
     // shell injects a JavascriptInterface called AndroidBridge). The locale
     // policy differs between the two contexts:
     //
     //   In-app WebView   — the APP's locale is the source of truth. Any
     //                      web-side picker change is pushed to the server
     //                      (which writes the app-side LocaleManager); the
     //                      server's value is also pulled in via /status so
     //                      flipping language in the Android Settings panel
     //                      live-syncs the WebView.
     //   External tunnel  — the BROWSER's localStorage is the source of
     //                      truth. The picker writes only locally; we do
     //                      NOT post to the server (would cross-pollute
     //                      the app's locale) and we do NOT honour
     //                      `status.locale` overrides (that's the app's
     //                      preference, not ours).
     //
     // This keeps the two locales fully separated, matching the design
     // already in place for the theme picker.
    function inAppWebView() {
        return typeof window !== 'undefined' && typeof window.AndroidBridge !== 'undefined';
    }

    /**
     * Switch active language. Always persists locally. Server persistence
     * splits two ways:
     *   In-app WebView  → POST /api/i18n/lang (writes the app's
     *                     LocaleManager so server-emitted strings match).
     *   External tunnel → POST /api/settings/appearance with {locale}
     *                     (writes a SEPARATE web-only locale into the
     *                     unified config). Survives tunnel-URL rotation:
     *                     each new zrok session is a fresh origin so
     *                     localStorage alone is not enough.
     * Either way, server writes are fire-and-forget; the catalog refetch
     * is the only thing the UI waits on.
     */
    function setLang(lang) {
        var resolved = resolveLang(lang);
        if (resolved === state.lang && state.loaded) return Promise.resolve();
        state.lang = resolved;
        setStored(resolved);
        if (inAppWebView()) {
            try { fetch('/api/i18n/lang', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ lang: resolved })
            }); } catch (e) {}
        } else {
            try { fetch('/api/settings/appearance', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ locale: resolved })
            }); } catch (e) {}
        }
        return fetchCatalog(resolved).then(function (cat) {
            state.catalog = cat || {};
            state.loaded = true;
            // AWAIT the en fallback before hydrate/notify, for the same reason
            // init() does: an onChange subscriber re-renders imperative text
            // (dropdown <option>s) that no later hydrate() can repair, so the
            // fallback has to be in place BEFORE we wake them.
            var fallbackReady = (state.lang !== 'en' && state.enCatalog == null)
                ? fetchCatalog('en').then(function (enCat) {
                      state.enCatalog = enCat || {};
                  }).catch(function () { /* best-effort */ })
                : Promise.resolve();
            return fallbackReady.then(function () {
                hydrate(document);
                notify();
            });
        });
    }

    /**
     * Returns true if the live `status.locale` from the server should
     * override the locally-chosen language. In-app WebView: yes (app is
     * the source of truth). External tunnel/browser: no (web picker is
     * the source of truth). Exposed via the public API so core.js's
     * /status handler can decide whether to call setLang().
     */
    function shouldFollowServerLocale() {
        return inAppWebView();
    }

    /**
     * Bootstrap. Pick order depends on context:
     *   In-app WebView  — AndroidBridge.getAppLocale() (sync, always fresh)
     *                     → localStorage → navigator.language → 'en'
     *   External        — localStorage → navigator.language → 'en'
     *                     and asynchronously sync from /api/settings/appearance
     *                     so a freshly-rotated tunnel URL still serves the
     *                     user's last-picked language (localStorage is per
     *                     origin, the server-stored value survives URL flips).
     */
    function init() {
        if (state.loadingPromise) return state.loadingPromise;
        var picked = null;
        if (inAppWebView()) {
            try {
                if (typeof window.AndroidBridge.getAppLocale === 'function') {
                    var fromApp = window.AndroidBridge.getAppLocale();
                    if (fromApp) {
                        picked = fromApp;
                        // The app's locale is the source of truth in-app.
                        // Mirror it into localStorage so a stale value left
                        // there from before this code shipped (or from a
                        // prior tunnel session on the same WebView profile)
                        // can never resurface in a future load. Without
                        // this, a user who picked French on the tunnel,
                        // then English in the app, would still see French
                        // until they cleared cache.
                        try { setStored(resolveLang(fromApp)); } catch (e) {}
                    }
                }
            } catch (e) { /* fall through to localStorage */ }
        }
        if (!picked) picked = getStored() || detectFromBrowser();
        state.lang = resolveLang(picked);
        state.loadingPromise = fetchCatalog(state.lang).then(function (cat) {
            state.catalog = cat || {};
            state.loaded = true;
            // Load en as a side-channel fallback when the active locale is non-en.
            // AWAITED, not fire-and-forget: callers gate their whole UI build on
            // init() (see key-mapping.html / automations.html), and dropdown
            // <option> text is written imperatively via textContent — it carries no
            // [data-i18n] attribute, so the later hydrate() cannot repair it. If a
            // page built its selects before enCatalog landed, every key missing from
            // the active locale rendered as the RAW KEY forever ("keymap.act_bsd").
            // That is not hypothetical: web/i18n/ar.json has no keymap or automation
            // section at all (all 188 keymap options), and nb.json is missing 77.
            // Waiting costs one parallel fetch of an already-cached asset.
            var fallbackReady = (state.lang !== 'en' && state.enCatalog == null)
                ? fetchCatalog('en').then(function (enCat) {
                      state.enCatalog = enCat || {};
                  }).catch(function () { /* best-effort — active catalog still usable */ })
                : Promise.resolve();
            return fallbackReady.then(function () {
                hydrate(document);
                notify();
                // External mode: pull the server-stored web locale to handle
                // the tunnel-URL-rotation case (localStorage on the new
                // origin is empty, but the server remembers the last pick).
                // Skipped in-app — the AndroidBridge sync read above is
                // already authoritative. Stays fire-and-forget: it only
                // CORRECTS an already-rendered page, so gating init() on it
                // would delay first paint for every tunnel user.
                if (!inAppWebView()) {
                    fetchServerWebLocale().then(function (serverLang) {
                        if (!serverLang) return;
                        var resolved = resolveLang(serverLang);
                        if (resolved && resolved !== state.lang) {
                            // Mirror the server pick into localStorage so a
                            // subsequent reload short-circuits without a fetch.
                            setStored(resolved);
                            // setLang() refetches + rehydrates. Skip the
                            // server POST inside it (we just READ the value).
                            state.lang = resolved;
                            fetchCatalog(resolved).then(function (cat2) {
                                state.catalog = cat2 || {};
                                hydrate(document);
                                notify();
                            });
                        }
                    });
                }
                return cat;
            });
        });
        return state.loadingPromise;
    }

    /** Read the web-only locale from /api/settings/appearance. Returns null
     *  on any failure or if the server has the "auto" sentinel (which means
     *  "no explicit pick — use the local detection"). */
    function fetchServerWebLocale() {
        try {
            return fetch('/api/settings/appearance', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (j) {
                    if (!j || !j.locale || j.locale === 'auto') return null;
                    return j.locale;
                })
                .catch(function () { return null; });
        } catch (e) {
            return Promise.resolve(null);
        }
    }

    return {
        init: init,
        t: t,
        plural: plural,
        hydrate: hydrate,
        setLang: setLang,
        onChange: onChange,
        getLang: function () { return state.lang; },
        modelName: modelName,
        getDisplayName: function (lang) { return DISPLAY_NAMES[lang] || lang; },
        supported: function () { return SUPPORTED.slice(); },
        // True when the app's server-side locale should override the local
        // pick — i.e. inside the Android WebView. The /status poll uses
        // this to avoid clobbering a tunnel user's web-only choice.
        shouldFollowServerLocale: shouldFollowServerLocale,
        // For tests / picker UI
        _resolve: resolveLang
    };
})();

/**
 * Unit formatting utility. All backend values are stored in km/km·h.
 * When the user's vehicle is set to miles, this module converts for display.
 * The mode is updated from the /status response on every poll cycle.
 */
BYD.units = {
    mode: 'km',  // 'km' or 'mi' — updated from /status.distanceUnit
    KM_TO_MI: 0.621371,

    /**
     * Format a distance value (stored in km) for display.
     *
     * `decimals` is honoured in BOTH unit modes. It previously applied only to km
     * and miles always rounded to whole, which silently discarded the precision
     * at call sites that ask for it — e.g. two odometer readings a few hundred
     * metres apart rendered as the same number.
     */
    dist(km, decimals) {
        if (km == null || isNaN(km)) return '--';
        if (this.mode === 'mi') {
            var mi = km * this.KM_TO_MI;
            return (decimals != null ? mi.toFixed(decimals) : Math.round(mi)) + ' mi';
        }
        return (decimals != null ? km.toFixed(decimals) : Math.round(km)) + ' km';
    },

    /** Format a speed value (stored in km/h) for display. */
    speed(kmh, decimals) {
        if (kmh == null || isNaN(kmh)) return '--';
        var d = decimals != null ? decimals : 1;
        if (this.mode === 'mi') return (kmh * this.KM_TO_MI).toFixed(d) + ' mph';
        return kmh.toFixed(d) + ' km/h';
    },

    /** Return just the distance unit label. */
    distLabel() { return this.mode === 'mi' ? 'mi' : 'km'; },

    /** Return just the speed unit label. */
    speedLabel() { return this.mode === 'mi' ? 'mph' : 'km/h'; },

    /** Convert km value to display value (number only, no label). */
    distVal(km) {
        if (km == null || isNaN(km)) return 0;
        return this.mode === 'mi' ? Math.round(km * this.KM_TO_MI) : Math.round(km);
    },

    /** Convert km/h value to display value (number only, no label). */
    speedVal(kmh) {
        if (kmh == null || isNaN(kmh)) return 0;
        return this.mode === 'mi' ? kmh * this.KM_TO_MI : kmh;
    },

    /**
     * Convert a "per-100km" rate (kWh/100km, %/100km, anything-per-100km)
     * to "per-100mi". Same rate over a longer distance unit, so the
     * numerator scales by 1/KM_TO_MI ≈ 1.609.
     */
    per100Val(perKm) {
        if (perKm == null || isNaN(perKm)) return 0;
        return this.mode === 'mi' ? perKm / this.KM_TO_MI : perKm;
    },

    /** Per-100 consumption label: "kWh/100km" or "kWh/100mi". */
    consumptionLabel() { return this.mode === 'mi' ? 'kWh/100mi' : 'kWh/100km'; },

    /**
     * Convert a "distance-per-kWh" efficiency (km/kWh) to the user's unit.
     * This is the inverse-facing sibling of per100Val: higher is better.
     * In miles mode, mi/kWh = km/kWh × KM_TO_MI (distance shrinks per unit).
     */
    effVal(kmPerKwh) {
        if (kmPerKwh == null || isNaN(kmPerKwh)) return 0;
        return this.mode === 'mi' ? kmPerKwh * this.KM_TO_MI : kmPerKwh;
    },

    /** Distance-per-energy efficiency label: "km/kWh" or "mi/kWh". */
    efficiencyLabel() { return this.mode === 'mi' ? 'mi/kWh' : 'km/kWh'; },

    /** "per km" or "per mi" for cost display. */
    perDistLabel() { return this.mode === 'mi' ? '/mi' : '/km'; },

    /** "%/km" or "%/mi" for SoC-based efficiency. */
    socPerDistLabel() { return this.mode === 'mi' ? ' %/mi' : ' %/km'; },

    /** Round a km/h threshold (40, 80) to the user's unit for legend labels. */
    speedThreshold(kmh) {
        return this.mode === 'mi' ? Math.round(kmh * this.KM_TO_MI) : kmh;
    },

    // ── Tyre pressure ────────────────────────────────────────────────
    // Canonical unit is kPa everywhere on the wire and in every threshold
    // comparison (see TyreLimitsApiHandler); these helpers convert at the
    // last render step only. pressureMode mirrors /status.pressureUnit the
    // same way `mode` mirrors distanceUnit. Default 'psi' matches what the
    // UI displayed before the preference existed.
    pressureMode: 'psi',  // 'kpa' | 'psi' | 'bar' — updated from /status.pressureUnit
    KPA_TO_PSI: 0.1450377,

    /**
     * Convert a kPa reading to the display value (number string, no label).
     * Decimals per unit: kPa integer (TPMS step ≈ 3 kPa), PSI one decimal
     * (matches the server's psi field rounding), bar two decimals.
     */
    pressureVal(kPa) {
        if (kPa == null || isNaN(kPa)) return '--';
        if (this.pressureMode === 'kpa') return String(Math.round(kPa));
        if (this.pressureMode === 'bar') return (kPa / 100).toFixed(2);
        return (kPa * this.KPA_TO_PSI).toFixed(1);
    },

    /** Format a kPa reading with its unit label, e.g. "36.5 PSI". */
    pressure(kPa) {
        if (kPa == null || isNaN(kPa)) return '--';
        return this.pressureVal(kPa) + ' ' + this.pressureLabel();
    },

    /** Return just the pressure unit label. */
    pressureLabel() {
        if (this.pressureMode === 'kpa') return 'kPa';
        if (this.pressureMode === 'bar') return 'bar';
        return 'PSI';
    }
};

/**
 * Screenshot privacy mode.
 *
 * The native Settings switch is persisted in unified config and echoed by
 * /status. This shared client applies the same policy to every daemon-served
 * page without copying masking rules into individual screens. The class is a
 * visual-only aid: values remain untouched for controls, links, and APIs.
 */
BYD.screenshotPrivacy = (function () {
    var MASK_CLASS = 'screenshot-privacy-mask';
    var AUTO_ATTR = 'data-screenshot-privacy-auto';
    var STYLE_ID = 'screenshotPrivacyStyle';
    var enabled = false;
    var observer = null;
    var scanQueued = false;
    var ipv4 = /(^|[^\d])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?::\d{1,5})?(?!\d)/;
    // Charging history renders stored coordinates to three decimal places,
    // while other screens retain the full precision. Treat both as location
    // data so a presentation-oriented rounding step cannot bypass the mask.
    var coordinates = /(^|[^\d.])[+-]?(?:\d{1,2}|1[0-7]\d|180)\.\d{3,}\s*[,;\/]\s*[+-]?(?:\d{1,2}|1[0-7]\d|180)\.\d{3,}(?![\d.])/;
    var labelledCoordinate = /\b(?:lat(?:itude)?|lon(?:gitude)?|lng)\s*[:=]\s*[+-]?\d{1,3}\.\d{3,}/i;
    var url = /\b(?:https?|wss?):\/\/\S+/i;
    var email = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i;
    var identityTokens = [
        'qrcode', 'deviceid', 'currenturl', 'tunnelurl', 'remoteurl',
        'ipaddress', 'latitude', 'longitude', 'coordinates', 'location',
        'address', 'mapcontainer', 'dashlocation', 'detailloc', 'locrow',
        'placefilter', 'placesearch', 'thumbnail', 'videoplayer',
        'camerapreview', 'cameraimage', 'snapshot', 'platenumber',
        'licenseplate'
    ];

    function ensureStyle() {
        if (document.getElementById(STYLE_ID)) return;
        var style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent =
            'html.screenshot-privacy-mode .' + MASK_CLASS + ' {' +
            'filter: blur(14px) brightness(.42) contrast(.45) saturate(.25) !important;' +
            '-webkit-filter: blur(14px) brightness(.42) contrast(.45) saturate(.25) !important;' +
            'user-select: none !important;' +
            '-webkit-user-select: none !important;' +
            '}' +
            'html.screenshot-privacy-mode img.' + MASK_CLASS + ',' +
            'html.screenshot-privacy-mode video.' + MASK_CLASS + ',' +
            'html.screenshot-privacy-mode canvas.' + MASK_CLASS + ',' +
            'html.screenshot-privacy-mode .recording-thumbnail.' + MASK_CLASS + ',' +
            'html.screenshot-privacy-mode .video-player.' + MASK_CLASS + ' {' +
            'filter: blur(22px) brightness(.32) contrast(.35) saturate(.15) !important;' +
            '-webkit-filter: blur(22px) brightness(.32) contrast(.35) saturate(.15) !important;' +
            '}';
        (document.head || document.documentElement).appendChild(style);
    }

    function normalize(value) {
        return String(value || '').toLowerCase().replace(/[^a-z0-9]/g, '');
    }

    function hasSensitiveIdentity(el) {
        if (!el || !el.getAttribute) return false;
        if (el.hasAttribute('data-screenshot-private')) return true;
        var identity = normalize(
            (el.id || '') + ' ' +
            (typeof el.className === 'string' ? el.className : '') + ' ' +
            (el.getAttribute('name') || '') + ' ' +
            (el.getAttribute('aria-label') || '') + ' ' +
            (el.getAttribute('data-card-id') || '') + ' ' +
            (el.getAttribute('data-filter-row') || '')
        );
        if (identity.indexOf('qr') >= 0) return true;
        for (var i = 0; i < identityTokens.length; i++) {
            if (identity.indexOf(identityTokens[i]) >= 0) return true;
        }
        return false;
    }

    function directText(el) {
        if (!el) return '';
        var tag = String(el.tagName || '').toLowerCase();
        if (tag === 'input' || tag === 'textarea') return el.value || '';
        var result = '';
        for (var i = 0; i < el.childNodes.length; i++) {
            var node = el.childNodes[i];
            if (node.nodeType === 3) result += ' ' + node.nodeValue;
        }
        return result;
    }

    function hasSensitiveText(text) {
        var value = String(text || '').trim();
        return value && (ipv4.test(value) || coordinates.test(value) ||
            labelledCoordinate.test(value) || url.test(value) || email.test(value));
    }

    function mark(el) {
        if (!el || !el.classList || el === document.body || el === document.documentElement) return;
        var ancestor = el.parentElement && el.parentElement.closest
            ? el.parentElement.closest('.' + MASK_CLASS)
            : null;
        if (ancestor) return;
        el.classList.add(MASK_CLASS);
        el.setAttribute(AUTO_ATTR, 'true');
    }

    function scan(root) {
        if (!enabled || !document.documentElement) return;
        ensureStyle();
        var scope = root && root.nodeType === 1 ? root : document;
        var elements = [];
        if (scope.nodeType === 1) elements.push(scope);
        var descendants = scope.querySelectorAll ? scope.querySelectorAll('*') : [];
        for (var i = 0; i < descendants.length; i++) elements.push(descendants[i]);

        for (var j = 0; j < elements.length; j++) {
            var el = elements[j];
            var tag = String(el.tagName || '').toLowerCase();
            if (tag === 'script' || tag === 'style' || tag === 'link' || tag === 'meta') continue;
            if (hasSensitiveIdentity(el) || hasSensitiveText(directText(el))) mark(el);
        }
    }

    function queueScan() {
        if (scanQueued || !enabled) return;
        scanQueued = true;
        setTimeout(function () {
            scanQueued = false;
            scan(document);
        }, 0);
    }

    function setEnabled(next) {
        next = !!next;
        if (enabled === next) {
            if (enabled) queueScan();
            return;
        }
        enabled = next;
        if (!document.documentElement) return;
        document.documentElement.classList.toggle('screenshot-privacy-mode', enabled);
        if (enabled) {
            ensureStyle();
            scan(document);
            if (!observer && typeof MutationObserver !== 'undefined') {
                observer = new MutationObserver(queueScan);
                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    characterData: true
                });
            }
        } else {
            if (observer) observer.disconnect();
            observer = null;
            scanQueued = false;
            var marked = document.querySelectorAll('[' + AUTO_ATTR + ']');
            for (var i = 0; i < marked.length; i++) {
                marked[i].classList.remove(MASK_CLASS);
                marked[i].removeAttribute(AUTO_ATTR);
            }
        }
    }

    return {
        setEnabled: setEnabled,
        isEnabled: function () { return enabled; },
        scan: scan
    };
})();

BYD.core = {
    deviceId: null,
    pollInterval: null,
    tickInFlight: false,
    lastStatus: null,
    // Counts /status fetch failures (network error, non-2xx, JSON parse).
    // Drives the UI "stale" / "disconnected" indicators and a sooner retry,
    // so a brief tunnel/Wi-Fi blip doesn't blank the dashboard for 5 s.
    pollFailureCount: 0,
    // Whether we've ever received a populated vehicle-data status. Used to
    // decide between "Waiting for vehicle…" (first load, binders not bound
    // yet) and last-known-good (we had data, transient error since).
    hasEverHadVehicleData: false,
    POLL_INTERVAL_OK_MS: 5000,
    POLL_INTERVAL_RETRY_MS: 1500,
    // Cadence while the tab is hidden/backgrounded. A tab left open over the
    // tunnel (e.g. a phone pointed at the zrok URL) would otherwise poll
    // /status every 5 s, 24/7 (~17k polls/day) over cellular. When nothing is
    // on screen there is nothing to refresh, so back off hard and resume
    // instantly on show (visibilitychange below forces an immediate tick).
    POLL_INTERVAL_HIDDEN_MS: 60000,
    POLL_STALE_AFTER_FAILURES: 2,

    /**
     * Initialize core module
     */
    init() {
        this.startStatusPolling();
        this.startClock();
        console.log('[Core] Initialized');
    },

    /**
     * Start clock update (if element exists)
     */
    startClock() {
        const update = () => {
            const el = document.getElementById('currentTime');
            if (el) {
                el.textContent = new Date().toLocaleTimeString(BYD.i18n.getLang(), {
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false
                });
            }
        };
        update();
        setInterval(update, 1000);
    },

    /**
     * Start status polling.
     *
     * Adaptive cadence: 5 s on success, 1.5 s while failing. Self-rescheduling
     * (no fixed setInterval) so the next tick always reflects the current
     * health — without this, a single long-fail period would still hold the
     * UI in "stale" for the full 5 s after recovery.
     */
    startStatusPolling() {
        var self = this;
        function tick() {
            // Single-flight guard: never run two self-rescheduling chains at
            // once. A visibilitychange firing mid-fetch must not spawn a
            // second loop (which would double the poll rate and leak a timer).
            if (self.tickInFlight) { return; }
            self.tickInFlight = true;
            self.pollInterval = null;  // no pending timer while fetching
            self.refreshStatus().then(function (ok) {
                self.tickInFlight = false;
                var delay;
                if (typeof document !== 'undefined' && document.hidden) {
                    // Tab not visible — back off to the hidden cadence
                    // regardless of OK/retry to stop background cellular polling.
                    delay = self.POLL_INTERVAL_HIDDEN_MS;
                } else {
                    delay = ok ? self.POLL_INTERVAL_OK_MS : self.POLL_INTERVAL_RETRY_MS;
                }
                self.pollInterval = setTimeout(tick, delay);
            }, function () {
                // refreshStatus() is async and could reject — clear the flag and
                // reschedule so a thrown error can never wedge polling forever.
                self.tickInFlight = false;
                self.pollInterval = setTimeout(tick, self.POLL_INTERVAL_RETRY_MS);
            });
        }
        // Resume immediately when the tab becomes visible again: cancel the
        // pending (possibly 60 s) hidden-cadence timer and tick now so the
        // dashboard is fresh the instant the user looks at it. tick() is a
        // no-op if a fetch is already in flight (guarded above).
        if (typeof document !== 'undefined' && document.addEventListener) {
            document.addEventListener('visibilitychange', function () {
                if (!document.hidden) {
                    if (self.pollInterval) { clearTimeout(self.pollInterval); self.pollInterval = null; }
                    tick();
                }
            });
        }
        tick();
    },

    /**
     * Refresh status from server (consolidated — includes GPS, vehicle data, etc).
     *
     * Resilience contract:
     *   - On HTTP 401: redirect to /login.html (JWT expired or never set).
     *   - On network/parse error: keep previously-rendered values, increment
     *     failure counter, and let startStatusPolling() retry sooner. We do
     *     NOT reset cards to "--" on a single bad poll — that's what made
     *     the dashboard look broken on tunnel hiccups.
     *   - On success but vehicleDataReady=false: show "Waiting for vehicle…"
     *     in the EV card on first load; keep last-known after that.
     *
     * @returns {Promise<Object|null>} the parsed status object on success
     *          (truthy → OK, used by tick() and update-flow's drift watch),
     *          or null on failure.
     */
    async refreshStatus() {
        try {
            const res = await fetch('/status');
            // 401 means JWT is missing/expired/invalid — bounce to login so
            // the user lands on a screen that actually does something. The
            // global fetch wrapper in auth.js attaches Authorization but does
            // NOT redirect on 401; we handle it here for /status specifically.
            if (res.status === 401) {
                this._showStaleBanner('disconnected');
                const path = window.location.pathname + window.location.search;
                window.location.href = '/login.html?redirect=' + encodeURIComponent(path);
                return null;
            }
            if (!res.ok) {
                throw new Error('HTTP ' + res.status);
            }
            const status = await res.json();
            this.pollFailureCount = 0;
            this._clearStaleBanner();
            this.lastStatus = status;
            // Track whether the server has ever delivered real vehicle data.
            // Drives the "Waiting for vehicle…" placeholder vs. last-known
            // behaviour on cards downstream.
            const hadData = !!(status.soc || status.range || status.charging);
            if (hadData) this.hasEverHadVehicleData = true;

            // One persisted developer switch controls native and web captures.
            // Apply before updating fields so newly-written sensitive values
            // are masked during the same render turn.
            if (BYD.screenshotPrivacy) {
                BYD.screenshotPrivacy.setEnabled(!!status.screenshotPrivacyMode);
            }

            // Distance unit preference (from user setting / auto-detect).
            // Announce a real CHANGE: this poll runs at ~1 Hz and silently
            // swapped the mode, so every already-rendered value flipped to the
            // new unit while its label — written only when the page itself
            // handles a unit switch — kept saying the old one. Pages listen for
            // this and repaint; the guard means a steady-state poll fires nothing.
            if (status.distanceUnit) {
                var previousDistanceUnit = BYD.units.mode;
                BYD.units.mode = status.distanceUnit;
                if (previousDistanceUnit !== status.distanceUnit) {
                    try {
                        window.dispatchEvent(new CustomEvent('byd:units-changed', {
                            detail: { mode: status.distanceUnit }
                        }));
                    } catch (e) { /* CustomEvent unsupported — values still convert */ }
                }
            }

            // Tyre pressure display unit — same delivery pattern as
            // distanceUnit. Guarded to the known tokens so an older/newer
            // server can't push a value the formatters don't understand.
            if (status.pressureUnit === 'kpa' || status.pressureUnit === 'psi'
                    || status.pressureUnit === 'bar') {
                BYD.units.pressureMode = status.pressureUnit;
            }

            // Locale sync — ONLY in the Android WebView, where the app's
            // language picker is the source of truth. External tunnel /
            // browser users keep their own web-only locale; we must not
            // clobber their pick with the app's server-side LocaleManager
            // value (which is what status.locale carries).
            if (status.locale && BYD.i18n
                    && BYD.i18n.shouldFollowServerLocale
                    && BYD.i18n.shouldFollowServerLocale()
                    && status.locale !== BYD.i18n.getLang()) {
                BYD.i18n.setLang(status.locale);
            }

            // Device ID
            if (status.deviceId) {
                this.deviceId = status.deviceId;
                const el = document.getElementById('deviceId');
                if (el) el.textContent = status.deviceId;
            }

            // App version. appVersion is getDisplayVersionFromFile() — the
            // VERSION_FILE-persisted installed GitHub label "<channel>-v<semver>"
            // (e.g. "braveheart-v27.4"), falling back to the BuildConfig identity
            // only when nothing was installed via the updater. Either way it
            // ALREADY carries its own "-v", so do NOT prepend another 'v' (that
            // produced "valpha-v26.0"). Show the label verbatim, matching the
            // native About row (also getDisplayVersion / VERSION_FILE-first).
            if (status.appVersion) {
                const el = document.getElementById('appVersion');
                if (el) el.textContent = status.appVersion;
            }

            // 12V Battery -- reads recordingStatus.voltage12v, a direct live
            // car_service read on every /status poll (no staleness/caching
            // layer, same pattern as gear/doors). The old status.battery.*
            // path depends on a vendor data source (BYDAutoStatisticDevice)
            // that's flaky/slow-to-bind on this vehicle.
            // recordingStatus.dilink5 is the same DiLink5Platform.isActive()
            // gate the backend already uses to decide which read path to
            // even attempt -- on a DiLink5 car we go car_service-only
            // (falling back only to the last-known-good cache during the
            // rare gap where car_service's lastEvent hasn't fired yet, e.g.
            // right after a reboot), and never consult the stock field at
            // all, so a stale/flaky vendor reading can't ever shadow it. Off
            // DiLink5 (other platforms/DiLink4 cars where car_service's
            // dumpsysText() returns unavailable and this field is always
            // -1), stock is the only path, same as stock ever was.
            {
                const rs12v = status.recordingStatus;
                const isDiLink5 = !!(rs12v && rs12v.dilink5 === true);
                const carSvcVoltage = (rs12v && rs12v.voltage12v !== undefined) ? Number(rs12v.voltage12v) : NaN;
                const carSvcValid = isFinite(carSvcVoltage) && carSvcVoltage > 0;

                const stockVoltage = status.battery ? Number(status.battery.voltage) : NaN;
                const stockValid = !!status.battery
                    && status.battery.available !== false
                    && status.battery.isStale !== true
                    && isFinite(stockVoltage)
                    && stockVoltage > 0;

                const el = document.getElementById('batteryValue');
                if (isDiLink5) {
                    if (carSvcValid) {
                        if (el) el.textContent = carSvcVoltage.toFixed(1) + 'V';
                        try { localStorage.setItem('cachedVoltage12v', String(carSvcVoltage)); } catch (e) {}
                    } else if (el && !el.textContent) {
                        let cached12vDl5 = null;
                        try { cached12vDl5 = localStorage.getItem('cachedVoltage12v'); } catch (e) {}
                        el.textContent = cached12vDl5 ? Number(cached12vDl5).toFixed(1) + 'V' : '--';
                    }
                } else if (stockValid) {
                    if (el) el.textContent = stockVoltage.toFixed(1) + 'V';
                    try { localStorage.setItem('cachedVoltage12v', String(stockVoltage)); } catch (e) {}
                } else if (el && !el.textContent) {
                    // Only reach for the cache on first paint (empty element);
                    // a later invalid poll just leaves the last good text
                    // alone instead of blanking it back to "--".
                    let cached12v = null;
                    try { cached12v = localStorage.getItem('cachedVoltage12v'); } catch (e) {}
                    el.textContent = cached12v ? Number(cached12v).toFixed(1) + 'V' : '--';
                }
            }

            // ACC status
            const accEl = document.getElementById('accValue');
            if (accEl) {
                accEl.textContent = status.acc ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
                accEl.className = 'status-value ' + (status.acc ? 'on' : 'off');
            }

            // Surveillance status
            const survEl = document.getElementById('survStatus');
            if (survEl) {
                if (status.safeZoneSuppressed || status.inSafeZone) {
                    survEl.textContent = '🏠 ' + BYD.i18n.t('status.safe');
                    survEl.className = 'status-value safe';
                } else {
                    const active = status.gpuSurveillance || false;
                    survEl.textContent = active ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
                    survEl.className = 'status-value ' + (active ? 'on' : 'off');
                }
            }

            // Network status (WiFi SSID + IP or Mobile Data)
            this.updateNetworkStatus(status);

            // Connection dot
            const connDot = document.getElementById('connDot');
            if (connDot) {
                connDot.classList.add('connected');
            }

            // EV Battery SOC
            this.updateEvStatus(status);

            // GPS data is now in status.gps - notify map module if exists
            if (status.gps && BYD.map && BYD.map.updateFromStatus) {
                BYD.map.updateFromStatus(status.gps);
            }

            // Notify surveillance module if exists
            if (BYD.surveillance && BYD.surveillance.updateFromStatus) {
                BYD.surveillance.updateFromStatus(status);
            }

            // Notify recording module if exists — drives the Recording Status
            // card (badge + Current State) on recording.html from live state.
            if (BYD.recording && BYD.recording.updateFromStatus) {
                BYD.recording.updateFromStatus(status);
            }

            return status;
        } catch (e) {
            this.pollFailureCount++;
            console.warn('[Core] Status refresh failed (' + this.pollFailureCount + '): ' + e);
            // Don't blank the dashboard on a single hiccup — keep the last
            // good values rendered. After a couple of consecutive failures,
            // surface a clear "Disconnected" indicator so the user knows the
            // numbers on screen are no longer fresh.
            if (this.pollFailureCount >= this.POLL_STALE_AFTER_FAILURES) {
                const connDot = document.getElementById('connDot');
                if (connDot) connDot.classList.remove('connected');
                this._showStaleBanner(this.pollFailureCount > 4 ? 'disconnected' : 'stale');
            }
            return null;
        }
    },

    /**
     * Show a small connection-state pill near the sidebar status card.
     * Created lazily so pages without the sidebar (e.g. login) cost nothing.
     * The pill replaces the deviceId text on the device row when stale —
     * keeps the existing two-column status-row layout intact and stays
     * clear of the data-i18n hydration path on the label.
     */
    _showStaleBanner(state) {
        var deviceEl = document.getElementById('deviceId');
        if (!deviceEl) return;
        // Stash the real device id so we can restore it on recovery.
        if (deviceEl.dataset.realText === undefined) {
            deviceEl.dataset.realText = deviceEl.textContent;
        }
        var pill = document.getElementById('connStatePill');
        if (!pill) {
            pill = document.createElement('span');
            pill.id = 'connStatePill';
            // Tight pill sized to fit the .status-value column even on
            // narrow sidebars. Long translations (e.g. Norwegian
            // "Frakoblet") clip with ellipsis rather than push the row out
            // of the card.
            pill.style.cssText = 'padding:2px 6px;border-radius:10px;' +
                'font-size:10px;font-weight:600;letter-spacing:.3px;' +
                'text-transform:uppercase;max-width:100%;display:inline-block;' +
                'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
                'vertical-align:middle;';
            deviceEl.textContent = '';
            deviceEl.appendChild(pill);
        }
        // i18n.t() returns null while the catalog is still loading and the
        // raw key (e.g. "status.disconnected") when loaded but the key is
        // missing in the active locale. Treat both as "fall back to the
        // English label" so the user never sees a dotted-namespace string.
        var i18nLookup = function (key, fallback) {
            if (!window.BYD || !BYD.i18n) return fallback;
            var v = BYD.i18n.t(key);
            return (v && v !== key) ? v : fallback;
        };
        if (state === 'disconnected') {
            pill.textContent = i18nLookup('status.disconnected', 'Disconnected');
            pill.style.background = 'rgba(239,68,68,0.18)';
            pill.style.color = '#ef4444';
        } else {
            pill.textContent = i18nLookup('status.stale', 'Stale');
            pill.style.background = 'rgba(251,191,36,0.18)';
            pill.style.color = '#f59e0b';
        }
        pill.style.display = '';
    },

    _clearStaleBanner() {
        var pill = document.getElementById('connStatePill');
        if (!pill) return;
        // Restore the real device id text so the row reads normally again.
        var deviceEl = document.getElementById('deviceId');
        if (deviceEl) {
            var real = deviceEl.dataset.realText;
            deviceEl.removeAttribute('data-real-text');
            // The next /status tick will overwrite this with the live id;
            // we only need to make the pill go away cleanly.
            deviceEl.textContent = real != null ? real : (this.deviceId || '');
        } else {
            pill.parentNode && pill.parentNode.removeChild(pill);
        }
    },

    /**
     * Update EV battery and charging status - White rims with flow animation
     */
    updateEvStatus(status) {
        const evCard = document.getElementById('evCard');
        if (!evCard) return;

        // Get SOC percentage from status.soc.percent
        let soc = null;
        if (status.soc && status.soc.percent !== undefined) {
            soc = status.soc.percent;
        }

        // Update elements
        const evPercentValue = document.getElementById('evPercentValue');
        const evRange = document.getElementById('evRange');

        // First-load placeholder: server says vehicle data isn't ready yet
        // (BYD binders still binding, ACC just came on, etc.). Show an
        // explicit "Waiting for vehicle…" instead of the silent "--%" that
        // looked like the app was simply broken.
        if (soc === null && status.vehicleDataReady === false && !this.hasEverHadVehicleData) {
            if (evPercentValue) {
                evPercentValue.textContent = (BYD.i18n && BYD.i18n.t('status.waiting_vehicle')) || 'Waiting…';
                evPercentValue.style.fontSize = '11px';
                evPercentValue.style.fontWeight = '600';
                evPercentValue.style.letterSpacing = '.3px';
            }
            if (evRange) evRange.textContent = '—';
            return;
        }
        // We've had real data at some point, or are getting it now — restore
        // the percentage formatting to its default look.
        if (evPercentValue && evPercentValue.style.fontSize) {
            evPercentValue.style.fontSize = '';
            evPercentValue.style.fontWeight = '';
            evPercentValue.style.letterSpacing = '';
        }

        if (soc !== null) {
            const socRounded = Math.round(soc);

            // Update percentage text
            if (evPercentValue) {
                evPercentValue.textContent = `${socRounded}%`;
            }

            // Body-mesh emissive glow. The teal/amber/red ramp + intensity
            // scaling lives in OverdriveEvCard3D — see ev-card-3d.js
            // socRampColor + _applyEmissive. No-op on pages where the 3D
            // shell isn't loaded yet (login.html, dev-only pages).
            if (window.OverdriveAppShell && window.OverdriveAppShell.setSoc) {
                window.OverdriveAppShell.setSoc(soc);
            }
        }

        // Update range from actual API data (electric range only)
        if (evRange) {
            if (status.range && status.range.elecRangeKm !== undefined) {
                // Use electric range from BYD API — convert to user's display unit
                const rangeKm = status.range.elecRangeKm;
                evRange.textContent = BYD.units.dist(rangeKm);
                
                // Add warning styling if range is low
                if (status.range.isCritical) {
                    evRange.classList.add('critical');
                    evRange.classList.remove('low');
                } else if (status.range.isLow) {
                    evRange.classList.add('low');
                    evRange.classList.remove('critical');
                } else {
                    evRange.classList.remove('low', 'critical');
                }
            } else if (soc !== null) {
                // Fallback: estimate range (~4km per %)
                const estimatedRange = Math.round(soc * 4);
                evRange.textContent = '~' + BYD.units.dist(estimatedRange);
                evRange.classList.remove('low', 'critical');
            }
        }

        // Charging state
        const evPower = document.getElementById('evPower');

        let isCharging = false;
        let powerKW = 0;

        if (status.charging) {
            var stateName = status.charging.stateName || '';
            powerKW = status.charging.chargingPowerKW || 0;
            var isEstimated = status.charging.isEstimated || false;
            var powerSource = status.charging.powerSource || 'none';
            
            // Determine if actively charging. TRUST THE SERVER'S OWN VERDICT when it sends one: it
            // fuses BMS state, the power MCU and the CV-taper flag, none of which can be re-derived
            // from a state name here. Re-deriving it locally rendered a live taper as "not charging",
            // because the taper deliberately keeps the FINISHED state name and its rate can be
            // unresolved (0) early on. The name/power test remains as a fallback for older daemons
            // that do not send the field.
            if (typeof status.charging.charging === 'boolean') {
                isCharging = status.charging.charging;
            } else {
                var chargingStates = ['Charging', 'DC Charging', 'AC Charging', 'Fast Charging'];
                isCharging = chargingStates.some(function(s) { return stateName.toLowerCase().indexOf(s.toLowerCase()) >= 0; }) || powerKW > 0;
            }
            // Never let a carried positive value survive an authoritative stop.
            if (!isCharging) powerKW = 0;
        }

        // Update power display
        if (evPower) {
            if (isCharging) {
                if (powerKW > 0 && powerSource !== 'nominalPlaceholder') {
                    evPower.textContent = (isEstimated ? '~' : '')
                        + powerKW.toFixed(1) + ' kW';
                } else {
                    // The nominal placeholder is not a measurement. Keep the charging
                    // state visible until a direct or data-derived value is available.
                    evPower.textContent = BYD.i18n.t('status.charging') || 'Charging';
                }
            } else {
                evPower.textContent = '-- kW';
            }
        }

        // Charging state. The body-overlay sweep + bolt-icon scale-in
        // are driven by toggling the .charging class on the card root.
        // We forward the live charging power (kW) so the sweep
        // animation duration scales with charging speed — DC fast
        // visibly snaps; AC trickle crawls.
        if (isCharging) evCard.classList.add('charging');
        else            evCard.classList.remove('charging');
        if (window.OverdriveAppShell && window.OverdriveAppShell.setCharging) {
            window.OverdriveAppShell.setCharging(isCharging, powerKW);
        }

        // SOH display
        const evSohEl = document.getElementById('evSohValue');
        const evSohRow = document.getElementById('evSohRow');
        if (evSohEl && status.soh && status.soh.percent > 0) {
            evSohEl.textContent = status.soh.percent.toFixed(1) + '%';
            evSohEl.style.color = status.soh.percent >= 90 ? '#22c55e' : status.soh.percent >= 80 ? '#00D4AA' : status.soh.percent >= 70 ? '#fbbf24' : '#ef4444';
            if (evSohRow) evSohRow.style.display = '';
        }

        // Stash the live HAL range so the sidebar can show the car's built-in
        // fuel + total range immediately (PHEV), upgrading to the learned
        // "Personalized" estimate once enough trips accumulate.
        this._lastRange = status.range || null;

        // Personalized range from trip analytics
        this.updatePersonalizedRange();

        // Fuel card (PHEV only) — show only if real fuel data is available
        const fuelCard = document.getElementById('fuelCard');
        if (fuelCard && status.range) {
            var fuelPct = status.range.fuelPercent;
            var fuelKm = status.range.fuelKm;
            if (fuelPct && fuelPct > 0) {
                fuelCard.style.display = '';
                const fuelPercentEl = document.getElementById('fuelPercentValue');
                const fuelBarFill = document.getElementById('fuelBarFill');
                if (fuelPercentEl) fuelPercentEl.textContent = Math.round(fuelPct) + '%';
                if (fuelBarFill) fuelBarFill.style.width = Math.min(100, fuelPct) + '%';
                if (fuelPercentEl) {
                    fuelPercentEl.style.color = fuelPct <= 15 ? '#EF4444' : fuelPct <= 30 ? '#F59E0B' : '#FBBF24';
                }
            } else {
                fuelCard.style.display = 'none';
            }
        }
    },

    /**
     * Update network status indicator in sidebar.
     * Shows WiFi SSID + IP, or "Mobile Data", or "No Network".
     */
    updateNetworkStatus(status) {
        const netEl = document.getElementById('networkValue');
        const netIcon = document.getElementById('networkIcon');
        if (!netEl) return;

        const net = status.network;
        if (!net) {
            netEl.textContent = '--';
            netEl.className = 'status-value';
            if (netIcon) netIcon.innerHTML = this._wifiSvg();
            return;
        }

        if (net.type === 'wifi') {
            const ssid = net.ssid || BYD.i18n.t('status.wifi');
            const ip = net.ip || '';
            // Show SSID on first line, IP smaller below
            netEl.innerHTML = '<span class="net-ssid">' + this._esc(ssid) + '</span>' +
                (ip ? '<span class="net-ip">' + this._esc(ip) + '</span>' : '');
            netEl.className = 'status-value on net-info';
            if (netIcon) netIcon.innerHTML = this._wifiSvg();
        } else if (net.type === 'cellular') {
            const ip = net.ip || '';
            netEl.innerHTML = '<span class="net-ssid">' + this._esc(BYD.i18n.t('status.mobile_data')) + '</span>' +
                (ip ? '<span class="net-ip">' + this._esc(ip) + '</span>' : '');
            netEl.className = 'status-value on net-info';
            if (netIcon) netIcon.innerHTML = this._cellSvg();
        } else {
            netEl.textContent = BYD.i18n.t('status.no_network');
            netEl.className = 'status-value off';
            if (netIcon) netIcon.innerHTML = this._wifiOffSvg();
        }
    },

    /** Escape HTML */
    _esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; },

    /** WiFi SVG icon */
    _wifiSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>';
    },

    /** Cellular SVG icon */
    _cellSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="17" width="4" height="5"/><rect x="7" y="12" width="4" height="10"/><rect x="12" y="7" width="4" height="15"/><rect x="17" y="2" width="4" height="20"/></svg>';
    },

    /** WiFi-off SVG icon */
    _wifiOffSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"/><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/><path d="M10.71 5.05A16 16 0 0 1 22.56 9"/><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>';
    },

    /**
     * Fetch and display personalized range estimates from trip analytics.
     *
     * Three rows are toggled in the sidebar:
     *   • EV-card Personalized      — electric km (BEV + PHEV)
     *   • EV-card Combined          — electric + petrol total (PHEV with petrol bucket)
     *   • Fuel-card Personalized    — petrol km (PHEV with petrol bucket)
     *
     * Session-cached for ~60 s so the sidebar isn't refetching on every
     * status tick, but expires so a user who just set tankCapacityL sees
     * the petrol leg appear on the next status refresh rather than after
     * a full page reload.
     */
    _parseRangeEstimate(node) {
        if (!node) return null;
        function positive(camelKey, snakeKey) {
            var raw = node[camelKey];
            if (raw === undefined || raw === null) raw = node[snakeKey];
            var value = Number(raw);
            return isFinite(value) && value > 0 ? value : 0;
        }

        var predictedKm = positive('predictedRangeKm', 'predicted_range_km');
        if (predictedKm <= 0) return null;
        var lowerKm = positive('lowerBoundKm', 'lower_bound_km') || predictedKm;
        var upperKm = positive('upperBoundKm', 'upper_bound_km') || predictedKm;
        if (lowerKm > upperKm) {
            var swap = lowerKm;
            lowerKm = upperKm;
            upperKm = swap;
        }
        var sampleCount = Number(node.sampleCount);
        if (!isFinite(sampleCount) || sampleCount < 0) sampleCount = 0;

        return {
            predictedKm: predictedKm,
            lowerKm: lowerKm,
            upperKm: upperKm,
            sampleCount: Math.round(sampleCount)
        };
    },

    _buildPersonalizedRangeSnapshot() {
        function positive(value) {
            return typeof value === 'number' && isFinite(value) && value > 0
                ? value : 0;
        }

        var hal = this._lastRange || {};
        var evEstimate = this._personalizedEvEstimate || null;
        var fuelEstimate = this._personalizedFuelEstimate || null;
        var halEvKm = positive(hal.elecRangeKm);
        var halFuelKm = positive(hal.fuelRangeKm);
        var halTotalKm = positive(hal.totalRangeKm);
        var isPhev = hal.isPhev === true
            || halFuelKm > 0
            || fuelEstimate !== null
            || (typeof hal.fuelPercent === 'number' && isFinite(hal.fuelPercent));
        var hasLearnedRange = evEstimate !== null || fuelEstimate !== null;
        var resolvedEvKm = evEstimate ? evEstimate.predictedKm : halEvKm;
        var resolvedFuelKm = isPhev
            ? (fuelEstimate ? fuelEstimate.predictedKm : halFuelKm) : 0;
        var vehicleKm = isPhev
            ? (halTotalKm || (halEvKm + halFuelKm))
            : (halTotalKm || halEvKm);
        var personalizedKm = 0;
        if (hasLearnedRange) {
            personalizedKm = isPhev
                ? (positive(this._personalizedTotalKm)
                    || (resolvedEvKm + resolvedFuelKm))
                : (evEstimate ? evEstimate.predictedKm : 0);
        }

        var lowerKm = 0;
        var upperKm = 0;
        if (personalizedKm > 0) {
            if (isPhev) {
                lowerKm = (evEstimate ? evEstimate.lowerKm : halEvKm)
                    + (fuelEstimate ? fuelEstimate.lowerKm : halFuelKm);
                upperKm = (evEstimate ? evEstimate.upperKm : halEvKm)
                    + (fuelEstimate ? fuelEstimate.upperKm : halFuelKm);
            } else if (evEstimate) {
                lowerKm = evEstimate.lowerKm;
                upperKm = evEstimate.upperKm;
            }
        }

        return {
            available: hasLearnedRange && personalizedKm > 0,
            isPhev: isPhev,
            personalizedKm: personalizedKm,
            vehicleKm: vehicleKm,
            resolvedEvKm: resolvedEvKm,
            resolvedFuelKm: resolvedFuelKm,
            lowerKm: lowerKm,
            upperKm: upperKm,
            sampleCount: (evEstimate ? evEstimate.sampleCount : 0)
                + (fuelEstimate ? fuelEstimate.sampleCount : 0)
        };
    },

    _publishPersonalizedRange() {
        var snapshot = this._buildPersonalizedRangeSnapshot();
        this.personalizedRangeSnapshot = snapshot;
        if (BYD.dashboard
                && typeof BYD.dashboard.updatePersonalizedRange === 'function') {
            BYD.dashboard.updatePersonalizedRange(snapshot);
        }
    },

    async updatePersonalizedRange() {
        const pRow = document.getElementById('evPersonalizedRow');
        const pVal = document.getElementById('evPersonalizedRange');
        const cRow = document.getElementById('evCombinedRow');
        const cVal = document.getElementById('evCombinedRange');
        const fRow = document.getElementById('fuelPersonalizedRow');
        const fVal = document.getElementById('fuelPersonalizedRange');
        if (!pRow || !pVal) return;

        var now = Date.now();
        var TTL_MS = 60000;
        if (this._personalizedRangeFetched
                && this._personalizedRangeFetchedAt
                && (now - this._personalizedRangeFetchedAt) < TTL_MS) {
            this._renderPersonalizedRange();
            return;
        }

        try {
            const resp = await fetch('/api/trips/range');
            const data = await resp.json();
            this._personalizedRangeFetched = true;
            this._personalizedRangeFetchedAt = now;
            this._personalizedRangeKm = 0;
            this._personalizedFuelKm = 0;
            this._personalizedTotalKm = 0;
            this._personalizedEvEstimate = data.success
                ? this._parseRangeEstimate(data.range) : null;
            this._personalizedFuelEstimate = data.success
                ? this._parseRangeEstimate(data.fuelRange) : null;
            if (this._personalizedEvEstimate) {
                this._personalizedRangeKm =
                    Math.round(this._personalizedEvEstimate.predictedKm);
            }
            if (this._personalizedFuelEstimate) {
                this._personalizedFuelKm =
                    Math.round(this._personalizedFuelEstimate.predictedKm);
            }
            if (data.success && data.totalRangeKm > 0) {
                this._personalizedTotalKm = Math.round(data.totalRangeKm);
            }
            this._renderPersonalizedRange();
        } catch (e) {
            this._personalizedRangeFetched = true;
            this._personalizedRangeFetchedAt = now;
            this._renderPersonalizedRange();
        }
    },

    /** Apply cached personalized range values to the sidebar DOM. */
    _renderPersonalizedRange() {
        const pRow = document.getElementById('evPersonalizedRow');
        const pVal = document.getElementById('evPersonalizedRange');
        const cRow = document.getElementById('evCombinedRow');
        const cVal = document.getElementById('evCombinedRange');
        const fRow = document.getElementById('fuelPersonalizedRow');
        const fVal = document.getElementById('fuelPersonalizedRange');

        if (pRow && pVal) {
            if (this._personalizedRangeKm > 0) {
                pRow.style.display = 'flex';
                pVal.textContent = BYD.units.dist(this._personalizedRangeKm);
            } else {
                pRow.style.display = 'none';
            }
        }
        // HAL range as the immediate fallback when the learned estimate isn't
        // ready yet (no tank capacity set / too few fuel trips). The car's
        // built-in fuel + total range are always present on a PHEV, so the rows
        // show real numbers right away and upgrade to "Personalized" later.
        var hal = this._lastRange || {};
        var halFuelKm = (typeof hal.fuelRangeKm === 'number' && hal.fuelRangeKm > 0)
            ? hal.fuelRangeKm : 0;
        var halTotalKm = (typeof hal.totalRangeKm === 'number' && hal.totalRangeKm > 0)
            ? hal.totalRangeKm : 0;

        if (fRow && fVal) {
            // Learned petrol estimate first, else the HAL fuel range.
            var fuelKm = this._personalizedFuelKm > 0 ? this._personalizedFuelKm : halFuelKm;
            if (fuelKm > 0) {
                fRow.style.display = 'flex';
                fVal.textContent = BYD.units.dist(fuelKm);
            } else {
                fRow.style.display = 'none';
            }
        }
        if (cRow && cVal) {
            // Prefer the learned combined total (both legs present). Otherwise
            // fall back to the HAL total range — but only on a PHEV (HAL fuel
            // leg present), so a BEV never shows a redundant "Combined" row that
            // just mirrors its EV range.
            var combinedKm = 0;
            if (this._personalizedTotalKm > 0
                    && this._personalizedRangeKm > 0
                    && this._personalizedFuelKm > 0) {
                combinedKm = this._personalizedTotalKm;
            } else if (halTotalKm > 0 && halFuelKm > 0) {
                combinedKm = halTotalKm;
            }
            if (combinedKm > 0) {
                cRow.style.display = 'flex';
                cVal.textContent = BYD.units.dist(combinedKm);
            } else {
                cRow.style.display = 'none';
            }
        }
        this._publishPersonalizedRange();
    },

    /**
     * Show toast notification.
     *
     * Defensive against catalog races: callers commonly do
     *   toast(BYD.i18n.t('foo.bar'), 'success')
     * and BYD.i18n.t() returns null when the catalog hasn't finished
     * loading yet. textContent = null paints an empty toast pill (a
     * black box with no visible message), which reads as "something
     * happened but I have no idea what". Fall back to a generic label
     * keyed off the toast type so the user always sees acknowledging
     * text. Same idea covers undefined / empty string callers.
     *
     * The exit animation references @keyframes slideIn (not slideUp,
     * which is what the entrance uses) — defined on pages that need it
     * (e.g. notifications.html). On pages without that keyframe the
     * animation is a no-op and the toast is removed by the setTimeout
     * regardless, so this isn't a regression.
     */
    toast(message, type = 'info', duration = 3000) {
        const container = document.getElementById('toastContainer');
        if (!container) return;

        let text = message;
        if (text == null || text === '') {
            // Try the i18n catalog first; if it hasn't loaded yet (the
            // common race that produces empty toasts in the WebView), fall
            // back to a typed English string so the user always sees text.
            const tr = BYD.i18n && BYD.i18n.t ? BYD.i18n.t.bind(BYD.i18n) : null;
            switch (type) {
                case 'success': text = (tr && tr('toast.fallback_success')) || 'Saved'; break;
                case 'error':   text = (tr && tr('toast.fallback_error'))   || 'Something went wrong'; break;
                case 'warning': text = (tr && tr('toast.fallback_warning')) || 'Warning'; break;
                default:        text = (tr && tr('toast.fallback_info'))    || 'Done';
            }
        }

        const toast = document.createElement('div');
        toast.className = 'toast ' + type;
        toast.textContent = text;
        container.appendChild(toast);

        setTimeout(() => {
            toast.style.animation = 'slideIn 0.4s ease reverse';
            setTimeout(() => toast.remove(), 400);
        }, duration);
    }
};

// Expose toast globally for convenience
BYD.utils = BYD.utils || {};
BYD.utils.toast = (msg, type) => BYD.core.toast(msg, type);

/**
 * Scroll the page back to the top of a list after it is replaced under the
 * user — paging a catalog, applying a filter, changing a sort. Landing on
 * page 2 still scrolled halfway down shows the middle of the new list, and
 * the user has to scroll up to find out where they are.
 *
 * `anchor` (optional) is the list container. When the page scroller is the
 * DOCUMENT (the usual case in this layout: html/body/.app-layout/.main-content
 * are all min-height only, so nothing is height-bounded and no ancestor
 * establishes an internal scroller), scrolling to absolute 0 would hide the
 * pagination controls and the filter bar. Instead we scroll so the anchor's
 * top sits just under the sticky .page-header — the user sees the first row of
 * the new page with its controls still in view.
 *
 * Resets every plausible scroller (window + documentElement + body + any
 * height-bounded ancestor of the anchor), because which one is live depends on
 * the page. Same belt-and-braces approach as the tab switcher in app-tabs.js.
 * Instant, never smooth: a smooth scroll would race the list re-render.
 */
BYD.utils.scrollToTop = function (anchor) {
    // 1. Any real overflow scroller between the anchor and the document. A page
    //    that DOES bound its list height (e.g. a two-pane layout) scrolls here,
    //    and the document reset below would be a no-op for it.
    var el = anchor && anchor.parentNode ? anchor.parentNode : null;
    for (var hops = 0; el && el.nodeType === 1 && hops < 12; hops++) {
        if (el.scrollHeight > el.clientHeight + 1) {
            var oy = '';
            try { oy = window.getComputedStyle(el).overflowY; } catch (e) { oy = ''; }
            if (oy === 'auto' || oy === 'scroll' || oy === 'overlay') { el.scrollTop = 0; break; }
        }
        el = el.parentNode;
    }

    // 2. The document scroller. Offset by the sticky header so the list's own
    //    controls stay on screen; absolute 0 when there's no anchor to align to.
    var top = 0;
    if (anchor && anchor.getBoundingClientRect) {
        var doc = document.documentElement;
        var cur = window.pageYOffset || (doc ? doc.scrollTop : 0) || (document.body ? document.body.scrollTop : 0) || 0;
        var header = document.querySelector('.page-header');
        var headerH = header ? header.getBoundingClientRect().height : 0;
        // Absolute document offset of the anchor, less the sticky header that
        // would otherwise cover its first rows, less a small breathing margin.
        top = Math.max(0, cur + anchor.getBoundingClientRect().top - headerH - 12);
        // Already at or above the target (short list, or the user never scrolled)
        // → don't scroll DOWN to it; that would feel like a jump for no reason.
        if (cur <= top) top = cur;
    }
    try { window.scrollTo(0, top); } catch (e) { /* ignore */ }
    if (document.documentElement) document.documentElement.scrollTop = top;
    if (document.body) document.body.scrollTop = top;
    // Defensive: .page-body is a declared overflow-y:auto container, so reset it
    // too in case a page ever makes it height-bounded and thus actually scroll.
    var pageBody = document.querySelector('.page-body');
    if (pageBody) pageBody.scrollTop = 0;
};

/**
 * Themed alert/confirm replacements. Native window.alert / window.confirm
 * paint the OS chrome (white panel + system font + "127.0.0.1 says…"
 * header inside the head-unit WebView), which breaks the dark Material
 * surface and leaks the loopback origin into the UI. These helpers reuse
 * the existing .modal-backdrop + .modal-card styling (see
 * shared/styles.css and the SOH capacity modal on performance.html) so
 * popups match the rest of the page.
 *
 * Both helpers return a Promise:
 *   - alertDialog resolves once the user dismisses.
 *   - confirmDialog resolves true on confirm, false on cancel / backdrop click / Esc.
 *
 * Each call mounts a one-shot DOM node and tears it down on dismiss —
 * the helpers don't keep persistent state, so calling them in quick
 * succession is safe.
 */
BYD.utils._modalEscBound = false;
BYD.utils._modalStack = [];
BYD.utils._dismissTopModal = function (result) {
    var top = BYD.utils._modalStack.pop();
    if (top) top.dismiss(result);
};
BYD.utils._ensureModalEscHandler = function () {
    if (BYD.utils._modalEscBound) return;
    BYD.utils._modalEscBound = true;
    document.addEventListener('keydown', function (ev) {
        if (ev.key !== 'Escape' || BYD.utils._modalStack.length === 0) return;
        ev.preventDefault();
        BYD.utils._dismissTopModal(false);
    });
};

BYD.utils._showModal = function (opts) {
    return new Promise(function (resolve) {
        BYD.utils._ensureModalEscHandler();

        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.setAttribute('role', 'presentation');
        // .modal-backdrop's stylesheet rule already sets display:flex; we
        // just need to make sure the inline display:none guard the SOH
        // template uses isn't inherited here.
        backdrop.style.display = 'flex';

        var card = document.createElement('div');
        card.className = 'modal-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');

        // Title + body — text content only, never raw HTML.
        if (opts.title) {
            var h = document.createElement('h3');
            h.className = 'soh-modal-title';
            h.textContent = opts.title;
            card.appendChild(h);
        }
        if (opts.body) {
            var p = document.createElement('p');
            p.style.margin = opts.prompt ? '0 0 12px 0' : '0 0 4px 0';
            p.style.fontSize = '14px';
            p.style.lineHeight = '1.5';
            p.style.color = 'var(--text-secondary, var(--text-primary))';
            p.style.whiteSpace = 'pre-wrap';
            p.textContent = opts.body;
            card.appendChild(p);
        }

        var input = null;
        if (opts.prompt) {
            input = document.createElement('input');
            input.type = opts.inputType || 'text';
            input.value = opts.promptValue || '';
            input.placeholder = opts.promptPlaceholder || '';
            input.setAttribute('aria-label',
                opts.promptLabel || opts.title || opts.body || 'Input');
            if (opts.inputMode) input.setAttribute('inputmode', opts.inputMode);
            input.style.width = '100%';
            input.style.boxSizing = 'border-box';
            input.style.padding = '10px 12px';
            input.style.marginBottom = '18px';
            input.style.border = '1px solid var(--border-subtle, #ccc)';
            input.style.borderRadius = 'var(--radius-md, 8px)';
            input.style.background = 'var(--bg-surface, #fff)';
            input.style.color = 'var(--text-primary, #000)';
            input.style.fontSize = '15px';
            input.style.outline = 'none';
            input.addEventListener('focus', function () {
                input.style.borderColor = 'var(--brand-primary, #007AFF)';
            });
            input.addEventListener('blur', function () {
                input.style.borderColor = 'var(--border-subtle, #ccc)';
            });
            input.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') {
                    ev.preventDefault();
                    dismiss(true);
                }
            });
            card.appendChild(input);
        }

        var actions = document.createElement('div');
        actions.className = 'soh-modal-actions';

        // Optional Cancel — confirm dialogs always have one; alerts skip it.
        if (opts.cancelLabel) {
            var cancelBtn = document.createElement('button');
            cancelBtn.className = 'btn btn-secondary';
            cancelBtn.type = 'button';
            cancelBtn.textContent = opts.cancelLabel;
            cancelBtn.addEventListener('click', function () { dismiss(false); });
            actions.appendChild(cancelBtn);
        }

        var confirmBtn = document.createElement('button');
        // btn-danger for destructive confirms, btn-primary for everything else.
        var confirmClass = opts.danger ? 'btn btn-danger' : 'btn btn-primary';
        confirmBtn.className = confirmClass;
        confirmBtn.type = 'button';
        confirmBtn.textContent = opts.confirmLabel || 'OK';
        confirmBtn.addEventListener('click', function () { dismiss(true); });
        actions.appendChild(confirmBtn);

        card.appendChild(actions);
        backdrop.appendChild(card);

        // Backdrop click closes (acts as cancel for confirm, dismiss for alert).
        backdrop.addEventListener('click', function (ev) {
            if (ev.target === backdrop) dismiss(false);
        });

        var prevFocus = document.activeElement;
        document.body.appendChild(backdrop);

        try {
            if (input) input.focus();
            else confirmBtn.focus();
        } catch (e) {}

        var entry = { dismiss: dismiss };
        BYD.utils._modalStack.push(entry);

        function dismiss(result) {
            // Idempotent — backdrop click + button click + Esc could all race.
            if (entry._done) return;
            entry._done = true;
            try { backdrop.remove(); } catch (e) {}
            try { if (prevFocus && prevFocus.focus) prevFocus.focus(); } catch (e) {}
            // Pop from stack if still there (e.g. button click path).
            var idx = BYD.utils._modalStack.indexOf(entry);
            if (idx !== -1) BYD.utils._modalStack.splice(idx, 1);
            resolve(opts.prompt ? (result ? input.value : null) : result);
        }
    });
};

/**
 * Themed alert. Returns a Promise<void> that resolves on dismiss.
 *   BYD.utils.alertDialog({ title: 'Upload failed', body: 'The file is too large.' })
 */
BYD.utils.alertDialog = function (opts) {
    opts = opts || {};
    var t = BYD.i18n && BYD.i18n.t ? BYD.i18n.t.bind(BYD.i18n) : null;
    return BYD.utils._showModal({
        title: opts.title || (t && t('common.notice')) || 'Notice',
        body: opts.body || '',
        confirmLabel: opts.confirmLabel || (t && t('common.ok')) || 'OK',
        danger: false
    });
};

/**
 * Themed confirm. Returns a Promise<boolean>.
 *   const ok = await BYD.utils.confirmDialog({
 *       title: 'Remove image?',
 *       body: 'This cannot be undone.',
 *       confirmLabel: 'Remove',
 *       danger: true
 *   });
 *   if (!ok) return;
 */
BYD.utils.confirmDialog = function (opts) {
    opts = opts || {};
    var t = BYD.i18n && BYD.i18n.t ? BYD.i18n.t.bind(BYD.i18n) : null;
    return BYD.utils._showModal({
        title: opts.title || (t && t('common.confirm')) || 'Confirm',
        body: opts.body || '',
        confirmLabel: opts.confirmLabel || (t && t('common.ok')) || 'OK',
        cancelLabel: opts.cancelLabel || (t && t('common.cancel')) || 'Cancel',
        danger: opts.danger === true
    });
};

/** Themed input prompt. Resolves to the entered string, or null when canceled. */
BYD.utils.promptDialog = function (opts) {
    opts = opts || {};
    var t = BYD.i18n && BYD.i18n.t ? BYD.i18n.t.bind(BYD.i18n) : null;
    return BYD.utils._showModal({
        title: opts.title || (t && t('common.notice')) || 'Notice',
        body: opts.body || '',
        confirmLabel: opts.confirmLabel || (t && t('common.ok')) || 'OK',
        cancelLabel: opts.cancelLabel || (t && t('common.cancel')) || 'Cancel',
        prompt: true,
        promptValue: opts.value || '',
        promptPlaceholder: opts.placeholder || '',
        promptLabel: opts.label || '',
        inputType: opts.inputType || 'text',
        inputMode: opts.inputMode || ''
    });
};

// Auto-load the language picker on every page that includes core.js so we
// don't have to touch every HTML file. Picker mounts itself once the DOM is
// ready and a sidebar-footer is found (login page has no sidebar — picker
// silently no-ops there).
(function () {
    if (document.querySelector('script[data-byd-lang-picker]')) return;
    var s = document.createElement('script');
    s.src = '/shared/lang-picker.js';
    s.async = true;
    s.setAttribute('data-byd-lang-picker', '1');
    (document.head || document.documentElement).appendChild(s);
})();
