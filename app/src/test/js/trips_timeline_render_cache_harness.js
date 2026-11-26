'use strict';
// Run with: node app/src/test/js/trips_timeline_render_cache_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/trips.js'), 'utf8');
let failCreate = false;
let suffix = 'm';
let created = 0;
function canvas(w, h) {
    const c = { w, h, style: {}, copies: 0, lines: 0, texts: [], failCopy: false, resets: 0 };
    let width = 0;
    Object.defineProperty(c, 'width', {
        get: () => width,
        set: v => { width = Math.floor(v); c.resets++; }
    });
    let height = 0;
    Object.defineProperty(c, 'height', {
        get: () => height,
        set: v => { height = Math.floor(v); }
    });
    const ctx = {
        scale() {}, setTransform() {}, clearRect() {}, beginPath() {}, closePath() {},
        moveTo() {}, lineTo() { c.lines++; }, stroke() {}, fill() {}, arc() {},
        quadraticCurveTo() {}, setLineDash() {},
        fillText(text) { c.texts.push(String(text)); },
        drawImage(image) {
            if (c.failCopy) throw new Error('copy failed');
            assert.ok(image.width > 0 && image.height > 0);
            assert.strictEqual(c.width, image.width);
            assert.strictEqual(c.height, image.height);
            c.copies++;
        }
    };
    c.getContext = () => ctx;
    c.getBoundingClientRect = () => ({ width: c.w, height: c.h, left: 0 });
    return c;
}
const visible = canvas(400, 160);
const elements = { timelineChart: visible };
const doc = {
    getElementById(id) { return elements[id] || (elements[id] = { style: {}, textContent: '' }); },
    createElement(tag) {
        assert.strictEqual(tag, 'canvas');
        if (failCreate) throw new Error('unsupported canvas');
        created++;
        return canvas(0, 0);
    },
    addEventListener() {},
    fonts: { status: 'loaded' }
};
const sandbox = {
    window: { devicePixelRatio: 1.25 }, document: doc, console,
    setTimeout() {}, clearTimeout() {},
    BYD: { i18n: { t: k => k }, units: { minuteSuffix: () => suffix, speedVal: v => v, speedLabel: () => 'km/h' } }
};
vm.createContext(sandbox);
vm.runInContext(source + '\nthis.TRIPS = TRIPS;', sandbox);
const trips = sandbox.TRIPS;
function rows(n) {
    return Array.from({ length: n }, (_, i) => ({ t: 1000000 + i * 1000, s: i % 100, a: 10, b: 0, so: 70 }));
}
const samples = rows(10000);
trips.telemetryCache = samples;
trips.renderTimeline(visible, samples);
const base = trips._timelineBase;
assert.ok(base);
assert.strictEqual(base.image.width, 500);
assert.strictEqual(base.image.height, 200);
assert.strictEqual(created, 1);
assert.strictEqual(base.image.copies, 1);
const baseLines = visible.lines;
assert.ok(baseLines >= 39999);
const resets = visible.resets;
for (let i = 0; i < 60; i++) trips.renderTimeline(visible, samples, i);
assert.strictEqual(visible.resets, resets, 'no backing-store reset during cached scrubs');
assert.strictEqual(visible.copies, 60);
assert.ok(visible.lines - baseLines < 600, 'no per-sample paths during cached scrubs');
assert.ok(visible.texts.includes('SoC: 70.0%'));
assert.strictEqual(trips._timelineBase, base);
assert.strictEqual(elements.tlAccelPct.textContent, '100%');

// Mouse/touch hover should draw once and still update slider/HUD.
let before = visible.copies;
visible.onmousemove({ clientX: 205 });
assert.strictEqual(visible.copies - before, 1);
assert.strictEqual(Number(elements.timelineSlider.value), 4999);
assert.strictEqual(elements.sliderSoc.textContent, '70.0');
visible.ontouchstart({ touches: [{ clientX: 60, clientY: 30 }] });
let prevented = false;
before = visible.copies;
visible.ontouchmove({ touches: [{ clientX: 205, clientY: 31 }], preventDefault() { prevented = true; } });
assert.ok(prevented);
assert.strictEqual(visible.copies - before, 1);
visible.ontouchend();
assert.strictEqual(visible._isScrubbing, false);
assert.strictEqual(trips._timelineBase, base);

