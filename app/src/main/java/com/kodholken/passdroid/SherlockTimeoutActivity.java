package com.kodholken.passdroid;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager.LayoutParams;

public class SherlockTimeoutActivity extends Activity {
    private BroadcastReceiver receiver;
    private String theme;  // The theme setting the activity was created with

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        theme = ThemeSetting.apply(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Session.getInstance().setLoggedIn(false);
                finish();
            }
        };

        Session.registerTimeoutReceiver(this, receiver);

        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        Utils.applySystemBarInsets(this);
    }

    @Override
    protected void onPause() {
        if (Session.getInstance().decResume() == 0) {
            Session.setTimeoutTimer(this);
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        Session.getInstance().incResume();
        Session.clearTimeoutTimer(this);
        super.onResume();

        // The session may have timed out or the process may have been
        // restarted while we were in the background.
        if (!Session.getInstance().isLoggedIn()) {
            finish();
        } else if (!theme.equals(ThemeSetting.get(this))) {
            // The theme was changed in the settings
            recreate();
        }
    }


    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
