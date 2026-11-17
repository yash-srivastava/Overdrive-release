/* Run with node tools/test-proximity-save-dom.js. Uses local assets and a mocked API. */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');

const root = path.resolve(__dirname, '..');
const asset = name => fs.readFileSync(path.join(root, 'app/src/main/assets/web', name), 'utf8');
const css = ['design-tokens.css', 'app-shell.css', 'app-tabs.css', 'app-collapse.css',
    'styles.css', 'remote-access.css'].map(name => asset('shared/' + name)
    .replace(/@import\s+[^;]+;/g, '')).join('\n').replace(/<\/style/gi, '<\\/style');
const html = asset('local/remote-access.html')
    .replace('<html lang="en">', '<html lang="en" data-theme="dark">')
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<link\b[^>]*>/gi, '')
    .replace('</head>', '<style>' + css + '</style></head>');
const script = asset('shared/remote-access.js');
const checks = [];
const proof = { realBrowser: true, actualAssets: true, mockedApi: true, checks, layouts: [] };

async function setup(browser, width = 390) {
    const page = await browser.newPage();
    await page.setViewport({ width, height: 844 });
    await page.setRequestInterception(true);
    page.on('request', request => request.respond({
        status: request.isNavigationRequest() ? 200 : 404,
        contentType: 'text/html',
        body: request.isNavigationRequest() ? html : ''
    }));
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('http://127.0.0.1/proximity-save-test', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => {
        const clone = value => JSON.parse(JSON.stringify(value));
        const phones = [
            { address: 'AA:BB:CC:DD:EE:01', name: 'An approved phone with a long device name', enabled: true, nearThreshold: -12, awayThreshold: -24 },
            { address: 'AA:BB:CC:DD:EE:02', name: 'Another approved phone with a long device name', enabled: true, nearThreshold: -12, awayThreshold: -24 },
            { address: 'AA:BB:CC:DD:EE:03', name: 'A paired phone that can be added', enabled: false, nearThreshold: -12, awayThreshold: -24 }
        ];
        const fixture = window.__saveFixture = {
            config: {
                enabled: true, deviceAddress: phones[0].address, deviceName: phones[0].name,
                unlockEnabled: true, lockEnabled: true, keyFobEnabled: true, keyFobLockEnabled: false,
                dryRun: false, advancedMode: true, unlockTrigger: 'away_to_near',
                nearThreshold: -12, awayThreshold: -24, windowMs: 15000, dwellMs: 4000,
                nearWindowMs: 4000, nearDwellMs: 2000, staleMs: 5000, cooldownMs: 30000,
                keyFobLockWindowMs: 10000, keepBluetoothOn: false, notifyPush: false, notifyTelegram: false,
                restrictLockToOutsideSafeZones: false, restrictUnlockToOutsideSafeZones: false,
                proximitySafeZoneIds: [], phones: clone(phones.slice(0, 2))
            },
            phones, sequence: 0, requests: [], posts: [], failure: null, hold: null,
            toasts: [], xhr: null
        };
        function snapshot() {
            return {
                success: true, sessionId: 'save-dom-test', sequence: ++fixture.sequence,
                config: clone(fixture.config), savedConfig: clone(fixture.config),
                devices: clone(phones), signals: [], signal: { state: 'UNKNOWN', connected: false },
                events: [], runtime: { active: true }, capabilities: {},
                keyFob: { active: true, fresh: false, reason: 'waiting_for_signal', state: 'UNKNOWN' },
                bluetooth: { state: 'off', available: true, fresh: true, ageMs: 0, maxAgeMs: 15000 }
            };
        }
        fixture.publishStatus = () => {
            const xhr = fixture.xhr;
            if (!xhr) throw new Error('Expected an active status subscription');
            xhr.status = 200; xhr.responseText = JSON.stringify(snapshot()); xhr.onload();
        };
        window.BYD = { utils: { toast: message => fixture.toasts.push(message) } };
        window.XMLHttpRequest = function () {
            this.open = () => {};
            this.setRequestHeader = () => {};
            this.send = () => { fixture.xhr = this; };
            this.abort = () => { if (this.onabort) this.onabort(); };
        };
        window.fetch = (url, options = {}) => {
            fixture.requests.push(String(url));
            let data;
            if (String(url).endsWith('/safe-locations')) {
                data = { success: true, zones: [{ id: 'home', name: 'Home with a long safe location name', radiusM: 100 }], featureEnabled: false };
            } else if (String(url).endsWith('/devices')) data = { success: true, devices: clone(phones) };
            else if (String(url).endsWith('/config')) {
                const body = JSON.parse(options.body);
                fixture.posts.push(body);
                if (fixture.failure && fixture.failure.network) return Promise.reject(new Error(fixture.failure.message));
                if (fixture.failure) return Promise.resolve({
                    ok: false, status: fixture.failure.status,
                    json: () => Promise.resolve({ success: false, error: fixture.failure.message })
                });
                if (fixture.hold) return new Promise(resolve => {
                    fixture.resolveSave = () => {
                        Object.assign(fixture.config, body);
                        resolve({ ok: true, status: 200, json: () => Promise.resolve(snapshot()) });
                    };
                });
                Object.assign(fixture.config, body); data = snapshot();
            } else data = snapshot();
            return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(data) });
        };
    });
    await page.addScriptTag({ content: script });
    await page.evaluate(() => RemoteAccess.init());
    await page.waitForFunction(() => !document.getElementById('raSaveBtn').disabled);
    return { page, errors };
}

