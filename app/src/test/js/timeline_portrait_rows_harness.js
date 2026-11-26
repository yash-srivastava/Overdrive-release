/* Run with node app/src/test/js/timeline_portrait_rows_harness.js. */
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const context = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-view.js'), 'utf8'), context);
const { PortraitMomentRows, buildMoments, momentPasses, pushRows } = context.BYD.tlview._internals;
const rows = new PortraitMomentRows();
const base = 1760000000000;
const filters = ['all', 'people', 'vehicles', 'alerts'];

// Previous full scan, including the off-screen collision padding and inclusive edges.
function original(list, filter, strip, height) {
    const items = [];
    for (const m of list) {
        if (!momentPasses(m, filter)) continue;
        const pos = strip.timeToPos(m.t);
        if (pos == null || pos === '' || !isFinite(Number(pos)) || pos < -448 || pos > height + 56) continue;
        items.push({ key: m.id, pos });
    }
    return items;
}
function stripAt(center, ppm, p0) {
    return { timeToPos(t) { return p0 - (t - center) * ppm; } };
}
const plain = x => JSON.parse(JSON.stringify(x));
let comparisons = 0;
function compare(list, filter, strip, height) {
    const expected = original(list, filter, strip, height);
    const actual = rows.collect(list, filter, strip, height);
    assert.deepStrictEqual(plain(actual), expected);
    assert.deepStrictEqual(plain(pushRows(actual, 56, 4, null)), plain(pushRows(expected, 56, 4, null)));
    comparisons++;
}
const boundary = [-449, -448, -448, -447, 0, 556, 556, 557].map((p, i) => ({
    id: String(i), t: base - p, classes: ['person'], level: i % 2 ? 'alert' : null
}));
for (const f of filters) compare(boundary, f, stripAt(base, 1, 0), 500);
assert.deepStrictEqual(plain(rows.collect(boundary, 'all', stripAt(base, 1, 0), 500)).map(x => x.key),
    ['1', '2', '3', '4', '5', '6']);
// New source arrays, invalid order, invalid timestamps, zero-scale and unavailable projections.
for (const list of [[], boundary.slice(0, 1), boundary.slice().reverse(),
    [{ id: 'invalid', t: NaN, classes: [] }, ...boundary],
    boundary.map(m => ({ ...m, t: String(m.t) }))]) {
    for (const f of filters) {
        compare(list, f, stripAt(base, 1, 0), 500);
        compare(list, f, stripAt(base, 0, 42), 500);
        compare(list, f, { timeToPos: () => NaN }, 500);
    }
}
let seed = 0x793dc1;
function rand(n) {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return (seed >>> 8) % n;
}
for (let run = 0; run < 100; run++) {
    const segs = Array.from({ length: rand(240) }, (_, i) => {
        const start = base + rand(10000) * 1000;
        return { id: String(i), start, end: start + rand(40) * 1000,
            type: ['sentry', 'proximity', 'replay'][rand(3)],
            classes: [['person', 'vehicle', 'animal', 'bike'][rand(4)]],
            peakSeverity: rand(3) === 0 ? 'ALERT' : '' };
    });
    const marks = segs.map(s => ({ rec: s.id, kind: 'motion', t0: s.start + 1000 }));
    const list = buildMoments(segs, marks);
    Object.freeze(list);
    for (const f of filters) {
        for (const span of [600000, 3600000, 21600000, 86400000]) {
            const h = 200 + rand(900);
            compare(list, f, stripAt(base + rand(12000) * 1000, h / span, h * 0.5), h);
        }
    }
}
// Warm frame: 10,000 events, 10-minute zoom, one event per minute.
let projections = 0, classReads = 0;
const large = Array.from({ length: 10000 }, (_, i) => ({
    id: String(i), t: base - i * 60000,
    get classes() { classReads++; return ['person']; }
}));
const strip = { timeToPos(t) { projections++; return 300 - (t - (base - 5000 * 60000)) * .001; } };
rows.collect(large, 'people', strip, 600); // validate this revision once
projections = classReads = 0;
const actual = rows.collect(large, 'people', strip, 600);
const fastProjections = projections, fastClasses = classReads;
projections = classReads = 0;
const expected = original(large, 'people', strip, 600);
assert.deepStrictEqual(plain(actual), expected);
assert.strictEqual(projections, 10000);
assert.strictEqual(classReads, 10000);
assert(fastProjections < 40, 'warm frame must skip off-screen projections');
assert(fastClasses < 25, 'warm frame must skip off-screen filter checks');
console.log(`${comparisons} reference comparisons passed; warm frame: ${projections} -> ${fastProjections} projections, ` +
    `${classReads} -> ${fastClasses} filter reads, ${actual.length} identical candidate rows`);
