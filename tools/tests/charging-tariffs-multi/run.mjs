import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const assets = path.join(root, 'app/src/main/assets/web');

const HOME = {lat: 32.081, lng: 34.781};
const OFFICE = {lat: 32.15, lng: 34.85};

function makeStore() {
  return {
    nextId: 1,
    rows: [],
    sessions: [],
    meta: {
      defaultTariffId: '',
      matchedTariffId: '',
      maxTariffs: 40,
      lat: HOME.lat,
      lng: HOME.lng
    }
  };
}

function tariffsPayload(store) {
  const rows = store.rows.slice(0);
  return {
    success: true,
    tariffs: rows,
    meta: Object.assign({tariffs: rows}, store.meta)
  };
}

function jsonResponse(body) {
  return {status: 200, contentType: 'application/json', body: JSON.stringify(body)};
}

function wireRoutes(page, store) {
  return page.route('**/*', async route => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    if (p.endsWith('/local/charging.html')) {
      let html = fs.readFileSync(path.join(assets, 'local/charging.html'), 'utf8');
      html = html.replace(
        /BYD\.i18n\.init\(\)\.then/,
        "window.__TEST_LOCALE='he';document.documentElement.setAttribute('dir','rtl');BYD.i18n.init().then");
      return route.fulfill({contentType: 'text/html', body: html});
    }
    if (p.startsWith('/api/charging/bootstrap')) {
      return route.fulfill(jsonResponse({
        success: true,
        bootstrap: {
          config: {
            success: true,
            config: {enabled: true, electricityRate: 0.35, currency: '₪', dcRate: 0}
          },
          summary: {success: true, summary: {periodSessions: store.sessions.length, live: {charging: false}}},
          sessions: {success: true, sessions: store.sessions.slice(0)},
          tariffs: tariffsPayload(store),
          soc: {success: true, soc: {points: []}}
        }
      }));
    }
    if (p === '/api/charging/tariffs' && route.request().method() === 'GET') {
      return route.fulfill(jsonResponse(tariffsPayload(store)));
    }
    if (p === '/api/charging/tariffs' && route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}');
      assert.ok(!body.id, 'create must not send id (not an edit)');
      const created = {
        id: 't' + (store.nextId++),
        label: body.label || '',
        lat: body.lat != null ? body.lat : store.meta.lat,
        lng: body.lng != null ? body.lng : store.meta.lng,
        radiusM: body.radiusM || 50,
        acRate: body.acRate || 0,
        dcRate: body.dcRate || 0,
        currency: body.currency || '₪',
        enabled: true,
        useCount: 0
      };
      store.rows.push(created);
      return route.fulfill(jsonResponse({success: true, tariff: created}));
    }
    if (p.startsWith('/api/charging/overview')) {
      return route.fulfill(jsonResponse({
        success: true,
        summary: {},
        sessions: store.sessions.slice(0)
      }));
    }
    if (p.startsWith('/api/charging/')) {
      return route.fulfill(jsonResponse({success: true}));
    }
    if (p.startsWith('/i18n/')) {
      const lang = p.split('/').pop().replace('.json', '');
      const catalog = path.join(assets, 'i18n', lang + '.json');
      if (fs.existsSync(catalog)) {
        return route.fulfill(jsonResponse(JSON.parse(fs.readFileSync(catalog, 'utf8'))));
      }
    }
    const file = path.resolve(assets, '.' + p);
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      return route.abort();
    }
    return route.fulfill({path: file});
  });
}

async function openSettingsTariffs(page) {
  await page.goto('http://tariffs-multi.invalid/local/charging.html#settings', {waitUntil: 'networkidle'});
  await page.waitForSelector('.bottom-tab[data-tab-target="settings"].is-active');
  await page.waitForSelector('#tariffAddBtn', {state: 'visible'});
}

async function saveTariff(page, label, acRate, {pickValue} = {}) {
  await page.click('#tariffAddBtn');
  await page.waitForFunction(() => {
    var ed = document.getElementById('tariffEditor');
    return ed && ed.style.display !== 'none';
  });
  if (pickValue != null) {
    await page.waitForSelector('#tariffLocPick', {state: 'visible'});
    await page.selectOption('#tariffLocPick', pickValue);
  }
  await page.fill('#tariffLabelInput', label);
  await page.fill('#tariffAcRateInput', String(acRate));
  await page.fill('#tariffRadiusInput', '50');
  await page.click('#tariffSaveBtn');
}

const launchOpts = {headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']};
if (process.env.CHROMIUM_EXECUTABLE) launchOpts.executablePath = process.env.CHROMIUM_EXECUTABLE;

const browser = await chromium.launch(launchOpts);
const results = [];
try {
  const page = await browser.newPage({viewport: {width: 412, height: 900}});
  await page.addInitScript(() => {
    window.AndroidBridge = {
      getAppLocale: () => window.__TEST_LOCALE || 'he',
      getI18nCatalog: lang => {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', '/i18n/' + lang + '.json', false);
        xhr.send();
        return xhr.status === 200 ? xhr.responseText : '';
      }
    };
  });

  // A: canonical JSON array, 1 saved tariff, GPS unchanged at same spot — 2nd create must append
  const storeA = makeStore();
  storeA.rows.push({
    id: 't0', label: 'בית', lat: HOME.lat, lng: HOME.lng,
    radiusM: 50, acRate: 1, dcRate: 0, currency: '₪', enabled: true, useCount: 0
  });
  storeA.meta.matchedTariffId = 't0';
  await wireRoutes(page, storeA);
  await openSettingsTariffs(page);
  assert.equal(await page.locator('.tariff-row').count(), 1);
  await saveTariff(page, 'חניה', 1.2);
  await page.waitForFunction(() => document.querySelectorAll('.tariff-row').length === 2);
  assert.equal(storeA.rows.length, 2);
  assert.ok(storeA.rows[0].id !== storeA.rows[1].id);
  assert.equal(storeA.rows[1].lat, HOME.lat);
  assert.equal(storeA.rows[1].lng, HOME.lng);
  results.push({scenario: 'array_same_gps', pass: true});

  await page.unroute('**/*');

  // B: at home GPS, add office tariff via session-history picker (different coordinates)
  const storeB = makeStore();
  storeB.sessions = [
    {
      id: 1, lat: HOME.lat, lng: HOME.lng, placeLabel: 'בית',
      energyAdded: 10, cost: 3, isDc: false, durationMinutes: 60
    },
    {
      id: 2, lat: OFFICE.lat, lng: OFFICE.lng, placeLabel: 'משרד',
      energyAdded: 8, cost: 2.5, isDc: false, durationMinutes: 45
    }
  ];
  await wireRoutes(page, storeB);
  await openSettingsTariffs(page);
  await saveTariff(page, 'בית', 1.0);
  await page.waitForFunction(() => document.querySelectorAll('.tariff-row').length === 1);
  await saveTariff(page, 'משרד', 1.1, {pickValue: OFFICE.lat + ',' + OFFICE.lng});
  await page.waitForFunction(() => document.querySelectorAll('.tariff-row').length === 2);
  assert.equal(storeB.rows[1].lat, OFFICE.lat);
  assert.equal(storeB.rows[1].lng, OFFICE.lng);
  await page.reload({waitUntil: 'networkidle'});
  await page.click('.bottom-tab[data-tab-target="settings"]');
  await page.waitForFunction(() => document.querySelectorAll('.tariff-row').length === 2);
  results.push({scenario: 'session_pick_different_gps', pass: true});

  console.log(JSON.stringify({status: 'PASS', results, totalTariffs: storeB.rows.length}));
} finally {
  await browser.close();
}