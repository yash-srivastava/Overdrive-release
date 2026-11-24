'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/abrp.js'), 'utf8');

function environment(grace) {
    const elements = new Map();
    const writes = [];
    const config = { enabled: true, gate_on_app: true, app_grace_seconds: grace };
    function element(id) {
        if (!elements.has(id)) {
            elements.set(id, { value: '', checked: false, disabled: false, style: {} });
        }
        return elements.get(id);
    }
    const context = vm.createContext({
        console,
        document: { getElementById: element },
        BYD: { units: { secondSuffix: () => 's', minuteSuffix: () => 'm' } },
        fetch: async (url, options) => {
            assert.strictEqual(url, '/api/abrp/config');
            if (options && options.method === 'POST') {
                const body = JSON.parse(options.body);
                writes.push(body);
                Object.assign(config, body);
                return { ok: true, json: async () => ({ success: true }) };
            }
            return { ok: true, json: async () => ({ success: true, config: { ...config } }) };
        }
    });
    vm.runInContext(source + '\nthis.abrp = ABRP;', context);
    return { abrp: context.abrp, element, writes };
}

async function checkLoad(value, expected) {
    const { abrp, element, writes } = environment(value);
    assert.strictEqual(await abrp.loadConfig(), true);
    assert.strictEqual(Number(element('abrpAppGrace').value), expected);
    assert.strictEqual(writes.length, 0);
    if (expected === 0) assert.strictEqual(element('abrpGraceLabel').textContent, '0s');
}

async function checkSave(value, expected) {
    const { abrp, element, writes } = environment(90);
    abrp._hydrated = await abrp.loadConfig();
    assert.strictEqual(abrp._hydrated, true);
    element('abrpAppGrace').value = value;
    await abrp.saveDataSaving();
    assert.strictEqual(writes.length, 1);
    assert.strictEqual(writes[0].app_grace_seconds, expected);
    assert.strictEqual(Number(element('abrpAppGrace').value), expected);
    assert.strictEqual(element('abrpAppGrace').disabled, false);
    if (expected === 0) assert.strictEqual(element('abrpGraceLabel').textContent, '0s');
}

async function main() {
    await checkLoad(0, 0);
    await checkSave('0', 0);
    await checkLoad(120, 120);
    await checkSave('600', 600);
    await checkLoad(undefined, 90);
    await checkLoad(null, 90);
    await checkLoad('invalid', 90);
    await checkSave('', 90);
    console.log('ABRP grace-period regression: 8 scenarios passed');
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
