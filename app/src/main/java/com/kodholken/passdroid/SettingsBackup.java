package com.kodholken.passdroid;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * The settings that are exported with the entries and imported again from
 * such a file. Fingerprint unlock is not included since it only works on the
 * device where it was turned on.
 */
public class SettingsBackup {
    private static final String [] BOOLEANS = {
        "close_logout", "screen_off_logout", "display_username", "clear_clipboard"
    };

    /**
     * @return the settings with a list of values, and the array resource with
     *         the values each may have.
     */
    private static Map<String, Integer> getLists() {
        Map<String, Integer> lists = new LinkedHashMap<String, Integer>();
        lists.put("logout_delay", R.array.logout_delay_values);
        lists.put(ThemeSetting.PREFERENCE, R.array.theme_values);
        lists.put(FontSetting.PREFERENCE, R.array.font_values);
        lists.put(FontSetting.SIZE_PREFERENCE, R.array.font_size_values);
        return lists;
    }

    /**
     * @return the current settings by name, as strings.
     */
    public static Map<String, String> get(Context context) {
        // The settings that have never been changed get their default values
        PreferenceManager.setDefaultValues(context, R.xml.settings, true);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        Map<String, String> settings = new TreeMap<String, String>();
        for (String name : BOOLEANS) {
            if (preferences.contains(name)) {
                settings.put(name, Boolean.toString(preferences.getBoolean(name, false)));
            }
        }

        for (String name : getLists().keySet()) {
            String value = preferences.getString(name, null);
            if (value != null) {
                settings.put(name, value);
            }
        }

        return settings;
    }

    /**
     * Change the settings to imported values. Unknown settings and invalid
     * values are ignored, those settings keep their current values.
     *
     * @return the number of settings that were imported.
     */
    public static int apply(Context context, Map<String, String> settings) {
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(context).edit();
        int imported = 0;

        for (String name : BOOLEANS) {
            String value = settings.get(name);
            if ("true".equals(value) || "false".equals(value)) {
                editor.putBoolean(name, Boolean.parseBoolean(value));
                imported++;
            }
        }

        for (Map.Entry<String, Integer> list : getLists().entrySet()) {
            String value = settings.get(list.getKey());
            String [] values = context.getResources().getStringArray(list.getValue());
            if (value != null && Arrays.asList(values).contains(value)) {
                editor.putString(list.getKey(), value);
                imported++;
            }
        }

        editor.apply();

        // The password list shows the new font settings
        Session.getInstance().setNeedReload(true);

        return imported;
    }
}
