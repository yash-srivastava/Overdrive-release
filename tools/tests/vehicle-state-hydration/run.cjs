'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
let assertions = 0;
function check(actual, expected) { assert.equal(actual, expected); assertions++; }
function element(id, key) {
  const attrs = key ? {'data-i18n': key} : {};
  const classes = new Set();
  return {id, children: [], style: {}, textContent: '',
    setAttribute(name, value) { attrs[name] = value; },
    getAttribute(name) { return attrs[name]; },
    classList: {add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name)},
    previousElementSibling: {},
    querySelector() { return this.dot ||= {}; }};
}
async function scenario(locale) {
  const nodes = Object.fromEntries(['lockStatus', 'cloudStatusText', 'cloudStatus', 'btnLock', 'btnUnlock'].map(id => [id,
    element(id, id === 'lockStatus' ? 'vehicle.tyre_no_data' : id === 'cloudStatusText' ? 'vehicle.checking' : null)]));
  const cloudControl = element('cloudOnly');
  const errors = [];
  const html = element('html');
  const context = {window: null, document: {readyState: 'loading', documentElement: html,
    addEventListener() {}, getElementById: id => nodes[id] || null,
    querySelectorAll(selector) {
      if (selector === '[data-i18n]') return [nodes.lockStatus, nodes.cloudStatusText];
      if (selector === '[data-requires-cloud="true"]') return [cloudControl];
      return [];
    }}, BYD: {}, VC_LITE: true, Promise,
    localStorage: {getItem: () => null, setItem() {}}, navigator: {language: locale},
    console: {log() {}, error(error) { errors.push(error); }},
    AndroidBridge: {getAppLocale: () => locale, getI18nCatalog: lang =>
      fs.readFileSync(path.join(root, `app/src/main/assets/web/i18n/${lang}.json`), 'utf8')}};
  context.window = context;
  const core = fs.readFileSync(path.join(root, 'app/src/main/assets/web/shared/core.js'), 'utf8');
  const start = core.indexOf('BYD.i18n = (function () {');
  const end = core.indexOf('\n})();', start) + '\n})();'.length;
  assert.ok(start >= 0 && end > start);
  vm.runInNewContext(core.slice(start, end), context, {filename: 'core-i18n.js', timeout: 1000});
  const script = fs.readFileSync(path.join(root, 'app/src/main/assets/web/shared/vehicle-control.js'), 'utf8');
  vm.runInNewContext(script, context, {filename: 'vehicle-control.js', timeout: 1000});
  const vc = context.VC;
  // Isolate state rendering from unrelated model/network/control boot collaborators.
  for (const name of ['refreshModelPickerNames', 'initLiteMode', 'bindControls', 'bindSafetyControls',
    'bindRemoteKeyDiagControls', 'fetchSafetySettings', 'startStateSync', 'startAcChargeCurrentSync',
    'startCloudStatusSync', 'requestCloudLockRefresh', 'startCloudLockSync', 'initCloudModal',
    'initVisibilitySync', 'initViewModeToggle']) vc[name] = () => {};
  vc.vehicleState.locked = true;
  vc.vehicleState.lockScope = 'driver_door';
  vc.vehicleState.cloudState = 'connected';
  vc.init();
  await context.BYD.i18n.init();
  const i18n = context.BYD.i18n;
  const t = i18n.t;
  check(nodes.lockStatus.textContent, t('vehicle.driver_door_locked'));
  check(nodes.cloudStatusText.textContent, t('vehicle.cloud_connected'));
  for (const locked of [null, true, false]) for (const scope of ['vehicle', 'driver_door'])
    for (const cloud of ['checking', 'connected', 'unavailable', 'not_configured']) {
      Object.assign(vc.vehicleState, {locked, lockScope: scope, cloudState: cloud});
      // A locale change must hydrate initial labels, then restore the real live state.
      await i18n.setLang(i18n.getLang() === 'he' ? 'en' : 'he', {persist: false});
      const key = locked === null ? 'vehicle.tyre_no_data' : scope === 'driver_door'
        ? (locked ? 'vehicle.driver_door_locked' : 'vehicle.driver_door_unlocked')
        : (locked ? 'vehicle.locked' : 'vehicle.unlocked');
      check(nodes.lockStatus.textContent, t(key));
      check(nodes.cloudStatusText.textContent, t('vehicle.' + ({checking: 'checking', connected: 'cloud_connected',
        unavailable: 'cloud_unavailable', not_configured: 'cloud_not_configured'}[cloud])));
      check(nodes.cloudStatus.getAttribute('data-cloud-state'), cloud);
      check(cloudControl.getAttribute('data-cloud-state'), cloud);
      check(nodes.btnLock.classList.contains('on'), locked === true && scope === 'vehicle');
      check(nodes.btnUnlock.classList.contains('on'), locked === false && scope === 'vehicle');
    }
  check(errors.length, 0);
  i18n.t = key => key;
  Object.assign(vc.vehicleState, {locked: false, lockScope: 'driver_door', cloudState: 'unavailable'});
  vc.updateHUD(); vc.updateCloudIndicator();
  check(nodes.lockStatus.textContent, 'Driver door unlocked');
  check(nodes.cloudStatusText.textContent, 'Cloud status unavailable');
}
(async () => { for (const locale of ['en', 'fr', 'he']) await scenario(locale);
  console.log(`PASS ${assertions} vehicle hydration and state-label assertions`);
})().catch(error => { console.error(error); process.exitCode = 1; });
