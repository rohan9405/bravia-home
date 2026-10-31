package io.github.rohan9405.feed;


import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Feed screen, and the module card's target: what each app on this TV publishes (the probe), the rows Home
 * gets, the OMDb key and a Refresh button.
 *
 * <p>Set up from a computer without the remote (my-tv/api-keys.sh): {@code --es omdb_key <key>}, {@code --es tmdb_token <token>} or
 * {@code --ez demo true|false} stores the setting, starts a refresh and closes at once, with nothing shown.
 */
public final class FeedActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (applySetup(getIntent())) {
            finish();
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (48 * d);
        col.setPadding(pad, (int) (32 * d), pad, pad);
        col.setBackgroundColor(0xFF0E1117);

        TextView title = text(32, 0xFFFFFFFF);
        title.setText(R.string.app_name);
        col.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, (int) (16 * d), 0, (int) (8 * d));
        Button refresh = new Button(this);
        refresh.setText(R.string.refresh);
        refresh.setOnClickListener(v -> refresh());
        buttons.addView(refresh);
        if (checkSelfPermission(Refresher.READ_TV) != PackageManager.PERMISSION_GRANTED) {
            Button grant = new Button(this);
            grant.setText(R.string.grant);
            grant.setOnClickListener(v -> requestPermissions(new String[] {Refresher.READ_TV}, 1));
            buttons.addView(grant);
        }
        col.addView(buttons);

        // Home's insight chip (INDmoney): on or off, the portfolio shown or only the indices, how often it turns.
        TextView chipTitle = text(13, 0x99FFFFFF);
        chipTitle.setText(R.string.chip_section);
        chipTitle.setPadding(0, (int) (16 * d), 0, (int) (4 * d));
        col.addView(chipTitle);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        Button chip = new Button(this), portfolio = new Button(this), rotation = new Button(this);
        Runnable label = () -> {
            chip.setText(getString(R.string.chip_on, getString(Insights.chipOn(this) ? R.string.on : R.string.off)));
            portfolio.setText(getString(R.string.chip_portfolio, getString(Insights.portfolioOn(this) ? R.string.on : R.string.off)));
            rotation.setText(getString(R.string.chip_rotation, Insights.rotationSec(this)));
        };
        chip.setOnClickListener(v -> {
            Insights.settings(this).edit().putBoolean("chip", !Insights.chipOn(this)).apply();
            label.run();
            Insights.changed(this);
        });
        portfolio.setOnClickListener(v -> {
            Insights.settings(this).edit().putBoolean("portfolio", !Insights.portfolioOn(this)).apply();
            label.run();
            Insights.changed(this);
        });
        rotation.setOnClickListener(v -> {
            int i = 0;
            while (i < Insights.ROTATIONS.length && Insights.ROTATIONS[i] != Insights.rotationSec(this)) i++;
            Insights.settings(this).edit().putInt("rotation", Insights.ROTATIONS[(i + 1) % Insights.ROTATIONS.length]).apply();
            label.run();
            Insights.changed(this);
        });
        label.run();
        chipRow.addView(chip);
        chipRow.addView(portfolio);
        chipRow.addView(rotation);
        col.addView(chipRow);

        status = text(15, 0xCCFFFFFF);
        status.setPadding(0, (int) (16 * d), 0, 0);
        status.setLineSpacing(4 * d, 1f);
        col.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        refresh.requestFocus();
        show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) recreate();
    }

    private void refresh() {
        status.setText("Refreshing…");
        Refresher.kick(this, () -> runOnUiThread(this::show));
    }

    private void show() {
        if (isDestroyed()) return;
        Store.Snapshot s = Store.get(this);
        StringBuilder b = new StringBuilder();
        b.append("Reading TV rows: ").append(checkSelfPermission(Refresher.READ_TV)
                == PackageManager.PERMISSION_GRANTED ? "allowed" : "not allowed yet").append('\n');
        b.append("IMDb ratings: ").append(Omdb.hasKey(this) ? "OMDb key set"
                : "no OMDb key yet (sent from the Mac: my-tv/api-keys.sh send)").append('\n');
        b.append("New releases (TMDB): ").append(Tmdb.hasToken(this) ? "token set"
                : "no token yet (sent from the Mac: my-tv/api-keys.sh send)").append('\n');
        b.append("Last refresh: ").append(s.refreshedMs == 0 ? "never"
                : DateUtils.getRelativeTimeSpanString(s.refreshedMs)).append('\n');
        if (!s.error.isEmpty()) b.append("Problem: ").append(s.error).append('\n');

        // Per section and app: tiles, and how many have a rating.
        PackageManager pm = getPackageManager();
        Map<String, int[]> rows = new LinkedHashMap<>();
        for (Item i : s.items) {
            String name = Item.CONTINUE.equals(i.section) ? "Continue watching"
                    : (Item.HERO.equals(i.section) ? "Header, new in 14 days: "
                    : Item.POOL.equals(i.section) ? "Genre pool: " : "Top 10 tab: ") + label(pm, i.tab, false);
            int[] n = rows.get(name);
            if (n == null) rows.put(name, n = new int[2]);
            n[0]++;
            if (!i.rating.isEmpty()) n[1]++;
        }
        b.append("\nOn Home\n");
        if (rows.isEmpty()) b.append("  none yet\n");
        for (Map.Entry<String, int[]> e : rows.entrySet()) {
            b.append("  ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(" tiles");
            if (e.getValue()[1] > 0) b.append(", ").append(e.getValue()[1]).append(" rated");
            b.append('\n');
        }

        b.append("\nWhat each app publishes (Continue watching / recommendations / channels)\n");
        if (s.probe.isEmpty()) b.append("  nothing visible\n");
        for (Map.Entry<String, int[]> e : s.probe.entrySet()) {
            int[] n = e.getValue();
            b.append("  ").append(label(pm, e.getKey(), true)).append(": ").append(n[0]).append(" / ").append(n[1])
                    .append(" / ").append(n[2]).append('\n');
        }
        b.append("\nApps not listed publish nothing this app can see.");
        status.setText(b);
    }

    private static String label(PackageManager pm, String pkg, boolean withPackage) {
        try {
            String l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            return withPackage ? l + " (" + pkg + ")" : l;
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private TextView text(int sp, int colour) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(colour);
        t.setFocusable(false);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return t;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applySetup(intent)) show();
    }

    /** A setup intent from a computer: store what it carries and refresh. False for an ordinary start. */
    private boolean applySetup(Intent intent) {
        boolean setup = false;
        String k = intent.getStringExtra("omdb_key");
        if (k != null) {
            Omdb.setKey(this, k);
            setup = true;
        }
        String t = intent.getStringExtra("tmdb_token");
        if (t != null) {
            Tmdb.setToken(this, t);
            setup = true;
        }
        String client = intent.getStringExtra("indmoney_client_id");
        if (client != null) {
            // A sign-in made on the computer (my-tv/indmoney-pair.sh); the TV renews it from here on.
            Indmoney.pair(this, client, String.valueOf(intent.getStringExtra("indmoney_client_secret")),
                    String.valueOf(intent.getStringExtra("indmoney_refresh_token")));
            Insights.kick(this);
            setup = true;
        }
        if (intent.hasExtra("demo")) {
            Demo.set(this, intent.getBooleanExtra("demo", false));
            setup = true;
        }
        if (setup) Refresher.kick(this, null);
        return setup;
    }
}
