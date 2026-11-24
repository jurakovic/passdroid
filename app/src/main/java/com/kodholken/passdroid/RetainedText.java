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
    private final String [] texts;
    // Another object the activity keeps across the rotation, or null
    private final Object extra;

    private RetainedText(String [] texts, Object extra) {
        this.texts = texts;
        this.extra = extra;
    }

    /**
     * @return the text of the fields, for onRetainNonConfigurationInstance(),
     *         or null if the activity has no such fields, e.g. because it
     *         finished before setting its content.
     */
    public static RetainedText collect(Activity activity, int... ids) {
        return collect(activity, ids, null);
    }

    /**
     * @param extra another object to keep across the rotation, which
     *              restore() returns.
     * @return the text of the fields and the object, for
     *         onRetainNonConfigurationInstance(), or null if the activity has
     *         no such fields.
     */
    public static RetainedText collect(Activity activity, int [] ids,
                                       Object extra) {
        String [] texts = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            TextView field = (TextView) activity.findViewById(ids[i]);
            if (field == null) {
                return null;
            }
            texts[i] = field.getText().toString();
        }
        return new RetainedText(texts, extra);
    }

    /**
     * Put back the text that collect() returned before a rotation. Anything
     * else, e.g. null when the activity is created for the first time, is
     * ignored.
     *
     * @return the extra object given to collect(), or null.
     */
    public static Object restore(Activity activity, Object retained, int... ids) {
        if (!(retained instanceof RetainedText) ||
            ((RetainedText) retained).texts.length != ids.length) {
            return null;
        }

        RetainedText kept = (RetainedText) retained;
        for (int i = 0; i < ids.length; i++) {
            TextView field = (TextView) activity.findViewById(ids[i]);
            if (field != null) {
                field.setText(kept.texts[i]);
            }
        }
        return kept.extra;
    }
}
