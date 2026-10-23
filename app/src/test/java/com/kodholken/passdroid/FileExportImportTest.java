package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Round trip tests for exporting and importing the password database.
 */
public class FileExportImportTest {
    private static final String APP_VERSION = "2.3";

    @BeforeClass
    public static void addBouncyCastle() {
        // Crypto explicitly asks for the "BC" provider which Android has built in
        Security.addProvider(new BouncyCastleProvider());
    }

    private static PasswordEntry createEntry(String system, String username,
                                             String password, String note,
                                             String url) {
        PasswordEntry entry = new PasswordEntry();
        entry.setDecSystem(system);
        entry.setDecUsername(username);
        entry.setDecPassword(password);
        entry.setDecNote(note);
        entry.setDecUrl(url);
        return entry;
    }

    private static PasswordEntry [] createEntries() {
        return new PasswordEntry [] {
            createEntry("Mail", "user@example.com", "secret", "", ""),
            createEntry("Tom & Jerry's <\"shop\">", "a<b", "p]w&d<",
                        "line 1\nline 2", "https://example.com/?a=1&b=2"),
            createEntry("Unicode åäö", "ü", "€ß",
                        "", "")
        };
    }

    private static void assertEntriesEqual(PasswordEntry [] expected,
                                           PasswordEntry [] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].getDecSystem(), actual[i].getDecSystem());
            assertEquals(expected[i].getDecUsername(), actual[i].getDecUsername());
            assertEquals(expected[i].getDecPassword(), actual[i].getDecPassword());
            assertEquals(expected[i].getDecNote(), actual[i].getDecNote());
            assertEquals(expected[i].getDecUrl(), actual[i].getDecUrl());
        }
    }

    @Test
    public void cleartextRoundTrip() throws Exception {
        PasswordEntry [] entries = createEntries();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new FileExporter(output, APP_VERSION).exportCleartext(entries);

        FileImporter importer = new FileImporter(output.toByteArray(), APP_VERSION);
        assertFalse(importer.isEncrypted());
        importer.parse();
        assertEntriesEqual(entries, importer.getPasswordEntries());
    }

    @Test
    public void encryptedRoundTrip() throws Exception {
        PasswordEntry [] entries = createEntries();
        byte [] key = Crypto.hmacFromPassword("master password");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new FileExporter(output, APP_VERSION).exportEncrypted(key, entries);

        FileImporter importer = new FileImporter(output.toByteArray(), APP_VERSION);
        assertTrue(importer.isEncrypted());
        importer.parseEncrypted(key);
        assertEntriesEqual(entries, importer.getPasswordEntries());
    }

    @Test
    public void encryptedWithWrongKeyFails() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FileExporter(output, APP_VERSION).exportEncrypted(
                Crypto.hmacFromPassword("master password"), createEntries());

        FileImporter importer = new FileImporter(output.toByteArray(), APP_VERSION);
        try {
            importer.parseEncrypted(Crypto.hmacFromPassword("wrong password"));
            fail("Expected FileImporterException");
        } catch (FileImporterException ex) {
            // Expected
        }
    }

    @Test
    public void newerFileVersionIsRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FileExporter(output, "9.9").exportCleartext(createEntries());

        FileImporter importer = new FileImporter(output.toByteArray(), APP_VERSION);
        try {
            importer.parse();
            fail("Expected FileImporterException");
        } catch (FileImporterException ex) {
            // Expected
        }
    }
}
