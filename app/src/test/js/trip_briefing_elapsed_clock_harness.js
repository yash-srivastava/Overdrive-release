'use strict';

// Run with: node app/src/test/js/trip_briefing_elapsed_clock_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(process.argv[2] || path.resolve(__dirname,
    '../../main/assets/web/shared/trip-briefing.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture() {
    let elapsed = 0, wall = 1700000000000, loads = 0;
    const timers = [], messages = [];
    const BYD = { core: { toast: (message, kind) => messages.push(kind) } };
    const window = {
        BYD,
        performance: { now: () => elapsed },
        setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; }
    };
    class FakeDate extends Date { static now() { return wall; } }
    vm.runInNewContext(source, {
        window, BYD, document: {}, Date: FakeDate,
        fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve({ learning: true }) })
    });
    const page = window.TripBriefing;
    page.isVisible = () => true;
    page.render = () => {};
    page.load = () => { loads++; };
    page.fetchPayload = () => Promise.resolve({ settings: {}, learnedMs: 10 });
    return {
        page, timers, messages, loads: () => loads,
        advance(ms, wallDelta = ms) { elapsed += ms; wall += wallDelta; },
        async fire(ms) {
            const timer = timers.shift();
            assert.ok(timer, 'expected scheduled retry');
            assert.strictEqual(timer.ms, ms);
            timer.fn();
            await flush();
        }
    };
}

function cache(wallCorrection) {
    const f = fixture();
    f.page.apply({ settings: {}, ready: true });
    f.advance(29999, wallCorrection);
    f.page.onShown();
    assert.strictEqual(f.loads(), 0, 'cache remains fresh before 30 seconds');
    f.advance(1);
    f.page.onShown();
    assert.strictEqual(f.loads(), 1, 'cache expires at 30 seconds');
}

async function readiness(wallCorrection) {
    const f = fixture();
    f.page.apply({ settings: {}, ready: false });
    f.page.waitReady(); // elapsed time is exactly zero at the first observation.
    f.advance(3000, wallCorrection);
    await f.fire(3000);
    f.page.waitReady();
    assert.strictEqual(f.page.notReady, false, 'do not time out early');
    f.advance(117000);
    await f.fire(3000);
    f.page.waitReady();
    assert.strictEqual(f.page.notReady, true, 'stop retrying after 120 elapsed seconds');
    assert.strictEqual(f.timers.length, 0);
    f.page.retryReady();
    f.page.waitReady();
    assert.strictEqual(f.page.notReady, false, 'manual retry starts a fresh wait');
    assert.strictEqual(f.timers.length, 1);
}

async function learning(wallCorrection, rejectedRead) {
    const f = fixture();
    f.page.apply({ settings: {}, learnedMs: 10 });
    if (rejectedRead) f.page.fetchPayload = () => Promise.reject(new Error('offline'));
    f.page.learn();
    await flush();
    f.advance(2000, wallCorrection);
    await f.fire(2000);
    assert.strictEqual(f.page.learning, true, 'learning must not time out early');
    f.advance(118000);
    await f.fire(2000);
    assert.strictEqual(f.page.learning, false, 'learning polling expires after 120 seconds');
    assert.deepStrictEqual(f.messages, ['error']);
    assert.strictEqual(f.timers.length, 0);
}

async function learningSuccess() {
    const f = fixture();
    f.page.apply({ settings: {}, learnedMs: 10 });
    f.page.fetchPayload = () => Promise.resolve({ settings: {}, learnedMs: 11 });
    f.page.learn();
    await flush();
    f.advance(2000, -3600000);
    await f.fire(2000);
    assert.strictEqual(f.page.learning, false);
    assert.strictEqual(f.page.data.learnedMs, 11, 'keep server epoch timestamp unchanged');
    assert.deepStrictEqual(f.messages, []);
}

(async () => {
    cache(-3600000);
    cache(3600000);
    await readiness(-3600000);
    await readiness(3600000);
    await learning(-3600000, false);
    await learning(3600000, false);
    await learning(-3600000, true);
    await learning(3600000, true);
    await learningSuccess();
    console.log('trip_briefing_elapsed_clock_harness: 9 scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
