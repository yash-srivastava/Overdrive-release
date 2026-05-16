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
        response.put("location", gps.getLocationJson());
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
