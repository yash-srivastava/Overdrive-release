/**
 * Proximity access. Backend owns phone sampling, keyfob events and actions.
 * The page only subscribes while enabled and visible; disabling aborts the
 * subscription and removes every feature timer. ES5 for older car WebViews.
 */
var RemoteAccess = (function () {
    'use strict';

    var API = '/api/remote-access';
    var state = {
        ready: false, enabled: false, saving: false, dirty: false, paused: false,
        config: null, status: null, devices: [], captures: {}, samples: [],
        liveRequest: null, pollTimer: null, ageTimer: null, generation: 0,
        sequence: null, receivedAt: 0, lastSampleTime: null, failures: 0, mutation: 0,
        phones: [], viewAddress: '', modelHandle: null, modelMode: '', modelGeneration: 0,
        cachePreviewTried: false, serverSessionId: null, retiredSessions: [],
        savedConfig: null, advancedDraft: null, artRequest: null, artUrl: null, artGeneration: 0,
        modelPosterValid: false, offRenderTried: false, modelTimer: null,
        bluetoothSaving: false, bluetooth: null, bluetoothReceivedAt: 0, bluetoothTransportStale: false,
        safeZones: [], safeZoneIds: [], safeZonesReady: false, safeZonesError: '', safeZonesRequest: null,
        safeZonesValidationError: false
    };
    var fields = [
        'raDeviceSelect', 'raNearThreshold', 'raAwayThreshold', 'raMedianSeconds',
        'raDwellSeconds', 'raCooldownSeconds', 'raNearWindowSeconds', 'raNearDwellSeconds', 'raLearningMode',
        'raUnlockOnArrival', 'raLockOnDeparture', 'raKeepBluetoothOn',
        'raNotifyPush', 'raNotifyTelegram', 'raAdvancedMode', 'raUnlockTrigger', 'raKeyFobEnabled', 'raKeyFobLockEnabled',
        'raKeyFobLockSamples', 'raLockWaitsForKeyAbsence',
        'raKeyFobLockSeconds', 'raRestrictLockSafeZones', 'raRestrictUnlockSafeZones',
        'raCloudFallback',
        'raConfirmWithVehicle', 'raRelockGraceSeconds', 'raPostLockQuietSeconds',
        'raKeyFobUnlockNear'
    ];
    // 18 Oct snappy defaults (server RemoteAccessSettings.defaults()); normal mode runs exactly these.
    var TIMING_DEFAULTS = { windowMs: 6000, dwellMs: 2000, nearWindowMs: 2000, nearDwellMs: 1000,
        cooldownMs: 10000, relockGraceMs: 10000, postLockQuietMs: 10000 };
    // 20 Oct snappy locks (server defaults): keyfob searches every 5 s and the first Absent result locks.
    var KEYFOB_LOCK_DEFAULTS = { windowMs: 5000, samples: 1 };

    function el(id) { return document.getElementById(id); }
    // Once this script writes a node it owns the text; drop data-i18n so a locale
    // re-hydrate cannot restore the placeholder over live state (see onChange in init).
    function text(id, value) { var node = el(id); if (node) { node.removeAttribute('data-i18n'); node.textContent = value; } }
    function renderBluetoothStatus() {
        var node = el('raBluetoothStatus');
        if (!node) return;
        var extraAge = state.bluetoothReceivedAt ? Math.max(0, Date.now() - state.bluetoothReceivedAt) : 0;
        var value = window.BYD && BYD.utils && BYD.utils.bluetoothStatus
            ? BYD.utils.bluetoothStatus(state.bluetooth, extraAge, state.bluetoothTransportStale)
            : { state: 'unknown', label: tr('state_unknown', 'Unknown'), detail: tr('bluetooth_status_waiting', 'Waiting for car Bluetooth status') };
        text('raBluetoothState', value.label);
        node.setAttribute('data-state', value.state);
        node.setAttribute('aria-label', tr('bluetooth_status_aria', 'Car Bluetooth: {label}. {detail}', { label: value.label, detail: value.detail }));
        node.title = value.detail;
    }
    function updateBluetoothStatus(radio, transportStale) {
        if (!transportStale) {
            state.bluetooth = radio || null;
            state.bluetoothReceivedAt = Date.now();
        }
        state.bluetoothTransportStale = !!transportStale;
        renderBluetoothStatus();
    }
    function copy(input) {
        var output = {}, key;
        for (key in input) if (Object.prototype.hasOwnProperty.call(input, key)) output[key] = input[key];
        return output;
    }
    function finite(value) { return typeof value === 'number' && isFinite(value); }
    function supports(source) {
        return !state.status || !state.status.capabilities || state.status.capabilities[source] !== false;
    }
    function hasActivePhones(phones, keepBluetoothOn) {
        return keepBluetoothOn === true && supports('bluetooth') &&
            phones.some(function (phone) { return phone.enabled; });
    }
    // The daemon evaluates phones only while the car is off; a fresh delivery that read power on pauses them.
    function vehicleOnPaused() {
        var config = state.config;
        return state.enabled && !!config && !!state.status && state.status.vehicleOn === true &&
            hasActivePhones(profilesFromConfig(config), config.keepBluetoothOn) &&
            Date.now() - state.receivedAt <= (config.staleMs || 5000);
    }
    function nearWindowValue(config) {
        if (finite(config.nearWindowMs)) return config.nearWindowMs;
        return config.advancedMode && finite(config.windowMs) && config.windowMs !== TIMING_DEFAULTS.windowMs ?
            config.windowMs : TIMING_DEFAULTS.nearWindowMs;
    }
    function lockSamplesValue(config) {
        var samples = config && config.keyFobLockSamples;
        return samples === 2 || samples === 3 ? samples : KEYFOB_LOCK_DEFAULTS.samples;
    }
    function nearDwellValue(config) {
        if (finite(config.nearDwellMs)) return config.nearDwellMs;
        return config.advancedMode && finite(config.dwellMs) && config.dwellMs !== TIMING_DEFAULTS.dwellMs ?
            config.dwellMs : TIMING_DEFAULTS.nearDwellMs;
    }
    function signalText(value) {
        if (!finite(value)) return '—';
        return String(Math.round(value * 10) / 10).replace('-', '−');
    }
    function fill(template, vars) {
        if (!vars || typeof template !== 'string') return template;
        return template.replace(/\{(\w+)\}/g, function (match, name) {
            return vars[name] != null ? String(vars[name]) : match;
        });
    }
    function tr(key, fallback, vars) {
        var value = window.BYD && BYD.i18n ? BYD.i18n.t('remote_access.' + key, vars) : null;
        return typeof value !== 'string' || !value || value === 'remote_access.' + key ? fill(fallback, vars) : value;
    }
    var DOOR_KEYS = { 'front-left door': 'front_left', 'front-right door': 'front_right', 'rear-left door': 'rear_left',
        'rear-right door': 'rear_right', hood: 'hood', trunk: 'trunk' };
    function doorListText(value) {
        return String(value).split(', ').map(function (item) {
            var match = /^(.+) (open|unknown)$/.exec(item);
            if (!match || !Object.prototype.hasOwnProperty.call(DOOR_KEYS, match[1])) return item;
            return tr('server.door_' + DOOR_KEYS[match[1]] + '_' + match[2], item);
        }).join(', ');
    }
    // The vehicle reports English texts plus an optional <field>Key/<field>Args/<field>ArgKeys code
    // (RemoteAccessMessageCodes). Translate the code; anything without one stays as reported.
    function serverText(item, field, fallback) {
        var english = item && typeof item[field] === 'string' && item[field] ? item[field] : fallback || '';
        var key = item && item[field + 'Key'];
        if (typeof key !== 'string' || !/^[a-z0-9_]+$/.test(key)) return english;
        var args = item[field + 'Args'] || {}, argKeys = item[field + 'ArgKeys'] || {}, vars = {};
        Object.keys(args).forEach(function (name) {
            var value = args[name] == null ? '' : String(args[name]);
            if (name === 'doors') value = doorListText(value);
            else if (typeof argKeys[name] === 'string' && /^[a-z0-9_]+$/.test(argKeys[name]))
                value = tr('server.' + argKeys[name], value);
            vars[name] = value;
        });
        var translated = window.BYD && BYD.i18n ? BYD.i18n.t('remote_access.server.' + key, vars) : null;
        return typeof translated === 'string' && translated && translated !== 'remote_access.server.' + key ? translated : english;
    }
    function actionName(action) {
        return action === 'lock' || action === 'lock_car' ? tr('action_lock', 'Lock') :
            action === 'unlock' || action === 'unlock_car' ? tr('action_unlock', 'Unlock') : String(action).replace(/_/g, ' ');
    }
    function resultName(result) {
        var names = { confirmed: 'confirmed', accepted: 'accepted', unconfirmed: 'unconfirmed',
            cloud_acknowledged: 'cloud acknowledged', sent: 'sent', dry_run: 'dry run', blocked: 'blocked', failed: 'failed' };
        return Object.prototype.hasOwnProperty.call(names, result) ? tr('result_' + result, names[result]) : String(result).replace(/_/g, ' ');
    }
    function error(message) {
        text('raError', message || '');
        el('raError').hidden = !message;
    }
    function saveError(message) {
        // Status refreshes may clear the page banner. Keep a failed Save visible
        // beside its button until the next attempt, without discarding the draft.
        text('raSaveError', message || '');
        el('raSaveError').hidden = !message;
        if (message) text('raSaveState', tr('save_failed', 'Not saved · Your changes are kept'));
    }
    function toast(message) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(message, 'success');
    }
    function visible() { return !document.hidden && !state.paused && !window.OverdriveNativePaused; }
    function isLive() { return state.enabled && visible(); }
    function redirectAuth() {
        window.location.href = '/login.html?redirect=' +
            encodeURIComponent(window.location.pathname + window.location.search);
    }
    function requestJson(path, body) {
        var options = { cache: 'no-store', credentials: 'same-origin' };
        if (body) {
            options.method = 'POST';
            options.headers = { 'Content-Type': 'application/json' };
            options.body = JSON.stringify(body);
        }
        return fetch(path, options).then(function (response) {
            if (response.status === 401) { redirectAuth(); throw new Error(tr('session_expired', 'Session expired')); }
            return response.json().then(function (data) {
                if (!response.ok || data.success === false)
                    throw new Error(serverText(data, 'error', tr('request_failed', 'Request failed ({status})', { status: response.status })));
                return data;
            });
        });
    }
    function api(path, body) { return requestJson(API + path, body); }
    function setBusy(busy) {
        state.saving = busy;
        el('raMasterToggle').disabled = !state.ready || busy;
        el('raSaveBtn').disabled = !state.ready || busy;
        el('raRefreshBtn').disabled = busy;
        fields.forEach(function (id) { el(id).disabled = !state.ready || busy; });
        el('raFastUnlockDefaults').disabled = !state.ready || busy;
        updateUnlockTrigger();
        el('raAddPhoneSelect').disabled = !state.ready || busy;
        el('raAddPhoneBtn').disabled = !state.ready || busy || state.phones.length >= 8 || !el('raAddPhoneSelect').value;
        renderRoster();
        renderSafeZoneChoices();
        if (state.ready) renderSignal();
    }

    function applyConfig(config) {
        el('raMedianSeconds').value = (config.windowMs || TIMING_DEFAULTS.windowMs) / 1000;
        el('raDwellSeconds').value = (config.dwellMs || TIMING_DEFAULTS.dwellMs) / 1000;
        el('raNearWindowSeconds').value = nearWindowValue(config) / 1000;
        el('raNearDwellSeconds').value = nearDwellValue(config) / 1000;
        el('raCooldownSeconds').value = (config.cooldownMs || TIMING_DEFAULTS.cooldownMs) / 1000;
        el('raRelockGraceSeconds').value = (config.relockGraceMs || TIMING_DEFAULTS.relockGraceMs) / 1000;
        el('raPostLockQuietSeconds').value = (config.postLockQuietMs || TIMING_DEFAULTS.postLockQuietMs) / 1000;
        el('raConfirmWithVehicle').checked = config.confirmWithVehicle !== false;
        el('raKeyFobLockSeconds').value = (config.keyFobLockWindowMs || KEYFOB_LOCK_DEFAULTS.windowMs) / 1000;
        el('raKeyFobLockSamples').value = String(lockSamplesValue(config));
        el('raLockWaitsForKeyAbsence').checked = config.lockWaitsForKeyAbsence === true;
        // 19 Oct: the keyfob rail's own trigger; on (near, the default) unlocks on the first fresh detection.
        el('raKeyFobUnlockNear').checked = config.keyFobUnlockTrigger !== 'away_to_near';
        el('raLearningMode').checked = config.dryRun !== false;
        el('raUnlockOnArrival').checked = !!config.unlockEnabled;
        el('raUnlockTrigger').value = config.unlockTrigger === 'near' ? 'near' : 'away_to_near';
        el('raKeyFobEnabled').checked = config.keyFobEnabled === true;
        el('raKeyFobLockEnabled').checked = config.keyFobEnabled === true && config.keyFobLockEnabled === true;
        el('raLockOnDeparture').checked = !!config.lockEnabled;
        el('raRestrictLockSafeZones').checked = config.restrictLockToOutsideSafeZones === true;
        el('raRestrictUnlockSafeZones').checked = config.restrictUnlockToOutsideSafeZones === true;
        state.safeZoneIds = Array.isArray(config.proximitySafeZoneIds) ? config.proximitySafeZoneIds.slice(0, 10) : [];
        el('raNotifyPush').checked = !!config.notifyPush;
        el('raNotifyTelegram').checked = !!config.notifyTelegram;
        el('raAdvancedMode').checked = !!config.advancedMode;
        el('raCloudFallback').checked = config.cloudFallback !== false;
        updateAdvancedView();
        renderDevices(state.viewAddress || config.deviceAddress || '');
        loadProfileThresholds();
        updateRulesSummary();
        renderSafeZoneChoices();
    }

    function renderDevices(selected) {
        var select = el('raDeviceSelect');
        var wanted = selected !== undefined ? selected : state.viewAddress;
        while (select.firstChild) select.removeChild(select.firstChild);
        var placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = state.phones.length ? tr('select_approved_phone', 'Select an approved phone') : tr('add_approved_phone', 'Add an approved phone');
        select.appendChild(placeholder);
        var matched = false;
        state.phones.forEach(function (device) {
            var option = document.createElement('option');
            option.value = device.address || '';
            option.textContent = (device.name || tr('approved_phone', 'Approved phone')) + ' · ' + (device.address || '').slice(-5) +
                (device.enabled === false ? ' · ' + tr('state_disabled', 'Disabled') : '');
            if (option.value.toUpperCase() === String(wanted).toUpperCase()) { matched = true; wanted = option.value; }
            select.appendChild(option);
        });
        if (!matched) wanted = state.phones.length ? state.phones[0].address : '';
        state.viewAddress = wanted || '';
        select.value = state.viewAddress;
        select.disabled = !state.ready || state.saving;
        var add = el('raAddPhoneSelect'), addWanted = add.value;
        while (add.firstChild) add.removeChild(add.firstChild);
        var option = document.createElement('option'); option.value = ''; option.textContent = tr('choose_paired_phone', 'Choose a paired phone'); add.appendChild(option);
        state.devices.forEach(function (device) {
            if (!device.address || profileFor(device.address)) return;
            var item = document.createElement('option'); item.value = device.address;
            item.textContent = (device.name || tr('paired_device', 'Paired device')) + ' · ' + device.address.slice(-5);
            add.appendChild(item);
        });
        add.value = addWanted;
        add.disabled = !state.ready || state.saving || state.phones.length >= 8;
        el('raAddPhoneBtn').disabled = add.disabled || !add.value;
        renderRoster();
    }
    function refreshDevices() {
        return api('/devices').then(function (result) {
            state.devices = Array.isArray(result.devices) ? result.devices : [];
            renderDevices();
        });
    }
    function safeZoneRestrictionsDraft() {
        return el('raRestrictLockSafeZones').checked || el('raRestrictUnlockSafeZones').checked;
    }
    function syncSafeZoneSelection() {
        var choices = el('raSafeZoneChoices');
        if (!choices || !choices.querySelectorAll) return;
        var inputs = choices.querySelectorAll('input[data-zone-id]');
        if (!inputs.length) return;
        var displayed = [], checked = [];
        for (var i = 0; i < inputs.length; i++) {
            var id = inputs[i].getAttribute('data-zone-id');
            if (!id || displayed.indexOf(id) !== -1) continue;
            displayed.push(id);
            if (inputs[i].checked) checked.push(id);
        }
        // Keep saved missing IDs if the corresponding choice is not rendered.
        // Browser-restored checks may not have dispatched a change event.
        state.safeZoneIds = state.safeZoneIds.filter(function (id) {
            return displayed.indexOf(id) === -1;
        }).concat(checked);
    }
    function renderSafeZoneChoices() {
        var choices = el('raSafeZoneChoices');
        if (!choices) return;
        while (choices.firstChild) choices.removeChild(choices.firstChild);
        var unavailable = !state.ready || state.saving;
        el('raSafeZoneList').disabled = unavailable;
        if (!safeZoneRestrictionsDraft() || state.safeZoneIds.length) state.safeZonesValidationError = false;
        el('raSafeZoneList').setAttribute('aria-invalid', state.safeZonesValidationError ? 'true' : 'false');
        if (state.safeZonesValidationError) el('raSafeZoneListHint').setAttribute('role', 'alert');
        else el('raSafeZoneListHint').removeAttribute('role');
        var displayed = [];
        function addChoice(zone, missing) {
            var id = zone.id;
            if (typeof id !== 'string' || !id || displayed.indexOf(id) !== -1) return;
            displayed.push(id);
            var row = document.createElement('label');
            row.className = 'ra-safe-zone-choice' + (missing ? ' is-missing' : '');
            var input = document.createElement('input'); input.type = 'checkbox';
            input.checked = state.safeZoneIds.indexOf(id) !== -1;
            input.disabled = unavailable;
            input.setAttribute('data-zone-id', id);
            input.setAttribute('aria-label', missing ?
                tr('safe_zones_missing_label', 'Remove missing safe location') + ': ' + id :
                tr('safe_zones_select_label', 'Select safe location') + ': ' + (zone.name || id));
            var copyNode = document.createElement('span');
            var name = document.createElement('strong');
            name.textContent = missing ? tr('safe_zones_missing', 'Missing safe location') + ' · ' + id : zone.name || id;
            var detail = document.createElement('small');
            detail.textContent = missing ?
                tr('safe_zones_missing_detail', 'Saved selection retained. Restricted actions stay paused until you remove it or restore the location.') :
                finite(zone.radiusM) ? Math.round(zone.radiusM) + ' m ' + tr('safe_zones_radius', 'radius') : tr('safe_zones_saved', 'Saved location');
            input.addEventListener('change', function () {
                if (!state.ready || state.saving) { input.checked = state.safeZoneIds.indexOf(id) !== -1; return; }
                if (input.checked && state.safeZoneIds.indexOf(id) === -1 && state.safeZoneIds.length >= 10) {
                    input.checked = false;
                    error(tr('safe_zones_max', 'Choose up to 10 safe locations.'));
                    return;
                }
                if (input.checked && state.safeZoneIds.indexOf(id) === -1) state.safeZoneIds.push(id);
                else if (!input.checked) state.safeZoneIds = state.safeZoneIds.filter(function (selected) { return selected !== id; });
                markDirty(); renderSafeZoneChoices();
            });
            copyNode.appendChild(name); copyNode.appendChild(detail);
            row.appendChild(input); row.appendChild(copyNode); choices.appendChild(row);
        }
        state.safeZones.forEach(function (zone) { addChoice(zone, false); });
        state.safeZoneIds.forEach(function (id) {
            if (displayed.indexOf(id) === -1) addChoice({ id: id }, state.safeZonesReady);
        });
        if (!displayed.length) {
            var empty = document.createElement('p'); empty.className = 'ra-hint';
            empty.textContent = state.safeZonesError ? tr('safe_zones_load_failed', 'Saved locations unavailable. Refresh to retry.') :
                state.safeZonesReady ? tr('safe_zones_empty', 'No saved locations. Add one in Manage locations, then refresh here.') :
                tr('safe_zones_loading', 'Loading saved locations…');
            choices.appendChild(empty);
        }
        text('raSafeZoneListHint', state.safeZonesValidationError ?
            tr('safe_zones_choose_required', 'Choose at least one safe location before saving a restriction.') :
            state.safeZonesError ?
            tr('safe_zones_load_failed', 'Saved locations unavailable. Refresh to retry.') :
            safeZoneRestrictionsDraft() && !state.safeZoneIds.length ?
                tr('safe_zones_choose_required', 'Choose at least one safe location before saving a restriction.') :
                tr('safe_zones_independent', 'Uses saved locations independently of Surveillance switches. Save rules to apply your selection.'));
    }
    function focusSafeZoneSelection() {
        state.safeZonesValidationError = true;
        renderSafeZoneChoices();
        var list = el('raSafeZoneList');
        if (list.scrollIntoView) {
            try { list.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
            catch (ignored) { list.scrollIntoView(); }
        }
        if (list.focus) list.focus();
    }
    function refreshSafeZones() {
        if (state.safeZonesRequest) return state.safeZonesRequest;
        state.safeZonesRequest = requestJson('/api/surveillance/safe-locations').then(function (result) {
            if (!Array.isArray(result.zones)) throw new Error(tr('safe_zones_unavailable', 'Saved locations unavailable'));
            state.safeZones = result.zones.slice(0, 10);
            state.safeZonesReady = true; state.safeZonesError = '';
            renderSafeZoneChoices();
        }).catch(function (err) {
            state.safeZonesError = err.message || tr('safe_zones_unavailable', 'Saved locations unavailable');
            renderSafeZoneChoices();
        }).then(function () { state.safeZonesRequest = null; });
        return state.safeZonesRequest;
    }
    function renderSafeZoneStatus() {
        var panel = el('raSafeZonePanel');
        if (!panel) return;
        var config = state.config || {};
        var lockConfigured = config.lockEnabled === true ||
            config.keyFobEnabled === true && config.keyFobLockEnabled === true;
        var restrictLock = config.restrictLockToOutsideSafeZones === true && lockConfigured;
        var restrictUnlock = config.restrictUnlockToOutsideSafeZones === true && config.unlockEnabled === true;
        // The existing Unlock-only restriction also opts into parked Bluetooth
        // suppression, but only after the daemon confirms the walk-away lock.
        var restrictBluetooth = config.restrictUnlockToOutsideSafeZones === true
            && config.keepBluetoothOn === true;
        var active = restrictLock || restrictUnlock || restrictBluetooth;
        var geofence = state.status && state.status.safeZones || {};
        var parkedBluetooth = state.status && state.status.parkedBluetooth || {};
        var labels = [];
        if (restrictLock) labels.push(tr('safe_zones_action_lock', 'Automatic lock'));
        if (restrictUnlock) labels.push(tr('safe_zones_action_unlock', 'Automatic unlock'));
        if (restrictBluetooth) labels.push(tr('safe_zones_action_bluetooth', 'Parked Bluetooth after confirmed lock'));
        var actions = labels.join(' · ');
        var title, detail, kind = 'disabled';
        if (!state.enabled) {
            title = tr('safe_zones_master_off', 'Proximity disabled');
            detail = tr('safe_zones_master_off_detail', 'Saved restrictions apply when proximity is enabled.');
        } else if (!active) {
            title = tr('safe_zones_off', 'Restrictions off');
            detail = tr('safe_zones_off_detail', 'Proximity follows your saved signal rules.');
        } else {
            var extraAge = state.receivedAt ? Math.max(0, Date.now() - state.receivedAt) : 0;
            // A parked position has no age limit: it holds until the car leaves P.
            var parked = geofence.position === 'parked';
            var fresh = geofence.enabled === true && finite(geofence.fixAgeMs) && geofence.fixAgeMs >= 0 &&
                finite(geofence.maxFixAgeMs) && geofence.maxFixAgeMs > 0 &&
                finite(geofence.accuracyMeters) && geofence.accuracyMeters > 0 &&
                finite(geofence.maxAccuracyMeters) && geofence.maxAccuracyMeters > 0 &&
                geofence.accuracyMeters <= geofence.maxAccuracyMeters &&
                (parked || geofence.fixAgeMs + extraAge <= geofence.maxFixAgeMs) && extraAge <= 5000 && state.failures === 0;
            var outsideAllowed = (!restrictLock || geofence.lock && geofence.lock.restricted === true && geofence.lock.allowed === true) &&
                (!restrictUnlock || geofence.unlock && geofence.unlock.restricted === true && geofence.unlock.allowed === true);
            if (fresh && geofence.state === 'INSIDE') {
                kind = 'inside'; title = tr('safe_zones_inside', 'Inside selected safe zone');
                if (restrictBluetooth) {
                    detail = parkedBluetooth.safeZoneDisableRequested === true
                        ? tr('safe_zones_bluetooth_disabled_detail', 'The walk-away lock is confirmed; OverDrive has requested parked Bluetooth shutdown until the vehicle is unlocked, a door opens, the vehicle starts, or this location is no longer confirmed.')
                        : tr('safe_zones_bluetooth_waiting_detail', 'Bluetooth remains available until OverDrive confirms the walk-away lock.');
                    if (restrictLock || restrictUnlock)
                        detail = actions + '. ' + detail;
                } else {
                    detail = actions + ' ' + tr('safe_zones_paused_suffix', 'paused by your saved restrictions.');
                }
            } else if (fresh && geofence.state === 'OUTSIDE' && outsideAllowed) {
                kind = 'outside'; title = tr('safe_zones_outside', 'Outside selected safe zones');
                detail = restrictBluetooth
                    ? actions + '. ' + tr('safe_zones_bluetooth_outside_detail', 'Parked Bluetooth keep-alive remains available outside selected safe zones.')
                    : tr('safe_zones_outside_detail', 'Restricted actions can follow saved signal rules. All other safety checks still apply.');
            } else {
                kind = 'unknown'; title = tr('safe_zones_unknown', 'Location not confirmed');
                detail = restrictBluetooth
                    ? actions + '. ' + tr('safe_zones_bluetooth_unknown_detail', 'Location is uncertain, so OverDrive keeps Bluetooth available.')
                    : actions + ' ' + tr('safe_zones_unknown_suffix', 'paused until a fresh, accurate location confirms the car is outside selected zones.');
                if (Array.isArray(geofence.selectedZones) && geofence.selectedZones.some(function (zone) { return zone.missing; }))
                    detail = tr('safe_zones_missing_detail', 'Saved selection retained. Restricted actions stay paused until you remove it or restore the location.');
            }
            if (parked && kind !== 'unknown')
                detail += ' ' + tr('safe_zones_parked_suffix', 'Using the position saved when the car was parked.');
        }
        panel.setAttribute('data-state', kind);
        text('raSafeZoneState', title); text('raSafeZoneDetail', detail);
    }
    function selectedDevice() {
        return profileFor(state.viewAddress) || { address: '', name: '' };
    }
    function profileFor(address) {
        for (var i = 0; i < state.phones.length; i++) {
            if (state.phones[i].address.toUpperCase() === String(address).toUpperCase()) return state.phones[i];
        }
        return null;
    }
    function profilesFromConfig(config) {
        var profiles = Array.isArray(config.phones) ? config.phones : config.deviceAddress ? [{
            address: config.deviceAddress, name: config.deviceName, enabled: true,
            nearThreshold: config.nearThreshold, awayThreshold: config.awayThreshold
        }] : [];
        return profiles.slice(0, 8).map(function (profile) {
            return { address: profile.address, name: profile.name || '', enabled: profile.enabled !== false,
                nearThreshold: finite(profile.nearThreshold) ? profile.nearThreshold : -12,
                awayThreshold: finite(profile.awayThreshold) ? profile.awayThreshold : -24 };
        });
    }
    function storeProfileThresholds() {
        var profile = selectedDevice();
        if (!profile.address) return;
        profile.nearThreshold = el('raNearThreshold').value.trim() ? Number(el('raNearThreshold').value) : null;
        profile.awayThreshold = el('raAwayThreshold').value.trim() ? Number(el('raAwayThreshold').value) : null;
    }
    function loadProfileThresholds() {
        var profile = selectedDevice(), config = state.config || {};
        el('raNearThreshold').value = finite(profile.nearThreshold) ? profile.nearThreshold : finite(config.nearThreshold) ? config.nearThreshold : -12;
        el('raAwayThreshold').value = finite(profile.awayThreshold) ? profile.awayThreshold : finite(config.awayThreshold) ? config.awayThreshold : -24;
        renderCaptures();
    }
    function customProfile(source, address) {
        var profiles = source && source.phones || [];
        for (var i = 0; i < profiles.length; i++) if (String(profiles[i].address).toUpperCase() === String(address).toUpperCase()) return profiles[i];
        return null;
    }
    function advancedSnapshot() {
        storeProfileThresholds();
        return {
            phones: state.phones.map(copy),
            windowMs: Number(el('raMedianSeconds').value) * 1000,
            dwellMs: Number(el('raDwellSeconds').value) * 1000,
            nearWindowMs: Number(el('raNearWindowSeconds').value) * 1000,
            nearDwellMs: Number(el('raNearDwellSeconds').value) * 1000,
            staleMs: state.savedConfig && state.savedConfig.staleMs || 5000,
            cooldownMs: Number(el('raCooldownSeconds').value) * 1000,
            relockGraceMs: Number(el('raRelockGraceSeconds').value) * 1000,
            postLockQuietMs: Number(el('raPostLockQuietSeconds').value) * 1000,
            confirmWithVehicle: el('raConfirmWithVehicle').checked,
            keyFobLockWindowMs: Number(el('raKeyFobLockSeconds').value) * 1000,
            keyFobLockSamples: Number(el('raKeyFobLockSamples').value),
            lockWaitsForKeyAbsence: el('raLockWaitsForKeyAbsence').checked,
            unlockTrigger: el('raUnlockTrigger').value === 'near' ? 'near' : 'away_to_near',
            keyFobUnlockTrigger: el('raKeyFobUnlockNear').checked ? 'near' : 'away_to_near'
        };
    }
    function updateAdvancedView() {
        var advanced = el('raAdvancedMode').checked;
        el('remoteAccessPage').classList.toggle('ra-advanced-mode', advanced);
        text('raCalibrationDetail', advanced && !(state.config && state.config.advancedMode) ?
            tr('calibration_save_advanced', 'Save Advanced mode before capturing your phone signal.') :
            tr('calibration_detail', 'Captures fill this phone\'s draft thresholds. Edit them as needed, then Save rules.'));
    }
    function toggleAdvanced() {
        var advanced = el('raAdvancedMode').checked;
        if (advanced) {
            var custom = state.advancedDraft || state.savedConfig || state.config || {};
            state.phones.forEach(function (profile) {
                var stored = customProfile(custom, profile.address);
                profile.nearThreshold = stored && finite(stored.nearThreshold) ? stored.nearThreshold : -12;
                profile.awayThreshold = stored && finite(stored.awayThreshold) ? stored.awayThreshold : -24;
            });
            el('raMedianSeconds').value = (custom.windowMs || TIMING_DEFAULTS.windowMs) / 1000;
            el('raDwellSeconds').value = (custom.dwellMs || TIMING_DEFAULTS.dwellMs) / 1000;
            el('raNearWindowSeconds').value = nearWindowValue(custom) / 1000;
            el('raNearDwellSeconds').value = nearDwellValue(custom) / 1000;
            el('raCooldownSeconds').value = (custom.cooldownMs || TIMING_DEFAULTS.cooldownMs) / 1000;
            el('raRelockGraceSeconds').value = (custom.relockGraceMs || TIMING_DEFAULTS.relockGraceMs) / 1000;
            el('raPostLockQuietSeconds').value = (custom.postLockQuietMs || TIMING_DEFAULTS.postLockQuietMs) / 1000;
            el('raConfirmWithVehicle').checked = custom.confirmWithVehicle !== false;
            el('raKeyFobLockSeconds').value = (custom.keyFobLockWindowMs || KEYFOB_LOCK_DEFAULTS.windowMs) / 1000;
            el('raKeyFobLockSamples').value = String(lockSamplesValue(custom));
            el('raLockWaitsForKeyAbsence').checked = custom.lockWaitsForKeyAbsence === true;
            el('raUnlockTrigger').value = custom.unlockTrigger === 'away_to_near' ? 'away_to_near' : 'near';
            el('raKeyFobUnlockNear').checked = custom.keyFobUnlockTrigger !== 'away_to_near';
        } else {
            state.advancedDraft = advancedSnapshot();
            state.phones.forEach(function (profile) { profile.nearThreshold = -12; profile.awayThreshold = -24; });
            el('raMedianSeconds').value = 6; el('raDwellSeconds').value = 2; el('raCooldownSeconds').value = 10;
            el('raNearWindowSeconds').value = 2; el('raNearDwellSeconds').value = 1;
            el('raRelockGraceSeconds').value = 10; el('raPostLockQuietSeconds').value = 10;
            el('raConfirmWithVehicle').checked = true;
            el('raKeyFobLockSeconds').value = 5;
            el('raKeyFobUnlockNear').checked = true;
            // 20 Oct: normal mode runs the snappy defaults; the Advanced choices are kept in advancedDraft.
            el('raKeyFobLockSamples').value = '1';
            el('raLockWaitsForKeyAbsence').checked = false;
            el('raUnlockTrigger').value = 'near';
        }
        loadProfileThresholds(); updateAdvancedView(); markDirty(); renderSignal();
    }
    function useFastUnlockDefaults() {
        if (!state.ready || state.saving || !el('raAdvancedMode').checked) return;
        el('raNearWindowSeconds').value = TIMING_DEFAULTS.nearWindowMs / 1000;
        el('raNearDwellSeconds').value = TIMING_DEFAULTS.nearDwellMs / 1000;
        markDirty();
    }
    function selectPhone(address) {
        storeProfileThresholds();
        state.viewAddress = address;
        state.samples = []; state.lastSampleTime = null;
        renderDevices(address); loadProfileThresholds(); renderSignal(); drawChart();
    }
    function addPhone() {
        if (state.saving || state.phones.length >= 8) return;
        var address = el('raAddPhoneSelect').value, device = null;
        state.devices.some(function (candidate) { if (candidate.address === address) { device = candidate; return true; } return false; });
        if (!device || profileFor(address)) return;
        storeProfileThresholds();
        state.phones.push({ address: device.address, name: device.name || '', enabled: true, nearThreshold: -12, awayThreshold: -24 });
        state.viewAddress = device.address;
        state.samples = []; state.lastSampleTime = null;
        renderDevices(device.address); loadProfileThresholds(); markDirty(); renderSignal();
    }
    function proximityStateName(value) {
        var stateName = String(value || 'UNKNOWN').toUpperCase();
        return stateName === 'NEAR' ? tr('state_near', 'near') : stateName === 'AWAY' ? tr('state_away', 'away') :
            tr('state_unknown_lower', 'unknown');
    }
    function renderRoster() {
        var roster = el('raPhoneRoster');
        while (roster.firstChild) roster.removeChild(roster.firstChild);
        if (!state.phones.length) {
            var empty = document.createElement('div'); empty.className = 'ra-roster-empty';
            empty.textContent = tr('roster_empty', 'Add paired phones below. Nothing is monitored until the master switch is enabled.');
            roster.appendChild(empty); return;
        }
        var vehicleOn = vehicleOnPaused();
        state.phones.forEach(function (profile) {
            var row = document.createElement('div'); row.className = 'ra-roster-row' + (profile.address === state.viewAddress ? ' is-selected' : '');
            var name = document.createElement('button'); name.type = 'button'; name.className = 'ra-roster-name'; name.disabled = state.saving;
            var title = document.createElement('strong'); title.textContent = profile.name || tr('approved_phone', 'Approved phone');
            var signal = signalFor(profile.address);
            var detail = document.createElement('span');
            var proof = phoneSignalProof(signal);
            detail.textContent = !profile.enabled ? tr('state_disabled', 'Disabled') : !state.enabled ? tr('keyfob_monitoring_off', 'Monitoring off') :
                signal.reason === 'save_phone_first' ? tr('phone_save_to_start', 'Save to start monitoring') :
                vehicleOn ? tr('phone_vehicle_on', 'Paused · car is on') :
                !proof.fresh ? tr('phone_reading_expired', 'Unknown · reading expired') :
                proof.confirmedAway ? tr('phone_disconnection_away', 'Away · disconnection confirmed') :
                proof.pendingDisconnect ? tr('phone_disconnection_pending', 'Disconnected · confirming departure') :
                !proof.connectionUsable ? tr('phone_connection_unconfirmed', 'Unknown · connection unconfirmed') :
                !signal.connected ? tr('phone_awaiting_connection', 'Disconnected / awaiting connection') :
                !proof.rssi ? tr('phone_reading_unavailable', 'Unknown · reading unavailable') :
                proximityStateName(signal.state) +
                    (el('raAdvancedMode').checked ? ' · ' + signalText(signal.filteredRssi) + ' RSSI' : '');
            name.appendChild(title); name.appendChild(detail); name.addEventListener('click', function () { selectPhone(profile.address); });
            var toggle = document.createElement('label'); toggle.className = 'ra-switch';
            var input = document.createElement('input'); input.type = 'checkbox'; input.checked = profile.enabled; input.disabled = state.saving;
            input.setAttribute('aria-label', tr('phone_enable_aria', 'Enable {name}', { name: profile.name || tr('approved_phone', 'Approved phone') }));
            var track = document.createElement('span'); track.className = 'ra-switch-track';
            var thumb = document.createElement('span'); thumb.className = 'ra-switch-thumb'; track.appendChild(thumb);
            input.addEventListener('change', function () { profile.enabled = input.checked; markDirty(); renderRoster(); });
            toggle.appendChild(input); toggle.appendChild(track);
            var remove = document.createElement('button'); remove.type = 'button'; remove.className = 'ra-roster-remove'; remove.textContent = '×'; remove.disabled = state.saving;
            remove.setAttribute('aria-label', tr('phone_remove_aria', 'Remove {name}', { name: profile.name || tr('approved_phone', 'Approved phone') }));
            remove.addEventListener('click', function () {
                storeProfileThresholds();
                state.phones = state.phones.filter(function (item) { return item.address !== profile.address; });
                delete state.captures[profile.address];
                if (state.viewAddress === profile.address) { state.viewAddress = state.phones.length ? state.phones[0].address : ''; state.samples = []; state.lastSampleTime = null; }
                renderDevices(); loadProfileThresholds(); markDirty(); renderSignal();
            });
            row.appendChild(name); row.appendChild(toggle); row.appendChild(remove); roster.appendChild(row);
        });
    }
    function draft() {
        syncSafeZoneSelection();
        storeProfileThresholds();
        var device = selectedDevice();
        var config = {
            enabled: state.enabled, deviceAddress: device.address, deviceName: device.name || '',
            nearThreshold: Number(el('raNearThreshold').value),
            awayThreshold: Number(el('raAwayThreshold').value),
            windowMs: Number(el('raMedianSeconds').value) * 1000,
            dwellMs: Number(el('raDwellSeconds').value) * 1000,
            nearWindowMs: Number(el('raNearWindowSeconds').value) * 1000,
            nearDwellMs: Number(el('raNearDwellSeconds').value) * 1000,
            staleMs: state.config && state.config.staleMs || 5000,
            cooldownMs: Number(el('raCooldownSeconds').value) * 1000,
            relockGraceMs: Number(el('raRelockGraceSeconds').value) * 1000,
            postLockQuietMs: Number(el('raPostLockQuietSeconds').value) * 1000,
            confirmWithVehicle: el('raConfirmWithVehicle').checked,
            keyFobLockWindowMs: Number(el('raKeyFobLockSeconds').value) * 1000,
            keyFobLockSamples: Number(el('raKeyFobLockSamples').value),
            lockWaitsForKeyAbsence: el('raLockWaitsForKeyAbsence').checked,
            keyFobUnlockTrigger: el('raKeyFobUnlockNear').checked ? 'near' : 'away_to_near',
            dryRun: el('raLearningMode').checked,
            unlockEnabled: el('raUnlockOnArrival').checked,
            unlockTrigger: el('raUnlockTrigger').value === 'near' ? 'near' : 'away_to_near',
            keyFobEnabled: el('raKeyFobEnabled').checked,
            keyFobLockEnabled: el('raKeyFobEnabled').checked && el('raKeyFobLockEnabled').checked,
            lockEnabled: el('raLockOnDeparture').checked,
            restrictLockToOutsideSafeZones: el('raRestrictLockSafeZones').checked,
            restrictUnlockToOutsideSafeZones: el('raRestrictUnlockSafeZones').checked,
            proximitySafeZoneIds: state.safeZoneIds.slice()
        };
        config.notifyPush = el('raNotifyPush').checked;
        config.notifyTelegram = el('raNotifyTelegram').checked;
        config.advancedMode = el('raAdvancedMode').checked;
        config.cloudFallback = el('raCloudFallback').checked;
        config.phones = state.phones.map(copy);
        if (!config.advancedMode) {
            // Basic mode runs server defaults while retaining the user's
            // saved custom calibration for the next Advanced-mode session.
            var custom = state.advancedDraft || state.savedConfig || state.config || {};
            config.phones.forEach(function (profile) {
                var stored = customProfile(custom, profile.address);
                profile.nearThreshold = stored && finite(stored.nearThreshold) ? stored.nearThreshold : -12;
                profile.awayThreshold = stored && finite(stored.awayThreshold) ? stored.awayThreshold : -24;
            });
            var selected = customProfile(config, config.deviceAddress);
            config.nearThreshold = selected ? selected.nearThreshold : -12;
            config.awayThreshold = selected ? selected.awayThreshold : -24;
            config.windowMs = custom.windowMs || TIMING_DEFAULTS.windowMs; config.dwellMs = custom.dwellMs || TIMING_DEFAULTS.dwellMs;
            config.nearWindowMs = nearWindowValue(custom);
            config.nearDwellMs = nearDwellValue(custom);
            config.staleMs = custom.staleMs || 5000; config.cooldownMs = custom.cooldownMs || TIMING_DEFAULTS.cooldownMs;
            config.relockGraceMs = custom.relockGraceMs || TIMING_DEFAULTS.relockGraceMs;
            config.postLockQuietMs = custom.postLockQuietMs || TIMING_DEFAULTS.postLockQuietMs;
            config.confirmWithVehicle = custom.confirmWithVehicle !== false;
            config.keyFobLockWindowMs = custom.keyFobLockWindowMs || KEYFOB_LOCK_DEFAULTS.windowMs;
            config.keyFobUnlockTrigger = custom.keyFobUnlockTrigger === 'away_to_near' ? 'away_to_near' : 'near';
            // 20 Oct: the server runs the defaults in normal mode; keep the Advanced choices for later.
            config.keyFobLockSamples = lockSamplesValue(custom);
            config.lockWaitsForKeyAbsence = custom.lockWaitsForKeyAbsence === true;
            config.unlockTrigger = custom.unlockTrigger === 'away_to_near' ? 'away_to_near' : 'near';
        }
        ['raNearThreshold', 'raAwayThreshold', 'raMedianSeconds', 'raDwellSeconds',
            'raNearWindowSeconds', 'raNearDwellSeconds', 'raCooldownSeconds', 'raKeyFobLockSeconds',
            'raRelockGraceSeconds', 'raPostLockQuietSeconds'].forEach(function (id) {
            if (el(id).value.trim() === '') throw new Error(tr('error_enter_all_values', 'Enter all signal and timing values.'));
        });
        if (config.enabled && !config.keyFobEnabled && !config.phones.some(function (profile) { return profile.enabled; })) throw new Error(tr('error_needs_source', 'Enable keyfob detection or add and enable an approved phone before enabling proximity access.'));
        config.phones.forEach(function (profile) {
            if (!finite(profile.nearThreshold) || !finite(profile.awayThreshold) ||
                Math.floor(profile.nearThreshold) !== profile.nearThreshold || Math.floor(profile.awayThreshold) !== profile.awayThreshold ||
                profile.nearThreshold < -127 || profile.nearThreshold > 20 || profile.awayThreshold < -127 || profile.awayThreshold > 20 ||
                profile.nearThreshold <= profile.awayThreshold) throw new Error(profile.name ?
                    tr('error_phone_thresholds', 'Check the saved signal thresholds for {name}. Nearby must be higher than away, within −127 to 20.', { name: profile.name }) :
                    tr('error_phone_thresholds_each', 'Check the saved signal thresholds for each approved phone. Nearby must be higher than away, within −127 to 20.'));
        });
        if (!finite(config.nearThreshold) || !finite(config.awayThreshold) ||
            Math.floor(config.nearThreshold) !== config.nearThreshold || Math.floor(config.awayThreshold) !== config.awayThreshold ||
            config.nearThreshold < -127 || config.nearThreshold > 20 || config.awayThreshold < -127 || config.awayThreshold > 20) {
            throw new Error(tr('error_thresholds_range', 'Signal thresholds must be whole numbers between −127 and 20.'));
        }
        if (config.nearThreshold <= config.awayThreshold) throw new Error(tr('error_near_above_away', 'Nearby threshold must be higher than the away threshold.'));
        if (!finite(config.windowMs) || config.windowMs < 4000 || config.windowMs > 60000 ||
            !finite(config.dwellMs) || config.dwellMs < 1000 || config.dwellMs > 30000 ||
            !finite(config.nearWindowMs) || config.nearWindowMs < 2000 || config.nearWindowMs > 60000 ||
            !finite(config.nearDwellMs) || config.nearDwellMs < 1000 || config.nearDwellMs > 30000 ||
            !finite(config.cooldownMs) || config.cooldownMs < 5000 || config.cooldownMs > 300000) {
            throw new Error(tr('error_timing_range', 'Use a 2–60 second unlock window, a 4–60 second departure window, 1–30 second confirmations, and a 5–300 second cooldown.'));
        }
        if (!finite(config.relockGraceMs) || config.relockGraceMs < 3000 || config.relockGraceMs > 30000 ||
            !finite(config.postLockQuietMs) || config.postLockQuietMs < 3000 || config.postLockQuietMs > 30000) {
            throw new Error(tr('error_grace_range', 'Use a 3–30 second relock grace and a 3–30 second keyfob quiet period.'));
        }
        if (!finite(config.keyFobLockWindowMs) || config.keyFobLockWindowMs < 5000 ||
            config.keyFobLockWindowMs > 300000 || config.keyFobLockWindowMs % 5000 !== 0)
            throw new Error(tr('error_keyfob_window_range', 'Use a 5–300 second keyfob locking window in 5-second steps.'));
        if ([1, 2, 3].indexOf(config.keyFobLockSamples) < 0)
            throw new Error(tr('error_keyfob_samples', 'Choose 1, 2 or 3 keyfob absence results.'));
        if ((config.restrictLockToOutsideSafeZones || config.restrictUnlockToOutsideSafeZones) && !config.proximitySafeZoneIds.length) {
            var selectionError = new Error(tr('safe_zones_choose_required', 'Choose at least one safe location before saving a restriction.'));
            selectionError.field = 'safe_zones';
            throw selectionError;
        }
        if (config.proximitySafeZoneIds.length > 10)
            throw new Error(tr('safe_zones_max', 'Choose up to 10 safe locations.'));
        return config;
    }
    function markDirty() {
        syncSafeZoneSelection();
        storeProfileThresholds();
        state.dirty = true;
        text('raSaveState', tr('unsaved', 'Unsaved changes · Save rules to apply'));
        updateRulesSummary();
        drawChart();
        renderPhoneMarkers();
        renderKeyFobStatus();
        renderSafeZoneChoices();
    }
    function updateUnlockTrigger() {
        var near = el('raUnlockTrigger').value === 'near';
        // 20 Oct: the phones' trigger is an Advanced control; normal mode runs the default (near).
        el('raUnlockTrigger').disabled = !state.ready || state.saving || !el('raUnlockOnArrival').checked ||
            !el('raAdvancedMode').checked;
        var fobDraft = el('raKeyFobEnabled').checked;
        var fob = fobDraft && supports('keyFob');
        var fobLock = fob && supports('keyFobLock') && el('raKeyFobLockEnabled').checked;
        // Keep saved preferences intact, but allow unavailable sources to be turned off.
        el('raKeyFobEnabled').disabled = !state.ready || state.saving || !supports('keyFob') && !fobDraft;
        el('raKeyFobLockEnabled').disabled = !state.ready || state.saving || !fobDraft ||
            (!supports('keyFob') || !supports('keyFobLock')) && !el('raKeyFobLockEnabled').checked;
        el('raKeyFobLockSeconds').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobLock;
        el('raKeyFobLockSamples').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobLock;
        el('raLockWaitsForKeyAbsence').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobLock;
        // The keyfob trigger is an Advanced switch; normal mode runs the default (unlock on the first fresh detection).
        el('raKeyFobUnlockNear').disabled = !state.ready || state.saving || !el('raAdvancedMode').checked || !fobDraft;
        var fobNear = keyFobUnlocksOnDetection();
        el('raRestrictLockSafeZones').disabled = !state.ready || state.saving ||
            !el('raLockOnDeparture').checked && !fobLock && !el('raRestrictLockSafeZones').checked;
        el('raRestrictUnlockSafeZones').disabled = !state.ready || state.saving ||
            !el('raUnlockOnArrival').checked && !el('raRestrictUnlockSafeZones').checked;
        text('raKeyFobLockDetail', !supports('keyFob') || !supports('keyFobLock') ?
            tr('keyfob_lock_unsupported', 'Keyfob departure locking is unsupported on this car. A saved rule can be switched off.') :
            !fobDraft ? tr('keyfob_lock_requires_detection', 'Enable Keyfob detection above to use departure locking. Automatic lock must also be on.') :
            tr('keyfob_lock_absence_priority_desc', 'With Automatic lock on, a key search that finds no key inside confirms departure (by default the first one); confirmed absence takes priority over phone presence. Searches run every 5 seconds (every 10 seconds for a window that is a multiple of 10 seconds).') +
                ' ' + (el('raAdvancedMode').checked ? el('raKeyFobLockSeconds').value || '5' : '5') +
                tr('keyfob_lock_priority_window_suffix', 's locking window. Unknown keyfob results block locking.'));
        var phones = hasActivePhones(state.phones, el('raKeepBluetoothOn').checked);
        var fobHint = fobNear ?
            tr('keyfob_near_hint', 'Keyfob: unlock on a fresh detection event; no prior Away event is required.') :
            tr('keyfob_return_hint', 'Keyfob: confirm an Away event first, then unlock on a fresh detection event.');
        var detail = near ?
            tr('unlock_near_detail', 'Unlock when any enabled approved phone has a stable Near signal.') :
            tr('unlock_return_detail', 'Unlock when any enabled approved phone returns from Away to Near.');
        text('raUnlockRuleDetail', fob && !phones ? fobHint :
            detail + (fob ? ' ' + tr('keyfob_unlock_detail', 'Enabled keyfob detection can also trigger unlock.') : ''));
        var hint = near ?
            tr('unlock_trigger_near_hint', 'Unlock after a stable Near signal, including first connection or reconnection. No prior Away signal is required.') :
            tr('unlock_trigger_away_to_near_hint', 'Slower after installs, restarts and new phones: the phone must first be seen leaving before a return can unlock. Protects against a phone left inside the car unlocking it on signal fluctuation.');
        text('raUnlockTriggerHint', fob && !phones ? fobHint :
            (fob ? tr('phone_trigger_prefix', 'Phones:') + ' ' : '') + hint + (fob ? ' ' + fobHint : ''));
        text('raLockRuleDetail', lockRuleDetail(phones, fobLock, fob));
    }
    function lockRuleDetail(phones, fobLock, fob) {
        // 20 Oct: phone locking waits for confirmed keyfob absence only with the Advanced switch on.
        var waits = el('raAdvancedMode').checked && el('raLockWaitsForKeyAbsence').checked;
        if (fobLock) return phones ? (waits ?
            tr('lock_keyfob_priority_phone_detail', 'Confirmed keyfob departure takes priority over phone presence. Phone-only locking requires every active phone to be freshly Away and confirmed keyfob absence. Power and closures are checked.') :
            tr('lock_keyfob_priority_phone_nowait_detail', 'Confirmed keyfob departure takes priority over phone presence. Phone-only locking requires every active phone to be freshly Away; a fresh keyfob detection or a key found inside blocks it. Power and closures are checked.')) :
            tr('lock_keyfob_priority_detail', 'Lock after confirmed keyfob departure, independently of phone presence. Unknown keyfob results block locking. Power and closures are checked.');
        return phones ?
            tr('lock_active_phone_disconnection_detail', 'Every active phone must be freshly Away. Confirmed disconnection counts as Away; Near or unconfirmed signals pause phone-only locking.') +
                (fob ? ' ' + tr('lock_keyfob_near_veto_detail', 'A fresh nearby keyfob also blocks phone-only locking.') : '') :
            tr('lock_no_active_sources', 'Departure locking is inactive. Enable parked Bluetooth with an approved phone, or enable keyfob departure locking.');
    }
    function toggleKeyFob() {
        if (!el('raKeyFobEnabled').checked) el('raKeyFobLockEnabled').checked = false;
        markDirty();
    }
    // 19 Oct: the keyfob rail's effective trigger — the Advanced switch, or the default (near) in normal mode.
    function keyFobUnlocksOnDetection() {
        return !el('raAdvancedMode').checked || el('raKeyFobUnlockNear').checked;
    }
    function updateRulesSummary() {
        updateUnlockTrigger();
        var learning = el('raLearningMode').checked;
        var unlock = el('raUnlockOnArrival').checked;
        var lock = el('raLockOnDeparture').checked;
        var fobEnabled = el('raKeyFobEnabled').checked && supports('keyFob');
        var fobNear = keyFobUnlocksOnDetection();
        var phones = hasActivePhones(state.phones, el('raKeepBluetoothOn').checked);
        var fobOnly = fobEnabled && !phones;
        var label;
        if (learning) label = tr('preview_note', 'Monitor preview is on. Vehicle commands are disabled.');
        else if (!unlock && !lock) label = tr('no_actions', 'Monitoring only. Both automatic vehicle actions are disabled.');
        else {
            label = unlock ? el('raUnlockTrigger').value === 'near' ?
                tr('unlock_near_note', 'Unlock accepts a confirmed Near signal, including first connection or reconnection. Power and closures are checked.') :
                tr('unlock_return_note', 'Unlock requires a confirmed departure followed by a stable return. Startup and reconnection alone do not trigger an action.') : '';
            if (unlock && fobEnabled) label += ' ' + (fobNear ?
                tr('keyfob_near_hint', 'Keyfob: unlock on a fresh detection event; no prior Away event is required.') :
                tr('keyfob_return_hint', 'Keyfob: confirm an Away event first, then unlock on a fresh detection event.'));
            if (unlock && fobOnly) label = (fobNear ?
                tr('keyfob_near_hint', 'Keyfob: unlock on a fresh detection event; no prior Away event is required.') :
                tr('keyfob_return_hint', 'Keyfob: confirm an Away event first, then unlock on a fresh detection event.')) + ' ' +
                tr('power_closures_checked', 'Power and closures are checked.');
            if (lock) label += (label ? ' ' : '') + lockRuleDetail(
                phones, fobEnabled && supports('keyFobLock') && el('raKeyFobLockEnabled').checked,
                fobEnabled);
        }
        text('raRuleStatus', label);
    }

    function reasonText(reason) {
        if (reason === 'confirmed_disconnection')
            return tr('phone_disconnection_confirmed_detail', 'Phone disconnection confirmed · waiting for your return');
        if (reason === 'confirming_disconnection')
            return tr('phone_disconnection_pending', 'Disconnected · confirming departure');
        if (reason === 'connection_proof_unavailable')
            return tr('phone_connection_unconfirmed_detail', 'Vehicle Bluetooth connection state is unconfirmed. Automatic actions are paused.');
        if (reason === 'disconnected_without_connection_history')
            return tr('phone_disconnection_waiting_history', 'Phone disconnected · waiting for a verified connection');
        if (reason === 'missing_signal')
            return tr('phone_connected_waiting_signal', 'Connected · waiting for a fresh signal');
        var reasons = {
            disabled: 'No background monitoring', no_device: 'Choose and save a paired phone',
            bluetooth_off: 'Car Bluetooth is off', disconnected: 'Phone disconnected · no departure assumed',
            stale: 'Reading expired · automatic actions suspended',
            waiting_for_signal: 'Waiting for a fresh phone reading', insufficient_samples: 'Collecting fresh samples',
            unavailable: 'Bluetooth RSSI unavailable on this platform', settling: 'Waiting for a stable signal',
            startup: 'Startup observation · no action', unarmed: 'Confirm departure to arm arrival',
            radio_unavailable: 'Car Bluetooth is unavailable', reader_unavailable: 'Signal reader unavailable',
            radio_off: 'Car Bluetooth is off', bluetooth_unavailable: 'Car Bluetooth is unavailable',
            waiting_for_phone: 'Waiting for the selected phone to connect',
            waiting_for_collector: 'Waiting for the car app to start monitoring',
            collector_stale: 'Updates from the car app stopped · automatic actions disarmed',
            paired_devices_unavailable: 'Waiting for the car to report its paired devices',
            rssi_unavailable: 'Fresh Bluetooth signal strength is unavailable',
            unsupported_platform: 'Bluetooth signal reading is unsupported on this platform',
            vehicle_ineligible: 'Vehicle power off is not confirmed · automatic actions disarmed',
            stable_near: 'Stable nearby signal', stable_away: 'Stable away signal',
            stabilizing_near: 'Confirming a nearby signal', stabilizing_away: 'Confirming an away signal',
            within_hysteresis: 'Signal is between thresholds · holding the current state',
            save_phone_first: 'Save this approved phone to start monitoring it',
            phone_disabled: 'This approved phone is disabled',
            master_off: 'Proximity is disabled',
            shared_bluetooth_off: 'Enable parked Bluetooth to reconnect your phone',
            vehicle_on: 'Phone reconnect waits until vehicle power is off',
            power_unknown: 'Waiting for confirmed vehicle power off',
            vehicle_on_only: 'Parked reconnect is unavailable in Vehicle on only mode',
            settings_changed: 'Phone settings changed · checking approved phones',
            phone_not_trusted: 'Save and enable this approved phone to reconnect',
            phone_not_paired: 'Pair this phone in the car\'s Bluetooth settings',
            profile_loading: 'Preparing to reconnect your phone',
            profile_timeout: 'Bluetooth connection service did not respond',
            profile_busy: 'Another phone is active · keeping its connection',
            other_bluetooth_active: 'Another Bluetooth device is active · keeping its connection',
            bluetooth_busy: 'Bluetooth is busy · reconnect paused',
            discovery_active: 'Bluetooth discovery is active · reconnect paused',
            pairing_active: 'Bluetooth pairing is active · reconnect paused',
            pairing_grace: 'Finishing Bluetooth setup · reconnect paused',
            bluetooth_state_unavailable: 'Bluetooth state is unavailable · reconnect paused',
            priority_disabled: 'Phone auto-connect is disabled in Bluetooth settings',
            retry_backoff: 'Waiting before retrying the phone connection',
            phone_turn: 'Waiting to reconnect this approved phone',
            attempt_accepted: 'Reconnect requested · waiting for your phone',
            attempt_rejected: 'Phone reconnect request was declined',
            connection_timeout: 'Phone did not reconnect · waiting before retrying',
            connection_failed: 'Phone did not connect · retrying shortly',
            profile_unavailable: 'Phone reconnect is unavailable on this car',
            connected: 'Phone connection restored · waiting for a fresh signal',
            stopped: 'Phone reconnect stopped',
            unexpected_profile: 'Phone reconnect service is unavailable'
        };
        if (Object.prototype.hasOwnProperty.call(reasons, reason)) return tr('reason_' + reason, reasons[reason]);
        return reason ? String(reason).replace(/_/g, ' ') : tr('reason_waiting_fresh_reading', 'Waiting for a fresh reading');
    }
    function currentSignal() {
        return signalFor(state.viewAddress);
    }
    function signalFor(address) {
        var signals = state.status && state.status.signals || [];
        for (var i = 0; i < signals.length; i++) {
            if (String(signals[i].deviceAddress).toUpperCase() === String(address).toUpperCase()) return signals[i];
        }
        var signal = state.status && state.status.signal || {};
        if (signal.deviceAddress && signal.deviceAddress.toUpperCase() === String(address).toUpperCase()) return signal;
        return { deviceAddress: address, reason: address ? 'save_phone_first' : 'no_device', state: 'UNKNOWN', connected: false };
    }
    function estimatedAge(signal) {
        return finite(signal.ageMs) ? Math.max(0, signal.ageMs + Date.now() - state.receivedAt) : null;
    }
    function phoneSignalProof(signal) {
        var age = estimatedAge(signal);
        var fresh = finite(signal.ageMs) && signal.ageMs >= 0 && age !== null &&
            age <= (state.config && state.config.staleMs || 5000);
        var owns = Object.prototype.hasOwnProperty;
        // Missing fields preserve older RSSI snapshots; explicit unknown/off
        // native reads cannot supply proximity or connection proof.
        var connectionKnown = owns.call(signal, 'connectionKnown') ? signal.connectionKnown === true :
            typeof signal.connected === 'boolean';
        var radioOn = !owns.call(signal, 'radioOn') || signal.radioOn === true;
        var radioReadKnown = !owns.call(signal, 'radioReadKnown') || signal.radioReadKnown === true;
        var connectionUsable = fresh && connectionKnown && radioOn && radioReadKnown;
        var kind = typeof signal.evidenceKind === 'string' ? signal.evidenceKind : 'rssi';
        return {
            age: age, fresh: fresh, connectionUsable: connectionUsable,
            rssi: connectionUsable && signal.connected === true && kind === 'rssi' &&
                finite(signal.rawRssi) && finite(signal.filteredRssi),
            confirmedAway: connectionUsable && signal.connectionKnown === true && signal.radioOn === true &&
                signal.connected === false && kind === 'confirmed_disconnection' &&
                signal.rawRssi == null && signal.filteredRssi == null && String(signal.state).toUpperCase() === 'AWAY',
            pendingDisconnect: connectionUsable && signal.connected === false && signal.reason === 'confirming_disconnection'
        };
    }
    function disconnectionProgress(signal) {
        if (!el('raAdvancedMode').checked) return '';
        var seconds = finite(signal.disconnectedForMs) ? Math.floor(Math.max(0, signal.disconnectedForMs) / 1000) : 0;
        var count = finite(signal.disconnectionSampleCount) ? Math.max(0, signal.disconnectionSampleCount) : 0;
        return ' · ' + seconds + 's · ' + count + ' ' + tr('phone_native_checks', 'native checks');
    }
    function keyFobAge(value) {
        return finite(value) && value >= 0 ? value + Math.max(0, Date.now() - state.receivedAt) : null;
    }
    function eventAgeText(age) {
        if (age === null) return '—';
        if (age < 1000) return tr('age_now', '<1s ago');
        if (age < 60000) return tr('age_seconds', '{n}s ago', { n: Math.floor(age / 1000) });
        if (age < 3600000) return tr('age_minutes', '{n}m ago', { n: Math.floor(age / 60000) });
        return tr('age_hours', '{n}h ago', { n: Math.floor(age / 3600000) });
    }
    function keyFobView() {
        var fob = state.status && state.status.keyFob || {};
        var age = keyFobAge(fob.lastValidEventAgeMs);
        var configured = state.config && state.config.keyFobEnabled === true;
        var active = supports('keyFob') && state.enabled && configured && fob.active === true && fob.registered === true && fob.tableRegistered === true;
        var fresh = active && fob.fresh === true && age !== null && age <= 5000;
        var value = String(fob.state || 'UNKNOWN').toUpperCase();
        var label, detail, style;
        if (!supports('keyFob')) {
            label = tr('keyfob_unavailable', 'Unavailable'); detail = tr('keyfob_unsupported', 'Keyfob proximity is unsupported on this car.'); style = 'unavailable';
        } else if (!el('raKeyFobEnabled').checked) {
            label = tr('keyfob_disabled', 'Disabled'); detail = tr('keyfob_disabled_detail', 'Keyfob detection is disabled.'); style = 'disabled';
        } else if (!configured) {
            label = tr('keyfob_save_first', 'Save to start detection'); detail = tr('keyfob_save_first_detail', 'Save rules to apply keyfob detection.'); style = 'waiting';
        } else if (!state.enabled) {
            label = tr('keyfob_monitoring_off', 'Monitoring off'); detail = tr('keyfob_master_off', 'The master switch stops keyfob monitoring and actions.'); style = 'disabled';
        } else if (!active) {
            var reason = fob.reason || fob.sourceStatus;
            label = reason === 'vehicle_not_off' ? tr('keyfob_waiting_power', 'Waiting for power off') :
                reason === 'vehicle_not_locked' && !(state.config && state.config.keyFobLockEnabled === true) ?
                    tr('keyfob_waiting_lock', 'Waiting for vehicle lock') :
                reason === 'collector_stale' ? tr('keyfob_stale', 'Updates stopped') :
                reason === 'stopped' || reason === 'revoked' || reason === 'disabled' ? tr('keyfob_stopped', 'Stopped') :
                reason === 'receiver_unavailable' || reason === 'table_unavailable' ||
                reason === 'registration_failed' || reason === 'cleanup_failed' ||
                reason === 'unsupported_platform' || reason === 'unavailable' ? tr('keyfob_unavailable', 'Unavailable') :
                tr('keyfob_waiting', 'Waiting for vehicle signal');
            detail = keyFobReasonText(reason); style = label === tr('keyfob_unavailable', 'Unavailable') ||
                reason === 'collector_stale' ? 'unavailable' : 'waiting';
        } else if (fresh && value === 'NEAR') {
            label = tr('keyfob_detected', 'Detected');
            detail = tr('keyfob_fresh_detection', 'Fresh keyfob detection event') + ' · ' + eventAgeText(age); style = 'detected';
        } else if (fresh && value === 'AWAY') {
            label = tr('keyfob_away_event', 'Away event');
            detail = tr('keyfob_fresh_away', 'Fresh keyfob Away event') + ' · ' + eventAgeText(age); style = 'listening';
        } else if (age !== null && (fob.lastKnownState === 'NEAR' || fob.lastKnownState === 'AWAY')) {
            label = fob.lastKnownState === 'NEAR' ? tr('keyfob_last_detected', 'Last detected') : tr('keyfob_last_away', 'Last Away event');
            detail = eventAgeText(age) + ' · ' + tr('keyfob_listening_next', 'Listening for the next event. Current proximity is unknown.'); style = 'listening';
        } else {
            label = tr('keyfob_listening', 'Listening');
            detail = tr('keyfob_waiting_event', 'Waiting for a fresh keyfob approach event.'); style = 'listening';
        }
        if (supports('keyFob') && !supports('keyFobLock') && el('raKeyFobEnabled').checked)
            detail += ' ' + tr('keyfob_departure_unsupported', 'Keyfob departure locking is unsupported on this car.');
        return { fob: fob, age: age, active: active, fresh: fresh, state: value, label: label, detail: detail, style: style };
    }
    function keyFobReasonText(reason) {
        if (reason === 'vehicle_not_locked' && state.config && state.config.keyFobLockEnabled === true)
            return tr('keyfob_lock_state_waiting', 'Waiting for a valid vehicle lock state.');
        var labels = {
            disabled: 'Keyfob detection is disabled.', master_off: 'Proximity is disabled.',
            no_device: 'Waiting for the vehicle keyfob receiver.',
            waiting_for_collector: 'Waiting for the car app to start keyfob monitoring.',
            collector_stale: 'Updates from the car app stopped. Keyfob actions are suspended.',
            scope_stale: 'Vehicle power or lock state expired. Keyfob actions are suspended.',
            vehicle_ineligible: 'Vehicle power off is not confirmed. Keyfob actions are suspended.',
            vehicle_on: 'Keyfob actions require confirmed vehicle power off.',
            vehicle_not_off: 'Keyfob detection waits until vehicle power off is confirmed.',
            vehicle_not_locked: 'Keyfob detection listens while the vehicle is locked and powered off.',
            power_unknown: 'Waiting for confirmed vehicle power off.',
            lock_unknown: 'Waiting for a valid vehicle lock state.',
            departure_unknown: 'Keyfob departure has not been confirmed. Automatic locking is suspended.',
            waiting_for_vehicle: 'Waiting for the vehicle to report power and lock state.',
            stopped: 'Keyfob monitoring is stopped.',
            revoked: 'Keyfob settings changed. Waiting for the current receiver.',
            unsupported_platform: 'Keyfob detection is unavailable on this vehicle.',
            unavailable: 'The vehicle keyfob receiver is unavailable.',
            receiver_unavailable: 'The vehicle keyfob receiver is unavailable.',
            table_unavailable: 'The vehicle keyfob event stream is unavailable.',
            registration_failed: 'The vehicle keyfob listener could not start.',
            cleanup_failed: 'The vehicle keyfob receiver could not stop cleanly. Actions are suspended.',
            waiting_for_signal: 'Waiting for a fresh vehicle keyfob event.',
            waiting_for_fresh_event: 'Listening for the next fresh keyfob event.',
            event_stale: 'The last keyfob event expired. Waiting for a fresh event.',
            unknown_event: 'The vehicle event does not report keyfob presence.'
        };
        if (Object.prototype.hasOwnProperty.call(labels, reason)) return tr('keyfob_reason_' + reason, labels[reason]);
        return tr('keyfob_waiting_source', 'Waiting for the vehicle keyfob receiver.');
    }
    function renderKeyFobStatus() {
        var panel = el('raKeyFobPanel'), view = keyFobView();
        panel.hidden = supports('keyFob') && el('raKeyFobEnabled').checked !== true;
        panel.setAttribute('data-state', view.style);
        text('raKeyFobState', view.label); text('raKeyFobDetail', view.detail);
        text('raKeyFobMask', finite(view.fob.lastMask) && view.fob.lastMask >= 0 && Math.floor(view.fob.lastMask) === view.fob.lastMask ?
            '0x' + view.fob.lastMask.toString(16).toUpperCase() : '—');
        text('raKeyFobAge', eventAgeText(view.age));
        text('raKeyFobSource', view.active ? tr('keyfob_source_can', 'Vehicle CAN · 0x180') : view.fob.sourceStatus ?
            String(view.fob.sourceStatus).replace(/_/g, ' ') : '—');
        var lockDraft = el('raKeyFobEnabled').checked && el('raKeyFobLockEnabled').checked;
        el('raKeyFobDepartureRow').hidden = !lockDraft;
        var presence = view.fob.presence || {};
        text('raKeyFobDeparture', !supports('keyFob') || !supports('keyFobLock') ? tr('unsupported_on_car', 'Unsupported on this car') :
            !state.config || state.config.keyFobLockEnabled !== true ?
            tr('keyfob_lock_save_first', 'Save to apply') : !state.enabled ?
                tr('keyfob_monitoring_off', 'Monitoring off') : pausedKeyFobPresence(presence) ?
                    tr('keyfob_presence_paused_locked', 'Paused while locked') : freshKeyFobPresence(presence) && presence.departureKnown === true ?
                    tr('keyfob_departure_confirmed', 'Departure confirmed') :
                    tr('keyfob_departure_waiting', 'Waiting for departure'));
        renderKeyFobPresence(presence, lockDraft);
        renderKeyFobMarker(view);
        return view;
    }
    function freshKeyFobPresence(presence) {
        var extraAge = state.receivedAt ? Math.max(0, Date.now() - state.receivedAt) : 0;
        var age = finite(presence.lastObservationAgeMs) ? presence.lastObservationAgeMs + extraAge : null;
        return presence.active === true && presence.fresh === true && age !== null &&
            age >= 0 && age <= 20000 && extraAge <= 5000 &&
            (!finite(presence.heartbeatAgeMs) || presence.heartbeatAgeMs + extraAge <= 5000) &&
            (!finite(presence.scopeAgeMs) || presence.scopeAgeMs + extraAge <= 5000);
    }
    function pausedKeyFobPresence(presence) {
        var extraAge = state.receivedAt ? Math.max(0, Date.now() - state.receivedAt) : 0;
        return presence.active === false && presence.reason === 'presence_search_paused_locked' &&
            extraAge <= 5000 && finite(presence.heartbeatAgeMs) && presence.heartbeatAgeMs >= 0 &&
            presence.heartbeatAgeMs + extraAge <= 5000 && finite(presence.scopeAgeMs) &&
            presence.scopeAgeMs >= 0 && presence.scopeAgeMs + extraAge <= 5000;
    }
    function renderKeyFobPresence(presence, lockDraft) {
        el('raKeyFobPresenceRow').hidden = !lockDraft;
        var label, fresh = freshKeyFobPresence(presence);
        if (!supports('keyFob') || !supports('keyFobLock')) label = tr('unsupported_on_car', 'Unsupported on this car');
        else if (!state.config || state.config.keyFobLockEnabled !== true) label = tr('keyfob_lock_save_first', 'Save to apply');
        else if (!state.enabled) label = tr('keyfob_monitoring_off', 'Monitoring off');
        else if (pausedKeyFobPresence(presence)) label = tr('keyfob_presence_paused_locked', 'Paused while locked');
        else if (!fresh) label = tr('keyfob_presence_unavailable', 'Waiting for a fresh search');
        else if (presence.reason === 'fresh_key_approach')
            label = tr('keyfob_presence_nearby_paused', 'Nearby keyfob · locking paused');
        else if (presence.state === 'PRESENT') label = tr('keyfob_presence_present_paused', 'Present · locking paused');
        else if (presence.state === 'ABSENT' && presence.departureKnown === true)
            label = tr('keyfob_departure_confirmed', 'Departure confirmed');
        else if (presence.state === 'ABSENT' && presence.armed === true)
            // 20 Oct: progress in completed searches (keyFobLockSamples); the window form is the fallback.
            label = tr('keyfob_presence_absent', 'Absent') + ' · ' +
                (finite(presence.sampleCount) && finite(presence.requiredSamples) ?
                    presence.sampleCount + '/' + presence.requiredSamples :
                    Math.floor(Math.max(0, presence.absentForMs || 0) / 1000) + '/' +
                    tr('seconds_short', '{n}s', { n: Math.floor((presence.windowMs || KEYFOB_LOCK_DEFAULTS.windowMs) / 1000) }));
        else if (presence.state === 'ABSENT' && presence.reason === 'presence_vehicle_on')
            label = tr('keyfob_presence_absent_power_on', 'Absent · waiting for power off');
        else if (presence.state === 'ABSENT')
            label = tr('keyfob_presence_absence_pending', 'Absent · waiting for a fresh search');
        else label = tr('keyfob_presence_unavailable', 'Waiting for a fresh search');
        text('raKeyFobPresence', label);
    }
    function svgNode(tag, attributes, value) {
        var node = document.createElementNS('http://www.w3.org/2000/svg', tag);
        for (var key in attributes) if (Object.prototype.hasOwnProperty.call(attributes, key)) {
            node.setAttribute(key, attributes[key]);
        }
        if (value !== undefined) node.textContent = value;
        return node;
    }
    function renderPhoneMarkers() {
        var markers = el('raPhoneMarkers'), links = el('raPhoneLinks');
        while (markers.firstChild) markers.removeChild(markers.firstChild);
        while (links.firstChild) links.removeChild(links.firstChild);
        var count = 0;
        // Fixed roster slots keep a phone still when another observation changes.
        var slots = [[208, 44], [326, 71], [376, 142], [326, 204],
            [208, 223], [90, 204], [40, 142], [90, 71]];
        var saved = { phones: profilesFromConfig(state.config || {}) };
        if (state.enabled && visible() && hasActivePhones(saved.phones, state.config && state.config.keepBluetoothOn))
            state.phones.slice(0, 8).forEach(function (phone, index) {
            var approved = customProfile(saved, phone.address);
            if (!phone.enabled || !approved || !approved.enabled) return;
            var signal = signalFor(phone.address), proof = phoneSignalProof(signal), age = proof.age;
            if (!proof.rssi || String(signal.state).toUpperCase() !== 'NEAR' ||
                !finite(signal.ageMs) || signal.ageMs < 0 || age === null ||
                age > (state.config && state.config.staleMs || 5000) ||
                !finite(signal.timestampMs) || !finite(signal.rawRssi) || !finite(signal.filteredRssi) ||
                (signal.sampleCount || 0) < 3) return;
            var slot = slots[index], name = String(phone.name || signal.deviceName || tr('phone_number', 'Phone {n}', { n: index + 1 }));
            var shortName = name.replace(/\s+/g, ' ').trim();
            if (shortName.length > 9) shortName = shortName.slice(0, 8) + '…';
            var marker = svgNode('g', { 'class': 'ra-phone-marker', role: 'img',
                'aria-label': name + ' · ' + tr('state_nearby', 'Nearby'), transform: 'translate(' + slot[0] + ' ' + slot[1] + ')' });
            marker.appendChild(svgNode('title', {}, name + ' · ' + tr('state_nearby', 'Nearby')));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-halo', r: '22' }));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-disc', r: '16' }));
            marker.appendChild(svgNode('rect', { x: '-5.5', y: '-9', width: '11', height: '18',
                rx: '2.5', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.5' }));
            marker.appendChild(svgNode('path', { d: 'M-2-6h4M-1 6h2', fill: 'none',
                stroke: 'currentColor', 'stroke-width': '1.5', 'stroke-linecap': 'round' }));
            marker.appendChild(svgNode('text', { 'class': 'ra-phone-label', x: '0', y: '30',
                'text-anchor': 'middle' }, (index + 1) + ' · ' + shortName));
            var linkX = 208 + (slot[0] - 208) * .45, linkY = 142 + (slot[1] - 142) * .45;
            links.appendChild(svgNode('path', { 'class': 'ra-phone-link', fill: 'none',
                d: 'M' + linkX + ' ' + linkY + 'L' + slot[0] + ' ' + slot[1] }));
            markers.appendChild(marker);
            count++;
        });
        markers.style.display = links.style.display = count ? '' : 'none';
        el('raRadar').setAttribute('data-nearby-phones', String(count));
    }
    function renderKeyFobMarker(view) {
        var markers = el('raKeyFobMarkers'), links = el('raKeyFobLinks');
        while (markers.firstChild) markers.removeChild(markers.firstChild);
        while (links.firstChild) links.removeChild(links.firstChild);
        view = view || keyFobView();
        var shown = visible() && el('raKeyFobEnabled').checked && view.fresh && view.state === 'NEAR';
        if (shown) {
            var label = tr('keyfob_marker', 'Keyfob · Detected');
            var marker = svgNode('g', { 'class': 'ra-keyfob-marker', role: 'img',
                'aria-label': label, transform: 'translate(150 44)' });
            marker.appendChild(svgNode('title', {}, label));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-halo', r: '22' }));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-disc', r: '16' }));
            marker.appendChild(svgNode('circle', { cx: '-5', cy: '-4', r: '4.5', fill: 'none',
                stroke: 'currentColor', 'stroke-width': '1.6' }));
            marker.appendChild(svgNode('path', { d: 'M-2-1L8 9M3 4l3-3M6 7l3-3', fill: 'none',
                stroke: 'currentColor', 'stroke-width': '1.6', 'stroke-linecap': 'round',
                'stroke-linejoin': 'round' }));
            marker.appendChild(svgNode('text', { 'class': 'ra-phone-label', x: '0', y: '30',
                'text-anchor': 'middle' }, tr('keyfob_marker_label', 'Keyfob')));
            links.appendChild(svgNode('path', { 'class': 'ra-phone-link', fill: 'none',
                d: 'M182 98L150 44' }));
            markers.appendChild(marker);
        }
        markers.style.display = links.style.display = shown ? '' : 'none';
        el('raRadar').setAttribute('data-keyfob-near', shown ? 'true' : 'false');
    }
    function renderSignal() {
        renderBluetoothStatus();
        updateRulesSummary();
        var fob = renderKeyFobStatus();
        var signal = currentSignal();
        var proof = phoneSignalProof(signal), age = proof.age;
        var phonesActive = state.config && hasActivePhones(profilesFromConfig(state.config), state.config.keepBluetoothOn);
        var bluetoothPaused = state.config && state.config.keepBluetoothOn !== true;
        var vehicleOn = vehicleOnPaused();
        var stale = state.enabled && (!phonesActive || !proof.fresh);
        var connected = signal.connected === true && phonesActive && proof.connectionUsable;
        var disconnectedAway = phonesActive && proof.confirmedAway;
        var pendingDisconnect = phonesActive && proof.pendingDisconnect;
        var reconnect = signal.reconnect || {};
        var reconnectReason = !connected && selectedDevice().enabled && signal.reason !== 'phone_disabled' && reconnect.reason;
        var proximity = !state.enabled ? 'DISABLED' : stale ? 'UNKNOWN' : disconnectedAway ? 'AWAY' :
            connected && proof.rssi ? String(signal.state || 'UNKNOWN').toUpperCase() : 'UNKNOWN';
        var label = proximity === 'NEAR' ? tr('state_nearby', 'Nearby') : proximity === 'AWAY' ? tr('state_away_title', 'Away') :
            proximity === 'DISABLED' ? tr('state_disabled', 'Disabled') : tr('state_unknown', 'Unknown');
        var detail = !state.enabled ? tr('reason_disabled', 'No background monitoring') :
            stale ? reasonText('stale') :
            disconnectedAway ? tr('phone_disconnection_confirmed_detail', 'Phone disconnection confirmed · waiting for your return') + disconnectionProgress(signal) :
            pendingDisconnect ? tr('phone_disconnection_pending', 'Disconnected · confirming departure') + disconnectionProgress(signal) :
            !proof.connectionUsable ? signal.radioOn === false && signal.radioReadKnown !== false ?
                reasonText('radio_off') : tr('phone_connection_unconfirmed_detail', 'Vehicle Bluetooth connection state is unconfirmed. Automatic actions are paused.') :
            !connected ? reconnectReason === 'unsupported_platform' ? tr('reconnect_unsupported', 'Phone reconnect is unsupported on this platform') :
                reasonText(reconnectReason || signal.reason || 'disconnected') :
            reasonText(signal.reason);
        if (state.enabled && connected && proof.rssi && !stale && signal.candidate && signal.candidate !== signal.state && signal.candidate !== 'UNKNOWN') {
            detail = tr('confirming_candidate', 'Confirming {state} · {n}s stable',
                { state: proximityStateName(signal.candidate), n: Math.floor((signal.stableForMs || 0) / 1000) });
        } else if (state.enabled && connected && !stale && proximity === 'NEAR') {
            detail = state.config && state.config.unlockTrigger === 'near' ? tr('reason_stable_near', 'Stable nearby signal') :
                signal.armed ? tr('stable_near_armed', 'Stable nearby signal · arrival armed') :
                    tr('stable_near_unarmed', 'Stable nearby signal · confirm departure to arm arrival');
        } else if (state.enabled && connected && !stale && proximity === 'AWAY') {
            detail = signal.armed ? tr('stable_away_armed', 'Stable away signal · waiting for your return') : tr('reason_stable_away', 'Stable away signal');
        }
        if (state.enabled && signal.reason === 'vehicle_ineligible') detail = reasonText(signal.reason);
        if (!el('raAdvancedMode').checked && state.enabled && connected && proof.rssi && !stale &&
            signal.candidate && signal.candidate !== signal.state && signal.candidate !== 'UNKNOWN') {
            detail = signal.candidate === 'NEAR' ? tr('confirming_phone_nearby', 'Confirming the phone is nearby') :
                tr('confirming_phone_away', 'Confirming the phone is away');
        }
        if (vehicleOn) {
            label = tr('phone_proximity_paused', 'Paused');
            detail = tr('phone_vehicle_on_detail', 'Proximity resumes when the car is switched off.');
            proximity = 'UNKNOWN';
        }
        if (!supports('bluetooth') && state.enabled) {
            label = tr('keyfob_unavailable', 'Unavailable'); detail = tr('phone_proximity_unsupported', 'Phone proximity is unsupported on this car.'); proximity = 'UNKNOWN';
        } else if (state.enabled && bluetoothPaused) {
            label = tr('phone_proximity_paused', 'Paused');
            detail = tr('phone_proximity_bluetooth_off', 'Parked Bluetooth is disabled. Phone proximity actions are paused.');
            proximity = 'UNKNOWN';
        }
        var fobOnly = supports('keyFob') && state.config && state.config.keyFobEnabled === true && !phonesActive;
        if (state.enabled && fobOnly) {
            label = fob.label; detail = fob.detail;
            proximity = fob.fresh && fob.state === 'NEAR' ? 'NEAR' : 'UNKNOWN';
        }
        text('raSignalTitle', !state.enabled ? tr('monitoring_disabled_title', 'Monitoring is disabled') : fobOnly ?
            tr('keyfob_monitoring_title', 'Monitoring your keyfob') :
            !supports('bluetooth') ? tr('phone_proximity_unavailable_title', 'Phone proximity unavailable') : bluetoothPaused ?
                tr('phone_proximity_paused_title', 'Phone proximity paused') :
                vehicleOn ? tr('phone_vehicle_on_title', 'Paused while the car is on') :
                disconnectedAway ? tr('phone_away_title', 'Your phone is away') :
                pendingDisconnect ? tr('phone_departure_confirming_title', 'Confirming phone departure') :
                !connected ? tr('waiting_for_phone_title', 'Waiting for your phone') :
                proximity === 'NEAR' ? tr('phone_nearby_title', 'Your phone is nearby') :
                proximity === 'AWAY' ? tr('phone_away_title', 'Your phone is away') :
                proximity === 'DISABLED' ? tr('phone_disabled_title', 'Your phone is disabled') :
                vehicleOn || label === tr('phone_proximity_paused', 'Paused') ? tr('phone_paused_title', 'Your phone is paused') :
                tr('phone_unknown_title', 'Your phone is unknown'));
        text('raProximityState', label);
        text('raProximityDetail', detail);
        el('raRadar').setAttribute('data-state', proximity.toLowerCase());
        var showRssi = phonesActive && state.enabled && proof.rssi;
        text('raRawValue', showRssi ? signalText(signal.rawRssi) : '—');
        text('raFilteredValue', showRssi ? signalText(signal.filteredRssi) : '—');
        var arrivalWindow = state.config && state.config.nearWindowMs || TIMING_DEFAULTS.nearWindowMs;
        var departureWindow = state.config && state.config.windowMs || TIMING_DEFAULTS.windowMs;
        text('raFilterDetail', disconnectedAway ? tr('phone_disconnect_lock_evidence', 'Lock · confirmed disconnection') : signal.filterKind === 'arrival' ?
            tr('filter_unlock', 'Unlock · last 3 / {n}s', { n: arrivalWindow / 1000 }) :
            tr('filter_lock', 'Lock · {n}s median', { n: departureWindow / 1000 }));
        // Two fresh Near readings in the window are the confirming floor (ProximityEvaluator.MIN_CONFIRMING_SAMPLES).
        text('raArrivalDiagnostics', tr('diagnostics_unlock', 'Unlock: {signal} · {count}/2 fresh Near readings · {n}s confirmed', {
            signal: showRssi ? signalText(signal.arrivalFilteredRssi) : '—',
            count: showRssi ? signal.nearSampleCount || 0 : 0,
            n: Math.floor((showRssi ? signal.nearStableForMs || 0 : 0) / 1000) }));
        text('raDepartureDiagnostics', disconnectedAway ?
            tr('phone_disconnect_lock_evidence', 'Lock · confirmed disconnection') + disconnectionProgress(signal) :
            pendingDisconnect ? tr('phone_disconnect_lock_pending', 'Lock · confirming disconnection') + disconnectionProgress(signal) :
            tr('diagnostics_lock', 'Lock: {signal} · {n}s median',
                { signal: showRssi ? signalText(signal.departureFilteredRssi) : '—', n: departureWindow / 1000 }));
        text('raAgeValue', !state.enabled || age === null ? '—' : age < 1000 ? tr('seconds_under_one', '<1s') :
            tr('seconds_short', '{n}s', { n: Math.floor(age / 1000) }));
        text('raFreshDetail', !state.enabled ? tr('no_reading', 'No reading') : vehicleOn ? tr('phone_vehicle_on', 'Paused · car is on') :
            stale ? tr('reading_expired', 'Expired / unavailable') :
            disconnectedAway || pendingDisconnect ? tr('phone_connection_evidence_fresh', 'Fresh connection evidence') :
            !proof.connectionUsable ? tr('phone_connection_unconfirmed', 'Unknown · connection unconfirmed') :
            tr('fresh_samples', 'Fresh · {count} samples', { count: signal.sampleCount || 0 }));
        text('raConnectionState', state.viewAddress ? vehicleOn ? tr('phone_proximity_paused', 'Paused') :
            !proof.connectionUsable ? tr('state_unknown', 'Unknown') : connected ? tr('state_connected', 'Connected') :
                tr('state_disconnected', 'Disconnected') : tr('state_not_selected', 'Not selected'));
        el('raConnectionDot').className = 'ra-status-dot' + (connected ? ' connected' : '');
        text('raDeviceAddress', state.viewAddress || '—');
        text('raArmedState', state.config && state.config.unlockTrigger === 'near' ?
            tr('departure_not_required', 'Prior departure not required') :
            state.enabled && signal.armed && !stale && (connected || disconnectedAway) ? tr('armed_for_return', 'Armed for return') : tr('disarmed', 'Disarmed'));
        text('raModeLabel', state.config && state.config.dryRun === false ? tr('automatic_actions', 'Automatic actions') : tr('monitor_preview', 'Monitor preview'));
        renderPhoneMarkers();
        var captureReady = supports('bluetooth') && state.enabled && connected && proof.rssi && !stale && selectedDevice().enabled &&
            el('raAdvancedMode').checked && state.config && state.config.advancedMode &&
            (signal.sampleCount || 0) >= 3 && !state.saving;
        el('raCaptureNear').disabled = !captureReady;
        el('raCaptureAway').disabled = !captureReady;
        var runtime = state.status && state.status.runtime || {};
        text('raMasterDetail', !state.enabled ?
            runtime.cleanupPending ? tr('master_cleanup_pending', 'Settings disabled · collector shutdown is awaiting confirmation from the car app.') :
            tr('master_off_detail', 'Proximity actions and sampling are stopped. The shared parked Bluetooth setting remains independent.') :
            // Approved phones with no running phone collector: say so, even while the keyfob rail is
            // active; "Monitoring approved phones" must never describe a dead collector.
            phonesActive && !runtime.active ? tr('enabled_detail', 'Enabled · {detail}', { detail: reasonText(runtime.reason || 'waiting_for_collector') }) :
            runtime.active || fob.active ? supports('keyFob') && state.config && state.config.keyFobEnabled === true ?
                fobOnly ? tr('master_monitoring_keyfob', 'Monitoring keyfob events. Automatic actions require confirmed vehicle power off.') :
                tr('master_monitoring_phones_keyfob', 'Monitoring approved phones and keyfob events. Automatic actions require confirmed vehicle power off.') :
                phonesActive ? tr('master_monitoring_phones', 'Monitoring approved phones. Automatic actions require confirmed vehicle power off.') :
                tr('proximity_sources_paused', 'No active proximity source. Enable parked Bluetooth or supported keyfob detection.') :
            fobOnly ? tr('enabled_detail', 'Enabled · {detail}', { detail: fob.detail }) :
            bluetoothPaused ? tr('phone_proximity_bluetooth_off', 'Parked Bluetooth is disabled. Phone proximity actions are paused.') :
            tr('enabled_detail', 'Enabled · {detail}', { detail: reasonText(runtime.reason || signal.reason) }));
        text('raRadioNote', !supports('bluetoothReconnect') ?
            tr('radio_note_unsupported', 'Automatic phone reconnect is unsupported on this car. The shared parked Bluetooth setting remains available.') :
            state.config && state.config.keepBluetoothOn === true ?
            state.enabled ?
                tr('radio_note_active', 'Proximity makes limited reconnect attempts for saved, enabled paired phones while parked. It keeps any existing active phone connection. Signal strength is relative; it is not a distance measurement.') :
                tr('radio_note_master_off', 'The shared setting keeps Bluetooth on while parked. With Proximity off, it does not reconnect phones. Pair approved phones in the car\'s Bluetooth settings before enabling Proximity.') :
            tr('radio_note_bluetooth_off', 'The shared parked Bluetooth setting is off. Parked phone reconnect requires this setting and Proximity to be on. Pair approved phones in the car\'s Bluetooth settings first.'));
        renderRoster();
    }
    function setStreamLabel(key, fallback, live) {
        state.streamLabel = [key, fallback, live];
        text('raStreamLabel', tr(key, fallback));
        el('raStreamState').className = 'ra-stream-state' + (live ? ' is-live' : '');
        el('remoteAccessPage').classList.toggle('ra-live', !!live && isLive());
    }
    function appendSample(signal) {
        if (!supports('bluetooth') || !state.enabled || !finite(signal.timestampMs) || signal.timestampMs === state.lastSampleTime ||
            !phoneSignalProof(signal).rssi ||
            signal.deviceAddress && signal.deviceAddress.toUpperCase() !== state.viewAddress.toUpperCase()) return;
        state.lastSampleTime = signal.timestampMs;
        state.samples.push({ time: signal.timestampMs, raw: signal.rawRssi, filtered: signal.filteredRssi });
        var cutoff = signal.timestampMs - 90000;
        state.samples = state.samples.filter(function (sample) { return sample.time >= cutoff; }).slice(-180);
    }
    function linePath(samples, key, x, y) {
        var path = '', connected = false;
        samples.forEach(function (sample) {
            if (!finite(sample[key])) { connected = false; return; }
            path += (connected ? 'L' : 'M') + x(sample.time).toFixed(2) + ' ' + y(sample[key]).toFixed(2);
            connected = true;
        });
        return path;
    }
    function drawChart() {
        var samples = state.samples, near = Number(el('raNearThreshold').value), away = Number(el('raAwayThreshold').value);
        if (!finite(near)) near = -12;
        if (!finite(away)) away = -24;
        var min = Math.min(-45, away - 10), max = Math.max(5, near + 10);
        samples.forEach(function (sample) { min = Math.min(min, sample.raw - 5); max = Math.max(max, sample.raw + 5); });
        min = Math.floor(min / 5) * 5; max = Math.ceil(max / 5) * 5;
        var end = samples.length ? samples[samples.length - 1].time : Date.now(), start = end - 90000;
        function x(time) { return 48 + Math.max(0, Math.min(1, (time - start) / 90000)) * 838; }
        function y(value) { return 164 - Math.max(0, Math.min(1, (value - min) / (max - min))) * 148; }
        var filtered = linePath(samples, 'filtered', x, y);
        el('raChartRaw').setAttribute('d', linePath(samples, 'raw', x, y));
        el('raChartFiltered').setAttribute('d', filtered);
        var valid = samples.filter(function (sample) { return finite(sample.filtered); });
        el('raChartFill').setAttribute('d', filtered && valid.length ?
            filtered + 'L' + x(valid[valid.length - 1].time).toFixed(2) + ' 164L' + x(valid[0].time).toFixed(2) + ' 164Z' : '');
        el('raChartNear').setAttribute('d', 'M48 ' + y(near) + 'H886');
        el('raChartAway').setAttribute('d', 'M48 ' + y(away) + 'H886');
        el('raNearLabel').setAttribute('y', Math.max(16, y(near) - 5));
        el('raAwayLabel').setAttribute('y', Math.min(172, y(away) + 13));
        text('raNearLabel', tr('chart_near', 'Near {value}', { value: signalText(near) }));
        text('raAwayLabel', tr('chart_away', 'Away {value}', { value: signalText(away) }));
        text('raChartMax', signalText(max)); text('raChartMin', signalText(min));
        el('raChartEmpty').hidden = samples.length > 0;
        text('raChartEmpty', state.enabled ? vehicleOnPaused() ? tr('phone_vehicle_on_title', 'Paused while the car is on') :
            tr('chart_waiting', 'Waiting for fresh readings from your selected phone.') : tr('chart_enable', 'Enable proximity access to see live readings.'));
        text('raChartStatus', state.enabled ? tr('chart_readings', '{count} readings shown', { count: samples.length }) : tr('keyfob_monitoring_off', 'Monitoring off'));
    }
    function eventLabel(event) {
        if (event.message) return serverText(event, 'message');
        var labels = { arrival: 'Phone arrived', departure: 'Phone departed', dry_run: 'Learning observation',
            accepted: 'Command sent · awaiting confirmation', confirmed: 'Vehicle state confirmed',
            unconfirmed: 'Command sent · no state confirmation',
            cloud_acknowledged: 'Cloud command acknowledged · no local confirmation',
            sent: 'Command sent · vehicle confirmation off',
            blocked: 'Action blocked', failed: 'Action failed' };
        if (Object.prototype.hasOwnProperty.call(labels, event.type)) return tr('event_' + event.type, labels[event.type]);
        return event.type ? String(event.type).replace(/_/g, ' ') : tr('event_activity', 'Activity');
    }
    function renderEvents(events) {
        var list = el('raEventList');
        while (list.firstChild) list.removeChild(list.firstChild);
        events = Array.isArray(events) ? events.slice(0, 40) : [];
        text('raEventCount', events.length === 1 ? tr('event_count_one', '1 event') :
            tr('event_count', '{count} events', { count: events.length }));
        if (!events.length) {
            var empty = document.createElement('div'); empty.className = 'ra-history-empty';
            empty.textContent = tr('history_empty', 'Detected transitions and action results will appear here.');
            list.appendChild(empty); return;
        }
        events.forEach(function (event) {
            var row = document.createElement('div'); row.className = 'ra-event';
            var mark = document.createElement('span');
            var kind = String(event.result || event.type || '').toLowerCase();
            mark.className = 'ra-event-mark ' + (kind === 'confirmed' ? 'confirmed' :
                kind === 'blocked' || kind === 'failed' ? kind : kind === 'arrival' || kind === 'departure' || kind === 'dry_run' ? 'detected' : '');
            var content = document.createElement('div'); content.className = 'ra-event-content';
            var title = document.createElement('strong'); title.textContent = eventLabel(event);
            content.appendChild(title);
            if (event.action || event.result) {
                var detail = document.createElement('span');
                detail.textContent = [event.action ? actionName(event.action) : '', event.result ? resultName(event.result) : '']
                    .filter(Boolean).join(' · ');
                content.appendChild(detail);
            }
            var time = document.createElement('time');
            if (finite(event.timestampMs) && Math.abs(event.timestampMs) <= 8640000000000000) {
                var date = new Date(event.timestampMs);
                time.dateTime = date.toISOString();
                time.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                time.title = date.toLocaleString();
            } else time.textContent = '—';
            row.appendChild(mark); row.appendChild(content); row.appendChild(time); list.appendChild(row);
        });
    }
    function renderLock(lastAction) {
        if (!lastAction || !lastAction.status) { text('raLockState', tr('state_unknown', 'Unknown')); return; }
        var lock = lastAction.action === 'lock' || lastAction.action === 'lock_car';
        var status = lastAction.status;
        var english = { confirmed: '{action} confirmed', accepted: '{action} sent · awaiting readback',
            unconfirmed: '{action} sent · unconfirmed', cloud_acknowledged: '{action} sent · cloud acknowledged, unconfirmed locally',
            sent: '{action} sent · vehicle confirmation off', dry_run: '{action} observed · learning',
            blocked: '{action} blocked', failed: '{action} failed' };
        var label = Object.prototype.hasOwnProperty.call(english, status) ?
            tr((lock ? 'lock_status_' : 'unlock_status_') + status, english[status], { action: lock ? 'Lock' : 'Unlock' }) :
            tr('state_unknown', 'Unknown');
        text('raLockState', label);
        el('raLockState').title = [serverText(lastAction, 'reason'),
            lastAction.confirmationScope === 'driver_door' ? tr('scope_driver_door', 'driver door') : lastAction.confirmationScope]
            .filter(Boolean).join(' · ');
    }
    function acceptStatus(data, forceConfig) {
        if (!data || !data.config) throw new Error(tr('status_incomplete', 'Vehicle returned an incomplete proximity status.'));
        if (typeof data.sessionId === 'string' && data.sessionId) {
            if (state.retiredSessions.indexOf(data.sessionId) !== -1) return;
            if (state.serverSessionId && state.serverSessionId !== data.sessionId) {
                state.retiredSessions.push(state.serverSessionId);
                state.retiredSessions = state.retiredSessions.slice(-8);
                state.sequence = null; state.samples = []; state.lastSampleTime = null;
                state.failures = 0;
                stopLive();
            }
            state.serverSessionId = data.sessionId;
        }
        // A pre-save status read can arrive after the newer config response.
        // Never let that older snapshot turn monitoring back on.
        if (finite(data.sequence) && finite(state.sequence) && data.sequence < state.sequence) return;
        var wasEnabled = state.enabled;
        state.status = data; state.config = copy(data.config); state.savedConfig = data.savedConfig || data.config; state.receivedAt = Date.now();
        if (Object.prototype.hasOwnProperty.call(data, 'bluetooth')) updateBluetoothStatus(data.bluetooth, false);
        state.sequence = data.sequence; state.enabled = !!data.config.enabled; state.ready = true;
        // This preference saves independently of proximity-rule drafts and is
        // shared with Surveillance, Automations and key bindings.
        if (!state.bluetoothSaving) el('raKeepBluetoothOn').checked = data.config.keepBluetoothOn === true;
        if (wasEnabled && !state.enabled && !state.modelPosterValid) {
            state.cachePreviewTried = false; state.offRenderTried = false;
        }
        if (!state.enabled) {
            state.samples = []; state.lastSampleTime = null;
        }
        if (Array.isArray(data.devices) && data.devices.length) state.devices = data.devices;
        if (forceConfig || !state.dirty) {
            var oldView = state.viewAddress;
            state.phones = profilesFromConfig(data.config);
            state.viewAddress = profileFor(oldView) ? oldView : data.config.deviceAddress || (state.phones[0] && state.phones[0].address) || '';
        }
        if (forceConfig || !state.dirty) applyConfig(data.config);
        el('raMasterToggle').checked = state.enabled;
        el('raMasterToggle').disabled = state.saving;
        el('raSaveBtn').disabled = state.saving;
        fields.forEach(function (id) { el(id).disabled = state.saving; });
        el('raFastUnlockDefaults').disabled = state.saving;
        updateUnlockTrigger();
        el('remoteAccessPage').classList.toggle('ra-enabled', state.enabled);
        text('raMasterBadge', state.enabled ? tr('state_enabled', 'Enabled') : data.runtime && data.runtime.cleanupPending ?
            tr('state_cleanup_pending', 'Cleanup pending') : tr('state_disabled', 'Disabled'));
        el('raMasterBadge').className = 'ra-badge' + (state.enabled ? ' is-on' : '');
        appendSample(currentSignal());
        renderSignal(); drawChart(); renderEvents(data.events); renderLock(data.lastAction);
        renderSafeZoneStatus(); renderSafeZoneChoices();
        if (!state.enabled) stopLive();
        else if (visible()) startLive();
        syncVehiclePreview();
    }

    function captureVehiclePoster(canvas) {
        canvas = canvas || el('raVehicleCanvas');
        if (!canvas || !canvas.toDataURL) return;
        try {
            var image = canvas.toDataURL('image/webp', .88);
            if (image && image !== 'data:,') {
                el('raVehiclePoster').src = image;
                el('raVehiclePoster').hidden = false;
                el('raVehiclePlaceholder').hidden = true;
                state.modelPosterValid = true;
            }
        } catch (ignored) { /* Keep the prior valid selected-model poster. */ }
    }
    function abortVehicleArt() {
        state.artGeneration++;
        if (state.artRequest) { var request = state.artRequest; state.artRequest = null; request.abort(); }
    }
    function loadStaticVehicleArt() {
        if (!visible() || state.enabled || state.artRequest || el('raVehiclePoster').src) return;
        var generation = ++state.artGeneration;
        function request(path, type, done) {
            if (generation !== state.artGeneration || !visible() || state.enabled) return;
            var xhr = new XMLHttpRequest(); state.artRequest = xhr;
            xhr.open('GET', path, true); xhr.withCredentials = true; xhr.timeout = 6000;
            if (type === 'blob') xhr.responseType = 'blob';
            var headers = window.BYDAuth ? BYDAuth.getAuthHeaders() : {};
            for (var name in headers) if (Object.prototype.hasOwnProperty.call(headers, name)) xhr.setRequestHeader(name, headers[name]);
            xhr.onload = function () {
                if (state.artRequest === xhr) state.artRequest = null;
                if (generation !== state.artGeneration || !visible() || state.enabled || xhr.status < 200 || xhr.status >= 300) return;
                try { done(type === 'blob' ? xhr.response : JSON.parse(xhr.responseText)); } catch (ignored) {}
            };
            function finish() { if (state.artRequest === xhr) state.artRequest = null; }
            xhr.onerror = finish; xhr.ontimeout = finish; xhr.onabort = finish; xhr.send();
        }
        // Same one-shot selected-vehicle artwork as Vehicle Control's lite
        // hero. No WebGL, polling, Bluetooth read, or feature keepalive.
        request('/api/models/selected', 'json', function (selection) {
            request('/api/models/art?id=' + encodeURIComponent(selection.modelId || ''), 'blob', function (blob) {
                if (!blob || !window.URL || !URL.createObjectURL) return;
                if (state.artUrl) URL.revokeObjectURL(state.artUrl);
                state.artUrl = URL.createObjectURL(blob);
                el('raVehiclePoster').src = state.artUrl; el('raVehiclePoster').hidden = false;
                el('raVehiclePlaceholder').hidden = true;
            });
        });
    }
    function disposeVehiclePreview() {
        state.modelGeneration++;
        if (state.modelTimer !== null) { clearTimeout(state.modelTimer); state.modelTimer = null; }
        if (state.modelHandle) {
            state.modelHandle.dispose();
            state.modelHandle = null;
        }
        state.modelMode = '';
        var canvas = el('raVehicleCanvas');
        // A disposed WebGL canvas cannot be reused as a sprite/2D canvas.
        // Swap the element while the static poster retains the last render.
        if (canvas && canvas.parentNode) {
            var fresh = canvas.cloneNode(false);
            fresh.hidden = true; fresh.style.opacity = '0';
            canvas.parentNode.replaceChild(fresh, canvas);
        }
        if (state.modelPosterValid || el('raVehiclePoster').src) el('raVehiclePoster').hidden = false;
    }
    function syncVehiclePreview(oneShot) {
        if (!window.OverdriveAppShell || !OverdriveAppShell.mountVehicleCanvas) return;
        if (!visible()) { abortVehicleArt(); disposeVehiclePreview(); return; }
        if (!state.enabled && state.modelPosterValid) {
            if (state.modelHandle) disposeVehiclePreview();
            return;
        }
        if (!state.enabled && state.modelHandle && state.modelMode === 'poster') return;
        var mode = state.enabled ? 'live' : oneShot ? 'poster' : 'cache';
        if (state.enabled) abortVehicleArt();
        if (state.modelHandle && state.modelMode === mode) return;
        if (state.modelHandle) disposeVehiclePreview();
        if (mode === 'cache' && state.cachePreviewTried) return;
        if (mode === 'cache') state.cachePreviewTried = true;
        if (mode === 'poster') state.offRenderTried = true;
        var generation = ++state.modelGeneration;
        var canvas = el('raVehicleCanvas');
        canvas.hidden = false; canvas.style.opacity = '0';
        state.modelMode = mode;
        var handle = OverdriveAppShell.mountVehicleCanvas(canvas, {
            view: 'side', cacheOnly: mode === 'cache',
            onReady: function (result) {
                if (generation !== state.modelGeneration || !visible()) return;
                var rendered = result && result.canvas || el('raVehicleCanvas');
                captureVehiclePoster(rendered);
                rendered.style.opacity = '1';
                if (state.enabled) el('raVehiclePoster').hidden = true;
                el('raVehiclePlaceholder').hidden = true;
                if (!state.enabled) disposeVehiclePreview();
            },
            onUnavailable: function () {
                if (generation !== state.modelGeneration) return;
                el('raVehicleCanvas').hidden = true;
                if (!el('raVehiclePoster').src) {
                    text('raVehiclePlaceholder', tr('vehicle_loading', 'Loading your selected vehicle…'));
                    el('raVehiclePlaceholder').hidden = false;
                }
                disposeVehiclePreview();
                if (mode === 'cache' && !state.offRenderTried && !state.enabled) {
                    syncVehiclePreview(true);
                } else loadStaticVehicleArt();
            }
        });
        if (generation !== state.modelGeneration) {
            if (handle) handle.dispose();
        } else {
            state.modelHandle = handle;
            if (mode === 'poster' && handle) {
                // A finite page illustration render, independent of the
                // disabled proximity collector. Release on first poster or
                // bounded failure; never leave idle WebGL/timers behind.
                state.modelTimer = setTimeout(function () {
                    state.modelTimer = null;
                    if (generation !== state.modelGeneration || state.modelPosterValid) return;
                    disposeVehiclePreview(); loadStaticVehicleArt();
                }, 30000);
            }
        }
    }

    function stopLive() {
        state.generation++;
        if (state.pollTimer !== null) { clearTimeout(state.pollTimer); state.pollTimer = null; }
        if (state.ageTimer !== null) { clearInterval(state.ageTimer); state.ageTimer = null; }
        if (state.liveRequest) { var request = state.liveRequest; state.liveRequest = null; request.abort(); }
        if (!state.enabled || !visible()) disposeVehiclePreview();
        if (!visible()) abortVehicleArt();
        if (state.enabled) setStreamLabel('stream_paused', 'Live view paused', false); else setStreamLabel('keyfob_monitoring_off', 'Monitoring off', false);
        renderPhoneMarkers();
        renderKeyFobMarker();
    }
    function startLive() {
        if (!isLive() || state.liveRequest || state.pollTimer !== null) return;
        syncVehiclePreview();
        if (state.ageTimer === null) state.ageTimer = setInterval(function () {
            if (isLive()) { renderSignal(); renderSafeZoneStatus(); }
        }, 1000);
        poll();
    }
    function schedulePoll(delay, generation) {
        if (!isLive() || generation !== state.generation) return;
        state.pollTimer = setTimeout(function () { state.pollTimer = null; poll(); }, delay);
    }
    function poll() {
        if (!isLive() || state.liveRequest) return;
        var generation = state.generation;
        // XMLHttpRequest provides abort on every supported WebView, including
        // older ones without AbortController. Auth mirrors the fetch helper.
        var request = new XMLHttpRequest();
        state.liveRequest = request;
        request.open('GET', API + '/status' + (state.sequence !== null && state.sequence !== undefined ?
            '?after=' + encodeURIComponent(state.sequence) : ''), true);
        request.timeout = 16000; request.withCredentials = true;
        request.setRequestHeader('Cache-Control', 'no-cache');
        var headers = window.BYDAuth ? BYDAuth.getAuthHeaders() : {};
        for (var name in headers) if (Object.prototype.hasOwnProperty.call(headers, name)) request.setRequestHeader(name, headers[name]);
        var completed = false;
        function finish(success) {
            if (completed) return; completed = true;
            if (state.liveRequest === request) state.liveRequest = null;
            if (generation !== state.generation || !isLive()) return;
            if (success) {
                state.failures = 0; setStreamLabel('stream_live', 'Live', true);
                schedulePoll(250, generation);
            } else {
                state.failures++;
                setStreamLabel('stream_reconnecting', 'Reconnecting', false);
                renderSignal();
                renderSafeZoneStatus();
                schedulePoll(Math.min(10000, 1500 * state.failures), generation);
            }
        }
        request.onload = function () {
            if (generation !== state.generation || !isLive()) { finish(false); return; }
            if (request.status === 401) { stopLive(); redirectAuth(); return; }
            if (request.status < 200 || request.status >= 300) { finish(false); return; }
            try {
                var data = JSON.parse(request.responseText);
                if (data.success === false) { finish(false); return; }
                // Clear only after parsing, before acceptStatus may start a
                // new subscription; schedule the next request once below.
                state.liveRequest = null;
                state.pollTimer = -1;
                acceptStatus(data, false);
                if (state.pollTimer === -1) state.pollTimer = null;
                finish(true);
            } catch (ignored) { if (state.pollTimer === -1) state.pollTimer = null; finish(false); }
        };
        request.onerror = function () { finish(false); };
        request.ontimeout = function () { finish(false); };
        request.onabort = function () { finish(false); };
        request.send();
    }
    function refresh() {
        error('');
        var mutation = state.mutation;
        return api('/status').then(function (data) {
            if (mutation === state.mutation) acceptStatus(data, !state.dirty);
        }).catch(function (err) {
            if (mutation !== state.mutation) return;
            error(err.message); setStreamLabel('stream_status_unavailable', 'Status unavailable', false);
        });
    }
    function refreshAll() {
        el('raRefreshBtn').disabled = true;
        return Promise.all([refresh(), refreshDevices().catch(function (err) { error(err.message); }), refreshSafeZones()])
            .then(function () { el('raRefreshBtn').disabled = state.saving; });
    }
    function save() {
        if (state.saving) return;
        var config;
        saveError('');
        try { config = draft(); } catch (err) {
            saveError(err.message);
            if (err.field === 'safe_zones') focusSafeZoneSelection();
            return;
        }
        error(''); setBusy(true); state.mutation++;
        api('/config', config).then(function (data) {
            acceptStatus(data, true); state.dirty = false; state.advancedDraft = null;
            text('raSaveState', tr('saved_on_vehicle', 'Saved on the vehicle')); toast(tr('rules_saved', 'Proximity rules saved'));
        }).catch(function (err) {
            state.dirty = true;
            saveError(err.message);
        }).then(function () { setBusy(false); });
    }
    function toggleBluetoothKeepAlive() {
        var previous = state.config && state.config.keepBluetoothOn === true;
        if (!state.ready || state.saving) {
            el('raKeepBluetoothOn').checked = !!previous; return;
        }
        var requested = el('raKeepBluetoothOn').checked;
        error(''); state.bluetoothSaving = true; setBusy(true); state.mutation++;
        // Never include this alias in generic Save: a stale page must not
        // reverse a shared preference changed elsewhere.
        api('/config', { keepBluetoothOn: requested }).then(function (data) {
            state.bluetoothSaving = false;
            acceptStatus(data, false);
            // A newer session/status may have superseded this POST response.
            el('raKeepBluetoothOn').checked = state.config.keepBluetoothOn === true;
            toast(state.config.keepBluetoothOn === true ?
                tr('bluetooth_saved_on', 'Shared parked Bluetooth enabled') :
                tr('bluetooth_saved_off', 'Shared parked Bluetooth disabled'));
        }).catch(function (err) {
            state.bluetoothSaving = false;
            el('raKeepBluetoothOn').checked = state.config ? state.config.keepBluetoothOn === true : !!previous;
            error(tr('bluetooth_save_failed', 'Could not save the shared Bluetooth setting') + ': ' + err.message);
        }).then(function () { setBusy(false); });
    }
    function toggleMaster() {
        if (state.saving) return;
        var requested = el('raMasterToggle').checked;
        if (requested) syncSafeZoneSelection();
        var device = selectedDevice();
        if (requested && !el('raKeyFobEnabled').checked && !state.phones.some(function (profile) { return profile.enabled; })) {
            el('raMasterToggle').checked = state.enabled;
            error(tr('error_needs_source', 'Enable keyfob detection or add and enable an approved phone before enabling proximity access.')); return;
        }
        error(''); setBusy(true); state.mutation++;
        if (!requested) {
            stopLive();
            disposeVehiclePreview();
            el('remoteAccessPage').classList.remove('ra-live');
            text('raMasterBadge', tr('state_stopping', 'Stopping…'));
        }
        var changes = { enabled: requested };
        if (requested) {
            var pending;
            try { pending = draft(); } catch (err) {
                error(err.message); el('raMasterToggle').checked = state.enabled; setBusy(false);
                if (err.field === 'safe_zones') focusSafeZoneSelection();
                return;
            }
            changes.deviceAddress = device.address; changes.deviceName = device.name || '';
            changes.keyFobEnabled = pending.keyFobEnabled;
            changes.keyFobLockEnabled = pending.keyFobLockEnabled;
            changes.restrictLockToOutsideSafeZones = pending.restrictLockToOutsideSafeZones;
            changes.restrictUnlockToOutsideSafeZones = pending.restrictUnlockToOutsideSafeZones;
            changes.proximitySafeZoneIds = pending.proximitySafeZoneIds;
            changes.phones = pending.phones;
            changes.nearThreshold = pending.nearThreshold; changes.awayThreshold = pending.awayThreshold;
        }
        api('/config', changes).then(function (data) {
            acceptStatus(data, false);
            var enabled = !!(data.config && data.config.enabled);
            if (enabled !== requested) {
                var failure = '';
                (data.events || []).some(function (event) {
                    if (event.type === 'error' || event.type === 'failed' || event.result === 'failed') {
                        failure = event.message ? serverText(event, 'message') : ''; return true;
                    }
                    return false;
                });
                error(failure || (requested ? tr('master_start_failed', 'Proximity access could not start and remains disabled.') :
                    tr('master_stop_failed', 'Proximity access is still enabled. The disable request was not applied.')));
            } else {
                toast(enabled ? tr('master_enabled_toast', 'Proximity access enabled') :
                    data.runtime && data.runtime.cleanupPending ? tr('master_cleanup_toast', 'Settings disabled · collector cleanup pending') :
                        tr('master_disabled_toast', 'Proximity access disabled'));
            }
        }).catch(function (err) {
            error(requested ? tr('master_enable_error', 'Could not enable proximity access: {error}', { error: err.message }) :
                tr('master_disable_error', 'Could not disable proximity access: {error}', { error: err.message }));
            el('raMasterToggle').checked = state.enabled;
            if (state.enabled && visible()) startLive();
        }).then(function () { setBusy(false); renderSignal(); });
    }
    function calibrate(side) {
        if (!supports('bluetooth') || !state.enabled || state.saving || !el('raAdvancedMode').checked || !(state.config && state.config.advancedMode)) return;
        var button = el(side === 'near' ? 'raCaptureNear' : 'raCaptureAway');
        var mutation = state.mutation;
        var address = state.viewAddress;
        button.disabled = true; error('');
        api('/calibrate', { side: side, deviceAddress: address }).then(function (data) {
            if (mutation !== state.mutation) return;
            if (!finite(data.value)) throw new Error(tr('capture_invalid', 'No valid fresh capture was returned.'));
            if (!profileFor(address)) return;
            if (!state.captures[address]) state.captures[address] = {};
            state.captures[address][side] = data;
            profileFor(address)[side === 'near' ? 'nearThreshold' : 'awayThreshold'] = Math.round(data.value);
            if (address === state.viewAddress) loadProfileThresholds();
            text('raCalibrationDetail', side === 'near' ?
                tr('capture_added_near', 'Nearby capture added to the draft. Save rules to apply.') :
                tr('capture_added_away', 'Away capture added to the draft. Save rules to apply.'));
            markDirty();
        }).catch(function (err) { error(err.message); }).then(renderSignal);
    }
    function renderCaptures() {
        var captures = state.captures[state.viewAddress] || {};
        ['near', 'away'].forEach(function (side) {
            var capture = captures[side];
            text(side === 'near' ? 'raNearCaptureValue' : 'raAwayCaptureValue',
                capture ? tr('capture_value', '{value} · {count} samples', { value: signalText(capture.value), count: capture.sampleCount }) :
                    tr('capture_none', 'No saved capture'));
        });
    }
    function pause() {
        if (!state.enabled && !state.modelPosterValid) { state.offRenderTried = false; state.cachePreviewTried = false; }
        state.paused = true; stopLive();
    }
    function resume() {
        state.paused = false;
        if (visible()) { refresh(); if (state.enabled) refreshSafeZones(); }
    }
    function relocalize() {
        renderBluetoothStatus();
        if (state.streamLabel) setStreamLabel(state.streamLabel[0], state.streamLabel[1], state.streamLabel[2]);
        if (!state.ready || !state.status) { drawChart(); return; }
        var data = state.status;
        text('raMasterBadge', state.enabled ? tr('state_enabled', 'Enabled') : data.runtime && data.runtime.cleanupPending ?
            tr('state_cleanup_pending', 'Cleanup pending') : tr('state_disabled', 'Disabled'));
        renderDevices(); updateAdvancedView(); updateUnlockTrigger(); renderCaptures();
        renderSignal(); drawChart(); renderEvents(data.events); renderLock(data.lastAction);
        renderSafeZoneStatus(); renderSafeZoneChoices();
    }
    function init() {
        state.paused = !!window.OverdriveNativePaused;
        fields.forEach(function (id) {
            if (id === 'raDeviceSelect' || id === 'raAdvancedMode' || id === 'raKeepBluetoothOn' || id === 'raKeyFobEnabled') return;
            el(id).addEventListener('change', markDirty);
            if (el(id).tagName === 'INPUT' && el(id).type === 'number') el(id).addEventListener('input', markDirty);
        });
        el('raDeviceSelect').addEventListener('change', function () { selectPhone(el('raDeviceSelect').value); });
        el('raAdvancedMode').addEventListener('change', toggleAdvanced);
        el('raKeyFobEnabled').addEventListener('change', toggleKeyFob);
        el('raFastUnlockDefaults').addEventListener('click', useFastUnlockDefaults);
        el('raKeepBluetoothOn').addEventListener('change', toggleBluetoothKeepAlive);
        el('raAddPhoneSelect').addEventListener('change', function () { el('raAddPhoneBtn').disabled = state.saving || state.phones.length >= 8 || !el('raAddPhoneSelect').value; });
        el('raAddPhoneBtn').addEventListener('click', addPhone);
        el('raSettingsForm').addEventListener('submit', function (event) { event.preventDefault(); save(); });
        el('raMasterToggle').addEventListener('change', toggleMaster);
        el('raRefreshBtn').addEventListener('click', refreshAll);
        el('raCaptureNear').addEventListener('click', function () { calibrate('near'); });
        el('raCaptureAway').addEventListener('click', function () { calibrate('away'); });
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) stopLive(); else if (!state.paused) {
                refresh(); if (state.enabled) refreshSafeZones();
            }
        });
        window.addEventListener('overdrive:pause', pause);
        window.addEventListener('overdrive:resume', resume);
        // A locale switch re-hydrates the static markup; repaint everything this script renders.
        if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') BYD.i18n.onChange(relocalize);
        window.addEventListener('focus', function () { if (visible() && state.ready && !state.saving) refresh(); });
        window.addEventListener('pagehide', pause);
        window.addEventListener('beforeunload', function () {
            abortVehicleArt();
            if (state.artUrl) URL.revokeObjectURL(state.artUrl);
        });
        window.addEventListener('pageshow', function (event) { if (event.persisted) resume(); });
        document.addEventListener('overdrive:vehicle-changed', function () {
            abortVehicleArt(); disposeVehiclePreview(); state.cachePreviewTried = false;
            state.modelPosterValid = false; state.offRenderTried = false;
            if (state.artUrl) { URL.revokeObjectURL(state.artUrl); state.artUrl = null; }
            el('raVehiclePoster').removeAttribute('src'); el('raVehiclePoster').hidden = true;
            el('raVehiclePlaceholder').hidden = false;
            syncVehiclePreview();
        });
        drawChart(); refreshAll();
    }
    return { init: init, pause: pause, resume: resume, updateBluetoothStatus: updateBluetoothStatus };
}());
