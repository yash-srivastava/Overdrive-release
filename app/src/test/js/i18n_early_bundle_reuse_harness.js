'use strict';

// Run with node app/src/test/js/i18n_early_bundle_reuse_harness.js.
// Execute the real theme bootstrap and core i18n module with mocked native/browser I/O.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const shared = path.resolve(__dirname, '../../main/assets/web/shared');
const theme = fs.readFileSync(path.join(shared, 'theme.js'), 'utf8');
const coreFile = fs.readFileSync(path.join(shared, 'core.js'), 'utf8');
const end = coreFile.indexOf('\n})();', coreFile.indexOf('BYD.i18n ='));
assert(end > 0);
const core = coreFile.slice(0, end + '\n})();'.length);
const catalogs = {
    fr: { title: 'Bonjour' }, de: { title: 'Hallo' }, he: { title: 'Shalom' },
    en: { title: 'Hello', missing: 'English fallback' }
};

function setup(options = {}) {
    let lang = options.lang || 'fr';
    const reads = [], requests = [], attrs = {}, callbacks = [];
    const values = new Map([['overdrive_locale', lang], ['overdrive_i18n_catalog_lang', lang]]);
    if (options.cached) values.set('overdrive_i18n_catalog_' + lang, JSON.stringify({ title: 'stale' }));
    let stopped = 0;
    const ctx = {
        console, navigator: { language: lang },
        localStorage: {
            get length() { return values.size; },
            key: i => Array.from(values.keys())[i],
            getItem: key => values.get(key) || null,
            setItem: (key, value) => values.set(key, value),
            removeItem: key => values.delete(key)
        },
        document: {
            readyState: 'loading', body: null,
            documentElement: { setAttribute: (key, value) => { attrs[key] = value; }, style: { setProperty() {} } },
            addEventListener: (type, cb) => { if (type === 'DOMContentLoaded') callbacks.push(cb); },
            querySelector: () => null, querySelectorAll: () => []
        },
        addEventListener() {},
        matchMedia: () => ({ matches: false, addEventListener() {} }),
        MutationObserver: class { observe() {} disconnect() { stopped++; } },
        fetch(url, opts) {
            requests.push({ url, opts });
            if (url === '/api/settings/appearance') return Promise.resolve({ ok: true, json: () => Promise.resolve({ locale: 'auto' }) });
            const match = /^\/i18n\/([^/]+)\.json$/.exec(url);
            assert(match, url);
            return Promise.resolve({ ok: true, json: () => Promise.resolve(catalogs[match[1]]) });
        }
    };
    if (!options.external) ctx.AndroidBridge = {
        getAppLocale: () => lang,
        getAppTheme: () => 'dark',
        getI18nCatalog: tag => {
            reads.push(tag);
            if (options.bundle) return options.bundle(tag, reads.filter(r => r === tag).length);
            return JSON.stringify(catalogs[tag]);
        }
    };
    ctx.window = ctx;
    vm.createContext(ctx);
    if (!options.noTheme) vm.runInContext(theme, ctx);
    if (options.oldTheme && ctx.__odI18nEarly) delete ctx.__odI18nEarly.fromBundle;
    const firstCoreCallback = callbacks.length;
    vm.runInContext(core, ctx);
    return { ctx, reads, requests, attrs, values, i18n: ctx.BYD.i18n,
        setLocale: value => { lang = value; },
        coreDomReady: () => callbacks.slice(firstCoreCallback).forEach(cb => cb()),
        stopped: () => stopped };
}

function catalogRequests(e) {
    return e.requests.filter(r => r.url.startsWith('/i18n/'));
}
function assertFrench(e) {
    assert.strictEqual(e.i18n.t('title'), 'Bonjour');
    assert.strictEqual(e.i18n.t('missing'), 'English fallback');
    assert.strictEqual(e.attrs.lang, 'fr');
}

