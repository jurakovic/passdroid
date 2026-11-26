package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

public class ShortPasswordReminderTest {
    private static final byte [] KEY = key(1);
    private static final byte [] OTHER_KEY = key(2);

    private static byte [] key(int seed) {
        byte [] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (seed * 31 + i);
        }
        return key;
    }

    @Test
    public void roundTrip() {
        assertEquals(Boolean.TRUE, ShortPasswordReminder.decrypt(
                KEY, ShortPasswordReminder.encrypt(KEY, true)));
        assertEquals(Boolean.FALSE, ShortPasswordReminder.decrypt(
                KEY, ShortPasswordReminder.encrypt(KEY, false)));
    }

    // A copy of the database must not tell the two values apart
    @Test
    public void bothValuesHaveTheSameLength() {
        String on = ShortPasswordReminder.encrypt(KEY, true);
        String off = ShortPasswordReminder.encrypt(KEY, false);

        assertNotNull(on);
        assertNotNull(off);
        // A 12 byte nonce, the byte and a 16 byte tag
        assertEquals(40, on.length());
        assertEquals(40, off.length());
    }

    // Stored again after every login: two copies of the database must not
    // show whether it changed
    @Test
    public void freshNonceEachTime() {
        assertNotEquals(ShortPasswordReminder.encrypt(KEY, false),
                        ShortPasswordReminder.encrypt(KEY, false));
    }

    @Test
    public void notWithAnotherKeyOrWithout() {
        String value = ShortPasswordReminder.encrypt(KEY, false);

        assertNull(ShortPasswordReminder.decrypt(OTHER_KEY, value));
        assertNull(ShortPasswordReminder.decrypt(null, value));
        assertNull(ShortPasswordReminder.encrypt(null, true));
    }

    @Test
    public void otherTextRejected() throws Base64Exception {
        String value = ShortPasswordReminder.encrypt(KEY, false);
        byte [] stored = Base64.decode(value);

        // Modified
        byte [] modified = stored.clone();
        modified[modified.length - 1] ^= 1;
        assertNull(ShortPasswordReminder.decrypt(KEY, Base64.encode(modified)));

        // Truncated, empty, only a nonce, not Base64
        assertNull(ShortPasswordReminder.decrypt(KEY, Base64.encode(
                Arrays.copyOf(stored, stored.length - 1))));
        assertNull(ShortPasswordReminder.decrypt(KEY, ""));
        assertNull(ShortPasswordReminder.decrypt(KEY, Base64.encode(
                Arrays.copyOf(stored, Crypto.GCM_NONCE_LENGTH))));
        assertNull(ShortPasswordReminder.decrypt(KEY, "not base64!"));
    }

    // Only the two values, and only with this attribute's associated data
    @Test
    public void otherValuesRejected() {
        byte [] nonce = new byte[Crypto.GCM_NONCE_LENGTH];
        byte [] aad = ShortPasswordReminder.ATTRIBUTE.getBytes(StandardCharsets.UTF_8);

        assertNull(ShortPasswordReminder.decrypt(KEY, stored(nonce,
                Crypto.encryptAesGcm(KEY, nonce, new byte [] { 2 }, aad))));
        assertNull(ShortPasswordReminder.decrypt(KEY, stored(nonce,
                Crypto.encryptAesGcm(KEY, nonce, new byte [] { 1, 1 }, aad))));
        assertNull(ShortPasswordReminder.decrypt(KEY, stored(nonce,
                Crypto.encryptAesGcm(KEY, nonce, new byte [] { 1 },
                                     "entry_draft".getBytes(StandardCharsets.UTF_8)))));

        // The same with this attribute's data is accepted
        assertEquals(Boolean.TRUE, ShortPasswordReminder.decrypt(KEY, stored(nonce,
                Crypto.encryptAesGcm(KEY, nonce, new byte [] { 1 }, aad))));
    }

    private static String stored(byte [] nonce, byte [] encrypted) {
        byte [] stored = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, stored, nonce.length, encrypted.length);
        return Base64.encode(stored);
    }
}
