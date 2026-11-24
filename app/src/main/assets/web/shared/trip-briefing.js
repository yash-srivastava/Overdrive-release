/* OverDrive Trip Briefing tab (Assistant page). ES5 for the head-unit WebView floor. */
(function (window, document) {
    'use strict';

    var API = '/api/briefing';
    var STALE_MS = 30000;
    // A self-test this good earns the "Turn on" prompt.
    var GOOD_RATE = 0.8;
    var GOOD_MIN_OFFERS = 3;
    var NAME_MAX = 40;
    var APPS = ['overdrive', 'waze', 'gmaps', 'car'];
    // A slow /learn answers 202 and finishes later; its result shows up as a newer learnedMs.
    var LEARN_POLL_MS = 2000;
    var LEARN_WAIT_MS = 120000;
    // Until the daemon has built its first model (ready:false).
    var READY_POLL_MS = 3000;
    var READY_WAIT_MS = 120000;
    // Writes wait up to 3 s for the rebuild; a reply that lags gets one more look.
    var RECHECK_MS = 3500;
    // A setting with no answer by then shows what the car has again (the in-car bridge gives up after 18 s).
    var SAVE_STUCK_MS = 20000;

    // English fallbacks for the i18n shim only; web/i18n/en.json is the source of truth.
    var EN = {
        loading: 'Loading Trip Briefing…',
        preparing: 'Reading your trips…',
        not_ready_title: 'Your trips aren\'t ready yet',
        not_ready_desc: 'OverDrive is still reading your trip history. Try again in a minute.',
        error_title: 'Can\'t reach OverDrive',
        error_desc: 'The car didn\'t answer. Check that it\'s on and connected, then try again.',
        retry: 'Try again',
        no_trips_title: 'No trips yet',
        no_trips_desc: 'Trip Briefing learns from your recorded trips. Drive a few times, then come back.',
        trips_off_title: 'Trip recording is off',
        trips_off_desc: 'Trip Briefing learns from your recorded trips. Turn on trip recording to start.',
        open_trips: 'Open Trips',
        turn_on_trips: 'Turn on trip recording',
        voice_mode: 'Voice follows your assistant mode ({mode}).',
        voice_mode_any: 'Voice follows your assistant mode (Local, Hybrid or GenAI).',
        voice_off: 'The voice assistant is off, so only the card shows.',
        mode_local: 'Local',
        mode_hybrid: 'Hybrid',
        mode_genai: 'GenAI',
        app_overdrive: 'OverDrive',
        app_waze: 'Waze',
        app_gmaps: 'Google Maps',
        app_car: 'Car navigation',
        app_default: 'Default ({app})',
        learn: 'Learn from my trips',
        learn_again: 'Learn again',
        learning: 'Learning…',
        learned_at: 'Last checked {when}',
        learn_failed: 'Couldn\'t learn from your trips. Try again.',
        learn_unavailable: 'Your trip history isn\'t available yet. Try again in a minute.',
        result: 'Would have guessed right on {correct} of {offers} suggestions',
        result_none: 'It wouldn\'t have made a suggestion yet.',
        'first_offer.one': 'It would have started suggesting after {count} day',
        'first_offer.other': 'It would have started suggesting after {count} days',
        first_offer_soon: 'It would have started suggesting within a day',
        'trips_checked.one': '{count} trip checked',
        'trips_checked.other': '{count} trips checked',
        turned_on: 'Trip Briefing is on.',
        weekdays: 'Weekdays',
        weekends: 'Weekends',
        usually: 'usually {time}',
        'trips.one': '{count} trip',
        'trips.other': '{count} trips',
        score: 'right {correct} of {offers}',
        suggest: 'Suggest {route}',
        hidden: 'Hidden',
        paused: 'Paused until {date}',
        resume: 'Ask again',
        rename: 'Rename',
        rename_start: 'Rename start',
        rename_end: 'Rename destination',
        rename_title: 'Rename this place',
        rename_body: 'Trip Briefing uses this name when it asks. Leave it empty to go back to the usual name.',
        name_cleared: 'Name removed',
        open_in: 'Open in',
        home: 'Home',
        work: 'Work',
        unnamed: 'Unnamed place',
        name: 'Name',
        name_start: 'Name start',
        name_end: 'Name destination',
        name_title: 'Name this place',
        name_body: 'Use a short name, like Gym or School.',
        name_label: 'Place name',
        from_place: 'From {place}',
        to_place: 'To {place}',
        near: 'Near {lat}, {lng}',
        save: 'Save',
        saved_name: 'Saved as {name}.',
        name_needed: 'Type a name first.',
        forget: 'Forget',
        forget_confirm_title: 'Forget your patterns?',
        forget_confirm_body: 'Trip Briefing forgets place names, hidden routines, app choices and your answers. Your trips are kept, so it can learn again.',
        forgot: 'Patterns forgotten. Your trips are kept.',
        save_failed: 'Couldn\'t save. Try again.'
    };

    var ICONS = {
        arrow: '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
        trips: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
        offline: '<path d="M12 20h.01"/><path d="M8.5 16.43a5 5 0 0 1 7 0"/><path d="M2 8.82a15 15 0 0 1 4.18-2.65"/><path d="M22 8.82a15 15 0 0 0-11.29-3.76"/><path d="M5 12.86a10 10 0 0 1 5.17-2.7"/><path d="M19 12.86a10 10 0 0 0-2.01-1.52"/><path d="m2 2 20 20"/>'
    };

    function $(id) { return document.getElementById(id); }

    function interpolate(text, vars) {
        if (!vars) return String(text);
        return String(text).replace(/\{(\w+)\}/g, function (match, name) {
            return vars[name] != null ? vars[name] : match;
        });
    }

    function t(key, vars) {
        var full = 'trip_briefing.' + key;
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
                var v = BYD.i18n.t(full, vars);
                if (typeof v === 'string' && v && v !== full) return v;
            }
        } catch (e) {}
        return interpolate(EN[key] != null ? EN[key] : full, vars);
    }

    function lang() {
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.getLang === 'function') {
                return BYD.i18n.getLang() || undefined;
            }
        } catch (e) {}
        return undefined;
    }

    function num(value) {
        try { return Number(value).toLocaleString(lang()); } catch (e) { return String(value); }
    }

    // Forms live under key.one / key.other; the English rule covers locales without them yet.
    function plural(key, count, vars) {
        var full = 'trip_briefing.' + key;
        var merged = { count: num(count) };
        if (vars) for (var k in vars) if (vars.hasOwnProperty(k)) merged[k] = vars[k];
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.plural === 'function') {
                var v = BYD.i18n.plural(full, count, merged);
                if (typeof v === 'string' && v && v !== full) return v;
            }
        } catch (e) {}
        return t(key + (count === 1 ? '.one' : '.other'), merged);
    }

    function pad(n) { return n < 10 ? '0' + n : String(n); }

    // Same clock style as the Trips page: the locale decides 12 or 24 h.
    function clock(value) {
        var h, m;
        if (typeof value === 'number' && isFinite(value)) {
            h = Math.floor(value / 60) % 24;
            m = Math.round(value % 60) % 60;
        } else {
            var match = /^(\d{1,2}):(\d{2})/.exec(String(value || ''));
            if (!match) return '';
            h = Number(match[1]);
            m = Number(match[2]);
        }
        try {
            return new Date(2000, 0, 1, h, m).toLocaleTimeString(lang(), { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
            return pad(h) + ':' + pad(m);
        }
    }

    // A short date ("12 Nov") in the locale's order.
    function day(ms) {
        try {
            return new Date(ms).toLocaleDateString(lang(), { day: 'numeric', month: 'short' });
        } catch (e) {
            return new Date(ms).toDateString();
        }
    }

    function when(ms) {
        var date = new Date(ms);
        var options = { hour: '2-digit', minute: '2-digit' };
        if (date.toDateString() !== new Date().toDateString()) {
            options.month = 'short';
            options.day = 'numeric';
        }
        try { return date.toLocaleString(lang(), options); } catch (e) { return date.toLocaleString(); }
    }

    function percent(correct, offers) {
        var ratio = offers > 0 ? correct / offers : 0;
        try {
            return ratio.toLocaleString(lang(), { style: 'percent', maximumFractionDigits: 0 });
        } catch (e) {
            return Math.round(ratio * 100) + '%';
        }
    }

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    function icon(paths, className) {
        var holder = document.createElement('span');
        holder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths + '</svg>';
        var svg = holder.firstChild;
        if (className) svg.setAttribute('class', className);
        return svg;
    }

    function option(value, text) {
        var node = el('option', null, text);
        node.value = value;
        return node;
    }

    function show(id, on) {
        var node = $(id);
        if (node) node.hidden = !on;
    }

    function toast(message, type) {
        if (window.BYD && BYD.core && typeof BYD.core.toast === 'function') {
            BYD.core.toast(message, type || 'info');
        }
    }

    function isPayload(data) {
        return !!(data && typeof data === 'object' && data.settings && typeof data.settings === 'object');
    }

    function request(path, body) {
        var options = body === undefined ? { cache: 'no-store' } : {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        };
        return fetch(path, options).then(function (response) {
            return response.json().catch(function () { return null; }).then(function (data) {
                if (!response.ok || !data || data.ok === false || data.success === false) {
                    var error = new Error((data && data.error) || ('HTTP ' + response.status));
                    error.status = response.status;
                    throw error;
                }
                return data;
            });
        });
    }

    function delay(ms) {
        return new Promise(function (resolve) { window.setTimeout(resolve, ms); });
    }

    // The daemon takes 1-40 characters (code points) and no control characters.
    function cleanName(raw) {
        var s = String(raw || '').replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/g, ' ')
            .replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
        var out = '';
        for (var i = 0, n = 0; i < s.length && n < NAME_MAX; i++, n++) {
            var c = s.charCodeAt(i);
            out += s.charAt(i);
            if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) out += s.charAt(++i);
        }
        out = out.replace(/\s+$/, '');
        // only spaces and invisible format characters is no name
        return /[^\s\u00ad\u034f\u0600-\u0605\u061c\u06dd\u070f\u115f\u1160\u180e\u200b-\u200f\u202a-\u202e\u2060-\u206f\u3164\ufe00-\ufe0f\ufeff\ufff9-\ufffb\uffa0]/.test(out) ? out : '';
    }

    function placeName(place) {
        if (place && place.role === 'HOME') return t('home');
        if (place && place.role === 'WORK') return t('work');
        return place && place.label ? String(place.label) : t('unnamed');
    }

    function isUnnamed(place) {
        return !place || (!place.role && !place.label);
    }

    function dayLabel(dayType) {
        return dayType === 'WEEKEND' ? t('weekends') : t('weekdays');
    }

    function appId(app) {
        return APPS.indexOf(app) >= 0 ? app : '';
    }

    function appLabel(app) {
        return t('app_' + (appId(app) || 'overdrive'));
    }

    function point(lat, lng) {
        if (lat == null || lng == null) return null;
        lat = Number(lat);
        lng = Number(lng);
        return isFinite(lat) && isFinite(lng) ? { lat: lat, lng: lng } : null;
    }

    // Routine ids carry both ends: "<oLat>,<oLng>><dLat>,<dLng>:DAYTYPE".
    function routineEnd(routine, which) {
        var place = routine[which] || {};
        var direct = point(place.lat, place.lng);
        if (direct) return direct;
        var match = /^(-?[\d.]+),(-?[\d.]+)>(-?[\d.]+),(-?[\d.]+)(?::|$)/.exec(String(routine.id || ''));
        if (!match) return null;
        return which === 'from' ? point(match[1], match[2]) : point(match[3], match[4]);
    }

    function near(a, b) {
        if (!a || !b) return false;
        var dLat = (a.lat - b.lat) * 111320;
        var dLng = (a.lng - b.lng) * 111320 * Math.cos(a.lat * Math.PI / 180);
        return dLat * dLat + dLng * dLng < 150 * 150;
    }

    function placeKey(p) {
        return p ? p.lat.toFixed(5) + ',' + p.lng.toFixed(5) : '';
    }

    function routeText(routine) {
        return placeName(routine.from) + ' → ' + placeName(routine.to);
    }

    var TripBriefing = {
        data: null,
        failed: false,
        loadedAt: 0,
        loading: false,
        learning: false,
        busy: 0,
        writeSeq: 0,
        appliedSeq: 0,
        tripsEnabled: null,
        tripCount: null,
        voice: null,
        listData: null,
        noticeKey: '',
        resultKey: '',
        readySince: 0,
        notReady: false,
        readyTimer: null,
        recheckTimer: null,
        // Settings sent but not answered yet, shown over the last payload.
        optimistic: {},
        drafts: {},

        init: function () {
            if (!$('tbSection')) return;
            var self = this;
            this.bind();
            this.render();
            document.addEventListener('ot-tabs:active-changed', function (event) {
                if (event && event.detail && event.detail.id === 'briefing') self.onShown();
            });
            document.addEventListener('visibilitychange', function () {
                if (!document.hidden) self.onShown();
            });
            window.addEventListener('overdrive:resume', function () { self.onShown(); });
            if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
                BYD.i18n.onChange(function () {
                    self.noticeKey = '';
                    self.render(true);
                });
            }
            this.onShown();
            // onShown reads only while the Briefing tab is on screen; opening it later reads then.
            if (window.BYD && BYD.offline && typeof BYD.offline.onReconnect === 'function') {
                BYD.offline.onReconnect(function () { self.onShown(); });
            }
        },

        bind: function () {
            var self = this;
            this.bindToggle('tbEnabled', 'enabled');
            this.bindToggle('tbVoice', 'voice');
            this.bindToggle('tbExtras', 'extras');
            this.bindToggle('tbOnlineExtras', 'onlineExtras');
            var app = $('tbDefaultApp');
            if (app) app.addEventListener('change', function () {
                var settings = self.data && self.data.settings;
                var previous = appId(settings && settings.defaultApp) || 'overdrive';
                self.saveSettings({ defaultApp: app.value }, function () { app.value = previous; });
            });
            var learn = $('tbLearnBtn');
            if (learn) learn.addEventListener('click', function () { self.learn(); });
            var cta = $('tbCtaBtn');
            if (cta) cta.addEventListener('click', function () { self.turnOn(); });
            var forget = $('tbForgetBtn');
            if (forget) forget.addEventListener('click', function () { self.forget(); });
            var voice = $('tbVoiceSettingsBtn');
            if (voice) voice.addEventListener('click', function () { self.openVoiceSettings(); });
        },

        bindToggle: function (id, field) {
            var self = this;
            var input = $(id);
            if (!input) return;
            input.addEventListener('change', function () {
                var on = !!input.checked;
                var patch = {};
                patch[field] = on;
                self.saveSettings(patch, function () { input.checked = !on; });
            });
        },

        isVisible: function () {
            var section = $('tbSection');
            return !!section && !section.hasAttribute('hidden') && !document.hidden;
        },

        onShown: function () {
            if (!this.isVisible() || this.loading || this.learning || this.busy || this.readyTimer) return;
            if (this.data && this.data.ready !== false && Date.now() - this.loadedAt < STALE_MS) return;
            this.load();
        },

        fetchPayload: function () {
            return request(API).then(function (data) {
                if (!isPayload(data)) throw new Error((data && data.error) || 'Bad response');
                return data;
            });
        },

        load: function () {
            var self = this;
            if (this.loading) return;
            this.loading = true;
            this.failed = false;
            this.render();
            var seq = this.writeSeq;
            Promise.all([
                this.fetchPayload(),
                request('/api/trips/config').then(null, function () { return null; }),
                request('/api/voice/status').then(null, function () { return null; })
            ]).then(function (results) {
                var data = results[0];
                var trips = results[1] && results[1].config;
                self.tripsEnabled = trips ? trips.enabled !== false : null;
                if (results[2] && results[2].config) self.voice = results[2].config;
                if ((data.routines || []).length || typeof data.trips === 'number' || data.ready === false) return data;
                // No routines: the trip count tells "no trips yet" from "no routine yet".
                return request('/api/trips/storage').then(function (storage) {
                    var count = storage && storage.storage ? Number(storage.storage.tripsCount) : NaN;
                    if (isFinite(count)) self.tripCount = count;
                    return data;
                }, function () { return data; });
            }).then(function (data) {
                // A write that landed meanwhile already applied newer state.
                if (seq === self.writeSeq || !self.data) self.apply(data);
                else self.loadedAt = Date.now();
            }, function () {
                self.failed = true;
            }).then(function () {
                self.loading = false;
                self.waitReady();
                self.render();
            });
        },

        // The first model is still being built: look again shortly, then say so.
        waitReady: function () {
            var self = this;
            if (!this.data || this.data.ready !== false) {
                this.readySince = 0;
                this.notReady = false;
                return;
            }
            if (!this.readySince) this.readySince = Date.now();
            if (Date.now() - this.readySince >= READY_WAIT_MS) {
                this.notReady = true;
                return;
            }
            if (this.readyTimer) return;
            this.readyTimer = window.setTimeout(function () {
                self.readyTimer = null;
                if (self.isVisible()) self.load();
            }, READY_POLL_MS);
        },

        retryReady: function () {
            this.readySince = 0;
            this.notReady = false;
            this.load();
        },

        recheck: function () {
            var self = this;
            if (this.recheckTimer) return;
            this.recheckTimer = window.setTimeout(function () {
                self.recheckTimer = null;
                if (!self.loading && !self.learning) self.load();
            }, RECHECK_MS);
        },

        apply: function (data, fromLearn) {
            this.data = data;
            this.loadedAt = Date.now();
            this.failed = false;
            if (typeof data.trips === 'number') {
                this.tripCount = data.trips;
            } else if (fromLearn && data.selfTest && isFinite(Number(data.selfTest.trips))) {
                this.tripCount = Number(data.selfTest.trips);
            }
        },

        write: function (path, body, check) {
            var self = this;
            var seq = ++this.writeSeq;
            this.busy++;
            return request(API + path, body).then(function (data) {
                if (isPayload(data)) return data;
                if (data && data.error) throw new Error(data.error);
                return self.fetchPayload();
            }).then(function (data) {
                if (seq >= self.appliedSeq) {
                    self.appliedSeq = seq;
                    self.apply(data);
                }
                self.busy--;
                self.render();
                if (check && !check(data)) self.recheck();
                return data;
            }, function (error) {
                self.busy--;
                self.render();
                // No answer, or the daemon failed midway: it may have saved anyway, so read it again.
                if (!(error && error.status >= 400 && error.status < 500)) self.recheck();
                throw error;
            });
        },

        saveSettings: function (patch, revert) {
            var self = this;
            var k;
            for (k in patch) if (patch.hasOwnProperty(k)) this.optimistic[k] = patch[k];
            function settle() {
                for (var key in patch) {
                    if (patch.hasOwnProperty(key) && self.optimistic[key] === patch[key]) delete self.optimistic[key];
                }
            }
            var answered = false;
            var gaveUp = false;
            var stuck = window.setTimeout(function () {
                if (answered) return;
                gaveUp = true;
                settle();
                self.render();
                toast(t('save_failed'), 'error');
                self.recheck();
            }, SAVE_STUCK_MS);
            this.render();
            return this.write('/settings', patch).then(function () {
                answered = true;
                window.clearTimeout(stuck);
                settle();
                self.render();
                return true;
            }, function () {
                answered = true;
                window.clearTimeout(stuck);
                settle();
                if (!gaveUp) {
                    if (revert) revert();
                    toast(t('save_failed'), 'error');
                }
                self.render();
                return false;
            });
        },

        settings: function () {
            var merged = {};
            var saved = (this.data && this.data.settings) || {};
            var k;
            for (k in saved) if (saved.hasOwnProperty(k)) merged[k] = saved[k];
            for (k in this.optimistic) if (this.optimistic.hasOwnProperty(k)) merged[k] = this.optimistic[k];
            return merged;
        },

        setRoutine: function (routine, patch, revert) {
            var body = { id: routine.id };
            for (var k in patch) if (patch.hasOwnProperty(k)) body[k] = patch[k];
            return this.write('/routine', body, function (data) {
                var list = data.routines || [];
                for (var i = 0; i < list.length; i++) {
                    if (list[i].id !== routine.id) continue;
                    if (patch.hasOwnProperty('hidden') && !!list[i].hidden !== !!patch.hidden) return false;
                    if (patch.hasOwnProperty('app') && appId(list[i].app) !== appId(patch.app)) return false;
                }
                return true;
            }).then(null, function () {
                if (revert) revert();
                toast(t('save_failed'), 'error');
            });
        },

        turnOn: function () {
            this.saveSettings({ enabled: true }).then(function (ok) {
                if (ok) toast(t('turned_on'), 'success');
            });
        },

        learn: function () {
            if (this.learning || !this.data) return;
            var self = this;
            var before = Number(this.data.learnedMs) || 0;
            var seq = ++this.writeSeq;
            this.learning = true;
            this.render();
            var deadline = Date.now() + LEARN_WAIT_MS;
            var reload = false;
            request(API + '/learn', {}).then(function (data) {
                // 202 {learning:true}: still running, so wait for a newer learnedMs.
                return isPayload(data) ? data : self.awaitLearned(before, deadline);
            }, function (error) {
                if (error && error.status >= 400) throw error;
                // No answer (e.g. the in-car write bridge gave up): the GET shows whether it finished.
                return self.awaitLearned(before, deadline);
            }).then(function (data) {
                if (seq >= self.appliedSeq) {
                    self.appliedSeq = seq;
                    self.apply(data, true);
                } else {
                    // A settings write landed meanwhile; read both results together.
                    reload = true;
                }
            }, function (error) {
                toast(error && error.status === 503 ? t('learn_unavailable') : t('learn_failed'), 'error');
            }).then(function () {
                self.learning = false;
                self.render();
                if (reload) self.load();
            });
        },

        awaitLearned: function (before, deadline) {
            var self = this;
            return delay(LEARN_POLL_MS).then(function () {
                return self.fetchPayload().then(null, function (error) {
                    if (Date.now() >= deadline) throw error;
                    return null;
                });
            }).then(function (data) {
                if (data && (Number(data.learnedMs) || 0) > before) return data;
                if (Date.now() >= deadline) throw new Error('Not learned');
                return self.awaitLearned(before, deadline);
            });
        },

        forget: function () {
            var self = this;
            var ask = window.BYD && BYD.utils && typeof BYD.utils.confirmDialog === 'function'
                ? BYD.utils.confirmDialog({
                    title: t('forget_confirm_title'),
                    body: t('forget_confirm_body'),
                    confirmLabel: t('forget'),
                    danger: true
                })
                : Promise.resolve(window.confirm(t('forget_confirm_body')));
            ask.then(function (ok) {
                if (!ok) return;
                self.write('/forget', {}).then(function () {
                    self.drafts = {};
                    toast(t('forgot'), 'success');
                }, function () {
                    toast(t('save_failed'), 'error');
                });
            });
        },

        namePlace: function (at, name) {
            var self = this;
            name = cleanName(name);
            if (!name) {
                toast(t('name_needed'), 'warning');
                return;
            }
            this.write('/place', { lat: at.lat, lng: at.lng, name: name }, function (data) {
                var left = data.unnamed || [];
                for (var i = 0; i < left.length; i++) {
                    if (near(point(left[i].lat, left[i].lng), at)) return false;
                }
                return true;
            }).then(function () {
                delete self.drafts[placeKey(at)];
                toast(t('saved_name', { name: name }), 'success');
            }, function () {
                toast(t('save_failed'), 'error');
            });
        },

        promptName: function (at, current) {
            var self = this;
            if (!(window.BYD && BYD.utils && typeof BYD.utils.promptDialog === 'function')) return;
            var renaming = current != null;
            BYD.utils.promptDialog({
                title: renaming ? t('rename_title') : t('name_title'),
                body: renaming ? t('rename_body') : t('name_body'),
                label: t('name_label'),
                placeholder: t('name_label'),
                value: renaming ? String(current) : '',
                confirmLabel: t('save')
            }).then(function (value) {
                if (value == null) return;
                // Save on the pre-filled name changes nothing (it may only be the translated Home or Work)
                if (renaming && cleanName(value) === cleanName(current)) return;
                if (renaming && !cleanName(value)) self.clearName(at);
                else self.namePlace(at, value);
            });
        },

        clearName: function (at) {
            this.write('/place', { lat: at.lat, lng: at.lng, remove: true }, function () { return true; })
                .then(function () { toast(t('name_cleared'), 'success'); }, function () { toast(t('save_failed'), 'error'); });
        },

        openVoiceSettings: function () {
            if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab('provider');
            var card = $('voiceAssistantCard');
            if (card && card.scrollIntoView) {
                try { card.scrollIntoView(true); } catch (e) {}
            }
        },

        view: function () {
            if (!this.data) return this.failed ? 'error' : 'loading';
            if ((this.data.routines || []).length) return 'ready';
            if (this.data.ready === false) return this.notReady ? 'not_ready' : 'preparing';
            if (this.tripCount === 0) return 'empty';
            if (this.tripCount == null && this.tripsEnabled === false) return 'empty';
            return 'ready';
        },

        render: function (force) {
            var section = $('tbSection');
            if (!section) return;
            var view = this.view();
            var data = this.data || {};
            var settings = this.settings();
            section.setAttribute('data-state', view);
            this.renderNotice(view);
            // Settings work before the first model is built, so they show in every loaded state.
            var loaded = view !== 'loading' && view !== 'error';
            show('tbMain', loaded);
            show('tbLearnCard', view === 'ready');
            show('tbRoutinesCard', view === 'ready');
            show('tbPlacesCard', view === 'ready' && (data.unnamed || []).length > 0);
            show('tbForgetCard', view === 'ready');
            this.renderSettings(settings, loaded);
            if (view !== 'ready') return;
            this.renderLearn(data, settings);
            if (force || this.listData !== this.data) {
                this.listData = this.data;
                this.renderLists(data, settings);
            }
            var controls = document.querySelectorAll('#tbRoutines input, #tbRoutines select, #tbRoutines button, #tbPlaces button');
            for (var i = 0; i < controls.length; i++) controls[i].disabled = this.learning;
        },

        renderNotice: function (view) {
            var card = $('tbNotice');
            if (!card) return;
            var kind = view === 'empty' ? (this.tripsEnabled === false ? 'off' : 'none') : view;
            if (view === 'ready') {
                card.hidden = true;
                this.noticeKey = '';
                return;
            }
            card.hidden = false;
            if (this.noticeKey === kind) return;
            this.noticeKey = kind;
            while (card.firstChild) card.removeChild(card.firstChild);
            if (kind === 'loading' || kind === 'preparing') {
                var loading = el('div', 'loading-state tb-loading');
                loading.setAttribute('role', 'status');
                var spin = el('span', 'r-btn-spinner');
                spin.setAttribute('aria-hidden', 'true');
                loading.appendChild(spin);
                loading.appendChild(el('span', null, t(kind)));
                card.appendChild(loading);
                return;
            }
            var self = this;
            var copy = {
                error: ['error_title', 'error_desc', 'retry', null, ICONS.offline],
                not_ready: ['not_ready_title', 'not_ready_desc', 'retry', null, ICONS.trips],
                none: ['no_trips_title', 'no_trips_desc', 'open_trips', '/trips', ICONS.trips],
                off: ['trips_off_title', 'trips_off_desc', 'turn_on_trips', '/trips#storage', ICONS.trips]
            }[kind];
            var box = el('div', 'empty-state tb-notice' + (kind === 'error' ? ' is-error' : ''));
            if (kind === 'error') box.setAttribute('role', 'alert');
            box.appendChild(icon(copy[4], 'empty-icon'));
            box.appendChild(el('div', 'empty-title', t(copy[0])));
            box.appendChild(el('p', 'empty-text', t(copy[1])));
            var actions = el('div', 'tb-notice-actions');
            var action;
            if (copy[3]) {
                action = el('a', 'btn btn-primary', t(copy[2]));
                action.href = copy[3];
            } else {
                action = el('button', 'btn btn-primary', t(copy[2]));
                action.type = 'button';
                action.addEventListener('click', function () {
                    if (kind === 'not_ready') self.retryReady();
                    else self.load();
                });
            }
            actions.appendChild(action);
            box.appendChild(actions);
            card.appendChild(box);
        },

        renderSettings: function (settings, ready) {
            var locked = !ready;
            var hero = $('tbHero');
            if (hero) hero.classList.toggle('is-off', !(ready && settings.enabled));
            this.setToggle('tbEnabled', !!settings.enabled, locked);
            this.setToggle('tbVoice', settings.voice !== false, locked);
            this.setToggle('tbExtras', settings.extras !== false, locked);
            this.setToggle('tbOnlineExtras', settings.onlineExtras === true, locked);
            var app = $('tbDefaultApp');
            if (app) {
                app.value = appId(settings.defaultApp) || 'overdrive';
                app.disabled = locked;
            }
            var note = $('tbVoiceMode');
            var voice = this.voice;
            var assistantOff = !!(voice && voice.enabled === false);
            if (note) {
                var mode = voice && EN['mode_' + voice.mode] ? t('mode_' + voice.mode) : '';
                note.textContent = assistantOff ? t('voice_off')
                    : mode ? t('voice_mode', { mode: mode }) : t('voice_mode_any');
                note.hidden = settings.voice === false;
            }
            show('tbVoiceSettings', assistantOff && settings.voice !== false);
        },

        setToggle: function (id, on, disabled) {
            var input = $(id);
            if (!input) return;
            input.checked = on;
            input.disabled = disabled;
        },

        renderLearn: function (data, settings) {
            var stats = data.selfTest || {};
            var learned = Number(data.learnedMs) > 0;
            var offers = Number(stats.offers);
            var correct = Number(stats.correct);
            var button = $('tbLearnBtn');
            if (button) {
                while (button.firstChild) button.removeChild(button.firstChild);
                if (this.learning) {
                    var spinner = el('span', 'r-btn-spinner');
                    spinner.setAttribute('aria-hidden', 'true');
                    button.appendChild(spinner);
                }
                button.appendChild(document.createTextNode(this.learning ? t('learning')
                    : learned ? t('learn_again') : t('learn')));
                button.disabled = this.learning;
            }
            var stamp = $('tbLearnedAt');
            if (stamp) stamp.textContent = learned && !this.learning ? t('learned_at', { when: when(data.learnedMs) }) : '';

            var host = $('tbResult');
            if (host) {
                host.setAttribute('aria-busy', this.learning ? 'true' : 'false');
                var key = learned && isFinite(offers)
                    ? [data.learnedMs, offers, correct, stats.trips, stats.firstOfferDay, lang()].join('|') : '';
                if (key !== this.resultKey) {
                    this.resultKey = key;
                    while (host.firstChild) host.removeChild(host.firstChild);
                    if (key) host.appendChild(this.resultCard(stats, offers, correct));
                }
            }
            var good = learned && offers >= GOOD_MIN_OFFERS && correct / offers >= GOOD_RATE;
            show('tbCta', good && !settings.enabled && (data.routines || []).length > 0);
            var cta = $('tbCtaBtn');
            if (cta) cta.disabled = this.learning;
            show('tbTripsOff', this.tripsEnabled === false);
        },

        resultCard: function (stats, offers, correct) {
            var card = el('div', 'ai-result-card tb-result');
            if (offers > 0) card.appendChild(el('div', 'tb-result-figure', percent(correct, offers)));
            var text = el('div', 'tb-result-text');
            text.appendChild(el('strong', null, offers > 0
                ? t('result', { correct: num(correct), offers: num(offers) }) : t('result_none')));
            var day = stats.firstOfferDay == null ? NaN : Number(stats.firstOfferDay);
            if (offers > 0 && isFinite(day)) {
                text.appendChild(el('div', 'ai-result-detail',
                    day < 1 ? t('first_offer_soon') : plural('first_offer', Math.round(day))));
            }
            var trips = Number(stats.trips);
            if (isFinite(trips) && stats.trips != null) {
                text.appendChild(el('div', 'ai-result-meta', plural('trips_checked', trips)));
            }
            card.appendChild(text);
            return card;
        },

        renderLists: function (data, settings) {
            var active = document.activeElement;
            var focusKey = active && active.getAttribute ? active.getAttribute('data-place-key') : null;
            var routines = data.routines || [];
            var scores = {};
            var tested = (data.selfTest && data.selfTest.routines) || [];
            for (var s = 0; s < tested.length; s++) {
                var testedId = tested[s] && (tested[s].id || tested[s].routineId);
                if (testedId) scores[testedId] = tested[s];
            }
            var list = $('tbRoutines');
            if (list) {
                while (list.firstChild) list.removeChild(list.firstChild);
                for (var i = 0; i < routines.length; i++) {
                    list.appendChild(this.routineItem(routines[i], settings, scores));
                }
                list.hidden = !routines.length;
            }
            show('tbRoutinesEmpty', !routines.length);

            var places = $('tbPlaces');
            if (places) {
                while (places.firstChild) places.removeChild(places.firstChild);
                var unnamed = data.unnamed || [];
                for (var p = 0; p < unnamed.length; p++) {
                    var item = this.placeItem(unnamed[p], routines);
                    if (item) places.appendChild(item);
                }
            }
            if (focusKey) {
                var again = document.querySelector('#tbPlaces input[data-place-key="' + focusKey + '"]');
                if (again) {
                    try { again.focus(); } catch (e) {}
                }
            }
        },

        routineItem: function (routine, settings, scores) {
            var self = this;
            var item = el('div', 'ai-result-card tb-item' + (routine.hidden ? ' is-hidden' : ''));
            var head = el('div', 'tb-item-head');
            var route = el('div', 'tb-route');
            route.appendChild(el('span', isUnnamed(routine.from) ? 'is-unnamed' : null, placeName(routine.from)));
            route.appendChild(icon(ICONS.arrow, 'tb-route-arrow'));
            route.appendChild(el('span', isUnnamed(routine.to) ? 'is-unnamed' : null, placeName(routine.to)));
            if (routine.hidden) route.appendChild(el('span', 'setting-badge', t('hidden')));
            var pausedUntil = Number(routine.pausedUntil);
            var paused = routine.pausedUntil != null && isFinite(pausedUntil) && pausedUntil > Date.now();
            if (paused && !routine.hidden) route.appendChild(el('span', 'setting-badge', t('paused', { date: day(pausedUntil) })));
            head.appendChild(route);

            var toggle = el('label', 'toggle-switch');
            var input = el('input');
            input.type = 'checkbox';
            input.checked = !routine.hidden;
            input.setAttribute('aria-label', t('suggest', { route: routeText(routine) }));
            input.addEventListener('change', function () {
                var on = input.checked;
                self.setRoutine(routine, { hidden: !on }, function () { input.checked = !on; });
            });
            toggle.appendChild(input);
            toggle.appendChild(el('span', 'toggle-slider'));
            head.appendChild(toggle);
            item.appendChild(head);

            var detail = [dayLabel(routine.dayType)];
            var time = clock(routine.time);
            if (time) detail.push(t('usually', { time: time }));
            item.appendChild(el('div', 'ai-result-detail', detail.join(' · ')));

            var meta = [];
            var trips = Number(routine.trips);
            if (routine.trips != null && isFinite(trips)) meta.push(plural('trips', trips));
            var score = routine.score || scores[routine.id];
            if (score && Number(score.offers) > 0) {
                meta.push(t('score', { correct: num(Number(score.correct) || 0), offers: num(Number(score.offers)) }));
            }
            if (meta.length) item.appendChild(el('div', 'ai-result-meta', meta.join(' · ')));

            var actions = el('div', 'ai-result-actions tb-item-actions');
            var pick = el('label', 'tb-app');
            pick.appendChild(el('span', 'tb-app-label', t('open_in')));
            var select = el('select', 'text-input');
            select.appendChild(option('', t('app_default', { app: appLabel(settings.defaultApp) })));
            for (var a = 0; a < APPS.length; a++) select.appendChild(option(APPS[a], appLabel(APPS[a])));
            select.value = appId(routine.app);
            select.addEventListener('change', function () {
                var previous = appId(routine.app);
                self.setRoutine(routine, { app: select.value || null }, function () { select.value = previous; });
            });
            pick.appendChild(select);
            actions.appendChild(pick);

            if (paused && !routine.hidden) {
                var resume = el('button', 'btn btn-secondary', t('resume'));
                resume.type = 'button';
                resume.addEventListener('click', function () { self.setRoutine(routine, { paused: false }); });
                actions.appendChild(resume);
            }

            // Unnamed ends get "Name"; named ones "Rename" (a wrong Home/Work is fixed here, not with Forget).
            var ends = ['from', 'to'];
            for (var e = 0; e < ends.length; e++) {
                var end = routine[ends[e]];
                var at = routineEnd(routine, ends[e]);
                if (!at) continue;
                var unnamedEnd = isUnnamed(end);
                var label = unnamedEnd
                    ? (ends[e] === 'from' ? t('name_start') : t('name_end'))
                    : (ends[e] === 'from' ? t('rename_start') : t('rename_end'));
                var name = el('button', 'btn btn-secondary', label);
                name.type = 'button';
                name.addEventListener('click', (function (target, current) {
                    return function () { self.promptName(target, current); };
                }(at, unnamedEnd ? null : placeName(end))));
                actions.appendChild(name);
            }
            item.appendChild(actions);
            return item;
        },

        placeItem: function (place, routines) {
            var self = this;
            var at = point(place && place.lat, place && place.lng);
            if (!at) return null;
            var key = placeKey(at);
            var item = el('div', 'ai-result-card tb-item');
            item.appendChild(el('strong', null, t('unnamed')));
            item.appendChild(el('div', 'ai-result-detail', this.placeContext(at, routines)));
            var trips = Number(place.trips);
            if (place.trips != null && isFinite(trips)) item.appendChild(el('div', 'ai-result-meta', plural('trips', trips)));

            var form = el('form', 'ai-result-actions tb-name-form');
            form.setAttribute('novalidate', '');
            var input = el('input', 'text-input');
            input.type = 'text';
            input.maxLength = NAME_MAX;
            input.autocomplete = 'off';
            input.placeholder = t('name_label');
            input.setAttribute('aria-label', t('name_label'));
            input.setAttribute('data-place-key', key);
            input.value = this.drafts[key] || '';
            input.addEventListener('input', function () { self.drafts[key] = input.value; });
            form.appendChild(input);
            var save = el('button', 'btn btn-secondary', t('save'));
            save.type = 'submit';
            form.appendChild(save);
            form.addEventListener('submit', function (event) {
                event.preventDefault();
                if (self.learning) return;
                self.namePlace(at, input.value);
            });
            item.appendChild(form);
            return item;
        },

        // Which routine touches this place, so the driver can tell places apart.
        placeContext: function (at, routines) {
            var best = null;
            var arriving = false;
            for (var i = 0; i < routines.length; i++) {
                var routine = routines[i];
                var trips = Number(routine.trips) || 0;
                if (best && trips <= (Number(best.trips) || 0)) continue;
                if (near(routineEnd(routine, 'to'), at)) {
                    best = routine;
                    arriving = true;
                } else if (near(routineEnd(routine, 'from'), at)) {
                    best = routine;
                    arriving = false;
                }
            }
            if (!best) return t('near', { lat: at.lat.toFixed(4), lng: at.lng.toFixed(4) });
            var bits = [arriving ? t('from_place', { place: placeName(best.from) })
                : t('to_place', { place: placeName(best.to) }), dayLabel(best.dayType)];
            var time = clock(best.time);
            if (time) bits.push(t('usually', { time: time }));
            return bits.join(' · ');
        }
    };

    window.TripBriefing = TripBriefing;
}(window, document));
