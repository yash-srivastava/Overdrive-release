'use strict';

// Run with: node app/src/test/js/search_index_cache_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web/shared/search.js'), 'utf8');
const key = 'overdrive.searchIndex.v1';
const pages = [{ href: 'trips.html', label: 'Trips' }, { href: 'charging.html', label: 'Charging' }];

function browser(storage, version = '1.0', nav = pages) {
    const requests = [];
    let writes = 0;
    const setting = {
        getAttribute: name => name === 'data-i18n' ? 'trip.example' : null,
        textContent: 'Example setting', parentNode: null
    };
    const document = {
        readyState: 'loading', addEventListener() {},
        getElementById: id => id === 'appVersion' ? { textContent: version } : null,
        querySelector: selector => selector === '.setting-name[data-i18n]' ? setting : null,
        querySelectorAll: selector => selector === '.setting-name[data-i18n]' ? [setting] : []
    };
    const window = {
        location: { pathname: '/trips.html' },
        OverdriveAppShell: { getPages: () => nav },
        localStorage: {
            getItem: k => storage[k] || null,
            setItem(k, value) { writes++; storage[k] = value; }
        }
    };
    vm.runInNewContext(source.replace('window.OverdriveSearch = {',
        'window.cacheTest = { ensureIndex: ensureIndex, harvestLivePage: harvestLivePage };\n    window.OverdriveSearch = {'), {
        window, document,
        XMLHttpRequest: function () {
            this.open = (_, url) => { this.url = url; };
            this.send = () => requests.push(this);
        },
        DOMParser: function () {
            this.parseFromString = text => {
                if (text === 'parse-error') throw new Error('parser failure');
                const item = Object.assign({}, setting, { textContent: text });
                return { querySelectorAll: selector => selector === '.setting-name[data-i18n]' ? [item] : [] };
            };
        }
    });
    return {
        requests, ensure: window.cacheTest.ensureIndex, harvest: window.cacheTest.harvestLivePage,
        setVersion: value => { version = value; }, writes: () => writes,
        respond(i, status = 200, text = 'Setting') {
            const req = requests[i];
            req.status = status; req.responseText = text; req.onload();
        }
    };
}

// A successful crawl persists all pages and their settings.
{
    const storage = {}, b = browser(storage);
    let entries;
    b.ensure(value => { entries = value; });
    b.respond(0); b.respond(1);
    assert.strictEqual(entries.length, 4);
    assert.strictEqual(JSON.parse(storage[key]).entries.length, 4);
    assert.strictEqual(b.writes(), 1);
    const fresh = browser(storage);
    fresh.ensure(value => { assert.strictEqual(value.length, 4); });
    assert.strictEqual(fresh.requests.length, 0);
    fresh.harvest();
    assert.strictEqual(fresh.writes(), 1);
}

// Failed pages still release the worker and return partial results, but never
// persist them, including when the live page is harvested later.
for (const fail of [
    b => b.requests[1].ontimeout(),
    b => b.requests[1].onerror(),
    b => b.requests[1].onabort(),
    b => b.respond(1, 503),
    b => b.respond(1, 200, ''),
    b => b.respond(1, 200, 'parse-error')
]) {
    const storage = {}, b = browser(storage);
    let entries, waiterCalls = 0;
    b.ensure(value => { entries = value; });
    b.ensure(() => { waiterCalls++; });
    b.respond(0); fail(b);
    assert.strictEqual(entries.length, 3);
    assert.strictEqual(waiterCalls, 1);
    assert.strictEqual(storage[key], undefined, 'partial crawl must not be persisted');
    b.harvest();
    assert.strictEqual(b.writes(), 0);
    const next = browser(storage);
    next.ensure(null);
    assert.strictEqual(next.requests.length, 2, 'next document must retry the crawl');
    next.respond(0); next.respond(1);
    assert.strictEqual(JSON.parse(storage[key]).entries.length, 4);
}

// Harvesting an old-version cache during a failed rebuild cannot promote it
// to the current version or replace it on disk with incomplete results.
{
    const storage = {}, old = browser(storage, 'old');
    old.ensure(null); old.respond(0); old.respond(1);
    const saved = storage[key], b = browser(storage);
    b.harvest();
    assert.strictEqual(storage[key], saved);
    b.respond(0); b.requests[1].ontimeout(); b.harvest();
    assert.strictEqual(storage[key], saved);
    const next = browser(storage);
    next.ensure(null);
    assert.strictEqual(next.requests.length, 2);
}

// A completed crawl before /status returns may be saved by a later harvest.
{
    const storage = {}, b = browser(storage, '');
    b.ensure(null); b.respond(0); b.respond(1);
    assert.strictEqual(storage[key], undefined);
    b.setVersion('1.0'); b.harvest();
    assert.strictEqual(JSON.parse(storage[key]).entries.length, 4);
}

// An unavailable navigation catalogue must not produce an empty valid cache.
{
    const storage = {}, b = browser(storage, '1.0', []);
    b.ensure(value => { assert.strictEqual(value.length, 0); });
    assert.strictEqual(storage[key], undefined);
}

// Schema 2 caches may contain partial results and must be rebuilt.
{
    const storage = { [key]: JSON.stringify({ ver: '2:1.0', entries: pages }) };
    const b = browser(storage);
    b.ensure(null);
    assert.strictEqual(b.requests.length, 2);
}
// A failed worker must release its slot so later pages are still crawled.
{
    const storage = {}, nav = Array.from({ length: 6 }, (_, i) => ({ href: i + '.html', label: 'Page ' + i }));
    const b = browser(storage, '1.0', nav);
    let entries;
    b.ensure(value => { entries = value; });
    assert.strictEqual(b.requests.length, 4);
    b.requests[0].ontimeout();
    assert.strictEqual(b.requests.length, 5);
    b.respond(0); // A late response must not release the same slot again.
    assert.strictEqual(b.requests.length, 5);
    b.respond(1);
    assert.strictEqual(b.requests.length, 6);
    for (let i = 2; i < 6; i++) b.respond(i);
    assert.strictEqual(entries.length, 11);
    assert.strictEqual(storage[key], undefined);
}
console.log('search_index_cache_harness: 12 scenarios passed');
