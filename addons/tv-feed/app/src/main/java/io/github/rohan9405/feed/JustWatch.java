package io.github.rohan9405.feed;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JustWatch (justwatch.com), the header's fallback when TMDB cannot be reached or has no token: what each service
 * added in India day by day, so a title that reached the app weeks after its premiere counts too. This is
 * the GraphQL API behind JustWatch's own website, not a published one, so it can change without notice: anything
 * other than a clean answer makes {@link #newOn} return null and the caller uses TMDB instead. Answers are kept on
 * disk: an hour for today, three hours for yesterday (JustWatch fills a day in as it goes), a week for older days.
 */
final class JustWatch {
    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String IMAGES = "https://images.justwatch.com";
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final int PER_DAY = 100;
    private static final String QUERY = "query($country: Country!, $date: Date!, $filter: TitleFilter, $first: Int!) {"
            + " newTitles(country: $country, date: $date, filter: $filter, first: $first, pageType: NEW, priceDrops: false) {"
            + " edges { node { objectType"
            + " content(country: $country, language: \"en\") { title originalReleaseDate shortDescription"
            + " genres { translation(language: \"en\") } backdrops { backdropUrl } externalIds { tmdbId } }"
            + " ... on Season { show { content(country: $country, language: \"en\") { title backdrops { backdropUrl }"
            + " externalIds { tmdbId } } } } }"
            + " newOffer(platform: WEB) { monetizationType } } } }";

    private static final String SEARCH = "query($q: String!, $pkg: String!) {"
            + " popularTitles(country: IN, first: 8, filter: {searchQuery: $q, packages: [$pkg]}) {"
            + " edges { node { objectType content(country: IN, language: \"en\") { externalIds { tmdbId } }"
            + " offers(country: IN, platform: ANDROID_TV, filter: {packages: [$pkg]}) {"
            + " standardWebURL deeplinkURL(platform: ANDROID_TV) } } } } }";
    private static final long LINK_MS = 30 * 24 * HOUR_MS, NO_LINK_MS = 24 * HOUR_MS;

    private final File cache;

    JustWatch(Context ctx) {
        cache = new File(ctx.getCacheDir(), "justwatch");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();
    }

    /**
     * Films and seasons the service added in the last {@code days} days that first came out (a season: premiered)
     * recently enough for the header, newest addition first; null when JustWatch did not answer every day cleanly,
     * or does not know the service.
     */
    List<Tmdb.Title> newOn(int provider, LocalDate today, int days) {
        String pkg = Providers.justWatch(provider);
        if (pkg == null) return null;
        List<Tmdb.Title> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int d = 0; d <= days; d++) {
            LocalDate day = today.minusDays(d);
            long maxAge = d == 0 ? HOUR_MS : d == 1 ? 3 * HOUR_MS : 7 * 24 * HOUR_MS;
            JSONObject page = get(pkg, day, maxAge);
            if (page == null) return null;
            JSONArray edges = page.optJSONObject("data") == null ? null
                    : page.optJSONObject("data").optJSONObject("newTitles") == null ? null
                    : page.optJSONObject("data").optJSONObject("newTitles").optJSONArray("edges");
            if (edges == null) return null;
            for (int i = 0; i < edges.length(); i++) {
                Tmdb.Title t = title(edges.optJSONObject(i));
                if (t == null || !Pick.recentEnough(t.released, "", today)) continue;
                if (seen.add(t.kind + "|" + t.name.toLowerCase(java.util.Locale.ROOT) + "|" + t.season)) {
                    t.popularity = 10000 - out.size();   // JustWatch's own order, newest day first
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * The title's own page in its app, as an intent URI, for a TMDB title ("movie:123", "tv:456") the app did not
     * publish; empty when JustWatch does not have it on that service. Found by searching the title on the service and
     * matching the TMDB id; kept a month (a miss, a day).
     */
    String link(String pkg, String tmdb, String title) {
        Integer provider = Providers.TMDB.get(pkg);
        String jw = provider == null ? null : Providers.justWatch(provider);
        if (jw == null || tmdb == null || !tmdb.matches("(movie|tv):\\d+") || title == null || title.isEmpty()) return "";
        File f = new File(cache, "link-" + jw + "-" + tmdb.replace(':', '-') + ".txt");
        long age = System.currentTimeMillis() - f.lastModified();
        if (f.exists() && age < (f.length() > 0 ? LINK_MS : NO_LINK_MS)) {
            try (InputStream in = new FileInputStream(f)) {
                return new String(Net.read(in, 1 << 14), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Ask again.
            }
        }
        String link;
        try {
            JSONObject vars = new JSONObject().put("q", title).put("pkg", jw);
            link = pick(new JSONObject(post(new JSONObject().put("query", SEARCH).put("variables", vars).toString())), pkg, tmdb, title);
        } catch (Exception e) {
            return "";   // offline or changed: not remembered, so asked again next time
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(link.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Served without caching.
        }
        return link;
    }

    /** The search answer's title with this TMDB id, as an intent for its page in the app; empty when none. */
    static String pick(JSONObject answer, String pkg, String tmdb, String title) {
        JSONObject data = answer.optJSONObject("data");
        JSONObject titles = data == null ? null : data.optJSONObject("popularTitles");
        JSONArray edges = titles == null ? null : titles.optJSONArray("edges");
        String type = tmdb.startsWith("movie:") ? "MOVIE" : "SHOW", id = tmdb.substring(tmdb.indexOf(':') + 1);
        for (int i = 0; edges != null && i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i) == null ? null : edges.optJSONObject(i).optJSONObject("node");
            if (node == null || !type.equals(node.optString("objectType"))) continue;
            JSONObject content = node.optJSONObject("content");
            JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
            if (ids == null || !id.equals(ids.optString("tmdbId"))) continue;
            JSONArray offers = node.optJSONArray("offers");
            JSONObject o = offers == null || offers.length() == 0 ? null : offers.optJSONObject(0);
            return o == null ? "" : page(pkg, o.optString("standardWebURL"), o.optString("deeplinkURL"), title);
        }
        return "";
    }

    /**
     * How each app opens a title's page, as tried on the BRAVIA: Prime Video and Netflix take their website's title
     * address; Apple TV its show or film address (JustWatch gives a show's first episode, which would play); JioHotstar
     * has no page-only link (every one plays), so its own link, which plays the title.
     */
    static String page(String pkg, String web, String deeplink, String title) {
        if ("in.startv.hotstar".equals(pkg)) return deeplink.startsWith("intent:") ? deeplink : "";
        if (!web.startsWith("https://")) return deeplink.startsWith("intent:") ? deeplink : "";
        String url = web;
        if (url.startsWith("https://tv.apple.com/")) {
            java.util.regex.Matcher show = java.util.regex.Pattern.compile("[?&]showId=([\\w.]+)").matcher(url);
            int q = url.indexOf('?');
            if (url.contains("/episode/") && show.find()) {
                String region = url.substring("https://tv.apple.com/".length()).split("/")[0];
                url = "https://tv.apple.com/" + region + "/show/" + slug(title) + "/" + show.group(1);
            } else if (q > 0) {
                url = url.substring(0, q);
            }
        }
        return "intent://" + url.substring("https://".length()) + "#Intent;scheme=https;package=" + pkg + ";end";
    }

    private static String slug(String title) {
        String s = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "show" : s;
    }

    /** One addition as a header title; null for what is not a film or a season, or comes without a title or date. */
    static Tmdb.Title title(JSONObject edge) {
        JSONObject node = edge == null ? null : edge.optJSONObject("node");
        JSONObject content = node == null ? null : node.optJSONObject("content");
        if (content == null) return null;
        String type = node.optString("objectType");
        Tmdb.Title t = new Tmdb.Title();
        t.released = content.optString("originalReleaseDate");
        t.year = Pick.year(t.released);
        t.overview = content.optString("shortDescription");
        t.genres = genres(content.optJSONArray("genres"));
        String backdrop = backdrop(content.optJSONArray("backdrops"));
        if ("MOVIE".equals(type)) {
            t.kind = "movie";
            t.name = content.optString("title");
            t.id = id(content);
        } else if ("SHOW_SEASON".equals(type) || "SHOW".equals(type)) {
            JSONObject show = node.optJSONObject("show") == null ? null : node.optJSONObject("show").optJSONObject("content");
            t.kind = "tv";
            t.name = show != null ? show.optString("title") : content.optString("title");
            t.id = id(show != null ? show : content);
            // "Season 1" of a new series is the series itself; a later one is what makes it new.
            String season = "SHOW_SEASON".equals(type) ? content.optString("title") : "";
            t.season = season.matches("Season ([2-9]|\\d\\d+)") ? season : "";
            if (backdrop.isEmpty() && show != null) backdrop = backdrop(show.optJSONArray("backdrops"));
        } else {
            return null;
        }
        t.backdrop = backdrop;
        return t.name.isEmpty() || t.released.isEmpty() ? null : t;
    }

    private static long id(JSONObject content) {
        JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
        String tmdb = ids == null || ids.isNull("tmdbId") ? "" : ids.optString("tmdbId");
        int colon = tmdb.indexOf(':');   // a season's is "show:season"
        try {
            return Long.parseLong(colon < 0 ? tmdb : tmdb.substring(0, colon));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String genres(JSONArray g) {
        List<String> names = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && names.size() < 3; i++) {
            JSONObject o = g.optJSONObject(i);
            String n = o == null ? "" : o.optString("translation");
            if (!n.isEmpty() && !names.contains(n)) names.add(n);
        }
        return String.join(", ", names);
    }

    /** The first backdrop at 1440 px wide: "/backdrop/354548090/{profile}/the-court.{format}". */
    private static String backdrop(JSONArray b) {
        JSONObject first = b == null || b.length() == 0 ? null : b.optJSONObject(0);
        String path = first == null ? "" : first.optString("backdropUrl");
        return path.isEmpty() ? "" : IMAGES + path.replace("{profile}", "s1440").replace("{format}", "jpg");
    }

    /** One day's additions to one service: from the disk when young enough, else JustWatch; null when it fails. */
    private JSONObject get(String pkg, LocalDate day, long maxAgeMs) {
        File f = new File(cache, pkg + "-" + day + ".json");
        if (System.currentTimeMillis() - f.lastModified() < maxAgeMs && f.length() > 0) {
            try (InputStream in = new FileInputStream(f)) {
                return new JSONObject(new String(Net.read(in, 1 << 21), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Fetch again.
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JSONObject vars = new JSONObject().put("country", Providers.REGION).put("date", day.toString())
                        .put("first", PER_DAY).put("filter", new JSONObject().put("packages", new JSONArray().put(pkg)));
                String body = post(new JSONObject().put("query", QUERY).put("variables", vars).toString());
                JSONObject o = new JSONObject(body);
                if (o.has("errors") || o.optJSONObject("data") == null) return null;   // the API changed: TMDB
                File tmp = new File(f.getPath() + "." + Thread.currentThread().getId());
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(f);
                } catch (IOException ignored) {
                    // Served without caching.
                }
                return o;
            } catch (Exception e) {
                // Reset or timeout: once more.
            }
        }
        return null;
    }

    private static String post(String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "CleanYourTV-Feed/1.0");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return new String(Net.read(in, 1 << 21), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
