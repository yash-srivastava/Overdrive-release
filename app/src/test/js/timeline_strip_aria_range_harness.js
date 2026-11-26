// Run with node app/src/test/js/timeline_strip_aria_range_harness.js.
// Exercise the actual strip controller without drawing pixels or opening recordings.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/timeline-strip.js'), 'utf8');
const BASE = Date.UTC(2026, 0, 1, 12);

function fixture() {
    let now = 0, next = 0;
    const frames = new Map(), timers = new Map();
    const attrs = new Map(), writes = [];
    const canvas = {
        style: {}, getContext: () => null,
        setAttribute(name, value) { attrs.set(name, String(value)); writes.push(name); },
        getAttribute(name) { return attrs.has(name) ? attrs.get(name) : null; },
        addEventListener() {}, removeEventListener() {}
    };
    const host = {
        style: {}, clientWidth: 600, clientHeight: 88,
        appendChild() {}, insertBefore(el) { el.parentNode = this; },
        removeChild(el) { el.parentNode = null; }, addEventListener() {}, removeEventListener() {}
    };
    const window = { addEventListener() {}, removeEventListener() {} };
    class FakeDate extends Date { static now() { return BASE; } }
    vm.runInNewContext(source, {
        window, Date: FakeDate, Intl, performance: { now: () => now },
        document: { createElement: () => canvas },
        getComputedStyle: () => ({ position: 'relative', getPropertyValue: () => '' }),
        requestAnimationFrame(fn) { const id = ++next; frames.set(id, fn); return id; },
        cancelAnimationFrame(id) { frames.delete(id); },
        setTimeout(fn, delay) { const id = ++next; timers.set(id, { fn, at: now + delay }); return id; },
        clearTimeout(id) { timers.delete(id); }
    });
    const strip = window.BYD.tlstrip.create(host, { tz: 'UTC', reducedMotion: true });
    function tick(ms) {
        now += ms;
        for (const [id, timer] of [...timers]) {
            if (timer.at <= now && timers.delete(id)) timer.fn();
        }
        const queued = [...frames.values()];
        frames.clear();
        queued.forEach(fn => fn());
    }
    function data(lo, hi, live = false) {
        strip.setData({ minMs: BASE + lo, maxMs: BASE + hi,
            now: BASE + hi + (live ? 0 : 10000) });
        tick(0);
    }
    return { strip, tick, data, writes, get: name => attrs.get('aria-value' + name) };
}

{
    const f = fixture();
    f.data(-10000, 10000);
    assert.strictEqual(f.get('max'), '20');
    assert.strictEqual(f.get('now'), '10');
    const text = f.get('text');
    f.writes.length = 0;
    f.data(-10000, 30000);
    assert.strictEqual(f.get('max'), '40', 'expanded bounds must update at the same selected time');
    assert.strictEqual(f.get('text'), text);
    assert.deepStrictEqual(f.writes, ['aria-valuemax']);
    f.data(-20000, 20000);
    assert.strictEqual(f.get('now'), '20', 'changed range origin must update relative position');
    assert.strictEqual(f.get('max'), '40');
    assert.strictEqual(f.get('text'), text);
    f.writes.length = 0;
    f.data(-20000, 20000);
    assert.deepStrictEqual(f.writes, [], 'unchanged attributes should not be rewritten');
    f.strip.destroy();
}
{
    const f = fixture();
    f.data(-10000, 10000, true);
    const text = f.get('text');
    f.writes.length = 0;
    f.tick(1000);
    assert.strictEqual(f.get('max'), '21', 'live bound must advance while selected time stays fixed');
    assert.strictEqual(f.get('text'), text);
    assert.deepStrictEqual(f.writes, ['aria-valuemax']);
    f.strip.destroy();
}
{
    const f = fixture();
    f.data(-10000, 10000);
    const text = f.get('text');
    f.strip.glideTo(BASE + 600);
    f.tick(0);
    assert.strictEqual(f.get('now'), '10', 'retain existing one-second throttle');
    f.tick(1000);
    assert.strictEqual(f.get('now'), '11', 'rounded numeric position can change within one spoken second');
    assert.strictEqual(f.get('text'), text);
    f.strip.glideTo(BASE + 2000);
    f.tick(1000);
    assert.strictEqual(f.get('now'), '12');
    assert.notStrictEqual(f.get('text'), text, 'ordinary spoken time updates remain intact');
    f.strip.destroy();
}
console.log('timeline strip ARIA range: all three scenarios passed');
