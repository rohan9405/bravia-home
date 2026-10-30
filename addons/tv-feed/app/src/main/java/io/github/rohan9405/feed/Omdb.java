package io.github.rohan9405.feed;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;

/**
 * IMDb ratings, looked up the way the StreamRatings extension does: this TV's own cache, then the StreamRatings shared
 * cache that the extension uses too, then OMDb directly while the shared cache is down (omdbapi.com; IMDb has no free
 * API, and a free key allows 1,000 lookups a day). Every answer is kept with the title's year and release date, and
 * stays fresh as long as {@link CachePolicy} says (3 to 30 days by age), so a refresh asks only for titles that are
 * new or due. When nothing can be reached, or the refresh's budget is spent, the last rating known is shown.
 */
final class Omdb {
    static final String PREFS = "omdb";
    static final String KEY = "api_key";

    private final SharedPreferences prefs;
    private final String apiKey;
    private int budget;

    Omdb(Context ctx, int budget) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        apiKey = prefs.getString(KEY, "").trim();
        this.budget = budget;
    }

    static void setKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key == null ? "" : key.trim()).apply();
    }

    static boolean hasKey(Context ctx) {
        return !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim().isEmpty();
    }

    /** One cached answer. */
    static final class Entry {
        final boolean found;
        final String rating;
        final String year;
        final String released;
        /** "Comedy, Drama", empty when unknown. */
        final String genre;
        /** OMDb's Type: "movie", "series" or "episode"; empty when unknown. */
        final String type;
        final long savedMs;
        /** False for entries cached before genre and type were kept: they are asked for again once. */
        final boolean complete;

        Entry(boolean found, String rating, String year, String released, String genre, String type, long savedMs) {
            this(found, rating, year, released, genre, type, savedMs, true);
        }

        private Entry(boolean found, String rating, String year, String released, String genre, String type, long savedMs,
                      boolean complete) {
            this.found = found;
            this.rating = rating;
            this.year = year;
            this.released = released;
            this.genre = genre == null || "N/A".equals(genre) ? "" : genre;
            this.type = type == null || "N/A".equals(type) ? "" : type;
            this.savedMs = savedMs;
            this.complete = complete;
        }

        boolean fresh(long nowMs) {
            LocalDate today = LocalDate.now();
            return complete && CachePolicy.fresh(savedMs, CachePolicy.ttlDays(found, year, released, today), nowMs);
        }

        String toJson() {
            try {
                return new JSONObject().put("found", found).put("rating", rating).put("year", year)
                        .put("released", released).put("genre", genre).put("type", type).put("ts", savedMs).toString();
            } catch (JSONException e) {
                return "";
            }
        }

        /** Null for a missing or unreadable entry (including the old "8.1@ms" format), which is then asked for again. */
        static Entry parse(String s) {
            if (s == null || !s.startsWith("{")) return null;
            try {
                JSONObject o = new JSONObject(s);
                return new Entry(o.optBoolean("found"), o.optString("rating"), o.optString("year"),
                        o.optString("released"), o.optString("genre"), o.optString("type"), o.optLong("ts"),
                        !o.optBoolean("found") || o.has("genre"));
            } catch (JSONException e) {
                return null;
            }
        }
    }

    /** "8.1", or empty when there is no key, no match, or no rating yet. */
    String rating(String title, String year) {
        Entry e = lookup(title, year);
        return e == null ? "" : e.rating;
    }

    /**
     * The rating, genre and type, or null when there is no key or nothing is known yet. A stale or older entry is
     * returned when the lookup cannot be made (no network, budget spent), so a tile keeps what it had.
     */
    Entry lookup(String title, String year) {
        String clean = Pick.cleanTitle(title);
        if (apiKey.isEmpty() || clean.isEmpty()) return null;
        String cacheKey = "r:" + clean.toLowerCase(Locale.ROOT) + "|" + year;
        Entry cached = Entry.parse(prefs.getString(cacheKey, null));
        long now = System.currentTimeMillis();
        if (cached != null && cached.fresh(now)) return cached;
        if (budget <= 0) return cached;
        budget--;
        Entry fetched = fetch(clean, year, now);
        if (fetched == null) return cached;   // network trouble: keep the last answer, cache nothing
        prefs.edit().putString(cacheKey, fetched.toJson()).apply();
        return fetched;
    }

    /**
     * The StreamRatings order: the shared cache first, which answers from ratings any user fetched and otherwise asks
     * OMDb with this key and keeps the answer for everyone; OMDb directly only while the shared cache is down. Null
     * on a network error, or when the key or its daily limit was refused (never cached).
     */
    private Entry fetch(String title, String year, long now) {
        try {
            return shared(title, year);
        } catch (SharedCacheDown down) {
            // Fall through to OMDb.
        } catch (Exception refused) {
            return null;
        }
        try {
            Entry e = query(title, year, now);
            if (!e.found && !year.isEmpty()) e = query(title, "", now);
            return e;
        } catch (Exception e) {
            return null;
        }
    }

    /** The StreamRatings shared cache (worker/README.md in StreamRatings); the extension uses the same URL. */
    static final String SHARED_CACHE_URL = "https://streamratings-cache.rohan-trades-trump.workers.dev";

    static final class SharedCacheDown extends Exception {
        SharedCacheDown(String message) {
            super(message);
        }
    }

    private Entry shared(String title, String year) throws Exception {
        Uri.Builder b = Uri.parse(SHARED_CACHE_URL + "/v1/rating").buildUpon().appendQueryParameter("title", title);
        if (!year.isEmpty()) b.appendQueryParameter("year", year);
        Net.Response r;
        try {
            r = Net.request(b.build().toString(), "X-OMDb-Key", apiKey, 64 * 1024);
        } catch (java.io.IOException e) {
            throw new SharedCacheDown(e.getMessage());
        }
        return parseShared(r.code, r.body);
    }

    /**
     * {@code {"data":{"found":true,"year":"2020–","released":"05 Mar 2020","imdbRating":"9.0",...},"ts":...}}. The
     * entry keeps the cache's ts, when OMDb was asked, so its freshness matches what every other user sees. A 4xx
     * with an error is OMDb refusing this key or its limit; anything else means the cache is down.
     */
    static Entry parseShared(int code, String body) throws Exception {
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (JSONException e) {
            o = null;
        }
        JSONObject data = o == null ? null : o.optJSONObject("data");
        if (code / 100 == 2 && data != null) {
            boolean found = data.optBoolean("found");
            String rating = data.isNull("imdbRating") ? "" : data.optString("imdbRating");
            return new Entry(found, rating.matches("\\d(\\.\\d)?|10(\\.0)?") ? rating : "",
                    data.isNull("year") ? "" : data.optString("year"),
                    data.isNull("released") ? "" : data.optString("released"),
                    data.isNull("genre") ? "" : data.optString("genre"),
                    data.isNull("type") ? "" : data.optString("type"),
                    o.optLong("ts", System.currentTimeMillis()));
        }
        if (code >= 400 && code < 500 && o != null && o.has("error")) throw new IllegalStateException(o.optString("error"));
        throw new SharedCacheDown("HTTP " + code);
    }

    private Entry query(String title, String year, long now) throws Exception {
        Uri.Builder b = Uri.parse("https://www.omdbapi.com/").buildUpon()
                .appendQueryParameter("apikey", apiKey).appendQueryParameter("t", title);
        if (!year.isEmpty()) b.appendQueryParameter("y", year);
        JSONObject o = new JSONObject(new String(Net.get(b.build().toString(), 64 * 1024), StandardCharsets.UTF_8));
        if (!"True".equalsIgnoreCase(o.optString("Response"))) {
            // "Invalid API key!" or "Request limit reached!" is not an answer about the title: don't cache it.
            String error = o.optString("Error").toLowerCase(Locale.ROOT);
            if (error.contains("key") || error.contains("limit")) throw new IllegalStateException(error);
            return new Entry(false, "", "", "", "", "", now);
        }
        String rating = o.optString("imdbRating");
        return new Entry(true, rating.matches("\\d(\\.\\d)?|10(\\.0)?") ? rating : "",
                o.optString("Year"), o.optString("Released"), o.optString("Genre"), o.optString("Type"), now);
    }
}
