// Run: node app/src/test/js/performance_chart_hover_frame_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/performance.js'), 'utf8');

function fixture() {
    const frames = new Map(), timers = new Map();
    const events = {}, windowEvents = {}, canvases = new Map(), paints = [];
    let frameId = 0, timerId = 0, layouts = 0, lineCalls = 0, resets = 0;
    const document = { hidden: false, addEventListener: (name, fn) => { events[name] = fn; },
        getElementById: id => canvases.get(id) };
    const BYD = { i18n: { t: key => key } };
    const window = { BYD, devicePixelRatio: 2,
        addEventListener: (name, fn) => { windowEvents[name] = fn; } };
    vm.runInNewContext(source, { BYD, window, document, console,
        requestAnimationFrame: fn => { const id = frameId++; frames.set(id, fn); return id; },
        cancelAnimationFrame: id => frames.delete(id),
        setTimeout: (fn, delay) => { assert.strictEqual(delay, 1500); const id = timerId++; timers.set(id, fn); return id; },
        clearTimeout: id => timers.delete(id) });
    const perf = BYD.performance;
    const render = perf.renderChartByType.bind(perf);
    perf.renderChartByType = type => paints.push([type, perf.tooltip.visible, perf.tooltip.dataIndex]);
    function canvas(type) {
        const handlers = {};
        const ctx = new Proxy({}, { get: (_, key) => key === 'lineTo' ? () => { lineCalls++; } : () => {} });
        const c = { isConnected: true, rect: { left: 10, top: 20, width: 400, height: 200 },
            getBoundingClientRect() { layouts++; return this.rect; },
            getContext: () => ctx,
            set width(v) { resets++; }, set height(v) { resets++; },
            addEventListener: (name, fn, options) => {
                if (name === 'touchstart' || name === 'touchmove') assert.strictEqual(options.passive, false);
                handlers[name] = fn;
            },
            fire: (name, event) => handlers[name](event), _interactionSetup: true };
        canvases.set(type + 'Chart', c);
        perf.setupChartInteraction(c, type);
        return c;
    }
    function frame() { const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn()); }
    function expire() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); }
    return { perf, document, events, windowEvents, frames, timers, paints, canvas, frame, expire,
        layouts: () => layouts, lineCalls: () => lineCalls, resets: () => resets,
        useRealRender: () => { perf.renderChartByType = render; } };
}
const mouse = (c, x, y = 90) => c.fire('mousemove', { clientX: x, clientY: y });
function touch(c, name, x) {
    let prevented = false;
    c.fire(name, { touches: [{ clientX: x, clientY: 90 }], preventDefault: () => { prevented = true; } });
    assert(prevented);
}
function seed(perf, n = 100) {
    const now = Date.now();
    const rows = Array.from({length: n}, (_, i) => ({ t: now - (n - i) * 1000,
        voltage: 12 + i % 5 / 10, high: 30, low: 20, avg: 25, soc: i % 100, charging: i % 20 < 5 }));
    perf.socData.history = rows;
    perf.batteryHealthData = { voltageHistory: rows, thermalHistory: rows };
    perf.dataUsageData.days = rows;
    for (const key of Object.keys(perf.history)) perf.history[key] = rows.map((_, i) => i);
}

// Every chart type must select exactly what the synchronous handler would at the latest position.
for (const type of ['cpu', 'mem', 'gpu', 'soc', 'voltage', 'thermal', 'dataUsage']) {
    const f = fixture(), reference = fixture();
    seed(f.perf); seed(reference.perf);
    const c = f.canvas(type);
    for (let i = 0; i < 1000; i++) mouse(c, 80 + i % 300);
    assert.strictEqual(f.frames.size, 1);
    assert.strictEqual(f.layouts(), 0);
    assert.strictEqual(f.paints.length, 0);
    f.frame();
    reference.perf.handleChartHover(type, 80 + 999 % 300 - 10, 70, 400, 200);
    assert.deepStrictEqual(f.paints, reference.paints);
    assert.strictEqual(f.layouts(), 1);
    assert.strictEqual(f.frames.size, 0);
    mouse(c, 130); f.frame();
    assert.strictEqual(f.paints.length, 2);
}

