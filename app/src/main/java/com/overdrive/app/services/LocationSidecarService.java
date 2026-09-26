package com.overdrive.app.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * Location Sidecar Service - Sends GPS coordinates to daemon via IPC.
 * 
 * This foreground service has proper permissions to access LocationManager.
 * It sends GPS data via TCP to port 19877 (SurveillanceIpcServer).
 * Sends periodically (every 2s) so daemon gets data even after restart.
 * 
 * Start via: am start-foreground-service -n com.overdrive.app/.services.LocationSidecarService
 */
public class LocationSidecarService extends Service implements LocationListener {

    private static final String TAG = "LocationSidecar";
    private static final String CHANNEL_ID = "location_sidecar";
    private static final int NOTIFICATION_ID = 9999;
    
    private LocationManager locationManager;
    private android.os.Handler handler;
    // Dedicated background looper for the GPS callback + periodic sender + file
    // write. Previously the handler was built on the MAIN looper and the 4-arg
    // requestLocationUpdates delivered onLocationChanged on main, so the per-fix
    // Log.d + synchronous saveToLocalCache() (JSON build + FileWriter + renameTo)
    // ran on the UI thread at ~1-2Hz — measurable jank contribution on the
    // foreground head unit. Moving the looper off-main keeps the UI thread free.
    private android.os.HandlerThread workerThread;
    private Runnable periodicSender;
    // Cross-thread now (GPS-IPC sender thread reads these) → volatile.
    private volatile double latitude = 0.0;
    private volatile double longitude = 0.0;
    private volatile float speed = 0.0f;
    private volatile float heading = 0.0f;
    private volatile float accuracy = 0.0f;
    private volatile double altitude = 0.0;
    // Reported 1-sigma vertical accuracy (m); 0 = unreported by this fix. Flows
    // to the daemon so the trip recorder can gate elevation on it.
    private volatile float verticalAccuracy = 0.0f;
    // True when `altitude` is MSL (geoid-corrected) rather than ellipsoidal.
    // Per-fix flag: an intermittent HAL can flip sources mid-trip, and the
    // ~tens-of-metres geoid step must reset elevation deltas, not bank as climb.
    private volatile boolean altitudeIsMsl = false;
    // MONOTONIC since-boot timestamp (SystemClock.elapsedRealtime ms) of the GPS
    // fix currently published — distinct from the "time" field we send (send/
    // receive-time, on purpose, for the RoadSense 5s cutoff + puck dead-reckon
    // clock). Geo-tagging ages against THIS (daemon compares vs its own
    // elapsedRealtime) so a parked car's stale fix re-sent every 4s by the
    // keep-alive doesn't read as fresh and tag the clip with a last-known
    // location — AND it can't cross the device-RTC clock domain (which is wrong
    // at cold boot until GPS/NTP corrects it).
    private volatile long fixElapsedMs = 0L;
    private boolean permissionGranted = false;

    // SOTA: Throttling fields to prevent IPC/Disk spam.
    // Holds the last location that was actually SENT to the daemon or SAVED to disk.
    private Location lastProcessedLocation = null;
    private long lastProcessedTime = 0;

    // Wall-clock (ms) of the last accepted GPS_PROVIDER fix. Used to REJECT a coarse
    // NETWORK_PROVIDER fix while a recent real GPS fix is still valid — see
    // onLocationChanged. 0 until the first GPS fix arrives.
    private long lastGpsFixTime = 0;
    // Accuracy (m) of the last accepted GPS fix — the anchor the NETWORK "not wildly worse"
    // guard compares against. MUST be the last GPS accuracy (not the last PROCESSED fix of
    // any provider): comparing vs lastProcessedLocation let accuracy ratchet ~200 m worse
    // per accepted NETWORK step during a sustained outage (each net fix compared only to the
    // previous net fix), defeating the documented "a 1 km cell fix must never clobber a 5 m
    // GPS one" intent. Float.MAX_VALUE until the first GPS fix (so the guard is inert before
    // we have a GPS reference to protect).
    private float lastGpsAccuracyM = Float.MAX_VALUE;
    // A NETWORK fix is only allowed to take over after GPS has been silent this long
    // (tunnel / cold start / GPS outage). MUST be BELOW the downstream consumer staleness
    // cutoff (LocationSource.DEFAULT_MAX_FIX_AGE_MS = 5000 ms), not above it: at 8000 ms a
    // GPS stutter left the consumer with NO fix from 5 s onward (it declares the pose stale
    // and RoadSense publishes idle → the "next hazard ahead" warning VANISHES) while the
    // NETWORK fallback stayed gated off until 8 s — so the warning dropped out and only
    // re-acquired late on GPS recovery (the "hazards show late" report). 4000 ms lets a
    // coarse fallback fix arrive ~1 s BEFORE the consumer would call the pose stale, so the
    // warning bridges the gap instead of blinking out. (NETWORK_MAX_WORSE_ACCURACY_M still
    // blocks a wildly-worse cell fix from clobbering a good last GPS position.)
    private static final long GPS_STALE_BEFORE_NETWORK_MS = 4000;
    // A NETWORK fix this much WORSE (accuracy, metres) than the last GPS fix is dropped
    // even past the staleness window — a 1 km cell fix must never clobber a 5 m GPS one.
    private static final float NETWORK_MAX_WORSE_ACCURACY_M = 200.0f;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");

