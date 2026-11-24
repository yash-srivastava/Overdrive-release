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
    const document = {
        hidden: false,
        querySelectorAll: () => env.badges,
        addEventListener: () => {}
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
            return new Promise(resolve => requests.push({ files: JSON.parse(opts.body).files, resolve }));
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
    console.log('SafeKeep badge request order: 6 scenarios passed');
}

run().catch(err => { console.error(err); process.exitCode = 1; });
