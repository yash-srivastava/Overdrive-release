package com.overdrive.app.safekeep;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.ConditionGroup;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.NetworkMonitor;
import com.overdrive.app.safekeep.util.NetRoute;
import com.overdrive.app.safekeep.util.Thermal;
import com.overdrive.app.server.CabinAudioWebSocket;
import com.overdrive.app.server.RemoteCommunicationWebSocket;
import com.overdrive.app.server.RemoteDevViewApiHandler;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.surveillance.SafeLocationManager;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SafeKeep's own view of the car, read from the primary sources on demand. The automation
 * state store is not used directly: it stays empty unless some automation references a key.
 */
public final class UploadSignals {

    /** Clocks earlier than this are the head unit's pre-NTP boot clock. */
    private static final long MIN_VALID_CLOCK_MS = 1767225600000L; // 2026-01-01
    private static final long SSID_REFRESH_MS = 5 * 60_000L;
    private static final long THERMAL_REFRESH_MS = 30_000L;
    private static final long LATCH_RELEASE_MS = 10_000L;
    private static final String[] DAYS =
            {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    public static final class Snap {
        public long at;
        public NetRoute.Snapshot net;
        public String ssid = "";
        public boolean phoneHotspot;
        public boolean accOn;
        /** The automation {@code power} word: on / acc / off. */
        public String power = "off";
        public boolean parked;
        public int gear;
        public double speedKmh = Double.NaN;
        public double v12 = Double.NaN;
        public double soc = Double.NaN;
        public boolean charging;
        public String zone;
        public int minuteOfDay;
        public String day = "";
        public boolean clockValid;
        public boolean liveView;
        public boolean bsVisible;
        public boolean voiceBusy;
        public boolean devView;
        public boolean eventActive;
        public boolean writing;
        public long writerLagMs;
        public int queueDepth;
        public long drops;
        public float tempC = Float.NaN;
        public boolean onOnlyMode;

        /** Unmetered Wi-Fi or Ethernet (phone hotspots and unknown links count as metered). */
        public boolean unmetered() {
            return net != null && !net.metered() && !phoneHotspot;
        }

        public boolean interactive() {
            return bsVisible || voiceBusy || devView;
        }
    }

    private volatile Snap last;
    private volatile long lastAtElapsed;
    private volatile long ssidAt;
    private volatile String ssidKey = "";
    // Monotonic time: RTC/NTP/timezone changes must not freeze thermal checks.
    private volatile long thermalAtElapsed = -1L;
    private volatile float lastTemp = Float.NaN;
    private final AtomicLong parkedUploadMs = new AtomicLong();
    private volatile long parkedSessionMark = -1;
    private volatile Set<String> homeSsids = Collections.emptySet();
    private final ConcurrentHashMap<String, long[]> latches = new ConcurrentHashMap<>();

    /** Cached for {@code maxAgeMs} (monotonic clock); everything here is a cheap in-process read. */
    public Snap capture(long maxAgeMs) {
        Snap s = last;
        long el = android.os.SystemClock.elapsedRealtime();
        if (s != null && el >= lastAtElapsed && el - lastAtElapsed <= maxAgeMs) return s;
        s = read(System.currentTimeMillis());
        last = s;
        lastAtElapsed = el;
        return s;
    }

    /** Home networks named by the user: they are never treated as phone hotspots. */
    void setHomeSsids(List<String> ssids) {
        Set<String> n = new HashSet<>();
        if (ssids != null) for (String h : ssids) n.add(Policy.normalizeSsid(h));
        n.remove("");
        homeSsids = n;
    }

    public Policy.Verdict guards(SafeKeepConfig cfg, Snap s) {
        return Policy.guards(cfg, s, this);
    }

    /** Hysteresis for hard guards: trips at once, releases after {@code clear} has held for 10 s. */
    boolean latch(String key, boolean trip, boolean clear) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (trip) {
            latches.put(key, new long[]{0});
            return true;
        }
        long[] st = latches.get(key);
        if (st == null) return false;
        if (!clear) {
            st[0] = 0;
            return true;
        }
        if (st[0] == 0) {
            st[0] = now;
            return true;
        }
        if (now - st[0] >= LATCH_RELEASE_MS) {
            latches.remove(key, st);
            return false;
        }
        return true;
    }

    void unlatch(String key) {
        latches.remove(key);
    }

    public Snap captureFresh() {
        return capture(0);
    }

