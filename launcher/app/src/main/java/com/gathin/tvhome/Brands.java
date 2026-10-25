package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "Principais" row of the glass home: a fixed set of apps shown by their wordmark. Each brand lists the
 * packages that can stand for it (TV build first, then the phone build), the one Aurora Store installs when none
 * is there, and the TMDB watch provider whose most popular series becomes its backdrop (Trending).
 */
final class Brand {
    final String key;
    final String label;
    final List<String> packages;
    /** Lower-case words an app's name can carry when it is installed under a package not listed here. */
    final List<String> names;
    final String installPackage;
    /** TMDB watch provider ids for watch_region=BR, "|"-joined; empty when the brand has no catalogue there. */
    final String providers;
    /** The backdrop comes from films rather than series (MUBI is a film service). */
    final boolean films;
    final int logoMono, logoColor;

    Brand(String key, String label, String[] names, String[] packages, String installPackage, String providers,
          int logoMono, int logoColor) {
        this.key = key;
        this.label = label;
        this.names = Collections.unmodifiableList(Arrays.asList(names));
        this.packages = Collections.unmodifiableList(Arrays.asList(packages));
        this.installPackage = installPackage;
        this.providers = providers;
        this.films = "mubi".equals(key);
        this.logoMono = logoMono;
        this.logoColor = logoColor;
    }

    /** The installed app that stands for this brand (see Brands.resolve), or null. */
    /** Whether a TMDB provider id belongs to this brand. */
    boolean hasProvider(int id) {
        for (String p : providers.split("\\|")) if (p.equals(String.valueOf(id))) return true;
        return false;
    }

    String installed(PackageManager pm) {
        if (Brands.resolved != null) return Brands.resolved.get(key);
        for (String p : packages) {
            if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) return p;
        }
        return null;
    }
}

final class Brands {
    private static final String PREFS = "principais";
    private static final String ORDER = "order";

    private Brands() {
    }

