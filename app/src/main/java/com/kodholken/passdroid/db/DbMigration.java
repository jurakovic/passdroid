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

package com.kodholken.passdroid.db;

import com.kodholken.passdroid.BiometricUnlock;
import com.kodholken.passdroid.MasterKey;
import com.kodholken.passdroid.PasswordEntry;
import com.kodholken.passdroid.Session;
import com.kodholken.passdroid.Utils;
import com.kodholken.passdroid.Version;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

public class DbMigration {
    /**
     * The version of the data in the database, how the entries are encrypted.
     * Increase it, and add the conversion to postLoginMigration(), when that
     * changes.
     *
     *   1  Passdroid 0.9: AES-ECB
     *   2  Passdroid 0.95 to 1.x: AES-CBC with a zero IV
     *   3  Passdroid 2.0 and later: AES-CBC with a random IV
     *
     * Versions before Passdroid++ 3.0 stored the version of the app instead,
     * and converted the entries when it crossed one of these versions. The
     * version name of the app is not used for anything but display anymore.
     */
    public static final int DATA_VERSION = 3;

    /**
     * Store the data version if the database has none: in a new database, or
     * in place of the app version that earlier versions stored. This does not
     * need the key, so it is done before the login.
     */
    public static void preLoginMigration(SystemData system) {
        if (system.hasDataVersion()) {
            return;
        }

        String appVersion = system.getLegacyVersion();
        system.setDataVersion(appVersion == null ? DATA_VERSION :
                              getLegacyDataVersion(appVersion));
    }

    /**
     * Convert the entries to the current data version. This needs the key, so
     * it is done after the login. If it fails, it is tried again at the next
     * login.
     */
    public static void postLoginMigration(Context context) {
        SystemData system = new SystemData(context);
        int dataVersion = system.getDataVersion();

        boolean result;
        if (dataVersion == 1) {
            // The entries are encrypted again with a random IV
            Utils.debug("Data version 1 to " + DATA_VERSION + " conversion: ECB -> CBC");
            result = ecb2cbcConversion(context);
        } else if (dataVersion == 2) {
            Utils.debug("Data version 2 to " + DATA_VERSION + " conversion: zero IV -> IV");
            result = zeroIvToIvConversion(context);
        } else {
            return;
        }

        if (result) {
            Utils.debug("Database converted successfully");
            system.setDataVersion(DATA_VERSION);
        } else {
            Utils.error("Database conversion failed");
        }
    }

    /**
     * @param appVersion version of the app that last used the database, as
     *                   stored by the versions before Passdroid++ 3.0.
     * @return the data version of the database.
     */
    static int getLegacyDataVersion(String appVersion) {
        Version version;
        try {
            version = Version.parse(appVersion);
        } catch (NumberFormatException ex) {
            // Only the versions before 2.0 stored the entries differently.
            // Converting entries that are already in the current format
            // would destroy them.
            Utils.error("Invalid app version in the database: " + appVersion);
            return DATA_VERSION;
        }

        if (version.compareTo(new Version(0, 9)) <= 0) {
            return 1;
        }
        if (version.compareTo(new Version(2, 0)) < 0) {
            return 2;
        }
        return 3;
    }

