package com.clonsapps.sample.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receiver yang dilindungi permission kustom milik aplikasi. */
public class PingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("ping", Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply();
    }
}
