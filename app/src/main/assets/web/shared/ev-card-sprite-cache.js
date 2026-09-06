/**
 * Overdrive — EV-card sprite cache.
 *
 * IndexedDB-backed cache of pre-rendered EV-card images keyed by
 * (modelId, paint colour, view, dpr-bucket). The sidebar EV card is
 * structurally identical on every PWA page, so the first page to paint
 * the 3D scene snaps the canvas into a webp Blob and every subsequent
 * page renders that blob into the canvas immediately — bypassing
 * three.js, GLTFLoader, DRACOLoader, and the GLB decode entirely.
 *
 * Cache invalidates whenever the user changes vehicle/colour on
 * vehicle-control.html. The sidebar refresh path (app-shell.js
 * applyVehicleSelection → setEvCardAppearance) is the single funnel
 * for selection changes and also the single funnel that writes/reads
 * sprites — so cache and selection stay in lockstep.
 *
 * Schema:
 *   db = 'overdrive-ev-sprites'
 *   store = 'sprites'
 *   key = `${modelId}|${color}|${view}|${dprBucket}`
 *   value = { blob: Blob, w: number, h: number, ts: number }
 *
 * Why dpr-bucket: the 3D renderer scales pixel-ratio up to 2 for
 * crisp lines. A DPR-1 device repainting a DPR-2 sprite blurs; DPR-2
 * repainting a DPR-1 sprite waste. Bucket the value (1 vs 2) so the
 * key collides cleanly across same-density devices.
 *
 * ES5-friendly. Falls through to a no-op cache on browsers without
 * IndexedDB, Blob, or HTMLImageElement (the BYD WebView floor at
 * Chrome 58 has all three — checked). Lazy-loaded by app-shell.js
 * only on PWA pages, so vehicle-control.html's stricter ES5/Chrome 58
 * scope (see WebView constraint feedback) doesn't bind this file.
 */
