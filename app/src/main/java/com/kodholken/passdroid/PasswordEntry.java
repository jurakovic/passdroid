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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PasswordEntry implements Comparable<PasswordEntry> {
    // PKCS5Padding is the PKCS #7 padding for AES, the same as PKCS7Padding
    // of the Bouncy Castle provider that earlier versions asked for. Android
    // has deprecated asking for that provider.
    private static final String CBC = "AES/CBC/PKCS5Padding";

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

    private String decrypt(byte [] key, String encrypted) {
        String decrypted;
        byte [] decBytes;

        try {
            byte [] encBytes = Base64.decode(encrypted);

            SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");  
            Cipher cipher = Cipher.getInstance(CBC);
            
            byte [] iv = new byte[16];
            System.arraycopy(encBytes, 0, iv, 0, iv.length);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);
            decBytes = cipher.doFinal(encBytes, iv.length, encBytes.length - iv.length);
            decrypted = new String(decBytes, 0, decBytes.length, "UTF8");
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return decrypted;
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

    @Override
    public int compareTo(PasswordEntry another) {
        if (getDecSystem().equalsIgnoreCase(another.getDecSystem())) {
            return this.getDecUsername().compareTo(another.getDecUsername());
        }

        return getDecSystem().toLowerCase().compareTo(another.getDecSystem().toLowerCase());
    }
}
