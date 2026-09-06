/**
 * OverDrive Service Worker
 *
 * Two responsibilities:
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
// v4: ev-card-sprite-cache.js bump to v5 and sidebar accordion display:none fix.
const CACHE_VERSION = 'overdrive-3d-v4';

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
  // Precache the 3D pipeline alongside skipWaiting. Use addAll on a
  // fresh cache so a partial failure (one asset missing) drops the
  // whole batch rather than leaving the cache half-populated — better
  // to fall through to the daemon's max-age=1d than serve a stale mix.
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(CACHE_VERSION);
      // {cache: 'reload'} so the install fetch bypasses HTTP cache and
      // pulls fresh bytes — important for GLB/manifest swaps shipped
      // inside an APK update where the on-disk assets changed but the
      // browser cache might still be holding the old copy.
      await cache.addAll(PRECACHE_URLS.map((u) => new Request(u, { cache: 'reload' })));
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

// Cache-first for precached 3D assets; network passthrough for everything
// else. Restricting to same-origin GETs is defensive — we must never
// short-circuit /api/* or push subscription endpoints, and Chrome's SW
// fetch event also fires for cross-origin subresources (CDN tiles for
// Leaflet etc.) which we explicitly want to leave alone.
self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  let url;
  try { url = new URL(req.url); } catch (e) { return; }
  if (url.origin !== self.location.origin) return;
  // Only intercept the static asset paths we actually precached. Other
  // same-origin requests (HTML pages, /api/*) flow straight through.
  const pathname = url.pathname;
  const isPrecacheTarget = PRECACHE_URLS.indexOf(pathname) !== -1;
  if (!isPrecacheTarget) return;

  event.respondWith((async () => {
    const cache = await caches.open(CACHE_VERSION);
    const cached = await cache.match(req, { ignoreSearch: true });
    if (cached) return cached;
    // Cache miss after install (asset added between SW versions, or
    // install-time precache failed). Fetch + store opportunistically
    // so subsequent loads still benefit.
    try {
      const resp = await fetch(req);
      if (resp && resp.ok && resp.type === 'basic') {
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
