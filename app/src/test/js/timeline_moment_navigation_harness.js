/* Run with node app/src/test/js/timeline_moment_navigation_harness.js. */
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const context = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-view.js'), 'utf8'), context);
const { MomentNavigation, momentPasses, buildMoments } = context.BYD.tlview._internals;
const nav = new MomentNavigation();
const filters = ['all', 'people', 'vehicles', 'alerts'];

// Reference is the previous findMoment implementation, including strict thresholds.
function original(moments, filter, t, dir) {
    const list = moments.filter(m => momentPasses(m, filter)).sort((a, b) => a.start - b.start);
    if (dir > 0) {
        for (let i = 0; i < list.length; i++) if (list[i].start > t + 1000) return list[i];
    } else {
        for (let i = list.length - 1; i >= 0; i--) if (list[i].start < t - 2000) return list[i];
    }
    return null;
}
let comparisons = 0;
function compare(moments, filter, t, dir) {
    assert.strictEqual(nav.find(moments, filter, t, dir), original(moments, filter, t, dir));
    comparisons++;
}
let seed = 0x235789ab;
function rand(n) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return (seed >>> 8) % n;
}
const base = 1760000000000;
// Real moment construction: event time order is deliberately different from clip start order.
const sample = buildMoments([
    { id: 'a', type: 'replay', start: base + 1000, end: base + 12000 },
    { id: 'b', type: 'sentry', start: base + 1000, end: base + 5000, classes: ['person'] },
    { id: 'c', type: 'proximity', start: base + 4000, end: base + 5000, peakSeverity: 'ALERT' }
], [{ rec: 'b', kind: 'person', t0: base + 3000 }]);
for (const f of filters) {
    for (const t of [base - 1, base, base + 1, base + 2999, base + 3000, base + 3001, base + 10000, NaN]) {
        compare(sample, f, t, 1); compare(sample, f, t, -1);
    }
}
// New source arrays, empty data, filter changes, duplicate starts, and numeric-string starts.
for (let run = 0; run < 120; run++) {
    const moments = Array.from({ length: rand(180) }, (_, i) => {
        const start = base + rand(80) * 1000;
        return { id: String(i), start: i % 9 === 0 ? String(start) : start,
            classes: [ ['person', 'vehicle', 'bike', 'animal'][rand(4)] ],
            level: rand(4) === 0 ? 'alert' : null };
    });
    Object.freeze(moments);
    for (const f of filters) {
        for (let i = 0; i < 12; i++) {
            const t = base + (rand(100) - 10) * 1000 + rand(3) - 1;
            compare(moments, f, t, 1); compare(moments, f, t, -1);
        }
    }
}
// The warm path must not refilter or sort. Count actual timestamp/class reads.
let starts = 0, classes = 0;
const large = Array.from({ length: 10000 }, (_, i) => ({
    get start() { starts++; return base + ((i * 7919) % 10000) * 3000; },
    get classes() { classes++; return ['person']; }
}));
nav.find(large, 'people', base, 1);
starts = classes = 0;
for (let i = 0; i < 240; i++) {
    const t = base + i * 250;
    nav.find(large, 'people', t, 1);
    nav.find(large, 'people', t, -1);
}
const warmStarts = starts;
assert.strictEqual(classes, 0, 'warm queries must not repeat filtering');
assert(warmStarts <= 480 * 14, 'warm queries must use logarithmic timestamp comparisons');
starts = classes = 0;
for (let i = 0; i < 240; i++) {
    const t = base + i * 250;
    original(large, 'people', t, 1);
    original(large, 'people', t, -1);
}
console.log(`${comparisons} reference comparisons passed; 480 warm queries: ` +
    `${starts} -> ${warmStarts} timestamp reads, ${classes} -> 0 class reads`);
// Invalidate the cache even if the next revision has the same length.
const replacement = [{ start: base + 5000, classes: ['person'] }];
assert.strictEqual(nav.find(replacement, 'people', base, 1), replacement[0]);
assert.strictEqual(nav.find(replacement, 'vehicles', base, 1), null);
assert.strictEqual(nav.find([], 'people', base, 1), null);
