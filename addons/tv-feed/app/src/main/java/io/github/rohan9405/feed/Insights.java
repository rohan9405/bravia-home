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
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Home's insight card: today's change on the INDmoney portfolio (mutual funds, Indian stocks, US stocks, as INDmoney
 * gives each) and Nifty 50 and Sensex, kept in insights.json. Fetched when Home asks and the last fetch is older than
 * 15 minutes while India's market is open, an hour otherwise. A failed fetch keeps the last figures with their time and
 * says why ("offline", "reconnect"); nothing is ever shown as zero for want of a value.
 */
final class Insights {
    static final String NIFTY = "INDI00012", SENSEX = "INDI00050";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ExecutorService ONE = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private Insights() {
    }

    /** The saved card data, or {"status":"none"} before INDmoney was ever connected. */
    static String json(Context ctx) {
        try (InputStream in = new FileInputStream(file(ctx))) {
            return new String(Net.read(in, 1 << 20), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Indmoney(ctx).paired() ? "{\"status\":\"loading\"}" : "{\"status\":\"none\"}";
        }
    }

    /** Fetches in the background when the saved data is older than {@link #maxAgeMs}; Home hears when it is stored. */
    static void refreshIfStale(Context ctx) {
        if (!new Indmoney(ctx).paired()) return;
        long updated = 0;
        try {
            updated = new JSONObject(json(ctx)).optLong("tried");
        } catch (JSONException ignored) {
            // Fetch.
        }
        if (System.currentTimeMillis() - updated > maxAgeMs(ZonedDateTime.now(IST))) kick(ctx);
    }

    static void kick(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        ONE.execute(() -> {
            try {
                store(app, fetch(app));
                app.getContentResolver().notifyChange(FeedProvider.insightsUri(), null);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** 15 minutes while India's market is open (Monday to Friday, 9:00 to 15:45), an hour otherwise. */
    static long maxAgeMs(ZonedDateTime now) {
        boolean weekday = now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY;
        LocalTime t = now.toLocalTime();
        boolean open = weekday && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 45));
        return (open ? 15 : 60) * 60_000L;
    }

    /** One fetch: the portfolio's three classes and the two indices; on failure, the last figures with the reason. */
    private static JSONObject fetch(Context ctx) {
        long now = System.currentTimeMillis();
        JSONObject last;
        try {
            last = new JSONObject(json(ctx));
        } catch (JSONException e) {
            last = new JSONObject();
        }
        Indmoney im = new Indmoney(ctx);
        try {
            JSONObject mf = im.tool("networth_holdings", new JSONObject().put("asset_type", "MF"));
            JSONObject in = im.tool("networth_holdings", new JSONObject().put("asset_type", "IND_STOCK"));
            JSONObject us = im.tool("networth_holdings", new JSONObject().put("asset_type", "US_STOCK"));
            JSONObject idx = im.tool("get_indian_stocks_details", new JSONObject().put("ind_keys", new JSONArray().put(NIFTY).put(SENSEX)));
            JSONObject out = compose(mf, in, us, idx);
            return out.put("status", "ok").put("updated", now).put("tried", now);
        } catch (Indmoney.ReconnectNeeded e) {
            return safePut(last, "status", "reconnect", now);
        } catch (IOException | JSONException | RuntimeException e) {
            android.util.Log.w("Feed", "insights: " + e);
            return safePut(last, "status", last.has("portfolio") ? "stale" : "offline", now);
        }
    }

    private static JSONObject safePut(JSONObject o, String k, String v, long now) {
        try {
            return o.put(k, v).put("tried", now);
        } catch (JSONException e) {
            return o;
        }
    }

    /**
     * The card's figures from INDmoney's answers: each class's value and today's change as INDmoney gives them, their
     * sum, the total's percent against yesterday's value, and each index's level, change and quote time.
     */
    static JSONObject compose(JSONObject mf, JSONObject in, JSONObject us, JSONObject indices) throws JSONException {
        JSONArray classes = new JSONArray();
        double change = 0, value = 0;
        String[][] names = {{"mf", "Mutual funds"}, {"in", "Indian stocks"}, {"us", "US stocks"}};
        JSONObject[] answers = {mf, in, us};
        for (int i = 0; i < answers.length; i++) {
            JSONObject s = answers[i].optJSONObject("asset_summary");
            if (s == null || !s.has("one_day_change")) continue;
            double c = s.getDouble("one_day_change"), v = s.optDouble("total_value", 0);
            JSONObject row = new JSONObject().put("key", names[i][0]).put("name", names[i][1]).put("change", c)
                    .put("pct", s.optDouble("one_day_change_percentage", 0)).put("value", v);
            if (s.has("one_day_change_usd")) row.put("change_usd", s.getDouble("one_day_change_usd"));
            classes.put(row);
            change += c;
            value += v;
        }
        JSONObject portfolio = new JSONObject().put("change", change).put("value", value).put("classes", classes)
                .put("pct", value - change > 0 ? change * 100 / (value - change) : 0);
        JSONArray markets = new JSONArray();
        for (String key : new String[] {NIFTY, SENSEX}) {
            JSONObject e = indices.optJSONObject(key);
            JSONObject st = e == null ? null : e.optJSONObject("entity_stats");
            if (st == null) continue;
            JSONObject basic = e.optJSONObject("entity_basic");
            markets.put(new JSONObject().put("name", basic == null ? key : basic.optString("display_name", key))
                    .put("price", st.optDouble("live_price")).put("change", st.optDouble("day_change"))
                    .put("pct", st.optDouble("day_change_percentage")).put("prev", st.optDouble("prev_close"))
                    .put("at", st.optString("price_updated_at_ist")));
        }
        JSONObject out = new JSONObject().put("markets", markets);
        if (classes.length() > 0) out.put("portfolio", portfolio);
        return out;
    }

    private static void store(Context ctx, JSONObject o) {
        File f = file(ctx), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "insights.json");
    }
}
