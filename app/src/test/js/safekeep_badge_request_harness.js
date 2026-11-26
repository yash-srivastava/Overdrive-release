'use strict';

// Run with: node app/src/test/js/safekeep_badge_request_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
    '../../main/assets/web/shared/safekeep-badges.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function badge(file) {
    return {
        hidden: true,
        getAttribute: () => file,
        setAttribute() { this.hidden = true; },
        removeAttribute() { this.hidden = false; },
        insertAdjacentHTML: () => {},
        textContent: ''
    };
}

function setup() {
    let nextTimer = 0;
    const timers = new Map();
    const requests = [];
    const env = { badges: [] };
    const listeners = {};
    const document = {
        hidden: false,
        querySelectorAll: () => env.badges,
        addEventListener: (name, fn) => { listeners[name] = fn; }
    };
    const window = {};
    const sandbox = {
        window, document,
        setTimeout(fn, ms) { timers.set(++nextTimer, { fn, ms }); return nextTimer; },
        clearTimeout(id) { timers.delete(id); },
        fetch(url, opts) {
            if (url === '/api/safekeep/status') {
                return Promise.resolve({ ok: true, json: () => Promise.resolve({
                    enabled: true, destinations: [{}]
                }) });
            }
            assert.strictEqual(url, '/api/safekeep/clips');
            return new Promise((resolve, reject) => requests.push({ files: JSON.parse(opts.body).files, resolve, reject }));
        }
    };
    vm.runInNewContext(source, sandbox, { filename: 'safekeep-badges.js' });
    env.setFiles = (...files) => { env.badges = files.map(badge); };
    env.runTimer = async ms => {
        const entry = Array.from(timers.entries()).find(([, t]) => t.ms === ms);
        assert.ok(entry, 'expected timer ' + ms);
        timers.delete(entry[0]);
        entry[1].fn();
        await flush();
    };
    env.refresh = async () => { window.SafeKeepBadges.decorate(); await env.runTimer(250); };
    env.respond = async (i, states, status = 200) => {
        requests[i].resolve({ ok: status === 200, status,
            json: () => Promise.resolve({ success: status === 200, states }) });
        await flush();
    };
    env.reject = async i => {
        requests[i].reject(new Error('offline'));
        await flush();
    };
    env.setHidden = hidden => {
        document.hidden = hidden;
        listeners.visibilitychange();
    };
    env.requests = requests;
    env.busyTimers = () => Array.from(timers.values()).filter(t => t.ms === 4000).length;
    return env;
}

async function run() {
    let e = setup();
    e.setFiles('a.mp4');
    await e.refresh();
    await e.refresh();
    await e.respond(1, { 'a.mp4': { state: 'saved' } });
    await e.respond(0, { 'a.mp4': { state: 'uploading', progress: 30 } });
    assert.strictEqual(e.badges[0].className, 'sk-badge sk-saved');
    assert.strictEqual(e.busyTimers(), 0, 'stale uploading state must not restart polling');

    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    e.setFiles('b.mp4'); await e.refresh();
    assert.deepStrictEqual(e.requests[1].files, ['b.mp4']);
    await e.respond(1, { 'b.mp4': { state: 'saved' } });
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    assert.strictEqual(e.badges[0].hidden, false, 'new list stays visible');
    assert.strictEqual(e.badges[0].className, 'sk-badge sk-saved');

    e = setup();
    e.setFiles('a.mp4'); await e.refresh(); await e.refresh();
    await e.respond(1, { 'a.mp4': { state: 'saved' } });
    await e.respond(0, {}, 409);
    assert.strictEqual(e.badges[0].hidden, false, 'obsolete disabled response ignored');
    await e.refresh();
    assert.strictEqual(e.requests.length, 3, 'obsolete disabled response must not stop refreshes');

    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    assert.strictEqual(e.busyTimers(), 1);
    await e.refresh(); await e.refresh();
    await e.respond(2, {}, 409);
    await e.respond(1, { 'a.mp4': { state: 'saved' } });
    assert.strictEqual(e.badges[0].hidden, true, 'current disabled response stays authoritative');
    assert.strictEqual(e.busyTimers(), 0, 'disabled response cancels scheduled polling');

    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    e.setFiles(); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    assert.strictEqual(e.busyTimers(), 0, 'empty refresh supersedes old list');
    assert.strictEqual(e.requests.length, 1);

    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'uploading', progress: 10 } });
    assert.strictEqual(e.busyTimers(), 1);
    await e.runTimer(4000);
    await e.respond(1, { 'a.mp4': { state: 'saved' } });
    assert.strictEqual(e.badges[0].className, 'sk-badge sk-saved');
    assert.strictEqual(e.busyTimers(), 0);
    // A transient failure of the periodic poll must not strand a busy badge.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'uploading', progress: 10 } });
    await e.runTimer(4000); await e.reject(1);
    assert.strictEqual(e.badges[0].textContent, '↑ 10%', 'keep last known progress');
    assert.strictEqual(e.busyTimers(), 1, 'network failure must reschedule busy polling');
    await e.runTimer(4000); await e.respond(2, {}, 503);
    assert.strictEqual(e.busyTimers(), 1, 'HTTP failure must also reschedule');
    await e.runTimer(4000); await e.respond(3, { 'a.mp4': { state: 'saved' } });
    assert.strictEqual(e.badges[0].className, 'sk-badge sk-saved');
    assert.strictEqual(e.busyTimers(), 0);

    // Do not introduce polling for a failed initial request with no known busy state.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh(); await e.reject(0);
    assert.strictEqual(e.busyTimers(), 0);

    // Permanent client errors must not start an automatic retry loop.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    await e.runTimer(4000); await e.respond(1, {}, 403);
    assert.strictEqual(e.busyTimers(), 0);

    // A stale failure cannot restart polling after a newer successful response.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    await e.runTimer(4000); await e.refresh();
    await e.respond(2, { 'a.mp4': { state: 'saved' } }); await e.reject(1);
    assert.strictEqual(e.busyTimers(), 0);

    // Keep the hidden-page policy, including when a request fails while hidden.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    e.setHidden(true);
    assert.strictEqual(e.busyTimers(), 0, 'hide cancels the scheduled refresh');
    e.setHidden(false);
    assert.strictEqual(e.busyTimers(), 1);
    await e.runTimer(4000); e.setHidden(true); await e.reject(1);
    assert.strictEqual(e.busyTimers(), 0);
    e.setHidden(false);
    await e.runTimer(4000); await e.respond(2, {}, 409);
    assert.strictEqual(e.busyTimers(), 0);
    assert.strictEqual(e.badges[0].hidden, true);

    // A busy clip from a previous list must not drive polling for the new list.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    e.setFiles('b.mp4'); await e.refresh(); await e.reject(1);
    assert.strictEqual(e.busyTimers(), 0);

    // Empty lists cancel outstanding polling as well.
    e = setup();
    e.setFiles('a.mp4'); await e.refresh();
    await e.respond(0, { 'a.mp4': { state: 'queued' } });
    e.setFiles(); await e.refresh();
    assert.strictEqual(e.busyTimers(), 0);
    console.log('SafeKeep badge request order and polling: 13 scenarios passed');
}

run().catch(err => { console.error(err); process.exitCode = 1; });
