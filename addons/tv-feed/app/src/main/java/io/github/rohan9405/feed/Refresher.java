package io.github.rohan9405.feed;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds Home's three sections and stores them:
 * <ul>
 *   <li><b>continue</b>: Continue watching (Watch Next) from the TV provider, browsers left out;</li>
 *   <li><b>app</b>: each app's top 10, from the rows it publishes, or from TMDB's most popular on that service in
 *       India when it publishes none (Netflix, SonyLIV);</li>
 *   <li><b>hero</b>: for each streaming service, the titles new in the last 10 days (TMDB), best IMDb rating first.
 *       Home picks 5 per drawer app from these and tops up to 20.</li>
 * </ul>
 * Runs only when Home asks and the last refresh is stale, or when the Feed screen says Refresh: no background service.
 */
final class Refresher {
    /** Not in the SDK as a constant: TvProvider defines it (docs/watch-next.md). */
    static final String READ_TV = "android.permission.READ_TV_LISTINGS";
    static final long STALE_MS = 20L * 60 * 1000;
    private static final int MAX_CONTINUE = 20;
    static final int TOP = 10;
    /** Header candidates kept per app, ranked: Home shows 5, its best 2 and 3 drawn from the rest each refresh. */
    private static final int HERO_PER_APP = 25;
    private static final int RATING_BUDGET = 200;
    static final String YOUTUBE = "com.google.android.youtube.tv";
    /** YouTube's videos kept for the header, in YouTube's own order, across all its rows. */
    private static final int YOUTUBE_HERO = 40;
    /** Titles per service and genre in the genre pool. */
    private static final int POOL_PER_GENRE = 4;

    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Asked for while a refresh was running (an app joined the drawer): run once more when it ends. */
    private static final AtomicBoolean AGAIN = new AtomicBoolean();
    /** How far the running refresh is, 0 to 100; -1 when none runs. Home shows it under the date. */
    private static volatile int progress = -1;
    private static final String DRAWER_PREFS = "drawer", DRAWER = "apps";

    private Refresher() {
    }

