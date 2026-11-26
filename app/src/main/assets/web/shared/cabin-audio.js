/* On-demand cabin microphone playback shared by Live View and Communicate. */
(function () {
    'use strict';

    var SOURCE_RATE = 48000;
    var WORKLET_URL = '/shared/cabin-audio-worklet.js?v=2';
    var listeners = [];
    var socket = null;
    var context = null;
    var outputNode = null;
    var workletNode = null;
    var scheduledSources = [];
    var nextBufferAt = 0;
    var generation = 0;
    var state = 'idle';
    var reason = '';
    var localPaused = false;
    var serverPaused = false;
    // A listen asked for while the car is off waits for it, bounded, while the page stays open.
    var WAIT_POLL_MS = 3000;
    var WAIT_LIMIT_MS = 10 * 60 * 1000;
    var STATUS_TIMEOUT_MS = 5000;
    var waitTimer = null;
    var waitStartedAt = 0;

    function snapshot() {
        return {
            state: state,
            reason: reason,
            active: state === 'connecting'
                || state === 'waiting'
                || state === 'live'
                || state === 'paused',
            live: state === 'live',
            paused: state === 'paused',
            waiting: state === 'waiting'
        };
    }

    function emit(nextState, nextReason) {
        state = nextState;
        reason = nextReason || '';
        var value = snapshot();
        for (var i = 0; i < listeners.length; i++) {
            try { listeners[i](value); } catch (error) {}
        }
    }

    function subscribe(listener) {
        if (typeof listener !== 'function') return function () {};
        listeners.push(listener);
        listener(snapshot());
        return function () {
            var index = listeners.indexOf(listener);
            if (index >= 0) listeners.splice(index, 1);
        };
    }

    function prepareOutput(currentGeneration) {
        var AudioContextCtor = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextCtor) {
            return Promise.reject(new Error('This browser cannot play live audio'));
        }
        try {
            context = new AudioContextCtor({
                latencyHint: 'interactive',
                sampleRate: SOURCE_RATE
            });
        } catch (error) {
            context = new AudioContextCtor();
        }
        outputNode = context.createGain();
        outputNode.gain.value = 1;
        outputNode.connect(context.destination);

        var resume = context.state === 'suspended'
            ? context.resume() : Promise.resolve();
        return Promise.resolve(resume).then(function () {
            if (currentGeneration !== generation || !context) {
                throw new Error('Listener start was cancelled');
            }
            var supportsWorklet = context.sampleRate === SOURCE_RATE
                && context.audioWorklet
                && window.AudioWorkletNode
                && typeof context.audioWorklet.addModule === 'function';
            if (!supportsWorklet) return;
            return context.audioWorklet.addModule(WORKLET_URL)
                .then(function () {
                    if (currentGeneration !== generation || !context) return;
                    workletNode = new window.AudioWorkletNode(
                        context,
                        'cabin-audio-playback',
                        {
                            numberOfInputs: 0,
                            numberOfOutputs: 1,
                            outputChannelCount: [1]
                        });
                    workletNode.connect(outputNode);
                })
                .catch(function () {
                    workletNode = null;
                });
        });
    }

    function websocketUrl() {
        var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        var url = protocol + '//' + location.host + '/ws/cabin-audio';
        if (typeof BYDAuth !== 'undefined') {
            var token = BYDAuth.getToken();
            if (token) url += '?token=' + encodeURIComponent(token);
        }
        return url;
    }

    function start() {
        if (state === 'connecting' || state === 'waiting'
                || state === 'live' || state === 'paused') {
            return Promise.resolve();
        }
        var currentGeneration = ++generation;
        localPaused = false;
        serverPaused = false;
        nextBufferAt = 0;
        waitStartedAt = 0;
        emit('connecting', '');

        return prepareOutput(currentGeneration)
            .then(function () {
                if (currentGeneration !== generation) return;
                return checkCar(currentGeneration);
            })
            .catch(function (error) {
                if (currentGeneration === generation) {
                    fail(error && error.message
                        ? error.message : 'Could not start cabin audio');
                }
            });
    }

    // null when the status can't be read; the socket then reports the reason, as before.
    function readStatus() {
        var headers = typeof BYDAuth !== 'undefined' && BYDAuth.getAuthHeaders
            ? BYDAuth.getAuthHeaders() : {};
        var request = fetch('/api/communicate/status', { cache: 'no-store', headers: headers })
            .then(function (response) { return response.ok ? response.json() : null; });
        var timeout = new Promise(function (resolve) {
            setTimeout(function () { resolve(null); }, STATUS_TIMEOUT_MS);
        });
        return Promise.race([request, timeout]).catch(function () { return null; });
    }

    function checkCar(currentGeneration) {
        return readStatus().then(function (status) {
            if (currentGeneration !== generation) return;
            var carOff = !!status && status.carState === 'off'
                && status.listenerState === 'car_off';
            if (carOff || (!status && state === 'waiting')) {
                waitForCar(currentGeneration);
            } else {
                openSocket(currentGeneration);
            }
        });
    }

    function waitForCar(currentGeneration) {
        if (currentGeneration !== generation) return;
        if (!waitStartedAt) waitStartedAt = Date.now();
        if (Date.now() - waitStartedAt >= WAIT_LIMIT_MS) {
            fail('The car was not turned on, so listening was cancelled');
            return;
        }
        if (state !== 'waiting') emit('waiting', 'Listening starts when the car turns on');
        clearWaitTimer();
        waitTimer = setTimeout(function () {
            waitTimer = null;
            if (currentGeneration !== generation || state !== 'waiting') return;
            checkCar(currentGeneration).catch(function (error) {
                if (currentGeneration === generation) {
                    fail(error && error.message
                        ? error.message : 'Could not start cabin audio');
                }
            });
        }, WAIT_POLL_MS);
    }

    function clearWaitTimer() {
        if (waitTimer !== null) {
            clearTimeout(waitTimer);
            waitTimer = null;
        }
    }

    function openSocket(currentGeneration) {
        if (currentGeneration !== generation) return;
        if (state !== 'connecting') emit('connecting', '');
        if (context && context.state === 'suspended') {
            try {
                var resumed = context.resume();
                if (resumed && resumed.catch) resumed.catch(function () {});
            } catch (error) {}
        }
        socket = new WebSocket(websocketUrl());
        socket.binaryType = 'arraybuffer';
        socket.onmessage = function (event) {
            if (currentGeneration !== generation) return;
            if (typeof event.data === 'string') {
                handleControl(event.data);
            } else if (!localPaused && !serverPaused) {
                playPcm(event.data);
            }
        };
        socket.onerror = function () {
            if (currentGeneration === generation) {
                fail('Cabin audio connection failed');
            }
        };
        socket.onclose = function () {
            if (currentGeneration === generation
                    && state !== 'idle'
                    && state !== 'error') {
                fail(reason || 'Cabin audio connection was lost');
            }
        };
    }

    function handleControl(raw) {
        var message;
        try { message = JSON.parse(raw); } catch (error) { return; }
        if (message.type === 'ready') {
            SOURCE_RATE = Number(message.sampleRate) || SOURCE_RATE;
            emit(localPaused || serverPaused ? 'paused' : 'live', '');
        } else if (message.type === 'paused') {
            serverPaused = true;
            resetPlayback();
            emit('paused', message.reason || 'Push-to-talk active');
        } else if (message.type === 'resumed') {
            serverPaused = false;
            resetPlayback();
            emit(localPaused ? 'paused' : 'live', '');
        } else if (message.type === 'failed') {
            fail(message.reason || 'Cabin audio is unavailable');
        } else if (message.type === 'stopped') {
            fail(message.reason || 'Cabin audio stopped');
        }
    }

    function playPcm(buffer) {
        if (!context || context.state === 'closed' || !buffer) return;
        if (workletNode) {
            try {
                workletNode.port.postMessage(buffer, [buffer]);
            } catch (error) {
                fail('Cabin audio playback failed');
            }
            return;
        }

        var pcm = new Int16Array(buffer);
        if (!pcm.length) return;
        var audioBuffer = context.createBuffer(1, pcm.length, SOURCE_RATE);
        var samples = audioBuffer.getChannelData(0);
        for (var i = 0; i < pcm.length; i++) {
            samples[i] = pcm[i] / 32768;
        }
        var now = context.currentTime;
        if (nextBufferAt > now + 0.5) resetPlayback();
        if (nextBufferAt < now + 0.06) nextBufferAt = now + 0.08;
        var source = context.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(outputNode);
        scheduledSources.push(source);
        source.start(nextBufferAt);
        nextBufferAt += pcm.length / SOURCE_RATE;
        source.onended = function () {
            try { source.disconnect(); } catch (error) {}
            var index = scheduledSources.indexOf(source);
            if (index >= 0) scheduledSources.splice(index, 1);
        };
    }

    function setLocalPaused(paused) {
        localPaused = !!paused;
        if (outputNode) outputNode.gain.value = localPaused ? 0 : 1;
        resetPlayback();
        if (state === 'live' || state === 'paused') {
            emit(localPaused || serverPaused ? 'paused' : 'live',
                localPaused ? 'Push-to-talk active' : '');
        }
    }

    function resetPlayback() {
        nextBufferAt = 0;
        var sources = scheduledSources;
        scheduledSources = [];
        for (var i = 0; i < sources.length; i++) {
            try { sources[i].stop(); } catch (error) {}
            try { sources[i].disconnect(); } catch (error) {}
        }
        if (workletNode) {
            try { workletNode.port.postMessage({ type: 'reset' }); }
            catch (error) {}
        }
    }

    function stop() {
        generation += 1;
        cleanup();
        emit('idle', '');
    }

    function fail(message) {
        generation += 1;
        cleanup();
        emit('error', message || 'Cabin audio is unavailable');
    }

    function cleanup() {
        clearWaitTimer();
        waitStartedAt = 0;
        resetPlayback();
        var oldSocket = socket;
        socket = null;
        if (oldSocket) {
            oldSocket.onopen = null;
            oldSocket.onmessage = null;
            oldSocket.onerror = null;
            oldSocket.onclose = null;
            try { oldSocket.close(1000, 'stop'); } catch (error) {}
        }
        if (workletNode) {
            try { workletNode.port.postMessage({ type: 'reset' }); }
            catch (error) {}
            try { workletNode.disconnect(); } catch (error) {}
            workletNode = null;
        }
        if (outputNode) {
            try { outputNode.disconnect(); } catch (error) {}
            outputNode = null;
        }
        if (context) {
            try {
                var closing = context.close();
                if (closing && closing.catch) closing.catch(function () {});
            } catch (error) {}
            context = null;
        }
        localPaused = false;
        serverPaused = false;
    }

    function toggle() {
        if (state === 'connecting' || state === 'waiting'
                || state === 'live' || state === 'paused') {
            stop();
            return Promise.resolve();
        }
        return start();
    }

    window.CabinAudio = {
        start: start,
        stop: stop,
        toggle: toggle,
        subscribe: subscribe,
        getState: snapshot,
        setLocalPaused: setLocalPaused
    };
})();
