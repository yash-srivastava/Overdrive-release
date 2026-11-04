// Run only in a disposable, network-disabled browser sandbox; see README.md.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {parseArgs} from 'node:util';
const {values: args} = parseArgs({options: {
  root: {type: 'string', default: process.cwd()},
  chromium: {type: 'string'}, playwright: {type: 'string', default: 'playwright'},
  font: {type: 'string'}, acorn: {type: 'string'}, source: {type: 'string', default: 'working-tree'},
  screenshot: {type: 'boolean', default: false}
}});
if (args.acorn) {
  const {parse} = await import(args.acorn);
  parse(fs.readFileSync(path.resolve(args.root, 'app/src/main/assets/web/shared/search.js'), 'utf8'), {ecmaVersion: 5});
  console.log('CHECK search.js ES5 syntax PASS');
}
const {chromium} = await import(args.playwright);
const assets = path.resolve(args.root, 'app/src/main/assets/web');
const origin = 'http://fullscreen.invalid';
const result = {source: args.source, scope: 'Actual shipped remote viewer, shell, core and search; dummy APIs/WebSocket only; browser compatibility, not vehicle acceptance', cases: [], assets: {}};
const browser = await chromium.launch({executablePath: args.chromium, headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking']});
let screenshot, failureScreenshot = false;
function apiBody(url) {
  if (url.pathname === '/status') return {success: true, deviceId: 'dummy-device', appVersion: 'braveheart-v58.0',
    soc: {value: 62}, range: {value: 280}, battery: {voltage: 12.5, available: true}, acc: false,
    vehicleDataReady: true, distanceUnit: 'km', pressureUnit: 'kpa', bluetooth: {state: 'off', enabled: false}};
  if (url.pathname === '/auth/status') return {deviceId: 'dummy-device', authenticated: true};
  if (url.pathname === '/api/settings/appearance') return {locale: 'auto', theme: 'dark'};
  if (url.pathname === '/api/models/selected') return {modelId: 'seal', color: '#E8E8EC'};
  if (url.pathname === '/api/models/manifest' || url.pathname === '/api/models/list') return {models: [], version: 0};
  if (url.pathname === '/api/dev-view/session') return {success: true, session: 'dummy-capability', activityReady: true};
  return {success: true, enabled: false, configured: false, available: false, settings: {}, items: []};
}
async function fixture(locale, width) {
  const context = await browser.newContext({viewport: {width, height: 900}, serviceWorkers: 'block'});
  const page = await context.newPage();
  page.setDefaultTimeout(4500);
  const data = {locale, width, errors: [], consoleErrors: [], writes: [], missing: [], external: [], observations: []};
  page.on('pageerror', error => data.errors.push(String(error).slice(0, 250)));
  page.on('console', message => { if (message.type() === 'error') data.consoleErrors.push(message.text().slice(0, 250)); });
  await page.addInitScript(({locale}) => {
    localStorage.setItem('byd_jwt', 'dummy-jwt'); localStorage.setItem('overdrive_locale', locale);
    window.__dummySockets = [];
    class DummyWebSocket extends EventTarget {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(url, protocols) {
        super(); this.url = String(url); this.protocols = protocols; this.readyState = 0; this.bufferedAmount = 0;
        this.sent = []; window.__dummySockets.push(this);
        setTimeout(() => { this.readyState = 1; this.fire('open', new Event('open')); }, 0);
      }
      fire(type, event) { if (typeof this['on' + type] === 'function') this['on' + type](event); this.dispatchEvent(event); }
      send(value) { this.sent.push(typeof value === 'string' ? value.slice(0, 100) : '[binary]'); }
      close(code = 1000, reason = '') { this.readyState = 3; this.fire('close', new CloseEvent('close', {code, reason})); }
    }
    window.WebSocket = DummyWebSocket;
    window.__mediaCalls = 0;
    if (navigator.mediaDevices) navigator.mediaDevices.getUserMedia = async () => {
      window.__mediaCalls++; throw new Error('No media access in fixture');
    };
  }, {locale});
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) { data.external.push(url.origin); return route.abort(); }
    if (url.pathname === '/_fixture/font.ttf' && args.font) return route.fulfill({contentType: 'font/ttf', path: args.font});
    if (url.pathname === '/status' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/auth/')) {
      if (!['GET', 'HEAD'].includes(request.method())) data.writes.push({path: url.pathname, method: request.method(), body: JSON.parse(request.postData() || '{}')});
      if (url.pathname === '/api/models/art') return route.fulfill({contentType: 'image/webp', path: path.join(assets, 'shared/app-icon-dark.webp')});
      return route.fulfill({contentType: 'application/json', body: JSON.stringify(apiBody(url))});
    }
    let relative = decodeURIComponent(url.pathname).replace(/^\//, '');
    if (!relative.includes('/') && !relative.includes('.')) relative = 'local/' + relative + '.html';
    if (relative === 'manifest.json') relative = 'local/manifest.json';
    const file = path.resolve(assets, relative);
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      data.missing.push(url.pathname); return route.fulfill({status: 404, body: 'Missing fixture asset'});
    }
    result.assets[relative] = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    return route.fulfill({path: file});
  });
  try {
  await page.goto(origin + '/local/remote-dev-view.html', {waitUntil: 'domcontentloaded'});
  if (args.font) {
    await page.addStyleTag({content: "@font-face{font-family:Fixture;src:url('/_fixture/font.ttf')} :root{--family-sans:Fixture,sans-serif;--family-mono:Fixture,monospace}"});
    await page.evaluate(async () => { await document.fonts.load('16px Fixture'); await document.fonts.ready; });
  }
  await page.waitForFunction(() => window.OverdriveSearch && OverdriveSearch.ready && window.BYD && BYD.i18n);
  await page.locator('#startButton').click();
  await page.waitForFunction(() => !document.getElementById('stopButton').disabled && window.__dummySockets.length === 1 && window.__dummySockets[0].readyState === 1);
  await page.evaluate(() => { window.__originalSocket = window.__dummySockets[0]; });
  return {context, page, data};
  } catch (error) { await context.close(); throw error; }
}
async function painted(page, label) {
  await page.waitForTimeout(60);
  const state = await page.evaluate(() => {
    const input = document.querySelector('.od-search-input'), overlay = document.querySelector('.od-search-backdrop');
    if (!input) return {open: false};
    const r = input.getBoundingClientRect(), hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return {open: !overlay.hasAttribute('hidden'), focused: document.activeElement === input,
      painted: r.width > 0 && r.height > 0 && hit === input,
      host: overlay.parentElement.id || overlay.parentElement.tagName, query: input.value,
      selection: [input.selectionStart, input.selectionEnd], fullscreen: document.fullscreenElement && document.fullscreenElement.id};
  });
  assert.equal(state.open, true, label + ': open');
  assert.equal(state.painted, true, label + ': input must be topmost at its center: ' + JSON.stringify(state));
  assert.equal(state.focused, true, label + ': input must own focus: ' + JSON.stringify(state));
  if (args.screenshot && !screenshot) screenshot = await page.screenshot({animations: 'disabled'});
  return state;
}
async function ownership(page, data) {
  assert.equal(await page.evaluate(() => window.__dummySockets.length === 1 && window.__dummySockets[0] === window.__originalSocket && window.__originalSocket.readyState === 1 && !document.getElementById('stopButton').disabled), true, 'session and stream identity preserved');
  assert.equal(data.writes.filter(write => write.path === '/api/dev-view/session' && write.method === 'POST').length, 1);
  assert.equal(data.writes.filter(write => write.path !== '/api/dev-view/input' && !(write.path === '/api/dev-view/session' && write.method === 'POST')).length, 0, 'no unexpected API writes');
  assert.deepEqual(data.errors, []); assert.deepEqual(data.consoleErrors, []);
  assert.deepEqual(data.missing, []); assert.deepEqual(data.external, []);
  assert.equal(await page.evaluate(() => window.__mediaCalls), 0);
}
async function enter(page, id = 'viewerCard') {
  await page.evaluate(id => document.getElementById(id).requestFullscreen(), id);
  await page.waitForFunction(id => document.fullscreenElement && document.fullscreenElement.id === id, id);
}
async function exit(page) {
  const previous = await page.evaluate(() => document.fullscreenElement && document.fullscreenElement.id);
  await page.evaluate(() => document.exitFullscreen());
  await page.waitForFunction(previous => !document.fullscreenElement || document.fullscreenElement.id !== previous, previous);
}
async function matrix({page, data}) {
  const inputWrites = () => data.writes.filter(write => write.path === '/api/dev-view/input').length;
  await enter(page);
  await page.locator('#viewerCard').focus();
  await page.evaluate(() => { OverdriveSearch.open(); OverdriveSearch.close(); });
  await page.waitForTimeout(30);
  assert.equal(await page.evaluate(() => document.activeElement.id), 'viewerCard', 'closed overlay must not steal focus in fullscreen');
  await exit(page);
  await enter(page);
  await page.locator('#viewerCard').focus();
  assert.equal(await page.evaluate(() => document.querySelector('.od-search-backdrop').hasAttribute('hidden')), true);
  const begin = inputWrites();
  await page.keyboard.press('Control+k');
  data.observations.push(await painted(page, 'open after native fullscreen entry'));
  await page.locator('.od-search-input').fill('vehicle');
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(140);
  await page.evaluate(() => document.querySelector('.od-search-input').setSelectionRange(1, 4));
  const next = data.locale === 'he' ? 'fr' : 'he';
  await page.evaluate(next => BYD.i18n.setLang(next, {persist: false}), next);
  let state = await painted(page, 'locale change');
  assert.equal(state.query, 'vehicle');
  assert.equal(await page.locator('.od-search-input').getAttribute('placeholder'), await page.evaluate(() => BYD.i18n.t('common.search') + '…'));
  await exit(page);
  state = await painted(page, 'exit while search is open');
  assert.equal(state.host, 'BODY'); assert.deepEqual(state.selection, [1, 4]);
  await enter(page);
  state = await painted(page, 'enter while search is open');
  assert.equal(state.host, 'viewerCard'); assert.deepEqual(state.selection, [1, 4]);
  await page.evaluate(() => {
    const child = document.createElement('div'); child.id = 'nestedFullscreen';
    child.style.cssText = 'background:#111;width:100vw;height:100vh'; document.getElementById('viewerCard').appendChild(child);
  });
  await enter(page, 'nestedFullscreen');
  state = await painted(page, 'nested fullscreen transition');
  assert.equal(state.host, 'nestedFullscreen'); assert.equal(state.query, 'vehicle');
  await exit(page); // Nested fullscreen returns to the original owner first.
  if (await page.evaluate(() => !!document.fullscreenElement)) await exit(page);
  await painted(page, 'return from nested fullscreen');
  await page.evaluate(() => OverdriveSearch.close());
  assert.equal(await page.evaluate(() => document.activeElement.id), 'viewerCard', 'original caller focus restored');
  for (let i = 0; i < 3; i++) {
    await page.evaluate(() => { OverdriveSearch.open(); OverdriveSearch.open(); });
    await painted(page, 'repeated open');
    await page.evaluate(() => OverdriveSearch.close());
    assert.equal(await page.evaluate(() => document.activeElement.id), 'viewerCard', 'repeated open retains caller');
  }
  await page.evaluate(() => { OverdriveSearch.open(); OverdriveSearch.close(); });
  await page.waitForTimeout(40);
  assert.equal(await page.evaluate(() => document.activeElement.id), 'viewerCard', 'close cancels deferred input focus');
  await enter(page);
  await page.locator('#viewerCard').focus(); await page.keyboard.press('Control+k');
  await painted(page, 'native browser Escape precondition');
  await page.keyboard.press('Escape'); await page.waitForTimeout(100);
  const escaped = await page.evaluate(() => ({native: !!document.fullscreenElement, open: !document.querySelector('.od-search-backdrop').hasAttribute('hidden')}));
  data.observations.push({browserEscape: escaped});
  if (escaped.open) { await painted(page, 'palette remains usable after browser fullscreen exit'); await page.evaluate(() => OverdriveSearch.close()); }
  if (escaped.native) await exit(page);
  assert.equal(inputWrites(), begin, 'palette typing, Escape and transitions must not forward remote input');
  // Verify the collector sees intentional keyboard and pointer input.
  await page.locator('#keyboardButton').click();
  await page.keyboard.type('dummy'); await page.keyboard.press('Enter'); await page.waitForTimeout(140);
  assert.ok(data.writes.some(write => write.path === '/api/dev-view/input' && write.body.type === 'text' && write.body.text === 'dummy'));
  assert.ok(data.writes.some(write => write.path === '/api/dev-view/input' && write.body.key === 'enter'));
  await page.evaluate(() => { const c = document.getElementById('remoteFrame'); c.style.display = 'block'; c.width = 320; c.height = 180; });
  await page.locator('#remoteFrame').click({position: {x: 30, y: 30}}); await page.waitForTimeout(50);
  assert.ok(data.writes.some(write => write.path === '/api/dev-view/input' && write.body.type === 'touch' && write.body.phase === 'down'));
  await ownership(page, data);
}
async function prefixed({page, data}) {
  await page.evaluate(() => {
    const nativeElement = Object.getOwnPropertyDescriptor(Document.prototype, 'fullscreenElement').get;
    Object.defineProperty(document, 'fullscreenElement', {configurable: true, get: () => null});
    Object.defineProperty(document, 'webkitFullscreenElement', {configurable: true, get: () => nativeElement.call(document)});
    document.webkitExitFullscreen = document.exitFullscreen.bind(document);
  });
  await page.evaluate(() => document.getElementById('viewerCard').requestFullscreen());
  await page.waitForFunction(() => document.webkitFullscreenElement);
  await page.locator('#viewerCard').focus(); await page.keyboard.press('Control+k');
  assert.equal((await painted(page, 'WebKit element alias')).host, 'viewerCard');
  // Isolate the prefixed event listener while the real top-layer owner remains fullscreen.
  await page.evaluate(() => { document.body.appendChild(document.querySelector('.od-search-backdrop')); document.dispatchEvent(new Event('webkitfullscreenchange')); });
  assert.equal((await painted(page, 'WebKit change event')).host, 'viewerCard');
  await page.evaluate(() => document.webkitExitFullscreen());
  await page.waitForFunction(() => !document.webkitFullscreenElement);
  assert.equal((await painted(page, 'WebKit exit')).host, 'BODY');
  await page.evaluate(() => OverdriveSearch.close()); await ownership(page, data);
}
async function replaced({page, data}, mode) {
  await page.evaluate(() => { document.getElementById('remoteFrame').style.display = 'block'; });
  await enter(page, 'remoteFrame');
  await page.evaluate(mode => {
    const canvas = document.getElementById('remoteFrame'); canvas.tabIndex = 0; canvas.focus();
    window.__realExit = document.exitFullscreen.bind(document);
    if (mode === 'reject') document.exitFullscreen = () => Promise.reject(new Error('Synthetic exit rejection'));
    if (mode === 'throw') document.exitFullscreen = () => { throw new Error('Synthetic exit exception'); };
    if (mode === 'missing') document.exitFullscreen = undefined;
    if (mode === 'pending-cancel' || mode === 'pending-complete') document.exitFullscreen = () => undefined;
    if (mode === 'stale-reject') document.exitFullscreen = () => new Promise((resolve, reject) => { window.__rejectExit = reject; });
    document.webkitExitFullscreen = document.exitFullscreen;
  }, mode);
  await page.evaluate(() => OverdriveSearch.open());
  if (mode === 'normal') {
    await page.waitForFunction(() => !document.fullscreenElement);
    assert.equal((await painted(page, 'replaced canvas fullscreen fallback')).host, 'BODY');
    await page.evaluate(() => OverdriveSearch.close());
  } else {
    await page.waitForTimeout(60);
    assert.equal(await page.evaluate(() => document.querySelector('.od-search-backdrop').hasAttribute('hidden')), true, 'unsupported host must never expose an invisible active palette');
    if (mode === 'pending-cancel' || mode === 'stale-reject') {
      await page.evaluate(() => OverdriveSearch.close());
      await page.evaluate(() => window.__realExit());
      await page.waitForFunction(() => !document.fullscreenElement);
      await page.waitForTimeout(60);
      assert.equal(await page.evaluate(() => document.querySelector('.od-search-backdrop').hasAttribute('hidden') && !document.activeElement.classList.contains('od-search-input')), true, 'exit completion must not reopen canceled search');
      if (mode === 'stale-reject') {
        await page.evaluate(() => { document.getElementById('viewerCard').focus(); OverdriveSearch.open(); window.__rejectExit(new Error('Stale exit rejection')); });
        await painted(page, 'old exit rejection must not close new open request');
        await page.evaluate(() => OverdriveSearch.close());
      }
    } else if (mode === 'pending-complete') {
      await page.evaluate(() => window.__realExit());
      await page.waitForFunction(() => !document.fullscreenElement);
      await painted(page, 'void-returning exit waits for actual fullscreen change');
      await page.evaluate(() => OverdriveSearch.close());
    }
  }
  await ownership(page, data);
}
async function run(name, locale, width, action) {
  let f;
  try {
    f = await fixture(locale, width); f.data.name = name;
    await action(f); f.data.status = 'PASS';
  } catch (error) {
    if (!f) f = {data: {name, locale, width}};
    f.data.status = 'FAIL'; f.data.failure = String(error.stack || error).slice(0, 1600);
    if (args.screenshot && f.page && !failureScreenshot) { screenshot = await f.page.screenshot({animations: 'disabled'}); failureScreenshot = true; }
  } finally {
    if (f.context) await f.context.close();
    result.cases.push(f.data); console.log('CASE ' + name + '/' + locale + '/' + width + ' ' + f.data.status + (f.data.failure ? ' ' + f.data.failure : ''));
  }
}
try {
  for (const locale of ['en', 'fr', 'he']) for (const width of [360, 1280]) await run('native', locale, width, matrix);
  await run('prefixed-api-and-event', 'en', 1280, prefixed);
  for (const mode of ['normal', 'reject', 'throw', 'missing', 'pending-cancel', 'pending-complete', 'stale-reject'])
    await run('replaced-canvas-' + mode, 'en', 1280, f => replaced(f, mode));
} finally { await browser.close(); }
result.status = result.cases.every(item => item.status === 'PASS') ? 'PASS' : 'FAIL';
result.pass = result.cases.filter(item => item.status === 'PASS').length;
console.log('RESULT ' + JSON.stringify(result));
if (args.screenshot && screenshot && screenshot.length <= 1048576) console.log('SCREENSHOT ' + screenshot.toString('base64'));
process.exitCode = result.status === 'PASS' ? 0 : 1;
