package com.kodholken.passdroid;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The numbers of the statistics screen, counted from entries with all
 * fields decrypted, see PasswordModel.getDecryptedPasswords(). Only the
 * numbers are kept: no reference to the entries or to their values.
 *
 * A field counts when it has a value that isn't empty or only spaces. A
 * field that couldn't be decrypted is null in the copy, so it doesn't count
 * either.
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
     * @param decrypted entries with all fields decrypted, e.g. from
     *                  PasswordModel.getDecryptedPasswords().
     */
    public static Statistics count(PasswordEntry [] decrypted) {
        int withEmail = 0;
        int withUsername = 0;
        int withPassword = 0;
        int withTotp = 0;
        int withNote = 0;
        int withUrl = 0;
        int withDate = 0;

        // Dropped when this returns, with the values in them
        Set<String> emails = new HashSet<String>();
        Set<String> usernames = new HashSet<String>();
        Map<String, Integer> passwordUses = new HashMap<String, Integer>();

        for (PasswordEntry entry : decrypted) {
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

            String password = entry.getDecPassword();
            if (hasValue(password)) {
                withPassword++;
                Integer uses = passwordUses.get(password);
                passwordUses.put(password, uses == null ? 1 : uses + 1);
            }

            if (hasValue(entry.getDecTotp())) {
                withTotp++;
            }
            if (hasValue(entry.getDecNote())) {
                withNote++;
            }
            if (hasValue(entry.getDecUrl())) {
                withUrl++;
            }
            if (hasValue(entry.getDecDate())) {
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

        return new Statistics(decrypted.length, withEmail, emails.size(),
                              withUsername, usernames.size(), withPassword,
                              reusedPasswords, entriesWithReusedPassword,
                              withTotp, withNote, withUrl, withDate);
    }

    private static boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
