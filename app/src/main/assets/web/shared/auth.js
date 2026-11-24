/**
 * BYD Champ - Client-side Auth Helper
 * 
 * Handles JWT session management and auth state.
 * Uses both cookie AND localStorage for redundancy (tunnels can drop cookies).
 * Include this in pages that need auth checking.
 */

const BYDAuth = {
    // Storage keys
    COOKIE_NAME: 'byd_session',
    STORAGE_KEY: 'byd_jwt',
    // Same-origin requests other than GET/HEAD that haven't settled yet.
    pendingWrites: 0,
    
    // Check if user is authenticated
    isAuthenticated: function() {
        return !!this.getToken();
    },
    
    // Get JWT from cookie OR localStorage (fallback)
    getToken: function() {
        // Try cookie first
        const cookies = document.cookie.split(';');
        for (let cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === this.COOKIE_NAME && value) {
                return value;
            }
        }
        
        // Fallback to localStorage (tunnels sometimes drop cookies)
        try {
            const stored = localStorage.getItem(this.STORAGE_KEY);
            if (stored) {
                console.log('JWT loaded from localStorage (cookie was missing)');
                // Try to restore the cookie
                this._setCookie(stored);
                return stored;
            }
        } catch (e) {
            // localStorage might be blocked
        }
        
        return null;
    },
    
    // Set JWT in both cookie AND localStorage
    setToken: function(jwt, maxAgeDays = 365) {
        this._setCookie(jwt, maxAgeDays);
        
        // Also store in localStorage as backup
        try {
            localStorage.setItem(this.STORAGE_KEY, jwt);
        } catch (e) {
            console.warn('Could not save JWT to localStorage:', e);
        }
    },
    
    // Internal: set cookie only
    _setCookie: function(jwt, maxAgeDays = 365) {
        const maxAge = maxAgeDays * 24 * 60 * 60;
        const isSecure = window.location.protocol === 'https:';
        // Use Lax for HTTPS through tunnels, works better than Strict
        const sameSite = isSecure ? 'Lax; Secure' : 'Lax';
        document.cookie = `${this.COOKIE_NAME}=${jwt}; path=/; max-age=${maxAge}; SameSite=${sameSite}`;
    },
    
    // Clear JWT from both cookie AND localStorage
    clearToken: function() {
        document.cookie = `${this.COOKIE_NAME}=; path=/; max-age=0`;
        try {
            localStorage.removeItem(this.STORAGE_KEY);
        } catch (e) {
            // Ignore
        }
    },
    
    // Get auth headers for fetch requests
    // Always include Authorization header (more reliable than cookies through tunnels)
    getAuthHeaders: function() {
        const token = this.getToken();
        if (token) {
            return { 'Authorization': 'Bearer ' + token };
        }
        return {};
    },
    
    // Authenticated fetch wrapper
    // ALWAYS sends Authorization header (doesn't rely on cookies)
    fetch: async function(url, options = {}) {
        // Object.assign, not object spread: spread needs Chrome 60 and the head-unit floor is 58.
        const headers = Object.assign({}, options.headers, this.getAuthHeaders());
        
        const response = await fetch(url, Object.assign({}, options, { headers }));
        
        // Handle 401 - redirect to login
        if (response.status === 401) {
            const currentPath = window.location.pathname + window.location.search;
            window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
            throw new Error('Unauthorized');
        }
        
        return response;
    },
    
    // Check auth status from server
    checkStatus: async function() {
        try {
            const response = await fetch('/auth/status', {
                headers: this.getAuthHeaders()
            });
            return await response.json();
        } catch (e) {
            console.error('Auth status check failed:', e);
            return { deviceId: null };
        }
    },
    
    // Logout
    logout: async function() {
        // Sign out on this phone first: a car that can't be reached must not keep the session or the copy here.
        const headers = this.getAuthHeaders();
        this.clearToken();
        await this.clearOfflineCopy();
        try {
            await Promise.race([
                fetch('/auth/logout', { method: 'POST', headers: headers, keepalive: true }),
                new Promise(function(resolve) { setTimeout(resolve, 3000); })
            ]);
        } catch (e) {
            // Ignore errors
        }
        window.location.href = '/login.html';
    },
    
    // Best effort: drop the phone's offline copy, holding logout up for at most 300 ms.
    clearOfflineCopy: function() {
        // The copy only exists on https tunnel origins; the in-car WebView has no worker to wait for.
        if (window.location.protocol !== 'https:') return Promise.resolve();
        const message = { type: 'od-offline-clear' };
        const deadline = new Promise(function(resolve) { setTimeout(resolve, 300); });
        const work = [];
        try {
            const sw = navigator.serviceWorker;
            if (sw && sw.controller) {
                sw.controller.postMessage(message);
            } else if (sw && sw.ready) {
                // No controller yet (first load, hard reload): reach the worker once it is ready.
                work.push(Promise.race([sw.ready, deadline]).then(function(registration) {
                    if (registration && registration.active) registration.active.postMessage(message);
                }).catch(function() {}));
            }
        } catch (e) {
            // No service worker here
        }
        try {
            if (window.caches && typeof window.caches.delete === 'function') {
                work.push(Promise.resolve(window.caches.delete('overdrive-offline-v1')).catch(function() {}));
            }
        } catch (e) {
            // Storage blocked
        }
        if (!work.length) return Promise.resolve();
        return Promise.race([Promise.all(work), deadline]);
    },

    // When this browser's sign-in runs out (epoch ms); 0 when there is none or it has expired.
    sessionExpiry: function() {
        const token = this.getToken();
        if (!token) return 0;
        try {
            const payload = JSON.parse(new TextDecoder().decode(this.b64urlDecode(String(token).split('.')[1])));
            const exp = Number(payload && payload.exp) * 1000;
            return isFinite(exp) && exp > Date.now() ? exp : 0;
        } catch (e) {
            return 0;
        }
    },

    // The offline copy lasts only as long as this sign-in; exp 0 makes the worker drop it.
    syncOfflineSession: function() {
        if (window.location.protocol !== 'https:') return;
        const message = { type: 'od-session', exp: this.sessionExpiry() };
        try {
            const sw = navigator.serviceWorker;
            if (!sw) return;
            if (sw.controller) sw.controller.postMessage(message);
            if (sw.ready) {
                sw.ready.then(function(registration) {
                    const active = registration && registration.active;
                    if (active && active !== sw.controller) active.postMessage(message);
                }).catch(function() {});
            }
        } catch (e) {
            // No service worker here
        }
    },

    // A saved copy with no valid sign-in: hide it, drop it, sign in again.
    leaveOfflineCopy: function() {
        if (this._leavingCopy) return;
        this._leavingCopy = true;
        try {
            document.documentElement.style.visibility = 'hidden';
        } catch (e) {
            // Nothing to hide yet
        }
        const path = window.location.pathname + window.location.search;
        this.clearOfflineCopy().then(function() {
            window.location.replace('/login.html?redirect=' + encodeURIComponent(path));
        });
    },

    // base64url without padding. Accepts an ArrayBuffer, a typed array or a string (as UTF-8).
    b64urlEncode: function(data) {
        let binary = '';
        if (typeof data === 'string') {
            binary = unescape(encodeURIComponent(data));
        } else {
            const bytes = ArrayBuffer.isView(data)
                ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
                : new Uint8Array(data);
            for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        }
        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    },

    b64urlDecode: function(text) {
        let base64 = String(text).replace(/-/g, '+').replace(/_/g, '/');
        while (base64.length % 4) base64 += '=';
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return bytes.buffer;
    },
    
    // Redirect to login if not authenticated
    requireAuth: function() {
        if (!this.isAuthenticated()) {
            const currentPath = window.location.pathname + window.location.search;
            window.location.href = '/login.html?redirect=' + encodeURIComponent(currentPath);
            return false;
        }
        return true;
    }
};

