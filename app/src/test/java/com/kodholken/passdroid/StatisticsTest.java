package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatisticsTest {
    private static PasswordEntry entry(String email, String username,
                                       String password) {
        PasswordEntry entry = new PasswordEntry();
        entry.setDecSystem("System");
        entry.setDecEmail(email);
        entry.setDecUsername(username);
        entry.setDecPassword(password);
        return entry;
    }

    private static PasswordEntry withPassword(String password) {
        return entry(null, null, password);
    }

    private static void assertAllFieldsZero(Statistics statistics) {
        assertEquals(0, statistics.withEmail);
        assertEquals(0, statistics.differentEmails);
        assertEquals(0, statistics.withUsername);
        assertEquals(0, statistics.differentUsernames);
        assertEquals(0, statistics.withPassword);
        assertEquals(0, statistics.reusedPasswords);
        assertEquals(0, statistics.entriesWithReusedPassword);
        assertEquals(0, statistics.withTotp);
        assertEquals(0, statistics.withNote);
        assertEquals(0, statistics.withUrl);
        assertEquals(0, statistics.withDate);
    }

    @Test
    public void emptyInput() {
        Statistics statistics = Statistics.count(new PasswordEntry[0]);

        assertEquals(0, statistics.entries);
        assertAllFieldsZero(statistics);
    }

    @Test
    public void everyField() {
        PasswordEntry full = entry("alice@example.com", "alice", "s3cret");
        full.setDecTotp("JBSWY3DPEHPK3PXP");
        full.setDecNote("Recovery codes");
        full.setDecUrl("https://example.com");
        full.setDecDate("2026-10-25T14:30");

        Statistics statistics = Statistics.count(new PasswordEntry [] {
            full, new PasswordEntry()
        });

        assertEquals(2, statistics.entries);
        assertEquals(1, statistics.withEmail);
        assertEquals(1, statistics.differentEmails);
        assertEquals(1, statistics.withUsername);
        assertEquals(1, statistics.differentUsernames);
        assertEquals(1, statistics.withPassword);
        assertEquals(0, statistics.reusedPasswords);
        assertEquals(1, statistics.withTotp);
        assertEquals(1, statistics.withNote);
        assertEquals(1, statistics.withUrl);
        assertEquals(1, statistics.withDate);
    }

    @Test
    public void nullEmptyAndSpacesDontCount() {
        String [] noValues = { null, "", " ", " \t\n " };
        PasswordEntry [] entries = new PasswordEntry[noValues.length];
        for (int i = 0; i < noValues.length; i++) {
            String value = noValues[i];
            // null also stands for a field that couldn't be decrypted
            entries[i] = entry(value, value, value);
            entries[i].setDecTotp(value);
            entries[i].setDecNote(value);
            entries[i].setDecUrl(value);
            entries[i].setDecDate(value);
        }

        Statistics statistics = Statistics.count(entries);

        assertEquals(noValues.length, statistics.entries);
        assertAllFieldsZero(statistics);
    }

    @Test
    public void emailsIgnoreCaseAndSpaces() {
        Statistics statistics = Statistics.count(new PasswordEntry [] {
            entry("alice@example.com", null, null),
            entry(" Alice@Example.COM ", null, null),
            entry("ALICE@EXAMPLE.COM", null, null),
            entry("bob@example.com", null, null),
        });

        assertEquals(4, statistics.withEmail);
        assertEquals(2, statistics.differentEmails);
    }

    @Test
    public void usernamesIgnoreOnlySpaces() {
        Statistics statistics = Statistics.count(new PasswordEntry [] {
            entry(null, "alice", null),
            entry(null, " alice\t", null),
            entry(null, "Alice", null),
        });

        assertEquals(3, statistics.withUsername);
        assertEquals(2, statistics.differentUsernames);
    }

    @Test
    public void reusedPasswords() {
        Statistics statistics = Statistics.count(new PasswordEntry [] {
            withPassword("hunter2"),
            withPassword("hunter2"),
            withPassword("hunter2"),
            withPassword("correct horse"),
            withPassword("correct horse"),
            withPassword("unique"),
        });

        assertEquals(6, statistics.withPassword);
        assertEquals(2, statistics.reusedPasswords);
        assertEquals(5, statistics.entriesWithReusedPassword);
    }

    @Test
    public void uniquePasswordsAreNotReused() {
        // Compared exactly: case and spaces make them different
        Statistics statistics = Statistics.count(new PasswordEntry [] {
            withPassword("hunter2"),
            withPassword("Hunter2"),
            withPassword("hunter2 "),
            withPassword("other"),
        });

        assertEquals(4, statistics.withPassword);
        assertEquals(0, statistics.reusedPasswords);
        assertEquals(0, statistics.entriesWithReusedPassword);
    }
}
