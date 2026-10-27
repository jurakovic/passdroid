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
 *
 * The password and the 2FA secret are only decrypted when they are shown or
 * copied. The entry is passed by its id, so that no decrypted values are
 * passed through the system in the intent.
 */
public class ShowActivity extends SherlockTimeoutActivity {	
    public static final String EXTRA_ID = "id";

    private static final int REQUEST_EDIT = 1;

    private PasswordEntry entry;  // With the fields of the list decrypted
    private Totp totp;            // null if there is no valid 2FA secret
    private boolean hasTotp;      // The entry has a 2FA secret, maybe invalid
    private PasswordToggle passwordToggle;
    private PasswordToggle totpToggle;

    // Decrypt the password and the 2FA secret when they are shown or copied
    private final PasswordToggle.Source password = new PasswordToggle.Source() {
        @Override
        public String get() {
            return entry.decryptPassword(Session.getInstance().getKey());
        }
    };
    private final PasswordToggle.Source totpSecret = new PasswordToggle.Source() {
        @Override
        public String get() {
            return entry.decryptTotp(Session.getInstance().getKey());
        }
    };

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

        entry = PasswordModel.getInstance(this).getById(
                getIntent().getLongExtra(EXTRA_ID, -1));
        if (entry == null) {
            // E.g. restored after the process was killed, without a login
            finish();
            return;
        }

        this.setContentView(R.layout.show);
        FontSetting.apply(this, R.id.system, R.id.email, R.id.username,
                          R.id.password, R.id.totp, R.id.totp_code, R.id.note,
                          R.id.url, R.id.date);

        // The password is hidden until the user chooses to show it
        passwordToggle = new PasswordToggle(
                (TextView) findViewById(R.id.password),
                (ImageButton) findViewById(R.id.password_toggle), password);
        totpToggle = new PasswordToggle(
                (TextView) findViewById(R.id.totp),
                (ImageButton) findViewById(R.id.totp_toggle), totpSecret);

        showEntry();
        setupClipboardActions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The activity finishes if the user has been logged out
        if (!isFinishing() && hasTotp) {
            handler.post(codeUpdater);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(codeUpdater);
        super.onPause();
    }

    private void showEntry() {
        byte [] key = Session.getInstance().getKey();

        showField(R.id.system_title, R.id.system, entry.getDecSystem());
        showField(R.id.email_title, R.id.email, entry.getDecEmail());
        showField(R.id.username_title, R.id.username, entry.getDecUsername());
        passwordToggle.setVisible(false);

        // Only the code generator is kept for the code, not the secret
        String secret = entry.decryptTotp(key);
        hasTotp = secret != null && secret.length() > 0;
        totp = hasTotp ? Totp.parse(secret) : null;
        totpToggle.setVisible(false);
        findViewById(R.id.totp_rows).setVisibility(hasTotp ? View.VISIBLE :
                                                             View.GONE);
        showTotpCode();

        showField(R.id.note_title, R.id.note, entry.decryptNote(key));
        showField(R.id.url_title, R.id.url, entry.decryptUrl(key));
        showField(R.id.date_title, R.id.date,
                  DateField.format(this, entry.decryptDate(key)));
    }

    /**
     * Show the current 2FA code and how long it is valid.
     */
    private void showTotpCode() {
        TextView codeView = (TextView) findViewById(R.id.totp_code);
        TextView secondsView = (TextView) findViewById(R.id.totp_seconds_left);

        if (totp == null) {
            codeView.setText(R.string.totp_invalid);
            secondsView.setVisibility(View.GONE);
            return;
        }

        long now = System.currentTimeMillis();
        codeView.setText(Totp.format(totp.getCode(now)));
        secondsView.setText(getString(R.string.totp_seconds_left,
                                      totp.getSecondsLeft(now)));
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

        // Show the saved changes, the model loads them again
        if (requestCode == REQUEST_EDIT && resultCode == RESULT_OK) {
            entry = PasswordModel.getInstance(this).getById(entry.getId());
            if (entry == null) {
                finish();
                return;
            }
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
                confirmCopy(getString(R.string.copy_totp_secret), totpSecret);
            }
        });

        findViewById(R.id.totp_code_row).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTotpCode();
            }
        });
    }

    /**
     * Ask before copying a value, which is decrypted only then.
     */
    private void confirmCopy(String message, final PasswordToggle.Source value) {
        final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Copy to clipboard");
        alertDialog.setMessage(message);

        alertDialog.setButton(AlertDialog.BUTTON1, "Yes",
                new DialogInterface.OnClickListener() { 
            public void onClick(DialogInterface dialog, int which) {
                Utils.copyPasswordToClipboard(ShowActivity.this, value.get());
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON2, "No",
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {}
        });

        alertDialog.show();
    }

    private void copyTotpCode() {
        if (totp != null) {
            Utils.copyTotpCode(this, totp);
        }
    }

    /**
     * @return intent that shows an entry.
     */
    public static Intent createIntent(Context context, PasswordEntry entry) {
        Intent i = new Intent(context, ShowActivity.class);
        i.putExtra(EXTRA_ID, entry.getId());
        return i;
    }

    private void editPassword() {
        Intent i = new Intent(this, EditActivity.class);
        i.putExtra(EditActivity.EXTRA_ID, entry.getId());

        // Back returns here, saving returns here with the changes
        startActivityForResult(i, REQUEST_EDIT);
    }

    private void confirmDelete() {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Confirm delete");
        alertDialog.setMessage("Are your sure you want to delete "+entry.getDecSystem()+"?");

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
            db.delete("data", "id=" + entry.getId(), null);
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
