/* Run with node app/src/test/js/widget_community_pagination_harness.js. */
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/widget-builder/community.js'), 'utf8');

function setup() {
    const requests = [];
    function el(tag, cls, host, html) {
        const node = {
            tag, cls, children: [], attrs: {}, hidden: false,
            classList: { toggle() {} },
            setAttribute(k, v) { this.attrs[k] = v; },
            addEventListener(k, fn) { this[k] = fn; },
            appendChild(child) { this.children.push(child); child.parentNode = this; },
            set innerHTML(v) { this.children = []; this.html = v; },
            get innerHTML() { return this.html; }
        };
        if (html !== undefined) node.innerHTML = html;
        if (host) host.appendChild(node);
        return node;
    }
    function btn(cls, host, html, click) {
        const node = el('button', cls, host, html);
        node.click = click;
        return node;
    }
    const window = { ODW: {}, WidgetBuilder: { util: {
        tr: k => k, esc: x => x, el, btn, icon: () => '',
        api(method, url) {
            assert.strictEqual(method, 'GET');
            assert.ok(url.startsWith('/api/community/widgets/list?'));
            return new Promise(resolve => requests.push({ url, resolve }));
        }
    } } };
    // Painting is not part of pagination; no animation frames need to run.
    vm.runInNewContext(source, { window, requestAnimationFrame: () => 1 });
    const host = el('div', 'host');
    window.WidgetCommunity.mount(host);
    function find(cls, root = host) {
        if (root.cls.split(' ').includes(cls)) return root;
        for (const child of root.children) {
            const found = find(cls, child);
            if (found) return found;
        }
        return null;
    }
    const more = find('wb-cm-more');
    const grid = find('wb-cm-grid');
    const status = find('wb-cm-status');
    return { requests, more, grid, status, find, community: window.WidgetCommunity };
}
function widget(id) { return { id, name: id, widget: { layers: [] } }; }
async function respond(request, items, nextCursor) {
    request.resolve({ ok: true, data: { items, nextCursor } });
    await Promise.resolve();
}
function cursor(request) { return new URL(request.url, 'https://example.test').searchParams.get('cursor'); }

async function main() {
    // A fully filtered first page is not an empty catalogue.
    const a = setup();
    await respond(a.requests[0], [], 'p2');
    assert.strictEqual(a.more.hidden, false, 'empty page must retain Load more');
    assert.strictEqual(a.status.children.length, 0, 'no premature empty message');
    a.more.click();
    assert.strictEqual(cursor(a.requests[1]), 'p2');
    await respond(a.requests[1], [widget('a')], null);
    assert.strictEqual(a.grid.children.length, 1);
    assert.strictEqual(a.more.hidden, true);

    // A page containing only duplicates or unusable rows can still advance.
    const b = setup();
    await respond(b.requests[0], [widget('a')], 'p2');
    b.more.click();
    await respond(b.requests[1], [widget('a'), null], 'p3');
    assert.strictEqual(b.grid.children.length, 1);
    assert.strictEqual(b.more.hidden, false);
    b.more.click();
    assert.strictEqual(cursor(b.requests[2]), 'p3');
    await respond(b.requests[2], [widget('b')], null);
    assert.strictEqual(b.grid.children.length, 2);
    assert.strictEqual(b.more.hidden, true);

    // Retry a failed continuation even when no cards have been rendered yet.
    const c = setup();
    await respond(c.requests[0], [], 'p2');
    c.more.click();
    c.requests[1].resolve({ ok: false, status: 0, data: {} });
    await Promise.resolve();
    c.find('wb-link', c.status).click();
    assert.strictEqual(cursor(c.requests[2]), 'p2');
    await respond(c.requests[2], [widget('a')], null);
    assert.strictEqual(c.grid.children.length, 1);

    // A non-advancing cursor stops rather than offering the same page again.
    const d = setup();
    await respond(d.requests[0], [], 'p2');
    d.more.click();
    await respond(d.requests[1], [], 'p2');
    assert.strictEqual(d.more.hidden, true);
    assert.ok(d.find('empty-state', d.status));

    // A genuine terminal empty result still displays the empty state.
    const e = setup();
    await respond(e.requests[0], [], null);
    assert.strictEqual(e.more.hidden, true);
    assert.ok(e.find('empty-state', e.status));

    // Reset invalidates a pending continuation and starts without a cursor.
    const f = setup();
    await respond(f.requests[0], [], 'p2');
    f.more.click();
    f.community.refresh();
    assert.strictEqual(cursor(f.requests[2]), null);
    await respond(f.requests[2], [widget('new')], null);
    await respond(f.requests[1], [widget('old')], 'p3');
    assert.strictEqual(f.grid.children.length, 1);
    assert.strictEqual(f.grid.children[0].attrs['data-id'], 'new');
    assert.strictEqual(f.more.hidden, true);
    // Deduplicate within the first response, preserving first occurrence and order.
    const g = setup();
    await respond(g.requests[0], [widget('a'), widget('b'), { ...widget('a'), name: 'duplicate' }], 'p2');
    assert.deepStrictEqual(g.grid.children.map(card => card.attrs['data-id']), ['a', 'b']);
    assert.strictEqual(g.find('wb-cm-name', g.grid.children[0]).textContent, 'a');
    assert.strictEqual(g.more.hidden, false);

    // A continuation can repeat IDs from earlier pages and from its own page.
    g.more.click();
    await respond(g.requests[1], [widget('b'), widget('c'), widget('c'), widget('d'), null], 'p3');
    assert.deepStrictEqual(g.grid.children.map(card => card.attrs['data-id']), ['a', 'b', 'c', 'd']);
    assert.strictEqual(g.more.hidden, false);
    g.more.click();
    assert.strictEqual(cursor(g.requests[2]), 'p3');
    await respond(g.requests[2], [widget('d')], null);
    assert.strictEqual(g.grid.children.length, 4);
    assert.strictEqual(g.more.hidden, true);

    // A reset must not retain IDs from the old result set.
    g.community.refresh();
    await respond(g.requests[3], [widget('a'), widget('a')], null);
    assert.deepStrictEqual(g.grid.children.map(card => card.attrs['data-id']), ['a']);

    console.log('9 widget community pagination scenarios passed');
}
main().catch(e => { console.error(e); process.exitCode = 1; });
