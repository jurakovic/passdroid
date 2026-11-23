package com.kodholken.passdroid;

import android.app.Activity;
import android.widget.TextView;

/**
 * Keeps the text of fields that hold secrets across a rotation, inside the
 * process.
 *
 * A field normally saves its text in the saved instance state of its
 * activity, and Android hands that state to the system process when the
 * activity stops, password fields included. So the fields that hold secrets
 * have android:saveEnabled="false", and their activities pass this copy
 * through onRetainNonConfigurationInstance() instead, which never leaves the
 * process. After the process has been killed nothing of the session is left
 * anyway: those screens finish themselves.
 */
public final class RetainedText {
    private RetainedText() {
    }

    /**
     * @return the text of the fields, in the order of the ids, or null if
     *         the activity has no such fields, e.g. because it finished
     *         before setting its content.
     */
    public static String [] collect(Activity activity, int... ids) {
        String [] texts = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            TextView field = (TextView) activity.findViewById(ids[i]);
            if (field == null) {
                return null;
            }
            texts[i] = field.getText().toString();
        }
        return texts;
    }

    /**
     * Put back the text that collect() returned before a rotation. Anything
     * else, e.g. null when the activity is created for the first time, is
     * ignored.
     */
    public static void restore(Activity activity, Object retained, int... ids) {
        if (!(retained instanceof String []) ||
            ((String []) retained).length != ids.length) {
            return;
        }

        String [] texts = (String []) retained;
        for (int i = 0; i < ids.length; i++) {
            TextView field = (TextView) activity.findViewById(ids[i]);
            if (field != null) {
                field.setText(texts[i]);
            }
        }
    }
}
