package io.github.rohan9405.ambient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the waiting service again after a reboot or an update, when Settings has it on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (new Prefs(ctx).enabled()) ctx.startForegroundService(new Intent(ctx, AmbientService.class));
    }
}
