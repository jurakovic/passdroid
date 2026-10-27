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
    /**
     * Provides a hidden value when it is shown, e.g. by decrypting it.
     */
    public interface Source {
        String get();
    }

    // Shown in place of a value from a Source, whatever its length
    private static final String HIDDEN = "********";

    private final TextView passwordView;
    private final ImageButton button;
    private final Source source;  // null if the view holds the value
    // Used for a visible password, e.g. the single line transformation
    private final TransformationMethod visibleTransformation;
    private boolean visible;

    public PasswordToggle(TextView passwordView, ImageButton button,
                          boolean visible) {
        this(passwordView, button, visible, null);
    }

    /**
     * Toggle a value that is only in the view while it is shown. It is
     * hidden to begin with.
     */
    public PasswordToggle(TextView passwordView, ImageButton button,
                          Source source) {
        this(passwordView, button, false, source);
    }

    private PasswordToggle(TextView passwordView, ImageButton button,
                           boolean visible, Source source) {
        this.passwordView = passwordView;
        this.button = button;
        this.source = source;
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

        if (source != null) {
            passwordView.setText(visible ? source.get() : HIDDEN);
        } else {
            // Changing the transformation moves the cursor to the start
            int selection = passwordView.getSelectionEnd();
            passwordView.setTransformationMethod(visible ? visibleTransformation :
                                                           Asterisks.INSTANCE);
            if (passwordView instanceof EditText && selection >= 0) {
                ((EditText) passwordView).setSelection(selection);
            }
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
