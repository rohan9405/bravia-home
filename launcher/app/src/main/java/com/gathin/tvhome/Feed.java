package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.util.LruCache;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * feed_package (config.xml or a theme): what Home shows besides the apps, from an add-on such as Feed
 * (addons/tv-feed): the header's new releases, Continue watching, and each app's top 10. Like {@link GameDay}, the
 * launcher only reads what the add-on publishes, art included, and stays offline: posters are opened through the
 * add-on's own provider, never fetched here.
 */
final class Feed {
    static final String META = "com.gathin.tvhome.feed";
    static final String HERO = "hero";
    static final String CONTINUE = "continue";
    static final String APP = "app";
    /** Each service's titles by genre: only for the genre rows. */
    static final String POOL = "pool";
    /** Never in the header: its rows are videos, not releases. */
    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String[] COLUMNS = {"row", "row_order", "title", "subtitle", "rating", "progress",
            "poster", "backdrop", "intent", "package", "section", "tab", "overview", "kind", "year", "genre", "plain_art", "engaged", "released", "tmdb"};

    /** One tile. */
    static final class Entry {
        final String section, tab, title, subtitle, rating, poster, backdrop, intent, pkg, overview, kind, year;
        /** "Drama, Thriller", empty when unknown. */
        final String genre;
        /** The art has no title on it (TMDB backdrops), so Home writes the title over it. */
        boolean plainArt;
        /** Continue watching: when it was last watched (epoch ms), 0 when the app does not say. */
        long engaged;
        /** "2026-10-23": first release (a new season: its premiere); empty when unknown. */
        String released = "";
        /** "movie:123" or "tv:456": the TMDB title Read more asks about; empty when unknown. */
        String tmdb = "";
        final int progress, order;

        Entry(String section, String tab, int order, String title, String subtitle, String rating, int progress,
              String poster, String backdrop, String intent, String pkg, String overview, String kind, String year,
              String genre) {
            this.section = section == null ? APP : section;
            this.tab = tab == null ? "" : tab;
            this.order = order;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.rating = rating == null ? "" : rating;
            this.progress = progress;
            this.poster = poster == null ? "" : poster;
            this.backdrop = backdrop == null ? "" : backdrop;
            this.intent = intent == null ? "" : intent;
            this.pkg = pkg == null ? "" : pkg;
            this.overview = overview == null ? "" : overview;
            this.kind = kind == null ? "" : kind;
            this.year = year == null ? "" : year;
            this.genre = genre == null ? "" : genre;
        }

