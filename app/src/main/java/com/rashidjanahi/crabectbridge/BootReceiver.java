package com.rashidjanahi.crabectbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = context.getSharedPreferences(EctBridgeService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(EctBridgeService.PREF_ENABLED, false);
        if (!enabled) return;

        Intent service = new Intent(context, EctBridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
