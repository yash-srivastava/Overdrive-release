'use strict';

// Exercise the actual refreshStatus wrapper with a fake wall clock and storage.
// Run with: node app/src/test/js/update_passive_check_clock_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/update-flow.js'), 'utf8');
const SIX_HOURS = 6 * 60 * 60 * 1000;
const NOW = Date.UTC(2026, 5, 1, 12);
const KEY = 'upd_lastAutoCheck';

function browser(now, storage, requests, offline = false) {
    let init;
    const sandbox = {
        Date: { now: () => now },
        document: {
            readyState: 'loading',
            addEventListener(type, callback) { if (type === 'DOMContentLoaded') init = callback; },
            createElement() { return {}; },
            head: { appendChild() {} },
            querySelector() { return null; },
            getElementById() { return null; }
        },
        localStorage: {
            getItem(key) { return storage.has(key) ? storage.get(key) : null; },
            setItem(key, value) { storage.set(key, String(value)); }
        },
        BYD: { core: { refreshStatus: async () => ({ acc: true }) } },
        fetch(url) {
            requests.push(url);
            if (offline) return Promise.reject(new Error('offline'));
            return Promise.resolve({ text: async () => JSON.stringify({ channel: 'braveheart', available: false }) });
        }
    };
    sandbox.window = sandbox;
    vm.runInNewContext(source, sandbox, { filename: 'update-flow.js' });
    init();
    return async function refresh(at = now) {
        now = at;
        await sandbox.BYD.core.refreshStatus();
        await new Promise(resolve => setImmediate(resolve));
    };
}

async function run() {
    let storage = new Map([[KEY, String(NOW + 24 * SIX_HOURS)]]);
    let requests = [];
    let refresh = browser(NOW, storage, requests);
    await refresh();
    assert.deepStrictEqual(requests, ['/api/update/check'], 'future timestamp must not suppress the check');
    assert.strictEqual(storage.get(KEY), String(NOW));
    await refresh();
    await refresh(NOW + SIX_HOURS - 1);
    assert.strictEqual(requests.length, 1, 'correction must restore normal throttling');
    refresh = browser(NOW + SIX_HOURS - 1, storage, requests);
    await refresh();
    assert.strictEqual(requests.length, 1, 'throttle must survive page reload');
    await refresh(NOW + SIX_HOURS);
    assert.strictEqual(requests.length, 2, 'check at exact six-hour boundary');

    for (const [label, last, expected] of [
        ['same time', String(NOW), 0],
        ['recent', String(NOW - 1), 0],
        ['expired', String(NOW - SIX_HOURS - 1), 1],
        ['missing', null, 1],
        ['nonnumeric', 'invalid', 1]
    ]) {
        storage = new Map(last === null ? [] : [[KEY, last]]);
        requests = [];
        await browser(NOW, storage, requests)();
        assert.strictEqual(requests.length, expected, label);
        assert.strictEqual(storage.get(KEY), expected ? String(NOW) : last, label + ' timestamp');
    }

    storage = new Map([[KEY, String(NOW + SIX_HOURS)]]);
    requests = [];
    refresh = browser(NOW, storage, requests, true);
    await refresh();
    await refresh(NOW + 1);
    assert.deepStrictEqual(requests, ['/api/update/check'], 'failed check must retain six-hour backoff');
    assert.strictEqual(storage.get(KEY), String(NOW));
    console.log('update_passive_check_clock_harness: all checks passed');
}

run().catch(error => { console.error(error); process.exitCode = 1; });
