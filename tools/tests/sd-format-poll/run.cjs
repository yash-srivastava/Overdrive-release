'use strict';

// Behavioural regression for transient status replies in the Chrome 58 web UI.
// Uses a minimal DOM/fetch harness; no Android daemon, browser, or device needed.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const asset = path.resolve(__dirname, '../../../app/src/main/assets/web/shared/storage.js');

async function runPolling(responses, originalJobId) {
    const status = { textContent: '' };
    const button = { disabled: false };
    const toasts = [];
    let polls = 0;
    const byd = {
        i18n: { t: key => key },
        utils: { toast: (message, kind) => toasts.push({ message, kind }) }
    };
    const window = { BYD: byd };
    const context = {
        window, BYD: byd, console,
        document: { getElementById: id => ({
            sdFormatStatus: status,
            sdFormatButton: button
        }[id] || null) },
        // Advance the 2s polling timer deterministically, without waiting.
        setTimeout: callback => { callback(); return 1; },
        fetch: async url => {
            assert.equal(url, '/api/storage/external/format/status');
            assert.ok(polls < responses.length, 'unexpected status request');
            const response = responses[polls++];
            if (response.networkError) throw new Error('network unavailable');
            return {
                ok: response.status >= 200 && response.status < 300,
                status: response.status,
                json: async () => {
                    if (response.invalidJson) throw new SyntaxError('invalid JSON');
                    return response.body;
                }
            };
        }
    };
    vm.runInNewContext(fs.readFileSync(asset, 'utf8'), context, { filename: asset });
    const page = window.StoragePage;
    // Avoid unrelated status-refresh requests after a successful operation.
    page.loadCdrConfig = () => {};
    page.loadStorageStats = () => {};
    page.loadStorageSettings = () => {};
    await page._pollSdFormat(originalJobId);
    assert.equal(button.disabled, false, 'format button re-enabled');
    assert.equal(page._sdFormatPolling, false, 'poller retired');
    return { status: status.textContent, toasts, polls };
}

async function main() {
    const job = 'original-job';
    const result = await runPolling([
        { status: 401, body: { success: false, jobId: 'other-job', error: 'Unauthorized' } },
        { status: 500, body: { success: false, jobId: 'other-job', error: 'Server error' } },
        { status: 502, invalidJson: true },
        { networkError: true },
        { status: 200, body: { success: true, jobId: job, state: 'running' } },
        { status: 200, body: { success: true, jobId: job, state: 'succeeded' } }
    ], job);
    assert.equal(result.polls, 6, 'retry JSON errors and non-JSON failures');
    assert.equal(result.status, 'storage.sd_format_success');
    assert.equal(result.toasts[result.toasts.length - 1].kind, 'success');

    const replaced = await runPolling([
        { status: 500, body: { success: false, jobId: 'other-job' } },
        { status: 200, body: { success: true, jobId: 'other-job', state: 'succeeded' } }
    ], job);
    assert.equal(replaced.polls, 2);
    assert.equal(replaced.status, 'storage.sd_format_reason_unverified');
    assert.equal(replaced.toasts.length, 0, 'no false success toast');

    const longRunning = Array.from({ length: 75 }, () => ({
        status: 200, body: { success: true, jobId: job, state: 'running' }
    }));
    longRunning.push({ status: 200, body: { success: true, jobId: job, state: 'failed', reasonCode: 'cdr_running' } });
    const terminal = await runPolling(longRunning, job);
    assert.equal(terminal.polls, 76, 'do not expire a long-running job');
    assert.equal(terminal.status, 'storage.sd_format_reason_cdr_running');
    assert.equal(terminal.toasts[0].kind, 'error');
    console.log('SD format status behavioural scenarios passed');
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
