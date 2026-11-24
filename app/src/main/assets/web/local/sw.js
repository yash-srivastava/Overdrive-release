/**
 * OverDrive Service Worker
 *
 * Three responsibilities:
 *
 *  1. Web Push fanout — receives Web Push payloads from the head unit,
 *     renders notifications, and routes taps back into the PWA.
 *
 *  2. EV-card 3D pipeline precache — the sidebar EV card mounts on every
 *     page and pulls in three.js (~600KB) + GLTFLoader + DRACOLoader +
 *     draco WASM + the user's selected GLB (~1.6MB). Without precache,
 *     each cold cache (or after the daemon's 24h max-age expires) costs
 *     a re-fetch on the very next page navigation. Precaching during
 *     install means the first installed-PWA cold start does the heavy
 *     fetch ONCE, and every page from then on hits the SW cache —
 *     three.js boot drops to ~tens of ms instead of hundreds.
 *
 *  3. Offline copy — on links whose /status reports link.offlineCopy, keeps
 *     the last good pages, assets and API replies and serves them, while this
 *     browser is still signed in, unless the car is known to be answering
 *     (see OFFLINE COPY below).
 *
 * Snapshot model: the head unit mints a single-purpose, short-TTL signed
 * token (HS256 over the device secret) and embeds it in the snapshot URL
 * as ?t=<jws>. The SW sets options.image directly to that URL — Chrome
 * fetches it browser-internally for the OS banner without an Authorization
 * header. iOS Safari ignores options.image, so on click we append the
 * snapshot URL as ?hero= to the click target, and events.js renders an
 * inline hero banner so iOS users still see the picture.
 */

// Bump CACHE_VERSION whenever any precached asset changes (vendor JS bump,
// new GLB, ev-card-3d.js logic change). The activate handler deletes any
// cache whose name doesn't match, so old assets are reclaimed.
//
// v2: ev-card-sprite-cache.js shipped its v3 layout-readiness gate
// (clientWidth/Height>0 before keying). Without bumping CACHE_VERSION the
// SW kept serving the v1-precached, pre-fix sprite-cache.js to existing
// installs — so daemon-restart reloads still painted the wide dashboard
// hero sprite into the narrow sidebar canvas (looked compressed), and
// only a hard refresh (which bypasses the SW) cleared it. Bumping forces
// a fresh precache on next visit and reaps overdrive-3d-v1.
//
// v3: dashboard refactor added the 'three-quarter' hero view —
// ev-card-3d.js gained the new camera/rotation branch AND
// ev-card-sprite-cache.js buildKey() now keys 'three-quarter' as a
// distinct bucket (was collapsed to 'side'). BOTH files are precached;
// without this bump the SW would keep serving the old bytes to existing
// installs, so the hero would render side-framed (no three-quarter
// branch) and re-introduce the wrong-sprite-into-canvas symptom.
//
// v4: ev-card-3d.js now honours each model's paintMeshHint. Without a
// cache bump, existing installs keep the brightness-only detector and
// Dolphin paint changes continue targeting its wheel accent instead of
// the body shell.
//
// v5: ev-card-3d.js side-view auto-fit accounts for perspective, so wide
// cards (phone drawer) no longer crop the bumpers; sprite cache v5 too.
const CACHE_VERSION = 'overdrive-3d-v5';

// Static, APK-bundled assets that the EV card needs on every page.
// Same-origin only — the daemon serves these with public, max-age=86400,
// so a SW cache layer underneath gives us "always fast" rather than
// "fast for 24h then re-fetch". Don't precache HTML or sw.js itself
// (the daemon explicitly serves those no-store).
const PRECACHE_URLS = [
  '/shared/ev-card-3d.js',
  '/shared/ev-card-sprite-cache.js',
  '/shared/vendor/three.min.js',
  '/shared/vendor/GLTFLoader.js',
  '/shared/vendor/DRACOLoader.js',
  '/shared/vendor/draco/draco_decoder.js',
  '/shared/vendor/draco/draco_wasm_wrapper.js',
  '/shared/vendor/draco/draco_decoder.wasm',
  '/shared/models/seal.glb'
];

self.addEventListener('install', (event) => {
  // Precache the 3D pipeline alongside skipWaiting. A partial failure
  // (one asset missing, or a tunnel's HTML page in its place) drops the
  // whole batch rather than leaving the cache half-populated — better
  // to fall through to the daemon's max-age=1d than serve a stale mix.
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(CACHE_VERSION);
      // {cache: 'reload'} so the install fetch bypasses HTTP cache and
      // pulls fresh bytes — important for GLB/manifest swaps shipped
      // inside an APK update where the on-disk assets changed but the
      // browser cache might still be holding the old copy.
      const requests = PRECACHE_URLS.map((u) => new Request(u, { cache: 'reload' }));
      const responses = await Promise.all(requests.map((r) => fetch(r)));
      responses.forEach((resp, i) => {
        if (!resp.ok || resp.status === 206 || isHtml(resp)) {
          throw new Error('precache reply rejected: ' + PRECACHE_URLS[i]);
        }
      });
      await Promise.all(responses.map((resp, i) => cache.put(requests[i], resp)));
    } catch (e) {
      // Precache failure is non-fatal — fetch handler falls back to
      // network-first for any URL that's missing from the cache.
      // Common causes: offline at install time, asset path renamed.
      // eslint-disable-next-line no-console
      if (self.console) self.console.warn('[sw] precache failed:', e);
    }
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Reap any caches from earlier CACHE_VERSION values. Without this
    // the user accumulates stale GLBs / vendor JS across app updates
    // until the browser-level Cache Storage quota evicts them.
    const names = await caches.keys();
    await Promise.all(names
      .filter((n) => n !== CACHE_VERSION && n.indexOf('overdrive-3d-') === 0)
      .map((n) => caches.delete(n)));
    await self.clients.claim();
  })());
});

