'use strict';

// Run with: node app/src/test/js/login_redirect_harness.js
// Execute the actual login page; only its browser environment and auth responses
// are replaced. Navigation is observed without executing untrusted destinations.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const loginPath = path.resolve(__dirname, '../../main/assets/web/local/login.html');
const loginHtml = fs.readFileSync(loginPath, 'utf8');
const inlineScripts = [];
const scriptPattern = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
let script;
while ((script = scriptPattern.exec(loginHtml)) !== null) {
    if (!/\bsrc\s*=/i.test(script[1]) && script[2].trim()) {
        inlineScripts.push(script[2]);
    }
}
assert.ok(inlineScripts.length, 'the login page must contain executable inline code');

const origin = 'https://device.invalid';
const dummyDevice = 'regression-device';
const dummyCode = 'testcode12345678';
const dummyJwt = 'regression-jwt-not-a-real-session';

function fakeElement() {
    const classes = new Set();
    return {
        value: '', textContent: '', className: '', disabled: false, style: {},
        classList: {
            add: function (name) { classes.add(name); },
            remove: function (name) { classes.delete(name); },
            contains: function (name) { return classes.has(name); }
        },
        addEventListener: function () {}
    };
}

async function createEnvironment(redirect, outcome) {
    const search = redirect === undefined ? '' : '?redirect=' + encodeURIComponent(redirect);
    let currentUrl = new URL(origin + '/login.html' + search);
    const navigations = [];
    const requests = [];
    const timers = [];
    const cookies = [];
    const storage = new Map();
    const elements = new Map();
    const location = {};
    function navigate(value) {
        const destination = new URL(String(value), currentUrl.href);
        navigations.push(destination.href);
        currentUrl = destination;
    }
    ['href', 'origin', 'pathname', 'search', 'hash', 'protocol'].forEach(function (key) {
        Object.defineProperty(location, key, {
            get: function () { return currentUrl[key]; },
            set: key === 'href' ? navigate : undefined
        });
    });
    location.assign = navigate;
    location.replace = navigate;
    const document = {
        getElementById: function (id) {
            if (!elements.has(id)) elements.set(id, fakeElement());
            return elements.get(id);
        }
    };
    Object.defineProperty(document, 'cookie', {
        get: function () { return cookies.join('; '); },
        set: function (value) { cookies.push(value); }
    });
    const context = {
        document: document,
        location: location,
        URL: URL,
        URLSearchParams: URLSearchParams,
        console: { log: function () {}, warn: function () {}, error: function () {} },
        BYD: { i18n: {
            init: function () {}, t: function (key) { return key; },
            onChange: function () {}
        } },
        localStorage: {
            getItem: function (key) { return storage.get(key) || null; },
            setItem: function (key, value) { storage.set(key, String(value)); }
        },
        setTimeout: function (callback) { timers.push(callback); return timers.length; },
        fetch: async function (url, options) {
            requests.push({ url: url, options: options || {} });
            if (url === '/auth/status') {
                return { ok: true, status: 200, json: async function () {
                    return { deviceId: dummyDevice };
                } };
            }
            assert.strictEqual(url, '/auth/token', 'unexpected request from the login page');
            if (outcome === 'network-error') throw new Error('simulated offline response');
            const success = outcome !== 'rejected';
            return { ok: success, status: success ? 200 : 401, json: async function () {
                return success
                    ? { success: true, jwt: dummyJwt, expiresIn: 60 }
                    : { success: false, error: 'Test access code rejected' };
            } };
        }
    };
    context.window = context;
    vm.createContext(context);
    assert.doesNotThrow(function () {
        inlineScripts.forEach(function (source, index) {
            vm.runInContext(source, context, { filename: loginPath + ':inline-' + index });
        });
    }, 'a redirect must not prevent the login page from initializing');
    await new Promise(function (resolve) { setImmediate(resolve); });
    assert.strictEqual(document.getElementById('deviceIdDisplay').textContent, dummyDevice,
        'the real device-status flow must complete before login');
    return {
        navigations: navigations, cookies: cookies,
        storage: storage, document: document,
        submit: async function () {
            document.getElementById('tokenInput').value = '  ' + dummyCode.toUpperCase() + '  ';
            await context.login();
            const authRequests = requests.filter(function (request) {
                return request.url === '/auth/token';
            });
            assert.strictEqual(authRequests.length, 1, 'login must reach the auth endpoint once');
            assert.strictEqual(authRequests[0].options.method, 'POST');
            assert.deepStrictEqual(JSON.parse(authRequests[0].options.body), {
                token: dummyDevice + '-' + dummyCode
            });
            assert.strictEqual(navigations.length, 0, 'login must wait for its redirect timer');
            timers.splice(0).forEach(function (callback) { callback(); });
        }
    };
}

const tests = [];
function test(name, body) { tests.push({ name: name, body: body }); }

