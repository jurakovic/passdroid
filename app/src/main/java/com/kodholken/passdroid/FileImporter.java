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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import android.util.Log;

public class FileImporter {
    private byte [] data;
    private PasswordEntry[] passwordEntries;

    /**
     * Importer constructor.
     *
     * @param data contents of the file to import.
     */
    public FileImporter(byte [] data) {
        this.data = data;
    }

    public void parse() throws FileImporterException {
        try {
            parse(createInputStream());
        } catch (IOException ex) {
            throw new FileImporterException(ex);
        }
    }

    public void parse(InputStream input) throws FileImporterException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            // Exports are always UTF-8. Decoding the file here, instead of
            // letting the parser pick the encoding, makes the check below see
            // the same characters as the parser.
            String xml = new String(readAll(input), StandardCharsets.UTF_8);
            if (xml.startsWith("\uFEFF")) {
                xml = xml.substring(1);
            }

            // Exports never have a document type declaration. It could declare
            // entities that expand to a lot of text when the file is read,
            // e.g. to use up the memory. Android's parser does not support
            // the feature that turns them off.
            if (hasDoctype(xml)) {
                throw new FileImporterException("Document type declarations are not supported");
            }

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            if (root == null || !root.getTagName().equals("passdroid")) {
                throw new FileImporterException("Invalid file format: " + (root == null ? "<empty>" : root.getTagName()));
            }

