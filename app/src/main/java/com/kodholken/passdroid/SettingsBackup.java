package com.kodholken.passdroid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;

/**
 * The settings that are exported with the entries and imported again from
 * such a file. Fingerprint unlock is not included since it only works on the
 * device where it was turned on.
 */
public class SettingsBackup {
    static final String [] BOOLEANS = {
        "close_logout", "screen_off_logout", "display_username", "tap_to_copy",
        "clear_clipboard"
    };

    /**
     * All the settings, in the order of the settings screen. An import only
     * writes the settings in here, see changes().
     */
    static final String [] ORDER = {
        "close_logout", "logout_delay", "screen_off_logout", "display_username",
        "tap_to_copy", "clear_clipboard", ThemeSetting.PREFERENCE, FontSetting.PREFERENCE,
        FontSetting.SIZE_PREFERENCE, DateField.PREFERENCE
    };

    /** A setting that an import changed. */
    public static class Change {
        public final String name;
        /** null if the setting had no value */
        public final String oldValue;
        public final String newValue;

        Change(String name, String oldValue, String newValue) {
            this.name = name;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    /**
     * @return the settings with a list of values, and the array resource with
     *         the values each may have.
     */
    static Map<String, Integer> getLists() {
        Map<String, Integer> lists = new LinkedHashMap<String, Integer>();
        lists.put("logout_delay", R.array.logout_delay_values);
        lists.put(ThemeSetting.PREFERENCE, R.array.theme_values);
        lists.put(FontSetting.PREFERENCE, R.array.font_values);
        lists.put(FontSetting.SIZE_PREFERENCE, R.array.font_size_values);
        lists.put(DateField.PREFERENCE, R.array.date_format_values);
        return lists;
    }

    /**
     * @return the array resource with the labels of each list's values, in
     *         the order of its values array.
     */
    static Map<String, Integer> getEntries() {
        Map<String, Integer> entries = new HashMap<String, Integer>();
        entries.put("logout_delay", R.array.logout_delay_entries);
        entries.put(ThemeSetting.PREFERENCE, R.array.theme_entries);
        entries.put(FontSetting.PREFERENCE, R.array.font_entries);
        entries.put(FontSetting.SIZE_PREFERENCE, R.array.font_size_entries);
        entries.put(DateField.PREFERENCE, R.array.date_format_entries);
        return entries;
    }

    /** @return the string resource with the title of each setting. */
    static Map<String, Integer> getTitles() {
        Map<String, Integer> titles = new HashMap<String, Integer>();
        titles.put("close_logout", R.string.close_logout_title);
        titles.put("logout_delay", R.string.logout_delay_title);
        titles.put("screen_off_logout", R.string.screen_off_logout_title);
        titles.put("display_username", R.string.display_username_title);
        titles.put("tap_to_copy", R.string.tap_to_copy_title);
        titles.put("clear_clipboard", R.string.clear_clipboard_title);
        titles.put(ThemeSetting.PREFERENCE, R.string.theme_title);
        titles.put(FontSetting.PREFERENCE, R.string.font_title);
        titles.put(FontSetting.SIZE_PREFERENCE, R.string.font_size_title);
        titles.put(DateField.PREFERENCE, R.string.date_format_title);
        return titles;
    }

    private static boolean isBoolean(String name) {
        return Arrays.asList(BOOLEANS).contains(name);
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
     * @return the settings that changed, in the order of the settings screen.
     */
    public static List<Change> apply(Context context, Map<String, String> settings) {
        Map<String, String> imported = new HashMap<String, String>();

        for (String name : BOOLEANS) {
            String value = settings.get(name);
            if ("true".equals(value) || "false".equals(value)) {
                imported.put(name, value);
            }
        }

        for (Map.Entry<String, Integer> list : getLists().entrySet()) {
            String value = settings.get(list.getKey());
            String [] values = context.getResources().getStringArray(list.getValue());
            if (value != null && Arrays.asList(values).contains(value)) {
                imported.put(list.getKey(), value);
            }
        }

        List<Change> changes = changes(get(context), imported);
        if (changes.isEmpty()) {
            return changes;
        }

        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (Change change : changes) {
            if (isBoolean(change.name)) {
                editor.putBoolean(change.name, Boolean.parseBoolean(change.newValue));
            } else {
                editor.putString(change.name, change.newValue);
            }
        }
        editor.apply();

        // The password list shows the new font settings
        Session.getInstance().setNeedReload(true);

        return changes;
    }

    /**
     * @param current  the current settings, see get().
     * @param imported valid imported values.
     * @return the imported settings whose value differs from the current one,
     *         in the order of the settings screen.
     */
    static List<Change> changes(Map<String, String> current,
                                Map<String, String> imported) {
        List<Change> changes = new ArrayList<Change>();
        for (String name : ORDER) {
            String value = imported.get(name);
            if (value != null && !value.equals(current.get(name))) {
                changes.add(new Change(name, current.get(name), value));
            }
        }
        return changes;
    }

    /**
     * @return one line per change, with the setting's title and its new and
     *         old value as the settings screen shows them, e.g.
     *         "Log out on close: off (was on)".
     */
    public static String describe(Context context, List<Change> changes) {
        StringBuilder text = new StringBuilder();
        for (Change change : changes) {
            if (text.length() > 0) {
                text.append('\n');
            }

            String title = context.getString(getTitles().get(change.name));
            String value = label(context, change.name, change.newValue);
            if (change.oldValue == null) {
                text.append(title).append(": ").append(value);
            } else {
                text.append(context.getString(R.string.setting_change, title, value,
                                              label(context, change.name, change.oldValue)));
            }
        }
        return text.toString();
    }

    /** @return how the settings screen shows the value of a setting. */
    private static String label(Context context, String name, String value) {
        if (isBoolean(name)) {
            return context.getString(Boolean.parseBoolean(value) ?
                                     R.string.setting_on : R.string.setting_off);
        }

        Resources resources = context.getResources();
        List<String> values = Arrays.asList(resources.getStringArray(getLists().get(name)));
        String [] entries = resources.getStringArray(getEntries().get(name));
        int i = values.indexOf(value);
        return i >= 0 && i < entries.length ? entries[i] : value;
    }
}