async function edit(page, id, value) {
    await page.evaluate((id, value) => {
        const field = document.getElementById(id);
        if (field.type === 'checkbox') field.checked = value; else field.value = value;
        field.dispatchEvent(new Event('change', { bubbles: true }));
    }, id, value);
}

async function submit(page) {
    await page.evaluate(() => document.getElementById('raSettingsForm')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })));
}

async function feedback(page) {
    return page.evaluate(() => {
        const error = document.getElementById('raSaveError');
        const button = document.getElementById('raSaveBtn');
        const rect = error.getBoundingClientRect();
        const buttonRect = button.getBoundingClientRect();
        return {
            error: error.textContent, hidden: error.hidden, role: error.getAttribute('role'),
            state: document.getElementById('raSaveState').textContent,
            checked: document.getElementById('raKeyFobLockEnabled').checked,
            saved: window.__saveFixture.config.keyFobLockEnabled,
            posts: window.__saveFixture.posts.length,
            toasts: window.__saveFixture.toasts.slice(),
            besideSave: error.closest('.ra-save-row') === button.closest('.ra-save-row'),
            visible: rect.width > 0 && rect.height > 0,
            sameRow: Math.abs(rect.top - buttonRect.top) < Math.max(rect.height, buttonRect.height) + 20
        };
    });
}

async function refresh(page) {
    const count = await page.evaluate(() => window.__saveFixture.requests.length);
    await page.evaluate(() => document.getElementById('raRefreshBtn').click());
    await page.waitForFunction(count => window.__saveFixture.requests.length > count, {}, count);
    await page.waitForFunction(() => !document.getElementById('raRefreshBtn').disabled);
}

