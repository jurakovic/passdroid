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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    /**
     * Called at a logout, before the key is wiped, e.g. so that an editor
     * can store its unsaved changes encrypted with it.
     */
    public interface LogoutListener {
        void beforeLogout(byte [] key);
    }

    public static final String TIMEOUT_ACTION = "com.kodholken.passdroid.TIMEOUT";

    // Time in seconds the application may be in the background before the
    // user is logged out, if it is not set with the logout_delay setting.
    private static final String DEFAULT_LOGOUT_DELAY = "5";
    // Minimum timeout while an activity from another application, such as
    // the system file picker, is in the foreground on our behalf.
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

    private long timeoutEnd;  // elapsedRealtime() when the user is logged
    // out. 0 if no timer is running.

    private long sleepTimeAtPause = -1;  // getSleepTime() when the application
    // went to the background. -1 if it is in the foreground.

    private boolean extendedTimeout;

    private int resumes = 0;

    private boolean passwordInClipboard;

    private boolean loggedOutByUser;

    private final List<LogoutListener> logoutListeners = new ArrayList<LogoutListener>();

    private Session() {
        needReload = false;
        isLoggedIn = false;
        exitMain = false;
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
            // Make sure a timer or a pause left from before the login does
            // not log out the user, also the alarm of an earlier process:
            // the timeout receivers ignore it (isTimeoutDue()), and the next
            // resume cancels it. The login screen does not clear them, see
            // clearTimeoutTimer().
            timeoutEnd = 0;
            sleepTimeAtPause = -1;
            loggedOutByUser = false;
        } else {
            // Forget the key and the decrypted entries, whatever the reason
            // for the logout
            if (key != null) {
                notifyLogoutListeners(key);
                Arrays.fill(key, (byte) 0);
                key = null;
            }
            PasswordModel.clear();
        }
    }

    public void addLogoutListener(LogoutListener listener) {
        if (!logoutListeners.contains(listener)) {
            logoutListeners.add(listener);
        }
    }

    public void removeLogoutListener(LogoutListener listener) {
        logoutListeners.remove(listener);
    }

    private void notifyLogoutListeners(byte [] key) {
        // A copy, since a listener may remove itself
        for (LogoutListener listener : new ArrayList<LogoutListener>(logoutListeners)) {
            try {
                listener.beforeLogout(key);
            } catch (RuntimeException ex) {
                // The logout must happen all the same
                Utils.error("A logout listener failed: " + ex);
            }
        }
    }

    /**
     * @return true if the user logged out, as opposed to being logged out
     *         automatically.
     */
    public boolean isLoggedOutByUser() {
        return loggedOutByUser;
    }

    public void setLoggedOutByUser(boolean loggedOutByUser) {
        this.loggedOutByUser = loggedOutByUser;
    }

    public void setLoggedIn() {
        setLoggedIn(true);
    }

    public void logout() {		
        if (!isLoggedIn) {
            return ;
        }

        setLoggedIn(false);
        loggedOutByUser = true;  // Cleared when the login screen is left
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

    public static void setTimeoutTimer(Context context) {
        Session session = getInstance();

        cancelTimeoutAlarm(context);

        session.sleepTimeAtPause = getSleepTime();

        // Automatic logout can be turned off in the settings
        if (!PreferenceManager.getDefaultSharedPreferences(context).
                               getBoolean("close_logout", true)) {
            return;
        }

        session.timeoutEnd = SystemClock.elapsedRealtime() +
                             session.getTimeout(context);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME, session.timeoutEnd,
               getTimeoutIntent(context, true));
    }

    /**
     * Cancel the timeout timer, also an alarm an earlier process of the
     * application set, see cancelTimeoutAlarm(). If the timeout has already
     * passed the user is logged out, since the alarm is inexact and its
     * broadcast may be held back while the application is in the background.
     */
    public static void clearTimeoutTimer(Context context) {
        Session session = getInstance();

        cancelTimeoutAlarm(context);

        if (session.timeoutEnd != 0 &&
            SystemClock.elapsedRealtime() >= session.timeoutEnd) {
            Utils.debug("Logout: the logout delay passed in the background");
            session.setLoggedIn(false);
        }

        // The screen broadcasts may only be delivered after the application
        // is in the foreground again, see ScreenOffListener. The device only
        // sleeps while the screen is off, so if it has slept the screen has
        // been off.
        if (session.sleepTimeAtPause >= 0 &&
            getSleepTime() - session.sleepTimeAtPause > 1000 &&
            isScreenOffLogout(context)) {
            Utils.debug("Logout: the device slept in the background");
            session.setLoggedIn(false);
        }

        session.timeoutEnd = 0;
        session.sleepTimeAtPause = -1;
        session.extendedTimeout = false;
    }

    /**
     * Whether a timeout broadcast logs out: the session has already ended,
     * e.g. in screenOff(), or its own logout delay has passed. A broadcast
     * that arrives after a later login, held back or from the alarm of an
     * earlier process, finds neither and is ignored.
     */
    public boolean isTimeoutDue() {
        return !isLoggedIn ||
               (timeoutEnd != 0 && SystemClock.elapsedRealtime() >= timeoutEnd);
    }

    /**
     * Cancel the logout alarm, whichever process of the application set it.
     * The alarm outlives the process, which an update or swiping the app away
     * from the recent apps ends, and would log out a later session.
     */
    private static void cancelTimeoutAlarm(Context context) {
        PendingIntent intent = getTimeoutIntent(context, false);
        if (intent != null) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.cancel(intent);
            intent.cancel();
        }
    }

    /**
     * Get the PendingIntent of the logout alarm. The same intent, request code
     * and flags give the same PendingIntent in every process, so the alarm of
     * an earlier process can be found.
     *
     * @param create false to only look up an existing one
     * @return null if create is false and there is none
     */
    private static PendingIntent getTimeoutIntent(Context context,
                                                  boolean create) {
        // Only deliver the timeout broadcast to this application
        Intent intent = new Intent(TIMEOUT_ACTION);
        intent.setPackage(context.getPackageName());

        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE;
        if (!create) {
            flags |= PendingIntent.FLAG_NO_CREATE;
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    /**
     * Log out because the screen has been turned off, if the user has chosen
     * to be logged out then. Open activities finish themselves when they
     * receive the timeout broadcast.
     */
    public static void screenOff(Context context) {
        Session session = getInstance();
        if (!session.isLoggedIn() || !isScreenOffLogout(context)) {
            return;
        }

        Utils.debug("Logout: a screen broadcast");
        session.setLoggedIn(false);

        Intent intent = new Intent(TIMEOUT_ACTION);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private static boolean isScreenOffLogout(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).
                                 getBoolean("screen_off_logout", true);
    }

    /**
     * Get the time the device has been asleep since it was started. The
     * uptime clock stops while the device is asleep.
     */
    private static long getSleepTime() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
    }

    /**
     * Use a longer timeout the next time the application goes to the
     * background. Call this before starting an activity from another
     * application, e.g. the system file picker.
     */
    public void setExtendedTimeout(boolean extendedTimeout) {
        this.extendedTimeout = extendedTimeout;
    }

    private long getTimeout(Context context) {
        String delay = PreferenceManager.getDefaultSharedPreferences(context).
                                         getString("logout_delay", DEFAULT_LOGOUT_DELAY);
        long timeout;
        try {
            timeout = Long.parseLong(delay) * 1000;
        } catch (NumberFormatException e) {
            timeout = Long.parseLong(DEFAULT_LOGOUT_DELAY) * 1000;
        }

        return extendedTimeout ? Math.max(timeout, EXTENDED_TIMEOUT) : timeout;
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