// Leaving before a frame must cancel even frame id 0; leaving after paint clears once.
{
    const f = fixture(); seed(f.perf); const c = f.canvas('cpu');
    mouse(c, 150); c.fire('mouseleave'); f.frame();
    assert.strictEqual(f.paints.length, 0);
    mouse(c, 180); f.frame(); c.fire('mouseleave');
    assert.deepStrictEqual(f.paints.map(p => p[1]), [true, false]);
    mouse(c, 200); c.fire('touchcancel'); f.frame();
    assert.strictEqual(f.paints.length, 2);
}

// Touch-end keeps the final sample visible; a new gesture invalidates the old hide timer.
{
    const f = fixture(); seed(f.perf); const c = f.canvas('soc');
    touch(c, 'touchstart', 100); touch(c, 'touchmove', 210); c.fire('touchend');
    f.frame(); assert.strictEqual(f.perf.tooltip.visible, true);
    assert.strictEqual(f.timers.size, 1);
    touch(c, 'touchstart', 230); assert.strictEqual(f.timers.size, 0);
    f.expire(); f.frame(); assert.strictEqual(f.perf.tooltip.visible, true);
    c.fire('touchend'); f.expire(); assert.strictEqual(f.perf.tooltip.visible, false);
    assert.deepStrictEqual(f.paints.map(p => p[1]), [true, true, false]);
}

// Moving to another chart invalidates obsolete work without hiding the new chart.
{
    const f = fixture(); seed(f.perf); const a = f.canvas('cpu'), b = f.canvas('mem');
    mouse(a, 180); mouse(b, 220); a.fire('mouseleave'); f.frame();
    assert.strictEqual(f.paints.length, 1); assert.strictEqual(f.paints[0][0], 'mem');
    a.fire('touchend'); f.expire(); assert.strictEqual(f.perf.tooltip.chartId, 'mem');
    assert.strictEqual(f.paints.length, 1);
}

// Resolve coordinates against the current layout, and skip hidden/detached canvases.
{
    const f = fixture(); seed(f.perf); const c = f.canvas('cpu');
    mouse(c, 180); c.rect.left = 30; f.frame(); assert.strictEqual(f.perf.tooltip.x, 150);
    mouse(c, 180); c.rect.width = 0; f.frame(); assert.strictEqual(f.paints.length, 1);
    c.rect.width = 400; mouse(c, 180); c.isConnected = false; f.frame();
    assert.strictEqual(f.paints.length, 1);
}

// The real lifecycle handlers cancel queued frames and delayed touch hides.
for (const transition of ['visibilitychange', 'pagehide', 'beforeunload', 'ot-tabs:active-changed']) {
    const f = fixture(); seed(f.perf); const c = f.canvas('cpu');
    f.perf.syncViewLifecycle = f.perf.stopPolling = f.perf.resizeCharts = () => {};
    f.perf.setupLifecycleHandlers();
    touch(c, 'touchstart', 100); f.frame(); touch(c, 'touchmove', 200); c.fire('touchend');
    if (transition === 'visibilitychange') f.document.hidden = true;
    (f.events[transition] || f.windowEvents[transition])({detail: {id: 'data'}});
    f.frame(); f.expire(); assert.strictEqual(f.paints.length, 1);
    assert.strictEqual(f.perf.tooltip.visible, false);
    assert.strictEqual(f.timers.size, 0);
}

// Stress the actual voltage renderer, not just the scheduler. The unchanged direct
// handler represents the former per-event rendering path. Canvas calls are mocked.
{
    const old = fixture(), next = fixture();
    for (const f of [old, next]) { seed(f.perf, 10000); f.canvas('voltage'); f.useRealRender(); }
    const c = next.document.getElementById('voltageChart');
    const eventsPerFrame = 20, batches = 10;
    for (let b = 0; b < batches; b++) {
        for (let i = 0; i < eventsPerFrame; i++) {
            const x = 90 + (b * eventsPerFrame + i) % 270;
            old.perf.handleChartHover('voltage', x - 10, 70, 400, 200);
            mouse(c, x);
        }
        next.frame();
        assert.strictEqual(next.perf.tooltip.dataIndex, old.perf.tooltip.dataIndex);
    }
    assert.strictEqual(old.resets(), 400);
    assert.strictEqual(next.resets(), 20);
    assert.strictEqual(old.lineCalls(), next.lineCalls() * eventsPerFrame);
    console.log(`Voltage stress: ${old.resets()/2} -> ${next.resets()/2} renders; ${old.lineCalls()} -> ${next.lineCalls()} lineTo calls`);
}
console.log('Performance chart hover frame harness: all assertions passed');
