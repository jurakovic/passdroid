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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PasswordEntry {
    // PKCS5Padding is the PKCS #7 padding for AES, the same as PKCS7Padding
    // of the Bouncy Castle provider that earlier versions asked for. Android
    // has deprecated asking for that provider.
    private static final String CBC = "AES/CBC/PKCS5Padding";

    /**
     * The order of the list of entries: those whose system didn't decrypt
     * first, so they are seen, then by system and by username, ignoring
     * case, with no username as an empty one. The exact system and username,
     * then the id, break ties, so the order is total.
     *
     * Case is ignored with String.CASE_INSENSITIVE_ORDER, which compares
     * character by character without a locale. equalsIgnoreCase() and
     * toLowerCase() in the default locale disagree in a Turkish locale,
     * where "I" lower-cases to a dotless i: the order was then inconsistent,
     * which Arrays.sort() may refuse with an exception.
     */
    public static final Comparator<PasswordEntry> LIST_ORDER = new Comparator<PasswordEntry>() {
        @Override
        public int compare(PasswordEntry a, PasswordEntry b) {
            String systemA = a.getDecSystem();
            String systemB = b.getDecSystem();
            if (systemA == null || systemB == null) {
                if (systemA != null || systemB != null) {
                    return systemA == null ? -1 : 1;
                }
                // Both null: by username and id
                systemA = systemB = "";
            }

            String userA = a.getDecUsername() != null ? a.getDecUsername() : "";
            String userB = b.getDecUsername() != null ? b.getDecUsername() : "";

            int result = String.CASE_INSENSITIVE_ORDER.compare(systemA, systemB);
            if (result == 0) {
                result = String.CASE_INSENSITIVE_ORDER.compare(userA, userB);
            }
            if (result == 0) {
                result = systemA.compareTo(systemB);
            }
            if (result == 0) {
                result = userA.compareTo(userB);
            }
            if (result == 0) {
                result = Long.compare(a.getId(), b.getId());
            }
            return result;
        }
    };

    private long id;

    private String encSystem;
    private String encUsername;
    private String encPassword;
    private String encNote;
    private String encUrl;
    private String encEmail;
    private String encTotp;
    private String encDate;

    private String decSystem;
    private String decUsername;
    private String decPassword;
    private String decNote;
    private String decUrl;
    private String decEmail;
    private String decTotp;   // 2FA secret, see Totp
    private String decDate;   // See DateField

    public void decryptAll(byte [] key) {
        decSystem   = decrypt(key, encSystem);
        decUsername = decrypt(key, encUsername);
        decPassword = decrypt(key, encPassword);
        if (encNote != null) {
            decNote = decrypt(key, encNote);
        } else {
            decNote = null;
        }
        
        if (encUrl != null) {
            decUrl = decrypt(key, encUrl);
        } else {
            decUrl = null;
        }

        // Entries added before these fields existed have no value for them
        decEmail = encEmail != null ? decrypt(key, encEmail) : null;
        decTotp  = encTotp  != null ? decrypt(key, encTotp)  : null;
        decDate  = encDate  != null ? decrypt(key, encDate)  : null;
    }

    /**
     * Decrypt only the fields that the list of entries shows and searches:
     * the system, the username and the email. The others are decrypted when
     * they are needed, with decryptPassword() etc., and are not kept.
     */
    public void decryptListFields(byte [] key) {
        decSystem   = decrypt(key, encSystem);
        decUsername = decrypt(key, encUsername);
        decEmail    = encEmail != null ? decrypt(key, encEmail) : null;
    }

    public String decryptPassword(byte [] key) {
        return decrypt(key, encPassword);
    }

    public String decryptTotp(byte [] key) {
        return encTotp != null ? decrypt(key, encTotp) : null;
    }

    public String decryptNote(byte [] key) {
        return encNote != null ? decrypt(key, encNote) : null;
    }

    public String decryptUrl(byte [] key) {
        return encUrl != null ? decrypt(key, encUrl) : null;
    }

    public String decryptDate(byte [] key) {
        return encDate != null ? decrypt(key, encDate) : null;
    }

    /**
     * @return a copy of the entry with all fields decrypted, e.g. to edit or
     *         export it.
     */
    public PasswordEntry decryptedCopy(byte [] key) {
        PasswordEntry copy = new PasswordEntry();
        copy.id          = id;
        copy.encSystem   = encSystem;
        copy.encUsername = encUsername;
        copy.encPassword = encPassword;
        copy.encNote     = encNote;
        copy.encUrl      = encUrl;
        copy.encEmail    = encEmail;
        copy.encTotp     = encTotp;
        copy.encDate     = encDate;
        copy.decryptAll(key);
        return copy;
    }

    public void encryptAll(byte [] key) {
        encSystem   = encrypt(key, decSystem);
        encUsername = encrypt(key, decUsername);
        encPassword = encrypt(key, decPassword);
        
        if (decNote != null) {
            encNote = encrypt(key, decNote);
        } else {
            encNote = null;
        }
        
        if (decUrl != null) {
            encUrl      = encrypt(key, decUrl);
        } else {
            encUrl = null;
        }

        encEmail = decEmail != null ? encrypt(key, decEmail) : null;
        encTotp  = decTotp  != null ? encrypt(key, decTotp)  : null;
        encDate  = decDate  != null ? encrypt(key, decDate)  : null;
    }

    /*
     * Since this method should only be used when converting really old
     * databases that does not have any note or URL columns we don't bother
     * converting these values.
     */
    public void convertEcbToCbc(byte [] key) {
        encSystem   = ecbToCbc(key, encSystem);
        encUsername = ecbToCbc(key, encUsername);
        encPassword = ecbToCbc(key, encPassword);
    }
    
    /*
     * This method is used to convert entries with zeroed out IVs and a salt
     * to entries with random IVs and no salt. The conversion takes place in
     * the same version where notes and URLs were introduced, hence we do not
     * need to convert the note and URL fields.
     */
    public void convertZeroIvToIv(byte [] key) {
        encSystem   = zeroIvToIv(key, encSystem);
        encUsername = zeroIvToIv(key, encUsername);
        encPassword = zeroIvToIv(key, encPassword);
    }

    private String ecbToCbc(byte [] key, String encrypted) {
        String decrypted = decryptEcb(key, encrypted);
        return encrypt(key, decrypted);
    }
    
    public String zeroIvToIv(byte [] key, String encrypted) {
        String decrypted = decryptZeroIv(key, encrypted);
        return encrypt(key, decrypted);
    }

    private String encrypt(byte [] key, String clear) {
        byte [] encrypted;
        byte [] complete;

        SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
        try {
            SecureRandom rnd = new SecureRandom();
            Cipher cipher = Cipher.getInstance(CBC);

            byte [] iv = new byte[16];
            rnd.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, ivSpec);
            encrypted = cipher.doFinal(clear.getBytes());
            
            complete = new byte[encrypted.length + iv.length];
            System.arraycopy(iv, 0, complete, 0, iv.length);
            System.arraycopy(encrypted, 0, complete, iv.length, encrypted.length);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return Base64.encode(complete);
    }

    /**
     * @return the value, or null if it can't be decrypted, e.g. because the
     *         stored value is damaged: the fields have no authentication, so
     *         a damaged byte usually fails the padding check.
     */
    private String decrypt(byte [] key, String encrypted) {
        byte [] decBytes = decryptBytes(key, encrypted);
        if (decBytes == null) {
            return null;
        }

        String decrypted = new String(decBytes, StandardCharsets.UTF_8);
        Arrays.fill(decBytes, (byte) 0);
        return decrypted;
    }

    /**
     * @return the decrypted bytes of a value, or null if it can't be
     *         decrypted. The caller wipes them.
     */
    private static byte [] decryptBytes(byte [] key, String encrypted) {
        try {
            byte [] encBytes = Base64.decode(encrypted);

            SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance(CBC);

            byte [] iv = new byte[16];
            System.arraycopy(encBytes, 0, iv, 0, iv.length);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);
            return cipher.doFinal(encBytes, iv.length, encBytes.length - iv.length);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Check that every field the entry has can be decrypted, without
     * creating a string of any value: the values are decrypted into byte
     * arrays, which are wiped. An entry where one doesn't is damaged.
     *
     * @return false if a stored field can't be decrypted.
     */
    public boolean canDecrypt(byte [] key) {
        String [] fields = {
            encSystem, encUsername, encPassword, encNote, encUrl, encEmail,
            encTotp, encDate
        };
        for (String encrypted : fields) {
            if (encrypted == null) {
                continue;
            }

            byte [] decrypted = decryptBytes(key, encrypted);
            if (decrypted == null) {
                return false;
            }
            Arrays.fill(decrypted, (byte) 0);
        }
        return true;
    }

    /**
     * @return for an entry whose fields were decrypted with decryptAll(),
     *         e.g. a decryptedCopy(), false if any stored field failed to
     *         decrypt.
     */
    public boolean isDecrypted() {
        return isDecrypted(encSystem, decSystem) &&
               isDecrypted(encUsername, decUsername) &&
               isDecrypted(encPassword, decPassword) &&
               isDecrypted(encNote, decNote) &&
               isDecrypted(encUrl, decUrl) &&
               isDecrypted(encEmail, decEmail) &&
               isDecrypted(encTotp, decTotp) &&
               isDecrypted(encDate, decDate);
    }

    /**
     * @return for an entry whose list fields were decrypted with
     *         decryptListFields(), false if one of them failed to decrypt.
     */
    public boolean listFieldsDecrypted() {
        return isDecrypted(encSystem, decSystem) &&
               isDecrypted(encUsername, decUsername) &&
               isDecrypted(encEmail, decEmail);
    }

    private static boolean isDecrypted(String encrypted, String decrypted) {
        return encrypted == null || decrypted != null;
    }

    private String decryptZeroIv(byte [] key, String encrypted) {
        String decrypted;
        byte [] decBytes;

        try {
            byte [] encBytes = Base64.decode(encrypted);

            SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");  

            Cipher cipher = Cipher.getInstance(CBC);
            byte [] iv = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);
            decBytes = cipher.doFinal(encBytes);
            decrypted = new String(decBytes, 2, decBytes.length - 2, "UTF8");
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return decrypted;
    }

    private String decryptEcb(byte [] key, String encrypted) {
        String decrypted;
        byte [] decBytes;

        try {
            byte [] encBytes = Base64.decode(encrypted);

            SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");  
            //Cipher cipher = Cipher.getInstance("AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec);  
            decBytes = cipher.doFinal(encBytes);
            decrypted = new String(decBytes, 2, decBytes.length - 2, "UTF8");
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return decrypted;
    }

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }
    public String getEncSystem() {
        return encSystem;
    }
    public void setEncSystem(String encSystem) {
        this.encSystem = encSystem;
    }
    public String getEncUsername() {
        return encUsername;
    }
    public void setEncUsername(String encUsername) {
        this.encUsername = encUsername;
    }
    public String getEncPassword() {
        return encPassword;
    }
    public void setEncPassword(String encPassword) {
        this.encPassword = encPassword;
    }
    public String getDecSystem() {
        return decSystem;
    }
    public void setDecSystem(String decSystem) {
        this.decSystem = decSystem;
    }
    public String getDecUsername() {
        return decUsername;
    }
    public void setDecUsername(String decUsername) {
        this.decUsername = decUsername;
    }
    public String getDecPassword() {
        return decPassword;
    }
    public void setDecPassword(String decPassword) {
        this.decPassword = decPassword;
    }
    public String getEncNote() {
        return encNote;
    }
    public void setEncNote(String encNote) {
        this.encNote = encNote;
    }
    public String getEncUrl() {
        return encUrl;
    }
    public void setEncUrl(String encUrl) {
        this.encUrl = encUrl;
    }
    public String getDecNote() {
        return decNote;
    }
    public void setDecNote(String decNote) {
        this.decNote = decNote;
    }
    public String getDecUrl() {
        return decUrl;
    }
    public void setDecUrl(String decUrl) {
        this.decUrl = decUrl;
    }
    public String getEncEmail() {
        return encEmail;
    }
    public void setEncEmail(String encEmail) {
        this.encEmail = encEmail;
    }
    public String getEncTotp() {
        return encTotp;
    }
    public void setEncTotp(String encTotp) {
        this.encTotp = encTotp;
    }
    public String getEncDate() {
        return encDate;
    }
    public void setEncDate(String encDate) {
        this.encDate = encDate;
    }
    public String getDecEmail() {
        return decEmail;
    }
    public void setDecEmail(String decEmail) {
        this.decEmail = decEmail;
    }
    public String getDecTotp() {
        return decTotp;
    }
    public void setDecTotp(String decTotp) {
        this.decTotp = decTotp;
    }
    public String getDecDate() {
        return decDate;
    }
    public void setDecDate(String decDate) {
        this.decDate = decDate;
    }
}