(function (root) {
    'use strict';

    if (root.OverdriveEvSpriteCache) return;

    var DB_NAME    = 'overdrive-ev-sprites';
    var DB_VERSION = 1;
    var STORE      = 'sprites';
    // Bump SPRITE_VERSION whenever the rendering pipeline changes in a
    // way that makes prior sprites stale (lighting rig, camera framing,
    // body-paint detection, GLB swap). The version is stamped into the
    // cache key so old sprites become unreachable and LRU eviction
    // sweeps them out — no manual purge required.
    //
    // v2: keys gained a canvas-size bucket so distinct surfaces sharing
    // the same `view` (e.g. sidebar EV-card and dashboard hero, both
    // 'side') stop trampling each other's sprites. v1 entries lack the
    // size segment and become unreachable — eviction reclaims them.
    // v3: sizeBucket() no longer falls back to canvas.width/.height and
    // get()/put() bail when the canvas has no CSS box. v2 sprites taken
    // mid-layout (after a daemon-restart-driven page reload) are keyed
    // by the wrong bucket and re-painting them stretches the car —
    // bumping the version evicts those poisoned entries. The next live
    // v4: sealion7 model support.
    // v5: invalidate stale fallback snapshots and align with updated model catalog.
    var SPRITE_VERSION = 5;

    // Keep the cache from growing unbounded as the user tries paint
    // colours. The realistic upper bound is:
    //   4 models × 6 colours × 2 views (side+top) × 2 dpr buckets = 96.
    // Cap at 128 so a user who experiments past that doesn't immediately
    // start evicting hot keys (the sidebar's side|dpr=N entry must
    // survive even after the user samples a couple of map markers).
    var MAX_ENTRIES = 128;

    var dbPromise = null;

    function supported() {
        return typeof indexedDB !== 'undefined'
            && typeof Blob !== 'undefined'
            && typeof URL !== 'undefined'
            && typeof URL.createObjectURL === 'function'
            && typeof Image !== 'undefined';
    }

    function openDb() {
        if (dbPromise) return dbPromise;
        dbPromise = new Promise(function (resolve, reject) {
            if (!supported()) { reject(new Error('IndexedDB unsupported')); return; }
            var req;
            try { req = indexedDB.open(DB_NAME, DB_VERSION); }
            catch (e) { reject(e); return; }
            req.onupgradeneeded = function () {
                var db = req.result;
                if (!db.objectStoreNames.contains(STORE)) {
                    var store = db.createObjectStore(STORE, { keyPath: 'key' });
                    store.createIndex('ts', 'ts');
                }
            };
            req.onsuccess = function () { resolve(req.result); };
            req.onerror   = function () { dbPromise = null; reject(req.error); };
            req.onblocked = function () { dbPromise = null; reject(new Error('blocked')); };
        });
        return dbPromise;
    }

    function dprBucket() {
        var d = (root.devicePixelRatio || 1);
        // Three.js clamps pixelRatio to min(devicePixelRatio, 2). Match
        // that here so the cached sprite's resolution lines up with the
        // canvas's backing store.
        return d >= 1.5 ? 2 : 1;
    }

    // Bucket the canvas footprint into a coarse "size class". The
    // sidebar EV-card (~280×200) and the dashboard hero (~1180×380 on
    // desktop, ~window-width × 200 on mobile) both render in `view='side'`
    // — without a size segment they collide on a single key and the
    // last surface to render overwrites the other's sprite. Painting
    // it back stretches the larger source into the smaller canvas
    // (or vice versa) and the car looks zoomed-in / squashed.
    //
    // Why the CSS client size and not the backing-buffer width: callers
    // that read the cache may not have laid out the canvas yet, but
    // they know its CSS box. Snapshot-time we ALSO have CSS box (the
    // 3D canvas was rendered to fit it). Both sides agree at CSS-box
    // resolution; backing buffer would re-introduce DPR drift after
    // we already bucket DPR separately.
    //
    // 32-px rounding gives forgiveness against minor layout drift
    // (browser zoom, sidebar collapse animations) while still
    // separating the sidebar (~280) from the hero (~1180).
    function sizeBucket(canvasEl) {
        if (!canvasEl) return '0x0';
        // CSS-box ONLY — never fall back to canvas.width/.height. The
        // backing-buffer size is whatever was last set by paintInto / the
        // three.js renderer, so reading it conflates "this canvas is
        // 280×200 in the layout" with "the last sprite painted into this
        // canvas was 280×200" — fine on warm reloads, but after a daemon
        // restart the page reload can hit get()/buildKey while the
        // sidebar is still mid-layout (XHR to /api/models/selected
        // returns before the flex tree finishes resolving). Falling back
        // to canvas.width=300 (the HTML default) in that window picks
        // the WRONG bucket — either a miss key, or worse a key collision
        // with an aux surface — and either way paintInto re-stretches
        // a sprite into a canvas that later resizes around it.
        var w = canvasEl.clientWidth  || 0;
        var h = canvasEl.clientHeight || 0;
        var bw = Math.round(w / 32) * 32;
        var bh = Math.round(h / 32) * 32;
        return bw + 'x' + bh;
    }

    // Layout-readiness gate. The canvas has a real CSS box only after
    // its parent flex tree has resolved — until then clientWidth/Height
    // read 0 and any sprite painted now will be stretched once layout
    // catches up. Callers that want the cache should bail on `false`
    // and either defer or fall through to the live 3D pipeline (which
    // observes its own ResizeObserver and re-autoFits on visibility).
    function hasLayoutSize(canvasEl) {
        if (!canvasEl) return false;
        return (canvasEl.clientWidth || 0) > 0
            && (canvasEl.clientHeight || 0) > 0;
    }

    function buildKey(modelId, color, view, canvasEl) {
        // Lower-case the colour so '#1A1A1E' and '#1a1a1e' don't fork.
        var c = (color || '').toLowerCase();
        // Keep 'top' and 'three-quarter' as distinct buckets; everything
        // else collapses to 'side'. The dashboard hero mounts with
        // view='three-quarter' (app-shell.mountVehicleCanvas → applyToAux →
        // scheduleSpriteSnapshot all preserve it); collapsing it to 'side'
        // here mis-keyed the snapshot so a warm reload painted the narrow
        // side sprite into the wider three-quarter canvas (cropped/zoomed
        // car) and missed the cache on every colour change. Additive — only
        // introduces a new bucket, never re-buckets existing top/side keys.
        var v = (view === 'top' || view === 'three-quarter') ? view : 'side';
        return 'v' + SPRITE_VERSION + '|'
             + (modelId || '') + '|' + c + '|' + v + '|' + dprBucket()
             + '|' + sizeBucket(canvasEl);
    }

    function get(modelId, color, view, canvasEl) {
        // Bail before hitting IndexedDB if the canvas hasn't laid out
        // yet. Reporting "miss" here makes app-shell take the live 3D
        // path, which auto-fits the moment layout resolves. Returning
        // a sprite for whatever 0x0 (or stale-backing-size) bucket
        // happened to match would paint at the wrong aspect.
        if (!hasLayoutSize(canvasEl)) return Promise.resolve(null);
        var key = buildKey(modelId, color, view, canvasEl);
        return openDb().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE, 'readonly');
                var req = tx.objectStore(STORE).get(key);
                req.onsuccess = function () { resolve(req.result || null); };
                req.onerror   = function () { reject(req.error); };
            });
        }).catch(function () { return null; });
    }

    function put(modelId, color, view, canvasEl, blob, w, h) {
        // Don't write a sprite keyed by a 0x0 bucket. Mirror the gate
        // in get() so a snapshot taken from a hidden / pre-layout
        // canvas can't pollute the cache with a key that nothing will
        // ever match anyway.
        if (!hasLayoutSize(canvasEl)) return Promise.resolve(false);
        var key = buildKey(modelId, color, view, canvasEl);
        return openDb().then(function (db) {
            return new Promise(function (resolve) {
                var tx = db.transaction(STORE, 'readwrite');
                var store = tx.objectStore(STORE);
                store.put({ key: key, blob: blob, w: w, h: h, ts: Date.now() });
                tx.oncomplete = function () { resolve(true); evictIfFull(); };
                tx.onerror    = function () { resolve(false); };
                tx.onabort    = function () { resolve(false); };
            });
        }).catch(function () { return false; });
    }

    // Drop every cached sprite whose key starts with `${modelId}|`.
    // Used when the user picks a different model (all colour and view
    // variants of the prior model become useless) or when the model's
    // GLB has been replaced on disk and old sprites would be stale.
    function invalidateModel(modelId) {
        return openDb().then(function (db) {
            return new Promise(function (resolve) {
                var tx = db.transaction(STORE, 'readwrite');
                var store = tx.objectStore(STORE);
                var prefix1 = 'v' + SPRITE_VERSION + '|' + (modelId || '') + '|';
                var prefix2 = (modelId || '') + '|';
                var midTarget = '|' + (modelId || '') + '|';
                var req = store.openCursor();
                req.onsuccess = function () {
                    var cur = req.result;
                    if (!cur) return;
                    if (typeof cur.key === 'string' &&
                        (cur.key.indexOf(prefix1) === 0 ||
                         cur.key.indexOf(prefix2) === 0 ||
                         cur.key.indexOf(midTarget) !== -1)) {
                        cur.delete();
                    }
                    cur['continue']();
                };
                tx.oncomplete = function () { resolve(true); };
                tx.onerror    = function () { resolve(false); };
                tx.onabort    = function () { resolve(false); };
            });
        }).catch(function () { return false; });
    }

    // LRU-ish eviction: when the store crosses MAX_ENTRIES, drop the
    // oldest by ts until we're back under the cap. Cheap because the
    // `ts` index gives us oldest-first iteration directly.
    function evictIfFull() {
        openDb().then(function (db) {
            var tx = db.transaction(STORE, 'readwrite');
            var store = tx.objectStore(STORE);
            var countReq = store.count();
            countReq.onsuccess = function () {
                var n = countReq.result;
                if (n <= MAX_ENTRIES) return;
                var toDrop = n - MAX_ENTRIES;
                var idxReq = store.index('ts').openCursor();
                idxReq.onsuccess = function () {
                    var cur = idxReq.result;
                    if (!cur || toDrop <= 0) return;
                    cur.delete();
                    toDrop--;
                    cur['continue']();
                };
            };
        }).catch(function () {});
    }

    // Paint a cached sprite into the canvas. Sets the backing buffer
    // to the SOURCE dimensions when they match the current backing
    // size (drawImage at 1:1 → no resampling), otherwise stretches.
    // Matching at 1:1 is the common case because both the snapshotter
    // and the painter compute dimensions the same way (clientSize *
    // pixelRatio, with the same Math.min(devicePixelRatio, 2) clamp),
    // so cross-page dimensions land identical on the same device.
    function paintInto(canvasEl, entry) {
        if (!canvasEl || !entry || !entry.blob) return Promise.resolve(false);
        var url = URL.createObjectURL(entry.blob);
        return new Promise(function (resolve) {
            var img = new Image();
            img.onload = function () {
                try {
                    var dpr = Math.min(root.devicePixelRatio || 1, 2);
                    var cw = canvasEl.clientWidth  || canvasEl.width  || entry.w;
                    var ch = canvasEl.clientHeight || canvasEl.height || entry.h;
                    var bw = Math.round(cw * dpr);
                    var bh = Math.round(ch * dpr);
                    if (canvasEl.width !== bw)  canvasEl.width  = bw;
                    if (canvasEl.height !== bh) canvasEl.height = bh;
                    var ctx = canvasEl.getContext('2d');
                    if (!ctx) { resolve(false); return; }
                    ctx.clearRect(0, 0, bw, bh);
                    if (entry.w === bw && entry.h === bh) {
                        // 1:1 blit — no resampling, no quality loss.
                        ctx.drawImage(img, 0, 0);
                    } else {
                        // Fallback for layout drift between pages
                        // (e.g. sidebar has a different padding under
                        // a media query). Aspect-preserving CONTAIN —
                        // never stretch. Stretching across an aspect
                        // mismatch turned a 280×200 sprite into a
                        // 280×130 canvas after a daemon restart and
                        // squashed the car vertically; a hard refresh
                        // happened to land both sides on the same
                        // aspect and so masked the bug.
                        var sw = entry.w || img.naturalWidth  || bw;
                        var sh = entry.h || img.naturalHeight || bh;
                        var sScale = Math.min(bw / sw, bh / sh);
                        var dw = sw * sScale;
                        var dh = sh * sScale;
                        var dx = Math.round((bw - dw) / 2);
                        var dy = Math.round((bh - dh) / 2);
                        ctx.drawImage(img, dx, dy, dw, dh);
                    }
                    resolve(true);
                } catch (e) {
                    resolve(false);
                } finally {
                    try { URL.revokeObjectURL(url); } catch (e) {}
                }
            };
            img.onerror = function () {
                try { URL.revokeObjectURL(url); } catch (e) {}
                resolve(false);
            };
            img.src = url;
        });
    }

    // Snap the current contents of a three.js-driven canvas to a Blob
    // suitable for storage. Webp at q=0.9 is ~25-50KB for a 280×200
    // EV-card sprite — about 50× smaller than the GLB+JS pipeline.
    // PNG fallback for browsers without webp encode (older Chrome on
    // some older WebViews).
    function snapshot(canvasEl) {
        return new Promise(function (resolve) {
            if (!canvasEl) { resolve(null); return; }
            try {
                if (typeof canvasEl.toBlob === 'function') {
                    canvasEl.toBlob(function (blob) {
                        if (blob) { resolve(blob); return; }
                        // Some implementations reject webp silently.
                        canvasEl.toBlob(function (b2) { resolve(b2 || null); }, 'image/png');
                    }, 'image/webp', 0.9);
                } else {
                    // Synchronous data-URL fallback.
                    var dataUrl = canvasEl.toDataURL('image/webp', 0.9);
                    fetch(dataUrl).then(function (r) { return r.blob(); }).then(resolve, function () { resolve(null); });
                }
            } catch (e) { resolve(null); }
        });
    }

    root.OverdriveEvSpriteCache = {
        supported: supported,
        get: get,
        put: put,
        invalidateModel: invalidateModel,
        paintInto: paintInto,
        snapshot: snapshot,
        buildKey: buildKey
    };
}(window));
