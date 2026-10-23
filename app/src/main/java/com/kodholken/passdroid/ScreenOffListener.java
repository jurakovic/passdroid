package com.kodholken.passdroid;

import android.content.BroadcastReceiver;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.preference.PreferenceManager;

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
        boolean clear = PreferenceManager.getDefaultSharedPreferences(context).
                                          getBoolean("clear_clipboard", true);

        if (clear && Session.getInstance().isPasswordInClipboard()) {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

            // Since Android 10 the clipboard can not be read from the
            // background, so we can not check that it still contains the
            // copied password and clear it unconditionally.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip();
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }

            Session.getInstance().setPasswordInClipboard(false);
        }
    }
}
