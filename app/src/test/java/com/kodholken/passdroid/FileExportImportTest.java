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
    // Low iteration count to keep the tests fast
    private static final int ITERATIONS = 1000;

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
            createEntry("Tom & Jerry's <\"shop\">", "a<b", "p]]>w&d<",
                        "line 1\nline 2", "https://example.com/?a=1&b=2"),
            createEntry("CDATA", "]]>", "]]]]>>", "x]]>y]]>", "]]"),
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
    public void invalidCharactersAreRemoved() throws Exception {
        // Control characters, e.g. in a pasted note, are not allowed in XML.
        // Tabs, line breaks and characters outside the BMP are.
        PasswordEntry [] entries = {
            createEntry("Sys\u0001tem", "user\u0000", "pass\uFFFEword",
                        "tab\tnew\nline \uD83D\uDE00 \uD800",
                        "https://example.com/\u0008")
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new FileExporter(output, APP_VERSION).exportCleartext(entries);

        FileImporter importer = new FileImporter(output.toByteArray());
        importer.parse();
        assertEntriesEqual(new PasswordEntry [] {
            createEntry("System", "user", "password",
                        "tab\tnew\nline \uD83D\uDE00 ", "https://example.com/")
        }, importer.getPasswordEntries());
    }

    private static byte [] exportEncrypted(MasterKey masterKey,
                                           PasswordEntry [] entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FileExporter(output, APP_VERSION).exportEncrypted(
                masterKey.getKey(),
                MasterKey.getSalt(masterKey.getRecord()),
                MasterKey.getIterations(masterKey.getRecord()),
                entries);
        return output.toByteArray();
    }

    private static FileImporterException assertDecryptionFails(byte [] file, byte [] key) {
        try {
            new FileImporter(file).parseEncrypted(key);
            fail("Expected FileImporterException");
            return null;
        } catch (FileImporterException ex) {
            return ex;
        }
    }

    private static FileImporterException assertDecryptionFails(byte [] file, String password) {
        try {
            new FileImporter(file).parseEncryptedWithPassword(password);
            fail("Expected FileImporterException");
            return null;
        } catch (FileImporterException ex) {
            return ex;
        }
    }

    @Test
    public void encryptedRoundTrip() throws Exception {
        PasswordEntry [] entries = createEntries();
        MasterKey masterKey = MasterKey.create("master password", ITERATIONS);
        byte [] file = exportEncrypted(masterKey, entries);

        assertEquals('s', file[0]);
        assertEquals('q', file[1]);
        assertEquals('2', file[2]);

        FileImporter importer = new FileImporter(file);
        assertTrue(importer.isEncrypted());
        importer.parseEncrypted(masterKey.getKey());
        assertEntriesEqual(entries, importer.getPasswordEntries());

        importer = new FileImporter(file);
        importer.parseEncryptedWithPassword("master password");
        assertEntriesEqual(entries, importer.getPasswordEntries());
    }

    @Test
    public void encryptedWithWrongKeyFails() throws Exception {
        byte [] file = exportEncrypted(
                MasterKey.create("master password", ITERATIONS), createEntries());

        assertTrue(assertDecryptionFails(file,
                MasterKey.create("master password", ITERATIONS).getKey()).isWrongKey());
        assertTrue(assertDecryptionFails(file, "wrong password").isWrongKey());
    }

    @Test
    public void encryptedFileOfNewerFormatIsNotAWrongKey() throws Exception {
        MasterKey masterKey = MasterKey.create("master password", ITERATIONS);
        byte [] file = encrypt(masterKey,
                createFile(String.valueOf(FileExporter.FORMAT_VERSION + 1), "Mail"));

        // Another password would not help
        assertFalse(assertDecryptionFails(file, masterKey.getKey()).isWrongKey());
        assertFalse(assertDecryptionFails(file, "master password").isWrongKey());
        assertTrue(assertDecryptionFails(file, "wrong password").isWrongKey());
    }

    /**
     * @return an encrypted export of any XML, see FileExporter.exportEncrypted().
     */
    private static byte [] encrypt(MasterKey masterKey, byte [] xml) throws Exception {
        byte [] salt = MasterKey.getSalt(masterKey.getRecord());
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(FileExporter.ENCRYPTED_SIGNATURE);
        header.write(java.nio.ByteBuffer.allocate(4)
                .putInt(MasterKey.getIterations(masterKey.getRecord())).array());
        header.write(salt.length);
        header.write(salt);

        byte [] nonce = new byte[Crypto.GCM_NONCE_LENGTH];
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(header.toByteArray());
        file.write(nonce);
        file.write(Crypto.encryptAesGcm(masterKey.getKey(), nonce, xml,
                                        header.toByteArray()));
        return file.toByteArray();
    }

    @Test
    public void modifiedEncryptedFileIsRejected() throws Exception {
        byte [] file = exportEncrypted(
                MasterKey.create("master password", ITERATIONS), createEntries());

        // Modified data, which can not be told from a wrong key
        byte [] modified = file.clone();
        modified[modified.length - 20] ^= 1;
        assertTrue(assertDecryptionFails(modified, "master password").isWrongKey());

        // Modified salt, which is authenticated as well
        modified = file.clone();
        modified[10] ^= 1;
        assertTrue(assertDecryptionFails(modified, "master password").isWrongKey());

        // Truncated file
        assertFalse(assertDecryptionFails(java.util.Arrays.copyOf(file, 20),
                                          "master password").isWrongKey());
    }

    @Test
    public void unreasonableIterationCountIsRejected() throws Exception {
        byte [] file = exportEncrypted(
                MasterKey.create("master password", ITERATIONS), createEntries());

        file[3] = 0x7f;
        file[4] = (byte) 0xff;
        file[5] = (byte) 0xff;
        file[6] = (byte) 0xff;
        assertDecryptionFails(file, "master password");
    }

    @Test
    public void legacyEncryptedRoundTrip() throws Exception {
        PasswordEntry [] entries = createEntries();
        byte [] key = Crypto.hmacFromPassword("master password");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new FileExporter(output, APP_VERSION).exportEncryptedLegacy(key, entries);
        byte [] file = output.toByteArray();
        assertEquals('t', file[2]);

        FileImporter importer = new FileImporter(file);
        assertTrue(importer.isEncrypted());
        importer.parseEncrypted(key);
        assertEntriesEqual(entries, importer.getPasswordEntries());

        importer = new FileImporter(file);
        importer.parseEncryptedWithPassword("master password");
        assertEntriesEqual(entries, importer.getPasswordEntries());

        assertTrue(assertDecryptionFails(file, "wrong password").isWrongKey());
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