    /** In the order the owner asked for; a saved order (hold OK, Move) overrides it. */
    static final List<Brand> ALL = Collections.unmodifiableList(Arrays.asList(
            // The official app first; SmartTube only stands in when it is the only YouTube installed.
            new Brand("youtube", "YouTube", new String[]{"youtube", "smarttube"}, new String[]{"com.google.android.youtube.tv",
                    "com.google.android.youtube.googletv", "com.google.android.youtube", "org.smarttube.stable",
                    "org.smarttube.beta", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv"}, "com.google.android.youtube.tv", "",
                    R.drawable.logo_youtube_mono, R.drawable.logo_youtube_color),
            new Brand("hbomax", "HBO Max", new String[]{"hbo"}, new String[]{"com.wbd.stream", "com.hbo.hbonow"}, "com.wbd.stream",
                    "1899|384", R.drawable.logo_hbomax_mono, R.drawable.logo_hbomax_color),
            new Brand("prime", "Prime Video", new String[]{"prime video"}, new String[]{"com.amazon.amazonvideo.livingroom",
                    "com.amazon.avod.thirdpartyclient"}, "com.amazon.amazonvideo.livingroom", "119",
                    R.drawable.logo_prime_mono, R.drawable.logo_prime_color),
            new Brand("disney", "Disney+", new String[]{"disney"}, new String[]{"com.disney.disneyplus"}, "com.disney.disneyplus", "337",
                    R.drawable.logo_disney_mono, R.drawable.logo_disney_color),
            new Brand("mubi", "MUBI", new String[]{"mubi"}, new String[]{"com.mubi"}, "com.mubi", "11",
                    R.drawable.logo_mubi_mono, R.drawable.logo_mubi_color),
            // The phone build first: on uncertified sets (the Bettdow projector) the TV build refuses to start.
            new Brand("netflix", "Netflix", new String[]{"netflix"}, new String[]{"com.netflix.mediaclient", "com.netflix.ninja"},
                    "com.netflix.mediaclient", "8", R.drawable.logo_netflix_mono, R.drawable.logo_netflix_color),
            new Brand("globoplay", "Globoplay", new String[]{"globoplay"}, new String[]{"com.globo.globotv"}, "com.globo.globotv", "307",
                    R.drawable.logo_globoplay_mono, R.drawable.logo_globoplay_color),
            new Brand("spotify", "Spotify", new String[]{"spotify"}, new String[]{"com.spotify.tv.android", "com.spotify.music"},
                    "com.spotify.tv.android", "", R.drawable.logo_spotify_mono, R.drawable.logo_spotify_color)));

    /** Brand key to the package installed for it, from the last resolve(); null before the first. */
    static Map<String, String> resolved;

    /**
     * Finds each brand's installed app: one of its known packages first, else a launchable app whose name carries
     * the brand's name (a store build under another package, a regional variant). Run when the app list changes.
     */
    static void resolve(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> found = new HashMap<>();
        List<ResolveInfo> launchable = null;
        for (Brand b : ALL) {
            String pkg = null;
            for (String p : b.packages) {
                if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) {
                    pkg = p;
                    break;
                }
            }
            if (pkg == null) {
                if (launchable == null) {
                    launchable = new ArrayList<>(pm.queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
                    launchable.addAll(pm.queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
                }
                for (ResolveInfo ri : launchable) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
                    String name = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                    for (String n : b.names) {
                        if (name.contains(n)) {
                            pkg = ri.activityInfo.packageName;
                            break;
                        }
                    }
                    if (pkg != null) break;
                }
            }
            if (pkg != null) found.put(b.key, pkg);
        }
        resolved = found;
    }

    /** Every package a brand can stand for: these appear in Principais, not in Ferramentas. */
    static Set<String> packages() {
        Set<String> all = new HashSet<>();
        for (Brand b : ALL) all.addAll(b.packages);
        if (resolved != null) all.addAll(resolved.values());
        return all;
    }

    /** The brand whose pill opens this package (its resolved app), or null: other builds are ordinary apps. */
    static Brand resolvedFor(String pkg) {
        if (pkg == null || resolved == null) return null;
        for (Brand b : ALL) if (pkg.equals(resolved.get(b.key))) return b;
        return null;
    }

    static Brand forPackage(String pkg) {
        if (pkg == null) return null;
        for (Brand b : ALL) if (b.packages.contains(pkg)) return b;
        if (resolved != null) {
            for (Brand b : ALL) if (pkg.equals(resolved.get(b.key))) return b;
        }
        return null;
    }

    static Brand byKey(String key) {
        for (Brand b : ALL) if (b.key.equals(key)) return b;
        return null;
    }

    /** The brands in the owner's order: saved keys first, then any brand the saved order does not know. */
    static List<Brand> ordered(Context ctx) {
        String saved = prefs(ctx).getString(ORDER, "");
        List<Brand> out = new ArrayList<>();
        for (String k : saved.split(",")) {
            Brand b = byKey(k);
            if (b != null && !out.contains(b)) out.add(b);
        }
        for (Brand b : ALL) if (!out.contains(b)) out.add(b);
        return out;
    }

    static void saveOrder(Context ctx, List<Brand> order) {
        StringBuilder sb = new StringBuilder();
        for (Brand b : order) {
            if (sb.length() > 0) sb.append(',');
            sb.append(b.key);
        }
        prefs(ctx).edit().putString(ORDER, sb.toString()).apply();
    }

    /** Opens the brand's app; when it is not installed, its page in Aurora Store (or any store) so it can be. */
    static Intent launchIntent(Context ctx, Brand b) {
        PackageManager pm = ctx.getPackageManager();
        String pkg = b.installed(pm);
        if (pkg != null) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            return i;
        }
        Intent store = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + b.installPackage));
        Intent aurora = new Intent(store).setPackage("com.aurora.store");
        if (pm.resolveActivity(aurora, 0) != null) return aurora;
        return pm.resolveActivity(store, 0) != null ? store : null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
