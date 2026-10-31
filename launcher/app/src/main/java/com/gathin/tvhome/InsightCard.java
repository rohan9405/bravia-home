package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Locale;

/**
 * The insight chip beside the date: today's change on the INDmoney portfolio, then NIFTY, then SENSEX, one at a time,
 * turning every 30 seconds with a short rise (still while focused). OK opens the details. The Feed add-on fetches the figures and holds
 * the Feed screen's settings (the chip on or off, the portfolio shown or only the indices, how often it turns); this
 * only shows them, with their time, and never shows a missing figure as zero.
 */
final class InsightCard {
    private long turnMs = 30_000;
    private static final int UP = 0xFF6EE7A0, DOWN = 0xFFFF8A80;

    private final Activity act;
    private final Handler handler;
    private final LinearLayout chip;
    private final TextView label, value;
    private final float d;
    private JSONObject data = new JSONObject();
    /** Which view is showing: the portfolio (when shown) first, then each index. */
    private int step;
    private final Runnable turn = new Runnable() {
        @Override
        public void run() {
            if (!chip.hasFocus()) slide();
            handler.postDelayed(this, turnMs);
        }
    };

    /** Adds the chip to the clock row, after the date; toTopBar and toHome move focus Right and Down from it. */
    InsightCard(Activity act, Handler handler, LinearLayout clockRow, Runnable toTopBar, Runnable toHome) {
        this.act = act;
        this.handler = handler;
        d = act.getResources().getDisplayMetrics().density;
        chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setFocusable(true);
        chip.setBackgroundResource(R.drawable.pill_bg_feed);
        chip.setPadding(dp(16), dp(8), dp(18), dp(8));
        chip.setVisibility(View.GONE);
        label = text(11, 0xB3FFFFFF, Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        label.setLetterSpacing(0.14f);
        label.setAllCaps(true);
        chip.addView(label);
        value = text(15, Color.WHITE, Typeface.DEFAULT);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.leftMargin = dp(10);
        chip.addView(value, vl);
        chip.setOnFocusChangeListener((v, has) -> {
            boolean on = has;
            label.setTextColor(on ? 0xB30B0D12 : 0xB3FFFFFF);
            paint();
        });
        chip.setOnClickListener(v -> details());
        chip.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    toTopBar.run();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    toHome.run();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                default:
                    return false;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = dp(22);
        lp.bottomMargin = dp(4);
        clockRow.addView(chip, lp);
        handler.postDelayed(turn, turnMs);
    }

    boolean focusable() {
        return chip.getVisibility() == View.VISIBLE;
    }

    void focus() {
        chip.requestFocus();
    }

    /** New figures from the Feed add-on (its insights JSON). */
    void update(String json) {
        try {
            data = new JSONObject(json);
        } catch (JSONException e) {
            data = new JSONObject();
        }
        String status = data.optString("status");
        boolean any = data.has("portfolio") || data.optJSONArray("markets") != null && data.optJSONArray("markets").length() > 0;
        boolean on = data.optBoolean("chip", true) && (any || "reconnect".equals(status));
        if (!on && chip.hasFocus()) chip.focusSearch(View.FOCUS_RIGHT);
        chip.setVisibility(on ? View.VISIBLE : View.GONE);
        long ms = 1000L * Math.max(5, data.optInt("rotation", 30));
        if (ms != turnMs) {
            turnMs = ms;
            handler.removeCallbacks(turn);
            handler.postDelayed(turn, turnMs);
        }
        paint();
    }

    void stop() {
        handler.removeCallbacks(turn);
    }

    /** The next view rises in: the current one lifts away and fades, then the next comes up from below. */
    private void slide() {
        float lift = dp(10);
        for (View v : new View[] {label, value}) v.animate().cancel();
        label.animate().translationY(-lift).alpha(0f).setDuration(220);
        value.animate().translationY(-lift).alpha(0f).setDuration(220).withEndAction(() -> {
            step++;
            paint();
            for (View v : new View[] {label, value}) {
                v.setTranslationY(lift);
                v.animate().translationY(0f).alpha(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator());
            }
        });
    }

    private void paint() {
        boolean focused = chip.hasFocus();
        int plain = focused ? 0xFF0B0D12 : Color.WHITE;
        JSONObject p = data.optJSONObject("portfolio");
        JSONArray m = data.optJSONArray("markets");
        if (p == null && (m == null || m.length() == 0)) {
            label.setText(R.string.insight_indmoney);
            value.setText(R.string.insight_reconnect);
            value.setTextColor(plain);
            return;
        }
        int views = (p != null ? 1 : 0) + (m == null ? 0 : m.length());
        int at = step % Math.max(1, views);
        JSONObject index = p != null && at == 0 || m == null ? null : m.optJSONObject(at - (p != null ? 1 : 0));
        double c;
        if (index != null) {
            label.setText(indexName(index.optString("name")));
            c = index.optDouble("change");
            value.setText(level(index.optDouble("price")) + "   " + arrow(c) + pct(index.optDouble("pct")));
        } else {
            label.setText(R.string.insight_portfolio);
            c = p.optDouble("change");
            value.setText(arrow(c) + rupees(c) + "   " + pct(p.optDouble("pct")));
        }
        value.setTextColor(focused ? (c < 0 ? 0xFFB3261E : 0xFF0F7B3F) : (c < 0 ? DOWN : UP));
    }

    /** The details: each portfolio class's day and value, both indices, and when the figures are from. */
    private void details() {
        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(24), dp(28), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF5171A22);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x26FFFFFF);
        box.setBackground(bg);

