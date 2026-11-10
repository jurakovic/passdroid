/*    
    This file is part of the Passdroid password management software.
    
    Copyright (C) 2009-2012  Magnus Eriksson <eriksson.mag@gmail.com>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.kodholken.passdroid;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import android.content.Context;
import android.preference.PreferenceManager;

/**
 * Random passwords, for the password generator (GeneratePasswordActivity) and
 * the generate button of the add and edit screens. The generator stores the
 * length and the characters last chosen, and the button uses them.
 */
public class PasswordGenerator {
    public static final int MAX_PASSWORD_LENGTH = 99;
    public static final int DEFAULT_PASSWORD_LENGTH = 16;

    private static final String LENGTH_PREFERENCE = "passwordLength";
    private static final String CHARSET_PREFERENCE = "passwordCharset";

    private static final String NUMERIC = "0123456789";
    private static final String ALPHABETIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALPHANUMERIC = ALPHABETIC + NUMERIC;
    private static final String EXTENDED = ALPHANUMERIC + "!#@%&/()?+-_:;*=$";

    /**
     * @param index the position in the generator's list of character sets,
     *              R.array.charsets.
     * @return the characters of the set, letters and digits for an unknown
     *         index.
     */
    public static String getCharset(int index) {
        switch (index) {
        case 1:
            return ALPHABETIC;
        case 2:
            return NUMERIC;
        case 3:
            return EXTENDED;
        default:
            return ALPHANUMERIC;
        }
    }

    /**
     * @return a password of the given length, each character chosen at
     *         random from the set with the given index, see getCharset().
     */
    public static String generate(int length, int charsetIndex) {
        Random random = new SecureRandom();
        String charset = getCharset(charsetIndex);

        // One string, not one for each added character
        char [] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = charset.charAt(random.nextInt(charset.length()));
        }
        String password = new String(chars);
        Arrays.fill(chars, '\0');
        return password;
    }

    /**
     * @return a password with the length and characters last chosen in the
     *         generator, or the defaults.
     */
    public static String generateWithStoredSettings(Context context) {
        return generate(getStoredLength(context), getStoredCharset(context));
    }

    public static int getStoredLength(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).
            getInt(LENGTH_PREFERENCE, DEFAULT_PASSWORD_LENGTH);
    }

    public static int getStoredCharset(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).
            getInt(CHARSET_PREFERENCE, 0);
    }

    public static void store(Context context, int length, int charset) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().
            putInt(LENGTH_PREFERENCE, length).
            putInt(CHARSET_PREFERENCE, charset).commit();
    }
}
