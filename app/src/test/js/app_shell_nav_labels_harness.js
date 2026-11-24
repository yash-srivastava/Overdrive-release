'use strict';

// Run: node app/src/test/js/app_shell_nav_labels_harness.js
// Exercise the shell's actual markup generation and core's i18n hydrator.
// Stop mounting after capturing the markup: no sidebar widgets or API calls.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const web = path.resolve(__dirname, '../../main/assets/web');
const shell = fs.readFileSync(path.join(web, 'shared/app-shell.js'), 'utf8');
const core = fs.readFileSync(path.join(web, 'shared/core.js'), 'utf8');
const end = core.indexOf('BYD.units =');
assert.ok(end > 0, 'i18n module boundary');
const i18n = core.slice(0, end);

function element(attrs, textContent) {
    return {
        children: [], textContent,
        getAttribute: key => attrs[key] == null ? null : attrs[key],
        setAttribute: (key, value) => { attrs[key] = String(value); }
    };
}
function attributes(markup) {
    return Object.fromEntries(Array.from(markup.matchAll(/([\w-]+)="([^"]*)"/g), m => [m[1], m[2]]));
}
function render(native) {
    const stop = new Error('markup captured');
    let html;
    const document = {
        readyState: 'complete', documentElement: element({}, ''),
        addEventListener() {}, querySelectorAll: () => [],
        querySelector: () => ({}),
        getElementById: id => id === 'app-shell-mount' ? {} : null,
        createElement: tag => {
            assert.strictEqual(tag, 'aside');
            return { set innerHTML(value) { html = value; throw stop; } };
        }
    };
    const window = { location: { pathname: '/trips' } };
    if (native) window.AndroidBridge = {};
    try {
        vm.runInNewContext(shell, { window, document, navigator: {}, setTimeout() {} }, { filename: 'app-shell.js' });
    } catch (e) { if (e !== stop) throw e; }
    assert.ok(html, 'shell markup captured');
    const rows = Array.from(html.matchAll(/<a\b([^>]*class="nav-link[^>]*")([^>]*)>[\s\S]*?<span([^>]*)>([^<]*)<\/span>\s*<\/a>/g), m => {
        const a = attributes(m[1] + m[2]);
        return { href: a.href, link: element(a, ''), label: element(attributes(m[3]), m[4]) };
    });
    assert.ok(rows.length > 20, 'sidebar links captured');
    return rows;
}
function catalog(lang) {
    return JSON.parse(fs.readFileSync(path.join(web, 'i18n', lang + '.json'), 'utf8'));
}
async function run() {
    const rows = render(false);
    const trips = rows.find(r => r.href === 'trips.html');
    assert.strictEqual(trips.link.getAttribute('aria-current'), 'page');
    assert.strictEqual(trips.link.getAttribute('aria-label'), 'Trips');
    assert.strictEqual(trips.label.textContent, 'Trips');
    const document = {
        documentElement: element({}, ''), readyState: 'complete',
        querySelectorAll(selector) {
            const attr = selector.slice(1, -1);
            return rows.flatMap(r => [r.link, r.label]).filter(el => el.getAttribute(attr) !== null);
        }
    };
    const window = { BYD: {} };
    vm.runInNewContext(i18n, {
        window, BYD: window.BYD, document, navigator: { language: 'en' }, console,
        fetch: url => Promise.resolve({ ok: true, json: () => Promise.resolve(catalog(url.split('/').pop().replace('.json', ''))) })
    }, { filename: 'core-i18n.js' });
    const t = window.BYD.i18n;
    // No catalog loaded: retain the hard-coded fallback labels.
    t.hydrate(document);
    assert.strictEqual(trips.link.getAttribute('aria-label'), 'Trips');
    await t.setLang('de', { persist: false });
    assert.strictEqual(trips.label.textContent, catalog('de').nav.trips);
    assert.strictEqual(trips.link.getAttribute('aria-label'), trips.label.textContent, 'German accessible name');
    await t.setLang('he', { persist: false });
    assert.strictEqual(trips.link.getAttribute('aria-label'), catalog('he').nav.trips);
    assert.strictEqual(trips.link.getAttribute('aria-label'), trips.label.textContent);
    assert.strictEqual(document.documentElement.getAttribute('dir'), 'rtl');
    await t.setLang('en', { persist: false });
    assert.strictEqual(trips.link.getAttribute('aria-label'), trips.label.textContent);
    assert.strictEqual(document.documentElement.getAttribute('dir'), 'ltr');
    // All navigation names use the same catalog key as their visible label.
    for (const r of rows.concat(render(true))) {
        const key = r.label.getAttribute('data-i18n');
        assert.ok(key, r.href);
        assert.strictEqual(r.link.getAttribute('data-i18n-attr'), 'aria-label:' + key, r.href);
    }
    assert.ok(rows.some(r => r.href === 'sessions.html'));
    assert.ok(!render(true).some(r => r.href === 'sessions.html'));
    console.log('app_shell_nav_labels_harness: all assertions passed');
}
run().catch(e => { console.error(e); process.exitCode = 1; });
