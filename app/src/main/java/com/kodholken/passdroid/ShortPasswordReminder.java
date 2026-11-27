package com.kodholken.passdroid;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import com.kodholken.passdroid.db.SystemData;

import android.content.Context;
import android.database.SQLException;

/**
 * Whether the list reminds of a short master password after a login, which
 * "Don't remind me" in the reminder turns off.
 *
 * The setting is stored for every vault, short master password or not,
 * encrypted with the vault key, and stored again with a fresh nonce after
 * every login. Both values encrypt to the same length. So a copy of the
 * database shows neither which vaults have a short master password nor
 * whether the reminder was turned off. Two copies of different days show
 * whether there was a login between them, but not whether the setting
 * changed.
 */
public final class ShortPasswordReminder {
    public static final String ATTRIBUTE = "short_password_reminder";

    // Authenticated with the value, so that no other value encrypted with
    // the vault key can stand in for it
    private static final byte [] AAD = ATTRIBUTE.getBytes(StandardCharsets.UTF_8);
    private static final byte ON = 1;
    private static final byte OFF = 0;

    private ShortPasswordReminder() {
    }

    /**
     * Read the setting and store it again with a fresh nonce. Called once
     * after every login, so that every vault has it.
     *
     * @return true if the list should remind: also if there is no setting
     *         yet, or it doesn't decrypt.
     */
    public static boolean renew(Context context, byte [] key) {
        String value;
        try {
            value = new SystemData(context).getAttribute(ATTRIBUTE);
        } catch (SQLException ex) {
            // Reminds, and leaves the stored setting as it is
            ex.printStackTrace();
            return true;
        }

        Boolean stored = value != null ? decrypt(key, value) : null;
        boolean on = stored == null || stored;
        set(context, key, on);
        return on;
    }

    /**
     * Store the setting in place of the one there may be.
     *
     * @return false if it could not be encrypted or stored.
     */
    public static boolean set(Context context, byte [] key, boolean on) {
        String value = encrypt(key, on);
        if (value == null) {
            return false;
        }

        try {
            SystemData system = new SystemData(context);
            if (system.hasAttribute(ATTRIBUTE)) {
                system.updateAttribute(ATTRIBUTE, value);
            } else {
                system.setAttribute(ATTRIBUTE, value);
            }
            return true;
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * @return the stored text of the setting: a random nonce followed by the
     *         encrypted value and its tag, in Base64, of the same length for
     *         both values. null without a key or if the encryption failed.
     */
    public static String encrypt(byte [] key, boolean on) {
        if (key == null) {
            return null;
        }

        byte [] nonce = new byte[Crypto.GCM_NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        byte [] encrypted = Crypto.encryptAesGcm(key, nonce,
                                                 new byte [] { on ? ON : OFF },
                                                 AAD);
        if (encrypted == null) {
            return null;
        }

        byte [] stored = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, stored, nonce.length, encrypted.length);
        return Base64.encode(stored);
    }

    /**
     * @return the setting in a text of encrypt(), or null if the text isn't
     *         one or doesn't decrypt with this key.
     */
    static Boolean decrypt(byte [] key, String value) {
        if (key == null) {
            return null;
        }

        byte [] stored;
        try {
            stored = Base64.decode(value);
        } catch (Base64Exception ex) {
            return null;
        }
        if (stored.length <= Crypto.GCM_NONCE_LENGTH) {
            return null;
        }

        byte [] nonce = Arrays.copyOf(stored, Crypto.GCM_NONCE_LENGTH);
        byte [] encrypted = Arrays.copyOfRange(stored, Crypto.GCM_NONCE_LENGTH,
                                               stored.length);
        byte [] clear = Crypto.decryptAesGcm(key, nonce, encrypted, AAD);
        if (clear == null || clear.length != 1) {
            return null;
        }
        if (clear[0] == ON) {
            return Boolean.TRUE;
        }
        if (clear[0] == OFF) {
            return Boolean.FALSE;
        }
        return null;
    }
}
