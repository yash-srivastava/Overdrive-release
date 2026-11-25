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

// A stationary status stream must not churn Leaflet layers or recalculate
// distance. Moving either endpoint must still update the existing layers.
function exerciseRouteLifecycle() {
    const counts = { lines: 0, labels: 0, icons: 0, adds: 0, removes: 0,
        lineMoves: 0, labelMoves: 0, carMoves: 0, iconUpdates: 0, distances: 0, textWrites: 0 };
    let text = '';
    const distance = {
        get textContent() { return text; },
        set textContent(value) { counts.textWrites++; text = value; }
    };
    const wrapper = { style: {}, classList: { add() {} } };
    function marker(point, options, moveCounter) {
        let pos = { lat: point[0], lng: point[1] };
        return {
            options,
            addTo() { counts.adds++; return this; },
            getLatLng() { return pos; },
            setLatLng(next) { counts[moveCounter]++; pos = { lat: next[0], lng: next[1] }; return this; },
            setIcon(icon) { counts.iconUpdates++; this.options.icon = icon; return this; }
        };
    }
    const BYD = { units: { mode: 'km', dist: km => (km / 1.609344).toFixed(1) + ' mi' } };
    const sandbox = {
        BYD, window: { BYD },
        document: {
            getElementById: id => id === 'distanceToCar' ? distance : id === 'carIconWrapper' ? wrapper : null,
            querySelector: () => null
        },
        L: {
            polyline(points, options) {
                counts.lines++;
                return { points, options, addTo() { counts.adds++; return this; },
                    setLatLngs(next) { counts.lineMoves++; this.points = next; return this; } };
            },
            marker(point, options) { counts.labels++; return marker(point, options, 'labelMoves'); },
            divIcon(options) { counts.icons++; return options; }
        },
        clearTimeout() {}
    };
    vm.runInNewContext(src, sandbox, { filename: 'map.js' });
    const map = BYD.map;
    const makeMap = () => ({ removeLayer() { counts.removes++; }, remove() {}, setView() {}, panTo() {} });
    map.map = makeMap();
    map.carMarker = marker([0, 0], {}, 'carMoves');
    map.userPosition = { lat: 1, lng: 2 };
    map.followCar = false;
    const calculate = map.calculateDistance;
    map.calculateDistance = function (...args) { counts.distances++; return calculate.apply(this, args); };
    for (let i = 0; i < 720; i++) map.updateCarPosition(1.01, 2.01, i % 360, 0);
    assert.deepStrictEqual(counts, { lines: 1, labels: 1, icons: 1, adds: 2, removes: 0,
        lineMoves: 0, labelMoves: 0, carMoves: 1, iconUpdates: 0, distances: 1, textWrites: 1 });
    assert.strictEqual(wrapper.style.transform, 'rotate(359deg)');
    const line = map.routeLine, label = map.distanceLabel;
    const metricText = text;
    BYD.units.mode = 'mi';
    map.drawRoute();
    assert.notStrictEqual(text, metricText);
    assert.strictEqual(counts.distances, 1);
    assert.strictEqual(counts.iconUpdates, 1);
    assert.strictEqual(counts.lineMoves, 0);
    assert.strictEqual(counts.labelMoves, 0);
    assert.ok(label.options.icon.html.includes(text));
    map.updateCarPosition(1.02, 2.03, 42, 0);
    assert.strictEqual(map.routeLine, line);
    assert.strictEqual(map.distanceLabel, label);
    assert.deepStrictEqual(JSON.parse(JSON.stringify(line.points)), [[1, 2], [1.02, 2.03]]);
    assert.deepStrictEqual(label.getLatLng(), { lat: (1 + 1.02) / 2, lng: (2 + 2.03) / 2 });
    assert.strictEqual(counts.lineMoves, 1);
    assert.strictEqual(counts.labelMoves, 1);
    assert.strictEqual(counts.distances, 2);
    assert.strictEqual(counts.carMoves, 2);
    // User-only movement follows the same update path.
    map.userPosition = { lat: 1.03, lng: 2.04 };
    map.drawRoute();
    assert.strictEqual(counts.lineMoves, 2);
    assert.strictEqual(counts.labelMoves, 2);
    assert.strictEqual(counts.distances, 3);
    assert.deepStrictEqual(JSON.parse(JSON.stringify(line.points)), [[1.03, 2.04], [1.02, 2.03]]);
    // Same rounded text after a tiny movement should not rebuild the icon.
    const iconsBefore = counts.icons, textBefore = text;
    map.updateCarPosition(1.020000001, 2.03, 42, 0);
    assert.strictEqual(text, textBefore);
    assert.strictEqual(counts.icons, iconsBefore);
    assert.strictEqual(counts.lineMoves, 3);
    // Invalid position retains existing route (existing behavior).
    map.carPosition = { lat: 0, lng: 0 };
    map.drawRoute();
    assert.strictEqual(counts.lineMoves, 3);
    map.carPosition = { lat: 1.04, lng: 2.05 };
    map.drawRoute();
    assert.strictEqual(counts.lineMoves, 4);
    assert.strictEqual(counts.removes, 0);
    // Replacing the external distance element must still paint the current text.
    text = '';
    map.drawRoute();
    assert.ok(text);
    map.destroy();
    assert.strictEqual(map.routeLine, null);
    assert.strictEqual(map.distanceLabel, null);
    assert.strictEqual(map._routeState, null);
    map.map = makeMap();
    map.drawRoute();
    assert.notStrictEqual(map.routeLine, line);
    assert.notStrictEqual(map.distanceLabel, label);
    assert.strictEqual(counts.lines, 2);
    assert.strictEqual(counts.labels, 2);
    console.log('map_position_harness: route lifecycle and 720 stationary polls passed');
}
exerciseRouteLifecycle();