// Resize, DPR, palette, localized labels, fonts, and new telemetry invalidate.
function rebuild(change) {
    const old = trips._timelineBase;
    change();
    trips.renderTimeline(visible, trips.telemetryCache, 1);
    assert.ok(trips._timelineBase && trips._timelineBase !== old);
    assert.strictEqual(old.image.width, 0);
}
rebuild(() => { visible.w = 420; });
rebuild(() => { sandbox.window.devicePixelRatio = 2; });
rebuild(() => { trips.colors.grid = '#aaa'; });
rebuild(() => { suffix = ' min'; });
rebuild(() => { trips.telemetryCache = rows(30); });
let old = trips._timelineBase;
doc.fonts.status = 'loading';
trips.renderTimeline(visible, trips.telemetryCache, 1);
assert.strictEqual(trips._timelineBase, null);
assert.strictEqual(old.image.width, 0);
doc.fonts.status = 'loaded';
trips.renderTimeline(visible, trips.telemetryCache, 1);
assert.ok(trips._timelineBase);

// Copy errors repaint rather than leaving a blank chart; oversized canvases and
// unavailable detached canvases also keep the original complete rendering path.
old = trips._timelineBase;
visible.failCopy = true;
let lines = visible.lines;
trips.renderTimeline(visible, trips.telemetryCache, 2);
assert.ok(visible.lines - lines >= 90);
assert.strictEqual(old.image.width, 0);
visible.failCopy = false;
visible.w = 2000;
trips.renderTimeline(visible, trips.telemetryCache, 2);
assert.strictEqual(trips._timelineBase, null);
visible.w = 400;
failCreate = true;
lines = visible.lines;
trips.renderTimeline(visible, trips.telemetryCache, 2);
assert.ok(visible.lines - lines >= 90);
assert.strictEqual(trips._timelineBase, null);
failCreate = false;
trips.renderTimeline(visible, trips.telemetryCache, 2);
old = trips._timelineBase;
trips._clearDetailCanvas('timelineChart');
assert.strictEqual(trips._timelineBase, null);
assert.strictEqual(old.image.width, 0);
assert.strictEqual(visible.onmousemove, null);

// No spread-call argument ceiling on long charts (regression for large journals).
const long = rows(150000);
trips.renderTimeline(visible, long, 149999);
assert.ok(trips._timelineBase);
trips.renderTimeline(visible, [], undefined);
assert.strictEqual(trips._timelineBase, null);

// Compare the ordered lookup and nonmonotonic legacy fallback against the old
// strict-less-than scan, including duplicate times and exact midpoint ties.
function oldNearest(data, key, target) {
    let best = 0, diff = Infinity;
    data.forEach((row, i) => {
        const d = Math.abs(row[key] - target);
        if (d < diff) { diff = d; best = i; }
    });
    return best;
}
let seed = 9137;
function rand(n) { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed % n; }
let comparisons = 0;
for (let j = 0; j < 500; j++) {
    let t = 0;
    const data = Array.from({ length: 2 + rand(120) }, (_, i) => {
        t += rand(6);
        return j % 3 === 0 ? { e: t, t: -i } : { t: j % 3 === 1 ? t : rand(200) };
    });
    const key = trips._timelineAxisFor(data).key;
    for (let i = 0; i < 20; i++) {
        const target = rand(500) / 2 - 25;
        assert.strictEqual(trips._closestTimelineSample(data, key, target), oldNearest(data, key, target));
        comparisons++;
    }
}
console.log('trips_timeline_render_cache_harness: 60 cached scrubs, lifecycle/fallback checks and ' + comparisons + ' nearest-sample comparisons passed');
