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

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        Utils.registerNotExportedReceiver(context.getApplicationContext(),
                                          new ScreenOffListener(), filter);
        registered = true;
    }

    /**
     * Called when the screen is turned off or on. Since Android 14 the
     * broadcasts are held back while the application is in the background
     * until it is in the foreground again, and only the last one is
     * delivered. Then SCREEN_ON is received instead of SCREEN_OFF, but it
     * also means that the screen has been off.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        Session.screenOff(context);

        if (Session.getInstance().isPasswordInClipboard()) {
            ClipboardClearReceiver.clear(context);
        }
    }
}
