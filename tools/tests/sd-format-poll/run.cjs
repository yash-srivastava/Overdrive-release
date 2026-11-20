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
    assert.equal(button.disabled, true, 'format stays disabled until a fresh identity preview');
    assert.equal(page._sdFormatPolling, false, 'poller retired');
    return { status: status.textContent, toasts, polls };
}

/** A missing CID, user-scoped mount or failed preview must never enable erase. */
async function checkEligibility(reply, cardAvailable = true) {
    const button = { disabled: true };
    const reason = { textContent: '' };
    const byd = { i18n: { t: key => key } };
    const window = { BYD: byd };
    const context = {
        window, BYD: byd, console,
        document: { getElementById: id => ({
            sdFormatButton: button, sdFormatEligibility: reason
        }[id] || null) },
        fetch: async url => {
            assert.equal(url, '/api/storage/external/format/target');
            if (reply.networkError) throw new Error('offline');
            return { ok: reply.status === 200, status: reply.status, json: async () => reply.body };
        }
    };
    vm.runInNewContext(fs.readFileSync(asset, 'utf8'), context, { filename: asset });
    const page = window.StoragePage;
    page.volumes.sdCardAvailable = cardAvailable;
    await page._checkSdFormatEligibility();
    return { enabled: !button.disabled, reason: reason.textContent };
}

async function main() {
    const noCid = await checkEligibility({ status: 409, body: {
        success: false, identityVerifiable: false, reasonCode: 'media_identity'
    } });
    assert.equal(noCid.enabled, false);
    assert.equal(noCid.reason, 'storage.sd_format_reason_media_identity');
    const byd100 = await checkEligibility({ status: 409, body: {
        success: false, reasonCode: 'dilink100_unsupported'
    } });
    assert.equal(byd100.enabled, false);
    assert.equal(byd100.reason, 'storage.sd_format_reason_dilink100_unsupported');
    const noCard = await checkEligibility({ status: 409, body: {
        success: false, reasonCode: 'no_card'
    } }, false);
    assert.equal(noCard.enabled, false);
    assert.equal(noCard.reason, 'storage.sd_format_reason_no_card');
    const busy = await checkEligibility({ status: 409, body: {
        success: false, reasonCode: 'storage_busy'
    } });
    assert.equal(busy.enabled, false);
    assert.equal(busy.reason, 'storage.sd_format_reason_storage_busy');
    assert.equal((await checkEligibility({ status: 500, body: null })).enabled, false);
    assert.equal((await checkEligibility({ status: 200, body: {
        success: true, identityVerifiable: true, uuid: '1234-ABCD',
        capacityBytes: 123456
    } })).enabled, false, 'older daemon without physical confirmation token is unsafe');
    assert.equal((await checkEligibility({ status: 200, body: {
        success: true, identityVerifiable: true, uuid: '1234-ABCD',
        identityToken: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', capacityBytes: 123456
    } })).enabled, true);

    // The 10-second storage refresh must not schedule another target preview.
    const bydForRender = { i18n: { t: key => key } };
    const renderWindow = { BYD: bydForRender };
    vm.runInNewContext(fs.readFileSync(asset, 'utf8'), {
        window: renderWindow, BYD: bydForRender, console,
        document: { getElementById: () => null }
    }, { filename: asset });
    const page = renderWindow.StoragePage;
    page._loaded.settings = true;
    page._paintVolume = () => {};
    let previews = 0;
    page._checkSdFormatEligibility = () => { previews++; };
    page.renderVolumes(); // initial no-card reason
    page.renderVolumes(); // same data: no retry
    page.volumes.sdCardAvailable = true;
    page.volumes.sdCardPath = '/storage/1234-ABCD';
    page.volumes.sdCardTotalSpace = 123456;
    page.renderVolumes(); // card inserted
    page.renderVolumes(); // unchanged on subsequent polls
    page.volumes.sdCardPath = '/storage/5678-DCBA';
    page.renderVolumes(); // replacement
    assert.equal(previews, 3, 'preview only on load / changed card, not on polls');

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
