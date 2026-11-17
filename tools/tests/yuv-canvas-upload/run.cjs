'use strict';

// Mock-GL contract coverage for the live-view YUV renderer. No Android or GPU
// required. Run manually with: node tools/tests/yuv-canvas-upload/run.cjs
const assert = require('node:assert/strict');
const YUVCanvas = require('../../../app/src/main/assets/web/shared/YUVCanvas.js');

function createCanvas(options) {
  const calls = [];
  let nextId = 0;
  const gl = new Proxy({}, {
    get(_target, name) {
      if (name === 'getParameter') return () => true;
      if (name === 'getShaderParameter' || name === 'getProgramParameter') return () => true;
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
      if (name === 'getContext' || name === 'getShaderInfoLog' || name === 'getProgramInfoLog') {
        return () => '';
      }
      if (typeof name === 'symbol') return undefined;
      if (/^[A-Z_0-9]+$/.test(name)) return name;
      return (...args) => {
        calls.push([name, ...args]);
      };
    }
  });
  const canvas = { getContext: () => gl };
  const renderer = new YUVCanvas(Object.assign({ canvas, width: 640, height: 480 }, options));
  return { renderer, calls, clear: () => { calls.length = 0; } };
}

function count(calls, name) {
  return calls.filter(call => call[0] === name).length;
}

const frame420 = {
  yData: new Uint8Array(640 * 480),
  uData: new Uint8Array(320 * 240),
  vData: new Uint8Array(320 * 240)
};
const view420 = createCanvas({ type: 'yuv420' });
view420.clear();
view420.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(view420.calls, 'texImage2D'), 3, 'first frame allocates three plane textures');
assert.equal(count(view420.calls, 'texSubImage2D'), 0);
assert.equal(count(view420.calls, 'bufferSubData'), 0, 'full-size crop uses initial coordinates');
view420.clear();
view420.renderer.drawNextOuptutPictureGL(frame420);
assert.equal(count(view420.calls, 'texImage2D'), 0, 'steady state does not reallocate');
assert.equal(count(view420.calls, 'texSubImage2D'), 3, 'every plane still receives fresh pixels');
assert.equal(count(view420.calls, 'bufferSubData'), 0, 'steady state does not upload coordinates');

// One padded stride should only invalidate that plane, not the other two.
view420.clear();
view420.renderer.drawNextOuptutPictureGL(Object.assign({}, frame420, {
  yDataPerRow: 672, yData: new Uint8Array(672 * 480)
}));
assert.equal(count(view420.calls, 'texImage2D'), 1);
assert.equal(count(view420.calls, 'texSubImage2D'), 2);
assert.equal(count(view420.calls, 'bufferSubData'), 1);
assert.equal(view420.calls.find(call => call[0] === 'bufferSubData')[3][0], Math.fround(640 / 672));

// The 4:4:4 option uses full-resolution U/V planes; their strides are
// independent of Y and each other.
const view444 = createCanvas({ type: 'yuv420', customYUV444: true });
view444.clear();
view444.renderer.drawNextOuptutPictureGL({
  yData: new Uint8Array(640 * 480),
  uData: new Uint8Array(704 * 480),
  vData: new Uint8Array(640 * 480),
  uDataPerRow: 704
});
assert.equal(count(view444.calls, 'texImage2D'), 3);
assert.equal(count(view444.calls, 'bufferSubData'), 1, '4:4:4 U crop follows its own stride');
view444.clear();
view444.renderer.drawNextOuptutPictureGL({
  yData: new Uint8Array(640 * 480),
  uData: new Uint8Array(704 * 480),
  vData: new Uint8Array(672 * 480),
  uDataPerRow: 704,
  vDataPerRow: 672
});
assert.equal(count(view444.calls, 'texImage2D'), 1, 'V stride alone resizes V');
assert.equal(count(view444.calls, 'bufferSubData'), 1, 'V stride alone updates V crop');

const frame422 = { data: new Uint8Array(1280 * 480) };
const view422 = createCanvas({ type: 'yuv422' });
assert.equal(view422.calls.filter(call => call[0] === 'getUniformLocation' && call[2] === 'resolution').length, 1);
view422.clear();
view422.renderer.drawNextOuptutPictureGL(frame422);
view422.renderer.drawNextOuptutPictureGL(frame422);
assert.equal(count(view422.calls, 'uniform2f'), 1, 'unchanged resolution is uploaded once');
assert.equal(count(view422.calls, 'texImage2D'), 1);
assert.equal(count(view422.calls, 'texSubImage2D'), 1);
view422.clear();
view422.renderer.drawNextOuptutPictureGL({ dataPerRow: 1344, data: new Uint8Array(1344 * 480) });
assert.equal(count(view422.calls, 'uniform2f'), 1, 'stride change updates resolution');
assert.equal(count(view422.calls, 'bufferSubData'), 1);
assert.equal(count(view422.calls, 'texImage2D'), 1);

console.log('YUVCanvas mock-GL upload contracts passed');
