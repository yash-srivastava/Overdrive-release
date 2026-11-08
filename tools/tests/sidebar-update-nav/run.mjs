/**
 * Sidebar More (עוד) round-trip: Connected devices / browsers -> More again.
 * Compares baseline main app-shell vs branch fix with identical viewport/state.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const repoRoot = path.resolve(import.meta.dirname, '../../..');
const assets = path.join(repoRoot, 'app/src/main/assets/web');
const origin = 'http://sidebar-update.invalid';
const SHELL_PATH = 'app/src/main/assets/web/shared/app-shell.js';
const pad = 8;

let baselineShell = null;
try {
  baselineShell = execFileSync('git', ['show', 'main:' + SHELL_PATH], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
} catch (e) {
  console.error('Could not read baseline app-shell from git main:', e.message);
  process.exit(1);
}

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

function makeRouter(shellMode, updateFlowDelayMs) {
  return async function routeAsset(route) {
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

    if (shellMode === 'baseline' && relative.includes('shared/app-shell.js')) {
      return route.fulfill({
        contentType: 'application/javascript',
        body: baselineShell,
      });
    }
    if (updateFlowDelayMs > 0 && relative.includes('shared/update-flow.js')) {
      await new Promise((r) => setTimeout(r, updateFlowDelayMs));
    }
    return route.fulfill({ path: file });
  };
}

/** Full visibility + clickability inside the sidebar scroll container (not loose intersection). */
async function measureUpdateAction(page) {
  return page.evaluate((padPx) => {
    const el = document.getElementById('navUpdateLink');
    const aside = document.getElementById('sidebar');
    if (!el || !aside) {
      return { exists: false, reason: !el ? 'no link' : 'no sidebar' };
    }
    const cs = window.getComputedStyle(el);
    const scrollParent = el.closest('.sidebar') || aside;
    const a = aside.getBoundingClientRect();
    const e = el.getBoundingClientRect();
    const fullInView =
      e.width > 0 &&
      e.height > 0 &&
      e.top >= a.top + padPx &&
      e.bottom <= a.bottom - padPx &&
      e.left >= a.left + padPx &&
      e.right <= a.right - padPx;
    const cx = e.left + e.width / 2;
    const cy = e.top + e.height / 2;
    let hit = null;
    if (fullInView) {
      hit = document.elementFromPoint(cx, cy);
    }
    let hitOk = false;
    if (hit) {
      let n = hit;
      while (n) {
        if (n === el) {
          hitOk = true;
          break;
        }
        n = n.parentElement;
      }
    }
    const brand =
      aside.querySelector('.brand-link') || aside.querySelector('.sidebar-header');
    let brandReachable = false;
    const prev = aside.scrollTop;
    aside.scrollTop = 0;
    if (brand) {
      const br = brand.getBoundingClientRect();
      brandReachable =
        br.bottom > a.top + 4 &&
        br.top < a.bottom - 4 &&
        br.width > 0;
    }
    aside.scrollTop = prev;

    const dashLink = aside.querySelector('.nav-link[href="index.html"]');
    const dashVisible = (() => {
      if (!dashLink || dashLink.hasAttribute('hidden')) return false;
      const dr = dashLink.getBoundingClientRect();
      return dr.width > 0 && dr.bottom > a.top + 4 && dr.top < a.bottom - 4;
    })();

    return {
      exists: true,
      hiddenAttr: el.hasAttribute('hidden'),
      display: cs.display,
      visibility: cs.visibility,
      opacity: cs.opacity,
      pointerEvents: cs.pointerEvents,
      scrollParentTag: scrollParent.tagName,
      scrollParentClass: scrollParent.className,
      sidebarScrollTop: aside.scrollTop,
      sidebarScrollHeight: aside.scrollHeight,
      sidebarClientHeight: aside.clientHeight,
      fullInView,
      clickHitOk: hitOk,
      clickable:
        fullInView &&
        hitOk &&
        cs.display !== 'none' &&
        cs.visibility !== 'hidden' &&
        parseFloat(cs.opacity || '1') > 0.1 &&
        cs.pointerEvents !== 'none',
      brandReachableViaScrollZero: brandReachable,
      dashboardLinkVisibleNow: dashVisible,
      rect: { top: e.top, bottom: e.bottom, left: e.left, right: e.right },
      asideRect: { top: a.top, bottom: a.bottom, left: a.left, right: a.right },
    };
  }, pad);
}

