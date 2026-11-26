'use strict';

// Run with: node app/src/test/js/trips_speed_histogram_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/trips.js'), 'utf8');
const summary = { innerHTML: '' };
const units = { mode: 'km', KM_TO_MI: 0.621371, speedLabel() { return this.mode === 'mi' ? 'mph' : 'km/h'; }, speed(v) { return String(v); } };
const sandbox = { window: { devicePixelRatio: 1 }, document: { getElementById: () => summary },
    BYD: { units, i18n: { t: k => k } } };
sandbox.window.BYD = sandbox.BYD;
vm.createContext(sandbox);
vm.runInContext(src + '\n;this.trips = TRIPS;', sandbox);
const trips = sandbox.trips;

// The old string-bucket and array-filter algorithm, independent of the new helper.
function reference(telemetry) {
    const buckets = {};
    const labels = [];
    for (let i = 0; i <= 130; i += 10) {
        const label = i + '-' + (i + 10);
        labels.push(label);
        buckets[label] = 0;
    }
    labels.push('140+');
    buckets['140+'] = 0;
    let total = 0;
    telemetry.forEach(s => {
        const speed = s.s || 0;
        total++;
        if (speed >= 140) { buckets['140+']++; return; }
        const idx = Math.floor(speed / 10) * 10;
        const label = idx + '-' + (idx + 10);
        if (buckets[label] !== undefined) buckets[label]++;
    });
    const allSpeeds = telemetry.map(s => s.s || 0);
    const moving = allSpeeds.filter(s => s > 0);
    return { counts: labels.map(l => buckets[l]), total, moving: moving.length,
        low: moving.filter(s => s < 40).length, high: moving.filter(s => s > 80).length };
}

function compare(rows) {
    const expected = reference(rows);
    const actual = trips._speedHistogramStats(rows);
    assert.deepStrictEqual(Array.from(actual.counts), expected.counts);
    for (const key of ['total', 'moving', 'low', 'high']) assert.strictEqual(actual[key], expected[key]);
}

const unusual = [undefined, null, false, true, '', '0', '39.9', '80', '140', 'bad',
    NaN, Infinity, -Infinity, -Number.MIN_VALUE, -10, -0, 0, Number.MIN_VALUE, 0.01, 39.99, 40, 80, 80.01, 139.99, 140, 150];
for (let i = 0; i <= 140; i += 10) unusual.push(i - 0.001, i, i + 0.001);
compare([]);
compare([{}, ...unusual.map(s => ({ s }))]);
const sparse = new Array(100);
sparse[2] = { s: 5 };
sparse[99] = { s: 100 };
compare(sparse);
let seed = 0x21d2026;
function random() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; }
for (let i = 0; i < 1000; i++) {
    const rows = Array.from({ length: Math.floor(random() * 500) }, () => ({
        s: random() < 0.25 ? unusual[Math.floor(random() * unusual.length)] : random() * 250 - 25
    }));
    compare(rows);
}
const large = Array.from({ length: 100000 }, (_, i) => ({ s: i % 160 }));
compare(large);
// Accessor instrumentation: one speed read per entry instead of two.
let reads = 0;
const observed = large.map(row => ({ get s() { reads++; return row.s; } }));
reference(observed);
assert.strictEqual(reads, 200000);
reads = 0;
trips._speedHistogramStats(observed);
assert.strictEqual(reads, 100000);

// Exercise the actual chart/summary wiring, including strict summary thresholds,
// metric/imperial labels, colors, and percentages using all samples as denominator.
function render(mode) {
    units.mode = mode;
    const texts = [], colors = [];
    const context = { scale() {}, clearRect() {}, beginPath() {}, moveTo() {}, lineTo() {},
        quadraticCurveTo() {}, closePath() {}, fill() { colors.push(this.fillStyle); }, fillText(text) { texts.push([text, this.textAlign]); } };
    const canvas = { style: {}, getContext: () => context, getBoundingClientRect: () => ({ width: 400, height: 300 }) };
    trips.currentTripData = { avgSpeedKmh: 34, maxSpeedKmh: 141 };
    trips.renderSpeedHistogram(canvas, [0, 39, 40, 80, 81, 140, -1, 'bad'].map(s => ({ s })));
    assert.ok(summary.innerHTML.includes('34'));
    assert.ok(summary.innerHTML.includes('141'));
    assert.ok(summary.innerHTML.includes('20%')); // 1 / 5 moving samples below 40
    assert.ok(summary.innerHTML.includes('40%')); // 2 / 5 moving samples above 80
    const labels = mode === 'mi' ? ['0-6 mph', '19-25 mph', '25-31 mph', '50-56 mph', '87+ mph']
        : ['0-10 km/h', '30-40 km/h', '40-50 km/h', '80-90 km/h', '140+ km/h'];
    assert.deepStrictEqual(texts.filter(t => t[1] === 'right').map(t => t[0]), labels);
    assert.deepStrictEqual(texts.filter(t => t[1] === 'left').map(t => t[0]), ['13%', '13%', '13%', '25%', '13%']);
    assert.deepStrictEqual(colors, [trips.colors.speedYellow, trips.colors.speedYellow,
        trips.colors.speedGreen, trips.colors.speedGreen, trips.colors.speedRed]);
}
render('km');
render('mi');
console.log('trips_speed_histogram_harness: ALL CHECKS PASSED (1004 reference datasets, 100000-row stress)');
