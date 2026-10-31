package io.github.rohan9405.feed;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * TMDB (themoviedb.org) for what the apps do not publish: titles new on each streaming service in India in the last
 * 14 days, for the header, and what is popular on a service whose app publishes no rows (Netflix, SonyLIV). The token
 * comes from the Mac (my-tv/api-keys.sh). Answers are kept on disk for as long as they stay true (6 hours for what is
 * new, a day for genre lists, a month for a film's first release date), so a refresh after a night off asks again only
 * for what can have changed. A host that resets the connection (some Indian ISPs do, for api.themoviedb.org) is retried
 * on the other. Safe to use from several threads.
 */
final class Tmdb {
    static final String PREFS = "tmdb";
    static final String KEY = "token";
    /** How far back "new" goes; a service that releases little looks further (see {@link Providers#newDays}). */
    static final int NEW_DAYS = 14;
    private static final long HOUR_MS = 60L * 60 * 1000;
    /** What is new or popular moves through the day; a genre's best-known titles, slowly; a film's first date, never. */
    private static final long LIST_MS = 6 * HOUR_MS, SEASONS_MS = 12 * HOUR_MS, POOL_MS = 24 * HOUR_MS,
            FIXED_MS = 30 * 24 * HOUR_MS;
    private static final String[] HOSTS = {"api.tmdb.org", "api.themoviedb.org"};
    static final String IMAGES = "https://image.tmdb.org/t/p/";

    /** One film or series. */
    static final class Title {
        long id;
        String kind = "";        // "movie" or "tv"
        /** "Drama, Thriller" from TMDB's genre ids. */
        String genres = "";
        /** "Season 3" when the title counts as new because a season of it premiered in the window. */
        String season = "";
        /** "2026-10-23": when it first came out anywhere (a new season: when the season premiered). */
        String released = "";
        String name = "";
        String year = "";
        String overview = "";
        String backdrop = "";    // full image URL, 16:9
        double popularity;
    }

    /** Read more's facts about one title; "" or 0 where TMDB has none. */
    static final class Details {
        String overview = "", tagline = "", released = "", certification = "", language = "", status = "";
        /** Names, comma-separated, best known first. */
        String cast = "", directors = "", creators = "";
        /** Minutes: a film's length, a series' usual episode. */
        int runtime, episodeRuntime, seasons, episodes;
    }

    private final String token;
    private final File cache;

    Tmdb(Context ctx) {
        token = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim();
        cache = new File(ctx.getCacheDir(), "tmdb");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();
    }

    static void setToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, token == null ? "" : token.trim()).apply();
    }

    static boolean hasToken(Context ctx) {
        return !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").trim().isEmpty();
    }

    boolean usable() {
        return !token.isEmpty();
    }

    /**
     * New on the service in the last {@value #NEW_DAYS} days: series that premiered, films that premiered, and films
     * that came out digitally in India. A digital release carries its streaming date, not when the film first came
     * out (Baar Baar Dekho, 2016, reached Netflix in October 2026), so each film's original date is looked up and
     * kept: the caller decides from it whether the film is recent. Null when TMDB could not be reached.
     */
    List<Title> newReleases(int provider, LocalDate today) {
        String from = today.minusDays(Providers.newDays(provider)).toString(), to = today.toString();
        JSONObject series = discover("tv", provider, LIST_MS, "first_air_date.gte", from, "first_air_date.lte", to);
        JSONObject films = discover("movie", provider, LIST_MS, "primary_release_date.gte", from, "primary_release_date.lte", to);
        JSONObject digital = discover("movie", provider, LIST_MS, "release_date.gte", from, "release_date.lte", to,
                "region", Providers.REGION, "with_release_type", "4|6");
        if (series == null || films == null || digital == null) return null;   // TMDB did not answer: not "nothing new"
        List<Title> out = new ArrayList<>();
        add(out, series, "tv");
        add(out, films, "movie");
        add(out, digital, "movie");
        List<Title> titles = dedupe(out);
        for (Title t : titles) {
            if (!"movie".equals(t.kind)) continue;
            JSONObject film = get("/3/movie/" + t.id, FIXED_MS);
            String first = film == null ? "" : film.optString("release_date");
            if (!first.isEmpty()) {
                t.released = first;
                t.year = Pick.year(first);
            }
        }
        return titles;
    }

    /**
     * Series whose new season premiered in the last {@value #NEW_DAYS} days. A series with only a new episode of an old
     * season (a long-running show airing weekly) is not new and stays out: each candidate's seasons are checked.
     */
    List<Title> newSeasons(int provider, LocalDate today) {
        LocalDate from = today.minusDays(Providers.newDays(provider));
        JSONObject page = discover("tv", provider, LIST_MS, "air_date.gte", from.toString(), "air_date.lte", today.toString());
        if (page == null) return null;   // TMDB did not answer
        List<Title> airing = new ArrayList<>();
        add(airing, page, "tv");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.min(20, airing.size()); i++) {
            Title t = airing.get(i);
            JSONObject show = get("/3/tv/" + t.id, SEASONS_MS);
            JSONArray seasons = show == null ? null : show.optJSONArray("seasons");
            for (int k = 0; seasons != null && k < seasons.length(); k++) {
                JSONObject season = seasons.optJSONObject(k);
                if (season == null || season.optInt("season_number") < 1) continue;
                LocalDate premiere = date(season.optString("air_date"));
                if (premiere != null && !premiere.isBefore(from) && !premiere.isAfter(today)) {
                    t.season = "Season " + season.optInt("season_number");
                    // The season is what is new: its year, not the show's first ("Season 23 · 2026", not "2005").
                    t.year = String.valueOf(premiere.getYear());
                    t.released = premiere.toString();
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    private static LocalDate date(String s) {
        try {
            return s == null || s.isEmpty() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Home's genre tabs and TMDB's ids for them: {name, movie id, series id}; 0 where TMDB has no such genre. */
    static final Object[][] POOL_GENRES = {
            {"Drama", 18, 18}, {"Comedy", 35, 35}, {"Action", 28, 10759}, {"Thriller", 53, 0}, {"Crime", 80, 80},
            {"Sci-Fi", 878, 10765}, {"Romance", 10749, 0}, {"Horror", 27, 0}, {"Mystery", 9648, 9648},
            {"Animation", 16, 16}, {"Family", 10751, 10751}, {"Documentary", 99, 99}};

    /**
     * A service's best-known titles in each of {@link #POOL_GENRES}, {@code perGenre} each (series and films mixed),
     * every title once: the pool Home's genre rows draw from, so every drawer app has a say in every genre.
     */
    List<Title> genrePool(int provider, int perGenre) {
        genres(new JSONArray());   // load the names once, before anything else reads them
        List<Title> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object[] g : POOL_GENRES) {
            List<Title> tv = new ArrayList<>(), movies = new ArrayList<>();
            if ((int) g[2] > 0) add(tv, discover("tv", provider, POOL_MS, "with_genres", String.valueOf(g[2]), "vote_count.gte", "20"), "tv");
            if ((int) g[1] > 0) add(movies, discover("movie", provider, POOL_MS, "with_genres", String.valueOf(g[1]), "vote_count.gte", "20"), "movie");
            int taken = 0;
            for (int i = 0; taken < perGenre && (i < tv.size() || i < movies.size()); i++) {
                for (Title t : new Title[] {i < tv.size() ? tv.get(i) : null, i < movies.size() ? movies.get(i) : null}) {
                    if (t == null || taken >= perGenre) continue;
                    if (seen.add(t.kind + t.id)) out.add(t);
                    taken++;
                }
            }
        }
        return out;
    }

    /** TMDB genre id -> name, movies and series together; empty when TMDB cannot be reached. */
    private java.util.Map<Integer, String> genreNames;

    private synchronized String genres(JSONArray ids) {
        if (ids == null) return "";
        if (genreNames == null) {
            genreNames = new java.util.HashMap<>();
            for (String kind : new String[] {"movie", "tv"}) {
                JSONObject list = get("/3/genre/" + kind + "/list", FIXED_MS);
                JSONArray g = list == null ? null : list.optJSONArray("genres");
                for (int i = 0; g != null && i < g.length(); i++) {
                    JSONObject o = g.optJSONObject(i);
                    if (o != null) genreNames.put(o.optInt("id"), o.optString("name"));
                }
            }
        }
        if (ids.length() == 0) return "";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ids.length() && names.size() < 3; i++) {
            String n = genreNames.get(ids.optInt(i));
            if (n != null && !n.isEmpty() && !names.contains(n)) names.add(n);
        }
        return android.text.TextUtils.join(", ", names);
    }

    /** A film's or series' details with its credits and ratings, kept a week; null for a bad id or no answer. */
    Details details(String kindAndId) {
        int colon = kindAndId.indexOf(':');
        if (colon < 0) return null;
        String kind = kindAndId.substring(0, colon), id = kindAndId.substring(colon + 1);
        if (!id.matches("\\d+") || !("movie".equals(kind) || "tv".equals(kind))) return null;
        String extra = "movie".equals(kind) ? "credits,release_dates" : "aggregate_credits,content_ratings";
        JSONObject o = get("/3/" + kind + "/" + id + "?append_to_response=" + extra, 7 * 24 * HOUR_MS);
        return o == null ? null : parseDetails(kind, o);
    }

    /** The title's IMDb id ("tt123") for "movie:123" or "tv:456", kept a year; empty when TMDB has none. */
    String imdbId(String kindAndId) {
        if (kindAndId == null || !kindAndId.matches("(movie|tv):\\d+")) return "";
        JSONObject o = get("/3/" + kindAndId.replace(':', '/') + "/external_ids", 365 * 24 * HOUR_MS);
        String id = o == null ? "" : o.optString("imdb_id");
        return id.matches("tt\\d+") ? id : "";
    }

        static Details parseDetails(String kind, JSONObject o) {
        Details d = new Details();
        boolean film = "movie".equals(kind);
        d.overview = o.optString("overview");
        d.tagline = o.optString("tagline");
        d.released = o.optString(film ? "release_date" : "first_air_date");
        d.status = film ? "" : o.optString("status");
        d.runtime = film ? o.optInt("runtime") : 0;
        if (!film) {
            JSONArray each = o.optJSONArray("episode_run_time");
            d.episodeRuntime = each != null && each.length() > 0 ? each.optInt(0)
                    : o.optJSONObject("last_episode_to_air") == null ? 0 : o.optJSONObject("last_episode_to_air").optInt("runtime");
            d.seasons = o.optInt("number_of_seasons");
            d.episodes = o.optInt("number_of_episodes");
        }
        d.language = language(o);
        d.certification = film ? filmRating(o.optJSONObject("release_dates")) : seriesRating(o.optJSONObject("content_ratings"));
        JSONObject credits = o.optJSONObject(film ? "credits" : "aggregate_credits");
        d.cast = names(credits == null ? null : credits.optJSONArray("cast"), null, 6);
        d.directors = film ? names(credits == null ? null : credits.optJSONArray("crew"), "Director", 2) : "";
        d.creators = film ? "" : names(o.optJSONArray("created_by"), null, 3);
        return d;
    }

    /** "Hindi": the original language's English name, from the title's own languages, else Java's. */
    private static String language(JSONObject o) {
        String code = o.optString("original_language");
        JSONArray spoken = o.optJSONArray("spoken_languages");
        for (int i = 0; spoken != null && i < spoken.length(); i++) {
            JSONObject l = spoken.optJSONObject(i);
            if (l != null && code.equals(l.optString("iso_639_1")) && !l.optString("english_name").isEmpty()) return l.optString("english_name");
        }
        return code.isEmpty() ? "" : new java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH);
    }

    /** India's age rating for a film ("UA 16+"), from its Indian releases; empty when it has none. */
    private static String filmRating(JSONObject releases) {
        JSONArray countries = releases == null ? null : releases.optJSONArray("results");
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            if (c == null || !Providers.REGION.equals(c.optString("iso_3166_1"))) continue;
            JSONArray dates = c.optJSONArray("release_dates");
            for (int k = 0; dates != null && k < dates.length(); k++) {
                String cert = dates.optJSONObject(k) == null ? "" : dates.optJSONObject(k).optString("certification").trim();
                if (!cert.isEmpty()) return cert;
            }
        }
        return "";
    }

    private static String seriesRating(JSONObject ratings) {
        JSONArray countries = ratings == null ? null : ratings.optJSONArray("results");
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            if (c != null && Providers.REGION.equals(c.optString("iso_3166_1"))) return c.optString("rating").trim();
        }
        return "";
    }

    /** Up to max names from a credits list, in TMDB's order (best known first); only that job's when job is set. */
    private static String names(JSONArray people, String job, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; people != null && i < people.length() && out.size() < max; i++) {
            JSONObject p = people.optJSONObject(i);
            if (p == null || (job != null && !job.equals(p.optString("job")))) continue;
            String n = p.optString("name");
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return String.join(", ", out);
    }

    /** The most popular series and films on a service, mixed. */
    List<Title> popular(int provider) {
        List<Title> tv = new ArrayList<>(), movies = new ArrayList<>();
        add(tv, discover("tv", provider, LIST_MS), "tv");
        add(movies, discover("movie", provider, LIST_MS), "movie");
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(tv.size(), movies.size()); i++) {
            if (i < tv.size()) out.add(tv.get(i));
            if (i < movies.size()) out.add(movies.get(i));
        }
        return out;
    }

    private JSONObject discover(String kind, int provider, long maxAgeMs, String... params) {
        Uri.Builder b = new Uri.Builder().path("/3/discover/" + kind)
                .appendQueryParameter("watch_region", Providers.REGION)
                .appendQueryParameter("with_watch_providers", String.valueOf(provider))
                .appendQueryParameter("with_watch_monetization_types", "flatrate")
                .appendQueryParameter("sort_by", "popularity.desc")
                .appendQueryParameter("include_adult", "false");
        for (int i = 0; i + 1 < params.length; i += 2) b.appendQueryParameter(params[i], params[i + 1]);
        return get(b.build().toString(), maxAgeMs);
    }

    private void add(List<Title> out, JSONObject page, String kind) {
        JSONArray results = page == null ? null : page.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            Title t = new Title();
            t.id = r.optLong("id");
            t.kind = kind;
            t.genres = genres(r.optJSONArray("genre_ids"));
            t.name = r.optString("tv".equals(kind) ? "name" : "title");
            String date = r.optString("tv".equals(kind) ? "first_air_date" : "release_date");
            t.year = Pick.year(date);
            t.released = date;
            t.overview = r.optString("overview");
            String backdrop = r.isNull("backdrop_path") ? "" : r.optString("backdrop_path");
            t.backdrop = backdrop.isEmpty() ? "" : IMAGES + "w1280" + backdrop;
            t.popularity = r.optDouble("popularity", 0);
            if (!t.name.isEmpty()) out.add(t);
        }
    }

    private static List<Title> dedupe(List<Title> in) {
        List<Title> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Title t : in) if (seen.add(t.kind + "|" + t.name.toLowerCase())) out.add(t);
        return out;
    }

    /** A cached answer younger than maxAgeMs, else the API (both hosts, twice each); null when nothing answers. */
    private JSONObject get(String pathAndQuery, long maxAgeMs) {
        if (token.isEmpty()) return null;
        File f = new File(cache, Images.hash(pathAndQuery) + ".json");
        if (System.currentTimeMillis() - f.lastModified() < maxAgeMs && f.length() > 0) {
            try (InputStream in = new FileInputStream(f)) {
                return new JSONObject(new String(Net.read(in, 1 << 20), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Fetch again.
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String host : HOSTS) {
                try {
                    Net.Response r = Net.request("https://" + host + pathAndQuery, "Authorization", "Bearer " + token, 1 << 20);
                    if (r.code / 100 != 2) {
                        if (r.code == 401) return null;   // a bad token: no point in retrying
                        continue;
                    }
                    JSONObject o = new JSONObject(r.body);
                    File tmp = new File(f.getPath() + "." + Thread.currentThread().getId());
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(r.body.getBytes(StandardCharsets.UTF_8));
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(f);
                    } catch (IOException ignored) {
                        // Served without caching.
                    }
                    return o;
                } catch (Exception e) {
                    // Reset or timeout: the other host, then once more.
                }
            }
        }
        return null;
    }
}
