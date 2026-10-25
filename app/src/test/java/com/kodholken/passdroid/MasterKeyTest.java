package com.kodholken.passdroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

import org.junit.Test;

public class MasterKeyTest {
    // Low iteration count to keep the tests fast
    private static final int ITERATIONS = 1000;

    private static byte [] hex(String s) {
        byte [] result = new byte[s.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return result;
    }

    private static byte [] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Create a master key record the way Passdroid 2.3 and earlier did.
     */
    private static String createLegacyRecord(String password) {
        byte [] key = new byte[32];
        new Random().nextBytes(key);

        CRC32 crc = new CRC32();
        crc.update(key, 0, 28);
        long crcValue = crc.getValue();
        key[28] = (byte) ((crcValue >> 24) & 0xff);
        key[29] = (byte) ((crcValue >> 16) & 0xff);
        key[30] = (byte) ((crcValue >> 8) & 0xff);
        key[31] = (byte) (crcValue & 0xff);

        byte [] hmac = Crypto.hmacFromPassword(password);
        for (int i = 0; i < key.length; i++) {
            key[i] ^= hmac[i];
        }

        return Base64.encode(key);
    }

    @Test
    public void pbkdf2MatchesRfc7914TestVectors() {
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
            MasterKey.pbkdf2HmacSha256(bytes("passwd"), bytes("salt"), 1, 64));
        assertArrayEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
            MasterKey.pbkdf2HmacSha256(bytes("Password"), bytes("NaCl"), 80000, 64));
    }

    @Test
    public void pbkdf2HandlesEmptyAndLongPasswords() {
        // Reference values from Python's hashlib.pbkdf2_hmac()
        assertArrayEquals(
            hex("62384466264daadc4144018c6bd864648272b34da8980d31521ffcce92ae003b"),
            MasterKey.pbkdf2HmacSha256(new byte[0], bytes("salt"), 2, 32));
        assertArrayEquals(
            hex("59bfa49750dd5462ce38370a1e7abe0736ff334cf1c2f0d84f23a03435d660d1"),
            MasterKey.pbkdf2HmacSha256(bytes(new String(new char[100]).replace('\0', 'x')),
                                       bytes("salt"), 3, 32));
    }

    @Test
    public void deriveKeyEncodesPasswordAsUtf8() {
        assertArrayEquals(
            hex("dd23090d2c628c100c5376256958736135e5013c9703de8999548a86aafb8e8d"),
            MasterKey.deriveKey("päss€", bytes("0123456789abcdef"), 1000));
    }

    @Test
    public void deriveKeyAcceptsEmptyPassword() {
        assertArrayEquals(
            hex("62384466264daadc4144018c6bd864648272b34da8980d31521ffcce92ae003b"),
            MasterKey.deriveKey("", bytes("salt"), 2));
    }

    @Test
    public void createAndUnlock() throws Exception {
        MasterKey created = MasterKey.create("secret", ITERATIONS);
        String record = created.getRecord();

        assertTrue(record.startsWith("pbkdf2-sha256$" + ITERATIONS + "$"));
        assertFalse(MasterKey.isLegacy(record));
        assertEquals(ITERATIONS, MasterKey.getIterations(record));
        assertEquals(16, MasterKey.getSalt(record).length);
        assertEquals(32, created.getKey().length);

        MasterKey unlocked = MasterKey.unlock("secret", record);
        assertNotNull(unlocked);
        assertArrayEquals(created.getKey(), unlocked.getKey());
        assertArrayEquals(created.getKey(),
                          MasterKey.deriveKey("secret", MasterKey.getSalt(record),
                                              ITERATIONS));

        assertNull(MasterKey.unlock("Secret", record));
        assertNull(MasterKey.unlock("", record));
    }

    @Test
    public void saltIsRandom() {
        MasterKey a = MasterKey.create("secret", ITERATIONS);
        MasterKey b = MasterKey.create("secret", ITERATIONS);

        assertFalse(a.getRecord().equals(b.getRecord()));
        assertFalse(java.util.Arrays.equals(a.getKey(), b.getKey()));
    }

    @Test
    public void unlockLegacyRecord() {
        String record = createLegacyRecord("secret");

        assertTrue(MasterKey.isLegacy(record));

        MasterKey unlocked = MasterKey.unlock("secret", record);
        assertNotNull(unlocked);
        assertArrayEquals(Crypto.hmacFromPassword("secret"), unlocked.getKey());

        assertNull(MasterKey.unlock("wrong", record));
    }

    @Test
    public void unlockMalformedRecord() {
        assertNull(MasterKey.unlock("secret", "pbkdf2-sha256$"));
        assertNull(MasterKey.unlock("secret", "pbkdf2-sha256$x$y$z"));
    }
}
