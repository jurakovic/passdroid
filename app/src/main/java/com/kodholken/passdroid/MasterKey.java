package com.kodholken.passdroid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The key derived from the master password, together with the record that is
 * stored in the database to verify the password. The record has the format
 *
 *   pbkdf2-sha256$&lt;iterations&gt;$&lt;base64 salt&gt;$&lt;base64 verifier&gt;
 *
 * where the verifier is a SHA-256 hash of the derived key. Databases created
 * by Passdroid 2.3 and earlier store a legacy record, see
 * Crypto.verifyPassword(), which is replaced at the next login.
 */
public class MasterKey {
    public static final int DEFAULT_ITERATIONS = 600000;

    // Upper limit for iteration counts read from files, so that a corrupt or
    // malicious file can not make the key derivation run (almost) forever.
    public static final int MAX_ITERATIONS = 10000000;

    // New master passwords must be at least this long. The key derivation
    // slows down guessing, but a short password can still be found by
    // someone who has a copy of the database or an export.
    public static final int MIN_PASSWORD_LENGTH = 12;

    private static final String RECORD_PREFIX = "pbkdf2-sha256";
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 32;
    private static final byte [] VERIFIER_PREFIX =
        "passdroid-key-verifier".getBytes(StandardCharsets.UTF_8);

    private final String record;
    private final byte [] key;

    private MasterKey(String record, byte [] key) {
        this.record = record;
        this.key = key;
    }

    /**
     * @return the record to store in the database.
     */
    public String getRecord() {
        return record;
    }

    /**
     * @return the key used to encrypt the password database.
     */
    public byte [] getKey() {
        return key;
    }

    /**
     * @return true if a password is too short to be a new master password.
     */
    public static boolean isTooShort(String password) {
        return password.codePointCount(0, password.length()) < MIN_PASSWORD_LENGTH;
    }

    /**
     * Derive a key from a new master password using a random salt. This is
     * slow and should not be called on the UI thread.
     */
    public static MasterKey create(String password) {
        return create(password, DEFAULT_ITERATIONS);
    }

    static MasterKey create(String password, int iterations) {
        byte [] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);

        byte [] key = deriveKey(password, salt, iterations);
        String record = RECORD_PREFIX + "$" + iterations + "$" +
                        Base64.encode(salt) + "$" +
                        Base64.encode(verifier(key));

        return new MasterKey(record, key);
    }

    /**
     * Verify a password against a record stored in the database. This is
     * slow and should not be called on the UI thread.
     *
     * @return the key derived from the password or null if the password is
     *         incorrect.
     */
    public static MasterKey unlock(String password, String record) {
        if (isLegacy(record)) {
            if (!Crypto.verifyPassword(password, record)) {
                return null;
            }
            return new MasterKey(record, Crypto.hmacFromPassword(password));
        }

        try {
            String [] parts = record.split("\\$");
            byte [] key = deriveKey(password, Base64.decode(parts[2]),
                                    Integer.parseInt(parts[1]));

            if (!verifyKey(key, record)) {
                return null;
            }
            return new MasterKey(record, key);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Check that a key, e.g. one stored for biometric unlock, belongs to a
     * (non legacy) record.
     */
    public static boolean verifyKey(byte [] key, String record) {
        try {
            return MessageDigest.isEqual(verifier(key),
                                         Base64.decode(record.split("\\$")[3]));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * @return true if the record was created by Passdroid 2.3 or earlier.
     */
    public static boolean isLegacy(String record) {
        return !record.startsWith(RECORD_PREFIX + "$");
    }

    /**
     * @return the salt of a (non legacy) record.
     */
    public static byte [] getSalt(String record) throws Base64Exception {
        return Base64.decode(record.split("\\$")[2]);
    }

    /**
     * @return the iteration count of a (non legacy) record.
     */
    public static int getIterations(String record) {
        return Integer.parseInt(record.split("\\$")[1]);
    }

    /**
     * Derive a 256 bit key using PBKDF2 with HMAC-SHA256.
     */
    public static byte [] deriveKey(String password, byte [] salt, int iterations) {
        try {
            // Natively implemented from Android 8.0
            SecretKeyFactory factory =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                                             iterations, KEY_LENGTH * 8);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            // Not available, or does not accept the input (e.g. an empty
            // password). Fall back on our own implementation.
            return pbkdf2HmacSha256(password.getBytes(StandardCharsets.UTF_8),
                                    salt, iterations, KEY_LENGTH);
        }
    }

    /**
     * PBKDF2 with HMAC-SHA256 as specified in RFC 8018.
     */
    static byte [] pbkdf2HmacSha256(byte [] password, byte [] salt,
                                    int iterations, int length) {
        final int blockSize = 64;
        final int hashLength = 32;

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            // HMAC keys longer than the block size are hashed
            byte [] hmacKey = password.length > blockSize ?
                              sha256.digest(password) : password;
            byte [] innerPad = new byte[blockSize];
            byte [] outerPad = new byte[blockSize];
            for (int i = 0; i < blockSize; i++) {
                byte k = i < hmacKey.length ? hmacKey[i] : 0;
                innerPad[i] = (byte) (k ^ 0x36);
                outerPad[i] = (byte) (k ^ 0x5c);
            }

            byte [] result = new byte[length];
            for (int block = 1; (block - 1) * hashLength < length; block++) {
                byte [] index = {
                    (byte) (block >>> 24), (byte) (block >>> 16),
                    (byte) (block >>> 8), (byte) block
                };

                byte [] u = hmac(sha256, innerPad, outerPad, salt, index);
                byte [] t = u.clone();
                for (int i = 1; i < iterations; i++) {
                    u = hmac(sha256, innerPad, outerPad, u, null);
                    for (int j = 0; j < t.length; j++) {
                        t[j] ^= u[j];
                    }
                }

                int offset = (block - 1) * hashLength;
                System.arraycopy(t, 0, result, offset,
                                 Math.min(hashLength, length - offset));
            }

            return result;
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static byte [] hmac(MessageDigest sha256, byte [] innerPad,
                                byte [] outerPad, byte [] data1, byte [] data2) {
        sha256.update(innerPad);
        sha256.update(data1);
        if (data2 != null) {
            sha256.update(data2);
        }
        byte [] inner = sha256.digest();

        sha256.update(outerPad);
        return sha256.digest(inner);
    }

    private static byte [] verifier(byte [] key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(VERIFIER_PREFIX);
            return sha256.digest(key);
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }
}
