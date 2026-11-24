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

import com.kodholken.passdroid.R;
import com.kodholken.passdroid.db.PasswordData;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Activity for adding new passwords.
 */
public class AddActivity extends SherlockTimeoutActivity {
    // Set to fill the fields from the stored draft of a new entry, see
    // EntryDraft
    public static final String EXTRA_DRAFT = "draft";

    private Button saveButton;
    private Button generateButton;
    private EditText password;
    private PasswordToggle passwordToggle;
    // The last password the generate button put in, which it replaces
    // without asking
    private String lastGenerated;
    private DateField date;
    private QrScan qrScan;
    private Intent generateIntent;
    // The values the screen opened with, null after a logout
    private PasswordEntry initialValues;

    private static final String STATE_DATE = "date";
    // Kept across a rotation by RetainedText instead of the saved state
    private static final int [] SECRET_FIELDS = {
        R.id.password, R.id.totp, R.id.note
    };
    // Every text field, except the date
    private static final int [] TEXT_FIELDS = {
        R.id.system, R.id.email, R.id.username, R.id.password, R.id.totp,
        R.id.note, R.id.url
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.add);
        FontSetting.apply(this, R.id.system, R.id.email, R.id.username,
                          R.id.password, R.id.totp, R.id.note, R.id.url,
                          R.id.date);

        password = (EditText) findViewById(R.id.password);
        // A new password is visible while it is entered
        passwordToggle = new PasswordToggle(password,
                (ImageButton) findViewById(R.id.password_toggle), true);
        new PasswordToggle((EditText) findViewById(R.id.totp),
                           (ImageButton) findViewById(R.id.totp_toggle), true);
        qrScan = new QrScan(this, findViewById(R.id.totp_scan));
        Object retainedValues = RetainedText.restore(this,
                getLastNonConfigurationInstance(), SECRET_FIELDS);

        // The date defaults to when the entry is added
        date = new DateField((EditText) findViewById(R.id.date),
                             findViewById(R.id.date_clear),
                             savedInstanceState != null ?
                                 savedInstanceState.getString(STATE_DATE) :
                                 DateField.now());

        if (retainedValues instanceof PasswordEntry) {
            // Rotated: the changes still count as changes
            initialValues = (PasswordEntry) retainedValues;
        } else {
            initialValues = readFields();
            if (savedInstanceState == null &&
                getIntent().getBooleanExtra(EXTRA_DRAFT, false)) {
                restoreDraft();
            }
        }
        Session.getInstance().addLogoutListener(logoutListener);
        registerBackCallback();

        saveButton = (Button) this.findViewById(R.id.save_button);

        saveButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                addPassword();
            }
        });

        setupGenerateButton();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DATE, date.getValue());
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return RetainedText.collect(this, SECRET_FIELDS, initialValues);
    }

    /**
     * @return the values of the fields, decrypted.
     */
    private PasswordEntry readFields() {
        PasswordEntry values = new PasswordEntry();
        values.setDecSystem(text(R.id.system));
        values.setDecEmail(text(R.id.email));
        values.setDecUsername(text(R.id.username));
        values.setDecPassword(text(R.id.password));
        values.setDecTotp(text(R.id.totp));
        values.setDecNote(text(R.id.note));
        values.setDecUrl(text(R.id.url));
        values.setDecDate(date.getValue());
        return values;
    }

    private String text(int id) {
        return ((TextView) findViewById(id)).getText().toString();
    }

    private void fillFields(PasswordEntry values) {
        ((TextView) findViewById(R.id.system)).setText(values.getDecSystem());
        ((TextView) findViewById(R.id.email)).setText(values.getDecEmail());
        ((TextView) findViewById(R.id.username)).setText(values.getDecUsername());
        ((TextView) findViewById(R.id.password)).setText(values.getDecPassword());
        ((TextView) findViewById(R.id.totp)).setText(values.getDecTotp());
        ((TextView) findViewById(R.id.note)).setText(values.getDecNote());
        ((TextView) findViewById(R.id.url)).setText(values.getDecUrl());
        date.setValue(values.getDecDate());
    }

    /**
     * Fill the fields with the stored draft, see EXTRA_DRAFT: of a new
     * entry, or of one that is gone since. Nothing changes if the draft is
     * gone or doesn't decrypt.
     */
    private void restoreDraft() {
        EntryDraft.Draft draft = EntryDraft.load(this, Session.getInstance().getKey());
        if (draft != null) {
            fillFields(draft.values);
        }
    }

    /**
     * @return true if the values differ from the ones the screen opened
     *         with.
     */
    private boolean hasChanges() {
        return initialValues != null &&
               EntryDraft.differ(initialValues, readFields());
    }

    /**
     * Stores the changes as a draft before the key is wiped, whatever the
     * reason for the logout, and empties the fields: the screen is finished
     * after a logout, and the values shouldn't stay in memory until then.
     */
    private final Session.LogoutListener logoutListener = new Session.LogoutListener() {
        @Override
        public void beforeLogout(byte [] key) {
            // A screen that is finishing has been saved or discarded,
            // although it is only destroyed later
            if (!isFinishing() && hasChanges()) {
                EntryDraft.save(AddActivity.this, key, readFields(), -1);
            }
            initialValues = null;
            lastGenerated = null;
            for (int id : TEXT_FIELDS) {
                ((TextView) findViewById(id)).setText("");
            }
            date.setValue("");
        }
    };

    @Override
    protected void onResume() {
        super.onResume();

        // Back from the generator, the scanner or another app, without a
        // logout: the screen still has the changes a draft was stored for
        if (!isFinishing() && Session.getInstance().isLoggedIn()) {
            EntryDraft.delete(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Android may kill the process in the background without a logout.
        // A rotation and a finished screen don't leave anything to keep.
        if (!isFinishing() && !isChangingConfigurations() &&
            Session.getInstance().isLoggedIn() && hasChanges()) {
            EntryDraft.save(this, Session.getInstance().getKey(), readFields(),
                            -1);
        }
    }

    @Override
    protected void onDestroy() {
        Session.getInstance().removeLogoutListener(logoutListener);
        super.onDestroy();
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

    /**
     * Close the screen, after asking if there are unsaved changes.
     */
    private void handleBack() {
        if (!hasChanges()) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
            .setMessage(R.string.discard_changes)
            .setPositiveButton(R.string.discard, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    EntryDraft.delete(AddActivity.this);
                    finish();
                }
            })
            .setNegativeButton(R.string.keep_editing, null)
            .show();
    }

    private void registerBackCallback() {
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

    /**
     * Store the new entry and close the screen, or show why it failed and
     * keep what was typed.
     */
    private void addPassword() {
        byte [] key = Session.getInstance().getKey();
        if (!Session.getInstance().isLoggedIn() || key == null) {
            showNotSaved(getString(R.string.error_logged_out));
            return;
        }

        PasswordEntry entry = readFields();
        // We require that the user has entered a valid system name
        if (entry.getDecSystem().matches("^\\s*$")) {
            Utils.alertDialog(this, "Empty system name",
            "Please enter a system name before adding the password.");
            return ;
        }

        entry.encryptAll(key);
        if (!entry.isEncrypted()) {
            showNotSaved(getString(R.string.error_not_encrypted));
            return;
        }

        SQLiteDatabase db = null;
        try {
            db = new PasswordData(this).getWritableDatabase();
            db.insertOrThrow("data", null, PasswordData.getValues(entry));
        } catch (SQLException ex) {
            ex.printStackTrace();
            showNotSaved(Utils.describeWriteError(this, ex));
            return;
        } finally {
            if (db != null) {
                db.close();
            }
        }

        EntryDraft.delete(this);
        Session.getInstance().setNeedReload(true);
        finish();
    }

    private void showNotSaved(String reason) {
        Utils.alertDialog(this, getString(R.string.failure),
                          getString(R.string.entry_not_saved, reason));
    }

    private void setupGenerateButton() {
        generateButton = (Button) findViewById(R.id.generate_button);
        generateButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                quickGenerate();
            }
        });

        findViewById(R.id.generator_options).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                createGenerateActivity();
            }
        });
    }

    /**
     * Generate a password with the generator's last settings, without
     * opening it. Asks before replacing a password that wasn't generated
     * here, such as a stored or a typed one.
     */
    private void quickGenerate() {
        String current = password.getText().toString();
        if (!current.equals(lastGenerated)) {
            // Replaced since, so it is not kept any longer
            lastGenerated = null;
        }
        if (current.length() == 0 || lastGenerated != null) {
            setGeneratedPassword();
            return;
        }

        new AlertDialog.Builder(this)
            .setMessage(R.string.replace_password)
            .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setGeneratedPassword();
                }
            })
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void setGeneratedPassword() {
        lastGenerated = PasswordGenerator.generateWithStoredSettings(this);
        password.setText(lastGenerated);
        // The new password is shown, so the change doesn't go unnoticed
        passwordToggle.setVisible(true);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Save")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        
        return super.onCreateOptionsMenu(menu);
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!item.getTitle().equals("Save")) {
            return super.onOptionsItemSelected(item);
        }
        
        addPassword();
        return true;
    }

    private void createGenerateActivity() {
        generateIntent = new Intent(this, GeneratePasswordActivity.class);
        startActivityForResult(generateIntent, 0x42);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        if (qrScan.onActivityResult(requestCode, resultCode, data)) {
            return;
        }

        if (resultCode == 1) {
            this.password.setText(GeneratePasswordActivity.
                    takeGeneratedPassword());
            lastGenerated = null;
            passwordToggle.setVisible(true);
        }
    }
}
