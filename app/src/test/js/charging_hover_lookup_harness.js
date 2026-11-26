'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/charging.js'), 'utf8'), context);
const charging = context.CHARGING;

// Original strict first-index nearest-sample loop.
function baseline(g, samples, px) {
    let best = -1, bestDx = 1e9;
    for (let i = 0; i < samples.length; i++) {
        if (samples[i] == null || samples[i].t == null) continue;
        const sx = g.pad.l + ((samples[i].t - g.t0) / g.tSpan) * g.plotW;
        const dx = Math.abs(sx - px);
        if (dx < bestDx) { bestDx = dx; best = i; }
    }
    return best;
}
function geom(t0 = 0, tSpan = 100, plotW = 600, left = 38) {
    return { t0, tSpan, plotW, pad: { l: left } };
}
let comparisons = 0;
function compare(g, samples, positions) {
    for (const px of positions) {
        assert.strictEqual(charging._nearestHoverSample(g, samples, px),
            baseline(g, samples, px), 'pointer ' + px);
        comparisons++;
    }
}
const positions = [-1e10, -100, 0, 38, 41, 44, 100, 300, 638, 700, 1e10, NaN, Infinity, -Infinity];
compare(geom(), [], positions);
compare(geom(), [{t: 1}], positions);
compare(geom(), [0, 0, 1, 1, 2, 100, 100].map(t => ({t})), positions);
compare(geom(), [{t: 3}, null, {t: 1}, {}, {t: NaN}, {t: Infinity}, {t: 2}], positions);
compare(geom(), [{t: 50}, {t: 1}, {t: 10}], positions);
compare(geom(0, 100, -600), [0, 10, 100].map(t => ({t})), positions);
compare(geom(0, 100, 0), [0, 10, 100].map(t => ({t})), positions);
compare(geom(0, 0), [0, 10, 100].map(t => ({t})), positions);
// Distinct projected coordinates can tie after floating-point subtraction.
const tiny = [0, 1e-20, 2e-20, 3e-20].map(t => ({t}));
compare(geom(0, 1, 1, 0), tiny, [-1, 1, 0, 1e-20, 1.5e-20]);
// Seeded series with duplicate timestamps, varied geometry and pointer positions.
let seed = 12345;
function rand() { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; }
for (let trial = 0; trial < 120; trial++) {
    let t = 1700000000000;
    const samples = Array.from({length: 1 + Math.floor(rand() * 500)}, () => {
        t += Math.floor(rand() * 4) * 1000;
        return {t};
    });
    const g = geom(samples[0].t, (t - samples[0].t) || 1, 10 + rand() * 2000);
    const xs = Array.from({length: 80}, () => (rand() * 1.4 - 0.2) * g.plotW + g.pad.l);
    // Exact sample pixels and midpoints exercise the first-index tie rule.
    const project = p => g.pad.l + ((p.t - g.t0) / g.tSpan) * g.plotW;
    for (let i = 0; i < Math.min(samples.length - 1, 20); i++) {
        xs.push(project(samples[i]), (project(samples[i]) + project(samples[i + 1])) / 2);
    }
    compare(g, samples, xs);
}

let reads = 0;
const many = Array.from({length: 10000}, (_, i) => ({get t() { reads++; return i; }}));
const g = geom(0, 9999);
charging._nearestHoverSample(g, many, 300); // one-time validation
reads = 0;
const expected = baseline(g, many, 300);
const originalReads = reads;
reads = 0;
assert.strictEqual(charging._nearestHoverSample(g, many, 300), expected);
const warmReads = reads;
assert.strictEqual(originalReads, 20000); // null check plus projection
assert(warmReads <= 30, 'warm lookup should be logarithmic: ' + warmReads);

// The actual pointer handlers must use fresh geometry after replacement.
function canvas() {
    return { listeners: {}, addEventListener(name, fn) { this.listeners[name] = fn; },
        getBoundingClientRect() { return {left: 20}; } };
}
charging._hoverLayoutStale = () => false;
charging._drawSocHover = () => {};
charging._drawDetailHover = () => {};
const soc = canvas(), detail = canvas();
const list = [0, 25, 50, 100].map(t => ({t}));
charging._socGeom = Object.assign(geom(), {canvas: soc, list});
charging._setupSocHover(soc);
charging._registerDetailHover(detail, Object.assign(geom(), {samples: list}));
for (const px of [38, 300, 638]) {
    soc.listeners.mousemove({clientX: px + 20});
    detail.listeners.mousemove({clientX: px + 20});
    assert.strictEqual(charging._socHoverIdx, baseline(charging._socGeom, list, px));
    assert.strictEqual(detail._chgHoverIdx, baseline(detail._chgHoverSpec, list, px));
}
const replacement = [100, 5, 25, 0].map(t => ({t}));
charging._socGeom = Object.assign(geom(0, 100, 300), {canvas: soc, list: replacement});
charging._registerDetailHover(detail, Object.assign(geom(0, 100, 300), {samples: replacement}));
soc.listeners.touchmove({touches: [{clientX: 58}], preventDefault() {}});
detail.listeners.touchmove({touches: [{clientX: 58}], preventDefault() {}});
assert.strictEqual(charging._socHoverIdx, 3);
assert.strictEqual(detail._chgHoverIdx, 3);
console.log('charging hover: ' + comparisons + ' baseline comparisons; timestamp reads ' +
    originalReads + ' -> ' + warmReads + '; mouse/touch geometry replacement passed');
