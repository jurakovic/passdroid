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

import java.io.IOException;
import java.io.OutputStream;

/**
 * Class that handles the exporting of the password database to file.
 */
public class FileExporter {
    /**
     * The version of the file format, see FileImporter.getFormatVersion().
     * Increase it when the format changes in a way that earlier versions
     * would not import correctly, e.g. a new field, which they would leave
     * out. They refuse files with a newer format.
     */
    static final int FORMAT_VERSION = 3;

    private OutputStream output;
    private String appVersion;

    /**
     * Exporter constructor.
     *
     * @param output     stream to write the exported file to. The caller is
     *                   responsible for closing it.
     * @param appVersion version name of the app, recorded in the file for
     *                   information.
     */
    public FileExporter(OutputStream output, String appVersion) {
        this.output = output;
        this.appVersion = appVersion;
    }

    /**
     * Export the password database unencrypted.
     * 
     * @param passwords  array of password entries that make up the passdroid
     *                   database.
     * @throws ExportException on failure.
     */
    public void exportCleartext(PasswordEntry [] passwords) throws ExportException {
        String xml = createXmlTree(passwords);
        try {
            write(xml.getBytes());
        } catch (Exception ex) {
            throw new ExportException(ex.getMessage());
        }
    }

    /**
     * Export the password database encrypted. The format of the encrypted file
     * is:
     * [1] 's'
     * [1] 'q'
     * [1] 't'
     * [?] encrypted data 
     * 
     * @param key        AES key to use for encryption.
     * @param passwords  array of passwords to export
     * @throws ExportException on failure.
     */
    public void exportEncrypted(byte [] key, PasswordEntry [] passwords) throws ExportException {
        String xml = createXmlTree(passwords);

        byte [] magic = { 's', 'q', 't' };
        byte [] encrypted = Crypto.encryptAesCbc(key, xml);
        if (encrypted == null) {
            throw new ExportException("Encryption failed");
        }

        byte [] all = new byte[magic.length + encrypted.length];
        System.arraycopy(magic, 0, all, 0, magic.length);
        System.arraycopy(encrypted, 0, all, magic.length, encrypted.length);

        try {
            write(all);
        } catch (Exception ex) {
            throw new ExportException(ex.getMessage());
        }
    }

    /**
     * Create XML tree from an array of passwords. This tree represents the
     * format that is stored in the exported files on disk.
     * 
     * @param passwords array of passwords.
     * @return String representing the XML tree.
     */
    private String createXmlTree(PasswordEntry [] passwords) {
        StringBuilder sb = new StringBuilder();

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<passdroid version=\"" + FORMAT_VERSION + "\" app-version=\"" +
                  Utils.escapeXMLChars(appVersion) + "\">\n");
        for (PasswordEntry entry : passwords) {
            sb.append("  <system name=\"" +
                      Utils.escapeXMLChars(removeInvalidChars(entry.getDecSystem())) +
                      "\">\n");
            appendElement(sb, "username", entry.getDecUsername());
            appendElement(sb, "password", entry.getDecPassword());
            appendElement(sb, "note", entry.getDecNote());
            appendElement(sb, "url", entry.getDecUrl());
            sb.append("  </system>\n");
        }

        sb.append("</passdroid>\n");

        return sb.toString();
    }

    /**
     * Append an element with a value in a CDATA section, unless the value is
     * null. A "]]>" in the value is split across two CDATA sections.
     */
    private static void appendElement(StringBuilder sb, String name, String value) {
        if (value == null) {
            return;
        }

        sb.append("    <" + name + "><![CDATA[" +
                  removeInvalidChars(value).replaceAll("]]>", "]]]]><![CDATA[>") +
                  "]]></" + name + ">\n");
    }

    /**
     * Remove the characters that XML 1.0 does not allow, not even in a CDATA
     * section, such as most control characters. A file with them could not
     * be imported.
     */
    static String removeInvalidChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (c == 0x9 || c == 0xa || c == 0xd ||
                (c >= 0x20 && c <= 0xd7ff) || (c >= 0xe000 && c <= 0xfffd) ||
                c >= 0x10000) {
                sb.appendCodePoint(c);
            }
            i += Character.charCount(c);
        }

        return sb.toString();
    }

    /**
     * Write a byte array to the output stream.
     *
     * @param data      byte array to write.
     * @throws IOException on failure.
     */
    private void write(byte [] data) throws IOException {
        output.write(data);
        output.flush();
    }
}
