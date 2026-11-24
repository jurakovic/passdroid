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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.kodholken.passdroid.R;
import com.kodholken.passdroid.db.SystemData;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * The welcome screen: sets up the master password, or restores a backup
 * before there is one.
 *
 * A restore reads the file and, for an encrypted one, asks for the password
 * it was exported with. That password can be kept as the master password;
 * otherwise, and for a file in the clear, the backup waits for a master
 * password in the screen's fields. Either way the key record is created as
 * in a setup, and the entries and settings of the file are stored after the
 * login. A waiting backup is dropped when the screen leaves the foreground,
 * so nobody can finish it with a password of their own later.
 */
public class InitializeActivity extends Activity {
    // Kept across a rotation by RetainedText instead of the saved state
    private static final int [] SECRET_FIELDS = {
        R.id.master_password_1, R.id.master_password_2
    };

    private static final int SELECT_BACKUP_REQUEST = 1;

    Button okButton;
    private Button restoreButton;
    private TextView restoreNotice;
    private boolean initializing = false;
    private boolean decrypting = false;
    // Between onStart() and onStop()
    private boolean started = false;
    // A backup that waits for a master password, null if there is none
    private PendingRestore pending;
    // Asks whether to keep the backup's password, while it is open
    private AlertDialog keepDialog;

    /**
     * The decrypted content of a backup to restore.
     */
    private static class PendingRestore {
        final Uri uri;
        final String name;
        final PasswordEntry [] entries;
        final Map<String, String> settings;  // null if the file has none
        final boolean cleartext;
        // The access the file picker granted, see Utils.keepDocumentAccess()
        final int grantFlags;

