'use strict';

// Run with: node app/src/test/js/storage_index_warming_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const src = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/storage.js'), 'utf8');

function setup() {
    const timers = new Map();
    let nextId = 1;
    let statsReads = 0;
    const sandbox = {
        window: {},
        document: { getElementById: () => null },
        setTimeout(fn, delay) {
            const id = nextId++;
            timers.set(id, { fn, delay });
            return id;
        },
        clearTimeout(id) { timers.delete(id); }
    };
    sandbox.window.BYD = {};
    sandbox.BYD = sandbox.window.BYD;
    vm.createContext(sandbox);
    vm.runInContext(src, sandbox, { filename: 'storage.js' });
    const page = sandbox.BYD.storagePage;
    page.loadStorageSettings = () => {};
    page.loadTripStorage = () => {};
    page.loadStorageStats = () => { statsReads++; };
    page._cdrVisible = () => false;
    return { page, timers, reads: () => statsReads };
}

const warming = { success: true, indexState: { warming: true, done: 1, total: 5 } };
const unavailable = { indexUnavailable: true };
const ready = { success: true };

// Warmup retry must not bypass the regular index-down polling policy.
{
    const { page, timers, reads } = setup();
    page._applyStats(warming);
    assert.strictEqual(timers.size, 1);
    page._applyStats(unavailable);
    assert.strictEqual(timers.size, 0, 'obsolete warmup timer must be cancelled');
    assert.strictEqual(page._warmingPollTimer, null);
    assert.strictEqual(page._warmingPollAttempt, 0);
    assert.strictEqual(page.stats, null);
    assert.strictEqual(page._indexDown, true);
    page._tick();
    page._tick();
    assert.strictEqual(reads(), 0);
    page._tick();
    assert.strictEqual(reads(), 1);
}

// Recovery into another warmup restarts at the initial delay.
{
    const { page, timers } = setup();
    page._applyStats(warming);
    page._applyStats(warming);
    assert.deepStrictEqual([...timers.values()].map(t => t.delay), [4000]);
    page._applyStats(unavailable);
    page._applyStats(unavailable);
    page._applyStats(warming);
    assert.strictEqual(page._indexDown, false);
    assert.deepStrictEqual([...timers.values()].map(t => t.delay), [2000]);
}

// Healthy warmup still backs off and stops when the index is ready.
{
    const { page, timers, reads } = setup();
    for (const delay of [2000, 4000, 8000, 10000, 10000]) {
        page._applyStats(warming);
        assert.deepStrictEqual([...timers.values()].map(t => t.delay), [delay]);
    }
    const id = page._warmingPollTimer;
    const timer = timers.get(id);
    timers.delete(id);
    timer.fn();
    assert.strictEqual(reads(), 1);
    assert.strictEqual(page._warmingPollTimer, null);
    page._applyStats(warming);
    page._applyStats(ready);
    assert.strictEqual(timers.size, 0);
    assert.strictEqual(page._warmingPollAttempt, 0);
    assert.strictEqual(page._indexDown, false);
}

console.log('storage_index_warming_harness: all 3 scenarios passed');
