package com.kodholken.passdroid;

import com.kodholken.passdroid.db.SystemData;

import android.content.Context;
import android.database.SQLException;

/**
 * The unsaved changes of the add or edit screen, kept as a draft in the
 * system table when the user is logged out, or when the screen stops, e.g.
 * before Android kills the process in the background. The list offers it
 * after the next login.
 *
 * A draft is encrypted with the vault key like an entry, so it can only be
 * read after a login with the same master password. There is at most one.
 */
public final class EntryDraft {
    static final String ATTRIBUTE = "entry_draft";

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";  // Not used by Base64
    private static final int FIELDS = 8;

    /** A draft as it was stored. */
    public static final class Draft {
        // The values of the editor's fields
        public final PasswordEntry values;
        // The id of the entry the draft belongs to, -1 for a new entry
        public final long entryId;
        // When the draft was stored, System.currentTimeMillis()
        public final long savedAt;

        Draft(PasswordEntry values, long entryId, long savedAt) {
            this.values = values;
            this.entryId = entryId;
            this.savedAt = savedAt;
        }
    }

    private EntryDraft() {
    }

    /**
     * Store a draft in place of the one there may be.
     *
     * @param values  the decrypted values of the editor's fields. They are
     *                not changed; an encrypted copy is stored.
     * @param entryId the id of the edited entry, -1 for a new entry.
     * @return false if the draft could not be encrypted or stored.
     */
    public static boolean save(Context context, byte [] key,
                               PasswordEntry values, long entryId) {
        String value = encrypt(key, values, entryId, System.currentTimeMillis());
        if (value == null) {
            Utils.error("The draft could not be encrypted");
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

    public static boolean exists(Context context) {
        try {
            return new SystemData(context).hasAttribute(ATTRIBUTE);
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public static void delete(Context context) {
        try {
            new SystemData(context).removeAttribute(ATTRIBUTE);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * @return the stored draft with its values decrypted, or null if there
     *         is none or it doesn't decrypt with this key, e.g. one stored
     *         with a key that has been replaced since.
     */
    public static Draft load(Context context, byte [] key) {
        String value;
        try {
            value = new SystemData(context).getAttribute(ATTRIBUTE);
        } catch (SQLException ex) {
            ex.printStackTrace();
            return null;
        }
        return value != null ? decrypt(value, key) : null;
    }

    /**
     * @return the stored text of a draft of these values, or null if a
     *         value could not be encrypted.
     */
    static String encrypt(byte [] key, PasswordEntry values, long entryId,
                          long savedAt) {
        if (key == null) {
            return null;
        }

        PasswordEntry copy = new PasswordEntry();
        copy.setDecSystem(values.getDecSystem());
        copy.setDecUsername(values.getDecUsername());
        copy.setDecPassword(values.getDecPassword());
        copy.setDecNote(values.getDecNote());
        copy.setDecUrl(values.getDecUrl());
        copy.setDecEmail(values.getDecEmail());
        copy.setDecTotp(values.getDecTotp());
        copy.setDecDate(values.getDecDate());
        copy.encryptAll(key);
        if (!copy.isEncrypted()) {
            return null;
        }
        return encode(copy, entryId, savedAt);
    }

    /**
     * @return the draft of the stored text with its values decrypted, or
     *         null if the text isn't a draft or a value doesn't decrypt.
     */
    static Draft decrypt(String value, byte [] key) {
        Draft draft = decode(value);
        if (draft == null || key == null) {
            return null;
        }

        PasswordEntry values = draft.values.decryptedCopy(key);
        if (!values.isDecrypted()) {
            return null;
        }
        return new Draft(values, draft.entryId, draft.savedAt);
    }

    /**
     * @param encrypted an entry with its values encrypted.
     * @return "v1|<entry id>|<saved at>|" followed by the encrypted values
     *         in the order of PasswordData.COLUMNS, separated by "|", each
     *         empty if it is null.
     */
    static String encode(PasswordEntry encrypted, long entryId, long savedAt) {
        String [] fields = {
            encrypted.getEncSystem(), encrypted.getEncUsername(),
            encrypted.getEncPassword(), encrypted.getEncNote(),
            encrypted.getEncUrl(), encrypted.getEncEmail(),
            encrypted.getEncTotp(), encrypted.getEncDate()
        };

        StringBuilder result = new StringBuilder();
        result.append(VERSION).append(SEPARATOR)
              .append(entryId).append(SEPARATOR)
              .append(savedAt);
        for (String field : fields) {
            result.append(SEPARATOR).append(field != null ? field : "");
        }
        return result.toString();
    }

    /**
     * @return the draft of encode()'s text, with its values still
     *         encrypted, or null if the text isn't one.
     */
    static Draft decode(String value) {
        String [] parts = value.split("\\" + SEPARATOR, -1);
        if (parts.length != 3 + FIELDS || !parts[0].equals(VERSION)) {
            return null;
        }

        long entryId;
        long savedAt;
        try {
            entryId = Long.parseLong(parts[1]);
            savedAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException ex) {
            return null;
        }

        PasswordEntry entry = new PasswordEntry();
        entry.setEncSystem(orNull(parts[3]));
        entry.setEncUsername(orNull(parts[4]));
        entry.setEncPassword(orNull(parts[5]));
        entry.setEncNote(orNull(parts[6]));
        entry.setEncUrl(orNull(parts[7]));
        entry.setEncEmail(orNull(parts[8]));
        entry.setEncTotp(orNull(parts[9]));
        entry.setEncDate(orNull(parts[10]));
        return new Draft(entry, entryId, savedAt);
    }

    /**
     * @return true if two sets of an editor's values differ in any field.
     */
    public static boolean differ(PasswordEntry a, PasswordEntry b) {
        return !same(a.getDecSystem(), b.getDecSystem()) ||
               !same(a.getDecUsername(), b.getDecUsername()) ||
               !same(a.getDecPassword(), b.getDecPassword()) ||
               !same(a.getDecNote(), b.getDecNote()) ||
               !same(a.getDecUrl(), b.getDecUrl()) ||
               !same(a.getDecEmail(), b.getDecEmail()) ||
               !same(a.getDecTotp(), b.getDecTotp()) ||
               !same(a.getDecDate(), b.getDecDate());
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String orNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
