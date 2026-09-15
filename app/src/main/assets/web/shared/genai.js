/* OverDrive GenAI BYOK page. ES5 for the head-unit WebView floor. */
(function (window, document) {
    'use strict';

    function t(key, vars) {
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
                var v = BYD.i18n.t(key, vars);
                if (v != null && v !== key) return v;
            }
        } catch (e) {}
        return key;
    }

    var DEFAULT_URLS = {
        openai: 'https://api.openai.com',
        anthropic: 'https://api.anthropic.com',
        gemini: 'https://generativelanguage.googleapis.com',
        openai_compatible: ''
    };

    var MODEL_PRESETS = {
        openai: {
            text: ['gpt-5.6-sol', 'gpt-5.6-terra', 'gpt-5.6-luna'],
            realtime: ['gpt-realtime-2.1', 'gpt-realtime-2-mini']
        },
        anthropic: {
            text: ['claude-fable-5', 'claude-opus-5', 'claude-sonnet-5', 'claude-haiku-4-5'],
            realtime: []
        },
        gemini: {
            text: ['gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-3.5-flash-lite', 'gemini-2.5-flash'],
            realtime: ['gemini-3.1-flash-live-preview']
        },
        openai_compatible: { text: [], realtime: [] }
    };

    var GenAI = {
        status: null,
        messages: [],
        busy: false,
        insightBusy: false,
        selectedMode: 'general',
        chatSocket: null,
        chatGeneration: 0,
        chatCancelled: false,
        streamMessage: null,
        streamRenderPending: false,
        nativeRequests: {},
        nativeRequestId: 0,
        voiceSocket: null,
        voiceStream: null,
        voiceContext: null,
        voiceSource: null,
        voiceProcessor: null,
        voiceWorklet: null,
        voiceWorkletUrl: '',
        voiceGain: null,
        voiceSilentGain: null,
        voicePlaybackSources: [],
        voiceActive: false,
        voiceStarting: false,
        voiceGeneration: 0,
        voicePhase: 'idle',
        voiceInputRate: 24000,
        voiceOutputRate: 24000,
        voicePlaybackAt: 0,
        voiceItemId: '',
        voiceItemContentIndex: 0,
        voiceItemStartAt: 0,
        voiceItemDurationMs: 0,
        voiceTranscript: '',
        voiceActionMessage: null,
        voiceContextRequests: {},
        voiceResampleInputRate: 0,
        voiceResampleOutputRate: 0,
        voiceResampleRemaining: 0,
        voiceResampleSum: 0,
        voiceResampleWeight: 0,

        init: function () {
            var self = this;
            this.bind();
            this.renderMessages(false);
            this.loadStatus().then(function () {
                self.openInitialTab();
            });
        },

        bind: function () {
            var self = this;
            var composer = document.getElementById('genAiComposer');
            var input = document.getElementById('genAiInput');
            var provider = document.getElementById('genAiProvider');
            var enabled = document.getElementById('genAiEnabled');
            var voice = document.getElementById('genAiVoiceBtn');
            var schedule = document.getElementById('genAiInsightSchedule');
            var textModel = document.getElementById('genAiModelPreset');
            var realtimeModel =
                document.getElementById('genAiRealtimeModelPreset');
            var chips = document.querySelectorAll('.ai-chip');

            if (composer) composer.addEventListener('submit', function (event) {
                event.preventDefault();
                self.send();
            });
            if (input) {
                input.addEventListener('input', function () {
                    this.style.height = 'auto';
                    this.style.height = Math.min(this.scrollHeight, 132) + 'px';
                });
                input.addEventListener('keydown', function (event) {
                    if (event.keyCode === 13 && !event.shiftKey) {
                        event.preventDefault();
                        self.send();
                    }
                });
            }
            if (provider) provider.addEventListener('change', function () {
                self.onProviderChanged(true);
            });
            if (enabled) enabled.addEventListener('change', function () {
                if (!this.checked) self.disableNow();
            });
            if (voice) voice.addEventListener('click', function () {
                self.toggleVoice();
            });
            if (schedule) schedule.addEventListener('change', function () {
                self.updateScheduleFields();
            });
            if (textModel) textModel.addEventListener('change', function () {
                self.applyModelPreset(
                    'genAiModelPreset', 'genAiModel',
                    'genAiModelCustomField', true);
            });
            if (realtimeModel) {
                realtimeModel.addEventListener('change', function () {
                    self.applyModelPreset(
                        'genAiRealtimeModelPreset',
                        'genAiRealtimeModel',
                        'genAiRealtimeModelCustomField', true);
                });
            }
            for (var i = 0; i < chips.length; i++) {
                chips[i].addEventListener('click', function () {
                    var mode = this.getAttribute('data-mode') || 'general';
                    if (mode === 'diagnostic_logs' && !window.confirm(
                            t('genai.confirm_diagnostic_logs'))) {
                        return;
                    }
                    self.selectedMode = mode;
                    var prompt = this.getAttribute('data-prompt') || '';
                    if (input) {
                        input.value = prompt;
                        input.focus();
                    }
                    var rail = this.parentNode;
                    this.blur();
                    window.setTimeout(function () {
                        if (rail) rail.scrollLeft = 0;
                    }, 0);
                });
            }
            window.addEventListener('hashchange', function () {
                self.openInitialTab();
            });
            window.addEventListener('beforeunload', function () {
                self.cancelChat('Page closed', true);
                self.stopVoice('Page closed', true);
            });
            document.addEventListener('visibilitychange', function () {
                if (document.hidden) {
                    self.cancelChat('Page hidden', true);
                    self.stopVoice('Page hidden', true);
                }
            });
        },

        request: function (url, options) {
            options = options || {};
            var method = String(options.method || 'GET').toUpperCase();
            if (url.indexOf('/api/genai/') === 0
                    && method !== 'GET'
                    && window.AndroidBridge
                    && typeof AndroidBridge.httpRequestAsync === 'function') {
                return this.requestViaBridge(url, options);
            }
            return fetch(url, options || {}).then(function (response) {
                return response.json().catch(function () {
                    return { success: false, error: 'The daemon returned an invalid response.' };
                }).then(function (data) {
                    if (!response.ok || data.success === false) {
                        var error = new Error(data.error || ('Request failed (' + response.status + ')'));
                        error.code = data.code || '';
                        throw error;
                    }
                    return data;
                });
            });
        },

        requestViaBridge: function (url, options) {
            var self = this;
            return new Promise(function (resolve, reject) {
                var id = 'genai-' + (++self.nativeRequestId);
                var timer = window.setTimeout(function () {
                    delete self.nativeRequests[id];
                    reject(new Error('The on-vehicle request timed out.'));
                }, 135000);
                self.nativeRequests[id] = {
                    resolve: resolve,
                    reject: reject,
                    timer: timer
                };
                try {
                    AndroidBridge.httpRequestAsync(
                        'http://127.0.0.1:8080' + url,
                        String(options.method || 'GET').toUpperCase(),
                        String(options.body || ''),
                        JSON.stringify(options.headers || {}),
                        id
                    );
                } catch (error) {
                    window.clearTimeout(timer);
                    delete self.nativeRequests[id];
                    reject(new Error('Could not start the on-vehicle request.'));
                }
            });
        },

        onNativeResponse: function (id, raw) {
            var pending = this.nativeRequests[id];
            if (!pending) return;
            window.clearTimeout(pending.timer);
            delete this.nativeRequests[id];
            var data;
            try { data = JSON.parse(raw || '{}'); }
            catch (error) {
                pending.reject(new Error('The daemon returned an invalid response.'));
                return;
            }
            var status = Number(data._status || 200);
            delete data._status;
            if (status < 200 || status >= 300 || data.success === false) {
                var failure = new Error(data.error || ('Request failed (' + status + ')'));
                failure.code = data.code || '';
                pending.reject(failure);
                return;
            }
            pending.resolve(data);
        },

        loadStatus: function () {
            var self = this;
            return this.request('/api/genai/status').then(function (status) {
                self.status = status;
                self.renderStatus();
                self.populateConfig();
            }).catch(function (error) {
                self.showState(t('genai.state_unavailable'), error.message, 'error');
                self.toast(error.message, 'error');
            });
        },

        populateConfig: function () {
            var s = this.status;
            if (!s) return;
            this.setValue('genAiProvider', s.provider || 'openai');
            this.setValue('genAiBaseUrl', s.baseUrl || DEFAULT_URLS[s.provider] || '');
            this.setValue('genAiModel', s.model || '');
            this.setValue('genAiRealtimeModel', s.realtimeModel || '');
            this.setValue('genAiMaxTokens', s.maxOutputTokens || 1200);
            this.setValue('genAiInsightSchedule', s.insightSchedule || 'off');
            this.setValue('genAiInsightMode', s.insightMode || 'overview');
            this.setValue(
                'genAiInsightScheduleMode',
                s.insightMode || 'overview');
            this.setValue('genAiInsightDay', s.insightDay || 7);
            this.setValue(
                'genAiInsightTime',
                this.pad2(s.insightHour == null ? 20 : s.insightHour)
                    + ':' + this.pad2(s.insightMinute || 0)
            );
            var enabled = document.getElementById('genAiEnabled');
            if (enabled) enabled.checked = !!s.enabled;
            var notifications = document.getElementById('genAiInsightNotifications');
            if (notifications) notifications.checked = !!s.insightNotifications;
            var dashboard = document.getElementById('genAiInsightDashboard');
            if (dashboard) dashboard.checked = !!s.insightDashboard;
            var key = document.getElementById('genAiApiKey');
            if (key) {
                key.value = '';
                key.placeholder = s.apiKeyConfigured ? t('genai.key_saved') : ' ';
            }
            var hint = document.getElementById('genAiKeyHint');
            if (hint) hint.textContent = s.apiKeyConfigured
                ? t('genai.key_protected')
                : t('genai.key_encrypted');
            this.onProviderChanged(false);
            this.updateScheduleFields();
        },

        renderStatus: function () {
            var s = this.status || {};
            var badge = document.getElementById('genAiBadge');
            if (badge) {
                badge.className = 'status-badge ' + (s.enabled && s.configured ? 'active' : 'inactive');
                badge.textContent = !s.enabled ? t('genai.off') : (s.configured ? t('genai.ready') : t('genai.setup'));
            }

            if (!s.enabled) {
                this.showState(
                    t('genai.state_off_title'),
                    t('genai.state_off_desc'),
                    'off'
                );
            } else if (!s.configured) {
                this.showState(
                    t('genai.state_setup_title'),
                    t('genai.state_setup_desc'),
                    'warning'
                );
            } else {
                var parked = s.availableWhileParked
                    ? t('genai.parked_available')
                    : t('genai.parked_on_only_desc');
                this.showState(
                    t('genai.ready_title', { provider: this.providerLabel(s.provider) }),
                    t('genai.state_using', { model: s.model || t('genai.configured_model') }) + parked,
                    'ready'
                );
            }

            this.setText('genAiParked', s.availableWhileParked
                ? t('genai.parked_on_off') : t('genai.parked_on_only'));
            this.setText('genAiTransport', s.transportActive
                ? (s.lastNetworkRoute || t('genai.active')) : t('genai.transport_idle'));
            this.setText('genAiProxy', s.proxyExpected
                ? t('genai.proxy_fail_closed') : t('genai.proxy_direct'));
            this.setText('genAiActiveRequests', String(s.activeRequests || 0));

            var voice = document.getElementById('genAiVoiceBtn');
            var voiceNote = document.getElementById('genAiVoiceNote');
            var voiceReady = !!s.enabled && !!s.nativeRealtimeAudioAvailable;
            if (voice) {
                voice.disabled = !voiceReady
                    && !this.voiceActive && !this.voiceStarting;
                voice.classList.toggle('is-live', this.voiceActive);
                voice.setAttribute(
                    'aria-pressed',
                    this.voiceActive || this.voiceStarting ? 'true' : 'false');
                voice.setAttribute(
                    'aria-label',
                    this.voiceActive || this.voiceStarting
                        ? t('genai.stop_voice')
                        : (voiceReady
                            ? t('genai.start_voice')
                            : t('genai.voice_unavailable_short')));
                voice.title = this.voiceActive || this.voiceStarting
                    ? t('genai.stop_voice')
                    : (voiceReady
                        ? t('genai.start_voice_native')
                        : t('genai.voice_unavailable'));
            }
            if (voiceNote) {
                voiceNote.classList.toggle(
                    'is-live', this.voiceActive || this.voiceStarting);
                if (this.voiceStarting) {
                    voiceNote.textContent = t('genai.connecting_realtime');
                } else if (this.voiceActive) {
                    var phase = this.voicePhase === 'speaking'
                        ? t('genai.speaking') : (this.voicePhase === 'thinking'
                            ? t('genai.thinking') : t('genai.listening'));
                    voiceNote.textContent = this.voiceTranscript
                        ? phase + ' · ' + this.voiceTranscript
                        : phase + ' · ' + t('genai.tap_to_stop');
                } else {
                    voiceNote.textContent = voiceReady
                        ? t('genai.voice_tap_hint')
                        : t('genai.voice_note');
                }
            }

            var usable = !!s.enabled && !!s.configured && !this.busy
                && !this.voiceActive && !this.voiceStarting;
            this.setChatEnabled(usable);
            var insightUsable = !!s.enabled && !!s.configured
                && !s.insightsGenerating && !this.insightBusy;
            this.setDisabled('genAiInsightGenerateBtn', !insightUsable);
            this.setDisabled('genAiInsightScheduleSaveBtn', this.insightBusy);
        },

        showState: function (title, description, kind) {
            this.setText('genAiStateTitle', title);
            this.setText('genAiStateDesc', description);
            var icon = document.querySelector('.ai-state-icon');
            if (!icon) return;
            icon.style.color = kind === 'error' ? 'var(--danger)' :
                (kind === 'warning' ? 'var(--status-warning)' : 'var(--brand-primary)');
        },

        setChatEnabled: function (enabled) {
            var input = document.getElementById('genAiInput');
            var send = document.getElementById('genAiSendBtn');
            var chips = document.querySelectorAll('.ai-chip');
            if (input) input.disabled = !enabled;
            if (send) {
                var cancellable = this.busy && !!this.streamMessage;
                send.disabled = this.busy ? !cancellable : !enabled;
                send.classList.toggle('is-cancel', cancellable);
                send.setAttribute('aria-label',
                    cancellable ? t('genai.stop_response') : t('genai.send'));
                send.title = cancellable ? t('genai.stop_response') : t('genai.send');
            }
            for (var i = 0; i < chips.length; i++) chips[i].disabled = !enabled;
        },

        openProvider: function () {
            if (typeof window.OT_setActiveTab === 'function') window.OT_setActiveTab('provider');
        },

        onProviderChanged: function (allowDefault) {
            var provider = this.value('genAiProvider') || 'openai';
            var presets = MODEL_PRESETS[provider] || MODEL_PRESETS.openai_compatible;
            var baseUrl = document.getElementById('genAiBaseUrl');
            var model = document.getElementById('genAiModel');
            var realtimeModel = document.getElementById('genAiRealtimeModel');
            if (allowDefault) {
                if (baseUrl) baseUrl.value = DEFAULT_URLS[provider] || '';
                if (model) model.value = presets.text.length ? presets.text[0] : '';
                if (realtimeModel) realtimeModel.value = '';
                var key = document.getElementById('genAiApiKey');
                if (key) key.value = '';
            }
            this.fillModelSelect(
                'genAiModelPreset', 'genAiModel',
                'genAiModelCustomField', presets.text, false);
            this.fillModelSelect(
                'genAiRealtimeModelPreset', 'genAiRealtimeModel',
                'genAiRealtimeModelCustomField', presets.realtime, true);
            var realtime = document.getElementById('genAiRealtimeField');
            if (realtime) {
                realtime.style.display =
                    (provider === 'openai' || provider === 'gemini') ? '' : 'none';
            }
            var modelHint = document.getElementById('genAiModelHint');
            if (modelHint) {
                modelHint.textContent = presets.text.length
                    ? t('genai.suggested_models', { list: presets.text.join(', ') })
                    : t('genai.enter_model_id');
            }
            var hint = document.getElementById('genAiKeyHint');
            if (hint && allowDefault) {
                hint.textContent = provider === 'openai_compatible'
                    ? t('genai.optional_bearer')
                    : t('genai.enter_new_key_full', { provider: this.providerLabel(provider) });
            }
        },

        fillModelSelect: function (
                selectId, inputId, customFieldId, values, allowEmpty) {
            var select = document.getElementById(selectId);
            var input = document.getElementById(inputId);
            if (!select || !input) return;
            var current = String(input.value || '').trim();
            while (select.firstChild) {
                select.removeChild(select.firstChild);
            }
            if (allowEmpty) {
                var none = document.createElement('option');
                none.value = '';
                none.textContent = t('genai.no_realtime');
                select.appendChild(none);
            }
            for (var i = 0; i < values.length; i++) {
                var option = document.createElement('option');
                option.value = values[i];
                option.textContent = values[i];
                select.appendChild(option);
            }
            var custom = document.createElement('option');
            custom.value = '__custom__';
            custom.textContent = t('genai.custom_model_ellipsis');
            select.appendChild(custom);

            if (values.indexOf(current) >= 0) {
                select.value = current;
            } else if (!current && allowEmpty) {
                select.value = '';
            } else if (!current && values.length) {
                select.value = values[0];
                input.value = values[0];
            } else {
                select.value = '__custom__';
            }
            this.applyModelPreset(
                selectId, inputId, customFieldId, false);
        },

        applyModelPreset: function (
                selectId, inputId, customFieldId, focusCustom) {
            var select = document.getElementById(selectId);
            var input = document.getElementById(inputId);
            var customField = document.getElementById(customFieldId);
            if (!select || !input || !customField) return;
            var custom = select.value === '__custom__';
            customField.hidden = !custom;
            if (custom) {
                if (focusCustom) {
                    input.value = '';
                    input.focus();
                }
            } else {
                input.value = select.value || '';
            }
        },

        configPayload: function () {
            var payload = {
                enabled: !!document.getElementById('genAiEnabled').checked,
                provider: this.value('genAiProvider'),
                baseUrl: this.value('genAiBaseUrl'),
                model: this.value('genAiModel'),
                realtimeModel: this.value('genAiRealtimeModel'),
                maxOutputTokens: parseInt(this.value('genAiMaxTokens'), 10) || 1200
            };
            var key = this.value('genAiApiKey').trim();
            if (key) payload.apiKey = key;
            return payload;
        },

        saveConfig: function () {
            var self = this;
            this.cancelChat('Provider settings changed', true);
            this.stopVoice('Provider settings changed', true);
            this.setButtonBusy('genAiSaveBtn', true, t('genai.saving'));
            return this.request('/api/genai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(this.configPayload())
            }).then(function (status) {
                self.status = status;
                self.renderStatus();
                self.populateConfig();
                self.toast(t('genai.saved_toast'), 'success');
            }).catch(function (error) {
                self.toast(error.message, 'error');
                var enabled = document.getElementById('genAiEnabled');
                if (enabled && self.status) enabled.checked = !!self.status.enabled;
            }).then(function () {
                self.setButtonBusy('genAiSaveBtn', false, t('genai.save'));
            });
        },

        disableNow: function () {
            var self = this;
            this.cancelChat('GenAI disabled', true);
            this.stopVoice('GenAI disabled', true);
            return this.request('/api/genai/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled: false })
            }).then(function (status) {
                self.status = status;
                self.renderStatus();
                self.toast(t('genai.disabled_toast'), 'success');
            }).catch(function (error) {
                self.toast(error.message, 'error');
                self.loadStatus();
            });
        },

        testConnection: function () {
            var self = this;
            this.setButtonBusy('genAiTestBtn', true, t('genai.testing'));
            return this.request('/api/genai/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: '{}'
            }).then(function () {
                self.toast(t('genai.test_ok'), 'success');
            }).catch(function (error) {
                self.toast(error.message, 'error');
            }).then(function () {
                self.setButtonBusy('genAiTestBtn', false, t('genai.test'));
                self.loadStatus();
            });
        },

        clearCredentials: function () {
            if (!window.confirm(t('genai.confirm_clear'))) return;
            var self = this;
            this.cancelChat('GenAI disabled', true);
            this.stopVoice('GenAI disabled', true);
            return this.request('/api/genai/config', { method: 'DELETE' })
                .then(function (status) {
                    self.status = status;
                    self.messages = [];
                    self.renderMessages();
                    self.renderStatus();
                    self.populateConfig();
                    self.toast(t('genai.cleared_toast'), 'success');
                }).catch(function (error) {
                    self.toast(error.message, 'error');
                });
        },

        toggleVoice: function () {
            if (this.voiceActive || this.voiceStarting) {
                this.stopVoice('Stopped', true);
            } else {
                this.startVoice();
            }
        },

        startVoice: function () {
            if (!this.status || !this.status.enabled
                    || !this.status.nativeRealtimeAudioAvailable
                    || this.voiceStarting || this.voiceActive) {
                return;
            }
            var AudioContextCtor =
                window.AudioContext || window.webkitAudioContext;
            if (!AudioContextCtor) {
                this.toast(
                    t('genai.no_realtime_audio'), 'error');
                return;
            }

            this.voiceStarting = true;
            this.voicePhase = 'connecting';
            this.voiceTranscript = '';
            this.voiceActionMessage = null;
            this.voiceContextRequests = {};
            var generation = ++this.voiceGeneration;
            this.renderStatus();

            try {
                var desiredRate = this.status.provider === 'gemini'
                    ? 16000 : 24000;
                try {
                    this.voiceContext = new AudioContextCtor({
                        sampleRate: desiredRate
                    });
                } catch (unsupportedOptions) {
                    this.voiceContext = new AudioContextCtor();
                }
                this.voiceGain = this.voiceContext.createGain();
                this.voiceGain.gain.value = 1;
                this.voiceGain.connect(this.voiceContext.destination);
                if (this.voiceContext.state === 'suspended') {
                    this.voiceContext.resume();
                }
            } catch (error) {
                this.failVoice(
                    t('genai.realtime_audio_failed'));
                return;
            }

            var self = this;
            this.getMicrophone().then(function (stream) {
                if (generation !== self.voiceGeneration
                        || !self.voiceStarting) {
                    self.stopTracks(stream);
                    return;
                }
                self.voiceStream = stream;
                self.openVoiceSocket(generation);
            }).catch(function (error) {
                if (generation !== self.voiceGeneration) return;
                self.failVoice(error && error.name === 'NotAllowedError'
                    ? t('genai.mic_denied')
                    : t('genai.mic_unavailable'));
            });
        },

        getMicrophone: function () {
            var constraints = {
                audio: {
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true
                },
                video: false
            };
            if (navigator.mediaDevices
                    && navigator.mediaDevices.getUserMedia) {
                return navigator.mediaDevices.getUserMedia(constraints);
            }
            var legacy = navigator.webkitGetUserMedia
                || navigator.getUserMedia;
            if (!legacy) {
                return Promise.reject(
                    new Error(t('genai.mic_unsupported')));
            }
            return new Promise(function (resolve, reject) {
                legacy.call(navigator, constraints, resolve, reject);
            });
        },

        openVoiceSocket: function (generation) {
            var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
            var url = protocol + '//' + location.host + '/ws/genai';
            var query = [
                'lang=' + encodeURIComponent(this.responseLanguage())
            ];
            if (typeof BYDAuth !== 'undefined') {
                var token = BYDAuth.getToken();
                if (token) query.push(
                    'token=' + encodeURIComponent(token));
            }
            url += '?' + query.join('&');
            var socket;
            try {
                socket = new WebSocket(url);
            } catch (error) {
                this.failVoice(
                    t('genai.realtime_open_failed'));
                return;
            }
            this.voiceSocket = socket;
            socket.binaryType = 'arraybuffer';
            var self = this;
            socket.onmessage = function (event) {
                if (generation !== self.voiceGeneration
                        || self.voiceSocket !== socket) return;
                if (typeof event.data === 'string') {
                    self.handleVoiceControl(event.data, generation);
                } else {
                    self.playVoicePcm(event.data, generation);
                }
            };
            socket.onerror = function () {
                if (generation === self.voiceGeneration
                        && self.voiceSocket === socket) {
                    self.failVoice(
                        t('genai.realtime_connect_failed'));
                }
            };
            socket.onclose = function () {
                if (generation === self.voiceGeneration
                        && self.voiceSocket === socket
                        && (self.voiceActive || self.voiceStarting)) {
                    self.failVoice(
                        t('genai.realtime_closed'));
                }
            };
        },

        handleVoiceControl: function (raw, generation) {
            var message;
            try { message = JSON.parse(raw); }
            catch (error) { return; }
            if (message.type === 'connected') {
                this.voiceInputRate =
                    Number(message.inputSampleRate) || 24000;
                this.voiceOutputRate =
                    Number(message.outputSampleRate) || 24000;
            } else if (message.type === 'live') {
                this.voiceInputRate =
                    Number(message.inputSampleRate)
                    || this.voiceInputRate;
                this.voiceOutputRate =
                    Number(message.outputSampleRate)
                    || this.voiceOutputRate;
                this.voiceStarting = false;
                this.voiceActive = true;
                this.voicePhase = 'listening';
                this.startVoiceCapture(generation);
                this.renderStatus();
            } else if (message.type === 'state') {
                this.voicePhase = message.state || 'listening';
                this.renderStatus();
            } else if (message.type === 'audio_item') {
                this.voiceItemId = String(message.itemId || '');
                this.voiceItemContentIndex =
                    Number(message.contentIndex) || 0;
                this.voiceItemStartAt = 0;
                this.voiceItemDurationMs = 0;
            } else if (message.type === 'clear') {
                this.clearVoicePlayback(true);
            } else if (message.type === 'action_proposal'
                    && message.actionProposal) {
                var actionMessage = {
                    role: 'assistant',
                    content: String(message.text || ''),
                    mode: 'vehicle_action',
                    actionProposal: message.actionProposal
                };
                this.messages.push(actionMessage);
                this.voiceActionMessage = actionMessage;
                if (this.messages.length > 20) {
                    this.messages = this.messages.slice(-20);
                }
                this.renderMessages(false);
            } else if (message.type === 'context_request'
                    && message.token) {
                var contextRequest = {
                    token: String(message.token),
                    mode: String(message.mode || ''),
                    query: String(message.query || ''),
                    responding: false,
                    resolved: false,
                    approved: false
                };
                this.voiceContextRequests[contextRequest.token] =
                    contextRequest;
                this.messages.push({
                    role: 'assistant',
                    content: t('genai.share_voice_prompt'),
                    mode: contextRequest.mode,
                    contextRequest: contextRequest
                });
                if (this.messages.length > 20) {
                    this.messages = this.messages.slice(-20);
                }
                this.renderMessages(false);
            } else if (message.type === 'context_resolved'
                    && message.token) {
                var resolved = this.voiceContextRequests[
                    String(message.token)];
                if (resolved) {
                    resolved.responding = false;
                    resolved.resolved = true;
                    resolved.approved = !!message.approved;
                    delete this.voiceContextRequests[
                        String(message.token)];
                    this.renderMessages(false);
                }
            } else if (message.type === 'transcript_delta') {
                this.voiceTranscript += String(message.text || '');
                if (this.voiceTranscript.length > 600) {
                    this.voiceTranscript =
                        this.voiceTranscript.slice(-600);
                }
                this.renderStatus();
            } else if (message.type === 'transcript_done') {
                var completed = String(
                    message.text || this.voiceTranscript || '').trim();
                if (completed) {
                    if (this.voiceActionMessage) {
                        this.voiceActionMessage.content = completed;
                        this.voiceActionMessage = null;
                    } else {
                        this.messages.push({
                            role: 'assistant',
                            content: completed
                        });
                    }
                    if (this.messages.length > 20) {
                        this.messages = this.messages.slice(-20);
                    }
                    this.renderMessages(false);
                }
                this.voiceTranscript = '';
                this.renderStatus();
            } else if (message.type === 'failed') {
                this.failVoice(
                    message.reason || t('genai.realtime_provider_failed'));
            } else if (message.type === 'stopped') {
                var reason = message.reason || t('genai.voice_session_ended');
                this.stopVoice(reason, true);
                if (reason !== 'Stopped' && reason !== 'Voice session closed') {
                    this.toast(reason, 'info');
                }
            }
        },

        startVoiceCapture: function (generation) {
            if (!this.voiceContext || !this.voiceStream
                    || generation !== this.voiceGeneration) {
                this.failVoice(
                    t('genai.mic_start_failed'));
                return;
            }
            var self = this;
            try {
                this.voiceSource =
                    this.voiceContext.createMediaStreamSource(
                        this.voiceStream);
                this.voiceSilentGain =
                    this.voiceContext.createGain();
                this.voiceSilentGain.gain.value = 0;
                this.voiceSilentGain.connect(
                    this.voiceContext.destination);
                if (this.voiceContext.audioWorklet
                        && window.AudioWorkletNode
                        && window.Blob && window.URL
                        && window.URL.createObjectURL) {
                    var code = 'class OverDrivePcmProcessor extends AudioWorkletProcessor{constructor(){super();this.buffer=new Float32Array(2048);this.offset=0;}process(inputs){var input=inputs[0]&&inputs[0][0];if(!input)return true;var index=0;while(index<input.length){var count=Math.min(input.length-index,this.buffer.length-this.offset);this.buffer.set(input.subarray(index,index+count),this.offset);this.offset+=count;index+=count;if(this.offset===this.buffer.length){var ready=this.buffer;this.buffer=new Float32Array(2048);this.offset=0;this.port.postMessage(ready.buffer,[ready.buffer]);}}return true;}}registerProcessor("overdrive-pcm",OverDrivePcmProcessor);';
                    var blob = new Blob([code], {
                        type: 'application/javascript'
                    });
                    this.voiceWorkletUrl =
                        URL.createObjectURL(blob);
                    this.voiceContext.audioWorklet
                        .addModule(this.voiceWorkletUrl)
                        .then(function () {
                            if (!self.voiceActive
                                    || generation
                                        !== self.voiceGeneration
                                    || !self.voiceSource) return;
                            self.voiceWorklet =
                                new AudioWorkletNode(
                                    self.voiceContext,
                                    'overdrive-pcm');
                            self.voiceWorklet.port.onmessage =
                                function (event) {
                                    self.sendVoiceSamples(
                                        new Float32Array(event.data),
                                        generation);
                                };
                            self.voiceSource.connect(
                                self.voiceWorklet);
                            self.voiceWorklet.connect(
                                self.voiceSilentGain);
                        }).catch(function () {
                            self.startLegacyVoiceCapture(
                                generation);
                        });
                } else {
                    this.startLegacyVoiceCapture(generation);
                }
            } catch (error) {
                this.failVoice(
                    t('genai.mic_processing_failed'));
            }
        },

        startLegacyVoiceCapture: function (generation) {
            if (!this.voiceContext || !this.voiceSource
                    || !this.voiceSilentGain
                    || generation !== this.voiceGeneration) return;
            var self = this;
            try {
                this.voiceProcessor =
                    this.voiceContext.createScriptProcessor(
                        2048, 1, 1);
                this.voiceSource.connect(this.voiceProcessor);
                this.voiceProcessor.connect(
                    this.voiceSilentGain);
                this.voiceProcessor.onaudioprocess =
                    function (event) {
                        self.sendVoiceSamples(
                            event.inputBuffer.getChannelData(0),
                            generation);
                    };
            } catch (error) {
                this.failVoice(
                    t('genai.mic_processing_failed'));
            }
        },

        sendVoiceSamples: function (input, generation) {
            if (!this.voiceActive
                    || generation !== this.voiceGeneration
                    || !input || !input.length) return;
            var socket = this.voiceSocket;
            if (!socket || socket.readyState !== WebSocket.OPEN) {
                return;
            }
            if (socket.bufferedAmount > 262144) {
                this.failVoice(
                    t('genai.voice_too_slow'));
                return;
            }
            var pcm = this.downsampleToPcm16(
                input, this.voiceContext.sampleRate,
                this.voiceInputRate);
            if (pcm.length) socket.send(pcm.buffer);
        },

        playVoicePcm: function (buffer, generation) {
            if (!this.voiceActive
                    || generation !== this.voiceGeneration
                    || !this.voiceContext || !buffer) return;
            var pcm = new Int16Array(buffer);
            if (!pcm.length) return;
            var audio = this.voiceContext.createBuffer(
                1, pcm.length, this.voiceOutputRate);
            var samples = audio.getChannelData(0);
            for (var i = 0; i < pcm.length; i++) {
                samples[i] = pcm[i] / 32768;
            }
            var now = this.voiceContext.currentTime;
            if (this.voicePlaybackAt > now + 1) {
                this.clearVoicePlayback(false);
            }
            var startAt = Math.max(
                now + 0.06, this.voicePlaybackAt || 0);
            if (!this.voiceItemStartAt) {
                this.voiceItemStartAt = startAt;
            }
            var source = this.voiceContext.createBufferSource();
            source.buffer = audio;
            source.connect(this.voiceGain);
            this.voicePlaybackSources.push(source);
            source.start(startAt);
            var durationMs =
                pcm.length / this.voiceOutputRate * 1000;
            this.voiceItemDurationMs += durationMs;
            this.voicePlaybackAt =
                startAt + durationMs / 1000;
            var self = this;
            source.onended = function () {
                try { source.disconnect(); } catch (error) {}
                var index =
                    self.voicePlaybackSources.indexOf(source);
                if (index >= 0) {
                    self.voicePlaybackSources.splice(index, 1);
                }
            };
        },

        clearVoicePlayback: function (truncate) {
            if (truncate && this.voiceItemId && this.voiceContext
                    && this.voiceSocket
                    && this.voiceSocket.readyState === WebSocket.OPEN) {
                var playedMs = this.voiceItemStartAt
                    ? Math.max(0, Math.min(
                        this.voiceItemDurationMs,
                        (this.voiceContext.currentTime
                            - this.voiceItemStartAt) * 1000))
                    : 0;
                this.voiceSocket.send(JSON.stringify({
                    type: 'truncate',
                    itemId: this.voiceItemId,
                    contentIndex: this.voiceItemContentIndex,
                    audioEndMs: Math.floor(playedMs)
                }));
            }
            var sources = this.voicePlaybackSources;
            this.voicePlaybackSources = [];
            for (var i = 0; i < sources.length; i++) {
                try { sources[i].stop(); } catch (error) {}
                try { sources[i].disconnect(); } catch (error) {}
            }
            this.voicePlaybackAt = 0;
            this.voiceItemId = '';
            this.voiceItemStartAt = 0;
            this.voiceItemDurationMs = 0;
        },

        downsampleToPcm16: function (
                buffer, inputRate, outputRate) {
            if (outputRate > inputRate) outputRate = inputRate;
            var ratio = inputRate / outputRate;
            if (this.voiceResampleInputRate !== inputRate
                    || this.voiceResampleOutputRate !== outputRate) {
                this.voiceResampleInputRate = inputRate;
                this.voiceResampleOutputRate = outputRate;
                this.voiceResampleRemaining = ratio;
                this.voiceResampleSum = 0;
                this.voiceResampleWeight = 0;
            }
            var result = new Int16Array(
                Math.ceil(buffer.length / ratio) + 1);
            var outputIndex = 0;
            for (var i = 0; i < buffer.length; i++) {
                var available = 1;
                while (available > 0) {
                    var take = Math.min(
                        available,
                        this.voiceResampleRemaining);
                    this.voiceResampleSum += buffer[i] * take;
                    this.voiceResampleWeight += take;
                    this.voiceResampleRemaining -= take;
                    available -= take;
                    if (this.voiceResampleRemaining <= 0.000001) {
                        var sample = this.voiceResampleWeight
                            ? this.voiceResampleSum
                                / this.voiceResampleWeight
                            : 0;
                        sample = Math.max(
                            -1, Math.min(1, sample));
                        result[outputIndex++] = sample < 0
                            ? sample * 32768
                            : sample * 32767;
                        this.voiceResampleRemaining = ratio;
                        this.voiceResampleSum = 0;
                        this.voiceResampleWeight = 0;
                    }
                }
            }
            var exact = new Int16Array(outputIndex);
            exact.set(result.subarray(0, outputIndex));
            return exact;
        },

        stopTracks: function (stream) {
            if (!stream || !stream.getTracks) return;
            var tracks = stream.getTracks();
            for (var i = 0; i < tracks.length; i++) {
                try { tracks[i].stop(); } catch (error) {}
            }
        },

        failVoice: function (message) {
            this.stopVoice(message, true);
            this.toast(message, 'error');
        },

        stopVoice: function (reason, silent) {
            var hadVoice = this.voiceActive || this.voiceStarting
                || this.voiceSocket || this.voiceStream
                || this.voiceContext;
            ++this.voiceGeneration;
            this.voiceActive = false;
            this.voiceStarting = false;
            this.voicePhase = 'idle';
            this.voiceTranscript = '';
            this.voiceActionMessage = null;
            for (var token in this.voiceContextRequests) {
                if (Object.prototype.hasOwnProperty.call(
                        this.voiceContextRequests, token)) {
                    var pendingContext =
                        this.voiceContextRequests[token];
                    pendingContext.responding = false;
                    pendingContext.resolved = true;
                    pendingContext.approved = false;
                }
            }
            this.voiceContextRequests = {};
            this.clearVoicePlayback(false);

            var socket = this.voiceSocket;
            this.voiceSocket = null;
            if (socket) {
                socket.onopen = null;
                socket.onmessage = null;
                socket.onerror = null;
                socket.onclose = null;
                try {
                    if (socket.readyState === WebSocket.OPEN) {
                        socket.send(JSON.stringify({
                            type: 'stop',
                            reason: reason || 'Stopped'
                        }));
                    }
                    socket.close(1000, reason || 'Stopped');
                } catch (error) {}
            }
            if (this.voiceProcessor) {
                try {
                    this.voiceProcessor.onaudioprocess = null;
                    this.voiceProcessor.disconnect();
                } catch (error) {}
            }
            if (this.voiceWorklet) {
                try {
                    this.voiceWorklet.port.onmessage = null;
                    this.voiceWorklet.disconnect();
                } catch (error) {}
            }
            if (this.voiceSource) {
                try { this.voiceSource.disconnect(); }
                catch (error) {}
            }
            if (this.voiceSilentGain) {
                try { this.voiceSilentGain.disconnect(); }
                catch (error) {}
            }
            if (this.voiceGain) {
                try { this.voiceGain.disconnect(); }
                catch (error) {}
            }
            this.voiceProcessor = null;
            this.voiceWorklet = null;
            this.voiceSource = null;
            this.voiceSilentGain = null;
            this.voiceGain = null;
            if (this.voiceWorkletUrl) {
                try {
                    URL.revokeObjectURL(
                        this.voiceWorkletUrl);
                } catch (error) {}
                this.voiceWorkletUrl = '';
            }
            this.voiceResampleInputRate = 0;
            this.voiceResampleOutputRate = 0;
            this.voiceResampleRemaining = 0;
            this.voiceResampleSum = 0;
            this.voiceResampleWeight = 0;
            this.stopTracks(this.voiceStream);
            this.voiceStream = null;
            if (this.voiceContext) {
                try {
                    var closing = this.voiceContext.close();
                    if (closing && closing.catch) {
                        closing.catch(function () {});
                    }
                } catch (error) {}
            }
            this.voiceContext = null;
            this.renderStatus();
            if (hadVoice && !silent && reason) {
                this.toast(reason, 'info');
            }
        },

        generateInsight: function () {
            if (this.insightBusy || !this.status
                    || !this.status.enabled || !this.status.configured) {
                return;
            }
            var self = this;
            this.setInsightBusy(true);
            return this.request('/api/genai/insights/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    mode: this.value('genAiInsightMode') || 'overview',
                    prompt: this.value('genAiInsightPrompt').trim(),
                    language: window.BYD && BYD.i18n
                        ? BYD.i18n.getLang() : 'en',
                    notify: !!document.getElementById('genAiInsightNotifyNow').checked
                })
            }).then(function () {
                self.toast(t('genai.insight_generated'), 'success');
            }).catch(function (error) {
                self.toast(error.message, 'error');
            }).then(function () {
                self.setInsightBusy(false);
                self.loadStatus();
            });
        },

        saveInsightSettings: function () {
            if (this.insightBusy) return;
            var match = /^(\d{1,2}):(\d{2})$/.exec(
                this.value('genAiInsightTime') || '');
            if (!match) {
                this.toast(t('genai.choose_time'), 'error');
                return;
            }
            var self = this;
            this.setInsightBusy(true);
            return this.request('/api/genai/insights/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    insightSchedule: this.value('genAiInsightSchedule') || 'off',
                    insightHour: Number(match[1]),
                    insightMinute: Number(match[2]),
                    insightDay: Number(this.value('genAiInsightDay') || 7),
                    insightMode:
                        this.value('genAiInsightScheduleMode') || 'overview',
                    insightDashboard:
                        !!document.getElementById('genAiInsightDashboard').checked,
                    insightNotifications:
                        !!document.getElementById('genAiInsightNotifications').checked
                })
            }).then(function (status) {
                self.status = status;
                self.populateConfig();
                self.renderStatus();
                self.toast(t('genai.insight_saved'), 'success');
            }).catch(function (error) {
                self.toast(error.message, 'error');
            }).then(function () {
                self.setInsightBusy(false);
            });
        },

        updateScheduleFields: function () {
            var schedule = this.value('genAiInsightSchedule') || 'off';
            var time = document.getElementById('genAiInsightTime');
            var day = document.getElementById('genAiInsightDay');
            var dayField = document.getElementById('genAiInsightDayField');
            if (time) time.disabled = schedule === 'off';
            if (day) day.disabled = schedule !== 'weekly';
            if (dayField) dayField.style.display =
                schedule === 'weekly' ? '' : 'none';
        },

        setInsightBusy: function (busy) {
            this.insightBusy = !!busy;
            this.setButtonBusy(
                'genAiInsightGenerateBtn', busy,
                busy ? t('genai.generating') : t('genai.generate_now'));
            this.setButtonBusy(
                'genAiInsightScheduleSaveBtn', busy,
                busy ? t('genai.saving') : t('genai.save_settings'));
            this.renderStatus();
        },

        openInitialTab: function () {
            var tab = String(window.location.hash || '')
                .replace(/^#/, '').toLowerCase();
            if (tab === 'assistant'
                    || tab === 'provider' || tab === 'privacy') {
                if (typeof window.OT_setActiveTab === 'function') {
                    window.OT_setActiveTab(tab);
                }
            }
        },

        pad2: function (value) {
            value = Number(value || 0);
            return value < 10 ? '0' + value : String(value);
        },

        responseLanguage: function () {
            return window.BYD && BYD.i18n && BYD.i18n.getLang
                ? String(BYD.i18n.getLang() || 'en') : 'en';
        },

        send: function () {
            if (this.busy) {
                if (this.streamMessage) {
                    this.cancelChat(t('genai.stopped'), false);
                }
                return;
            }
            var input = document.getElementById('genAiInput');
            var text = input ? input.value.trim() : '';
            if (!text || !this.status || !this.status.enabled
                    || !this.status.configured) return;

            this.messages.push({ role: 'user', content: text });
            if (this.messages.length > 20) this.messages = this.messages.slice(-20);
            if (input) {
                input.value = '';
                input.style.height = 'auto';
            }
            this.busy = true;
            var mode = this.selectedMode || 'general';
            var requestId = this.newRequestId();
            if (mode === 'automation_draft') {
                this.renderMessages(true);
                this.renderStatus();
                this.sendDraftRequest(mode, requestId);
                return;
            }

            var requestMessages = this.messages.slice(0);
            var reply = {
                role: 'assistant',
                content: '',
                mode: mode,
                pending: true
            };
            this.messages.push(reply);
            this.streamMessage = reply;
            this.chatCancelled = false;
            this.renderMessages(false);
            this.renderStatus();
            this.openChatStream(requestMessages, mode, requestId);
        },

        sendDraftRequest: function (mode, requestId) {
            var self = this;
            this.request('/api/genai/automation/draft', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    messages: this.messages,
                    mode: mode,
                    requestId: requestId,
                    language: this.responseLanguage()
                })
            }).then(function (response) {
                self.messages.push({
                    role: 'assistant',
                    content: response.text || '',
                    mode: response.mode || mode,
                    draft: response.draft || null,
                    communityResults: response.communityResults || null,
                    usage: response.usage || null
                });
                self.selectedMode = response.needsInput
                    ? 'automation_draft' : 'general';
                if (self.messages.length > 20) self.messages = self.messages.slice(-20);
            }).catch(function (error) {
                self.selectedMode = 'automation_draft';
                self.messages.push({
                    role: 'assistant',
                    content: t('genai.could_not_complete', {error: error.message})
                });
            }).then(function () {
                self.busy = false;
                self.renderMessages(false);
                self.renderStatus();
                self.loadStatus();
            });
        },

        openChatStream: function (messages, mode, requestId) {
            var generation = ++this.chatGeneration;
            var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
            var url = protocol + '//' + location.host + '/ws/genai/chat';
            if (typeof BYDAuth !== 'undefined') {
                var token = BYDAuth.getToken();
                if (token) url += '?token=' + encodeURIComponent(token);
            }
            var socket;
            try {
                socket = new WebSocket(url);
            } catch (error) {
                this.fallbackChatRequest(
                    messages, mode, requestId, generation);
                return;
            }

            this.chatSocket = socket;
            var self = this;
            var started = false;
            var completed = false;
            socket.onopen = function () {
                if (generation !== self.chatGeneration) return;
                socket.send(JSON.stringify({
                    messages: messages,
                    mode: mode,
                    requestId: requestId,
                    language: self.responseLanguage()
                }));
            };
            socket.onmessage = function (event) {
                if (generation !== self.chatGeneration
                        || typeof event.data !== 'string') return;
                var message;
                try { message = JSON.parse(event.data); }
                catch (error) { return; }
                if (message.type === 'start') {
                    started = true;
                    if (self.streamMessage) {
                        self.streamMessage.mode = message.mode || mode;
                    }
                } else if (message.type === 'delta') {
                    started = true;
                    if (self.streamMessage) {
                        self.streamMessage.content +=
                            String(message.text || '');
                        self.scheduleStreamRender();
                    }
                } else if (message.type === 'done') {
                    completed = true;
                    self.finishChatSuccess(message, generation);
                } else if (message.type === 'error') {
                    completed = true;
                    self.finishChatFailure(
                        message.error || 'The request failed.',
                        generation);
                }
            };
            socket.onerror = function () {
                // onclose owns fallback/error handling so one failure is shown.
            };
            socket.onclose = function () {
                if (generation !== self.chatGeneration || completed
                        || self.chatCancelled) return;
                if (!started) {
                    self.fallbackChatRequest(
                        messages, mode, requestId, generation);
                } else {
                    self.finishChatFailure(
                        'The streaming connection closed before completion.',
                        generation);
                }
            };
        },

        fallbackChatRequest: function (
                messages, mode, requestId, generation) {
            if (generation !== this.chatGeneration) return;
            var self = this;
            this.chatSocket = null;
            this.request('/api/genai/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    messages: messages,
                    mode: mode,
                    requestId: requestId,
                    language: this.responseLanguage()
                })
            }).then(function (response) {
                self.finishChatSuccess(response, generation);
            }).catch(function (error) {
                self.finishChatFailure(error.message, generation);
            });
        },

        finishChatSuccess: function (response, generation) {
            if (generation !== this.chatGeneration) return;
            ++this.chatGeneration;
            this.chatSocket = null;
            var reply = this.streamMessage;
            if (reply) {
                reply.pending = false;
                reply.content = response.text || reply.content || '';
                reply.mode = response.mode || reply.mode || 'general';
                reply.draft = response.draft || null;
                reply.communityResults =
                    response.communityResults || null;
                reply.actionProposal =
                    response.actionProposal || null;
                reply.usage = response.usage || null;
            }
            this.selectedMode = response.needsInput
                ? (response.mode
                    || (reply && reply.mode) || 'general')
                : 'general';
            this.streamMessage = null;
            this.busy = false;
            if (this.messages.length > 20) {
                this.messages = this.messages.slice(-20);
            }
            this.renderMessages(false);
            this.renderStatus();
            this.loadStatus();
        },

        finishChatFailure: function (reason, generation) {
            if (generation !== this.chatGeneration) return;
            ++this.chatGeneration;
            this.chatSocket = null;
            var reply = this.streamMessage;
            if (reply) {
                reply.pending = false;
                if (!reply.content) {
                    reply.content =
                        'I could not complete that request: ' + reason;
                }
            }
            this.streamMessage = null;
            this.busy = false;
            this.renderMessages(false);
            this.renderStatus();
            this.loadStatus();
        },

        cancelChat: function (reason, silent) {
            if (!this.busy || !this.streamMessage) return;
            this.chatCancelled = true;
            ++this.chatGeneration;
            var socket = this.chatSocket;
            this.chatSocket = null;
            if (socket) {
                try {
                    if (socket.readyState === WebSocket.OPEN) {
                        socket.send(JSON.stringify({ type: 'cancel' }));
                    }
                    socket.close(1000, reason || 'Stopped');
                } catch (error) {}
            }
            var reply = this.streamMessage;
            reply.pending = false;
            if (!reply.content) {
                var index = this.messages.indexOf(reply);
                if (index >= 0) this.messages.splice(index, 1);
            }
            this.streamMessage = null;
            this.busy = false;
            this.renderMessages(false);
            this.renderStatus();
            if (!silent) this.toast(reason || t('genai.stopped'), 'info');
        },

        scheduleStreamRender: function () {
            if (this.streamRenderPending) return;
            this.streamRenderPending = true;
            var self = this;
            var schedule = window.requestAnimationFrame
                || function (callback) {
                    return window.setTimeout(callback, 33);
                };
            schedule(function () {
                self.streamRenderPending = false;
                if (self.busy && self.streamMessage) {
                    self.renderMessages(false);
                }
            });
        },

        newRequestId: function () {
            return 'req-' + Date.now().toString(36) + '-'
                + Math.random().toString(36).slice(2, 12);
        },

        renderMessages: function (pending) {
            var host = document.getElementById('genAiConversation');
            if (!host) return;
            while (host.firstChild) host.removeChild(host.firstChild);
            if (!this.messages.length && !pending) {
                var empty = document.createElement('div');
                empty.className = 'ai-empty';
                empty.id = 'genAiEmpty';
                var icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                icon.setAttribute('viewBox', '0 0 24 24');
                icon.setAttribute('fill', 'none');
                icon.setAttribute('stroke', 'currentColor');
                icon.setAttribute('stroke-width', '1.7');
                var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                path.setAttribute('d', 'M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z');
                icon.appendChild(path);
                var title = document.createElement('strong');
                title.textContent = t('genai.empty_title');
                var description = document.createElement('span');
                description.textContent = t('genai.empty_desc');
                empty.appendChild(icon);
                empty.appendChild(title);
                empty.appendChild(description);
                host.appendChild(empty);
                return;
            }

            for (var i = 0; i < this.messages.length; i++) {
                this.appendMessage(host, this.messages[i], false);
            }
            if (pending) this.appendMessage(host, {
                role: 'assistant',
                content: t('genai.thinking_ellipsis')
            }, true);
            host.scrollTop = host.scrollHeight;
        },

        appendMessage: function (host, message, pending) {
            pending = !!pending || !!(message && message.pending);
            var role = message && message.role === 'user' ? 'user' : 'assistant';
            var row = document.createElement('div');
            row.className = 'ai-message ' + role +
                (pending ? ' pending' : '');
            var bubble = document.createElement('div');
            bubble.className = 'ai-bubble';
            var content = message && message.content
                ? String(message.content) : '';
            if (!pending && role === 'assistant') {
                bubble.className += ' ai-rich-text';
                if (message.mode === 'community_search') {
                    var resultCount = message.communityResults
                        && message.communityResults.length
                        ? message.communityResults.length : 0;
                    content = resultCount
                        ? (resultCount === 1
                            ? t('genai.community_found_one')
                            : t('genai.community_found', { count: resultCount }))
                        : t('genai.no_community');
                }
                this.renderRichText(bubble, content);
            } else {
                bubble.textContent = content;
            }
            if (!pending && role === 'assistant' && message.mode &&
                    message.mode !== 'general') {
                var grounding = document.createElement('div');
                grounding.className = 'ai-grounding-label';
                grounding.textContent = this.modeLabel(message.mode);
                bubble.insertBefore(grounding, bubble.firstChild);
            }
            if (!pending && message && message.draft) {
                bubble.appendChild(this.automationDraftCard(message.draft));
            }
            if (!pending && message && message.communityResults &&
                    message.communityResults.length) {
                bubble.appendChild(this.communityCards(message.communityResults));
            }
            if (!pending && message && message.actionProposal) {
                bubble.appendChild(this.actionProposalCard(
                    message.actionProposal));
            }
            if (!pending && message && message.contextRequest) {
                bubble.appendChild(this.contextRequestCard(
                    message.contextRequest));
            }
            if (!pending && role === 'assistant'
                    && message && message.usage) {
                var usageText = this.usageLabel(message.usage);
                if (usageText) {
                    var usage = document.createElement('div');
                    usage.className = 'ai-result-meta';
                    usage.textContent = usageText;
                    bubble.appendChild(usage);
                }
            }
            row.appendChild(bubble);
            host.appendChild(row);
        },

        renderRichText: function (container, value) {
            var lines = String(value || '')
                .replace(/\r\n?/g, '\n').split('\n');
            var paragraph = null;
            var list = null;
            var listType = '';
            for (var i = 0; i < lines.length; i++) {
                var line = lines[i].replace(/^\s+|\s+$/g, '');
                if (!line) {
                    paragraph = null;
                    list = null;
                    listType = '';
                    continue;
                }

                var heading = /^#{1,3}\s+(.+)$/.exec(line);
                var ordered = /^\d+[.)]\s+(.+)$/.exec(line);
                var bullet = /^[-*]\s+(.+)$/.exec(line);
                var quote = /^>\s*(.*)$/.exec(line);
                if (heading) {
                    paragraph = null;
                    list = null;
                    listType = '';
                    var headingNode = document.createElement('div');
                    headingNode.className = 'ai-rich-heading';
                    this.appendInlineText(headingNode, heading[1]);
                    container.appendChild(headingNode);
                } else if (ordered || bullet) {
                    paragraph = null;
                    var nextListType = ordered ? 'ol' : 'ul';
                    if (!list || listType !== nextListType) {
                        list = document.createElement(nextListType);
                        listType = nextListType;
                        container.appendChild(list);
                    }
                    var item = document.createElement('li');
                    this.appendInlineText(item, (ordered || bullet)[1]);
                    list.appendChild(item);
                } else if (quote) {
                    paragraph = null;
                    list = null;
                    listType = '';
                    var quoteNode = document.createElement('div');
                    quoteNode.className = 'ai-rich-quote';
                    this.appendInlineText(quoteNode, quote[1]);
                    container.appendChild(quoteNode);
                } else {
                    list = null;
                    listType = '';
                    if (!paragraph) {
                        paragraph = document.createElement('p');
                        container.appendChild(paragraph);
                    } else {
                        paragraph.appendChild(document.createTextNode(' '));
                    }
                    this.appendInlineText(paragraph, line);
                }
            }
        },

        appendInlineText: function (container, value) {
            var text = String(value || '');
            var pattern = /(\*\*[^*\n]+\*\*|`[^`\n]+`)/g;
            var start = 0;
            var match;
            while ((match = pattern.exec(text)) !== null) {
                if (match.index > start) {
                    container.appendChild(document.createTextNode(
                        text.slice(start, match.index)));
                }
                var token = match[0];
                var node = document.createElement(
                    token.slice(0, 2) === '**' ? 'strong' : 'code');
                node.textContent = token.slice(
                    token.slice(0, 2) === '**' ? 2 : 1,
                    token.slice(0, 2) === '**' ? -2 : -1);
                container.appendChild(node);
                start = match.index + token.length;
            }
            if (start < text.length) {
                container.appendChild(document.createTextNode(
                    text.slice(start)));
            }
        },

        actionProposalCard: function (proposal) {
            var self = this;
            var type = String(proposal.type || '');
            var card = document.createElement('div');
            card.className = 'ai-result-card ai-action-card';

            var title = document.createElement('strong');
            if (type === 'climate_temperature') {
                title.textContent = t('genai.set_cabin_temp');
            } else if (type === 'sunshade') {
                title.textContent =
                    proposal.operation === 'open'
                        ? t('genai.open_sunshade') : t('genai.close_sunshade');
            } else {
                title.textContent = t('genai.run_automation');
            }
            card.appendChild(title);

            var detail = document.createElement('div');
            detail.className = 'ai-result-detail';
            if (type === 'climate_temperature') {
                var zone = Number(proposal.zone);
                var zoneName = zone === 1
                    ? 'driver zone' : (zone === 2
                        ? 'passenger zone' : 'both zones');
                detail.textContent = Number(proposal.temperatureC)
                    + ' °C · ' + zoneName;
            } else if (type === 'sunshade') {
                detail.textContent =
                    'Sunshade · ' + String(proposal.operation || '');
            } else {
                detail.textContent =
                    proposal.automationName || t('genai.saved_automation');
            }
            card.appendChild(detail);

            var safety = document.createElement('div');
            safety.className = 'ai-draft-safety';
            safety.textContent = proposal.executed
                ? (proposal.result || t('genai.action_accepted'))
                : (proposal.error
                    ? proposal.error
                    : t('genai.confirm_hint'));
            card.appendChild(safety);

            if (!proposal.executed) {
                var controls = document.createElement('div');
                controls.className = 'ai-result-actions';
                var confirm = document.createElement('button');
                confirm.type = 'button';
                confirm.className = 'btn btn-primary';
                confirm.disabled = !!proposal.executing;
                confirm.textContent = proposal.executing
                    ? t('genai.running') : t('genai.confirm_run');
                confirm.addEventListener('click', function () {
                    self.executeAction(proposal);
                });
                controls.appendChild(confirm);
                card.appendChild(controls);
            }
            return card;
        },

        executeAction: function (proposal) {
            if (!proposal || proposal.executing || proposal.executed) return;
            proposal.executing = true;
            proposal.error = '';
            this.renderMessages(false);
            var self = this;
            this.request('/api/genai/action/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: proposal })
            }).then(function (response) {
                proposal.executed = true;
                proposal.result = response.message ||
                    (proposal.type === 'run_automation'
                        ? t('genai.automation_accepted')
                        : t('genai.vehicle_cmd_accepted'));
                self.announceVoiceActionResult(proposal, true);
                self.toast(proposal.result, 'success');
            }).catch(function (error) {
                proposal.error =
                    t('genai.could_not_run', { error: error.message });
                self.announceVoiceActionResult(proposal, false);
                self.toast(error.message, 'error');
            }).then(function () {
                proposal.executing = false;
                self.renderMessages(false);
            });
        },

        announceVoiceActionResult: function (proposal, success) {
            var socket = this.voiceSocket;
            if (!this.voiceActive || !socket
                    || socket.readyState !== WebSocket.OPEN) return;
            try {
                socket.send(JSON.stringify({
                    type: 'action_result',
                    actionType: String(proposal.type || ''),
                    success: !!success
                }));
            } catch (error) {}
        },

        contextRequestCard: function (request) {
            var self = this;
            var card = document.createElement('div');
            card.className = 'ai-result-card ai-context-card';
            var title = document.createElement('strong');
            title.textContent = t('genai.share_once');
            card.appendChild(title);

            var detail = document.createElement('div');
            detail.className = 'ai-result-detail';
            detail.textContent = this.modeLabel(request.mode)
                + (request.query
                    ? ' · ' + t('genai.voice_request', {query: request.query}) : '');
            card.appendChild(detail);

            var safety = document.createElement('div');
            safety.className = 'ai-draft-safety';
            safety.textContent = request.resolved
                ? (request.approved
                    ? t('genai.shared_once_ok')
                    : t('genai.no_vehicle_shared'))
                : t('genai.share_until_approve');
            card.appendChild(safety);

            if (!request.resolved) {
                var controls = document.createElement('div');
                controls.className = 'ai-result-actions';
                var share = document.createElement('button');
                share.type = 'button';
                share.className = 'btn btn-primary';
                share.disabled = !!request.responding
                    || !this.voiceActive;
                share.textContent = request.responding
                    ? t('genai.sharing') : t('genai.share_once_btn');
                share.addEventListener('click', function () {
                    self.respondVoiceContext(request, true);
                });
                controls.appendChild(share);

                var deny = document.createElement('button');
                deny.type = 'button';
                deny.className = 'btn btn-secondary';
                deny.disabled = !!request.responding
                    || !this.voiceActive;
                deny.textContent = this.voiceActive
                    ? t('genai.not_now') : t('genai.voice_session_ended');
                deny.addEventListener('click', function () {
                    self.respondVoiceContext(request, false);
                });
                controls.appendChild(deny);
                card.appendChild(controls);
            }
            return card;
        },

        respondVoiceContext: function (request, approved) {
            var socket = this.voiceSocket;
            if (!request || request.responding || request.resolved
                    || !this.voiceActive || !socket
                    || socket.readyState !== WebSocket.OPEN) return;
            request.responding = true;
            this.clearVoicePlayback(true);
            this.renderMessages(false);
            try {
                socket.send(JSON.stringify({
                    type: approved
                        ? 'confirm_context' : 'deny_context',
                    token: request.token
                }));
            } catch (error) {
                request.responding = false;
                this.renderMessages(false);
            }
        },

        automationDraftCard: function (draft) {
            var self = this;
            var automation = draft.automation || {};
            var card = document.createElement('div');
            card.className = 'ai-result-card ai-draft-card';

            var title = document.createElement('strong');
            title.textContent = automation.name || t('genai.draft_title');
            card.appendChild(title);

            var counts = document.createElement('div');
            counts.className = 'ai-result-meta';
            counts.textContent = t('genai.draft_counts', {
                triggers: automation.triggers ? automation.triggers.length : 0,
                conditions: automation.conditions ? automation.conditions.length : 0,
                actions: automation.actions ? automation.actions.length : 0
            });
            card.appendChild(counts);

            var actions = automation.actions || [];
            if (actions.length) {
                var actionText = document.createElement('div');
                actionText.className = 'ai-result-detail';
                var names = [];
                for (var i = 0; i < actions.length && i < 6; i++) {
                    names.push(actions[i].type || 'action');
                }
                actionText.textContent = t('genai.actions_prefix', { list: names.join(', ') });
                card.appendChild(actionText);
            }

            var safety = document.createElement('div');
            safety.className = 'ai-draft-safety';
            safety.textContent = draft.saved
                ? t('genai.draft_saved_manual')
                : t('genai.draft_not_active');
            card.appendChild(safety);

            var controls = document.createElement('div');
            controls.className = 'ai-result-actions';
            if (!draft.saved) {
                var save = document.createElement('button');
                save.type = 'button';
                save.className = 'btn btn-primary';
                save.textContent = t('genai.save_manual_draft');
                save.addEventListener('click', function () {
                    self.saveAutomationDraft(draft, save);
                });
                controls.appendChild(save);
            }

            var open = document.createElement('button');
            open.type = 'button';
            open.className = 'btn btn-secondary';
            open.textContent = t('genai.open_automations');
            open.addEventListener('click', function () {
                window.location.href = '/automations';
            });
            controls.appendChild(open);
            card.appendChild(controls);
            return card;
        },

        saveAutomationDraft: function (draft, button) {
            if (!draft || !draft.automation) return;
            if (!window.confirm(
                    t('genai.confirm_save_draft'))) {
                return;
            }
            var self = this;
            button.disabled = true;
            button.textContent = t('genai.saving');
            this.request('/api/genai/automation/commit', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ automation: draft.automation })
            }).then(function (result) {
                draft.saved = true;
                draft.id = result.id || '';
                button.textContent = t('genai.saved_manual_only');
                self.toast(result.message || t('genai.draft_saved'), 'success');
            }).catch(function (error) {
                button.disabled = false;
                button.textContent = t('genai.save_manual_draft');
                self.toast(error.message, 'error');
            });
        },

        communityCards: function (items) {
            var self = this;
            var wrap = document.createElement('div');
            wrap.className = 'ai-community-results';
            var count = Math.min(items.length, 5);
            for (var i = 0; i < count; i++) {
                (function (item) {
                    var card = document.createElement('div');
                    card.className = 'ai-result-card';
                    var title = document.createElement('strong');
                    title.textContent = item.name || 'Community automation';
                    card.appendChild(title);
                    if (item.description) {
                        var description = document.createElement('div');
                        description.className = 'ai-result-detail';
                        description.textContent = item.description;
                        card.appendChild(description);
                    }
                    var meta = document.createElement('div');
                    meta.className = 'ai-result-meta';
                    var rating = Number(item.ratingAvg || 0);
                    meta.textContent = (isFinite(rating) && rating > 0
                        ? rating.toFixed(1) + '★ · ' : '')
                        + (item.downloadCount || 0) + ' downloads';
                    card.appendChild(meta);
                    var controls = document.createElement('div');
                    controls.className = 'ai-result-actions';
                    var add = document.createElement('button');
                    add.type = 'button';
                    add.className = 'btn btn-secondary';
                    add.textContent = t('genai.add_disabled');
                    add.addEventListener('click', function () {
                        self.importCommunityAutomation(item, add);
                    });
                    controls.appendChild(add);
                    card.appendChild(controls);
                    wrap.appendChild(card);
                }(items[i]));
            }
            return wrap;
        },

        importCommunityAutomation: function (item, button) {
            if (!item || !item.id) return;
            if (!window.confirm(
                    t('genai.confirm_import', {
                        name: item.name || t('genai.this_automation')
                    }))) return;
            var self = this;
            button.disabled = true;
            button.textContent = t('genai.adding');
            this.request('/api/community/import/' + encodeURIComponent(item.id), {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: '{}'
            }).then(function () {
                button.textContent = t('genai.added_disabled');
                self.toast(t('genai.community_added'), 'success');
            }).catch(function (error) {
                button.disabled = false;
                button.textContent = t('genai.add_disabled');
                self.toast(error.message, 'error');
            });
        },

        modeLabel: function (mode) {
            var keys = {
                overview: 'genai.mode_overview',
                current_vehicle: 'genai.mode_current_vehicle',
                latest_trip: 'genai.mode_latest_trip',
                trip_comparison: 'genai.mode_trip_comparison',
                recent_events: 'genai.mode_recent_events',
                roadsense: 'genai.mode_roadsense',
                charging: 'genai.mode_charging',
                diagnostics: 'genai.mode_diagnostics',
                diagnostic_logs: 'genai.mode_diagnostic_logs',
                automation_diagnostics: 'genai.mode_automation_diagnostics',
                automation_draft: 'genai.mode_automation_draft',
                community_search: 'genai.mode_community_search',
                vehicle_action: 'genai.mode_vehicle_action'
            };
            return t(keys[mode] || 'genai.assistant');
        },

        usageLabel: function (usage) {
            if (!usage) return '';
            var input = Number(
                usage.input_tokens != null
                    ? usage.input_tokens
                    : (usage.prompt_tokens != null
                        ? usage.prompt_tokens
                        : (usage.inputTokenCount != null
                            ? usage.inputTokenCount
                            : usage.promptTokenCount)));
            var output = Number(
                usage.output_tokens != null
                    ? usage.output_tokens
                    : (usage.completion_tokens != null
                        ? usage.completion_tokens
                        : (usage.outputTokenCount != null
                            ? usage.outputTokenCount
                            : usage.candidatesTokenCount)));
            var total = Number(
                usage.total_tokens != null
                    ? usage.total_tokens
                    : usage.totalTokenCount);
            var parts = [];
            if (isFinite(input) && input >= 0) {
                parts.push('Input ' + Math.round(input));
            }
            if (isFinite(output) && output >= 0) {
                parts.push('Output ' + Math.round(output));
            }
            if (!parts.length && isFinite(total) && total >= 0) {
                parts.push('Total ' + Math.round(total));
            }
            return parts.length
                ? parts.join(' · ') + ' tokens' : '';
        },

        setButtonBusy: function (id, busy, text) {
            var button = document.getElementById(id);
            if (!button) return;
            button.disabled = busy;
            button.textContent = text;
        },

        setDisabled: function (id, disabled) {
            var element = document.getElementById(id);
            if (element) element.disabled = !!disabled;
        },

        providerLabel: function (provider) {
            if (provider === 'anthropic') return 'Anthropic';
            if (provider === 'gemini') return 'Google Gemini';
            if (provider === 'openai_compatible') return 'OpenAI-compatible';
            return 'OpenAI';
        },

        value: function (id) {
            var element = document.getElementById(id);
            return element ? String(element.value || '') : '';
        },

        setValue: function (id, value) {
            var element = document.getElementById(id);
            if (element) element.value = value == null ? '' : value;
        },

        setText: function (id, value) {
            var element = document.getElementById(id);
            if (element) element.textContent = value == null ? '' : value;
        },

        toast: function (message, type) {
            if (window.BYD && BYD.core && BYD.core.toast) BYD.core.toast(message, type);
        }
    };

    window.GenAI = GenAI;
}(window, document));
