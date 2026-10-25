package com.kodholken.passdroid;

import android.content.Context;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import android.view.ContextThemeWrapper;

/**
 * The theme setting: light, dark or the system setting. The dark theme
 * resources (values-night) are selected by the night mode of the
 * configuration, which is overridden for each activity when the light or the
 * dark theme is chosen.
 */
public class ThemeSetting {
    public static final String PREFERENCE = "theme";

    private static final String SYSTEM = "system";
    private static final String LIGHT = "light";
    private static final String DARK = "dark";

    public static String get(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).
                                 getString(PREFERENCE, SYSTEM);
    }

    /**
     * Apply the theme setting to an activity. It must be called from
     * attachBaseContext(), before the resources of the activity are used.
     *
     * @return the applied setting
     */
    public static String apply(ContextThemeWrapper activity) {
        String setting = get(activity);

        int nightMode;
        if (LIGHT.equals(setting)) {
            nightMode = Configuration.UI_MODE_NIGHT_NO;
        } else if (DARK.equals(setting)) {
            nightMode = Configuration.UI_MODE_NIGHT_YES;
        } else {
            return setting;
        }

        // Only the defined fields of the override replace the configuration
        // of the activity. Before Android 8 the constructor sets the font
        // scale to 1, which would replace the font size of the user.
        Configuration override = new Configuration();
        override.fontScale = 0;
        override.uiMode = Configuration.UI_MODE_TYPE_UNDEFINED | nightMode;
        activity.applyOverrideConfiguration(override);

        return setting;
    }
}
