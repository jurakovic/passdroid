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

import com.kodholken.passdroid.Constants;
import com.kodholken.passdroid.PasswordEntry;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class PasswordData extends SQLiteOpenHelper {	
    /**
     * Columns of the data table in the order readEntry() expects them.
     */
    public static final String [] COLUMNS = {
        "id", "system", "username", "password", "note", "url", "email", "totp",
        "date"
    };

    public PasswordData(Context ctx) {
        super(ctx, Constants.DBNAME, null, Constants.DBVERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE data (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                   "system TEXT NOT NULL, username TEXT, password TEXT, note TEXT, url TEXT, " +
                   "email TEXT, totp TEXT, date TEXT)");
    }

    /**
     * Read an entry from a cursor over COLUMNS. The values are not decrypted.
     */
    public static PasswordEntry readEntry(Cursor cur) {
        PasswordEntry entry = new PasswordEntry();
        entry.setId(cur.getInt(0));
        entry.setEncSystem(cur.getString(1));
        entry.setEncUsername(cur.getString(2));
        entry.setEncPassword(cur.getString(3));
        entry.setEncNote(cur.getString(4));
        entry.setEncUrl(cur.getString(5));
        entry.setEncEmail(cur.getString(6));
        entry.setEncTotp(cur.getString(7));
        entry.setEncDate(cur.getString(8));
        return entry;
    }

    /**
     * The encrypted values of an entry to insert or update, without the id.
     */
    public static ContentValues getValues(PasswordEntry entry) {
        ContentValues values = new ContentValues();
        values.put("system",   entry.getEncSystem());
        values.put("username", entry.getEncUsername());
        values.put("password", entry.getEncPassword());
        values.put("note",     entry.getEncNote());
        values.put("url",      entry.getEncUrl());
        values.put("email",    entry.getEncEmail());
        values.put("totp",     entry.getEncTotp());
        values.put("date",     entry.getEncDate());
        return values;
    }
    
    /**
     * Do not perform any operations in this method except
     * DbMigration.handleDatabaseUpgrade(). @see DbMigration.handleDatabaseUpgrade()
     * for explanation.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        DbMigration.handleDatabaseUpgrade(db, oldVersion, newVersion);
    }

    public void verifyTable() {
        SQLiteDatabase db = getReadableDatabase();
        try {
            db.rawQuery("SELECT * FROM data", null);
        } catch (SQLException ex) {
            onCreate(db);
        }
        db.close();
    }
}
