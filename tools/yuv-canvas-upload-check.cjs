'use strict';

// Manual mock-WebGL contract; this is not a GPU benchmark.
// Run with: node tools/yuv-canvas-upload-check.cjs
const assert = require('node:assert/strict');
const YUVCanvas = require('../app/src/main/assets/web/shared/YUVCanvas.js');

function createCanvas(options) {
  const calls = [];
  const errors = [];
  let nextId = 0;
  let failNextUpload = false;
  const gl = new Proxy({}, {
    get(_target, name) {
      if (name === 'NO_ERROR') return 0;
      if (name === 'getError') return () => errors.length ? errors.shift() : 0;
      if (name === 'getParameter' || name === 'getShaderParameter' ||
          name === 'getProgramParameter') return () => true;
      if (name === 'getUniformLocation' || name === 'getAttribLocation') {
        return (...args) => {
          calls.push([name, ...args]);
          return name === 'getUniformLocation' ? { name: args[1] } : 0;
        };
      }
      if (name === 'createShader' || name === 'createProgram' ||
          name === 'createBuffer' || name === 'createTexture') {
        return () => {
          calls.push([name]);
          return { id: ++nextId };
        };
      }
      if (name === 'getShaderInfoLog' || name === 'getProgramInfoLog') return () => '';
      if (typeof name === 'symbol') return undefined;
      if (/^[A-Z_0-9]+$/.test(name)) return name;
      return (...args) => {
        const snapshot = args.map(value => value instanceof Float32Array ? Array.from(value) : value);
        calls.push([name, ...snapshot]);
        if (name === 'texImage2D' && failNextUpload) {
          failNextUpload = false;
          errors.push('OUT_OF_MEMORY');
        }
      };
    }
  });
  const canvas = { getContext: () => gl };
  const renderer = new YUVCanvas(Object.assign({ canvas, width: 4, height: 4 }, options));
  return {
    renderer, calls,
    clear() { calls.length = 0; },
    failAllocation() { failNextUpload = true; }
  };
}

function count(calls, name) {
  return calls.filter(call => call[0] === name).length;
}

const frame420 = {
  yData: new Uint8Array(16), uData: new Uint8Array(4), vData: new Uint8Array(4)
};

// The safe default uses texImage2D and keeps per-frame bufferData.
const baseline = createCanvas({ type: 'yuv420' });
baseline.clear();
baseline.renderer.drawNextOuptutPictureGL(frame420);
baseline.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(baseline.calls, 'texImage2D'), 6);
assert.equal(count(baseline.calls, 'texSubImage2D'), 0);
assert.equal(count(baseline.calls, 'bufferData'), 6);

const view420 = createCanvas({ type: 'yuv420', reusePlaneTextures: true });
view420.clear();
view420.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(view420.calls, 'texImage2D'), 3);
assert.equal(count(view420.calls, 'texSubImage2D'), 0);
assert.equal(count(view420.calls, 'bufferData'), 3);
view420.clear();
view420.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(view420.calls, 'texImage2D'), 0);
assert.equal(count(view420.calls, 'texSubImage2D'), 3);
assert.equal(count(view420.calls, 'bufferData'), 3, 'keep per-frame GL orphaning');

view420.clear();
view420.renderer.drawNextOuptutPictureGL(Object.assign({}, frame420, {
  yDataPerRow: 6, yData: new Uint8Array(24)
}));
assert.equal(count(view420.calls, 'texImage2D'), 1, 'Y stride resizes Y only');
assert.equal(count(view420.calls, 'texSubImage2D'), 2);
assert.equal(view420.calls.filter(c => c[0] === 'bufferData')[0][2][0], Math.fround(4 / 6));

// An unsuccessful texImage2D must be retried on the next frame; the other
// planes remain cached, and the failed frame must not be drawn.
const failure = createCanvas({ reusePlaneTextures: true });
failure.clear();
failure.failAllocation();
failure.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(failure.calls, 'drawArrays'), 0);
assert.equal(failure.renderer.yTextureSize.width, 0);
failure.clear();
failure.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(failure.calls, 'texImage2D'), 1);
assert.equal(count(failure.calls, 'texSubImage2D'), 2);
assert.equal(count(failure.calls, 'drawArrays'), 1);

// U stride must not be applied to the independently packed V plane.
const view444 = createCanvas({ type: 'yuv420', customYUV444: true,
  reusePlaneTextures: true });
view444.clear();
view444.renderer.drawNextOuptutPictureGL({
  yData: new Uint8Array(16), uData: new Uint8Array(24),
  vData: new Uint8Array(16), uDataPerRow: 6
});
assert.equal(view444.renderer.uTextureSize.width, 6);
assert.equal(view444.renderer.vTextureSize.width, 4);
view444.clear();
view444.renderer.drawNextOuptutPictureGL({
  yData: new Uint8Array(16), uData: new Uint8Array(24),
  vData: new Uint8Array(20), uDataPerRow: 6, vDataPerRow: 5
});
assert.equal(count(view444.calls, 'texImage2D'), 1);
assert.equal(count(view444.calls, 'bufferData'), 3);

const view422 = createCanvas({ type: 'yuv422', reusePlaneTextures: true });
assert.equal(view422.calls.filter(c => c[0] === 'getUniformLocation' &&
    c[2] === 'resolution').length, 1);
view422.clear();
view422.renderer.drawNextOuptutPictureGL({ data: new Uint8Array(32) });
view422.renderer.drawNextOuptutPictureGL({ data: new Uint8Array(32) });
assert.equal(count(view422.calls, 'uniform2f'), 1);
assert.equal(count(view422.calls, 'texImage2D'), 1);
assert.equal(count(view422.calls, 'texSubImage2D'), 1);
assert.equal(count(view422.calls, 'bufferData'), 2);
view422.clear();
view422.renderer.drawNextOuptutPictureGL({ dataPerRow: 10, data: new Uint8Array(40) });
assert.equal(count(view422.calls, 'uniform2f'), 1);
assert.equal(count(view422.calls, 'texImage2D'), 1);

const animation = createCanvas({ type: 'yuv420', animationTime: 15 });
const vertexUpload = animation.calls.find(c => c[0] === 'bufferSubData');
assert.deepEqual(vertexUpload[3], [1, 1, -1, 1, 1, -1, -1, -1]);
assert.equal(count(animation.calls, 'createBuffer'), 4,
  'animation reuses the initial vertex buffer');

console.log('YUVCanvas mock-WebGL upload contracts passed (not a benchmark)');
