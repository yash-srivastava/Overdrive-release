/* Dedicated remote voice/message page. Talk resources are press-scoped;
 * cabin-listener resources are explicit-toggle scoped. */
var CommunicatePage = {
    STATUS_POLL_MS: 5000,
    SETUP_STATUS_POLL_MS: 15000,
    INACTIVE_STATUS_POLL_MS: 30000,
    TARGET_SAMPLE_RATE: 16000,
    MAX_SESSION_MS: 30000,
    AUDIO_INACTIVITY_MS: 3000,
    MAX_CAPTURE_AGE_MS: 250,
    WORKLET_MODULE_URL: '/shared/communicate-worklet.js?v=1',

    tt: function (key, fallback) {
        var v = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key) : null;
        if (v && v !== key) return v;
        return fallback || key;
    },

    status: null,
    statusTimer: null,
    statusGeneration: 0,
    tickTimer: null,
    stream: null,
    audioContext: null,
    sourceNode: null,
    processorNode: null,
    silentGain: null,
    captureMode: '',
    audioClockOffsetMs: 0,
    staleCaptureFrames: 0,
    socket: null,
    activePointerId: null,
    pressing: false,
    live: false,
    stopping: false,
    generation: 0,
    startedAt: 0,
    lastAudioAt: 0,
    lastMeterAt: 0,
    outputLevel: 70,
    persistedOutputLevel: 70,
    outputOverrideEnabled: false,
    persistedOutputOverrideEnabled: false,
    outputSettingsReady: false,
    outputLoading: false,
    outputSaving: false,
    messageKind: 'toast',
    messagePending: false,
    activeMobilePanel: 'talk',
    listenerLastState: 'idle',

    init: function () {
        this.bindCabinListener();
        this.bindTalk();
        this.bindOutputLevel();
        this.bindMessage();
        this.bindMobileTabs();
        this.bindLifecycle();
        this.setTalkState('disabled', this.tt('communicate.checking_car', 'Checking car...'), '', '');
        this.loadOutputSettings();
        this.startStatusPolling(true);
    },

    bindCabinListener: function () {
        var self = this;
        var button = document.getElementById('cabinListenerToggle');
        if (button) {
            button.addEventListener('click', function () {
                var current = CabinAudio.getState();
                if (!current.active
                        && (!self.status || !self.status.listenerReady)) {
                    return;
                }
                CabinAudio.toggle();
            });
        }
        CabinAudio.subscribe(function (status) {
            self.renderCabinListener(status);
        });
    },

    renderCabinListener: function (listener) {
        var button = document.getElementById('cabinListenerToggle');
        if (!button) return;
        var active = listener.state === 'live';
        var paused = listener.state === 'paused';
        var connecting = listener.state === 'connecting';
        var owned = active || paused || connecting;
        var ready = !!(this.status && this.status.listenerReady);
        button.disabled = !owned && (!ready || this.pressing || this.live);
        button.classList.toggle('is-active', active);
        button.classList.toggle('is-paused', paused);
        button.classList.toggle('is-connecting', connecting);
        button.setAttribute('aria-pressed', owned ? 'true' : 'false');
        var label = button.querySelector('span');
        var key = active
            ? 'communicate.stop_listening'
            : paused
                ? 'communicate.listening_paused'
                : connecting
                    ? 'communicate.listener_starting'
                    : 'communicate.listen';
        var translated = BYD.i18n.t(key);
        if (label && translated && translated !== key) {
            label.textContent = translated;
        }
        button.title = owned
            ? (translated || '')
            : (this.status && this.status.listenerReason) || translated || '';
        if (listener.state === 'error'
                && this.listenerLastState !== 'error'
                && BYD.utils
                && BYD.utils.toast) {
            BYD.utils.toast(
                listener.reason || BYD.i18n.t('communicate.listener_unavailable'),
                'error');
        }
        this.listenerLastState = listener.state;
    },

    bindTalk: function () {
        var self = this;
        var button = document.getElementById('pttButton');
        if (!button) return;
        button.addEventListener('contextmenu', function (event) {
            event.preventDefault();
        });

        if (window.PointerEvent) {
            button.addEventListener('pointerdown', function (event) {
                self.onPressStart(event);
            });
            window.addEventListener('pointerup', function (event) {
                self.onPressEnd(event);
            });
            window.addEventListener('pointercancel', function (event) {
                self.onPressEnd(event, 'Touch cancelled');
            });
            button.addEventListener('lostpointercapture', function (event) {
                if (self.activePointerId === event.pointerId
                        && (self.pressing || self.live)) {
                    self.stopTalk('Touch cancelled');
                }
            });
        } else {
            button.addEventListener('touchstart', function (event) {
                self.onPressStart(event);
            }, { passive: false });
            window.addEventListener('touchend', function (event) {
                self.onPressEnd(event);
            }, { passive: false });
            window.addEventListener('touchcancel', function (event) {
                self.onPressEnd(event, 'Touch cancelled');
            }, { passive: false });
            button.addEventListener('mousedown', function (event) {
                self.onPressStart(event);
            });
            window.addEventListener('mouseup', function (event) {
                self.onPressEnd(event);
            });
        }
    },

    bindMessage: function () {
        var self = this;
        var kind = document.getElementById('messageKind');
        if (kind) {
            kind.addEventListener('click', function (event) {
                var button = event.target;
                while (button && button !== kind && !button.getAttribute('data-value')) {
                    button = button.parentNode;
                }
                if (!button || button === kind) return;
                self.messageKind = button.getAttribute('data-value') || 'toast';
                var buttons = kind.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    var selected = buttons[i] === button;
                    buttons[i].classList.toggle('is-selected', selected);
                    buttons[i].setAttribute('aria-checked', selected ? 'true' : 'false');
                }
            });
        }

        var text = document.getElementById('messageText');
        if (text) {
            text.addEventListener('input', function () {
                self.updateMessageControls();
            });
        }
        var form = document.getElementById('messageForm');
        if (form) {
            form.addEventListener('submit', function (event) {
                event.preventDefault();
                self.sendMessage();
            });
        }
        this.updateMessageControls();
    },

    bindOutputLevel: function () {
        var self = this;
        var slider = document.getElementById('remoteOutputLevel');
        var override = document.getElementById('remoteOutputOverride');
        if (!slider) return;
        if (override) {
            override.addEventListener('change', function () {
                self.outputOverrideEnabled = override.checked;
                self.renderOutputLevel();
                self.saveOutputLevel();
            });
        }
        slider.addEventListener('input', function () {
            self.outputLevel = self.clampOutputLevel(
                parseInt(slider.value, 10));
            self.renderOutputLevel();
        });
        slider.addEventListener('change', function () {
            self.saveOutputLevel();
        });
        this.renderOutputLevel();
        this.updateOutputControlState();
    },

    loadOutputSettings: function () {
        if (this.outputLoading || document.hidden) return;
        this.outputLoading = true;
        this.updateOutputControlState();
        var self = this;
        var options = { cache: 'no-store' };
        var request = typeof BYDAuth !== 'undefined'
            ? BYDAuth.fetch('/api/communicate/settings', options)
            : fetch('/api/communicate/settings', options);
        request.then(function (response) {
            return response.json().then(function (data) {
                return { ok: response.ok, data: data };
            });
        }).then(function (result) {
            var data = result.data || {};
            if (!result.ok || typeof data.outputLevel !== 'number') {
                throw new Error(data.reason || 'Output settings unavailable');
            }
            self.outputLevel = self.clampOutputLevel(data.outputLevel);
            self.persistedOutputLevel = self.outputLevel;
            self.outputOverrideEnabled =
                data.outputLevelOverrideEnabled === true;
            self.persistedOutputOverrideEnabled =
                self.outputOverrideEnabled;
            self.outputSettingsReady = true;
            self.renderOutputLevel();
            self.renderOutputStatus();
        }).catch(function () {
            self.outputSettingsReady = false;
            self.setOutputStatus(false, self.tt('communicate.output_unavailable', 'Output level unavailable'));
        }).then(function () {
            self.outputLoading = false;
            self.updateOutputControlState();
        });
    },

    saveOutputLevel: function () {
        if (!this.outputSettingsReady || this.outputSaving) return;
        var level = this.clampOutputLevel(this.outputLevel);
        if (level === this.persistedOutputLevel
                && this.outputOverrideEnabled
                    === this.persistedOutputOverrideEnabled) {
            this.renderOutputStatus();
            return;
        }

        this.outputSaving = true;
        this.setOutputStatus(null, this.tt('communicate.saving', 'Saving...'));
        this.updateOutputControlState();
        if (this.status) this.renderStatus();

        var self = this;
        var options = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                outputLevel: level,
                outputLevelOverrideEnabled: this.outputOverrideEnabled
            })
        };
        var request = typeof BYDAuth !== 'undefined'
            ? BYDAuth.fetch('/api/communicate/settings', options)
            : fetch('/api/communicate/settings', options);
        request.then(function (response) {
            return response.json().then(function (data) {
                return { ok: response.ok, data: data };
            });
        }).then(function (result) {
            var data = result.data || {};
            if (!result.ok || typeof data.outputLevel !== 'number') {
                throw new Error(data.reason || 'Could not save output level');
            }
            self.outputLevel = self.clampOutputLevel(data.outputLevel);
            self.persistedOutputLevel = self.outputLevel;
            self.outputOverrideEnabled =
                data.outputLevelOverrideEnabled === true;
            self.persistedOutputOverrideEnabled =
                self.outputOverrideEnabled;
            self.renderOutputLevel();
            self.renderOutputStatus(true);
        }).catch(function () {
            self.outputLevel = self.persistedOutputLevel;
            self.outputOverrideEnabled =
                self.persistedOutputOverrideEnabled;
            self.renderOutputLevel();
            self.setOutputStatus(false, self.tt('communicate.could_not_save', 'Could not save'));
        }).then(function () {
            self.outputSaving = false;
            self.updateOutputControlState();
            if (self.status) self.renderStatus();
        });
    },

    clampOutputLevel: function (value) {
        if (isNaN(value)) return 70;
        return Math.max(0, Math.min(100, value));
    },

    renderOutputLevel: function () {
        var slider = document.getElementById('remoteOutputLevel');
        var override = document.getElementById('remoteOutputOverride');
        var value = document.getElementById('remoteOutputValue');
        var control = document.querySelector('.talk-output-control');
        if (override) override.checked = this.outputOverrideEnabled;
        if (slider) slider.value = String(this.outputLevel);
        if (value) value.textContent = this.outputLevel + '%';
        if (control) {
            control.classList.toggle(
                'is-overridden', this.outputOverrideEnabled);
        }
    },

    renderOutputStatus: function (saved) {
        if (!this.outputOverrideEnabled) {
            this.setOutputStatus(null, this.tt('communicate.using_car_volume', 'Using selected car volume'));
        } else if (saved) {
            this.setOutputStatus(true, this.tt('communicate.saved', 'Saved'));
        } else {
            this.setOutputStatus(null, '');
        }
    },

    setOutputStatus: function (success, message) {
        var status = document.getElementById('remoteOutputStatus');
        if (!status) return;
        status.classList.toggle('is-success', success === true);
        status.classList.toggle('is-error', success === false);
        status.textContent = message || '';
    },

    updateOutputControlState: function () {
        var slider = document.getElementById('remoteOutputLevel');
        var override = document.getElementById('remoteOutputOverride');
        if (!slider || !override) return;
        var unreachable = this.status && this.status.reachable === false;
        var controlsDisabled = !this.outputSettingsReady
            || this.outputLoading
            || this.outputSaving
            || this.pressing
            || this.live
            || this.stopping
            || unreachable;
        override.disabled = controlsDisabled;
        slider.disabled = controlsDisabled || !this.outputOverrideEnabled;
    },

    bindMobileTabs: function () {
        var self = this;
        var talk = document.getElementById('talkTab');
        var message = document.getElementById('messageTab');
        if (talk) talk.addEventListener('click', function () {
            self.setMobilePanel('talk');
        });
        if (message) message.addEventListener('click', function () {
            self.setMobilePanel('message');
        });
    },

    bindLifecycle: function () {
        var self = this;
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) {
                self.stopTalk('Page hidden', true);
                CabinAudio.stop();
                self.stopStatusPolling();
            } else {
                self.loadOutputSettings();
                self.startStatusPolling(true);
            }
        });
        window.addEventListener('pagehide', function () {
            self.stopTalk('Page hidden', true);
            CabinAudio.stop();
            self.stopStatusPolling();
        });
        window.addEventListener('pageshow', function () {
            if (!document.hidden) {
                self.loadOutputSettings();
                self.startStatusPolling(true);
            }
        });
        window.addEventListener('beforeunload', function () {
            self.stopTalk('Navigation', true);
            CabinAudio.stop();
            self.stopStatusPolling();
        });
        document.addEventListener('click', function (event) {
            var node = event.target;
            while (node && node !== document.body && node.tagName !== 'A') {
                node = node.parentNode;
            }
            if (node && node.tagName === 'A' && node.getAttribute('href')) {
                self.stopTalk('Navigation', true);
                CabinAudio.stop();
            }
        }, true);
    },

    setMobilePanel: function (panel) {
        if (panel === this.activeMobilePanel) return;
        if (panel !== 'talk') this.stopTalk('Talk tab closed');
        this.activeMobilePanel = panel;
        var talkPanel = document.getElementById('talkPanel');
        var messagePanel = document.getElementById('messagePanel');
        var talkTab = document.getElementById('talkTab');
        var messageTab = document.getElementById('messageTab');
        var talkActive = panel === 'talk';
        if (talkPanel) talkPanel.classList.toggle('is-mobile-active', talkActive);
        if (messagePanel) messagePanel.classList.toggle('is-mobile-active', !talkActive);
        if (talkTab) {
            talkTab.classList.toggle('is-active', talkActive);
            talkTab.setAttribute('aria-selected', talkActive ? 'true' : 'false');
        }
        if (messageTab) {
            messageTab.classList.toggle('is-active', !talkActive);
            messageTab.setAttribute('aria-selected', talkActive ? 'false' : 'true');
        }
    },

    startStatusPolling: function (immediate) {
        var self = this;
        this.stopStatusPolling();
        if (document.hidden || this.pressing || this.live || this.stopping) return;
        var generation = this.statusGeneration;
        var run = function () {
            self.statusTimer = null;
            if (!self.isStatusPollingCurrent(generation)) return;
            self.refreshStatus(generation).then(function () {
                if (self.isStatusPollingCurrent(generation)) {
                    self.statusTimer = window.setTimeout(
                        run, self.statusPollDelay());
                }
            });
        };
        if (immediate) run();
        else this.statusTimer = window.setTimeout(run, this.STATUS_POLL_MS);
    },

    stopStatusPolling: function () {
        this.statusGeneration += 1;
        if (this.statusTimer !== null) {
            window.clearTimeout(this.statusTimer);
            this.statusTimer = null;
        }
    },

    isStatusPollingCurrent: function (generation) {
        return generation === this.statusGeneration
            && !document.hidden
            && !this.pressing
            && !this.live
            && !this.stopping;
    },

    statusPollDelay: function () {
        var status = this.status || {};
        if (status.carState === 'off') return this.INACTIVE_STATUS_POLL_MS;

        var audioInactive = status.audioState === 'voice_disabled'
            || status.audioState === 'emergency_disabled';
        var listenerInactive = status.listenerState === 'listener_disabled'
            || status.listenerState === 'emergency_disabled';
        var messagesInactive = status.messageState === 'messages_disabled'
            || status.messageState === 'emergency_disabled';
        if (audioInactive && listenerInactive && messagesInactive) {
            return this.INACTIVE_STATUS_POLL_MS;
        }
        if (status.reachable === false
                || (!status.audioReady
                    && !status.listenerReady
                    && !status.messagesReady)) {
            return this.SETUP_STATUS_POLL_MS;
        }
        return this.STATUS_POLL_MS;
    },

    refreshStatus: function (generation) {
        var self = this;
        var options = { cache: 'no-store' };
        var request = typeof BYDAuth !== 'undefined'
            ? BYDAuth.fetch('/api/communicate/status', options)
            : fetch('/api/communicate/status', options);
        return request
            .then(function (response) {
                if (!response.ok) throw new Error('Car returned ' + response.status);
                return response.json();
            })
            .then(function (data) {
                if (typeof generation === 'number'
                        && generation !== self.statusGeneration) {
                    return;
                }
                self.status = data;
                self.renderStatus();
            })
            .catch(function () {
                if (typeof generation === 'number'
                        && generation !== self.statusGeneration) {
                    return;
                }
                self.status = {
                    reachable: false,
                    online: false,
                    carState: 'unreachable',
                    audioReady: false,
                    audioState: 'unreachable',
                    audioReason: self.tt('communicate.offline_reason', 'Car is offline or unreachable'),
                    audioGuidance:
                        self.tt('communicate.offline_guidance', 'Check that the car is powered on and its OverDrive connection is reachable.'),
                    listenerReady: false,
                    listenerState: 'unreachable',
                    listenerReason: self.tt('communicate.offline_reason', 'Car is offline or unreachable'),
                    listenerGuidance:
                        self.tt('communicate.offline_guidance', 'Check that the car is powered on and its OverDrive connection is reachable.'),
                    messagesReady: false,
                    messageState: 'unreachable',
                    messageReason: self.tt('communicate.offline_reason', 'Car is offline or unreachable'),
                    messageGuidance:
                        self.tt('communicate.offline_guidance', 'Check that the car is powered on and its OverDrive connection is reachable.')
                };
                self.renderStatus();
            });
    },

    renderStatus: function () {
        var status = this.status || {};
        var availability = document.getElementById('availability');
        var availabilityText = document.getElementById('availabilityText');
        var mobileDot = document.getElementById('mobileConnectionDot');
        var carState = status.carState
            || (status.reachable === false || !status.online
                ? 'unreachable' : 'unknown');
        var carOff = carState === 'off';
        var unreachable = carState === 'unreachable';
        var ready = !carOff && !unreachable && !!status.audioReady;
        var warning = !carOff && !unreachable && !ready;
        var carLabel = carState === 'unknown' ? 'Car reachable' : 'Car online';
        var text;
        if (carOff) {
            text = 'Car off \u2022 Communication unavailable';
        } else if (unreachable) {
            text = 'Car unreachable';
        } else if (ready) {
            text = carLabel + ' \u2022 Audio ready';
        } else if (status.audioState === 'busy') {
            text = carLabel + ' \u2022 Talk busy';
        } else {
            text = carLabel + ' \u2022 Setup required';
        }
        if (availability) {
            availability.classList.toggle('is-ready', ready);
            availability.classList.toggle('is-warning', warning);
            availability.classList.toggle('is-off', carOff);
            availability.classList.toggle('is-offline', unreachable);
        }
        if (availabilityText) availabilityText.textContent = text;
        if (mobileDot) {
            mobileDot.classList.toggle('is-ready', ready);
            mobileDot.classList.toggle('is-warning', warning);
            mobileDot.classList.toggle('is-off', carOff);
            mobileDot.classList.toggle('is-offline', unreachable);
        }

        if (!this.live && !this.stopping && !this.pressing) {
            if (ready && !this.outputSaving) {
                this.setTalkState('ready', this.tt('communicate.hold_to_talk', 'Hold to talk'), '', '');
            } else if (ready) {
                this.setTalkState(
                    'disabled', this.tt('communicate.saving_output', 'Saving output level...'), '', '');
            } else {
                var title = carOff
                    ? this.tt('communicate.car_off', 'Car is off')
                    : unreachable
                        ? this.tt('communicate.car_unreachable', 'Car unreachable')
                        : status.audioState === 'busy'
                            ? this.tt('communicate.talk_unavailable', 'Talk unavailable')
                            : this.tt('communicate.setup_required', 'Setup required');
                this.setTalkState(
                    'disabled',
                    title,
                    status.audioReason || this.tt('communicate.car_unavailable', 'Car is unavailable'),
                    status.audioGuidance || '');
            }
        }
        this.renderMessageAvailability(carState);
        this.renderCabinListener(CabinAudio.getState());
        this.updateMessageControls();
        this.updateOutputControlState();
    },

    renderMessageAvailability: function (carState) {
        var status = this.status || {};
        var banner = document.getElementById('messageReadiness');
        var title = document.getElementById('messageReadinessTitle');
        var reason = document.getElementById('messageReadinessReason');
        var guidance = document.getElementById('messageReadinessGuidance');
        if (!banner) return;

        var ready = !!status.messagesReady;
        banner.hidden = ready;
        if (ready) return;

        var carOff = carState === 'off';
        var unreachable = carState === 'unreachable';
        banner.classList.toggle('is-off', carOff);
        banner.classList.toggle('is-unreachable', unreachable);
        if (title) {
            title.textContent = carOff
                ? this.tt('communicate.car_off', 'Car is off')
                : unreachable ? this.tt('communicate.car_unreachable', 'Car unreachable') : this.tt('communicate.messages_unavailable', 'Messages unavailable');
        }
        if (reason) {
            reason.textContent =
                status.messageReason || this.tt('communicate.remote_messages_unavailable', 'Remote messages are unavailable');
        }
        if (guidance) {
            guidance.textContent = status.messageGuidance || '';
        }
    },

    onPressStart: function (event) {
        if (event) event.preventDefault();
        if (event && typeof event.button === 'number' && event.button !== 0) return;
        if (document.hidden || this.pressing || this.live || this.stopping) return;
        if (this.outputSaving) return;
        if (!this.status || !this.status.audioReady) return;
        CabinAudio.setLocalPaused(true);
        if (event && typeof event.pointerId === 'number') {
            this.activePointerId = event.pointerId;
            if (event.currentTarget
                    && typeof event.currentTarget.setPointerCapture
                        === 'function') {
                try {
                    event.currentTarget.setPointerCapture(event.pointerId);
                } catch (error) {}
            }
        }
        this.pressing = true;
        this.generation += 1;
        this.captureMode = '';
        this.audioClockOffsetMs = 0;
        this.staleCaptureFrames = 0;
        var generation = this.generation;
        this.stopStatusPolling();
        this.setTalkState('connecting', this.tt('communicate.connecting', 'Connecting...'), '', '');

        if (!this.prepareAudioContext(generation)) return;

        var media = navigator.mediaDevices;
        if (!media || !media.getUserMedia) {
            this.failTalk(this.tt('communicate.mic_https', 'Microphone access requires a supported HTTPS browser'));
            return;
        }

        var self = this;
        media.getUserMedia({
            audio: {
                channelCount: 1,
                echoCancellation: true,
                noiseSuppression: true,
                autoGainControl: true
            },
            video: false
        }).then(function (stream) {
            if (!self.isPressCurrent(generation)) {
                self.stopTracks(stream);
                return;
            }
            self.stream = stream;
            self.openTalkSocket(generation);
        }).catch(function (error) {
            if (!self.isPressCurrent(generation)) return;
            var reason = error && error.name === 'NotAllowedError'
                ? 'Microphone permission was denied'
                : 'Microphone is unavailable';
            self.failTalk(reason);
        });
    },

    prepareAudioContext: function (generation) {
        var AudioContextCtor = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextCtor) {
            this.failTalk(this.tt('communicate.mic_unsupported', 'This browser cannot process microphone audio'));
            return false;
        }
        try {
            var context = new AudioContextCtor();
            this.audioContext = context;
            if (context.state === 'suspended') {
                var self = this;
                var resume = context.resume();
                if (resume && typeof resume.catch === 'function') {
                    resume.catch(function () {
                        if (self.isPressCurrent(generation)
                                && self.audioContext === context) {
                            self.failTalk(self.tt('communicate.mic_activate_failed', 'Could not activate microphone processing'));
                        }
                    });
                }
            }
            return true;
        } catch (error) {
            this.failTalk(this.tt('communicate.mic_start_failed', 'Could not start microphone processing'));
            return false;
        }
    },

    onPressEnd: function (event, reason) {
        if (event && event.cancelable) event.preventDefault();
        if (event
                && this.activePointerId !== null
                && typeof event.pointerId === 'number'
                && event.pointerId !== this.activePointerId) {
            return;
        }
        if (!this.pressing && !this.live) return;
        this.stopTalk(reason || 'Released');
    },

    isPressCurrent: function (generation) {
        return this.pressing
            && !document.hidden
            && generation === this.generation
            && this.activeMobilePanel === 'talk';
    },

    openTalkSocket: function (generation) {
        var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        var url = protocol + '//' + location.host + '/ws/communicate';
        if (typeof BYDAuth !== 'undefined') {
            var token = BYDAuth.getToken();
            if (token) url += '?token=' + encodeURIComponent(token);
        }

        var self = this;
        var socket;
        try {
            socket = new WebSocket(url);
        } catch (error) {
            this.failTalk(this.tt('communicate.audio_open_failed', 'Could not open the car audio connection'));
            return;
        }
        this.socket = socket;
        socket.binaryType = 'arraybuffer';
        socket.onopen = function () {
            if (self.socket !== socket
                    || !self.isPressCurrent(generation)) {
                try { socket.close(1000, 'stale'); } catch (error) {}
                return;
            }
            socket.send(JSON.stringify({
                type: 'start',
                format: 'pcm_s16le',
                sampleRate: self.TARGET_SAMPLE_RATE
            }));
        };
        socket.onmessage = function (event) {
            if (self.socket !== socket
                    || generation !== self.generation) {
                return;
            }
            if (typeof event.data !== 'string') return;
            var message;
            try { message = JSON.parse(event.data); }
            catch (error) { return; }
            if (message.type === 'live') {
                if (!self.isPressCurrent(generation)) {
                    self.stopTalk('Released');
                    return;
                }
                self.startAudioPipeline(generation);
            } else if (message.type === 'failed') {
                self.failTalk(message.reason || self.tt('communicate.audio_receiver_failed', 'The car audio receiver failed'));
            } else if (message.type === 'stopped') {
                self.stopTalk(message.reason || 'Transmission ended');
            }
        };
        socket.onerror = function () {
            if (self.socket === socket
                    && generation === self.generation
                    && !self.stopping) {
                self.failTalk(self.tt('communicate.audio_conn_failed', 'Car audio connection failed'));
            }
        };
        socket.onclose = function () {
            if (self.socket === socket
                    && generation === self.generation
                    && !self.stopping
                    && (self.pressing || self.live)) {
                self.failTalk(self.tt('communicate.audio_conn_lost', 'Car audio connection was lost'));
            }
        };
    },

    startAudioPipeline: function (generation) {
        if (!this.isPressCurrent(generation) || !this.stream || this.live) return;
        if (!this.audioContext || this.audioContext.state === 'closed') {
            this.failTalk(this.tt('communicate.mic_not_activated', 'Microphone processing was not activated by the press'));
            return;
        }
        var self = this;
        var context = this.audioContext;
        var supportsWorklet = !!(
            context.audioWorklet
            && window.AudioWorkletNode
            && typeof context.audioWorklet.addModule === 'function');
        if (!supportsWorklet) {
            this.startScriptProcessorPipeline(generation);
            return;
        }

        context.audioWorklet.addModule(this.WORKLET_MODULE_URL)
            .then(function () {
                if (!self.isPressCurrent(generation)
                        || self.audioContext !== context
                        || context.state === 'closed') {
                    return;
                }
                self.startWorkletPipeline(generation);
            })
            .catch(function () {
                if (self.isPressCurrent(generation)
                        && self.audioContext === context
                        && context.state !== 'closed') {
                    self.startScriptProcessorPipeline(generation);
                }
            });
    },

    startWorkletPipeline: function (generation) {
        var self = this;
        try {
            this.sourceNode = this.audioContext.createMediaStreamSource(this.stream);
            this.processorNode = new window.AudioWorkletNode(
                this.audioContext,
                'remote-voice-capture',
                {
                    numberOfInputs: 1,
                    numberOfOutputs: 1,
                    outputChannelCount: [1],
                    processorOptions: {
                        targetSampleRate: this.TARGET_SAMPLE_RATE,
                        blockSize: 2048
                    }
                });
            this.silentGain = this.audioContext.createGain();
            this.silentGain.gain.value = 0;
            this.processorNode.port.onmessage = function (event) {
                var data = event.data || {};
                if (data.type !== 'pcm' || !data.pcm) return;
                self.handleCapturedPcm(
                    data.pcm,
                    typeof data.rms === 'number' ? data.rms : 0,
                    typeof data.contextTime === 'number'
                        ? data.contextTime : null,
                    generation);
            };
            this.processorNode.onprocessorerror = function () {
                if (!self.isPressCurrent(generation)
                        || self.captureMode !== 'worklet') {
                    return;
                }
                self.disconnectCaptureGraph();
                self.captureMode = '';
                self.startScriptProcessorPipeline(generation);
            };
            this.connectCaptureGraph();
            this.captureMode = 'worklet';
            this.activateAudioPipeline(generation);
        } catch (error) {
            this.disconnectCaptureGraph();
            if (this.isPressCurrent(generation)) {
                this.startScriptProcessorPipeline(generation);
            }
        }
    },

    startScriptProcessorPipeline: function (generation) {
        var self = this;
        try {
            this.sourceNode = this.audioContext.createMediaStreamSource(this.stream);
            this.processorNode = this.audioContext.createScriptProcessor(2048, 1, 1);
            this.silentGain = this.audioContext.createGain();
            this.silentGain.gain.value = 0;
            this.connectCaptureGraph();
        } catch (error) {
            this.disconnectCaptureGraph();
            this.failTalk(this.tt('communicate.mic_start_failed', 'Could not start microphone processing'));
            return;
        }

        this.processorNode.onaudioprocess = function (event) {
            var input = event.inputBuffer.getChannelData(0);
            var pcm = self.downsampleToPcm16(
                input, self.audioContext.sampleRate, self.TARGET_SAMPLE_RATE);
            self.handleCapturedPcm(
                pcm.buffer, self.sampleRms(input), null, generation);
        };
        this.captureMode = 'script';
        this.activateAudioPipeline(generation);
    },

    connectCaptureGraph: function () {
        this.sourceNode.connect(this.processorNode);
        this.processorNode.connect(this.silentGain);
        this.silentGain.connect(this.audioContext.destination);
    },

    disconnectCaptureGraph: function () {
        if (this.processorNode) {
            try {
                if (this.processorNode.port) {
                    this.processorNode.port.onmessage = null;
                }
            } catch (error) {}
            try { this.processorNode.onprocessorerror = null; } catch (error) {}
            try { this.processorNode.onaudioprocess = null; } catch (error) {}
            try { this.processorNode.disconnect(); } catch (error) {}
        }
        if (this.sourceNode) {
            try { this.sourceNode.disconnect(); } catch (error) {}
        }
        if (this.silentGain) {
            try { this.silentGain.disconnect(); } catch (error) {}
        }
        this.processorNode = null;
        this.sourceNode = null;
        this.silentGain = null;
    },

    activateAudioPipeline: function (generation) {
        var self = this;
        var activate = function () {
            if (!self.isPressCurrent(generation)
                    || !self.audioContext
                    || self.audioContext.state !== 'running') {
                if (self.isPressCurrent(generation)) {
                    self.failTalk(self.tt('communicate.mic_activate_failed', 'Could not activate microphone processing'));
                }
                return;
            }
            if (self.captureMode === 'worklet'
                    && window.performance
                    && typeof window.performance.now === 'function') {
                self.audioClockOffsetMs =
                    window.performance.now()
                    - self.audioContext.currentTime * 1000;
            }
            self.markTalkLive();
        };
        if (this.audioContext.state === 'running') {
            activate();
            return;
        }
        try {
            var resume = this.audioContext.resume();
            if (resume && typeof resume.then === 'function') {
                resume.then(activate).catch(function () {
                    if (self.isPressCurrent(generation)) {
                        self.failTalk(self.tt('communicate.mic_activate_failed', 'Could not activate microphone processing'));
                    }
                });
                return;
            }
        } catch (error) {
            this.failTalk(this.tt('communicate.mic_activate_failed', 'Could not activate microphone processing'));
            return;
        }
        activate();
    },

    handleCapturedPcm: function (
            pcmBuffer, rms, contextTime, generation) {
        if (!this.live || generation !== this.generation) return;
        if (contextTime !== null
                && window.performance
                && typeof window.performance.now === 'function') {
            var capturedAt =
                this.audioClockOffsetMs + contextTime * 1000;
            if (window.performance.now() - capturedAt
                    > this.MAX_CAPTURE_AGE_MS) {
                this.staleCaptureFrames++;
                return;
            }
        }

        var now = Date.now();
        this.lastAudioAt = now;
        if (now - this.lastMeterAt >= 100) {
            this.lastMeterAt = now;
            this.setMeter(Math.min(1, Math.max(0, rms) * 4.5));
        }
        if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
            this.failTalk(this.tt('communicate.audio_conn_lost', 'Car audio connection was lost'));
            return;
        }
        if (this.socket.bufferedAmount > 131072) {
            this.failTalk(this.tt('communicate.network_too_slow', 'Network is too slow for live audio'));
            return;
        }
        this.socket.send(pcmBuffer);
    },

    markTalkLive: function () {
        if (this.live) return;
        this.live = true;
        this.startedAt = Date.now();
        this.lastAudioAt = this.startedAt;
        this.lastMeterAt = 0;
        this.setTalkState('live', this.tt('communicate.live', 'Live'), '', '');
        this.startTalkTicker();
    },

    startTalkTicker: function () {
        var self = this;
        this.stopTalkTicker();
        this.tickTimer = window.setInterval(function () {
            if (!self.live) return;
            var now = Date.now();
            var elapsed = now - self.startedAt;
            self.renderElapsed(elapsed);
            if (elapsed >= self.MAX_SESSION_MS) {
                self.stopTalk(self.tt('communicate.limit_30s', '30 second limit reached'));
            } else if (now - self.lastAudioAt >= self.AUDIO_INACTIVITY_MS) {
                self.stopTalk(self.tt('communicate.mic_inactive', 'Microphone stream became inactive'));
            }
        }, 100);
    },

    stopTalkTicker: function () {
        if (this.tickTimer !== null) {
            window.clearInterval(this.tickTimer);
            this.tickTimer = null;
        }
    },

    stopTalk: function (reason, silent) {
        if (this.stopping) return;
        var hadResources = this.pressing || this.live || this.socket || this.stream;
        this.stopping = true;
        this.pressing = false;
        this.live = false;
        this.activePointerId = null;
        this.generation += 1;
        this.stopTalkTicker();
        if (hadResources && !silent) {
            this.setTalkState('stopping', this.tt('communicate.stopping', 'Stopping...'), '', '');
        }

        var socket = this.socket;
        this.socket = null;
        if (socket) {
            try {
                if (socket.readyState === WebSocket.OPEN) {
                    socket.send(JSON.stringify({
                        type: 'stop',
                        reason: reason || 'Released'
                    }));
                }
            } catch (error) {}
            try { socket.close(1000, 'stop'); } catch (error) {}
        }

        this.disconnectCaptureGraph();
        this.captureMode = '';
        this.audioClockOffsetMs = 0;

        if (this.audioContext) {
            try {
                var closeResult = this.audioContext.close();
                if (closeResult && typeof closeResult.catch === 'function') {
                    closeResult.catch(function () {});
                }
            } catch (error) {}
            this.audioContext = null;
        }
        this.stopTracks(this.stream);
        this.stream = null;
        CabinAudio.setLocalPaused(false);
        this.renderElapsed(0);
        this.setMeter(0);

        this.stopping = false;
        if (document.hidden || silent) {
            this.setTalkState('disabled', this.tt('communicate.talk_unavailable', 'Talk unavailable'), reason || '', '');
            return;
        }
        if (reason && reason !== 'Released' && reason !== 'Talk tab closed') {
            this.setTalkState('disabled', this.tt('communicate.talk_stopped', 'Talk stopped'), reason, '');
        }
        this.startStatusPolling(true);
    },

    failTalk: function (reason) {
        this.stopTalk(reason, true);
        if (!document.hidden) {
            this.setTalkState('disabled', this.tt('communicate.cannot_talk', 'Cannot talk'), reason, '');
            this.startStatusPolling(false);
        }
    },

    stopTracks: function (stream) {
        if (!stream || !stream.getTracks) return;
        var tracks = stream.getTracks();
        for (var i = 0; i < tracks.length; i++) {
            try { tracks[i].stop(); } catch (error) {}
        }
    },

    setTalkState: function (state, title, reason, guidance) {
        var button = document.getElementById('pttButton');
        var titleNode = document.getElementById('talkStateTitle');
        var reasonNode = document.getElementById('talkStateReason');
        var guidanceNode = document.getElementById('talkStateGuidance');
        var badge = document.getElementById('liveBadge');
        var meter = document.getElementById('talkMeter');
        if (button) {
            button.classList.remove(
                'is-ready', 'is-connecting', 'is-live', 'is-stopping', 'is-disabled');
            button.classList.add('is-' + state);
            var enabled = state === 'ready';
            button.setAttribute('aria-disabled', enabled ? 'false' : 'true');
        }
        if (titleNode) titleNode.textContent = title || '';
        if (reasonNode) reasonNode.textContent = reason || '';
        if (guidanceNode) guidanceNode.textContent = guidance || '';
        if (badge) badge.classList.toggle('is-visible', state === 'live');
        if (meter) meter.classList.toggle('is-visible', state === 'live');
        this.updateOutputControlState();
    },

    renderElapsed: function (elapsedMs) {
        var elapsed = document.getElementById('elapsedTime');
        var countdown = document.getElementById('talkCountdown');
        var seconds = Math.min(30, Math.max(0, Math.floor(elapsedMs / 1000)));
        if (elapsed) elapsed.textContent = '0:' + (seconds < 10 ? '0' : '') + seconds;
        var remaining = Math.max(0, Math.ceil((this.MAX_SESSION_MS - elapsedMs) / 1000));
        var show = this.live && remaining <= 5;
        if (countdown) {
            countdown.classList.toggle('is-visible', show);
            countdown.textContent = show ? String(remaining) : '';
        }
    },

    updateMeter: function (samples) {
        this.setMeter(Math.min(1, this.sampleRms(samples) * 4.5));
    },

    sampleRms: function (samples) {
        var sum = 0;
        for (var i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
        return Math.sqrt(sum / Math.max(1, samples.length));
    },

    setMeter: function (level) {
        var fill = document.getElementById('talkMeterFill');
        if (fill) fill.style.transform = 'scaleX(' + Math.max(0, Math.min(1, level)) + ')';
    },

    downsampleToPcm16: function (buffer, inputRate, outputRate) {
        if (outputRate > inputRate) outputRate = inputRate;
        var ratio = inputRate / outputRate;
        var length = Math.max(1, Math.floor(buffer.length / ratio));
        var result = new Int16Array(length);
        var offset = 0;
        for (var i = 0; i < length; i++) {
            var next = Math.min(buffer.length, Math.floor((i + 1) * ratio));
            var sum = 0;
            var count = 0;
            while (offset < next) {
                sum += buffer[offset++];
                count += 1;
            }
            var sample = count ? sum / count : 0;
            sample = Math.max(-1, Math.min(1, sample));
            result[i] = sample < 0 ? sample * 32768 : sample * 32767;
        }
        return result;
    },

    updateMessageControls: function () {
        var text = document.getElementById('messageText');
        var counter = document.getElementById('messageCounter');
        var send = document.getElementById('messageSend');
        var length = text ? text.value.length : 0;
        if (counter) counter.textContent = length + ' / 200';
        var ready = !!(this.status && this.status.messagesReady);
        if (send) send.disabled = this.messagePending || !ready || !text || !text.value.trim();
    },

    sendMessage: function () {
        var self = this;
        var text = document.getElementById('messageText');
        if (!text || !text.value.trim() || this.messagePending) return;
        if (!this.status || !this.status.messagesReady) {
            this.showMessageAck(
                false,
                (this.status && this.status.messageReason)
                    || this.tt('communicate.remote_messages_unavailable', 'Remote messages are unavailable'));
            return;
        }
        this.messagePending = true;
        this.showMessageAck(null, this.tt('communicate.sending', 'Sending...'));
        this.updateMessageControls();

        var payload = {
            kind: this.messageKind,
            message: text.value.trim(),
            severity: this.valueOf('messageSeverity', 'info'),
            position: this.valueOf('messagePosition', 'bottom'),
            duration: this.valueOf('messageDuration', 'short')
        };
        var options = {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        };
        var request = typeof BYDAuth !== 'undefined'
            ? BYDAuth.fetch('/api/communicate/message', options)
            : fetch('/api/communicate/message', options);
        request.then(function (response) {
            return response.json().then(function (data) {
                return { ok: response.ok, data: data };
            });
        }).then(function (result) {
            var data = result.data || {};
            if (result.ok && data.status === 'displayed') {
                var message = data.downgraded
                    ? self.tt('communicate.displayed_moving', 'Displayed as a toast while the vehicle is moving')
                    : self.tt('communicate.displayed', 'Displayed');
                self.showMessageAck(true, message);
                text.value = '';
            } else {
                self.showMessageAck(false,
                    data.reason || self.tt('communicate.display_failed', 'The car failed to display the message'));
            }
        }).catch(function () {
            self.showMessageAck(false, self.tt('communicate.display_no_ack', 'The car did not acknowledge the message'));
        }).then(function () {
            self.messagePending = false;
            self.updateMessageControls();
            self.startStatusPolling(true);
        });
    },

    showMessageAck: function (success, message) {
        var ack = document.getElementById('messageAck');
        if (!ack) return;
        ack.classList.toggle('is-success', success === true);
        ack.classList.toggle('is-error', success === false);
        ack.textContent = message || '';
    },

    valueOf: function (id, fallback) {
        var element = document.getElementById(id);
        return element && element.value ? element.value : fallback;
    }
};