        PendingRestore(Uri uri, int grantFlags, String name,
                       FileImporter importer, boolean cleartext) {
            this.uri = uri;
            this.grantFlags = grantFlags;
            this.name = name;
            this.entries = importer.getPasswordEntries();
            this.settings = importer.getSettings();
            this.cleartext = cleartext;
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        ThemeSetting.apply(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Kept out of screenshots, screen recordings and the recent apps,
        // like the login screen: the new master password is typed here and
        // can be shown with the eye buttons, and a backup being restored is
        // decrypted here. This screen doesn't extend a timeout base class,
        // which sets it.
        getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);

        setContentView(R.layout.initialize);

        new PasswordLengthHint((EditText) findViewById(R.id.master_password_1),
                               (TextView) findViewById(R.id.master_password_hint));

        // Hidden again after a rotation; the fields keep their text
        new PasswordToggle((EditText) findViewById(R.id.master_password_1),
                           (ImageButton) findViewById(R.id.master_password_1_toggle),
                           false);
        new PasswordToggle((EditText) findViewById(R.id.master_password_2),
                           (ImageButton) findViewById(R.id.master_password_2_toggle),
                           false);
        Object retained = RetainedText.restore(this,
                getLastNonConfigurationInstance(), SECRET_FIELDS);
        if (retained instanceof PendingRestore) {
            pending = (PendingRestore) retained;
        }

        okButton = (Button) this.findViewById(R.id.ok_button);
        okButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                handleOK();
            }
        });

        restoreNotice = (TextView) findViewById(R.id.restore_notice);
        restoreButton = (Button) findViewById(R.id.restore_button);
        restoreButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pending != null) {
                    dropRestore();
                } else {
                    selectBackup();
                }
            }
        });
        updateRestoreViews();

        Utils.applySystemBarInsets(this);

        // From Android 13 back events are delivered through callbacks instead
        // of onBackPressed() (see android:enableOnBackInvokedCallback).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            handleBack();
                        }
                    });
        }
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return RetainedText.collect(this, SECRET_FIELDS, pending);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;

        // Decrypted entries don't wait in the background, only across a
        // rotation
        if (!isChangingConfigurations()) {
            dropRestore();
        }
    }

    /**
     * Back handler for Android 12 and earlier. Later versions use the
     * callback registered in onCreate().
     */
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        // Back exits the application, as on the login screen. Without the
        // flag MainActivity would find no master key in onResume() and start
        // this screen again.
        Session.getInstance().setExitMain(true);
        finish();
    }

    private void handleOK() {
        String p1;
        EditText p2;


        p1 = ((EditText) findViewById(R.id.master_password_1)).getText().toString();
        p2 = (EditText) findViewById(R.id.master_password_2);

        if (!p1.equals(p2.getText().toString())) {
            Utils.alertDialog(this, getString(R.string.initialize_mismatch_title), getString(R.string.initialize_mismatch_text));
        } else if (MasterKey.isTooShort(p1)) {
            Utils.alertDialog(this, getString(R.string.master_password_too_short_title),
                              getString(R.string.master_password_too_short,
                                        MasterKey.MIN_PASSWORD_LENGTH));
        } else {
            initialize(p1, false);
        }
    }

    /**
     * Create the master key and log in, and restore the waiting backup if
     * there is one.
     *
     * @param shortPassword true if the password is shorter than is required
     *                      for new master passwords, as a kept backup
     *                      password may be.
     */
    private void initialize(final String masterPassword,
                            final boolean shortPassword) {
        if (initializing) {
            return;
        }

        final MasterKey [] masterKey = new MasterKey[1];
        final PendingRestore restore = pending;

        initializing = true;
        Utils.runWithProgress(this, "Creating key…", new Runnable() {
            @Override
            public void run() {
                masterKey[0] = MasterKey.create(masterPassword);
            }
        }, new Runnable() {
            @Override
            public void run() {
                initializing = false;
                if (masterKey[0] == null) {
                    Utils.alertDialog(InitializeActivity.this, "Failure",
                                      "Failed to create the master key.");
                    return;
                }

                if (restore != null && restore != pending) {
                    // Dropped meanwhile, when the screen left the foreground
                    Arrays.fill(masterKey[0].getKey(), (byte) 0);
                    return;
                }

                SystemData system = new SystemData(InitializeActivity.this);
                system.setAttribute("key", masterKey[0].getRecord());

                Session.getInstance().setKey(masterKey[0].getKey());
                Session.getInstance().setLoggedIn();
                if (restore != null) {
                    finishRestore(restore, shortPassword);
                    return;
                }
                Utils.startPasswordActivity(InitializeActivity.this);
                finish();
            }
        });
    }

    /**
     * Let the user pick the backup with the system file picker. Nobody is
     * logged in, so there is no timeout to extend.
     */
    private void selectBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Encrypted files have no well-known MIME type
        intent.setType("*/*");

        try {
            startActivityForResult(intent, SELECT_BACKUP_REQUEST);
        } catch (ActivityNotFoundException ex) {
            Utils.alertDialog(this, getString(R.string.failure),
                              getString(R.string.no_file_app));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != SELECT_BACKUP_REQUEST || resultCode != RESULT_OK ||
            data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        int grantFlags = data.getFlags();
        String name = Utils.getDisplayName(this, uri);
        try {
            FileImporter importer = new FileImporter(FileImporter.read(getContentResolver(), uri));
            if (importer.isEncrypted()) {
                askBackupPassword(importer, uri, grantFlags, name, false);
            } else {
                importer.parse();
                setRestore(new PendingRestore(uri, grantFlags, name, importer, true));
            }
        } catch (Exception ex) {
            // FileImporterException, or IOException when reading the file
            ex.printStackTrace();
            showReadError(name, ex);
        }
    }

    /**
     * Ask for the password an encrypted backup was exported with.
     *
     * @param wrong true if the password entered before was wrong.
     */
    private void askBackupPassword(final FileImporter importer, final Uri uri,
                                   final int grantFlags, final String name,
                                   boolean wrong) {
        View view = View.inflate(this, R.layout.password_dialog, null);
        final EditText field = (EditText) view.findViewById(R.id.dialog_password);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.restore_password_title)
            .setMessage(wrong ? R.string.restore_password_wrong :
                                R.string.restore_password_message)
            .setView(view)
            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    decryptBackup(importer, uri, grantFlags, name,
                                  field.getText().toString());
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .create();
        // A dialog has its own window, without the FLAG_SECURE of the
        // activity that keeps it out of screenshots
        dialog.getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        field.requestFocus();
    }

    /**
     * Decrypt the backup with a password in the background, since deriving
     * the key is slow.
     */
    private void decryptBackup(final FileImporter importer, final Uri uri,
                               final int grantFlags, final String name,
                               final String password) {
        if (decrypting) {
            return;
        }

        final FileImporterException [] error = new FileImporterException[1];

        decrypting = true;
        Utils.runWithProgress(this, "Decrypting…", new Runnable() {
            @Override
            public void run() {
                try {
                    importer.parseEncryptedWithPassword(password);
                } catch (FileImporterException ex) {
                    ex.printStackTrace();
                    error[0] = ex;
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                decrypting = false;
                if (!started) {
                    // Left meanwhile: the entries don't wait in the
                    // background
                    return;
                }

                if (error[0] == null) {
                    setRestore(new PendingRestore(uri, grantFlags, name, importer, false));
                    askKeepPassword(password);
                } else if (error[0].isWrongKey()) {
                    askBackupPassword(importer, uri, grantFlags, name, true);
                } else {
                    // E.g. a newer file format
                    showReadError(name, error[0]);
                }
            }
        });
    }

    /**
     * Offer to keep the backup's password as the master password. Otherwise
     * the backup waits for one in the screen's fields.
     */
    private void askKeepPassword(final String password) {
        boolean tooShort = MasterKey.isTooShort(password);
        String message = tooShort ?
                getString(R.string.restore_keep_message_short,
                          MasterKey.MIN_PASSWORD_LENGTH) :
                getString(R.string.restore_keep_message);

        final PendingRestore restore = pending;
        keepDialog = new AlertDialog.Builder(this)
            .setTitle(R.string.restore_keep_title)
            .setMessage(message)
            .setPositiveButton(R.string.restore_keep, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (restore != pending) {
                        // Dropped meanwhile; without a backup this would set
                        // up an empty vault
                        return;
                    }
                    // A fresh key record, with a new salt and the current
                    // iteration count, also for a legacy "sqt" export
                    initialize(password, MasterKey.isTooShort(password));
                }
            })
            .setNegativeButton(R.string.restore_new, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    findViewById(R.id.master_password_1).requestFocus();
                }
            })
            .show();
    }

    /**
     * Store the entries and the settings of the backup once the key record
     * is stored and the user is logged in, and open the list. If the
     * entries can't be stored, the setup is undone, so there is never a
     * vault without them.
     */
    private void finishRestore(PendingRestore restore, boolean shortPassword) {
        pending = null;
        updateRestoreViews();

        if (!PasswordModel.getInstance(this).setPasswords(restore.entries)) {
            new SystemData(this).removeAttribute("key");
            Session.getInstance().setLoggedIn(false);
            Utils.alertDialog(this, getString(R.string.failure),
                              getString(R.string.restore_failed));
            return;
        }

        Intent intent = new Intent(this, PasswordListActivity.class);
        intent.putExtra(PasswordListActivity.EXTRA_SHORT_PASSWORD, shortPassword);
        // The list reports the restore, so that its timeouts already apply
        // while the message is open
        intent.putExtra(PasswordListActivity.EXTRA_RESTORED, restore.entries.length);
        if (restore.settings != null) {
            List<SettingsBackup.Change> changes = SettingsBackup.apply(this, restore.settings);
            if (!changes.isEmpty()) {
                intent.putExtra(PasswordListActivity.EXTRA_RESTORED_SETTINGS,
                                SettingsBackup.describe(this, changes));
            }
        }
        if (restore.cleartext) {
            // The list asks whether to delete the file. The access the file
            // picker granted ends with this screen, so it is kept until then.
            Utils.keepDocumentAccess(this, restore.uri, restore.grantFlags);
            intent.putExtra(PasswordListActivity.EXTRA_RESTORED_FILE, restore.uri);
        }
        startActivity(intent);
        finish();
    }

    private void setRestore(PendingRestore restore) {
        pending = restore;
        updateRestoreViews();
    }

    private void dropRestore() {
        if (pending != null) {
            pending = null;
            updateRestoreViews();
        }
        // The question is about the backup that was dropped
        if (keepDialog != null) {
            keepDialog.dismiss();
            keepDialog = null;
        }
    }

    /**
     * Show the notice and the button's label for the waiting backup, or for
     * none.
     */
    private void updateRestoreViews() {
        if (pending == null) {
            restoreNotice.setVisibility(View.GONE);
            restoreButton.setText(R.string.restore_backup);
        } else {
            restoreNotice.setText(getResources().getQuantityString(
                    R.plurals.restore_notice, pending.entries.length,
                    pending.name, pending.entries.length));
            restoreNotice.setVisibility(View.VISIBLE);
            restoreButton.setText(R.string.restore_cancel);
        }
    }

    /**
     * Report a file that can not be restored, as the import does.
     */
    private void showReadError(String name, Exception ex) {
        Utils.alertDialog(this, getString(R.string.failure),
                          getString(R.string.import_description_failure)
                              .replace("%f", name)
                              .replace("%e", String.valueOf(ex.getMessage())));
    }
}
