package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Once a week, the most popular series in Brazil on each brand's service (TMDB discover, by watch provider) becomes
 * that brand's backdrop: the art is composed once (HeroBackdrop) and kept with its title logo in the launcher's
 * files. Nothing about the owner is sent; TMDB only sees the key and which services are asked about.
 */
final class Trending {
    private static final String TAG = "TvHome.Trending";
    private static final String PREFS = "trending";
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    /** After a failed run (no network at boot, a timeout), try again this much later rather than at every Home. */
    private static final long RETRY_MS = 6L * 60 * 60 * 1000;
    private static final String API = "https://api.themoviedb.org/3";
    /** The catalogue the art comes from: what streams in this country (TMDB watch region). */
    static final String REGION = "BR";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    /** News, Talk and Reality: popular on paper, but not what a backdrop should show. */
    private static final String SKIP_GENRES = "10763,10767,10764";
    private static volatile boolean running;

    private Trending() {
    }

    static final class Item {
        final String title;
        /** A film rather than a series. */
        final boolean film;
        final File backdrop;
        /** Title logo (PNG), or null: then the title is shown as text. */
        final File logo;

        Item(String title, boolean film, File backdrop, File logo) {
            this.title = title;
            this.film = film;
            this.backdrop = backdrop;
            this.logo = logo;
        }
    }

    static boolean hasKey(Context ctx) {
        return !key(ctx).isEmpty();
    }

    /** The owner's TMDB key: the one typed on the TV (Settings menu), else one built into the APK. */
    static String key(Context ctx) {
        String typed = prefs(ctx).getString("tmdb_key", "").trim();
        return typed.isEmpty() ? BuildConfig.TMDB_KEY.trim() : typed;
    }

    /** A v3 key (32 hex characters) or a v4 read token (a JWT). */
    static boolean looksLikeKey(String k) {
        k = k.trim();
        return k.matches("[0-9a-fA-F]{32}") || (k.startsWith("eyJ") && k.length() > 100);
    }

    /** Saves a typed key (empty clears it) and forgets this week's picks, so the next refresh starts over. */
    static void setKey(Context ctx, String k) {
        prefs(ctx.getApplicationContext()).edit().putString("tmdb_key", k.trim())
                .putLong("last_success", 0).putLong("last_attempt", 0).apply();
    }

    /** This week's art for the brand, or null when there is none yet (or the brand has no series catalogue). */
    static Item get(Context ctx, Brand b) {
        if (b == null || b.providers.isEmpty()) return null;
        File hero = file(ctx, "hero_" + b.key + ".jpg");
        if (!hero.exists()) return null;
        File logo = file(ctx, "logo_" + b.key + ".png");
        String title = prefs(ctx).getString("title_" + b.key, "");
        boolean film = "movie".equals(prefs(ctx).getString("type_" + b.key, "tv"));
        return new Item(title, film, hero, logo.exists() ? logo : null);
    }

    /** Starts a refresh in the background when the week is up (or force); done runs on the network thread. */
    static void refreshIfDue(Context ctx, boolean force, Runnable done) {
        Context app = ctx.getApplicationContext();
        if (!hasKey(app) || running) return;
        SharedPreferences p = prefs(app);
        long now = System.currentTimeMillis();
        boolean missing = false;
        for (Brand b : Brands.ALL) if (!b.providers.isEmpty() && get(app, b) == null) missing = true;
        boolean weekUp = now - p.getLong("last_success", 0) > WEEK_MS;
        boolean retryUp = now - p.getLong("last_attempt", 0) > RETRY_MS;
        if (!force && !((weekUp || missing) && retryUp)) return;
        running = true;
        p.edit().putLong("last_attempt", now).apply();
        Net.IO.execute(() -> {
            try {
                Map<String, JSONObject> picks = choose(app);
                int ok = 0;
                for (Brand b : Brands.ALL) {
                    JSONObject show = picks.get(b.key);
                    if (show == null) continue;
                    try {
                        if (fetch(app, b, show)) ok++;
                    } catch (Exception e) {
                        Log.w(TAG, b.key + ": " + e);
                    }
                }
                if (ok > 0) prefs(app).edit().putLong("last_success", System.currentTimeMillis()).apply();
                if (ok > 0 && done != null) done.run();
            } catch (Exception e) {
                Log.w(TAG, "refresh: " + e);
            } finally {
                running = false;
            }
        });
    }

