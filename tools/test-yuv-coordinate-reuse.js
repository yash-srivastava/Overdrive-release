/* Run with node tools/test-yuv-coordinate-reuse.js. No browser required. */
'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const YUVCanvas = require('../app/src/main/assets/web/shared/YUVCanvas');

// Capture values synchronously, as WebGL bufferData copies its source data.
// Keep identities separately to detect accidental per-frame allocations.
function fakeCanvas() {
    const calls = [];
    const coordinates = [];
    let id = 0;
    const gl = {};
    [ 'ARRAY_BUFFER', 'STATIC_DRAW', 'DYNAMIC_DRAW', 'FLOAT', 'VERTEX_SHADER',
        'FRAGMENT_SHADER', 'COMPILE_STATUS', 'LINK_STATUS', 'TEXTURE_2D',
        'TEXTURE_MAG_FILTER', 'TEXTURE_MIN_FILTER', 'NEAREST', 'TEXTURE_WRAP_S',
        'TEXTURE_WRAP_T', 'CLAMP_TO_EDGE', 'TEXTURE0', 'TEXTURE1', 'TEXTURE2',
        'LUMINANCE', 'UNSIGNED_BYTE', 'TRIANGLE_STRIP', 'UNPACK_ALIGNMENT'
    ].forEach(k => { gl[k] = k; });
    [ 'createBuffer', 'createTexture', 'createShader', 'createProgram' ]
        .forEach(k => { gl[k] = () => k + (++id); });
    gl.getParameter = gl.getShaderParameter = gl.getProgramParameter = () => true;
    gl.getAttribLocation = gl.getUniformLocation = (_, name) => name;
    [ 'shaderSource', 'compileShader', 'attachShader', 'linkProgram',
        'useProgram', 'uniformMatrix4fv', 'enableVertexAttribArray',
        'vertexAttribPointer', 'texParameteri', 'uniform1i' ]
        .forEach(k => { gl[k] = () => {}; });
    [ 'viewport', 'bindBuffer', 'activeTexture', 'bindTexture', 'texImage2D',
        'texSubImage2D', 'uniform2f', 'drawArrays', 'pixelStorei' ]
        .forEach(k => { gl[k] = (...args) => calls.push([k, ...args]); });
    gl.bufferData = (target, values, usage) => {
        if (usage === gl.DYNAMIC_DRAW) coordinates.push(values);
        calls.push(['bufferData', target, Array.from(values), usage]);
    };
    return { canvas: { getContext: () => gl }, calls, coordinates };
}

function expectedCoords(right, bottom) {
    return Array.from(new Float32Array([right, 0, 0, 0, right, bottom, 0, bottom]));
}

function exercise(type, customYUV444, reusePlaneTextures) {
    const mock = fakeCanvas();
    const renderer = new YUVCanvas({ canvas: mock.canvas, type, customYUV444,
        reusePlaneTextures, width: 12, height: 8 });
    const digest = crypto.createHash('sha256');
    const identities = new Set();
    let firstCoordinates;
    let uploads = 0;
    for (let i = 0; i < 240; i++) {
        // Long steady runs, independent U/V strides, default values, and a
        // dimension change, including a return to the original geometry.
        renderer.width = i >= 100 && i < 180 ? 20 : 12;
        renderer.height = i >= 100 && i < 180 ? 12 : 8;
        const w = renderer.width, h = renderer.height;
        const padded = i >= 40 && i < 80 || i >= 130 && i < 160;
        const params = {};
        const expected = [];
        if (type === 'yuv420') {
            const cw = customYUV444 ? w : w / 2;
            const ch = customYUV444 ? h : h / 2;
            // Omitted chroma strides retain the renderer's existing defaults,
            // which differ in customYUV444 mode between its two upload paths.
            if (padded) {
                Object.assign(params, { yDataPerRow: w + 4, yRowCnt: h + 2,
                    uDataPerRow: cw + 4, uRowCnt: ch + 2,
                    vDataPerRow: cw + 8, vRowCnt: ch + 4 });
            } else if (customYUV444) {
                Object.assign(params, { uDataPerRow: cw, uRowCnt: ch });
            }
            const ys = params.yDataPerRow || w, yr = params.yRowCnt || h;
            const us = params.uDataPerRow || w / 2, ur = params.uRowCnt || h / 2;
            const vs = params.vDataPerRow || us, vr = params.vRowCnt || ur;
            params.yData = new Uint8Array(ys * yr).fill(i);
            params.uData = new Uint8Array(us * ur).fill(i + 1);
            params.vData = new Uint8Array(vs * vr).fill(i + 2);
            expected.push(expectedCoords(w / ys, h / yr),
                expectedCoords(cw / us, ch / ur), expectedCoords(cw / vs, ch / vr));
        } else {
            if (padded) Object.assign(params, { dataPerRow: w * 2 + 8, rowCnt: h + 2 });
            const stride = params.dataPerRow || w * 2, rows = params.rowCnt || h;
            params.data = new Uint8Array(stride * rows).fill(i);
            expected.push(expectedCoords(w / (stride / 2), h / rows));
        }
        mock.calls.length = mock.coordinates.length = 0;
        renderer.drawNextOutputPicture(params);
        const dynamic = mock.calls.filter(c => c[0] === 'bufferData');
        assert.deepEqual(dynamic.map(c => c[2]), expected);
        assert.ok(dynamic.every(c => c[3] === 'DYNAMIC_DRAW'));
        assert.equal(mock.calls.filter(c => c[0] === 'drawArrays').length, 1);
        assert.deepEqual(mock.calls[0], ['viewport', 0, 0, w, h]);
        const textureCalls = mock.calls.filter(c => c[0] === 'texImage2D' || c[0] === 'texSubImage2D');
        assert.equal(textureCalls.length, expected.length);
        if (!reusePlaneTextures) assert.ok(textureCalls.every(c => c[0] === 'texImage2D'));
        const data = type === 'yuv420' ? [params.yData, params.uData, params.vData] : [params.data];
        textureCalls.forEach((c, index) => assert.equal(c[c.length - 1], data[index]));
        mock.coordinates.forEach(a => identities.add(a));
        if (!firstCoordinates) firstCoordinates = mock.coordinates.slice();
        else if (!process.env.YUV_TRACE_ONLY) mock.coordinates.forEach((a, j) => assert.equal(a, firstCoordinates[j]));
        uploads += dynamic.length;
        // Compare full draw command sequences, numeric coordinates and plane
        // bytes against the original implementation in separate local runs.
        digest.update(JSON.stringify(mock.calls));
    }
    if (!process.env.YUV_TRACE_ONLY) assert.equal(identities.size, firstCoordinates.length);
    console.log(`${type} custom444=${customYUV444} pooled=${reusePlaneTextures}: ${uploads} coordinate uploads, ${identities.size} arrays, trace=${digest.digest('hex')}`);

    // Reinitializing buffers must use fresh per-canvas state; later uploads
    // still use the same new array even when the geometry changes.
    renderer.initBuffers();
    const other = fakeCanvas();
    const second = new YUVCanvas({ canvas: other.canvas, type, customYUV444, reusePlaneTextures });
    if (!process.env.YUV_TRACE_ONLY) {
        assert.notEqual(renderer.yCoords.values, firstCoordinates[0]);
        assert.notEqual(renderer.yCoords.values, second.yCoords.values);
    }
}

for (const reuse of [false, true]) {
    exercise('yuv420', false, reuse);
    exercise('yuv420', true, reuse);
    exercise('yuv422', false, reuse);
}
console.log('YUV coordinate regression checks passed');
