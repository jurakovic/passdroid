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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

/**
 * Activity for adding new passwords.
 */
public class AddActivity extends SherlockTimeoutActivity {
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

    private static final String STATE_DATE = "date";

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

        // The date defaults to when the entry is added
        date = new DateField((EditText) findViewById(R.id.date),
                             findViewById(R.id.date_clear),
                             savedInstanceState != null ?
                                 savedInstanceState.getString(STATE_DATE) :
                                 DateField.now());

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

    private void addPassword() {
        long id = -1;
        EditText v;

        if (!Session.getInstance().isLoggedIn()) {
            Utils.alertDialog(getParent(), "Failure",
            "Adding new entry failed due to session timeout.");
            return;
        }

        PasswordEntry entry = new PasswordEntry();

        v = (EditText) this.findViewById(R.id.system);
        // We require that the user has entered a valid system name
        if (v.getText().toString().matches("^\\s*$")) {
            Utils.alertDialog(this, "Empty system name",
            "Please enter a system name before adding the password.");
            return ;
        }

        entry.setDecSystem(v.getText().toString());		
        v = (EditText) this.findViewById(R.id.email);
        entry.setDecEmail(v.getText().toString());
        v = (EditText) this.findViewById(R.id.username);
        entry.setDecUsername(v.getText().toString());
        v = (EditText) this.findViewById(R.id.password);
        entry.setDecPassword(v.getText().toString());
        v = (EditText) this.findViewById(R.id.totp);
        entry.setDecTotp(v.getText().toString());
        v = (EditText) this.findViewById(R.id.note);
        entry.setDecNote(v.getText().toString());
        v = (EditText) this.findViewById(R.id.url);
        entry.setDecUrl(v.getText().toString());
        entry.setDecDate(date.getValue());

        entry.encryptAll(Session.getInstance().getKey());

        PasswordData passwordData = new PasswordData(this);
        SQLiteDatabase db = passwordData.getWritableDatabase();

        try {
            id = db.insertOrThrow("data", null, PasswordData.getValues(entry));
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        db.close();

        if (id == -1) {
            Utils.alertDialog(this, "Failure", "Adding new entry failed.");
        } else {
            Session.getInstance().setNeedReload(true);
            finish();
        }
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
