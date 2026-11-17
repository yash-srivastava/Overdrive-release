package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.monitor.GpsMonitor;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * GPS API Handler - manages GPS tracking and location data.
 * 
 * Endpoints:
 * - GET /api/gps - Get current GPS location
 * - POST /api/gps/start - Start GPS tracking
 * - POST /api/gps/stop - Stop GPS tracking
 */
public class GpsApiHandler {
    
    /**
     * Handle GPS API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/gps") && method.equals("GET")) {
            sendGpsLocation(out);
            return true;
        }
        if (path.equals("/api/gps/start") && method.equals("POST")) {
            handleGpsStart(out);
            return true;
        }
        if (path.equals("/api/gps/stop") && method.equals("POST")) {
            handleGpsStop(out);
            return true;
        }
        return false;
    }
    
    private static void sendGpsLocation(OutputStream out) throws Exception {
        GpsMonitor gps = GpsMonitor.getInstance();

        // Auto-start GPS if not running
        if (!gps.isRunning()) {
            CameraDaemon.log("GPS: Auto-starting GPS tracking");
            gps.start();
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        JSONObject location = gps.getLocationJson();
        // Fuse in the real BYD CAN motion signals. GPS speed (location.speed) is noisy
        // — multipath, dilution of precision, ~1-2s cadence — so the map's puck
        // dead-reckoning over-travels then snaps back when the next fix lands. The CAN
        // bus exposes the true wheel/inverter speed (smooth, high-rate) plus pedal
        // positions; the nav puck smoother prefers canSpeedKmh when present and can use
        // brakePercent to anticipate decel. Collected daemon-side (BydDataCollector runs
        // here as UID 2000); best-effort so a missing/absent collector never breaks the
        // GPS payload. UNAVAILABLE sentinels / NaN are simply omitted (the app treats an
        // absent key as "no CAN signal" and falls back to GPS speed).
        try {
            com.overdrive.app.byd.BydVehicleData vd =
                    com.overdrive.app.byd.BydDataCollector.getInstance().getData();
            if (vd != null) {
                if (!Double.isNaN(vd.speedKmh)) location.put("canSpeedKmh", vd.speedKmh);
                if (vd.accelPercent != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE)
                    location.put("accelPercent", vd.accelPercent);
                if (vd.brakePercent != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE)
                    location.put("brakePercent", vd.brakePercent);
            }
        } catch (Throwable ignored) {
            // No CAN collector / not initialized — GPS-only payload, as before.
        }
        // ACC as published (absent: not known yet) and the gear while fresh (absent: unknown): the map takes the car
        // put in P or switched off by its destination as arrived.
        try {
            if (com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()) {
                location.put("accOn", com.overdrive.app.monitor.AccMonitor.isAccOn());
            }
        } catch (Throwable ignored) {
        }
        try {
            int gear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGearIfFresh();
            if (com.overdrive.app.monitor.GearMonitor.isValidGearMode(gear)) {
                location.put("gear", com.overdrive.app.monitor.GearMonitor.gearToString(gear));
            }
        } catch (Throwable ignored) {
        }
        response.put("location", location);
        response.put("googleMapsUrl", gps.getGoogleMapsUrl());
        
        CameraDaemon.log("GPS: Sending location - lat=" + gps.getLatitude() + 
                        ", lng=" + gps.getLongitude() + ", hasLocation=" + gps.hasLocation());
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleGpsStart(OutputStream out) throws Exception {
        GpsMonitor gps = GpsMonitor.getInstance();
        gps.start();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.gps_tracking_started"));
        response.put("location", gps.getLocationJson());
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleGpsStop(OutputStream out) throws Exception {
        GpsMonitor gps = GpsMonitor.getInstance();
        gps.stop();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.gps_tracking_stopped"));
        
        HttpResponse.sendJson(out, response.toString());
    }
}