// Cache-first for precached 3D assets; every other request follows the
// offline-copy rules below (plain pass-through unless /status allows a
// copy). Restricting to same-origin GETs is defensive — push subscription
// endpoints must never be short-circuited, and Chrome's SW fetch event
// also fires for cross-origin subresources (CDN tiles for Leaflet etc.)
// which we explicitly want to leave alone.
self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  let url;
  try { url = new URL(req.url); } catch (e) { return; }
  if (url.origin !== self.location.origin) return;
  const pathname = url.pathname;
  const isPrecacheTarget = PRECACHE_URLS.indexOf(pathname) !== -1;
  if (!isPrecacheTarget) {
    routeOffline(event, req, url);
    return;
  }

  event.respondWith((async () => {
    const cache = await caches.open(CACHE_VERSION);
    const cached = await cache.match(req, { ignoreSearch: true });
    if (cached) return cached;
    // Cache miss after install (asset added between SW versions, or
    // install-time precache failed). Fetch + store opportunistically
    // so subsequent loads still benefit.
    try {
      const resp = await fetch(req);
      if (resp && resp.ok && resp.type === 'basic' && !isHtml(resp)) {
        // Clone before reading — Response bodies are single-use.
        try { await cache.put(req, resp.clone()); } catch (e) {}
      }
      return resp;
    } catch (e) {
      // Offline + miss — surface a network failure to the caller.
      // The page-level code already handles vendor-load failures
      // (canvas stays empty, battery overlay still shows SOC).
      return new Response('', { status: 504, statusText: 'offline' });
    }
  })());
});

// ==================== OFFLINE COPY ====================

// Used only where the latest fresh /status says link.offlineCopy, and shown only while this browser's
// sign-in is valid. Unless recent replies show the car answering (linkState), a request with a copy gets
// it at once and a background request checks on the car; a failure or an edge reply gets the copy too.
const OFFLINE_CACHE = 'overdrive-offline-v1';
const OFFLINE_META_PATH = '/__od_offline_meta';
const OFFLINE_MAX_ENTRIES = 400;
const OFFLINE_DATA_MAX_BYTES = 512 * 1024;
const OFFLINE_STATIC_MAX_BYTES = 3 * 1024 * 1024;
const OFFLINE_META_REFRESH_MS = 60000;
const OFFLINE_UPDATE_KEEP_MS = 60000;
const OFFLINE_DATA_WRITE_MS = 30000;
// Evidence lasts 20 s (up) or 60 s (down). With a copy, an up link is waited for until DOWN evidence
// (pages at most 15 s); a background request gets 10 s to send headers.
const LINK_UP_MS = 20000;
const LINK_DOWN_MS = 60000;
const PAGE_WAIT_MS = 15000;
const REFRESH_ABORT_MS = 10000;
const LINK_UP_NOTIFY_MS = 3000;
// core.js asks for the copy at most 8 s after the /status that failed (its STATUS_FETCH_TIMEOUT_MS).
const STATUS_TIMEOUT_MS = 8000;
// A check held 2 s without headers lets another go out for its URL (3 at most); a stored page fetches up
// to 40 of the scripts and styles it names that the copy lacks.
const REFRESH_RETRY_MS = 2000;
const REFRESH_MAX_PER_URL = 3;
const PAGE_ASSETS_MAX = 40;
// After an edge page (see isEdgePage), navigations skip the copy this long or until the car answers; pages
// hear it at once and again after 1 s and 3 s.
const EDGE_PAGE_MS = 300000;
const EDGE_PAGE_TELL_MS = 1000;
const OFFLINE_SKIP_PREFIXES = [
  '/video/', '/hls/', '/h264/', '/thumb/', '/snapshot/', '/ws', '/stream',
  '/api/debug/', '/api/dev-view/', '/api/update/', '/api/stepup/', '/api/logs',
  '/api/backup', '/api/push/', '/auth/'
];
// The only data the copy keeps: these exact paths plus the subpath rules in isOfflineData.
const OFFLINE_DATA_PATHS = [
  '/status', '/api/vehicle/state', '/api/vehicle/cloud-status', '/api/parking/current',
  '/api/parking/status', '/api/parking/sessions', '/api/recordings/stats', '/api/quick-controls',
  '/api/settings/range-display', '/api/performance', '/api/performance/parking-delta',
  '/api/performance/battery', '/api/performance/soh', '/api/performance/hv-cells',
  '/api/performance/top', '/api/performance/data-usage', '/api/trips', '/api/charging',
  '/api/genai/insights/dashboard'
];

// undefined until the stored meta is read, then null or {deviceId, appVersion, offlineCopy, at, sessionExp}.
let offlineMeta;
let offlineMetaRead = null;
// Bumped by every wipe; replies to requests that started before it are never stored.
let offlineGen = 0;
let offlineChain = Promise.resolve();
// Per URL, this worker run: when a data copy was last written, and when the car last served an
// asset (written, or identical to its stored copy, which is then current without being rewritten).
const dataWrittenAt = new Map();
const assetConfirmedAt = new Map();
// Link evidence this worker run (ms): the car answering (up) or not (down); see linkState. carRepliedAt
// is the plain time of the last car reply (linkUpAt may sit a millisecond later to keep the order).
let linkUpAt = 0;
let linkDownAt = 0;
let carRepliedAt = 0;
let linkUpNotifiedAt = 0;
// Requests waiting for the car while the link is up; the next DOWN evidence releases them.
const linkDownWaiters = new Set();
// href -> its background checks in flight ({ac, answered, since}), started while the link is not up.
const refreshing = new Map();
// When this browser's sign-in ends (ms), from pages and Bearer headers; undefined until known.
let sessionExp;
// When a navigation the copy answered got an edge page from the network instead (ms); 0 when none.
let edgePageAt = 0;

function routeOffline(event, req, url) {
  // Reaching the sign-in page means there is no session left to keep a copy for.
  if (req.mode === 'navigate' && url.pathname === '/login.html') {
    event.waitUntil(wipeCopies());
    return;
  }
  const kind = offlineKind(req, url.pathname);
  if (url.pathname === '/status') {
    noteBearer(event, req);
    event.respondWith(statusResponse(event, req, kind));
  } else if (kind && (offlineMeta === undefined || offlineOn())) {
    // On a fresh worker start the stored meta isn't read yet, so the decision waits inside respondWith.
    noteBearer(event, req);
    event.respondWith(offlineResponse(event, req, kind));
  }
}

