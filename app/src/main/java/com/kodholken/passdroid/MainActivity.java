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

import com.kodholken.passdroid.db.DbMigration;
import com.kodholken.passdroid.db.SystemData;

import android.app.Activity;
import android.content.Intent;

/**
 * Main activity of the application. This is started upon application startup
 * and will determine which parts to launch depending on the stored application
 * state.
 */
public class MainActivity extends Activity {	
    @Override
    public void onResume() {
    	super.onResume();
    	
    	// Other activities use SessiongetInstance().setExitMain() to indicate
    	// whether we should exit the the application or spawn a new activity
    	// when control returns to this activity.
    	if (Session.getInstance().getExitMain()) {
    		Session.getInstance().setExitMain(false);
    		finish();
    		return;
    	}
    	
    	startup();
    }
    
    /*
     * This method launch different activities depending on the application
     * state. If the user is flagged as logged in we proceed to the password
     * list activity. If the database was written by a version that stored the
     * data differently we perform migration steps. if we detect that this is the
     * first time the application is run we prompt the user for a master
     * password.
     */
    private void startup() {
    	if (Session.getInstance().isLoggedIn()) {
    		Utils.startPasswordActivity(this);
    		return;
    	}

    	SystemData system = new SystemData(this);
    	system.verifyTable();

    	// The entries are converted after the login, see
    	// LoginActivity#loginDone
    	DbMigration.preLoginMigration(system);

    	// First time logins is detected by the absence of a master password
    	if (system.hasKey()) {
        	Intent i = new Intent(this, LoginActivity.class);
        	startActivity(i);
    	} else {
        	Intent i = new Intent(this, InitializeActivity.class);
        	startActivity(i);
    	}
    }
}