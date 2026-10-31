package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home screen when feed_package is on, in place of the dock and grid, top to bottom:
 * <ol>
 *   <li>the header: a release from the last 10 days on one of the drawer's services, its app's logo, IMDb rating and a
 *       line of plot over its backdrop, turning every few seconds; nothing focusable;</li>
 *   <li>the drawer: the owner's favourite apps on one line, the tiles shrinking so all of them fit. Hold OK on one
 *       for Move, Remove and App info, and on any app below to add it (as on Google TV);</li>
 *   <li>Continue watching;</li>
 *   <li>Top 10: one row and a tab per drawer app; moving across the tabs changes the row;</li>
 *   <li>every other app.</li>
 * </ol>
 * Up from the drawer goes to the top bar, so nothing but the header sits above it.
 */
final class FeedHome {
    interface Host {
        /** Up from the drawer. */
        void onUp();
    }

    private static final long TURN_MS = 8000;
    private static final float BACKDROP_TOP = 1f;
    private static final float BACKDROP_SCROLLED = 0.28f;
    private static final int IMDB_YELLOW = 0xFFF5C518;
    private static final int HERO_PER_APP = 5;
    private static final int HERO_TOTAL = 20;
    /** With a drawer app focused, the header is that app's new releases only: its 5 best, then 10 drawn from the rest. */
    private static final int HERO_ONE_APP = 15, HERO_ONE_BEST = 5;
    /** Long enough that sweeping along the drawer does not turn the header at every app passed. */
    private static final long HERO_SWITCH_MS = 350;
    private static final int GRID_COLUMNS = 6;
    private static final String PREFS = "feed_home";
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String DRAWER = "drawer";
    /** Continue watching titles the owner removed: {"pkg|title": {"e": last watched, "p": progress}}. */
    private static final String REMOVED = "continue_removed";

    private static final int S_DRAWER = 0, S_CONTINUE = 1, S_TABS = 2, S_TAB_ROW = 3, S_GENRES = 4, S_GENRE_ROW = 5,
            S_APPS = 6;
    private static final int GENRE_TOP = 10, GENRE_TABS = 8, GENRE_MIN = 3;

    private final Activity act;
    private final Feed feed;
    private final Host host;
    private final Handler handler;
    private final float d;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final Set<String> hidden;
    private final List<String> defaultDrawer;
    private final Typeface light, regular, medium;

    private final LinearLayout container;
    private final ImageView backdrop;
    private final Scroll scroll;
    private final LinearLayout content;
    private final LinearLayout heroBlock;
    private final ImageView heroLogo;
    private final TextView heroTitle, heroMeta, heroAction;
    /** Watch, shown while the header has focus (holding OK opens Read more). */
    private final LinearLayout heroButtons;
    private final LinearLayout heroTag;
    private final LinearLayout heroDots;
    private final LinearLayout drawerBand;
    private final TextView continueLabel, tabsLabel, genresLabel, appsLabel, tabCaption, genreCaption;
    private final RecyclerView continueRow, tabRow, genreRow, appsGrid;
    private final LinearLayout tabStrip, genreStrip;
    private final View[] sections = new View[7];

    private final Map<String, App> apps = new HashMap<>();
    private List<String> drawer = new ArrayList<>();
    private final List<String> tabPkgs = new ArrayList<>();
    private String selectedTab = "";
    private Map<String, List<Feed.Entry>> genres = Collections.emptyMap();
    private final List<String> genreNames = new ArrayList<>();
    private String selectedGenre = "";
    private List<Feed.Entry> heroes = Collections.emptyList();
    private int heroTurn = -1;
    private String backdropSource = "";
    private int lastDrawer, lastContinue, lastTabRow, lastGenreRow;
    private int movingIndex = -1;
    private final Runnable turn = this::nextHero;
    /** The drawer app the header shows (null: every drawer app's), and the one focus just reached. */
    private String heroApp, pendingHeroApp;
    private final Runnable switchHero = () -> showHeroFor(pendingHeroApp);
    private final Rect rect = new Rect();

    /** An installed TV app. */
    static final class App {
        final String pkg;
        final String label;
        final Drawable banner;
        final Drawable icon;

