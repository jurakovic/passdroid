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
import android.content.Context;
import android.content.Intent;
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
    private Button fingerprintButton;
    private EditText passwordView;
    private boolean loggingIn = false;
    // Used to show the fingerprint prompt automatically only once each time
    // the login screen is shown.
    private boolean biometricPromptShown = false;
    private String theme;  // The theme setting the activity was created with

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        theme = ThemeSetting.apply(this);
    }

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

        fingerprintButton = (Button) findViewById(R.id.fingerprint_button);
        fingerprintButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startBiometricUnlock();
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

        // The theme was changed in the settings while the user was logged
        // in, this activity stays below the password list
        if (!theme.equals(ThemeSetting.get(this))) {
            recreate();
            return;
        }

        ((EditText) this.findViewById(R.id.login_password)).setText("");

        // Show the fingerprint prompt automatically, unless the user just
        // logged out
        if (updateFingerprintButton() && !biometricPromptShown &&
            !Session.getInstance().isLoggedOutByUser()) {
            biometricPromptShown = true;
            startBiometricUnlock();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Show the fingerprint prompt again when we come back, also after
        // the user logged out
        biometricPromptShown = false;
        Session.getInstance().setLoggedOutByUser(false);
    }

    /**
     * Show the fingerprint button if biometric unlock is turned on.
     *
     * @return true if the button is shown.
     */
    private boolean updateFingerprintButton() {
        boolean show = BiometricUnlock.isEnabled(this) &&
                       BiometricUnlock.isAvailable(this);
        fingerprintButton.setVisibility(show ? View.VISIBLE : View.GONE);
        return show;
    }

    private void startBiometricUnlock() {
        BiometricUnlock.unlock(this, new BiometricUnlock.Listener() {
            @Override
            public void onSuccess(byte [] key) {
                if (!isFinishing() && !isDestroyed() &&
                    !Session.getInstance().isLoggedIn()) {
                    biometricLogin(key);
                }
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                // Biometric unlock may have been turned off
                updateFingerprintButton();
                if (message != null) {
                    Utils.alertDialog(LoginActivity.this,
                                      getString(R.string.failure), message);
                }
            }
        });
    }

    private void biometricLogin(byte [] key) {
        // The stored key should always match the current master key, since it
        // is removed when the key is changed. Check it anyway rather than
        // logging in with a key that can not decrypt the passwords.
        String record = new SystemData(this).getKey();
        if (MasterKey.isLegacy(record) || !MasterKey.verifyKey(key, record)) {
            BiometricUnlock.disable(this);
            updateFingerprintButton();
            Utils.alertDialog(this, getString(R.string.failure),
                              getString(R.string.biometric_unlock_failed) + " " +
                              getString(R.string.biometric_turned_off));
            return;
        }

        loginDone(key, null, false);
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
                loginDone(keys[0] != null ? keys[0].getKey() : null, keys[1],
                          MasterKey.isTooShort(password));
            }
        });
    }

    /**
     * @param key         master key, derived from the entered password or
     *                    stored for biometric unlock. null if the password
     *                    was incorrect.
     * @param upgradedKey new key to re-encrypt a legacy database with, or null.
     * @param shortPassword true if the master password is shorter than is
     *                      required for new master passwords.
     */
    private void loginDone(byte [] key, MasterKey upgradedKey,
                           boolean shortPassword) {
        if (key == null) {
            Utils.alertDialog(this, "Login Failure",
            "The supplied password was incorrect.");
            passwordView.selectAll();
            return;
        }

        Session.getInstance().setKey(key);
        Session.getInstance().setLoggedIn();

        DbMigration.postLoginMigration(this);

        if (upgradedKey != null) {
            if (DbMigration.changeKey(this, key, upgradedKey)) {
                Session.getInstance().setKey(upgradedKey.getKey());
                Session.getInstance().setNeedReload(true);
                Arrays.fill(key, (byte) 0);
            } else {
                // We keep using the legacy key and try again at next login
                Utils.error("Failed to upgrade the master key");
                Arrays.fill(upgradedKey.getKey(), (byte) 0);
            }
        }

        Intent intent = new Intent(this, PasswordListActivity.class);
        intent.putExtra(PasswordListActivity.EXTRA_SHORT_PASSWORD, shortPassword);
        startActivity(intent);
    }
}
