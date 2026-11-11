package com.overdrive.app.byd;

import android.content.Context;
import android.os.Looper;

import com.overdrive.app.byd.bodywork.BodyworkManager;
import com.overdrive.app.byd.radar.RadarManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * BYD Event Daemon - runs as UID 1000 (system) via app_process.
 * 
 * This daemon:
 * 1. Registers listeners with BYD SDK (Radar, Bodywork)
 * 2. Pushes events to connected clients via TCP (port 19878)
 * 3. Must run as UID 1000 to bypass Binder.getCallingUid() checks
 */
public class BydEventDaemon {
    
    private static final String TAG = "BydEventDaemon";
    private static final DaemonLogger logger = DaemonLogger.getInstance("BydEventDaemon");
    private static final int TCP_PORT = 19878;
    
    private static volatile boolean running = true;
    private static ServerSocket serverSocket;
    private static final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();

    // Each client handler blocks while reading its TCP connection. Bound both
    // worker threads and queued sockets so local clients cannot grow threads
    // without limit or accumulate an unbounded backlog.
    private static final ThreadPoolExecutor clientExecutor = new ThreadPoolExecutor(
            4, 4, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable, "BydEventClient");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    
    // Managers
    private static RadarManager radarManager;
    private static BodyworkManager bodyworkManager;
    
    public static void main(String[] args) {
        int myUid = android.os.Process.myUid();
        
        logger.info("=== BYD Event Daemon Starting ===");
        logger.info("UID: " + myUid);
        logger.info("PID: " + android.os.Process.myPid());
        
        if (myUid != 1000) {
            logger.warn("Not running as UID 1000! BYD SDK calls may fail.");
        } else {
            logger.info("Running as SYSTEM (UID 1000) - BYD SDK access enabled");
        }
        
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        
        try {
            Context context = createAppContext();
            if (context == null) {
                logger.error("Could not get context");
                return;
            }
            logger.info("Got context: " + context.getPackageName());
            
            // Start TCP server
            new Thread(BydEventDaemon::runTcpServer, "TcpServer").start();
            
            // Initialize managers
            radarManager = new RadarManager(context, BydEventDaemon::broadcastEvent, BydEventDaemon::logMessage);
            bodyworkManager = new BodyworkManager(context, BydEventDaemon::broadcastEvent, BydEventDaemon::logMessage);
            
            // Register listeners
            radarManager.register();
            bodyworkManager.register();
            
            // Start battery polling thread (every 30 seconds)
            new Thread(BydEventDaemon::runBatteryPoller, "BatteryPoller").start();
            
            logger.info("Daemon ready, listening on TCP port " + TCP_PORT);
            
            Looper.loop();
            
        } catch (Exception e) {
            logger.error("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ==================== TCP SERVER ====================
    
    private static void runTcpServer() {
        logger.info("TCP server thread starting...");
        
        while (running) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception e) {}
                }
                
                serverSocket = new ServerSocket(TCP_PORT, 10, java.net.InetAddress.getByName("127.0.0.1"));
                serverSocket.setReuseAddress(true);
                logger.info("TCP server started on 127.0.0.1:" + TCP_PORT);
                
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        logger.info("Client connected from " + client.getRemoteSocketAddress());
                        ClientHandler handler = new ClientHandler(client);
                        clients.add(handler);
                        try {
                            clientExecutor.execute(handler);
                        } catch (RejectedExecutionException e) {
                            clients.remove(handler);
                            try { client.close(); } catch (Exception ignored) {}
                            logger.warn("TCP client limit reached; rejected connection");
                        }
                    } catch (java.net.SocketException e) {
                        if (running) logger.warn("TCP socket error: " + e.getMessage());
                        break;
                    }
                }
                
