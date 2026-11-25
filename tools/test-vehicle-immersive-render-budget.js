/* Run from repository root: node tools/test-vehicle-immersive-render-budget.js */
'use strict';
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const frames = new Map();
let nextId = 0, now = 0, draws = 0, tweens = [];
const context = {
    window: { AndroidBridge: {}, performance: { now: () => now } },
    performance: { now: () => now },
    document: { readyState: 'loading', hidden: false, addEventListener() {} },
    requestAnimationFrame(fn) { const id = ++nextId; frames.set(id, fn); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    gsap: { globalTimeline: { getChildren: () => tweens } }
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('app/src/main/assets/web/shared/vehicle-control.js', 'utf8'), context);
const vc = context.VC;
vc.renderer = { render() { draws++; } };
vc.scene = {};
vc.camera = {};
vc.controls = { update() {} };

function step(time) {
    now = time;
    assert.strictEqual(frames.size, 1, 'exactly one pending frame');
    const [id, callback] = frames.entries().next().value;
    frames.delete(id);
    callback(time);
}
function tween(repeat) { return { repeat: () => repeat, isActive: () => true }; }

vc.animate();
vc.animate();
assert.strictEqual(draws, 1);
step(33);
step(66);
assert.strictEqual(draws, 1, 'idle frames skipped');
step(100);
assert.strictEqual(draws, 2);
vc._orbitInteracting = true;
step(133);
assert.strictEqual(draws, 3, 'gesture restores fast cadence');
step(150);
assert.strictEqual(draws, 3, 'head-unit fast cadence still capped');
vc._orbitInteracting = false;
vc._orbitActiveUntil = 250;
step(166);
assert.strictEqual(draws, 4, 'damping grace stays responsive');
vc._orbitActiveUntil = 0;
tweens = [tween(0)];
step(200);
assert.strictEqual(draws, 5, 'finite VFX restores fast cadence');
tweens = [tween(-1)];
step(233);
assert.strictEqual(draws, 5, 'infinite cosmetic glow uses idle cadence');
vc._3dViewActive = true;
step(266);
assert.strictEqual(draws, 6, 'live video retains fast cadence');
vc._3dViewActive = false;

const obsolete = frames.values().next().value;
context.document.hidden = true;
vc._stopRenderLoop();
vc.animate();
assert.strictEqual(frames.size, 0, 'hidden page schedules no frames');
context.document.hidden = false;
now = 300;
vc.animate();
obsolete(300);
assert.strictEqual(draws, 7, 'obsolete callback cannot paint or join new loop');
assert.strictEqual(frames.size, 1);

// A desktop/phone browser retains the original native refresh cadence.
delete context.window.AndroidBridge;
step(316);
assert.strictEqual(draws, 8);
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
console.log('Vehicle immersive render budget: cadence, visibility, generation and disposal passed');
