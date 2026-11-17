import { chromium } from 'playwright';
import { createServer } from 'http';
import { readFileSync, existsSync, mkdirSync, writeFileSync, cpSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dirname, '..');
const webRoot = join(repoRoot, 'app/src/main/assets/web');
const artifacts = '/opt/cursor/artifacts';
const mode = process.argv[2] || 'after-scrolled';
const SCROLL_Y = 450;

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
};

function ensureBeforeTree() {
  const tmp = join(repoRoot, '.screenshot-tree/web');
  if (!existsSync(tmp)) {
    cpSync(webRoot, tmp, { recursive: true });
  }
  const commit = 'a28b23db';
  for (const rel of ['local/trips.html', 'local/charging.html']) {
    writeFileSync(
      join(tmp, rel),
      execSync(`git show ${commit}:app/src/main/assets/web/${rel}`, {
        cwd: repoRoot,
        encoding: 'utf8',
      }),
    );
  }
  return tmp;
}

const isBefore = mode.includes('before');
const isGap = mode.includes('gap');
const serveRoot = isBefore ? ensureBeforeTree() : webRoot;

function serve(req, res) {
  let p = req.url.split('?')[0];
  if (p === '/') p = '/local/trips.html';
  const file = join(serveRoot, p.replace(/^\//, ''));
  if (!existsSync(file) || !file.startsWith(serveRoot)) {
    res.writeHead(404);
    res.end('not found');
    return;
  }
  const ext = file.slice(file.lastIndexOf('.'));
  res.writeHead(200, { 'Content-Type': mime[ext] || 'application/octet-stream' });
  res.end(readFileSync(file));
}

const server = createServer(serve);
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 412, height: 915 },
  locale: 'he-IL',
  colorScheme: 'dark',
});
const page = await context.newPage();

async function setupTrips() {
  await page.evaluate(() => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'he');
    document.documentElement.setAttribute('data-app-shell', '1');
    document.body.classList.add('ot-tabs-on', 'trips-page');
    document.querySelector('.page-header')?.remove();
    const mob = document.querySelector('.mobile-header');
    if (mob) mob.style.display = 'none';
    document.querySelectorAll('[data-tab]').forEach((el) => {
      if (el.getAttribute('data-tab') !== 'trips') el.style.display = 'none';
    });
    const main = document.querySelector('.main-content');
    if (main) {
      main.removeAttribute('inert');
      main.style.pointerEvents = '';
    }
    document.querySelector('.summary-card').style.display = 'none';
    const list = document.getElementById('tripList');
    if (list) {
      list.innerHTML = '';
      for (let i = 0; i < 20; i++) {
        const d = document.createElement('div');
        d.id = 'tc' + i;
        d.className = 'trip-card';
        d.style.cssText =
          'height:150px;margin-bottom:12px;padding:18px;border-radius:14px;background:linear-gradient(135deg,#ff2d6a 0%,#7b2ff7 100%);color:#fff;font-size:22px;font-weight:700;';
        d.textContent = 'נסיעה ' + (i + 1);
        list.appendChild(d);
      }
    }
    document.getElementById('tripListSkeleton')?.remove();
  });
}

