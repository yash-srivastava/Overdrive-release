'use strict';

// Run with: node app/src/test/js/search_query_activation_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/search.js'), 'utf8');

function browser(cached) {
    const nodes = [], timers = new Map(), requests = [];
    let nextTimer = 0;
    function element() {
        const attrs = {}, listeners = {}, classes = new Set();
        const el = {
            value: '', innerHTML: '', children: [], parentNode: null,
            classList: { add: x => classes.add(x), remove: x => classes.delete(x) },
            appendChild(child) { this.children.push(child); child.parentNode = this; },
            setAttribute(k, v) { attrs[k] = v; },
            getAttribute(k) { return Object.prototype.hasOwnProperty.call(attrs, k) ? attrs[k] : null; },
            hasAttribute(k) { return this.getAttribute(k) !== null; },
            removeAttribute(k) { delete attrs[k]; },
            addEventListener(k, cb) { listeners[k] = cb; },
            fire(k, ev = {}) { listeners[k](Object.assign({ preventDefault() {} }, ev)); },
            querySelectorAll() { return []; },
            focus() {}, select() {}
        };
        nodes.push(el);
        return el;
    }
    const body = element();
    const document = {
        body, activeElement: body, readyState: 'loading', createElement: element,
        addEventListener() {}, getElementById() { return null; },
        documentElement: { contains() { return true; } }
    };
    const pages = [
        { type: 'page', href: 'trips.html', label: 'Trips' },
        { type: 'page', href: 'charging.html', label: 'Charging' }
    ];
    const window = {
        location: { pathname: '/index.html', href: '/index.html' },
        OverdriveAppShell: { getPages: () => pages },
        localStorage: { getItem: () => cached ? JSON.stringify({ ver: '2:', entries: pages }) : null }
    };
    vm.runInNewContext(source, {
        window, document,
        setTimeout(cb, delay) { const id = ++nextTimer; timers.set(id, { cb, delay }); return id; },
        clearTimeout(id) { timers.delete(id); },
        XMLHttpRequest: function () { this.open = () => {}; this.send = () => requests.push(this); }
    });
    window.OverdriveSearch.open();
    const find = cls => nodes.find(n => n.className === cls);
    const input = find('od-search-input'), results = find('od-search-results');
    return {
        window, input, results,
        type(value) { input.value = value; input.fire('input'); },
        enter() { input.fire('keydown', { key: 'Enter' }); },
        clickFirst() { results.fire('click', { target: { getAttribute: () => '0' } }); },
        clear() { find('od-search-clear').fire('click'); },
        flushQuery() {
            for (const [id, timer] of timers) if (timer.delay === 110) {
                timers.delete(id); timer.cb();
            }
        },
        pendingQuery() { return Array.from(timers.values()).some(t => t.delay === 110); },
        finishCrawl() { requests.forEach(r => r.onerror()); }
    };
}

// Pressing Enter before the debounce completes must not navigate to Trips.
{
    const b = browser(true);
    b.type('charging');
    b.enter();
    assert.strictEqual(b.window.location.href, '/index.html');
    b.flushQuery();
    b.enter();
    assert.strictEqual(b.window.location.href, 'charging.html');
}
// Clicking an old row has the same guard; normal clicking still works.
{
    const b = browser(true);
    b.type('charging');
    b.clickFirst();
    assert.strictEqual(b.window.location.href, '/index.html');
    b.flushQuery();
    b.clickFirst();
    assert.strictEqual(b.window.location.href, 'charging.html');
}
// The initial crawl may finish before the new query's debounce fires.
{
    const b = browser(false);
    b.type('charging');
    b.finishCrawl();
    assert.strictEqual(b.results.innerHTML, '');
    b.enter();
    assert.strictEqual(b.window.location.href, '/index.html');
    b.flushQuery();
    b.enter();
    assert.strictEqual(b.window.location.href, 'charging.html');
}
// Closing cancels a pending query. Reopening renders the retained input.
{
    const b = browser(true);
    b.type('charging');
    b.window.OverdriveSearch.close();
    assert.strictEqual(b.pendingQuery(), false);
    b.window.OverdriveSearch.open();
    b.enter();
    assert.strictEqual(b.window.location.href, 'charging.html');
}
// Clear cancels the debounce and immediately restores the default page list.
{
    const b = browser(true);
    b.type('charging');
    b.clear();
    assert.strictEqual(b.pendingQuery(), false);
    b.enter();
    assert.strictEqual(b.window.location.href, 'trips.html');
}
// Case/whitespace changes do not invalidate an equivalent normalized query.
{
    const b = browser(true);
    b.type('charging');
    b.flushQuery();
    b.type(' CHARGING ');
    b.enter();
    assert.strictEqual(b.window.location.href, 'charging.html');
}
console.log('search_query_activation_harness: 6 scenarios passed');
