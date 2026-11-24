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

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.EditText;

public class ImportPasswordActivity extends TimeoutActivity {
    // Kept across a rotation by RetainedText instead of the saved state
    private static final int [] SECRET_FIELDS = { R.id.password };

    private EditText password;
    // The password entered last, until FileImportActivity takes it. It is
    // handed over in the process, not in the result intent, which would go
    // through the system.
    private static String enteredPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.setContentView(R.layout.import_password);
        
        password = (EditText) findViewById(R.id.password);
        RetainedText.restore(this, getLastNonConfigurationInstance(),
                             SECRET_FIELDS);

        findViewById(R.id.ok_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                enteredPassword = password.getText().toString();
                password.setText("");
                setResult(RESULT_OK);
                finish();
            }
        });
        
        findViewById(R.id.cancel_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return RetainedText.collect(this, SECRET_FIELDS);
    }

    /**
     * Get the password the user entered and forget it, so it is only
     * returned once.
     *
     * @return the password, or null if none was entered since the last
     *         call.
     */
    public static String takePassword() {
        String result = enteredPassword;
        enteredPassword = null;
        return result;
    }
}
