'use strict';

// Run with: node app/src/test/js/events_escape_harness.js
// Loads the real shared/events.js in a minimal browser stub and proves the
// run-2 XSS fix: a malicious recording filename can no longer produce live
// markup or break out of onclick JS strings on the events page.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const eventsPath = path.resolve(__dirname, '../../main/assets/web/shared/events.js');
const src = fs.readFileSync(eventsPath, 'utf8');

const sandbox = {
    window: {},
    document: { createElement: () => {
        const el = { _h: '' };
        Object.defineProperty(el, 'textContent', {
            set(v) { el._h = String(v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); },
            get() { return el._h; }
        });
        Object.defineProperty(el, 'innerHTML', { get() { return el._h; }, set(v) { el._h = v; } });
        return el;
    } },
    console,
    BYD: undefined
};
sandbox.window.BYD = {};
sandbox.BYD = sandbox.window.BYD;
vm.createContext(sandbox);
vm.runInContext(src, sandbox, { filename: 'events.js' });

const ev = sandbox.window.BYD.events;
assert.ok(ev, 'BYD.events must load');

// _esc must HTML-escape markup
const esc = ev._esc('"><img src=x onerror=alert(1)>');
assert.ok(!esc.includes('<img'), 'escaped value must not contain live tags: ' + esc);
assert.ok(esc.includes('&lt;'), 'escaped value must contain entities');

// _jsStr must strip JS-string/HTML metacharacters
const js = ev._jsStr("a'b\\c\"d\r\ne<f>&g");
assert.ok(!/[\\'"\r\n<>&]/.test(js), 'jsStr must strip metachars: ' + JSON.stringify(js));

// recordingKey fallback must sanitize a hostile filename
const key = ev.recordingKey({ filename: 'clip"><img src=x onerror=alert(1)>.mp4' });
assert.ok(!/[<>"'&]/.test(key), 'recordingKey fallback must be sanitized: ' + key);
assert.strictEqual(ev.recordingKey({ id: 'abc123' }), 'abc123', 'id passthrough unchanged');

// Simulate the patched card template attribute interpolation with the hostile name
const hostile = 'clip"><img src=x onerror=alert(1)>.mp4';
const attr = 'data-filename="' + ev._esc(hostile) + '"';
assert.ok(!attr.includes('"<img'), 'attribute must not break out: ' + attr);
const text = ev._esc(hostile);
assert.ok(!text.includes('<img'), 'element text must not contain live tag');

// Source-level guard: no raw rec.filename interpolation remains in the file
assert.ok(!/data-filename="' \+ rec\.filename/.test(src), 'raw data-filename gone');
assert.ok(!/>'\s*\+ rec\.filename \+ '<\/span>'/.test(src), 'raw filename text gone');

console.log('events_escape_harness: ALL CHECKS PASSED');
