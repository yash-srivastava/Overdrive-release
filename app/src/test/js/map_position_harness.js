'use strict';

// Run with: node app/src/test/js/map_position_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/map.js'), 'utf8');

function exercise(lat, lng, valid) {
    const calls = [];
    const distance = { textContent: '' };
    const record = (name, value) => calls.push([name, JSON.parse(JSON.stringify(value))]);
    const layer = () => ({ addTo() { return this; } });
    const BYD = {
        i18n: { t: key => key },
        utils: { toast: text => record('toast', text) }
    };
    const sandbox = {
        BYD,
        window: { BYD, open: url => record('open', url) },
        document: { getElementById: id => id === 'distanceToCar' ? distance : null },
        L: {
            polyline: points => { record('line', points); return layer(); },
            marker: point => { record('label', point); return layer(); },
            divIcon: options => options
        },
        clearTimeout() {}
    };
    vm.runInNewContext(src, sandbox, { filename: 'map.js' });
    const map = BYD.map;
    map.carPosition = { lat, lng };
    map.userPosition = { lat: 1, lng: 2 };
    map.map = {
        setView: point => record('view', point),
        panTo: point => record('pan', point),
        fitBounds: points => record('bounds', points)
    };

    map.drawRoute();
    map.fitBounds();
    map.centerOnCar();
    map.resumeFollow();
    map.openDirections();

    if (valid) {
        const point = [lat, lng];
        assert.deepStrictEqual(calls, [
            ['line', [[1, 2], point]],
            ['label', [(1 + lat) / 2, (2 + lng) / 2]],
            ['bounds', [point, [1, 2]]],
            ['view', point],
            ['pan', point],
            ['open', `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}&travelmode=driving`]
        ]);
        assert.ok(distance.textContent, 'distance should be displayed');
    } else {
        assert.deepStrictEqual(calls, [
            ['view', [1, 2]],
            ['toast', 'map.car_location_unavailable']
        ]);
        assert.strictEqual(distance.textContent, '');
    }
}

const cases = [
    [0, 30, true], [0, -30, true], [45, 0, true], [-45, -30, true],
    [0, 0, false], [null, 30, false], [45, undefined, false],
    [NaN, 30, false], [45, Infinity, false], [91, 30, false],
    [45, -181, false], ['0', 30, false]
];
cases.forEach(args => exercise(...args));
console.log('map_position_harness: ' + cases.length + ' scenarios passed');
