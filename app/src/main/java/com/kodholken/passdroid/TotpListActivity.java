package com.kodholken.passdroid;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Lists the current 2FA codes of the entries that have a 2FA secret. The bar
 * at the top shows how long the codes are valid. A tap copies a code, a long
 * press opens the entry.
 */
public class TotpListActivity extends SherlockTimeoutActivity {
    // Seconds of the time bar. Almost all services use 30 seconds, codes with
    // another period show their own countdown.
    private static final int PERIOD = 30;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar timeBar;
    private TextView emptyText;
    private CodeAdapter adapter;
    private long shownSecond;  // When the codes were last updated

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long period = PERIOD * 1000L;
            timeBar.setProgress((int) (period - now % period));

            if (now / 1000 != shownSecond) {
                shownSecond = now / 1000;
                adapter.notifyDataSetChanged();
            }

            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.totp_list);

        timeBar = (ProgressBar) findViewById(R.id.time_bar);
        timeBar.setMax(PERIOD * 1000);
        emptyText = (TextView) findViewById(R.id.empty);

        adapter = new CodeAdapter(this);
        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                Totp totp = adapter.getItem(position).totp;
                if (totp != null) {
                    Utils.copyTotpCode(TotpListActivity.this, totp);
                }
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                startActivity(ShowActivity.createIntent(TotpListActivity.this,
                        adapter.getItem(position).entry));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The activity finishes if the user has been logged out
        if (isFinishing()) {
            return;
        }

        // Entries may have been changed on the entry screen
        loadEntries();
        shownSecond = 0;
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void loadEntries() {
        // The same setting as in the password list
        boolean showAccount = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("display_username", true);
        List<PasswordEntry> entries = new ArrayList<PasswordEntry>();
        for (PasswordEntry entry : PasswordModel.getInstance(this).getPasswords()) {
            if (entry.getDecTotp() != null && entry.getDecTotp().length() > 0) {
                entries.add(entry);
            }
        }

        adapter.setEntries(entries, showAccount);
        emptyText.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        timeBar.setVisibility(entries.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private static class Code {
        final PasswordEntry entry;
        final String title;
        final Totp totp;  // null if the secret is not valid

        /**
         * @param showAccount true to show the username, or the email if there
         *                    is none, after the system.
         */
        Code(PasswordEntry entry, boolean showAccount) {
            this.entry = entry;
            totp = Totp.parse(entry.getDecTotp());

            // The account tells entries of the same service apart
            String account = showAccount ? entry.getDecUsername() : null;
            if (showAccount && (account == null || account.length() == 0)) {
                account = entry.getDecEmail();
            }

            title = account != null && account.length() > 0 ?
                    entry.getDecSystem() + " (" + account + ")" :
                    entry.getDecSystem();
        }
    }

    private static class CodeAdapter extends BaseAdapter {
        private final Context context;
        private final LayoutInflater inflater;
        private List<Code> codes = new ArrayList<Code>();

        CodeAdapter(Context context) {
            this.context = context;
            inflater = LayoutInflater.from(context);
        }

        void setEntries(List<PasswordEntry> entries, boolean showAccount) {
            codes = new ArrayList<Code>();
            for (PasswordEntry entry : entries) {
                codes.add(new Code(entry, showAccount));
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return codes.size();
        }

        @Override
        public Code getItem(int position) {
            return codes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return codes.get(position).entry.getId();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.totp_row, parent, false);
            }

            Code code = codes.get(position);
            TextView codeView = (TextView) convertView.findViewById(R.id.code);
            TextView secondsView = (TextView) convertView.findViewById(R.id.seconds_left);
            ((TextView) convertView.findViewById(R.id.title)).setText(code.title);

            if (code.totp == null) {
                codeView.setText(R.string.totp_invalid);
                codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                codeView.setTextColor(context.getColor(R.color.panel_text_secondary));
                secondsView.setVisibility(View.GONE);
                return convertView;
            }

            long now = System.currentTimeMillis();
            codeView.setText(Totp.format(code.totp.getCode(now)));
            codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
            codeView.setTextColor(context.getColor(R.color.totp_code));

            if (code.totp.getPeriod() != PERIOD) {
                secondsView.setText(context.getString(R.string.totp_seconds_left,
                        code.totp.getSecondsLeft(now)));
                secondsView.setVisibility(View.VISIBLE);
            } else {
                secondsView.setVisibility(View.GONE);
            }

            return convertView;
        }
    }
}