async function openMore(page) {
  await page.evaluate(() => {
    const aside = document.getElementById('sidebar');
    if (typeof window.toggleSidebar === 'function') {
      window.toggleSidebar(true);
    } else if (aside) {
      aside.classList.add('open');
      const ov = document.getElementById('sidebarOverlay');
      if (ov) ov.classList.add('show');
    }
  });
  await page.waitForTimeout(80);
}

async function closeMore(page) {
  await page.evaluate(() => {
    if (typeof window.toggleSidebar === 'function') {
      window.toggleSidebar(false);
    }
  });
  await page.waitForTimeout(40);
}

async function clickDevicesTab(page) {
  const tab = page.locator('button.bottom-tab[data-tab-target="devices"]');
  if (await tab.count()) {
    await tab.first().click();
    await page.waitForTimeout(120);
  }
}

/** Exact user path: More -> Connected devices/browsers page -> More again (x cycles). */
async function reproRoundTrip(page, dest) {
  await page.goto(origin + '/local/index.html', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('navUpdateLink'), null, { timeout: 15000 });
  await openMore(page);
  await closeMore(page);
  await page.goto(origin + dest, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('navUpdateLink'), null, { timeout: 15000 });
  if (dest.includes('network') || dest.includes('notifications')) {
    await clickDevicesTab(page);
  }
  let last = null;
  for (let i = 0; i < 3; i++) {
    await openMore(page);
    last = await measureUpdateAction(page);
    await closeMore(page);
  }
  return last;
}

async function desktopSmoke(page) {
  const snap = await page.evaluate(() => {
    const aside = document.getElementById('sidebar');
    const mobileSheet = !!(
      window.matchMedia &&
      window.matchMedia('(max-width: 768px)').matches &&
      !document.documentElement.hasAttribute('data-android-embed')
    );
    return {
      width: window.innerWidth,
      mobileSheet,
      scrollTop: aside ? aside.scrollTop : null,
    };
  });
  assert.ok(snap.width >= 1000, 'desktop viewport expected');
  assert.equal(snap.mobileSheet, false, 'desktop: not phone More sheet');
  assert.ok(snap.scrollTop !== null, 'sidebar present');
  await page.evaluate(() => {
    if (window.OverdriveAppShell && window.OverdriveAppShell.revealAboutMenuActions) {
      window.OverdriveAppShell.revealAboutMenuActions();
    }
  });
  const detail = await measureUpdateAction(page);
  assert.equal(detail.clickable, true, 'desktop: update action fully visible + clickable');
}

