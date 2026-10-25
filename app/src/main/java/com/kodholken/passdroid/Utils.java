/*    
    This file is part of the Passdroid password management software.
    
    Copyright (C) 2009-2012  Magnus Eriksson <eriksson.mag@gmail.com>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.kodholken.passdroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

public class Utils {
    public static void alertDialog(Context context, String title, 
            String message) {
        AlertDialog alertDialog;

        alertDialog = new AlertDialog.Builder(context).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                return;
            } }); 
        alertDialog.show();
    }

    public static void startPasswordActivity(Context context) {
        Intent i = new Intent(context, PasswordListActivity.class);
        context.startActivity(i);
    }

    public static void logoutConfirmDialog(Context context) {
    }

    /**
     * Register a broadcast receiver that only receives broadcasts from this
     * application and the system.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Flags are API 33+
    public static void registerNotExportedReceiver(Context context,
                                                   BroadcastReceiver receiver,
                                                   IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter,
                                     Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /**
     * Keep the content of an activity clear of the system bars, display
     * cutouts and the soft keyboard. Starting with Android 15 the activity
     * window is always drawn behind the system bars (edge-to-edge).
     */
    public static void applySystemBarInsets(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        View content = activity.findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() |
                                               WindowInsets.Type.displayCutout() |
                                               WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
        });
    }

    /**
     * Copy a password to the clipboard. The clip is flagged as sensitive so
     * that the clipboard preview (Android 13 and later) does not show it.
     */
    public static void copyPasswordToClipboard(Context context, String password) {
        ClipboardManager clipboard =
            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        ClipData clip = ClipData.newPlainText("password", password);
        PersistableBundle extras = new PersistableBundle();
        // ClipDescription.EXTRA_IS_SENSITIVE, which is only defined in API 33+
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);

        clipboard.setPrimaryClip(clip);
        Session.getInstance().setPasswordInClipboard(true);
    }

    /**
     * Get the name of a document to show to the user.
     */
    public static String getDisplayName(Context context, Uri uri) {
        Cursor cursor = null;

        try {
            cursor = context.getContentResolver().query(uri,
                    new String [] { OpenableColumns.DISPLAY_NAME },
                    null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        String name = uri.getLastPathSegment();
        return name != null ? name : uri.toString();
    }

    public static void debug(String message) {
        Log.d("Passdroid", message);
    }

    public static void error(String message) {
        Log.e("Passdroid", message);
    }

    public static void notice(String message) {
        Log.i("Passdroid", message);
    }

    public static String getVersion(Context context) throws RuntimeException {
        try {
            return context.getPackageManager()
            .getPackageInfo(context.getPackageName(), 0)
            .versionName;
        } catch(NameNotFoundException ex) {
            throw new RuntimeException("Failed to get app version");
        }
    }

    /**
     * Run a slow operation, such as deriving a key from a password, in a
     * background thread while a progress dialog is shown. When the operation
     * has finished done is run on the UI thread, unless the activity has been
     * destroyed in the meantime.
     */
    public static void runWithProgress(final Activity activity, String message,
                                       final Runnable operation,
                                       final Runnable done) {
        final ProgressDialog dialog =
            ProgressDialog.show(activity, null, message, true, false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    operation.run();
                } catch (RuntimeException ex) {
                    ex.printStackTrace();
                }

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isDestroyed()) {
                            return;
                        }
                        dialog.dismiss();
                        done.run();
                    }
                });
            }
        }).start();
    }

    public static String escapeXMLChars(String s) {
        return s.replaceAll("&",  "&amp;")
        .replaceAll("'",  "&apos;")
        .replaceAll("\"", "&quot;")
        .replaceAll("<",  "&lt;")
        .replaceAll(">",  "&gt;");
    }

    public static String unescapeXMLChars(String s) {
        return s.replaceAll("&amp;",  "&")
        .replaceAll("&apos;", "'")
        .replaceAll("&quot;", "\"")
        .replaceAll("&lt;",   "<")
        .replaceAll("&gt;",   ">");
    }
}
