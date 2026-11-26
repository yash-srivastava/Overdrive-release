'use strict';

// Run with: node app/src/test/js/app_collapse_animation_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/app-collapse.js'), 'utf8');

function fixture() {
    let now = 0;
    let nextId = 0;
    const timers = new Map();
    const saved = new Map();
    function element() {
        const attrs = new Map();
        const classes = new Set();
        const handlers = {};
        return {
            style: {}, scrollHeight: 200, offsetHeight: 200,
            classList: {
                add: name => classes.add(name),
                remove: name => classes.delete(name),
                contains: name => classes.has(name)
            },
            setAttribute: (name, value) => attrs.set(name, value),
            getAttribute: name => attrs.has(name) ? attrs.get(name) : null,
            hasAttribute: name => attrs.has(name),
            addEventListener: (type, handler) => { handlers[type] = handler; },
            querySelector: () => null,
            appendChild: () => {},
            click() { handlers.click({ target: this }); },
            keydown(options) {
                let prevented = false;
                handlers.keydown(Object.assign({ target: this, preventDefault() { prevented = true; } }, options));
                return prevented;
            }
        };
    }
    function makeCard(id, closed) {
        const card = element();
        const header = element();
        const body = element();
        card.id = id;
        if (closed) card.setAttribute('data-collapse-default', 'closed');
        card.querySelector = selector => selector === ':scope > .card-header' ? header
            : selector === ':scope > .card-body' ? body : null;
        return { card, header, body, toggle: () => header.click() };
    }
    const a = makeCard('a', true);
    const b = makeCard('b', true);
    const c = makeCard('c', false);
    vm.runInNewContext(source, {
        window: {
            location: { pathname: '/settings.html' },
            localStorage: {
                getItem: key => saved.get(key) || null,
                setItem: (key, value) => saved.set(key, value)
            }
        },
        document: {
            readyState: 'complete',
            querySelectorAll: () => [a.card, b.card, c.card],
            createElement: element,
            addEventListener: () => {}
        },
        setTimeout(fn, delay) {
            const id = ++nextId;
            timers.set(id, { fn, at: now + delay });
            return id;
        },
        clearTimeout: id => timers.delete(id)
    });
    function advance(ms) {
        const target = now + ms;
        for (;;) {
            const due = [...timers.entries()].filter(([, t]) => t.at <= target)
                .sort((a, b) => a[1].at - b[1].at)[0];
            if (!due) break;
            now = due[1].at;
            timers.delete(due[0]);
            due[1].fn();
        }
        now = target;
    }
    return { a, b, c, advance, timers, saved };
}

// A cleanup from the first opening must not interrupt the second opening.
{
    const { a, advance, timers } = fixture();
    a.toggle();
    advance(100);
    a.toggle();
    advance(100);
    a.toggle();
    advance(120); // First opening's original deadline.
    assert.strictEqual(a.body.style.maxHeight, '264px');
    advance(199);
    assert.strictEqual(a.body.style.maxHeight, '264px');
    advance(1);
    assert.strictEqual(a.body.style.maxHeight, 'none');
    assert.strictEqual(timers.size, 0);
}

// Closing cancels cleanup; collapsed state stays intact.
{
    const { a, advance, timers, saved } = fixture();
    a.toggle();
    advance(50);
    a.toggle();
    assert.strictEqual(timers.size, 0);
    advance(500);
    assert.strictEqual(a.body.style.maxHeight, '0px');
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'false');
    assert.strictEqual(saved.get('ot-card-collapsed:settings.html:a'), '1');
}

// Timers belong to individual cards, not the whole page.
{
    const { a, b, advance, timers } = fixture();
    a.toggle();
    advance(50);
    b.toggle();
    advance(50);
    a.toggle();
    advance(270);
    assert.strictEqual(b.body.style.maxHeight, 'none');
    assert.strictEqual(a.body.style.maxHeight, '0px');
    assert.strictEqual(timers.size, 0);
}

// Ordinary opening and initial state still release the height for dynamic content.
{
    const { a, c, advance, timers, saved } = fixture();
    assert.strictEqual(c.body.style.maxHeight, 'none');
    assert.strictEqual(a.body.style.maxHeight, '0px');
    assert.strictEqual(timers.size, 0);
    a.toggle();
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'true');
    advance(319);
    assert.strictEqual(a.body.style.maxHeight, '264px');
    advance(1);
    assert.strictEqual(a.body.style.maxHeight, 'none');
    assert.strictEqual(saved.get('ot-card-collapsed:settings.html:a'), '0');
}
// A held activation key must not repeatedly toggle or restart the animation.
for (const key of [{ key: 'Enter' }, { key: ' ' }, { keyCode: 13 }, { keyCode: 32 }]) {
    const { a, advance, timers, saved } = fixture();
    assert.strictEqual(a.header.keydown(key), true);
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'true');
    advance(100);
    for (let i = 0; i < 3; i++) {
        assert.strictEqual(a.header.keydown({ ...key, repeat: true }), true,
            'repeated activation must still prevent default scrolling');
        assert.strictEqual(a.header.getAttribute('aria-expanded'), 'true',
            'holding an activation key must not toggle again');
        assert.strictEqual(saved.get('ot-card-collapsed:settings.html:a'), '0');
        assert.strictEqual(timers.size, 1);
    }
    advance(220);
    assert.strictEqual(a.body.style.maxHeight, 'none', 'original expansion deadline is preserved');
    assert.strictEqual(a.header.keydown(key), true, 'a separate press still toggles');
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'false');
    assert.strictEqual(saved.get('ot-card-collapsed:settings.html:a'), '1');
    assert.strictEqual(a.header.keydown({ ...key, repeat: true }), true);
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'false');
    assert.strictEqual(timers.size, 0);
}

// Child controls and unrelated keys must keep their native behavior.
{
    const { a, timers, saved } = fixture();
    assert.strictEqual(a.header.keydown({ key: ' ', target: {}, repeat: true }), false);
    assert.strictEqual(a.header.keydown({ key: 'Enter', target: {} }), false);
    assert.strictEqual(a.header.keydown({ key: 'Tab', repeat: true }), false);
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'false');
    assert.strictEqual(saved.size, 0);
    assert.strictEqual(timers.size, 0);
    a.toggle();
    assert.strictEqual(a.header.getAttribute('aria-expanded'), 'true');
}
console.log('app_collapse_animation_harness: 9 scenarios passed');
