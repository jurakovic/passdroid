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

public class FileImporterException extends Exception {
    private static final long serialVersionUID = 1L;

    private boolean wrongKey;

    public FileImporterException(String message) {
        super(message);
    }

    public FileImporterException(Exception ex) {
        super(ex);
    }

    /**
     * @return an exception for an encrypted file that could not be
     *         decrypted, most likely because the key is wrong.
     */
    static FileImporterException wrongKey(String message) {
        FileImporterException ex = new FileImporterException(message);
        ex.wrongKey = true;
        return ex;
    }

    /**
     * @return true if the file may be imported with another password. False
     *         if the file itself can not be imported, e.g. because it has a
     *         newer format.
     */
    public boolean isWrongKey() {
        return wrongKey;
    }
}