        TextView head = text(24, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.setText(R.string.insight_title);
        box.addView(head);
        TextView when = text(12, 0x99FFFFFF, Typeface.DEFAULT);
        when.setText(status());
        when.setPadding(0, dp(4), 0, dp(10));
        box.addView(when);

        JSONObject p = data.optJSONObject("portfolio");
        if (p != null) {
            section(box, R.string.insight_portfolio);
            row(box, act.getString(R.string.insight_total), p.optDouble("change"), p.optDouble("pct"), p.optDouble("value"), true);
            JSONArray classes = p.optJSONArray("classes");
            for (int i = 0; classes != null && i < classes.length(); i++) {
                JSONObject c = classes.optJSONObject(i);
                if (c != null) row(box, className(c.optString("key"), c.optString("name")), c.optDouble("change"), c.optDouble("pct"), c.optDouble("value"), false);
            }
        }
        JSONArray m = data.optJSONArray("markets");
        if (m != null && m.length() > 0) {
            section(box, R.string.insight_markets);
            for (int i = 0; i < m.length(); i++) {
                JSONObject x = m.optJSONObject(i);
                if (x == null) continue;
                LinearLayout line = line(box);
                cell(line, indexName(x.optString("name")), 150, Color.WHITE, Gravity.START);
                cell(line, level(x.optDouble("price")), 110, Color.WHITE, Gravity.END);
                double c = x.optDouble("change");
                cell(line, arrow(c) + level(Math.abs(c)) + "  " + pct(x.optDouble("pct")), 150, c < 0 ? DOWN : UP, Gravity.END);
                cell(line, act.getString(R.string.insight_prev, level(x.optDouble("prev"))), 150, 0x99FFFFFF, Gravity.END);
            }
        }
        if ("reconnect".equals(data.optString("status"))) {
            TextView r = text(13, 0xFFFFC46B, Typeface.DEFAULT);
            r.setText(R.string.insight_reconnect_help);
            r.setPadding(0, dp(14), 0, 0);
            box.addView(r, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView src = text(11, 0x66FFFFFF, Typeface.DEFAULT);
        src.setText(p != null ? R.string.insight_source : R.string.insight_source_markets);
        src.setPadding(0, dp(14), 0, 0);
        box.addView(src);
        dialog.setContentView(box);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // Back on the chip when it closes, not wherever Home's focus finder lands.
        dialog.setOnDismissListener(x -> chip.post(chip::requestFocus));
        dialog.show();
    }

    private void row(LinearLayout box, String name, double change, double pct, double value, boolean total) {
        LinearLayout line = line(box);
        Typeface face = total ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD) : Typeface.DEFAULT;
        TextView n = cell(line, name, 150, Color.WHITE, Gravity.START);
        n.setTypeface(face);
        cell(line, arrow(change) + rupees(change), 130, change < 0 ? DOWN : UP, Gravity.END).setTypeface(face);
        cell(line, pct(pct), 90, change < 0 ? DOWN : UP, Gravity.END);
        cell(line, compact(value), 120, 0x99FFFFFF, Gravity.END);
    }

    private LinearLayout line(LinearLayout box) {
        LinearLayout line = new LinearLayout(act);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(5), 0, dp(5));
        box.addView(line);
        return line;
    }