async function setupCharging() {
  await page.evaluate(() => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'he');
    document.documentElement.setAttribute('data-app-shell', '1');
    document.body.classList.add('ot-tabs-on', 'charging-page');
    document.querySelector('.page-header')?.remove();
    const mob = document.querySelector('.mobile-header');
    if (mob) mob.style.display = 'none';
    document.querySelectorAll('[data-tab]').forEach((el) => {
      if (el.getAttribute('data-tab') !== 'sessions') el.style.display = 'none';
    });
    const main = document.querySelector('.main-content');
    if (main) {
      main.removeAttribute('inert');
      main.style.pointerEvents = '';
    };
    document.getElementById('chargeLocationFilterHint')?.style.setProperty('display', 'none');
    document.getElementById('chargeLocationFilterRow')?.style.setProperty('display', 'none');
    document.getElementById('chargeSummary')?.style.setProperty('display', 'none');
    let wrap = document.getElementById('mockSessionList');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.id = 'mockSessionList';
      wrap.setAttribute('data-tab', 'sessions');
      const anchor = document.getElementById('sessionFilters');
      anchor?.parentNode?.insertBefore(wrap, anchor?.nextSibling);
    }
    wrap.innerHTML = '';
    for (let i = 0; i < 18; i++) {
      const d = document.createElement('div');
      d.id = 'cs' + i;
      d.style.cssText =
        'height:140px;margin-bottom:12px;padding:18px;border-radius:14px;background:linear-gradient(135deg,#ff2d6a 0%,#7b2ff7 100%);color:#fff;font-size:22px;font-weight:700;';
      d.textContent = 'טעינה ' + (i + 1);
      wrap.appendChild(d);
    }
  });
}

async function prep(which) {
  const path = which === 'charging' ? '/local/charging.html' : '/local/trips.html';
  await page.goto(`http://127.0.0.1:${port}${path}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(300);
  if (which === 'charging') await setupCharging();
  else await setupTrips();
  await page.waitForSelector('.bottom-tabs.is-inflow', { timeout: 8000 }).catch(() => null);
  await page.waitForTimeout(400);

  if (isGap) {
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      const summary = document.querySelector('.summary-card');
      if (summary) summary.style.display = '';
    });
    await page.waitForTimeout(200);
    return;
  }

  await page.evaluate((y) => window.scrollTo(0, y), SCROLL_Y);
  await page.waitForTimeout(350);
}

async function metrics(which) {
  return page.evaluate(({ which }) => {
    const sticky =
      which === 'charging'
        ? document.getElementById('sessionFilters')
        : document.getElementById('tripFilters');
    const card = document.getElementById(which === 'charging' ? 'cs2' : 'tc2');
    const tabs = document.querySelector('.bottom-tabs.is-inflow');
    const sc = sticky && getComputedStyle(sticky);
    const tc = tabs && getComputedStyle(tabs);
    const sr = sticky?.getBoundingClientRect();
    const cr = card?.getBoundingClientRect();
    const tr = tabs?.getBoundingClientRect();
    return {
      scrollY: window.scrollY,
      scrollHeight: document.documentElement.scrollHeight,
      stickyId: sticky?.id,
      stickyTop: sr?.top,
      stickyH: sr?.height,
      stickyBg: sc?.backgroundColor,
      tabsBottom: tr ? tr.top + tr.height : null,
      cardTop: cr?.top,
      cardBottom: cr?.bottom,
      gapTabsPeriod: sr && tr ? sr.top - (tr.top + tr.height) : null,
    };
  }, { which });
}

async function shot(which, file) {
  const tabs = page.locator('.bottom-tabs.is-inflow');
  const sticky =
    which === 'charging' ? page.locator('#sessionFilters') : page.locator('#tripFilters');
  await tabs.waitFor({ state: 'visible', timeout: 5000 });
  const tb = await tabs.boundingBox();
  const sb = await sticky.boundingBox();
  if (!tb || !sb) throw new Error('missing sticky/tabs box for ' + which);
  const y = Math.max(0, Math.floor(tb.y - 6));
  const bottom = Math.min(915, Math.floor(sb.y + sb.height + (isGap ? 100 : 150)));
  const h = Math.max(120, bottom - y);
  await page.screenshot({
    path: join(artifacts, file),
    clip: { x: 0, y, width: 412, height: h },
  });
}

for (const which of ['trips', 'charging']) {
  await prep(which);
  const m = await metrics(which);
  const suffix = isGap ? '-after-gap' : isBefore ? '-before-scrolled' : '-after-scrolled';
  const file = `${which}-period-row${suffix}.png`;
  await shot(which, file);
  console.log(JSON.stringify({ mode, which, file, metrics: m }, null, 2));
}

await browser.close();
server.close();