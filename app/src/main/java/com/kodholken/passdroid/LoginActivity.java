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

import com.kodholken.passdroid.R;
import com.kodholken.passdroid.db.DbMigration;
import com.kodholken.passdroid.db.SystemData;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Activity that displays the login screen and verifies the master password.
 */
public class LoginActivity extends Activity {
    private Button loginButton;
    private EditText passwordView;
    private boolean loggingIn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // If we are already logged in we are done. This will return control
        // to the MainActivity which is responsible for creating and launching
        // a PasswordActivity instance.
        if (Session.getInstance().isLoggedIn()) {
            finish();
        }

        setContentView(R.layout.login);

        passwordView = (EditText) findViewById(R.id.login_password);
        passwordView.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        handleLogin();
                        return true;
                    }
                }
                return false;
            }
        });

        loginButton = (Button) this.findViewById(R.id.login_button);

        loginButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                handleLogin();
            }
        });

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
    protected void onResume() {
        super.onResume();

        if (Session.getInstance().isLoggedIn()) {
            finish();
            return ;
        }

        ((EditText) this.findViewById(R.id.login_password)).setText("");
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
        // A back key on the login menu should exit the application
        // so we need to indicate to MainActivity that it should exit
        // in onResume()
        Session.getInstance().setExitMain(true);
        finish();
    }

    /**
     * Handler for when the user press the login button after entering the
     * master password. If the correct password is entered we flag the user as
     * logged in and return control to the MainActivity which will launch the
     * 
     */
    private void handleLogin() {
        if (loggingIn) {
            return;
        }

        final String password = passwordView.getText().toString();
        final String record = new SystemData(this).getKey();
        final MasterKey [] keys = new MasterKey[2];

        loggingIn = true;
        Utils.runWithProgress(this, "Logging in…", new Runnable() {
            @Override
            public void run() {
                keys[0] = MasterKey.unlock(password, record);

                // Databases from Passdroid 2.3 and earlier are upgraded to the
                // current key derivation, which requires the password.
                if (keys[0] != null && MasterKey.isLegacy(record)) {
                    keys[1] = MasterKey.create(password);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                loggingIn = false;
                loginDone(keys[0], keys[1]);
            }
        });
    }

    /**
     * @param masterKey   key derived from the entered password. null if the
     *                    password was incorrect.
     * @param upgradedKey new key to re-encrypt a legacy database with, or null.
     */
    private void loginDone(MasterKey masterKey, MasterKey upgradedKey) {
        if (masterKey == null) {
            Utils.alertDialog(this, "Login Failure",
            "The supplied password was incorrect.");
            passwordView.selectAll();
            return;
        }

        Session.getInstance().setKey(masterKey.getKey());
        Session.getInstance().setLoggedIn();

        DbMigration.postLoginMigration(this);

        if (upgradedKey != null) {
            if (DbMigration.changeKey(this, masterKey.getKey(), upgradedKey)) {
                Session.getInstance().setKey(upgradedKey.getKey());
                Session.getInstance().setNeedReload(true);
                Arrays.fill(masterKey.getKey(), (byte) 0);
            } else {
                // We keep using the legacy key and try again at next login
                Utils.error("Failed to upgrade the master key");
            }
        }

        Utils.startPasswordActivity(this);
    }
}
