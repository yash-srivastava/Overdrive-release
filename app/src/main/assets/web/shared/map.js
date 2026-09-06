/**
 * BYD Champ - Map Module
 * 
 * Features:
 * - Leaflet.js map with car position marker
 * - User location with route to car
 * - Google Maps directions integration
 * - Real-time GPS updates from consolidated /status endpoint
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

    // Auto-follow state -- true means every car position update re-centers
    // the map on the car. A genuine user pan/zoom (detected via Leaflet's
    // dragstart/zoomstart events, which only carry an originalEvent when
    // they originate from real mouse/touch/wheel input -- our own
    // panTo()/setView() calls never do) flips this off and shows the
    // recenter button; tapping it (or a fresh position after re-init)
    // turns following back on.
    followCar: true,
    recenterBtnEl: null,

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

        // Reset follow-state and wire up user-pan/zoom detection fresh for
        // this map instance (each page calls init() once on its own
        // #mapContainer, so there's nothing to double-bind).
        this.followCar = true;
        this.recenterBtnEl = null;
        this._bindInteractionTracking();

        // Create car marker with custom icon
        this.carMarker = L.marker([0, 0], {
            icon: this.createCarIcon(),
            rotationAngle: 0
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
        
        // Show location even if stale/cached (last known position when ACC was on)
        if (gpsData.hasLocation) {
            this.updateCarPosition(gpsData.lat, gpsData.lng, gpsData.heading, gpsData.speed);
            
            // Update last GPS update time with staleness indicator
            if (lastUpdateEl && gpsData.lastUpdate) {
                const ago = this.formatTimeAgo(gpsData.lastUpdate);
                if (gpsData.isCached) {
                    // Location is from cache (ACC likely off, no recent GPS)
                    lastUpdateEl.textContent = ago + ' (cached)';
                    lastUpdateEl.classList.add('stale');
                } else if (gpsData.isStale) {
                    // Location is stale but not too old
                    lastUpdateEl.textContent = ago + ' (stale)';
                    lastUpdateEl.classList.add('stale');
                } else {
                    lastUpdateEl.textContent = ago;
                    lastUpdateEl.classList.remove('stale');
                }
            }
        } else {
            if (lastUpdateEl) {
                lastUpdateEl.textContent = 'No GPS';
                lastUpdateEl.classList.add('stale');
            }
        }
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
        if (seconds < 60) return seconds + 's ago';
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return minutes + 'm ago';
        const hours = Math.floor(minutes / 60);
        return hours + 'h ago';
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
        
        // Center map on car if this is first position; on every later
        // update, keep following the car (at whatever zoom the user is
        // currently at) as long as they haven't panned/zoomed away.
        // panTo() never carries an originalEvent, so it can't be mistaken
        // for user interaction by the dragstart/zoomstart listeners below.
        if (!this.hasInitialPosition) {
            this.map.setView([lat, lng], this.DEFAULT_ZOOM);
            this.hasInitialPosition = true;
        } else if (this.followCar) {
            this.map.panTo([lat, lng], { animate: true });
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
            BYD.utils && BYD.utils.toast('Geolocation not supported', 'error');
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
                    BYD.utils && BYD.utils.toast('Location inaccurate (' + Math.round(accuracy) + 'm). Try enabling GPS.', 'warning');
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
                
                // Fit bounds to show both markers
                this.fitBounds();
                
                if (btn) btn.classList.remove('loading');
                BYD.utils && BYD.utils.toast('Location found', 'success');
            },
            (err) => {
                console.error('[Map] Geolocation error:', err);
                if (btn) btn.classList.remove('loading');
                BYD.utils && BYD.utils.toast('Could not get location', 'error');
            },
            {
                enableHighAccuracy: true,
                timeout: 15000,
                maximumAge: 10000
            }
        );
    },
    
    /**
     * Draw route line from user to car
     */
    drawRoute() {
        if (!this.map || !this.userPosition || !this.carPosition.lat) return;
        
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
        
        if (this.carPosition.lat) {
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
     * Center map on car. An explicit "go to car" action, so it also
     * resumes auto-follow -- same as tapping the recenter button.
     */
    centerOnCar() {
        if (!this.map || !this.carPosition.lat) return;
        this.map.setView([this.carPosition.lat, this.carPosition.lng], this.DEFAULT_ZOOM);
        this.followCar = true;
        this._hideRecenterButton();
    },

    /**
     * Wire up detection for genuine user pans/zooms (drag, wheel, pinch,
     * double-click-zoom) vs. our own programmatic panTo()/setView() calls.
     * Leaflet only attaches originalEvent to move/zoom events that trace
     * back to real DOM input, so panTo()/setView() (no originalEvent) can
     * never be mistaken for a user pulling away from the car.
     */
    _bindInteractionTracking() {
        if (!this.map || this._interactionBound) return;
        this._interactionBound = true;

        this.map.on('dragstart', () => this._onUserInteraction());
        this.map.on('zoomstart', (e) => {
            if (e.originalEvent) this._onUserInteraction();
        });
    },

    _onUserInteraction() {
        if (!this.followCar) return;
        this.followCar = false;
        this._showRecenterButton();
    },

    /**
     * Create (once) and reveal the floating recenter button inside the
     * Leaflet container. Lives outside Leaflet's own pane system so it
     * never gets swept up by tile/marker z-index rules.
     */
    _showRecenterButton() {
        if (this.recenterBtnEl) {
            this.recenterBtnEl.classList.add('visible');
            return;
        }
        if (!this.map) return;

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'map-recenter-btn visible';
        btn.setAttribute('aria-label', BYD.i18n.t('map.recenter') || 'Recenter on vehicle');
        btn.innerHTML = `
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="3"/>
                <path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>
            </svg>
        `;
        btn.addEventListener('click', (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            this.centerOnCar();
        });

        this.map.getContainer().appendChild(btn);
        this.recenterBtnEl = btn;
    },

    _hideRecenterButton() {
        if (this.recenterBtnEl) this.recenterBtnEl.classList.remove('visible');
    },

    /**
     * Open Google Maps with directions to car
     */
    openDirections() {
        if (!this.carPosition.lat) {
            BYD.utils && BYD.utils.toast('Car location not available', 'error');
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
        if (this.map) {
            this.map.remove();
            this.map = null;
        }
        this.isInitialized = false;
        this._interactionBound = false;
        this.recenterBtnEl = null;
        this.followCar = true;
    }
};