    private Snap read(long now) {
        Snap s = new Snap();
        s.at = now;
        s.net = NetRoute.probe();
        s.clockValid = now > MIN_VALID_CLOCK_MS;
        Calendar c = Calendar.getInstance();
        s.minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        s.day = DAYS[c.get(Calendar.DAY_OF_WEEK) - 1];

        boolean meteredHint = false;
        if (s.net.transport == NetRoute.Transport.WIFI) {
            // A new address on the same wlan0 is a new network (home → hotspot): re-read the SSID now.
            String key = s.net.iface + "|" + s.net.localIp;
            long el = android.os.SystemClock.elapsedRealtime();
            if (!key.equals(ssidKey) || el - ssidAt > SSID_REFRESH_MS) {
                try {
                    NetworkMonitor.refresh();
                } catch (Throwable ignored) {
                }
                ssidAt = el;
                ssidKey = key;
            }
            String ssid = NetworkMonitor.getWifiSsid();
            s.ssid = ssid == null ? "" : ssid;
            try {
                // Tri-state: an unreadable metered flag is "unknown", not "not metered", and
                // leaves hotspot detection to the subnet / network-name heuristics below.
                meteredHint = NetworkMonitor.isWifiMeteredHintKnown() && NetworkMonitor.isWifiMeteredHint();
            } catch (Throwable ignored) {
            }
        } else {
            ssidKey = "";
        }
        boolean named = homeSsids.contains(Policy.normalizeSsid(s.ssid));
        s.phoneHotspot = !named && (s.net.likelyPhoneHotspot || (s.net.transport == NetRoute.Transport.WIFI
                && (meteredHint || NetRoute.looksLikePhoneHotspotSsid(s.ssid))));

        try {
            s.accOn = AccMonitor.isAccOn();
        } catch (Throwable ignored) {
        }
        int powerLevel = -1;
        try {
            BydVehicleData d = BydDataCollector.getInstance().getData();
            if (d != null) {
                s.gear = d.gearMode;
                s.speedKmh = d.speedKmh;
                s.v12 = d.voltage12v;
                s.soc = d.socPercent;
                powerLevel = d.powerLevel;
            }
        } catch (Throwable ignored) {
        }
        s.power = s.accOn ? "on"
                : (powerLevel == com.overdrive.app.byd.bodywork.BodyworkConstants.POWER_LEVEL_ACC ? "acc" : "off");
        s.parked = !s.accOn || s.gear == GearMonitor.GEAR_P;
        try {
            s.charging = ChargingDetector.getInstance().isCharging();
        } catch (Throwable ignored) {
        }
        try {
            s.zone = SafeLocationManager.getInstance().getCurrentZoneName();
        } catch (Throwable ignored) {
        }

        try {
            s.voiceBusy = RemoteCommunicationWebSocket.isBusy() || CabinAudioWebSocket.isBusy();
        } catch (Throwable ignored) {
        }
        try {
            s.devView = RemoteDevViewApiHandler.isSessionActive();
        } catch (Throwable ignored) {
        }
        try {
            GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            if (p != null) {
                try {
                    s.bsVisible = p.isBlindSpotLayerVisible();
                } catch (Throwable ignored) {
                }
                try {
                    com.overdrive.app.streaming.WebSocketStreamServer ws = p.getWebSocketServer();
                    s.liveView = ws != null && ws.hasActiveClients();
                } catch (Throwable ignored) {
                }
                try {
                    SurveillanceEngineGpu sentry = p.getSentry();
                    s.eventActive = sentry != null && sentry.hasActiveMotion();
                } catch (Throwable ignored) {
                }
                try {
                    HardwareEventRecorderGpu enc = p.getEncoder();
                    if (enc != null) {
                        s.writing = enc.isWritingToFile();
                        long lw = enc.getLastDiskWrittenMs();
                        s.writerLagMs = (s.writing && lw > 0) ? Math.max(0, now - lw) : 0;
                        s.queueDepth = enc.getMuxerQueueDepth();
                        s.drops = enc.getMuxerDropCount();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        if (SafeKeepTiming.intervalDue(nowElapsed, thermalAtElapsed, THERMAL_REFRESH_MS)) {
            lastTemp = Thermal.readCelsius();
            thermalAtElapsed = android.os.SystemClock.elapsedRealtime();
        }
        s.tempC = lastTemp;
        try {
            s.onOnlyMode = UnifiedConfigManager.isVehicleOnOnlyMode();
        } catch (Throwable ignored) {
        }

        return s;
    }

    /** Every ACC-on report starts a new parking session (seen even when nothing was captured while driving). */
    private void syncParkedSession() {
        long mark;
        try {
            mark = AccMonitor.lastAccOnElapsedMs();
        } catch (Throwable t) {
            mark = 0;
        }
        if (mark != parkedSessionMark) {
            parkedSessionMark = mark;
            parkedUploadMs.set(0);
        }
    }

    /** Upload time on battery while parked (charging is free); bounds the parked keep-awake cost. */
    public void addParkedUploadMs(long ms) {
        syncParkedSession();
        if (ms > 0) parkedUploadMs.addAndGet(ms);
    }

    public long parkedUploadMs() {
        syncParkedSession();
        return parkedUploadMs.get();
    }

    /** Engine start: the time used earlier in this park, if the car has not been switched on since. */
    void restoreParkedUploadMs(long ms, long savedAtWallMs) {
        syncParkedSession();
        long now = System.currentTimeMillis();
        boolean samePark;
        try {
            long lastOnEl = AccMonitor.lastAccOnElapsedMs();
            long lastOnWall = lastOnEl > 0 ? now - (android.os.SystemClock.elapsedRealtime() - lastOnEl) : 0;
            samePark = !AccMonitor.isAccOn() && savedAtWallMs > lastOnWall;
        } catch (Throwable t) {
            samePark = false;
        }
        long age = now - savedAtWallMs;
        if (samePark && ms > 0 && age >= 0 && age < 12 * 3600_000L) parkedUploadMs.set(ms);
    }

    /**
     * Automation-condition state for an advanced rule: our fresh values for the keys we own,
     * the automation store for anything else.
     */
    public static Map<EventData, Value> stateFor(ConditionGroup g, Snap s) {
        List<AutomationCondition> leaves = new ArrayList<>();
        g.collectConditions(leaves);
        Map<EventData, Value> out = new HashMap<>();
        for (AutomationCondition c : leaves) {
            EventData k = c.getEventData();
            if (k == null || out.containsKey(k)) continue;
            Value v = ownValue(k, s);
            // Keys SafeKeep reads itself never fall back to the (possibly stale) automation store.
            if (v == null && !isOwned(k.getType())) {
                try {
                    v = Automations.getStateValue(k);
                } catch (Throwable ignored) {
                }
            }
            if (v != null) out.put(k, v);
        }
        return out;
    }

    /** Condition types the advanced editor offers for SafeKeep rules. */
    public static final String[] OWNED_TYPES = {"power", "gear", "speed", "chargingState",
            "wifiState", "wifiSsid", "locationZone", "time", "day", "aux12vVoltage", "batteryLevel"};

    static boolean isOwned(String type) {
        for (String t : OWNED_TYPES) if (t.equals(type)) return true;
        return false;
    }

    private static Value ownValue(EventData k, Snap s) {
        switch (k.getType()) {
            case "power":
                return new StringValue(s.power);
            case "gear": {
                String g = gearName(s.gear);
                return g.isEmpty() ? null : new StringValue(g);
            }
            case "speed": {
                if (Double.isNaN(s.speedKmh)) return null;
                Map<String, String> vars = k.getVariables();
                double v = (vars != null && "mph".equals(vars.get("units"))) ? s.speedKmh / 1.609344 : s.speedKmh;
                return new IntValue((int) Math.round(v));
            }
            case "chargingState":
                return new StringValue(s.charging ? "on" : "off");
            case "wifiState":
                return new StringValue(s.net != null && s.net.transport == NetRoute.Transport.WIFI ? "on" : "off");
            case "wifiSsid":
                return new StringValue(s.ssid == null ? "" : s.ssid);
            case "locationZone":
                return new StringValue(s.zone == null ? "none" : s.zone);
            case "time":
                return new IntValue(s.minuteOfDay);
            case "day":
                return new StringValue(s.day);
            case "aux12vVoltage":
                return Double.isNaN(s.v12) ? null : new IntValue((int) Math.round(s.v12 * 10));
            case "batteryLevel":
                return Double.isNaN(s.soc) ? null : new IntValue((int) Math.round(s.soc));
            default:
                return null;
        }
    }

    private static String gearName(int g) {
        switch (g) {
            case 1: return "p";
            case 2: return "r";
            case 3: return "n";
            case 4: return "d";
            case 5: return "m";
            case 6: return "s";
            default: return "";
        }
    }
}
