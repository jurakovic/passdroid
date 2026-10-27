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
import android.widget.TextView;

public class EditActivity extends SherlockTimeoutActivity {
    // The id of the entry, see ShowActivity
    public static final String EXTRA_ID = "id";

    private Button saveButton;
    private Button generateButton;
    private EditText password;
    private EditText note;
    private EditText url;
    private DateField date;
    private QrScan qrScan;

    private Intent generateIntent;
    private long passwordId;

    private static final String STATE_DATE = "date";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        passwordId = getIntent().getLongExtra(EXTRA_ID, -1);
        PasswordEntry stored = PasswordModel.getInstance(this).getById(passwordId);
        if (stored == null) {
            // E.g. restored after the process was killed, without a login
            finish();
            return;
        }
        PasswordEntry entry = stored.decryptedCopy(Session.getInstance().getKey());

        setContentView(R.layout.add);
        FontSetting.apply(this, R.id.system, R.id.email, R.id.username,
                          R.id.password, R.id.totp, R.id.note, R.id.url,
                          R.id.date);

        ((TextView) findViewById(R.id.system)).setText(entry.getDecSystem());
        ((TextView) findViewById(R.id.email)).setText(entry.getDecEmail());
        ((TextView) findViewById(R.id.username)).setText(entry.getDecUsername());

        password = (EditText) findViewById(R.id.password);
        password.setText(entry.getDecPassword());
        // The password is hidden until the user chooses to show it
        new PasswordToggle(password, (ImageButton) findViewById(R.id.password_toggle),
                           false);

        EditText totp = (EditText) findViewById(R.id.totp);
        totp.setText(entry.getDecTotp());
        new PasswordToggle(totp, (ImageButton) findViewById(R.id.totp_toggle), false);
        qrScan = new QrScan(this, findViewById(R.id.totp_scan));
        
        note = (EditText) findViewById(R.id.note);
        if (entry.getDecNote() != null) {
            note.setText(entry.getDecNote());
        }

        url = (EditText) findViewById(R.id.url);
        if (entry.getDecUrl() != null) {
            url.setText(entry.getDecUrl());
        }

        date = new DateField((EditText) findViewById(R.id.date),
                             findViewById(R.id.date_clear),
                             savedInstanceState != null ?
                                 savedInstanceState.getString(STATE_DATE) :
                                 entry.getDecDate());

        saveButton = (Button) this.findViewById(R.id.save_button);
        saveButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                updatePassword();
                Session.getInstance().setNeedReload(true);
                Utils.debug("Save Clicked");
            }
        });

        setupGenerateButton();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DATE, date.getValue());
    }

    private void updatePassword() {
        String system   = ((TextView) findViewById(R.id.system)).getText().toString();
        // We require a valid system name, as when adding the entry
        if (system.matches("^\\s*$")) {
            Utils.alertDialog(this, "Empty system name",
            "Please enter a system name before saving the password.");
            return;
        }

        String email    = ((TextView) findViewById(R.id.email)).getText().toString();
        String username = ((TextView) findViewById(R.id.username)).getText().toString();
        String password = ((TextView) findViewById(R.id.password)).getText().toString();
        String totp = ((TextView) findViewById(R.id.totp)).getText().toString();
        String note = ((TextView) findViewById(R.id.note)).getText().toString();
        String url = ((TextView) findViewById(R.id.url)).getText().toString();
        String date = this.date.getValue();

        PasswordEntry entry = new PasswordEntry();
        entry.setDecSystem(system);
        entry.setDecEmail(email);
        entry.setDecUsername(username);
        entry.setDecPassword(password);
        entry.setDecTotp(totp);
        entry.setDecNote(note);
        entry.setDecUrl(url);
        entry.setDecDate(date);
        entry.encryptAll(Session.getInstance().getKey());

        PasswordData passwordData = new PasswordData(this);
        SQLiteDatabase db = passwordData.getWritableDatabase();

        try {
            db.update("data", PasswordData.getValues(entry), "id=" + passwordId, null);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        db.close();

        // ShowActivity shows the changes
        setResult(RESULT_OK);

        finish();
    }

    private void setupGenerateButton() {
        generateButton = (Button) findViewById(R.id.generate_button);
        generateButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                createGenerateActivity();
            }
        });
    }

    private void createGenerateActivity() {
        generateIntent = new Intent(this, GeneratePasswordActivity.class);
        startActivityForResult(generateIntent, 0x42);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        //super.onActivityResult(requestCode, resultCode, data);
        if (qrScan.onActivityResult(requestCode, resultCode, data)) {
            return;
        }

        if (resultCode == 1) {
            this.password.setText(GeneratePasswordActivity.takeGeneratedPassword());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Save")
        	.setIcon(R.drawable.ic_menu_save)
        	.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!item.getTitle().equals("Save")) {
            return super.onOptionsItemSelected(item);
        }

        updatePassword();
        Session.getInstance().setNeedReload(true);
        return true;
    }
}
