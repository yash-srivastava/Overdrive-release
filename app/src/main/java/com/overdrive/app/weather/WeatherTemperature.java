package com.overdrive.app.weather;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.mqtt.ProxyHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared outside-air temperature from the Open-Meteo weather API, keyed on GPS
 * coordinates. Single source of truth for weather temp across the daemon (the
 * automation temperature fallback, ABRP {@code ext_temp}, …) so there is ONE
 * cache and ONE network path, not a copy per caller.
 *
 * <p>Design for the automation use-case: the value is read from the telemetry
 * poll thread (via BydEvent), which must NEVER block on the network. So the
 * public {@link #getCached()} / {@link #refreshAsync} split is:
 * <ul>
 *   <li>{@link #getCached()} returns the last fetched value instantly (NaN if none
 *       yet / too stale), never touching the network.</li>
 *   <li>{@link #refreshAsync(double, double)} kicks a background fetch if the cache
 *       is stale and no fetch is already in flight. Cheap to call every poll.</li>
 * </ul>
 * On ACC-off the head-unit data interface briefly drops ~17s after park but
 * self-recovers ~3 min later while still parked, so an async fetch that fails is
 * simply retried on a later poll; the cache rides out the transient blackout.
 */
public final class WeatherTemperature {
    private static final DaemonLogger logger = DaemonLogger.getInstance("WeatherTemperature");

    // Reuse OkHttp's dispatcher and connection pool across the temperature and
    // assistant-forecast endpoints. Constructing a fresh client for every
    // refresh defeats keep-alive reuse and creates separate dispatcher pools.
    private static final OkHttpClient WEATHER_CLIENT = new OkHttpClient.Builder()
            .proxySelector(ProxyHelper.chainProxySelector())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    // fetchNow is used by the telemetry/ABRP path and intentionally retains
    // its tighter three-second network budget while sharing the base pool.
    private static final OkHttpClient WEATHER_FAST_CLIENT = WEATHER_CLIENT.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    /** Serve the cached value for this long before a refresh is attempted. */
    private static final long CACHE_MS = 10 * 60 * 1000; // 10 min
    /** Discard the cache entirely past this age (a parked car's local weather
     *  doesn't change fast, but a day-old value shouldn't drive an automation). */
    private static final long STALE_MS = 60 * 60 * 1000; // 1 h

    private static volatile double cachedTemp = Double.NaN;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean(false);

    // Mean precipitation probability (%) over the next PRECIP_HOURS hours, from the same
    // Open-Meteo fetch (one round-trip serves temp + rain). -1 = not yet fetched/stale.
    // Drives the "rain likely" automation trigger; kept on the same cache clock as temp.
    private static final int PRECIP_HOURS = 6;
    private static volatile int cachedPrecipProb = -1;

    private WeatherTemperature() {}

    /**
     * Mean precipitation probability (%) over roughly the next {@value #PRECIP_HOURS}
     * hours, or -1 if none fetched yet / cache is stale. Never blocks. Pair with
     * {@link #refreshAsync} on the poll thread the same way as {@link #getCached()}.
     */
    public static int getCachedPrecipProbability() {
        if (cachedPrecipProb < 0) return -1;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return -1;
        return cachedPrecipProb;
    }

    /**
     * The last fetched outside temperature (°C), or NaN if none has been fetched
     * yet or the cached value is older than {@link #STALE_MS}. Never blocks.
     */
    public static double getCached() {
        double t = cachedTemp;
        if (Double.isNaN(t)) return Double.NaN;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return Double.NaN;
        return t;
    }

    /**
     * Kick a background refresh if the cache is stale ({@literal >}{@link #CACHE_MS})
     * and no fetch is already running. Returns immediately. Safe to call every poll.
     *
     * @param lat last-known latitude  (parked car doesn't move, so last-known is fine)
     * @param lon last-known longitude
     */
    public static void refreshAsync(final double lat, final double lon) {
        if (lat == 0.0 && lon == 0.0) return; // no location yet
        long now = System.currentTimeMillis();
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return; // fresh enough
        if (!fetchInFlight.compareAndSet(false, true)) return; // one fetch at a time
        Thread t = new Thread(() -> {
            try {
                fetchNow(lat, lon);
            } finally {
                fetchInFlight.set(false);
            }
        }, "weather-temp-fetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronous fetch — for callers already off the hot path (e.g. ABRP's own
     * upload thread). Updates the shared cache. Returns the fetched value, or the
     * existing cache (possibly NaN) on failure. Bounded to a few seconds.
     */
    public static double fetchNow(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return getCached();
        long now = System.currentTimeMillis();
        // Serve fresh cache without a network round-trip.
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return cachedTemp;
        try {
            // One round-trip serves both signals: current temperature + the next-hours
            // precipitation-probability series (for the "rain likely" automation trigger).
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m&hourly=precipitation_probability&forecast_days=1&timezone=auto",
                    lat, lon);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "OverDrive/1.0")
                    .build();
            // Chain selector (proxy → direct) rather than a frozen proxy: a
            // tailnet-only Tailscale listener would otherwise block the fetch
            // while probing healthy, and weather's 3s budget can't afford it.
            try (Response response = WEATHER_FAST_CLIENT.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    JSONObject root = new JSONObject(body);
                    // Parse precip first so a temp-only success still updates rain (and
                    // vice versa); both share lastFetchTime set once below on any success.
                    int precip = parseMeanPrecip(root);
                    if (precip >= 0) cachedPrecipProb = precip;
                    JSONObject current = root.optJSONObject("current");
                    if (current != null && current.has("temperature_2m")) {
                        double temp = current.getDouble("temperature_2m");
                        cachedTemp = temp;
                        lastFetchTime = System.currentTimeMillis();
                        logger.debug("weather temp=" + String.format(Locale.US, "%.1f", temp)
                                + "°C precip=" + cachedPrecipProb + "%");
                        return temp;
                    }
                    // Temp missing but precip parsed — still a useful refresh.
                    if (precip >= 0) lastFetchTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            logger.debug("weather fetch failed: " + e.getMessage());
        }
        return getCached(); // stale cache or NaN
    }

    /**
     * Mean precipitation probability (%) over the next {@value #PRECIP_HOURS} hourly
     * buckets from Open-Meteo's {@code hourly.precipitation_probability} array. The
     * array starts at 00:00 today; we start at the current hour so "next hours" is
     * actually ahead of now. Returns -1 if the field is absent/empty.
     */
    private static int parseMeanPrecip(JSONObject root) {
        try {
            JSONObject hourly = root.optJSONObject("hourly");
            if (hourly == null) return -1;
            org.json.JSONArray times = hourly.optJSONArray("time");
            org.json.JSONArray probs = hourly.optJSONArray("precipitation_probability");
            if (probs == null || probs.length() == 0) return -1;
            // Find the first bucket at/after the current wall-clock hour. Open-Meteo
            // returns local time strings "YYYY-MM-DDTHH:00"; match on the "THH" hour.
            int startIdx = 0;
            if (times != null && times.length() == probs.length()) {
                String hourTag = String.format(Locale.US, "T%02d",
                        java.time.LocalTime.now().getHour());
                for (int i = 0; i < times.length(); i++) {
                    String ts = times.optString(i, "");
                    if (ts.contains(hourTag)) { startIdx = i; break; }
                }
            }
            long sum = 0; int n = 0;
            for (int i = startIdx; i < probs.length() && n < PRECIP_HOURS; i++, n++) {
                int p = probs.optInt(i, -1);
                if (p >= 0) sum += p;
                else n--; // skip nulls without counting them
            }
            if (n <= 0) return -1;
            return (int) Math.round(sum / (double) n);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- Assistant forecast ----
    // Deliberately a second cache: the fields above feed the "rain likely" automation
    // trigger on the telemetry poll thread, and its cache clock must not be disturbed
    // by an assistant question. One extra Open-Meteo round-trip every FORECAST_CACHE_MS.

    private static final long FORECAST_CACHE_MS = 10 * 60 * 1000;
    private static volatile String forecastJson = "";
    private static volatile long forecastFetchedAt;
    private static volatile double forecastLat = Double.NaN, forecastLon = Double.NaN;

    /**
     * Current conditions plus today's high/low for the assistant, or {@code available:false}.
     * Synchronous and bounded like {@link #fetchNow} — call it off the hot path. Re-fetches
     * when the cache ages out or the car has moved more than ~5 km.
     */
    public static synchronized JSONObject forecast(double lat, double lon) {
        try {
            if (lat == 0.0 && lon == 0.0) {
                return new JSONObject().put("available", false).put("reason", "no_location");
            }
            long now = System.currentTimeMillis();
            boolean moved = Double.isNaN(forecastLat)
                    || Math.abs(lat - forecastLat) > 0.05 || Math.abs(lon - forecastLon) > 0.05;
            if (!forecastJson.isEmpty() && !moved && now - forecastFetchedAt < FORECAST_CACHE_MS) {
                return new JSONObject(forecastJson);
            }
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
                    + "precipitation,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&forecast_days=2&timezone=auto", lat, lon);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "OverDrive/1.0")
                    .build();
            try (Response response = WEATHER_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return new JSONObject().put("available", false).put("reason", "fetch_failed");
                }
                JSONObject root = new JSONObject(response.body().string());
                JSONObject current = root.optJSONObject("current");
                if (current == null) {
                    return new JSONObject().put("available", false).put("reason", "fetch_failed");
                }
                JSONObject out = new JSONObject()
                        .put("available", true)
                        .put("source", "open-meteo")
                        .put("temperatureC", current.optDouble("temperature_2m", Double.NaN))
                        .put("feelsLikeC", current.optDouble("apparent_temperature", Double.NaN))
                        .put("humidityPct", current.optInt("relative_humidity_2m", -1))
                        .put("precipitationMm", current.optDouble("precipitation", Double.NaN))
                        .put("windKph", current.optDouble("wind_speed_10m", Double.NaN))
                        .put("conditions", describeCode(current.optInt("weather_code", -1)));
                JSONObject daily = root.optJSONObject("daily");
                if (daily != null) {
                    out.put("today", day(daily, 0)).put("tomorrow", day(daily, 1));
                }
                forecastJson = out.toString();
                forecastFetchedAt = now;
                forecastLat = lat;
                forecastLon = lon;
                return out;
            }
        } catch (Exception e) {
            logger.debug("weather forecast failed: " + e.getMessage());
            try {
                return new JSONObject().put("available", false).put("reason", "fetch_failed");
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private static JSONObject day(JSONObject daily, int index) throws Exception {
        org.json.JSONArray high = daily.optJSONArray("temperature_2m_max");
        org.json.JSONArray low = daily.optJSONArray("temperature_2m_min");
        org.json.JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        org.json.JSONArray dates = daily.optJSONArray("time");
        JSONObject out = new JSONObject();
        if (dates != null && index < dates.length()) out.put("date", dates.optString(index, ""));
        if (high != null && index < high.length()) out.put("highC", high.optDouble(index, Double.NaN));
        if (low != null && index < low.length()) out.put("lowC", low.optDouble(index, Double.NaN));
        if (rain != null && index < rain.length()) {
            out.put("precipitationProbabilityPct", rain.optInt(index, -1));
        }
        return out;
    }

    /** WMO weather codes as Open-Meteo documents them; unknown stays numeric-free. */
    private static String describeCode(int code) {
        switch (code) {
            case 0: return "clear sky";
            case 1: return "mainly clear";
            case 2: return "partly cloudy";
            case 3: return "overcast";
            case 45: case 48: return "fog";
            case 51: case 53: case 55: return "drizzle";
            case 56: case 57: return "freezing drizzle";
            case 61: return "light rain";
            case 63: return "rain";
            case 65: return "heavy rain";
            case 66: case 67: return "freezing rain";
            case 71: return "light snow";
            case 73: return "snow";
            case 75: return "heavy snow";
            case 77: return "snow grains";
            case 80: case 81: case 82: return "rain showers";
            case 85: case 86: return "snow showers";
            case 95: return "thunderstorm";
            case 96: case 99: return "thunderstorm with hail";
            default: return "unknown";
        }
    }
}