async function runCase(opts) {
  const {
    label,
    shellMode,
    locale,
    rtl,
    viewport,
    dest,
    updateFlowDelayMs = 0,
    expectClickable,
  } = opts;

  const context = await opts.browser.newContext({
    viewport,
    locale: rtl ? (locale === 'he' ? 'he-IL' : 'en-US') : 'en-US',
  });
  const page = await context.newPage();
  await page.route('**/*', makeRouter(shellMode, updateFlowDelayMs));
  await page.addInitScript(
    ({ locale: loc, useRtl }) => {
      localStorage.setItem('byd_jwt', 'dummy');
      localStorage.setItem('overdrive_locale', loc);
      if (useRtl) {
        document.documentElement.setAttribute('dir', 'rtl');
        document.documentElement.setAttribute('lang', loc);
      } else {
        document.documentElement.setAttribute('dir', 'ltr');
        document.documentElement.setAttribute('lang', 'en');
      }
    },
    { locale, useRtl: rtl }
  );

  let status = 'PASS';
  let detail = null;
  let error = null;
  try {
    if (label.startsWith('desktop')) {
      await page.goto(origin + '/local/network.html', { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => document.getElementById('navUpdateLink'));
      await desktopSmoke(page);
    } else if (label.startsWith('delayed-inject')) {
      await page.goto(origin + '/local/index.html', { waitUntil: 'domcontentloaded' });
      await page.waitForFunction(
        () => document.getElementById('sidebar') && typeof window.toggleSidebar === 'function'
      );
      await openMore(page);
      await page.waitForTimeout(updateFlowDelayMs + 200);
      await page.waitForFunction(() => document.getElementById('navUpdateLink'), null, { timeout: 10000 });
      await page.evaluate(
        () =>
          new Promise((resolve) => {
            requestAnimationFrame(() => requestAnimationFrame(resolve));
          })
      );
      detail = await measureUpdateAction(page);
      if (expectClickable) {
        assert.equal(detail.clickable, true, 'late inject while open');
      }
    } else {
      detail = await reproRoundTrip(page, dest);
      if (expectClickable) {
        assert.equal(detail.clickable, true, `${label}: full visibility + click`);
        assert.equal(detail.clickHitOk, true, `${label}: elementFromPoint`);
        assert.equal(detail.fullInView, true, `${label}: full rect in sidebar`);
      } else {
        assert.equal(detail.clickable, false, `${label}: baseline must fail clickability`);
      }
      assert.equal(
        detail.brandReachableViaScrollZero,
        true,
        `${label}: brand must remain discoverable (scroll to top)`
      );
    }
  } catch (e) {
    status = expectClickable ? 'FAIL' : 'FAIL';
    if (expectClickable) error = String(e.message || e);
    detail = detail || (await measureUpdateAction(page).catch(() => null));
    if (!expectClickable) error = String(e.message || e);
  } finally {
    await context.close();
  }

  if (!expectClickable && status === 'PASS' && detail && detail.clickable === false) {
    status = 'BASELINE_FAIL_OK';
  }
  return { label, shellMode, locale, rtl, viewport, status, error, detail };
}

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_EXECUTABLE || '/usr/local/bin/google-chrome',
  headless: true,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

const phoneViewport = { width: 390, height: 844 };
const cases = [
  {
    label: 'repro-network-devices-en',
    shellMode: 'baseline',
    locale: 'en',
    rtl: false,
    viewport: phoneViewport,
    dest: '/local/network.html',
    expectClickable: false,
  },
  {
    label: 'repro-network-devices-en',
    shellMode: 'fixed',
    locale: 'en',
    rtl: false,
    viewport: phoneViewport,
    dest: '/local/network.html',
    expectClickable: true,
  },
  {
    label: 'repro-notifications-devices-he-rtl',
    shellMode: 'baseline',
    locale: 'he',
    rtl: true,
    viewport: phoneViewport,
    dest: '/local/notifications.html',
    expectClickable: false,
  },
  {
    label: 'repro-notifications-devices-he-rtl',
    shellMode: 'fixed',
    locale: 'he',
    rtl: true,
    viewport: phoneViewport,
    dest: '/local/notifications.html',
    expectClickable: true,
  },
  {
    label: 'desktop-network-en',
    shellMode: 'fixed',
    locale: 'en',
    rtl: false,
    viewport: { width: 1280, height: 900 },
    dest: '/local/network.html',
    expectClickable: true,
  },
  {
    label: 'delayed-inject-fixed',
    shellMode: 'fixed',
    locale: 'he',
    rtl: true,
    viewport: phoneViewport,
    updateFlowDelayMs: 1200,
    expectClickable: true,
  },
];

const results = [];
for (const c of cases) {
  const row = await runCase({ ...c, browser });
  results.push(row);
  console.log(
    JSON.stringify({ case: row.label, mode: row.shellMode, status: row.status, clickable: row.detail && row.detail.clickable })
  );
}

await browser.close();

const report = {
  scope:
    'Browser-only; main-branch app-shell served for baseline via git show; identical 390x844 phone viewport',
  limitations: [
    'No in-car WebView / Android embed (data-android-embed)',
    'Gradle unit tests not run (no Android SDK)',
    'Short-viewport-only stress case removed; repro uses standard phone height',
  ],
  results,
};

console.log('\n--- REPORT ---\n' + JSON.stringify(report, null, 2));

const baselineNetwork = results.find(
  (r) => r.label === 'repro-network-devices-en' && r.shellMode === 'baseline'
);
const fixedNetwork = results.find(
  (r) => r.label === 'repro-network-devices-en' && r.shellMode === 'fixed'
);

assert.equal(baselineNetwork.status, 'BASELINE_FAIL_OK', 'baseline must fail full clickability on phone repro');
assert.equal(fixedNetwork.status, 'PASS', 'fixed must pass full clickability on phone repro');

for (const r of results.filter((x) => x.shellMode === 'fixed' && x.expectClickable !== false)) {
  if (r.label === 'delayed-inject-fixed') continue;
  if (r.status !== 'PASS') throw new Error('fixed case failed: ' + r.label + ' ' + (r.error || ''));
}
assert.equal(
  results.find((r) => r.label === 'delayed-inject-fixed').status,
  'PASS',
  'delayed inject'
);

console.log('PASS sidebar-update-nav (baseline vs fixed verified)');
