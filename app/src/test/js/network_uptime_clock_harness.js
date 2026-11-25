// Standalone regression harness: node app/src/test/js/network_uptime_clock_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname,
    '../../main/assets/web/shared/network.js'), 'utf8');

async function main() {
    let wall = 1700000000000;
    let elapsed = 1000;
    let status = { enabled: true, uptimeSeconds: 120 };
    let requests = 0;
    const uptime = { textContent: '' };
    const context = vm.createContext({
        Date: { now: () => wall },
        performance: { now: () => elapsed },
        document: {
            hidden: false,
            getElementById: id => id === 'hsUptime' ? uptime : null
        },
        fetch: async url => {
            assert.strictEqual(url, '/api/hotspot');
            requests++;
            return { json: async () => ({ success: true, status }) };
        },
        console
    });
    const page = vm.runInContext(source + '\nNetworkPage;', context);
    // Keep the actual refresh, uptime renderer and formatter; isolate other UI fields.
    page.render = () => page.renderUptime();

    await page.refresh();
    assert.strictEqual(uptime.textContent, '2:00', 'initial server uptime');

    elapsed += 1000;
    wall += 1000;
    page.renderUptime();
    assert.strictEqual(uptime.textContent, '2:01', 'normal one-second tick');

    elapsed += 1000;
    wall -= 300000;
    page.renderUptime();
    assert.strictEqual(uptime.textContent, '2:02', 'backward wall-clock correction');

    elapsed += 1000;
    wall += 600000;
    page.renderUptime();
    assert.strictEqual(uptime.textContent, '2:03', 'forward wall-clock correction');
    assert.strictEqual(requests, 1, 'display ticks do not issue requests');

    status = { enabled: true, uptimeSeconds: 130 };
    await page.refresh();
    assert.strictEqual(uptime.textContent, '2:10', 'fresh server snapshot resets baseline');
    elapsed += 2000;
    page.renderUptime();
    assert.strictEqual(uptime.textContent, '2:12', 'ticks use refreshed baseline');

    status = { enabled: false, uptimeSeconds: 130 };
    await page.refresh();
    elapsed += 1000;
    page.renderUptime();
    assert.strictEqual(uptime.textContent, '--', 'disabled hotspot does not extrapolate');
    assert.strictEqual(requests, 3, 'only explicit refreshes issue requests');

    console.log('Network uptime clock: all scenarios passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
