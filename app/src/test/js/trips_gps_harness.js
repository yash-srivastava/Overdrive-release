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

console.log('trips_gps_harness: ALL CHECKS PASSED');