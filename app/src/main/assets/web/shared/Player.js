/*


usage:

p = new Player({
  useWorker: <bool>,
  workerFile: <defaults to "Decoder.js"> // give path to Decoder.js
  webgl: true | false | "auto" // defaults to "auto"
  reusePlaneTextures: true | false // experimental; defaults to false
  benchmarkPlaneTextures: true | false // diagnostic draw + readPixels timing; defaults to false
});

// canvas property represents the canvas node
// put it somewhere in the dom
p.canvas;

p.webgl; // contains the used rendering mode. if you pass auto to webgl you can see what auto detection resulted in

p.decode(<binary>);


*/



// universal module definition
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD. Register as an anonymous module.
        define(["./Decoder", "./YUVCanvas"], factory);
    } else if (typeof exports === 'object') {
        // Node. Does not work with strict CommonJS, but
        // only CommonJS-like environments that support module.exports,
        // like Node.
        module.exports = factory(require("./Decoder"), require("./YUVCanvas"));
    } else {
        // Browser globals (root is window)
        root.Player = factory(root.Decoder, root.YUVCanvas);
    }
}(this, function (Decoder, WebGLCanvas) {
  "use strict";


  var nowValue = Decoder.nowValue;


  var Player = function(parOptions){
    var self = this;
    this._config = parOptions || {};
    // A single RGBA pixel buffer for benchmark-only GPU readback; never
    // allocate it per frame or use it on normal playback paths.
    this._planeReadbackPixel = this._config.benchmarkPlaneTextures === true
        ? new Uint8Array(4) : null;

    this.render = true;
    if (this._config.render === false){
      this.render = false;
    };

    this.nowValue = nowValue;

    this._config.workerFile = this._config.workerFile || "Decoder.js";
    if (this._config.preserveDrawingBuffer){
      this._config.contextOptions = this._config.contextOptions || {};
      this._config.contextOptions.preserveDrawingBuffer = true;
    };

    var webgl = "auto";
    if (this._config.webgl === true){
      webgl = true;
    }else if (this._config.webgl === false){
      webgl = false;
    };

    if (webgl == "auto"){
      webgl = true;
      try{
        if (!window.WebGLRenderingContext) {
          // the browser doesn't even know what WebGL is
          webgl = false;
        } else {
          var canvas = document.createElement('canvas');
          var ctx = canvas.getContext("webgl");
          if (!ctx) {
            // browser supports WebGL but initialization failed.
            webgl = false;
          };
        };
      }catch(e){
        webgl = false;
      };
    };

    this.webgl = webgl;

    // choose functions
    if (this.webgl){
      this.createCanvasObj = this.createCanvasWebGL;
      this.renderFrame = this.renderFrameWebGL;
    }else{
      this.createCanvasObj = this.createCanvasRGB;
      this.renderFrame = this.renderFrameRGB;
    };


    var lastWidth;
    var lastHeight;
    var onPictureDecoded = function(buffer, width, height, infos) {
      self.onPictureDecoded(buffer, width, height, infos);

      var startTime = nowValue();

      if (!buffer || !self.render) {
        return;
      };

      self.renderFrame({
        canvasObj: self.canvasObj,
        data: buffer,
        width: width,
        height: height
      });

      if (self.onRenderFrameComplete){
        self.onRenderFrameComplete({
          data: buffer,
          width: width,
          height: height,
          infos: infos,
          canvasObj: self.canvasObj
        });
      };

    };

    // provide size

    if (!this._config.size){
      this._config.size = {};
    };
    this._config.size.width = this._config.size.width || 200;
    this._config.size.height = this._config.size.height || 200;

    if (this._config.useWorker){
      var worker = new Worker(this._config.workerFile);
      this.worker = worker;
      worker.addEventListener('message', function(e) {
        var data = e.data;
        if (data.consoleLog){
          console.log(data.consoleLog);
          return;
        };

        onPictureDecoded.call(self, new Uint8Array(data.buf, 0, data.length), data.width, data.height, data.infos);

      }, false);

      worker.postMessage({type: "Broadway.js - Worker init", options: {
        rgb: !webgl,
        memsize: this.memsize,
        reuseMemory: this._config.reuseMemory ? true : false
      }});

      if (this._config.transferMemory){
        this.decode = function(parData, parInfo){
          // no copy
          // instead we are transfering the ownership of the buffer
          // dangerous!!!

          worker.postMessage({buf: parData.buffer, offset: parData.byteOffset, length: parData.length, info: parInfo}, [parData.buffer]); // Send data to our worker.
        };

      }else{
        this.decode = function(parData, parInfo){
          // Copy the sample so that we only do a structured clone of the
          // region of interest
          var copyU8 = new Uint8Array(parData.length);
          copyU8.set( parData, 0, parData.length );
          worker.postMessage({buf: copyU8.buffer, offset: 0, length: parData.length, info: parInfo}, [copyU8.buffer]); // Send data to our worker.
        };

      };

      if (this._config.reuseMemory){
        this.recycleMemory = function(parArray){
          //this.beforeRecycle();
          worker.postMessage({reuse: parArray.buffer}, [parArray.buffer]); // Send data to our worker.
          //this.afterRecycle();
        };
      }

    }else{

      this.decoder = new Decoder({
        rgb: !webgl
      });
      this.decoder.onPictureDecoded = onPictureDecoded;

      this.decode = function(parData, parInfo){
        self.decoder.decode(parData, parInfo);
      };

    };



    if (this.render){
      this.canvasObj = this.createCanvasObj({
        contextOptions: this._config.contextOptions
      });
      this.canvas = this.canvasObj.canvas;
    };

    this.domNode = this.canvas;

    lastWidth = this._config.size.width;
    lastHeight = this._config.size.height;

  };

  Player.prototype = {

    onPictureDecoded: function(buffer, width, height, infos){},

    // call when memory of decoded frames is not used anymore
    recycleMemory: function(buf){
    },
    /*beforeRecycle: function(){},
    afterRecycle: function(){},*/

    // for both functions options is:
    //
    //  width
    //  height
    //  enableScreenshot
    //
    // returns a object that has a property canvas which is a html5 canvas
    createCanvasWebGL: function(options){
      var canvasObj = this._createBasicCanvasObj(options);
      canvasObj.contextOptions = options.contextOptions;
      return canvasObj;
    },

    createCanvasRGB: function(options){
      var canvasObj = this._createBasicCanvasObj(options);
      return canvasObj;
    },

    // part that is the same for webGL and RGB
    _createBasicCanvasObj: function(options){
      options = options || {};

      var obj = {};
      var width = options.width;
      if (!width){
        width = this._config.size.width;
      };
      var height = options.height;
      if (!height){
        height = this._config.size.height;
      };
      obj.canvas = document.createElement('canvas');
      obj.canvas.width = width;
      obj.canvas.height = height;
      obj.canvas.style.backgroundColor = "#0D0E1B";


      return obj;
    },

    // options:
    //
    // canvas
    // data
    renderFrameWebGL: function(options){

      var canvasObj = options.canvasObj;

      var width = options.width || canvasObj.canvas.width;
      var height = options.height || canvasObj.canvas.height;

      var newCanvas = canvasObj.canvas.width !== width ||
          canvasObj.canvas.height !== height || !canvasObj.webGLCanvas;
      if (newCanvas){
        canvasObj.canvas.width = width;
        canvasObj.canvas.height = height;
        canvasObj.webGLCanvas = new WebGLCanvas({
          canvas: canvasObj.canvas,
          contextOptions: canvasObj.contextOptions,
          reusePlaneTextures: this._config.reusePlaneTextures === true,
          width: width,
          height: height
        });
      };

      var ylen = width * height;
      var uvlen = (width / 2) * (height / 2);

      // CPU command submission vs draw + 1x1 GPU readback wall time.
      // A pixel read forces completion of the rendered work on supported
      // WebGL implementations; gl.finish() alone does not on this WebView.
      // Never read pixels outside the explicitly enabled benchmark.
      var benchmark = this._config.benchmarkPlaneTextures === true &&
          typeof performance !== 'undefined' && typeof performance.now === 'function';
      var startedAt = benchmark ? performance.now() : 0;
      canvasObj.webGLCanvas.drawNextOutputPicture({
        yData: options.data.subarray(0, ylen),
        uData: options.data.subarray(ylen, ylen + uvlen),
        vData: options.data.subarray(ylen + uvlen, ylen + uvlen + uvlen)
      });
      if (benchmark) {
        var cpuMs = performance.now() - startedAt;
        var gl = canvasObj.webGLCanvas.contextGL;
        var readbackMs = null;
        if (gl && typeof gl.readPixels === 'function') {
          try {
            gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE,
                this._planeReadbackPixel);
            readbackMs = performance.now() - startedAt;
          } catch (e) {
            // Diagnostics must not interrupt video if readback is unavailable.
          }
        }
        this.recordPlaneUploadMs(cpuMs, readbackMs, newCanvas);
      }

      var self = this;
      self.recycleMemory(options.data);

    },
    // 300 steady frames per window, bucketed against the 30-upload refresh
    // positions. First decoded picture following a resize is separate.
    recordPlaneUploadMs: function(cpuMs, readbackMs, firstAfterResize) {
      var stats = this._planeUploadStats;
      if (!stats) {
        stats = { frames: 0, regular: [], refresh: [], first: [] };
        this._planeUploadStats = stats;
      }
      if (firstAfterResize) {
        stats.frames = 0;
        stats.regular = [];
        stats.refresh = [];
        stats.first = [];
      }
      stats.frames++;
      // Allocation on frame 1, then refreshes at 31, 61, 91, ...
      var bucket = firstAfterResize ? stats.first :
          ((stats.frames - 1) % 30 === 0 ? stats.refresh : stats.regular);
      bucket.push({ cpu: cpuMs, readback: readbackMs });
      if (stats.regular.length + stats.refresh.length < 300) return;

      function summarize(samples, key) {
        var values = [];
        for (var j = 0; j < samples.length; j++) {
          if (samples[j][key] !== null) values.push(samples[j][key]);
        }
        if (!values.length) return { count: 0 };
        values.sort(function(a, b) { return a - b; });
        var total = 0;
        for (var i = 0; i < values.length; i++) total += values[i];
        return {
          count: values.length,
          meanMs: +(total / values.length).toFixed(3),
          p50Ms: +values[Math.floor((values.length - 1) * 0.50)].toFixed(3),
          p95Ms: +values[Math.floor((values.length - 1) * 0.95)].toFixed(3)
        };
      }
      function summary(samples) {
        return { count: samples.length, cpu: summarize(samples, 'cpu'),
            drawAndReadback: summarize(samples, 'readback') };
      }
      var result = {
        decoder: 'broadway',
        reusePlaneTextures: this._config.reusePlaneTextures === true,
        normal: summary(stats.regular),
        every30th: summary(stats.refresh),
        firstAfterResize: summary(stats.first)
      };
      console.log('[Broadway A/B] ' + JSON.stringify(result));
      if (typeof this.onPlaneUploadSummary === 'function') {
        this.onPlaneUploadSummary(result);
      }
      stats.regular = [];
      stats.refresh = [];
      stats.first = [];
    },
    renderFrameRGB: function(options){
      var canvasObj = options.canvasObj;

      var width = options.width || canvasObj.canvas.width;
      var height = options.height || canvasObj.canvas.height;

      if (canvasObj.canvas.width !== width || canvasObj.canvas.height !== height){
        canvasObj.canvas.width = width;
        canvasObj.canvas.height = height;
      };

      var ctx = canvasObj.ctx;
      var imgData = canvasObj.imgData;

      if (!ctx){
        canvasObj.ctx = canvasObj.canvas.getContext('2d');
        ctx = canvasObj.ctx;

        canvasObj.imgData = ctx.createImageData(width, height);
        imgData = canvasObj.imgData;
      };

      imgData.data.set(options.data);
      ctx.putImageData(imgData, 0, 0);
      var self = this;
      self.recycleMemory(options.data);

    }

  };

  return Player;

}));
