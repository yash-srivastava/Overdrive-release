'use strict';

// Run from repository root: node app/src/test/js/surround_texture_upload_harness.js
// Deterministic canvas/texture mocks, not a GPU benchmark.
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const shared = 'app/src/main/assets/web/shared/';
const rafs = new Map();
let nextId = 1;
const sandbox = {
    console,
    document: {
        readyState: 'loading', hidden: false,
        addEventListener() {}, removeEventListener() {},
        createElement() {
            const ctx = { draws: 0, clears: 0,
                drawImage() { this.draws++; if (this.fail) throw Error('draw failed'); },
                clearRect() { this.clears++; } };
            return { width: 0, height: 0, style: {}, getContext() { return ctx; } };
        },
        body: { appendChild() {} }
    },
    location: { protocol: 'https:', host: 'example.invalid' },
    VideoDecoder: function() {}, EncodedVideoChunk: function() {},
    WebSocket: { CLOSED: 3 },
    BYD: { i18n: { t: key => key } },
    addEventListener() {}, removeEventListener() {},
    requestAnimationFrame(fn) { const id = nextId++; rafs.set(id, fn); return id; },
    cancelAnimationFrame(id) { rafs.delete(id); }
};
sandbox.window = sandbox;
vm.createContext(sandbox);
for (const file of ['SotaPlayer.js', 'vehicle-control.js']) {
    vm.runInContext(fs.readFileSync(shared + file, 'utf8'), sandbox);
}
const VC = sandbox.VC;
const Player = sandbox.SotaPlayer;
// Use the real paint, close and callback paths; skip network/scene creation.
Player.prototype.start = function() { this.running = true; };
VC.toast = () => {};
VC._createContactShadow = () => {};
VC._setLightsForBowl = () => {};
VC._updateTyreCalloutPositions = () => {};
let uploads = 0, renders = 0;
VC._createSurroundBowl = function() {
    // CanvasTexture initially needs upload; successive marks coalesce.
    this._videoTexture = { dirty: true, set needsUpdate(value) { if (value) this.dirty = true; } };
};
VC.renderer = { render() {
    renders++;
    if (VC._videoTexture.dirty) { uploads++; VC._videoTexture.dirty = false; }
} };
VC.scene = {}; VC.camera = {};
VC._3dViewActive = true;
VC._start3dStream();
const player = VC._sotaPlayer;
assert.strictEqual(typeof player.onCanvasChanged, 'function');
VC.animate();
function refresh(time) {
    const pending = Array.from(rafs.entries());
    for (const [id, fn] of pending) if (rafs.delete(id)) fn(time);
}
function frame(w = 1280, h = 960) {
    return { displayWidth: w, displayHeight: h, closed: 0, close() { this.closed++; } };
}
assert.strictEqual(uploads, 1);
for (let i = 0; i < 60; i++) refresh(i * 17);
assert.strictEqual(uploads, 1, 'unchanged canvas must not upload again');
assert.strictEqual(renders, 61, 'scene animation continues');
let notified = 0;
player.onFrame = () => notified++;
const dropped = frame(), painted = frame();
player.handleFrame(dropped); player.handleFrame(painted);
refresh(1100); refresh(1117);
assert.strictEqual(dropped.closed, 1);
assert.strictEqual(painted.closed, 1);
assert.strictEqual(notified, 1);
assert.strictEqual(uploads, 2, 'only the painted frame dirties the texture');
const resized = frame(640, 480);
player.pendingFrame = resized;
player._paintLatestFrame(); refresh(1134);
assert.strictEqual(player.canvas.width, 640);
assert.strictEqual(uploads, 3);
// Even a failed draw after a resize may have cleared the source canvas.
player.ctx.fail = true;
const failed = frame(320, 240);
player.pendingFrame = failed;
assert.throws(() => player._paintLatestFrame(), /draw failed/);
assert.strictEqual(failed.closed, 1);
refresh(1151);
assert.strictEqual(uploads, 4);
assert.strictEqual(notified, 2, 'failed draws are not successful frame notifications');
player.ctx.fail = false;
player.stop(); refresh(1168);
assert.strictEqual(player.ctx.clears, 1);
assert.strictEqual(uploads, 5, 'an explicit canvas clear must upload');
const retiredCallback = player.onCanvasChanged;
VC._start3dStream(); refresh(1185);
assert.strictEqual(uploads, 6, 'replacement texture gets its initial upload');
retiredCallback(); refresh(1202);
assert.strictEqual(uploads, 6, 'old player must not dirty replacement texture');
// A mixed cached version retains the original per-render fallback.
delete VC._sotaPlayer.onCanvasChanged;
refresh(1219); refresh(1236);
assert.strictEqual(uploads, 8);
VC._stopRenderLoop();
console.log('surround texture upload harness passed');