async function main() {
    // A successful APK handoff transfers/parses the active catalog only once.
    let e = setup();
    assert.strictEqual(e.ctx.__odI18nEarly.fromBundle, true);
    assert.deepStrictEqual(e.reads, ['fr', 'en']);
    const early = e.ctx.__odI18nEarly.catalog;
    let notified = 0;
    e.i18n.onChange(() => { notified++; assertFrench(e); });
    assert.strictEqual(await e.i18n.init(), early);
    assert.deepStrictEqual(e.reads, ['fr', 'en']);
    assert.strictEqual(catalogRequests(e).length, 0);
    assert.strictEqual(notified, 1);
    e.coreDomReady();
    assert.strictEqual(e.stopped(), 1);

    // Core-only pages also reuse their own synchronous APK read.
    e = setup({ noTheme: true });
    await e.i18n.init();
    assertFrench(e);
    assert.deepStrictEqual(e.reads, ['fr', 'en']);

    // A cached older theme.js has no provenance: don't trust its early catalog.
    e = setup({ oldTheme: true });
    await e.i18n.init();
    assertFrench(e);
    assert.deepStrictEqual(e.reads, ['fr', 'en', 'fr']);

    // A stale browser cache must be refreshed, even when early paint succeeded.
    e = setup({ external: true, cached: true });
    assert.strictEqual(e.ctx.__odI18nEarly.fromBundle, false);
    assert.strictEqual(e.i18n.t('title'), 'stale');
    await e.i18n.init();
    assertFrench(e);
    assert.deepStrictEqual(catalogRequests(e).map(r => r.url), ['/i18n/fr.json', '/i18n/en.json']);
    assert(catalogRequests(e).every(r => r.opts.cache === 'no-cache'));
    assert.strictEqual(JSON.parse(e.values.get('overdrive_i18n_catalog_fr')).title, 'Bonjour');

    // In-app early bridge failure + stale cache: retry native on init.
    e = setup({ cached: true, bundle: (tag, count) => tag === 'fr' && count === 1 ? '' : JSON.stringify(catalogs[tag]) });
    assert.strictEqual(e.ctx.__odI18nEarly.fromBundle, false);
    await e.i18n.init();
    assertFrench(e);
    assert.deepStrictEqual(e.reads, ['fr', 'en', 'fr']);
    assert.strictEqual(catalogRequests(e).length, 0);

    // Invalid native JSON must still reach the HTTP fallback, including English.
    e = setup({ cached: true, bundle: () => '{bad' });
    await e.i18n.init();
    assertFrench(e);
    assert.deepStrictEqual(catalogRequests(e).map(r => r.url), ['/i18n/fr.json', '/i18n/en.json']);

    // App locale changes between early paint and init: discard mismatched handoff.
    e = setup();
    e.setLocale('de');
    await e.i18n.init();
    assert.strictEqual(e.i18n.t('title'), 'Hallo');
    assert.strictEqual(e.i18n.t('missing'), 'English fallback');
    assert.deepStrictEqual(e.reads, ['fr', 'en', 'de']);
    await e.i18n.setLang('fr', { persist: false });
    assertFrench(e);
    assert.deepStrictEqual(e.reads, ['fr', 'en', 'de', 'fr']);

    // English continues to skip early localization, then loads once in init.
    e = setup({ lang: 'en' });
    assert.deepStrictEqual(e.reads, []);
    await e.i18n.init();
    assert.strictEqual(e.i18n.t('title'), 'Hello');
    assert.deepStrictEqual(e.reads, ['en']);

    // RTL and normalized Hebrew alias still use the same authoritative catalog.
    e = setup({ lang: 'iw-IL' });
    await e.i18n.init();
    assert.strictEqual(e.i18n.t('title'), 'Shalom');
    assert.strictEqual(e.attrs.dir, 'rtl');
    assert.deepStrictEqual(e.reads, ['he', 'en']);
    await e.i18n.setLang('de', { persist: false });
    assert.strictEqual(e.attrs.dir, 'ltr');
    assert.strictEqual(e.i18n.t('title'), 'Hallo');
    console.log('9 i18n early bundle reuse scenarios passed');
}
main().catch(err => { console.error(err); process.exitCode = 1; });
