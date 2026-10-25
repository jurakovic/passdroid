package com.kodholken.passdroid;

import android.app.Application;

/**
 * Set up what is needed whenever the process starts. That is not always
 * through MainActivity: after the process has been killed in the background,
 * Android starts it again with the activity that was on top.
 */
public class PassdroidApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Logs out and clears the clipboard when the screen turns off
        ScreenOffListener.register(this);
    }
}
