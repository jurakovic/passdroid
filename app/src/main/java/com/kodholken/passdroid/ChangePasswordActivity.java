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
import com.kodholken.passdroid.db.DbMigration;
import com.kodholken.passdroid.db.SystemData;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class ChangePasswordActivity extends TimeoutActivity {
    private boolean changing = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.setContentView(R.layout.change_password);

        new PasswordLengthHint((EditText) findViewById(R.id.master_password_1),
                               (TextView) findViewById(R.id.master_password_hint));

        Button okButton = (Button) this.findViewById(R.id.ok_button);
        okButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.debug("OK clicked");
                handleOK();
            }
        });

        Button cancelButton = (Button) this.findViewById(R.id.cancel_button);
        cancelButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.debug("Cancel clicked");
                finish();
            }
        });
    }

    private void handleOK() {
        if (changing) {
            return;
        }

        final EditText oldPasswordView = (EditText) this.findViewById(
                R.id.old_master_password);
        EditText newPassword1View = (EditText) this.findViewById(
                R.id.master_password_1);
        EditText newPassword2View = (EditText) this.findViewById(
                R.id.master_password_2);

        final String oldPassword = oldPasswordView.getText().toString();
        final String newPassword1 = newPassword1View.getText().toString();
        String newPassword2 = newPassword2View.getText().toString();

        // Check this first since verifying the old password is slow
        if (!newPassword1.equals(newPassword2)) {
            Utils.alertDialog(this, "Passwords mismatch",
            "The new passwords you entered does not match.");
            newPassword1View.requestFocus();
            newPassword1View.selectAll();
            return;
        }

        if (MasterKey.isTooShort(newPassword1)) {
            Utils.alertDialog(this, getString(R.string.master_password_too_short_title),
                              getString(R.string.master_password_too_short,
                                        MasterKey.MIN_PASSWORD_LENGTH));
            newPassword1View.requestFocus();
            newPassword1View.selectAll();
            return;
        }

        final String record = new SystemData(this).getKey();
        final MasterKey [] keys = new MasterKey[2];

        changing = true;
        Utils.runWithProgress(this, "Changing password…", new Runnable() {
            @Override
            public void run() {
                keys[0] = MasterKey.unlock(oldPassword, record);
                if (keys[0] != null) {
                    keys[1] = MasterKey.create(newPassword1);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                changing = false;
                if (keys[0] == null) {
                    Utils.alertDialog(ChangePasswordActivity.this,
                                      "Invalid password",
                                      "The master password entered is incorrect.");
                    oldPasswordView.requestFocus();
                    oldPasswordView.selectAll();
                } else {
                    changeKey(keys[0], keys[1]);
                }
            }
        });
    }

    private void changeKey(MasterKey oldKey, MasterKey newKey) {
        if (newKey != null && DbMigration.changeKey(this, oldKey.getKey(), newKey)) {
            Session.getInstance().setKey(newKey.getKey());
            Session.getInstance().setNeedReload(true);

            AlertDialog alertDialog;

            alertDialog = new AlertDialog.Builder(this).create();
            alertDialog.setTitle("Password changed");
            alertDialog.setMessage("The password was changed successfully.");
            alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                    return;
                } }); 
            alertDialog.show();
        } else {
            Utils.alertDialog(this, "Password change failed",
            "There was a failure when changing the password.");
        }
    }
}
