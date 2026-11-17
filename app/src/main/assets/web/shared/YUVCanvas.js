//
//  Copyright (c) 2015 Paperspace Co. All rights reserved.
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to
//  deal in the Software without restriction, including without limitation the
//  rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
//  sell copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
//  The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
//  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
//  IN THE SOFTWARE.
//


// universal module definition
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD. Register as an anonymous module.
        define([], factory);
    } else if (typeof exports === 'object') {
        // Node. Does not work with strict CommonJS, but
        // only CommonJS-like environments that support module.exports,
        // like Node.
        module.exports = factory();
    } else {
        // Browser globals (root is window)
        root.YUVCanvas = factory();
    }
}(this, function () {


/**
 * This class can be used to render output pictures from an H264bsdDecoder to a canvas element.
 * If available the content is rendered using WebGL.
 */
  function YUVCanvas(parOptions) {

    parOptions = parOptions || {};

    this.canvasElement = parOptions.canvas || document.createElement("canvas");
    this.contextOptions = parOptions.contextOptions;

    this.type = parOptions.type || "yuv420";

    this.customYUV444 = parOptions.customYUV444;

    this.conversionType = parOptions.conversionType || "rec601";

    this.width = parOptions.width || 640;
    this.height = parOptions.height || 320;

    this.animationTime = parOptions.animationTime || 0;

    this.canvasElement.width = this.width;
    this.canvasElement.height = this.height;

    this.initContextGL();

    if(this.contextGL) {
      this.initProgram();
      this.initBuffers();
      this.initTextures();
    };


/**
 * Draw the next output picture using WebGL
 */
    if (this.type === "yuv420"){
      this.drawNextOuptutPictureGL = function(par) {
        var gl = this.contextGL;
        var texturePosBuffer = this.texturePosBuffer;
        var uTexturePosBuffer = this.uTexturePosBuffer;
        var vTexturePosBuffer = this.vTexturePosBuffer;

        var yTextureRef = this.yTextureRef;
        var uTextureRef = this.uTextureRef;
        var vTextureRef = this.vTextureRef;

        var yData = par.yData;
        var uData = par.uData;
        var vData = par.vData;

        var width = this.width;
        var height = this.height;

        var yDataPerRow = par.yDataPerRow || width;
        var yRowCnt     = par.yRowCnt || height;

        var uDataPerRow = par.uDataPerRow || (width / 2);
        var uRowCnt     = par.uRowCnt || (height / 2);

        var vDataPerRow = par.vDataPerRow || uDataPerRow;
        var vRowCnt     = par.vRowCnt || uRowCnt;

        gl.viewport(0, 0, width, height);

        // Strides/crop extents usually stay fixed across decoded frames.
        // Leave the attribute buffers alone until their coordinates change.
        this.updateTextureCoordinates(texturePosBuffer, this.yCoords,
            width / yDataPerRow, height / yRowCnt);
        this.updateTextureCoordinates(uTexturePosBuffer, this.uCoords,
            (this.customYUV444 ? width : width / 2) / uDataPerRow,
            (this.customYUV444 ? height : height / 2) / uRowCnt);
        this.updateTextureCoordinates(vTexturePosBuffer, this.vCoords,
            (this.customYUV444 ? width : width / 2) / vDataPerRow,
            (this.customYUV444 ? height : height / 2) / vRowCnt);

        gl.activeTexture(gl.TEXTURE0);
        this.uploadPlane(yTextureRef, this.yTextureSize, yDataPerRow, yRowCnt, yData);

        gl.activeTexture(gl.TEXTURE1);
        this.uploadPlane(uTextureRef, this.uTextureSize, uDataPerRow, uRowCnt, uData);

        gl.activeTexture(gl.TEXTURE2);
        this.uploadPlane(vTextureRef, this.vTextureSize, vDataPerRow, vRowCnt, vData);

        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      };

    }else if (this.type === "yuv422"){
      this.drawNextOuptutPictureGL = function(par) {
        var gl = this.contextGL;
        var texturePosBuffer = this.texturePosBuffer;

        var textureRef = this.textureRef;

        var data = par.data;

        var width = this.width;
        var height = this.height;

        var dataPerRow = par.dataPerRow || (width * 2);
        var rowCnt     = par.rowCnt || height;

        gl.viewport(0, 0, width, height);

        this.updateTextureCoordinates(texturePosBuffer, this.yCoords,
            width / (dataPerRow / 2), height / rowCnt);

        if (this.lastResolutionWidth !== dataPerRow) {
          gl.uniform2f(this.resolutionLocation, dataPerRow, height);
          this.lastResolutionWidth = dataPerRow;
        }

        gl.activeTexture(gl.TEXTURE0);
        this.uploadPlane(textureRef, this.yTextureSize, dataPerRow, rowCnt, data);

        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      };
    };

  };

  /**
   * Update a fixed-size vertex buffer only when the decoded plane's crop changes.
   * The buffer storage and typed array are reused for the life of this canvas.
   */
  YUVCanvas.prototype.updateTextureCoordinates = function(buffer, state, right, bottom) {
    if (state.right === right && state.bottom === bottom) return;
    var values = state.values;
    values[0] = right;
    values[2] = 0;
    values[4] = right;
    values[6] = 0;
    values[1] = 0;
    values[3] = 0;
    values[5] = bottom;
    values[7] = bottom;
    var gl = this.contextGL;
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, values);
    state.right = right;
    state.bottom = bottom;
  };

  /**
   * Reallocate a luminance texture only when the decoder changes plane size.
   * WebGL texImage2D on every frame discards the previous GPU storage even
   * though most frames have identical dimensions. texSubImage2D updates it.
   */
  YUVCanvas.prototype.uploadPlane = function(texture, size, width, height, data) {
    var gl = this.contextGL;
    gl.bindTexture(gl.TEXTURE_2D, texture);
    if (size.width !== width || size.height !== height) {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.LUMINANCE, width, height, 0,
          gl.LUMINANCE, gl.UNSIGNED_BYTE, data);
      size.width = width;
      size.height = height;
    } else {
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, width, height,
          gl.LUMINANCE, gl.UNSIGNED_BYTE, data);
    }
  };

  /**
 * Returns true if the canvas supports WebGL
 */
  YUVCanvas.prototype.isWebGL = function() {
    return this.contextGL;
  };

  /**
 * Create the GL context from the canvas element
 */
  YUVCanvas.prototype.initContextGL = function() {
    var canvas = this.canvasElement;
    var gl = null;

    var validContextNames = ["webgl", "experimental-webgl", "moz-webgl", "webkit-3d"];
    var nameIndex = 0;

    while(!gl && nameIndex < validContextNames.length) {
      var contextName = validContextNames[nameIndex];

      try {
        if (this.contextOptions){
          gl = canvas.getContext(contextName, this.contextOptions);
        }else{
          gl = canvas.getContext(contextName);
        };
      } catch (e) {
        gl = null;
      }

      if(!gl || typeof gl.getParameter !== "function") {
        gl = null;
      }

      ++nameIndex;
    };

    this.contextGL = gl;
  };

