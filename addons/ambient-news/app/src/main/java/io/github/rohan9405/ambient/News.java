package io.github.rohan9405.ambient;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the news from two sources, each switchable in Settings: Google News's topic pages for India, which list
 * each story's title, publisher, time and article address (the photo then comes from the article page's main image,
 * og:image, since Google's own copies are thumbnails of 739 px at most), and TheNewsAPI, whose stories carry their
 * photo and their website, named as its publisher by {@link #publisher} when the list knows it. Plain Java, so the JVM
 * tests can run it.
 */
final class News {
    static final String INDIA = "india", WORLD = "world", BUSINESS = "business", TECH = "tech", SPORTS = "sports",
            ENTERTAINMENT = "entertainment", SCIENCE = "science", HEALTH = "health";
    static final String[] TOPICS = {INDIA, WORLD, BUSINESS, TECH, SPORTS, ENTERTAINMENT, SCIENCE, HEALTH};
    /** On until Settings says otherwise; the rest start off. */
    static final java.util.Set<String> ON_AT_FIRST = java.util.Set.of(INDIA, BUSINESS, TECH);
    static final String GOOGLE = "google", THENEWS = "thenews";

    /** One story: what the screensaver shows, and where its photo comes from. */
    static final class Story {
        /** source: the publisher's name ("The Economic Times"); from: GOOGLE or THENEWS. */
        String topic = "", source = "", title = "", link = "", image = "", from = "";
        /** Epoch ms; 0 when unknown. */
        long published;
    }

    /** Google News's topic page for India, in English. */
    static String topicPage(String topic) {
        String section = switch (topic) {
            case INDIA -> "NATION";
            case TECH -> "TECHNOLOGY";
            default -> topic.toUpperCase(java.util.Locale.ROOT);   // WORLD, BUSINESS, SPORTS, ENTERTAINMENT, SCIENCE, HEALTH
        };
        return "https://news.google.com/headlines/section/topic/" + section + "?hl=en-IN&gl=IN&ceid=IN:en";
    }

    // Each story starts with a link whose tracking attribute carries, in base64, a JSON array holding the article's
    // own address. After it, before the next story: the title (the first linked text), the source and the time.
    private static final Pattern STORY = Pattern.compile("jslog=\"95014; 5:([A-Za-z0-9+/=]+);");
    private static final Pattern TITLE = Pattern.compile("<a [^>]*href=\"\\./read/[^\"]*\"[^>]*>([^<]{8,})</a>");
    private static final Pattern SOURCE = Pattern.compile("data-n-tid=\"9\">([^<]+)<");
    private static final Pattern TIME = Pattern.compile("<time[^>]+datetime=\"([^\"]+)\"");
    private static final Pattern URL_IN_JSON = Pattern.compile("\"(https?://[^\"]+)\"");

    private News() {
    }

    /** The page's stories in Google's order, each article once; ones without a title or an address are left out. */
    static List<Story> parseTopicPage(String html, String topic) {
        List<Story> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<int[]> marks = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        Matcher m = STORY.matcher(html);
        while (m.find()) {
            marks.add(new int[] {m.start(), m.end()});
            payloads.add(m.group(1));
        }
        for (int k = 0; k < marks.size(); k++) {
            String link = articleUrl(payloads.get(k));
            if (link.isEmpty() || !seen.add(link)) continue;
            int from = marks.get(k)[1];
            int to = Math.min(k + 1 < marks.size() ? marks.get(k + 1)[0] : html.length(), from + 15000);
            String seg = html.substring(from, to);
            Matcher title = TITLE.matcher(seg);
            if (!title.find()) continue;
            Story s = new Story();
            s.topic = topic;
            s.from = GOOGLE;
            s.link = link;
            s.title = text(title.group(1));
            Matcher src = SOURCE.matcher(seg);
            if (src.find()) s.source = text(src.group(1));
            Matcher time = TIME.matcher(seg);
            if (time.find()) s.published = isoDate(time.group(1));
            if (!s.title.isEmpty()) out.add(s);
        }
        return out;
    }

    /** TheNewsAPI's top stories for a topic, in English: India's, except World (everywhere's general news). */
    static String theNewsUrl(String topic, String token) {
        String category = switch (topic) {
            case INDIA, WORLD -> "general";
            default -> topic;   // business, tech, sports, entertainment, science, health: TheNewsAPI's own names
        };
        try {
            return "https://api.thenewsapi.com/v1/news/top?" + (WORLD.equals(topic) ? "" : "locale=in&") + "language=en&categories="
                    + category + "&api_token=" + java.net.URLEncoder.encode(token, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** TheNewsAPI's answer as stories, each with its photo and publisher (or website). */
    static List<Story> parseTheNews(String json, String topic) {
        List<Story> out = new ArrayList<>();
        try {
            org.json.JSONArray data = new org.json.JSONObject(json).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                org.json.JSONObject a = data.optJSONObject(i);
                if (a == null) continue;
                Story s = new Story();
                s.topic = topic;
                s.from = THENEWS;
                s.title = text(a.optString("title"));
                s.link = a.optString("url");
                s.image = a.isNull("image_url") ? "" : a.optString("image_url");
                s.source = publisher(a.optString("source"));
                s.published = isoDate(a.optString("published_at"));
                if (!s.title.isEmpty() && s.link.startsWith("http")) out.add(s);
            }
        } catch (org.json.JSONException e) {
            // Not TheNewsAPI's answer (an error page): no stories from it this time.
        }
        return out;
    }

    /** Website -> publisher, for the sites TheNewsAPI names most in India; others ask the article page. */
    private static final java.util.Map<String, String> PUBLISHERS = new java.util.HashMap<>();

    static {
        String[][] known = {
                {"economictimes.indiatimes.com", "The Economic Times"}, {"timesofindia.indiatimes.com", "The Times of India"},
                {"thehindu.com", "The Hindu"}, {"thehindubusinessline.com", "BusinessLine"}, {"hindustantimes.com", "Hindustan Times"},
                {"indianexpress.com", "The Indian Express"}, {"ndtv.com", "NDTV"}, {"ndtvprofit.com", "NDTV Profit"},
                {"livemint.com", "Mint"}, {"moneycontrol.com", "Moneycontrol"}, {"business-standard.com", "Business Standard"},
                {"financialexpress.com", "Financial Express"}, {"indiatoday.in", "India Today"}, {"news18.com", "News18"},
                {"firstpost.com", "Firstpost"}, {"theprint.in", "ThePrint"}, {"scroll.in", "Scroll"}, {"thewire.in", "The Wire"},
                {"deccanherald.com", "Deccan Herald"}, {"telegraphindia.com", "The Telegraph"}, {"rediff.com", "Rediff"},
                {"medianama.com", "MediaNama"}, {"nextbigwhat.com", "NextBigWhat"}, {"inc42.com", "Inc42"}, {"yourstory.com", "YourStory"},
                {"thelogicalindian.com", "The Logical Indian"}, {"zeebiz.com", "Zee Business"}, {"cnbctv18.com", "CNBC-TV18"},
                {"reuters.com", "Reuters"}, {"bbc.com", "BBC"}, {"bbc.co.uk", "BBC"}, {"cnbc.com", "CNBC"}, {"bloomberg.com", "Bloomberg"},
                {"techcrunch.com", "TechCrunch"}, {"theverge.com", "The Verge"}, {"gadgets360.com", "Gadgets 360"}};
        for (String[] k : known) PUBLISHERS.put(k[0], k[1]);
    }

    /** The publisher's name for a website from the list above ("economictimes.indiatimes.com" -> "The Economic Times"), else the website. */
    static String publisher(String site) {
        String host = site.toLowerCase(java.util.Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("/.*$", "")
                .replaceFirst("^(www|m|amp)\\.", "");
        String known = PUBLISHERS.get(host);
        return known != null ? known : host;
    }

    /** The article address inside a story's base64 tracking payload; empty when there is none. */
    static String articleUrl(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(pad(base64)), java.nio.charset.StandardCharsets.UTF_8);
            Matcher u = URL_IN_JSON.matcher(json);
            return u.find() ? u.group(1).replace("\\u003d", "=").replace("\\u0026", "&") : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String pad(String s) {
        int extra = s.length() % 4;
        return extra == 0 ? s : s + "====".substring(extra);
    }

    /** The article page's main image (og:image, else twitter:image); empty when it names none. */
    static String mainImage(String html) {
        for (String key : new String[] {"og:image", "twitter:image"}) {
            Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + Pattern.quote(key)
                    + "[\"'][^>]*content=[\"']([^\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) return fullSize(text(m.group(1)));
            m = Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"']" + Pattern.quote(key)
                    + "[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) return fullSize(text(m.group(1)));
        }
        return "";
    }

    /** A WordPress upload asked for at a smaller width ("…jpg?w=799") is asked for at its full size. */
    static String fullSize(String url) {
        int q = url.indexOf('?');
        if (q > 0 && url.contains("/wp-content/uploads/")) return url.substring(0, q);
        return url;
    }

    /** Page text as plain text: tags dropped, entities decoded, spaces collapsed. */
    static String text(String raw) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").matcher(raw.replaceAll("<[^>]+>", " "));
        StringBuffer out = new StringBuffer();   // appendReplacement(StringBuilder) is API 34
        while (m.find()) {
            String e = m.group(1), r;
            if (e.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            else r = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                default -> " ";
            };
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** "2026-10-31T04:10:27Z" or TheNewsAPI's "2026-10-31T04:10:48.000000Z" as epoch ms; 0 when unreadable. */
    static long isoDate(String s) {
        try {
            return OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