async function expectSuccessfulDestination(redirect, expectedPath) {
    const env = await createEnvironment(redirect);
    await env.submit();
    assert.strictEqual(env.storage.get('byd_jwt'), dummyJwt,
        'a successful login must retain its session');
    assert.ok(env.cookies.some(function (cookie) {
        return cookie.indexOf('byd_session=' + dummyJwt + ';') === 0;
    }), 'a successful login must set the session cookie');
    assert.deepStrictEqual(env.navigations, [new URL(expectedPath, origin).href],
        'successful login must navigate to the permitted destination');
}

[
    ['missing redirect', undefined],
    ['empty redirect', ''],
    ['JavaScript URL', 'javascript:document.documentElement.dataset.loginMarker=1;void 0'],
    ['mixed-case executable scheme', 'JaVaScRiPt:void(0)'],
    ['tab-obfuscated executable scheme', 'java\tscript:void(0)'],
    ['newline-obfuscated executable scheme', 'java\nscript:void(0)'],
    ['data URL', 'data:text/html,<script>document.title="marker"</script>'],
    ['blob URL carrying the local origin', 'blob:' + origin + '/dummy-blob-id'],
    ['foreign origin', 'https://foreign.invalid/landing'],
    ['different scheme', 'http://device.invalid/landing'],
    ['different port', 'https://device.invalid:444/landing'],
    ['scheme-relative foreign origin', '//foreign.invalid/landing'],
    ['userinfo hiding a foreign host', 'https://device.invalid@foreign.invalid/landing'],
    ['userinfo on the local host', 'https://user:password@device.invalid/landing'],
    ['scheme-relative userinfo', '//user:password@device.invalid/landing'],
    ['backslash authority', '\\\\foreign.invalid/landing'],
    ['slash-backslash authority', '/\\foreign.invalid/landing'],
    ['leading backslash', '\\landing'],
    ['backslash inside a path', '/landing\\child'],
    ['null in a path', '/landing\u0000child'],
    ['newline in a path', '/landing\nchild'],
    ['tab in a query', '/landing?value=one\ttwo'],
    ['carriage return in a fragment', '/landing#one\rtwo'],
    ['delete control character in a path', '/landing\u007fchild'],
    ['malformed percent escape', '/landing%ZZ'],
    ['incomplete percent escape', '/landing%2'],
    ['lone percent sign', '%'],
    ['invalid UTF-8 escape', '/landing%E0%A4%A6%FF']
].forEach(function (entry) {
    test(entry[0] + ' falls back to the local root after successful login', async function () {
        await expectSuccessfulDestination(entry[1], '/');
    });
});

[
    ['root path', '/'],
    ['internal path with query and fragment', '/reports/charging?days=7#summary'],
    ['relative path with query and fragment', 'reports/charging?days=7#summary'],
    ['absolute URL on the same origin',
        origin + '/reports/charging?days=7#summary'],
    ['explicit default port on the same origin',
        origin + ':443/reports?days=7#summary'],
    // Returning a parsed pathname beginning with // as a relative URL would
    // reinterpret the path as a foreign authority. Keep the resolved origin.
    ['absolute URL whose pathname begins with two slashes',
        origin + '//foreign.invalid/reports?days=7#summary'],
    ['local path normalizing to two leading slashes',
        '/.//foreign.invalid/reports?days=7#summary'],
    ['escaped path, query and fragment',
        '/reports/energy%20use?query=charge%2Blimit&next=%2Fsettings#details%20tab'],
    ['literal escaped percent signs', '/reports/%252Fvalue?literal=%2525&label=100%25'],
    ['executable-looking query data', '/reports?next=javascript%3Avoid%200#safe'],
    ['encoded scheme retained as a harmless relative path', 'javascript%3Avoid(0)'],
    ['encoded authority retained as a harmless relative path', '%2F%2Fforeign.invalid/landing'],
    ['encoded slashes inside a local path', '/%2F%2Fforeign.invalid/path?next=%2Fsafe']
].forEach(function (entry) {
    test(entry[0] + ' is preserved after successful login', async function () {
        await expectSuccessfulDestination(entry[1], entry[1]);
    });
});

['rejected', 'network-error'].forEach(function (outcome) {
    test(outcome + ' login neither redirects nor stores a session', async function () {
        const env = await createEnvironment('javascript:void(0)', outcome);
        await env.submit();
        assert.deepStrictEqual(env.navigations, []);
        assert.deepStrictEqual(env.cookies, []);
        assert.strictEqual(env.storage.size, 0);
        assert.strictEqual(env.document.getElementById('loginBtn').disabled, false);
        assert.ok(env.document.getElementById(outcome === 'rejected'
            ? 'tokenError' : 'statusMessage').textContent);
    });
});

(async function run() {
    let failures = 0;
    for (const entry of tests) {
        try {
            await entry.body();
            console.log('PASS ' + entry.name);
        } catch (error) {
            failures++;
            console.error('FAIL ' + entry.name + '\n' + error.stack);
        }
    }
    console.log((tests.length - failures) + '/' + tests.length + ' login redirect tests passed');
    if (failures) process.exitCode = 1;
})();
