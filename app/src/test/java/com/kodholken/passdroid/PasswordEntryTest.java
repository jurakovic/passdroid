package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;

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
}
