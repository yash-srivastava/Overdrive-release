/* OverDrive voice assistant settings card (Assistant page). ES5 for the head-unit WebView floor. */
(function (window, document) {
    'use strict';

    var POLL_MS = 5000;
    var MIN_REFRESH_GAP_MS = 2000;
    var FOLLOW_UP_MAX_S = 15;
    var MODES = ['local', 'hybrid', 'genai'];

    // English fallbacks for the i18n shim only; web/i18n/en.json is the source of truth.
    var EN = {
        state_off: 'Off',
        state_waiting: 'Waiting for the car',
        state_listening: 'Listening for “OverDrive”',
        state_push_to_talk: 'Push-to-talk only (wake model not installed)',
        state_unavailable: 'Unavailable',
        status_load_failed: 'Could not load the voice assistant status.',
        installed: 'Installed',
        not_installed: 'Not installed',
        never: 'Never',
        handled_fmt: '{local} on the car · {cloud} by AI · {failures} failed',
        follow_up_off: 'Off',
        needs_genai: 'Hybrid and GenAI need a realtime voice model from OpenAI or Gemini.',
        reason_genai_off: 'GenAI is turned off.',
        reason_genai_incomplete: 'Finish the provider setup with a text model and API key.',
        reason_provider_unsupported: 'The selected provider has no realtime voice. Choose OpenAI or Google Gemini.',
        reason_no_realtime_model: 'Choose a realtime voice model in the provider settings.',
        reason_unavailable: 'The GenAI settings could not be read.',
        mode_unusable: 'The selected mode can\'t reach your AI provider. Switch to Local or finish the GenAI setup.',
        save_failed: 'Could not save the voice assistant settings.',
        command_language: 'Command language',
        command_language_auto: 'Automatic (follow the app language)',
        command_language_en: 'English',
        command_language_zh_cn: '中文（简体）',
        command_language_zh_tw: '中文（繁體）',
        command_language_hint: 'Which spoken commands OverDrive listens for. Only commands in this language are recognised locally.',
        direct_fallback_title: 'Allow a direct connection when the proxy is unreachable',
        direct_fallback_desc: 'Hands-free assistant only. If the car\'s privacy proxy accepts the connection but relays nothing for 5 seconds, the voice session is retried once over the car\'s own internet connection. The AI provider then sees the car\'s IP address. Off by default.',
        pause_media_title: 'Pause media while listening',
        pause_media_desc: 'When music or a podcast is playing, saying “OverDrive” pauses it for the command and resumes it afterwards. Turn off to only lower its volume instead.',
        resume_media_title: 'Resume media afterwards',
        resume_media_desc: 'Plays the music or podcast again when OverDrive is done, but only if OverDrive paused it. Turn off to leave it paused.',
        share_location_title: 'Share the car\'s approximate location',
        share_location_desc: 'Hybrid and GenAI only. While you talk to the assistant, the car\'s approximate location (a nearby place name, or a position rounded to about 1 km) is sent to your AI provider so it can answer questions like “where am I”.',
        mic_source: 'Microphone input (experimental)',
        mic_source_mic: 'Standard microphone',
        mic_source_voice_recognition: 'Voice recognition',
        mic_source_voice_communication: 'Voice communication (echo cancelling)',
        mic_source_hint: 'Voice communication may let the car cancel its own speakers so the wake word is heard over music. Try it if OverDrive misses you while media plays.',
        enroll_untrained: 'Not trained yet.',
        enroll_trained: 'Trained to your voice {when}.',
        enroll_requested: 'Starting… watch the car screen.',
        enroll_recording: 'Say “OverDrive” now ({take} of {takes}).',
        enroll_processing: 'Learning your voice…',
        enroll_saved: 'Learned your voice.',
        enroll_failed: 'Could not learn your voice. Try again in a quiet car.',
        enroll_failed_no_speech: 'Did not hear you. Try again and speak after each chime.',
        enroll_not_running: 'Turn the car on with the assistant listening, then try again.',
        enroll_start_failed: 'Could not start the training.',
        enroll_clear_failed: 'Could not forget the learned voice.',
        enroll_music_saved: 'Tuned for the wake word over music.',
        enroll_music_failed: 'Could not tune for music. Keep the music at your usual volume and say “OverDrive” clearly 4 times.',
        enroll_people_desc: 'Each person can train their own voice: type their name, then Train. Up to 4 voices.',
        enroll_name: 'Who is training? (optional)',
        enroll_voice_main: 'Main voice',
        enroll_voice_trained: '{name}: trained {when}',
        enroll_voice_music: '{name}: trained {when}, tuned for music',
        enroll_voice_forget: 'Forget',
        enroll_voices_count: '{count} of {max} voices trained.',
        enroll_full: 'Four voices are trained. Forget one first.',
        enroll_forget_failed: 'Could not forget that voice.',
        enroll_moving: 'Park the car to train: road noise drowns your voice while driving.',
        enroll_clear_confirm: 'Forget every trained voice? OverDrive goes back to the standard pronunciation until you train again.',
        enroll_forget_confirm: 'Forget the trained voice of {name}?',
        enroll_cancel: 'Cancel'
    };

    // Polling while a training runs, and how long a request may wait for the car to pick it up.
    var ENROLL_POLL_MS = 1500;
    var ENROLL_PICKUP_MS = 30000;

    var RUNTIME_STATES = {
        off: 1, waiting: 1, listening: 1, push_to_talk: 1, unavailable: 1
    };

    // Field the "Set up GenAI" button focuses for each readiness reason.
    var SETUP_TARGETS = {
        genai_off: 'genAiEnabled',
        genai_incomplete: 'genAiApiKey',
        provider_unsupported: 'genAiProvider',
        no_realtime_model: 'genAiRealtimeModelPreset'
    };

    function $(id) { return document.getElementById(id); }

    function interpolate(text, vars) {
        if (!vars) return text;
        return String(text).replace(/\{(\w+)\}/g, function (match, name) {
            return vars[name] != null ? vars[name] : match;
        });
    }

    function t(key, vars) {
        var full = 'voice_assistant.' + key;
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
                var v = BYD.i18n.t(full, vars);
                if (v && v !== full) return v;
            }
        } catch (e) {}
        return interpolate(EN[key] != null ? EN[key] : full, vars);
    }

    function secondSuffix() {
        try {
            if (window.BYD && BYD.units && typeof BYD.units.secondSuffix === 'function') {
                return BYD.units.secondSuffix();
            }
        } catch (e) {}
        return ' s';
    }

    function lang() {
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.getLang === 'function') {
                return BYD.i18n.getLang() || undefined;
            }
        } catch (e) {}
        return undefined;
    }

    function formatWakeTime(ms) {
        var date = new Date(ms);
        var options = { hour: '2-digit', minute: '2-digit', hour12: false };
        try {
            if (date.toDateString() === new Date().toDateString()) {
                return date.toLocaleTimeString(lang(), options);
            }
            options.month = 'short';
            options.day = 'numeric';
            return date.toLocaleString(lang(), options);
        } catch (e) {
            return date.toLocaleString();
        }
    }

    function request(url, options) {
        return fetch(url, options || { cache: 'no-store' }).then(function (response) {
            return response.json().catch(function () {
                return { success: false };
            }).then(function (data) {
                if (!response.ok || !data || data.success === false) {
                    var error = new Error((data && data.error) || ('HTTP ' + response.status));
                    error.code = (data && data.code) || '';
                    throw error;
                }
                return data;
            });
        });
    }

    var VoiceAssistantCard = {
        config: null,
        status: null,
        loadFailed: false,
        busy: false,
        saveSeq: 0,
        inFlight: false,
        refreshAgain: false,
        lastRefreshAt: 0,
        pollTimer: null,
        intersecting: true,
        genAiRenderTimer: null,
        enrollId: null,
        enrollSince: 0,
        enrollBusy: false,
        enrollDoneText: null,
        enrollMusic: false,

        init: function () {
            if (!$('voiceAssistantCard')) return;
            this.buildFollowUpOptions();
            this.bind();
            this.render();
            this.onVisibilityChanged();
            var self = this;
            // Only a card that never loaded (a loaded one polls while shown). A read in flight makes refresh()
            // queue one more after it, so a late failure can't be the last word. Hidden: the card reads on return.
            if (window.BYD && BYD.offline && typeof BYD.offline.onReconnect === 'function') {
                BYD.offline.onReconnect(function () { if (!self.status && !document.hidden) self.refresh(); });
            }
        },

        bind: function () {
            var self = this;
            var enabled = $('voiceEnabled');
            if (enabled) enabled.addEventListener('change', function () {
                self.onEnabledChanged(this.checked);
            });
            var radios = document.querySelectorAll('input[name="voiceMode"]');
            for (var i = 0; i < radios.length; i++) {
                radios[i].addEventListener('change', function () {
                    if (this.checked) self.onModeChanged(this.value);
                });
            }
            this.bindSelect('voiceSensitivity', function (value) {
                return { sensitivity: value };
            });
            this.bindSelect('voiceFollowUp', function (value) {
                return { followUpSeconds: parseInt(value, 10) || 0 };
            });
            this.bindSelect('voiceAudioChannel', function (value) {
                return { audioChannel: value };
            });
            this.bindSelect('voiceCommandLanguage', function (value) {
                return { commandLanguage: value };
            });
            this.bindSelect('voiceMicSource', function (value) {
                return { micSource: value };
            });
            var share = $('voiceShareVehicleState');
            if (share) share.addEventListener('change', function () {
                self.save({ shareVehicleState: !!this.checked });
            });
            var shareLocation = $('voiceShareLocationWithAi');
            if (shareLocation) shareLocation.addEventListener('change', function () {
                self.save({ shareLocationWithAi: !!this.checked });
            });
            var pauseMedia = $('voicePauseMediaWhileListening');
            if (pauseMedia) pauseMedia.addEventListener('change', function () {
                self.save({ pauseMediaWhileListening: !!this.checked });
            });
            var resumeMedia = $('voiceResumeMediaAfterListening');
            if (resumeMedia) resumeMedia.addEventListener('change', function () {
                self.save({ resumeMediaAfterListening: !!this.checked });
            });
            var direct = $('voiceRealtimeDirectFallback');
            if (direct) direct.addEventListener('change', function () {
                self.save({ realtimeDirectFallback: !!this.checked });
            });
            var setup = $('voiceSetupGenAiBtn');
            if (setup) setup.addEventListener('click', function () {
                self.openGenAiSetup();
            });
            var enroll = $('voiceEnrollBtn');
            if (enroll) enroll.addEventListener('click', function () {
                self.startEnroll(false);
            });
            var enrollMusic = $('voiceEnrollMusicBtn');
            if (enrollMusic) enrollMusic.addEventListener('click', function () {
                self.startEnroll(true);
            });
            var forget = $('voiceEnrollClearBtn');
            if (forget) forget.addEventListener('click', function () {
                self.confirmThen(t('enroll_clear_confirm'), t('enroll_clear'), function () {
                    self.clearEnroll();
                });
            });

            document.addEventListener('visibilitychange', function () {
                self.onVisibilityChanged();
            });
            document.addEventListener('ot-tabs:active-changed', function () {
                self.onVisibilityChanged();
            });
            var card = $('voiceAssistantCard');
            if (card && window.IntersectionObserver) {
                new IntersectionObserver(function (entries) {
                    for (var j = 0; j < entries.length; j++) {
                        var entry = entries[j];
                        self.intersecting = typeof entry.isIntersecting === 'boolean'
                            ? entry.isIntersecting : entry.intersectionRatio > 0;
                    }
                    self.onVisibilityChanged();
                }).observe(card);
            }
            // GenAI.renderStatus() rewrites the realtime voice button after every GenAI
            // load/save; re-check readiness only when its realtime flag flips.
            var genAiVoice = $('genAiVoiceBtn');
            if (genAiVoice && window.MutationObserver) {
                new MutationObserver(function () {
                    self.onGenAiRendered();
                }).observe(genAiVoice, { attributes: true });
            }
            if (window.BYD && BYD.i18n && typeof BYD.i18n.onChange === 'function') {
                BYD.i18n.onChange(function () {
                    self.buildFollowUpOptions();
                    self.render();
                });
            }
        },

        bindSelect: function (id, toPatch) {
            var self = this;
            var select = $(id);
            if (select) select.addEventListener('change', function () {
                self.save(toPatch(this.value));
            });
        },

        buildFollowUpOptions: function () {
            var select = $('voiceFollowUp');
            if (!select) return;
            while (select.firstChild) select.removeChild(select.firstChild);
            for (var seconds = 0; seconds <= FOLLOW_UP_MAX_S; seconds++) {
                var option = document.createElement('option');
                option.value = String(seconds);
                option.textContent = seconds === 0
                    ? t('follow_up_off') : seconds + secondSuffix();
                select.appendChild(option);
            }
            if (this.config) select.value = String(this.config.followUpSeconds);
        },

        isVisible: function () {
            if (document.hidden) return false;
            var card = $('voiceAssistantCard');
            if (!card || (card.closest && card.closest('[hidden]'))) return false;
            return this.intersecting;
        },

        shouldPoll: function () {
            return !!(this.config && this.config.enabled) && this.isVisible();
        },

        onVisibilityChanged: function () {
            if (!this.isVisible()) {
                this.stopPolling();
                return;
            }
            if (this.inFlight || this.pollTimer) return;
            if (Date.now() - this.lastRefreshAt < MIN_REFRESH_GAP_MS) {
                this.schedule();
            } else {
                this.refresh();
            }
        },

        stopPolling: function () {
            if (this.pollTimer) {
                window.clearTimeout(this.pollTimer);
                this.pollTimer = null;
            }
        },

        schedule: function () {
            var self = this;
            this.stopPolling();
            if (!this.shouldPoll()) return;
            this.pollTimer = window.setTimeout(function () {
                self.pollTimer = null;
                self.refresh();
            }, this.enrollId ? ENROLL_POLL_MS : POLL_MS);
        },

        startEnroll: function (music) {
            var self = this;
            if (this.enrollBusy || this.enrollId || !this.config || !this.config.enabled) return;
            this.enrollBusy = true;
            this.enrollDoneText = null;
            this.render();
            var nameInput = $('voiceEnrollName');
            request('/api/voice/enroll', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'start', music: !!music, name: nameInput ? nameInput.value.trim() : '' })
            }).then(function (reply) {
                self.enrollId = reply.id || null;
                self.enrollMusic = !!music;
                self.enrollSince = Date.now();
            }, function (error) {
                var code = error && error.code;
                self.toast(t(code === 'voices_full' ? 'enroll_full' : code === 'moving' ? 'enroll_moving'
                    : 'enroll_start_failed'), 'error');
            }).then(function () {
                self.enrollBusy = false;
                self.render();
                self.refresh();
            });
        },

        clearEnroll: function () {
            var self = this;
            if (this.enrollBusy || this.enrollId) return;
            this.enrollBusy = true;
            this.render();
            request('/api/voice/enroll', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'clear' })
            }).then(function () {
                self.enrollDoneText = null;
            }, function () {
                self.toast(t('enroll_clear_failed'), 'error');
            }).then(function () {
                self.enrollBusy = false;
                self.refresh();
            });
        },

        forgetVoice: function (voice) {
            var self = this;
            if (this.enrollBusy || this.enrollId) return;
            this.enrollBusy = true;
            this.render();
            request('/api/voice/enroll', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'forget', voice: voice })
            }).then(function () {
                self.enrollDoneText = null;
            }, function () {
                self.toast(t('enroll_forget_failed'), 'error');
            }).then(function () {
                self.enrollBusy = false;
                self.refresh();
            });
        },

        /** Runs {@code action} after the themed confirmation (a plain confirm() where it is missing). */
        confirmThen: function (body, label, action) {
            var run = function (ok) {
                if (ok) action();
            };
            if (window.BYD && BYD.utils && typeof BYD.utils.confirmDialog === 'function') {
                BYD.utils.confirmDialog({
                    title: label,
                    body: body,
                    confirmLabel: label,
                    cancelLabel: t('enroll_cancel'),
                    danger: true
                }).then(run);
            } else {
                run(window.confirm(body));
            }
        },

        /** One line per trained voice, each with its own Forget; rebuilt only when something changed. */
        renderVoices: function (voices, disabled) {
            var self = this;
            var list = $('voiceEnrollVoices');
            if (!list) return;
            var key = JSON.stringify(voices) + '|' + disabled + '|' + lang();
            if (list.getAttribute('data-key') === key) return;
            list.setAttribute('data-key', key);
            while (list.firstChild) list.removeChild(list.firstChild);
            voices.forEach(function (voice) {
                var item = document.createElement('li');
                var label = document.createElement('span');
                label.textContent = t(voice.music ? 'enroll_voice_music' : 'enroll_voice_trained', {
                    name: voice.name || t('enroll_voice_main'),
                    when: voice.trainedAt ? formatWakeTime(voice.trainedAt) : ''
                });
                var forget = document.createElement('button');
                forget.type = 'button';
                forget.className = 'btn btn-secondary';
                forget.textContent = t('enroll_voice_forget');
                forget.disabled = disabled;
                forget.addEventListener('click', function () {
                    self.confirmThen(t('enroll_forget_confirm', { name: voice.name || t('enroll_voice_main') }),
                        t('enroll_voice_forget'), function () {
                            self.forgetVoice(voice.id);
                        });
                });
                item.appendChild(label);
                item.appendChild(forget);
                list.appendChild(item);
            });
            list.hidden = voices.length === 0;
        },

        /** The car's report for the training this page started, or null. */
        enrollReport: function () {
            var runtime = this.status && this.status.runtime;
            var report = runtime && runtime.enroll;
            return report && this.enrollId && report.id === this.enrollId ? report : null;
        },

        renderEnroll: function (locked) {
            var row = $('voiceEnrollRow');
            if (!row) return;
            var config = this.config;
            var personal = (config && config.personalWake) || {};
            var report = this.enrollReport();
            var text;
            if (this.enrollId) {
                if (report && (report.state === 'saved' || report.state === 'failed')) {
                    text = report.state === 'saved' ? t(this.enrollMusic ? 'enroll_music_saved' : 'enroll_saved')
                        : t(report.reason === 'no_speech' ? 'enroll_failed_no_speech'
                            : this.enrollMusic ? 'enroll_music_failed' : 'enroll_failed');
                    this.enrollId = null;
                    this.enrollDoneText = text;
                } else if (report && report.state === 'recording') {
                    text = t('enroll_recording', { take: report.take, takes: report.takes });
                } else if (report && report.state === 'processing') {
                    text = t('enroll_processing');
                } else if (Date.now() - this.enrollSince > ENROLL_PICKUP_MS) {
                    text = t('enroll_not_running');
                    this.enrollId = null;
                    this.enrollDoneText = text;
                } else {
                    text = t('enroll_requested');
                }
            } else if (this.enrollDoneText) {
                text = this.enrollDoneText;
            } else {
                var trained = (personal.voices || []).length;
                text = trained ? t('enroll_voices_count', { count: trained, max: personal.maxVoices || 4 })
                    : t('enroll_untrained');
            }
            this.setText('voiceEnrollStatus', text);
            var busy = locked || this.enrollBusy || !!this.enrollId;
            this.renderVoices(personal.voices || [], busy);
            var nameInput = $('voiceEnrollName');
            if (nameInput) nameInput.disabled = busy || !(config && config.enabled);
            var start = $('voiceEnrollBtn');
            if (start) start.disabled = locked || this.enrollBusy || !!this.enrollId || !(config && config.enabled);
            var startMusic = $('voiceEnrollMusicBtn');
            if (startMusic) startMusic.disabled = locked || this.enrollBusy || !!this.enrollId || !(config && config.enabled);
            var forget = $('voiceEnrollClearBtn');
            if (forget) {
                forget.hidden = !personal.trained;
                forget.disabled = locked || this.enrollBusy || !!this.enrollId;
            }
        },

        refresh: function () {
            var self = this;
            if (this.inFlight) {
                this.refreshAgain = true;
                return;
            }
            this.inFlight = true;
            this.stopPolling();
            var seq = this.saveSeq;
            request('/api/voice/status').then(function (status) {
                self.status = status;
                self.loadFailed = false;
                // A status read that started before a save must not undo that save.
                if (!self.busy && seq === self.saveSeq && status.config) self.config = status.config;
            }, function () {
                if (!self.status) self.loadFailed = true;
            }).then(function () {
                self.inFlight = false;
                self.lastRefreshAt = Date.now();
                self.render();
                if (self.refreshAgain) {
                    self.refreshAgain = false;
                    self.refresh();
                } else {
                    self.schedule();
                }
            });
        },

        onGenAiRendered: function () {
            var self = this;
            if (this.genAiRenderTimer) window.clearTimeout(this.genAiRenderTimer);
            this.genAiRenderTimer = window.setTimeout(function () {
                self.genAiRenderTimer = null;
                var genAi = window.GenAI && window.GenAI.status;
                if (!genAi || !self.status || !self.isVisible()) return;
                if (!!genAi.nativeRealtimeAudioAvailable !== self.genAiReady()) self.refresh();
            }, 300);
        },

        genAiReady: function () {
            return !!(this.status && this.status.genai && this.status.genai.ready);
        },

        save: function (patch) {
            var self = this;
            if (this.busy || !this.config) {
                this.render();
                return;
            }
            this.busy = true;
            this.saveSeq++;
            this.render();
            request('/api/voice/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(patch)
            }).then(function (saved) {
                self.config = saved;
                return true;
            }, function (error) {
                self.toast(error && error.code === 'genai_not_ready'
                    ? t('needs_genai') : t('save_failed'), 'error');
                return false;
            }).then(function (saved) {
                self.busy = false;
                self.render();
                if (saved && (patch.hasOwnProperty('enabled') || patch.hasOwnProperty('mode'))) {
                    self.refresh();
                } else {
                    self.schedule();
                }
            });
        },

        onEnabledChanged: function (on) {
            var patch = { enabled: !!on };
            // Without GenAI realtime only Local can work, so enabling lands there.
            if (on && this.config && this.config.mode !== 'local' && !this.genAiReady()) {
                patch.mode = 'local';
            }
            this.save(patch);
        },

        onModeChanged: function (mode) {
            if (!this.config || mode === this.config.mode) return;
            if (mode !== 'local' && !this.genAiReady()) {
                this.render();
                return;
            }
            this.save({ mode: mode });
        },

        openGenAiSetup: function () {
            var reason = this.status && this.status.genai ? this.status.genai.reason : '';
            var target = $(SETUP_TARGETS[reason] || 'genAiEnabled');
            if (window.GenAI && typeof window.GenAI.openProvider === 'function') {
                window.GenAI.openProvider();
            }
            var card = target && target.closest ? target.closest('.card') : null;
            if (card && card.scrollIntoView) card.scrollIntoView(true);
            if (target && typeof target.focus === 'function') {
                try { target.focus(); } catch (e) {}
            }
        },

        stateKey: function () {
            if (this.loadFailed) return 'unavailable';
            if (!this.config) return null;
            if (!this.config.enabled) return 'off';
            var runtime = this.status && this.status.runtime;
            var state = runtime && runtime.state;
            if (!state || state === 'off' || state === 'waiting') return 'waiting';
            return RUNTIME_STATES[state] ? state : 'unavailable';
        },

        render: function () {
            var config = this.config;
            var ready = this.genAiReady();
            var locked = this.busy || !config;

            var enabled = $('voiceEnabled');
            if (enabled) {
                enabled.checked = !!(config && config.enabled);
                enabled.disabled = locked;
            }

            var selector = $('voiceModeSelector');
            if (selector) selector.setAttribute('aria-busy', config ? 'false' : 'true');
            for (var i = 0; i < MODES.length; i++) {
                var radio = document.querySelector('input[name="voiceMode"][value="' + MODES[i] + '"]');
                if (!radio) continue;
                radio.checked = !!(config && config.mode === MODES[i]);
                radio.disabled = locked || (MODES[i] !== 'local' && !ready);
            }

            this.setValue('voiceSensitivity', config ? config.sensitivity : 'normal', locked);
            this.setValue('voiceFollowUp', config ? String(config.followUpSeconds) : '6', locked);
            this.setValue('voiceAudioChannel', config ? config.audioChannel : 'media', locked);
            this.setValue('voiceCommandLanguage', config ? (config.commandLanguage || '') : '', locked);
            this.setValue('voiceMicSource', config ? (config.micSource || 'mic') : 'mic', locked);
            var share = $('voiceShareVehicleState');
            if (share) {
                share.checked = !!(config && config.shareVehicleState);
                share.disabled = locked || config.mode === 'local';
            }
            var shareLocation = $('voiceShareLocationWithAi');
            if (shareLocation) {
                // Default on (config default); only Hybrid and GenAI talk to a provider.
                shareLocation.checked = !config || config.shareLocationWithAi !== false;
                shareLocation.disabled = locked || config.mode === 'local';
            }
            var pauseMedia = $('voicePauseMediaWhileListening');
            if (pauseMedia) {
                // Default on: a missing field (older daemon) renders as checked like the config default.
                pauseMedia.checked = !config || config.pauseMediaWhileListening !== false;
                pauseMedia.disabled = locked;
            }
            var resumeMedia = $('voiceResumeMediaAfterListening');
            if (resumeMedia) {
                // Default on; only meaningful while media is paused for the command.
                resumeMedia.checked = !config || config.resumeMediaAfterListening !== false;
                resumeMedia.disabled = locked || config.pauseMediaWhileListening === false;
            }
            var direct = $('voiceRealtimeDirectFallback');
            if (direct) {
                direct.checked = !!(config && config.realtimeDirectFallback);
                direct.disabled = locked || config.mode === 'local';
            }

            this.renderGenAiGate(config, ready);
            this.renderRuntime();
            this.renderEnroll(locked);
        },

        renderGenAiGate: function (config, ready) {
            var gate = $('voiceGenAiGate');
            if (!gate) return;
            var known = !!(this.status && this.status.genai);
            gate.hidden = !known || ready;
            if (gate.hidden) return;
            var reason = this.status.genai.reason;
            var text = t('needs_genai');
            if (EN.hasOwnProperty('reason_' + reason)) text += ' ' + t('reason_' + reason);
            if (config && config.enabled && config.mode !== 'local') text += ' ' + t('mode_unusable');
            this.setText('voiceGenAiReason', text);
        },

        renderRuntime: function () {
            var key = this.stateKey();
            this.setText('voiceState', key ? t('state_' + key) : '—');
            var runtime = (this.status && this.status.runtime) || {};
            var counters = runtime.counters || {};
            this.setText('voiceWakeModel', this.presence(runtime.wakeModelPresent));
            this.setText('voiceLocalEngine', this.presence(runtime.localEnginePresent));
            this.setText('voiceLastWake', runtime.lastWakeAt > 0
                ? formatWakeTime(runtime.lastWakeAt) : (this.status ? t('never') : '—'));
            this.setText('voiceWakes', this.status ? String(counters.wakes || 0) : '—');
            this.setText('voiceHandled', this.status ? t('handled_fmt', {
                local: counters.localHits || 0,
                cloud: counters.cloudTurns || 0,
                failures: counters.failures || 0
            }) : '—');

            var note = $('voiceRuntimeNote');
            if (note) {
                var message = this.loadFailed ? t('status_load_failed')
                    : (this.config && this.config.enabled && runtime.message ? runtime.message : '');
                note.hidden = !message;
                note.textContent = message;
            }
        },

        presence: function (value) {
            if (value === true) return t('installed');
            if (value === false) return t('not_installed');
            return '—';
        },

        setValue: function (id, value, disabled) {
            var element = $(id);
            if (!element) return;
            element.value = value;
            element.disabled = !!disabled;
        },

        setText: function (id, value) {
            var element = $(id);
            if (element) element.textContent = value == null ? '' : value;
        },

        toast: function (message, type) {
            if (window.BYD && BYD.core && BYD.core.toast) BYD.core.toast(message, type);
        }
    };

    window.VoiceAssistantCard = VoiceAssistantCard;
}(window, document));
