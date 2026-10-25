package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Temperature for the top bar: the city comes from the network's public address (geojs), the weather from
 * Open-Meteo. Neither needs a key. Fetched at most once an hour; the city is kept for three days.
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather";
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final long PLACE_MS = 3L * 24 * HOUR_MS;
    /** Older than this, the last reading is not shown at all rather than shown stale. */
    private static final long STALE_MS = 3 * HOUR_MS;

    interface Listener {
        void onWeather(int celsius, int code);
    }

    private Weather() {
    }

    /** The last reading as {celsius, WMO code}, or null when there is none recent enough. */
    static int[] cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (System.currentTimeMillis() - p.getLong("at", 0) > STALE_MS) return null;
        return new int[]{p.getInt("temp", 0), p.getInt("code", 0)};
    }

    static void refreshIfDue(Context ctx, Listener listener) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        if (now - p.getLong("tried", 0) < HOUR_MS) return;
        p.edit().putLong("tried", now).apply();
        Handler main = new Handler(Looper.getMainLooper());
        Net.IO.execute(() -> {
            try {
                if (now - p.getLong("place_at", 0) > PLACE_MS || !p.contains("lat")) {
                    JSONObject geo = new JSONObject(Net.getText("https://get.geojs.io/v1/ip/geo.json", null));
                    p.edit().putFloat("lat", (float) geo.getDouble("latitude"))
                            .putFloat("lon", (float) geo.getDouble("longitude"))
                            .putLong("place_at", now).apply();
                }
                String url = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code",
                        p.getFloat("lat", 0f), p.getFloat("lon", 0f));
                JSONObject cur = new JSONObject(Net.getText(url, null)).getJSONObject("current");
                int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                int code = cur.getInt("weather_code");
                p.edit().putInt("temp", temp).putInt("code", code).putLong("at", System.currentTimeMillis()).apply();
                main.post(() -> listener.onWeather(temp, code));
            } catch (Exception e) {
                Log.w(TAG, "no weather: " + e);
            }
        });
    }

    /** Line icon for a WMO weather code. */
    static int icon(int code) {
        if (code <= 1) return R.drawable.ic_g_sun;
        if (code == 2) return R.drawable.ic_g_cloud_sun;
        if (code >= 95) return R.drawable.ic_g_storm;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_g_rain;
        return R.drawable.ic_g_cloud;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
