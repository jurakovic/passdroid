package com.kodholken.passdroid;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;

/**
 * Removes a copied password or 2FA code from the clipboard, a while after it
 * was copied and when the screen turns off (see ScreenOffListener), unless
 * the user has turned it off in the settings.
 *
 * The timeout is an alarm to this receiver, which is declared in the
 * manifest, so that it also works while the application is in the background,
 * where it may be frozen or its process killed.
 */
public class ClipboardClearReceiver extends BroadcastReceiver {
    // The label of copied passwords and 2FA codes
    public static final String LABEL = "password";

    // The alarm is inexact, it may go off somewhat later
    private static final long DELAY = 45 * 1000;

    /**
     * Clear the clipboard after a while. Call this when a password has been
     * copied.
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + DELAY,
               getAlarmIntent(context));
    }

    /**
     * Do not clear the clipboard. Call this when something else has been
     * copied.
     */
    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(getAlarmIntent(context));
    }

    private static PendingIntent getAlarmIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(context, ClipboardClearReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        clear(context);
    }

    /**
     * Remove the copied password from the clipboard now.
     */
    public static void clear(Context context) {
        cancel(context);
        Session.getInstance().setPasswordInClipboard(false);

        if (!PreferenceManager.getDefaultSharedPreferences(context).
                               getBoolean("clear_clipboard", true)) {
            return;
        }

        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        // Keep what has been copied since, e.g. in another application, which
        // often has no label. Since Android 10 the clipboard can not be read
        // in the background, and then it is cleared anyway.
        ClipDescription description = cm.getPrimaryClipDescription();
        if (description != null && (description.getLabel() == null ||
                                    !LABEL.contentEquals(description.getLabel()))) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip();
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }
}
