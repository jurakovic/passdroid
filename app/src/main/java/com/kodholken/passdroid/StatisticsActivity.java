package com.kodholken.passdroid;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TextView;

/**
 * Shows how many entries there are, how many have each field, how many
 * different emails and usernames they have, and how many passwords more
 * than one entry uses. See Statistics.
 */
public class StatisticsActivity extends TimeoutActivity {
    private TableLayout table;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.statistics);

        table = (TableLayout) findViewById(R.id.statistics);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The activity finishes if the user has been logged out
        if (isFinishing()) {
            return;
        }

        // Counted each time, so the numbers are current after a change to
        // the entries. Only the numbers are kept, and no password, 2FA
        // secret, note, URL or date becomes a string, see Statistics.
        show(Statistics.count(PasswordModel.getInstance(this).getPasswords(),
                              Session.getInstance().getKey()));
    }

    private void show(Statistics statistics) {
        table.removeAllViews();

        addRow(R.string.statistics_entries, Integer.toString(statistics.entries));
        if (statistics.entries == 0) {
            return;
        }

        addRow(R.string.statistics_emails,
               withDifferent(statistics.withEmail, statistics.differentEmails));
        addRow(R.string.statistics_usernames,
               withDifferent(statistics.withUsername, statistics.differentUsernames));
        addRow(R.string.statistics_passwords, entries(statistics.withPassword));
        addRow(R.string.statistics_reused_passwords,
               statistics.reusedPasswords == 0 ? "0" :
               getString(R.string.statistics_reused, statistics.reusedPasswords,
                         entries(statistics.entriesWithReusedPassword)));
        addRow(R.string.statistics_totp, entries(statistics.withTotp));
        addRow(R.string.statistics_notes, entries(statistics.withNote));
        addRow(R.string.statistics_urls, entries(statistics.withUrl));
        addRow(R.string.statistics_dates, entries(statistics.withDate));
    }

    /** E.g. "12 entries, 3 different", or "0 entries". */
    private String withDifferent(int entries, int different) {
        return entries == 0 ? entries(entries) :
               getResources().getQuantityString(R.plurals.statistics_different,
                                                different, entries(entries),
                                                different);
    }

    /** E.g. "1 entry" or "12 entries". */
    private String entries(int count) {
        return getResources().getQuantityString(R.plurals.statistics_entry_count,
                                                count, count);
    }

    private void addRow(int label, String value) {
        if (table.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.list_divider));
            table.addView(divider, new TableLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        View row = LayoutInflater.from(this).inflate(R.layout.statistics_row,
                                                     table, false);
        ((TextView) row.findViewById(R.id.statistics_label)).setText(label);
        ((TextView) row.findViewById(R.id.statistics_value)).setText(value);
        table.addView(row);
    }
}
