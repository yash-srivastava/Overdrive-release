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

// Exercise the actual Leaflet control handlers and follow-state transitions.
{
    const pans = [];
    const handlers = {};
    const attrs = {};
    const button = {
        style: {},
        setAttribute(name, value) { attrs[name] = value; },
        click() { return dispatch('click'); }
    };
    function dispatch(type, fields = {}) {
        const event = Object.assign({ defaultPrevented: false }, fields);
        if (handlers[type]) handlers[type](event);
        return event;
    }
    const BYD = { i18n: { t: () => 'Follow vehicle' } };
    const L = {
        Control: { extend(options) { return class {
            addTo() { options.onAdd(); return this; }
        }; } },
        DomUtil: { create(tag) { return tag === 'a' ? button : {}; } },
        DomEvent: {
            disableClickPropagation() {},
            on(el, type, handler) { handlers[type] = handler; },
            preventDefault(event) { event.defaultPrevented = true; }
        }
    };
    vm.runInNewContext(src, { BYD, window: { BYD }, L, clearTimeout() {} });
    const map = BYD.map;
    map.carPosition = { lat: 1, lng: 2 };
    map.map = { panTo(point) { pans.push(Array.from(point)); } };
    map._addFollowControl();
    assert.strictEqual(attrs.role, 'button');
    assert.strictEqual(attrs['aria-pressed'], 'true');

    // Space activates once and prevents the page from scrolling.
    assert.strictEqual(dispatch('keydown', { key: ' ' }).defaultPrevented, true);
    assert.strictEqual(map.followCar, false);
    assert.strictEqual(attrs['aria-pressed'], 'false');
    assert.strictEqual(pans.length, 0);
    assert.strictEqual(dispatch('keydown', { key: ' ', repeat: true }).defaultPrevented, true);
    assert.strictEqual(map.followCar, false);

    // A second press resumes following and immediately pans to the car.
    assert.strictEqual(dispatch('keydown', { keyCode: 32 }).defaultPrevented, true);
    assert.strictEqual(map.followCar, true);
    assert.strictEqual(attrs['aria-pressed'], 'true');
    assert.deepStrictEqual(pans, [[1, 2]]);

    // Leave native anchor Enter activation and other keys alone.
    for (const key of ['Enter', 'ArrowLeft', 'Tab']) {
        assert.strictEqual(dispatch('keydown', { key }).defaultPrevented, false);
        assert.strictEqual(map.followCar, true);
    }
    // The existing click path remains responsible for toggling.
    assert.strictEqual(button.click().defaultPrevented, true);
    assert.strictEqual(map.followCar, false);
    assert.strictEqual(attrs['aria-pressed'], 'false');
    assert.strictEqual(button.click().defaultPrevented, true);
    assert.strictEqual(map.followCar, true);
    assert.deepStrictEqual(pans, [[1, 2], [1, 2]]);
    console.log('map_position_harness: follow control keyboard and click checks passed');
}