        // Background looper for GPS callbacks + sender + file writes (off the UI
        // thread). Started before anything posts to `handler`.
        workerThread = new android.os.HandlerThread("location-sidecar");
        workerThread.start();
        handler = new android.os.Handler(workerThread.getLooper());

        // Create notification channel FIRST
        createNotificationChannel();
        
        // FIX #1: Load previous location immediately from disk cache.
        // Even if GPS is currently off/dead, we report where the car was last seen.
        // This prevents the "0,0 silence trap" when service restarts.
        loadFromLocalCache();
        
        // Check location permission BEFORE starting foreground with location type
        // Android 14+ (SDK 34+) requires runtime permission to be granted before
        // starting a foreground service with FOREGROUND_SERVICE_TYPE_LOCATION
        boolean hasPermission = hasLocationPermission();
        
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ - must have permission before using location type
            if (hasPermission) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                // Start with dataSync type (declared in manifest), will upgrade when permission granted
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        if (!hasPermission) {
            Log.e(TAG, "Location permission not granted - service will wait for permission");
            permissionGranted = false;
            
            // Start a retry loop to check for permissions (on the worker looper).
            if (handler == null) handler = new android.os.Handler(workerThread.getLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (hasLocationPermission()) {
                        Log.i(TAG, "Location permission now granted, starting updates");
                        permissionGranted = true;
                        
                        // Upgrade to location foreground service type now that we have permission
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            try {
                                stopForeground(STOP_FOREGROUND_DETACH);
                                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                            } catch (Exception e) {
                                Log.w(TAG, "Could not upgrade to location FGS type: " + e.getMessage());
                            }
                        }
                        
                        startLocationUpdates();
                        startPeriodicSender();
                    } else {
                        Log.d(TAG, "Still waiting for location permission...");
                        handler.postDelayed(this, 5000); // Check every 5 seconds
                    }
                }
            }, 5000);
            return;
        }
        
        permissionGranted = true;
        
        // Start location updates
        startLocationUpdates();
        
        // Start periodic sender
        startPeriodicSender();
    }
    
    private void startPeriodicSender() {
        if (handler == null) {
            handler = new android.os.Handler(workerThread.getLooper());
        }
        
        // FIX #3: Always send GPS data, even if 0,0.
        // Thanks to loadFromLocalCache(), we should have valid cached coordinates.
        // If it's truly 0,0 (brand new install), the daemon handles "invalid location" logic.
        // The sender should never be the gatekeeper - that's the daemon's job.
        periodicSender = new Runnable() {
            @Override
            public void run() {
                sendGpsViaTcp();

                // Poll the provider's last-known fix and process it. Our own 1s
                // GPS request (requestLocationUpdates GPS_PROVIDER, 1000ms) keeps
                // the provider PRODUCING fixes at ~1Hz, so its last-known cache is
                // fresh at ~1Hz even if the onLocationChanged callback isn't
                // delivering on this background looper — this poll then picks up a
                // fresh distinct fix each tick.
                // STALENESS-GATED: only poll when the provider CALLBACK has gone
                // quiet. The comment above describes the callback-not-delivering
                // failure mode this poll exists to cover — but the listener is now
                // registered with an explicit looper (see requestLocationUpdates
                // below), so in the healthy case the callback DOES deliver and this
                // poll was a redundant binder round-trip into system_server every
                // second for the entire drive, whose fix then re-passed processFix's
                // >1.5m/>500ms gate and triggered a SECOND TCP write for the same
                // position. Skipping while callbacks are fresh removes that per-second
                // cost; the backstop is preserved bit-for-bit because the moment
                // callbacks stop for >CALLBACK_STALE_MS we resume polling exactly as
                // before. Threshold is 3s: comfortably under RoadSense's 5s
                // fix-staleness cutoff, so a fallback still lands in time.
                boolean callbacksStale =
                        (System.currentTimeMillis() - lastCallbackFixAtMs) > CALLBACK_STALE_MS;
                if (callbacksStale && permissionGranted && locationManager != null) {
                    try {
                        Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (lastGps != null) {
                            long fixAge = System.currentTimeMillis() - lastGps.getTime();
                            if (fixAge < 10000) {
                                // Fresh fix available that we might have missed
                                processFix(lastGps);
                            }
                        }
                    } catch (SecurityException e) {
                        // Permission lost
                    } catch (Exception e) {
                        // Ignore
                    }
                }

                // Periodic keep-alive / poll / daemon-restart recovery. Dropped
                // from 4000ms -> 1000ms: at 4s this poll was the ONLY thing
                // advancing GpsMonitor (the provider callback wasn't delivering),
                // capping GPS at ~0.25Hz across both the MQTT feed AND the internal
                // trip track. 1s makes GpsMonitor ~1Hz. Still well under RoadSense's
                // 5s fix-staleness cutoff; CPU/IPC cost of a localhost write + one
                // file cache per second is negligible (was 2s originally).
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(periodicSender, 5000);
    }
    
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("GPS tracking for surveillance");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Tag with the shared Overdrive group key so DaemonKeepaliveService's
        // group-summary collapses this entry under a single shade row.
        // Android still requires this FGS notification to exist and remain
        // user-visible; grouping just changes how the shade renders it.
        return builder
            .setContentTitle("Location Active")
            .setContentText("GPS tracking running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setGroup(com.overdrive.app.services.DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build();
    }

    private void startLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            
            if (locationManager == null) {
                Log.e(TAG, "LocationManager not available");
                return;
            }
            
            // Keep GPS at 1s / 0m so RoadSense back-projection still sees the
            // ~2 Hz distinct-fix stream its GpsRingBuffer is designed around
            // (GPS_POLL_MS≈500, FIX_LATENCY≈700ms). The throttling that cuts
            // IPC/disk spam happens downstream in onLocationChanged (the
            // distance/time gate), NOT at the provider — coarsening the
            // provider here would starve hazard approach detection.
            //
            // Register UNCONDITIONALLY — never gate on isProviderEnabled().
            // The head unit disables location (location_mode=0) whenever the
            // car is off, and app (re)starts almost always happen parked, so
            // an isProviderEnabled gate here meant the listener was NEVER
            // registered for that app instance and the callback path never
            // delivered — fixes then only arrived via the periodic
            // getLastKnownLocation poll, riding on the factory nav's own GPS
            // request while driving. Android accepts registration while a
            // provider is disabled and starts delivering the moment it comes
            // on (ACC-on) — exactly the behavior we want.
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,  // 1 second
                    0.0f,  // every fix (no provider-side distance filter)
                    this,
                    workerThread.getLooper()  // deliver off the UI thread
                );
                Log.i(TAG, "GPS provider registered (1s/0m), enabled="
                        + locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
            } catch (Exception e) {
                Log.e(TAG, "GPS provider registration failed: " + e.getMessage());
            }

            // Also use network provider as fallback. 5s cadence is fine; keep
            // min-distance 0 so it doesn't pre-filter fixes the gate wants.
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000,  // 5 seconds
                    0.0f,  // every fix
                    this,
                    workerThread.getLooper()  // deliver off the UI thread
                );
                Log.i(TAG, "Network provider registered (5s/0m), enabled="
                        + locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            } catch (Exception e) {
                Log.e(TAG, "Network provider registration failed: " + e.getMessage());
            }
            
            // Get last known location immediately
            Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            
            Log.i(TAG, "Last GPS: " + lastGps + ", Last Network: " + lastNetwork);
            
            if (lastGps != null) {
                onLocationChanged(lastGps);
            } else if (lastNetwork != null) {
                onLocationChanged(lastNetwork);
            } else {
                // Send initial update (will fail if daemon not running yet, that's OK)
                sendGpsViaTcp();
                Log.i(TAG, "No last known location, sent initial update");
            }
            
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start location updates: " + e.getMessage());
        }
    }

    // Wall-clock of the last GPS_PROVIDER fix delivered by the PROVIDER CALLBACK
    // (as opposed to the periodic getLastKnownLocation poll). Lets the poll skip
    // itself while GPS callbacks are healthy — see periodicSender.
    //
    // MUST be stamped only for GPS_PROVIDER. Both GPS (1s) and NETWORK (5s) are
    // registered on this same listener, and processFix DISCARDS network fixes when
    // GPS is fresh or when the accuracy delta guard trips. Stamping on any
    // provider meant a 5s network tick — including one that was then thrown away —
    // marked callbacks "healthy" and suppressed the GPS backstop poll for 3 of
    // every 5 seconds during a GPS dropout, i.e. exactly when the backstop is
    // needed. That degraded the distinct-fix rate feeding RoadSense's
    // GpsRingBuffer back-projection.
    private volatile long lastCallbackFixAtMs = 0;

    @Override
    public void onLocationChanged(Location location) {
        if (location != null
                && LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
            lastCallbackFixAtMs = System.currentTimeMillis();
        }
        processFix(location);
    }

    /** Process a fix from either the provider callback or the periodic poll. */
    private void processFix(Location location) {
        if (location == null) return;

        long now = System.currentTimeMillis();

        // ── Provider-quality gate (fixes the "GPS jumps + RoadSense distance wrong"
        //    bug). We listen to BOTH GPS_PROVIDER (1s, ~5m) and NETWORK_PROVIDER (5s,
        //    often 100-1500m, and usually with NO bearing/speed). Previously EVERY fix
        //    from EITHER provider unconditionally overwrote the published lat/lng/speed/
        //    heading — so a coarse cell/wifi fix periodically clobbered the good GPS
        //    position (puck teleport / lag) AND zeroed heading+speed (defeating the
        //    dead-reckon + gyro fusion), and poisoned RoadSense's Haversine distance.
        //    Keep GPS as the source of truth: accept a NETWORK fix ONLY when GPS has
        //    gone genuinely silent (tunnel / cold start) AND the network fix isn't
        //    wildly worse than the last GPS accuracy. GPS fixes always pass. ──
        boolean isGps = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        if (isGps) {
            lastGpsFixTime = now;
            // Snapshot the GPS accuracy as the anchor for the NETWORK "not wildly worse"
            // guard below (so it compares vs the last real GPS fix, never a prior net fix).
            lastGpsAccuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
        } else {
            // Non-GPS (network/passive/fused) fix. Reject while a recent GPS fix is live.
            long sinceGps = (lastGpsFixTime == 0) ? Long.MAX_VALUE : (now - lastGpsFixTime);
            if (sinceGps < GPS_STALE_BEFORE_NETWORK_MS) {
                return;   // GPS still fresh — ignore the coarse fix entirely.
            }
            // GPS is stale: allow the network fix as a fallback, but still drop a fix that is
            // far less accurate than the GPS we last had (avoid a huge jump). Compare against
            // the last GPS accuracy — NOT lastProcessedLocation — so a sustained outage can't
            // ratchet accuracy ~200 m worse per accepted net step (each net fix would else be
            // judged only against the previous net fix).
            float netAcc = location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE;
            if (lastGpsAccuracyM != Float.MAX_VALUE
                    && netAcc - lastGpsAccuracyM > NETWORK_MAX_WORSE_ACCURACY_M) {
                return;
            }
        }

        float distanceMoved = (lastProcessedLocation != null) ? location.distanceTo(lastProcessedLocation) : Float.MAX_VALUE;
        long timeSinceLastProcess = now - lastProcessedTime;

        // Update volatiles immediately so the periodic sender (and any other
        // internal consumer) has access to the absolutely latest fix.
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        speed = location.hasSpeed() ? location.getSpeed() : 0.0f;
        // HOLD the last bearing when this fix carries none (low speed, just-acquired, or
        // a network fix) rather than slamming heading to 0.0 — a spurious 0° (due North)
        // mid-drive snapped the puck/heading-up camera and skewed any heading-based math.
        // Consumers already treat heading as noise when stationary, so holding is safe.
        if (location.hasBearing()) heading = location.getBearing();
        accuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
        // Altitude: PREFER mean-sea-level (MSL) height when the platform can
        // supply it. Location.getAltitude() returns height above the WGS84
        // ELLIPSOID, which differs from MSL by the local geoid undulation
        // (roughly −100 m … +85 m worldwide; strongly POSITIVE across Europe and
        // much of Asia, so ellipsoidal altitude reads tens of metres HIGH there
        // vs. a map/altimeter — the "altitude too high" report). Android 14
        // (API 34) added getMslAltitudeMeters(), populated only when the GNSS HAL
        // provides the geoid correction — hence the hasMslAltitude() guard, with
        // the ellipsoidal value as the fallback on older platforms / HALs that
        // don't report it. The correction is a near-constant local offset, so it
        // does NOT change trip elevation gain/loss (computed from altitude deltas,
        // where a constant offset cancels) — only the absolute altitude we store,
        // send to ABRP/MQTT, and expose to RoadSense.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && location.hasMslAltitude()) {
            altitude = location.getMslAltitudeMeters();
            altitudeIsMsl = true;
        } else {
            altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
            altitudeIsMsl = false;
        }
        // Vertical accuracy: MSL fixes carry their own figure; otherwise use the
        // standard per-fix vertical accuracy. 0 = unreported (consumers treat it
        // as "no gate available", never as a perfect fix).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && location.hasMslAltitude() && location.hasMslAltitudeAccuracy()) {
            verticalAccuracy = location.getMslAltitudeAccuracyMeters();
        } else {
            verticalAccuracy = location.hasVerticalAccuracy()
                    ? location.getVerticalAccuracyMeters() : 0.0f;
        }
        // Fix age basis = the MONOTONIC since-boot clock, NOT UTC getTime(). The
        // earlier attempt used location.getTime() (GNSS-UTC) and aged it against
        // the daemon's System.currentTimeMillis() (device RTC) — two clock domains.
        // BYD units boot with a wrong RTC until GPS/NTP corrects it (see AppUpdater /
        // UnifiedConfigManager), so during the cold-boot window that cross-clock
        // delta could exceed the 5-min gate and DROP a perfectly fresh fix's tag.
        // elapsedRealtime() is since-boot, identical across processes on the device,
        // and immune to RTC correction — so app-side fix time and daemon-side now
        // are on the SAME monotonic clock and their delta is the true fix age.
        //
        // BUG FIX (parked-clip "tags as Home"): the previous fallback stamped a
        // stamp-LESS fix with elapsedRealtime()=NOW, which made it read as
        // eternally fresh. A last-known / cached seed Location (the fix the OS
        // hands back once the data iface dies ~17s after ACC-OFF — the parked
        // sentry case) has getElapsedRealtimeNanos()==0, so it got NOW-stamped
        // and the daemon's geo gate then tagged the sentry clip with the last
        // real fix before the blackout — the driveway, inside the Home
        // SafeLocation zone. ACC-ON was unaffected because live GPS fixes always
        // carry a real elapsedRealtimeNanos. The fix: when the fix carries no
        // monotonic stamp, DERIVE one from its true UTC age (getTime()) by
        // back-dating our own elapsedRealtime() — so a 10-min-old seed reads as
        // 10-min-old and the 5-min gate rejects it — instead of fabricating NOW.
        // Both operands of the back-date (now/currentTimeMillis and getTime) are
        // the device RTC, so their DELTA (the fix age) is skew-immune even if the
        // absolute RTC is wrong; only the delta is used. If getTime() is also
        // unusable (0 / future-dated), leave fixElapsedMs=0 → the daemon gate's
        // send-time fallback governs (never worse than before this feature).
        long ern = location.getElapsedRealtimeNanos();
        if (ern > 0) {
            fixElapsedMs = ern / 1_000_000L;
        } else {
            long fixUtc = location.getTime();
            long ageByUtc = (fixUtc > 0) ? (now - fixUtc) : -1L;
            fixElapsedMs = (ageByUtc >= 0)
                    ? android.os.SystemClock.elapsedRealtime() - ageByUtc
                    : 0L;
        }

        // Throttle Log, IPC and Disk I/O — but keep the distinct-fix rate as
        // high as the provider delivers (GPS is requested at 1s/0m) so
        // RoadSense back-projection (GpsRingBuffer ~2 Hz design, 5s max fix
        // age) and the nav puck stay fresh. We "Process" if:
        // 1. Car moved > 1.5 meters (drops sub-metre stationary GPS jitter), OR
        // 2. > 500 ms passed since the last processed fix. NOTE: the time gate
        //    is intentionally HALF the 1000 ms provider period — a >=1000 ms
        //    gate would race the provider cadence and drop every other fix
        //    (~2 s spacing); 500 ms lets every ~1 s provider fix through while
        //    still keeping GpsMonitor.lastUpdate far under the 5s staleness
        //    cutoff when stopped at a light, OR
        // 3. it's the first fix ever.
        if (lastProcessedLocation == null || distanceMoved > 1.5f || timeSinceLastProcess > 500) {
            boolean isFirstFix = (lastProcessedLocation == null);
            lastProcessedLocation = new Location(location);
            lastProcessedTime = now;

            if (isFirstFix) {
                Log.i(TAG, "First location fix: " + latitude + ", " + longitude);
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                // Per-fix logging is runtime opt-in: `setprop log.tag.LocationSidecar DEBUG`
                // (same pattern as KeepAliveAccessibilityService.onKeyEvent). The old
                // BuildConfig.DEBUG gate was compile-time: always on in debug builds
                // (~1 Hz spam) and unreachable in release-type builds — including
                // braveheart, where logcat survives R8 and this line is actually useful.
                Log.d(TAG, "Location update (moved=" + String.format("%.1f", distanceMoved) + "m): " + latitude + ", " + longitude);
            }
            
            // Send to daemon via IPC — NOT throttled. This is the live path the
            // daemon ages fixes against (geo-tagging staleness, RoadSense
            // back-projection, nav puck), so it must keep the full provider rate.
            sendGpsViaTcp();

            // Persist to the on-disk cache — THROTTLED (see CACHE_WRITE_MIN_INTERVAL_MS).
            saveToLocalCacheThrottled(now);
        }
    }

    /**
     * Minimum spacing between gps_cache.json writes.
     *
     * <p>WHY (perf): saveToLocalCache() serializes a JSONObject, writes a temp
     * file, renames it over the real one, and chmods it. It used to run on EVERY
     * processed fix — and the gate above admits every provider fix (the time
     * clause is 500 ms, deliberately half the 1 s provider period), so this was
     * a JSON-serialize + create + write + rename + chmod at ~1 Hz for the entire
     * drive: on the order of 86k write+rename cycles per day of driving, all on
     * the same flash the recorder is writing to.
     *
     * <p>Why 5 s is safe: this file exists ONLY as a cold-start seed so the
     * daemon has a last-known position before the first live fix arrives. It is
     * not on any live path — the daemon reads live fixes over IPC (above), and
     * per saveToLocalCache()'s own contract a cache-loaded fix is rejected by
     * {@code isLoadedFromCache} in the geo gate, so it is never used for an age
     * decision. The worst observable effect of a 5 s bound is that a cache
     * restored after an unclean restart can be up to 5 s (≈ a few tens of
     * metres) staler than before — well inside the 5 s max-fix-age the geo gate
     * already enforces.
     */
    private static final long CACHE_WRITE_MIN_INTERVAL_MS = 5_000;

    /** How long the provider callback must be silent before the periodic
     *  getLastKnownLocation fallback re-engages. Under RoadSense's 5s
     *  fix-staleness cutoff so a fallback fix still arrives in time. */
    private static final long CALLBACK_STALE_MS = 3_000;
    private long lastCacheWriteAtMs = 0;

    /**
     * Rate-limited wrapper around {@link #saveToLocalCache()}. Always writes the
     * first fix (so a cold start seeds the cache immediately) and then at most
     * once per {@link #CACHE_WRITE_MIN_INTERVAL_MS}. Called only from the
     * single-threaded location callback, so the timestamp needs no lock.
     */
    private void saveToLocalCacheThrottled(long now) {
        if (lastCacheWriteAtMs != 0
                && (now - lastCacheWriteAtMs) < CACHE_WRITE_MIN_INTERVAL_MS) {
            return;
        }
        lastCacheWriteAtMs = now;
        saveToLocalCache();
    }
    
    /**
     * Save GPS to app's local cache file.
     * This file persists across reboots and can be read by the daemon.
     * The daemon (UID 2000) can read from /data/data/com.overdrive.app/files/ but cannot write to it.
     */
    private void saveToLocalCache() {
        if (latitude == 0.0 && longitude == 0.0) return;
        
        try {
            JSONObject json = new JSONObject();
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("speed", speed);
            json.put("heading", heading);
            json.put("accuracy", accuracy);
            json.put("altitude", altitude);
            // Send/receive-time (see sendGpsViaTcp) — what the daemon ages against; never
            // the fix's own back-datable getTime().
            json.put("time", System.currentTimeMillis());
            // Monotonic since-boot fix timestamp for geo-tagging staleness (see
            // sendGpsViaTcp). NOTE: elapsedRealtime resets across reboots, so a
            // cache loaded after a reboot has an incomparable value — but a
            // cache-loaded fix is already rejected by isLoadedFromCache in the geo
            // gate, so the cross-boot value is never used for an age decision.
            json.put("fixElapsedMs", fixElapsedMs);

            // Write to app's files directory
            java.io.File file = new java.io.File(getFilesDir(), "gps_cache.json");
            java.io.File tmp = new java.io.File(getFilesDir(), "gps_cache.json.tmp");
            
            try (java.io.FileWriter writer = new java.io.FileWriter(tmp)) {
                writer.write(json.toString());
            }
            
            if (!tmp.renameTo(file)) {
                // Fallback: direct write
                try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                    writer.write(json.toString());
                }
                tmp.delete();
            }
            
            // Make readable by other UIDs (daemon UID 2000)
            file.setReadable(true, false);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to save local GPS cache: " + e.getMessage());
        }
    }
    
    /**
     * Load GPS from app's local cache file on service startup.
     * Always loads cached location (better than nothing), but marks it so
     * we know to prioritize fresh GPS fixes when they arrive.
     */
    private void loadFromLocalCache() {
        try {
            java.io.File file = new java.io.File(getFilesDir(), "gps_cache.json");
            if (!file.exists()) {
                Log.i(TAG, "No GPS cache file found");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            
            JSONObject json = new JSONObject(sb.toString());
            double lat = json.optDouble("lat", 0.0);
            double lng = json.optDouble("lng", 0.0);
            
            if (lat != 0.0 || lng != 0.0) {
                this.latitude = lat;
                this.longitude = lng;
                this.speed = (float) json.optDouble("speed", 0.0);
                this.heading = (float) json.optDouble("heading", 0.0);
                this.accuracy = (float) json.optDouble("accuracy", 0.0);
                this.altitude = json.optDouble("altitude", 0.0);
                
                long cacheTime = json.optLong("time", 0);
                long ageMs = cacheTime > 0 ? System.currentTimeMillis() - cacheTime : -1;
                Log.i(TAG, "Loaded cached location (" + (ageMs > 0 ? (ageMs / 1000) + "s old" : "unknown age") + "): " + lat + ", " + lng);
                
                // Send cached location to daemon immediately — better than nothing
                sendGpsViaTcp();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load GPS cache: " + e.getMessage());
        }
    }

    // Persistent IPC socket to the daemon (SurveillanceIpcServer 19877). Only
    // touched on the workerThread looper (every send is posted there), so no
    // locking. Previously each fix spawned a NEW Thread AND a NEW Socket
    // (connect+handshake+teardown) at ~1-2Hz; now one socket is reused and the
    // send runs on the existing worker looper — zero thread spawns, zero
    // connects in steady state. The daemon server loops per-connection.
    private java.net.Socket ipcSocket = null;
    private java.io.OutputStream ipcOut = null;

    private void sendGpsViaTcp() {
        // Snapshot the volatile fields on the caller, build + write on the
        // worker looper (network off the main/GPS-callback thread).
        final JSONObject json = new JSONObject();
        try {
            json.put("command", "UPDATE_GPS");
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("speed", speed);
            json.put("heading", heading);
            json.put("accuracy", accuracy);
            json.put("altitude", altitude);
            // Vertical accuracy + altitude source for the elevation pipeline
            // (TripTelemetryRecorder → ElevationEstimator). Older daemons ignore
            // unknown keys; a missing key on the daemon side defaults to 0/false.
            json.put("vAcc", verticalAccuracy);
            json.put("altMsl", altitudeIsMsl);
            // SEND-TIME (receive-time), NOT the GPS fix's own getTime(). This field flows
            // to GpsMonitor.lastUpdate, which LocationSource.latest() ages against a 5 s
            // cutoff (DEFAULT_MAX_FIX_AGE_MS); a fresh re-send must read as fresh. Stamping
            // the fix's own (back-datable) getTime() let a periodicSender re-send of a
            // ~10 s-old last-known fix age past 5 s → latest()=null → RoadSense publishIdle
            // → the "next hazard ahead" card vanished. This matches the v26.8/v27.3 baseline
            // (both used System.currentTimeMillis()) and keeps the puck dead-reckon clock
            // anchored to ingestion as designed (feedMotionTruth re-anchors on a NEW ts; the
            // estimator + feedMotionTruth already de-dupe identical re-polls by position/ts).
            json.put("time", System.currentTimeMillis());
            // MONOTONIC since-boot fix timestamp — SEPARATE from "time". Geo-tagging
            // ages this against the daemon's own elapsedRealtime() so a parked car's
            // stale fix (re-sent every 4s by the keep-alive, which keeps "time"
            // perpetually fresh) reads as stale, WITHOUT crossing the device-RTC
            // clock domain (wrong at cold boot until GPS/NTP corrects it).
            json.put("fixElapsedMs", fixElapsedMs);
        } catch (Exception e) {
            return;
        }
        final byte[] payload = (json.toString() + "\n").getBytes();
        android.os.Handler h = handler;
        if (h != null) {
            h.post(() -> writeGpsWithReconnect(payload, true));
        }
    }

    /** Write over the persistent socket, reconnecting once on failure. Worker thread only. */
    private void writeGpsWithReconnect(byte[] payload, boolean allowReconnect) {
        try {
            if (ipcSocket == null) {
                if (!allowReconnect || !openIpcSocket()) return;
            }
            ipcOut.write(payload);
            ipcOut.flush();
            // Fire-and-forget: we don't block reading the ack (the old code read
            // one line purely to log a warning; the daemon applies the update
            // regardless). Skipping the read removes a per-fix round-trip wait.
        } catch (java.net.ConnectException e) {
            closeIpcSocket();  // daemon not up — expected on startup, drop fix
        } catch (Exception e) {
            closeIpcSocket();
            if (allowReconnect) writeGpsWithReconnect(payload, false);
        }
    }

    private boolean openIpcSocket() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 19877), 1000);
            s.setSoTimeout(1000);
            s.setTcpNoDelay(true);
            ipcSocket = s;
            ipcOut = s.getOutputStream();
            return true;
        } catch (Exception e) {
            closeIpcSocket();
            return false;
        }
    }

    private void closeIpcSocket() {
        try { if (ipcSocket != null) ipcSocket.close(); } catch (Exception ignored) {}
        ipcSocket = null;
        ipcOut = null;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "Provider " + provider + " status: " + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "Provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.w(TAG, "Provider disabled: " + provider);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");
        // "Vehicle ON only" parked-shutdown gate: if the stack was terminated for the
        // parked window, this sidecar (1Hz GPS→IPC loop to the now-dead CameraDaemon)
        // must not keep running/respawning. Self-stop and stay down until ACC-on recovery
        // clears the marker. Guarded on onOnly (fail-open) so onAndOff is unaffected. The
        // recoveryInProgress guard avoids self-stopping on the recovery edge before the
        // async marker clear lands. Mirrors DaemonKeepaliveService.onStartCommand.
        try {
            if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()
                    && new java.io.File(com.overdrive.app.ui.model.ParkedShutdown.markerPath()).exists()
                    && !com.overdrive.app.ui.daemon.DaemonStartupManager.getRecoveryInProgress()) {
                Log.i(TAG, "onOnly + parked-shutdown marker present — stopping location sidecar");
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Throwable t) {
            Log.w(TAG, "parked-marker gate failed (" + t.getMessage() + ") — proceeding");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (handler != null && periodicSender != null) {
            handler.removeCallbacks(periodicSender);
        }
        // Close the persistent IPC socket on the worker thread that owns it,
        // before quitSafely() lets the looper drain and stop.
        if (handler != null) {
            handler.post(this::closeIpcSocket);
        }

        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }

        // FINAL FLUSH of the on-disk position cache. saveToLocalCache() is
        // rate-limited on the live path (CACHE_WRITE_MIN_INTERVAL_MS), so at any
        // moment up to that interval of movement may not be on disk yet. Without
        // an unconditional write here, a teardown landing inside that window
        // (ACC-off, parked shutdown, service restart, process kill) would leave
        // the cache holding a position up to CACHE_WRITE_MIN_INTERVAL_MS old —
        // which is exactly the value the next cold start seeds from. Bypasses the
        // throttle deliberately: this is a one-shot on a path that is already
        // tearing down, not a hot path.
        //
        // Posted to the worker looper (NOT run inline) because that thread owns
        // the file: quitSafely() below lets the looper drain queued work first,
        // and saveToLocalCache reads the volatile position fields the GPS
        // callback writes on that same thread. Ordering: after removeUpdates()
        // so no new fix can race it, before quitSafely() so it is still drained.
        if (handler != null) {
            handler.post(this::saveToLocalCache);
        }

        // Stop the background looper (callbacks already removed above).
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }

        Log.i(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
