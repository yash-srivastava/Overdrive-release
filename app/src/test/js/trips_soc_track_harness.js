'use strict';

// Run with: node app/src/test/js/trips_soc_track_harness.js
//
// The trip timeline's SoC line, slider and tooltip must show the SoC the car recorded
// per row ('so'), not a straight line from start to end SoC: a PHEV that drained
// 100 → 70 and then held 70 in HEV mode used to read as a slow drain over the whole trip.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const tripsPath = path.resolve(__dirname, '../../main/assets/web/shared/trips.js');
const src = fs.readFileSync(tripsPath, 'utf8');

const elements = {};
function element(id) {
    if (!elements[id]) {
        // The slider re-renders #timelineChart, so that one is a recording canvas.
        elements[id] = id === 'timelineChart' ? fakeCanvas() : { id: id, textContent: '', style: {} };
    }
    return elements[id];
}

const sandbox = {
    window: { devicePixelRatio: 1 },
    document: {
        getElementById: function (id) { return element(id); },
        addEventListener: function () {}
    },
    console: console,
    setTimeout: function () {},
    clearTimeout: function () {},
    BYD: {
        i18n: { t: function (k) { return k; } },
        units: {
            minuteSuffix: function () { return 'm'; },
            speedVal: function (v) { return v; },
            speedLabel: function () { return 'km/h'; }
        },
        theme: {}
    },
    L: undefined
};

vm.createContext(sandbox);
vm.runInContext(src + '\n;this.__TRIPS = TRIPS;', sandbox, { filename: 'trips.js' });
const TRIPS = sandbox.__TRIPS;

// A canvas whose 2D context records the dashed (SoC) path.
function fakeCanvas() {
    const calls = [];
    let dash = [];
    let path = [];
    const ctx = {
        scale: function () {}, clearRect: function () {}, fill: function () {},
        fillText: function (text, x, y) { calls.push({ op: 'text', text: String(text), y: y }); },
        beginPath: function () { path = []; },
        closePath: function () {},
        arc: function () {},
        moveTo: function (x, y) { path.push({ op: 'move', x: x, y: y }); },
        lineTo: function (x, y) { path.push({ op: 'line', x: x, y: y }); },
        quadraticCurveTo: function () {}, arcTo: function () {}, rect: function () {},
        setLineDash: function (d) { dash = d; },
        stroke: function () { calls.push({ op: 'stroke', dash: dash.slice(), path: path.slice() }); }
    };
    return {
        calls: calls,
        style: {},
        getBoundingClientRect: function () { return { width: 400, height: 160, left: 0 }; },
        getContext: function () { return ctx; }
    };
}

function socStrokes(canvas) {
    return canvas.calls.filter(c => c.op === 'stroke' && c.dash.length === 2 && c.dash[0] === 6);
}

function rows(socs, opts) {
    opts = opts || {};
    return socs.map((so, i) => {
        const r = { t: 1000000 + i * 60000, s: 50, a: 10, b: 0 };
        if (so !== undefined) r.so = so;
        return r;
    });
}

// 1. Trips recorded before rows carried SoC: no track, no line, legend hidden, slider '--'.
const legacy = rows([undefined, undefined, undefined]);
TRIPS.currentTripData = { socStart: 100, socEnd: 65 };
assert.strictEqual(TRIPS._socTrackFor(legacy), null);
let canvas = fakeCanvas();
TRIPS.renderTimeline(canvas, legacy);
assert.strictEqual(socStrokes(canvas).length, 0, 'legacy trip must not draw a made-up SoC line');
assert.strictEqual(element('tlLegendSoc').style.display, 'none');
TRIPS.telemetryCache = legacy;
TRIPS.updateSliderDisplay(1);
assert.strictEqual(element('sliderSoc').textContent, '--');

// 2. PHEV: EV drain 100 → 70, then HEV holds 70 for the rest of the trip.
const socs = [100, 90, 80, 70, 70, 70, 70, 70, 70, 70];
const hold = rows(socs);
const track = TRIPS._socTrackFor(hold);
assert.ok(track);
assert.deepStrictEqual(Array.from(track.at), socs);
assert.strictEqual(track.first, 100);
assert.strictEqual(track.last, 70);
assert.strictEqual(track.min, 70);
assert.strictEqual(track.max, 100);

canvas = fakeCanvas();
TRIPS.renderTimeline(canvas, hold);
const strokes = socStrokes(canvas);
assert.strictEqual(strokes.length, 1, 'one SoC line');
const pts = strokes[0].path;
assert.strictEqual(pts.length, socs.length);
assert.strictEqual(pts[0].op, 'move');
assert.ok(pts.slice(1).every(p => p.op === 'line'));
// Held part is flat; the drain part falls.
const heldY = pts.slice(3).map(p => p.y);
assert.ok(heldY.every(y => Math.abs(y - heldY[0]) < 1e-9), 'held SoC must draw flat');
assert.ok(pts[0].y < pts[3].y, '100% sits above 70%');
assert.strictEqual(element('tlLegendSoc').style.display, 'flex');
assert.ok(canvas.calls.some(c => c.op === 'text' && c.text === '100.0%'));
assert.ok(canvas.calls.some(c => c.op === 'text' && c.text === '70.0%'));

// Slider and tooltip read the recorded value, not an interpolation (which would say ~83% here).
TRIPS.telemetryCache = hold;
TRIPS.updateSliderDisplay(5);
assert.strictEqual(element('sliderSoc').textContent, '70.0');
canvas = fakeCanvas();
TRIPS.renderTimeline(canvas, hold, 5);
assert.ok(canvas.calls.some(c => c.op === 'text' && c.text === 'SoC: 70.0%'));

// 3. Rows without a reading: held for up to _socHoldMs, then '--' and a break in the line.
const gappy = rows([80, undefined, 0, null, undefined, 78]);
// rows are 60 s apart: row 1 (60 s) and row 2 (120 s) hold 80; rows 3-4 are past the hold.
const gapTrack = TRIPS._socTrackFor(gappy);
assert.strictEqual(gapTrack.at[1], 80);
assert.strictEqual(gapTrack.at[2], 80);
assert.ok(Number.isNaN(gapTrack.at[3]));
assert.ok(Number.isNaN(gapTrack.at[4]));
assert.strictEqual(gapTrack.at[5], 78);
canvas = fakeCanvas();
TRIPS.renderTimeline(canvas, gappy);
const gapPath = socStrokes(canvas)[0].path;
assert.deepStrictEqual(gapPath.map(p => p.op), ['move', 'move'], 'a 5-minute gap breaks the line');

// 4. Rows before the first reading show '--', not a back-filled value.
const lateStart = rows([undefined, 64, 64]);
const lateTrack = TRIPS._socTrackFor(lateStart);
assert.ok(Number.isNaN(lateTrack.at[0]));
assert.strictEqual(lateTrack.at[1], 64);

// 5. The memo follows the sample array, and a detail reset clears it.
assert.strictEqual(TRIPS._socTrackFor(lateStart), lateTrack);
TRIPS._resetDetailTelemetryState();
assert.strictEqual(TRIPS._socTrack, null);

console.log('trips_soc_track_harness: ALL CHECKS PASSED');
