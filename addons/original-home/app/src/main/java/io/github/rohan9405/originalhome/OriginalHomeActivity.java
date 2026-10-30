package io.github.rohan9405.originalhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/**
 * One press on the card opens the TV's own home screen. CleanYourTV stays the default, so the remote's Home button
 * comes back to it. When the stock launcher is switched off (the install script disables it unless
 * STOCK_LAUNCHER=none), this screen says so and gives the command that brings it back.
 */
public final class OriginalHomeActivity extends Activity {
    /** The stock launchers the CleanYourTV scripts know (KNOWN_STOCK_LAUNCHERS), Google TV's first. */
    static final List<String> STOCK = Arrays.asList(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            "com.mitv.tvhome");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ComponentName home = stockHome();
        if (home != null) {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(home)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
                return;
            } catch (RuntimeException ignored) {
                // Fall through to the explanation.
            }
        }
        TextView t = new TextView(this);
        int pad = (int) (56 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad, pad, pad);
        t.setTextSize(20);
        t.setTextColor(0xFFFFFFFF);
        t.setBackgroundColor(0xFF0E1117);
        t.setText("The TV's own home screen is switched off.\n\n"
                + "To bring it back for good, run this on the computer you set the TV up from:\n\n"
                + "TV_HOST=<tv ip> tools/switch-home.sh stock\n\n"
                + "To undo everything, the debloat included:\n\n"
                + "TV_HOST=<tv ip> tools/restore.sh examples/<your tv>/debloat.txt com.google.android.apps.tv.launcherx");
        setContentView(t);
    }

    /** The enabled stock launcher's HOME activity, or null. */
    private ComponentName stockHome() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> homes = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        for (String pkg : STOCK) {
            for (ResolveInfo r : homes) {
                ActivityInfo a = r.activityInfo;
                if (a != null && pkg.equals(a.packageName) && a.enabled) return new ComponentName(a.packageName, a.name);
            }
        }
        return null;
    }
}