(async () => {
    const browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox', '--disable-gpu'] });
    try {
        const { page, errors } = await setup(browser);
        await edit(page, 'raKeyFobLockEnabled', true);
        await page.evaluate(() => { window.__saveFixture.failure = { status: 400, message: 'A new phone must be paired before enabling it' }; });
        await submit(page);
        await page.waitForFunction(() => !document.getElementById('raSaveError').hidden);
        let result = await feedback(page);
        assert.strictEqual(result.error, 'A new phone must be paired before enabling it');
        assert.strictEqual(result.role, 'alert');
        assert(result.besideSave && result.visible && result.sameRow, 'Save failure must render beside Save');
        assert(result.checked && !result.saved, 'Rejected save must preserve the unsaved checked toggle');
        assert(!result.state.includes('Saved on the vehicle') && result.toasts.length === 0);
        checks.push('HTTP 400 has accessible visible feedback beside Save and preserves the draft');
        await refresh(page);
        await page.evaluate(() => { window.dispatchEvent(new Event('focus')); });
        await page.evaluate(() => window.__saveFixture.publishStatus());
        result = await feedback(page);
        assert(!result.hidden && result.checked && !result.saved);
        checks.push('Refresh, focus and live status leave failed Save feedback and draft intact');

        await page.evaluate(() => { window.__saveFixture.failure = { status: 503, message: 'Vehicle service unavailable' }; });
        await submit(page);
        await page.waitForFunction(() => document.getElementById('raSaveError').textContent === 'Vehicle service unavailable');
        result = await feedback(page);
        assert(result.checked && !result.saved && result.toasts.length === 0);
        checks.push('HTTP 503 replaces the failure without falsely reporting success');

        await page.evaluate(() => { window.__saveFixture.failure = { network: true, message: 'Connection lost' }; });
        await submit(page);
        await page.waitForFunction(() => document.getElementById('raSaveError').textContent === 'Connection lost');
        result = await feedback(page);
        assert(result.checked && !result.saved && result.toasts.length === 0);
        checks.push('Network rejection keeps a visible actionable failure and the unsaved draft');

        const postCount = result.posts;
        await edit(page, 'raKeyFobLockSeconds', '11');
        await submit(page);
        result = await feedback(page);
        assert(!result.hidden && result.error.includes('10-second steps'));
        assert.strictEqual(result.posts, postCount, 'Invalid local draft must not POST');
        assert(result.checked && !result.saved);
        checks.push('Local draft validation appears beside Save without a request');
        await edit(page, 'raKeyFobLockSeconds', '10');
        await page.evaluate(() => { window.__saveFixture.failure = null; window.__saveFixture.hold = true; });
        await submit(page);
        await page.waitForFunction(() => !!window.__saveFixture.resolveSave);
        await page.evaluate(() => window.__saveFixture.publishStatus());
        assert((await feedback(page)).checked, 'A status response during Save must preserve the draft');
        await page.evaluate(() => window.__saveFixture.resolveSave());
        await page.waitForFunction(() => document.getElementById('raSaveState').textContent === 'Saved on the vehicle');
        result = await feedback(page);
        assert(result.hidden && result.checked && result.saved);
        assert.deepStrictEqual(result.toasts, ['Proximity rules saved']);
        checks.push('Successful retry clears failure only after saving; in-flight status preserves draft');
        assert.deepStrictEqual(errors, [], 'No browser runtime errors');
        await page.close();

        for (const width of [320, 375, 390, 768, 1280]) {
            const fixture = await setup(browser, width);
            const layoutPage = fixture.page;
            const layout = await layoutPage.evaluate(() => {
                const rect = node => {
                    const r = node.getBoundingClientRect();
                    return { x: r.x, y: r.y, right: r.right, bottom: r.bottom, width: r.width, height: r.height };
                };
                const visible = node => node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0;
                return {
                    width: innerWidth, pageWidth: document.documentElement.scrollWidth,
                    cards: Array.from(document.querySelectorAll('.ra-body .card')).filter(visible)
                        .map(node => ({ title: node.getAttribute('aria-labelledby'), rect: rect(node), scrollWidth: node.scrollWidth, clientWidth: node.clientWidth })),
                    fields: Array.from(document.querySelectorAll('.ra-fields-two')).filter(visible).map(node => Array.from(node.querySelectorAll('input')).map(rect)),
                    add: [rect(document.getElementById('raAddPhoneSelect')), rect(document.getElementById('raAddPhoneBtn'))],
                    captures: Array.from(document.querySelectorAll('.ra-capture')).map(rect),
                    controls: Array.from(document.querySelectorAll('.ra-body .btn, .ra-body select, .ra-body input[type="number"]'))
                        .filter(visible).map(node => ({ id: node.id, rect: rect(node), card: rect(node.closest('.card')) }))
                };
            });
            proof.layouts.push(layout);
            if (process.env.PROXIMITY_SAVE_SCREENSHOTS) {
                fs.mkdirSync(process.env.PROXIMITY_SAVE_SCREENSHOTS, { recursive: true });
                await layoutPage.screenshot({
                    path: path.join(process.env.PROXIMITY_SAVE_SCREENSHOTS, 'proximity-' + width + '-advanced.png'),
                    fullPage: false
                });
                await layoutPage.evaluate(() => document.querySelector('.ra-settings-grid').scrollIntoView());
                await layoutPage.screenshot({
                    path: path.join(process.env.PROXIMITY_SAVE_SCREENSHOTS, 'proximity-' + width + '-fields.png'),
                    fullPage: false
                });
            }
            assert(layout.pageWidth <= width, 'No page overflow at ' + width);
            for (const control of layout.controls) {
                assert(control.rect.x >= control.card.x && control.rect.right <= control.card.right + 1,
                    control.id + ' stays inside its card at ' + width);
            }
            for (const pair of layout.fields) {
                assert(Math.abs(pair[0].bottom - pair[1].bottom) <= 1,
                    'Paired timing fields align on their bottom edge at ' + width);
            }
            assert(Math.abs(layout.add[0].bottom - layout.add[1].bottom) <= 1, 'Add action aligns with selector at ' + width);
            assert(Math.abs(layout.captures[0].y - layout.captures[1].y) <= 1 &&
                Math.abs(layout.captures[0].bottom - layout.captures[1].bottom) <= 1, 'Capture buttons form an aligned row at ' + width);
            await edit(layoutPage, 'raAdvancedMode', false);
            assert.strictEqual(await layoutPage.evaluate(() => document.querySelector('.ra-settings-grid').getBoundingClientRect().height), 0);
            if (process.env.PROXIMITY_SAVE_SCREENSHOTS) await layoutPage.screenshot({
                path: path.join(process.env.PROXIMITY_SAVE_SCREENSHOTS, 'proximity-' + width + '-normal.png'),
                fullPage: false
            });
            assert.deepStrictEqual(fixture.errors, [], 'No layout page runtime errors');
            checks.push('Aligned card controls without overflow at ' + width + 'px, advanced and normal mode');
            await layoutPage.close();
        }
        if (process.env.PROXIMITY_SAVE_PROOF) fs.writeFileSync(process.env.PROXIMITY_SAVE_PROOF, JSON.stringify(proof, null, 2));
        console.log(JSON.stringify(proof, null, 2));
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
