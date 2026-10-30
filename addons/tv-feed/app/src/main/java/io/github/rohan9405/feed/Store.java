package io.github.rohan9405.feed;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The last refresh on disk (feed.json), so Home has its rows straight after a reboot, before the next refresh. */
final class Store {
    /** What one refresh found: the tiles, and per app how many rows it publishes (the Feed screen's probe). */
    static final class Snapshot {
        final long refreshedMs;
        final String error;
        final List<Item> items;
        /** package -> {Continue watching rows, recommendation rows, channels}. */
        final Map<String, int[]> probe;

        Snapshot(long refreshedMs, String error, List<Item> items, Map<String, int[]> probe) {
            this.refreshedMs = refreshedMs;
            this.error = error;
            this.items = items;
            this.probe = probe;
        }
    }

    private static final Snapshot EMPTY = new Snapshot(0, "", Collections.emptyList(), Collections.emptyMap());
    private static volatile Snapshot current;

    private Store() {
    }

    static Snapshot get(Context ctx) {
        Snapshot s = current;
        if (s != null) return s;
        synchronized (Store.class) {
            if (current == null) current = load(file(ctx));
            return current;
        }
    }

    static synchronized void put(Context ctx, Snapshot s) {
        current = s;
        try {
            JSONArray items = new JSONArray();
            for (Item i : s.items) items.put(i.toJson());
            JSONObject probe = new JSONObject();
            for (Map.Entry<String, int[]> e : s.probe.entrySet()) {
                probe.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]).put(e.getValue()[2]));
            }
            JSONObject root = new JSONObject().put("refreshed", s.refreshedMs).put("error", s.error)
                    .put("items", items).put("probe", probe);
            File f = file(ctx);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (JSONException | IOException ignored) {
            // The rows stay in memory; the next refresh writes again.
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "feed.json");
    }

    private static Snapshot load(File f) {
        if (!f.exists()) return EMPTY;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject root = new JSONObject(new String(Net.read(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
            List<Item> items = new ArrayList<>();
            JSONArray a = root.optJSONArray("items");
            for (int i = 0; a != null && i < a.length(); i++) items.add(Item.fromJson(a.getJSONObject(i)));
            Map<String, int[]> probe = new LinkedHashMap<>();
            JSONObject p = root.optJSONObject("probe");
            if (p != null) {
                for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray v = p.getJSONArray(k);
                    probe.put(k, new int[] {v.optInt(0), v.optInt(1), v.optInt(2)});
                }
            }
            return new Snapshot(root.optLong("refreshed"), root.optString("error"), items, probe);
        } catch (IOException | JSONException e) {
            return EMPTY;
        }
    }
}
