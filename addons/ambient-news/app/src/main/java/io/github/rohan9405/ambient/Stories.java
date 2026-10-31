package io.github.rohan9405.ambient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The stories ready for the screensaver: fetched from the sources and topics Settings has on, each with a photo at
 * least 1000 px wide saved on the TV (a story without one is skipped), the last day's only. Kept in files so a reboot
 * starts with them. Fetching runs on the caller's thread; the service gives it a background one.
 */
final class Stories {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Stories kept per topic, and Google News candidates looked at per topic for them. */
    private static final int PER_TOPIC = 15, CANDIDATES = 30;
    private static final int MIN_WIDTH = 1000;
    private static final int THENEWS_PER_REFRESH = 3;
    private static final String UA = "Mozilla/5.0 (Linux; Android 12; BRAVIA) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Safari/537.36";

    /** A story ready to show: the story and its saved photo. */
    static final class Ready {
        final News.Story story;
        final File photo;

        Ready(News.Story story, File photo) {
            this.story = story;
            this.photo = photo;
        }
    }

    private final Context ctx;
    private final File dir, list, shownFile;
    private final Random random = new Random();
    private volatile List<Ready> ready = new ArrayList<>();
    private volatile long updated;
    /** Articles with no usable photo: not asked again for a day. */
    private final Map<String, Long> noPhoto = new HashMap<>();

    Stories(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        dir = new File(this.ctx.getFilesDir(), "photos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        list = new File(this.ctx.getFilesDir(), "stories.json");
        shownFile = new File(this.ctx.getFilesDir(), "shown.json");
        load();
    }

    long updated() {
        return updated;
    }

    /** How many stories are ready from a source (GOOGLE or THENEWS), or from all when from is null. */
    int count(String from) {
        int n = 0;
        for (Ready r : ready) if (from == null || from.equals(r.story.from)) n++;
        return n;
    }

    /** Fetches the topics and sources Settings has on; keeps what it had for a source that does not answer. */
    void refresh(Prefs prefs) {
        long now = System.currentTimeMillis();
        Map<String, Ready> byLink = new HashMap<>();
        for (Ready r : ready) byLink.put(r.story.link, r);
        List<Ready> next = new ArrayList<>();
        Set<String> links = new HashSet<>();
        // TheNewsAPI's free plan allows 100 requests a day: at most THENEWS_PER_REFRESH per (hourly) refresh, 72 a day,
        // the topics taking turns from where the last refresh stopped, and never more than THENEWS_PER_DAY.
        List<String> topics = new ArrayList<>();
        for (String t : News.TOPICS) if (prefs.topic(t)) topics.add(t);
        int turn = topics.isEmpty() ? 0 : prefs.theNewsTurn() % topics.size();
        java.util.Collections.rotate(topics, -turn);
        int asked = 0;
        for (String topic : topics) {
            List<News.Story> candidates = new ArrayList<>();
            boolean googleAnswered = false, theNewsAnswered = false;
            if (prefs.source(News.THENEWS) && !prefs.token().isEmpty() && asked < THENEWS_PER_REFRESH
                    && prefs.theNewsLeft(now) > 0) {
                prefs.theNewsUsed(now);
                asked++;
                String json = text(News.theNewsUrl(topic, prefs.token()), 1 << 20);
                if (json != null) {
                    theNewsAnswered = true;
                    candidates.addAll(News.parseTheNews(json, topic));
                }
            }
            if (prefs.source(News.GOOGLE)) {
                String page = text(News.topicPage(topic), 12 << 20);
                if (page != null) {
                    List<News.Story> g = News.parseTopicPage(page, topic);
                    googleAnswered = !g.isEmpty();
                    for (News.Story s : g) {
                        if (candidates.size() >= CANDIDATES + 3) break;
                        if (s.published == 0 || now - s.published < DAY_MS) candidates.add(s);
                    }
                }
            }
            int kept = 0;
            for (News.Story s : candidates) {
                if (kept >= PER_TOPIC || !links.add(s.link)) continue;
                Ready had = byLink.get(s.link);
                if (had != null && had.photo.exists()) {
                    next.add(had);
                    kept++;
                    continue;
                }
                Long miss = noPhoto.get(s.link);
                if (miss != null && now - miss < DAY_MS) continue;
                File photo = photo(s);
                if (photo == null) {
                    noPhoto.put(s.link, now);
                    continue;
                }
                next.add(new Ready(s, photo));
                kept++;
            }
            // A source that did not answer keeps its stories from last time, while they are still from the last day.
            for (Ready r : ready) {
                boolean silent = (News.GOOGLE.equals(r.story.from) && prefs.source(News.GOOGLE) && !googleAnswered)
                        || (News.THENEWS.equals(r.story.from) && prefs.source(News.THENEWS) && !theNewsAnswered);
                if (silent && topic.equals(r.story.topic) && fresh(r, now) && links.add(r.story.link)) next.add(r);
            }
        }
        if (!topics.isEmpty()) prefs.set("thenews_turn", (turn + asked) % topics.size());
        next.sort((a, b) -> Long.compare(b.story.published, a.story.published));
        ready = next;
        updated = now;
        save();
        prune();
    }

    /** The story to show now: one not shown in the last day, from what Settings has on, newest first with some variety. */
    Ready next(Prefs prefs) {
        long now = System.currentTimeMillis();
        Map<String, Long> shown = shown();
        List<Ready> pool = new ArrayList<>();
        for (Ready r : ready) {
            if (!prefs.topic(r.story.topic) || !prefs.source(r.story.from) || !r.photo.exists() || !fresh(r, now)) continue;
            Long at = shown.get(r.story.link);
            if (at == null || now - at > DAY_MS) pool.add(r);
        }
        if (pool.isEmpty()) {
            // Everything has been shown: start again, newest first.
            for (Ready r : ready) if (prefs.topic(r.story.topic) && prefs.source(r.story.from) && r.photo.exists()) pool.add(r);
            if (pool.isEmpty()) return null;
            shown.clear();
        }
        Ready pick = pool.get(random.nextInt(Math.min(4, pool.size())));
        shown.put(pick.story.link, now);
        saveShown(shown);
        return pick;
    }

    private static boolean fresh(Ready r, long now) {
        return r.story.published == 0 || now - r.story.published < DAY_MS;
    }

    /**
     * The story's photo saved on the TV, at most 1920 px wide; null when it has none 1000 px wide. TheNewsAPI gives the
     * photo's address; for a Google News story it comes from the article page.
     */
    private File photo(News.Story s) {
        String imageUrl = s.image;
        if (imageUrl.isEmpty()) {
            String page = text(s.link, 400_000);
            if (page == null) return null;
            imageUrl = News.mainImage(page);
        }
        if (imageUrl.isEmpty()) return null;
        byte[] bytes = bytes(imageUrl, 8 << 20);
        if (bytes == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < MIN_WIDTH || bounds.outHeight < MIN_WIDTH / 3) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;   // 0 until set
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1920) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (b == null) return null;
        if (b.getWidth() > 1920) {
            Bitmap scaled = Bitmap.createScaledBitmap(b, 1920, Math.round(b.getHeight() * 1920f / b.getWidth()), true);
            b.recycle();
            b = scaled;
        }
        File f = new File(dir, hash(s.link) + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            return null;
        } finally {
            b.recycle();
        }
        return f;
    }