                if (running) {
                    logger.info("TCP server restarting...");
                    Thread.sleep(2000);
                }
                
            } catch (java.net.BindException e) {
                logger.error("Port " + TCP_PORT + " already in use, retrying in 5s...");
                try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            } catch (Exception e) {
                logger.error("TCP server error: " + e.getMessage());
                if (running) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) {}
                }
            }
        }
    }
    
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter writer;
        private volatile boolean connected = true;
        
        ClientHandler(Socket socket) { this.socket = socket; }
        
        @Override
        public void run() {
            try {
                socket.setSoTimeout(0);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new PrintWriter(socket.getOutputStream(), true);
                
                sendCurrentState();
                
                String line;
                while (connected && (line = reader.readLine()) != null) {
                    logger.debug("Received: " + line);
                    try {
                        JSONObject cmd = new JSONObject(line);
                        JSONObject response = processCommand(cmd);
                        writer.println(response.toString());
                    } catch (Exception e) {
                        JSONObject error = new JSONObject();
                        error.put("status", "error");
                        error.put("message", e.getMessage());
                        writer.println(error.toString());
                    }
                }
            } catch (Exception e) {
                logger.debug("Client disconnected: " + e.getMessage());
            } finally {
                connected = false;
                clients.remove(this);
                try { socket.close(); } catch (Exception e) {}
            }
        }
        
        void sendEvent(JSONObject event) {
            if (connected && writer != null) {
                try {
                    writer.println(event.toString());
                } catch (Exception e) {
                    connected = false;
                }
            }
        }
        
        private void sendCurrentState() {
            try {
                JSONObject state = new JSONObject();
                state.put("type", "state");
                
                if (bodyworkManager != null) {
                    state.put("powerLevel", bodyworkManager.getLastPowerLevel());
                    state.put("powerLevelName", bodyworkManager.getLastPowerLevelName());
                    state.put("batteryVoltageLevel", bodyworkManager.getLastBatteryVoltageLevel());
                    state.put("batteryVoltageLevelName", bodyworkManager.getLastBatteryVoltageLevelName());
                    state.put("batteryVoltage", bodyworkManager.getLastBatteryVoltage());
                }
                
                if (radarManager != null) {
                    state.put("radar", radarManager.getStateAsJson());
                }
                
                writer.println(state.toString());
            } catch (Exception e) {
                logger.error("Error sending state: " + e.getMessage());
            }
        }
    }
    
    private static JSONObject processCommand(JSONObject cmd) throws Exception {
        String command = cmd.optString("cmd", "");
        JSONObject response = new JSONObject();
        
        switch (command) {
            case "ping":
                response.put("status", "ok");
                response.put("message", "pong");
                break;
                
            case "status":
                response.put("status", "ok");
                response.put("clients", clients.size());
                if (bodyworkManager != null) {
                    response.put("powerLevel", bodyworkManager.getLastPowerLevel());
                    response.put("powerLevelName", bodyworkManager.getLastPowerLevelName());
                    response.put("bodyworkDevice", bodyworkManager.isRegistered());
                }
                if (radarManager != null) {
                    response.put("radarDevice", radarManager.isRegistered());
                }
                break;
                
            case "getRadar":
                response.put("status", "ok");
                if (radarManager != null) {
                    JSONObject radar = radarManager.getStateAsJson();
                    for (java.util.Iterator<String> it = radar.keys(); it.hasNext(); ) {
                        String key = it.next();
                        response.put(key, radar.get(key));
                    }
                }
                break;
                
            case "getBattery":
                response.put("status", "ok");
                if (bodyworkManager != null) {
                    // Refresh battery info first
                    bodyworkManager.refreshBatteryInfo();
                    response.put("voltageLevel", bodyworkManager.getLastBatteryVoltageLevel());
                    response.put("voltageLevelName", bodyworkManager.getLastBatteryVoltageLevelName());
                    response.put("voltage", bodyworkManager.getLastBatteryVoltage());
                    response.put("powerValue", bodyworkManager.getLastBatteryPowerValue());
                }
                break;
                
            default:
                response.put("status", "error");
                response.put("message", "Unknown command: " + command);
        }
        
        logger.debug("Response: " + response.toString());
        return response;
    }
    
    // ==================== BROADCASTING ====================
    
    public static void broadcastEvent(JSONObject event) {
        for (ClientHandler client : clients) {
            client.sendEvent(event);
        }
    }
    
    // ==================== CONTEXT ====================
    
    private static Context createAppContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread;
            
            try {
                Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                activityThread = currentActivityThread.invoke(null);
            } catch (Exception e) {
                activityThread = null;
            }
            
            if (activityThread == null) {
                Method systemMain = activityThreadClass.getMethod("systemMain");
                activityThread = systemMain.invoke(null);
            }
            
            if (activityThread == null) return null;
            
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            Context systemContext = (Context) getSystemContext.invoke(activityThread);
            
            return systemContext.createPackageContext("com.overdrive.app",
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            
        } catch (Exception e) {
            logger.error("createAppContext failed: " + e.getMessage());
            return null;
        }
    }
    
    // ==================== BATTERY POLLING ====================
    
    private static final long BATTERY_POLL_INTERVAL_MS = 30000; // 30 seconds
    
    private static void runBatteryPoller() {
        logger.info("Battery poller thread starting (interval: " + BATTERY_POLL_INTERVAL_MS + "ms)...");
        
        while (running) {
            try {
                Thread.sleep(BATTERY_POLL_INTERVAL_MS);
                
                if (bodyworkManager != null && bodyworkManager.isRegistered()) {
                    // Refresh battery info - this will broadcast to all clients
                    bodyworkManager.refreshBatteryInfo();
                }
            } catch (InterruptedException e) {
                logger.info("Battery poller interrupted");
                break;
            } catch (Exception e) {
                logger.error("Battery poller error: " + e.getMessage());
            }
        }
        
        logger.info("Battery poller thread exiting");
    }
    
    // ==================== LOGGING ====================
    
    /**
     * Log message (for callback compatibility with managers).
     */
    public static void logMessage(String message) {
        logger.info(message);
    }
}
