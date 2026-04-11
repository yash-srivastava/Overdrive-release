package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Streaming API Handler - manages WebSocket streaming configuration and control.
 * 
 * Endpoints:
 * - POST /api/stream/enable - Enable WebSocket streaming
 * - POST /api/stream/disable - Disable WebSocket streaming
 * - GET /api/stream/status - Get streaming status
 * - GET /api/stream/quality - Get available quality presets
 * - POST /api/stream/quality/{preset} - Set streaming quality
 * - POST /api/stream/view/{mode} - Set view mode (0=Mosaic, 1-4=Single camera)
 * - GET /api/stream/view - Get current view mode
 */
public class StreamingApiHandler {
    
    private static String streamingQuality = "LOW";  // Default to LOW for better performance
    
    /**
     * Handle streaming API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/stream/enable") && method.equals("POST")) {
            handleEnableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/disable") && method.equals("POST")) {
            handleDisableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/status") && method.equals("GET")) {
            sendStreamStatus(out);
            return true;
        }
        if (path.equals("/api/stream/quality") && method.equals("GET")) {
            sendStreamQualityOptions(out);
            return true;
        }
        if (path.startsWith("/api/stream/quality/") && method.equals("POST")) {
            String quality = path.substring(20).toUpperCase();
            handleSetStreamQuality(out, quality);
            return true;
        }
        if (path.startsWith("/api/stream/view/")) {
            int viewMode = Integer.parseInt(path.substring(17));
            handleStreamViewMode(out, viewMode);
            return true;
        }
        if (path.equals("/api/stream/view") && method.equals("GET")) {
            sendStreamViewMode(out);
            return true;
        }
        return false;
    }
    
    private static void handleEnableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        CameraDaemon.log("handleEnableStreaming: pipeline=" + (pipeline != null) + 
                        ", running=" + (pipeline != null && pipeline.isRunning()));
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, "Pipeline not initialized");
            return;
        }
        
        // Auto-start pipeline if not running
        if (!pipeline.isRunning()) {
            try {
                CameraDaemon.log("handleEnableStreaming: auto-starting pipeline for streaming");
                pipeline.start();
                Thread.sleep(500);
            } catch (Exception e) {
                CameraDaemon.log("handleEnableStreaming: failed to start pipeline - " + e.getMessage());
                HttpResponse.sendJsonError(out, "Failed to start pipeline: " + e.getMessage());
                return;
            }
        }
        
        if (pipeline.isStreamingEnabled()) {
            CameraDaemon.log("handleEnableStreaming: already enabled");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Streaming already enabled");
            response.put("wsPort", 8887);
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        
        try {
            GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
            CameraDaemon.log("handleEnableStreaming: quality=" + q.displayName);
            pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            
            CameraDaemon.log("handleEnableStreaming: success");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Streaming enabled");
            response.put("wsPort", 8887);
            response.put("quality", q.name());
            response.put("resolution", q.width + "x" + q.height);
            response.put("fps", q.fps);
            response.put("bitrate", q.bitrate);
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("handleEnableStreaming: error - " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    private static void handleDisableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, "Pipeline not available");
            return;
        }
        
        pipeline.disableStreaming();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", "Streaming disabled");
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamStatus(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("pipelineRunning", pipeline != null && pipeline.isRunning());
        response.put("streamingEnabled", pipeline != null && pipeline.isStreamingEnabled());
        response.put("wsPort", 8887);
        
        if (pipeline != null && pipeline.isStreamingEnabled()) {
            response.put("viewMode", pipeline.getStreamViewMode());
            String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left"};
            int vm = pipeline.getStreamViewMode();
            response.put("viewName", vm >= 0 && vm <= 4 ? modeNames[vm] : "Unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamQualityOptions(OutputStream out) throws Exception {
        CameraDaemon.log("sendStreamQualityOptions: current=" + streamingQuality);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("current", streamingQuality);
        
        JSONArray options = new JSONArray();
        for (GpuPipelineConfig.StreamingQuality q : GpuPipelineConfig.StreamingQuality.values()) {
            JSONObject opt = new JSONObject();
            opt.put("id", q.name());
            opt.put("name", q.displayName);
            opt.put("width", q.width);
            opt.put("height", q.height);
            opt.put("fps", q.fps);
            opt.put("bitrate", q.bitrate);
            opt.put("bitrateKbps", q.bitrate / 1000);
            options.put(opt);
        }
        response.put("options", options);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleSetStreamQuality(OutputStream out, String quality) throws Exception {
        GpuPipelineConfig.StreamingQuality newQuality = GpuPipelineConfig.StreamingQuality.fromString(quality);
        
        streamingQuality = newQuality.name();
        CameraDaemon.setStreamingQuality(quality);
        
        // Save quality preference — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        // The /ws handler applies the quality when the client reconnects.
        CameraDaemon.log("Streaming quality set to: " + newQuality.displayName);
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("quality", newQuality.name());
        response.put("displayName", newQuality.displayName);
        response.put("width", newQuality.width);
        response.put("height", newQuality.height);
        response.put("fps", newQuality.fps);
        response.put("bitrate", newQuality.bitrate);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleStreamViewMode(OutputStream out, int viewMode) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, "Pipeline not available");
            return;
        }
        
        if (viewMode < 0 || viewMode > 5) {
            HttpResponse.sendJsonError(out, "Invalid view mode. Use 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, 5=Raw strip");
            return;
        }
        
        // Auto-start pipeline if not running
        if (!pipeline.isRunning()) {
            try {
                CameraDaemon.log("handleStreamViewMode: auto-starting pipeline");
                pipeline.start();
                Thread.sleep(500);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "Failed to start pipeline: " + e.getMessage());
                return;
            }
        }
        
        // Enable streaming first if not enabled
        if (!pipeline.isStreamingEnabled()) {
            try {
                CameraDaemon.log("Enabling streaming before setting view mode");
                GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
                Thread.sleep(500);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "Failed to enable streaming: " + e.getMessage());
                return;
            }
        }
        
        pipeline.setStreamViewMode(viewMode);
        
        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw"};
        CameraDaemon.log("Stream view mode set to: " + (viewMode < modeNames.length ? modeNames[viewMode] : "Unknown"));
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamViewMode(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        int viewMode = (pipeline != null) ? pipeline.getStreamViewMode() : -1;
        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw"};
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode >= 0 && viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }
    
    // Static getters/setters for cross-component access
    public static String getStreamingQuality() { return streamingQuality; }
    
    public static void setStreamingQuality(String quality) {
        if (quality.equals("ULTRA_LOW") || quality.equals("LOW") || 
            quality.equals("MEDIUM") || quality.equals("HIGH") || 
            quality.equals("ULTRA_HIGH") || quality.equals("LQ") || quality.equals("HQ")) {
            streamingQuality = quality;
        }
    }
}
