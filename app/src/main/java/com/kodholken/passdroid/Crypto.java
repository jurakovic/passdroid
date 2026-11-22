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
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Crypto {
    // PKCS5Padding is the PKCS #7 padding for AES, see PasswordEntry
    private static final String CBC = "AES/CBC/PKCS5Padding";

    /**
     * Calculate SHA256 hash from a password string.
     * 
     * @param password password to calculate hash from.
     * @return byte array representing the hash or null if there was an error.
     */
    public static byte [] hmacFromPassword(String password) {
        byte [] key = null;

        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init (new SecretKeySpec ("notverysecretiv".getBytes ("UTF-8"),
                                          "RAW"));
            hmac.update(password.getBytes("UTF-8"));
            key = hmac.doFinal();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return key;
    }

    /**
     * Verify password against CRC value after transformation.
     * 
     * @param password password to verify.
     * @param dbString string to use as verfication after transformation.
     * 
     * @return true if the password is verified. False if not.
     */
    public static boolean verifyPassword(String password, String dbString) {
        byte [] xor;

        try {
            xor = Base64.decode(dbString);
        } catch (Base64Exception ex) {
            ex.printStackTrace();
            return false;
        }

        byte [] hmac = hmacFromPassword(password);
        byte [] key = new byte[xor.length];

        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (xor[i] ^ hmac[i]);
        }

        CRC32 crc = new CRC32();
        crc.update(key, 0, 28);
        long crcValue = crc.getValue();

        return ((key[28] == (byte) ((crcValue >> 24) & 0xff)) &&
                (key[29] == (byte) ((crcValue >> 16) & 0xff)) &&
                (key[30] == (byte) ((crcValue >> 8) & 0xff)) &&
                (key[31] == (byte) (crcValue & 0xff)));
    }

    /**
     * Encrypt a string using AES in CBC mode. The result is PKCS7 padded.
     * 
     * @param key    AES key to use. 16, 24 or 32 bytes for 128, 192 or 256 bit
     *               encryption.
     * @param clear Clear text string to encrypt.
     * @return      PKCS7 padded byte array with the encrypted data. null on 
     *              error.
     */
    public static byte[] encryptAesCbc(byte[] key, String clear) {
        byte[] encrypted;
        byte[] salt = new byte[2];

        SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
        try {
            SecureRandom rnd = new SecureRandom();
            Cipher cipher = Cipher.getInstance(CBC);
            byte[] iv = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, ivSpec);
            rnd.nextBytes(salt);
            cipher.update(salt);
            encrypted = cipher.doFinal(clear.getBytes());
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return encrypted;
    }
    
    /**
     * Decrypt PKCS7 padded data using AES in CBC mode.
     * 
     * @param key       AES key.
     * @param encrypted encrypted data as byte array.
     * @return Decrypted data as a byte array or null if there was an error.
     */
    public static byte[] decryptAesCbc(byte[] key, byte[] encrypted) {
        byte[] decBytes;

        try {
            SecretKeySpec skeySpec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance(CBC);
            byte[] iv = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec);
            decBytes = cipher.doFinal(encrypted);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }

        return decBytes;
    }

    /**
     * Decrypt PKCS7 padded data using AES in CBC mode.
     * 
     * @param key       AES key.
     * @param encrypted encrypted data as string.
     * @return Decrypted data as a byte array or null if there was an error.
     */
    public static byte[] decryptAesCbc(byte[] key, String encrypted) {
        try {
            return decryptAesCbc(key, Base64.decode(encrypted));
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return null;
    }

    /**
     * Length in bytes of the nonce used by encryptAesGcm().
     */
    public static final int GCM_NONCE_LENGTH = 12;

    private static final int GCM_TAG_BITS = 128;

    /**
     * Encrypt and authenticate data using AES in GCM mode.
     *
     * @param key   AES key.
     * @param nonce GCM_NONCE_LENGTH random bytes. A nonce must never be used
     *              twice with the same key.
     * @param clear data to encrypt.
     * @param aad   additional data that is authenticated but not encrypted.
     * @return the encrypted data followed by the authentication tag. null on
     *         error.
     */
    public static byte[] encryptAesGcm(byte[] key, byte[] nonce, byte[] clear,
                                       byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(clear);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Decrypt data encrypted with encryptAesGcm().
     *
     * @return the decrypted data or null if the key is wrong or the data has
     *         been modified.
     */
    public static byte[] decryptAesGcm(byte[] key, byte[] nonce,
                                       byte[] encrypted, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(encrypted);
        } catch (Exception ex) {
            return null;
        }
    }
}
