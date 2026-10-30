package io.github.rohan9405.feed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample rows with posters drawn here, for previewing Home on an emulator that has none of your apps:
 * {@code am start -n io.github.rohan9405.feed/.FeedActivity --ez demo true} (false goes back to the TV's rows).
 */
final class Demo {
    static final String PREFS = "feed";
    static final String KEY = "demo";
    /** Opens the Feed screen: the emulator has none of the apps the samples name. */
    private static final String OPEN = "intent:#Intent;component=io.github.rohan9405.feed/.FeedActivity;end";

    private Demo() {
    }

    static boolean on(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    static void set(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }

    private static final String NETFLIX = "com.netflix.ninja";
    private static final String PRIME = "com.amazon.amazonvideo.livingroom";
    private static final String HOTSTAR = "in.startv.hotstar";
    private static final String APPLE = "com.apple.atve.androidtv.appletv";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String SONYLIV = "com.sonyliv";
    private static final String ZEE5 = "com.graymatrix.did";
    private static final String[] GENRES = {"Crime, Drama", "Drama", "Comedy, Drama", "Thriller, Crime", "Comedy"};
    private static final String PLOT = "A sample title for previewing Home: the real header shows what is new on your "
            + "services in the last 10 days, with its IMDb rating.";

    /** Sample app -> the app its tiles go to: itself when installed, else one of the TV's own (an emulator has few). */
    private static final Map<String, String> STAND_IN = new LinkedHashMap<>();

    static Store.Snapshot snapshot(Context ctx) {
        standIns(ctx);
        List<Item> items = new ArrayList<>();
        hero(ctx, items, "Minimum Wage", "tv", "2026", "8.4", NETFLIX, 0xFFB71C1C);
        hero(ctx, items, "Gajab Raat", "movie", "2026", "7.9", NETFLIX, 0xFF4A148C);
        hero(ctx, items, "Chaar Yaar", "tv", "2026", "8.1", PRIME, 0xFF0D47A1);
        hero(ctx, items, "Love in Disguise", "tv", "2026", "7.2", PRIME, 0xFF1B5E20);
        hero(ctx, items, "The Mob", "tv", "2026", "7.6", HOTSTAR, 0xFF263238);
        hero(ctx, items, "Slow Horses", "tv", "2022", "8.2", APPLE, 0xFF37474F);
        String cont = ctx.getString(R.string.continue_watching);
        add(ctx, items, Item.CONTINUE, cont, 0, "Panchayat", "S3 E4 · The Election", "9.0", 62, PRIME, 0xFF2E7D32);
        add(ctx, items, Item.CONTINUE, cont, 0, "Kota Factory", "S3 E2", "9.0", 35, NETFLIX, 0xFF37474F);
        add(ctx, items, Item.CONTINUE, cont, 0, "Scam 1992", "S1 E7 · Bull Run", "9.2", 80, SONYLIV, 0xFF6D4C41);
        add(ctx, items, Item.CONTINUE, cont, 0, "The Family Man", "S2 E1", "8.7", 15, PRIME, 0xFF1565C0);
        add(ctx, items, Item.CONTINUE, cont, 0, "Aspirants", "S2 E3", "9.1", 50, PRIME, 0xFF4E342E);
        tab(ctx, items, NETFLIX, 0xFFB71C1C, "Sacred Games|8.5", "Delhi Crime|8.5", "Jamtara|7.8", "Kohrra|8.3", "Rana Naidu|6.8", "Trial by Fire|8.7");
        tab(ctx, items, YOUTUBE, 0xFFC62828, "How the Indian Railways run on time|", "Street food tour of Old Delhi|",
                "Cricket highlights: final over|", "Lo-fi beats to study to|", "Himalaya trek, day 4|");
        tab(ctx, items, PRIME, 0xFF0D47A1, "Mirzapur|8.4", "Paatal Lok|8.1", "Made in Heaven|8.3", "Farzi|8.3", "Dahaad|7.9");
        tab(ctx, items, HOTSTAR, 0xFF263238, "Special Ops|8.6", "Aarya|7.8", "Criminal Justice|8.1", "Hostages|7.4");
        tab(ctx, items, SONYLIV, 0xFF6D4C41, "Rocket Boys|8.9", "Gullak|9.1", "Undekhi|7.9");
        tab(ctx, items, ZEE5, 0xFF4A148C, "Sunflower|7.4", "Abhay|7.6", "Duranga|6.9");
        Map<String, int[]> probe = new LinkedHashMap<>();
        probe.put("(demo rows: no app on this TV was read)", new int[3]);
        return new Store.Snapshot(System.currentTimeMillis(), "", items, probe);
    }

    private static void hero(Context ctx, List<Item> items, String title, String kind, String year, String rating,
                             String pkg, int colour) {
        Item i = add(ctx, items, Item.HERO, "", 0, title, "", rating, -1, pkg, colour);
        i.kind = kind;
        i.year = year;
        i.genre = "Drama, Thriller";
        i.overview = PLOT;
        i.backdrop = draw(ctx, i.posterSource, title, colour, true);
    }

    private static void tab(Context ctx, List<Item> items, String pkg, int colour, String... titles) {
        for (int n = 0; n < titles.length; n++) {
            String[] t = titles[n].split("\\|", -1);
            Item i = add(ctx, items, Item.APP, "", n + 1, t[0], "", t[1], -1, pkg, colour + n * 0x00080808);
            i.overview = PLOT;
            if (!t[1].isEmpty()) {
                i.kind = n % 3 == 1 ? "movie" : "tv";
                i.genre = GENRES[(n + t[0].length()) % GENRES.length];
            }
        }
    }

    private static void standIns(Context ctx) {
        STAND_IN.clear();
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        List<String> installed = new ArrayList<>();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String p = r.activityInfo.packageName;
            if (!p.equals("com.gathin.tvhome") && !installed.contains(p)) installed.add(p);
        }
        int next = 0;
        for (String p : new String[] {NETFLIX, YOUTUBE, PRIME, HOTSTAR, SONYLIV, ZEE5, APPLE}) {
            if (installed.contains(p) || installed.isEmpty()) STAND_IN.put(p, p);
            else STAND_IN.put(p, installed.get(next++ % installed.size()));
        }
    }

    private static Item add(Context ctx, List<Item> items, String section, String row, int order, String title,
                            String subtitle, String rating, int progress, String pkg, int colour) {
        String stand = STAND_IN.get(pkg);
        if (stand != null) pkg = stand;
        Item i = new Item();
        i.section = section;
        i.tab = pkg;
        i.row = row;
        i.rowOrder = order;
        i.title = title;
        i.subtitle = subtitle;
        i.rating = rating;
        i.progress = progress;
        i.pkg = pkg;
        i.intent = OPEN;
        i.posterSource = "demo:" + title;
        i.poster = draw(ctx, i.posterSource, title, colour, false);
        items.add(i);
        return i;
    }

    /** A gradient card with the title on it, saved where FeedProvider serves art from. */
    private static String draw(Context ctx, String source, String title, int colour, boolean backdrop) {
        String name = Images.hash(source) + (backdrop ? "-b.jpg" : "-t.jpg");
        File f = new File(Images.dir(ctx), name);
        if (f.length() > 0) return name;
        int w = backdrop ? Images.BACKDROP_W : Images.TILE_W;
        int h = w * 9 / 16;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, colour, 0xFF0B0D12, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        c.drawCircle(w * 0.78f, h * 0.35f, h * 0.45f, p);
        if (!backdrop) {
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(h * 0.13f);
            String t = title;
            while (p.measureText(t) > w * 0.86f && t.length() > 4) t = t.substring(0, t.length() - 2);
            if (!t.equals(title)) t = t.trim() + "…";
            c.drawText(t, w * 0.07f, h * 0.86f, p);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            return "";
        } finally {
            b.recycle();
        }
        return name;
    }
}
