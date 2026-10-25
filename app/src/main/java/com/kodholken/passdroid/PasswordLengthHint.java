package com.kodholken.passdroid;

import android.content.res.Resources;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

/**
 * Shows the minimum length of a new master password below its field, and how
 * many characters are still missing while it is typed.
 *
 * There is deliberately no strength rating: PasswordStrength rates random
 * passwords, and would rate a typed one like "Password1234!" as excellent.
 */
public class PasswordLengthHint {
    private final TextView passwordView;
    private final TextView hintView;

    public PasswordLengthHint(TextView passwordView, TextView hintView) {
        this.passwordView = passwordView;
        this.hintView = hintView;

        passwordView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                update();
            }
        });
        update();
    }

    private void update() {
        // Counted like MasterKey.isTooShort(), without copying the password
        // to a String
        CharSequence password = passwordView.getText();
        int length = Character.codePointCount(password, 0, password.length());
        int min = MasterKey.MIN_PASSWORD_LENGTH;
        int missing = min - length;

        Resources res = hintView.getResources();
        if (length == 0) {
            hintView.setText(res.getQuantityString(R.plurals.master_password_hint,
                                                   min, min));
        } else if (missing > 0) {
            hintView.setText(res.getQuantityString(R.plurals.master_password_hint_missing,
                                                   min, min, missing));
        } else {
            hintView.setText(res.getQuantityString(R.plurals.master_password_hint_done,
                                                   min, min));
        }
    }
}