    private void prune() {
        Set<String> keep = new HashSet<>();
        for (Ready r : ready) keep.add(r.photo.getName());
        File[] files = dir.listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (!keep.contains(files[i].getName())) files[i].delete();
        }
    }

    private void load() {
        try {
            JSONObject o = new JSONObject(new String(read(list), StandardCharsets.UTF_8));
            updated = o.optLong("updated");
            JSONArray a = o.optJSONArray("stories");
            List<Ready> out = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                News.Story s = new News.Story();
                s.topic = j.optString("topic");
                s.source = j.optString("source");
                s.title = j.optString("title");
                s.link = j.optString("link");
                s.image = j.optString("image");
                s.from = j.optString("from");
                s.published = j.optLong("published");
                File photo = new File(dir, j.optString("photo"));
                if (photo.exists()) out.add(new Ready(s, photo));
            }
            ready = out;
        } catch (IOException | JSONException e) {
            // Nothing saved yet: the first refresh fills it.
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Ready r : ready) {
                a.put(new JSONObject().put("topic", r.story.topic).put("source", r.story.source).put("title", r.story.title)
                        .put("link", r.story.link).put("image", r.story.image).put("from", r.story.from)
                        .put("published", r.story.published).put("photo", r.photo.getName()));
            }
            write(list, new JSONObject().put("updated", updated).put("stories", a).toString());
        } catch (JSONException | IOException e) {
            // Kept in memory; the next refresh saves again.
        }
    }

    private Map<String, Long> shown() {
        Map<String, Long> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(new String(read(shownFile), StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.getLong(k));
            }
        } catch (IOException | JSONException e) {
            // Nothing shown yet.
        }
        return out;
    }

    private void saveShown(Map<String, Long> shown) {
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Long> e : shown.entrySet()) if (now - e.getValue() < 2 * DAY_MS) o.put(e.getKey(), e.getValue());
            write(shownFile, o.toString());
        } catch (JSONException | IOException ignored) {
            // A story may come back a little sooner.
        }
    }

    private static String text(String url, int maxBytes) {
        byte[] b = bytes(url, maxBytes);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** A GET with a browser's user agent (Google News and some publishers refuse others); null on any failure. */
    private static byte[] bytes(String url, int maxBytes) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
            if (c.getResponseCode() / 100 != 2) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < maxBytes) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("rename");
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) out.append(String.format("%02x", d[i]));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
