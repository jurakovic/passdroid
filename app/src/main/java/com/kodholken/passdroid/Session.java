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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.preference.PreferenceManager;

/**
 * Singleton class for keeping application state accessible to all classes of
 * the application.
 */
public class Session {
    public static final String TIMEOUT_ACTION = "com.kodholken.passdroid.TIMEOUT";

    // Time the application may be in the background before the user is
    // logged out.
    private static final long TIMEOUT = 5000;
    // Timeout used while an activity from another application, such as the
    // system file picker, is in the foreground on our behalf.
    private static final long EXTENDED_TIMEOUT = 2 * 60 * 1000;

    private byte [] key;         // Key derived from the master password. It is
    // used for all encryption and decryption of 
    // the user stored data.
    private boolean isLoggedIn;  // Indicates whether the user is currently
    // logged in.

    private boolean needReload;

    private boolean exitMain;  // Indicates whether the MainActivity should 
    // exit when it receives control (onResume)

    private static Session session;  // Singleton instance of this class
    
    private PendingIntent timeoutIntent;

    private long timeoutStart;  // elapsedRealtime() when the timeout timer was
    // set. 0 if no timer is running.

    private boolean extendedTimeout;

    private int resumes = 0;

    private boolean passwordInClipboard;

    private Session() {
        needReload = false;
        isLoggedIn = false;
        exitMain = false;
        timeoutIntent = null;
    }

    public static Session getInstance() {
        if (session == null) {
            session = new Session();
        }

        return session;
    }

    public void setKey(byte [] key) {
        this.key = key;
    }

    public byte[] getKey() {
        return key;
    }

    public boolean isLoggedIn() {
        return isLoggedIn;
    }

    public void setLoggedIn(boolean isLoggedIn) {
        this.isLoggedIn = isLoggedIn;

        if (isLoggedIn) {
            // Make sure a timer left from before the login does not log out
            // the user.
            timeoutStart = 0;
        } else {
            // Forget the key and the decrypted entries, whatever the reason
            // for the logout
            if (key != null) {
                Arrays.fill(key, (byte) 0);
                key = null;
            }
            PasswordModel.clear();
        }
    }

    public void setLoggedIn() {
        setLoggedIn(true);
    }

    public void logout() {		
        if (!isLoggedIn) {
            return ;
        }

        setLoggedIn(false);
    }

    public void setNeedReload(boolean needReload) {
        this.needReload = needReload;
    }

    public boolean needReload() {
        return needReload;
    }

    public void setExitMain(boolean exitMain) {
        this.exitMain = exitMain;
    }

    public boolean getExitMain() {
        return exitMain;
    }
    
    public int incResume() {
        return ++resumes;
    }
    
    public int decResume() {
        if (resumes < 1) {
            throw new RuntimeException("Invalid resume state");
        }
        
        return --resumes;
    }
    
    public int getResumes() {
        return resumes;
    }

    public static void setTimeoutTimer(Context context) {
        Session session = getInstance();

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (session.timeoutIntent != null) {
            am.cancel(session.timeoutIntent);
            session.timeoutIntent = null;
        }

        // Automatic logout can be turned off in the settings
        if (!PreferenceManager.getDefaultSharedPreferences(context).
                               getBoolean("close_logout", true)) {
            return;
        }

        // Only deliver the timeout broadcast to this application
        Intent intent = new Intent(TIMEOUT_ACTION);
        intent.setPackage(context.getPackageName());

        session.timeoutIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        session.timeoutStart = SystemClock.elapsedRealtime();
        am.set(AlarmManager.ELAPSED_REALTIME,
               session.timeoutStart + session.getTimeout(),
               session.timeoutIntent);
    }

    /**
     * Cancel the timeout timer. If the timeout has already passed the user is
     * logged out, since the alarm is inexact and its broadcast may be held
     * back while the application is in the background.
     */
    public static void clearTimeoutTimer(Context context) {
        Session session = getInstance();

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (session.timeoutIntent != null) {
            am.cancel(session.timeoutIntent);
            session.timeoutIntent = null;
        }

        if (session.timeoutStart != 0 &&
            SystemClock.elapsedRealtime() - session.timeoutStart >= session.getTimeout()) {
            session.setLoggedIn(false);
        }

        session.timeoutStart = 0;
        session.extendedTimeout = false;
    }

    /**
     * Use a longer timeout the next time the application goes to the
     * background. Call this before starting an activity from another
     * application, e.g. the system file picker.
     */
    public void setExtendedTimeout(boolean extendedTimeout) {
        this.extendedTimeout = extendedTimeout;
    }

    private long getTimeout() {
        return extendedTimeout ? EXTENDED_TIMEOUT : TIMEOUT;
    }

    /**
     * Register a receiver for the timeout broadcast. The receiver is not
     * exported, i.e. it only receives broadcasts from this application.
     */
    public static void registerTimeoutReceiver(Context context,
                                               BroadcastReceiver receiver) {
        Utils.registerNotExportedReceiver(context, receiver,
                                          new IntentFilter(TIMEOUT_ACTION));
    }

    public boolean isPasswordInClipboard() {
        return passwordInClipboard;
    }

    public void setPasswordInClipboard(boolean passwordInClipboard) {
        this.passwordInClipboard = passwordInClipboard;
    }
}
