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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FileImportActivity extends SherlockTimeoutActivity {
	private LinearLayout importLayout;
    private Button cancelButton;
    private Button importButton;
    private TextView importDesc2;
    private FileImporter fileImporter;
    private Uri importUri;
    private boolean selectingFile;
    private boolean prepared;

    private static final int IMPORT_PASSWORD_RESULT_ID = 1;
    private static final int SELECT_FILE_RESULT_ID = 2;

    private static final String STATE_IMPORT_URI = "importUri";
    private static final String STATE_SELECTING_FILE = "selectingFile";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.setContentView(R.layout.file_import);

        importLayout = (LinearLayout) findViewById(R.id.import_layout);

        cancelButton = (Button) this.findViewById(R.id.cancel_button);
        cancelButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        importButton = (Button) this.findViewById(R.id.import_button);
        importDesc2 = (TextView) this.findViewById(R.id.import_desc_2);

        if (savedInstanceState != null) {
            importUri = savedInstanceState.getParcelable(STATE_IMPORT_URI);
            selectingFile = savedInstanceState.getBoolean(STATE_SELECTING_FILE);
        }
        prepared = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(STATE_IMPORT_URI, importUri);
        outState.putBoolean(STATE_SELECTING_FILE, selectingFile);
    }

    @Override
    protected void onResume() {
    	super.onResume();

    	if (isFinishing() || prepared) {
    	    return;
    	}

    	if (importUri != null) {
    	    prepareImport();
    	} else if (!selectingFile) {
    	    selectImportFile();
    	}
    }

    /**
     * Let the user choose the file to import using the system file picker.
     */
    private void selectImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Encrypted files have no well-known MIME type
        intent.setType("*/*");

        // The file picker is another application so we give the user more
        // time than usual before being logged out.
        Session.getInstance().setExtendedTimeout(true);
        try {
            startActivityForResult(intent, SELECT_FILE_RESULT_ID);
            selectingFile = true;
        } catch (ActivityNotFoundException ex) {
            Session.getInstance().setExtendedTimeout(false);
            showDialog(getString(R.string.failure),
                       "No application available for opening files.");
        }
    }

    private void importEncrypted() {
        try {
            /**
             * First we try to decrypt the file using our current master password. If that
             * fails we ask the user to supply a different password.
             */
            fileImporter.parseEncrypted(Session.getInstance().getKey());

            // If we get here the key was correct

            PasswordModel model = PasswordModel.getInstance(this);
            model.setPasswords(fileImporter.getPasswordEntries());
            showDialog(getString(R.string.success), fileImporter.getPasswordEntries().length + " entries imported.");
        } catch (FileImporterException ex) {
            ex.printStackTrace();
            // Something failed when decrypting the file, try with a user supplied password even though
            // the reason might have been something other that an invalid key.

            Intent i = new Intent(this, ImportPasswordActivity.class);
            startActivityForResult(i, IMPORT_PASSWORD_RESULT_ID);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // This is called before onResume() so check for timeout here as well
        Session.clearTimeoutTimer(this);
        if (!Session.getInstance().isLoggedIn()) {
            finish();
            return;
        }

        if (requestCode == IMPORT_PASSWORD_RESULT_ID && resultCode == RESULT_OK && data.getExtras() != null) {
            String password = data.getExtras().getString("password");

            try {
                fileImporter.parseEncrypted(Crypto.hmacFromPassword(password));
                PasswordModel model = PasswordModel.getInstance(this);
                model.setPasswords(fileImporter.getPasswordEntries());
                showDialog(getString(R.string.success), fileImporter.getPasswordEntries().length + " entries imported from encrypted file.");
            } catch (FileImporterException ex) {
                showDialog(getString(R.string.failure), "Failed to decrypt the file. Please make sure you entered the correct password.");
            }
        } else if (requestCode == SELECT_FILE_RESULT_ID) {
            selectingFile = false;

        	if (resultCode == RESULT_OK && data != null && data.getData() != null) {
        	    importUri = data.getData();
        	    prepareImport();
        	} else {
        		// Something went wrong when selecting import file or the
        		// user pressed the back button. Since we don't have any
        		// import file to work with we close down.
        		finish();
        	}
        }
    }

    private void importUnencrypted() {
        PasswordModel model = PasswordModel.getInstance(this);
        if (model.setPasswords(fileImporter.getPasswordEntries())) {
            deleteFileDialog(getString(R.string.success),
                             fileImporter.getPasswordEntries().length +
                             " entries imported. Do you want to delete the imported file from the device?");
        } else {
            showDialog(getString(R.string.failure), "Import failed.");
        }
    }

    private void showDialog(String title, String message) {
        AlertDialog alertDialog;

        alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        alertDialog.setButton(Dialog.BUTTON_POSITIVE, getString(R.string.ok), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });
        alertDialog.show();
    }

    private void deleteFileDialog(String title, String message) {
        AlertDialog alertDialog;

        alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle(title);
        alertDialog.setMessage(message);
        alertDialog.setButton(Dialog.BUTTON_POSITIVE, getString(R.string.yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (deleteImportFile()) {
                    finish();
                } else {
                    showDialog(getString(R.string.failure),
                               "The imported file could not be deleted. Please delete it manually.");
                }
            }
        });
        alertDialog.setButton(Dialog.BUTTON_NEGATIVE, getString(R.string.no), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });

        alertDialog.show();
    }

    private boolean deleteImportFile() {
        try {
            return DocumentsContract.deleteDocument(getContentResolver(), importUri);
        } catch (Exception ex) {
            // Not all document providers support deleting documents
            ex.printStackTrace();
            return false;
        }
    }

    private String formatString(String template, String file, int nFiles,
            String error) {
        // The file name is chosen by the user and may contain characters
        // that have a special meaning in replacement strings.
        return template.replaceAll("%f", Matcher.quoteReplacement(file))
                .replaceAll("%n", Integer.valueOf(nFiles).toString())
                .replaceAll("%e", Matcher.quoteReplacement(error));
    }

    /**
     * Read the complete contents of a document.
     */
    private byte [] readFile(Uri uri) throws IOException {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("Unable to open file");
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte [] buffer = new byte[4096];
            int nread;
            while ((nread = input.read(buffer)) != -1) {
                output.write(buffer, 0, nread);
            }

            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private void prepareImport() {
        String filename = Utils.getDisplayName(this, importUri);

        importLayout.setVisibility(View.VISIBLE);

        try {
            fileImporter = new FileImporter(readFile(importUri),
                                            Utils.getVersion(FileImportActivity.this));

            if (fileImporter.isEncrypted()) {
                String desc2 = getString(R.string.import_description_encrypted_file_present, filename);
                importDesc2.setText(desc2);
                importDesc2.setTextColor(Color.rgb(50, 150, 50));
                importButton.setEnabled(true);
                importButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        importEncrypted();
                    }
                });
            } else {
                fileImporter.parse();
                String desc2 = formatString(
                        getString(R.string.import_description_file_present),
                        filename,
                        fileImporter.getPasswordEntries().length, "");
                importDesc2.setText(desc2);
                importDesc2.setTextColor(Color.rgb(50, 150, 50));
                importButton.setEnabled(true);
                importButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        importUnencrypted();
                    }
                });
            }
        } catch (Exception ex) {
            // FileImporterException, or IOException when reading the file
            ex.printStackTrace();
            String desc2 = formatString(
                    getString(R.string.import_description_failure),
                    filename, 0, String.valueOf(ex.getMessage()));
            importDesc2.setText(desc2);
            importDesc2.setTextColor(Color.rgb(255, 50, 50));
        }

        prepared = true;
    }
}