/**
 * Initialize GL shader program
 */
YUVCanvas.prototype.initProgram = function() {
    var gl = this.contextGL;

  // vertex shader is the same for all types
  var vertexShaderScript;
  var fragmentShaderScript;

  if (this.type === "yuv420"){

    vertexShaderScript = [
      'attribute vec4 vertexPos;',
      'attribute vec4 texturePos;',
      'attribute vec4 uTexturePos;',
      'attribute vec4 vTexturePos;',
      'varying vec2 textureCoord;',
      'varying vec2 uTextureCoord;',
      'varying vec2 vTextureCoord;',

      'void main()',
      '{',
      '  gl_Position = vertexPos;',
      '  textureCoord = texturePos.xy;',
      '  uTextureCoord = uTexturePos.xy;',
      '  vTextureCoord = vTexturePos.xy;',
      '}'
    ].join('\n');

    fragmentShaderScript = [
      'precision highp float;',
      'varying highp vec2 textureCoord;',
      'varying highp vec2 uTextureCoord;',
      'varying highp vec2 vTextureCoord;',
      'uniform sampler2D ySampler;',
      'uniform sampler2D uSampler;',
      'uniform sampler2D vSampler;',
      'uniform mat4 YUV2RGB;',

      'void main(void) {',
      '  highp float y = texture2D(ySampler,  textureCoord).r;',
      '  highp float u = texture2D(uSampler,  uTextureCoord).r;',
      '  highp float v = texture2D(vSampler,  vTextureCoord).r;',
      '  gl_FragColor = vec4(y, u, v, 1) * YUV2RGB;',
      '}'
    ].join('\n');

  }else if (this.type === "yuv422"){
    vertexShaderScript = [
      'attribute vec4 vertexPos;',
      'attribute vec4 texturePos;',
      'varying vec2 textureCoord;',

      'void main()',
      '{',
      '  gl_Position = vertexPos;',
      '  textureCoord = texturePos.xy;',
      '}'
    ].join('\n');

    fragmentShaderScript = [
      'precision highp float;',
      'varying highp vec2 textureCoord;',
      'uniform sampler2D sampler;',
      'uniform highp vec2 resolution;',
      'uniform mat4 YUV2RGB;',

      'void main(void) {',

      '  highp float texPixX = 1.0 / resolution.x;',
      '  highp float logPixX = 2.0 / resolution.x;', // half the resolution of the texture
      '  highp float logHalfPixX = 4.0 / resolution.x;', // half of the logical resolution so every 4th pixel
      '  highp float steps = floor(textureCoord.x / logPixX);',
      '  highp float uvSteps = floor(textureCoord.x / logHalfPixX);',
      '  highp float y = texture2D(sampler, vec2((logPixX * steps) + texPixX, textureCoord.y)).r;',
      '  highp float u = texture2D(sampler, vec2((logHalfPixX * uvSteps), textureCoord.y)).r;',
      '  highp float v = texture2D(sampler, vec2((logHalfPixX * uvSteps) + texPixX + texPixX, textureCoord.y)).r;',

      //'  highp float y = texture2D(sampler,  textureCoord).r;',
      //'  gl_FragColor = vec4(y, u, v, 1) * YUV2RGB;',
      '  gl_FragColor = vec4(y, u, v, 1.0) * YUV2RGB;',
      '}'
    ].join('\n');
  };

  var YUV2RGB = [];

  if (this.conversionType == "rec709") {
      // ITU-T Rec. 709
      YUV2RGB = [
          1.16438,  0.00000,  1.79274, -0.97295,
          1.16438, -0.21325, -0.53291,  0.30148,
          1.16438,  2.11240,  0.00000, -1.13340,
          0, 0, 0, 1,
      ];
  } else {
      // assume ITU-T Rec. 601
      YUV2RGB = [
          1.16438,  0.00000,  1.59603, -0.87079,
          1.16438, -0.39176, -0.81297,  0.52959,
          1.16438,  2.01723,  0.00000, -1.08139,
          0, 0, 0, 1
      ];
  };

  var vertexShader = gl.createShader(gl.VERTEX_SHADER);
  gl.shaderSource(vertexShader, vertexShaderScript);
  gl.compileShader(vertexShader);
  if(!gl.getShaderParameter(vertexShader, gl.COMPILE_STATUS)) {
    console.log('Vertex shader failed to compile: ' + gl.getShaderInfoLog(vertexShader));
  }

  var fragmentShader = gl.createShader(gl.FRAGMENT_SHADER);
  gl.shaderSource(fragmentShader, fragmentShaderScript);
  gl.compileShader(fragmentShader);
  if(!gl.getShaderParameter(fragmentShader, gl.COMPILE_STATUS)) {
    console.log('Fragment shader failed to compile: ' + gl.getShaderInfoLog(fragmentShader));
  }

  var program = gl.createProgram();
  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  if(!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    console.log('Program failed to compile: ' + gl.getProgramInfoLog(program));
  }

  gl.useProgram(program);

  var YUV2RGBRef = gl.getUniformLocation(program, 'YUV2RGB');
  gl.uniformMatrix4fv(YUV2RGBRef, false, YUV2RGB);

  this.shaderProgram = program;
  if (this.type === 'yuv422') {
    this.resolutionLocation = gl.getUniformLocation(program, 'resolution');
    this.lastResolutionWidth = null;
  }
};

