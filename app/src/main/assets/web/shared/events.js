/**
 * BYD Champ - Events Module
 * Calendar-based recording browser with video playback, pagination & thumbnails
 */

window.BYD = window.BYD || {};

BYD.events = {
    currentDate: new Date(),
    selectedDate: null,
    currentFilter: 'all',
    recordings: [],
    totalCount: 0,
    datesWithRecordings: new Map(),
    currentPage: 1,
    pageSize: 12,
    totalPages: 1,
    
    // Multi-select state
    selectMode: false,
    selectedFiles: new Set(),

    // In-flight recording state. Populated only when the user is deep-linked
    // (?file=…) from a fresh notification AND the referenced file isn't yet
    // in the recordings list (it's still <name>.mp4.tmp on disk). The
    // recordings page renders a pinned placeholder card while this is set,
    // and polls /api/recordings/inflight/<file> every 2s to detect when the
    // post-record window finishes and the rename completes.
    inflightFilename: null,
    inflightPollHandle: null,

    // v3 actor/severity/proximity filter state (item 6). Empty values = no filter.
    actorFilter: { class: '', severity: '', proximity: '' },

    // Storage-volume filter. '' = all volumes (default — the index already
    // spans internal + SD + USB). Otherwise 'INTERNAL' / 'SD_CARD' / 'USB',
    // sent as the `storage` param to /api/recordings + /api/recordings/places.
    // Surfaces clips across every storage location and lets the user narrow to
    // one (e.g. "only what's on the SD card").
    storageFilter: '',

    // Free-text place substring filter — separate from actorFilter.place
    // (which is the exact-match chip selection). Routed through
    // /api/recordings + /api/recordings/places as `placeContains=` and
    // resolved server-side via RecordingsIndex.Filter.placeContains
    // (substring across short / medium / displayName labels, lowercase).
    // Stacks with the chip filter: if both are set, the SQL AND-chains
    // them so the chip wins (it's stricter), but the UI keeps both lit.
    // Lives outside actorFilter because it has its own input affordance,
    // its own debounce + state machine, and gets URL-persisted (q=…)
    // independently of the chip.
    placeContainsQuery: '',
    _placeSearchDebounceMs: 250,
    _placeSearchTimer: null,
    _placeSearchWired: false,

    // Last observed player.muted value, used by the volumechange handler in
    // playVideo() to debounce slider-drag events from actual mute-button
    // transitions. Initialized lazily on first playVideo so we can tell the
    // first listener invocation apart from a real transition.
    _lastMutedState: null,

    // Quadrant zoom state. Recordings are written as a single 2x2 mosaic
    // (Front=TL, Right=TR, Rear=BL, Left=BR by GpuMosaicRecorder convention);
    // this lets the user zoom to one camera at native resolution without a
    // re-encode. Persisted across clip changes via localStorage so a user
    // reviewing a sequence stays on the same camera until they switch back.
    // Valid values: 'all' | 'front' | 'right' | 'rear' | 'left'.
    //
    // Casing note: lowercase here for CSS class compatibility (.zoom-front
    // etc.). The native counterpart (ZoomableVideoView.Quadrant) uses
    // UPPERCASE for Kotlin idiom + SharedPreferences storage. The two
    // surfaces don't share storage, so this is a local convention only.
    _quadrant: 'all',
    // Composition layout of the currently-open clip ('standard' | 'dashcam'),
    // read from the sidecar in loadTimeline. Drives the double-tap hit-test
    // (_quadrantAtFit) and the `dashcam` CSS class on the wrap (which selects
    // the dashcam zoom rects). Mirrors ZoomableVideoView.Layout on native.
    _layout: 'standard',
    _quadrantWired: false,
    _QUADRANT_LS_KEY: 'byd.events.quadrant',
    _QUADRANT_VALID: ['all', 'front', 'right', 'rear', 'left'],

    // Auto-hide state for the floating quadrant pill. Idle → fade out
    // after this many ms; any pointer movement / tap on the video resets
    // the timer. Mirrors VideoPlayerFragment.OVERLAY_HIDE_DELAY (3 s).
    _QUADRANT_HIDE_MS: 3000,
    _quadrantHideTimer: null,

    recordingKey(rec) {
        return rec && rec.id ? rec.id : (rec ? rec.filename : '');
    },

    findRecording(key) {
        return this.recordings.find(rec => this.recordingKey(rec) === key)
            || this.recordings.find(rec => rec.filename === key);
    },

    /**
     * Read the recording.audioEnabled setting from the backend on every
     * call. Used by playVideo() to decide whether to default the mute
     * state to unmuted on first-ever clip review when the user has audio
     * recording enabled (so they can actually hear the captured audio
     * without a second tap).
     *
     * Deliberately uncached: the setting is toggled from recording.html,
     * and a stale init-time cache would leave the events page defaulting
     * future clips to unmuted after the user disabled audio (and vice
     * versa). The fetch is a cheap loopback (~5ms) and only fires once
     * per clip the user opens, so always-fresh is the simpler and safer
     * default.
     */
    async getAudioRecordingEnabled() {
        try {
            const resp = await fetch('/api/settings/audio-recording');
            const data = await resp.json();
            return data && data.success ? !!data.enabled : false;
        } catch (e) {
            return false;
        }
    },

    /**
     * One-time install of the quadrant-bar click handlers. Each button
     * exposes its identity via data-quadrant; we read it back and route
     * through setQuadrant so persistence + active-state highlight + DOM
     * class swap all happen in one place.
     */
    wireQuadrantBar() {
        if (this._quadrantWired) return;
        const bar = document.getElementById('quadrantBar');
        if (!bar) return;
        const self = this;
        bar.querySelectorAll('button[data-quadrant]').forEach(function(btn) {
            btn.addEventListener('click', function(e) {
                e.stopPropagation();
                const target = btn.getAttribute('data-quadrant') || 'all';
                // Tap-to-toggle: tapping the active quadrant returns to ALL.
                // Mirrors the native player's behavior so the surfaces feel
                // identical. The All button never toggles itself off.
                if (target === self._quadrant && target !== 'all') {
                    self.setQuadrant('all');
                } else {
                    self.setQuadrant(target);
                }
                // Reset the auto-hide timer so the user sees the active-
                // state flip before the bar fades.
                self.showQuadrantBar();
            });
        });

        // Auto-hide wiring. Re-show on any interaction with the player
        // surface — tap, pointer movement (desktop hover), focus on a
        // bar button. Hover on the bar itself keeps it visible until
        // the cursor leaves.
        bar.addEventListener('pointerenter', function() { self.showQuadrantBar(true); });
        bar.addEventListener('pointerleave', function() { self.showQuadrantBar(); });
        bar.addEventListener('focusin', function() { self.showQuadrantBar(true); });
        bar.addEventListener('focusout', function() { self.showQuadrantBar(); });
        // Restore persisted choice. localStorage may be denied in some
        // contexts (private mode, etc) — falls back to 'all' silently.
        try {
            const stored = localStorage.getItem(this._QUADRANT_LS_KEY);
            if (stored && this._QUADRANT_VALID.indexOf(stored) >= 0) {
                this._quadrant = stored;
            }
        } catch (e) { /* ignore */ }
        this._applyQuadrantClass();

        // Double-tap on the video = zoom into the tapped quadrant (or
        // reset to ALL when already zoomed). We DON'T use the browser's
        // `dblclick` event:
        //   - On touch devices Chrome/Safari often suppress dblclick
        //     when touch-action: manipulation is active (it absorbs the
        //     gesture for fast-tap and never synthesizes the second
        //     mousedown→click→dblclick chain).
        //   - On head-unit WebViews dblclick latency varies wildly.
        //
        // Instead we synthesize from pointerup events: two pointerups
        // within 320 ms whose down-up points are each <12 px from the
        // first one's point count as a double-tap. This works the same
        // on mouse, touch, and pen with one path.
        const player = document.getElementById('videoPlayer');
        if (player) {
            let lastTapTime = 0;
            let lastTapX = 0;
            let lastTapY = 0;
            let downTime = 0;
            let downX = 0;
            let downY = 0;
            let preTapPaused = null;
            const TAP_GAP_MS = 320;        // max gap between the two taps
            const TAP_MOVE_PX = 12;         // each tap must stay this still
            const TAP_DRIFT_PX = 30;        // 2nd tap point must be near 1st
            const LONG_TAP_MS = 500;        // anything longer isn't a tap

            const dist = (x1, y1, x2, y2) => Math.hypot(x1 - x2, y1 - y2);

            const onPointerDown = (e) => {
                // Mouse path: only left button counts as a tap.
                if (e.pointerType === 'mouse' && e.button !== 0) return;
                downTime = e.timeStamp;
                downX = e.clientX;
                downY = e.clientY;
                preTapPaused = player.paused;
                // Wake the auto-hidden quadrant bar so the user can see
                // and aim at it. Subsequent inactivity → fades again.
                self.showQuadrantBar();
            };

            // Desktop hover keeps the bar visible while the cursor is
            // on the player; touch devices skip this branch (no hover).
            const onPointerMove = () => self.showQuadrantBar();

            const onPointerUp = (e) => {
                if (e.pointerType === 'mouse' && e.button !== 0) return;
                const upX = e.clientX;
                const upY = e.clientY;
                const heldMs = e.timeStamp - downTime;
                if (heldMs > LONG_TAP_MS) return;            // long-press, not a tap
                if (dist(downX, downY, upX, upY) > TAP_MOVE_PX) return; // dragged

                const now = e.timeStamp;
                const gap = now - lastTapTime;
                if (lastTapTime > 0 && gap < TAP_GAP_MS &&
                    dist(lastTapX, lastTapY, upX, upY) < TAP_DRIFT_PX) {
                    // Confirmed double-tap. Single-tap toggled play/pause
                    // already (via the onSurfaceClick handler in
                    // bindTransport); revert that side-effect so the user
                    // sees "video keeps playing, view zooms".
                    if (preTapPaused !== null && player.paused !== preTapPaused) {
                        if (preTapPaused) {
                            player.pause();
                        } else {
                            const p = player.play();
                            if (p && typeof p.catch === 'function') p.catch(function() {});
                        }
                    }
                    preTapPaused = null;

                    // Already zoomed → reset to ALL. Same convention as
                    // the native player.
                    if (self._quadrant !== 'all') {
                        self.setQuadrant('all');
                    } else {
                        // Map the tap point to a quadrant. The wrapper's
                        // aspect-ratio is set inline to the source so the
                        // letterbox math reduces to a clean halves split,
                        // but we keep the fit-rect calc so a pre-metadata
                        // tap (no aspect-ratio yet) still works.
                        const rect = player.getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0) {
                            const vw = player.videoWidth || rect.width;
                            const vh = player.videoHeight || rect.height;
                            const fit = Math.min(rect.width / vw, rect.height / vh);
                            const dw = vw * fit;
                            const dh = vh * fit;
                            const dx = (rect.width - dw) / 2;
                            const dy = (rect.height - dh) / 2;
                            const lx = upX - rect.left;
                            const ly = upY - rect.top;
                            if (!(lx < dx || lx > dx + dw || ly < dy || ly > dy + dh)) {
                                self.setQuadrant(self._quadrantAtFit(lx - dx, ly - dy, dw, dh));
                            }
                        }
                    }
                    // Consume — don't let this tap also start a new pair.
                    lastTapTime = 0;
                    return;
                }

                // First tap (or stale gap) — remember and let the click
                // path do its play/pause toggle naturally.
                lastTapTime = now;
                lastTapX = upX;
                lastTapY = upY;
            };

            if (typeof window.PointerEvent !== 'undefined') {
                player.addEventListener('pointerdown', onPointerDown);
                player.addEventListener('pointerup', onPointerUp);
                player.addEventListener('pointermove', onPointerMove);
            } else {
                // touchend wrapper — synthesize a Pointer-shaped event.
                player.addEventListener('mousedown', onPointerDown);
                player.addEventListener('mouseup', onPointerUp);
                player.addEventListener('touchstart', (e) => {
                    const t = e.touches[0];
                    if (t) onPointerDown({ pointerType: 'touch', button: 0, timeStamp: e.timeStamp, clientX: t.clientX, clientY: t.clientY });
                }, { passive: true });
                player.addEventListener('touchend', (e) => {
                    const t = e.changedTouches[0];
                    if (t) onPointerUp({ pointerType: 'touch', button: 0, timeStamp: e.timeStamp, clientX: t.clientX, clientY: t.clientY });
                }, { passive: true });
            }
        }

        this._quadrantWired = true;
    },

    /**
     * Map a point inside the fitted video rect [0..dw, 0..dh] to a camera,
     * honouring the active composition [_layout]. STANDARD = a 2x2 halves
     * split; DASHCAM = the top 70% band → front, the bottom band split into
     * left/middle/right thirds → left/rear/right. Mirrors
     * ZoomableVideoView.quadrantAtPoint on the native side.
     */
    _quadrantAtFit(fx, fy, dw, dh) {
        if (this._layout === 'dashcam') {
            if (fy < 0.70 * dh) return 'front';
            const t = dw > 0 ? fx / dw : 0;
            if (t < 1 / 3) return 'left';
            if (t < 2 / 3) return 'rear';
            return 'right';
        }
        const left = fx < dw / 2;
        const top = fy < dh / 2;
        return top ? (left ? 'front' : 'right') : (left ? 'rear' : 'left');
    },

    /**
     * Authoritative setter for the active quadrant. Validates input,
     * persists to localStorage, repaints the active-button highlight, and
     * applies the CSS class on the video wrapper. The actual zoom is
     * driven by transform/transform-origin rules in events.html.
     */
    setQuadrant(target) {
        if (this._QUADRANT_VALID.indexOf(target) < 0) return;
        if (this._quadrant === target) return;
        this._quadrant = target;
        try {
            localStorage.setItem(this._QUADRANT_LS_KEY, target);
        } catch (e) { /* ignore */ }
        this._applyQuadrantClass();
    },

    /**
     * Sync the DOM with [_quadrant]: video wrapper class drives the
     * transform, button .active class drives the highlight. Pulled into
     * its own helper so both wireQuadrantBar (initial) and setQuadrant
     * (subsequent) share the exact same paint path.
     */
    _applyQuadrantClass() {
        const wrap = document.getElementById('videoPlayerWrap');
        if (wrap) {
            // Strip every zoom-* class then add the one we want. Keeps the
            // class list deterministic instead of accumulating cruft when
            // the user switches quadrants repeatedly.
            wrap.classList.remove('zoom-all', 'zoom-front', 'zoom-right', 'zoom-rear', 'zoom-left');
            wrap.classList.add('zoom-' + this._quadrant);
        }
        const bar = document.getElementById('quadrantBar');
        if (bar) {
            bar.querySelectorAll('button[data-quadrant]').forEach(function(btn) {
                const q = btn.getAttribute('data-quadrant');
                btn.classList.toggle('active', q === window.BYD.events._quadrant);
            });
        }
    },

    /**
     * Show the quadrant pill and (re)arm the auto-hide timer. Mirrors
     * VideoPlayerFragment.scheduleOverlayHide. Cancellable by passing
     * keepVisible=true (used by the hover path so the pill stays put
     * while the cursor is parked on it).
     */
    showQuadrantBar(keepVisible) {
        const bar = document.getElementById('quadrantBar');
        if (!bar) return;
        bar.classList.remove('hidden');
        if (this._quadrantHideTimer) {
            clearTimeout(this._quadrantHideTimer);
            this._quadrantHideTimer = null;
        }
        if (keepVisible) return;
        const self = this;
        this._quadrantHideTimer = setTimeout(function() {
            const b = document.getElementById('quadrantBar');
            // Don't hide while the modal is closed (no relevance) or while
            // the user is clearly mid-interaction (a button has hover/focus).
            if (!b) return;
            const modal = document.getElementById('videoModal');
            if (!modal || !modal.classList.contains('active')) return;
            if (b.matches(':hover') || b.contains(document.activeElement)) {
                self.showQuadrantBar();
                return;
            }
            b.classList.add('hidden');
        }, this._QUADRANT_HIDE_MS);
    },

    /**
     * Force-hide the pill (used on closeVideo so the next playVideo
     * starts from a clean state).
     */
    hideQuadrantBarNow() {
        const bar = document.getElementById('quadrantBar');
        if (bar) bar.classList.add('hidden');
        if (this._quadrantHideTimer) {
            clearTimeout(this._quadrantHideTimer);
            this._quadrantHideTimer = null;
        }
    },

    /**
     * Wire the free-text place-search input. Idempotent. Routes typing
     * through a 250ms debounce into placeContainsQuery, which is sent
     * as the `placeContains` param to /api/recordings + /api/recordings/places.
     *
     * <p>Why 250ms (vs the native landscape's 300): web typing is faster,
     * and the daemon-loopback fetch is ~5-15ms — the user notices the
     * latency more than the native fragment which has to traverse a
     * binder hop. 250ms keeps the UX responsive without making every
     * keystroke a fetch.
     *
     * <p>Lifecycle: bound once in init() AFTER the first loadRecordings
     * fires. Any restored query from the URL has already been applied
     * to that initial fetch (loadRecordings reads placeContainsQuery
     * directly) — we just need to mirror it into the input here.
     *
     * <p>The wrapper's `has-query` class drives the clear-X visibility
     * + focus-ring colour shift via CSS in events.html.
     */
    wirePlaceSearch() {
        if (this._placeSearchWired) return;
        const input = document.getElementById('placeSearchInput');
        const wrap = document.getElementById('placeSearchWrap');
        const clear = document.getElementById('placeSearchClear');
        if (!input || !wrap) return;
        const self = this;

        // Mirror restored query into the input + state class.
        if (this.placeContainsQuery) {
            input.value = this.placeContainsQuery;
            wrap.classList.add('has-query');
        }

        const apply = function(rawValue) {
            const v = (rawValue || '').slice(0, 64).trim();
            if (v === self.placeContainsQuery) return;
            self.placeContainsQuery = v;
            wrap.classList.toggle('has-query', !!v);
            // URL persistence: keep ?q=… in sync without polluting
            // history. replaceState avoids a back-button entry per
            // keystroke. Drop the param entirely when empty so the
            // shared URL stays clean.
            try {
                const u = new URL(window.location.href);
                if (v) u.searchParams.set('q', v);
                else u.searchParams.delete('q');
                window.history.replaceState({}, '', u.toString());
            } catch (e) { /* older WebViews — non-fatal */ }
            // Reset to first page so the new substring's results don't
            // land on a now-out-of-range page index. Mirrors how chip
            // taps go through loadRecordings → page=1.
            self.currentPage = 1;
            // Invalidate the chip-fetch memo so the next loadPlaceChips
            // call hits the network with the new placeContains value
            // even if every other dimension is unchanged.
            self._invalidatePlaceMemo();
            self.loadRecordings();
        };

        input.addEventListener('input', function() {
            const value = input.value;
            if (self._placeSearchTimer) clearTimeout(self._placeSearchTimer);
            self._placeSearchTimer = setTimeout(function() {
                self._placeSearchTimer = null;
                apply(value);
            }, self._placeSearchDebounceMs);
        });

        // Pressing Enter / IME confirm short-circuits the debounce so
        // power users get an instant fetch.
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                if (self._placeSearchTimer) {
                    clearTimeout(self._placeSearchTimer);
                    self._placeSearchTimer = null;
                }
                apply(input.value);
            } else if (e.key === 'Escape' && input.value) {
                e.preventDefault();
                input.value = '';
                if (self._placeSearchTimer) {
                    clearTimeout(self._placeSearchTimer);
                    self._placeSearchTimer = null;
                }
                apply('');
            }
        });

        if (clear) {
            clear.addEventListener('click', function(e) {
                e.preventDefault();
                input.value = '';
                if (self._placeSearchTimer) {
                    clearTimeout(self._placeSearchTimer);
                    self._placeSearchTimer = null;
                }
                apply('');
                // Return focus so subsequent typing keeps flowing.
                input.focus();
            });
        }

        this._placeSearchWired = true;
    },

    async init() {
        const urlParams = new URLSearchParams(window.location.search);
        const filterParam = urlParams.get('filter');
        if (filterParam && ['all', 'sentry', 'normal', 'proximity', 'replay'].includes(filterParam)) {
            this.currentFilter = filterParam;
            document.querySelectorAll('.filter-tab').forEach(tab => {
                tab.classList.toggle('active', tab.dataset.filter === filterParam);
            });
        }
        // Restore prior place-search query from URL (?q=…) so a refresh /
        // bookmark / shared deeplink keeps the user's narrowing intact.
        // Capped at 64 chars for the same reason the EditText hint is
        // short — anything longer is almost certainly paste-noise rather
        // than a place name, and the column LIKE pattern can't usefully
        // exploit it.
        const qParam = (urlParams.get('q') || '').slice(0, 64).trim();
        if (qParam) this.placeContainsQuery = qParam;
        const idParam = urlParams.get('id');
        const fileParam = urlParams.get('file');

        // iOS Web Push can't render options.image, so the SW forwards the
        // signed snapshot URL as ?hero=<encoded URL>. Render it inline at
        // the top of the page so the user sees the same picture they would
        // have seen on Android's banner.
        const heroParam = urlParams.get('hero');
        if (heroParam) this.renderHeroBanner(heroParam);

        this.renderCalendar();
        this.updateRecordingsTitle();
        this.updateCalendarButton();
        await this.loadDatesWithRecordings();
        await this.loadStorageStats();
        await this.loadRecordings();

        // Deep-link from a notification.
        //   - If the recording is finalized (in this.recordings): open the player.
        //   - If it's still being written (.mp4.tmp on disk): show a pinned
        //     placeholder card and poll for finalization.
        //   - If it's neither finalized nor in flight (older notification, file
        //     was deleted, etc.): silently no-op so we don't yank the user
        //     around.
        if (idParam) {
            const found = this.findRecording(idParam);
            if (found) this.playVideo(idParam);
        } else if (fileParam) {
            const found = this.recordings.find(r => r.filename === fileParam);
            if (found) {
                this.playVideo(fileParam);
            } else {
                this.checkAndPollInflight(fileParam);
            }
        }
        
        // Wire the quadrant zoom selector once. The buttons are static in
        // events.html; we install delegated click handlers + restore the
        // last-chosen quadrant from localStorage. Subsequent playVideo
        // calls just re-apply the persisted choice without re-binding.
        this.wireQuadrantBar();
        this.wirePlaceSearch();

        document.addEventListener('keydown', (e) => {
            // Quadrant hotkeys while the player modal is open. Mirrors the
            // numeric pad layout of the AVM cameras: 0 = all, then 1-4 walk
            // Front, Right, Rear, Left. Skip when the focus is on a form
            // input — typing "1" in a search box must not zoom the player.
            const tag = (e.target && e.target.tagName) || '';
            const isFormTarget = /^(input|textarea|select)$/i.test(tag) ||
                (e.target && e.target.isContentEditable);
            const modalOpen = document.getElementById('videoModal').classList.contains('active');
            if (modalOpen && !isFormTarget && !e.altKey && !e.ctrlKey && !e.metaKey) {
                if (e.key === '0') { this.setQuadrant('all'); return; }
                if (e.key === '1') { this.setQuadrant('front'); return; }
                if (e.key === '2') { this.setQuadrant('right'); return; }
                if (e.key === '3') { this.setQuadrant('rear'); return; }
                if (e.key === '4') { this.setQuadrant('left'); return; }
            }
            if (e.key === 'Escape') {
                this.closeVideo();
                this.closeCalendar();
            }
        });
        
        document.getElementById('videoModal').addEventListener('click', (e) => {
            if (e.target.id === 'videoModal') this.closeVideo();
        });
        
        document.getElementById('calendarPopup').addEventListener('click', (e) => {
            if (e.target.id === 'calendarPopup') this.closeCalendar();
        });
        
        const calendarPopup = document.getElementById('calendarPopup');
        const videoModal = document.getElementById('videoModal');
        
        const observer = new MutationObserver(() => {
            const isOpen = calendarPopup.classList.contains('active') || videoModal.classList.contains('active');
            document.body.style.overflow = isOpen ? 'hidden' : '';
        });
        
        observer.observe(calendarPopup, { attributes: true });
        observer.observe(videoModal, { attributes: true });

        // Stop inflight polling when the page is unloaded or hidden — without
        // this, setInterval keeps firing in the background after the user
        // navigates away (BFCache visit, swipe-to-back PWA gesture, etc.).
        // visibilitychange covers tab-hidden too, since the platform may not
        // run setInterval reliably while hidden anyway.
        const stopOnExit = () => this.stopInflightPolling();
        window.addEventListener('pagehide', stopOnExit);
        window.addEventListener('beforeunload', stopOnExit);
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden') { stopOnExit(); return; }
            // Coming back to the foreground: the index-down retry is a ONE-SHOT
            // timer, so stopOnExit() above didn't just pause it — it destroyed
            // the only thing that would ever recover the page. (The inflight
            // poller is a setInterval that other code re-establishes; this
            // isn't.) Without re-arming, the card sits on "Retrying
            // automatically…" forever after any backgrounding.
            if (this._indexDown && !this._indexDownRetryTimer) {
                this.scheduleIndexDownRetry(0);
            }
        });
    },

    /**
     * Inserts a full-bleed hero banner at the top of the page using the
     * pre-signed snapshot URL forwarded from the notification. Used as the
     * iOS Web Push fallback path because Safari ignores options.image.
     * The URL already carries a single-purpose signed token, so no auth
     * header is required and the browser fetches it directly.
     */
    renderHeroBanner(heroUrl) {
        try {
            // Idempotent: replace any existing banner so re-entries don't stack.
            var existing = document.getElementById('eventsHeroBanner');
            if (existing && existing.parentNode) {
                existing.parentNode.removeChild(existing);
            }
            var img = document.createElement('img');
            img.id = 'eventsHeroBanner';
            img.src = heroUrl;
            img.alt = '';
            img.style.cssText =
                'display:block;width:100%;max-height:42vh;object-fit:cover;' +
                'border-radius:20px;margin:12px auto 16px;' +
                'box-shadow:0 8px 24px rgba(0,0,0,0.35);';
            // Fail silently if the token expired — text-only fallback is fine.
            img.onerror = function () {
                if (img.parentNode) img.parentNode.removeChild(img);
            };
            // Insert above the filter tabs so it dominates the first viewport.
            var anchor = document.querySelector('.filter-tab');
            var host = anchor && anchor.parentNode ? anchor.parentNode : document.body;
            host.parentNode.insertBefore(img, host);
        } catch (e) { /* best-effort */ }
    },

    /** Idempotent stop — safe to call multiple times. */
    stopInflightPolling() {
        if (this.inflightPollHandle) {
            clearInterval(this.inflightPollHandle);
            this.inflightPollHandle = null;
        }
        // Index-down retry is a page-scoped poll too — drop it on exit so a
        // backgrounded tab doesn't keep waking the daemon.
        if (this._indexDownRetryTimer) {
            clearTimeout(this._indexDownRetryTimer);
            this._indexDownRetryTimer = null;
        }
    },

    /**
     * Markup for the "recordings index unavailable" card. Shared by the fetch
     * handler and renderRecordings() so every path that can repaint the list
     * while the index is down shows the same honest state.
     */
    _indexDownCardHtml() {
        return '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><div class="empty-title">' +
            BYD.i18n.t('events.empty_index_down_title') + '</div><div class="empty-text">' +
            BYD.i18n.t('events.empty_index_down_text') + '</div></div>';
    },

    /**
     * Schedule one retry of loadRecordings() after the daemon reported its
     * recordings index is down. Cancels any pending retry first (so callers
     * racing from pagination / filter taps can't stack timers) and backs off
     * 5s → 30s so a permanently-down index settles into a slow poll instead
     * of hammering the daemon every 5s forever.
     */
    scheduleIndexDownRetry(serverRetryMs) {
        if (this._indexDownRetryTimer) {
            clearTimeout(this._indexDownRetryTimer);
            this._indexDownRetryTimer = null;
        }
        var base = serverRetryMs || 5000;
        var attempt = Math.min(this._indexDownAttempt || 0, 3);
        var delay = Math.min(base * Math.pow(2, attempt), 30000);
        this._indexDownAttempt = (this._indexDownAttempt || 0) + 1;
        var self = this;
        this._indexDownRetryTimer = setTimeout(function () {
            self._indexDownRetryTimer = null;
            self.loadRecordings();
        }, delay);
    },
    
    toggleCalendar() {
        document.getElementById('calendarPopup').classList.toggle('active');
    },
    
    closeCalendar() {
        document.getElementById('calendarPopup').classList.remove('active');
    },
    
    updateCalendarButton() {
        const btn = document.getElementById('calendarToggle');
        const text = document.getElementById('calendarBtnText');
        
        if (this.selectedDate) {
            const date = new Date(this.selectedDate + 'T00:00:00');
            text.textContent = date.toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric' });
            btn.classList.add('has-date');
        } else {
            text.textContent = BYD.i18n.t('events.select_date');
            btn.classList.remove('has-date');
        }
    },
    
    renderCalendar() {
        const grid = document.getElementById('calendarGrid');
        const title = document.getElementById('calendarTitle');
        const year = this.currentDate.getFullYear();
        const month = this.currentDate.getMonth();
        
        // i18n: Use Intl.DateTimeFormat for localized month/weekday names instead of hardcoded English arrays.
        var monthDate = new Date(year, month, 1);
        var monthName;
        try {
            monthName = new Intl.DateTimeFormat(BYD.i18n.getLang(), { month: 'long' }).format(monthDate);
        } catch (e) {
            monthName = monthDate.toLocaleDateString(BYD.i18n.getLang(), { month: 'long' });
        }
        title.textContent = monthName + ' ' + year;
        grid.innerHTML = '';

        var weekdayFmt;
        try {
            weekdayFmt = new Intl.DateTimeFormat(BYD.i18n.getLang(), { weekday: 'short' });
        } catch (e) { weekdayFmt = null; }
        // Use Sunday 2024-01-07 .. Saturday 2024-01-13 as a known week for label rendering
        for (var w = 0; w < 7; w++) {
            const dateForDay = new Date(2024, 0, 7 + w); // Sun..Sat
            const label = weekdayFmt ? weekdayFmt.format(dateForDay) : dateForDay.toLocaleDateString(BYD.i18n.getLang(), { weekday: 'short' });
            const el = document.createElement('div');
            el.className = 'calendar-weekday';
            el.textContent = label;
            grid.appendChild(el);
        }
        
        const firstDay = new Date(year, month, 1).getDay();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        const daysInPrevMonth = new Date(year, month, 0).getDate();
        const todayStr = this.formatDateKey(new Date());
        
        for (let i = firstDay - 1; i >= 0; i--) {
            const day = daysInPrevMonth - i;
            this.addDayCell(grid, day, this.formatDateKey(new Date(year, month - 1, day)), true);
        }
        
        for (let day = 1; day <= daysInMonth; day++) {
            const dateKey = this.formatDateKey(new Date(year, month, day));
            this.addDayCell(grid, day, dateKey, false, dateKey === todayStr, this.selectedDate === dateKey);
        }
        
        const totalCells = grid.children.length;
        for (let day = 1; totalCells + day - 7 <= 42; day++) {
            this.addDayCell(grid, day, this.formatDateKey(new Date(year, month + 1, day)), true);
        }
    },
    
    addDayCell(grid, day, dateKey, isOtherMonth, isToday, isSelected) {
        const el = document.createElement('div');
        el.className = 'calendar-day';
        el.textContent = day;
        el.dataset.date = dateKey;
        
        if (isOtherMonth) el.classList.add('other-month');
        if (isToday) el.classList.add('today');
        if (isSelected) el.classList.add('selected');
        
        // Disable future dates
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        const cellDate = new Date(dateKey + 'T00:00:00');
        const isFuture = cellDate > today;
        
        if (isFuture) {
            el.classList.add('disabled');
        } else {
            const dateInfo = this.datesWithRecordings.get(dateKey);
            if (dateInfo) {
                el.classList.add('has-recordings');
                if (dateInfo.hasSentry) el.classList.add('has-sentry');
            }
            el.addEventListener('click', () => this.selectDate(dateKey));
        }
        
        grid.appendChild(el);
    },
    
    formatDateKey(date) {
        return date.getFullYear() + '-' + 
               String(date.getMonth() + 1).padStart(2, '0') + '-' + 
               String(date.getDate()).padStart(2, '0');
    },
    
    prevMonth() {
        this.currentDate.setMonth(this.currentDate.getMonth() - 1);
        this.renderCalendar();
    },
    
    nextMonth() {
        this.currentDate.setMonth(this.currentDate.getMonth() + 1);
        this.renderCalendar();
    },
    
    selectDate(dateKey) {
        this.selectedDate = this.selectedDate === dateKey ? null : dateKey;
        document.querySelectorAll('.calendar-day').forEach(el => {
            el.classList.toggle('selected', el.dataset.date === this.selectedDate);
        });
        this.updateRecordingsTitle();
        this.updateCalendarButton();
        this.currentPage = 1;
        this.loadRecordings();
        this.closeCalendar();
    },
    
    setFilter(filter) {
        this.currentFilter = filter;
        document.querySelectorAll('.filter-tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.filter === filter);
        });
        this.updateRecordingsTitle();
        this.currentPage = 1;
        this.loadRecordings();
    },

    /**
     * Set v3 actor / severity / proximity / place filter (item 6 + place).
     * Empty value clears the row. Updates chip active states.
     *
     * Place IS server-side — sent as the {@code place} query param to
     * /api/recordings so pagination + totalCount stay honest under the
     * filter. The previous client-side approach hid matching clips on
     * later pages because the server returned only one page at a time.
     */
    setActorFilter(kind, value) {
        if (!this.actorFilter) {
            this.actorFilter = { class: '', severity: '', proximity: '', place: '' };
        }
        this.actorFilter[kind] = value || '';
        const rowSel = '.filter-tabs[data-filter-row="' + kind + '"] .filter-chip';
        document.querySelectorAll(rowSel).forEach(chip => {
            chip.classList.toggle('active', (chip.dataset[kind] || '') === (value || ''));
        });
        this.currentPage = 1;
        // All four filters round-trip to the server now. The chip-row
        // re-render piggybacks on the loadRecordings completion — see
        // loadRecordings() which calls loadPlaceChips() after the fetch.
        this.loadRecordings();
    },

    /**
     * Set the storage-volume filter ('' = all, 'INTERNAL' / 'SD_CARD' / 'USB')
     * and reload. Server-side like the other filters so pagination +
     * totalCount stay honest. Clips already span every volume in the index;
     * this just narrows the view to one physical location.
     */
    setStorageFilter(value) {
        this.storageFilter = value || '';
        document.querySelectorAll('.filter-tabs[data-filter-row="storage"] .filter-chip').forEach(chip => {
            chip.classList.toggle('active', (chip.dataset.storage || '') === (value || ''));
        });
        this.currentPage = 1;
        this.loadRecordings();
    },

    /**
     * Rebuild the dynamic Place chip row from the currently loaded
     * recordings. Called from loadRecordings() after each successful
     * fetch. Hidden when no clip in the loaded set carries a
     * place.short — legacy/feature-disabled users never see this row.
     *
     * Chip identity is the lowercase short label; the chip TEXT is the
     * canonical mixed-case form picked from the most recently captured
     * clip in each bucket. Top 8 by count, alpha-tiebreak.
     */
    /**
     * Fetch the chip set from /api/recordings/places, scoped by the
     * SAME filter context as the recordings list (type, date, class,
     * severity, proximity — minus the place filter itself, since
     * narrowing the chips by the active place would always return only
     * the active chip).
     *
     * <p>This replaces the previous client-side derivation from
     * `this.recordings` which was page-bounded — places that existed
     * only on later pages would never show up as chips. The server
     * now does the full scan + bucket + top-N once per filter change.
     */
    async loadPlaceChips() {
        const row = document.getElementById('placeFilterRow');
        if (!row) return;
        // In-flight token: a rapid succession of filter taps can dispatch
        // two parallel loadPlaceChips. Without this guard the later
        // request's response could lose the race to the earlier one and
        // memoize a stale result, producing a momentary chip-row
        // inconsistency. We keep a monotonic seq and only honour the
        // most recent response — older fetches finish but their state
        // updates are dropped.
        const seq = (this._placeChipsSeq = (this._placeChipsSeq || 0) + 1);
        try {
            const params = [];
            if (this.currentFilter !== 'all') params.push('type=' + this.currentFilter);
            if (this.selectedDate) params.push('date=' + this.selectedDate);
            if (this.actorFilter && this.actorFilter.class)     params.push('class=' + encodeURIComponent(this.actorFilter.class));
            if (this.actorFilter && this.actorFilter.severity)  params.push('severity=' + encodeURIComponent(this.actorFilter.severity));
            if (this.actorFilter && this.actorFilter.proximity) params.push('proximity=' + encodeURIComponent(this.actorFilter.proximity));
            if (this.placeContainsQuery)                        params.push('placeContains=' + encodeURIComponent(this.placeContainsQuery));
            if (this.storageFilter)                             params.push('storage=' + encodeURIComponent(this.storageFilter));
            const queryStr = params.join('&');
            // Fetch memo: paging through the same filter set (prev/next
            // page) reissues loadRecordings → loadPlaceChips with the
            // same query string. The chip set is identical because place
            // is excluded from the chip endpoint's params; replay the
            // last response instead of round-tripping. Invalidated by:
            //   - any class/severity/proximity/type/date filter change
            //     (changes queryStr)
            //   - delete/batch-delete (clears memo via _placeFetchMemo
            //     reset in the delete handlers below).
            // We deliberately memo the FETCH only — _renderPlaceChipsFromServer
            // still runs because the active place filter may differ
            // (its memo via _placeChipsSignature handles DOM-skip when
            // both filter + chip set are unchanged).
            if (this._placeFetchMemoQuery === queryStr && this._placeFetchMemoData) {
                // Memo replay is synchronous, so the seq guard is not
                // strictly required here, but check anyway for symmetry
                // with the fetch path.
                if (seq !== this._placeChipsSeq) return;
                this._renderPlaceChipsFromServer(row, this._placeFetchMemoData.places || []);
                return;
            }
            const url = '/api/recordings/places' + (queryStr ? '?' + queryStr : '');
            const res = await fetch(url);
            const data = await res.json();
            // Stale-response guard — drop everything if a newer fetch was
            // dispatched while we were awaiting. Do this BEFORE writing
            // the memo so a stale result can't shadow the fresh one.
            if (seq !== this._placeChipsSeq) return;
            if (!data || !data.success) {
                // Row stays mounted (it hosts the search input). Render
                // the empty path so we strip stale chips + reset hits.
                this._renderPlaceChipsFromServer(row, []);
                return;
            }
            this._placeFetchMemoQuery = queryStr;
            this._placeFetchMemoData = data;
            this._renderPlaceChipsFromServer(row, data.places || []);
        } catch (e) {
            console.warn('loadPlaceChips failed:', e);
            // Same stale-response guard for the failure path.
            if (seq !== this._placeChipsSeq) return;
            // Keep the row + search input mounted on network failure too;
            // the user can still type a substring even if /places 500's.
            this._renderPlaceChipsFromServer(row, []);
        }
    },

    /**
     * Render the trailing "n places match" pill at the end of the place
     * row. Only shown while a substring search is active — otherwise the
     * unmodified chip set is self-explanatory. Uses BYD.i18n.plural for
     * proper plural rules across the supported langs.
     */
    _renderPlaceSearchHits(count) {
        const pill = document.getElementById('placeSearchHits');
        if (!pill) return;
        if (!this.placeContainsQuery) {
            pill.classList.remove('visible');
            pill.textContent = '';
            return;
        }
        // Reuse a generic count phrasing — the i18n side defines a
        // dedicated `events.place_search_hits` plural. Falls back to a
        // bare number if the i18n table doesn't have the key yet.
        let label;
        if (BYD.i18n && typeof BYD.i18n.plural === 'function') {
            label = BYD.i18n.plural('events.place_search_hits', count, { count: count });
        } else {
            label = count + (count === 1 ? ' match' : ' matches');
        }
        pill.textContent = label;
        pill.classList.add('visible');
    },

    /**
     * Drop the cached chip-set fetch. Called from the delete/batch-delete
     * handlers so the chip row reflects "places where clips still exist"
     * after a destructive action. Without this, a user deleting the last
     * Cheras clip would still see a "Cheras" chip until they changed
     * another filter.
     */
    _invalidatePlaceMemo() {
        this._placeFetchMemoQuery = null;
        this._placeFetchMemoData = null;
        this._placeChipsSignature = '';
    },

    /**
     * Render the chip row from a {@code [{key, label, count}]} array
     * fetched from the server. Memoizes via a signature that includes
     * the active filter key so paginations within the same chip set
     * skip the DOM mutation entirely.
     */
    _renderPlaceChipsFromServer(row, places) {
        // Row is unconditionally mounted now — it hosts the search input,
        // so hiding on empty-chips would yank the search affordance the
        // moment a query narrows to zero. Visibility is controlled only
        // by the inline display style which we always reset here.
        row.style.display = '';
        // Refresh the trailing "n matches" hint pill independent of
        // chip-set emptiness — the user needs feedback even when the
        // input narrows the chip list to nothing.
        this._renderPlaceSearchHits(places ? places.length : 0);
        if (!places || places.length === 0) {
            // If the active filter is no longer in the server set,
            // clear it. This can happen if the user deletes every
            // matching clip while the filter is on.
            if (this.actorFilter && this.actorFilter.place) {
                this.actorFilter.place = '';
                // The current /api/recordings response WAS fetched
                // with a place filter that's now invalid; trigger a
                // refetch so the visible list stops showing nothing.
                this.loadRecordings();
            }
            // Clear data chips but leave Any + the search input intact
            // so the user keeps a usable surface during empty searches.
            const anyChip = row.querySelector('.filter-chip[data-place=""]');
            Array.from(row.querySelectorAll('.filter-chip')).forEach(function(c) {
                if (c !== anyChip) c.remove();
            });
            this._placeChipsSignature = '';
            return;
        }

        const activePlaceLower = (this.actorFilter && this.actorFilter.place || '').toLowerCase();

        // Memo signature: order-stable join of chip keys + active key.
        // Pure paginations don't refetch chips (loadRecordings does, but
        // the same query params produce the same server output, so this
        // memo skips the DOM rebuild).
        const signature = activePlaceLower + '|' + places.map(p => p.key || '').join(',');
        if (this._placeChipsSignature === signature) return;
        this._placeChipsSignature = signature;

        // Replace data chips while keeping the static "Any" chip at index 0.
        const anyChip = row.querySelector('.filter-chip[data-place=""]');
        Array.from(row.querySelectorAll('.filter-chip')).forEach(c => {
            if (c !== anyChip) c.remove();
        });
        if (anyChip) anyChip.classList.toggle('active', !activePlaceLower);

        // Drop a place filter that's no longer in the server set.
        if (activePlaceLower && !places.some(p => (p.key || '') === activePlaceLower)) {
            this.actorFilter.place = '';
            // Re-fetch the recordings list — the place arg we sent on
            // loadRecordings is now a no-match. Without this, the user
            // sees an empty list with no UI clue what's blocking it.
            this.loadRecordings();
            return;
        }

        // Insert data chips BEFORE the trailing hits pill so the row
        // reads: [search] [Any] [data chips…] [hits pill]. appendChild
        // would put new chips after the pill which breaks the visual
        // grammar.
        const hitsPill = row.querySelector('.place-search-hits');
        places.forEach(place => {
            const btn = document.createElement('button');
            const key = place.key || '';
            btn.className = 'filter-chip' + (key === activePlaceLower ? ' active' : '');
            btn.setAttribute('data-place', key);
            // textContent escapes everything — the label flows through
            // untrusted (Nominatim / user-edited SafeLocation strings).
            btn.textContent = place.label || key;
            btn.addEventListener('click', () => {
                BYD.events.setActorFilter('place', key === activePlaceLower ? '' : key);
            });
            if (hitsPill) row.insertBefore(btn, hitsPill);
            else row.appendChild(btn);
        });
    },
    
    updateRecordingsTitle() {
        const title = document.getElementById('recordingsTitle');
        let prefix = this.selectedDate
            ? new Date(this.selectedDate + 'T00:00:00').toLocaleDateString(BYD.i18n.getLang(), { month: 'short', day: 'numeric', year: 'numeric' })
            : BYD.i18n.t('events.all');

        var suffixKey;
        if (this.currentFilter === 'sentry') suffixKey = 'events.title_sentry';
        else if (this.currentFilter === 'proximity') suffixKey = 'events.title_proximity';
        else if (this.currentFilter === 'replay') suffixKey = 'events.title_replay';
        else suffixKey = 'events.title_recordings';
        title.textContent = BYD.i18n.t(suffixKey, {prefix: prefix});
    },
    
    updatePagination() {
        const pagination = document.getElementById('pagination');
        const prevBtn = document.getElementById('prevPageBtn');
        const nextBtn = document.getElementById('nextPageBtn');
        const info = document.getElementById('paginationInfo');

        if (this.totalPages <= 1) {
            pagination.style.display = 'none';
            return;
        }

        pagination.style.display = 'flex';
        prevBtn.disabled = this.currentPage <= 1;
        nextBtn.disabled = this.currentPage >= this.totalPages;
        info.textContent = BYD.i18n.t('events.page_of', {page: this.currentPage, total: this.totalPages});
    },
    
    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.loadRecordings();
            document.getElementById('recordingsList').scrollTop = 0;
        }
    },
    
    nextPage() {
        if (this.currentPage < this.totalPages) {
            this.currentPage++;
            this.loadRecordings();
            document.getElementById('recordingsList').scrollTop = 0;
        }
    },

    async loadDatesWithRecordings() {
        try {
            const res = await fetch('/api/recordings/dates');
            const data = await res.json();
            // Index down: drop the stale day map instead of keeping dots for
            // days we can no longer verify (including days whose clips were
            // just deleted). A stale dot is also clickable and would just walk
            // the user into another failed query.
            if (data.indexUnavailable) {
                this.datesWithRecordings.clear();
                this.renderCalendar();
                return;
            }
            if (data.success && data.dates) {
                this.datesWithRecordings.clear();
                data.dates.forEach(d => {
                    this.datesWithRecordings.set(d.date, { count: d.count, hasSentry: d.hasSentry });
                });
                this.renderCalendar();
            }
        } catch (e) {
            console.error('Failed to load dates:', e);
        }
    },
    
    async loadStorageStats() {
        try {
            const res = await fetch('/api/recordings/stats');
            const data = await res.json();
            // Index down: the counters in this payload are all zero and are
            // NOT authoritative. Leaving the card at its hardcoded "0 / 0 / 0"
            // defaults made the page contradict itself — an honest "index
            // unavailable" list card sitting under a storage card asserting
            // zero clips of every type. Blank the numbers instead, and tear
            // down the warming banner: if the store died mid-warmup its
            // spinner would otherwise stay frozen on screen forever, because
            // the only _hideWarmingBanner() call lives in the success branch.
            if (data.indexUnavailable) {
                if (this._warmingPollTimer) {
                    clearTimeout(this._warmingPollTimer);
                    this._warmingPollTimer = null;
                }
                this._hideWarmingBanner();
                var unknownIds = ['normalCount', 'sentryCount', 'proximityCount', 'replayCount'];
                for (var ui = 0; ui < unknownIds.length; ui++) {
                    var uel = document.getElementById(unknownIds[ui]);
                    if (uel) uel.textContent = '--';
                }
                var usedEl = document.getElementById('storageUsed');
                if (usedEl) usedEl.textContent = '--';
                var fillEl = document.getElementById('storageFill');
                if (fillEl) fillEl.style.width = '0%';
                return;
            }
            if (data.success) {
                document.getElementById('storageUsed').textContent = data.totalSizeFormatted;
                const usedPercent = data.totalSpace > 0 ? (data.totalSize / data.totalSpace) * 100 : 0;
                document.getElementById('storageFill').style.width = Math.min(usedPercent, 100) + '%';
                // Prefer the structured byType block; fall back to legacy
                // flat fields so a daemon that hasn't shipped the new shape
                // still renders correctly.
                const counts = data.byType ? {
                    normal: data.byType.normal || {},
                    sentry: data.byType.sentry || {},
                    proximity: data.byType.proximity || {},
                    replay: data.byType.replay || {}
                } : {
                    normal: { count: data.normalCount, bytes: data.normalSize, todayCount: data.normalTodayCount },
                    sentry: { count: data.sentryCount, bytes: data.sentrySize, todayCount: data.sentryTodayCount },
                    proximity: { count: data.proximityCount, bytes: data.proximitySize, todayCount: data.proximityTodayCount },
                    replay: { count: data.replayCount, bytes: data.replaySize, todayCount: data.replayTodayCount }
                };
                document.getElementById('normalCount').textContent = counts.normal.count || 0;
                document.getElementById('sentryCount').textContent = counts.sentry.count || 0;
                document.getElementById('proximityCount').textContent = counts.proximity.count || 0;
                const replayCountEl = document.getElementById('replayCount');
                if (replayCountEl) replayCountEl.textContent = counts.replay.count || 0;

                // Daemon's recording index is still warming — surface a tiny
                // inline notice and self-refresh until it finishes. The notice
                // is removed as soon as indexState disappears from the payload.
                // Exponential backoff (2s → 4s → 8s → 10s cap) so a long
                // warmup (60+s on a 2k-clip library) doesn't pile redundant
                // requests on the daemon.
                //
                // Single in-flight timer: we cancel any prior pending
                // refresh before scheduling a new one. Without this, page
                // revisits / segment switches stack independent polling
                // chains that all increment _warmingPollAttempt and re-fire,
                // multiplying daemon HTTP load.
                if (this._warmingPollTimer) {
                    clearTimeout(this._warmingPollTimer);
                    this._warmingPollTimer = null;
                }
                if (data.indexState && data.indexState.warming) {
                    const done = data.indexState.done || 0;
                    const total = data.indexState.total || 0;
                    const pct = total > 0 ? Math.round(done * 100 / total) : 0;
                    const tmpl = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t('events.index_warming', {done: done, total: total, pct: pct}) : '';
                    const fallback = 'Building library index — ' + done + ' / ' + total + ' (' + pct + '%)';
                    // If translation key is missing, BYD.i18n.t typically
                    // returns the key itself; treat that as no translation.
                    const text = (tmpl && tmpl !== 'events.index_warming') ? tmpl : fallback;
                    this._showWarmingBanner(text);
                    const self = this;
                    var attempt = Math.min(this._warmingPollAttempt || 0, 8);
                    var delay = Math.min(2000 * Math.pow(2, attempt), 10000);
                    this._warmingPollAttempt = (this._warmingPollAttempt || 0) + 1;
                    this._warmingPollTimer = setTimeout(function () {
                        self._warmingPollTimer = null;
                        if (self.loadStorageStats) self.loadStorageStats();
                    }, delay);
                } else {
                    this._hideWarmingBanner();
                    // Reset backoff so a future warmup window (storage
                    // hot-plug rebuild) starts at 2s base again.
                    this._warmingPollAttempt = 0;
                }
            }
        } catch (e) {
            console.error('Failed to load storage stats:', e);
        }
    },

    /**
     * Inline "Building library index..." banner inserted directly above
     * #recordingsList. Reuses the existing .loading visual idiom (spinner
     * + muted text) plus a small .warming-banner positioning class. Idempotent —
     * safe to call repeatedly; just refreshes the text.
     */
    _showWarmingBanner(text) {
        let banner = document.getElementById('recordingIndexWarming');
        if (!banner) {
            const list = document.getElementById('recordingsList');
            if (!list || !list.parentNode) return;
            banner = document.createElement('div');
            banner.id = 'recordingIndexWarming';
            banner.className = 'loading warming-banner';
            banner.innerHTML = '<div class="spinner"></div><span class="warming-banner-text"></span>';
            list.parentNode.insertBefore(banner, list);
        }
        const textEl = banner.querySelector('.warming-banner-text');
        if (textEl) textEl.textContent = text;
    },

    _hideWarmingBanner() {
        const banner = document.getElementById('recordingIndexWarming');
        if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
    },
    
    /**
     * Single source of truth for filename → thumbnail DOM id. Both the inflight
     * placeholder and the recordings-list renderer use this so the
     * "highlight just-finalized card" lookup can't drift from the id used
     * when the card was rendered.
     */
    _thumbDomId(filename) {
        return 'thumb-' + filename.replace(/[^a-zA-Z0-9]/g, '_');
    },

    /**
     * Probe the backend for an in-flight .mp4.tmp matching the deep-linked
     * filename. If it's there, set state and start polling so the placeholder
     * card upgrades to a normal entry the moment the post-record window ends.
     */
    async checkAndPollInflight(filename) {
        try {
            const res = await fetch('/api/recordings/inflight/' + encodeURIComponent(filename));
            const data = await res.json();
            if (!data || !data.inflight) {
                // File isn't in flight — no placeholder, no polling. The
                // recording either finished and was paginated past the first
                // page, or it never existed.
                return;
            }
            this.inflightFilename = filename;
            this.renderRecordings();
            this.startInflightPolling();
        } catch (e) {
            console.warn('[events] inflight probe failed:', e);
        }
    },

    startInflightPolling() {
        this.stopInflightPolling();
        this.inflightPollHandle = setInterval(async () => {
            if (!this.inflightFilename) {
                this.stopInflightPolling();
                return;
            }
            try {
                const res = await fetch('/api/recordings/inflight/' +
                    encodeURIComponent(this.inflightFilename));
                const data = await res.json();
                if (!data.inflight) {
                    // Rename finished. Clear the placeholder, reload the list
                    // so the freshly-renamed file appears in its proper slot,
                    // and stop polling. We deliberately do NOT auto-open the
                    // video — the user came here from a notification a few
                    // seconds ago; surfacing the entry visibly is enough.
                    const finishedFile = this.inflightFilename;
                    this.inflightFilename = null;
                    this.stopInflightPolling();
                    await this.loadRecordings();
                    // If the file landed on this page, briefly highlight it.
                    const node = document.getElementById(this._thumbDomId(finishedFile));
                    const card = node ? node.closest('.recording-card') : null;
                    if (card) {
                        card.classList.add('recording-card-just-finalized');
                        setTimeout(() => {
                            card.classList.remove('recording-card-just-finalized');
                        }, 2000);
                    }
                }
            } catch (e) {
                // Network blip — keep polling, don't bail.
            }
        }, 2000);
    },

    /**
     * Build the pinned "Recording in progress" card markup. Reuses the
     * existing recording-card / recording-thumbnail / recording-info CSS so
     * it slots in visually with the rest of the list. Distinguished by the
     * .recording-card-inflight modifier (subtle pulse) and a Recording badge
     * sitting where the duration badge would normally be.
     *
     * Thumbnail loads from the same /thumb/ endpoint, which now serves a sync
     * frame from the .mp4.tmp file before post-record finalises (see
     * RecordingsApiHandler.findVideoFile, allowInFlightTmp).
     */
    renderInflightCard(filename) {
        const thumbId = this._thumbDomId(filename);
        const fname = filename.length > 28 ? filename.substring(0, 25) + '...' : filename;
        const thumbUrl = '/thumb/' + encodeURIComponent(filename);
        return '' +
            '<div class="recording-card recording-card-inflight" data-filename="' + filename + '">' +
                '<div class="recording-thumbnail" id="' + thumbId + '" data-thumb="' + thumbUrl + '">' +
                    '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg></div>' +
                    '<span class="inflight-badge"><span class="inflight-dot"></span>' + BYD.i18n.t('events.recording_badge') + '</span>' +
                '</div>' +
                '<div class="recording-info">' +
                    '<div class="recording-name"><span class="recording-badge live">' + BYD.i18n.t('events.live_badge') + '</span>' + fname + '</div>' +
                    '<div class="recording-meta"><span>' + BYD.i18n.t('events.available_in_seconds') + '</span></div>' +
                '</div>' +
            '</div>';
    },

    async loadRecordings() {
        const list = document.getElementById('recordingsList');
        list.innerHTML = '<div class="loading"><div class="spinner"></div></div>';

        try {
            let url = '/api/recordings';
            const params = [];
            if (this.currentFilter !== 'all') params.push('type=' + this.currentFilter);
            if (this.selectedDate) params.push('date=' + this.selectedDate);
            // v3 actor filters (item 6) + place filter (item 7) — all
            // server-side now so pagination + totalCount stay honest
            // under any combination of narrowing chips.
            if (this.actorFilter && this.actorFilter.class)     params.push('class=' + encodeURIComponent(this.actorFilter.class));
            if (this.actorFilter && this.actorFilter.severity)  params.push('severity=' + encodeURIComponent(this.actorFilter.severity));
            if (this.actorFilter && this.actorFilter.proximity) params.push('proximity=' + encodeURIComponent(this.actorFilter.proximity));
            if (this.actorFilter && this.actorFilter.place)     params.push('place=' + encodeURIComponent(this.actorFilter.place));
            // Substring narrowing — stacks with the chip filter; SQL
            // AND-chains them so the user sees clips matching BOTH the
            // exact chip AND the typed substring. Indexed via place_short
            // / place_medium / place_display LIKE in RecordingsIndex.
            if (this.placeContainsQuery)                        params.push('placeContains=' + encodeURIComponent(this.placeContainsQuery));
            // Storage-volume narrowing — '' = all volumes.
            if (this.storageFilter)                             params.push('storage=' + encodeURIComponent(this.storageFilter));
            params.push('page=' + this.currentPage);
            params.push('pageSize=' + this.pageSize);
            url += '?' + params.join('&');

            const res = await fetch(url);
            const data = await res.json();

            // Daemon reports its recordings index is down (H2 closed the
            // store). The clips ARE on disk — this is explicitly NOT an
            // empty library, so show the error state and retry rather than
            // rendering a misleading "no recordings" empty state.
            if (data.indexUnavailable) {
                this._indexDown = true;
                list.innerHTML = this._indexDownCardHtml();
                // Clear the stale pagination bar + count. Without this the
                // user is left with "Page 3 of 5" and an enabled Next button
                // sitting above the error card, and clicking Next just walks
                // currentPage into another failed request.
                this.totalCount = 0;
                this.totalPages = 1;
                this.currentPage = 1;
                this.recordings = [];
                this.updatePagination();
                // Blank the count rather than writing "0 videos" — that would
                // assert an empty library in the header directly above the
                // "index unavailable" card.
                var countEl = document.getElementById('recordingsCount');
                if (countEl) countEl.textContent = '';
                // Retry with cancel-then-rearm + exponential backoff, matching
                // the warming poller. A fixed-interval re-arm would poll the
                // daemon forever at full rate when the index is permanently
                // down (init() can fail unrecoverably for the whole daemon
                // lifetime), and storing the handle lets page-exit cancel it.
                this.scheduleIndexDownRetry(data.retryAfterMs);
                return;
            }

            if (data.success) {
                // Index answered — reset the index-down backoff so a future
                // outage starts at the 5s base instead of the 30s cap, and
                // clear the flag so renderRecordings() shows the normal empty
                // state again for a genuinely empty library.
                this._indexDownAttempt = 0;
                if (this._indexDown) {
                    this._indexDown = false;
                    // The storage card and calendar were blanked while down and
                    // this page has no periodic stats poller, so refresh them
                    // explicitly — otherwise counters stay at '--' and the
                    // calendar has no dots until a manual reload.
                    if (this.loadStorageStats) this.loadStorageStats();
                    if (this.loadDatesWithRecordings) this.loadDatesWithRecordings();
                }
                this.recordings = data.recordings || [];
                this.totalPages = data.totalPages || 1;
                this.totalCount = data.totalCount || this.recordings.length;
                this.renderRecordings();
                this.updatePagination();
                document.getElementById('recordingsCount').textContent =
                    BYD.i18n.plural('events.video_count', this.totalCount);
                // Server signal: the recordings index was empty but the
                // filesystem actually has files (storage hot-plug, fresh
                // boot, type-switch). The daemon kicked an async reconcile;
                // schedule one retry so the user sees the populated list
                // without manually reloading. Capped at one retry to avoid
                // a poll loop if reconcile somehow keeps failing.
                if (data.reconciling && this.totalCount === 0
                        && !this._reconcileRetryPending) {
                    this._reconcileRetryPending = true;
                    var self = this;
                    var delay = data.retryAfterMs || 1500;
                    setTimeout(function () {
                        self._reconcileRetryPending = false;
                        self.loadRecordings();
                    }, delay);
                }
                // Refresh the chip row from the dedicated places endpoint
                // — scoped by the SAME filter context (minus place
                // itself) so the user sees "places reachable under the
                // current Sentry/Dashcam/date narrowing." Kicked off
                // AFTER the recordings render so the user-visible list
                // doesn't wait on this auxiliary fetch.
                this.loadPlaceChips();
            }
        } catch (e) {
            console.error('Failed to load recordings:', e);
            list.innerHTML = '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><div class="empty-title">' + BYD.i18n.t('events.empty_failed_title') + '</div><div class="empty-text">' + BYD.i18n.t('events.empty_failed_text') + '</div></div>';
        }
    },
    
    // Build the per-clip storage chip (INTERNAL / SD_CARD / USB) for the
    // recordings list meta row. Returns '' when the server didn't tag the
    // clip (rec.storage absent) so legacy rows render unchanged. Inline SVGs
    // match the existing icon style in this file; ES5-safe for the Chrome 58
    // head-unit WebView (no template literals / arrow funcs).
    _storageChip(storage) {
        var s = (storage || '').toUpperCase();
        if (s === 'SD_CARD') {
            return '<span class="storage-chip storage-sd" title="' + BYD.i18n.t('events.storage_sd_card') + '">' +
                '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4h11l5 5v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"/><path d="M9 4v4M13 4v4M17 9v3"/></svg>' +
                BYD.i18n.t('events.storage_sd_card') + '</span>';
        }
        if (s === 'USB') {
            return '<span class="storage-chip storage-usb" title="' + BYD.i18n.t('events.storage_usb') + '">' +
                '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="19" r="2"/><path d="M12 17V3M8 7l4-4 4 4M6 12h12v3a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2z"/></svg>' +
                BYD.i18n.t('events.storage_usb') + '</span>';
        }
        if (s === 'INTERNAL') {
            return '<span class="storage-chip storage-internal" title="' + BYD.i18n.t('events.storage_internal') + '">' +
                '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><line x1="7" y1="9" x2="7" y2="9"/><line x1="7" y1="15" x2="17" y2="15"/></svg>' +
                BYD.i18n.t('events.storage_internal') + '</span>';
        }
        return '';
    },

    renderRecordings() {
        const list = document.getElementById('recordingsList');

        // Inflight placeholder. We only render it when there's NO active
        // narrowing filter — class/severity/proximity/place all hide it.
        // Reason: the placeholder represents a not-yet-finalized .mp4
        // whose sidecar hasn't been written, so its place/severity/etc
        // are unknowable. Showing it under an active filter would have
        // it vanish (without being replaced) when the .mp4 finalizes
        // and the filter excludes it. The filter-based bar lets the
        // user see the active recording in the unfiltered or type-only
        // views; stricter narrowing implies "show me clips matching X"
        // which the placeholder can't promise to deliver.
        const filterNarrowing = !!(this.actorFilter
                && (this.actorFilter.class || this.actorFilter.severity
                        || this.actorFilter.proximity || this.actorFilter.place));
        const inflightHtml = (this.inflightFilename && !filterNarrowing)
            ? this.renderInflightCard(this.inflightFilename)
            : '';

        // Server applies every filter (type, date, class, severity,
        // proximity, place). The list arrives ready to render.
        const visible = this.recordings;

        if (visible.length === 0) {
            if (inflightHtml) {
                list.innerHTML = inflightHtml;
            } else if (this._indexDown) {
                // The index is down, so "empty" is unknown, not zero. Several
                // handlers (select-mode toggle, select/deselect-all, the
                // inflight poller) re-render straight from this.recordings
                // without refetching — without this branch any one of them
                // would overwrite the honest card with "No recordings found",
                // reinstating exactly the lie this fix removes.
                list.innerHTML = this._indexDownCardHtml();
            } else {
                list.innerHTML = '<div class="empty-state"><svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><div class="empty-title">' + BYD.i18n.t('events.empty_none_title') + '</div><div class="empty-text">' + BYD.i18n.t('events.empty_none_text') + '</div></div>';
            }
            return;
        }

        list.innerHTML = inflightHtml + visible.map(rec => {
            const recordingKey = this.recordingKey(rec);
            const thumbId = this._thumbDomId(recordingKey);
            const badge = rec.type === 'sentry' ? BYD.i18n.t('events.badge_sentry') : rec.type === 'proximity' ? BYD.i18n.t('events.badge_proximity') : rec.type === 'replay' ? BYD.i18n.t('events.badge_replay') : BYD.i18n.t('events.badge_normal');
            const fname = rec.filename.length > 28 ? rec.filename.substring(0, 25) + '...' : rec.filename;
            const isSelected = this.selectedFiles.has(recordingKey);
            
            // Checkbox for select mode
            const checkbox = this.selectMode 
                ? '<label class="select-checkbox-wrap" onclick="event.stopPropagation()"><input type="checkbox" class="select-checkbox" ' + (isSelected ? 'checked' : '') + ' onchange="BYD.events.toggleFileSelection(\'' + recordingKey + '\', event)"></label>'
                : '';
            
            // Card click handler depends on mode
            const cardClick = this.selectMode 
                ? 'BYD.events.toggleFileSelection(\'' + recordingKey + '\')'
                : 'BYD.events.playVideo(\'' + recordingKey + '\')';
            
            // v3 enrichment (item 6): use hero JPEG when present, sev badge, actor summary
            const sev = (rec.peakSeverity || '').toUpperCase();
            const sevClass = sev === 'CRITICAL' ? 'sev-critical' : sev === 'ALERT' ? 'sev-alert' : '';
            const sevLabel = sev === 'CRITICAL' ? BYD.i18n.t('events.severity_critical')
                : sev === 'ALERT' ? BYD.i18n.t('events.severity_alert')
                : sev === 'NOTICE' ? BYD.i18n.t('events.severity_notice')
                : sev;
            const sevBadge = sev ? '<span class="recording-badge sev-' + sev.toLowerCase() + '">' + sevLabel + '</span>' : '';
            const thumbUrl = rec.heroThumbnailUrl || rec.thumbnailUrl || '';
            const personCount  = rec.personCount  || rec.personSpans  || 0;
            const vehicleCount = rec.vehicleCount || rec.vehicleSpans || 0;
            const bikeCount    = rec.bikeCount    || rec.bikeSpans    || 0;
            const animalCount  = rec.animalCount  || 0;
            const proxLabel = (function(p) {
                switch ((p||'').toUpperCase()) {
                    case 'VERY_CLOSE': return BYD.i18n.t('events.prox_very_close');
                    case 'CLOSE': return BYD.i18n.t('events.prox_close');
                    case 'MID': return BYD.i18n.t('events.prox_mid');
                    case 'FAR': return BYD.i18n.t('events.prox_far');
                    default: return '';
                }
            })(rec.peakProximity);
            // Per-clip storage tag. Surfaces where the clip ACTUALLY landed
            // (INTERNAL / SD_CARD / USB) so the silent SD→internal fallback
            // — the SD card is bridged behind the USB power rail, so cutting
            // USB power unmounts it and recordings fall back to internal — is
            // visible at the file level. Omitted when the server couldn't
            // classify (rec.storage absent) so legacy rows render unchanged.
            const storageChip = this._storageChip(rec.storage);
            let actorPills = '';
            if (personCount > 0)  actorPills += '<span class="pill">👤 ' + personCount + '</span>';
            if (vehicleCount > 0) actorPills += '<span class="pill">🚗 ' + vehicleCount + '</span>';
            if (bikeCount > 0)    actorPills += '<span class="pill">🚲 ' + bikeCount + '</span>';
            if (animalCount > 0)  actorPills += '<span class="pill">🐾 ' + animalCount + '</span>';
            if (proxLabel)        actorPills += '<span class="pill prox-' + (rec.peakProximity || 'UNKNOWN') + '">' + proxLabel + '</span>';
            const actorRow = actorPills ? '<div class="actor-summary">' + actorPills + '</div>' : '';

            // v3 geo enrichment — server-side parser populates rec.place
            // (medium/short/displayName/source/countryCode). Hidden when
            // missing so legacy clips and clips with no GPS fix render
            // exactly as before. HTML-escape the place name because it
            // can contain user-edited SafeLocation labels and
            // OpenStreetMap-emitted strings; both flow through the
            // sidecar untrusted.
            let placeRow = '';
            if (rec.place && (rec.place.medium || rec.place.short)) {
                const placeText = rec.place.medium || rec.place.short || '';
                const escaped = String(placeText)
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;');
                placeRow = '<div class="recording-place">📍 ' + escaped + '</div>';
            }

            return '<div class="recording-card' + (isSelected ? ' selected' : '') + (sevClass ? ' ' + sevClass : '') + '" data-recording-key="' + recordingKey + '" data-filename="' + rec.filename + '" onclick="' + cardClick + '">' +
                checkbox +
                '<div class="recording-thumbnail" id="' + thumbId + '" data-thumb="' + thumbUrl + '">' +
                '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg></div>' +
                '<div class="play-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>' +
                (rec.duration ? '<span class="duration-badge">' + rec.duration + '</span>' : '') +
                '</div>' +
                '<div class="recording-info">' +
                '<div class="recording-name"><span class="recording-badge ' + rec.type + '">' + badge + '</span>' + sevBadge + fname + '</div>' +
                '<div class="recording-meta"><span>' + rec.dateFormatted + '</span><span>' + rec.timeFormatted + '</span><span>' + rec.sizeFormatted + '</span>' + storageChip + '</div>' +
                placeRow +
                actorRow +
                '</div>' +
                (this.selectMode ? '' : 
                '<div class="recording-actions">' +
                '<button class="action-btn" onclick="event.stopPropagation(); BYD.events.downloadVideo(\'' + recordingKey + '\')" title="' + BYD.i18n.t('common.download') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>' +
                '<button class="action-btn delete" onclick="event.stopPropagation(); BYD.events.deleteRecording(\'' + recordingKey + '\')" title="' + BYD.i18n.t('common.delete') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>' +
                '</div>') +
                '</div>';
        }).join('');
        
        // Load thumbnails async after render
        this.loadThumbnailsAsync();
    },
    
    // Load thumbnails asynchronously without blocking
    loadThumbnailsAsync() {
        document.querySelectorAll('.recording-thumbnail[data-thumb]').forEach(container => {
            const thumbUrl = container.dataset.thumb;
            if (!thumbUrl) return;
            
            this.loadSingleThumbnail(container, thumbUrl, 0);
        });
    },
    
    // Load single thumbnail with retry on 202
    loadSingleThumbnail(container, url, retryCount) {
        if (retryCount > 8) {
            // After max retries, show "no preview" state
            const placeholder = container.querySelector('.thumb-placeholder');
            if (placeholder) {
                placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
            }
            return;
        }
        
        fetch(url).then(res => {
            if (res.status === 200) {
                return res.blob();
            } else if (res.status === 202) {
                // Generating, retry with exponential backoff
                const delay = Math.min(1000 * Math.pow(1.5, retryCount), 5000);
                setTimeout(() => this.loadSingleThumbnail(container, url, retryCount + 1), delay);
                return null;
            }
            return null;
        }).then(blob => {
            if (blob && blob.size > 0) {
                const imgUrl = URL.createObjectURL(blob);
                const placeholder = container.querySelector('.thumb-placeholder');
                if (placeholder) {
                    const img = document.createElement('img');
                    img.src = imgUrl;
                    img.alt = BYD.i18n.t('events.thumbnail_alt');
                    img.onload = () => placeholder.remove();
                    img.onerror = () => {
                        URL.revokeObjectURL(imgUrl);
                        placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
                    };
                    container.insertBefore(img, container.firstChild);
                }
            }
        }).catch(() => {
            // Network error - show fallback
            const placeholder = container.querySelector('.thumb-placeholder');
            if (placeholder) {
                placeholder.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span>';
            }
        });
    },
    
    onThumbError(el) {
        const container = el.parentElement;
        container.innerHTML = '<div class="thumb-placeholder"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m22 8-6 4 6 4V8Z"/><rect width="14" height="12" x="2" y="6" rx="2"/></svg><span>' + BYD.i18n.t('events.no_preview') + '</span></div><div class="play-icon"><svg width="16" height="16" viewBox="0 0 24 24" fill="white"><polygon points="5 3 19 12 5 21 5 3"/></svg></div>';
    },
    
    playVideo(recordingKey) {
        const rec = this.findRecording(recordingKey);
        if (!rec) return;
        
        document.getElementById('videoTitle').textContent = rec.filename;
        document.getElementById('videoDate').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg> ' + rec.dateFormatted;
        document.getElementById('videoTime').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg> ' + rec.timeFormatted;
        document.getElementById('videoSize').innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> ' + rec.sizeFormatted;
        
        const downloadBtn = document.getElementById('downloadBtn');
        downloadBtn.href = rec.videoUrl;
        downloadBtn.download = rec.filename;
        
        const player = document.getElementById('videoPlayer');
        player.setAttribute('playsinline', '');
        player.setAttribute('webkit-playsinline', '');
        player.src = rec.videoUrl;
        document.getElementById('videoModal').classList.add('active');
        player.load();

        // Bind the external transport (play/pause, mute, time, scrubber)
        // BEFORE the first frame so the controls reflect state from the
        // moment the modal appears. bindTransport itself is idempotent.
        this.bindTransport(player);

        // Show the quadrant pill on open, then arm the auto-hide so the
        // user gets a glance of where the camera selector lives before
        // the surface clears for unobstructed viewing.
        this.showQuadrantBar();

        // Hide the body-level theme FAB (theme.js mounts it at
        // z-index 9000 — higher than our modal). Skipped inside the
        // Android WebView (theme.js doesn't mount it there) but on
        // PWA / external mobile browsers the FAB would otherwise float
        // over the fullscreen player. The class on <html> drives a
        // CSS rule installed below; closeVideo() removes it.
        document.documentElement.classList.add('video-modal-open');

        // Browser autoplay policy requires muted=true to start without a
        // user gesture. After play begins we apply the user's persisted
        // mute preference (or the audio-recording-enabled fallback for
        // first-run). The mute-icon SVG re-paints via the volumechange
        // listener registered in bindTransport.
        const self = this;
        player.oncanplay = function() {
            player.play().catch(function() {});
            try {
                const stored = localStorage.getItem('byd.events.unmuted');
                if (stored !== null) {
                    player.muted = stored !== '1';
                } else {
                    // Loopback fetch (~5ms). Keep muted while in flight —
                    // autoplay is gated on it, so the muted→unmuted flip
                    // is imperceptible.
                    player.muted = true;
                    self.getAudioRecordingEnabled().then(function(enabled) {
                        if (enabled) player.muted = false;
                    });
                }
            } catch (e) {
                player.muted = true;
            }
            self._lastMutedState = player.muted;
            player.oncanplay = null;
        };

        // Load event-timeline marker overlay for this recording (the
        // scrubber itself stays visible even with no markers).
        this.loadTimeline(rec, player);
    },
    
    closeVideo() {
        const player = document.getElementById('videoPlayer');
        player.pause();
        player.src = '';
        document.getElementById('videoModal').classList.remove('active');
        // Restore the theme FAB (and any other body-level chrome the
        // .video-modal-open class hides).
        document.documentElement.classList.remove('video-modal-open');
        // Cancel any in-flight auto-hide timer + reset the bar state so
        // the next playVideo starts from a clean "visible" baseline.
        this.hideQuadrantBarNow();
        const bar = document.getElementById('quadrantBar');
        if (bar) bar.classList.remove('hidden');
        // Reset the cross-modal mute snapshot so the next playVideo's
        // first volumechange fires with a clean reference. Without this,
        // a stale value from the prior clip can mis-classify the next
        // clip's auto-applied mute as a "user change" and re-persist it.
        this._lastMutedState = null;
        // SOTA: Clean up timeline
        this.destroyTimeline();
    },
    
    downloadVideo(recordingKey) {
        const rec = this.findRecording(recordingKey);
        if (!rec) return;
        const a = document.createElement('a');
        a.href = rec.videoUrl;
        a.download = rec.filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    },
    
    async deleteRecording(recordingKey) {
        const rec = this.findRecording(recordingKey);
        if (!rec) return;
        if (!confirm(BYD.i18n.t('events.confirm_delete_one', {filename: rec.filename}))) return;

        // If the modal player is currently showing this recording, pause it
        // and detach the source BEFORE the DELETE goes through. Otherwise
        // the in-flight Range request races with the file disappearing and
        // the user sees an "error: source not supported" toast.
        try {
            const player = document.getElementById('videoPlayer');
            if (player && player.getAttribute('src') === rec.videoUrl) {
                this.closeVideo();
            }
        } catch (_) {}

        try {
            const deleteUrl = rec.deleteUrl || ('/api/recordings/' + encodeURIComponent(rec.filename));
            const res = await fetch(deleteUrl, { method: 'DELETE' });
            const data = await res.json();

            if (data.success) {
                // Drop the place-chip fetch memo so the row re-derives
                // from the post-delete set; otherwise a now-empty bucket
                // would still show as a chip until the next filter flip.
                this._invalidatePlaceMemo();
                await this.loadDatesWithRecordings();
                await this.loadStorageStats();
                await this.loadRecordings();
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(BYD.i18n.t('events.toast_deleted'), 'success');
                }
            } else {
                alert(BYD.i18n.t('events.alert_delete_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            console.error('Delete failed:', e);
            alert(BYD.i18n.t('events.alert_delete_failed_generic'));
        }
    },
    
    // ========================================================================
    // Multi-Select & Batch Delete
    // ========================================================================
    
    toggleSelectMode() {
        this.selectMode = !this.selectMode;
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    exitSelectMode() {
        this.selectMode = false;
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    toggleFileSelection(recordingKey, event) {
        if (event) event.stopPropagation();
        
        if (this.selectedFiles.has(recordingKey)) {
            this.selectedFiles.delete(recordingKey);
        } else {
            this.selectedFiles.add(recordingKey);
        }
        this.updateSelectUI();
        this.updateCardSelection(recordingKey);
    },
    
    selectAll() {
        this.recordings.forEach(rec => this.selectedFiles.add(this.recordingKey(rec)));
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    deselectAll() {
        this.selectedFiles.clear();
        this.updateSelectUI();
        this.renderRecordings();
    },
    
    updateCardSelection(recordingKey) {
        const card = document.querySelector('[data-recording-key="' + recordingKey + '"]');
        if (card) {
            card.classList.toggle('selected', this.selectedFiles.has(recordingKey));
            const checkbox = card.querySelector('.select-checkbox');
            if (checkbox) checkbox.checked = this.selectedFiles.has(recordingKey);
        }
    },
    
    updateSelectUI() {
        const toolbar = document.getElementById('selectToolbar');
        const selectBtn = document.getElementById('selectModeBtn');
        const count = document.getElementById('selectedCount');
        
        if (this.selectMode) {
            if (toolbar) toolbar.style.display = 'flex';
            if (selectBtn) selectBtn.classList.add('active');
            if (count) count.textContent = BYD.i18n.t('events.n_selected', {n: this.selectedFiles.size});
        } else {
            if (toolbar) toolbar.style.display = 'none';
            if (selectBtn) selectBtn.classList.remove('active');
        }
    },
    
    async batchDelete() {
        const count = this.selectedFiles.size;
        if (count === 0) return;
        
        if (!confirm(BYD.i18n.plural('events.confirm_delete_n', count))) return;
        
        const selected = Array.from(this.selectedFiles)
            .map(key => this.findRecording(key)).filter(Boolean);
        const ids = selected.map(rec => rec.id).filter(Boolean);
        const filenames = selected.filter(rec => !rec.id).map(rec => rec.filename);
        const payload = { ids: ids, filenames: filenames };
        
        try {
            const res = await fetch('/api/recordings/batch-delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await res.json();
            
            if (data.success) {
                const msg = data.failed > 0
                    ? BYD.i18n.t('events.batch_deleted_with_failures', {deleted: data.deleted, failed: data.failed})
                    : BYD.i18n.t('events.batch_deleted', {deleted: data.deleted});
                if (BYD.core && BYD.core.showToast) {
                    BYD.core.showToast(msg, data.failed > 0 ? 'warning' : 'success');
                }
                // Same memo invalidation as single-delete — keeps the
                // chip row in sync after a multi-clip purge.
                this._invalidatePlaceMemo();
                this.exitSelectMode();
                await this.loadDatesWithRecordings();
                await this.loadStorageStats();
                await this.loadRecordings();
            } else {
                alert(BYD.i18n.t('events.batch_delete_failed', {error: data.error || BYD.i18n.t('errors.generic')}));
            }
        } catch (e) {
            console.error('Batch delete failed:', e);
            alert(BYD.i18n.t('events.batch_delete_failed_generic'));
        }
    },
    
    // ========================================================================
    // Custom transport (scrubber + play/pause + time + mute).
    //
    // Why we own the chrome instead of using <video controls>:
    //   The quadrant zoom is a CSS transform: scale(2) on the <video> element.
    //   The browser's native control bar is painted INSIDE the video element,
    //   so the same transform drags it off-screen on every quadrant zoom —
    //   the user loses the timestamp and the seek bar the moment they zoom in.
    //   We render an external transport row that lives OUTSIDE the zooming
    //   surface so chrome stays put. Same vocabulary as VideoPlayerFragment +
    //   ZoomableVideoView (Matrix on TextureView, sibling FrameLayout chrome).
    // ========================================================================

    _timelineRaf: null,
    _timelineEvents: null,
    // True while the user is mid-drag on the scrubber. timeupdate suppresses
    // the playhead repaint during a drag so the user's finger position wins.
    _scrubbing: false,
    _scrubPointerId: null,
    // Bound transport handlers — captured per playVideo so closeVideo can
    // detach exactly the same function references. Keys: play_pause, mute,
    // timeupdate, durationchange, loadedmetadata, ended, scrubdown, scrubmove,
    // scrubup, surfaceclick.
    _transport: null,

    /**
     * Load event timeline markers + bind transport controls. Markers come
     * from the JSON sidecar — when none exists, the scrubber still shows
     * (so the user can still seek), just without colored event ranges.
     */
    async loadTimeline(recording, videoEl) {
        const scrubber = document.getElementById('timelineScrubber');
        const legend = document.getElementById('timelineLegend');
        const track = document.getElementById('timelineTrack');
        const playhead = document.getElementById('timelinePlayhead');
        const progress = document.getElementById('timelineProgress');

        // Guard: old cached page without the new chrome — skip silently.
        if (!scrubber || !track || !playhead) return;

        // Reset visual state but KEEP the scrubber visible — it's now the
        // primary seek surface, not just an event-marker overlay. Event-only
        // children get cleared; the .timeline-progress fill is preserved.
        Array.from(track.querySelectorAll('.timeline-marker')).forEach(n => n.remove());
        playhead.style.left = '0%';
        if (progress) progress.style.width = '0%';
        if (legend) legend.style.display = 'none';
        this._timelineEvents = null;
        // Reset composition layout for the new clip (overridden below once the
        // sidecar loads). Ensures a dashcam clip followed by a standard one
        // drops back to the 2x2 zoom regions.
        this._layout = 'standard';
        var _wrapReset = document.getElementById('videoPlayerWrap');
        if (_wrapReset) _wrapReset.classList.remove('dashcam');

        try {
            const eventUrl = recording.eventUrl
                || ('/api/events/' + encodeURIComponent(recording.filename));
            const res = await fetch(eventUrl);
            const data = await res.json();
            // Composition layout drives the per-camera zoom regions: toggle the
            // `dashcam` class so the dashcam CSS zoom rects apply, and store it
            // for the double-tap hit-test (_quadrantAtFit).
            if (data && data.layout === 'dashcam') {
                this._layout = 'dashcam';
                var _wrap = document.getElementById('videoPlayerWrap');
                if (_wrap) _wrap.classList.add('dashcam');
            }

            if (data && data.events && data.events.length > 0) {
                this._timelineEvents = data;

                const renderMarkers = () => {
                    const duration = videoEl.duration;
                    if (!duration || duration <= 0) return;
                    const durationMs = data.durationMs > 0 ? data.durationMs : duration * 1000;
                    Array.from(track.querySelectorAll('.timeline-marker')).forEach(n => n.remove());
                    for (const ev of data.events) {
                        const marker = document.createElement('div');
                        marker.className = 'timeline-marker type-' + ev.type;
                        const leftPct = (ev.start / durationMs) * 100;
                        const widthPct = Math.max(((ev.end - ev.start) / durationMs) * 100, 0.5);
                        marker.style.left = leftPct + '%';
                        marker.style.width = widthPct + '%';
                        track.appendChild(marker);
                    }
                    if (legend) legend.style.display = 'flex';
                };

                if (videoEl.readyState >= 1) {
                    renderMarkers();
                } else {
                    videoEl.addEventListener('loadedmetadata', renderMarkers, { once: true });
                }
            }
        } catch (e) {
            // Best-effort marker overlay; no events file is fine.
            console.debug('No timeline data for', filename);
        }
    },

    /**
     * Wire the external transport (play/pause, time, mute, scrubber).
     * Called from playVideo each time a clip opens; closeVideo's
     * destroyTimeline tears the listeners down. Idempotent: re-binding is
     * safe because we always remove the prior handler set first.
     */
    bindTransport(videoEl) {
        this.unbindTransport();

        const self = this;
        const scrubber = document.getElementById('timelineScrubber');
        const playhead = document.getElementById('timelinePlayhead');
        const progress = document.getElementById('timelineProgress');
        const playPauseBtn = document.getElementById('videoPlayPauseBtn');
        const muteBtn = document.getElementById('videoMuteBtn');
        const fullscreenBtn = document.getElementById('videoFullscreenBtn');
        const surface = document.getElementById('videoPlayer');
        if (!scrubber || !playhead || !playPauseBtn || !muteBtn) return;

        const setPct = (pct) => {
            const p = Math.max(0, Math.min(1, pct));
            playhead.style.left = (p * 100) + '%';
            if (progress) progress.style.width = (p * 100) + '%';
            try { scrubber.setAttribute('aria-valuenow', String(Math.round(p * 100))); } catch (e) {}
        };

        const formatTime = (sec) => {
            if (!isFinite(sec) || sec < 0) sec = 0;
            const m = Math.floor(sec / 60);
            const s = Math.floor(sec % 60);
            return m + ':' + (s < 10 ? '0' + s : s);
        };

        const refreshTime = () => {
            const cur = document.getElementById('videoCurTime');
            const dur = document.getElementById('videoDurTime');
            if (cur) cur.textContent = formatTime(videoEl.currentTime);
            if (dur && isFinite(videoEl.duration)) dur.textContent = formatTime(videoEl.duration);
        };

        const refreshPlayPauseIcon = () => {
            const icon = document.getElementById('videoPlayPauseIcon');
            if (!icon) return;
            // Rebuild children rather than swapping innerHTML on the SVG —
            // some Chrome 58 builds (BYD head unit) drop attribute mutations
            // on inline SVGs after innerHTML swap. Replace the SVG node
            // entirely for predictable repaint.
            const fresh = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            fresh.setAttribute('id', 'videoPlayPauseIcon');
            fresh.setAttribute('viewBox', '0 0 24 24');
            fresh.setAttribute('fill', 'currentColor');
            if (videoEl.paused) {
                const tri = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
                tri.setAttribute('points', '6,4 20,12 6,20');
                fresh.appendChild(tri);
            } else {
                const r1 = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                r1.setAttribute('x', '6'); r1.setAttribute('y', '4');
                r1.setAttribute('width', '4'); r1.setAttribute('height', '16');
                r1.setAttribute('rx', '1');
                const r2 = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
                r2.setAttribute('x', '14'); r2.setAttribute('y', '4');
                r2.setAttribute('width', '4'); r2.setAttribute('height', '16');
                r2.setAttribute('rx', '1');
                fresh.appendChild(r1); fresh.appendChild(r2);
            }
            icon.parentNode.replaceChild(fresh, icon);
        };

        const refreshMuteIcon = () => {
            const icon = document.getElementById('videoMuteIcon');
            if (!icon) return;
            const fresh = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            fresh.setAttribute('id', 'videoMuteIcon');
            fresh.setAttribute('viewBox', '0 0 24 24');
            fresh.setAttribute('fill', 'none');
            fresh.setAttribute('stroke', 'currentColor');
            fresh.setAttribute('stroke-width', '2');
            fresh.setAttribute('stroke-linecap', 'round');
            fresh.setAttribute('stroke-linejoin', 'round');
            const speaker = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
            speaker.setAttribute('points', '11 5 6 9 2 9 2 15 6 15 11 19 11 5');
            fresh.appendChild(speaker);
            if (videoEl.muted || videoEl.volume === 0) {
                // X over the speaker.
                const x1 = document.createElementNS('http://www.w3.org/2000/svg', 'line');
                x1.setAttribute('x1', '23'); x1.setAttribute('y1', '9');
                x1.setAttribute('x2', '17'); x1.setAttribute('y2', '15');
                const x2 = document.createElementNS('http://www.w3.org/2000/svg', 'line');
                x2.setAttribute('x1', '17'); x2.setAttribute('y1', '9');
                x2.setAttribute('x2', '23'); x2.setAttribute('y2', '15');
                fresh.appendChild(x1); fresh.appendChild(x2);
            } else {
                // Sound waves.
                const w1 = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                w1.setAttribute('d', 'M15.54 8.46a5 5 0 0 1 0 7.07');
                const w2 = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                w2.setAttribute('d', 'M19.07 4.93a10 10 0 0 1 0 14.14');
                fresh.appendChild(w1); fresh.appendChild(w2);
            }
            icon.parentNode.replaceChild(fresh, icon);
        };

        const onPlayPause = (e) => {
            if (e) e.stopPropagation();
            if (videoEl.paused) {
                const p = videoEl.play();
                if (p && typeof p.catch === 'function') p.catch(function() {});
            } else {
                videoEl.pause();
            }
        };

        const onMute = (e) => {
            if (e) e.stopPropagation();
            videoEl.muted = !videoEl.muted;
            // Persist explicit user gesture so the next clip honors it.
            try {
                localStorage.setItem('byd.events.unmuted', videoEl.muted ? '0' : '1');
                self._lastMutedState = videoEl.muted;
            } catch (_) {}
            refreshMuteIcon();
        };

        const onTimeUpdate = () => {
            if (self._scrubbing) return;
            if (videoEl.duration > 0) {
                setPct(videoEl.currentTime / videoEl.duration);
            }
            refreshTime();
        };

        const onDurationChange = () => refreshTime();

        /**
         * Match the wrapper's aspect-ratio to the actual mosaic dims.
         *
         * Why: recordings are a 2x2 mosaic — Seal is 2560×1920 (4:3),
         * Tang is 2560×1440 (16:9). With a fixed 16:9 wrapper, a 4:3
         * mosaic pillarboxes (black bars left+right). Zooming 2x into
         * a CORNER then surfaces half-of-pillarbox + half-of-quadrant
         * — the user reads the residual black band as broken zoom.
         *
         * The clean fix is to align the wrapper aspect to the source
         * (combined with object-fit:fill on the video). Each quadrant
         * is exactly 1/4 of the mosaic so it shares the same aspect;
         * once the wrapper matches the source, BOTH the all-view AND
         * every zoom-* corner view fill the frame edge-to-edge.
         *
         * Falls back gracefully if the browser doesn't honor
         * style.aspectRatio (very old WebViews) — the static 16/9 from
         * CSS still applies, which is the pre-fix behavior.
         */
        const onLoadedMetadata = () => {
            const wrap = document.getElementById('videoPlayerWrap');
            if (wrap && videoEl.videoWidth > 0 && videoEl.videoHeight > 0) {
                wrap.style.aspectRatio = videoEl.videoWidth + ' / ' + videoEl.videoHeight;
            }
            refreshTime();
            refreshPlayPauseIcon();
            refreshMuteIcon();
        };
        const onPlay = () => { refreshPlayPauseIcon(); playhead.classList.add('smooth'); };
        const onPause = () => { refreshPlayPauseIcon(); playhead.classList.remove('smooth'); };
        const onEnded = () => refreshPlayPauseIcon();
        const onVolumeChange = () => refreshMuteIcon();

        // Pointer-driven scrub: down → seek immediately + start tracking;
        // move → live seek (RAF-coalesced, dragging class amplifies playhead);
        // up/cancel → release. Uses Pointer Events (Chrome 55+) so we cover
        // mouse + touch + pen with one path.
        let scrubLatestPct = 0;
        let scrubRaf = 0;
        const seekToScrubPct = () => {
            scrubRaf = 0;
            if (videoEl.duration > 0) {
                videoEl.currentTime = scrubLatestPct * videoEl.duration;
            }
        };

        const pctFromEvent = (e) => {
            const rect = scrubber.getBoundingClientRect();
            if (rect.width <= 0) return 0;
            return Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
        };

        const onScrubDown = (e) => {
            if (e.button !== undefined && e.button !== 0) return;
            self._scrubbing = true;
            self._scrubPointerId = e.pointerId !== undefined ? e.pointerId : null;
            scrubber.classList.add('dragging');
            playhead.classList.remove('smooth');
            try { if (e.pointerId !== undefined) scrubber.setPointerCapture(e.pointerId); } catch (_) {}
            const pct = pctFromEvent(e);
            scrubLatestPct = pct;
            setPct(pct);
            // Reflect dragging time in the time chip so users see where
            // they're going to land — refresh after the implicit currentTime
            // mutation but seek through RAF to coalesce rapid moves.
            if (videoEl.duration > 0) {
                document.getElementById('videoCurTime').textContent = formatTime(pct * videoEl.duration);
            }
            if (!scrubRaf) scrubRaf = requestAnimationFrame(seekToScrubPct);
            e.preventDefault();
        };

        const onScrubMove = (e) => {
            if (!self._scrubbing) return;
            const pct = pctFromEvent(e);
            scrubLatestPct = pct;
            setPct(pct);
            if (videoEl.duration > 0) {
                document.getElementById('videoCurTime').textContent = formatTime(pct * videoEl.duration);
            }
            if (!scrubRaf) scrubRaf = requestAnimationFrame(seekToScrubPct);
        };

        const onScrubUp = (e) => {
            if (!self._scrubbing) return;
            self._scrubbing = false;
            scrubber.classList.remove('dragging');
            if (!videoEl.paused) playhead.classList.add('smooth');
            try {
                if (self._scrubPointerId !== null) scrubber.releasePointerCapture(self._scrubPointerId);
            } catch (_) {}
            self._scrubPointerId = null;
            // Final seek so the last sample wins even if the RAF didn't fire.
            if (videoEl.duration > 0) {
                videoEl.currentTime = scrubLatestPct * videoEl.duration;
            }
        };

        // Tap on the video surface = toggle play/pause (replaces what the
        // native controls used to do). Mirrors VideoPlayerFragment's
        // single-tap-confirmed → toggle path.
        const onSurfaceClick = (e) => {
            // Don't fight the dblclick→quadrant gesture: dblclick handler
            // already fires after this click, so the play/pause toggle is
            // reverted there. We just pass through.
            if (e.target !== videoEl) return;
            onPlayPause();
        };

        /* ------------------------------------------------------------------
         * Fullscreen — toggles the .video-container (NOT the <video>) so the
         * quadrant zoom + custom transport survive. Tries the standard
         * Fullscreen API first, then -webkit- (iOS Safari, older Chromium),
         * and finally falls back to a manual .is-fullscreen class for the
         * rare WebView with neither (the in-app head-unit WebView reliably
         * has the prefixed path; this is mostly a safety net).
         * ------------------------------------------------------------------ */
        const fsContainer = document.querySelector('.video-container');

        const isFsActive = () => {
            const fsEl = document.fullscreenElement || document.webkitFullscreenElement;
            return !!fsEl || (fsContainer && fsContainer.classList.contains('is-fullscreen'));
        };

        const refreshFullscreenIcon = () => {
            const icon = document.getElementById('videoFullscreenIcon');
            if (!icon) return;
            const fresh = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            fresh.setAttribute('id', 'videoFullscreenIcon');
            fresh.setAttribute('viewBox', '0 0 24 24');
            fresh.setAttribute('fill', 'none');
            fresh.setAttribute('stroke', 'currentColor');
            fresh.setAttribute('stroke-width', '2');
            fresh.setAttribute('stroke-linecap', 'round');
            fresh.setAttribute('stroke-linejoin', 'round');
            const paths = isFsActive()
                // Exit-fullscreen: arrows pointing inward (collapse).
                ? ['M9 4v5H4', 'M15 4v5h5', 'M9 20v-5H4', 'M15 20v-5h5']
                // Enter-fullscreen: arrows pointing outward (expand).
                : ['M4 9V4h5', 'M20 9V4h-5', 'M4 15v5h5', 'M20 15v5h-5'];
            for (const d of paths) {
                const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                p.setAttribute('d', d);
                fresh.appendChild(p);
            }
            icon.parentNode.replaceChild(fresh, icon);
        };

        const onFullscreen = (e) => {
            if (e) e.stopPropagation();
            if (!fsContainer) return;
            if (isFsActive()) {
                // Exit
                if (document.exitFullscreen) {
                    document.exitFullscreen().catch(() => {});
                } else if (document.webkitExitFullscreen) {
                    document.webkitExitFullscreen();
                } else {
                    fsContainer.classList.remove('is-fullscreen');
                    refreshFullscreenIcon();
                }
            } else {
                // Enter — request on the container so transport stays inside
                // the fullscreened subtree and remains visible.
                if (fsContainer.requestFullscreen) {
                    fsContainer.requestFullscreen().catch(() => {
                        fsContainer.classList.add('is-fullscreen');
                        refreshFullscreenIcon();
                    });
                } else if (fsContainer.webkitRequestFullscreen) {
                    try {
                        fsContainer.webkitRequestFullscreen();
                    } catch (_) {
                        fsContainer.classList.add('is-fullscreen');
                        refreshFullscreenIcon();
                    }
                } else {
                    fsContainer.classList.add('is-fullscreen');
                    refreshFullscreenIcon();
                }
            }
        };

        // The browser fires fullscreenchange when the user exits via Esc /
        // back gesture / system UI — keep the icon in sync without polling.
        // Also toggles a `show-handle` class so the pull-handle affordance
        // (CSS ::before pseudo) only renders while in fullscreen, where
        // the swipe-down-to-close gesture is the most natural exit.
        const onFullscreenChange = () => {
            refreshFullscreenIcon();
            if (fsContainer) {
                fsContainer.classList.toggle('show-handle', isFsActive());
            }
        };

        /* ------------------------------------------------------------------
         * Swipe-down-to-close — drag the header (or the pull-handle area
         * at the top edge) downward; release past 120 px or with strong
         * downward velocity to dismiss, otherwise spring back. Disabled
         * while the user is scrubbing so the scrubber's pointer capture
         * isn't fighting this gesture, and disabled on horizontal-leading
         * drags so a horizontal scrub doesn't accidentally close.
         *
         * Targets the header element (top strip) — the rest of the video
         * surface keeps its play/pause + double-tap-quadrant gestures.
         * In fullscreen the header is a transparent gradient strip across
         * the top of the video, so the gesture covers the natural "grab
         * the top of the sheet" affordance.
         * ------------------------------------------------------------------ */
        const headerEl = fsContainer ? fsContainer.querySelector('.video-header') : null;
        const modalEl = document.getElementById('videoModal');
        const SWIPE_DISMISS_PX = 120;       // drag-distance threshold
        const SWIPE_DISMISS_VEL = 0.6;      // px/ms threshold for fling
        let dragStartY = 0;
        let dragStartX = 0;
        let dragStartTime = 0;
        let dragLastY = 0;
        let dragLastTime = 0;
        let dragActive = false;
        let dragRejected = false;          // flipped true if user drags horizontally
        let dragPointerId = null;

        const setDragOffset = (dy) => {
            if (!fsContainer) return;
            const eased = dy < 0 ? -Math.sqrt(-dy * 4) : dy;     // resist upward drags
            fsContainer.style.transform = 'translateY(' + eased + 'px)';
            // Backdrop fade — proportional to drag, capped at 80% gone.
            if (modalEl) {
                const fade = Math.min(0.8, Math.max(0, dy) / 400);
                modalEl.style.background = 'rgba(0,0,0,' + (0.85 * (1 - fade)) + ')';
            }
        };

        const resetDrag = () => {
            if (!fsContainer) return;
            fsContainer.style.transform = '';
            if (modalEl) modalEl.style.background = '';
            if (modalEl) modalEl.classList.remove('dragging');
        };

        const dismiss = () => {
            // Fling animation: continue the translate to off-screen, then
            // close. The container's CSS transition does the curve.
            if (!fsContainer) return;
            fsContainer.style.transform = 'translateY(100vh)';
            if (modalEl) modalEl.style.background = 'rgba(0,0,0,0)';
            // After the transition fires, exit fullscreen + close the modal.
            setTimeout(() => {
                resetDrag();
                if (isFsActive()) {
                    if (document.exitFullscreen) document.exitFullscreen().catch(() => {});
                    else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                    else if (fsContainer) fsContainer.classList.remove('is-fullscreen');
                }
                self.closeVideo();
            }, 280);
        };

        const onDragStart = (e) => {
            if (self._scrubbing) return;
            // Only on primary button for mouse; touch / pen always pass.
            if (e.pointerType === 'mouse' && e.button !== 0) return;
            dragStartY = e.clientY;
            dragStartX = e.clientX;
            dragStartTime = e.timeStamp;
            dragLastY = e.clientY;
            dragLastTime = e.timeStamp;
            dragActive = true;
            dragRejected = false;
            dragPointerId = e.pointerId !== undefined ? e.pointerId : null;
        };

        const onDragMove = (e) => {
            if (!dragActive || dragRejected) return;
            const dy = e.clientY - dragStartY;
            const dx = Math.abs(e.clientX - dragStartX);
            // Within a 10 px slop circle: don't commit yet.
            if (Math.abs(dy) < 10 && dx < 10) return;
            // Horizontal-leading drag → not a dismiss gesture; release.
            if (dx > Math.abs(dy) * 1.4) {
                dragRejected = true;
                return;
            }
            // Capture so the gesture survives the cursor leaving the
            // header element (drag continues over the video).
            try { if (dragPointerId !== null && headerEl) headerEl.setPointerCapture(dragPointerId); } catch (_) {}
            if (modalEl) modalEl.classList.add('dragging');
            setDragOffset(dy);
            dragLastY = e.clientY;
            dragLastTime = e.timeStamp;
            // Prevent the header's text-selection / scrolling behaviors
            // while we own the gesture.
            e.preventDefault();
        };

        const onDragEnd = (e) => {
            if (!dragActive) return;
            const wasActive = !dragRejected;
            dragActive = false;
            try { if (dragPointerId !== null && headerEl) headerEl.releasePointerCapture(dragPointerId); } catch (_) {}
            dragPointerId = null;
            if (!wasActive) {
                resetDrag();
                return;
            }
            const dy = (e.clientY || dragLastY) - dragStartY;
            const dt = Math.max(1, (e.timeStamp || dragLastTime) - dragLastTime);
            const velY = (e.clientY ? (e.clientY - dragLastY) : 0) / dt; // px per ms (recent)
            if (dy > SWIPE_DISMISS_PX || velY > SWIPE_DISMISS_VEL) {
                dismiss();
            } else {
                resetDrag();
            }
        };

        if (headerEl) {
            if (typeof window.PointerEvent !== 'undefined') {
                headerEl.addEventListener('pointerdown', onDragStart);
                headerEl.addEventListener('pointermove', onDragMove);
                headerEl.addEventListener('pointerup', onDragEnd);
                headerEl.addEventListener('pointercancel', onDragEnd);
            } else {
                headerEl.addEventListener('mousedown', onDragStart);
                window.addEventListener('mousemove', onDragMove);
                window.addEventListener('mouseup', onDragEnd);
                headerEl.addEventListener('touchstart', (te) => {
                    const t = te.touches[0];
                    if (t) onDragStart({ pointerType: 'touch', button: 0, timeStamp: te.timeStamp, clientX: t.clientX, clientY: t.clientY });
                }, { passive: true });
                headerEl.addEventListener('touchmove', (te) => {
                    const t = te.touches[0];
                    if (t) onDragMove({ pointerType: 'touch', button: 0, timeStamp: te.timeStamp, clientX: t.clientX, clientY: t.clientY, preventDefault: () => te.preventDefault() });
                }, { passive: false });
                headerEl.addEventListener('touchend', (te) => {
                    const t = te.changedTouches[0];
                    if (t) onDragEnd({ pointerType: 'touch', button: 0, timeStamp: te.timeStamp, clientX: t.clientX, clientY: t.clientY });
                }, { passive: true });
            }
        }

        playPauseBtn.addEventListener('click', onPlayPause);
        muteBtn.addEventListener('click', onMute);
        if (fullscreenBtn) fullscreenBtn.addEventListener('click', onFullscreen);
        document.addEventListener('fullscreenchange', onFullscreenChange);
        document.addEventListener('webkitfullscreenchange', onFullscreenChange);
        videoEl.addEventListener('timeupdate', onTimeUpdate);
        videoEl.addEventListener('durationchange', onDurationChange);
        videoEl.addEventListener('loadedmetadata', onLoadedMetadata);
        videoEl.addEventListener('play', onPlay);
        videoEl.addEventListener('pause', onPause);
        videoEl.addEventListener('ended', onEnded);
        videoEl.addEventListener('volumechange', onVolumeChange);
        if (surface) surface.addEventListener('click', onSurfaceClick);

        if (typeof window.PointerEvent !== 'undefined') {
            scrubber.addEventListener('pointerdown', onScrubDown);
            scrubber.addEventListener('pointermove', onScrubMove);
            scrubber.addEventListener('pointerup', onScrubUp);
            scrubber.addEventListener('pointercancel', onScrubUp);
        } else {
            // Mouse + touch fallback for ancient WebViews. The BYD head unit
            // is Chrome 58+ so PointerEvent is available, but the dev/PWA
            // path (e.g. iOS 12 Safari testing) lands here.
            scrubber.addEventListener('mousedown', onScrubDown);
            window.addEventListener('mousemove', onScrubMove);
            window.addEventListener('mouseup', onScrubUp);
            scrubber.addEventListener('touchstart', (e) => onScrubDown(e.touches[0]), { passive: false });
            window.addEventListener('touchmove', (e) => onScrubMove(e.touches[0]), { passive: false });
            window.addEventListener('touchend', onScrubUp);
        }

        // Initial paint.
        refreshPlayPauseIcon();
        refreshMuteIcon();
        refreshFullscreenIcon();
        refreshTime();

        this._transport = {
            videoEl: videoEl,
            scrubber: scrubber,
            surface: surface,
            playPauseBtn: playPauseBtn,
            muteBtn: muteBtn,
            fullscreenBtn: fullscreenBtn,
            handlers: {
                onPlayPause, onMute, onFullscreen, onFullscreenChange,
                onTimeUpdate, onDurationChange, onLoadedMetadata,
                onPlay, onPause, onEnded, onVolumeChange,
                onScrubDown, onScrubMove, onScrubUp, onSurfaceClick,
                onDragStart, onDragMove, onDragEnd
            },
            headerEl: headerEl
        };
    },

    /**
     * Detach every listener bindTransport installed. Safe to call when
     * nothing is bound (idempotent).
     */
    unbindTransport() {
        const t = this._transport;
        if (!t) return;
        const { videoEl, scrubber, surface, playPauseBtn, muteBtn, fullscreenBtn, headerEl, handlers } = t;
        try {
            playPauseBtn.removeEventListener('click', handlers.onPlayPause);
            muteBtn.removeEventListener('click', handlers.onMute);
            if (fullscreenBtn) fullscreenBtn.removeEventListener('click', handlers.onFullscreen);
            document.removeEventListener('fullscreenchange', handlers.onFullscreenChange);
            document.removeEventListener('webkitfullscreenchange', handlers.onFullscreenChange);
            // Drop the manual-fallback class + any in-flight drag styles
            // if a closeVideo happened while we were "fullscreen" via the
            // className path (no API). Otherwise the next playVideo would
            // inherit a translated container or the fullscreen layout.
            const fsContainer = document.querySelector('.video-container');
            if (fsContainer) {
                fsContainer.classList.remove('is-fullscreen');
                fsContainer.classList.remove('show-handle');
                fsContainer.style.transform = '';
            }
            const modalEl = document.getElementById('videoModal');
            if (modalEl) {
                modalEl.classList.remove('dragging');
                modalEl.style.background = '';
            }
            if (headerEl && handlers.onDragStart) {
                if (typeof window.PointerEvent !== 'undefined') {
                    headerEl.removeEventListener('pointerdown', handlers.onDragStart);
                    headerEl.removeEventListener('pointermove', handlers.onDragMove);
                    headerEl.removeEventListener('pointerup', handlers.onDragEnd);
                    headerEl.removeEventListener('pointercancel', handlers.onDragEnd);
                } else {
                    headerEl.removeEventListener('mousedown', handlers.onDragStart);
                    window.removeEventListener('mousemove', handlers.onDragMove);
                    window.removeEventListener('mouseup', handlers.onDragEnd);
                    // touch wrappers used anonymous closures; they GC with the DOM.
                }
            }
            videoEl.removeEventListener('timeupdate', handlers.onTimeUpdate);
            videoEl.removeEventListener('durationchange', handlers.onDurationChange);
            videoEl.removeEventListener('loadedmetadata', handlers.onLoadedMetadata);
            videoEl.removeEventListener('play', handlers.onPlay);
            videoEl.removeEventListener('pause', handlers.onPause);
            videoEl.removeEventListener('ended', handlers.onEnded);
            videoEl.removeEventListener('volumechange', handlers.onVolumeChange);
            if (surface) surface.removeEventListener('click', handlers.onSurfaceClick);
            if (typeof window.PointerEvent !== 'undefined') {
                scrubber.removeEventListener('pointerdown', handlers.onScrubDown);
                scrubber.removeEventListener('pointermove', handlers.onScrubMove);
                scrubber.removeEventListener('pointerup', handlers.onScrubUp);
                scrubber.removeEventListener('pointercancel', handlers.onScrubUp);
            } else {
                scrubber.removeEventListener('mousedown', handlers.onScrubDown);
                window.removeEventListener('mousemove', handlers.onScrubMove);
                window.removeEventListener('mouseup', handlers.onScrubUp);
                // touch listeners use anonymous wrappers; relying on the
                // surface being removed-from-DOM (modal close) to GC them.
            }
        } catch (_) { /* best-effort cleanup */ }
        this._transport = null;
        this._scrubbing = false;
        this._scrubPointerId = null;
    },

    /**
     * Tear down timeline + transport for closeVideo / clip switch.
     */
    destroyTimeline() {
        if (this._timelineRaf) {
            cancelAnimationFrame(this._timelineRaf);
            this._timelineRaf = null;
        }
        this._timelineEvents = null;

        const track = document.getElementById('timelineTrack');
        const legend = document.getElementById('timelineLegend');
        const playhead = document.getElementById('timelinePlayhead');
        const progress = document.getElementById('timelineProgress');
        const wrap = document.getElementById('videoPlayerWrap');
        if (track) Array.from(track.querySelectorAll('.timeline-marker')).forEach(n => n.remove());
        if (playhead) playhead.style.left = '0%';
        if (progress) progress.style.width = '0%';
        if (legend) legend.style.display = 'none';
        // Drop the inline aspect-ratio so the wrapper falls back to the CSS
        // 16/9 default before the next clip's metadata fires. Without this,
        // a Tang clip (16:9) opened after a Seal clip (4:3) would briefly
        // render at 4:3 dimensions until loadedmetadata corrected it.
        if (wrap) wrap.style.aspectRatio = '';

        this.unbindTransport();
    }
};