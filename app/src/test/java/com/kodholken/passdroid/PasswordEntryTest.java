package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;

/**
 * The entries were encrypted with the Bouncy Castle provider before, the
 * default provider must read them and write the same format.
 */
public class PasswordEntryTest {
    private static final Provider BC = new BouncyCastleProvider();

    private static byte [] randomBytes(int length) {
        byte [] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Encrypt like earlier versions: the IV followed by the encrypted text.
     */
    private static String encryptWithBouncyCastle(byte [] key, String clear) throws Exception {
        byte [] iv = randomBytes(16);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", BC);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
        byte [] encrypted = cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));

        byte [] complete = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, complete, 0, iv.length);
        System.arraycopy(encrypted, 0, complete, iv.length, encrypted.length);
        return Base64.encode(complete);
    }

    private static String decryptWithBouncyCastle(byte [] key, String encrypted) throws Exception {
        byte [] complete = Base64.decode(encrypted);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", BC);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(complete, 0, 16));
        return new String(cipher.doFinal(complete, 16, complete.length - 16),
                          StandardCharsets.UTF_8);
    }

    // Lengths around the block size, where the padding differs
    private static final String [] VALUES = {
        "", "a", "fifteen chars..", "sixteen chars...", "seventeen chars..",
        "åäö € 😀", "a much longer value that spans several blocks of AES"
    };

    @Test
    public void readsEntriesEncryptedWithBouncyCastle() throws Exception {
        byte [] key = randomBytes(32);

        for (String value : VALUES) {
            PasswordEntry entry = new PasswordEntry();
            entry.setEncSystem(encryptWithBouncyCastle(key, "system " + value));
            entry.setEncUsername(encryptWithBouncyCastle(key, value));
            entry.setEncPassword(encryptWithBouncyCastle(key, value + "!"));

            entry.decryptAll(key);

            assertEquals("system " + value, entry.getDecSystem());
            assertEquals(value, entry.getDecUsername());
            assertEquals(value + "!", entry.getDecPassword());
        }
    }

    @Test
    public void writesEntriesBouncyCastleReads() throws Exception {
        byte [] key = randomBytes(32);

        for (String value : VALUES) {
            PasswordEntry entry = new PasswordEntry();
            entry.setDecSystem("system " + value);
            entry.setDecUsername(value);
            entry.setDecPassword(value + "!");

            entry.encryptAll(key);

            assertEquals("system " + value, decryptWithBouncyCastle(key, entry.getEncSystem()));
            assertEquals(value, decryptWithBouncyCastle(key, entry.getEncUsername()));
            assertEquals(value + "!", decryptWithBouncyCastle(key, entry.getEncPassword()));
        }
    }

    @Test
    public void convertsZeroIvEntriesEncryptedWithBouncyCastle() throws Exception {
        // Passdroid 1.x: a zero IV and two random bytes before the text
        byte [] key = randomBytes(32);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", BC);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(new byte[16]));
        cipher.update(randomBytes(2));
        String old = Base64.encode(cipher.doFinal("secret".getBytes(StandardCharsets.UTF_8)));

        PasswordEntry entry = new PasswordEntry();
        entry.setEncSystem(old);
        entry.setEncUsername(old);
        entry.setEncPassword(old);
        entry.convertZeroIvToIv(key);
        entry.decryptAll(key);

        assertEquals("secret", entry.getDecPassword());
    }

    @Test
    public void convertsEcbEntriesEncryptedWithBouncyCastle() throws Exception {
        // The first versions: ECB and two random bytes before the text
        byte [] key = randomBytes(32);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding", BC);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        cipher.update(randomBytes(2));
        String old = Base64.encode(cipher.doFinal("secret".getBytes(StandardCharsets.UTF_8)));

        PasswordEntry entry = new PasswordEntry();
        entry.setEncSystem(old);
        entry.setEncUsername(old);
        entry.setEncPassword(old);
        entry.convertEcbToCbc(key);
        entry.decryptAll(key);

        assertEquals("secret", entry.getDecPassword());
    }

    private static PasswordEntry encryptedEntry(byte [] key) {
        PasswordEntry entry = new PasswordEntry();
        entry.setId(42);
        entry.setDecSystem("Mail");
        entry.setDecEmail("alice@example.com");
        entry.setDecUsername("alice");
        entry.setDecPassword("secret");
        entry.setDecTotp("JBSWY3DPEHPK3PXP");
        entry.setDecNote("note");
        entry.setDecUrl("https://example.com");
        entry.setDecDate("2026-10-25T14:30");
        entry.encryptAll(key);

        // As read from the database
        PasswordEntry stored = new PasswordEntry();
        stored.setId(entry.getId());
        stored.setEncSystem(entry.getEncSystem());
        stored.setEncEmail(entry.getEncEmail());
        stored.setEncUsername(entry.getEncUsername());
        stored.setEncPassword(entry.getEncPassword());
        stored.setEncTotp(entry.getEncTotp());
        stored.setEncNote(entry.getEncNote());
        stored.setEncUrl(entry.getEncUrl());
        stored.setEncDate(entry.getEncDate());
        return stored;
    }

    @Test
    public void onlyListFieldsAreDecryptedWhenLoading() {
        byte [] key = randomBytes(32);
        PasswordEntry entry = encryptedEntry(key);

        entry.decryptListFields(key);

        assertEquals("Mail", entry.getDecSystem());
        assertEquals("alice@example.com", entry.getDecEmail());
        assertEquals("alice", entry.getDecUsername());
        assertNull(entry.getDecPassword());
        assertNull(entry.getDecTotp());
        assertNull(entry.getDecNote());
        assertNull(entry.getDecUrl());
        assertNull(entry.getDecDate());

        // The others on demand, without keeping them
        assertEquals("secret", entry.decryptPassword(key));
        assertEquals("JBSWY3DPEHPK3PXP", entry.decryptTotp(key));
        assertEquals("note", entry.decryptNote(key));
        assertEquals("https://example.com", entry.decryptUrl(key));
        assertEquals("2026-10-25T14:30", entry.decryptDate(key));
        assertNull(entry.getDecPassword());
    }

    @Test
    public void decryptedCopyHasAllFields() {
        byte [] key = randomBytes(32);
        PasswordEntry entry = encryptedEntry(key);
        entry.decryptListFields(key);

        PasswordEntry copy = entry.decryptedCopy(key);

        assertEquals(42, copy.getId());
        assertEquals("Mail", copy.getDecSystem());
        assertEquals("secret", copy.getDecPassword());
        assertEquals("JBSWY3DPEHPK3PXP", copy.getDecTotp());
        assertEquals("2026-10-25T14:30", copy.getDecDate());
        assertNull(entry.getDecPassword());
    }

    @Test
    public void fieldsOfOldEntriesAreNull() {
        // Entries added before the email, 2FA secret and date existed
        byte [] key = randomBytes(32);
        PasswordEntry entry = encryptedEntry(key);
        entry.setEncEmail(null);
        entry.setEncTotp(null);
        entry.setEncDate(null);

        entry.decryptListFields(key);

        assertNull(entry.getDecEmail());
        assertNull(entry.decryptTotp(key));
        assertNull(entry.decryptDate(key));
    }
}