            Node versionNode = root.getAttributes().getNamedItem("version");
            if (versionNode == null) {
                throw new FileImporterException("Missing version attribute on passdroid tag");
            }
            String version = versionNode.getNodeValue();
            Log.d(FileImporter.class.getName(), "Import file version: " + version);
            parseImportFile(version, root);
        } catch (ParserConfigurationException ex) {
            throw new FileImporterException(ex);
        } catch (IOException ex) {
            throw new FileImporterException(ex);
        } catch (SAXException ex) {
            throw new FileImporterException(ex);
        }
    }

    /**
     * @return true if the XML has a document type declaration. It can only
     *         come before the root element, where the rest is whitespace, the
     *         XML declaration, comments and processing instructions. Values
     *         in the elements can contain the same text.
     */
    static boolean hasDoctype(String xml) {
        int i = 0;
        while (true) {
            while (i < xml.length() && Character.isWhitespace(xml.charAt(i))) {
                i++;
            }

            String end;
            if (xml.startsWith("<?", i)) {
                i += 2;
                end = "?>";
            } else if (xml.startsWith("<!--", i)) {
                i += 4;
                end = "-->";
            } else {
                // Only a document type declaration starts with "<!" here
                return xml.startsWith("<!", i);
            }

            i = xml.indexOf(end, i);
            if (i == -1) {
                // The parser does not accept the file
                return false;
            }
            i += end.length();
        }
    }

    private static byte [] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte [] buffer = new byte[4096];
        int nread;
        while ((nread = input.read(buffer)) != -1) {
            output.write(buffer, 0, nread);
        }
        return output.toByteArray();
    }

    public boolean isEncrypted() throws FileImporterException {
        if (data.length < 3) {
            throw new FileImporterException("Could not read signature");
        }

        return hasSignature(FileExporter.ENCRYPTED_SIGNATURE) ||
               hasSignature(FileExporter.LEGACY_ENCRYPTED_SIGNATURE);
    }

    /**
     * Tries to extract the exported file version without any XML parsing. This
     * is needed since the (invalid) exported files are sometimes not accepted
     * by the SAX parser.
     * 
     * @return The extracted file version if it exists. Else null.
     * 
     * @throws IOException
     */
    private String getFileVersion() throws IOException {
        String version = null;
        BufferedReader br = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data)));

        String s;
        Pattern p = Pattern.compile(".*passdroid version=\"([0-9]+(\\.[0-9]+)?)\".*");
        while ((s = br.readLine()) != null) {
            Matcher m = p.matcher(s);
            if (m.matches()) {
                version = m.group(1);
                break;
            }
        }

        br.close();

        return version;
    }

    private InputStream createInputStream() throws IOException, FileImporterException {
        String version = getFileVersion();

        if (version == null) {
            throw new FileImporterException("Unable to get exported file version");
        }

        if (getFormatVersion(version) > 1) {
            // Version 1.7 or later is passed as is.
            return new ByteArrayInputStream(data);
        }

        /* 
         * If the version is 1.6 or earlier we need to pass the exported file
         * through a filter to escape some non-allowed characters so we
         * escape the non-allowed characters and create a new input stream
         * from the result.
         */

        BufferedReader br = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data)));

        String s;
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("(.*)<system name=\\\"([^\"]+)\\\">.*");
        while ((s = br.readLine()) != null) {
            Matcher m = p.matcher(s);
            if (m.matches()) {
                sb.append(m.group(1) + "<system name=\"" + 
                        Utils.escapeXMLChars(m.group(2)) + "\">\n");
            } else {
                sb.append(s + "\n");
            }
        }

        br.close();

        return new ByteArrayInputStream(sb.toString().getBytes("UTF-8"));
    }

    private void parseImportFile(String version, Node root) throws FileImporterException {
        ImportFileParser parser = null;

        int formatVersion = getFormatVersion(version);
        if (formatVersion > FileExporter.FORMAT_VERSION) {
            throw new FileImporterException("Import file format (" +
                    formatVersion + ") is newer than this app supports (" +
                    FileExporter.FORMAT_VERSION + "). Update the app to import it.");
        }

        parser = new ImportFileParser_v_1_0();		
        parser.parse(root);
    }

    /**
     * Get the version of the file format from the version attribute of the
     * passdroid element. Since Passdroid++ 3.0 it is the format version, a
     * whole number, see FileExporter.FORMAT_VERSION. Earlier files have the
     * version of the app that exported them, <major>.<minor>:
     *
     *   1  Passdroid 1.6 and earlier, see createInputStream()
     *   2  Passdroid 1.7 to 2.3
     *   3  Passdroid++: CDATA values. Its first builds were numbered 2.4.
     *
     * @throws FileImporterException if the version is invalid.
     */
    static int getFormatVersion(String version) throws FileImporterException {
        if (version.matches("[1-9][0-9]{0,8}")) {
            return Integer.parseInt(version);
        }

        Version appVersion;
        try {
            appVersion = Version.parse(version);
        } catch (NumberFormatException ex) {
            throw new FileImporterException("Invalid import file version: " + version);
        }

        if (appVersion.compareTo(new Version(1, 6)) <= 0) {
            return 1;
        }
        if (appVersion.compareTo(new Version(2, 3)) <= 0) {
            return 2;
        }
        if (appVersion.compareTo(new Version(2, 4)) <= 0) {
            return 3;
        }
        // No app version after 2.4 was written to the file
        throw new FileImporterException("Unknown import file version: " + version);
    }

    public PasswordEntry [] getPasswordEntries() {
        return passwordEntries;
    }

    private interface ImportFileParser {
        void parse(Node root);
    }

    private class ImportFileParser_v_1_0 implements ImportFileParser {
        public ImportFileParser_v_1_0() {}

        @Override
        public void parse(Node root) {
            ArrayList<PasswordEntry> entries = new ArrayList<PasswordEntry>();

            NodeList nodes = root.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i).getNodeName().equals("system")) {
                    PasswordEntry entry = parseSystemNode(nodes.item(i));
                    entries.add(entry);
                }
            }

            passwordEntries = entries.toArray(new PasswordEntry [entries.size()]);
        }

        private PasswordEntry parseSystemNode(Node system) {
            String name  = system.getAttributes().getNamedItem("name").getNodeValue();
            String username = "";
            String password = "";
            String note = "";
            String url = "";

            NodeList nodes = system.getChildNodes();

            // The values are read with getTextContent() since a value
            // containing "]]>" is exported as several CDATA sections.
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node.getNodeName().equals("username")) {
                    username = node.getTextContent();
                } else if (node.getNodeName().equals("password")) {
                    password = node.getTextContent();
                } else if (node.getNodeName().equals("note")) {
                    note = node.getTextContent();
                } else if (node.getNodeName().equals("url")) {
                    url = node.getTextContent();
                }
            }

            PasswordEntry entry = new PasswordEntry();
            entry.setDecSystem(name);
            entry.setDecUsername(username);
            entry.setDecPassword(password);
            entry.setDecNote(note);
            entry.setDecUrl(url);

            return entry;
        }
    }

    /**
     * Decrypt and parse an encrypted file.
     *
     * @param key key derived from the master password that was used when the
     *            file was exported.
     * @throws FileImporterException if the key is wrong or the file is invalid.
     */
    public void parseEncrypted(byte[] key) throws FileImporterException {
        if (hasSignature(FileExporter.ENCRYPTED_SIGNATURE)) {
            EncryptedHeader header = new EncryptedHeader();

            byte [] aad = Arrays.copyOfRange(data, 0, header.length);
            byte [] nonce = Arrays.copyOfRange(data, header.length,
                    header.length + Crypto.GCM_NONCE_LENGTH);
            byte [] encrypted = Arrays.copyOfRange(data,
                    header.length + Crypto.GCM_NONCE_LENGTH, data.length);

            byte [] decrypted = Crypto.decryptAesGcm(key, nonce, encrypted, aad);
            if (decrypted == null) {
                throw FileImporterException.wrongKey("Decryption failed");
            }

            // The file is authenticated, so the key was right and the errors
            // from here on are errors of the file
            parse(new ByteArrayInputStream(decrypted));
            return;
        }

        // File needs to be atleast 3 bytes to have room for encryption signature
        if (data.length < 3) {
            throw new FileImporterException("File too small: " + data.length);
        }

        // Skip the signature
        byte[] buffer = Arrays.copyOfRange(data, 3, data.length);

        byte[] decrypted = Crypto.decryptAesCbc(key, buffer);
        if (decrypted == null) {
            throw FileImporterException.wrongKey("Decryption failed");
        }

        // This format is not authenticated: a wrong key can also give data
        // that can not be parsed
        InputStream is = new ByteArrayInputStream(decrypted, 2 /* Skip salt */, decrypted.length - 1);
        try {
            parse(is);
        } catch (FileImporterException ex) {
            throw FileImporterException.wrongKey(ex.getMessage());
        }
    }

    /**
     * Decrypt and parse an encrypted file using the master password that was
     * used when the file was exported. This is slow and should not be called
     * on the UI thread.
     *
     * @throws FileImporterException if the password is wrong or the file is
     *         invalid.
     */
    public void parseEncryptedWithPassword(String password) throws FileImporterException {
        if (hasSignature(FileExporter.ENCRYPTED_SIGNATURE)) {
            EncryptedHeader header = new EncryptedHeader();
            parseEncrypted(MasterKey.deriveKey(password, header.salt,
                                               header.iterations));
        } else {
            parseEncrypted(Crypto.hmacFromPassword(password));
        }
    }

    private boolean hasSignature(byte [] signature) {
        if (data.length < signature.length) {
            return false;
        }

        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * The unencrypted header of an encrypted file, @see
     * FileExporter#exportEncrypted(byte[], byte[], int, PasswordEntry[]).
     */
    private class EncryptedHeader {
        final int iterations;
        final byte [] salt;
        final int length;  // Length of the header in bytes

        EncryptedHeader() throws FileImporterException {
            int offset = FileExporter.ENCRYPTED_SIGNATURE.length;
            if (data.length < offset + 5) {
                throw new FileImporterException("File too small: " + data.length);
            }

            iterations = ByteBuffer.wrap(data, offset, 4).getInt();
            int saltLength = data[offset + 4] & 0xff;
            length = offset + 5 + saltLength;

            if (iterations < 1 || iterations > MasterKey.MAX_ITERATIONS) {
                throw new FileImporterException("Invalid iteration count: " + iterations);
            }

            if (data.length < length + Crypto.GCM_NONCE_LENGTH) {
                throw new FileImporterException("File too small: " + data.length);
            }

            salt = Arrays.copyOfRange(data, offset + 5, length);
        }
    }
}