    /** Key for this refresh, read once on the network thread. */
    private static volatile String activeKey = "";

    private static String auth() {
        // A v4 read token (a JWT) goes in the header; a v3 key goes in the query.
        return activeKey.startsWith("eyJ") ? "" : "&api_key=" + Uri.encode(activeKey);
    }

    private static String bearer() {
        return activeKey.startsWith("eyJ") ? activeKey : null;
    }

    private static JSONObject api(String pathAndQuery) throws Exception {
        String sep = pathAndQuery.contains("?") ? "" : "?x=1";
        return new JSONObject(Net.getText(API + pathAndQuery + sep + auth(), bearer()));
    }

    /**
     * What each brand shows this week. "In alta" is TMDB's weekly trending list, walked in order: the first title
     * streaming on the brand's service in Brazil wins, one title per brand, live action before animation. A brand
     * with nothing in the trending list falls back to its most popular recent title there. Series, except for
     * film services (MUBI).
     */
    private static Map<String, JSONObject> choose(Context ctx) throws Exception {
        activeKey = key(ctx);
        Map<String, JSONObject> picks = new HashMap<>();
        Map<String, JSONObject> animated = new HashMap<>();
        Set<Long> used = new HashSet<>();
        for (boolean films : new boolean[]{false, true}) {
            List<Brand> want = new ArrayList<>();
            for (Brand b : Brands.ALL) if (!b.providers.isEmpty() && b.films == films) want.add(b);
            if (want.isEmpty()) continue;
            String type = films ? "movie" : "tv";
            for (int page = 1; page <= 5 && !allPicked(picks, want); page++) {
                JSONArray results = api("/trending/" + type + "/week?language=" + language() + "&page=" + page).optJSONArray("results");
                if (results == null) break;
                for (int i = 0; i < results.length() && !allPicked(picks, want); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (r.isNull("backdrop_path") || used.contains(r.getLong("id"))) continue;
                    if (hasGenre(r, SKIP_GENRES)) continue;
                    Set<Integer> on = providersInBrazil(type, r.getLong("id"));
                    for (Brand b : want) {
                        if (picks.containsKey(b.key) || !servedBy(b, on)) continue;
                        if (hasGenre(r, "16")) {
                            if (!animated.containsKey(b.key)) animated.put(b.key, r);
                            continue;
                        }
                        r.put("media_type", type);
                        picks.put(b.key, r);
                        used.add(r.getLong("id"));
                        break;
                    }
                }
            }
            for (Brand b : want) {
                if (picks.containsKey(b.key)) continue;
                JSONObject a = animated.get(b.key);
                if (a != null && !used.contains(a.getLong("id"))) {
                    a.put("media_type", type);
                    picks.put(b.key, a);
                    used.add(a.getLong("id"));
                    continue;
                }
                JSONObject f = popularRecent(b, type, used);
                if (f != null) {
                    picks.put(b.key, f);
                    used.add(f.getLong("id"));
                }
            }
        }
        return picks;
    }

    private static boolean allPicked(Map<String, JSONObject> picks, List<Brand> want) {
        for (Brand b : want) if (!picks.containsKey(b.key)) return false;
        return true;
    }

    private static boolean servedBy(Brand b, Set<Integer> providers) {
        for (int id : providers) if (b.hasProvider(id)) return true;
        return false;
    }

    private static boolean hasGenre(JSONObject r, String csv) {
        JSONArray g = r.optJSONArray("genre_ids");
        if (g == null) return false;
        for (String want : csv.split(",")) {
            for (int i = 0; i < g.length(); i++) if (String.valueOf(g.optInt(i)).equals(want)) return true;
        }
        return false;
    }

    /** Subscription services the title streams on in Brazil. */
    private static Set<Integer> providersInBrazil(String type, long id) {
        Set<Integer> out = new HashSet<>();
        try {
            JSONObject br = api("/" + type + "/" + id + "/watch/providers").getJSONObject("results").optJSONObject(REGION);
            JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
            if (flat != null) for (int i = 0; i < flat.length(); i++) out.add(flat.getJSONObject(i).getInt("provider_id"));
        } catch (Exception e) {
            Log.w(TAG, "providers " + id + ": " + e);
        }
        return out;
    }