// 'page' | 'static' | 'data', or null for requests the copy never touches.
function offlineKind(req, path) {
  if (req.headers.has('Range') || req.cache === 'no-store' || req.cache === 'reload') return null;
  if (req.destination === 'video' || req.destination === 'audio') return null;
  if (path === '/sw.js' || path === OFFLINE_META_PATH) return null;
  if (OFFLINE_SKIP_PREFIXES.some((p) => path.indexOf(p) === 0)) return null;
  if (req.mode === 'navigate') {
    return path === '/' || /\.html$/.test(path) || /^\/[^/.]+$/.test(path) ? 'page' : null;
  }
  if (isStaticPath(path)) return 'static';
  return isOfflineData(path) ? 'data' : null;
}

function isStaticPath(path) {
  return path.indexOf('/shared/') === 0 || path.indexOf('/i18n/') === 0 || path === '/manifest.json';
}

function isOfflineData(path) {
  if (OFFLINE_DATA_PATHS.indexOf(path) !== -1 || path.indexOf('/api/parking/sessions/') === 0) return true;
  if (path.indexOf('/api/trips/') === 0) {
    return !/recover|storage|calibration|config/.test(path.slice('/api/trips/'.length));
  }
  if (path.indexOf('/api/charging/') === 0) {
    return !/config|tariffs|history\/clear/.test(path.slice('/api/charging/'.length));
  }
  return false;
}

function offlineOn() {
  return !!offlineMeta && offlineMeta.offlineCopy === true;
}

// Every /status is observed so the meta follows the link; only an "on" link may answer it from the copy.
async function statusResponse(event, req, kind) {
  // core.js sends this only after its own /status attempt failed or timed out: answer from the copy
  // now, and never send a second /status to a slow car (no copy is a plain network error).
  if (headerOf(req, 'X-OverDrive-Want-Copy').trim() === '1') {
    // DOWN evidence only without a car reply since that /status could have started: a page cancelling
    // its poll as it navigates away sends one too.
    noteLinkDown(Date.now() - STATUS_TIMEOUT_MS);
    await readOfflineMeta();
    const copy = kind && offlineOn() ? await usableCopy(new URL(req.url), kind) : null;
    return copy ? serveCopy(copy, kind) : Response.error();
  }
  const gen = offlineGen;
  await readOfflineMeta();
  if (kind && offlineOn() && sessionValid() && linkState() !== 'up') return copyFirst(event, req, kind, gen);
  if (kind && offlineOn()) return networkFirst(event, req, kind, gen);
  const resp = unwrap(await watchFetch(event, req, kind));
  if (offlineOn() && isMarked(resp) && resp.status === 401) {
    await purgeOffline();
  } else if (isFreshStatus(resp)) {
    keepAlive(event, absorb(resp, resp.clone(), req.url, kind, true, gen));
  }
  return resp;
}

async function offlineResponse(event, req, kind) {
  const gen = offlineGen;
  // Decide with the meta in hand: when the copy answers, the page's own request is never fetched (a
  // browser that started the navigation itself while this worker booted cancels that fetch).
  await readOfflineMeta();
  if (kind === 'page' && edgePageActive()) return passThrough(event, req, kind, gen);
  if (offlineOn() && sessionValid() && linkState() !== 'up') return copyFirst(event, req, kind, gen);
  if (offlineOn()) return networkFirst(event, req, kind, gen);
  return unwrap(await watchFetch(event, req, kind));
}

// An edge page lately: a navigation goes to the network as it is and is never answered from the copy, so
// the user gets that page (and the link's attention).
async function passThrough(event, req, kind, gen) {
  const resp = unwrap(await watchFetch(event, req, kind));
  if (isMarked(resp) && resp.status === 401) {
    await purgeOffline();
  } else if (wantsCopy(resp, kind, false)) {
    keepAlive(event, absorb(resp, resp.clone(), req.url, kind, false, gen));
  }
  return resp;
}

// Link down or unknown: the copy answers at once, and one background request per URL checks on the car.
async function copyFirst(event, req, kind, gen) {
  const copy = await usableCopy(new URL(req.url), kind);
  if (!copy) return networkFirst(event, req, kind, gen);
  // No check for an asset: each stored page keeps its scripts and styles in the copy (keepPageAssets).
  if (kind !== 'static') keepAlive(event, refreshCopy(event, req, kind, gen));
  return serveCopy(copy, kind);
}

async function networkFirst(event, req, kind, gen) {
  const url = new URL(req.url);
  const isStatus = url.pathname === '/status';
  // With a copy that may be shown, what goes to the car skips the HTTP cache (forwardOf). Without one the
  // request goes as it is (a navigation reuses the browser's preload) and the reply is awaited however slow.
  const usable = sessionValid() && !!(await findCopy(url, kind));
  const net = watchFetch(event, usable ? forwardOf(req, kind) : req, kind);
  let outcome = usable ? await awaitCar(net, kind) : await net;
  if (!outcome) {
    const copy = await usableCopy(url, kind);
    if (copy) {
      keepAlive(event, net.then((late) => lateReply(late, req.url, kind, isStatus, gen)));
      return serveCopy(copy, kind);
    }
    outcome = await net;
  }
  // A page abort is not a failure.
  const failed = outcome.failed ? !wasAborted(req) : isEdgeReply(outcome.resp, kind);
  if (failed) {
    const copy = await usableCopy(url, kind);
    if (copy) return serveCopy(copy, kind);
  }
  const resp = unwrap(outcome);
  if (isMarked(resp) && resp.status === 401) {
    await purgeOffline();
  } else if (wantsCopy(resp, kind, isStatus)) {
    keepAlive(event, absorb(resp, resp.clone(), req.url, kind, isStatus, gen));
  }
  return resp;
}

