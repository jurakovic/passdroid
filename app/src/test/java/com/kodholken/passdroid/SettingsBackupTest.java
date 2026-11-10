package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SettingsBackupTest {
    private static Map<String, String> current() {
        Map<String, String> settings = new HashMap<String, String>();
        settings.put("close_logout", "true");
        settings.put("logout_delay", "5");
        settings.put("screen_off_logout", "true");
        settings.put("display_username", "true");
        settings.put("clear_clipboard", "true");
        settings.put("theme", "system");
        settings.put("font", "default");
        settings.put("font_size", "normal");
        settings.put("date_format", "device");
        return settings;
    }

    @Test
    public void onlyDifferingValuesAreChanges() {
        Map<String, String> imported = current();
        imported.put("close_logout", "false");
        imported.put("logout_delay", "60");

        List<SettingsBackup.Change> changes = SettingsBackup.changes(current(), imported);

        assertEquals(2, changes.size());
        assertChange(changes.get(0), "close_logout", "true", "false");
        assertChange(changes.get(1), "logout_delay", "5", "60");
    }

    @Test
    public void changesAreInTheOrderOfTheSettingsScreen() {
        Map<String, String> imported = new LinkedHashMap<String, String>();
        imported.put("date_format", "iso");
        imported.put("font_size", "large");
        imported.put("theme", "dark");
        imported.put("clear_clipboard", "false");
        imported.put("screen_off_logout", "false");
        imported.put("close_logout", "false");

        List<SettingsBackup.Change> changes = SettingsBackup.changes(current(), imported);

        assertEquals(6, changes.size());
        assertEquals("close_logout", changes.get(0).name);
        assertEquals("screen_off_logout", changes.get(1).name);
        assertEquals("clear_clipboard", changes.get(2).name);
        assertEquals("theme", changes.get(3).name);
        assertEquals("font_size", changes.get(4).name);
        assertEquals("date_format", changes.get(5).name);
    }

    @Test
    public void sameValuesGiveNoChanges() {
        assertTrue(SettingsBackup.changes(current(), current()).isEmpty());
        assertTrue(SettingsBackup.changes(current(), new HashMap<String, String>()).isEmpty());
    }

    @Test
    public void unknownSettingsAreIgnored() {
        Map<String, String> imported = new HashMap<String, String>();
        imported.put("biometric_unlock", "true");
        imported.put("unknown", "1");

        assertTrue(SettingsBackup.changes(current(), imported).isEmpty());
    }

    @Test
    public void aSettingWithoutValueIsChanged() {
        Map<String, String> current = current();
        current.remove("font");
        Map<String, String> imported = new HashMap<String, String>();
        imported.put("font", "monospace");

        List<SettingsBackup.Change> changes = SettingsBackup.changes(current, imported);

        assertEquals(1, changes.size());
        assertEquals("font", changes.get(0).name);
        assertNull(changes.get(0).oldValue);
        assertEquals("monospace", changes.get(0).newValue);
    }

    /**
     * An import only writes the settings of ORDER, so one that is missing
     * there would be checked and then left out.
     */
    @Test
    public void everyImportedSettingHasAPlaceInTheOrder() {
        List<String> order = Arrays.asList(SettingsBackup.ORDER);
        for (String name : SettingsBackup.BOOLEANS) {
            assertTrue(name, order.contains(name));
        }
        for (String name : SettingsBackup.getLists().keySet()) {
            assertTrue(name, order.contains(name));
        }
        assertEquals(SettingsBackup.BOOLEANS.length + SettingsBackup.getLists().size(),
                     order.size());
    }

    /** The import message shows the title of each setting and the labels of the lists. */
    @Test
    public void everySettingHasATitleAndLabels() {
        for (String name : SettingsBackup.ORDER) {
            assertNotNull(name, SettingsBackup.getTitles().get(name));
        }
        for (String name : SettingsBackup.getLists().keySet()) {
            assertNotNull(name, SettingsBackup.getEntries().get(name));
        }
    }

    private static void assertChange(SettingsBackup.Change change, String name,
                                     String oldValue, String newValue) {
        assertEquals(name, change.name);
        assertEquals(oldValue, change.oldValue);
        assertEquals(newValue, change.newValue);
    }
}
