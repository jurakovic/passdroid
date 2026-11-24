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

import java.util.Date;

import android.annotation.SuppressLint;
import android.app.AlertDialog;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Activity that displays the user passwords.
 */
public class PasswordListActivity extends TimeoutListActivity
implements PasswordModelListener {
    // True if the user logged in with a master password that is shorter than
    // is required for new ones, see MasterKey.MIN_PASSWORD_LENGTH
    public static final String EXTRA_SHORT_PASSWORD = "short_password";
    // After a backup was restored on the welcome screen: the number of
    // entries, the settings it changed as SettingsBackup.describe() lists
    // them (if any), and the file if it isn't encrypted
    public static final String EXTRA_RESTORED = "restored";
    public static final String EXTRA_RESTORED_SETTINGS = "restored_settings";
    public static final String EXTRA_RESTORED_FILE = "restored_file";

    private static final int OPTION_MENU_ADD      = 1;
    private static final int OPTION_MENU_SETTINGS = 3;
    private static final int OPTION_MENU_ABOUT    = 4;
    private static final int OPTION_MENU_LOGOUT   = 6;
    private static final int OPTION_MENU_GENERATE = 7;
    private static final int OPTION_MENU_SEARCH   = 8;
    private static final int OPTION_MENU_TOTP     = 9;

    private ListView list;
    private int listPosition;

    private TextView     emptyListHelp;
    private TextView     passwordCountTextView;

    private boolean passwordCounterIsVisible = false;

    private PasswordAdapter passwordAdapter = null;

    // The text of the search, null while it is closed. A reload of the list
    // creates a new adapter, which gets it too.
    private String searchFilter = null;
    
    MenuItem searchMenuItem;

    public PasswordListActivity() {
        listPosition = 0;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Session.getInstance().isLoggedIn()) {
            finish();
            return ;
        }

        setContentView(R.layout.password);

        list = (ListView) findViewById(android.R.id.list);
        initLongClickListener();

        // Make sure the password entries is loaded from the database
        Session.getInstance().setNeedReload(true);

        // Once after the login, not when the activity is created again
        boolean shortPassword = savedInstanceState == null &&
                getIntent().getBooleanExtra(EXTRA_SHORT_PASSWORD, false);
        if (savedInstanceState == null && getIntent().hasExtra(EXTRA_RESTORED)) {
            showRestoreResult(shortPassword);
        } else if (savedInstanceState == null && EntryDraft.exists(this)) {
            offerDraft(shortPassword);
        } else if (shortPassword) {
            showShortPasswordReminder();
        }

        emptyListHelp = (TextView) findViewById(R.id.empty_list_help);
        
        passwordCountTextView = (TextView) findViewById(R.id.password_count);
        getListView().setTextFilterEnabled(true);

        PasswordModel.getInstance(this).addListener(this);

        // From Android 13 back events are delivered through callbacks instead
        // of onBackPressed() (see android:enableOnBackInvokedCallback).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            handleBack();
                        }
                    });
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!Session.getInstance().isLoggedIn()) {
            finish();
            return ;
        }

        if (Session.getInstance().needReload()) {
            loadPasswords();
            Session.getInstance().setNeedReload(false);
            list.setSelection(listPosition);
        } else {
            Utils.debug("Skipping loadPassword()");
        }
    }
    
    @Override
    protected void onDestroy() {
        // The model outlives the screen, which would otherwise be kept with
        // the decrypted entries of its list
        PasswordModel.getInstance(this).removeListener(this);
        super.onDestroy();
    }

    @Override
    public boolean onSearchRequested() {
        searchMenuItem.expandActionView();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        searchMenuItem = menu.add(Menu.NONE, OPTION_MENU_SEARCH, Menu.NONE, getString(R.string.options_search));
        
        searchMenuItem
        .setActionView(R.layout.collapsable_search)
        .setIcon(R.drawable.ic_menu_search)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);

        final EditText searchField = (EditText) searchMenuItem.getActionView().findViewById(R.id.search_field);
        final View searchClear = searchMenuItem.getActionView().findViewById(R.id.search_clear);

        // Empties the field and keeps the search open
        searchClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchField.setText("");
                searchField.requestFocus();
            }
        });

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable arg0) {
                searchClear.setVisibility(arg0.length() > 0 ? View.VISIBLE : View.GONE);
                searchFilter = arg0.toString();
                if (passwordAdapter != null) {
                    passwordAdapter.setFilterString(searchFilter);
                    passwordAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void beforeTextChanged(CharSequence arg0, int arg1,
                    int arg2, int arg3) {
                // TODO Auto-generated method stub
                
            }

            @Override
            public void onTextChanged(CharSequence arg0, int arg1, int arg2,
                    int arg3) {
                // TODO Auto-generated method stub
                
            }    
        });

        searchMenuItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                searchFilter = "";
                passwordCountTextView.setVisibility(View.GONE);
                searchField.post(new Runnable() {
                    @Override
                    public void run() {
                        searchField.setText("");
                        searchField.setFocusable(true);
                        searchField.setFocusableInTouchMode(true);
                        searchField.requestFocus();
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
                    }
                });

                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                searchField.setFocusable(false);
                searchField.setFocusableInTouchMode(false);

                if (passwordCounterIsVisible) {
                    passwordCountTextView.setVisibility(View.VISIBLE);
                }
                searchFilter = null;
                passwordAdapter.setFilterString(null);
                passwordAdapter.notifyDataSetChanged();

                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(searchField.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);

                return true;
            }
        });
        
        menu.add(Menu.NONE, OPTION_MENU_TOTP, Menu.NONE, getString(R.string.totp_list_title))
        .setIcon(R.drawable.ic_menu_totp)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

        menu.add(Menu.NONE, OPTION_MENU_ADD, Menu.NONE, getString(R.string.options_add))
        .setIcon(R.drawable.ic_menu_add);
        
        menu.add(Menu.NONE, OPTION_MENU_GENERATE, Menu.NONE,
                getString(R.string.options_generate_password))
        .setIcon(R.drawable.ic_menu_rotate);

        menu.add(Menu.NONE, OPTION_MENU_SETTINGS, Menu.NONE,
                getString(R.string.options_settings))
        .setIcon(R.drawable.ic_menu_preferences);

        menu.add(Menu.NONE, OPTION_MENU_ABOUT, Menu.NONE,
                getString(R.string.options_about))
        .setIcon(R.drawable.ic_menu_info_details);

        menu.add(Menu.NONE, OPTION_MENU_LOGOUT, Menu.NONE,
                getString(R.string.options_logout))
        .setIcon(R.drawable.ic_menu_close_clear_cancel);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;

        switch (item.getItemId()) {
        case OPTION_MENU_ABOUT:
            i = new Intent(this, AboutActivity.class);
            startActivity(i);
            break;
        case OPTION_MENU_SETTINGS:
            startActivity(new Intent(this, SettingsActivity.class));
            listPosition = list.getFirstVisiblePosition();
            break;
        case OPTION_MENU_ADD:
            startActivity(new Intent(this, AddActivity.class));
            listPosition = list.getFirstVisiblePosition();
            break;
        case OPTION_MENU_TOTP:
            startActivity(new Intent(this, TotpListActivity.class));
            listPosition = list.getFirstVisiblePosition();
            break;
        case OPTION_MENU_GENERATE:
            Intent generateIntent = new Intent(this,
                    GeneratePasswordActivity.class);
            generateIntent.putExtra("displayPassword", 1);
            startActivity(generateIntent);
            break;
        case OPTION_MENU_LOGOUT:
            confirmLogout();
            break;
        default:
            return super.onOptionsItemSelected(item);
        }
        
        return true;
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);

        //PasswordModel model = PasswordModel.getInstance(this);
        PasswordEntry entry = (PasswordEntry) passwordAdapter.getItem(position);

        Intent i = ShowActivity.createIntent(this, entry);

        listPosition = list.getFirstVisiblePosition();

        i.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    /**
     * Back handler for Android 12 and earlier. Later versions use the
     * callback registered in onCreate().
     */
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (searchMenuItem != null && searchMenuItem.isActionViewExpanded()) {
            searchMenuItem.collapseActionView();
        } else {
            confirmLogout();
        }
    }

    /**
     * Set up a long click handler for the list view. The user will be presented
     * with an option to copy the password to clipboard.
     */
    private void initLongClickListener() {
        final Context context = this;

        list.setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> a, View v,
                    int position, long id) {

                // Get the entry from the adapter since the list may be filtered
                final PasswordEntry entry =
                    (PasswordEntry) passwordAdapter.getItem(position);

                final AlertDialog alertDialog = new AlertDialog.Builder(context).create();
                alertDialog.setTitle("Copy to clipboard");
                alertDialog.setMessage("Copy the password to clipboard?");

                alertDialog.setButton(AlertDialog.BUTTON1, "Yes",
                        new DialogInterface.OnClickListener() { 
                    public void onClick(DialogInterface dialog, int which) {
                        Utils.copyPasswordToClipboard(context,
                                entry.decryptPassword(Session.getInstance().getKey()));
                    }
                });

                alertDialog.setButton(AlertDialog.BUTTON2, "No",
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {}
                });

                alertDialog.show();

                return true;
            }
        });
    }

    /**
     * Suggest changing a master password that is too short to be set now.
     */
    /**
     * Report a backup restored on the welcome screen, and offer to delete
     * it if it isn't encrypted, as the import does.
     *
     * @param shortPassword show the reminder about a short master password
     *                      once the dialog is closed.
     */
    private void showRestoreResult(final boolean shortPassword) {
        final Runnable next = new Runnable() {
            @Override
            public void run() {
                if (shortPassword && !isFinishing()) {
                    showShortPasswordReminder();
                }
            }
        };
        final DialogInterface.OnDismissListener nextOnDismiss = new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                next.run();
            }
        };

        int count = getIntent().getIntExtra(EXTRA_RESTORED, 0);
        String message = getResources().getQuantityString(R.plurals.restore_done,
                                                          count, count);
        String settings = getIntent().getStringExtra(EXTRA_RESTORED_SETTINGS);
        if (settings != null) {
            message += "\n\n" + getString(R.string.import_settings_changed) +
                       "\n" + settings;
        }

        final Uri file = getIntent().getParcelableExtra(EXTRA_RESTORED_FILE);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(R.string.success);
        if (file == null) {
            builder.setMessage(message)
                   .setPositiveButton(R.string.ok, null)
                   .setOnDismissListener(nextOnDismiss);
        } else {
            builder.setMessage(message + "\n\n" + getString(R.string.restore_delete_question))
                   .setCancelable(false)
                   .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                       @Override
                       public void onClick(DialogInterface dialog, int which) {
                           if (Utils.deleteDocument(PasswordListActivity.this, file)) {
                               next.run();
                               return;
                           }
                           new AlertDialog.Builder(PasswordListActivity.this)
                               .setTitle(R.string.failure)
                               .setMessage(R.string.restore_delete_failed)
                               .setPositiveButton(R.string.ok, null)
                               .setOnDismissListener(nextOnDismiss)
                               .show();
                       }
                   })
                   .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                       @Override
                       public void onClick(DialogInterface dialog, int which) {
                           next.run();
                       }
                   });
        }
        builder.show();
    }

    /**
     * Offer the unsaved changes kept at the last logout, see EntryDraft.
     *
     * @param shortPassword show the reminder about a short master password
     *                      once the dialog is closed.
     */
    private void offerDraft(final boolean shortPassword) {
        DialogInterface.OnDismissListener next = new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (shortPassword && !isFinishing()) {
                    showShortPasswordReminder();
                }
            }
        };

        final EntryDraft.Draft draft = EntryDraft.load(this, Session.getInstance().getKey());
        if (draft == null) {
            // E.g. stored with a legacy key that the login has replaced
            EntryDraft.delete(this);
            new AlertDialog.Builder(this)
                .setMessage(R.string.draft_lost)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(next)
                .show();
            return;
        }

        final PasswordEntry entry = draft.entryId >= 0 ?
                PasswordModel.getInstance(this).getById(draft.entryId) : null;
        String name = draft.values.getDecSystem();
        if (name == null || name.trim().isEmpty()) {
            name = entry != null ? Utils.systemName(this, entry) :
                                   getString(R.string.draft_new_entry);
        }
        String time = DateField.format(this, DateField.toValue(new Date(draft.savedAt), true));

        new AlertDialog.Builder(this)
            .setTitle(R.string.draft_title)
            .setMessage(getString(R.string.draft_message, time, name))
            // Answered either way, so that an editor opened later doesn't
            // replace it unseen
            .setCancelable(false)
            .setPositiveButton(R.string.draft_continue, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    continueDraft(entry);
                }
            })
            .setNegativeButton(R.string.discard, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    EntryDraft.delete(PasswordListActivity.this);
                }
            })
            .setOnDismissListener(next)
            .show();
    }

    /**
     * Open the draft in the edit screen of its entry, or in the add screen
     * if it is of a new entry or its entry is gone.
     */
    private void continueDraft(PasswordEntry entry) {
        Intent intent;
        if (entry != null) {
            intent = new Intent(this, EditActivity.class);
            intent.putExtra(EditActivity.EXTRA_ID, entry.getId());
            intent.putExtra(EditActivity.EXTRA_DRAFT, true);
        } else {
            intent = new Intent(this, AddActivity.class);
            intent.putExtra(AddActivity.EXTRA_DRAFT, true);
        }
        startActivity(intent);
    }

    private void showShortPasswordReminder() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.master_password_short_title)
            .setMessage(getString(R.string.master_password_short_reminder,
                                  MasterKey.MIN_PASSWORD_LENGTH))
            .setPositiveButton(R.string.change, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startActivity(new Intent(PasswordListActivity.this,
                                             ChangePasswordActivity.class));
                }
            })
            .setNegativeButton(R.string.later, null)
            .show();
    }

    private void confirmLogout() {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Confirm logout");
        alertDialog.setMessage("Do you really want to log out?");

        alertDialog.setButton(AlertDialog.BUTTON1, "Yes",
                new DialogInterface.OnClickListener() { 
            public void onClick(DialogInterface dialog, int which) {
                Session.getInstance().logout();
                finish();
            }
        });

        alertDialog.setButton(AlertDialog.BUTTON2, "No",
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {}
        });

        alertDialog.show();
    }

    private void loadPasswords() {
        PasswordEntry [] entries =
            PasswordModel.getInstance(this).getPasswords();
        
        passwordAdapter = new PasswordAdapter(this, entries,
                                              PreferenceManager.getDefaultSharedPreferences(this).
                                              getBoolean("display_username", true)); 
        passwordAdapter.setFilterString(searchFilter);
        setListAdapter(passwordAdapter); 

        updateTitlebar();

        if (entries.length == 0) {
            emptyListHelp.setVisibility(View.VISIBLE);
            passwordCounterIsVisible = false;
            passwordCountTextView.setVisibility(View.GONE);
            list.setVisibility(View.GONE);
        } else {
            emptyListHelp.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            passwordCounterIsVisible = true;
            passwordCountTextView.setVisibility(View.VISIBLE);
        }
    }

    private void updateTitlebar() {		
        int count = PasswordModel.getInstance(this).getPasswords().length;
        String quantifier = count == 1 ? "" : "s";

        passwordCountTextView.setText(count + " password" + quantifier);
    }

    @Override
    public void onPasswordModelChange(PasswordModel model) {
        loadPasswords();
    }
}
