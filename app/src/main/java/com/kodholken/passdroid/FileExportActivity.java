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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.kodholken.passdroid.db.SystemData;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

public class FileExportActivity extends SherlockTimeoutActivity {
    private static final String EXPORT_FILE_NAME = "passdroid_db.xml";

    private static final int CREATE_FILE_RESULT_ID = 1;
    // The damaged entries named before an export
    private static final int MAX_NAMES = 5;

    private Button cancelButton;
    private Button exportButton;
    private CheckBox encryptFileCheckbox;
    // Entries of the last export with a field that couldn't be decrypted
    private int incomplete;
    // The master password is being checked
    private boolean checking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.setContentView(R.layout.file_export);

        cancelButton = (Button) this.findViewById(R.id.cancel_button);
        cancelButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        exportButton = (Button) this.findViewById(R.id.export_button);
        exportButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (encryptFileCheckbox.isChecked()) {
                    confirmDamaged();
                } else {
                    askMasterPassword();
                }
            }
        });

        encryptFileCheckbox = (CheckBox) findViewById(R.id.encrypt_export);
    }

    /**
     * Ask for the master password before an unencrypted export, the whole
     * vault in the clear, so that whoever holds the unlocked app can't take
     * it out without knowing the password. An encrypted export is encrypted
     * with the vault key, so it still needs the password to be read. The
     * fingerprint isn't offered, since it doesn't prove the password is
     * known.
     */
    private void askMasterPassword() {
        View view = View.inflate(this, R.layout.password_dialog, null);
        final EditText field = (EditText) view.findViewById(R.id.dialog_password);
        FontSetting.applyToPasswordField(field);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.export_password_title)
            .setMessage(R.string.export_password_message)
            .setView(view)
            .setPositiveButton(R.string.export, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    checkMasterPassword(field.getText().toString());
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        // A dialog has its own window, without the FLAG_SECURE of the
        // activity that keeps it out of screenshots
        dialog.getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        field.requestFocus();
    }

    /**
     * Check the master password against the stored record, as the password
     * change does with the old one, in the background since it is slow. The
     * right one goes on with the export, a wrong one asks again.
     */
    private void checkMasterPassword(final String password) {
        if (checking) {
            return;
        }

        final String record = new SystemData(this).getKey();
        final boolean [] correct = new boolean[1];

        checking = true;
        Utils.runWithProgress(this, getString(R.string.checking), new Runnable() {
            @Override
            public void run() {
                MasterKey key = MasterKey.unlock(password, record);
                if (key != null) {
                    // Only the check is needed, the session has the key
                    Arrays.fill(key.getKey(), (byte) 0);
                    correct[0] = true;
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                checking = false;
                if (!Session.getInstance().isLoggedIn()) {
                    finish();
                    return;
                }

                if (correct[0]) {
                    confirmDamaged();
                } else {
                    new AlertDialog.Builder(FileExportActivity.this)
                        .setTitle(R.string.export_password_title)
                        .setMessage(R.string.export_password_wrong)
                        .setPositiveButton(R.string.ok, null)
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialog) {
                                if (!isFinishing() && !isDestroyed()) {
                                    askMasterPassword();
                                }
                            }
                        })
                        .show();
                }
            }
        });
    }

    /**
     * Before an export, name the entries with a field that can't be
     * decrypted, since the export can't contain those fields, and let the
     * user export anyway: everything that still decrypts is kept, which is
     * what a damaged vault needs most.
     */
    private void confirmDamaged() {
        byte [] key = Session.getInstance().getKey();
        List<String> names = new ArrayList<String>();
        for (PasswordEntry entry : PasswordModel.getInstance(this).getPasswords()) {
            if (!entry.canDecrypt(key)) {
                names.add(Utils.systemName(this, entry));
            }
        }

        if (names.isEmpty()) {
            selectExportFile();
            return;
        }

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < names.size() && i < MAX_NAMES; i++) {
            if (i > 0) {
                list.append(", ");
            }
            list.append(names.get(i));
        }
        String shown = names.size() <= MAX_NAMES ? list.toString() :
            getString(R.string.export_incomplete_more, list, names.size() - MAX_NAMES);

        new AlertDialog.Builder(this)
            .setTitle(R.string.export_incomplete_title)
            .setMessage(getResources().getQuantityString(R.plurals.export_incomplete_message,
                                                         names.size(), names.size(), shown))
            .setPositiveButton(R.string.export_anyway, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    selectExportFile();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Let the user choose where to save the exported file using the system
     * file picker.
     */
    private void selectExportFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(encryptFileCheckbox.isChecked() ?
                       "application/octet-stream" : "text/xml");
        intent.putExtra(Intent.EXTRA_TITLE, EXPORT_FILE_NAME);

        // The file picker is another application so we give the user more
        // time than usual before being logged out.
        Session.getInstance().setExtendedTimeout(true);
        try {
            startActivityForResult(intent, CREATE_FILE_RESULT_ID);
        } catch (ActivityNotFoundException ex) {
            Session.getInstance().setExtendedTimeout(false);
            Utils.alertDialog(this, getString(R.string.failure),
                              "No application available for saving files.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // This is called before onResume() so check for timeout here as well
        Session.clearTimeoutTimer(this);
        if (!Session.getInstance().isLoggedIn()) {
            finish();
            return;
        }

        if (requestCode == CREATE_FILE_RESULT_ID && resultCode == RESULT_OK &&
            data != null && data.getData() != null) {
            doExport(data.getData());
        }
    }

    private void doExport(Uri uri) {
        String filename = Utils.getDisplayName(this, uri);

        // The whole file is built before the chosen file is opened, since
        // opening it empties it: an export that fails while decrypting or
        // encrypting leaves the file, e.g. an earlier backup, as it was.
        byte [] content;
        try {
            content = buildExport();
        } catch (Exception e) {
            e.printStackTrace();
            Utils.alertDialog(this, getString(R.string.failure),
                              getString(R.string.export_failed_unchanged, describe(e)));
            return;
        }

        OutputStream output = null;
        try {
            // "w" alone does not always truncate an existing file, which
            // would leave the end of a longer file after the export.
            output = getContentResolver().openOutputStream(uri, "wt");
            if (output == null) {
                throw new IOException("Unable to open " + filename);
            }
            output.write(content);

            // Some document providers only store the data when the stream is
            // closed so we need to do that before reporting success.
            output.close();
            output = null;
        } catch (Exception e) {
            // There is no way to replace a document in one step, so the
            // file may have been emptied or written in part
            e.printStackTrace();
            Utils.alertDialog(this, getString(R.string.failure),
                              getString(R.string.export_failed_writing, filename,
                                        describe(e)));
            return;
        } finally {
            Arrays.fill(content, (byte) 0);
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {}
            }
        }

        String message = getString(encryptFileCheckbox.isChecked() ?
                                   R.string.export_successful :
                                   R.string.export_cleartext_successful, filename);
        if (incomplete > 0) {
            message += "\n\n" + getResources().getQuantityString(
                R.plurals.export_incomplete_done, incomplete, incomplete);
        }
        showDialog("Success", message);
    }

    /**
     * @return the content of the export file, encrypted or not. Fields that
     *         can't be decrypted are left out, see confirmDamaged(), and an
     *         entry whose system can't be decrypted is named with the
     *         placeholder, so its other fields come back with an import too.
     */
    private byte [] buildExport() throws ExportException, Base64Exception {
        PasswordEntry [] entries = PasswordModel.getInstance(this).getDecryptedPasswords();
        incomplete = 0;
        for (PasswordEntry entry : entries) {
            if (!entry.isDecrypted()) {
                incomplete++;
            }
            if (entry.getDecSystem() == null) {
                entry.setDecSystem(getString(R.string.entry_cant_decrypt));
            }
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        FileExporter exporter = new FileExporter(buffer, Utils.getVersion(this));
        exporter.setSettings(SettingsBackup.get(this));
        if (encryptFileCheckbox.isChecked()) {
            // The file is encrypted with the database key, so the salt
            // and iteration count it was derived with are stored in the
            // file as well.
            String record = new SystemData(this).getKey();
            if (MasterKey.isLegacy(record)) {
                exporter.exportEncryptedLegacy(Session.getInstance().getKey(), entries);
            } else {
                exporter.exportEncrypted(Session.getInstance().getKey(),
                                         MasterKey.getSalt(record),
                                         MasterKey.getIterations(record),
                                         entries);
            }
        } else {
            exporter.exportCleartext(entries);
        }

        return buffer.toByteArray();
    }

    /** @return the message of an exception, or its name if it has none. */
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void showDialog(String title, String message) {
        AlertDialog alertDialog;

        alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                finish();
            } });
        alertDialog.show();
    }
}