// The car's reply, or null when the copy should answer first: at once unless the link is up, and while
// it is up on DOWN evidence from any request; pages get the copy 15 s after they started.
async function awaitCar(net, kind) {
  if (linkState() !== 'up') return null;
  const since = Date.now();
  let timer = null;
  let release = null;
  const racers = [net, new Promise((r) => { release = () => r('down'); })];
  if (kind === 'page') {
    racers.push(new Promise((r) => { timer = setTimeout(() => r('timeout'), PAGE_WAIT_MS); }));
  }
  linkDownWaiters.add(release);
  const won = await Promise.race(racers);
  clearTimeout(timer);
  linkDownWaiters.delete(release);
  if (won === 'timeout') noteLinkDown(since);
  return typeof won === 'string' ? null : won;
}

// One background check per URL while the link is not up: a request of its own (the page's may be
// cancelled under it), past the HTTP cache, aborted without headers after 10 s or once the car answers
// anything (a check the edge holds would hold up later loads of that URL).
function refreshCopy(event, req, kind, gen) {
  const href = req.url;
  const since = Date.now();
  const checks = refreshing.get(href) || [];
  // Another one only when every check in flight has waited 2 s (none has headers while the link is not up):
  // one held over from the outage would otherwise hide the car's return.
  if (checks.length >= REFRESH_MAX_PER_URL) return null;
  if (checks.some((c) => since - c.since < REFRESH_RETRY_MS)) return null;
  const ac = typeof AbortController === 'function' ? new AbortController() : null;
  const init = { headers: req.headers, credentials: 'same-origin', cache: 'no-store', redirect: 'manual' };
  if (ac) init.signal = ac.signal;
  const check = { ac: ac, answered: false, since: since };
  const timer = ac ? setTimeout(() => { noteLinkDown(since); ac.abort(); }, REFRESH_ABORT_MS) : null;
  const done = () => {
    checks.splice(checks.indexOf(check), 1);
    if (!checks.length) refreshing.delete(href);
  };
  const net = watchFetch(event, new Request(href, init), kind, () => { check.answered = true; });
  const task = net.then((outcome) => {
    clearTimeout(timer);
    return lateReply(outcome, href, kind, new URL(href).pathname === '/status', gen);
  }).then(done, done);
  checks.push(check);
  refreshing.set(href, checks);
  return task;
}

function abortHeldChecks() {
  refreshing.forEach((checks) => checks.forEach((c) => { if (!c.answered && c.ac) c.ac.abort(); }));
}

// What goes to the car for a page or data URL with a copy skips the HTTP cache, whose entry a request held
// by the edge would lock for that URL's later loads. A navigation can't be copied, so it gets a request of
// its own (manual redirects, as navigations have); data keeps the page's signal and headers.
function forwardOf(req, kind) {
  if (kind === 'page') {
    return new Request(req.url, {
      headers: req.headers, credentials: 'same-origin', cache: 'no-store', redirect: 'manual'
    });
  }
  return kind === 'data' ? new Request(req, { cache: 'no-store' }) : req;
}

// A reply the page didn't wait for still gets the usual bookkeeping.
function lateReply(outcome, key, kind, isStatus, gen) {
  if (outcome.failed) return null;
  const resp = outcome.resp;
  if (kind === 'page' && isEdgePage(resp)) return noteEdgePage();
  if (isMarked(resp) && resp.status === 401) return purgeOffline();
  return wantsCopy(resp, kind, isStatus) ? absorb(resp, resp, key, kind, isStatus, gen) : null;
}

// What a link in front of the car puts up instead of the page: any redirect (an opaque one tells no more), or
// an unmarked page that is not a 5xx (an unmarked 5xx is the usual outage: DOWN evidence).
function isEdgePage(resp) {
  if (resp.type === 'opaqueredirect') return true;
  return !resp.headers.has('X-OverDrive') && resp.status < 500 && isHtml(resp);
}

// Told again later: the page the copy just answered may not take messages yet.
function noteEdgePage() {
  edgePageAt = Date.now();
  const tell = () => self.clients.matchAll({ type: 'window' }).then((wins) => {
    wins.forEach((win) => win.postMessage({ type: 'od-edge-page' }));
  });
  const later = (ms) => new Promise((r) => { setTimeout(r, ms); }).then(tell);
  return Promise.all([tell(), later(EDGE_PAGE_TELL_MS), later(3 * EDGE_PAGE_TELL_MS)]);
}

function edgePageActive() {
  return edgePageAt > 0 && Date.now() - edgePageAt < EDGE_PAGE_MS;
}

function wantsCopy(resp, kind, isStatus) {
  return canStore(resp, kind) || (isStatus && isFreshStatus(resp));
}

// resp supplies status and headers; body is the stream to read (a clone when resp goes to the page).
async function absorb(resp, body, key, kind, isStatus, gen) {
  const limit = isStatus || kind === 'data' ? OFFLINE_DATA_MAX_BYTES : OFFLINE_STATIC_MAX_BYTES;
  const bytes = await readCapped(body, limit);
  if (!bytes) return;
  let json = null;
  if (isJson(resp)) {
    try { json = JSON.parse(new TextDecoder().decode(bytes)); } catch (e) { return; }
  }
  if (isStatus && isFreshStatus(resp)) {
    gen = await observeStatus(json, gen);
    if (gen < 0) return;
  }
  if (!canStore(resp, kind)) return;
  await storeCopy(key, resp, bytes, gen, kind);
  if (kind === 'page') await keepPageAssets(key, new TextDecoder().decode(bytes), gen);
}

// A stored page brings along the scripts and styles it names that the copy lacks: later loads of them come
// from the browser's memory cache and never reach this worker, and an outage start needs them.
async function keepPageAssets(pageUrl, html, gen) {
  const urls = pageAssetUrls(pageUrl, html);
  let fetched = 0;
  try {
    for (let i = 0; i < urls.length && fetched < PAGE_ASSETS_MAX; i++) {
      if (gen !== offlineGen || !offlineOn()) return;
      const cache = await caches.open(OFFLINE_CACHE);
      let copy = await cache.match(urls[i], { ignoreVary: true });
      if (!copy) {
        // The car just answered; if it stops, the next stored page tries again.
        fetched++;
        const resp = await fetchWithin(urls[i], REFRESH_ABORT_MS);
        if (!resp) return;
        await absorb(resp, resp, urls[i], 'static', false, gen);
        copy = await cache.match(urls[i], { ignoreVary: true });
      }
      // A stylesheet brings what it @imports (styles.css pulls in app-shell.css).
      if (copy && /text\/css/i.test(headerOf(copy, 'Content-Type'))) {
        cssImports(urls[i], await copy.text()).forEach((u) => { if (urls.indexOf(u) === -1) urls.push(u); });
      }
    }
  } catch (e) {}
}

