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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

/**
 * Activity that displays a password entry and let the user choose to edit or
 * delete the entry.
 */
public class ShowActivity extends SherlockTimeoutActivity {	
    private static final int REQUEST_EDIT = 1;

    private long passwordId;
    private String system;
    private String email;
    private String username;
    private String password;
    private String totp;
    private String note;
    private String url;
    private String date;

    // Updates the 2FA code while the activity is in the foreground
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable codeUpdater = new Runnable() {
        @Override
        public void run() {
            showTotpCode();
            // Right after the next full second
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();

        passwordId = extras.getLong("id");
        readEntry(extras);

        this.setContentView(R.layout.show);

        // The password is hidden until the user chooses to show it
        new PasswordToggle((TextView) findViewById(R.id.password),
                           (ImageButton) findViewById(R.id.password_toggle),
                           false);
        new PasswordToggle((TextView) findViewById(R.id.totp),
                           (ImageButton) findViewById(R.id.totp_toggle),
                           false);

        showEntry();
        setupClipboardActions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The activity finishes if the user has been logged out
        if (!isFinishing() && totp != null && totp.length() > 0) {
            handler.post(codeUpdater);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(codeUpdater);
        super.onPause();
    }

    private void readEntry(Bundle extras) {
        system = extras.getString("system");
        email = extras.getString("email");
        username = extras.getString("username");
        password = extras.getString("password");
        totp = extras.getString("totp");
        note = extras.getString("note");
        url = extras.getString("url");
        date = extras.getString("date");
    }

    private void showEntry() {
        showField(R.id.system_title, R.id.system, system);
        showField(R.id.email_title, R.id.email, email);
        showField(R.id.username_title, R.id.username, username);
        ((TextView) findViewById(R.id.password)).setText(password);
        ((TextView) findViewById(R.id.totp)).setText(totp);
        findViewById(R.id.totp_rows).setVisibility(
                totp != null && totp.length() > 0 ? View.VISIBLE : View.GONE);
        showTotpCode();
        showField(R.id.note_title, R.id.note, note);
        showField(R.id.url_title, R.id.url, url);
        showField(R.id.date_title, R.id.date, DateField.format(this, date));
    }

    /**
     * Show the current 2FA code and how long it is valid.
     */
    private void showTotpCode() {
        TextView codeView = (TextView) findViewById(R.id.totp_code);
        TextView secondsView = (TextView) findViewById(R.id.totp_seconds_left);

        Totp generator = Totp.parse(totp);
        if (generator == null) {
            codeView.setText(R.string.totp_invalid);
            secondsView.setVisibility(View.GONE);
            return;
        }

        long now = System.currentTimeMillis();
        codeView.setText(Totp.format(generator.getCode(now)));
        secondsView.setText(getString(R.string.totp_seconds_left,
                                      generator.getSecondsLeft(now)));
        secondsView.setVisibility(View.VISIBLE);
    }

    /**
     * Show a field with its title, or hide them if the field is empty.
     */
    private void showField(int titleId, int valueId, String value) {
        int visibility = value != null && value.length() > 0 ? View.VISIBLE :
                                                               View.GONE;
        TextView valueView = (TextView) findViewById(valueId);
        valueView.setText(value);
        valueView.setVisibility(visibility);
        findViewById(titleId).setVisibility(visibility);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Show the saved changes
        if (requestCode == REQUEST_EDIT && resultCode == RESULT_OK &&
            data != null) {
            readEntry(data.getExtras());
            showEntry();
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Edit")
            .setIcon(R.drawable.ic_menu_edit)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

        menu.add("Delete")
            .setIcon(R.drawable.ic_menu_delete) 
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
    
        return super.onCreateOptionsMenu(menu);
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // User press home back button
            finish();
            overridePendingTransition(0, 0);
            return true;
        }
        
        String option = item.getTitle().toString();
        
        if (option.equals("Edit")) {
            editPassword();
            return true;
        } else if (option.equals("Delete")) {
            confirmDelete();
            return true;
        }
        
        return false;
    }

    /**
     * Set up copying the values to the clipboard with a click. The password
     * and the 2FA secret are copied after a confirmation, which also works
     * while they are hidden. The other values are copied right away, except
     * the URL, which opens in the browser, and the date.
     */
    private void setupClipboardActions() {
        OnClickListener copyText = new OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.copyToClipboard(ShowActivity.this,
                                      ((TextView) v).getText().toString());
            }
        };

        for (int id : new int [] { R.id.system, R.id.email, R.id.username, R.id.note }) {
            findViewById(id).setOnClickListener(copyText);
        }

        findViewById(R.id.password).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmCopy("Copy the password to clipboard?", password);
            }
        });

        findViewById(R.id.totp).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmCopy(getString(R.string.copy_totp_secret), totp);
            }
        });

        findViewById(R.id.totp_code_row).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTotpCode();
            }
        });
    }

    private void confirmCopy(String message, final String value) {
        final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Copy to clipboard");
        alertDialog.setMessage(message);

        alertDialog.setButton(AlertDialog.BUTTON1, "Yes",
                new DialogInterface.OnClickListener() { 
            public void onClick(DialogInterface dialog, int which) {
                Utils.copyPasswordToClipboard(ShowActivity.this, value);
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON2, "No",
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {}
        });

        alertDialog.show();
    }

    private void copyTotpCode() {
        Totp generator = Totp.parse(totp);
        if (generator != null) {
            Utils.copyTotpCode(this, generator);
        }
    }

    /**
     * @return intent that shows an entry.
     */
    public static Intent createIntent(Context context, PasswordEntry entry) {
        Intent i = new Intent(context, ShowActivity.class);
        i.putExtra("id",       entry.getId());
        i.putExtra("system",   entry.getDecSystem());
        i.putExtra("email",    entry.getDecEmail());
        i.putExtra("username", entry.getDecUsername());
        i.putExtra("password", entry.getDecPassword());
        i.putExtra("totp",     entry.getDecTotp());
        i.putExtra("note",     entry.getDecNote());
        i.putExtra("url",      entry.getDecUrl());
        i.putExtra("date",     entry.getDecDate());
        return i;
    }

    private void editPassword() {
        Intent i = new Intent(this, EditActivity.class);
        i.putExtra("id", passwordId);
        i.putExtra("system", system);
        i.putExtra("email", email);
        i.putExtra("username", username);
        i.putExtra("password", password);
        i.putExtra("totp", totp);
        i.putExtra("note", note);
        i.putExtra("url", url);
        i.putExtra("date", date);

        // Back returns here, saving returns here with the changes
        startActivityForResult(i, REQUEST_EDIT);
    }

    private void confirmDelete() {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Confirm delete");
        alertDialog.setMessage("Are your sure you want to delete "+system+"?");

        alertDialog.setButton(AlertDialog.BUTTON1, "Yes",
                new DialogInterface.OnClickListener() { 
            public void onClick(DialogInterface dialog, int which) {
                deletePassword();
                Session.getInstance().setNeedReload(true);
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON2, "No", 
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        alertDialog.show();
    }

    private void deletePassword() {
        PasswordData passwordData = new PasswordData(this);
        SQLiteDatabase db = passwordData.getWritableDatabase();

        try {
            db.delete("data", "id=" + passwordId, null);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        db.close();

        finish();
    }
    
    // Only called before Android 13. Later versions use the default back
    // handling, including its animation.
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(0, 0);
    }
}
