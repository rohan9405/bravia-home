package io.github.rohan9405.ambient;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * Ambient News's settings, from the Apps row: on or off, each source and topic on or off, how often a story comes up
 * and how long it stays, and a button to try it now. A computer sends TheNewsAPI token here too
 * (my-tv/api-keys.sh: --es news_token), or asks for that try (--ez preview true); those starts close again at once.
 */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;
    private int focus;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        String token = getIntent().getStringExtra("news_token");
        if (token != null) {
            prefs.set("token", token.trim());
            Toast.makeText(this, R.string.token_saved, Toast.LENGTH_SHORT).show();
            startService(true);
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("preview", false)) {
            // From the computer: the same as the Start the screensaver now row.
            startService(false);
            preview();
            finish();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0D12);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(96), px(48), px(96), px(48));
        scroll.addView(list);
        setContentView(scroll);
        startService(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) build();
    }

    private void build() {
        list.removeAllViews();
        TextView title = text(30, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setText(R.string.settings_title);
        list.addView(title);
        TextView intro = text(15, 0xA6FFFFFF, Typeface.DEFAULT);
        intro.setText(R.string.settings_intro);
        intro.setPadding(0, px(8), 0, px(8));
        list.addView(intro, new LinearLayout.LayoutParams(px(640), -2));
        TextView status = text(14, 0xFF8AB4F8, Typeface.DEFAULT);
        status.setText(status());
        status.setPadding(0, 0, 0, px(12));
        list.addView(status);

        row(getString(R.string.row_enabled), onOff(prefs.enabled()), () -> {
            prefs.set("enabled", !prefs.enabled());
            if (prefs.enabled()) startService(false);
            else stopService(new Intent(this, AmbientService.class));
        });
        section(R.string.section_sources);
        row(getString(R.string.source_google), onOff(prefs.source(News.GOOGLE)), () -> toggle("source_" + News.GOOGLE));
        String theNews = prefs.token().isEmpty() ? getString(R.string.no_token) : onOff(prefs.source(News.THENEWS));
        row(getString(R.string.source_thenews), theNews, () -> toggle("source_" + News.THENEWS));
        section(R.string.section_topics);
        for (String topic : News.TOPICS) row(getString(topicLabel(topic, false)), onOff(prefs.topic(topic)), () -> toggle("topic_" + topic));
        section(R.string.section_timing);
        int[] gap = Prefs.GAPS[prefs.gap()];
        row(getString(R.string.row_frequency), getString(R.string.gap_range, span(gap[0]), span(gap[1])),
                () -> prefs.set("gap", (prefs.gap() + 1) % Prefs.GAPS.length));
        row(getString(R.string.row_length), getString(R.string.seconds, prefs.length()), () -> {
            int i = 0;
            while (Prefs.LENGTHS[i] != prefs.length()) i++;
            prefs.set("length", Prefs.LENGTHS[(i + 1) % Prefs.LENGTHS.length]);
        });
        row(getString(R.string.row_preview), "", this::preview);
        int rows = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) rows++;
        focus = Math.min(focus, rows - 1);
        int seen = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (!list.getChildAt(i).isFocusable()) continue;
            if (seen++ == focus) {
                list.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    /** A topic's name: in Settings ("Business & markets"), or short, on a story ("Business"). */
    static int topicLabel(String topic, boolean onStory) {
        return switch (topic) {
            case News.INDIA -> R.string.row_topic_india;
            case News.WORLD -> R.string.row_topic_world;
            case News.BUSINESS -> onStory ? R.string.topic_business_short : R.string.row_topic_business;
            case News.TECH -> R.string.row_topic_tech;
            case News.SPORTS -> R.string.row_topic_sports;
            case News.ENTERTAINMENT -> R.string.row_topic_entertainment;
            case News.SCIENCE -> R.string.row_topic_science;
            default -> R.string.row_topic_health;
        };
    }

    private String status() {
        if (!Settings.canDrawOverlays(this)) return getString(R.string.status_no_overlay);
        Stories s = new Stories(this);
        if (s.count(null) == 0) return getString(R.string.status_none);
        return getString(R.string.status_counts, s.count(null), s.count(News.GOOGLE), s.count(News.THENEWS),
                DateFormat.getTimeFormat(this).format(new Date(s.updated())));
    }

    private void toggle(String key) {
        boolean on = key.startsWith("source_") ? prefs.source(key.substring(7)) : prefs.topic(key.substring(6));
        prefs.set(key, !on);
        startService(true);
    }

    /** The next screensaver shows a story after a few seconds; then the screensaver starts. */
    private void preview() {
        prefs.set("preview", true);
        try {
            startActivity(new Intent().setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")));
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startService(boolean refresh) {
        if (!prefs.enabled()) return;
        Intent i = new Intent(this, AmbientService.class);
        if (refresh) i.setAction(AmbientService.ACTION_REFRESH);
        startForegroundService(i);
    }

    private void section(int label) {
        TextView t = text(13, 0x80FFFFFF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(getString(label).toUpperCase(java.util.Locale.ROOT));
        t.setLetterSpacing(0.14f);
        t.setPadding(px(14), px(18), 0, px(6));
        list.addView(t);
    }

    private void row(String label, String value, Runnable onClick) {
        TextView t = text(18, Color.WHITE, Typeface.DEFAULT);
        t.setText(value.isEmpty() ? label : label + "    " + value);
        t.setFocusable(true);
        t.setPadding(px(16), px(11), px(16), px(11));
        t.setBackground(pill(false));
        t.setOnFocusChangeListener((v, has) -> {
            v.setBackground(pill(has));
            ((TextView) v).setTextColor(has ? 0xFF0B0D12 : Color.WHITE);
        });
        final int index = focusableCount();
        t.setOnClickListener(v -> {
            focus = index;
            onClick.run();
            build();
        });
        list.addView(t, new LinearLayout.LayoutParams(px(560), -2));
    }

    private int focusableCount() {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) n++;
        return n;
    }

    private GradientDrawable pill(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? 0xF2FFFFFF : 0x00000000);
        g.setCornerRadius(px(14));
        return g;
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    /** "90 s", "3 min". */
    private String span(int seconds) {
        return seconds < 120 ? getString(R.string.seconds, seconds) : (seconds / 60) + " min";
    }

    private TextView text(int sp, int colour, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(face);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
