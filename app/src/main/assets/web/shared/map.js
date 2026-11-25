/**
 * BYD Champ - Map Module
 * 
 * Features:
 * - Leaflet.js map with car position marker
 * - User location with route to car
 * - Google Maps directions integration
 * - Real-time GPS updates from consolidated /status endpoint
 * - Follow mode: map re-centers on the car as it moves; dragging the map
 *   pauses it (auto-resumes after idle, or via the follow button)
 */

window.BYD = window.BYD || {};

BYD.map = {
    // Leaflet instances
    map: null,
    carMarker: null,
    userMarker: null,
    routeLine: null,
    
    // State
    carPosition: { lat: 0, lng: 0, heading: 0, speed: 0, accuracy: 0 },
    userPosition: null,
    isInitialized: false,
    distanceLabel: null,
    
    // Default center (will be updated with car position)
    DEFAULT_ZOOM: 18,

    // Follow mode: keep the car centered while it moves. Paused while the
    // user is panning the map by hand, then resumed after FOLLOW_RESUME_MS
    // of inactivity (or immediately via the follow button / centerOnCar()).
    followCar: true,
    FOLLOW_RESUME_MS: 15000,
    FOLLOW_MIN_MOVE_M: 15,
    _lastFollowLatLng: null,
    _followResumeTimer: null,
    _followBtn: null,
    
    /**
     * Initialize the map
     */
    init() {
        const container = document.getElementById('mapContainer');
        if (!container || this.isInitialized) return;
        
        // Check if Leaflet is loaded
        if (typeof L === 'undefined') {
            console.error('[Map] Leaflet not loaded');
            return;
        }
        
        console.log('[Map] Initializing...');
        
        // Start GPS tracking on the backend
        this.startGpsTracking();
        
        this.map = L.map('mapContainer', {
            zoomControl: false,
            attributionControl: false
        }).setView([0, 0], 2);
        
        // Shared keyless OSM.de tiles follow the current day/night theme.
        BYD.theme.attachMapTiles(this.map);
        
        // Add zoom control to bottom right
        L.control.zoom({ position: 'bottomright' }).addTo(this.map);

        // Follow-vehicle toggle (sits above the zoom buttons) and pause on
        // manual drag. 'dragstart' only fires for user drags, never for
        // our own panTo()/setView() calls.
        this._addFollowControl();
        this.map.on('dragstart', () => this.pauseFollow());
        
        // Create car marker with custom icon
        this.carMarker = L.marker([0, 0], {
            icon: this.createCarIcon(),
            rotationAngle: 0,
            title: BYD.i18n.t('map.your_vehicle')
        }).addTo(this.map);

        this.carMarker.bindPopup('<b>' + BYD.i18n.t('map.your_vehicle') + '</b><br>' + BYD.i18n.t('map.your_vehicle_sub'));

        // Mount the top-down 3D vehicle render onto the marker canvas
        // now that Leaflet has injected the divIcon's HTML into the
        // map pane. mountVehicleCanvas is idempotent and tracks the
        // sidebar's (model, colour) selection automatically.
        this._mountCarIcon3d();
        
        this.isInitialized = true;

        // Force map to recalculate size (fixes rendering issues)
        setTimeout(() => {
            if (this.map) {
                this.map.invalidateSize();
            }
        }, 100);

        // Re-measure on viewport changes. Leaflet caches the container's
        // pixel box and only re-reads it on invalidateSize(); without this
        // the map paints blank/clipped tiles after a landscape↔portrait
        // rotation (the container box changed but Leaflet never noticed).
        // theme.js dispatches a synthetic `resize` on orientationchange too,
        // so this single listener covers both plain resizes and rotation.
        if (!this._viewportListenerBound) {
            this._viewportListenerBound = true;
            window.addEventListener('resize', () => {
                if (this._mapResizeTimer) clearTimeout(this._mapResizeTimer);
                this._mapResizeTimer = setTimeout(() => {
                    if (this.map) this.map.invalidateSize();
                }, 120);
            });
        }

        // GPS data now comes from consolidated /status call via core.js
        // Check if we already have status data
        if (BYD.core && BYD.core.lastStatus && BYD.core.lastStatus.gps) {
            this.updateFromStatus(BYD.core.lastStatus.gps);
        }

        console.log('[Map] Initialized');
    },
    
    /**
     * Mount OverdriveEvCard3D onto the map marker canvas. The canvas
     * is injected by Leaflet inside `createCarIcon()`'s divIcon HTML,
     * so we reach it through the live #carIconCanvas in the map pane
     * (Leaflet renders the divIcon to a positioned <div> with the same
     * DOM contents we returned).
     *
     * If the user's vehicle selection changes (e.g. they pick a new
     * model on vehicle-control.html and call OverdriveAppShell.refresh
     * Vehicle()), app-shell.js fans the change out to all aux instances
     * — so the marker stays in sync without us re-mounting.
     */
    _mountCarIcon3d() {
        const canvas = document.getElementById('carIconCanvas');
        if (!canvas) return;
        const shell = window.OverdriveAppShell;
        if (!shell || typeof shell.mountVehicleCanvas !== 'function') {
            // Shell loaded after map.init — listen for the ready event
            // so we still get the 3D render once the API exists.
            document.addEventListener('app-shell:ready', () => this._mountCarIcon3d(), { once: true });
            return;
        }
        shell.mountVehicleCanvas(canvas, { view: 'top' });
    },

    /**
     * Start GPS tracking on the backend
     */
    async startGpsTracking() {
        try {
            await fetch('/api/gps/start', { method: 'POST' });
            console.log('[Map] GPS tracking started');
        } catch (e) {
            console.error('[Map] Failed to start GPS tracking:', e);
        }
    },
    
    /**
     * Update from consolidated status call (called by core.js)
     */
    updateFromStatus(gpsData) {
        if (!gpsData) return;
        
        const lastUpdateEl = document.getElementById('lastGpsUpdate');
        // First resolved paint only, including "No GPS" — that is an answer, not loading.
        if (window.BYD && BYD.skeleton) {
            BYD.skeleton.resolve('gpsFreshness');
            BYD.skeleton.resolve('gpsDistance');
        }
        
        // Show location even if stale/cached (last known position when ACC was on)
        if (gpsData.hasLocation) {
            this.updateCarPosition(gpsData.lat, gpsData.lng, gpsData.heading, gpsData.speed);
            
            // Update last GPS update time with staleness indicator
            if (lastUpdateEl && gpsData.lastUpdate) {
                const ago = this.formatTimeAgo(gpsData.lastUpdate);
                if (gpsData.isCached) {
                    // Location is from cache (ACC likely off, no recent GPS)
                    this._setFreshness(lastUpdateEl, ago + ' (cached)', 'is-stale');
                } else if (gpsData.isStale) {
                    // Location is stale but not too old
                    this._setFreshness(lastUpdateEl, ago + ' (stale)', 'is-stale');
                } else {
                    this._setFreshness(lastUpdateEl, ago, 'is-live');
                }
            }
        } else {
            if (lastUpdateEl) {
                this._setFreshness(lastUpdateEl, 'No GPS', 'is-down');
            }
        }
    },

    /** Freshness reads as a state dot plus a plain label, never coloured text. */
    _setFreshness(labelEl, text, dotState) {
        labelEl.textContent = text;
        labelEl.classList.remove('stale');
        const row = labelEl.closest
            ? labelEl.closest('.location-freshness')
            : document.querySelector('.location-freshness');
        const dot = row && row.querySelector('.location-freshness-dot');
        if (dot) dot.className = 'location-freshness-dot ' + dotState;
    },
    
    /**
     * Create custom car icon. Renders the user's selected GLB top-down
     * via OverdriveEvCard3D — same model and paint colour as the
     * sidebar EV-card and the Live View camera selector. The wrapper
     * keeps the same dimensions as the legacy car-icon-map.webp PNG so
     * Leaflet's iconSize / iconAnchor stay valid and rotation by
     * `wrapper.style.transform = rotate(heading)` continues to work
     * (the canvas rotates as a 2D bitmap; the top-down render points
     * forward along canvas-up so heading=0 = front pointing north).
     */
    createCarIcon() {
        return L.divIcon({
            className: 'car-map-marker',
            html: `
                <div class="car-icon-wrapper" id="carIconWrapper">
                    <canvas id="carIconCanvas" class="car-icon-img" aria-hidden="true"></canvas>
                    <div class="car-pulse"></div>
                </div>
            `,
            iconSize: [24, 50],
            iconAnchor: [12, 25]
        });
    },
    
    /**
     * Create user location icon
     */
    createUserIcon() {
        return L.divIcon({
            className: 'user-map-marker',
            html: `
                <div class="user-icon-wrapper">
                    <div class="user-dot"></div>
                    <div class="user-pulse"></div>
                </div>
            `,
            iconSize: [24, 24],
            iconAnchor: [12, 12]
        });
    },
    
    /**
     * Format time ago string
     */
    formatTimeAgo(timestamp) {
        const seconds = Math.floor((Date.now() - timestamp) / 1000);
        function ago(key, n, fallback) {
            var phrase = (window.BYD && BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key, { n: n }) : null;
            if (phrase && phrase !== key) return phrase;
            return fallback;
        }
        if (seconds < 60) return ago('dashboard.time_seconds_ago', seconds, seconds + 's ago');
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return ago('dashboard.time_minutes_ago', minutes, minutes + 'm ago');
        const hours = Math.floor(minutes / 60);
        return ago('dashboard.time_hours_ago', hours, hours + 'h ago');
    },
    
    /**
     * Update car position on map
     */
    updateCarPosition(lat, lng, heading = 0, speed = 0) {
        if (!this.map || !this.carMarker) return;
        
        this.carPosition = { lat, lng, heading, speed };
        
        // Update marker position
        this.carMarker.setLatLng([lat, lng]);
        
        // Rotate car icon based on heading and add glow class
        const wrapper = document.getElementById('carIconWrapper');
        if (wrapper) {
            wrapper.style.transform = `rotate(${heading}deg)`;
            // Add enhanced glow class when location is present
            wrapper.classList.add('has-location');
        }
        
        // Activate pulse animation when GPS is received
        const pulse = document.querySelector('.car-pulse');
        if (pulse) {
            pulse.classList.add('active');
        }
        
        // Center map on car if this is first position
        if (!this.hasInitialPosition) {
            this.map.setView([lat, lng], this.DEFAULT_ZOOM);
            this.hasInitialPosition = true;
            this._lastFollowLatLng = [lat, lng];
        } else if (this.followCar && this._followTargetMoved(lat, lng)) {
            // Keep the car centered as it moves. Keep the user's zoom, and
            // skip the animation: the marker jumps to its new fix, so an
            // animated pan would make it slide off-center and back each tick.
            this.map.panTo([lat, lng], { animate: false });
            this._lastFollowLatLng = [lat, lng];
        }
        
        // Update route if user position exists
        if (this.userPosition) {
            this.drawRoute();
        }
    },
    
    /**
     * Show user's current location
     */
    showMyLocation() {
        if (!navigator.geolocation) {
            BYD.utils && BYD.utils.toast(
                BYD.i18n.t('map.geo_not_supported') || 'Geolocation not supported', 'error');
            return;
        }
        
        // Show loading state
        const btn = document.querySelector('.btn-my-location');
        if (btn) btn.classList.add('loading');
        
        navigator.geolocation.getCurrentPosition(
            (pos) => {
                const { latitude, longitude, accuracy } = pos.coords;
                
                // Filter out very inaccurate positions (> 500m radius)
                if (accuracy > 500) {
                    console.warn('[Map] Location too inaccurate:', accuracy, 'm');
                    if (btn) btn.classList.remove('loading');
                    BYD.utils && BYD.utils.toast(
                        BYD.i18n.t('map.location_inaccurate', {m: Math.round(accuracy)})
                            || ('Location inaccurate (' + Math.round(accuracy) + 'm). Try enabling GPS.'),
                        'warning');
                    return;
                }
                
                this.userPosition = { lat: latitude, lng: longitude };
                
                // Create or update user marker
                if (!this.userMarker) {
                    this.userMarker = L.marker([latitude, longitude], {
                        icon: this.createUserIcon()
                    }).addTo(this.map);
                    this.userMarker.bindPopup('<b>' + BYD.i18n.t('map.you_are_here') + '</b>');
                } else {
                    this.userMarker.setLatLng([latitude, longitude]);
                }
                
                // Draw route to car
                this.drawRoute();
                
                // Fit bounds to show both markers. Hold follow off so the
                // next GPS tick doesn't snap the view back onto the car.
                this.pauseFollow({ hold: true });
                this.fitBounds();
                
                if (btn) btn.classList.remove('loading');
                BYD.utils && BYD.utils.toast(
                    BYD.i18n.t('map.location_found') || 'Location found', 'success');
            },
            (err) => {
                console.error('[Map] Geolocation error:', err);
                if (btn) btn.classList.remove('loading');
                BYD.utils && BYD.utils.toast(
                    BYD.i18n.t('map.location_failed') || 'Could not get location', 'error');
            },
            {
                enableHighAccuracy: true,
                timeout: 15000,
                maximumAge: 10000
            }
        );
    },
    
    // Match the GPS no-location sentinel without rejecting valid zero-degree coordinates.
    _hasCarPosition() {
        const { lat, lng } = this.carPosition;
        return Number.isFinite(lat) && Number.isFinite(lng)
            && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
            && (lat !== 0 || lng !== 0);
    },

    /**
     * Draw route line from user to car
     */
    drawRoute() {
        if (!this.map || !this.userPosition || !this._hasCarPosition()) return;
        
        // Remove existing route and label
        if (this.routeLine) {
            this.map.removeLayer(this.routeLine);
        }
        if (this.distanceLabel) {
            this.map.removeLayer(this.distanceLabel);
        }
        
        // Draw simple line (for full routing, would need routing service)
        this.routeLine = L.polyline([
            [this.userPosition.lat, this.userPosition.lng],
            [this.carPosition.lat, this.carPosition.lng]
        ], {
            color: '#00D4AA',
            weight: 3,
            opacity: 0.7,
            dashArray: '10, 10'
        }).addTo(this.map);
        
        // Calculate distance
        const distance = this.calculateDistance(
            this.userPosition.lat, this.userPosition.lng,
            this.carPosition.lat, this.carPosition.lng
        );
        
        // Calculate midpoint for label
        const midLat = (this.userPosition.lat + this.carPosition.lat) / 2;
        const midLng = (this.userPosition.lng + this.carPosition.lng) / 2;
        
        // Add distance label on the line
        const distanceText = this.formatDistance(distance);
        this.distanceLabel = L.marker([midLat, midLng], {
            icon: L.divIcon({
                className: 'distance-label',
                html: `<div class="distance-label-content">${distanceText}</div>`,
                iconSize: [80, 24],
                iconAnchor: [40, 12]
            })
        }).addTo(this.map);
        
        // Update distance display
        const distanceEl = document.getElementById('distanceToCar');
        if (distanceEl) {
            distanceEl.textContent = distanceText;
        }
    },
    
    /**
     * Fit map bounds to show both markers
     */
    fitBounds() {
        if (!this.map) return;
        
        const bounds = [];
        
        if (this._hasCarPosition()) {
            bounds.push([this.carPosition.lat, this.carPosition.lng]);
        }
        
        if (this.userPosition) {
            bounds.push([this.userPosition.lat, this.userPosition.lng]);
        }
        
        if (bounds.length > 1) {
            this.map.fitBounds(bounds, { padding: [50, 50] });
        } else if (bounds.length === 1) {
            this.map.setView(bounds[0], this.DEFAULT_ZOOM);
        }
    },
    
    /**
     * Center map on car
     */
    centerOnCar() {
        if (!this.map || !this._hasCarPosition()) return;
        this.map.setView([this.carPosition.lat, this.carPosition.lng], this.DEFAULT_ZOOM);
        this._setFollow(true);
    },

    /**
     * Pause follow mode (user took control of the map).
     * Auto-resumes after FOLLOW_RESUME_MS unless opts.hold is set.
     */
    pauseFollow(opts) {
        this._clearFollowTimer();
        this._setFollow(false);
        if (!(opts && opts.hold)) {
            this._followResumeTimer = setTimeout(() => {
                this._followResumeTimer = null;
                this.resumeFollow(false);
            }, this.FOLLOW_RESUME_MS);
        }
    },

    /**
     * Resume follow mode (keeps current zoom). An idle auto-resume waits for
     * the car to move instead of snapping back to a parked car.
     */
    resumeFollow(panNow = true) {
        this._clearFollowTimer();
        this._setFollow(true);
        if (!this.map || !this._hasCarPosition()) return;
        this._lastFollowLatLng = [this.carPosition.lat, this.carPosition.lng];
        if (panNow) this.map.panTo(this._lastFollowLatLng);
    },

    // GPS jitter on a parked car must not re-center the map.
    _followTargetMoved(lat, lng) {
        const last = this._lastFollowLatLng;
        if (!last) return true;
        return this.map.distance(last, [lat, lng]) > this.FOLLOW_MIN_MOVE_M;
    },

    toggleFollow() {
        if (this.followCar) {
            this.pauseFollow({ hold: true });
        } else {
            this.resumeFollow();
        }
    },

    _clearFollowTimer() {
        if (this._followResumeTimer) {
            clearTimeout(this._followResumeTimer);
            this._followResumeTimer = null;
        }
    },

    _setFollow(on) {
        this.followCar = !!on;
        const btn = this._followBtn;
        if (!btn) return;
        btn.setAttribute('aria-pressed', this.followCar ? 'true' : 'false');
        btn.style.color = this.followCar ? 'var(--primary, #00D4AA)' : '';
    },

    /**
     * Leaflet control: round-the-car crosshair button that toggles follow.
     * Reuses the zoom control's classes so it picks up the existing
     * theme styling on both index.html and live-view.html.
     */
    _addFollowControl() {
        const self = this;
        const FollowControl = L.Control.extend({
            options: { position: 'bottomright' },
            onAdd() {
                const bar = L.DomUtil.create('div', 'leaflet-bar leaflet-control-zoom leaflet-control-follow');
                const a = L.DomUtil.create('a', '', bar);
                const key = 'map.follow_vehicle';
                let label = (BYD.i18n && BYD.i18n.t) ? BYD.i18n.t(key) : null;
                if (!label || label === key) label = 'Follow vehicle';
                a.href = '#';
                a.setAttribute('role', 'button');
                a.title = label;
                a.setAttribute('aria-label', label);
                a.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" ' +
                    'stroke="currentColor" stroke-width="2" stroke-linecap="round" ' +
                    'style="vertical-align:middle" aria-hidden="true">' +
                    '<circle cx="12" cy="12" r="3.5"/><circle cx="12" cy="12" r="8"/>' +
                    '<path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3"/></svg>';
                L.DomEvent.disableClickPropagation(bar);
                L.DomEvent.on(a, 'click', (e) => {
                    L.DomEvent.preventDefault(e);
                    self.toggleFollow();
                });
                self._followBtn = a;
                return bar;
            }
        });
        new FollowControl().addTo(this.map);
        this._setFollow(this.followCar);
    },
    
    /**
     * Open Google Maps with directions to car
     */
    openDirections() {
        if (!this._hasCarPosition()) {
            BYD.utils && BYD.utils.toast(
                BYD.i18n.t('map.car_location_unavailable') || 'Car location not available', 'error');
            return;
        }
        
        const url = `https://www.google.com/maps/dir/?api=1&destination=${this.carPosition.lat},${this.carPosition.lng}&travelmode=driving`;
        window.open(url, '_blank');
    },
    
    /**
     * Calculate distance between two points (Haversine formula)
     */
    calculateDistance(lat1, lng1, lat2, lng2) {
        const R = 6371e3; // Earth radius in meters
        const φ1 = lat1 * Math.PI / 180;
        const φ2 = lat2 * Math.PI / 180;
        const Δφ = (lat2 - lat1) * Math.PI / 180;
        const Δλ = (lng2 - lng1) * Math.PI / 180;
        
        const a = Math.sin(Δφ/2) * Math.sin(Δφ/2) +
                  Math.cos(φ1) * Math.cos(φ2) *
                  Math.sin(Δλ/2) * Math.sin(Δλ/2);
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        
        return R * c; // Distance in meters
    },
    
    /**
     * Format distance for display. Sub-1km values stay in meters/feet for
     * precision; longer distances go through BYD.units to honor mi mode.
     */
    formatDistance(meters) {
        if (typeof BYD !== 'undefined' && BYD.units && BYD.units.mode === 'mi') {
            // 1 ft = 0.3048 m, 1 mi = 1609.344 m
            if (meters < 1609.344) {
                return Math.round(meters / 0.3048) + ' ft';
            }
            return BYD.units.dist(meters / 1000, 1);
        }
        if (meters < 1000) {
            return Math.round(meters) + ' m';
        }
        return (meters / 1000).toFixed(1) + ' km';
    },
    
    /**
     * Cleanup
     */
    destroy() {
        this._clearFollowTimer();
        this._followBtn = null;
        this._lastFollowLatLng = null;
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
        this.isInitialized = false;
    }
};
