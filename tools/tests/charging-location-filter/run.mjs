import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const assets = path.join(root, 'app/src/main/assets/web');
const artifactDir = process.env.CHARGING_FILTER_ARTIFACTS
  || '/opt/cursor/artifacts';
fs.mkdirSync(artifactDir, {recursive: true});

const mock = {
  config: {
    success: true,
    config: {
      enabled: true,
      electricityRate: 0.35,
      dcRate: 0.55,
      currency: '$',
      fastSampleSec: 12,
      isPhev: false,
      nominalKwh: 60
    }
  },
  summary: {
    success: true,
    summary: {
      periodSessions: 3,
      periodEnergyKwh: 45.5,
      periodCost: 15.92,
      periodDcCount: 0,
      periodAcCount: 3,
      periodRangeGained: 180,
      lifetimeSessions: 10,
      lifetimeEnergyKwh: 500,
      lifetimeCost: 120,
      live: {charging: false}
    }
  },
  sessions: {
    success: true,
    sessions: [
      {
        id: 101,
        startTime: Date.now() - 86400000 * 2,
        energyAdded: 20,
        cost: 7,
        peakPower: 7.2,
        isDc: false,
        tariffId: 'neighbor1',
        tariffLabel: 'Neighbor',
        lat: 32.081,
        lng: 34.781,
        durationMinutes: 120,
        startSoc: 20,
        endSoc: 60
      },
      {
        id: 102,
        startTime: Date.now() - 86400000,
        energyAdded: 12.5,
        cost: 4.38,
        peakPower: 7,
        isDc: false,
        tariffId: 'neighbor1',
        tariffLabel: 'Neighbor',
        lat: 32.0811,
        lng: 34.7811,
        durationMinutes: 90,
        startSoc: 40,
        endSoc: 65
      },
      {
        id: 103,
        startTime: Date.now() - 3600000,
        energyAdded: 13,
        cost: 4.55,
        peakPower: 11,
        isDc: false,
        tariffId: 'home1',
        tariffLabel: 'Home',
        lat: 32.09,
        lng: 34.79,
        durationMinutes: 100,
        startSoc: 50,
        endSoc: 75
      }
    ]
  },
  tariffs: {
    success: true,
    tariffs: [
      {
        id: 'neighbor1',
        label: 'Neighbor',
        lat: 32.081,
        lng: 34.781,
        radiusM: 50,
        acRate: 0.35,
        dcRate: 0,
        enabled: true
      },
      {
        id: 'home1',
        label: 'Home',
        lat: 32.09,
        lng: 34.79,
        radiusM: 50,
        acRate: 0.35,
        dcRate: 0,
        enabled: true
      }
    ],
    meta: {defaultTariffId: '', matchedTariffId: '', maxTariffs: 40}
  },
  soc: {success: true, soc: []}
};

function jsonResponse(body) {
  return {contentType: 'application/json', body: JSON.stringify(body)};
}

async function servePage(page, locale) {
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== 'http://charge-filter.invalid') return route.abort();
    const p = url.pathname;
    if (p === '/local/charging.html') {
      let html = fs.readFileSync(path.join(assets, 'local/charging.html'), 'utf8');
      html = html.replace(
        /BYD\.i18n\.init\(\)\.then/,
        'window.__TEST_LOCALE="' + locale + '";BYD.i18n.init().then');
      return route.fulfill({contentType: 'text/html', body: html});
    }
    if (p.startsWith('/api/charging/bootstrap')) {
      return route.fulfill(jsonResponse({
        success: true,
        bootstrap: {
          config: mock.config,
          summary: mock.summary,
          sessions: mock.sessions,
          tariffs: mock.tariffs,
          soc: mock.soc
        }
      }));
    }
    if (p.startsWith('/api/charging/overview')) {
      return route.fulfill(jsonResponse({
        success: true,
        summary: mock.summary.summary,
        sessions: mock.sessions.sessions
      }));
    }
    if (p.startsWith('/i18n/')) {
      const lang = p.split('/').pop().replace('.json', '');
      const catalog = path.join(assets, 'i18n', lang + '.json');
      if (fs.existsSync(catalog)) {
        return route.fulfill({contentType: 'application/json', path: catalog});
      }
    }
    if (p.startsWith('/api/charging/config')) return route.fulfill(jsonResponse(mock.config));
    if (p.startsWith('/api/charging/summary')) return route.fulfill(jsonResponse(mock.summary));
    if (p.startsWith('/api/charging/tariffs')) return route.fulfill(jsonResponse(mock.tariffs));
    if (p.startsWith('/api/charging/soc')) return route.fulfill(jsonResponse(mock.soc));
    if (p.startsWith('/api/charging')) return route.fulfill(jsonResponse(mock.sessions));
    const file = path.resolve(assets, '.' + p);
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      return route.abort();
    }
    return route.fulfill({path: file});
  });
}

async function runCase(browser, {locale, width, screenshotName}) {
  const page = await browser.newPage({viewport: {width, height: 900}});
  await servePage(page, locale);
  await page.addInitScript(locale => {
    window.AndroidBridge = {
      getAppLocale: () => window.__TEST_LOCALE || locale,
      getI18nCatalog: lang => {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', '/i18n/' + lang + '.json', false);
        xhr.send();
        return xhr.status === 200 ? xhr.responseText : '';
      }
    };
  }, locale);
  await page.goto('http://charge-filter.invalid/local/charging.html', {waitUntil: 'networkidle'});
  await page.waitForFunction(() =>
    window.CHARGING && document.getElementById('sessionLocationFilter')
    && document.getElementById('sessionLocationFilter').options.length > 1);
  const beforeCount = await page.locator('.session-card').count();
  assert.equal(beforeCount, 3);
  await page.selectOption('#sessionLocationFilter', 'neighbor1');
  await page.waitForFunction(() =>
    document.querySelectorAll('.session-card').length === 2);
  const energy = await page.locator('#summaryEnergy').textContent();
  assert.ok(energy.includes('32.5'), 'filtered energy: ' + energy);
  const sessions = await page.locator('#summarySessions').textContent();
  assert.equal(sessions.trim(), '2');
  await page.evaluate(() => {
    window.__copied = '';
    if (!navigator.clipboard) {
      navigator.clipboard = {};
    }
    navigator.clipboard.writeText = function (t) {
      window.__copied = t;
      return Promise.resolve();
    };
  });
  await page.click('#copyLocationSummaryBtn');
  await page.waitForFunction(() => window.__copied && window.__copied.includes('Neighbor'));
  const copied = await page.evaluate(() => window.__copied);
  assert.ok(copied.includes('32.5'));
  if (screenshotName) {
    await page.screenshot({
      path: path.join(artifactDir, screenshotName),
      fullPage: false,
      animations: 'disabled'
    });
  }
  await page.close();
}

const launchOpts = {
  headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage']
};
if (process.env.CHROMIUM_EXECUTABLE) {
  launchOpts.executablePath = process.env.CHROMIUM_EXECUTABLE;
}
const browser = await chromium.launch(launchOpts);
try {
  await runCase(browser, {
    locale: 'he',
    width: 412,
    screenshotName: 'charging-location-filter-he-narrow.png'
  });
  await runCase(browser, {
    locale: 'en',
    width: 1280,
    screenshotName: 'charging-location-filter-en-wide.png'
  });
  console.log(JSON.stringify({status: 'PASS', artifacts: artifactDir}));
} finally {
  await browser.close();
}
