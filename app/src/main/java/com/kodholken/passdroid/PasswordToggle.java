package com.kodholken.passdroid;

import java.util.Arrays;

import android.graphics.Rect;
import android.text.method.TransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

/**
 * Shows or hides the password in a TextView or EditText with an "eye"
 * button. A hidden password is shown as asterisks. Only the displayed text
 * changes, getText() still returns the password.
 */
public class PasswordToggle {
    private final TextView passwordView;
    private final ImageButton button;
    // Used for a visible password, e.g. the single line transformation
    private final TransformationMethod visibleTransformation;
    private boolean visible;

    public PasswordToggle(TextView passwordView, ImageButton button,
                          boolean visible) {
        this.passwordView = passwordView;
        this.button = button;
        visibleTransformation = passwordView.getTransformationMethod();

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVisible(!PasswordToggle.this.visible);
            }
        });

        setVisible(visible);
    }

    public void setVisible(boolean visible) {
        this.visible = visible;

        // Changing the transformation moves the cursor to the start
        int selection = passwordView.getSelectionEnd();
        passwordView.setTransformationMethod(visible ? visibleTransformation :
                                                       Asterisks.INSTANCE);
        if (passwordView instanceof EditText && selection >= 0) {
            ((EditText) passwordView).setSelection(selection);
        }

        // The button shows what pressing it does
        button.setImageResource(visible ? R.drawable.ic_visibility_off :
                                          R.drawable.ic_visibility);
        button.setContentDescription(button.getContext().getString(
                visible ? R.string.hide_password : R.string.show_password));
    }

    private static class Asterisks implements TransformationMethod {
        static final Asterisks INSTANCE = new Asterisks();

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            return new Masked(source);
        }

        @Override
        public void onFocusChanged(View view, CharSequence sourceText,
                                   boolean focused, int direction,
                                   Rect previouslyFocusedRect) {
        }
    }

    /**
     * Asterisks in place of the characters of a text. The length follows the
     * text, which an EditText changes while the user types.
     */
    private static class Masked implements CharSequence {
        private final CharSequence source;

        Masked(CharSequence source) {
            this.source = source;
        }

        @Override
        public int length() {
            return source.length();
        }

        @Override
        public char charAt(int index) {
            return '*';
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            char [] chars = new char[end - start];
            Arrays.fill(chars, '*');
            return new String(chars);
        }

        @Override
        public String toString() {
            return subSequence(0, length()).toString();
        }
    }
}
