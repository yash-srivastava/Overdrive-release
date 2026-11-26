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

function setup(inApp = false, stored = 'dark', systemLight = true, legacy = false) {
    const values = new Map([['overdrive_theme', stored]]);
    const attrs = {};
    const listeners = [];
    const requests = [];
    const mediaListeners = [];
    const media = { get matches() { return systemLight; } };
    if (legacy) media.addListener = cb => mediaListeners.push(cb);
    else media.addEventListener = (type, cb) => {
        assert.strictEqual(type, 'change');
        mediaListeners.push(cb);
    };
    const favicon = {};
    const touchIcon = {};
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
            querySelector: selector => selector === 'link[rel~="icon"]' ? favicon
                : selector === 'link[rel~="apple-touch-icon"]' ? touchIcon : null,
            querySelectorAll: () => []
        },
        addEventListener() {},
        matchMedia: () => media,
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
        setSystemLight: light => {
            systemLight = light;
            mediaListeners.forEach(cb => cb({ matches: light }));
        },
        checkIcons: effective => {
            assert.strictEqual(favicon.href, '/shared/app-icon-' + effective + '.webp');
            assert.strictEqual(touchIcon.href, favicon.href);
        },
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
    // A stale web Auto preference must not let a media event override Android.
    s = setup(true, 'auto');
    s.start();
    s.setSystemLight(false);
    s.check('auto', 'light');
    assert.strictEqual(s.requests.length, 0);

    // A fixed web preference must not determine the initial native icons.
    s = setup(true, 'dark');
    s.check('dark', 'light');
    s.checkIcons('light'); // Before DOMContentLoaded or any mutation callback.
    s.start();
    s.checkIcons('light');

    // External browsers in Auto still track the OS, including legacy Safari.
    for (const legacy of [false, true]) {
        s = setup(false, 'auto', false, legacy);
        s.check('auto', 'dark');
        s.checkIcons('dark');
        s.start();
        s.setSystemLight(true);
        s.check('auto', 'light');
        s.setSystemLight(false);
        s.check('auto', 'dark');
    }

    // Explicit browser choices remain independent of the media query.
    s = setup(false, 'light');
    s.start();
    s.setSystemLight(false);
    s.check('light', 'light');
    console.log('13 theme bootstrap synchronization scenarios passed');
}
main().catch(err => { console.error(err); process.exitCode = 1; });
