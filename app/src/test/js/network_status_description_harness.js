// Standalone regression harness: node app/src/test/js/network_status_description_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/network.js'), 'utf8');
const fallback = "Turns the head unit into a WiFi access point that shares the built-in SIM's internet.";

async function main() {
    let status;
    let translated = 'Localized hotspot description';
    const desc = { textContent: translated };
    const badge = { textContent: '' };
    const context = vm.createContext({
        BYD: { i18n: { t: key => key === 'network.enable_desc' && translated ? translated : key } },
        performance: { now: () => 1000 },
        document: {
            hidden: false,
            getElementById: id => id === 'hsStateDesc' ? desc : id === 'hsStateBadge' ? badge : null
        },
        fetch: async url => {
            assert.strictEqual(url, '/api/hotspot');
            return { ok: true, json: async () => ({ success: true, status }) };
        },
        console
    });
    const page = vm.runInContext(source + '\nNetworkPage;', context);

    async function check(snapshot, expectedBadge, expectedDesc, message) {
        status = snapshot;
        await page.refresh();
        assert.strictEqual(badge.textContent, expectedBadge, message + ': badge');
        assert.strictEqual(desc.textContent, expectedDesc, message + ': description');
    }

    await check({ enabled: false }, 'Off', translated, 'initial normal state');
    await check({ enabled: false, lastError: 'start failed' }, 'Off', 'start failed', 'current error');
    await check({ enabled: false, lastError: 'data limit reached' }, 'Off', 'data limit reached', 'updated error');
    await check({ enabled: false, transitioning: true, lastError: '' }, 'Starting…', translated, 'retry clears error');
    await check({ enabled: false, lastError: 'start failed' }, 'Off', 'start failed', 'retry fails');
    await check({ enabled: true, lastError: 'start failed' }, 'On', translated, 'enabled ignores old error');
    await check({ enabled: false, lastError: 'access point failed' }, 'Off', 'access point failed', 'new error');
    await check({ enabled: false, lastError: '' }, 'Off', translated, 'off without error restores description');
    translated = 'Updated locale description';
    await check({ enabled: false }, 'Off', translated, 'current locale used');
    translated = '';
    await check({ enabled: true }, 'On', fallback, 'missing translation uses English fallback');

    console.log('Network status description: all 10 scenarios passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
