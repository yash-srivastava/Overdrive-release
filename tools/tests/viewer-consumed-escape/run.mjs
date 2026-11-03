import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {parseArgs} from 'node:util';
import {fileURLToPath, pathToFileURL} from 'node:url';

const {values} = parseArgs({options: {
  'playwright-module': {type: 'string'}, browser: {type: 'string'}, font: {type: 'string'},
  screenshot: {type: 'boolean', default: false}
}});
const {chromium} = values['playwright-module']
  ? await import(pathToFileURL(path.resolve(values['playwright-module'])).href)
  : await import('playwright-core');
const root = fileURLToPath(new URL('../../../', import.meta.url));
const assets = path.join(root, 'app/src/main/assets/web');
const origin = 'http://viewer-fixture.invalid';
const result = {scope: 'Real shipped browser scripts; intercepted dummy HTTP and WebSocket only; CSS fallback fullscreen',
  cases: [], servedAssetSha256: {}};
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const browser = await chromium.launch({headless: true, ...(values.browser ? {executablePath: values.browser} : {}),
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking']});
let screenshotTaken = false;

function apiBody(url) {
  if (url.pathname === '/status') return {success: true, deviceId: 'dummy-device', soc: {value: 62},
    range: {value: 280}, battery: {voltage: 12.5, available: true}, acc: false, vehicleDataReady: true};
  if (url.pathname === '/auth/status') return {deviceId: 'dummy-device', authenticated: true};
  if (url.pathname === '/api/settings/appearance') return {locale: 'auto', theme: 'dark'};
  if (url.pathname === '/api/models/selected') return {modelId: 'seal', color: '#E8E8EC'};
  if (url.pathname === '/api/models/manifest' || url.pathname === '/api/models/list') return {models: [], version: 0};
  if (url.pathname === '/api/dev-view/session') return {success: true, session: 'dummy-capability', activityReady: true};
  return {success: true, enabled: false, configured: false, available: false, supported: false,
    settings: {}, items: [], events: [], results: []};
}

async function scenario(locale, width) {
  const context = await browser.newContext({viewport: {width, height: 900}, serviceWorkers: 'block'});
  const page = await context.newPage();
  page.setDefaultTimeout(6000);
  const data = {locale, width, checks: [], errors: [], consoleErrors: [], missingAssets: [], blockedExternal: [], writes: []};
  const check = (name, actual, expected) => {
    try { assert.deepEqual(actual, expected); data.checks.push({name, status: 'PASS'}); }
    catch { data.checks.push({name, status: 'FAIL', actual, expected}); }
  };
  const inputs = () => data.writes.filter(write => write.path === '/api/dev-view/input')
    .map(({body: {session, ...payload}}) => payload);
  const fallback = () => page.evaluate(() => document.body.classList.contains('dev-view-focus'));
  const paletteOpen = () => page.evaluate(() => !document.querySelector('.od-search-backdrop').hidden);
  const focusViewer = () => page.locator('#viewerCard').focus();
  async function enterFallback() {
    if (!await fallback()) {
      // Separate capture-textarea focus changes from the real pointer sequence.
      // An initial RTL control delivered button pointerdown without a click.
      await page.locator('#fullscreenButton').evaluate(button => {
        window.__tracingActivation = true;
        const rect = button.getBoundingClientRect();
        window.__fullscreenTrace.push({type: 'before-focus', active: document.activeElement.id,
          scrollX, scrollY, rect: {x: rect.x, y: rect.y, width: rect.width, height: rect.height}});
        button.focus({preventScroll: true});
      });
      await page.locator('#fullscreenButton').click();
    }
    await page.waitForFunction(() => document.body.classList.contains('dev-view-focus'));
    if (!data.firstActivation) data.firstActivation = await page.evaluate(() => window.__fullscreenTrace.slice(-8));
    await page.evaluate(() => { window.__tracingActivation = false; });
    await focusViewer();
    assert.equal(await fallback(), true, 'Positive control must enter real CSS fullscreen fallback');
    assert.equal(await page.evaluate(() => !!document.fullscreenElement), false);
  }
  async function openPalette(shortcut = 'Control+k') {
    await focusViewer();
    await page.keyboard.press(shortcut);
    await page.locator('.od-search-input').waitFor({state: 'visible'});
    await page.waitForFunction(() => document.activeElement === document.querySelector('.od-search-input'));
  }
  page.on('pageerror', error => data.errors.push(String(error).slice(0, 300)));
  page.on('console', message => { if (message.type() === 'error') data.consoleErrors.push(message.text().slice(0, 300)); });
  await page.addInitScript(locale => {
    localStorage.setItem('byd_jwt', 'dummy-jwt');
    localStorage.setItem('overdrive_locale', locale);
    window.__dummySockets = [];
    window.__microphoneCalls = 0;
    class DummyWebSocket extends EventTarget {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(url, protocols) {
        super(); this.url = String(url); this.protocols = protocols; this.readyState = 0; this.bufferedAmount = 0;
        window.__dummySockets.push(this);
        setTimeout(() => { this.readyState = 1; this.fire('open', new Event('open')); }, 0);
      }
      fire(type, event) { if (typeof this['on' + type] === 'function') this['on' + type](event); this.dispatchEvent(event); }
      send() {}
      close(code = 1000, reason = '') { this.readyState = 3; this.fire('close', new CloseEvent('close', {code, reason})); }
    }
    window.WebSocket = DummyWebSocket;
    if (navigator.mediaDevices) navigator.mediaDevices.getUserMedia = async () => {
      window.__microphoneCalls++; throw new Error('Microphone excluded from dummy fixture');
    };
  }, locale);
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== origin) { data.blockedExternal.push(url.origin); return route.abort(); }
    if (values.font && url.pathname === '/_fixture/font.ttf') return route.fulfill({contentType: 'font/ttf', path: values.font});
    if (url.pathname === '/status' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/auth/')) {
      if (!['GET', 'HEAD'].includes(request.method())) data.writes.push({path: url.pathname,
        method: request.method(), body: JSON.parse(request.postData() || '{}')});
      if (url.pathname === '/api/models/art') return route.fulfill({contentType: 'image/webp', path: path.join(assets, 'shared/app-icon-dark.webp')});
      return route.fulfill({contentType: 'application/json', body: JSON.stringify(apiBody(url))});
    }
    let relative = decodeURIComponent(url.pathname).replace(/^\//, '');
    if (!relative.includes('/') && !relative.includes('.')) relative = 'local/' + relative + '.html';
    if (relative === 'manifest.json') relative = 'local/manifest.json';
    const file = path.resolve(assets, relative);
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      data.missingAssets.push(url.pathname); return route.fulfill({status: 404, body: 'Missing fixture asset'});
    }
    result.servedAssetSha256[path.relative(root, file)] = sha(file);
    return route.fulfill({path: file});
  });
  try {
    await page.goto(origin + '/local/remote-dev-view.html', {waitUntil: 'domcontentloaded'});
    if (values.font) {
      await page.addStyleTag({content: "@font-face{font-family:Fixture;src:url('/_fixture/font.ttf')} :root{--family-sans:Fixture,sans-serif;--family-mono:Fixture,monospace}"});
      await page.evaluate(async () => { await document.fonts.load('16px Fixture'); await document.fonts.ready; });
    }
    await page.waitForFunction(() => window.OverdriveSearch && OverdriveSearch.ready);
    await page.waitForTimeout(4100); // Include the shell's deferred search-index harvest.
    check('actual shell/core/search scripts and locale loaded', await page.evaluate(() => ({
      shell: !!window.OverdriveAppShell, core: !!(window.BYD && BYD.core), lang: BYD.i18n.getLang(),
      dir: document.documentElement.dir})), {shell: true, core: true, lang: locale, dir: locale === 'he' ? 'rtl' : 'ltr'});
    await page.locator('#startButton').click();
    await page.waitForFunction(() => !document.getElementById('stopButton').disabled && window.__dummySockets.length > 0);
    await page.evaluate(() => {
      const viewer = document.getElementById('viewerCard');
      viewer.requestFullscreen = undefined; viewer.webkitRequestFullscreen = undefined;
      window.__fullscreenTrace = [];
      const button = document.getElementById('fullscreenButton');
      for (const type of ['pointerdown', 'pointerup', 'click']) document.addEventListener(type, event => {
        if (event.target !== button && !window.__tracingActivation) return;
        const rect = button.getBoundingClientRect();
        window.__fullscreenTrace.push({type, phase: event.eventPhase, target: event.target.id,
          defaultPrevented: event.defaultPrevented, x: event.clientX, y: event.clientY,
          path: event.composedPath().slice(0, 7).map(node => node.id || node.nodeName),
          scrollX, scrollY, rect: {x: rect.x, y: rect.y, width: rect.width, height: rect.height},
          fallback: document.body.classList.contains('dev-view-focus'),
          nativeRequest: typeof viewer.requestFullscreen, webkitRequest: typeof viewer.webkitRequestFullscreen});
      }, true);
      new MutationObserver(() => window.__fullscreenTrace.push({type: 'body-class', value: document.body.className}))
        .observe(document.body, {attributes: true, attributeFilter: ['class']});
    });

    let begin = inputs().length;
    await openPalette();
    await page.locator('.od-search-input').fill('Dummy local search');
    await page.keyboard.press('ArrowDown'); await page.keyboard.press('Escape');
    await page.waitForTimeout(180);
    check('Ctrl+K search typing, arrow and Escape stay local', inputs().slice(begin), []);
    check('Ctrl+K palette closes', await paletteOpen(), false);
    await openPalette('/'); await page.keyboard.press('Escape'); await page.waitForTimeout(180);
    check('slash opens local search without remote leakage', inputs().slice(begin), []);

    await page.locator('#keyboardButton').click();
    begin = inputs().length;
    await page.keyboard.type('dummy-capture');
    await page.keyboard.press('Enter'); await page.keyboard.press('ArrowLeft');
    await page.waitForTimeout(180);
    const capture = inputs().slice(begin);
    check('direct capture text is delivered once', capture.filter(input => input.type === 'text').map(input => input.text).join(''), 'dummy-capture');
    check('direct capture keys are delivered once', capture.filter(input => input.type === 'key').map(input => input.key), ['enter', 'dpad_left']);

    await enterFallback(); begin = inputs().length;
    await openPalette();
    await page.keyboard.press('Escape'); await page.waitForTimeout(180);
    check('first Escape closes focused palette', await paletteOpen(), false);
    check('consumed palette Escape retains viewer fullscreen', await fallback(), true);
    check('palette Escape never reaches remote input', inputs().slice(begin), []);
    if (values.screenshot && !screenshotTaken) {
      const bytes = await page.screenshot({type: 'png', animations: 'disabled'});
      assert.ok(bytes.length <= 1048576); console.log('SCREENSHOT ' + bytes.toString('base64')); screenshotTaken = true;
    }
    await enterFallback(); begin = inputs().length;
    await page.keyboard.press('Escape'); await page.waitForTimeout(180);
    check('viewer-only Escape exits fullscreen', await fallback(), false);
    check('viewer fullscreen Escape does not send remote back', inputs().slice(begin), []);

    await enterFallback(); begin = inputs().length;
    await page.evaluate(() => document.getElementById('viewerCard').addEventListener('keydown', event => event.preventDefault(), {once: true}));
    await page.keyboard.press('Escape'); await page.waitForTimeout(180);
    check('other already consumed Escape retains fullscreen', await fallback(), true);
    check('already consumed Escape never reaches remote input', inputs().slice(begin), []);

    await enterFallback(); begin = inputs().length;
    await openPalette();
    const keyCodeConsumed = await page.evaluate(() => {
      const event = new KeyboardEvent('keydown', {key: 'Unidentified', keyCode: 27, bubbles: true, cancelable: true});
      document.querySelector('.od-search-input').dispatchEvent(event);
      return event.defaultPrevented;
    });
    check('legacy keyCode Escape is consumed by search', keyCodeConsumed, true);
    check('legacy keyCode Escape closes search', await paletteOpen(), false);
    check('legacy keyCode Escape retains fullscreen', await fallback(), true);
    check('legacy keyCode Escape stays local', inputs().slice(begin), []);

    await page.locator('#fullscreenKeyboardButton').click(); begin = inputs().length;
    await page.keyboard.press('Escape'); await page.waitForTimeout(180);
    check('direct capture Escape sends one remote back', inputs().slice(begin), [{type: 'key', key: 'back'}]);
    check('direct capture consumed Escape retains fullscreen', await fallback(), true);
    await enterFallback();
    await page.locator('#fullscreenExitButton').click();
    await focusViewer(); begin = inputs().length;
    await page.keyboard.press('Escape'); await page.waitForTimeout(180);
    check('viewer Escape outside fullscreen sends remote back', inputs().slice(begin), [{type: 'key', key: 'back'}]);
    await page.locator('#stopButton').click(); await page.waitForTimeout(180);
    await focusViewer(); begin = inputs().length;
    await page.keyboard.press('Escape'); await page.keyboard.press('q'); await page.waitForTimeout(180);
    check('stopped viewer never sends keyboard input', inputs().slice(begin), []);
    check('microphone is never opened', await page.evaluate(() => window.__microphoneCalls), 0);
    check('all remote input uses the dummy capability', data.writes.filter(write => write.path === '/api/dev-view/input')
      .every(write => write.body.session === 'dummy-capability'), true);
    check('no unexpected writes', data.writes.filter(write => !['/api/dev-view/session', '/api/dev-view/input'].includes(write.path)), []);
    check('no page errors', data.errors, []);
    check('no console errors', data.consoleErrors, []);
    check('no missing static assets', data.missingAssets, []);
    check('no external resources requested', data.blockedExternal, []);
  } catch (error) {
    data.setupOrRunnerFailure = String(error.stack || error).slice(0, 1200);
    data.failureState = await page.evaluate(() => ({activeElement: document.activeElement && document.activeElement.id,
      activeClass: document.activeElement && document.activeElement.className,
      fallback: document.body.classList.contains('dev-view-focus'), native: !!document.fullscreenElement,
      fullscreenDisabled: document.getElementById('fullscreenButton').disabled,
      stopDisabled: document.getElementById('stopButton').disabled,
      nativeRequest: typeof document.getElementById('viewerCard').requestFullscreen,
      webkitRequest: typeof document.getElementById('viewerCard').webkitRequestFullscreen,
      sockets: (window.__dummySockets || []).map(socket => socket.readyState),
      paletteOpen: !!document.querySelector('.od-search-backdrop') && !document.querySelector('.od-search-backdrop').hidden,
      fullscreenTrace: (window.__fullscreenTrace || []).slice(-60)}));
    if (data.setupOrRunnerFailure.includes('at enterFallback')) {
      await page.locator('#fullscreenButton').evaluate(button => button.click());
      data.programmaticButtonControl = await fallback();
    }
  }
  finally { await context.close(); }
  data.status = data.setupOrRunnerFailure || data.checks.some(check => check.status === 'FAIL') ? 'FAIL' : 'PASS';
  result.cases.push(data);
  console.log('CASE ' + locale + '/' + width + ' ' + data.status + ' ' + JSON.stringify(data.checks.filter(check => check.status === 'FAIL'))
    + (data.setupOrRunnerFailure ? ' ' + data.setupOrRunnerFailure : ''));
}

try {
  for (const locale of ['en', 'fr', 'he']) for (const width of [360, 1280]) await scenario(locale, width);
} finally {
  await browser.close();
  result.status = result.cases.length === 6 && result.cases.every(data => data.status === 'PASS') ? 'PASS' : 'FAIL';
  result.checks = result.cases.reduce((sum, data) => sum + data.checks.length, 0);
  result.failedChecks = result.cases.reduce((sum, data) => sum + data.checks.filter(check => check.status === 'FAIL').length, 0);
  console.log('RESULT ' + JSON.stringify(result));
  process.exitCode = result.status === 'PASS' ? 0 : 1;
}
