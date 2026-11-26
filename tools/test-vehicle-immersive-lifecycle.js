/* Run from repository root: node tools/test-vehicle-immersive-lifecycle.js */
'use strict';
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const frames = new Map(), listeners = new Map();
let nextId = 0, now = 0, draws = 0;
const context = {
    window: { AndroidBridge: {}, performance: { now: () => now } },
    performance: { now: () => now },
    document: { readyState: 'loading', hidden: false,
        addEventListener(name, fn) { listeners.set(name, fn); } },
    requestAnimationFrame(fn) { const id = ++nextId; frames.set(id, fn); return id; },
    cancelAnimationFrame(id) { frames.delete(id); }
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('app/src/main/assets/web/shared/vehicle-control.js', 'utf8'), context);
const vc = context.VC;
const renderer = { render() { draws++; }, dispose() {}, forceContextLoss() {} };
function startScene() {
    vc.renderer = renderer;
    vc.scene = {};
    vc.camera = {};
    vc.controls = { update() {} };
    vc.animate();
}
function step(time) {
    now = time;
    assert.strictEqual(frames.size, 1, 'exactly one pending frame');
    const [id, callback] = frames.entries().next().value;
    frames.delete(id);
    callback(time);
}

// Isolate the rendering lifecycle from the existing network pollers.
for (const name of ['stopSyncPollers', 'startStateSync', 'startAcChargeCurrentSync',
    'startCloudStatusSync', 'startCloudLockSync']) vc[name] = () => {};
vc.initVisibilitySync();
function visible(value) {
    context.document.hidden = !value;
    listeners.get('visibilitychange')();
}

startScene();
vc.animate();
assert.strictEqual(draws, 1);
step(16);
assert.strictEqual(draws, 1, 'existing head-unit 32 ms gate');
step(33);
step(66);
assert.strictEqual(draws, 3, 'no additional idle gate');

const obsolete = frames.values().next().value;
visible(false);
vc.animate();
assert.strictEqual(frames.size, 0, 'hidden page schedules no frames');
now = 100;
visible(true);
obsolete(100);
assert.strictEqual(draws, 4, 'obsolete callback cannot paint or join new loop');
assert.strictEqual(frames.size, 1);
visible(true);
assert.strictEqual(frames.size, 1, 'repeated reveal does not duplicate the loop');

// A desktop/phone browser retains the original native refresh cadence.
delete context.window.AndroidBridge;
step(116);
assert.strictEqual(draws, 5);

const beforeLite = frames.values().next().value;
let disposedRenderer = 0, lostContext = 0;
renderer.dispose = () => { disposedRenderer++; };
renderer.forceContextLoss = () => { lostContext++; };
vc._teardownImmersiveScene();
assert.strictEqual(disposedRenderer, 1);
assert.strictEqual(lostContext, 1);
assert.strictEqual(frames.size, 0);
visible(false);
visible(true);
assert.strictEqual(frames.size, 0, 'lite reveal cannot start a renderer');
startScene();
beforeLite(132);
assert.strictEqual(draws, 6, 'old scene callback cannot render after lite flip');
assert.strictEqual(frames.size, 1);
vc._stopRenderLoop();
assert.strictEqual(frames.size, 0);

// Shared geometry/material/texture objects are disposed once per model tree.
const disposed = { geometry: 0, material: 0, texture: 0 };
const texture = { isTexture: true, dispose() { disposed.texture++; } };
const material = { map: texture, emissiveMap: texture, dispose() { disposed.material++; } };
const geometry = { dispose() { disposed.geometry++; } };
vc._disposeModelMeshes({ traverse(fn) {
    fn({ isMesh: true, geometry, material });
    fn({ isMesh: true, geometry, material: [material] });
} });
assert.deepStrictEqual(disposed, { geometry: 1, material: 1, texture: 1 });
console.log('Vehicle immersive lifecycle: visibility, generation, cadence and disposal passed');
