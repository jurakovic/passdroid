package com.kodholken.passdroid.db;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the data version of databases that stored the app version.
 */
public class DbMigrationTest {
    @Test
    public void dataVersionOfLegacyDatabases() {
        assertEquals(1, DbMigration.getLegacyDataVersion("0.9"));
        assertEquals(2, DbMigration.getLegacyDataVersion("0.95"));
        assertEquals(2, DbMigration.getLegacyDataVersion("1.9"));
        assertEquals(3, DbMigration.getLegacyDataVersion("2.0"));
        assertEquals(3, DbMigration.getLegacyDataVersion("2.3"));
        // The first builds of Passdroid++
        assertEquals(3, DbMigration.getLegacyDataVersion("2.4"));
        assertEquals(DbMigration.DATA_VERSION, DbMigration.getLegacyDataVersion("2.4"));
    }

    @Test
    public void invalidLegacyVersionIsCurrent() {
        // Converting entries that are already current would destroy them
        assertEquals(DbMigration.DATA_VERSION, DbMigration.getLegacyDataVersion("3.0.0"));
        assertEquals(DbMigration.DATA_VERSION, DbMigration.getLegacyDataVersion(""));
        assertEquals(DbMigration.DATA_VERSION, DbMigration.getLegacyDataVersion("x"));
    }
}
