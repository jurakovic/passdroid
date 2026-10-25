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
import com.kodholken.passdroid.db.SystemData;


import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class InitializeActivity extends Activity {
    Button okButton;
    private boolean initializing = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        ThemeSetting.apply(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.initialize);

        new PasswordLengthHint((EditText) findViewById(R.id.master_password_1),
                               (TextView) findViewById(R.id.master_password_hint));

        okButton = (Button) this.findViewById(R.id.ok_button);
        okButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                handleOK();
            }
        });

        Utils.applySystemBarInsets(this);
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
            initialize(p1);
        }
    }

    private void initialize(final String masterPassword) {
        if (initializing) {
            return;
        }

        final MasterKey [] masterKey = new MasterKey[1];

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

                SystemData system = new SystemData(InitializeActivity.this);
                system.setAttribute("key", masterKey[0].getRecord());

                Session.getInstance().setKey(masterKey[0].getKey());
                Session.getInstance().setLoggedIn();
                Utils.startPasswordActivity(InitializeActivity.this);
                finish();
            }
        });
    }
}
