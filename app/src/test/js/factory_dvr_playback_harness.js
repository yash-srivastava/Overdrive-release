'use strict';

// Run with: node app/src/test/js/factory_dvr_playback_harness.js
// Exercise the actual controller through its rendered Play and Refresh buttons.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const src = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/factory-dvr.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const failure = 'Playback failed or the format is unsupported.';

function element() {
    return {
        children: [], style: {}, listeners: {},
        set textContent(value) { this.text = value; this.children = []; },
        get textContent() { return this.text || ''; },
        appendChild(child) { this.children.push(child); },
        addEventListener(type, fn) { this.listeners[type] = fn; },
        removeAttribute(name) { delete this[name]; },
        scrollIntoView() {},
        click() { this.listeners.click(); }
    };
}

async function setup() {
    const ids = {};
    ['factoryList', 'factoryStatus', 'factoryPlayer', 'factoryPlayerPanel',
        'factoryPlaying', 'factoryRefresh'].forEach(id => { ids[id] = element(); });
    const player = ids.factoryPlayer;
    const pending = [];
    player.pause = player.load = () => {};
    player.play = () => new Promise((resolve, reject) => pending.push({ resolve, reject }));
    const clips = ['a', 'b'].map(id => ({ id, name: id + '.mp4',
        relativePath: id + '.mp4', modified: 0, bytes: 1024 }));
    const BYD = { i18n: { t: key => key } };
    const sandbox = {
        BYD, window: { BYD }, document: {
            getElementById: id => ids[id], createElement: element
        }, BYDAuth: { fetch: async () => ({ ok: true,
            json: async () => ({ found: true, videos: clips }) }) }
    };
    vm.runInNewContext(src, sandbox, { filename: 'factory-dvr.js' });
    BYD.factoryDvr.init();
    await flush();
    const click = index => ids.factoryList.children[index].children[2].children[0].click();
    const status = () => ids.factoryStatus.textContent;
    assert.strictEqual(status(), '2 factory videos');
    return { ids, player, pending, click, status };
}

async function main() {
    // Switching clips can reject the previous play promise after the new clip starts.
    let h = await setup();
    h.click(0); h.click(1);
    h.pending[1].resolve(); h.pending[0].reject(new Error('interrupted'));
    await flush();
    assert.strictEqual(h.status(), '2 factory videos', 'obsolete playback rejection');
    assert.strictEqual(h.ids.factoryPlaying.textContent, 'b.mp4');
    assert.strictEqual(h.player.src, '/api/factory-dvr/file?id=b');

    // Replaying the same clip still supersedes its earlier play attempt.
    h = await setup();
    h.click(0); h.click(0);
    h.pending[0].reject(new Error('interrupted'));
    await flush();
    assert.strictEqual(h.status(), '2 factory videos', 'same-clip obsolete rejection');
    h.pending[1].resolve();

    // Refresh stops playback; its list status must not be overwritten.
    h = await setup();
    h.click(0); h.ids.factoryRefresh.click();
    await flush();
    h.pending[0].reject(new Error('interrupted'));
    await flush();
    assert.strictEqual(h.status(), '2 factory videos', 'rejection after refresh');
    assert.strictEqual(h.ids.factoryPlayerPanel.style.display, 'none');

    // Genuine failure of the current play attempt remains visible.
    h = await setup();
    h.click(0); h.click(1);
    h.pending[1].reject(new Error('unsupported'));
    h.pending[0].resolve();
    await flush();
    assert.strictEqual(h.status(), failure, 'current playback rejection');

    // Native media errors and older browsers without a play promise are unchanged.
    h = await setup();
    h.player.play = () => undefined;
    h.click(0);
    assert.strictEqual(h.status(), '2 factory videos');
    h.player.listeners.error();
    assert.strictEqual(h.status(), failure, 'native media error');
    console.log('factory_dvr_playback_harness: 5 scenarios passed');
}

main().catch(err => { console.error(err); process.exitCode = 1; });
