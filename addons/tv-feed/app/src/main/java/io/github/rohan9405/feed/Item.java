package io.github.rohan9405.feed;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One tile. {@link #section} says where Home puts it: {@code hero} (new in the last 10 days, for the header, ranked
 * per app), {@code continue} (Continue watching), or {@code app} (that app's top 10, for its tab).
 */
final class Item {
    static final String HERO = "hero";
    static final String CONTINUE = "continue";
    static final String APP = "app";
    /** A service's titles by genre (TMDB), for Home's genre rows; not shown on their own. */
    static final String POOL = "pool";

    String section = APP;
    /** The app the tile belongs to: its tab, the logo beside a header title. */
    String tab = "";
    String row = "";
    int rowOrder;
    String title = "";
    String subtitle = "";
    /** The app's row a YouTube video came from, while a refresh orders them; not stored. */
    String subtitleRow = "";
    String overview = "";
    /** "movie", "tv" or empty. */
    String kind = "";
    String year = "";
    /** "Comedy, Drama", empty when unknown. */
    String genre = "";
    /** "2026-10-23": first release (a new season: its premiere); empty when unknown. */
    String released = "";
    /** IMDb rating such as "8.1", empty when unknown. */
    String rating = "";
    /** 0 to 100, -1 unknown. */
    int progress = -1;
    /** Where the artwork lives (usually https); downloaded into the cache. */
    String posterSource = "";
    /** Cache file names served by FeedProvider, empty until downloaded. */
    String poster = "";
    String backdrop = "";
    String intent = "";
    String pkg = "";
    boolean hero;
    double popularity;
    /** Continue watching: when it was last watched (epoch ms), 0 when the app does not say. */
    long engaged;
    /** "movie:1339713" or "tv:331750": the TMDB title, for Home's Read more; empty when unknown (an app's own row). */
    String tmdb = "";
    /** Artwork without the title on it (TMDB backdrops): Home writes the title over it. App posters carry their own. */
    boolean plainArt;

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("section", section).put("tab", tab).put("row", row).put("row_order", rowOrder)
                .put("title", title).put("subtitle", subtitle).put("overview", overview).put("kind", kind)
                .put("year", year).put("released", released).put("genre", genre).put("rating", rating).put("progress", progress)
                .put("poster_source", posterSource).put("poster", poster).put("backdrop", backdrop)
                .put("intent", intent).put("package", pkg).put("hero", hero).put("popularity", popularity)
                .put("plain_art", plainArt).put("engaged", engaged).put("tmdb", tmdb);
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.section = o.optString("section", APP);
        i.tab = o.optString("tab");
        i.row = o.optString("row");
        i.rowOrder = o.optInt("row_order");
        i.title = o.optString("title");
        i.subtitle = o.optString("subtitle");
        i.overview = o.optString("overview");
        i.kind = o.optString("kind");
        i.year = o.optString("year");
        i.genre = o.optString("genre");
        i.released = o.optString("released");
        i.rating = o.optString("rating");
        i.progress = o.optInt("progress", -1);
        i.posterSource = o.optString("poster_source");
        i.poster = o.optString("poster");
        i.backdrop = o.optString("backdrop");
        i.intent = o.optString("intent");
        i.pkg = o.optString("package");
        i.hero = o.optBoolean("hero");
        i.popularity = o.optDouble("popularity", 0);
        i.plainArt = o.optBoolean("plain_art");
        i.engaged = o.optLong("engaged");
        i.tmdb = o.optString("tmdb");
        return i;
    }

    /** For ranking: the IMDb rating as a number, 0 when there is none. */
    double ratingValue() {
        try {
            return rating.isEmpty() ? 0 : Double.parseDouble(rating);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