    /*
     * Since we have two SQLiteOpenHelper derived classes (PasswordData and
     * SystemData) that work on the same database table (Constants.DBNAME) all
     * update processing needs to be done in a common function since we do not 
     * know which class will trigger the update callback. I.e. the onUpgrade()
     * implementations should call this, and only this, method.
     */
    public static void handleDatabaseUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Utils.debug("handleDatabaseUpgrade()");
        if (oldVersion == 1 && newVersion == 2) {
            Utils.debug("Adding table comlums 'note' and 'url' due to version " +
                        "upgrade from 1 to 2");
            db.execSQL("ALTER TABLE data ADD COLUMN note TEXT");
            db.execSQL("ALTER TABLE data ADD COLUMN url TEXT");
        }
    }

    private static boolean zeroIvToIvConversion(Context context) {
        boolean result = false;

        final String [] columns = { "id", "system", "username", "password" };
        PasswordData passwordData = new PasswordData(context);
        SQLiteDatabase db = passwordData.getReadableDatabase();
        try {
            db.beginTransaction();
            Cursor cur = db.query("data", columns, null, null, null, null,
                                  "id DESC");

            PasswordEntry password;
            ContentValues values = new ContentValues();
            while (cur.moveToNext()) {
                password = new PasswordEntry();
                password.setId(cur.getInt(0));
                password.setEncSystem(cur.getString(1));
                password.setEncUsername(cur.getString(2));
                password.setEncPassword(cur.getString(3));

                password.convertZeroIvToIv(Session.getInstance().getKey());

                values.put("system", password.getEncSystem());
                values.put("username", password.getEncUsername());
                values.put("password", password.getEncPassword());

                db.update("data", values, "id=" + password.getId(), null);
            }
            cur.close();
            db.setTransactionSuccessful();
            result = true;
        } catch (SQLiteException ex) {
            ex.printStackTrace();
        } finally {
            db.endTransaction();
            db.close();
        }

        return result;
    }

    public static boolean ecb2cbcConversion(Context context) {
        boolean result = false;

        final String [] columns = { "id", "system", "username", "password" };
        PasswordData passwordData = new PasswordData(context);
        SQLiteDatabase db = passwordData.getReadableDatabase();
        try {
            db.beginTransaction();
            Cursor cur = db.query("data", columns, null, null, null, null,
            "id DESC");

            PasswordEntry password;
            ContentValues values = new ContentValues();
            while (cur.moveToNext()) {
                password = new PasswordEntry();
                password.setId(cur.getInt(0));
                password.setEncSystem(cur.getString(1));
                password.setEncUsername(cur.getString(2));
                password.setEncPassword(cur.getString(3));

                password.convertEcbToCbc(Session.getInstance().getKey());

                values.put("system", password.getEncSystem());
                values.put("username", password.getEncUsername());
                values.put("password", password.getEncPassword());

                db.update("data", values, "id=" + password.getId(), null);
            }
            cur.close();
            db.setTransactionSuccessful();
            result = true;
        } catch (SQLiteException ex) {
            ex.printStackTrace();
        } finally {
            db.endTransaction();
            db.close();
        }

        return result;
    }

    /**
     * Re-encrypt all password entries with a new key and store the new master
     * key record. This is used both when the master password is changed and
     * when a database using the legacy key derivation is upgraded. Nothing is
     * changed unless all entries could be decrypted with the old key.
     *
     * @param oldKey    key the entries are currently encrypted with.
     * @param masterKey new key and the record to store in the database.
     * @return true on success.
     */
    public static boolean changeKey(Context context, byte [] oldKey, MasterKey masterKey) {
        SQLiteDatabase db = null;
        boolean result = false;

        byte [] newKey = masterKey.getKey();

        try {
            final String [] columns = { "id", "system", "username", "password", "note", "url" };
            PasswordData passwordData = new PasswordData(context);

            db = passwordData.getReadableDatabase();
            db.beginTransaction();
            Cursor cur = db.query("data", columns, null, null, null, null, "id DESC");

            int i = 0;
            PasswordEntry [] passwords = new PasswordEntry[cur.getCount()];
            while (cur.moveToNext()) {
                passwords[i] = new PasswordEntry();
                passwords[i].setId(cur.getInt(0));
                passwords[i].setEncSystem(cur.getString(1));
                passwords[i].setEncUsername(cur.getString(2));
                passwords[i].setEncPassword(cur.getString(3));
                passwords[i].setEncNote(cur.getString(4));
                passwords[i].setEncUrl(cur.getString(5));

                passwords[i].decryptAll(oldKey);
                if (!isDecrypted(passwords[i])) {
                    Utils.error("Failed to decrypt entry " + passwords[i].getId());
                    cur.close();
                    return false;
                }

                i++;
            }
            cur.close();

            ContentValues values = new ContentValues();
            for (i = 0; i < passwords.length; i++) {
                passwords[i].encryptAll(newKey);
                values.put("system", passwords[i].getEncSystem());
                values.put("username", passwords[i].getEncUsername());
                values.put("password", passwords[i].getEncPassword());
                values.put("note", passwords[i].getEncNote());
                values.put("url", passwords[i].getEncUrl());
                db.update("data", values, "id=" + passwords[i].getId(), null);
            }

            values.clear();
            values.put("value", masterKey.getRecord());
            String [] attrs = { "key" };
            db.update("system", values, "attribute=?", attrs);

            // The key stored for biometric unlock is the old key
            String [] biometricAttrs = { BiometricUnlock.DB_ATTRIBUTE };
            db.delete("system", "attribute=?", biometricAttrs);

            db.setTransactionSuccessful();
            result = true;
        } catch (SQLiteException ex) {
            ex.printStackTrace();
        } finally {
            if (db != null) {
                db.endTransaction();
                db.close();
            }
        }

        return result;
    }

    /**
     * @return false if any field of the entry failed to decrypt.
     */
    private static boolean isDecrypted(PasswordEntry entry) {
        return isDecrypted(entry.getEncSystem(), entry.getDecSystem()) &&
               isDecrypted(entry.getEncUsername(), entry.getDecUsername()) &&
               isDecrypted(entry.getEncPassword(), entry.getDecPassword()) &&
               isDecrypted(entry.getEncNote(), entry.getDecNote()) &&
               isDecrypted(entry.getEncUrl(), entry.getDecUrl());
    }

    private static boolean isDecrypted(String encrypted, String decrypted) {
        return encrypted == null || decrypted != null;
    }
}