    /** Starts a refresh unless one is running; onDone (any thread) once the new rows are stored. */
    static void kick(Context ctx, Runnable onDone) {
        Context app = ctx.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        ONE.execute(() -> {
            try {
                long started = android.os.SystemClock.elapsedRealtime();
                step(app, 0);
                Store.put(app, run(app));
                android.util.Log.i("Feed", "refresh took " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
                app.getContentResolver().notifyChange(FeedProvider.itemsUri(), null);
            } finally {
                RUNNING.set(false);
                step(app, -1);
                if (onDone != null) onDone.run();
                if (AGAIN.getAndSet(false)) kick(app, null);
            }
        });
    }

    /** A refresh now, or straight after the one running, which started before the drawer changed. */
    static void kickSoon(Context ctx) {
        if (RUNNING.get()) AGAIN.set(true);
        kick(ctx, null);
    }

    /** Stores Home's drawer apps; true when one TMDB knows about has just joined, so it has no rows yet. */
    static synchronized boolean setDrawer(Context ctx, String[] apps) {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(DRAWER_PREFS, Context.MODE_PRIVATE);
        Set<String> old = prefs.getStringSet(DRAWER, null);
        Set<String> now = new HashSet<>(java.util.Arrays.asList(apps));
        if (now.equals(old)) return false;
        prefs.edit().putStringSet(DRAWER, now).apply();
        if (old == null) return false;   // the first time Home says: the rows already there came from every app
        for (String pkg : now) if (!old.contains(pkg) && Providers.TMDB.containsKey(pkg)) return true;
        return false;
    }

    /** The apps whose TMDB rows are wanted: the drawer's, or every one before Home has said. */
    static boolean wanted(Context ctx, String pkg) {
        Set<String> drawer = ctx.getSharedPreferences(DRAWER_PREFS, Context.MODE_PRIVATE).getStringSet(DRAWER, null);
        return drawer == null || drawer.contains(pkg);
    }

    static int progress() {
        return progress;
    }

    /** Moves the progress on (never back, so parallel steps cannot make it jump about) and tells Home. */
    private static void step(Context ctx, int percent) {
        int now = progress;
        if (percent >= 0 && now >= percent) return;
        progress = percent;
        ctx.getContentResolver().notifyChange(FeedProvider.statusUri(), null);
    }

    static boolean running() {
        return RUNNING.get();
    }

    private static Store.Snapshot run(Context ctx) {
        long now = System.currentTimeMillis();
        if (Demo.on(ctx)) return Demo.snapshot(ctx);
        if (ctx.checkSelfPermission(READ_TV) != PackageManager.PERMISSION_GRANTED) {
            return new Store.Snapshot(now, "READ_TV_LISTINGS is not granted", Collections.emptyList(), Collections.emptyMap());
        }
        ContentResolver cr = ctx.getContentResolver();
        PackageManager pm = ctx.getPackageManager();
        Map<String, int[]> probe = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        StringBuilder error = new StringBuilder();
        String continueRow = ctx.getString(R.string.continue_watching);

        // Continue watching, most recently watched first.
        List<long[]> order = new ArrayList<>();
        List<Item> cont = new ArrayList<>();
        try (Cursor c = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 0);
                if (Providers.browser(pkg)) continue;
                int progress = Pick.progress(lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
                        lng(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS));
                long engaged = lng(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                if (!Pick.keepContinue(engaged, now, progress)) continue;
                Item i = program(c, pkg);
                if (i == null) continue;
                i.section = Item.CONTINUE;
                i.tab = pkg;
                i.row = continueRow;
                i.progress = progress;
                i.engaged = engaged;
                i.subtitle = Pick.subtitle(str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                        str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                        str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE));
                order.add(new long[] {engaged, cont.size()});
                cont.add(i);
            }
        } catch (RuntimeException e) {
            error.append("Watch Next: ").append(e.getClass().getSimpleName()).append(". ");
        }
        Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));
        for (long[] o : order) {
            if (items.size() < MAX_CONTINUE) items.add(cont.get((int) o[1]));
        }

        // Each app's own recommendations, the first ten it publishes across its channels.
        Set<Long> previewChannels = new HashSet<>();
        Map<Long, String> rowNames = new HashMap<>();
        Map<String, List<String>> rowsByApp = new LinkedHashMap<>();
        try (Cursor c = cr.query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(str(c, TvContract.Channels.COLUMN_TYPE))) continue;
                count(probe, str(c, TvContract.Channels.COLUMN_PACKAGE_NAME), 2);
                previewChannels.add(lng(c, TvContract.Channels._ID));
                rowNames.put(lng(c, TvContract.Channels._ID), str(c, TvContract.Channels.COLUMN_DISPLAY_NAME));
                rowsByApp.computeIfAbsent(str(c, TvContract.Channels.COLUMN_PACKAGE_NAME), k -> new ArrayList<>())
                        .add(str(c, TvContract.Channels.COLUMN_DISPLAY_NAME));
            }
        } catch (RuntimeException e) {
            error.append("Channels: ").append(e.getClass().getSimpleName()).append(". ");
        }
        for (Map.Entry<String, List<String>> e : rowsByApp.entrySet()) {
            android.util.Log.i("Feed", "rows " + e.getKey() + " (" + e.getValue().size() + "): " + new java.util.LinkedHashSet<>(e.getValue()));
        }
        Map<String, List<Item>> published = new LinkedHashMap<>();
        List<Item> youTubeHero = new ArrayList<>();
        Map<String, Integer> youTubeRows = new LinkedHashMap<>();
        try (Cursor c = cr.query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME);
                count(probe, pkg, 1);
                if (Providers.browser(pkg) || pkg.isEmpty()) continue;
                // YouTube's videos are the header's when YouTube is focused: every row's, not just the first ten.
                if (YOUTUBE.equals(pkg) && youTubeHero.size() < YOUTUBE_HERO) {
                    Item v = program(c, pkg);
                    if (v != null && !has(youTubeHero, v.title)) {
                        String author = str(c, TvContract.PreviewPrograms.COLUMN_AUTHOR);
                        String row = rowNames.get(lng(c, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID));
                        v.section = Item.HERO;
                        v.tab = pkg;
                        v.subtitle = !author.isEmpty() ? author : row == null ? "" : row;
                        v.subtitleRow = row == null ? "" : row;
                        v.rowOrder = youTubeRank(row);
                        // New videos only: "Recommended for you" is mostly years-old music videos.
                        if (v.rowOrder < Integer.MAX_VALUE && Pick.newVideo(v.overview)) youTubeHero.add(v);
                        youTubeRows.merge(row == null ? "?" : row, 1, Integer::sum);
                    }
                }
                List<Item> list = published.get(pkg);
                if (list == null) published.put(pkg, list = new ArrayList<>());
                if (list.size() >= TOP) continue;
                Item i = program(c, pkg);
                if (i == null || has(list, i.title)) continue;
                i.section = Item.APP;
                i.tab = pkg;
                i.rowOrder = list.size() + 1;
                list.add(i);
            }
        } catch (RuntimeException e) {
            error.append("Preview: ").append(e.getClass().getSimpleName()).append(". ");
        }
        for (List<Item> l : published.values()) items.addAll(l);
        if (!youTubeHero.isEmpty()) android.util.Log.i("Feed", "YouTube rows " + youTubeRows);
        if (wanted(ctx, YOUTUBE)) items.addAll(youTubeFeed(youTubeHero));

        // What is on the TV itself is ready now: show it with the last refresh's TMDB rows while those are fetched.
        showEarly(ctx, items, published.keySet(), probe);
        step(ctx, 10);

        // TMDB: the header's new releases for every service installed here, and a top 10 for the ones that publish none.
        // Only the drawer's apps: Home shows TMDB rows for no other. One thread per service: each asks TMDB dozens of
        // things, and one at a time that took minutes.
        Tmdb tmdb = new Tmdb(ctx);
        if (tmdb.usable()) {
            LocalDate today = LocalDate.now();
            List<Item> local = new ArrayList<>(items);
            ExecutorService services = Executors.newFixedThreadPool(Providers.TMDB.size());
            List<Future<List<Item>>> found = new ArrayList<>();
            for (Map.Entry<String, Integer> p : Providers.TMDB.entrySet()) {
                String pkg = p.getKey();
                if (!installed(pm, pkg) || !wanted(ctx, pkg)) continue;
                found.add(services.submit(() -> fromService(tmdb, pkg, p.getValue(), today, local, published.containsKey(pkg))));
            }
            android.util.Log.i("Feed", "TMDB for " + found.size() + " drawer apps");
            for (int n = 0; n < found.size(); n++) {
                try {
                    items.addAll(found.get(n).get());
                    step(ctx, 10 + 60 * (n + 1) / found.size());
                } catch (Exception e) {
                    error.append("TMDB: ").append(e.getClass().getSimpleName()).append(". ");
                }
            }
            services.shutdown();
        } else {
            error.append("No TMDB token: the header has no new releases. ");
        }

        // Ratings (the StreamRatings cache), then the header's ranking, then art.
        step(ctx, 70);
        Omdb omdb = new Omdb(ctx, RATING_BUDGET);
        int rated = 0;
        for (Item i : items) {
            step(ctx, 70 + 15 * rated++ / Math.max(1, items.size()));
            if (!Pick.rateable(i.pkg)) continue;
            Omdb.Entry e = omdb.lookup(i.title, i.year);
            if (e == null) continue;
            i.rating = e.rating;
            if (i.genre.isEmpty()) i.genre = e.genre;
            if (i.kind.isEmpty()) i.kind = "series".equals(e.type) ? "tv" : "movie".equals(e.type) ? "movie" : "";
        }
        Map<String, Integer> heroCount = new LinkedHashMap<>();
        for (Item i : items) if (Item.HERO.equals(i.section)) heroCount.merge(i.tab, 1, Integer::sum);
        android.util.Log.i("Feed", "header candidates " + heroCount);
        items = keepHeroTop(items);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> jobs = new ArrayList<>();
        for (Item i : items) {
            jobs.add(pool.submit(() -> {
                if (Item.HERO.equals(i.section)) i.backdrop = Images.cache(ctx, i.posterSource, true);
                else i.poster = Images.cache(ctx, i.posterSource, false);
            }));
        }
        for (int n = 0; n < jobs.size(); n++) {
            try {
                step(ctx, 85 + 14 * n / Math.max(1, jobs.size()));
                jobs.get(n).get();
            } catch (Exception ignored) {
                // That tile shows its title on a plain card.
            }
        }
        pool.shutdown();
        Set<String> keep = new HashSet<>();
        for (Item i : items) {
            keep.add(i.poster);
            keep.add(i.backdrop);
        }
        Images.prune(ctx, keep);
        return new Store.Snapshot(now, error.toString().trim(), items, probe);
    }

    /**
     * YouTube's rows by how much they are your feed: new uploads from your subscriptions and your recommendations
     * first, then what is trending; music videos are left out. Unknown rows (YouTube renaming one) go last.
     */
    static int youTubeRank(String row) {
        String r = row == null ? "" : row.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("music")) return Integer.MAX_VALUE;
        if (r.contains("subscription") || r.contains("recommended")) return 0;
        if (r.contains("trending")) return 1;
        return 2;
    }

    /** The feed rows' videos taken in turn (a subscription, a recommendation, ...), then trending, then the rest. */
    static List<Item> youTubeFeed(List<Item> videos) {
        Map<String, List<Item>> byRow = new LinkedHashMap<>();
        for (Item v : videos) {
            List<Item> l = byRow.get(v.rowOrder + "|" + v.subtitleRow);
            if (l == null) byRow.put(v.rowOrder + "|" + v.subtitleRow, l = new ArrayList<>());
            l.add(v);
        }
        List<Item> out = new ArrayList<>();
        for (int rank = 0; rank <= 2; rank++) {
            List<List<Item>> rows = new ArrayList<>();
            for (Map.Entry<String, List<Item>> e : byRow.entrySet()) if (e.getKey().startsWith(rank + "|")) rows.add(e.getValue());
            for (int i = 0; ; i++) {
                boolean any = false;
                for (List<Item> l : rows) if (i < l.size()) { out.add(l.get(i)); any = true; }
                if (!any) break;
            }
        }
        for (int i = 0; i < out.size(); i++) out.get(i).rowOrder = i + 1;
        return out;
    }

    /** One service's TMDB rows: new releases and seasons for the header, its genre pool, and a top 10 if it publishes none. */
    private static List<Item> fromService(Tmdb tmdb, String pkg, int provider, LocalDate today, List<Item> local,
            boolean publishes) {
        List<Item> out = new ArrayList<>();
        // Premieres in the window, and series whose new season premiered in it; never a weekly episode of an old one.
        List<Tmdb.Title> fresh = tmdb.newReleases(provider, today);
        for (Tmdb.Title t : tmdb.newSeasons(provider, today)) {
            boolean dup = false;
            for (Tmdb.Title f : fresh) dup |= f.id == t.id && f.kind.equals(t.kind);
            if (!dup) fresh.add(t);
        }
        for (Tmdb.Title t : fresh) {
            if (Pick.recentEnough(t.released, t.season, today)) out.add(fromTmdb(t, pkg, Item.HERO, 0));
        }
        for (Tmdb.Title t : tmdb.genrePool(provider, POOL_PER_GENRE)) {
            if (!hasTitle(local, pkg, t.name) && !hasTitle(out, pkg, t.name)) out.add(fromTmdb(t, pkg, Item.POOL, 0));
        }
        if (!publishes) {
            List<Tmdb.Title> top = tmdb.popular(provider);
            for (int i = 0; i < Math.min(TOP, top.size()); i++) out.add(fromTmdb(top.get(i), pkg, Item.APP, i + 1));
        }
        return out;
    }

    /**
     * Stores this refresh's Continue watching and app rows with the last refresh's TMDB rows, and tells Home, before the
     * slow part. The time stays the last full refresh's, so a refresh cut short is run again.
     */
    private static void showEarly(Context ctx, List<Item> local, Set<String> publishing, Map<String, int[]> probe) {
        Store.Snapshot last = Store.get(ctx);
        if (last.items.isEmpty()) return;
        List<Item> early = new ArrayList<>(local);
        for (Item i : last.items) {
            boolean tmdb = Item.HERO.equals(i.section) || Item.POOL.equals(i.section)
                    || (Item.APP.equals(i.section) && !publishing.contains(i.tab));
            if (tmdb && wanted(ctx, i.tab)) early.add(i);
        }
        for (Item i : local) i.poster = Images.cache(ctx, i.posterSource, false);
        Store.put(ctx, new Store.Snapshot(last.refreshedMs, last.error, early, probe));
        ctx.getContentResolver().notifyChange(FeedProvider.itemsUri(), null);
    }

    /** The header candidates ranked per app (best IMDb rating, then most popular), {@value #HERO_PER_APP} each. */
    static List<Item> keepHeroTop(List<Item> items) {
        Map<String, List<Item>> hero = new LinkedHashMap<>();
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (!Item.HERO.equals(i.section)) {
                out.add(i);
                continue;
            }
            List<Item> l = hero.get(i.tab);
            if (l == null) hero.put(i.tab, l = new ArrayList<>());
            l.add(i);
        }
        for (List<Item> l : hero.values()) {
            // YouTube's order is its own recommendation (and its videos have no rating): kept as it is.
            List<Item> ranked = YOUTUBE.equals(l.get(0).tab) ? l : Pick.rankHero(l, LocalDate.now().getYear());
            int keep = YOUTUBE.equals(l.get(0).tab) ? YOUTUBE_HERO : HERO_PER_APP;
            for (int i = 0; i < Math.min(keep, ranked.size()); i++) {
                ranked.get(i).rowOrder = i + 1;
                out.add(ranked.get(i));
            }
        }
        return out;
    }

    private static Item fromTmdb(Tmdb.Title t, String pkg, String section, int order) {
        Item i = new Item();
        i.section = section;
        i.tab = pkg;
        i.pkg = pkg;
        i.title = t.name;
        i.year = t.year;
        i.kind = t.kind;
        i.overview = t.overview;
        i.genre = t.genres;
        i.released = t.released;
        i.subtitle = t.season;
        i.posterSource = t.backdrop;
        i.plainArt = true;
        i.popularity = t.popularity;
        i.rowOrder = order;
        i.intent = Providers.launchIntent(pkg);
        return i;
    }

    /** The service already has this title in Home (its own top 10, say): the pool does not need it twice. */
    private static boolean hasTitle(List<Item> items, String pkg, String title) {
        for (Item i : items) if (i.pkg.equals(pkg) && i.title.equalsIgnoreCase(title)) return true;
        return false;
    }

    private static boolean has(List<Item> list, String title) {
        for (Item i : list) if (i.title.equalsIgnoreCase(title)) return true;
        return false;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The parts every program row shares; null for a row without a title or a way to open it. */
    private static Item program(Cursor c, String pkg) {
        String title = str(c, TvContract.PreviewPrograms.COLUMN_TITLE);
        String intent = str(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
        if (title.isEmpty() || intent.isEmpty()) return null;
        Item i = new Item();
        i.title = title;
        i.intent = intent;
        i.pkg = pkg;
        i.year = Pick.year(str(c, TvContract.PreviewPrograms.COLUMN_RELEASE_DATE));
        i.overview = str(c, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
        String poster = str(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
        i.posterSource = !poster.isEmpty() ? poster : str(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
        return i;
    }

    private static void count(Map<String, int[]> probe, String pkg, int column) {
        String key = pkg == null || pkg.isEmpty() ? "?" : pkg;
        int[] n = probe.get(key);
        if (n == null) probe.put(key, n = new int[3]);
        n[column]++;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return "";
        String s = c.getString(i);
        return s == null ? "" : s.trim();
    }

    private static long lng(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return 0;
        try {
            return c.getLong(i);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
