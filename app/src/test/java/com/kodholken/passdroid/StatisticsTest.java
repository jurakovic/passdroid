package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;

import org.junit.Test;

public class StatisticsTest {
    private static final byte [] KEY = new byte[32];
    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) (7 * i + 3);
        }
    }

    private static PasswordEntry values(String email, String username,
                                        String password) {
        PasswordEntry values = new PasswordEntry();
        values.setDecSystem("System");
        values.setDecEmail(email);
        values.setDecUsername(username);
        values.setDecPassword(password);
        return values;
    }

    /**
     * The entry as the list has it: read from the database, with only the
     * list fields decrypted.
     */
    private static PasswordEntry stored(PasswordEntry values) {
        values.encryptAll(KEY);

        PasswordEntry stored = new PasswordEntry();
        stored.setEncSystem(values.getEncSystem());
        stored.setEncEmail(values.getEncEmail());
        stored.setEncUsername(values.getEncUsername());
        stored.setEncPassword(values.getEncPassword());
        stored.setEncTotp(values.getEncTotp());
        stored.setEncNote(values.getEncNote());
        stored.setEncUrl(values.getEncUrl());
        stored.setEncDate(values.getEncDate());
        stored.decryptListFields(KEY);
        return stored;
    }

    private static PasswordEntry entry(String email, String username,
                                       String password) {
        return stored(values(email, username, password));
    }

    private static PasswordEntry withPassword(String password) {
        return entry(null, null, password);
    }

    private static PasswordEntry full() {
        PasswordEntry values = values("alice@example.com", "alice", "s3cret");
        values.setDecTotp("JBSWY3DPEHPK3PXP");
        values.setDecNote("Recovery codes");
        values.setDecUrl("https://example.com");
        values.setDecDate("2026-10-25T14:30");
        return stored(values);
    }

    private static Statistics count(PasswordEntry... entries) {
        return Statistics.count(entries, KEY);
    }

    /**
     * Damage a stored value as one changed byte does: the byte before the
     * last block makes the padding invalid, so it never decrypts.
     */
    private static String damage(String encrypted) throws Base64Exception {
        byte [] complete = Base64.decode(encrypted);
        complete[complete.length - 17] ^= (byte) 0x80;
        return Base64.encode(complete);
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
        Statistics statistics = count();

        assertEquals(0, statistics.entries);
        assertAllFieldsZero(statistics);
    }

    @Test
    public void everyField() {
        Statistics statistics = count(full(), stored(new PasswordEntry()));

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
    public void countsWithoutDecryptingTheOtherFields() {
        PasswordEntry entry = full();

        count(entry, entry);

        assertNull(entry.getDecPassword());
        assertNull(entry.getDecTotp());
        assertNull(entry.getDecNote());
        assertNull(entry.getDecUrl());
        assertNull(entry.getDecDate());
    }

    @Test
    public void nullEmptyAndSpacesDontCount() {
        String [] noValues = { null, "", " ", " \t\n " };
        PasswordEntry [] entries = new PasswordEntry[noValues.length];
        for (int i = 0; i < noValues.length; i++) {
            String value = noValues[i];
            PasswordEntry values = values(value, value, value);
            values.setDecTotp(value);
            values.setDecNote(value);
            values.setDecUrl(value);
            values.setDecDate(value);
            entries[i] = stored(values);
        }

        Statistics statistics = count(entries);

        assertEquals(noValues.length, statistics.entries);
        assertAllFieldsZero(statistics);
    }

    @Test
    public void otherCharactersCount() {
        // A no-break space, a u with an umlaut and the euro sign aren't
        // spaces for String.trim() either, which only removes the
        // characters up to U+0020
        String [] values = { "a", " a ", "\u00a0", "\u00fc", "\u20ac" };
        PasswordEntry [] entries = new PasswordEntry[values.length];
        for (int i = 0; i < values.length; i++) {
            PasswordEntry entry = values(null, null, values[i]);
            entry.setDecTotp(values[i]);
            entry.setDecNote(values[i]);
            entry.setDecUrl(values[i]);
            entry.setDecDate(values[i]);
            entries[i] = stored(entry);
        }

        Statistics statistics = count(entries);

        assertEquals(values.length, statistics.withPassword);
        assertEquals(values.length, statistics.withTotp);
        assertEquals(values.length, statistics.withNote);
        assertEquals(values.length, statistics.withUrl);
        assertEquals(values.length, statistics.withDate);
    }

    @Test
    public void damagedFieldsDontCount() throws Exception {
        PasswordEntry entry = full();
        entry.setEncPassword(damage(entry.getEncPassword()));
        entry.setEncTotp(damage(entry.getEncTotp()));
        entry.setEncNote(damage(entry.getEncNote()));
        entry.setEncUrl(damage(entry.getEncUrl()));
        entry.setEncDate(damage(entry.getEncDate()));

        Statistics statistics = count(entry, entry);

        assertEquals(2, statistics.entries);
        assertEquals(2, statistics.withEmail);
        assertEquals(0, statistics.withPassword);
        assertEquals(0, statistics.reusedPasswords);
        assertEquals(0, statistics.withTotp);
        assertEquals(0, statistics.withNote);
        assertEquals(0, statistics.withUrl);
        assertEquals(0, statistics.withDate);
    }

    @Test
    public void emailsIgnoreCaseAndSpaces() {
        Statistics statistics = count(
            entry("alice@example.com", null, null),
            entry(" Alice@Example.COM ", null, null),
            entry("ALICE@EXAMPLE.COM", null, null),
            entry("bob@example.com", null, null));

        assertEquals(4, statistics.withEmail);
        assertEquals(2, statistics.differentEmails);
    }

    @Test
    public void emailsIgnoreCaseInATurkishLocale() {
        Locale locale = Locale.getDefault();
        try {
            // Where "I" lower-cases to a dotless i
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            Statistics statistics = count(
                entry("ALICE@EXAMPLE.COM", null, null),
                entry("alice@example.com", null, null));

            assertEquals(1, statistics.differentEmails);
        } finally {
            Locale.setDefault(locale);
        }
    }

    @Test
    public void usernamesIgnoreOnlySpaces() {
        Statistics statistics = count(
            entry(null, "alice", null),
            entry(null, " alice\t", null),
            entry(null, "Alice", null));

        assertEquals(3, statistics.withUsername);
        assertEquals(2, statistics.differentUsernames);
    }

    @Test
    public void reusedPasswords() {
        Statistics statistics = count(
            withPassword("hunter2"),
            withPassword("hunter2"),
            withPassword("hunter2"),
            withPassword("correct horse"),
            withPassword("correct horse"),
            withPassword("unique"));

        assertEquals(6, statistics.withPassword);
        assertEquals(2, statistics.reusedPasswords);
        assertEquals(5, statistics.entriesWithReusedPassword);
    }

    @Test
    public void uniquePasswordsAreNotReused() {
        // Compared exactly: case and spaces make them different
        Statistics statistics = count(
            withPassword("hunter2"),
            withPassword("Hunter2"),
            withPassword("hunter2 "),
            withPassword("other"));

        assertEquals(4, statistics.withPassword);
        assertEquals(0, statistics.reusedPasswords);
        assertEquals(0, statistics.entriesWithReusedPassword);
    }
}
