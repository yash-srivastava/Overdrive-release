// Run with: node app/src/test/js/performance_data_usage_request_harness.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/assets/web/shared/performance.js'), 'utf8');

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function fixture() {
    const requests = [];
    const paints = [];
    const errors = [];
    const sandbox = {
        BYD: {},
        document: { getElementById: () => null },
        console: { error: (...args) => errors.push(args) },
        fetch: url => {
            const response = deferred();
            requests.push({ url, response });
            return response.promise;
        }
    };
    sandbox.window = { BYD: sandbox.BYD };
    vm.runInNewContext(source, sandbox, { filename: 'performance.js' });
    const perf = sandbox.BYD.performance;
    perf.applyDataUsageState = () => paints.push('state');
    perf.updateDataUsageStats = () => paints.push('stats');
    perf.renderDataUsageChart = () => paints.push('chart');
    function reply(index, data) {
        requests[index].response.resolve({ ok: true, json: () => Promise.resolve(data) });
    }
    return { perf, requests, paints, errors, reply };
}

async function latestRangeWins() {
    const { perf, requests, paints, reply } = fixture();
    const older = perf.fetchDataUsage();
    // Use the actual UI range handler, capturing its fetch promise for the test.
    const fetchUsage = perf.fetchDataUsage.bind(perf);
    let newer;
    perf.fetchDataUsage = () => (newer = fetchUsage());
    perf.setDataUsageRange(7);
    assert.strictEqual(requests[0].url, '/api/performance/data-usage?days=30');
    assert.strictEqual(requests[1].url, '/api/performance/data-usage?days=7');
    const recent = { enabled: true, days: [], total: 7 };
    reply(1, recent);
    await newer;
    reply(0, { enabled: false, days: [], total: 30 });
    await older;
    assert.strictEqual(perf.dataUsageData, recent);
    assert.deepStrictEqual(paints, ['state', 'stats', 'chart']);
}

async function lateJsonCannotOverwriteSameRangeRefresh() {
    const { perf, requests, paints, reply } = fixture();
    const body = deferred();
    const reading = deferred();
    const older = perf.fetchDataUsage();
    requests[0].response.resolve({ ok: true, json: () => {
        reading.resolve();
        return body.promise;
    } });
    await reading.promise;
    const newer = perf.fetchDataUsage();
    const recent = { enabled: true, total: 200 };
    reply(1, recent);
    await newer;
    body.resolve({ enabled: true, total: 100 });
    await older;
    assert.strictEqual(perf.dataUsageData, recent);
    assert.deepStrictEqual(paints, ['state', 'stats', 'chart']);
}

async function failureHandling(kind, superseded) {
    const { perf, requests, paints, errors, reply } = fixture();
    const original = perf.dataUsageData;
    const older = perf.fetchDataUsage();
    const recent = { enabled: true, total: 42 };
    const body = deferred();
    if (kind === 'json') {
        const reading = deferred();
        requests[0].response.resolve({ ok: true, json: () => {
            reading.resolve();
            return body.promise;
        } });
        await reading.promise;
    }
    if (superseded) {
        const newer = perf.fetchDataUsage();
        reply(1, recent);
        await newer;
    }
    if (kind === 'http') requests[0].response.resolve({ ok: false });
    if (kind === 'network') requests[0].response.reject(new Error('offline'));
    if (kind === 'json') body.reject(new Error('invalid JSON'));
    await older;
    assert.strictEqual(perf.dataUsageData, superseded ? recent : original);
    assert.deepStrictEqual(paints, superseded ? ['state', 'stats', 'chart'] : ['state']);
    assert.strictEqual(errors.length, !superseded && kind !== 'http' ? 1 : 0);
}

(async () => {
    await latestRangeWins();
    await lateJsonCannotOverwriteSameRangeRefresh();
    for (const kind of ['http', 'network', 'json']) {
        await failureHandling(kind, true);
        await failureHandling(kind, false);
    }
    console.log('performance_data_usage_request_harness: 8 scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