/**
 * Initialize vertex buffers and attach to shader program
 */
YUVCanvas.prototype.initBuffers = function() {
  var gl = this.contextGL;
  var program = this.shaderProgram;

  var vertexPosBuffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, vertexPosBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([1, 1, -1, 1, 1, -1, -1, -1]), gl.STATIC_DRAW);

  var vertexPosRef = gl.getAttribLocation(program, 'vertexPos');
  gl.enableVertexAttribArray(vertexPosRef);
  gl.vertexAttribPointer(vertexPosRef, 2, gl.FLOAT, false, 0, 0);

  if (this.animationTime){

    var animationTime = this.animationTime;
    var timePassed = 0;
    var stepTime = 15;
    var animationVertices = new Float32Array(8);

    var aniFun = function(){

      timePassed += stepTime;
      var mul = ( 1 * timePassed ) / animationTime;

      if (timePassed >= animationTime){
        mul = 1;
      }else{
        setTimeout(aniFun, stepTime);
      };

      var neg = -1 * mul;
      var pos = 1 * mul;

      // Animation must not allocate a new GPU buffer every 15 ms.
      animationVertices[0] = animationVertices[1] = animationVertices[4] = pos;
      animationVertices[2] = animationVertices[3] = animationVertices[6] = animationVertices[7] = neg;
      animationVertices[5] = neg;
      gl.bindBuffer(gl.ARRAY_BUFFER, vertexPosBuffer);
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, animationVertices);

      try{
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      }catch(e){};

    };
    aniFun();

  };



  var texturePosBuffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, texturePosBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([1, 0, 0, 0, 1, 1, 0, 1]), gl.STATIC_DRAW);

  var texturePosRef = gl.getAttribLocation(program, 'texturePos');
  gl.enableVertexAttribArray(texturePosRef);
  gl.vertexAttribPointer(texturePosRef, 2, gl.FLOAT, false, 0, 0);

  this.texturePosBuffer = texturePosBuffer;
  // Initial attribute coordinates are (0..1), so ordinary tightly-packed
  // frames require no per-frame buffer uploads.
  this.yCoords = { right: 1, bottom: 1, values: new Float32Array(8) };

  if (this.type === "yuv420"){
    var uTexturePosBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, uTexturePosBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([1, 0, 0, 0, 1, 1, 0, 1]), gl.STATIC_DRAW);

    var uTexturePosRef = gl.getAttribLocation(program, 'uTexturePos');
    gl.enableVertexAttribArray(uTexturePosRef);
    gl.vertexAttribPointer(uTexturePosRef, 2, gl.FLOAT, false, 0, 0);

    this.uTexturePosBuffer = uTexturePosBuffer;
    this.uCoords = { right: 1, bottom: 1, values: new Float32Array(8) };


    var vTexturePosBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vTexturePosBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([1, 0, 0, 0, 1, 1, 0, 1]), gl.STATIC_DRAW);

    var vTexturePosRef = gl.getAttribLocation(program, 'vTexturePos');
    gl.enableVertexAttribArray(vTexturePosRef);
    gl.vertexAttribPointer(vTexturePosRef, 2, gl.FLOAT, false, 0, 0);

    this.vTexturePosBuffer = vTexturePosBuffer;
    this.vCoords = { right: 1, bottom: 1, values: new Float32Array(8) };
  };

};

