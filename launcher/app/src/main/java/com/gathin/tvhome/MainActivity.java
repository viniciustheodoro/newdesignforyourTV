package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glass home: the time large at the top left, a few line icons at the top right, the owner's main apps as one row of
 * wordmark pills (the focused one turns white and takes the brand's colour), small category tabs under it, and the
 * focused app's backdrop behind everything: this week's most popular series on that service (Trending), or a wash
 * of the app's colour. Everything is laid out in 1920 x 1080 design pixels scaled to the screen.
 */
public class MainActivity extends Activity {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    // ---- layout, in 1920 x 1080 design pixels -------------------------------------------------------
    private static final float SIDE = 96;
    private static final float CLOCK_TOP = 78, TIME_SIZE = 84, DATE_SIZE = 24;
    private static final float ICON = 34, ICON_BOX = 64, ICON_STEP = 76, ICON_CENTER_Y = 109;
    private static final float HERO_TOP = 470, HERO_LOGO_W = 560, HERO_LOGO_H = 150, META_SIZE = 24;
    private static final float WORDMARK_W = 440, WORDMARK_H = 119;
    private static final float ROW_BOTTOM = 872, PILL_H = 112, ROW_GAP = 22;
    private static final int MAIN_COLS = 8, APP_COLS = 6, MAX_ROWS = 4;
    private static final float MAIN_GAP = 20, APP_GAP = 24;
    private static final float CATS_TOP = 940, CAT_H = 40, CAT_SIZE = 16, CAT_PAD = 18, CAT_GAP = 6;
    private static final float FOCUS_SCALE = 1.07f;

    private static final int CAT_MAIN = 0, CAT_APPS = 1, CAT_TOOLS = 2;
    private static final int INK = 0xFFFFFFFF, INK_2 = 0xC7FFFFFF, INK_3 = 0x99FFFFFF, DARK = 0xFF111111;

    /** Brand colours for the wash when a brand has no series art (YouTube, Spotify) or none yet. */
    private static final Map<String, Integer> BRAND_COLORS = new HashMap<>();

    static {
        BRAND_COLORS.put("youtube", 0xFFE01B1B);
        BRAND_COLORS.put("hbomax", 0xFF5B2BD9);
        BRAND_COLORS.put("prime", 0xFF1399FF);
        BRAND_COLORS.put("disney", 0xFF1F4FD8);
        BRAND_COLORS.put("mubi", 0xFF3A3A44);
        BRAND_COLORS.put("netflix", 0xFFE50914);
        BRAND_COLORS.put("globoplay", 0xFFFB3A2A);
        BRAND_COLORS.put("spotify", 0xFF1ED760);
    }

    /** One pill: a brand (Principais) or an app from the model (Apps, Ferramentas). */
    private static final class Item {
        final String key;
        final Brand brand;
        final AppAdapter.Entry entry;
        View view;
        ImageView logo;
        TextView label;
        /** A brand whose app is not installed yet: its pill opens Aurora Store and rests dimmer. */
        boolean missing;

        Item(String key, Brand brand, AppAdapter.Entry entry) {
            this.key = key;
            this.brand = brand;
            this.entry = entry;
        }

        String pkg(PackageManager pm) {
            if (entry != null) return entry.pkg;
            return brand.installed(pm);
        }
    }

    private Config cfg;
    private AppAdapter model;
    private float scale;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> new Thread(r, "glass-decode"));
    /** Composed backdrops and washes by key, so moving back and forth along the row costs no decoding. */
    private final LruCache<String, Bitmap> backdrops = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    private FrameLayout root;
    private ImageView bgLow, bgHigh;
    private boolean highShown;
    private String bgKey;
    private int bgRequest;
    private ImageView heroLogo;
    private TextView heroTitle, heroMeta;
    private FrameLayout pills;
    private final List<Item> items = new ArrayList<>();
    /** Icons loaded for app pills, by package; cleared when the app list changes. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private int cols = MAIN_COLS;
    private int windowStart;
    private final List<TextView> catTabs = new ArrayList<>();
    private int category = CAT_MAIN;
    private final List<View> topButtons = new ArrayList<>();
    private View btnInputs, btnSettings;
    private ImageView weatherIcon;
    private TextView weatherText;
    private View weatherBox;
    private Typeface light, regular, medium;

    // Static so they survive the activity being recreated while an app is open (1 GB: Android may kill Home).
    private static final String[] lastKey = new String[3];
    private static int lastCategory = CAT_MAIN;

    /** Index of the pill being moved (hold OK, Move), or -1. */
    private int moving = -1;
    private boolean rebuildOnResume = true;
    private PopupWindow openPopup;
    private AppLibraryDialog appLibrary;
    private Runnable pendingHero;

    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadModel();
            rebuildPills(pills.hasFocus());
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.refreshIfDue(MainActivity.this, (t, c) -> showWeather(t, c));
            handler.postDelayed(this, 15 * 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        light = font(R.font.outfit_light);
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);

        model = new AppAdapter(this, null, cfg.columns, cfg);
        model.setAlwaysListed(Brands.packages());
        Order saved = Order.load(Order.file(this));
        model.setSavedOrder(Order.ranks(saved, cfg.dockApps), Order.docked(saved, cfg.dockApps));

        buildViews();
        category = lastCategory;

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        applyViewInputsHandler(cfg.handleViewInputs);
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent())) openInputsIfAsked(getIntent());
    }

    /** handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off (off in the manifest). */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /** VIEW_INPUTS (the remote's Input button, through InputsRequestActivity): the inputs menu, once laid out. */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        btnInputs.post(() -> {
            if (isFinishing() || btnInputs.getVisibility() != View.VISIBLE) return;
            btnInputs.requestFocus();
            showInputs();
        });
        return true;
    }

    private Typeface font(int res) {
        try {
            return getResources().getFont(res);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    private int px(float design) {
        return Math.round(design * scale);
    }

    // ---- views ------------------------------------------------------------------------------------

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        setContentView(root);

        bgLow = backdropView();
        bgHigh = backdropView();
        bgHigh.setAlpha(0f);
        root.addView(bgLow, match());
        root.addView(bgHigh, match());

        // Clock: the time large, the date in small capitals under it.
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        styleText(time, TIME_SIZE, regular, INK, -0.02f);
        time.setIncludeFontPadding(false);
        root.addView(time, at(SIDE, CLOCK_TOP));
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMM");
        date.setFormat12Hour("EEEE, d MMM");
        styleText(date, DATE_SIZE, medium, INK_2, 0.06f);
        date.setAllCaps(true);
        date.setIncludeFontPadding(false);
        root.addView(date, at(SIDE + 2, CLOCK_TOP + TIME_SIZE + 14));

        buildTopBar();

        // Hero: the series' title logo (or its name), and one line under it.
        heroLogo = new ImageView(this);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(heroLogo, centered(HERO_LOGO_W, HERO_LOGO_H, HERO_TOP));
        heroTitle = new TextView(this);
        styleText(heroTitle, 66, light, INK, -0.01f);
        heroTitle.setGravity(Gravity.CENTER);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(heroTitle, centered(1300, HERO_LOGO_H, HERO_TOP));
        heroMeta = new TextView(this);
        styleText(heroMeta, META_SIZE, medium, INK_2, 0.04f);
        heroMeta.setGravity(Gravity.CENTER);
        heroMeta.setSingleLine(true);
        root.addView(heroMeta, centered(1300, 40, HERO_TOP + HERO_LOGO_H + 22));

        pills = new FrameLayout(this);
        pills.setClipChildren(false);
        pills.setClipToPadding(false);
        root.addView(pills, match());

        buildCategories();
    }

    private ImageView backdropView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    private void buildTopBar() {
        View bluetooth = iconButton(R.drawable.ic_g_bluetooth, R.string.glass_bluetooth);
        View inputs = iconButton(R.drawable.ic_g_input, R.string.inputs);
        View settings = iconButton(R.drawable.ic_g_settings, R.string.settings);
        View search = iconButton(R.drawable.ic_g_search, R.string.glass_search);
        topButtons.add(bluetooth);
        topButtons.add(inputs);
        topButtons.add(settings);
        topButtons.add(search);
        btnInputs = inputs;
        btnSettings = settings;

        bluetooth.setOnClickListener(v -> open(bluetoothIntent()));
        inputs.setOnClickListener(v -> showInputs());
        settings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        settings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        search.setOnClickListener(v -> {
            setCategory(CAT_APPS);
            focusPill(0);
        });

        weatherBox = new LinearLayout(this);
        ((LinearLayout) weatherBox).setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(this);
        weatherIcon.setImageTintList(ColorStateList.valueOf(0xE6FFFFFF));
        ((LinearLayout) weatherBox).addView(weatherIcon, new LinearLayout.LayoutParams(px(ICON), px(ICON)));
        weatherText = new TextView(this);
        styleText(weatherText, 26, regular, INK_2, 0f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = px(10);
        ((LinearLayout) weatherBox).addView(weatherText, tl);
        weatherBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(ICON_BOX));
        wl.gravity = Gravity.TOP | Gravity.RIGHT;
        wl.topMargin = px(ICON_CENTER_Y - ICON_BOX / 2);
        root.addView(weatherBox, wl);
        layoutTopBar();

        View.OnKeyListener keys = (v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            List<View> shown = visibleTopButtons();
            int i = shown.indexOf(v);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (i > 0) shown.get(i - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (i >= 0 && i < shown.size() - 1) shown.get(i + 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusPill(indexOf(lastKey[category]));
                    return true;
                default:
                    return false;
            }
        };
        for (View b : topButtons) b.setOnKeyListener(keys);
    }

    private List<View> visibleTopButtons() {
        List<View> shown = new ArrayList<>();
        for (View b : topButtons) if (b.getVisibility() == View.VISIBLE) shown.add(b);
        return shown;
    }

    /** Right to left from the right margin: the visible icons one step apart, then the weather one gap further. */
    private void layoutTopBar() {
        List<View> shown = visibleTopButtons();
        float cx = 1920 - SIDE - ICON / 2;
        for (int i = shown.size() - 1; i >= 0; i--) {
            shown.get(i).setLayoutParams(at(cx - ICON_BOX / 2, ICON_CENTER_Y - ICON_BOX / 2, ICON_BOX, ICON_BOX));
            cx -= ICON_STEP;
        }
        FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) weatherBox.getLayoutParams();
        // cx is now one step left of the leftmost icon: the weather ends a gap before that icon starts.
        wl.rightMargin = px(1920 - (cx + ICON_STEP - ICON / 2 - (ICON_STEP - ICON)));
        weatherBox.setLayoutParams(wl);
    }

    /** A focusable icon: a white disc behind it, and the icon dark, while it has focus. */
    private View iconButton(int icon, int description) {
        FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        box.setClickable(true);
        box.setContentDescription(getString(description));
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.WHITE);
        disc.setAlpha(0);
        box.setBackground(disc);
        ImageView img = new ImageView(this);
        img.setImageResource(icon);
        img.setImageTintList(ColorStateList.valueOf(0xE6FFFFFF));
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(px(ICON), px(ICON));
        il.gravity = Gravity.CENTER;
        box.addView(img, il);
        box.setOnFocusChangeListener((v, f) -> {
            disc.setAlpha(f ? 255 : 0);
            img.setImageTintList(ColorStateList.valueOf(f ? DARK : 0xE6FFFFFF));
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
        });
        root.addView(box, at(0, ICON_CENTER_Y - ICON_BOX / 2, ICON_BOX, ICON_BOX));
        return box;
    }

    private void buildCategories() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        int[] names = {R.string.glass_principais, R.string.glass_apps, R.string.glass_tools};
        for (int i = 0; i < names.length; i++) {
            TextView t = new TextView(this);
            t.setText(names[i]);
            styleText(t, CAT_SIZE, medium, INK_3, 0.12f);
            t.setAllCaps(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(CAT_PAD), 0, px(CAT_PAD), 0);
            t.setFocusable(true);
            t.setClickable(true);
            final int cat = i;
            t.setOnFocusChangeListener((v, f) -> {
                if (f && cat != category) setCategory(cat);
                styleTabs();
            });
            t.setOnClickListener(v -> focusPill(indexOf(lastKey[cat])));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(CAT_H));
            lp.leftMargin = lp.rightMargin = px(CAT_GAP / 2);
            bar.addView(t, lp);
            catTabs.add(t);
        }
        TextView add = new TextView(this);
        add.setText("+");
        styleText(add, 22, regular, INK_2, 0f);
        add.setGravity(Gravity.CENTER);
        add.setFocusable(true);
        add.setClickable(true);
        add.setContentDescription(getString(R.string.glass_add));
        add.setOnClickListener(v -> openLibrary());
        add.setOnFocusChangeListener((v, f) -> styleTabs());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(px(CAT_H), px(CAT_H));
        al.leftMargin = px(CAT_GAP / 2);
        bar.addView(add, al);
        catTabs.add(add);

        View.OnKeyListener keys = (v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int i = catTabs.indexOf(v);
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (i > 0) catTabs.get(i - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (i < catTabs.size() - 1) catTabs.get(i + 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    focusPill(indexOf(lastKey[category]));
                    return true;
                default:
                    return false;
            }
        };
        for (View t : catTabs) t.setOnKeyListener(keys);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(CAT_H));
        lp.topMargin = px(CATS_TOP);
        root.addView(bar, lp);
    }

    /** The current category's tab is lit; the focused tab is white. */
    private void styleTabs() {
        for (int i = 0; i < catTabs.size(); i++) {
            TextView t = catTabs.get(i);
            boolean focused = t.isFocused();
            boolean current = i == category;
            boolean plus = i == catTabs.size() - 1;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(CAT_H / 2));
            bg.setColor(focused ? Color.WHITE : current ? 0x2EFFFFFF : plus ? 0x24FFFFFF : Color.TRANSPARENT);
            t.setBackground(bg);
            t.setTextColor(focused ? DARK : current ? INK : plus ? INK_2 : INK_3);
        }
    }

    // ---- model and pills --------------------------------------------------------------------------

    private void reloadModel() {
        icons.clear();
        Brands.resolve(this);
        model.setAlwaysListed(Brands.packages());
        model.reload(Collections.emptyList(), getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
    }

    /** The current category's pills, in order. */
    private List<Item> itemsFor(int cat) {
        List<Item> out = new ArrayList<>();
        if (cat == CAT_MAIN) {
            for (Brand b : Brands.ordered(this)) out.add(new Item("brand:" + b.key, b, null));
            return out;
        }
        for (int i = 0; i < model.appCount(); i++) {
            AppAdapter.Entry e = model.entryAt(i);
            if (e == null || e.kind == AppAdapter.Entry.EMPTY || e.kind == AppAdapter.Entry.HOME_SETTINGS) continue;
            // Only the app a brand's pill opens wears the wordmark; a second build (SmartTube next to YouTube,
            // Netflix's TV build next to its phone build) is an ordinary app with its own icon and name.
            Brand brand = Brands.resolvedFor(e.pkg);
            if (cat == CAT_TOOLS && brand != null) continue;
            out.add(new Item(e.component.flattenToShortString(), brand, e));
        }
        return out;
    }

    private void setCategory(int cat) {
        if (cat == category && !items.isEmpty()) return;
        category = cat;
        lastCategory = cat;
        rebuildPills(false);
        styleTabs();
        updateHeroVisibility();
    }

    /** Recreates the pills of the current category; with refocus, focus returns to the last focused one. */
    private void rebuildPills(boolean refocus) {
        String focusKey = lastKey[category];
        pills.removeAllViews();
        items.clear();
        items.addAll(itemsFor(category));
        cols = category == CAT_MAIN ? MAIN_COLS : APP_COLS;
        windowStart = 0;
        for (Item it : items) {
            it.view = category == CAT_MAIN || it.brand != null ? brandPill(it) : appPill(it);
            pills.addView(it.view);
        }
        layoutPills();
        markDefaultFocus(indexOf(focusKey));
        if (refocus) focusPill(indexOf(focusKey));
    }

    /**
     * The pill Android focuses by itself when it leaves touch mode or the window regains focus, so the first key
     * lands on the app row instead of the first focusable in the layout (the Bluetooth icon).
     */
    private void markDefaultFocus(int index) {
        for (int i = 0; i < items.size(); i++) items.get(i).view.setFocusedByDefault(i == index);
    }

    private float pillWidth() {
        float gap = cols == MAIN_COLS ? MAIN_GAP : APP_GAP;
        return (1920 - 2 * SIDE - (cols - 1) * gap) / cols;
    }

    private int rows() {
        return (items.size() + cols - 1) / cols;
    }

    /** Puts every pill on the grid; rows outside the visible window (more than four rows of apps) are hidden. */
    private void layoutPills() {
        float w = pillWidth(), gap = cols == MAIN_COLS ? MAIN_GAP : APP_GAP;
        int visible = Math.min(rows(), MAX_ROWS);
        float top = ROW_BOTTOM - visible * PILL_H - (visible - 1) * ROW_GAP;
        for (int i = 0; i < items.size(); i++) {
            int r = i / cols, c = i % cols;
            View v = items.get(i).view;
            boolean shown = r >= windowStart && r < windowStart + visible;
            v.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
            FrameLayout.LayoutParams lp = at(SIDE + c * (w + gap), top + (r - windowStart) * (PILL_H + ROW_GAP), w, PILL_H);
            v.setLayoutParams(lp);
        }
    }

    private GradientDrawable pillBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(PILL_H / 2));
        if (focused) {
            d.setColor(Color.WHITE);
        } else {
            d.setColor(0x66101014);
            d.setStroke(Math.max(1, px(1.5f)), 0x26FFFFFF);
        }
        return d;
    }

    /** A pill showing the brand's wordmark: white while resting, the brand's own colours on white when focused. */
    private View brandPill(Item it) {
        FrameLayout v = new FrameLayout(this);
        setUpPill(v, it);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImageResource(it.brand.logoMono);
        Drawable d = logo.getDrawable();
        float aspect = d != null && d.getIntrinsicHeight() > 0 ? d.getIntrinsicWidth() / (float) d.getIntrinsicHeight() : 3f;
        float k = Math.max(0.92f, Math.min(1.12f, pillWidth() / 252f));
        float lw = (float) (66.6 * Math.sqrt(aspect) * k);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(lw), px(lw / aspect));
        lp.gravity = Gravity.CENTER;
        v.addView(logo, lp);
        it.logo = logo;
        // Not installed yet: the pill still opens Aurora Store on the app, but rests a little dimmer.
        it.missing = it.entry == null && it.brand.installed(getPackageManager()) == null;
        if (it.missing) logo.setAlpha(0.55f);
        return v;
    }

    /** A pill with the app's own icon and name, for apps that have no wordmark here. */
    private View appPill(Item it) {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER);
        v.setPadding(px(22), 0, px(22), 0);
        setUpPill(v, it);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(iconFor(it.entry));
        v.addView(icon, new LinearLayout.LayoutParams(px(38), px(38)));
        TextView label = new TextView(this);
        styleText(label, 23, medium, INK, 0.01f);
        label.setText(it.entry.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(px(pillWidth() - 2 * 22 - 38 - 14));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.leftMargin = px(14);
        v.addView(label, ll);
        it.label = label;
        return v;
    }

    /** The app's icon. The model only keeps one for apps without a banner, so the rest are loaded here, once. */
    private Drawable iconFor(AppAdapter.Entry e) {
        if (e.icon != null) return e.icon;
        Drawable d = icons.get(e.pkg);
        if (d != null) return d;
        PackageManager pm = getPackageManager();
        try {
            d = pm.getActivityIcon(e.component);
        } catch (PackageManager.NameNotFoundException ex) {
            try {
                d = pm.getApplicationIcon(e.pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                d = null;
            }
        }
        if (d != null) icons.put(e.pkg, d);
        return d;
    }

    private void setUpPill(View v, Item it) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(pillBackground(false));
        // The focus shadow follows the pill's round ends; the background swap on focus would leave it square.
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        v.setContentDescription(it.brand != null ? it.brand.label : it.entry.label);
        v.setOnFocusChangeListener((view, f) -> onPillFocus(it, f));
        v.setOnClickListener(view -> {
            if (moving >= 0) return;
            launch(it);
        });
        v.setOnLongClickListener(view -> {
            if (moving < 0) showPillMenu(it);
            return true;
        });
        v.setOnKeyListener((view, code, e) -> onPillKey(it, code, e));
    }

    private void onPillFocus(Item it, boolean focused) {
        View v = it.view;
        v.setBackground(pillBackground(focused));
        if (it.logo != null && it.brand != null) {
            it.logo.setImageResource(focused ? it.brand.logoColor : it.brand.logoMono);
            it.logo.setAlpha(focused || !it.missing ? 1f : 0.55f);
        }
        if (it.label != null) it.label.setTextColor(focused ? DARK : INK);
        v.animate().cancel();
        v.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f)
                .translationZ(focused ? px(14) : 0f)
                .setDuration(focused ? 170 : 120).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        if (!focused) return;
        lastKey[category] = it.key;
        markDefaultFocus(items.indexOf(it));
        // Wait for the D-pad to settle before decoding: holding Right across the row only shows where it stops.
        if (pendingHero != null) handler.removeCallbacks(pendingHero);
        pendingHero = () -> showHero(it);
        handler.postDelayed(pendingHero, 90);
    }

    private boolean onPillKey(Item it, int code, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int i = items.indexOf(it);
        if (i < 0) return false;
        int r = i / cols, last = items.size() - 1;
        int target;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                target = i % cols > 0 ? i - 1 : i;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                target = i % cols < cols - 1 && i < last ? i + 1 : i;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (r == 0) {
                    if (moving < 0) focusNearestTopButton(it.view);
                    return true;
                }
                target = i - cols;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (r == rows() - 1) {
                    if (moving < 0) catTabs.get(category).requestFocus();
                    return true;
                }
                target = Math.min(i + cols, last);
                break;
            default:
                return false;
        }
        if (target != i) {
            if (moving >= 0) moveTo(i, target);
            else focusPill(target);
        }
        return true;
    }

    private void focusNearestTopButton(View from) {
        float x = from.getX() + from.getWidth() / 2f;
        View best = null;
        float bestD = Float.MAX_VALUE;
        for (View b : topButtons) {
            if (b.getVisibility() != View.VISIBLE) continue;
            float d = Math.abs(b.getX() + b.getWidth() / 2f - x);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best != null) best.requestFocus();
    }

    private int indexOf(String key) {
        if (key == null) return 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(key)) return i;
        return 0;
    }

    /** Focuses the pill at i, scrolling the row window first when it is out of view. */
    private void focusPill(int i) {
        if (items.isEmpty()) {
            catTabs.get(category).requestFocus();
            return;
        }
        i = Math.max(0, Math.min(i, items.size() - 1));
        int r = i / cols, visible = Math.min(rows(), MAX_ROWS);
        if (r < windowStart || r >= windowStart + visible) {
            windowStart = Math.max(0, Math.min(r - (r < windowStart ? 0 : visible - 1), rows() - visible));
            layoutPills();
        }
        items.get(i).view.requestFocus();
    }

    private void launch(Item it) {
        if (it.entry != null) {
            model.launch(it.entry);
            return;
        }
        Intent i = Brands.launchIntent(this, it.brand);
        if (i == null) {
            Toast.makeText(this, getString(R.string.cant_open, it.brand.label), Toast.LENGTH_SHORT).show();
            return;
        }
        String pkg = it.brand.installed(getPackageManager());
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            if (pkg != null) LastOpened.record(this, pkg);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cant_open, it.brand.label), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- moving a pill (hold OK, Move) --------------------------------------------------------------

    private void showPillMenu(Item it) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * getResources().getDisplayMetrics().density));
        addMenuRow(rows, popup, R.string.glass_move, () -> startMove(it));
        String pkg = it.pkg(getPackageManager());
        if (pkg != null) {
            addMenuRow(rows, popup, R.string.glass_app_info, () -> open(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(popup.getWidth(), panel.getMeasuredWidth()));
        dropIn(popup, panel, it.view, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private void startMove(Item it) {
        moving = items.indexOf(it);
        if (moving < 0) return;
        dimOthers();
        it.view.requestFocus();
        Toast.makeText(this, R.string.glass_move_hint, Toast.LENGTH_SHORT).show();
    }

    private void dimOthers() {
        for (int i = 0; i < items.size(); i++) items.get(i).view.setAlpha(moving < 0 || i == moving ? 1f : 0.45f);
    }

    /** Moves the carried pill from one place to another; the others shift. Saved when it is put down. */
    private void moveTo(int from, int to) {
        Item it = items.remove(from);
        items.add(to, it);
        if (category != CAT_MAIN) {
            // Apps and Ferramentas share the launcher's one saved order: move it there next to its new neighbour.
            int a = model.positionOf(it.key);
            Item neighbour = items.get(to == 0 ? 1 : to - 1);
            int b = model.positionOf(neighbour.key);
            if (a >= 0 && b >= 0) model.move(a, to == 0 ? (a < b ? b - 1 : b) : (a < b ? b : b + 1));
        }
        moving = to;
        layoutPills();
        focusPill(to);
        dimOthers();
    }

    private void endMove() {
        if (moving < 0) return;
        moving = -1;
        dimOthers();
        if (category == CAT_MAIN) {
            List<Brand> order = new ArrayList<>();
            for (Item it : items) order.add(it.brand);
            Brands.saveOrder(this, order);
        } else {
            Order order = new Order(Order.dockKey(cfg.dockApps), model.movableKeys());
            if (Order.save(Order.file(this), order)) {
                model.setSavedOrder(Order.ranks(order, cfg.dockApps), Order.docked(order, cfg.dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---- hero and backdrop ----------------------------------------------------------------------------

    private void updateHeroVisibility() {
        boolean show = category == CAT_MAIN;
        heroLogo.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        heroTitle.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        heroMeta.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }

    /** Backdrop and title for the focused pill: the brand's series this week, else the app's colour. */
    private void showHero(Item it) {
        Trending.Item art = it.brand != null ? Trending.get(this, it.brand) : null;
        String pkg = it.pkg(getPackageManager());
        if (art != null) {
            setBackdrop("art:" + it.brand.key + ":" + art.backdrop.lastModified(), () -> decodeBackdrop(art.backdrop.getPath()));
        } else {
            int color = it.brand != null ? BRAND_COLORS.get(it.brand.key) : modelColor(it);
            setBackdrop("wash:" + Integer.toHexString(color), () -> HeroBackdrop.wash(color));
        }
        if (category != CAT_MAIN) return;

        heroLogo.setImageDrawable(null);
        heroTitle.setText("");
        if (art != null) {
            setBox(heroLogo, HERO_LOGO_W, HERO_LOGO_H);
            if (art.logo != null) {
                String path = art.logo.getPath();
                final String expect = it.key;
                decoder.execute(() -> {
                    Bitmap b = BitmapFactory.decodeFile(path);
                    handler.post(() -> {
                        if (!expect.equals(lastKey[category])) return;
                        if (b == null) {
                            heroTitle.setText(art.title);
                            return;
                        }
                        // Sit the logo on the bottom of its box, so the line under it never floats far below.
                        float aspect = b.getWidth() / (float) Math.max(1, b.getHeight());
                        float h = Math.min(HERO_LOGO_H, HERO_LOGO_W / aspect);
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) heroLogo.getLayoutParams();
                        lp.width = px(HERO_LOGO_W);
                        lp.height = px(h);
                        lp.topMargin = px(HERO_TOP + HERO_LOGO_H - h);
                        heroLogo.setLayoutParams(lp);
                        heroLogo.setImageBitmap(b);
                    });
                });
            } else {
                heroTitle.setText(art.title);
            }
            heroMeta.setText(getString(art.film ? R.string.glass_trending_film : R.string.glass_trending, it.brand.label));
        } else if (it.brand != null) {
            // No series art (YouTube, Spotify, or none fetched yet): the wordmark large, as on its pill.
            setBox(heroLogo, WORDMARK_W, WORDMARK_H);
            heroLogo.setImageResource(it.brand.logoMono);
            heroMeta.setText(pkg == null ? getString(R.string.glass_not_installed) : lastOpenedLine(pkg));
        } else {
            heroTitle.setText(it.entry.label);
            heroMeta.setText(lastOpenedLine(pkg));
        }
    }

    private String lastOpenedLine(String pkg) {
        long when = LastOpened.get(this, pkg);
        return when > 0 ? getString(R.string.last_opened, LastOpened.describe(this, when)) : "";
    }

    private int modelColor(Item it) {
        int pos = model.positionOf(it.key);
        return pos >= 0 ? model.ambientColorAt(pos) : 0xFF444444;
    }

    private void setBox(View v, float w, float h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = px(w);
        lp.height = px(h);
        lp.topMargin = px(HERO_TOP + (HERO_LOGO_H - h) / 2);
        v.setLayoutParams(lp);
    }

    private Bitmap decodeBackdrop(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o);
    }

    private interface Maker {
        Bitmap make();
    }

    /** Cross-fades to the backdrop for key, made off the main thread unless it is cached. */
    private void setBackdrop(String key, Maker maker) {
        if (key.equals(bgKey)) return;
        bgKey = key;
        Bitmap cached = backdrops.get(key);
        if (cached != null) {
            crossfade(cached);
            return;
        }
        final int request = ++bgRequest;
        decoder.execute(() -> {
            Bitmap b;
            try {
                b = maker.make();
            } catch (OutOfMemoryError e) {
                backdrops.evictAll();
                b = null;
            }
            if (b == null) {
                handler.post(() -> {
                    if (key.equals(bgKey)) bgKey = null;
                });
                return;
            }
            Bitmap made = b;
            handler.post(() -> {
                backdrops.put(key, made);
                if (request == bgRequest && key.equals(bgKey)) crossfade(made);
            });
        });
    }

    private void crossfade(Bitmap b) {
        int ms = cfg.reducedMotion ? 140 : 300;
        bgHigh.animate().cancel();
        if (highShown) {
            // The top layer is showing: put the new image under it and fade the top away.
            bgLow.setImageBitmap(b);
            bgHigh.animate().alpha(0f).setDuration(ms).start();
        } else {
            bgHigh.setImageBitmap(b);
            bgHigh.animate().alpha(1f).setDuration(ms).start();
        }
        highShown = !highShown;
    }

    private void showWeather(int celsius, int code) {
        weatherIcon.setImageResource(Weather.icon(code));
        weatherText.setText(String.format(Locale.getDefault(), "%d°", celsius));
        weatherBox.setVisibility(View.VISIBLE);
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /** Home pressed on Home: back to Principais with the first app focused. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent) || openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        endMove();
        if (category != CAT_MAIN) setCategory(CAT_MAIN);
        lastKey[CAT_MAIN] = null;
        handler.post(() -> focusPill(0));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rebuildOnResume) {
            reloadModel();
            rebuildPills(true);
            styleTabs();
            updateHeroVisibility();
        }
        rebuildOnResume = false;
        updateInputsButton();
        int[] w = Weather.cached(this);
        if (w != null) showWeather(w[0], w[1]);
        handler.post(weatherTick);
        Trending.refreshIfDue(this, false, () -> handler.post(this::onArtUpdated));
    }

    private void onArtUpdated() {
        bgKey = null;
        int i = indexOf(lastKey[category]);
        if (i < items.size() && items.get(i).view.hasFocus()) showHero(items.get(i));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        endMove();
        handler.removeCallbacks(weatherTick);
        if (pendingHero != null) handler.removeCallbacks(pendingHero);
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        decoder.shutdownNow();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) backdrops.evictAll();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleSettingsKey(event)) return true;
        // Nothing focused yet (first key after boot, or after touch mode): start on the app row, not on whatever
        // focusable comes first in the layout (the Bluetooth icon).
        if (getCurrentFocus() == null && event.getAction() == KeyEvent.ACTION_DOWN && isDpad(event.getKeyCode())) {
            focusPill(indexOf(lastKey[category]));
            return true;
        }
        if (moving >= 0) {
            int code = event.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
            if (ok || back) {
                if (event.getAction() == KeyEvent.ACTION_UP) endMove();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Back on Apps or Ferramentas returns to Principais; on Principais it goes nowhere.
        if (category != CAT_MAIN) {
            setCategory(CAT_MAIN);
            focusPill(indexOf(lastKey[CAT_MAIN]));
        }
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : cfg.settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(cfg.settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- menus ------------------------------------------------------------------------------------

    private void openLibrary() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        appLibrary = new AppLibraryDialog(this, cfg.animations, cfg.reducedMotion, cfg.appLibrarySelection,
                cfg.libraryOkOpens, this::onHome, () -> {
            reloadModel();
            rebuildPills(false);
        }, () -> {
            appLibrary = null;
            openPopup = null;
            handler.post(() -> catTabs.get(catTabs.size() - 1).requestFocus());
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    private boolean onHome(String pkg) {
        return model.containsPackage(pkg);
    }

    /** Hold OK on the gear: the launcher's own choices. A normal click opens the TV's settings. */
    private void showSettingsMenu() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        float density = getResources().getDisplayMetrics().density;
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        addMenuRow(rows, popup, R.string.glass_tmdb_key, this::showKeyDialog);
        addMenuRow(rows, popup, R.string.glass_refresh_art, () -> {
            if (!Trending.hasKey(this)) {
                showKeyDialog();
                return;
            }
            Toast.makeText(this, R.string.glass_refresh_started, Toast.LENGTH_SHORT).show();
            Trending.refreshIfDue(this, true, () -> handler.post(this::onArtUpdated));
        });
        addMenuRow(rows, popup, R.string.glass_add, this::openLibrary);
        addMenuRow(rows, popup, R.string.home_settings, () -> open(new Intent(this, HomeSettingsActivity.class)));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, btnSettings, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * Where each owner types their own TMDB key (the APK carries none). Saving starts the first refresh at once;
     * an empty field removes the key, and the backdrops fall back to the apps' colours.
     */
    private void showKeyDialog() {
        float density = getResources().getDisplayMetrics().density;
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (26 * density);
        panel.setPadding(pad, (int) (22 * density), pad, (int) (20 * density));

        TextView title = new TextView(this);
        title.setText(R.string.glass_tmdb_key);
        title.setTextColor(INK);
        title.setTextSize(22);
        title.setTypeface(medium);
        panel.addView(title);
        TextView body = new TextView(this);
        body.setText(R.string.glass_tmdb_body);
        body.setTextColor(0xD9FFFFFF);
        body.setTextSize(15);
        body.setTypeface(regular);
        body.setLineSpacing(3 * density, 1f);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = (int) (10 * density);
        panel.addView(body, bl);

        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setHint(R.string.glass_tmdb_hint);
        field.setHintTextColor(0x80FFFFFF);
        field.setTextColor(INK);
        field.setTextSize(18);
        field.setTypeface(regular);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        String current = getSharedPreferences("trending", MODE_PRIVATE).getString("tmdb_key", "");
        field.setText(current);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = (int) (16 * density);
        panel.addView(field, fl);

        TextView credit = new TextView(this);
        credit.setText(R.string.glass_tmdb_credit);
        credit.setTextColor(0x99FFFFFF);
        credit.setTextSize(12);
        credit.setTypeface(regular);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = (int) (12 * density);
        panel.addView(credit, cl);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btl.topMargin = (int) (18 * density);
        panel.addView(buttons, btl);
        Runnable save = () -> {
            String k = field.getText().toString().trim();
            if (!k.isEmpty() && !Trending.looksLikeKey(k)) {
                Toast.makeText(this, R.string.glass_tmdb_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            Trending.setKey(this, k);
            dialog.dismiss();
            if (k.isEmpty()) return;
            Toast.makeText(this, R.string.glass_tmdb_saved, Toast.LENGTH_LONG).show();
            Trending.refreshIfDue(this, true, () -> handler.post(this::onArtUpdated));
        };
        buttons.addView(dialogButton(R.string.glass_cancel, dialog::dismiss));
        buttons.addView(dialogButton(R.string.glass_save, save));
        field.setOnEditorActionListener((v, action, e) -> {
            save.run();
            return true;
        });

        dialog.setContentView(panel);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (520 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        field.requestFocus();
    }

    /** A text button for the key dialog: white with dark text while focused, quiet otherwise. */
    private TextView dialogButton(int text, Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(medium);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding((int) (20 * density), 0, (int) (20 * density), 0);
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, f) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(18 * density);
            bg.setColor(f ? Color.WHITE : 0x24FFFFFF);
            v.setBackground(bg);
            ((TextView) v).setTextColor(f ? DARK : INK);
        });
        b.getOnFocusChangeListener().onFocusChange(b, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * density));
        lp.leftMargin = (int) (10 * density);
        b.setLayoutParams(lp);
        return b;
    }

    private void addMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up of the long press that opened the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        float density = getResources().getDisplayMetrics().density;
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    // ---- system screens and inputs ----------------------------------------------------------------

    /** Bluetooth settings where the firmware has them; the TV's accessory pairing screen, or Settings, otherwise. */
    private Intent bluetoothIntent() {
        PackageManager pm = getPackageManager();
        Intent bt = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        if (bt.resolveActivity(pm) != null) return bt;
        Intent pair = new Intent().setClassName("com.android.tv.settings",
                "com.android.tv.settings.accessories.AddAccessoryActivity");
        if (pair.resolveActivity(pm) != null) return pair;
        return systemIntent(Settings.ACTION_SETTINGS);
    }

    /** MIUI TV answers no standard Settings intents: its own settings app then. */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Hides the inputs icon when it would have nothing to list. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !cfg.sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        int want = any ? View.VISIBLE : View.GONE;
        if (btnInputs.getVisibility() != want) {
            btnInputs.setVisibility(want);
            layoutTopBar();
        }
    }

    private Intent liveTvIntent() {
        if (cfg.liveTvPackage.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(cfg.liveTvPackage);
        if (i != null || cfg.liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(cfg.liveTvAction).setPackage(cfg.liveTvPackage);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    private void openSourcePanel() {
        Intent i = new Intent(cfg.sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(cfg.sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (cfg.inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(cfg.inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown under the inputs icon: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        if (!cfg.sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (180 * density);
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false, tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " · " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                if (named) label = custom + " · " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state, () -> openInput(id));
            }
        }
        if (rows.getChildCount() == 0 && !cfg.sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(cfg.liveTvPackage);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            popup.dismiss();
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));
        dropIn(popup, panel, btnInputs, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        float density = getResources().getDisplayMetrics().density;
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- small helpers ----------------------------------------------------------------------------

    private void styleText(TextView t, float size, Typeface face, int color, float tracking) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(face);
        t.setTextColor(color);
        t.setLetterSpacing(tracking);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams at(float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(w), px(h));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private FrameLayout.LayoutParams centered(float w, float h, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(w), px(h));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = px(y);
        return lp;
    }
}