        App(String pkg, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    FeedHome(Activity act, Feed feed, Host host, Handler handler, LinearLayout container, ImageView backdrop, View scrim,
             List<String> dockApps, List<String> hiddenApps) {
        this.act = act;
        this.feed = feed;
        this.host = host;
        this.handler = handler;
        this.container = container;
        this.backdrop = backdrop;
        d = act.getResources().getDisplayMetrics().density;
        pm = act.getPackageManager();
        prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        hidden = new HashSet<>(hiddenApps);
        defaultDrawer = new ArrayList<>(dockApps);
        light = font(R.font.outfit_light);
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);

        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        backdrop.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        // As Apple's TV app does: a soft shade behind the header's text, from the left edge to about the middle, so the
        // art keeps its own colours on the right; and a fade along the bottom, where the rows begin.
        scrim.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x99080A10, 0x5C080A10, 0x00080A10, 0x00080A10}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0x00080A10, 0x00080A10, 0x00080A10, 0x26080A10, 0xE6080A10})}));

        scroll = new Scroll(act, null);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The page is clipped to its own bounds only when its parent clips children: rows scrolling up then pass under
        // the top bar instead of over the clock. Tiles keep room to grow from the rows' padding.
        container.setClipChildren(true);
        container.setClipToPadding(true);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            float a = y > dp(40) ? BACKDROP_SCROLLED : BACKDROP_TOP;
            if (backdrop.getAlpha() > 0 && Math.abs(backdrop.getAlpha() - a) > 0.01f) {
                backdrop.animate().alpha(a).setDuration(300).start();
            }
        });
        container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content = column();
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(4), 0, dp(4), dp(48));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Header.
        heroBlock = column();
        heroBlock.setGravity(Gravity.BOTTOM);
        heroBlock.setPadding(dp(8), 0, 0, dp(18));
        heroTitle = text(36, Color.WHITE, light);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLetterSpacing(-0.01f);
        heroTitle.setIncludeFontPadding(false);
        heroTitle.setShadowLayer(dp(10), 0, dp(1), 0x99000000);
        heroBlock.addView(heroTitle, new LinearLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.WRAP_CONTENT));
        // The app's logo, then the IMDb tag, then year · genres · season: the logo says where it streams.
        LinearLayout metaRow = row();
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        heroLogo = new ImageView(act);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heroLogo.setClipToOutline(true);
        heroLogo.setBackground(rounded(0x00000000, dp(5)));
        heroLogo.setElevation(dp(2));
        metaRow.addView(heroLogo, new LinearLayout.LayoutParams(dp(24), dp(24)));
        heroTag = row();
        LinearLayout.LayoutParams glp = wrap();
        glp.leftMargin = dp(10);
        metaRow.addView(heroTag, glp);
        heroMeta = text(14, 0xCCFFFFFF, regular);
        heroMeta.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        LinearLayout.LayoutParams mlp = wrap();
        mlp.leftMargin = dp(12);
        metaRow.addView(heroMeta, mlp);
        LinearLayout.LayoutParams mrlp = wrap();
        mrlp.topMargin = dp(12);
        heroBlock.addView(metaRow, mrlp);
        // The plot is behind Read more: the header keeps to the title, its rating and its genres.
        heroButtons = row();
        heroButtons.setVisibility(View.GONE);
        heroAction = text(14, 0xFF0B0D12, medium);
        heroAction.setBackground(rounded(0xF2FFFFFF, dp(18)));
        heroAction.setPadding(dp(16), dp(7), dp(18), dp(7));
        heroButtons.addView(heroAction, wrap());
        LinearLayout.LayoutParams alp = wrap();
        alp.topMargin = dp(16);
        heroBlock.addView(heroButtons, alp);
        // The header is a carousel: Left and Right turn it in one press (round and round), OK watches, holding OK opens
        // Read more (the drawer's hold-OK gesture), Down goes to the drawer.
        heroBlock.setFocusable(true);
        heroBlock.setOnClickListener(v -> {
            if (heroTurn < 0 || heroTurn >= heroes.size()) return;
            Feed.Entry h = heroes.get(heroTurn);
            if (!Feed.open(act, h)) Toast.makeText(act, act.getString(R.string.cant_open, h.title), Toast.LENGTH_SHORT).show();
        });
        heroBlock.setOnLongClickListener(v -> {
            if (heroTurn >= 0 && heroTurn < heroes.size()) showDetails(heroes.get(heroTurn));
            return true;
        });
        heroBlock.setOnFocusChangeListener((v, has) -> {
            heroButtons.setVisibility(has ? View.VISIBLE : View.GONE);
            handler.removeCallbacks(turn);
            if (!has && heroes.size() > 1) handler.postDelayed(turn, TURN_MS);
            if (has) scroll.smoothScrollTo(0, 0);
        });
        heroBlock.setOnKeyListener((v, key, e) -> heroKey(key, e));
        heroDots = row();
        heroDots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(10));
        dlp.topMargin = dp(14);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        heroBlock.addView(heroDots, dlp);
        content.addView(heroBlock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        // 2. Drawer.
        drawerBand = row();
        drawerBand.setGravity(Gravity.CENTER);
        drawerBand.setClipChildren(false);
        drawerBand.setClipToPadding(false);
        drawerBand.setPadding(dp(10), dp(10), dp(10), dp(10));
        drawerBand.setBackground(new GlassBand(dp(18), dp(24), dp(8), Math.max(1, dp(1) / 2)));
        // As wide as its apps, centred: the header's art shows on either side.
        LinearLayout.LayoutParams blp = wrap();
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(drawerBand, blp);
        sections[S_DRAWER] = drawerBand;

        // 3. Continue watching.
        continueLabel = label(act.getString(R.string.feed_continue));
        content.addView(continueLabel, labelParams());
        continueRow = row(S_CONTINUE);
        content.addView(continueRow, rowParams(TILE_H + 28));
        sections[S_CONTINUE] = continueRow;

        // 4. Top 10, a tab per drawer app.
        tabsLabel = label(act.getString(R.string.feed_top10));
        content.addView(tabsLabel, labelParams());
        tabStrip = row();
        tabStrip.setClipChildren(false);
        tabStrip.setClipToPadding(false);
        tabStrip.setPadding(dp(8), dp(4), dp(8), dp(4));
        content.addView(tabStrip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections[S_TABS] = tabStrip;
        tabRow = row(S_TAB_ROW);
        LinearLayout.LayoutParams trp = rowParams(TILE_H + 28);
        trp.topMargin = dp(8);
        content.addView(tabRow, trp);
        sections[S_TAB_ROW] = tabRow;
        tabCaption = caption();
        content.addView(tabCaption, captionParams());

        // 5. Top 10 by genre, from all the drawer apps.
        genresLabel = label(act.getString(R.string.feed_by_genre));
        content.addView(genresLabel, labelParams());
        genreStrip = row();
        genreStrip.setClipChildren(false);
        genreStrip.setClipToPadding(false);
        genreStrip.setPadding(dp(8), dp(4), dp(8), dp(4));
        content.addView(genreStrip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections[S_GENRES] = genreStrip;
        genreRow = row(S_GENRE_ROW);
        LinearLayout.LayoutParams grp = rowParams(TILE_H + 28);
        grp.topMargin = dp(8);
        content.addView(genreRow, grp);
        sections[S_GENRE_ROW] = genreRow;
        genreCaption = caption();
        content.addView(genreCaption, captionParams());

        // 6. Every other app.
        appsLabel = label(act.getString(R.string.feed_apps));
        content.addView(appsLabel, labelParams());
        appsGrid = new RecyclerView(act);
        appsGrid.setLayoutManager(new GridLayoutManager(act, GRID_COLUMNS));
        appsGrid.setNestedScrollingEnabled(false);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setPadding(dp(8), dp(8), dp(8), dp(8));
        appsGrid.setItemAnimator(null);
        appsGrid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content.addView(appsGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sections[S_APPS] = appsGrid;

        // The header fills what the screen has above the drawer, so the drawer sits low with nothing over it but the header.
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot || r - l != or - ol) handler.post(this::layoutSizes);
        });
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    /** Home is on screen: apps may have changed while it was not. Rebuilding drops focus, so it goes back to the drawer. */
    void start() {
        loadApps();
        buildDrawer();
        buildApps();
        update();
        handler.post(this::ensureFocus);
    }

    /** Nothing focused, or focus lost in the hidden grid: back to the drawer. The top bar keeps its focus. */
    void ensureFocus() {
        View f = act.getCurrentFocus();
        if (f == null || !f.isShown()) focusDrawer();
    }

    void stop() {
        handler.removeCallbacks(turn);
        handler.removeCallbacks(switchHero);
        endMove(false);
    }

    /** The add-on published new rows, or the drawer changed. */
    void update() {
        List<Feed.Entry> cont = visibleContinue();
        continueRow.setAdapter(new Tiles(cont, S_CONTINUE, false));
        continueLabel.setVisibility(cont.isEmpty() ? View.GONE : View.VISIBLE);
        continueRow.setVisibility(cont.isEmpty() ? View.GONE : View.VISIBLE);
        buildTabs();
        buildGenres();
        Feed.Entry showing = heroTurn >= 0 && heroTurn < heroes.size() ? heroes.get(heroTurn) : null;
        heroes = pickHeroes();
        heroBlock.setFocusable(!heroes.isEmpty());
        buildDots();
        // A refresh keeps the title on screen where it still is, refreshed in place: the header neither blanks nor
        // starts again from the first title each time the add-on publishes.
        int keep = -1;
        for (int i = 0; showing != null && i < heroes.size(); i++) {
            Feed.Entry h = heroes.get(i);
            if (h.pkg.equals(showing.pkg) && h.title.equals(showing.title)) keep = i;
        }
        if (keep >= 0) {
            heroTurn = keep;
            fillHero(heroes.get(keep));
            updateDots();
            handler.removeCallbacks(turn);
            if (heroes.size() > 1 && !heroBlock.hasFocus()) handler.postDelayed(turn, TURN_MS);
            return;
        }
        heroTurn = -1;
        handler.removeCallbacks(turn);
        nextHero();
    }

    /**
     * The focused drawer app's new releases, or when it has none (YouTube; an app with nothing new) or no app was
     * focused yet, every drawer app's in turn. The draw changes with each refresh.
     */
    private List<Feed.Entry> pickHeroes() {
        List<Feed.Entry> own = heroApp == null ? null : feed.heroCandidates().get(heroApp);
        if (own != null && !own.isEmpty()) {
            return Feed.pickApp(own, HERO_ONE_APP, HERO_ONE_BEST, feed.refreshed() ^ heroApp.hashCode());
        }
        return Feed.pickHero(feed.heroCandidates(), drawer, HERO_PER_APP, HERO_TOTAL, feed.refreshed());
    }

    /** Focus settled on a drawer app: the header turns to its new releases, from the first. */
    private void showHeroFor(String pkg) {
        if (pkg == null || pkg.equals(heroApp)) return;
        heroApp = pkg;
        List<Feed.Entry> next = pickHeroes();
        if (next.equals(heroes)) return;
        heroes = next;
        heroBlock.setFocusable(!heroes.isEmpty());
        buildDots();
        heroTurn = -1;
        nextHero();
    }

    /** Down from the top bar: the header, or the drawer when there is nothing in it. */
    void focusTop() {
        if (heroes.isEmpty()) {
            focusDrawer();
            return;
        }
        heroBlock.requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    /** Focus the drawer, on the tile it was last on. */
    void focusDrawer() {
        if (drawerBand.getChildCount() == 0) {
            focusSection(S_CONTINUE, 1);
            return;
        }
        drawerBand.getChildAt(Math.max(0, Math.min(lastDrawer, drawerBand.getChildCount() - 1))).requestFocus();
        // A tile that already had focus fires no focus change, so the page would stay where it was.
        scroll.smoothScrollTo(0, 0);
    }

    // ---- Continue watching: removed titles ---------------------------------------------------------------

    private static String removedKey(Feed.Entry e) {
        return e.pkg + "|" + e.title.toLowerCase(java.util.Locale.ROOT);
    }

    private org.json.JSONObject removed() {
        try {
            return new org.json.JSONObject(prefs.getString(REMOVED, "{}"));
        } catch (org.json.JSONException e) {
            return new org.json.JSONObject();
        }
    }

    /**
     * Continue watching without the titles the owner removed. A removed title that has been watched since comes back,
     * and is forgotten, so removing it again works the same way.
     */
    private List<Feed.Entry> visibleContinue() {
        org.json.JSONObject gone = removed();
        if (gone.length() == 0) return feed.continueRow();
        List<Feed.Entry> out = new ArrayList<>();
        boolean changed = false;
        for (Feed.Entry e : feed.continueRow()) {
            org.json.JSONObject r = gone.optJSONObject(removedKey(e));
            if (r != null && Feed.stillRemoved(r.optLong("e"), r.optInt("p", -1), e.engaged, e.progress)) continue;
            if (r != null) {
                gone.remove(removedKey(e));
                changed = true;
            }
            out.add(e);
        }
        if (changed) prefs.edit().putString(REMOVED, gone.toString()).apply();
        return out;
    }

    private void removeFromContinue(Feed.Entry e, int position) {
        org.json.JSONObject gone = removed();
        try {
            gone.put(removedKey(e), new org.json.JSONObject().put("e", e.engaged).put("p", e.progress));
        } catch (org.json.JSONException ignored) {
            return;
        }
        // Keep the list small: the oldest removals go first past 200.
        while (gone.length() > 200) gone.remove(gone.keys().next());
        prefs.edit().putString(REMOVED, gone.toString()).apply();
        update();
        if (continueRow.getVisibility() == View.VISIBLE) focusRowAt(continueRow, Math.max(0, position - 1));
        else focusDrawer();
    }

    // ---- sizes -------------------------------------------------------------------------------------------

    private static final int TILE_W = 232;
    private static final int TILE_H = 130;

    private void layoutSizes() {
        int h = container.getHeight();
        if (h <= 0) return;
        int drawerH = drawerBand.getHeight() > 0 ? drawerBand.getHeight() : dp(124);
        // Leave room under the drawer for the next row's name to peek in, as a hint that there is more below.
        int heroH = Math.max(dp(160), h - drawerH - dp(44));
        ViewGroup.LayoutParams lp = heroBlock.getLayoutParams();
        if (lp.height != heroH) {
            lp.height = heroH;
            heroBlock.setLayoutParams(lp);
        }
        buildDrawer();
        buildApps();
    }

    // ---- apps and the drawer ------------------------------------------------------------------------------

    private void loadApps() {
        apps.clear();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(leanback, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(act.getPackageName()) || hidden.contains(pkg) || apps.containsKey(pkg)) continue;
            Drawable banner = r.activityInfo.loadBanner(pm);
            if (banner == null) banner = r.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = NETFLIX.equals(pkg) ? act.getDrawable(R.drawable.logo_netflix_n) : r.loadIcon(pm);
            apps.put(pkg, new App(pkg, String.valueOf(r.loadLabel(pm)), banner, icon));
        }
        String saved = prefs.getString(DRAWER, null);
        List<String> want = saved == null ? defaultDrawer : Arrays.asList(TextUtils.split(saved, ","));
        drawer = new ArrayList<>();
        for (String p : want) if (apps.containsKey(p) && !drawer.contains(p)) drawer.add(p);
        feed.setDrawer(drawer);
    }

    private void saveDrawer() {
        prefs.edit().putString(DRAWER, TextUtils.join(",", drawer)).apply();
        feed.setDrawer(drawer);
    }

    /** Every drawer app on one line: tiles up to 112 dp wide, smaller when more apps have to fit. */
    private void buildDrawer() {
        boolean hadFocus = drawerBand.hasFocus();
        drawerBand.removeAllViews();
        int n = drawer.size();
        if (n == 0) return;
        // The band wraps its tiles, so the room is the page's, not the band's.
        int width = content.getWidth() > 0 ? content.getWidth() : act.getResources().getDisplayMetrics().widthPixels - dp(120);
        int gap = dp(12);
        int inner = width - drawerBand.getPaddingLeft() - drawerBand.getPaddingRight();
        int w = Math.min(dp(112), (inner - gap * (n - 1)) / n);
        int h = w * 9 / 16;
        for (int i = 0; i < n; i++) {
            App a = apps.get(drawer.get(i));
            View tile = appTile(a, w, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i > 0) lp.leftMargin = gap;
            final int index = i;
            tile.setOnFocusChangeListener((v, has) -> {
                lift(v, has, movingIndex == index ? 1.16f : 1.12f);
                if (has) {
                    lastDrawer = index;
                    pendingHeroApp = a.pkg;
                    handler.removeCallbacks(switchHero);
                    handler.postDelayed(switchHero, HERO_SWITCH_MS);
                }
            });
            tile.setOnClickListener(v -> {
                if (movingIndex >= 0) endMove(true);
                else launch(a.pkg);
            });
            tile.setOnLongClickListener(v -> {
                drawerMenu(index);
                return true;
            });
            tile.setOnKeyListener((v, key, e) -> drawerKey(index, key, e));
            drawerBand.addView(tile, lp);
        }
        if (hadFocus) focusDrawer();
    }

    private boolean drawerKey(int index, int key, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (movingIndex >= 0) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int to = index + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to >= 0 && to < drawer.size()) {
                    Collections.swap(drawer, index, to);
                    movingIndex = to;
                    lastDrawer = to;
                    buildDrawer();
                    drawerBand.getChildAt(to).requestFocus();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                endMove(true);
                return true;
            }
            return false;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (heroes.isEmpty()) host.onUp();
                else heroBlock.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusSection(S_CONTINUE, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return index == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return index == drawer.size() - 1;
            default:
                return false;
        }
    }

    private void drawerMenu(int index) {
        String pkg = drawer.get(index);
        App a = apps.get(pkg);
        menu(a == null ? pkg : a.label, new String[] {
                act.getString(R.string.feed_open), act.getString(R.string.feed_move),
                act.getString(R.string.feed_remove), act.getString(R.string.feed_app_info)}, new Runnable[] {
                () -> launch(pkg),
                () -> {
                    movingIndex = index;
                    buildDrawer();
                    drawerBand.getChildAt(index).requestFocus();
                    Toast.makeText(act, R.string.feed_move_hint, Toast.LENGTH_SHORT).show();
                },
                () -> {
                    drawer.remove(pkg);
                    saveDrawer();
                    lastDrawer = Math.max(0, Math.min(index, drawer.size() - 1));
                    buildDrawer();
                    buildApps();
                    update();
                    focusDrawer();
                },
                () -> appInfo(pkg)});
    }

    private void endMove(boolean save) {
        if (movingIndex < 0) return;
        movingIndex = -1;
        if (save) saveDrawer();
        buildDrawer();
        update();
        focusDrawer();
    }

    /** Every installed TV app that is not in the drawer. */
    private void buildApps() {
        List<App> rest = new ArrayList<>();
        for (App a : apps.values()) if (!drawer.contains(a.pkg)) rest.add(a);
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        int width = appsGrid.getWidth() > 0 ? appsGrid.getWidth() : act.getResources().getDisplayMetrics().widthPixels - dp(120);
        int w = (width - appsGrid.getPaddingLeft() - appsGrid.getPaddingRight()) / GRID_COLUMNS - dp(14);
        appsGrid.setAdapter(new AppsAdapter(rest, w, w * 9 / 16));
    }

    private final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<App> list;
        private final int w, h;

        AppsAdapter(List<App> list, int w, int h) {
            this.list = list;
            this.w = w;
            this.h = h;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout cell = new FrameLayout(act);
            cell.setClipChildren(false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h + dp(14)));
            return new RecyclerView.ViewHolder(cell) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            FrameLayout cell = (FrameLayout) vh.itemView;
            cell.removeAllViews();
            App a = list.get(position);
            View tile = appTile(a, w, h);
            cell.addView(tile, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
            tile.setOnFocusChangeListener((v, has) -> lift(v, has, 1.1f));
            tile.setOnClickListener(v -> launch(a.pkg));
            tile.setOnLongClickListener(v -> {
                menu(a.label, new String[] {act.getString(R.string.feed_open), act.getString(R.string.feed_add),
                        act.getString(R.string.feed_app_info)}, new Runnable[] {
                        () -> launch(a.pkg),
                        () -> {
                            drawer.add(a.pkg);
                            saveDrawer();
                            lastDrawer = drawer.size() - 1;
                            buildDrawer();
                            buildApps();
                            update();
                            focusDrawer();
                        },
                        () -> appInfo(a.pkg)});
                return true;
            });
            tile.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int col = position % GRID_COLUMNS;
                if (key == KeyEvent.KEYCODE_DPAD_UP && position < GRID_COLUMNS) {
                    focusSection(S_APPS - 1, -1);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && position + GRID_COLUMNS >= list.size()) {
                    int below = position + GRID_COLUMNS - col;   // the next row's first cell
                    return below >= list.size() || focusGridCell(list.size() - 1);
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return col == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return col == GRID_COLUMNS - 1 || position == list.size() - 1;
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    private boolean focusGridCell(int position) {
        RecyclerView.ViewHolder vh = appsGrid.findViewHolderForAdapterPosition(position);
        if (vh == null) return false;
        ((ViewGroup) vh.itemView).getChildAt(0).requestFocus();
        return true;
    }

    /** An app's banner, or its icon and name on a dark card when it has none. */
    private View appTile(App a, int w, int h) {
        FrameLayout tile = new FrameLayout(act);
        tile.setFocusable(true);
        tile.setLongClickable(true);
        tile.setClipToOutline(true);
        tile.setBackground(rounded(0xFF1C2130, dp(12)));
        tile.setForeground(ring(dp(12)));
        tile.setContentDescription(a == null ? "" : a.label);
        if (a == null) return tile;
        if (a.banner != null) {
            ImageView img = new ImageView(act);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            tile.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = column();
            col.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(act);
            icon.setImageDrawable(a.icon);
            int s = Math.max(dp(20), h / 3);
            col.addView(icon, new LinearLayout.LayoutParams(s, s));
            TextView name = text(h > dp(70) ? 13 : 11, 0xE6FFFFFF, medium);
            name.setText(a.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(6), dp(4), dp(6), 0);
            col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return tile;
    }

    // ---- top 10 tabs -------------------------------------------------------------------------------------

    private void buildTabs() {
        tabPkgs.clear();
        Map<String, List<Feed.Entry>> tabs = feed.tabs();
        for (String pkg : drawer) if (tabs.containsKey(pkg)) tabPkgs.add(pkg);
        boolean any = !tabPkgs.isEmpty();
        tabsLabel.setVisibility(any ? View.VISIBLE : View.GONE);
        tabStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        tabRow.setVisibility(any ? View.VISIBLE : View.GONE);
        boolean hadFocus = tabStrip.hasFocus();
        tabStrip.removeAllViews();
        if (!any) return;
        if (!tabPkgs.contains(selectedTab)) selectedTab = tabPkgs.get(0);
        for (int i = 0; i < tabPkgs.size(); i++) {
            String pkg = tabPkgs.get(i);
            App a = apps.get(pkg);
            LinearLayout pill = row();
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setFocusable(true);
            pill.setPadding(dp(12), dp(7), dp(16), dp(7));
            pill.setForeground(ring(dp(20)));
            ImageView icon = new ImageView(act);
            icon.setImageDrawable(a == null ? null : a.icon);
            icon.setClipToOutline(true);
            icon.setBackground(rounded(0x00000000, dp(5)));
            pill.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView name = text(14, Color.WHITE, medium);
            name.setText(a == null ? pkg : a.label);
            LinearLayout.LayoutParams nlp = wrap();
            nlp.leftMargin = dp(8);
            pill.addView(name, nlp);
            styleTab(pill, pkg.equals(selectedTab));
            final int index = i;
            pill.setOnFocusChangeListener((v, has) -> {
                lift(v, has, 1.06f);
                if (has && !pkg.equals(selectedTab)) {
                    selectedTab = pkg;
                    lastTabRow = 0;
                    for (int k = 0; k < tabStrip.getChildCount(); k++) styleTab((LinearLayout) tabStrip.getChildAt(k), k == index);
                    bindTabRow();
                }
            });
            pill.setOnClickListener(v -> focusSection(S_TAB_ROW, 1));
            pill.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return focusSection(S_TABS - 1, -1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusSection(S_TAB_ROW, 1);
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return index == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return index == tabPkgs.size() - 1;
                return false;
            });
            LinearLayout.LayoutParams lp = wrap();
            if (i > 0) lp.leftMargin = dp(10);
            tabStrip.addView(pill, lp);
        }
        bindTabRow();
        if (hadFocus) focusSection(S_TABS, 1);
    }

    private void styleTab(LinearLayout pill, boolean selected) {
        pill.setBackground(rounded(selected ? 0xF2FFFFFF : 0x1FFFFFFF, dp(20)));
        ((TextView) pill.getChildAt(1)).setTextColor(selected ? 0xFF0B0D12 : 0xD9FFFFFF);
    }

    private void bindTabRow() {
        List<Feed.Entry> list = feed.tabs().get(selectedTab);
        tabRow.setAdapter(new Tiles(list == null ? Collections.emptyList() : list, S_TAB_ROW, true));
        tabCaption.setText("");
    }

    // ---- poster rows --------------------------------------------------------------------------------------

    private RecyclerView row(int section) {
        RecyclerView rv = new RecyclerView(act);
        rv.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setPadding(dp(8), dp(10), dp(8), dp(10));
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setItemAnimator(null);
        rv.setTag(section);
        return rv;
    }

    private final class Tiles extends RecyclerView.Adapter<Tiles.Holder> {
        private final List<Feed.Entry> list;
        private final int section;
        private final boolean ranked;

        Tiles(List<Feed.Entry> list, int section, boolean ranked) {
            this.list = list;
            this.section = section;
            this.ranked = ranked;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView art = new ImageView(act);
            final View shade = new View(act);
            final TextView title = text(15, Color.WHITE, medium);
            final TextView rank = text(30, Color.WHITE, medium);
            final LinearLayout tag = row();
            final ImageView badge = new ImageView(act);
            final View progress = new View(act);
            final View track = new View(act);
            String source = "";

            Holder(FrameLayout card) {
                super(card);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(act);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(TILE_W), dp(TILE_H));
            lp.rightMargin = dp(18);
            card.setLayoutParams(lp);
            card.setBackground(rounded(0xFF1C2130, dp(14)));
            card.setForeground(ring(dp(14)));
            card.setClipToOutline(true);
            card.setFocusable(true);
            Holder h = new Holder(card);
            h.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(h.art, match());
            h.shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x00000000, 0x00000000, 0xE6000000}));
            card.addView(h.shade, match());
            h.title.setMaxLines(2);
            h.title.setEllipsize(TextUtils.TruncateAt.END);
            h.title.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            h.title.setPadding(dp(12), 0, dp(12), dp(12));
            card.addView(h.title, tl);
            h.rank.setShadowLayer(dp(6), 0, dp(1), 0xCC000000);
            h.rank.setIncludeFontPadding(false);
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            rl.leftMargin = dp(12);
            rl.topMargin = dp(8);
            card.addView(h.rank, rl);
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            gl.topMargin = dp(8);
            gl.rightMargin = dp(8);
            card.addView(h.tag, gl);
            h.badge.setClipToOutline(true);
            h.badge.setBackground(rounded(0x00000000, dp(4)));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.END);
            bl.rightMargin = dp(10);
            bl.bottomMargin = dp(12);
            card.addView(h.badge, bl);
            h.track.setBackgroundColor(0x40FFFFFF);
            card.addView(h.track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM));
            h.progress.setBackgroundColor(0xFFE50914);
            card.addView(h.progress, new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM | Gravity.START));

            card.setOnFocusChangeListener((v, has) -> {
                lift(v, has, 1.08f);
                int pos = h.getBindingAdapterPosition();
                if (has && pos != RecyclerView.NO_POSITION) {
                    if (section == S_CONTINUE) lastContinue = pos;
                    else if (section == S_GENRE_ROW) lastGenreRow = pos;
                    else lastTabRow = pos;
                    TextView cap = section == S_TAB_ROW ? tabCaption : section == S_GENRE_ROW ? genreCaption : null;
                    if (cap != null) cap.setText(describe(list.get(pos)));
                }
            });
            card.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Feed.Entry e = list.get(pos);
                if (!Feed.open(act, e)) Toast.makeText(act, act.getString(R.string.cant_open, e.title), Toast.LENGTH_SHORT).show();
            });
            if (section == S_CONTINUE) {
                // Hold OK: remove it from Continue watching until it is watched again.
                card.setLongClickable(true);
                card.setOnLongClickListener(v -> {
                    int pos = h.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return true;
                    Feed.Entry e = list.get(pos);
                    menu(e.title, new String[] {act.getString(R.string.feed_open), act.getString(R.string.feed_remove_continue)},
                            new Runnable[] {() -> v.performClick(), () -> removeFromContinue(e, pos)});
                    return true;
                });
            }
            card.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                if (key == KeyEvent.KEYCODE_DPAD_UP) return focusSection(section - 1, -1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusSection(section + 1, 1);
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return pos == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return pos == list.size() - 1;
                return false;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Feed.Entry e = list.get(position);
            h.itemView.setContentDescription(e.title);
            h.art.setImageDrawable(null);
            h.title.setText(e.title);
            h.title.setVisibility(View.VISIBLE);
            h.shade.setVisibility(View.VISIBLE);
            h.rank.setVisibility(ranked ? View.VISIBLE : View.GONE);
            h.rank.setText(String.valueOf(position + 1));
            h.tag.removeAllViews();
            if (!e.rating.isEmpty()) imdbTag(h.tag, e.rating, true);
            App a = apps.get(e.pkg);
            h.badge.setImageDrawable(a == null ? null : a.icon);
            h.badge.setVisibility(section != S_TAB_ROW && a != null ? View.VISIBLE : View.GONE);
            boolean showProgress = e.progress > 0;
            h.track.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams plp = h.progress.getLayoutParams();
            plp.width = showProgress ? dp(TILE_W) * e.progress / 100 : 0;
            h.progress.setLayoutParams(plp);
            h.source = e.poster;
            String want = e.poster;
            feed.loadArt(want, dp(TILE_W), b -> {
                if (b == null || !want.equals(h.source)) return;
                h.art.setImageBitmap(b);
                // Apps' posters carry their own title; TMDB backdrops do not.
                if (!e.plainArt()) {
                    h.title.setVisibility(View.GONE);
                    h.shade.setVisibility(ranked ? View.VISIBLE : View.GONE);
                }
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ---- navigation ---------------------------------------------------------------------------------------

    /** Focus the nearest shown section from {@code section} in direction {@code dir} (+1 down, -1 up). */
    private boolean focusSection(int section, int dir) {
        for (int s = section; s >= S_DRAWER && s <= S_APPS; s += dir) {
            View v = sections[s];
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            switch (s) {
                case S_DRAWER:
                    if (drawerBand.getChildCount() == 0) continue;
                    focusDrawer();
                    return true;
                case S_CONTINUE:
                    if (focusRowAt(continueRow, lastContinue)) return true;
                    continue;
                case S_TABS:
                    int i = tabPkgs.indexOf(selectedTab);
                    if (i >= 0 && i < tabStrip.getChildCount()) {
                        tabStrip.getChildAt(i).requestFocus();
                        return true;
                    }
                    continue;
                case S_TAB_ROW:
                    if (focusRowAt(tabRow, lastTabRow)) return true;
                    continue;
                case S_GENRES:
                    int g = genreNames.indexOf(selectedGenre);
                    if (g >= 0 && g < genreStrip.getChildCount()) {
                        genreStrip.getChildAt(g).requestFocus();
                        return true;
                    }
                    continue;
                case S_GENRE_ROW:
                    if (focusRowAt(genreRow, lastGenreRow)) return true;
                    continue;
                case S_APPS:
                    if (appsGrid.getAdapter() != null && appsGrid.getAdapter().getItemCount() > 0 && focusGridCell(0)) return true;
                    continue;
                default:
                    break;
            }
        }
        return true;   // nothing that way: stay
    }

    private boolean focusRowAt(RecyclerView rv, int pos) {
        if (rv.getAdapter() == null || rv.getAdapter().getItemCount() == 0) return false;
        int p = Math.max(0, Math.min(pos, rv.getAdapter().getItemCount() - 1));
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            vh.itemView.requestFocus();
            return true;
        }
        rv.scrollToPosition(p);
        rv.post(() -> {
            RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(p);
            if (h != null) h.itemView.requestFocus();
        });
        return true;
    }

    /** Where the page scrolls for a focused view: the top for the drawer (the header in full), else its section's name. */
    private int scrollTarget(View focused) {
        rect.set(0, 0, focused.getWidth(), focused.getHeight());
        try {
            scroll.offsetDescendantRectToMyCoords(focused, rect);
        } catch (IllegalArgumentException notOurs) {
            return scroll.getScrollY();
        }
        int y = rect.top + scroll.getScrollY();
        if (y < continueLabel.getTop() || isIn(focused, drawerBand) || focused == heroBlock) return 0;
        if (isIn(focused, appsGrid)) {
            return Math.max(appsLabel.getTop() - dp(12), y - dp(150));
        }
        View label = isIn(focused, continueRow) ? continueLabel
                : isIn(focused, genreStrip) || isIn(focused, genreRow) ? genresLabel : tabsLabel;
        return Math.max(0, label.getTop() - dp(12));
    }

    private static boolean isIn(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    /** The page: it scrolls by section, smoothly, instead of just far enough to show the focused tile. */
    private final class Scroll extends ScrollView {
        Scroll(Context ctx, AttributeSet attrs) {
            super(ctx, attrs);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            smoothScrollTo(0, scrollTarget(focused));
        }
    }

    // ---- header -------------------------------------------------------------------------------------------

    private void nextHero() {
        stepHero(1);
    }

    /** Turn the header by {@code step} (either way, round and round). */
    private void stepHero(int step) {
        handler.removeCallbacks(turn);
        if (heroes.isEmpty()) {
            heroBlock.setAlpha(0f);
            return;
        }
        heroTurn = Math.floorMod(heroTurn + step, heroes.size());
        dotStep = step;
        Feed.Entry e = heroes.get(heroTurn);
        heroBlock.animate().cancel();
        heroBlock.animate().alpha(0f).setDuration(heroBlock.getAlpha() > 0 ? 180 : 0).withEndAction(() -> {
            fillHero(e);
            heroBlock.animate().alpha(1f).setDuration(420).start();
        }).start();
        String source = !e.backdrop.isEmpty() ? e.backdrop : e.poster;
        if (!source.equals(backdropSource)) {
            backdropSource = source;
            feed.loadArt(source, act.getResources().getDisplayMetrics().widthPixels, b -> showBackdrop(source, b));
        }
        updateDots();
        if (heroes.size() > 1 && !heroBlock.hasFocus()) handler.postDelayed(turn, TURN_MS);
    }

    private void fillHero(Feed.Entry e) {
        App a = apps.get(e.pkg);
        heroLogo.setImageDrawable(a == null ? null : a.icon);
        heroLogo.setVisibility(a == null ? View.GONE : View.VISIBLE);
        String app = a == null ? "" : a.label;
        heroAction.setText(app.isEmpty() ? act.getString(R.string.feed_open) : act.getString(R.string.feed_watch_on, app));
        heroTitle.setText(e.title);
        heroTag.removeAllViews();
        if (!e.rating.isEmpty()) imdbTag(heroTag, e.rating, false);
        // Brand-new titles often have no rating yet: the year then follows the logo where the tag would have.
        heroTag.setVisibility(e.rating.isEmpty() ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) heroMeta.getLayoutParams()).leftMargin = dp(10);
        heroMeta.requestLayout();
        // After the IMDb tag: year, genres, and the season for a series that counts as new because of it.
        List<String> meta = new ArrayList<>();
        if (!e.year.isEmpty()) meta.add(e.year);
        String genres = genreNames(e.genre);
        if (!genres.isEmpty()) meta.add(genres);
        if (!e.subtitle.isEmpty()) meta.add(e.subtitle);
        heroMeta.setText(TextUtils.join("  ·  ", meta));
    }


    private void showBackdrop(String source, Bitmap b) {
        if (!source.equals(backdropSource)) return;
        float target = scroll.getScrollY() > dp(40) ? BACKDROP_SCROLLED : BACKDROP_TOP;
        backdrop.animate().cancel();
        if (b == null) {
            backdrop.animate().alpha(0f).setDuration(300).start();
            return;
        }
        backdrop.animate().alpha(0f).setDuration(backdrop.getAlpha() > 0 ? 260 : 0).withEndAction(() -> {
            backdrop.setImageBitmap(b);
            backdrop.animate().alpha(target).setDuration(700).start();
        }).start();
    }

    /** At most 5 dots, centred: the current one a pill, its neighbours smaller, the outer two smaller still. */
    private void buildDots() {
        heroDots.removeAllViews();
        if (heroes.size() < 2) return;
        for (int i = 0; i < Math.min(5, heroes.size()); i++) {
            View dot = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.gravity = Gravity.CENTER_VERTICAL;
            if (i > 0) lp.leftMargin = dp(10);
            heroDots.addView(dot, lp);
        }
    }

    private int dotStep = 1;

    /**
     * Each dot grows or shrinks to its new size, and the row glides in from the side the carousel moved toward, so
     * the pill seems to travel along it.
     */
    private void updateDots() {
        int k = heroDots.getChildCount();
        if (k == 0) return;
        boolean animate = heroBlock.getAlpha() > 0 && heroDots.isLaidOut();
        if (animate && heroes.size() > 5) {
            heroDots.setTranslationX(dotStep * dp(16));
            heroDots.animate().translationX(0).setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        // Five or more titles: the window is centred on the current one. Fewer: one dot per title.
        int current = heroes.size() > 5 ? k / 2 : heroTurn;
        for (int i = 0; i < k; i++) {
            int dist = Math.abs(i - current);
            View dot = heroDots.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            int w = dist == 0 ? dp(22) : dist == 1 ? dp(8) : dp(5);
            int h = dist == 0 ? dp(8) : dist == 1 ? dp(8) : dp(5);
            dot.setBackground(rounded(dist == 0 ? 0xFFFFFFFF : dist == 1 ? 0xB3FFFFFF : 0x66FFFFFF, dp(4)));
            if (!animate || (lp.width == w && lp.height == h)) {
                lp.width = w;
                lp.height = h;
                dot.setLayoutParams(lp);
                continue;
            }
            int w0 = lp.width, h0 = lp.height;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(320);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(an -> {
                float f = (float) an.getAnimatedValue();
                lp.width = Math.round(w0 + (w - w0) * f);
                lp.height = Math.round(h0 + (h - h0) * f);
                dot.setLayoutParams(lp);
            });
            a.start();
        }
    }

    private boolean heroKey(int key, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                stepHero(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                stepHero(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                host.onUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusDrawer();
                return true;
            default:
                // OK and Enter go to the view: a press watches, a hold opens Read more.
                return false;
        }
    }

    /** "Drama, Action" from "Drama, Action & Adventure": one name per genre, each once. */
    private static String genreNames(String genre) {
        List<String> names = new ArrayList<>();
        for (String g : genre.split(",")) {
            String n = Feed.genreName(g);
            if (!n.isEmpty() && !names.contains(n)) names.add(n);
        }
        return TextUtils.join(", ", names);
    }

    /** "TV show" or "Movie", empty when the add-on could not tell. */
    private String kindLabel(Feed.Entry e) {
        if ("tv".equals(e.kind)) return act.getString(R.string.feed_series);
        if ("movie".equals(e.kind)) return act.getString(R.string.feed_movie);
        return "";
    }

    /** The focused tile in words, under its row: what the tile itself does not show, its type, genres and year. */
    private String describe(Feed.Entry e) {
        List<String> parts = new ArrayList<>();
        String kind = kindLabel(e);
        if (!kind.isEmpty()) parts.add(kind);
        String genres = genreNames(e.genre);
        if (!genres.isEmpty()) parts.add(genres);
        if (!e.year.isEmpty()) parts.add(e.year);
        return TextUtils.join("  ·  ", parts);
    }

    // ---- genres ------------------------------------------------------------------------------------------

    private void buildGenres() {
        genres = Feed.genreRows(feed.tabs(), feed.pool(), drawer, GENRE_TOP, GENRE_TABS, GENRE_MIN);
        genreNames.clear();
        genreNames.addAll(genres.keySet());
        boolean any = !genreNames.isEmpty();
        genresLabel.setVisibility(any ? View.VISIBLE : View.GONE);
        genreStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        genreRow.setVisibility(any ? View.VISIBLE : View.GONE);
        genreCaption.setVisibility(any ? View.VISIBLE : View.GONE);
        boolean hadFocus = genreStrip.hasFocus();
        genreStrip.removeAllViews();
        if (!any) return;
        if (!genreNames.contains(selectedGenre)) selectedGenre = genreNames.get(0);
        for (int i = 0; i < genreNames.size(); i++) {
            String name = genreNames.get(i);
            TextView pill = text(14, Color.WHITE, medium);
            pill.setText(name);
            pill.setFocusable(true);
            pill.setPadding(dp(16), dp(8), dp(16), dp(8));
            pill.setForeground(ring(dp(20)));
            styleGenre(pill, name.equals(selectedGenre));
            final int index = i;
            pill.setOnFocusChangeListener((v, has) -> {
                lift(v, has, 1.06f);
                if (has && !name.equals(selectedGenre)) {
                    selectedGenre = name;
                    lastGenreRow = 0;
                    for (int k = 0; k < genreStrip.getChildCount(); k++) styleGenre((TextView) genreStrip.getChildAt(k), k == index);
                    bindGenreRow();
                }
            });
            pill.setOnClickListener(v -> focusSection(S_GENRE_ROW, 1));
            pill.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return focusSection(S_GENRES - 1, -1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusSection(S_GENRE_ROW, 1);
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return index == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return index == genreNames.size() - 1;
                return false;
            });
            LinearLayout.LayoutParams lp = wrap();
            if (i > 0) lp.leftMargin = dp(10);
            genreStrip.addView(pill, lp);
        }
        bindGenreRow();
        if (hadFocus) focusSection(S_GENRES, 1);
    }

    private void styleGenre(TextView pill, boolean selected) {
        pill.setBackground(rounded(selected ? 0xF2FFFFFF : 0x1FFFFFFF, dp(20)));
        pill.setTextColor(selected ? 0xFF0B0D12 : 0xD9FFFFFF);
    }

    private void bindGenreRow() {
        List<Feed.Entry> list = genres.get(selectedGenre);
        genreRow.setAdapter(new Tiles(list == null ? Collections.emptyList() : list, S_GENRE_ROW, true));
        genreCaption.setText("");
    }

    private TextView caption() {
        TextView t = text(13, 0xB3FFFFFF, regular);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private LinearLayout.LayoutParams captionParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.topMargin = dp(2);
        return lp;
    }

    // ---- pieces -------------------------------------------------------------------------------------------

    /** The IMDb tag: the yellow IMDb mark and the rating on dark glass, in one pill. */
    private void imdbTag(LinearLayout into, String rating, boolean small) {
        int h = dp(small ? 20 : 24);
        int r = dp(small ? 5 : 6);
        TextView mark = text(small ? 10 : 12, 0xFF000000, Typeface.create(medium, Typeface.BOLD));
        mark.setText(R.string.feed_imdb);
        mark.setGravity(Gravity.CENTER);
        mark.setPadding(dp(small ? 5 : 7), 0, dp(small ? 5 : 7), 0);
        GradientDrawable left = new GradientDrawable();
        left.setColor(IMDB_YELLOW);
        left.setCornerRadii(new float[] {r, r, 0, 0, 0, 0, r, r});
        mark.setBackground(left);
        into.addView(mark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h));
        TextView value = text(small ? 11 : 13, Color.WHITE, medium);
        value.setText(rating);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(small ? 6 : 8), 0, dp(small ? 6 : 8), 0);
        GradientDrawable right = new GradientDrawable();
        right.setColor(0xB3101218);
        right.setStroke(Math.max(1, dp(1) / 2), 0x66F5C518);
        right.setCornerRadii(new float[] {0, 0, r, r, r, r, 0, 0});
        value.setBackground(right);
        into.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h));
    }

    /**
     * Read more: the title's plot, cast, crew and facts over Home, with Watch and Close (Back closes too). What Home
     * already has shows at once; the cast and the rest arrive from the add-on, which asks TMDB the first time.
     */
    private void showDetails(Feed.Entry e) {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column();
        box.setPadding(dp(26), dp(24), dp(26), dp(20));
        GradientDrawable bg = rounded(0xF5171A22, dp(22));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x26FFFFFF);
        box.setBackground(bg);

        // Art beside the app, the title and the facts line.
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setBackground(rounded(0x1AFFFFFF, dp(12)));
        top.addView(art, new LinearLayout.LayoutParams(dp(208), dp(117)));
        String source = !e.backdrop.isEmpty() ? e.backdrop : e.poster;
        if (!source.isEmpty()) feed.loadArt(source, dp(208) * 2, art::setImageBitmap);
        LinearLayout head = column();
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = dp(20);
        top.addView(head, hlp);
        App a = apps.get(e.pkg);
        if (a != null) {
            LinearLayout kicker = row();
            kicker.setGravity(Gravity.CENTER_VERTICAL);
            ImageView logo = new ImageView(act);
            logo.setImageDrawable(a.icon);
            logo.setClipToOutline(true);
            logo.setBackground(rounded(0x00000000, dp(5)));
            kicker.addView(logo, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView name = text(12, 0xD9FFFFFF, medium);
            name.setText(a.label);
            name.setAllCaps(true);
            name.setLetterSpacing(0.16f);
            LinearLayout.LayoutParams nlp = wrap();
            nlp.leftMargin = dp(8);
            kicker.addView(name, nlp);
            head.addView(kicker, wrap());
        }
        TextView title = text(26, Color.WHITE, light);
        title.setText(e.title);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.topMargin = dp(8);
        head.addView(title, tlp);
        LinearLayout metaRow = row();
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        if (!e.rating.isEmpty()) imdbTag(metaRow, e.rating, true);
        TextView meta = text(13, 0xCCFFFFFF, regular);
        LinearLayout.LayoutParams mlp = wrap();
        mlp.leftMargin = e.rating.isEmpty() ? 0 : dp(10);
        metaRow.addView(meta, mlp);
        LinearLayout.LayoutParams mrl = wrap();
        mrl.topMargin = dp(10);
        head.addView(metaRow, mrl);
        meta.setText(detailsMeta(e, null));

        // The plot, then the people and the facts as they arrive.
        TextView tagline = text(14, 0x99FFFFFF, Typeface.create(regular, Typeface.ITALIC));
        tagline.setVisibility(View.GONE);
        LinearLayout.LayoutParams glp = wrap();
        glp.topMargin = dp(18);
        box.addView(tagline, glp);
        TextView plot = text(15, 0xE6FFFFFF, regular);
        plot.setText(e.overview);
        plot.setMaxLines(6);
        plot.setEllipsize(TextUtils.TruncateAt.END);
        plot.setLineSpacing(dp(3), 1f);
        plot.setVisibility(e.overview.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(12);
        box.addView(plot, plp);
        LinearLayout facts = column();
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(14);
        box.addView(facts, flp);
        if (!e.tmdb.isEmpty()) {
            TextView loading = text(13, 0x80FFFFFF, regular);
            loading.setText(R.string.feed_loading_details);
            facts.addView(loading, wrap());
        }

        // Watch and Close.
        LinearLayout buttons = row();
        LinearLayout.LayoutParams blp = wrap();
        blp.topMargin = dp(20);
        box.addView(buttons, blp);
        String app = a == null ? "" : a.label;
        TextView watch = detailsButton(app.isEmpty() ? act.getString(R.string.feed_open) : act.getString(R.string.feed_watch_on, app));
        watch.setOnClickListener(v -> {
            dialog.dismiss();
            if (!Feed.open(act, e)) Toast.makeText(act, act.getString(R.string.cant_open, e.title), Toast.LENGTH_SHORT).show();
        });
        buttons.addView(watch, wrap());
        TextView close = detailsButton(act.getString(R.string.feed_close));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = wrap();
        clp.leftMargin = dp(10);
        buttons.addView(close, clp);

        dialog.setContentView(box, new ViewGroup.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        watch.requestFocus();

        if (e.tmdb.isEmpty()) return;   // a YouTube video: its description is all there is
        feed.details(e.tmdb, d -> {
            facts.removeAllViews();
            if (d == null) {
                TextView none = text(13, 0x80FFFFFF, regular);
                none.setText(R.string.feed_no_details);
                facts.addView(none, wrap());
                return;
            }
            meta.setText(detailsMeta(e, d));
            String tag = d.getOrDefault("tagline", "");
            tagline.setText(tag);
            tagline.setVisibility(tag.isEmpty() ? View.GONE : View.VISIBLE);
            String overview = d.getOrDefault("overview", "");
            if (!overview.isEmpty()) {
                plot.setText(overview);
                plot.setVisibility(View.VISIBLE);
            }
            fact(facts, R.string.feed_starring, d.get("cast"));
            String directors = d.getOrDefault("directors", "");
            fact(facts, directors.contains(",") ? R.string.feed_directors : R.string.feed_director, directors);
            fact(facts, R.string.feed_created_by, d.get("creators"));
            fact(facts, R.string.feed_seasons_label, seasonsLine(d));
            fact(facts, R.string.feed_language, d.get("language"));
            fact(facts, R.string.feed_released, longDate(d.get("released")));
            fact(facts, R.string.feed_status, d.get("status"));
        });
    }

    /** Year · length (or seasons) · age rating · genres · season: what Home has, then what the add-on adds. */
    private String detailsMeta(Feed.Entry e, Map<String, String> d) {
        List<String> meta = new ArrayList<>();
        if (!e.year.isEmpty()) meta.add(e.year);
        if (d != null) {
            int runtime = number(d.get("runtime")), seasons = number(d.get("seasons"));
            if (runtime > 0) meta.add(runtime >= 60 ? act.getString(R.string.feed_runtime, runtime / 60, runtime % 60)
                    : act.getString(R.string.feed_minutes, runtime));
            else if (seasons > 0) meta.add(act.getResources().getQuantityString(R.plurals.feed_seasons, seasons, seasons));
            String rating = d.getOrDefault("certification", "");
            if (!rating.isEmpty()) meta.add(rating);
        }
        String genres = genreNames(e.genre);
        if (!genres.isEmpty()) meta.add(genres);
        if (!e.subtitle.isEmpty()) meta.add(e.subtitle);
        return TextUtils.join("  ·  ", meta);
    }

    /** "2 seasons · 16 episodes · about 42 min each", or empty for a film. */
    private String seasonsLine(Map<String, String> d) {
        int seasons = number(d.get("seasons")), episodes = number(d.get("episodes")), each = number(d.get("episode_runtime"));
        List<String> parts = new ArrayList<>();
        if (seasons > 0) parts.add(act.getResources().getQuantityString(R.plurals.feed_seasons, seasons, seasons));
        if (episodes > 0) parts.add(act.getResources().getQuantityString(R.plurals.feed_episodes, episodes, episodes));
        if (each > 0) parts.add(act.getString(R.string.feed_episode_length, each));
        return TextUtils.join("  ·  ", parts);
    }

    /** "13 May 2026" from "2026-05-13"; empty when there is no date. */
    private static String longDate(String iso) {
        try {
            return java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int number(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One "Starring   Name, Name" line under the plot; nothing when the value is empty. */
    private void fact(LinearLayout into, int label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout line = row();
        TextView l = text(13, 0x80FFFFFF, regular);
        l.setText(label);
        line.addView(l, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(13, 0xE6FFFFFF, regular);
        v.setText(value);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (into.getChildCount() > 0) lp.topMargin = dp(6);
        into.addView(line, lp);
    }

    /** A pill like the header's: white with dark text when focused, glass otherwise. */
    private TextView detailsButton(String label) {
        TextView b = text(14, Color.WHITE, medium);
        b.setText(label);
        b.setFocusable(true);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setOnFocusChangeListener((v, has) -> {
            GradientDrawable bg = rounded(has ? 0xF2FFFFFF : 0x29FFFFFF, dp(18));
            if (!has) bg.setStroke(Math.max(1, dp(1) / 2), 0x40FFFFFF);
            v.setBackground(bg);
            ((TextView) v).setTextColor(has ? 0xFF0B0D12 : Color.WHITE);
        });
        b.getOnFocusChangeListener().onFocusChange(b, false);
        return b;
    }

    /**
     * The drawer's panel: nearly clear glass with a thin light edge and a soft shadow around it. The shadow is drawn
     * outside the panel only, so the glass stays see-through; the parent lets children draw past their bounds.
     */
    private static final class GlassBand extends Drawable {
        private final float radius;
        private final android.graphics.Paint shadow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path outline = new android.graphics.Path();
        private final android.graphics.RectF box = new android.graphics.RectF();

        GlassBand(float radius, float blur, float drop, float stroke) {
            this.radius = radius;
            shadow.setColor(0xFF000000);
            shadow.setShadowLayer(blur, 0, drop, 0xA6000000);
            fill.setColor(0x0FFFFFFF);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            edge.setColor(0x38FFFFFF);
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas canvas) {
            box.set(getBounds());
            outline.reset();
            outline.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(outline);
            canvas.drawRoundRect(box, radius, radius, shadow);
            canvas.restore();
            canvas.drawRoundRect(box, radius, radius, fill);
            canvas.drawRoundRect(box, radius, radius, edge);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void menu(String title, String[] labels, Runnable[] actions) {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column();
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        GradientDrawable bg = rounded(0xF2171A22, dp(20));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x26FFFFFF);
        box.setBackground(bg);
        TextView head = text(13, 0x99FFFFFF, medium);
        head.setText(title);
        head.setAllCaps(true);
        head.setLetterSpacing(0.12f);
        head.setPadding(dp(10), 0, dp(10), dp(10));
        box.addView(head, wrap());
        for (int i = 0; i < labels.length; i++) {
            TextView item = text(17, Color.WHITE, regular);
            item.setText(labels[i]);
            item.setFocusable(true);
            item.setPadding(dp(14), dp(11), dp(14), dp(11));
            item.setBackground(rounded(0x00000000, dp(12)));
            item.setOnFocusChangeListener((v, has) -> {
                v.setBackground(rounded(has ? 0xF2FFFFFF : 0x00000000, dp(12)));
                ((TextView) v).setTextColor(has ? 0xFF0B0D12 : Color.WHITE);
            });
            Runnable run = actions[i];
            item.setOnClickListener(v -> {
                dialog.dismiss();
                run.run();
            });
            box.addView(item, new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        dialog.setContentView(box);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
        if (box.getChildCount() > 1) box.getChildAt(1).requestFocus();
    }

    private void launch(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(act, act.getString(R.string.cant_open, pkg), Toast.LENGTH_SHORT).show();
        }
    }

    private void appInfo(String pkg) {
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // No app info screen on this TV.
        }
    }

    /** The focused tile grows and gets a white ring; the others sit flat. */
    private void lift(View v, boolean has, float scale) {
        float s = has ? scale : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(10) : 0).setDuration(150).start();
        Drawable fg = v.getForeground();
        if (fg instanceof GradientDrawable) ((GradientDrawable) fg).setStroke(has ? dp(3) : 0, Color.WHITE);
    }

    private GradientDrawable ring(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(radius);
        return g;
    }

    private static GradientDrawable rounded(int colour, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radius);
        return g;
    }

    private TextView label(String s) {
        TextView t = text(15, 0xCCFFFFFF, medium);
        t.setText(s);
        t.setLetterSpacing(0.04f);
        return t;
    }

    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(26);
        lp.leftMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams rowParams(int heightDp) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
    }

    private TextView text(int sp, int colour, Typeface face) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(colour);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private Typeface font(int id) {
        try {
            return act.getResources().getFont(id);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