/**
 * Initialize GL textures and attach to shader program
 */
YUVCanvas.prototype.initTextures = function() {
  var gl = this.contextGL;
  var program = this.shaderProgram;

  if (this.type === "yuv420"){

    var yTextureRef = this.initTexture();
    var ySamplerRef = gl.getUniformLocation(program, 'ySampler');
    gl.uniform1i(ySamplerRef, 0);
    this.yTextureRef = yTextureRef;
    this.yTextureSize = { width: 0, height: 0 };

    var uTextureRef = this.initTexture();
    var uSamplerRef = gl.getUniformLocation(program, 'uSampler');
    gl.uniform1i(uSamplerRef, 1);
    this.uTextureRef = uTextureRef;
    this.uTextureSize = { width: 0, height: 0 };

    var vTextureRef = this.initTexture();
    var vSamplerRef = gl.getUniformLocation(program, 'vSampler');
    gl.uniform1i(vSamplerRef, 2);
    this.vTextureRef = vTextureRef;
    this.vTextureSize = { width: 0, height: 0 };

  }else if (this.type === "yuv422"){
    // only one texture for 422
    var textureRef = this.initTexture();
    var samplerRef = gl.getUniformLocation(program, 'sampler');
    gl.uniform1i(samplerRef, 0);
    this.textureRef = textureRef;
    this.yTextureSize = { width: 0, height: 0 };

  };
};

/**
 * Create and configure a single texture
 */
YUVCanvas.prototype.initTexture = function() {
    var gl = this.contextGL;

    var textureRef = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, textureRef);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.bindTexture(gl.TEXTURE_2D, null);

    return textureRef;
};

/**
 * Draw picture data to the canvas.
 * If this object is using WebGL, the data must be an I420 formatted ArrayBuffer,
 * Otherwise, data must be an RGBA formatted ArrayBuffer.
 */
YUVCanvas.prototype.drawNextOutputPicture = function(width, height, croppingParams, data) {
    var gl = this.contextGL;

    if(gl) {
        this.drawNextOuptutPictureGL(width, height, croppingParams, data);
    } else {
        this.drawNextOuptutPictureRGBA(width, height, croppingParams, data);
    }
};



/**
 * Draw next output picture using ARGB data on a 2d canvas.
 */
YUVCanvas.prototype.drawNextOuptutPictureRGBA = function(width, height, croppingParams, data) {
    var canvas = this.canvasElement;

    var croppingParams = null;

    var argbData = data;

    var ctx = canvas.getContext('2d');
    var imageData = ctx.getImageData(0, 0, width, height);
    imageData.data.set(argbData);

    if(croppingParams === null) {
        ctx.putImageData(imageData, 0, 0);
    } else {
        ctx.putImageData(imageData, -croppingParams.left, -croppingParams.top, 0, 0, croppingParams.width, croppingParams.height);
    }
};

  return YUVCanvas;

}));