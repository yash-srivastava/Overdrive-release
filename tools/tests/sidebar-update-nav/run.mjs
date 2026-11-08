/**
 * Regression: "Check for Updates" must stay in the More/sidebar menu after
 * navigating to Network (devices / connected clients) and reopening the menu.
 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const assets = path.resolve(import.meta.dirname, '../../../app/src/main/assets/web');
const origin = 'http://sidebar-update.invalid';

function apiBody(url) {
  if (url.pathname === '/status') {
    return {
      success: true,
      deviceId: 'dummy',
      appVersion: 'braveheart-v58.0',
      acc: false,
    };
  }
  if (url.pathname === '/auth/status') return { deviceId: 'dummy', authenticated: true };
  if (url.pathname === '/api/settings/appearance') return { locale: 'he', theme: 'dark' };
  if (url.pathname === '/api/models/selected') return { modelId: 'seal', color: '#E8E8EC' };
  return { success: true };
}

async function routeAsset(route) {
  const url = new URL(route.request().url());
  if (url.origin !== origin) return route.abort();
  if (url.pathname === '/status' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/auth/')) {
    return route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(apiBody(url)),
    });
  }
  let relative = decodeURIComponent(url.pathname).replace(/^\//, '');
  if (!relative.includes('/') && !relative.includes('.')) relative = 'local/' + relative + '.html';
  const file = path.resolve(assets, relative);
  if (!fs.existsSync(file)) return route.fulfill({ status: 404, body: 'missing' });
  return route.fulfill({ path: file });
}

async function updateLinkState(page) {
  return page.evaluate(() => {
    const el = document.getElementById('navUpdateLink');
    const aside = document.getElementById('sidebar');
    if (!el) return { exists: false };
    const cs = window.getComputedStyle(el);
    let inViewport = false;
    if (aside) {
      const a = aside.getBoundingClientRect();
      const e = el.getBoundingClientRect();
      inViewport = e.bottom > a.top + 4 && e.top < a.bottom - 4;
    }
    return {
      exists: true,
      hiddenAttr: el.hasAttribute('hidden'),
      display: cs.display,
      visibility: cs.visibility,
      inDom: !!el.parentNode,
      inViewport,
      sidebarScrollTop: aside ? aside.scrollTop : null,
    };
  });
}

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_EXECUTABLE || '/usr/local/bin/google-chrome',
  headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

try {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    locale: 'he-IL',
  });
  const page = await context.newPage();
  await page.route('**/*', routeAsset);
  async function seed(locale, extraLocalStorage) {
    await page.addInitScript(
      ({ locale, extra }) => {
        localStorage.setItem('byd_jwt', 'dummy');
        localStorage.setItem('overdrive_locale', locale);
        if (extra) {
          for (const [k, v] of Object.entries(extra)) localStorage.setItem(k, v);
        }
      },
      { locale, extra: extraLocalStorage }
    );
  }

  await seed('he', {
    'overdrive.navGroup.nav.settings_group': '1',
    'overdrive.navGroup.nav.diagnostics_group': '1',
    'overdrive.navGroup.nav.integrations_group': '1',
  });

  // Fresh: dashboard
  await page.goto(origin + '/local/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('navUpdateLink'));
  let s0 = await updateLinkState(page);
  assert.equal(s0.exists, true, 'index: link injected');
  assert.equal(s0.hiddenAttr, false, 'index: not hidden');

  await page.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  let s1 = await updateLinkState(page);
  assert.equal(s1.hiddenAttr, false, 'index after open More: still visible');

  // Navigate to network (connected devices tab)
  await page.goto(origin + '/local/network.html', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('navUpdateLink'));
  let s2 = await updateLinkState(page);
  console.log('network fresh:', JSON.stringify(s2));
  assert.equal(s2.exists, true, 'network: link exists');
  assert.equal(s2.hiddenAttr, false, 'network: not hidden');

  await page.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  let s3 = await updateLinkState(page);
  console.log('network after More:', JSON.stringify(s3));
  assert.equal(s3.hiddenAttr, false, 'network after More: still visible');

  // Devices tab deep link via hash
  await page.goto(origin + '/local/network.html#devices', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('navUpdateLink'));
  await page.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  let s4 = await updateLinkState(page);
  console.log('network#devices after More:', JSON.stringify(s4));
  assert.equal(s4.hiddenAttr, false, 'network#devices after More: still visible');

  // Round-trip: index -> network -> index -> More
  await page.goto(origin + '/local/index.html', { waitUntil: 'domcontentloaded' });
  await page.goto(origin + '/local/network.html', { waitUntil: 'domcontentloaded' });
  await page.goto(origin + '/local/index.html', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  let s5 = await updateLinkState(page);
  assert.equal(s5.hiddenAttr, false, 'round-trip index: still visible');

  // notifications → Devices tab (connected push clients / browsers)
  await context.close();
  const ctx2 = await browser.newContext({
    viewport: { width: 390, height: 844 },
    locale: 'he-IL',
  });
  const page2 = await ctx2.newPage();
  await page2.route('**/*', routeAsset);
  await page2.addInitScript(
    () => {
      localStorage.setItem('byd_jwt', 'dummy');
      localStorage.setItem('overdrive_locale', 'he');
      localStorage.setItem('overdrive.navGroup.nav.settings_group', '1');
    }
  );
  await page2.goto(origin + '/local/index.html', { waitUntil: 'domcontentloaded' });
  await page2.waitForFunction(() => document.getElementById('navUpdateLink'));
  await page2.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  assert.equal((await updateLinkState(page2)).hiddenAttr, false, 'index/he collapsed groups');
  await page2.goto(origin + '/local/notifications.html', { waitUntil: 'domcontentloaded' });
  await page2.waitForFunction(() => document.getElementById('navUpdateLink'));
  await page2.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(false);
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  const n1 = await updateLinkState(page2);
  console.log('notifications after More:', JSON.stringify(n1));
  assert.equal(n1.hiddenAttr, false, 'notifications after More');

  await ctx2.close();

  // Short viewport: scrollTop=0 used to clip the update link below the fold.
  const ctx3 = await browser.newContext({
    viewport: { width: 390, height: 520 },
    locale: 'he-IL',
  });
  const page3 = await ctx3.newPage();
  await page3.route('**/*', routeAsset);
  await page3.addInitScript(() => {
    localStorage.setItem('byd_jwt', 'dummy');
    localStorage.setItem('overdrive_locale', 'he');
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'he');
  });
  await page3.goto(origin + '/local/notifications.html', { waitUntil: 'domcontentloaded' });
  await page3.waitForFunction(() => document.getElementById('navUpdateLink'));
  await page3.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') window.toggleSidebar(true);
  });
  const n2 = await updateLinkState(page3);
  console.log('notifications short viewport:', JSON.stringify(n2));
  assert.equal(n2.inViewport, true, 'short viewport: update link must be in sidebar viewport');

  await ctx3.close();
  console.log('PASS sidebar-update-nav');
} finally {
  await browser.close();
}
