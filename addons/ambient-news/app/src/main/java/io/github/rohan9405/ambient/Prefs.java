package io.github.rohan9405.ambient;

import android.content.Context;
import android.content.SharedPreferences;

/** What Settings controls, kept on the TV. Everything starts on, a story every 1–3 minutes for 20 seconds. */
final class Prefs {
    /** How often a story comes up: the gap between stories, drawn at random between the two (seconds). */
    static final int[][] GAPS = {{30, 90}, {60, 180}, {180, 360}, {300, 600}};
    static final int[] LENGTHS = {15, 20, 30};

    private final SharedPreferences p;

    Prefs(Context ctx) {
        p = ctx.getSharedPreferences("ambient", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return p.getBoolean("enabled", true);
    }

    boolean source(String from) {
        return p.getBoolean("source_" + from, true);
    }

    boolean topic(String topic) {
        return p.getBoolean("topic_" + topic, News.ON_AT_FIRST.contains(topic));
    }

    /** TheNewsAPI's free plan: 100 requests a day, counted from midnight UTC; this keeps 10 spare. */
    static final int THENEWS_PER_DAY = 90;

    private static String utcDay(long now) {
        return java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    /** TheNewsAPI requests still allowed today. */
    int theNewsLeft(long now) {
        return utcDay(now).equals(p.getString("thenews_day", "")) ? THENEWS_PER_DAY - p.getInt("thenews_used", 0) : THENEWS_PER_DAY;
    }

    /** Counts one TheNewsAPI request against today's allowance. */
    void theNewsUsed(long now) {
        String day = utcDay(now);
        int used = day.equals(p.getString("thenews_day", "")) ? p.getInt("thenews_used", 0) : 0;
        p.edit().putString("thenews_day", day).putInt("thenews_used", used + 1).apply();
    }

    /** Which topic asks TheNewsAPI first next time, so all get turns when the allowance runs short. */
    int theNewsTurn() {
        return p.getInt("thenews_turn", 0);
    }

    /** Index into {@link #GAPS}. */
    int gap() {
        return Math.max(0, Math.min(GAPS.length - 1, p.getInt("gap", 1)));
    }

    /** Seconds a story stays on screen. */
    int length() {
        int l = p.getInt("length", 20);
        for (int v : LENGTHS) if (v == l) return l;
        return 20;
    }

    /** TheNewsAPI token, sent from the computer (my-tv/api-keys.sh); empty until then. */
    String token() {
        return p.getString("token", "").trim();
    }

    /** The next screensaver shows a story after a few seconds, not after the usual gap: Settings' preview. */
    boolean previewOnce() {
        return p.getBoolean("preview", false);
    }

    void set(String key, boolean value) {
        p.edit().putBoolean(key, value).apply();
    }

    void set(String key, int value) {
        p.edit().putInt(key, value).apply();
    }

    void set(String key, String value) {
        p.edit().putString(key, value).apply();
    }
}
