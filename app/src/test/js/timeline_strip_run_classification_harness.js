// Run with node app/src/test/js/timeline_strip_run_classification_harness.js.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const window = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-strip.js'), 'utf8'), { window });
const { buildModel } = window.BYD.tlstrip._internals;
const TOL = 1500;

// Independent reference: merge available intervals, then use the original
// strict overlap predicate to classify every merged run.
function reference(segments) {
    const valid = segments.filter(s => s && Number.isFinite(s.start) && s.available !== false)
        .map(s => [s.start, Number.isFinite(s.end) && s.end > s.start ? s.end : s.start + 1000,
            s.type === 'normal' || s.type === 'oemDashcam'])
        .filter(p => p[1] > p[0]).sort((a, b) => a[0] - b[0]);
    const runs = [];
    for (const [s, e] of valid) {
        const last = runs[runs.length - 1];
        if (last && s <= last[1] + TOL) last[1] = Math.max(last[1], e);
        else runs.push([s, e]);
    }
    return { runs: runs.flat(), flags: runs.map(([s, e]) =>
        Number(valid.some(p => p[2] && p[0] < e && p[1] > s))) };
}
function check(segments, message) {
    const expected = reference(segments);
    const model = buildModel({ segments, minMs: 0, maxMs: 86400000, now: 86400000 });
    assert.deepStrictEqual(Array.from(model.runs), expected.runs, message);
    assert.deepStrictEqual(Array.from(model.runDrive), expected.flags, message);
    assert.strictEqual(model.nRuns, expected.flags.length, message);
}

check([], 'empty');
check([null, { start: NaN }, { start: 0, end: 1000, type: 'sentry' },
    { start: 2500, end: 3000, type: 'normal' }, // exact merge tolerance
    { start: 4501, end: 6000, type: 'sentry' }, // separate run
    { start: 4502, end: 5999, type: 'oemDashcam', available: false },
    { start: 8000, end: 12000, type: 'sentry' },
    { start: 8100, end: 8200, type: 'oemDashcam' }, // nested driving clip
    { start: 14000, end: null, type: 'normal' }, // fallback duration
    { start: 14000, end: 15000, type: 'sentry' }], 'boundaries and nested clips');

let seed = 0x12345678;
function random(n) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return Math.floor(seed / 4294967296 * n);
}
const types = ['normal', 'oemDashcam', 'sentry', 'replay', 'proximity'];
for (let c = 0; c < 400; c++) {
    const segments = [];
    const count = random(120);
    for (let i = 0; i < count; i++) {
        const start = random(500) * 1000;
        segments.push({ id: i, start, end: random(7) === 0 ? null : start + random(12000) - 1000,
            type: types[random(types.length)], available: random(4) !== 0 });
    }
    check(segments, 'random case ' + c);
}
const fragmented = Array.from({ length: 2000 }, (_, i) => ({
    id: i, start: i * 4000, end: i * 4000 + 1000, type: 'normal'
}));
check(fragmented.reverse(), '2000 separate driving runs, reverse input order');
console.log('timeline run classification: 403 cases passed');
