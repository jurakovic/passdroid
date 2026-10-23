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
    private static final String APP_VERSION = "3.0.0";

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

        FileImporter importer = new FileImporter(output.toByteArray());
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

        FileImporter importer = new FileImporter(output.toByteArray());
        assertTrue(importer.isEncrypted());
        importer.parseEncrypted(key);
        assertEntriesEqual(entries, importer.getPasswordEntries());
    }

    @Test
    public void encryptedWithWrongKeyFails() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FileExporter(output, APP_VERSION).exportEncrypted(
                Crypto.hmacFromPassword("master password"), createEntries());

        FileImporter importer = new FileImporter(output.toByteArray());
        try {
            importer.parseEncrypted(Crypto.hmacFromPassword("wrong password"));
            fail("Expected FileImporterException");
        } catch (FileImporterException ex) {
            // Expected
        }
    }

    @Test
    public void exportHasFormatVersion() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FileExporter(output, APP_VERSION).exportCleartext(createEntries());

        String xml = output.toString("UTF-8");
        assertTrue(xml, xml.contains("<passdroid version=\"" +
                                     FileExporter.FORMAT_VERSION +
                                     "\" app-version=\"" + APP_VERSION + "\">"));
    }

    @Test
    public void formatVersionOfEarlierFiles() throws Exception {
        // The version of the app that exported the file
        assertEquals(1, FileImporter.getFormatVersion("0.9"));
        assertEquals(1, FileImporter.getFormatVersion("1.6"));
        assertEquals(2, FileImporter.getFormatVersion("1.7"));
        assertEquals(2, FileImporter.getFormatVersion("2.3"));
        assertEquals(3, FileImporter.getFormatVersion("2.4"));
        // The format version
        assertEquals(3, FileImporter.getFormatVersion("3"));
    }

    @Test
    public void filesOfEarlierVersionsAreImported() throws Exception {
        // Passdroid 1.6 and earlier did not escape the system name
        assertImported("1.6", "Tom & Jerry", "Tom & Jerry");
        assertImported("2.3", "Tom &amp; Jerry", "Tom & Jerry");
        assertImported("2.4", "Tom &amp; Jerry", "Tom & Jerry");
        assertImported("3", "Tom &amp; Jerry", "Tom & Jerry");
    }

    @Test
    public void newerFileFormatIsRejected() throws Exception {
        assertImportRejected(String.valueOf(FileExporter.FORMAT_VERSION + 1));
        // No later app versions were written to files
        assertImportRejected("2.5");
        assertImportRejected("3.0");
        // Invalid versions
        assertImportRejected("0");
        assertImportRejected("3.0.0");
        assertImportRejected("x");
    }

    private static byte [] createFile(String version, String systemName)
            throws Exception {
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<passdroid version=\"" + version + "\">\n" +
            "  <system name=\"" + systemName + "\">\n" +
            "    <username>alice</username>\n" +
            "    <password>secret</password>\n" +
            "  </system>\n" +
            "</passdroid>\n";
        return xml.getBytes("UTF-8");
    }

    private static void assertImported(String version, String systemName,
                                       String expected) throws Exception {
        FileImporter importer = new FileImporter(createFile(version, systemName));
        importer.parse();
        assertEntriesEqual(new PasswordEntry [] {
            createEntry(expected, "alice", "secret", "", "")
        }, importer.getPasswordEntries());
    }

    private static void assertImportRejected(String version) throws Exception {
        FileImporter importer = new FileImporter(createFile(version, "Mail"));
        try {
            importer.parse();
            fail("Version " + version + " was accepted");
        } catch (FileImporterException ex) {
            // Expected
        }
    }
}