// SOTA: Override global fetch to automatically inject Authorization header.
// This ensures all API calls work through tunnels (where cookies get dropped due to SameSite policy).
// Only injects for same-origin or relative URL requests (not external APIs like ABRP/weather).
(function() {
    const originalFetch = window.fetch;
    window.fetch = function(url, options) {
        // Only inject auth for relative URLs or same-origin requests
        if (typeof url === 'string' && (url.startsWith('/') || url.startsWith(window.location.origin))) {
            const token = BYDAuth.getToken();
            if (token) {
                options = options || {};
                options.headers = options.headers || {};
                // Don't override if Authorization is already set
                if (!options.headers['Authorization'] && !options.headers['authorization']) {
                    if (options.headers instanceof Headers) {
                        if (!options.headers.has('Authorization')) {
                            options.headers.set('Authorization', 'Bearer ' + token);
                        }
                    } else {
                        options.headers['Authorization'] = 'Bearer ' + token;
                    }
                }
            }
        }
        const isRequest = typeof Request === 'function' && url instanceof Request;
        const href = typeof url === 'string' ? url : (isRequest ? url.url : null);
        if (href === null || !isOwnOrigin(href)) return originalFetch.call(this, url, options);
        const method = String((options && options.method) || (isRequest ? url.method : 'GET')).toUpperCase();
        const write = method !== 'GET' && method !== 'HEAD';
        // While the car is known to be offline a command or save is refused at once, never queued for later.
        if (write && carOffline() && pathOf(href).indexOf('/auth/') !== 0) {
            return Promise.resolve(codeReply(503, 'car_offline', CODE_TEXT.car_offline));
        }
        // A Request's body can be read once: keep a copy in case a step-up retry needs it.
        let spare = null;
        if (isRequest) {
            try { spare = url.clone(); } catch (e) { spare = null; }
        }
        const pending = originalFetch.call(this, url, options);
        const request = { self: this, url: href, isRequest: isRequest, input: spare, options: options };
        const result = pending.then(function(response) { return inspect(response, request, true); }, function(err) {
            // A load the page didn't cancel itself.
            if (!write && isPageData(pathOf(href)) && !(err && err.name === 'AbortError')) noteLoadFailed();
            throw err;
        });
        if (!write) return result;
        // A page reload must not cut off a command or save that is still on its way (core.js checks this).
        BYDAuth.pendingWrites++;
        const settled = function() { BYDAuth.pendingWrites = Math.max(0, BYDAuth.pendingWrites - 1); };
        result.then(settled, settled);
        return result;
    };

    // Replies from this car: offline copy notice, tunnel error pages and the Face ID / fingerprint
    // step-up (428 -> confirm sheet -> passkey -> one retry with X-OD-StepUp).
    // Synthetic and relabelled replies carry code = machine code, error = message = localized text.
    const CODE_TEXT = {
        car_unreachable: ['offline.car_unreachable', "Can't reach the car. The command may not have been delivered."],
        signed_out: ['login.enter_code', 'Please enter your access code'],
        car_offline: ['offline.not_sent', "The car is offline, so this wasn't sent. Try again when it's back."],
        step_up_cancelled: ['stepup.cancelled', "Cancelled. The action wasn't sent."],
        step_up_busy: ['stepup.waiting', 'Waiting for Face ID or fingerprint…'],
        step_up_failed: ['stepup.prompt_failed', "Couldn't confirm it's you. The action wasn't sent."],
        step_up_needs_secure_link: ['stepup.needs_secure_link_action',
            'This action needs Face ID or fingerprint. Use your secure link or the car screen.'],
        step_up_car_only: ['stepup.car_only',
            'While the Face ID or fingerprint lock is on, this works only from the car screen.'],
        step_up_remote_view: ['stepup.remote_view_action', 'Stop remote screen control first.'],
        step_up_wrong_code: ['stepup.wrong_code', "That code didn't work. Check it and try again."],
        step_up_too_many_codes: ['stepup.too_many_codes', 'Too many wrong codes. Start again on the car screen.'],
        step_up_code_expired: ['stepup.pair_expired', 'The code expired. Start again.'],
        step_up_already_added: ['stepup.already_added', 'This phone is already added.'],
        step_up_no_phone: ['stepup.enable_needs_phone', 'Add a phone first.'],
        step_up_store_unreadable: ['stepup.store_unreadable',
            "The lock settings couldn't be read. Turn the lock off here to reset them."],
        step_up_too_many: ['stepup.too_many_phones', "You've added the most phones allowed. Remove one first."],
        step_up_unknown_phone: ['stepup.unknown_phone', "That phone isn't added anymore."],
        step_up_link_checking: ['stepup.link_checking', 'Checking the secure link. Try again in a few seconds.'],
        step_up_error: ['stepup.error_generic', "Couldn't save. Try again."],
        step_up_save_failed: ['stepup.error_generic', "Couldn't save. Try again."]
    };
    // On the registration endpoints these codes are about adding the phone, not about an action.
    const REGISTER_TEXT = {
        step_up_needs_secure_link: ['stepup.need_secure_link',
            'To add this phone, open OverDrive through your secure link: zrok, your Cloudflare domain or Tailscale HTTPS.'],
        step_up_failed: ['stepup.error_generic', "Couldn't save. Try again."]
    };
    const NOT_SUPPORTED = ['stepup.not_supported', "This browser can't use Face ID or fingerprint here."];
    const CONFIRM_TITLE = {
        unlock: 'Unlock the car?', trunkOpen: 'Open the trunk?', powerOff: 'Switch the car off?',
        powerOn: 'Switch the car on?', run: 'Run this action?', config: 'Save this change?',
        settings: 'Change the lock settings?', screenControl: 'Control the car screen?',
        keyInject: 'Send a key to the car screen?'
    };
    const STATUS_TEXT = { 403: 'Forbidden', 503: 'Service Unavailable' };
    // True while the confirm sheet or the passkey prompt is up; another 428 then gets step_up_busy at once.
    let sheetOpen = false;
    // Gates whose heading is generic: the car's own description says what will run or change.
    const DESCRIBED_GATES = {
        run: true, config: true, settings: true, screenControl: true, keyInject: true, cabinAudio: true
    };

    function has(map, key) {
        return typeof key === 'string' && Object.prototype.hasOwnProperty.call(map, key);
    }

    function isOwnOrigin(url) {
        if (url.charAt(0) === '/') return url.charAt(1) !== '/' && url.charAt(1) !== '\\';
        const origin = window.location.origin;
        if (url.indexOf(origin) !== 0) return false;
        const next = url.charAt(origin.length);
        return next === '' || next === '/' || next === '?' || next === '#';
    }

    function pathOf(url) {
        let path = url.charAt(0) === '/' ? url : url.slice(window.location.origin.length);
        const cut = path.search(/[?#]/);
        if (cut >= 0) path = path.slice(0, cut);
        return path || '/';
    }

    // BYD.i18n may not exist yet: auth.js loads before core.js on some pages.
    function text(key, fallback) {
        try {
            if (window.BYD && BYD.i18n && typeof BYD.i18n.t === 'function') {
                const value = BYD.i18n.t(key);
                if (value && value !== key) return value;
            }
        } catch (e) {
            // Catalog not ready
        }
        return fallback;
    }

    // The page's own data: catalogs, /status and core's sidebar range refresh themselves.
    function isPageData(path) {
        return path.indexOf('/api/') === 0 && path !== '/api/trips/range';
    }

    function isRead(request) {
        const options = request.options;
        const method = String((options && options.method) || (request.isRequest && request.input ? request.input.method : 'GET'));
        return /^(GET|HEAD)$/i.test(method);
    }

    // A page whose loads failed during an outage gets reloaded once the car is back (core.js decides).
    function noteLoadFailed() {
        try {
            if (window.location.protocol === 'https:' && window.BYD && BYD.offline
                    && typeof BYD.offline.noteLoadFailed === 'function') {
                BYD.offline.noteLoadFailed();
            }
        } catch (e) {
            // Best effort
        }
    }

    // core.js's banner knows when the car is offline; on http (in the car) it never is.
    function carOffline() {
        try {
            return window.location.protocol === 'https:' && !!(window.BYD && BYD.offline
                && typeof BYD.offline.linkState === 'function' && BYD.offline.linkState() === 'offline');
        } catch (e) {
            return false;
        }
    }

    function jsonReply(status, body, extraHeaders, base) {
        const headers = new Headers(base ? base.headers : undefined);
        headers.delete('Content-Length');
        headers.delete('Content-Encoding');
        headers.set('Content-Type', 'application/json');
        if (extraHeaders) {
            Object.keys(extraHeaders).forEach(function(name) { headers.set(name, extraHeaders[name]); });
        }
        return new Response(JSON.stringify(body), {
            status: status,
            statusText: base ? base.statusText : (STATUS_TEXT[status] || ''),
            headers: headers
        });
    }

    function codeReply(status, code, entry, extraHeaders) {
        const message = text(entry[0], entry[1]);
        return jsonReply(status, { success: false, ok: false, code: code, error: message, message: message },
            extraHeaders);
    }

    function stepUpFailed(entry) {
        return codeReply(403, 'step_up_failed', entry || CODE_TEXT.step_up_failed);
    }

    function stepUpCancelled() {
        return codeReply(403, 'step_up_cancelled', CODE_TEXT.step_up_cancelled);
    }

    function readJson(response) {
        try {
            return response.clone().json().catch(function() { return null; });
        } catch (e) {
            return Promise.resolve(null);
        }
    }

    function inspect(response, request, mayAsk) {
        const path = pathOf(request.url);
        let offlineAt = null;
        try {
            offlineAt = response.headers.get('X-OverDrive-Offline');
            if (offlineAt !== null && !BYDAuth.sessionExpiry()) {
                BYDAuth.leaveOfflineCopy();
                return codeReply(401, 'signed_out', CODE_TEXT.signed_out);
            }
            if (offlineAt !== null && window.BYD && BYD.offline && typeof BYD.offline.show === 'function') {
                // 'unknown' while the phone is still reaching for the car, 'down' once it can't.
                const link = response.headers.get('X-OverDrive-Link') === 'unknown' ? 'unknown' : 'down';
                if (isPageData(path)) BYD.offline.show(Number(offlineAt), { data: true, link: link });
                else BYD.offline.show(Number(offlineAt), { link: link });
            }
        } catch (e) {
            // The notice is best effort
        }
        const status = response.status;
        const secure = window.location.protocol === 'https:';
        // Only the car stamps X-OverDrive: an un-marked error or HTML page is the tunnel's own.
        if (secure && offlineAt === null && !response.headers.has('X-OverDrive')) {
            const html = /text\/html/i.test(response.headers.get('Content-Type') || '');
            if (status >= 500 || (html && (status >= 400 || status === 200))) {
                if (isRead(request) && isPageData(path)) noteLoadFailed();
                return codeReply(503, 'car_unreachable', CODE_TEXT.car_unreachable, { 'X-OverDrive-Unreachable': '1' });
            }
        }
        if (status < 400) return response;
        return readJson(response).then(function(data) {
            if (!data || typeof data !== 'object') return response;
            const code = typeof data.code === 'string' ? data.code : data.error;
            if (typeof code !== 'string' || code.indexOf('step_up_') !== 0) return response;
            if (status === 428 && code === 'step_up_required') {
                if (!secure) return codeReply(403, 'step_up_needs_secure_link', CODE_TEXT.step_up_needs_secure_link);
                return mayAsk ? stepUp(data.stepUp, request) : stepUpFailed();
            }
            return relabel(response, data, code, path);
        });
    }

    function relabel(response, data, code, path) {
        let entry = path.indexOf('/api/stepup/register/') === 0 && has(REGISTER_TEXT, code)
            ? REGISTER_TEXT[code]
            : (has(CODE_TEXT, code) ? CODE_TEXT[code] : null);
        // A pairing ended by wrong codes comes back as step_up_wrong_code with its own wording.
        if (code === 'step_up_wrong_code' && /too many/i.test(String(data.message || ''))) {
            entry = CODE_TEXT.step_up_too_many_codes;
        }
        const server = typeof data.message === 'string' ? data.message : '';
        const message = entry ? text(entry[0], server || entry[1]) : (server || code);
        data.code = code;
        data.error = message;
        data.message = message;
        return jsonReply(response.status, data, null, response);
    }

    // A ReadableStream body is the only kind that can't be sent twice.
    function canResend(body) {
        return body === undefined || body === null || typeof body === 'string'
            || (typeof URLSearchParams === 'function' && body instanceof URLSearchParams)
            || (typeof FormData === 'function' && body instanceof FormData)
            || (typeof Blob === 'function' && body instanceof Blob)
            || Object.prototype.toString.call(body) === '[object ArrayBuffer]'
            || (typeof ArrayBuffer === 'function' && ArrayBuffer.isView(body));
    }

    function stepUp(info, request) {
        const options = request.options || {};
        const resendable = request.isRequest ? !!request.input && canResend(options.body) : canResend(options.body);
        if (!info || !info.challengeId || !info.publicKey || !resendable) return stepUpFailed();
        if (!window.PublicKeyCredential || !navigator.credentials
                || typeof navigator.credentials.get !== 'function') {
            return stepUpFailed(NOT_SUPPORTED);
        }
        if (sheetOpen) return codeReply(403, 'step_up_busy', CODE_TEXT.step_up_busy);
        if (!document.body) return stepUpFailed();
        return confirmWithPasskey(info).then(function(assertion) {
            // Init headers replace a Request's own, so start from whichever the first send used.
            const headers = new Headers(options.headers !== undefined ? options.headers
                : (request.input ? request.input.headers : undefined));
            const token = BYDAuth.getToken();
            if (token && !headers.has('Authorization')) headers.set('Authorization', 'Bearer ' + token);
            headers.set('X-OD-StepUp', assertion);
            const retry = Object.assign({}, options, { headers: headers });
            return originalFetch.call(request.self, request.input || request.url, retry).then(function(response) {
                return inspect(response, request, false);
            });
        }, function(err) {
            const name = err && err.name;
            if (name === 'NotAllowedError' || name === 'AbortError') return stepUpCancelled();
            return stepUpFailed(name === 'NotSupportedError' ? NOT_SUPPORTED : null);
        });
    }

    function decodeRequest(pk) {
        const publicKey = Object.assign({}, pk);
        publicKey.challenge = BYDAuth.b64urlDecode(pk.challenge);
        if (Array.isArray(pk.allowCredentials)) {
            publicKey.allowCredentials = pk.allowCredentials.map(function(cred) {
                return Object.assign({}, cred, { id: BYDAuth.b64urlDecode(cred.id) });
            });
        }
        return publicKey;
    }

    function encodeAssertion(challengeId, cred) {
        const r = cred.response;
        return BYDAuth.b64urlEncode(JSON.stringify({
            challengeId: challengeId,
            id: BYDAuth.b64urlEncode(cred.rawId),
            clientDataJSON: BYDAuth.b64urlEncode(r.clientDataJSON),
            authenticatorData: BYDAuth.b64urlEncode(r.authenticatorData),
            signature: BYDAuth.b64urlEncode(r.signature),
            userHandle: r.userHandle ? BYDAuth.b64urlEncode(r.userHandle) : ''
        }));
    }

    function sheetButton(label, primary) {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.style.cssText = 'display:block;width:100%;min-height:48px;margin:8px 0 0;padding:12px 16px;' +
            'border:0;border-radius:9999px;font:inherit;font-size:16px;font-weight:600;cursor:pointer;' +
            (primary ? 'background:var(--primary,#5DDBB6);color:var(--on-primary,#003828);'
                : 'background:var(--surface-container-highest,#2F3432);color:var(--on-surface,#DEE4E0);');
        return button;
    }

    // Only a tap on the sheet's button starts the passkey prompt: browsers require a user gesture.
    function confirmWithPasskey(info) {
        return new Promise(function(resolve, reject) {
            const previous = document.activeElement;
            const gate = typeof info.gate === 'string' ? info.gate : '';
            const scrim = document.createElement('div');
            const sheet = document.createElement('div');
            const title = document.createElement('h2');
            const detail = document.createElement('p');
            const status = document.createElement('p');
            const confirm = sheetButton(text('stepup.confirm_button', 'Confirm with Face ID or fingerprint'), true);
            const cancel = sheetButton(text('stepup.cancel', 'Cancel'), false);
            let controller = null;
            let done = false;
            // The car keeps a challenge for 120 s; a sheet left open longer could only fail.
            // A prompt already up is left to its own timeout.
            const expiry = setTimeout(function() { if (!confirm.disabled) dismiss(); }, 115000);

            function finish(settle, value) {
                if (done) return;
                done = true;
                clearTimeout(expiry);
                document.removeEventListener('keydown', onKey, true);
                document.removeEventListener('focusin', onFocus, true);
                if (scrim.parentNode) scrim.parentNode.removeChild(scrim);
                sheetOpen = false;
                try {
                    if (previous && typeof previous.focus === 'function') previous.focus();
                } catch (e) {
                    // Focus target gone
                }
                settle(value);
            }
            function dismiss() {
                if (controller) {
                    try { controller.abort(); } catch (e) { /* already settled */ }
                }
                const cancelled = new Error('Cancelled');
                cancelled.name = 'AbortError';
                finish(reject, cancelled);
            }
            function onKey(e) {
                if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                    e.preventDefault();
                    e.stopPropagation();
                    dismiss();
                } else if (e.key === 'Tab' || e.keyCode === 9) {
                    const items = [confirm, cancel].filter(function(b) { return !b.disabled; });
                    const at = items.indexOf(document.activeElement);
                    e.preventDefault();
                    items[(at + (e.shiftKey ? items.length - 1 : 1)) % items.length].focus();
                }
            }
            function onFocus(e) {
                if (!sheet.contains(e.target)) (confirm.disabled ? cancel : confirm).focus();
            }

            try {
                sheetOpen = true;
                scrim.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:50000;' +
                    'background:rgba(0,0,0,0.55);display:-webkit-flex;display:flex;' +
                    '-webkit-align-items:flex-end;align-items:flex-end;' +
                    '-webkit-justify-content:center;justify-content:center;';
                sheet.setAttribute('role', 'dialog');
                sheet.setAttribute('aria-modal', 'true');
                sheet.setAttribute('aria-labelledby', 'odStepUpTitle');
                sheet.setAttribute('aria-describedby', 'odStepUpStatus');
                sheet.style.cssText = 'box-sizing:border-box;width:100%;max-width:480px;padding:24px 20px 20px;' +
                    'padding-bottom:calc(20px + env(safe-area-inset-bottom, 0px));border-radius:24px 24px 0 0;' +
                    'background:var(--surface-container-high,#242927);color:var(--on-surface,#DEE4E0);' +
                    'box-shadow:0 -4px 24px rgba(0,0,0,0.35);';
                title.id = 'odStepUpTitle';
                title.style.cssText = 'margin:0 0 4px;font-size:20px;font-weight:700;line-height:1.3;';
                title.textContent = has(CONFIRM_TITLE, gate)
                    ? text('stepup.confirm_' + gate, CONFIRM_TITLE[gate])
                    : text('stepup.confirm_generic', 'Confirm this action?');
                const description = typeof info.description === 'string' ? info.description.trim() : '';
                if (has(DESCRIBED_GATES, gate) && description) {
                    detail.style.cssText = 'margin:0 0 4px;font-size:15px;line-height:1.4;opacity:0.85;' +
                        'overflow-wrap:break-word;word-wrap:break-word;';
                    detail.textContent = description;
                }
                status.id = 'odStepUpStatus';
                status.setAttribute('aria-live', 'polite');
                status.style.cssText = 'min-height:1.4em;margin:0 0 8px;font-size:14px;line-height:1.4;opacity:0.8;';
                sheet.appendChild(title);
                if (detail.textContent) sheet.appendChild(detail);
                sheet.appendChild(status);
                sheet.appendChild(confirm);
                sheet.appendChild(cancel);
                scrim.appendChild(sheet);

                confirm.addEventListener('click', function() {
                    if (done || confirm.disabled) return;
                    let request;
                    try {
                        request = { publicKey: decodeRequest(info.publicKey) };
                    } catch (e) {
                        finish(reject, e);
                        return;
                    }
                    controller = typeof AbortController === 'function' ? new AbortController() : null;
                    if (controller) request.signal = controller.signal;
                    confirm.disabled = true;
                    confirm.style.opacity = '0.6';
                    status.textContent = text('stepup.waiting', 'Waiting for Face ID or fingerprint…');
                    let pending;
                    try {
                        pending = navigator.credentials.get(request);
                    } catch (e) {
                        pending = Promise.reject(e);
                    }
                    Promise.resolve(pending).then(function(cred) {
                        finish(resolve, encodeAssertion(info.challengeId, cred));
                    }).catch(function(err) {
                        finish(reject, err);
                    });
                });
                cancel.addEventListener('click', dismiss);
                scrim.addEventListener('click', function(e) { if (e.target === scrim) dismiss(); });
                document.addEventListener('keydown', onKey, true);
                document.addEventListener('focusin', onFocus, true);
                document.body.appendChild(scrim);
                confirm.focus();
            } catch (e) {
                finish(reject, e);
            }
        });
    }
})();

// XMLHttpRequest writes count in pendingWrites too, so an automatic reload can't cut them off.
(function() {
    if (window.location.protocol !== 'https:' || typeof XMLHttpRequest !== 'function') return;
    const proto = XMLHttpRequest.prototype;
    const open = proto.open;
    const send = proto.send;
    if (typeof open !== 'function' || typeof send !== 'function') return;
    function ownOrigin(url) {
        try {
            return new URL(String(url), window.location.href).origin === window.location.origin;
        } catch (e) {
            return false;
        }
    }
    proto.open = function(method, url) {
        // Reopening ends the previous request without loadend.
        if (this.__odSettle) this.__odSettle();
        this.__odWrite = !/^(GET|HEAD)$/i.test(String(method)) && ownOrigin(url);
        return open.apply(this, arguments);
    };
    proto.send = function() {
        if (!this.__odWrite || this.__odSettle) return send.apply(this, arguments);
        const xhr = this;
        // A loadend arriving after a reopen inside the request's own handlers is not ours.
        const onEnd = function() { if (xhr.readyState !== 1) settle(); };
        const settle = function() {
            xhr.__odSettle = null;
            xhr.removeEventListener('loadend', onEnd);
            BYDAuth.pendingWrites = Math.max(0, BYDAuth.pendingWrites - 1);
        };
        xhr.__odSettle = settle;
        BYDAuth.pendingWrites++;
        xhr.addEventListener('loadend', onEnd);
        try {
            return send.apply(this, arguments);
        } catch (e) {
            settle();
            throw e;
        }
    };
})();

// Each https page tells the worker how long its sign-in lasts; a copied page without one signs in again.
(function() {
    if (window.location.protocol !== 'https:') return;
    BYDAuth.syncOfflineSession();
    try {
        const sw = navigator.serviceWorker;
        if (sw && sw.addEventListener) {
            sw.addEventListener('controllerchange', function() { BYDAuth.syncOfflineSession(); });
        }
        if (/\/login\.html$/.test(window.location.pathname)) return;
        if (document.querySelector('meta[name="od-offline-shell"]') && !BYDAuth.sessionExpiry()) {
            BYDAuth.leaveOfflineCopy();
        }
    } catch (e) {
        // Best effort; the worker applies the same rule
    }
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BYDAuth;
}
