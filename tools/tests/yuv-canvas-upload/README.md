# YUVCanvas WebGL upload contract

`node tools/tests/yuv-canvas-upload/run.cjs` runs a mock WebGL context against
`app/src/main/assets/web/shared/YUVCanvas.js` (used by live-view's Player).
It checks first-frame allocation, repeated uploads, independent plane-stride
changes, stable crop attributes, and the packed YUV422 resolution uniform.

It is a functional contract only, **not** a GPU benchmark: for performance
validation, compare WebGL texture allocation and CPU frame time for fixed-size
YUV420, YUV444, and YUV422 streams on a real head unit, then repeat after a resolution
or decoder-stride change. Watch for incorrect cropping or black frames.
