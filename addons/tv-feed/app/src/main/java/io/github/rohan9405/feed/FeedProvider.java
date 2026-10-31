package io.github.rohan9405.feed;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * What Home reads: {@code content://io.github.rohan9405.feed/items}, one row per tile, and
 * {@code .../image/<name>} for the cached art. Exported, but it answers only apps signed with this app's own
 * key (the launcher built on the same computer), because the rows show what you watch.
 *
 * <p>A query also starts a refresh when the last one is older than {@link Refresher#STALE_MS}; the launcher hears
 * about the new rows through notifyChange.
 */
public final class FeedProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.rohan9405.feed";
    static final String[] COLUMNS = {"row", "row_order", "title", "subtitle", "rating", "progress",
            "poster", "backdrop", "intent", "package", "hero", "section", "tab", "overview", "kind", "year", "genre", "plain_art", "engaged", "released", "tmdb"};

    static Uri itemsUri() {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).appendPath("items").build();
    }

    /** The running refresh's progress (-1 when none runs) and when the last one finished, for Home's top bar. */
    static Uri statusUri() {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).appendPath("status").build();
    }

    /** Read more: one title's cast, crew and facts from TMDB, asked for when Home opens the popup. */
    static final String[] DETAILS = {"overview", "tagline", "released", "runtime", "episode_runtime", "seasons", "episodes",
            "certification", "cast", "directors", "creators", "language", "status"};

    /** A row of {@link #DETAILS} for "movie:123" or "tv:456", fetched now (kept a week); null when TMDB has nothing. */
    private Cursor details(String tmdb) {
        Tmdb.Details d = tmdb == null ? null : new Tmdb(getContext()).details(tmdb);
        if (d == null) return null;
        MatrixCursor c = new MatrixCursor(DETAILS);
        c.addRow(new Object[] {d.overview, d.tagline, d.released, d.runtime, d.episodeRuntime, d.seasons, d.episodes,
                d.certification, d.cast, d.directors, d.creators, d.language, d.status});
        return c;
    }

    /** Home's insight card: the INDmoney portfolio's day and the indices, as one JSON row. */
    static Uri insightsUri() {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY).appendPath("insights").build();
    }

    static Uri imageUri(String name) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
                .appendPath("image").appendPath(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (!allowed()) return null;
        if ("status".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(new String[] {"progress", "refreshed"});
            c.addRow(new Object[] {Refresher.progress(), Store.get(getContext()).refreshedMs});
            c.setNotificationUri(getContext().getContentResolver(), statusUri());
            return c;
        }
        if ("details".equals(uri.getLastPathSegment())) return details(uri.getQueryParameter("tmdb"));
        if ("link".equals(uri.getLastPathSegment())) {
            // A title's own page in its app, looked up when Home opens it (JustWatch), for a title the app did not publish.
            MatrixCursor c = new MatrixCursor(new String[] {"intent"});
            c.addRow(new Object[] {new JustWatch(getContext()).link(uri.getQueryParameter("pkg"), uri.getQueryParameter("tmdb"),
                    uri.getQueryParameter("title"))});
            return c;
        }
        if ("insights".equals(uri.getLastPathSegment())) {
            Insights.refreshIfStale(getContext());
            MatrixCursor c = new MatrixCursor(new String[] {"json"});
            c.addRow(new Object[] {Insights.json(getContext())});
            c.setNotificationUri(getContext().getContentResolver(), insightsUri());
            return c;
        }
        if (!"items".equals(uri.getLastPathSegment())) return null;
        Store.Snapshot s = Store.get(getContext());
        // Home sends its drawer apps: TMDB is asked about those only, and straight away for one just added.
        boolean added = args != null && Refresher.setDrawer(getContext(), args);
        if (added) Refresher.kickSoon(getContext());
        else if (System.currentTimeMillis() - s.refreshedMs > Refresher.STALE_MS) Refresher.kick(getContext(), null);
        MatrixCursor c = new MatrixCursor(COLUMNS);
        List<Item> items = s.items;
        for (Item i : items) {
            c.addRow(new Object[] {i.row, i.rowOrder, i.title, i.subtitle, i.rating, i.progress,
                    i.poster.isEmpty() ? "" : imageUri(i.poster).toString(),
                    i.backdrop.isEmpty() ? "" : imageUri(i.backdrop).toString(),
                    i.intent, i.pkg, i.hero ? 1 : 0, i.section, i.tab, i.overview, i.kind, i.year, i.genre, i.plainArt ? 1 : 0, i.engaged, i.released, i.tmdb});
        }
        c.setNotificationUri(getContext().getContentResolver(), itemsUri());
        // When these rows were made: Home draws the header's picks from it, a new draw for each refresh.
        android.os.Bundle extras = new android.os.Bundle();
        extras.putLong("refreshed", s.refreshedMs);
        c.setExtras(extras);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!allowed() || path.size() != 2 || !"image".equals(path.get(0)) || !Images.NAME.matcher(path.get(1)).matches()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(new File(Images.dir(getContext()), path.get(1)), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** This app, or an app signed with the same key. */
    private boolean allowed() {
        String caller;
        try {
            caller = getCallingPackage();
        } catch (SecurityException e) {
            return false;
        }
        if (caller == null) return false;
        String self = getContext().getPackageName();
        return caller.equals(self)
                || getContext().getPackageManager().checkSignatures(self, caller) == PackageManager.SIGNATURE_MATCH;
    }

    @Override
    public String getType(Uri uri) {
        return "image".equals(uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0)) ? "image/jpeg" : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
