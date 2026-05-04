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
    private Runnable periodicSender;
    private double latitude = 0.0;
    private double longitude = 0.0;
    private float speed = 0.0f;
    private float heading = 0.0f;
    private float accuracy = 0.0f;
    private double altitude = 0.0;
    private boolean permissionGranted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
        
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
            
            // Start a retry loop to check for permissions
            handler = new android.os.Handler(android.os.Looper.getMainLooper());
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
            handler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        
        // FIX #3: Always send GPS data, even if 0,0.
        // Thanks to loadFromLocalCache(), we should have valid cached coordinates.
        // If it's truly 0,0 (brand new install), the daemon handles "invalid location" logic.
        // The sender should never be the gatekeeper - that's the daemon's job.
        periodicSender = new Runnable() {
            @Override
            public void run() {
                sendGpsViaTcp();
                
                // If no fresh GPS fix in 30 seconds, request one explicitly
                // This handles cases where the provider stopped sending updates
                if (permissionGranted && locationManager != null) {
                    try {
                        Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (lastGps != null) {
                            long fixAge = System.currentTimeMillis() - lastGps.getTime();
                            if (fixAge < 10000) {
                                // Fresh fix available that we might have missed
                                onLocationChanged(lastGps);
                            }
                        }
                    } catch (SecurityException e) {
                        // Permission lost
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(periodicSender, 2000);
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
        
        return builder
            .setContentTitle("Location Active")
            .setContentText("GPS tracking running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build();
    }

    private void startLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            
            if (locationManager == null) {
                Log.e(TAG, "LocationManager not available");
                return;
            }
            
            // Request GPS updates
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,  // 1 second
                    0.0f,  // 0 meters - always get updates even when stationary
                    this
                );
                Log.i(TAG, "GPS provider started (minDistance=0)");
            }
            
            // Also use network provider as fallback
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000,  // 2 seconds
                    0.0f,  // 0 meters
                    this
                );
                Log.i(TAG, "Network provider started (minDistance=0)");
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

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        
        double prevLat = latitude;
        double prevLng = longitude;
        boolean hadFix = (prevLat != 0.0 || prevLng != 0.0);
        
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        speed = location.hasSpeed() ? location.getSpeed() : 0.0f;
        heading = location.hasBearing() ? location.getBearing() : 0.0f;
        accuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
        altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
        
        // Only log the first fix at INFO; suppress steady-state spam.
        // Individual fixes go to DEBUG so they're still available via
        // `adb logcat *:V` but don't fill the normal log.
        if (!hadFix) {
            Log.i(TAG, "First location fix: " + latitude + ", " + longitude);
        } else {
            Log.d(TAG, "Location update: " + latitude + ", " + longitude);
        }
        
        // Send to daemon via IPC
        sendGpsViaTcp();
        
        // Also save to app's local cache (persists across reboots, readable by daemon)
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
            json.put("time", System.currentTimeMillis());
            
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

    private void sendGpsViaTcp() {
        // Must run on background thread (Android doesn't allow network on main thread)
        new Thread(() -> {
            java.net.Socket socket = null;
            try {
                JSONObject json = new JSONObject();
                json.put("command", "UPDATE_GPS");
                json.put("lat", latitude);
                json.put("lng", longitude);
                json.put("speed", speed);
                json.put("heading", heading);
                json.put("accuracy", accuracy);
                json.put("altitude", altitude);
                json.put("time", System.currentTimeMillis());
                
                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", 19877), 1000);
                socket.setSoTimeout(1000);
                
                java.io.OutputStream out = socket.getOutputStream();
                byte[] data = (json.toString() + "\n").getBytes();
                out.write(data);
                out.flush();
                
                // Read response to confirm daemon received it
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream()));
                String response = reader.readLine();
                if (response == null) {
                    Log.w(TAG, "No response from daemon - GPS update may be lost");
                }
                
            } catch (java.net.ConnectException e) {
                // Daemon not running yet - expected on startup
            } catch (Exception e) {
                Log.e(TAG, "IPC error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }, "GPS-IPC").start();
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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (handler != null && periodicSender != null) {
            handler.removeCallbacks(periodicSender);
        }
        
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        
        Log.i(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
