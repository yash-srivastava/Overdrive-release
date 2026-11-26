// Run with: node app/src/test/js/performance_soc_request_harness.js
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
    const requests = [], paints = [], errors = [];
    const readouts = { socCurrent: { textContent: '50%' }, evSocValue: { textContent: '50%' } };
    const BYD = {};
    vm.runInNewContext(source, {
        BYD, window: { BYD },
        document: { getElementById: id => readouts[id] || null },
        console: { error: (...args) => errors.push(args) },
        fetch: url => {
            const response = deferred();
            requests.push({ url, response });
            return response.promise;
        }
    });
    const perf = BYD.performance;
    perf.updateSocStats = () => {
        paints.push('stats');
        Object.values(readouts).forEach(el => { el.textContent = perf.socData.stats.currentSoc + '%'; });
    };
    perf.renderSocChart = () => paints.push('chart');
    function reply(index, soc) {
        requests[index].response.resolve({ ok: true, json: async () => ({ stats: { currentSoc: soc } }) });
    }
    return { perf, requests, paints, errors, readouts, reply };
}

async function latestRangeWins() {
    const { perf, requests, paints, reply } = fixture();
    const older = perf.fetchSocHistory();
    const fetchHistory = perf.fetchSocHistory.bind(perf);
    let newer;
    perf.fetchSocHistory = () => (newer = fetchHistory());
    perf.setSocTimeRange(24);
    assert.strictEqual(requests[0].url, '/api/performance/soc/full?hours=72&points=300');
    assert.strictEqual(requests[1].url, '/api/performance/soc/full?hours=24&points=300');
    reply(1, 60);
    await newer;
    reply(0, 40);
    await older;
    assert.strictEqual(perf.socData.stats.currentSoc, 60);
    assert.deepStrictEqual(paints, ['stats', 'chart']);
}

async function delayedBodyCannotOverwriteRefresh() {
    const { perf, requests, paints, reply } = fixture();
    const body = deferred(), reading = deferred();
    const older = perf.fetchSocHistory();
    requests[0].response.resolve({ ok: true, json: () => {
        reading.resolve();
        return body.promise;
    } });
    await reading.promise;
    const newer = perf.fetchSocHistory();
    reply(1, 70);
    await newer;
    body.resolve({ stats: { currentSoc: 30 } });
    await older;
    assert.strictEqual(perf.socData.stats.currentSoc, 70);
    assert.deepStrictEqual(paints, ['stats', 'chart']);
}

async function failureHandling(kind, superseded) {
    const { perf, requests, paints, errors, readouts, reply } = fixture();
    perf.socFetchFailures = 2;
    const older = perf.fetchSocHistory();
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
        const newer = perf.fetchSocHistory();
        reply(1, 65);
        await newer;
    }
    if (kind === 'http') requests[0].response.resolve({ ok: false, status: 503 });
    if (kind === 'network') requests[0].response.reject(new Error('offline'));
    if (kind === 'json') body.reject(new Error('invalid JSON'));
    await older;
    assert.strictEqual(perf.socFetchFailures, superseded ? 0 : 3);
    assert.strictEqual(errors.length, superseded ? 0 : 1);
    assert.deepStrictEqual(paints, superseded ? ['stats', 'chart'] : []);
    Object.values(readouts).forEach(el => {
        assert.strictEqual(el.textContent, superseded ? '65%' : '50% \u2022 stale');
    });
    // A subsequent current success still clears the stale state and counter.
    const recovery = perf.fetchSocHistory();
    reply(requests.length - 1, 80);
    await recovery;
    assert.strictEqual(perf.socFetchFailures, 0);
    Object.values(readouts).forEach(el => assert.strictEqual(el.textContent, '80%'));
}

async function obsoleteSuccessDoesNotClearCurrentFailure() {
    const { perf, requests, paints, reply } = fixture();
    perf.socFetchFailures = 2;
    const older = perf.fetchSocHistory();
    const newer = perf.fetchSocHistory();
    requests[1].response.resolve({ ok: false, status: 503 });
    await newer;
    reply(0, 40);
    await older;
    assert.strictEqual(perf.socFetchFailures, 3);
    assert.deepStrictEqual(paints, []);
}

(async () => {
    await latestRangeWins();
    await delayedBodyCannotOverwriteRefresh();
    for (const kind of ['http', 'network', 'json']) {
        await failureHandling(kind, false);
        await failureHandling(kind, true);
    }
    await obsoleteSuccessDoesNotClearCurrentFailure();
    console.log('performance_soc_request_harness: 9 scenarios passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
