(function () {
    'use strict';

    if (typeof BYDAuth === 'undefined' || !BYDAuth.requireAuth()) return;

    var session = null;
    var stopped = true;
    var fetchingFrame = false;
    var forceRefresh = false;
    var currentObjectUrl = null;
    var inputChain = Promise.resolve();
    var pollTimer = null;
    var lastMoveAt = 0;

    var confirmStart = document.getElementById('confirmStart');
    var startButton = document.getElementById('startButton');
    var stopButton = document.getElementById('stopButton');
    var refreshButton = document.getElementById('refreshButton');
    var image = document.getElementById('remoteFrame');
    var placeholder = document.getElementById('framePlaceholder');
    var message = document.getElementById('message');
    var liveDot = document.getElementById('liveDot');
    var connectionState = document.getElementById('connectionState');
    var dimensions = document.getElementById('frameDimensions');
    var pixelCopyState = document.getElementById('pixelCopyState');
    var activityState = document.getElementById('activityState');
    var textInput = document.getElementById('textInput');
    var textButton = document.getElementById('textButton');

    function setMessage(text, isError) {
        message.textContent = text || '';
        message.className = isError ? 'error' : '';
    }

    function setRunning(running) {
        stopped = !running;
        startButton.disabled = running || !confirmStart.checked;
        stopButton.disabled = !running;
        refreshButton.disabled = !running;
        textInput.disabled = !running;
        textButton.disabled = !running;
        var keyButtons = document.querySelectorAll('.key-button');
        for (var i = 0; i < keyButtons.length; i++) keyButtons[i].disabled = !running;
        liveDot.className = running ? 'status-dot live' : 'status-dot';
        connectionState.textContent = running ? 'Connected' : 'Stopped';
        if (!running) {
            fetchingFrame = false;
            if (pollTimer) window.clearTimeout(pollTimer);
            pollTimer = null;
        }
    }

    function jsonRequest(path, method, payload) {
        return BYDAuth.fetch(path, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload || {})
        }).then(function (response) {
            return response.json().then(function (data) {
                if (!response.ok || data.success === false) {
                    var error = new Error(data.error || data.detail || ('HTTP ' + response.status));
                    error.sessionInvalid = response.status === 403;
                    throw error;
                }
                return data;
            });
        });
    }

    function startSession() {
        if (!confirmStart.checked || session) return;
        startButton.disabled = true;
        setMessage('Starting the protected app-process bridge…', false);
        jsonRequest('/api/dev-view/session', 'POST', { confirm: 'I UNDERSTAND' })
            .then(function (data) {
                session = data.session;
                setRunning(true);
                setMessage(data.activityReady
                    ? 'Session ready. The physical display has not been changed.'
                    : 'Bridge ready; waiting for the Overdrive activity to render.', false);
                requestFrame();
            })
            .catch(function (error) {
                session = null;
                setRunning(false);
                setMessage(error.message, true);
            });
    }

    function scheduleFrame(delay) {
        if (stopped || !session) return;
        if (pollTimer) window.clearTimeout(pollTimer);
        pollTimer = window.setTimeout(requestFrame, delay);
    }

    function requestFrame() {
        if (stopped || !session || fetchingFrame || document.hidden) return;
        fetchingFrame = true;
        var token = session;
        BYDAuth.fetch('/api/dev-view/frame', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session: token, maxWidth: 1280, quality: 72 }),
            cache: 'no-store'
        }).then(function (response) {
            if (!response.ok) {
                return response.json().then(function (data) {
                    var error = new Error(data.error || data.detail || data.pixelCopyResult || ('HTTP ' + response.status));
                    error.sessionInvalid = response.status === 403;
                    throw error;
                });
            }
            dimensions.textContent = response.headers.get('X-Overdrive-Width') + ' × ' +
                response.headers.get('X-Overdrive-Height');
            pixelCopyState.textContent = 'PixelCopy ' +
                (response.headers.get('X-Overdrive-PixelCopy-Result') || '--') + ' (' +
                (response.headers.get('X-Overdrive-PixelCopy-Code') || '--') + ')';
            activityState.textContent = 'Activity ' +
                shortActivity(response.headers.get('X-Overdrive-Activity'));
            return response.blob();
        }).then(function (blob) {
            if (!blob || token !== session) return;
            var nextUrl = URL.createObjectURL(blob);
            image.onload = function () {
                if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
                currentObjectUrl = nextUrl;
                image.onload = null;
            };
            image.src = nextUrl;
            image.style.display = 'block';
            placeholder.style.display = 'none';
            setMessage('', false);
        }).catch(function (error) {
            if (error.sessionInvalid) {
                session = null;
                setRunning(false);
            }
            setMessage(error.message + (error.sessionInvalid ? '.' : ' — retrying.'), true);
        }).then(function () {
            fetchingFrame = false;
            var delay = forceRefresh ? 0 : 300;
            forceRefresh = false;
            scheduleFrame(delay);
        });
    }

    function shortActivity(name) {
        if (!name) return '--';
        var index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index + 1) : name;
    }

    function sendInput(payload) {
        if (!session) return Promise.reject(new Error('No active session'));
        payload.session = session;
        inputChain = inputChain.then(function () {
            return jsonRequest('/api/dev-view/input', 'POST', payload);
        }).then(function () {
            forceRefresh = true;
            if (!fetchingFrame) requestFrame();
        }).catch(function (error) {
            if (error.sessionInvalid) {
                session = null;
                setRunning(false);
            }
            setMessage(error.message, true);
        });
        return inputChain;
    }

    function pointerPayload(event, phase) {
        var rect = image.getBoundingClientRect();
        var clientX = event.clientX;
        var clientY = event.clientY;
        if (event.touches && event.touches.length) {
            clientX = event.touches[0].clientX;
            clientY = event.touches[0].clientY;
        } else if (event.changedTouches && event.changedTouches.length) {
            clientX = event.changedTouches[0].clientX;
            clientY = event.changedTouches[0].clientY;
        }
        if (typeof clientX !== 'number' || typeof clientY !== 'number') {
            clientX = rect.left + rect.width / 2;
            clientY = rect.top + rect.height / 2;
        }
        return {
            type: 'touch', phase: phase,
            x: Math.max(0, Math.min(1, (clientX - rect.left) / rect.width)),
            y: Math.max(0, Math.min(1, (clientY - rect.top) / rect.height))
        };
    }

    function bindPointer() {
        var dragging = false;
        image.addEventListener('mousedown', function (event) {
            dragging = true;
            event.preventDefault();
            sendInput(pointerPayload(event, 'down'));
        });
        window.addEventListener('mousemove', function (event) {
            if (!dragging) return;
            var now = Date.now();
            if (now - lastMoveAt < 50) return;
            lastMoveAt = now;
            event.preventDefault();
            sendInput(pointerPayload(event, 'move'));
        });
        window.addEventListener('mouseup', function (event) {
            if (!dragging) return;
            dragging = false;
            event.preventDefault();
            sendInput(pointerPayload(event, 'up'));
        });
        image.addEventListener('touchstart', function (event) {
            dragging = true;
            event.preventDefault();
            sendInput(pointerPayload(event, 'down'));
        }, { passive: false });
        image.addEventListener('touchmove', function (event) {
            if (!dragging) return;
            var now = Date.now();
            if (now - lastMoveAt < 50) return;
            lastMoveAt = now;
            event.preventDefault();
            sendInput(pointerPayload(event, 'move'));
        }, { passive: false });
        image.addEventListener('touchend', function (event) {
            if (!dragging) return;
            dragging = false;
            event.preventDefault();
            sendInput(pointerPayload(event, 'up'));
        }, { passive: false });
        image.addEventListener('touchcancel', function (event) {
            if (!dragging) return;
            dragging = false;
            event.preventDefault();
            sendInput(pointerPayload(event, 'cancel'));
        }, { passive: false });
    }

    function endSession(silent) {
        var token = session;
        session = null;
        setRunning(false);
        if (!token) return Promise.resolve();
        return jsonRequest('/api/dev-view/session', 'DELETE', { session: token })
            .then(function () {
                if (!silent) setMessage('Developer-view session ended.', false);
            }).catch(function (error) {
                if (!silent) setMessage(error.message, true);
            });
    }

    confirmStart.addEventListener('change', function () {
        startButton.disabled = !confirmStart.checked || !!session;
    });
    startButton.addEventListener('click', startSession);
    stopButton.addEventListener('click', function () { endSession(false); });
    refreshButton.addEventListener('click', function () {
        forceRefresh = true;
        if (!fetchingFrame) requestFrame();
    });
    var keyButtons = document.querySelectorAll('.key-button');
    for (var i = 0; i < keyButtons.length; i++) {
        keyButtons[i].addEventListener('click', function () {
            sendInput({ type: 'key', key: this.getAttribute('data-key') });
        });
    }
    textButton.addEventListener('click', function () {
        if (!textInput.value) return;
        sendInput({ type: 'text', text: textInput.value });
        textInput.value = '';
    });
    textInput.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') {
            event.preventDefault();
            textButton.click();
        }
    });
    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && session && !fetchingFrame) requestFrame();
    });
    window.addEventListener('beforeunload', function () {
        if (session) endSession(true);
    });

    bindPointer();
    setRunning(false);
})();