    /** The brand's most popular title from the last three years, for weeks when none of its titles is trending. */
    private static JSONObject popularRecent(Brand b, String type, Set<Long> used) throws Exception {
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(System.currentTimeMillis() - 3L * 365 * 24 * 60 * 60 * 1000));
        String dateField = type.equals("tv") ? "first_air_date.gte" : "primary_release_date.gte";
        JSONArray results = api("/discover/" + type + "?language=" + language() + "&watch_region=" + REGION + "&with_watch_providers="
                + Uri.encode(b.providers) + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&"
                + dateField + "=" + since + "&without_genres=" + Uri.encode(SKIP_GENRES)
                + "&include_adult=false&page=1").optJSONArray("results");
        if (results == null) return null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            if (r.isNull("backdrop_path") || used.contains(r.getLong("id"))) continue;
            r.put("media_type", type);
            return r;
        }
        return null;
    }

    /** One brand: download and compose the chosen title's art, unless it is the same title as before. */
    private static boolean fetch(Context ctx, Brand b, JSONObject show) throws Exception {
        String type = show.optString("media_type", "tv");
        long id = show.getLong("id");
        String title = type.equals("movie") ? show.optString("title", show.optString("original_title", ""))
                : show.optString("name", show.optString("original_name", ""));
        SharedPreferences p = prefs(ctx);
        File hero = file(ctx, "hero_" + b.key + ".jpg");
        if (p.getLong("id_" + b.key, -1) == id && hero.exists()) return true;   // same title as last week

        byte[] art = Net.get(IMG + "w1280" + show.getString("backdrop_path"), null);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(art, 0, art.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = HeroBackdrop.sampleSize(o.outWidth, o.outHeight, HeroBackdrop.W, HeroBackdrop.H);
        Bitmap src = BitmapFactory.decodeByteArray(art, 0, art.length, o);
        if (src == null) return false;
        Bitmap composed = HeroBackdrop.compose(src);
        src.recycle();
        File tmp = file(ctx, "hero_" + b.key + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            composed.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            composed.recycle();
        }

        byte[] logo = null;
        try {
            JSONObject images = api("/" + type + "/" + id + "/images?include_image_language=" + logoLanguage() + ",en,null");
            String path = pickLogo(images.optJSONArray("logos"));
            if (path != null) logo = Net.get(IMG + "w500" + path, null);
        } catch (Exception e) {
            Log.w(TAG, b.key + " logo: " + e);
        }

        if (!tmp.renameTo(hero)) throw new IOException("cannot save " + hero.getName());
        File logoFile = file(ctx, "logo_" + b.key + ".png");
        if (logo != null) {
            File lt = file(ctx, "logo_" + b.key + ".tmp");
            try (FileOutputStream out = new FileOutputStream(lt)) {
                out.write(logo);
            }
            if (!lt.renameTo(logoFile)) logoFile.delete();
        } else {
            logoFile.delete();
        }
        p.edit().putLong("id_" + b.key, id).putString("title_" + b.key, title).putString("type_" + b.key, type).apply();
        return true;
    }

    /** Titles and logos in the TV's language; Portuguese is Brazil's, since the catalogue is Brazil's. */
    private static String language() {
        String lang = Locale.getDefault().getLanguage();
        return "pt".equals(lang) ? "pt-BR" : Locale.getDefault().toLanguageTag();
    }

    private static String logoLanguage() {
        return Locale.getDefault().getLanguage();
    }

    /** A PNG title logo in the TV's language first, then English, then one with no language. */
    private static String pickLogo(JSONArray logos) {
        if (logos == null) return null;
        for (String lang : new String[]{logoLanguage(), "en", null}) {
            for (int i = 0; i < logos.length(); i++) {
                JSONObject l = logos.optJSONObject(i);
                if (l == null) continue;
                String path = l.optString("file_path", "");
                if (!path.endsWith(".png")) continue;
                String iso = l.isNull("iso_639_1") ? null : l.optString("iso_639_1");
                if (lang == null ? iso == null : lang.equals(iso)) return path;
            }
        }
        return null;
    }

    private static File file(Context ctx, String name) {
        File dir = new File(ctx.getFilesDir(), "trending");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
