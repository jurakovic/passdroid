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
import android.content.UriPermission;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

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

    /**
     * Register a broadcast receiver that only receives broadcasts from this
     * application and the system, on every Android version. Since Android 13
     * the receiver is not exported. Before, it is guarded by a signature
     * permission that only this application holds, which AndroidX declares
     * in the manifest (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION).
     */
    public static void registerNotExportedReceiver(Context context,
                                                   BroadcastReceiver receiver,
                                                   IntentFilter filter) {
        ContextCompat.registerReceiver(context, receiver, filter,
                                       ContextCompat.RECEIVER_NOT_EXPORTED);
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
     * Copy a secret, e.g. a password, 2FA code or note, to the clipboard. The
     * clip is flagged as sensitive so that the clipboard preview (Android 13
     * and later) does not show it, and it is cleared later, see
     * ClipboardClearReceiver.
     */
    public static void copyPasswordToClipboard(Context context, String password) {
        // E.g. it could not be decrypted after a logout
        if (password == null) {
            return;
        }

        ClipboardManager clipboard =
            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        ClipData clip = ClipData.newPlainText(ClipboardClearReceiver.LABEL, password);
        PersistableBundle extras = new PersistableBundle();
        // ClipDescription.EXTRA_IS_SENSITIVE, which is only defined in API 33+
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);

        clipboard.setPrimaryClip(clip);
        Session.getInstance().setPasswordInClipboard(true);
        ClipboardClearReceiver.schedule(context);
    }

    /**
     * Copy a value that is not secret to the clipboard, e.g. a username.
     */
    public static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard =
            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text));

        // A password copied before is no longer in the clipboard
        Session.getInstance().setPasswordInClipboard(false);
        ClipboardClearReceiver.cancel(context);

        // Android 13 and later confirm copying to the clipboard themselves
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Copy the current 2FA code to the clipboard, like a password.
     */
    public static void copyTotpCode(Context context, Totp totp) {
        copyPasswordToClipboard(context, totp.getCode(System.currentTimeMillis()));

        // Android 13 and later confirm copying to the clipboard themselves
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.totp_code_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Delete a document of the Storage Access Framework, e.g. an imported
     * file in the clear.
     *
     * @return false if it could not be deleted.
     */
    public static boolean deleteDocument(Context context, Uri uri) {
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        } catch (Exception ex) {
            // Not all document providers support deleting documents
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * Keep the access to a document that the system file picker granted
     * after the activity that picked it has finished, e.g. to delete it from
     * another screen. Release it with releaseDocumentAccess().
     *
     * @param flags the FLAG_GRANT_READ_URI_PERMISSION and
     *              FLAG_GRANT_WRITE_URI_PERMISSION of the picker's result.
     * @return false if the access can't be kept.
     */
    public static boolean keepDocumentAccess(Context context, Uri uri, int flags) {
        flags &= Intent.FLAG_GRANT_READ_URI_PERMISSION |
                 Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
            return true;
        } catch (SecurityException ex) {
            // The picker didn't grant a persistable access
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * Give up the access that keepDocumentAccess() kept, if any.
     */
    public static void releaseDocumentAccess(Context context, Uri uri) {
        for (UriPermission permission :
             context.getContentResolver().getPersistedUriPermissions()) {
            if (!permission.getUri().equals(uri)) {
                continue;
            }

            int flags = (permission.isReadPermission() ?
                         Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) |
                        (permission.isWritePermission() ?
                         Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            try {
                context.getContentResolver().releasePersistableUriPermission(uri, flags);
            } catch (SecurityException ex) {
                ex.printStackTrace();
            }
        }
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

    /**
     * @return why writing to the database failed, for a message such as
     *         R.string.entry_not_saved.
     */
    public static String describeWriteError(Context context, Exception ex) {
        if (ex instanceof SQLiteFullException) {
            return context.getString(R.string.error_storage_full);
        }
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    /**
     * @return the system of an entry to show, or a placeholder if it could
     *         not be decrypted.
     */
    public static String systemName(Context context, PasswordEntry entry) {
        String system = entry.getDecSystem();
        return system != null ? system : context.getString(R.string.entry_cant_decrypt);
    }

    public static void debug(String message) {
        Log.d("Passdroid", message);
    }

    public static void error(String message) {
        Log.e("Passdroid", message);
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
     * has finished done is run on the UI thread, unless the activity is
     * finishing or has been destroyed in the meantime, e.g. because the user
     * has been logged out.
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
                        if (!activity.isFinishing()) {
                            done.run();
                        }
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
}
