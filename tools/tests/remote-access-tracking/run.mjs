import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const assets = path.join(root, 'app/src/main/assets/web');
const current = fs.readFileSync(path.join(assets, 'shared/remote-access.css'), 'utf8');
assert.ok(!/letter-spacing:\s*-[\d.]+(?:em|px)/.test(current));
const baseline = process.env.TRACKING_BASELINE_CSS
  ? fs.readFileSync(process.env.TRACKING_BASELINE_CSS, 'utf8') : null;
if (baseline) assert.equal(current, baseline.replace(/letter-spacing: -\.(?:025|04)em;/g, 'letter-spacing: normal;'));
const html = fs.readFileSync(path.join(assets, 'local/remote-access.html'), 'utf8')
  .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
const core = fs.readFileSync(path.join(assets, 'shared/core.js'), 'utf8');
const start = core.indexOf('BYD.i18n = (function () {');
const end = core.indexOf('\n})();', start) + '\n})();'.length;
assert.ok(start >= 0 && end > start);
const i18n = core.slice(start, end);
const catalogs = Object.fromEntries(['en', 'fr', 'he'].map(lang => [lang,
  fs.readFileSync(path.join(assets, `i18n/${lang}.json`), 'utf8')]));
const browser = await chromium.launch({executablePath: process.env.CHROMIUM_EXECUTABLE || '/opt/chromium/chrome',
  headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking']});
const results = [];
try {
  for (const width of [360, 768, 1280]) for (const locale of ['en', 'fr', 'he']) for (const scale of [1, 2]) {
    const phases = {};
    for (const phase of baseline ? ['before', 'after'] : ['after']) {
      const page = await browser.newPage({viewport: {width, height: 1100}});
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== 'http://tracking.invalid') return route.abort();
        if (url.pathname === '/local/remote-access.html') return route.fulfill({contentType: 'text/html', body: html});
        if (url.pathname === '/_test/font.ttf' && process.env.TRACKING_FIXTURE_FONT) return route.fulfill({contentType: 'font/ttf', path: process.env.TRACKING_FIXTURE_FONT});
        if (url.pathname === '/shared/remote-access.css') return route.fulfill({contentType: 'text/css', body: phase === 'before' ? baseline : current});
        const file = path.resolve(assets, '.' + url.pathname);
        if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return route.abort();
        return route.fulfill({path: file});
      });
      await page.goto('http://tracking.invalid/local/remote-access.html', {waitUntil: 'networkidle'});
      if (locale === 'he' && process.env.TRACKING_FIXTURE_FONT) {
        await page.addStyleTag({content: `@font-face {font-family:TrackingFixture;src:url('/_test/font.ttf')} :root {--family-sans:TrackingFixture,sans-serif;--family-mono:TrackingFixture,monospace}`});
        await page.evaluate(async () => {
          await document.fonts.load('16px TrackingFixture');
          await document.fonts.ready;
          if (!document.fonts.check('16px TrackingFixture', 'שלום')) throw new Error('Hebrew fixture font not ready');
        });
      }
      await page.evaluate(async ({locale, catalogs, i18n, scale}) => {
        window.BYD = {};
        window.AndroidBridge = {getAppLocale: () => locale, getI18nCatalog: lang => catalogs[lang]};
        (0, eval)(i18n);
        await BYD.i18n.init();
        document.getElementById('remoteAccessPage').classList.add('ra-advanced-mode');
        document.getElementById('raRawValue').textContent = '-67';
        document.getElementById('raFilteredValue').textContent = '-70';
        document.getElementById('raAgeValue').textContent = '2 s';
        if (scale !== 1) {
          const sizes = Array.from(document.querySelectorAll('*')).map(node => [node, parseFloat(getComputedStyle(node).fontSize)]);
          for (const [node, size] of sizes) node.style.fontSize = size * scale + 'px';
        }
      }, {locale, catalogs, i18n, scale});
      const data = await page.evaluate(() => ({
        width: innerWidth, documentWidth: document.documentElement.scrollWidth,
        lang: document.documentElement.lang, dir: document.documentElement.dir,
        targets: Array.from(document.querySelectorAll('.ra-body h2, .ra-radar-caption > span:first-child, .ra-metric-value')).map(node => {
          const style = getComputedStyle(node), box = node.getBoundingClientRect();
          return {id: node.id, text: node.textContent, tracking: style.letterSpacing,
            width: box.width, height: box.height, overflow: Math.max(0, node.scrollWidth - node.clientWidth)};
        })
      }));
      assert.equal(data.lang, locale); assert.equal(data.dir, locale === 'he' ? 'rtl' : 'ltr');
      assert.ok(data.targets.length >= 10);
      if (phase === 'after') for (const target of data.targets) {
        assert.ok(target.text.trim().length > 0);
        assert.ok(target.width > 0 && target.height > 0, 'affected target must be visible: ' + target.id);
        assert.ok(target.tracking === 'normal' || parseFloat(target.tracking) >= 0);
      }
      phases[phase] = data;
      if (process.env.EMIT_TRACKING_SCREENSHOTS && width === 768 && locale === 'he' && scale === 1) {
        const image = await page.screenshot({type: 'png', animations: 'disabled'});
        assert.ok(image.length <= 1048576);
        console.log('SCREENSHOT ' + phase + ' ' + image.toString('base64'));
      }
      await page.close();
    }
    if (baseline) {
      assert.ok(phases.after.documentWidth <= phases.before.documentWidth + 1, 'no new document overflow');
      for (let index = 0; index < phases.after.targets.length; index++) {
        assert.ok(phases.after.targets[index].overflow <= phases.before.targets[index].overflow + 1,
          'no added clipping: ' + phases.after.targets[index].id);
      }
    }
    results.push({width, locale, textScale: scale, phases});
  }
  console.log(JSON.stringify({status: 'PASS', renderedCases: results.length, results}));
} finally { await browser.close(); }