        double ratingValue() {
            try {
                return rating.isEmpty() ? 0 : Double.parseDouble(rating);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Artwork without the title on it: Home writes the title over it. */
        boolean plainArt() {
            return plainArt;
        }
    }

    private final Context ctx;
    private final String authority;
    private final Uri uri;
    private final Handler main;
    private final Runnable onChange;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ContentObserver observer;
    private final Uri statusUri;
    private final ContentObserver statusObserver;
    /** The add-on's refresh: progress 0 to 100, or -1 when none runs; when the last one finished (0: never). */
    interface StatusListener {
        void onStatus(int progress, long refreshedMs);
    }
    private StatusListener statusListener;
    private final Uri insightsUri;
    private final ContentObserver insightsObserver;
    /** The insight chip's figures, as the add-on's JSON. */
    interface InsightsListener {
        void onInsights(String json);
    }
    private InsightsListener insightsListener;
    /** Decoded art, in RGB_565: a tile is about 160 KB, so this holds a screen of rows and a few backdrops. */
    private final LruCache<String, Bitmap> art = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private boolean watching;
    private List<Entry> continueRow = Collections.emptyList();
    private Map<String, List<Entry>> tabs = Collections.emptyMap();
    private Map<String, List<Entry>> hero = Collections.emptyMap();
    private Map<String, List<Entry>> pool = Collections.emptyMap();
    private long refreshed;

    private Feed(Context ctx, String authority, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.authority = authority;
        this.uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath("items").build();
        this.main = main;
        this.onChange = onChange;
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
        statusUri = uri.buildUpon().path("status").build();
        insightsUri = uri.buildUpon().path("insights").build();
        insightsObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                readInsights();
            }
        };
        statusObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                readStatus();
            }
        };
    }

    /** Null when the key is empty or the package names no provider of its own in a {@value #META} meta-data. */
    static Feed create(Context ctx, String pkg, Handler main, Runnable onChange) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            return new Feed(ctx, authority.trim(), main, onChange);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The drawer's apps, sent with every read: the add-on fetches TMDB rows for these only. */
    private volatile String[] drawer;

    /** The drawer changed (or Home loaded it): tell the add-on, which starts fetching for an app just added. */
    void setDrawer(List<String> apps) {
        String[] now = apps.toArray(new String[0]);
        boolean changed = drawer != null && !java.util.Arrays.equals(drawer, now);
        drawer = now;
        if (changed && watching) read();
    }

    void setStatusListener(StatusListener l) {
        statusListener = l;
    }

    void setInsightsListener(InsightsListener l) {
        insightsListener = l;
    }

    /** The add-on's insights (it fetches them when they are old); an add-on without them answers nothing. */
    private void readInsights() {
        if (insightsListener == null) return;
        try {
            io.execute(() -> {
                String json;
                try (Cursor c = ctx.getContentResolver().query(insightsUri, null, null, null, null)) {
                    if (c == null || !c.moveToFirst()) return;
                    json = s(c, "json");
                } catch (RuntimeException gone) {
                    return;
                }
                main.post(() -> {
                    if (insightsListener != null) insightsListener.onInsights(json);
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    /** An add-on from before the status existed answers nothing: the listener is not called. */
    private void readStatus() {
        try {
            io.execute(() -> {
                int progress;
                long refreshed;
                try (Cursor c = ctx.getContentResolver().query(statusUri, null, null, null, null)) {
                    if (c == null || !c.moveToFirst()) return;
                    progress = i(c, "progress");
                    int r = c.getColumnIndex("refreshed");
                    refreshed = r < 0 || c.isNull(r) ? 0 : c.getLong(r);
                } catch (RuntimeException gone) {
                    return;
                }
                main.post(() -> {
                    if (statusListener != null) statusListener.onStatus(progress, refreshed);
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    void start() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(statusUri, false, statusObserver);
                ctx.getContentResolver().registerContentObserver(insightsUri, false, insightsObserver);
                ctx.getContentResolver().registerContentObserver(uri, false, observer);
                watching = true;
            } catch (SecurityException e) {
                // The add-on went away; Home keeps what it had.
            }
        }
        read();
        readStatus();
        readInsights();
    }

    void stop() {
        if (watching) {
            ctx.getContentResolver().unregisterContentObserver(observer);
            ctx.getContentResolver().unregisterContentObserver(statusObserver);
            ctx.getContentResolver().unregisterContentObserver(insightsObserver);
        }
        watching = false;
    }

    void shutdown() {
        stop();
        io.shutdownNow();
        art.evictAll();
    }

    List<Entry> continueRow() {
        return continueRow;
    }

    /** package -> its top 10, in rank order. */
    Map<String, List<Entry>> tabs() {
        return tabs;
    }

    /** package -> its titles by genre, for the genre rows. */
    Map<String, List<Entry>> pool() {
        return pool;
    }

    /** When the add-on made these rows (0: unknown): the header's draw changes with it. */
    long refreshed() {
        return refreshed;
    }

    /** package -> its header candidates, best first. */
    Map<String, List<Entry>> heroCandidates() {
        return hero;
    }

    void read() {
        try {
            io.execute(() -> {
                List<Entry> entries = new ArrayList<>();
                long made;
                try (Cursor c = ctx.getContentResolver().query(uri, COLUMNS, null, drawer, null)) {
                    made = c == null ? 0 : c.getExtras().getLong("refreshed");
                    // By name: the add-on may answer with its columns in its own order, or with more of them.
                    while (c != null && c.moveToNext()) {
                        entries.add(new Entry(s(c, "section"), s(c, "tab"), i(c, "row_order"), s(c, "title"),
                                s(c, "subtitle"), s(c, "rating"), i(c, "progress"), s(c, "poster"), s(c, "backdrop"),
                                s(c, "intent"), s(c, "package"), s(c, "overview"), s(c, "kind"), s(c, "year"),
                                s(c, "genre")));
                        entries.get(entries.size() - 1).plainArt = i(c, "plain_art") == 1;
                        int ec = c.getColumnIndex("engaged");
                        entries.get(entries.size() - 1).engaged = ec < 0 || c.isNull(ec) ? 0 : c.getLong(ec);
                        entries.get(entries.size() - 1).released = s(c, "released");
                        entries.get(entries.size() - 1).tmdb = s(c, "tmdb");
                    }
                } catch (RuntimeException gone) {
                    return;
                }
                List<Entry> cont = new ArrayList<>();
                Map<String, List<Entry>> byTab = group(entries, APP);
                Map<String, List<Entry>> byHero = group(entries, HERO);
                Map<String, List<Entry>> byPool = group(entries, POOL);
                for (Entry e : entries) if (CONTINUE.equals(e.section) && usable(e)) cont.add(e);
                main.post(() -> {
                    continueRow = cont;
                    tabs = byTab;
                    hero = byHero;
                    pool = byPool;
                    refreshed = made;
                    onChange.run();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    private static String s(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static int i(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? -1 : c.getInt(i);
    }

    /** One section's tiles by app, each list in its rank order; tiles without a title or a target are dropped. */
    static Map<String, List<Entry>> group(List<Entry> entries, String section) {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!section.equals(e.section) || !usable(e) || e.tab.isEmpty()) continue;
            List<Entry> l = out.get(e.tab);
            if (l == null) out.put(e.tab, l = new ArrayList<>());
            l.add(e);
        }
        for (List<Entry> l : out.values()) Collections.sort(l, (a, b) -> Integer.compare(a.order, b.order));
        return out;
    }

    private static boolean usable(Entry e) {
        return !e.title.isEmpty() && !e.intent.isEmpty();
    }

    /**
     * The header's rotation: up to {@code perApp} of the best from each drawer app (YouTube left out), taken in turn
     * so the apps alternate, then topped up to {@code total} with the best-rated of what is left from those apps.
     */
    /**
     * One app's header: its {@code best} best titles, then titles drawn at random from the rest of its recent releases
     * up to {@code count}, the draw fixed by {@code seed} (the refresh). A title it lists twice shows once.
     */
    static List<Entry> pickApp(List<Entry> ranked, int count, int best, long seed) {
        List<Entry> own = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry e : ranked) if (seen.add(e.title.toLowerCase(java.util.Locale.ROOT))) own.add(e);
        int keep = Math.min(Math.min(best, count), own.size());
        List<Entry> out = new ArrayList<>(own.subList(0, keep));
        List<Entry> others = new ArrayList<>(own.subList(keep, own.size()));
        Collections.shuffle(others, new java.util.Random(seed));
        out.addAll(others.subList(0, Math.min(count - keep, others.size())));
        return out;
    }

    /** Each app's best titles, always in the header; the rest of its perApp are drawn from its other recent releases. */
    static final int HERO_BEST = 2;

    /**
     * The header: perApp titles from each drawer app in turn (not YouTube), then the best of the rest up to total. An
     * app's {@value #HERO_BEST} best always show; its other places go to titles drawn at random from the rest of its
     * recent releases, the draw fixed by {@code seed} (the refresh), so each refresh shows a different mix and Home
     * redrawing in between does not.
     */
    static List<Entry> pickHero(Map<String, List<Entry>> candidates, List<String> drawer, int perApp, int total, long seed) {
        List<List<Entry>> firsts = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        // A title on two services (The Awakening, on Prime Video and JioHotstar) is shown once: the first drawer app's.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String pkg : drawer) {
            if (YOUTUBE.equals(pkg)) continue;
            List<Entry> l = candidates.get(pkg);
            if (l == null || l.isEmpty()) continue;
            List<Entry> own = new ArrayList<>();
            for (Entry e : l) if (seen.add(e.title.toLowerCase(java.util.Locale.ROOT))) own.add(e);
            int best = Math.min(Math.min(HERO_BEST, perApp), own.size());
            List<Entry> others = new ArrayList<>(own.subList(best, own.size()));
            Collections.shuffle(others, new java.util.Random(seed ^ pkg.hashCode()));
            List<Entry> mine = new ArrayList<>(own.subList(0, best));
            int drawn = Math.min(perApp - best, others.size());
            mine.addAll(others.subList(0, drawn));
            firsts.add(mine);
            rest.addAll(others.subList(drawn, others.size()));
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; out.size() < total; i++) {
            boolean any = false;
            for (List<Entry> l : firsts) {
                if (i < l.size() && out.size() < total) {
                    out.add(l.get(i));
                    any = true;
                }
            }
            if (!any) break;
        }
        // The top-up prefers this year's titles too, then the best rated.
        int thisYear = java.time.LocalDate.now().getYear();
        Collections.sort(rest, (a, b) -> {
            int t = Integer.compare(heroTier(a, thisYear), heroTier(b, thisYear));
            return t != 0 ? t : Double.compare(b.ratingValue(), a.ratingValue());
        });
        for (Entry e : rest) {
            if (out.size() >= total) break;
            out.add(e);
        }
        return out;
    }

    /**
     * The genre rows, from the drawer apps' top 10s and their genre pools (YouTube's videos have no genre), each title
     * once. A genre needs {@code minTitles} titles to get a tab; the tabs go most titles first, up to
     * {@code maxGenres}. Each row takes the apps in turn, in drawer order, each app's best-rated first, so one app with
     * many titles cannot fill a row on its own.
     */
    static Map<String, List<Entry>> genreRows(Map<String, List<Entry>> tabs, Map<String, List<Entry>> pool,
                                              List<String> drawer, int perGenre, int maxGenres, int minTitles) {
        // genre -> app -> titles
        Map<String, Map<String, List<Entry>>> byGenre = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String pkg : drawer) {
            List<Entry> all = new ArrayList<>();
            if (tabs.get(pkg) != null) all.addAll(tabs.get(pkg));
            if (pool.get(pkg) != null) all.addAll(pool.get(pkg));
            for (Entry e : all) {
                if (e.genre.isEmpty() || !seen.add(e.title.toLowerCase(java.util.Locale.ROOT))) continue;
                java.util.Set<String> named = new java.util.HashSet<>();
                for (String g : e.genre.split(",")) {
                    String name = genreName(g);
                    if (name.isEmpty() || !named.add(name)) continue;
                    Map<String, List<Entry>> apps = byGenre.get(name);
                    if (apps == null) byGenre.put(name, apps = new LinkedHashMap<>());
                    List<Entry> list = apps.get(pkg);
                    if (list == null) apps.put(pkg, list = new ArrayList<>());
                    list.add(e);
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }
        List<String> names = new ArrayList<>(byGenre.keySet());
        names.removeIf(n -> counts.get(n) < minTitles);
        Collections.sort(names, (a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        for (String name : names) {
            if (out.size() >= maxGenres) break;
            List<List<Entry>> perApp = new ArrayList<>();
            for (List<Entry> l : byGenre.get(name).values()) {
                List<Entry> sorted = new ArrayList<>(l);
                Collections.sort(sorted, (a, b) -> Double.compare(b.ratingValue(), a.ratingValue()));
                perApp.add(sorted);
            }
            List<Entry> row = new ArrayList<>();
            for (int i = 0; row.size() < perGenre; i++) {
                boolean any = false;
                for (List<Entry> l : perApp) {
                    if (i < l.size() && row.size() < perGenre) {
                        row.add(l.get(i));
                        any = true;
                    }
                }
                if (!any) break;
            }
            out.put(name, row);
        }
        return out;
    }

    /** One name per genre: TMDB's series genres pair two ("Action & Adventure"); OMDb names them singly ("Action"). */
    static String genreName(String raw) {
        String g = raw.trim();
        switch (g) {
            case "Action & Adventure":
                return "Action";
            case "Sci-Fi & Fantasy":
            case "Science Fiction":
                return "Sci-Fi";
            case "War & Politics":
                return "War";
            default:
                return g;
        }
    }

    /** 0: first released this year; 1: a new season out this year; 2: anything older. */
    static int heroTier(Entry e, int thisYear) {
        if (!String.valueOf(thisYear).equals(e.year)) return 2;
        return e.subtitle.isEmpty() ? 0 : 1;
    }

    /**
     * A Continue watching title the owner removed stays hidden until it is watched again: a later "last watched" time
     * than when it was removed, or, from an app that gives no time, a different position.
     */
    static boolean stillRemoved(long removedEngaged, int removedProgress, long engaged, int progress) {
        if (engaged > 0 && removedEngaged > 0) return engaged <= removedEngaged;
        return progress == removedProgress;
    }

    /** The art at {@code source}, decoded to about {@code widthPx}; onLoaded on the main thread, null when none. */
    /**
     * Read more: the title's cast, crew and facts, which the add-on fetches when asked (a second or two the first
     * time). Column -> value on the main thread, or null when it has none or is too old to know the question.
     */
    void details(String tmdb, Consumer<Map<String, String>> onDone) {
        Uri q = uri.buildUpon().path("details").appendQueryParameter("tmdb", tmdb).build();
        try {
            io.execute(() -> {
                Map<String, String> out = null;
                try (Cursor c = ctx.getContentResolver().query(q, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        out = new java.util.HashMap<>();
                        for (String col : c.getColumnNames()) out.put(col, s(c, col));
                    }
                } catch (RuntimeException gone) {
                    // The add-on went away: the popup keeps what Home already had.
                }
                Map<String, String> found = out;
                main.post(() -> onDone.accept(found));
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    void loadArt(String source, int widthPx, Consumer<Bitmap> onLoaded) {
        if (!ours(source)) {
            onLoaded.accept(null);
            return;
        }
        String key = source + "@" + widthPx;
        Bitmap cached = art.get(key);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        try {
            io.execute(() -> {
                Bitmap b = decode(Uri.parse(source), widthPx);
                main.post(() -> {
                    if (b != null) art.put(key, b);
                    onLoaded.accept(b);
                });
            });
        } catch (RejectedExecutionException shutDown) {
            onLoaded.accept(null);
        }
    }

    /** Only the add-on's own provider is opened: a row cannot point Home at another app's files. */
    boolean ours(String source) {
        if (source == null || source.isEmpty()) return false;
        Uri u = Uri.parse(source);
        return ContentResolver.SCHEME_CONTENT.equals(u.getScheme()) && authority.equals(u.getAuthority());
    }

    private Bitmap decode(Uri u, int widthPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opens a tile: the app's own intent from its row, kept to that app (no selector, never this launcher), else
     * the app's start screen.
     */
    static boolean open(Context ctx, Entry e) {
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent i = Intent.parseUri(e.intent, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            if (i.getPackage() == null && i.getComponent() == null && !e.pkg.isEmpty()) i.setPackage(e.pkg);
            String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
            if (target != null && !target.equals(ctx.getPackageName())) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall through to the app's start screen.
        }
        if (e.pkg.isEmpty()) return false;
        Intent launch = pm.getLeanbackLaunchIntentForPackage(e.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(e.pkg);
        if (launch == null) return false;
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
