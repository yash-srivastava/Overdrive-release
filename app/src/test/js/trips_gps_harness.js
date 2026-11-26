'use strict';

// Run with: node app/src/test/js/trips_gps_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const tripsPath = path.resolve(__dirname, '../../main/assets/web/shared/trips.js');
const src = fs.readFileSync(tripsPath, 'utf8');

const sandbox = {
    window: {},
    document: {
        getElementById: function () { return null; },
        addEventListener: function () {}
    },
    console: console,
    setTimeout: function () {},
    clearTimeout: function () {},
    BYD: {
        i18n: { t: function (k) { return k; } },
        units: {},
        theme: {}
    },
    L: undefined
};

vm.createContext(sandbox);
vm.runInContext(src + '\n;this.__TRIPS = TRIPS;', sandbox, { filename: 'trips.js' });

const TRIPS = sandbox.__TRIPS;
assert.ok(TRIPS && TRIPS._telemetryHasGps, 'TRIPS._telemetryHasGps must exist');

assert.ok(TRIPS._telemetryHasGps({ la: -33.8, lo: 151.2 }));
assert.ok(!TRIPS._telemetryHasGps({ la: 0, lo: 0 }));
assert.ok(!TRIPS._telemetryHasGps({ la: 51, lo: 'x' }));
assert.ok(!TRIPS._telemetryHasGps({ la: 91, lo: 10 }));

const pts = TRIPS._gpsPointsFromTelemetry([
    { la: 0, lo: 0, s: 0 },
    { la: 32.1, lo: 34.8, s: 40 },
    { la: 32.2, lo: 34.9, s: 50 }
]);
assert.strictEqual(pts.length, 2);

// Capture the actual Leaflet multi-polyline input. Every subpath must be
// contiguous and use the destination sample's speed, without phantom edges.
const layers = [];
const map = {};
sandbox.L = {
    polyline(coords, options) {
        return { addTo(target) {
            assert.strictEqual(target, map);
            assert.strictEqual(options.weight, 4);
            assert.strictEqual(options.opacity, 0.85);
            layers.push({ coords, color: options.color });
        } };
    }
};
function rows(speeds) {
    return speeds.map((s, i) => ({ la: 32 + i / 10000, lo: 34 + i / 10000, s }));
}
function draw(points) {
    layers.length = 0;
    TRIPS._renderRouteRibbon(points, map);
    return layers;
}
function expectedEdges(points) {
    return points.slice(1).map((p, i) => JSON.stringify([
        [points[i].la, points[i].lo], [p.la, p.lo], TRIPS._speedColorForRoute(p.s)
    ])).sort();
}
function actualEdges() {
    const edges = [];
    for (const layer of layers) {
        for (const run of layer.coords) {
            assert.ok(run.length >= 2, 'no degenerate runs');
            for (let i = 1; i < run.length; i++) {
                edges.push(JSON.stringify([run[i - 1], run[i], layer.color]));
            }
        }
    }
    return edges.sort();
}
assert.strictEqual(draw([]).length, 0);
assert.strictEqual(draw(rows([20])).length, 0);
for (const speeds of [[39, 40], [40, 80, 81, 39, 40, 80], [20, 20, 20], [90, 20, 90, 20]]) {
    const points = rows(speeds);
    draw(points);
    assert.ok(layers.length <= 3);
    assert.deepStrictEqual(actualEdges(), expectedEdges(points));
}
assert.strictEqual(draw(rows([20, 20, 20])).length, 1);
assert.strictEqual(layers[0].coords.length, 1, 'same-band edges remain one run');
assert.strictEqual(layers[0].coords[0].length, 3);

// A long trip alternating bands would previously allocate one layer per run.
const alternating = rows(Array.from({ length: 10000 }, (_, i) => [20, 60, 90][i % 3]));
assert.strictEqual(draw(alternating).length, 3);
assert.deepStrictEqual(actualEdges(), expectedEdges(alternating));
assert.deepStrictEqual(layers.map(l => l.color), [TRIPS.colors.speedYellow, TRIPS.colors.speedRed, TRIPS.colors.speedGreen]);

// Missing, nonnumeric and nonfinite speed values retain the whole flat route.
for (const missing of [undefined, null, '40', NaN, Infinity]) {
    const points = rows([20, 60, 90, missing]);
    assert.strictEqual(draw(points).length, 1);
    assert.strictEqual(layers[0].color, TRIPS.colors.brand);
    assert.strictEqual(JSON.stringify(layers[0].coords), JSON.stringify(points.map(p => [p.la, p.lo])));
}

console.log('trips_gps_harness: ALL CHECKS PASSED');