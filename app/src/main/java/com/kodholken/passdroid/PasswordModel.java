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

import java.util.ArrayList;
import java.util.Arrays;

import com.kodholken.passdroid.db.PasswordData;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

public class PasswordModel {
    private static PasswordModel singleton = null;

    private boolean loaded;
    private PasswordData passwordData;
    private PasswordEntry [] passwords;
    private ArrayList<PasswordModelListener> listeners;

    public PasswordModel(Context context) {
        passwordData = new PasswordData(context.getApplicationContext());
        passwordData.verifyTable(); // Make sure data table exist
        loaded = false;
        listeners = new ArrayList<PasswordModelListener>();
    }

    public static PasswordModel getInstance(Context context) {
        if (singleton == null) {
            singleton = new PasswordModel(context);
        }

        return singleton;
    }

    /**
     * Forget the decrypted entries when the user is logged out. They are
     * loaded again after the next login.
     */
    public static void clear() {
        if (singleton != null) {
            singleton.passwords = null;
            singleton.loaded = false;
        }
    }

    public PasswordEntry[] getPasswords() {
        load();
        return passwords;
    }

    public PasswordEntry getAt(int index) {
        load();
        return passwords[index];
    }

    /**
     * @return the entry with an id, or null if there is none, e.g. because
     *         the user has been logged out.
     */
    public PasswordEntry getById(long id) {
        for (PasswordEntry entry : getPasswords()) {
            if (entry.getId() == id) {
                return entry;
            }
        }

        return null;
    }

    /**
     * @return copies of the entries with all fields decrypted, e.g. for an
     *         export. The model only keeps the fields that the list shows
     *         decrypted, see PasswordEntry.decryptListFields().
     */
    public PasswordEntry[] getDecryptedPasswords() {
        PasswordEntry [] entries = getPasswords();
        byte [] key = Session.getInstance().getKey();

        PasswordEntry [] copies = new PasswordEntry[entries.length];
        for (int i = 0; i < entries.length; i++) {
            copies[i] = entries[i].decryptedCopy(key);
        }

        return copies;
    }

    public boolean setPasswords(PasswordEntry [] passwords) {
        // The user has been logged out
        byte [] key = Session.getInstance().getKey();
        if (key == null) {
            return false;
        }

        boolean res = false;
        SQLiteDatabase db = passwordData.getWritableDatabase();

        db.beginTransaction();
        try {
            SQLiteStatement stmt = db.compileStatement("DELETE FROM data");
            stmt.execute();
            for (PasswordEntry entry : passwords) {
                entry.encryptAll(key);

                long id = db.insertOrThrow("data", null,
                                           PasswordData.getValues(entry));
                entry.setId(id);
            }
            db.setTransactionSuccessful();
            res = true;
        } catch (SQLException ex) {
            ex.printStackTrace();
        } finally {
            db.endTransaction();
        }
        db.close();

        if (res) {
            // Loaded again with only the fields the list shows decrypted
            this.passwords = null;
            loaded = false;
            notifyListeners();
        }

        return res;
    }

    public void addListener(PasswordModelListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PasswordModelListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onPasswordModelChange(this);
        }
    }

    private final void load() {
        // Nothing can be decrypted after a logout
        if (Session.getInstance().getKey() == null) {
            passwords = new PasswordEntry[0];
            loaded = false;
            return;
        }

        if (!loaded || Session.getInstance().needReload()) {
            Session.getInstance().setNeedReload(false);
            loadPasswords();
            loaded = true;
            notifyListeners();
        }
    }

    private void loadPasswords() {
        SQLiteDatabase db = passwordData.getReadableDatabase();
        Cursor cur = db.query("data", PasswordData.COLUMNS, null, null, null, null, "id DESC");

        int i = 0;
        passwords = new PasswordEntry[cur.getCount()];
        while (cur.moveToNext()) {
            passwords[i] = PasswordData.readEntry(cur);

            passwords[i].decryptListFields(Session.getInstance().getKey());

            i++;
        }
        cur.close();
        db.close();

        Arrays.sort(passwords);
    }
}