function cssImports(cssUrl, css) {
  const urls = [];
  const imports = /@import\s+(?:url\(\s*)?["']?([^"')\s;]+)/gi;
  let m;
  while ((m = imports.exec(css))) {
    let url = null;
    try { url = new URL(m[1], cssUrl); } catch (e) {}
    if (!url || url.origin !== self.location.origin || !isStaticPath(url.pathname)) continue;
    url.hash = '';
    urls.push(url.href);
  }
  return urls;
}

// Same-origin assets from <script src> and <link rel="stylesheet" href>, in page order.
function pageAssetUrls(pageUrl, html) {
  const urls = [];
  const tags = /<(script|link)\b[^>]*>/gi;
  let m;
  while ((m = tags.exec(html))) {
    const script = m[1].toLowerCase() === 'script';
    if (!script && !/\srel\s*=\s*["']?[^"'>]*\bstylesheet\b/i.test(m[0])) continue;
    const ref = (script ? /\ssrc\s*=\s*["']([^"']+)["']/i : /\shref\s*=\s*["']([^"']+)["']/i).exec(m[0]);
    let url = null;
    try { url = ref ? new URL(ref[1].replace(/&amp;/g, '&'), pageUrl) : null; } catch (e) {}
    if (!url || url.origin !== self.location.origin || !isStaticPath(url.pathname)) continue;
    url.hash = '';
    if (PRECACHE_URLS.indexOf(url.pathname) === -1 && urls.indexOf(url.href) === -1) urls.push(url.href);
  }
  return urls;
}

// The reply once its headers came within ms, else null (aborted, so a held request locks nothing for long).
function fetchWithin(href, ms) {
  const ac = typeof AbortController === 'function' ? new AbortController() : null;
  const init = { credentials: 'same-origin', cache: 'default' };
  if (ac) init.signal = ac.signal;
  const timer = ac ? setTimeout(() => ac.abort(), ms) : null;
  const end = () => clearTimeout(timer);
  return fetch(new Request(href, init)).then((resp) => { end(); return resp; },
    () => { end(); return null; });
}

// Another car wipes the copy, and so does a link that stops allowing it; an app update keeps only
// what this load already saved from the new daemon.
function observeStatus(status, gen) {
  return serialOffline(async () => {
    if (!status || typeof status !== 'object' || gen !== offlineGen) return -1;
    const prev = await readOfflineMeta();
    // Only an explicit boolean changes the link state; the daemon omits it while it can't tell yet.
    const link = status.link;
    const linkKnown = !!link && typeof link.offlineCopy === 'boolean';
    // An empty id or version is unknown: it is never a change and the stored value stays.
    const deviceId = status.deviceId == null ? '' : String(status.deviceId);
    const appVersion = status.appVersion == null ? '' : String(status.appVersion);
    const next = {
      deviceId: deviceId || (prev && prev.deviceId) || '',
      appVersion: appVersion || (prev && prev.appVersion) || '',
      offlineCopy: linkKnown ? link.offlineCopy : !!prev && prev.offlineCopy === true,
      at: Date.now(),
      sessionExp: sessionExp
    };
    const deviceChanged = !!prev && !!prev.deviceId && !!deviceId && prev.deviceId !== deviceId;
    const versionChanged = !!prev && !!prev.appVersion && !!appVersion && prev.appVersion !== appVersion;
    // Entries are only stored while the meta is on, so an "off" meta has nothing left to wipe.
    if (deviceChanged || (!!prev && prev.offlineCopy === true && !next.offlineCopy)) {
      offlineGen++;
      await caches.delete(OFFLINE_CACHE);
    } else if (versionChanged) {
      // No generation bump: replies still in flight on this load come from the updated daemon.
      await dropCopies(next.at - OFFLINE_UPDATE_KEEP_MS);
    }
    const write = !prev || prev.deviceId !== next.deviceId || prev.appVersion !== next.appVersion
      || prev.offlineCopy !== next.offlineCopy
      || (next.offlineCopy && next.at - (prev.at || 0) >= OFFLINE_META_REFRESH_MS);
    if (!write) next.at = prev.at;
    offlineMeta = next;
    if (write) await writeOfflineMeta(next);
    return offlineGen;
  });
}

function purgeOffline() {
  offlineGen++;
  offlineMeta = null;
  return serialOffline(() => caches.delete(OFFLINE_CACHE));
}

// Drops every copy but keeps the meta, so the link stays known as stable and the next load is saved.
function wipeCopies() {
  offlineGen++;
  return serialOffline(() => dropCopies(Infinity));
}

// Deletes the copies saved (or confirmed unchanged by the car) before `before` (ms); the meta stays, and
// unless everything goes (before = Infinity) so do the ?v= assets a remaining page still names.
async function dropCopies(before) {
  if (!(await caches.has(OFFLINE_CACHE))) return;
  const cache = await caches.open(OFFLINE_CACHE);
  const keys = await cache.keys();
  const versioned = [];
  for (let i = 0; i < keys.length; i++) {
    if (new URL(keys[i].url).pathname === OFFLINE_META_PATH) continue;
    if (before !== Infinity && isVersionedAsset(keys[i].url)) {
      versioned.push(keys[i]);
      continue;
    }
    const copy = before === Infinity ? null : await cache.match(keys[i], { ignoreVary: true });
    const seen = copy ? Math.max(cachedAt(copy), assetConfirmedAt.get(keys[i].url) || 0) : 0;
    if (seen < before) await cache.delete(keys[i]);
  }
  if (!versioned.length) return;
  const named = await namedAssets(cache);
  for (let i = 0; i < versioned.length; i++) {
    if (!named.has(versioned[i].url)) await cache.delete(versioned[i]);
  }
}

// The assets the stored pages name, and what the named stylesheets @import.
async function namedAssets(cache) {
  const named = new Set();
  const keys = await cache.keys();
  for (let i = 0; i < keys.length; i++) {
    const path = new URL(keys[i].url).pathname;
    if (path === OFFLINE_META_PATH || isStaticPath(path)) continue;
    if (path === '/status' || path.indexOf('/api/') === 0) continue;
    const page = await cache.match(keys[i], { ignoreVary: true });
    if (page && isHtml(page)) pageAssetUrls(keys[i].url, await page.text()).forEach((u) => named.add(u));
  }
  const queue = Array.from(named);
  for (let i = 0; i < queue.length; i++) {
    const sheet = await cache.match(queue[i], { ignoreVary: true });
    if (!sheet || !/text\/css/i.test(headerOf(sheet, 'Content-Type'))) continue;
    cssImports(queue[i], await sheet.text()).forEach((u) => {
      if (!named.has(u)) { named.add(u); queue.push(u); }
    });
  }
  return named;
}

function storeCopy(key, resp, bytes, gen, kind) {
  return serialOffline(async () => {
    if (gen !== offlineGen || !offlineOn()) return;
    const cache = await caches.open(OFFLINE_CACHE);
    const known = await cache.match(key, { ignoreVary: true });
    const now = Date.now();
    // Fewer disk writes while the car answers: an unchanged asset is not rewritten, and a data URL
    // that already has a copy is rewritten at most every 30 s.
    if (known && kind === 'static' && sameAsset(known, resp)) {
      noteAssetServed(key, now);
      return;
    }
    const age = now - dataWrittenAt.get(key);
    if (known && kind === 'data' && age >= 0 && age < OFFLINE_DATA_WRITE_MS) return;
    // The stored body is already decoded, so its encoding and length headers are rewritten.
    const headers = new Headers(resp.headers);
    headers.delete('Content-Encoding');
    headers.delete('Transfer-Encoding');
    headers.set('Content-Length', String(bytes.byteLength));
    headers.set('X-OverDrive-Cached-At', String(now));
    await cache.put(key, new Response(bytes, {
      status: resp.status, statusText: resp.statusText, headers: headers
    }));
    if (kind === 'data') {
      if (dataWrittenAt.size > 1000) dataWrittenAt.clear();
      dataWrittenAt.set(key, now);
    } else if (kind === 'static') {
      noteAssetServed(key, now);
    }
    if (!known) await trimOffline(cache);
  });
}

// /shared/x.js?v=27 never changes under one URL (pages bump the query), so an app update keeps it.
function isVersionedAsset(href) {
  const url = new URL(href);
  return isStaticPath(url.pathname) && url.searchParams.has('v');
}

function noteAssetServed(key, now) {
  if (assetConfirmedAt.size > 1000) assetConfirmedAt.clear();
  assetConfirmedAt.set(key, now);
}

// Same asset: equal ETag, or without one equal Content-Length and Last-Modified.
function sameAsset(stored, resp) {
  const tag = resp.headers.get('ETag');
  if (tag) return stored.headers.get('ETag') === tag;
  const length = resp.headers.get('Content-Length');
  const modified = resp.headers.get('Last-Modified');
  return !!length && !!modified && stored.headers.get('Content-Length') === length
    && stored.headers.get('Last-Modified') === modified;
}

// Over the cap, data copies go first, then pages, and assets only when nothing else is left (an
// asset the car served this run counts as fresh). keys() lists oldest first; the meta never goes.
async function trimOffline(cache) {
  const data = [];
  const pages = [];
  const assets = [];
  const served = [];
  (await cache.keys()).forEach((k) => {
    const path = new URL(k.url).pathname;
    if (path === OFFLINE_META_PATH) return;
    if (isStaticPath(path)) (assetConfirmedAt.has(k.url) ? served : assets).push(k);
    else (path === '/status' || path.indexOf('/api/') === 0 ? data : pages).push(k);
  });
  served.sort((a, b) => assetConfirmedAt.get(a.url) - assetConfirmedAt.get(b.url));
  const order = data.concat(pages, assets, served);
  for (let i = 0; i < order.length - OFFLINE_MAX_ENTRIES; i++) await cache.delete(order[i]);
}

// Exact URL; a navigation may also take the newest copy of the same path with any query.
async function findCopy(url, kind) {
  try {
    if (!(await caches.has(OFFLINE_CACHE))) return null;
    const cache = await caches.open(OFFLINE_CACHE);
    const usable = (r) => !!r && r.headers.has('X-OverDrive-Cached-At') && isHtml(r) === (kind === 'page');
    const exact = await cache.match(url.href, { ignoreVary: true });
    if (usable(exact)) return exact;
    if (kind !== 'page') return null;
    const sameUrl = await cache.matchAll(url.origin + url.pathname, { ignoreSearch: true, ignoreVary: true });
    let best = null;
    sameUrl.forEach((r) => {
      if (usable(r) && (!best || cachedAt(r) >= cachedAt(best))) best = r;
    });
    return best;
  } catch (e) {
    return null;
  }
}

// The copy to answer with, only while this browser's sign-in is valid; otherwise the copies go.
async function usableCopy(url, kind) {
  const copy = await findCopy(url, kind);
  if (!copy || sessionValid()) return copy;
  await wipeCopies();
  return null;
}

// The link state as served, in X-OverDrive-Link and the shell meta: 'down', or 'unknown' (also while it
// reads up: the car answered, but not this request).
async function serveCopy(copy, kind) {
  const at = cachedAt(copy);
  const link = linkState() === 'down' ? 'down' : 'unknown';
  const headers = new Headers(copy.headers);
  headers.delete('X-OverDrive-Cached-At');
  headers.set('X-OverDrive-Offline', String(at));
  headers.set('X-OverDrive-Link', link);
  let body;
  if (kind === 'page') {
    const html = await copy.text();
    const head = /<head(\s[^>]*)?>/i.exec(html);
    const version = offlineMeta && offlineMeta.appVersion ? offlineMeta.appVersion : '';
    const content = escapeAttr(at + '|' + version + '|' + link);
    const tag = '<meta name="od-offline-shell" content="' + content + '">';
    const end = head ? head.index + head[0].length : -1;
    body = end < 0 ? html : html.slice(0, end) + tag + html.slice(end);
    headers.delete('Content-Length');
  } else {
    body = await copy.arrayBuffer();
  }
  return new Response(body, { status: copy.status, statusText: copy.statusText, headers: headers });
}

function readOfflineMeta() {
  if (offlineMeta !== undefined) return Promise.resolve(offlineMeta);
  if (!offlineMetaRead) {
    offlineMetaRead = (async () => {
      let stored = null;
      try {
        if (await caches.has(OFFLINE_CACHE)) {
          const resp = await (await caches.open(OFFLINE_CACHE)).match(OFFLINE_META_PATH);
          if (resp) stored = await resp.json();
        }
      } catch (e) {}
      // A wipe or a fresh /status that landed meanwhile wins over the stored value, and so does a
      // sign-in end learned meanwhile.
      if (offlineMeta === undefined) {
        offlineMeta = stored && typeof stored === 'object' ? stored : null;
        const exp = offlineMeta ? offlineMeta.sessionExp : undefined;
        if (sessionExp === undefined && typeof exp === 'number') sessionExp = exp;
      }
      return offlineMeta;
    })();
  }
  return offlineMetaRead;
}

async function writeOfflineMeta(meta) {
  const cache = await caches.open(OFFLINE_CACHE);
  await cache.put(OFFLINE_META_PATH, new Response(JSON.stringify(meta), {
    headers: { 'Content-Type': 'application/json' }
  }));
}

// Reads at most limit bytes; null when the body is larger (the rest is cancelled).
async function readCapped(resp, limit) {
  if (!resp.body || typeof resp.body.getReader !== 'function') {
    const whole = new Uint8Array(await resp.arrayBuffer());
    return whole.byteLength <= limit ? whole : null;
  }
  const reader = resp.body.getReader();
  const parts = [];
  let size = 0;
  for (;;) {
    const step = await reader.read();
    if (step.done) break;
    size += step.value.byteLength;
    if (size > limit) {
      reader.cancel().catch(() => {});
      return null;
    }
    parts.push(step.value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  parts.forEach((part) => {
    bytes.set(part, offset);
    offset += part.byteLength;
  });
  return bytes;
}

function serialOffline(task) {
  const run = offlineChain.then(task);
  offlineChain = run.catch(() => {});
  return run;
}

function settle(promise) {
  return promise.then((resp) => ({ resp: resp }), (err) => ({ failed: true, err: err }));
}

function unwrap(outcome) {
  if (outcome.failed) throw outcome.err;
  return outcome.resp;
}

function keepAlive(event, work) {
  const done = Promise.resolve(work).catch(() => {});
  try { event.waitUntil(done); } catch (e) {}
}

function wasAborted(req) {
  return !!(req.signal && req.signal.aborted);
}

// fetch() settled to {resp} or {failed, err}; after onSettled, what it says about the link is noted.
function watchFetch(event, request, kind, onSettled) {
  const since = Date.now();
  return settle(fetch(request)).then((outcome) => {
    if (onSettled) onSettled();
    const notify = noteOutcome(outcome, request, kind, since);
    if (notify) keepAlive(event, notify);
    return outcome;
  });
}

// A marked reply is UP evidence, except a static one (the browser's HTTP cache may hold it); a failure
// the page didn't cause, or an edge reply, is DOWN evidence.
function noteOutcome(outcome, request, kind, since) {
  if (outcome.failed ? !wasAborted(request) : isEdgeReply(outcome.resp, kind)) {
    noteLinkDown(since);
    return null;
  }
  return !outcome.failed && kind !== 'static' && isMarked(outcome.resp) ? noteLinkUp() : null;
}

// 'up' while the newest evidence is a car reply under 20 s old, 'down' while it is a failure under
// 60 s old, otherwise 'unknown'.
function linkState() {
  const now = Date.now();
  if (linkUpAt >= linkDownAt && now - linkUpAt < LINK_UP_MS) return 'up';
  if (linkDownAt > linkUpAt && now - linkDownAt < LINK_DOWN_MS) return 'down';
  return 'unknown';
}

// Newer evidence wins, also within one millisecond. Pages hear when the car answers a link that wasn't up.
function noteLinkUp() {
  const wasUp = linkState() === 'up';
  carRepliedAt = Date.now();
  linkUpAt = Math.max(carRepliedAt, linkDownAt);
  edgePageAt = 0;
  abortHeldChecks();
  if (wasUp || !offlineOn() || linkUpAt - linkUpNotifiedAt < LINK_UP_NOTIFY_MS) return null;
  linkUpNotifiedAt = linkUpAt;
  return self.clients.matchAll({ type: 'window' }).then((wins) => {
    wins.forEach((win) => win.postMessage({ type: 'od-link-up' }));
  });
}

// since: when the request that failed started. A failure says nothing new once the car has answered
// after that (a request held through an outage can fail after the car is back).
function noteLinkDown(since) {
  if (since !== undefined && carRepliedAt > since) return;
  linkDownAt = Math.max(Date.now(), linkUpAt + 1);
  linkDownWaiters.forEach((release) => release());
  linkDownWaiters.clear();
}

function sessionValid() {
  return typeof sessionExp === 'number' && sessionExp > Date.now();
}

// A Bearer token says when this sign-in ends; a missing or malformed one says nothing.
function noteBearer(event, req) {
  const exp = bearerExp(headerOf(req, 'Authorization'));
  if (exp && exp !== sessionExp) keepAlive(event, noteSession(exp));
}

// JWT payload exp (s) as ms, or 0.
function bearerExp(value) {
  const m = /^Bearer\s+[\w-]+\.([\w-]+)\.[\w-]*$/i.exec(value.trim());
  if (!m) return 0;
  try {
    const b64 = m[1].replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(b64 + '='.repeat((4 - b64.length % 4) % 4));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const payload = JSON.parse(new TextDecoder().decode(bytes));
    const exp = payload && typeof payload.exp === 'number' ? payload.exp * 1000 : 0;
    return isFinite(exp) && exp > 0 ? exp : 0;
  } catch (e) {
    return 0;
  }
}

// A sign-in that has ended takes the copies with it (the meta stays); an "on" meta stores the end.
function noteSession(exp) {
  sessionExp = exp;
  const wipe = exp > Date.now() ? null : wipeCopies();
  const save = serialOffline(async () => {
    const meta = await readOfflineMeta();
    if (meta !== offlineMeta || !offlineOn() || meta.sessionExp === sessionExp) return;
    meta.sessionExp = sessionExp;
    await writeOfflineMeta(meta);
  });
  return Promise.all([wipe, save]);
}

function headerOf(resp, name) {
  return resp.headers.get(name) || '';
}

function isHtml(resp) {
  return /text\/html/i.test(headerOf(resp, 'Content-Type'));
}

function isJson(resp) {
  return /json/i.test(headerOf(resp, 'Content-Type'));
}

function isMarked(resp) {
  return headerOf(resp, 'X-OverDrive').trim() === '1';
}

// The edge, not the car, answered: no daemon marker and a 5xx, or for data and static also a 4xx
// or an HTML page. A page's un-marked 404 or 200 (share gone, interstitial) must stay visible.
function isEdgeReply(resp, kind) {
  if (resp.headers.has('X-OverDrive')) return false;
  return resp.status >= 500 || (kind !== 'page' && (resp.status >= 400 || isHtml(resp)));
}

function isFreshStatus(resp) {
  return resp.status === 200 && resp.type === 'basic' && !resp.redirected
    && isMarked(resp) && isJson(resp);
}

// Content-Length may be missing behind an edge that compresses; readCapped enforces the same cap.
function canStore(resp, kind) {
  if (!kind || resp.status !== 200 || resp.type !== 'basic' || resp.redirected) return false;
  // A static asset from the browser's HTTP cache may predate the marker; pages and data need it.
  if (kind !== 'static' && !isMarked(resp)) return false;
  if (isHtml(resp) !== (kind === 'page') || (kind === 'data' && !isJson(resp))) return false;
  const length = resp.headers.get('Content-Length');
  return length === null
    || Number(length) <= (kind === 'data' ? OFFLINE_DATA_MAX_BYTES : OFFLINE_STATIC_MAX_BYTES);
}

function cachedAt(resp) {
  return parseInt(headerOf(resp, 'X-OverDrive-Cached-At'), 10) || 0;
}

function escapeAttr(value) {
  return String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'od-offline-clear') keepAlive(event, purgeOffline());
  // From auth.js on every page load and sign-in/out: when this sign-in ends (ms), 0 for none.
  const exp = event.data && event.data.type === 'od-session' ? event.data.exp : undefined;
  if (typeof exp === 'number' && isFinite(exp)) keepAlive(event, noteSession(exp));
});

readOfflineMeta();

// ==================== PUSH ====================

self.addEventListener('push', (event) => {
  let payload;
  try {
    payload = event.data ? event.data.json() : {};
  } catch (e) {
    payload = { title: 'OverDrive', body: '(unreadable payload)', severity: 'info' };
  }

  event.waitUntil(showFromPayload(payload));
});

function showFromPayload(payload) {
  const title = payload.title || 'OverDrive';
  const severity = payload.severity || 'info';

  const options = {
    body: payload.body || '',
    // Large icon shown next to the body. Use the full app icon edge-to-edge.
    // The OS may still apply a circular mask on Android — that's the system
    // notification rail and not controllable from the SW.
    icon: '/shared/app-icon-dark.webp',
    // Status-bar badge: small monochrome glyph rendered next to the time.
    // Without this, Android falls back to a generic dot which looks worse
    // than the real branding. Same source file — Android renders it
    // monochrome anyway.
    badge: '/shared/app-icon-dark.webp',
    tag: payload.tag || payload.category || 'overdrive',
    timestamp: payload.ts || Date.now(),
    data: payload,
    renotify: severity === 'critical'
  };

  if (severity === 'critical') {
    options.requireInteraction = true;
    options.vibrate = [300, 100, 300, 100, 300];
  } else if (severity === 'warn') {
    options.vibrate = [200];
  }

  // Snapshot rendering: hand the URL straight to options.image. The URL is
  // pre-signed by the head unit (?t=<jws>) so no Authorization header is
  // required — the browser fetches the JPEG itself for the OS banner. We
  // skip the assignment for stage="start" because the hero JPEG isn't
  // written until stopRecording finalises; the matching `final` push will
  // carry the real snapshot URL and replace this banner via tag.
  var snap = payload.data && payload.data.snapshot;
  var stage = payload.data && payload.data.stage;
  if (snap && stage !== 'start') {
    options.image = snap;
    // iOS Safari ignores options.image on Web Push, so we surface the same
    // URL through notification.data → notificationclick appends ?hero=<url>
    // and events.js renders an inline hero banner on the page.
    options.data = Object.assign({}, payload, { heroUrl: snap });
  }

  return self.registration.showNotification(title, options);
}

// ==================== CLICK ====================

self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const data = event.notification.data || {};
  let url = data.url || '/';

  // iOS Safari ignores options.image on Web Push, so the OS banner never
  // shows the snapshot. Forward heroUrl into the click target so the
  // events page can render an inline hero image at the top — gives iOS
  // users the "the photo is part of the alert" UX even though the OS
  // banner can't carry it.
  if (data.heroUrl) {
    const sep = url.indexOf('?') >= 0 ? '&' : '?';
    url = url + sep + 'hero=' + encodeURIComponent(data.heroUrl);
  }

  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const sameOrigin = wins.find((w) => {
      try { return new URL(w.url).origin === self.location.origin; } catch (e) { return false; }
    });

    if (sameOrigin) {
      try { await sameOrigin.focus(); } catch (e) {}
      // Navigating an existing window keeps SW + tab state; openWindow would create a new one.
      try { await sameOrigin.navigate(url); return; } catch (e) {}
      // Fallback: postMessage so the page can route in-app
      try { sameOrigin.postMessage({ type: 'notification-click', payload: data }); return; } catch (e) {}
    }
    await self.clients.openWindow(url);
  })());
});
