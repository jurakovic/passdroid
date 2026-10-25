package com.kodholken.passdroid;

import android.content.BroadcastReceiver;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class ScreenOffListener extends BroadcastReceiver {
    private static boolean registered = false;

    /**
     * Start listening for the screen being turned off. Only the first call
     * has any effect.
     */
    public static void register(Context context) {
        if (registered) {
            return;
        }

        Utils.registerNotExportedReceiver(context.getApplicationContext(),
                                          new ScreenOffListener(),
                                          new IntentFilter(Intent.ACTION_SCREEN_OFF));
        registered = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Session.getInstance().isPasswordInClipboard()) {
            ClipboardClearReceiver.clear(context);
        }
    }
}
