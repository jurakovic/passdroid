package com.kodholken.passdroid;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The numbers of the statistics screen, counted from the entries as the
 * list has them, see PasswordModel.getPasswords(). Only the numbers are
 * kept: no reference to the entries or to their values.
 *
 * The email and username are the decrypted list fields. The password, 2FA
 * secret, note, URL and date are checked with PasswordEntry.hasTotp() etc.
 * and passwordMac(), which decrypt into byte arrays and wipe them, so none
 * of them becomes a string. Passwords are compared by their MACs, with a
 * random key made for each count, and the MACs are wiped once counted.
 *
 * A field counts when it has a value that isn't empty or only spaces. A
 * field that can't be decrypted doesn't count either.
 */
public final class Statistics {
    /** All entries. */
    public final int entries;
    /** Entries with an email, and the different emails, ignoring case and
     *  the spaces around them. */
    public final int withEmail;
    public final int differentEmails;
    /** Entries with a username, and the different usernames, ignoring only
     *  the spaces around them. */
    public final int withUsername;
    public final int differentUsernames;
    /** Entries with a password. */
    public final int withPassword;
    /** The passwords that more than one entry uses, compared exactly, and the
     *  entries that use them. */
    public final int reusedPasswords;
    public final int entriesWithReusedPassword;
    /** Entries with a 2FA secret, a note, a URL and a date. */
    public final int withTotp;
    public final int withNote;
    public final int withUrl;
    public final int withDate;

    private Statistics(int entries, int withEmail, int differentEmails,
                       int withUsername, int differentUsernames,
                       int withPassword, int reusedPasswords,
                       int entriesWithReusedPassword, int withTotp,
                       int withNote, int withUrl, int withDate) {
        this.entries = entries;
        this.withEmail = withEmail;
        this.differentEmails = differentEmails;
        this.withUsername = withUsername;
        this.differentUsernames = differentUsernames;
        this.withPassword = withPassword;
        this.reusedPasswords = reusedPasswords;
        this.entriesWithReusedPassword = entriesWithReusedPassword;
        this.withTotp = withTotp;
        this.withNote = withNote;
        this.withUrl = withUrl;
        this.withDate = withDate;
    }

    /**
     * @param entries entries with their list fields decrypted, as from
     *                PasswordModel.getPasswords().
     * @param key     the key the entries are encrypted with.
     */
    public static Statistics count(PasswordEntry [] entries, byte [] key) {
        Mac mac = newMac();
        int withEmail = 0;
        int withUsername = 0;
        int withPassword = 0;
        int withTotp = 0;
        int withNote = 0;
        int withUrl = 0;
        int withDate = 0;

        // Dropped when this returns, with what is in them
        Set<String> emails = new HashSet<String>();
        Set<String> usernames = new HashSet<String>();
        // By the MACs of the passwords; a ByteBuffer compares its content
        Map<ByteBuffer, Integer> passwordUses = new HashMap<ByteBuffer, Integer>();

        for (PasswordEntry entry : entries) {
            String email = entry.getDecEmail();
            if (hasValue(email)) {
                withEmail++;
                // Without a locale, so that e.g. a Turkish one doesn't
                // tell "I" and "i" apart differently
                emails.add(email.trim().toLowerCase(Locale.ROOT));
            }

            String username = entry.getDecUsername();
            if (hasValue(username)) {
                withUsername++;
                usernames.add(username.trim());
            }

            byte [] passwordMac = entry.passwordMac(key, mac);
            if (passwordMac != null) {
                withPassword++;
                ByteBuffer password = ByteBuffer.wrap(passwordMac);
                Integer uses = passwordUses.get(password);
                passwordUses.put(password, uses == null ? 1 : uses + 1);
                if (uses != null) {
                    // The map keeps the equal MAC it has
                    Arrays.fill(passwordMac, (byte) 0);
                }
            }

            if (entry.hasTotp(key)) {
                withTotp++;
            }
            if (entry.hasNote(key)) {
                withNote++;
            }
            if (entry.hasUrl(key)) {
                withUrl++;
            }
            if (entry.hasDate(key)) {
                withDate++;
            }
        }

        int reusedPasswords = 0;
        int entriesWithReusedPassword = 0;
        for (int uses : passwordUses.values()) {
            if (uses > 1) {
                reusedPasswords++;
                entriesWithReusedPassword += uses;
            }
        }
        // Until they are collected, the MACs would let a copy of the
        // memory test guessed passwords against them. The map isn't used
        // after this, so its keys may change.
        for (ByteBuffer password : passwordUses.keySet()) {
            Arrays.fill(password.array(), (byte) 0);
        }

        return new Statistics(entries.length, withEmail, emails.size(),
                              withUsername, usernames.size(), withPassword,
                              reusedPasswords, entriesWithReusedPassword,
                              withTotp, withNote, withUrl, withDate);
    }

    /**
     * @return an HMAC-SHA256 with a random key, so that its MACs of the
     *         passwords can only be compared with each other, and can't be
     *         computed for a guessed password without the key.
     */
    private static Mac newMac() {
        byte [] macKey = new byte[32];
        new SecureRandom().nextBytes(macKey);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            // Every Java and Android version has it
            throw new IllegalStateException("HmacSHA256 is not available", e);
        } finally {
            Arrays.fill(macKey, (byte) 0);
        }
    }

    /** For the email and username, which the list has decrypted. */
    private static boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
