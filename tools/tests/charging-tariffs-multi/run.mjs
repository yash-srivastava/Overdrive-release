import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const assets = path.join(root, 'app/src/main/assets/web');

const tariffStore = {
  nextId: 1,
  rows: [],
  meta: {
    defaultTariffId: '',
    matchedTariffId: '',
    maxTariffs: 40,
    lat: 32.081,
    lng: 34.781
  }
};

function tariffsPayload() {
  return {
    success: true,
    tariffs: tariffStore.rows.slice(0),
    meta: Object.assign({tariffs: tariffStore.rows.slice(0)}, tariffStore.meta)
  };
}

function jsonResponse(body) {
  return {status: 200, contentType: 'application/json', body: JSON.stringify(body)};
}

async function servePage(page) {
  await page.route('**/*', async route => {
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
          summary: {success: true, summary: {periodSessions: 0, live: {charging: false}}},
          sessions: {success: true, sessions: []},
          tariffs: tariffsPayload(),
          soc: {success: true, soc: {points: []}}
        }
      }));
    }
    if (p === '/api/charging/tariffs' && route.request().method() === 'GET') {
      return route.fulfill(jsonResponse(tariffsPayload()));
    }
  if (p === '/api/charging/tariffs' && route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}');
      if (body.id) {
        const idx = tariffStore.rows.findIndex(t => t.id === body.id);
        if (idx >= 0) Object.assign(tariffStore.rows[idx], body);
      } else {
        const created = {
          id: 't' + (tariffStore.nextId++),
          label: body.label || '',
          lat: body.lat != null ? body.lat : tariffStore.meta.lat,
          lng: body.lng != null ? body.lng : tariffStore.meta.lng,
          radiusM: body.radiusM || 50,
          acRate: body.acRate || 0,
          dcRate: body.dcRate || 0,
          currency: body.currency || '₪',
          enabled: true,
          useCount: 0
        };
        tariffStore.rows.push(created);
        return route.fulfill(jsonResponse({success: true, tariff: created}));
      }
      return route.fulfill(jsonResponse({success: true, tariff: tariffStore.rows.find(t => t.id === body.id)}));
    }
    if (p.startsWith('/api/charging/overview')) {
      return route.fulfill(jsonResponse({success: true, summary: {}, sessions: []}));
    }
    if (p.startsWith('/api/charging/')) {
      return route.fulfill(jsonResponse({success: true}));
    }
    if (p.startsWith('/i18n/')) {
      const lang = p.split('/').pop().replace('.json', '');
      const catalog = path.join(assets, 'i18n', lang + '.json');
      if (fs.existsSync(catalog)) {
        return route.fulfill({contentType: 'application/json', path: catalog});
      }
    }
    const file = path.resolve(assets, '.' + p);
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      return route.abort();
    }
    return route.fulfill({path: file});
  });
}

const launchOpts = {headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']};
if (process.env.CHROMIUM_EXECUTABLE) launchOpts.executablePath = process.env.CHROMIUM_EXECUTABLE;

const browser = await chromium.launch(launchOpts);
try {
  const page = await browser.newPage({viewport: {width: 412, height: 900}});
  await servePage(page);
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
  await page.goto('http://tariffs-multi.invalid/local/charging.html#settings', {waitUntil: 'networkidle'});
  await page.waitForSelector('.bottom-tab[data-tab-target="settings"].is-active');
  await page.waitForSelector('#tariffAddBtn', {state: 'visible'});
  const labels = ['בית', 'עבודה', 'קניון'];
  for (let i = 0; i < labels.length; i++) {
    await page.click('#tariffAddBtn');
    await page.waitForFunction(() => {
      var ed = document.getElementById('tariffEditor');
      return ed && ed.style.display !== 'none';
    });
    await page.fill('#tariffLabelInput', labels[i]);
    await page.fill('#tariffAcRateInput', String(1 + i * 0.1));
    await page.fill('#tariffRadiusInput', '50');
    await page.click('#tariffSaveBtn');
    await page.waitForFunction(n => document.querySelectorAll('.tariff-row').length === n, i + 1);
    const addBtn = await page.$('#tariffAddBtn');
    assert.equal(await addBtn.isDisabled(), false, 'add stays enabled after tariff ' + (i + 1));
  }
  assert.equal(tariffStore.rows.length, 3);
  await page.reload({waitUntil: 'networkidle'});
  await page.click('.bottom-tab[data-tab-target="settings"]');
  await page.waitForSelector('.bottom-tab[data-tab-target="settings"].is-active');
  await page.waitForFunction(() => document.querySelectorAll('.tariff-row').length === 3);
  console.log(JSON.stringify({status: 'PASS', persisted: tariffStore.rows.length}));
} finally {
  await browser.close();
}