    private TextView cell(LinearLayout line, String s, int widthDp, int colour, int gravity) {
        TextView t = text(15, colour, Typeface.DEFAULT);
        t.setText(s);
        t.setGravity(gravity);
        line.addView(t, new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void section(LinearLayout box, int label) {
        TextView t = text(11, 0x80FFFFFF, Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setText(act.getString(label).toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.14f);
        t.setPadding(0, dp(12), 0, dp(4));
        box.addView(t);
    }

    /** "Updated 10:42 AM · prices as of Fri 3:45 PM", and why newer ones are missing when they are. */
    private String status() {
        StringBuilder s = new StringBuilder();
        long updated = data.optLong("updated");
        if (updated > 0) s.append(act.getString(R.string.insight_updated, DateFormat.getTimeFormat(act).format(new Date(updated))));
        JSONArray m = data.optJSONArray("markets");
        JSONObject first = m == null ? null : m.optJSONObject(0);
        if (first != null && !first.optString("at").isEmpty()) {
            try {
                Date at = new Date(OffsetDateTime.parse(first.optString("at")).toInstant().toEpochMilli());
                s.append("  ·  ").append(act.getString(R.string.insight_prices_at,
                        DateFormat.format("EEE", at) + " " + DateFormat.getTimeFormat(act).format(at)));
            } catch (RuntimeException ignored) {
                // No quote time.
            }
        }
        String status = data.optString("status");
        if ("stale".equals(status) || "offline".equals(status)) s.append("  ·  ").append(act.getString(R.string.insight_stale));
        return s.toString();
    }

    private String className(String key, String fallback) {
        switch (key) {
            case "mf": return act.getString(R.string.insight_mf);
            case "in": return act.getString(R.string.insight_in);
            case "us": return act.getString(R.string.insight_us);
            default: return fallback;
        }
    }

    /** "NIFTY" for NIFTY 50; others as INDmoney names them, in capitals. */
    private static String indexName(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        return n.startsWith("NIFTY 50") ? "NIFTY" : n;
    }

    private static String arrow(double v) {
        return v < 0 ? "▼ " : "▲ ";
    }

    /** "₹6,011" in Indian grouping, the sign shown by the arrow. */
    private static String rupees(double v) {
        return "₹" + NumberFormat.getIntegerInstance(new Locale("en", "IN")).format(Math.round(Math.abs(v)));
    }

    /** "₹68.7 L", "₹1.32 Cr". */
    private static String compact(double v) {
        if (v >= 1e7) return String.format(Locale.US, "₹%.2f Cr", v / 1e7);
        if (v >= 1e5) return String.format(Locale.US, "₹%.1f L", v / 1e5);
        return rupees(v);
    }

    private static String level(double v) {
        NumberFormat f = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }

    private static String pct(double v) {
        return String.format(Locale.US, "%+.2f%%", v);
    }

    private TextView text(int sp, int colour, Typeface face) {
        TextView t = new TextView(act);
        t.setTextSize(sp);
        t.setTextColor(colour);
        t.setTypeface(face);
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * d);
    }
}
