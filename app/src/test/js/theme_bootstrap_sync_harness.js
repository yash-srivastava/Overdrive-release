// Run with Node.js; all appearance requests and browser state are mocked.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/assets/web/shared/theme.js'), 'utf8');

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function flush() { return new Promise(resolve => setImmediate(resolve)); }

function setup(inApp = false) {
    const values = new Map([['overdrive_theme', 'dark']]);
    const attrs = {};
    const listeners = [];
    const requests = [];
    const context = {
        console, navigator: { language: 'en' },
        localStorage: {
            getItem: key => values.get(key) || null,
            setItem: (key, value) => values.set(key, value)
        },
        document: {
            readyState: 'loading', body: null,
            documentElement: {
                setAttribute: (key, value) => { attrs[key] = value; },
                getAttribute: key => attrs[key],
                style: { setProperty() {} }
            },
            addEventListener: (type, cb) => { if (type === 'DOMContentLoaded') listeners.push(cb); },
            querySelector: () => null, querySelectorAll: () => []
        },
        addEventListener() {},
        matchMedia: () => ({ matches: true, addEventListener() {} }),
        MutationObserver: class { observe() {} },
        fetch: (url, options) => {
            assert.strictEqual(url, '/api/settings/appearance');
            const pending = deferred();
            requests.push({ options, ...pending });
            return pending.promise;
        }
    };
    if (inApp) context.AndroidBridge = { getAppTheme: () => 'light' };
    context.window = context;
    vm.runInNewContext(source, context);
    return {
        theme: context.BYD.theme, requests, attrs,
        start: () => listeners.forEach(cb => cb()),
        check: (preference, effective) => {
            assert.strictEqual(values.get('overdrive_theme'), preference);
            assert.strictEqual(attrs['data-theme'], effective);
        }
    };
}
function reply(request, theme) { request.resolve({ ok: true, json: () => Promise.resolve({ theme }) }); }

async function main() {
    // A slow GET must not undo a choice that has already been posted.
    let s = setup();
    s.start();
    s.theme.set('light');
    assert.strictEqual(JSON.parse(s.requests[1].options.body).theme, 'light');
    reply(s.requests[1], 'light');
    reply(s.requests[0], 'dark');
    await flush();
    s.check('light', 'light');

    // Even if the headers arrived first, JSON can settle after the choice.
    s = setup();
    s.start();
    const body = deferred();
    s.requests[0].resolve({ ok: true, json: () => body.promise });
    await flush();
    s.theme.set('auto');
    body.resolve({ theme: 'dark' });
    await flush();
    s.check('auto', 'light');

    // An explicit re-selection of the stored value also wins over the GET.
    s = setup();
    s.start();
    s.theme.set('dark');
    reply(s.requests[0], 'light');
    await flush();
    s.check('dark', 'dark');

    // Without an explicit choice, normal server synchronization still works.
    s = setup();
    s.start();
    reply(s.requests[0], 'auto');
    await flush();
    s.check('auto', 'light');

    // A valid choice before DOMContentLoaded makes bootstrap GET unnecessary.
    s = setup();
    s.theme.set('light');
    s.start();
    assert.strictEqual(s.requests.length, 1);
    assert.strictEqual(s.requests[0].options.method, 'POST');
    s.check('light', 'light');

    // Invalid set calls do not suppress server synchronization.
    s = setup();
    s.start();
    s.theme.set('invalid');
    assert.strictEqual(s.requests.length, 1);
    reply(s.requests[0], 'light');
    await flush();
    s.check('light', 'light');

    // Failed POST is best-effort: the user's local choice still wins.
    s = setup();
    s.start();
    s.theme.set('light');
    s.requests[1].reject(new Error('offline'));
    reply(s.requests[0], 'dark');
    await flush();
    s.check('light', 'light');

    // The embedded WebView continues to take its theme from Android.
    s = setup(true);
    s.start();
    assert.strictEqual(s.requests.length, 0);
    s.check('dark', 'light');
    console.log('8 theme bootstrap synchronization scenarios passed');
}
main().catch(err => { console.error(err); process.exitCode = 1; });
