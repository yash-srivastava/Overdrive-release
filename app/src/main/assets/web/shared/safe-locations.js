/**
 * Safe Locations — SOTA Interactive Geofence Editor
 *
 * UX Flow:
 * 1. Toggle ON → map appears centered on current GPS
 * 2. A live draggable circle shows the zone being configured
 * 3. Slider below map controls radius (50-500m), circle updates in real-time
 * 4. "Add This Zone" saves it with a name prompt
 * 5. Saved zones appear as circles on map + list cards below
 */

window.SafeLocations = {
    map: null,
    zones: [],
    zoneCircles: {},    // id -> L.circle (saved zones)
    zoneMarkers: {},    // id -> L.marker (saved zones)
    featureEnabled: false,
    currentGps: null,
    gpsMarker: null,

    // Live editing circle
    editCircle: null,
    editRadius: 150,

    refreshTimer: null,

    async init() {
        await this.loadData();
        this.updateUI();
        this.refreshTimer = setInterval(() => this.refreshStatus(), 5000);
    },

    async loadData() {
        try {
            const resp = await fetch('/api/surveillance/safe-locations');
            const data = await resp.json();
            this.featureEnabled = data.featureEnabled || false;
            this.zones = data.zones || [];
            this.currentGps = data.hasGps ? { lat: data.lat, lng: data.lng, accuracy: data.accuracy } : null;
            this.updateStatusText(data);
        } catch (e) {
            console.warn('Failed to load safe locations:', e);
        }
    },

    updateStatusText(data) {
        const el = document.getElementById('safeLocStatus');
        if (!el) return;
        if (data.inSafeZone) {
            el.textContent = BYD.i18n.t('safe_loc.in_safe_zone', {zone: data.currentZone, meters: data.nearestDistanceM});
            el.style.color = 'var(--brand-secondary)';
        } else if (data.hasGps && this.zones.length > 0) {
            el.textContent = BYD.i18n.t('safe_loc.outside_zone', {meters: data.nearestDistanceM});
            el.style.color = 'var(--danger)';
        } else {
            el.textContent = this.zones.length === 0 ? BYD.i18n.t('safe_loc.no_zones_yet') : BYD.i18n.t('safe_loc.waiting_gps');
            el.style.color = 'var(--text-muted)';
        }
        const gpsEl = document.getElementById('safeLocGps');
        if (gpsEl && data.hasGps) {
            gpsEl.textContent = data.lat.toFixed(6) + ', ' + data.lng.toFixed(6) + ' (±' + Math.round(data.accuracy) + 'm)';
        }
    },

    updateUI() {
        const toggle = document.getElementById('safeLocEnabled');
        if (toggle) toggle.checked = this.featureEnabled;

        const badge = document.getElementById('safeLocBadge');
        if (badge) {
            badge.textContent = this.featureEnabled ? BYD.i18n.t('safe_loc.badge_on') : BYD.i18n.t('safe_loc.badge_off');
            badge.className = 'status-badge ' + (this.featureEnabled ? 'active' : 'inactive');
        }

        const content = document.getElementById('safeLocContent');
        const statusBox = document.getElementById('safeLocStatusBox');
        if (content) content.style.display = this.featureEnabled ? 'block' : 'none';
        if (statusBox) statusBox.style.display = this.featureEnabled ? 'block' : 'none';

        if (this.featureEnabled && !this.map) {
            setTimeout(() => this.initMap(), 150);
        }

        this.renderZoneList();
    },

    initMap() {
        if (this.map) return;
        const container = document.getElementById('safeLocMap');
        if (!container) return;
        
        // Guard: Leaflet may not be loaded yet (async script)
        if (typeof L === 'undefined') {
            setTimeout(() => this.initMap(), 500);
            return;
        }

        const center = this.currentGps ? [this.currentGps.lat, this.currentGps.lng] : [31.23, 121.47];
        const zoom = this.currentGps ? 16 : 3;

        this.map = L.map('safeLocMap', {
            zoomControl: true,
            attributionControl: false
        }).setView(center, zoom);

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19
        }).addTo(this.map);

        // Current GPS blue dot
        if (this.currentGps) {
            this.gpsMarker = L.circleMarker([this.currentGps.lat, this.currentGps.lng], {
                radius: 7, fillColor: '#3b82f6', fillOpacity: 1,
                color: '#fff', weight: 2
            }).addTo(this.map).bindPopup(BYD.i18n.t('safe_loc.you_are_here'));
        }

        // Add saved zones
        this.zones.forEach(z => this.addSavedZoneToMap(z));

        // Create the live edit circle at current position
        if (this.currentGps) {
            this.createEditCircle(this.currentGps.lat, this.currentGps.lng);
        }

        // Sync slider
        const slider = document.getElementById('safeLocRadiusSlider');
        if (slider) slider.value = this.editRadius;
        this.updateRadiusLabel();
    },

    createEditCircle(lat, lng) {
        if (this.editCircle) {
            this.map.removeLayer(this.editCircle);
        }

        this.editCircle = L.circle([lat, lng], {
            radius: this.editRadius,
            color: '#f59e0b',
            fillColor: '#f59e0b',
            fillOpacity: 0.18,
            weight: 2.5,
            dashArray: '8,6',
            interactive: true
        }).addTo(this.map);

        // Fit map to show the circle
        this.map.fitBounds(this.editCircle.getBounds().pad(0.3));
    },

    updateEditCircleRadius(radius) {
        this.editRadius = parseInt(radius);
        if (this.editCircle) {
            this.editCircle.setRadius(this.editRadius);
            this.map.fitBounds(this.editCircle.getBounds().pad(0.2));
        }
        this.updateRadiusLabel();
    },

    updateRadiusLabel() {
        const el = document.getElementById('safeLocRadiusValue');
        if (el) el.textContent = this.editRadius + 'm';
    },

    addSavedZoneToMap(zone) {
        if (!this.map) return;
        const color = zone.enabled ? '#10b981' : '#6b7280';

        const circle = L.circle([zone.lat, zone.lng], {
            radius: zone.radiusM,
            color: color, fillColor: color, fillOpacity: 0.12,
            weight: 2, dashArray: zone.enabled ? null : '5,5'
        }).addTo(this.map);

        const marker = L.marker([zone.lat, zone.lng], { title: zone.name })
            .addTo(this.map)
            .bindPopup(BYD.i18n.t('safe_loc.popup_radius', {name: zone.name, meters: zone.radiusM}));

        this.zoneCircles[zone.id] = circle;
        this.zoneMarkers[zone.id] = marker;
    },

    removeSavedZoneFromMap(id) {
        if (this.zoneCircles[id]) { this.map.removeLayer(this.zoneCircles[id]); delete this.zoneCircles[id]; }
        if (this.zoneMarkers[id]) { this.map.removeLayer(this.zoneMarkers[id]); delete this.zoneMarkers[id]; }
    },

    renderZoneList() {
        const container = document.getElementById('safeLocZoneList');
        if (!container) return;

        if (this.zones.length === 0) {
            container.innerHTML = '';
            return;
        }

        container.innerHTML = this.zones.map(z => `
            <div class="info-box" style="margin-bottom:8px;">
                <div style="display:flex; align-items:center; justify-content:space-between;">
                    <div style="display:flex; align-items:center; gap:8px;">
                        <span style="font-size:14px;">${z.enabled ? '🟢' : '⚪'}</span>
                        <span style="font-weight:600; font-size:13px;">${z.name}</span>
                        <span style="font-size:11px; color:var(--text-muted);">${z.radiusM}m</span>
                    </div>
                    <div style="display:flex; align-items:center; gap:4px;">
                        <label class="toggle-switch" style="transform:scale(0.75);">
                            <input type="checkbox" ${z.enabled ? 'checked' : ''} onchange="SafeLocations.toggleZone('${z.id}', this.checked)">
                            <span class="toggle-slider"></span>
                        </label>
                        <button onclick="SafeLocations.deleteZone('${z.id}')" style="background:none;border:none;cursor:pointer;font-size:14px;padding:2px;" title="${BYD.i18n.t('common.delete')}">🗑️</button>
                    </div>
                </div>
            </div>
        `).join('');
    },

    // ==================== ACTIONS ====================

    async toggleFeature() {
        const enabled = document.getElementById('safeLocEnabled').checked;
        try {
            await fetch('/api/surveillance/safe-locations/toggle', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ enabled })
            });
            this.featureEnabled = enabled;
            this.updateUI();
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(enabled ? BYD.i18n.t('safe_loc.enabled') : BYD.i18n.t('safe_loc.disabled'), 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('safe_loc.toggle_failed'), 'error');
        }
    },

    async addCurrentZone() {
        if (!this.currentGps) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('safe_loc.no_gps'), 'error');
            return;
        }

        const name = prompt(BYD.i18n.t('safe_loc.name_prompt'), BYD.i18n.t('safe_loc.default_name'));
        if (!name) return;

        try {
            const resp = await fetch('/api/surveillance/safe-locations', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    name: name,
                    lat: this.currentGps.lat,
                    lng: this.currentGps.lng,
                    radiusM: this.editRadius
                })
            });
            const data = await resp.json();
            if (data.success) {
                this.zones.push(data.zone);
                this.addSavedZoneToMap(data.zone);
                this.renderZoneList();
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('safe_loc.zone_added', {name: name, radius: this.editRadius}), 'success');
            } else {
                if (BYD.utils && BYD.utils.toast) BYD.utils.toast(data.error || BYD.i18n.t('common.error'), 'error');
            }
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('safe_loc.add_zone_failed'), 'error');
        }
    },

    async toggleZone(id, enabled) {
        try {
            await fetch('/api/surveillance/safe-locations', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id, enabled })
            });
            const zone = this.zones.find(z => z.id === id);
            if (zone) zone.enabled = enabled;

            // Update map circle color
            if (this.zoneCircles[id]) {
                const color = enabled ? '#10b981' : '#6b7280';
                this.zoneCircles[id].setStyle({ color, fillColor: color, dashArray: enabled ? null : '5,5' });
            }
            this.renderZoneList();
        } catch (e) {
            console.warn('Failed to toggle zone:', e);
        }
    },

    async deleteZone(id) {
        if (!confirm(BYD.i18n.t('safe_loc.confirm_delete_zone'))) return;
        try {
            await fetch('/api/surveillance/safe-locations', {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            });
            this.zones = this.zones.filter(z => z.id !== id);
            this.removeSavedZoneFromMap(id);
            this.renderZoneList();
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('safe_loc.zone_deleted'), 'success');
        } catch (e) {
            if (BYD.utils && BYD.utils.toast) BYD.utils.toast(BYD.i18n.t('errors.delete_failed'), 'error');
        }
    },

    async refreshStatus() {
        try {
            const resp = await fetch('/api/surveillance/safe-locations');
            const data = await resp.json();
            this.currentGps = data.hasGps ? { lat: data.lat, lng: data.lng, accuracy: data.accuracy } : null;

            // Update GPS marker
            if (this.map && this.currentGps) {
                if (this.gpsMarker) {
                    this.gpsMarker.setLatLng([this.currentGps.lat, this.currentGps.lng]);
                } else {
                    this.gpsMarker = L.circleMarker([this.currentGps.lat, this.currentGps.lng], {
                        radius: 7, fillColor: '#3b82f6', fillOpacity: 1, color: '#fff', weight: 2
                    }).addTo(this.map);
                }

                // Move edit circle to follow GPS if it exists
                if (this.editCircle) {
                    this.editCircle.setLatLng([this.currentGps.lat, this.currentGps.lng]);
                }
            }

            this.updateStatusText(data);
        } catch (e) { /* silent */ }
    }
};
