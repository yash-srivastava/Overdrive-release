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
    var consecutiveFrameFailures = 0;
    var frameSocket = null;
    var reconnectTimer = null;
    var reconnectAttempts = 0;
    var pollingFallback = false;
    var renderingFrame = false;
    var pendingFrameBlob = null;
    var frameTimes = [];
    var screenshotInFlight = false;

    var startButton = document.getElementById('startButton');
    var stopButton = document.getElementById('stopButton');
    var refreshButton = document.getElementById('refreshButton');
    var fullscreenButton = document.getElementById('fullscreenButton');
    var fullscreenBackButton = document.getElementById('fullscreenBackButton');
    var fullscreenScreenshotButton = document.getElementById('fullscreenScreenshotButton');
    var fullscreenRefreshButton = document.getElementById('fullscreenRefreshButton');
    var fullscreenStopButton = document.getElementById('fullscreenStopButton');
    var fullscreenExitButton = document.getElementById('fullscreenExitButton');
    var viewerCard = document.getElementById('viewerCard');
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
    var screenshotButton = document.getElementById('screenshotButton');

    function setMessage(text, isError) {
        message.textContent = text || '';
        message.className = isError ? 'error' : '';
    }

    function setRunning(running) {
        stopped = !running;
        startButton.disabled = running;
        startButton.textContent = running ? 'Session active' : 'Start session';
        stopButton.disabled = !running;
        refreshButton.disabled = !running;
        fullscreenButton.disabled = !running;
        fullscreenBackButton.disabled = !running;
        screenshotButton.disabled = !running || screenshotInFlight;
        fullscreenScreenshotButton.disabled = !running || screenshotInFlight;
        fullscreenRefreshButton.disabled = !running;
        fullscreenStopButton.disabled = !running;
        textInput.disabled = !running;
        textButton.disabled = !running;
        var keyButtons = document.querySelectorAll('.key-button');
        for (var i = 0; i < keyButtons.length; i++) keyButtons[i].disabled = !running;
        liveDot.className = running ? 'status-dot live' : 'status-dot';
        connectionState.textContent = running ? 'Connected' : 'Stopped';
        if (!running) {
            exitFullscreen();
            closeFrameStream();
            fetchingFrame = false;
            consecutiveFrameFailures = 0;
            pollingFallback = false;
            renderingFrame = false;
            pendingFrameBlob = null;
            frameTimes = [];
            if (pollTimer) window.clearTimeout(pollTimer);
            pollTimer = null;
        }
    }

    function nativeFullscreenElement() {
        return document.fullscreenElement || document.webkitFullscreenElement || null;
    }

    function isFullscreen() {
        return nativeFullscreenElement() === viewerCard ||
            document.body.classList.contains('dev-view-focus');
    }

    function syncFullscreenState() {
        var active = isFullscreen();
        fullscreenButton.textContent = active ? 'Exit fullscreen' : 'Fullscreen';
        fullscreenButton.setAttribute('aria-pressed', active ? 'true' : 'false');
    }

    function enableFullscreenFallback() {
        document.body.classList.add('dev-view-focus');
        syncFullscreenState();
    }

    function enterFullscreen() {
        if (!session || isFullscreen()) return;
        var request = viewerCard.requestFullscreen || viewerCard.webkitRequestFullscreen;
        if (!request) {
            enableFullscreenFallback();
            return;
        }
        try {
            var result = request.call(viewerCard);
            if (result && typeof result.catch === 'function') {
                result.catch(enableFullscreenFallback);
            }
        } catch (error) {
            enableFullscreenFallback();
        }
    }

    function exitFullscreen() {
        document.body.classList.remove('dev-view-focus');
        var active = nativeFullscreenElement();
        var exit = document.exitFullscreen || document.webkitExitFullscreen;
        if (active === viewerCard && exit) {
            try {
                var result = exit.call(document);
                if (result && typeof result.catch === 'function') result.catch(function () {});
            } catch (error) {}
        }
        syncFullscreenState();
    }

    function toggleFullscreen() {
        if (isFullscreen()) exitFullscreen();
        else enterFullscreen();
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
        if (session) return;
        startButton.disabled = true;
        startButton.textContent = 'Starting...';
        connectionState.textContent = 'Starting';
        setMessage('', false);
        jsonRequest('/api/dev-view/session', 'POST', { confirm: 'I UNDERSTAND' })
            .then(function (data) {
                session = data.session;
                setRunning(true);
                placeholder.textContent = data.activityReady
                    ? 'Connecting live stream...'
                    : 'Waiting for Overdrive to render...';
                setMessage('', false);
                openFrameStream();
            })
            .catch(function (error) {
                session = null;
                setRunning(false);
                setMessage(error.message, true);
            });
    }

    function closeFrameStream() {
        if (reconnectTimer) window.clearTimeout(reconnectTimer);
        reconnectTimer = null;
        var socket = frameSocket;
        frameSocket = null;
        if (!socket) return;
        socket.onopen = null;
        socket.onmessage = null;
        socket.onerror = null;
        socket.onclose = null;
        try { socket.close(1000, 'stream stopped'); } catch (error) {}
    }

    function streamUrl() {
        var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        var url = protocol + '//' + window.location.host + '/ws/dev-view';
        var jwt = BYDAuth.getToken();
        if (jwt) url += '?token=' + encodeURIComponent(jwt);
        return url;
    }

    function openFrameStream() {
        if (stopped || !session || document.hidden) return;
        if (typeof WebSocket === 'undefined' || !BYDAuth.getToken()) {
            startPollingFallback('Live stream is unavailable; using compatibility mode.');
            return;
        }

        closeFrameStream();
        pollingFallback = false;
        if (pollTimer) window.clearTimeout(pollTimer);
        pollTimer = null;
        var token = session;
        var socket;
        try {
            // The JWT authenticates the WebSocket upgrade. The memory-only
            // developer capability travels as a subprotocol, never in the URL.
            socket = new WebSocket(streamUrl(), ['overdrive-dev-view', token]);
        } catch (error) {
            startPollingFallback('Live stream could not start; using compatibility mode.');
            return;
        }
        frameSocket = socket;
        socket.binaryType = 'blob';

        socket.onopen = function () {
            if (socket !== frameSocket || token !== session) return;
            reconnectAttempts = 0;
            connectionState.textContent = 'Live stream';
            setMessage('', false);
        };

        socket.onmessage = function (event) {
            if (socket !== frameSocket || token !== session) return;
            if (typeof event.data === 'string') {
                handleStreamMetadata(event.data);
                return;
            }
            var blob = event.data instanceof Blob
                ? event.data
                : new Blob([event.data], { type: 'image/jpeg' });
            renderFrame(blob);
        };

        socket.onerror = function () {
            if (socket === frameSocket) connectionState.textContent = 'Stream reconnecting';
        };

        socket.onclose = function (event) {
            if (socket !== frameSocket) return;
            frameSocket = null;
            if (stopped || token !== session || document.hidden) return;
            if (event && event.code === 1008) {
                session = null;
                setRunning(false);
                setMessage('Developer-view session expired.', true);
                return;
            }
            reconnectAttempts += 1;
            if (reconnectAttempts <= 3) {
                connectionState.textContent = 'Stream reconnecting';
                reconnectTimer = window.setTimeout(openFrameStream,
                    Math.min(1500, reconnectAttempts * 350));
            } else {
                startPollingFallback('Live stream disconnected; using compatibility mode.');
            }
        };
    }

    function handleStreamMetadata(text) {
        var data;
        try { data = JSON.parse(text); } catch (error) { return; }
        if (data.type === 'ready') {
            connectionState.textContent = 'Live stream';
            return;
        }
        if (data.type !== 'frame') return;

        if (data.width && data.height) {
            dimensions.textContent = data.width + ' × ' + data.height;
        }
        pixelCopyState.textContent = 'PixelCopy ' +
            (data.pixelCopyResult || '--') + ' (' +
            (typeof data.pixelCopyCode === 'number' ? data.pixelCopyCode : '--') + ')';
        activityState.textContent = 'Activity ' + shortActivity(data.activity);

        if (data.success) {
            consecutiveFrameFailures = 0;
            setMessage('', false);
        } else {
            consecutiveFrameFailures += 1;
            connectionState.textContent = currentObjectUrl
                ? 'Live - holding last frame'
                : 'Waiting for a stable frame';
            if (!currentObjectUrl) placeholder.textContent = 'Waiting for a stable app frame...';
        }
    }

    function renderFrame(blob) {
        if (!blob || !blob.size) return;
        if (renderingFrame) {
            // Browser decode/display can lag behind the producer. Keep only
            // the newest waiting frame so controls never show a stale backlog.
            pendingFrameBlob = blob;
            return;
        }
        renderingFrame = true;
        var nextUrl = URL.createObjectURL(blob);
        var loader = new Image();
        loader.onload = function () {
            var previousUrl = currentObjectUrl;
            currentObjectUrl = nextUrl;
            image.src = nextUrl;
            renderingFrame = false;
            image.style.display = 'block';
            placeholder.style.display = 'none';
            noteDisplayedFrame();
            if (previousUrl) {
                window.setTimeout(function () { URL.revokeObjectURL(previousUrl); }, 1000);
            }
            if (pendingFrameBlob) {
                var newest = pendingFrameBlob;
                pendingFrameBlob = null;
                renderFrame(newest);
            }
        };
        loader.onerror = function () {
            URL.revokeObjectURL(nextUrl);
            renderingFrame = false;
        };
        loader.src = nextUrl;
    }

    function noteDisplayedFrame() {
        var now = Date.now();
        frameTimes.push(now);
        if (frameTimes.length > 12) frameTimes.shift();
        if (frameTimes.length >= 2) {
            var elapsed = frameTimes[frameTimes.length - 1] - frameTimes[0];
            if (elapsed > 0) {
                var fps = (frameTimes.length - 1) * 1000 / elapsed;
                connectionState.textContent = 'Live · ' + fps.toFixed(1) + ' fps';
            }
        }
    }

    function startPollingFallback(reason) {
        if (stopped || !session) return;
        closeFrameStream();
        pollingFallback = true;
        connectionState.textContent = 'Connected · compatibility';
        if (reason) setMessage(reason, false);
        requestFrame();
    }

    function scheduleFrame(delay) {
        if (stopped || !session || !pollingFallback) return;
        if (pollTimer) window.clearTimeout(pollTimer);
        pollTimer = window.setTimeout(requestFrame, delay);
    }

    function requestFrame() {
        if (stopped || !session || !pollingFallback || fetchingFrame || document.hidden) return;
        fetchingFrame = true;
        var frameFailed = false;
        var token = session;
        BYDAuth.fetch('/api/dev-view/frame', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session: token, maxWidth: 960, quality: 55 }),
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
            renderFrame(blob);
            consecutiveFrameFailures = 0;
            setMessage('', false);
        }).catch(function (error) {
            frameFailed = true;
            consecutiveFrameFailures += 1;
            if (error.sessionInvalid) {
                session = null;
                setRunning(false);
            }
            if (error.sessionInvalid) setMessage(error.message + '.', true);
            else {
                connectionState.textContent = currentObjectUrl
                    ? 'Connected - holding last frame'
                    : 'Waiting for a stable frame';
                if (!currentObjectUrl) placeholder.textContent = 'Waiting for a stable app frame...';
            }
        }).then(function () {
            fetchingFrame = false;
            var delay = forceRefresh ? 0 : (frameFailed
                ? Math.min(1000, 120 * consecutiveFrameFailures)
                : 60);
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
            if (pollingFallback) {
                forceRefresh = true;
                if (!fetchingFrame) requestFrame();
            }
        }).catch(function (error) {
            if (error.sessionInvalid) {
                session = null;
                setRunning(false);
            }
            setMessage(error.message, true);
        });
        return inputChain;
    }

    function setScreenshotBusy(busy) {
        screenshotInFlight = busy;
        screenshotButton.disabled = stopped || busy;
        fullscreenScreenshotButton.disabled = stopped || busy;
        screenshotButton.textContent = busy ? 'Capturing...' : 'Screenshot';
        fullscreenScreenshotButton.textContent = busy ? 'Capturing...' : 'Screenshot';
    }

    function captureScreenshot() {
        if (!session || screenshotInFlight) return;
        var token = session;
        setScreenshotBusy(true);
        BYDAuth.fetch('/api/dev-view/frame', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                session: token,
                maxWidth: 1920,
                quality: 100,
                format: 'png'
            }),
            cache: 'no-store'
        }).then(function (response) {
            if (!response.ok) {
                return response.json().then(function (data) {
                    var error = new Error(data.error || data.detail ||
                        data.pixelCopyResult || ('HTTP ' + response.status));
                    error.sessionInvalid = response.status === 403;
                    throw error;
                });
            }
            return response.blob();
        }).then(function (blob) {
            if (!blob || !blob.size || token !== session) return;
            var url = URL.createObjectURL(blob);
            var link = document.createElement('a');
            link.href = url;
            link.download = 'overdrive-dev-view-' +
                new Date().toISOString().replace(/[:.]/g, '-') + '.png';
            link.style.display = 'none';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            window.setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
            setMessage('', false);
        }).catch(function (error) {
            if (error.sessionInvalid) {
                session = null;
                setRunning(false);
            }
            setMessage('Screenshot failed: ' + error.message, true);
        }).then(function () {
            setScreenshotBusy(false);
        });
    }

    function frameContentRect() {
        var rect = image.getBoundingClientRect();
        var naturalWidth = image.naturalWidth || 16;
        var naturalHeight = image.naturalHeight || 9;
        var imageAspect = naturalWidth / naturalHeight;
        var boxAspect = rect.width / Math.max(1, rect.height);
        if (boxAspect > imageAspect) {
            var width = rect.height * imageAspect;
            return {
                left: rect.left + (rect.width - width) / 2,
                top: rect.top,
                width: width,
                height: rect.height
            };
        }
        var height = rect.width / imageAspect;
        return {
            left: rect.left,
            top: rect.top + (rect.height - height) / 2,
            width: rect.width,
            height: height
        };
    }

    function pointerPayload(event, phase) {
        var rect = frameContentRect();
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

    startButton.addEventListener('click', startSession);
    stopButton.addEventListener('click', function () { endSession(false); });
    fullscreenStopButton.addEventListener('click', function () { endSession(false); });
    fullscreenButton.addEventListener('click', toggleFullscreen);
    screenshotButton.addEventListener('click', captureScreenshot);
    fullscreenScreenshotButton.addEventListener('click', captureScreenshot);
    fullscreenExitButton.addEventListener('click', exitFullscreen);
    fullscreenBackButton.addEventListener('click', function () {
        sendInput({ type: 'key', key: 'back' });
    });
    refreshButton.addEventListener('click', function () {
        if (pollingFallback) {
            forceRefresh = true;
            if (!fetchingFrame) requestFrame();
        } else {
            reconnectAttempts = 0;
            openFrameStream();
        }
    });
    fullscreenRefreshButton.addEventListener('click', function () {
        if (pollingFallback) {
            forceRefresh = true;
            if (!fetchingFrame) requestFrame();
        } else {
            reconnectAttempts = 0;
            openFrameStream();
        }
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
        if (document.hidden) {
            closeFrameStream();
            if (pollTimer) window.clearTimeout(pollTimer);
            pollTimer = null;
        } else if (session) {
            reconnectAttempts = 0;
            openFrameStream();
        }
    });
    document.addEventListener('fullscreenchange', syncFullscreenState);
    document.addEventListener('webkitfullscreenchange', syncFullscreenState);
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && document.body.classList.contains('dev-view-focus')) {
            exitFullscreen();
        }
    });
    window.addEventListener('beforeunload', function () {
        if (session) endSession(true);
    });

    bindPointer();
    setRunning(false);
    syncFullscreenState();
})();
