package com.kodholken.passdroid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The font settings for the values of the entries, on the entry screens and
 * in the lists:
 * - The font: the default font or the monospace font Roboto Mono, which makes
 *   characters like 0 and O easier to tell apart. Roboto Mono is licensed
 *   under the SIL Open Font License, see assets/fonts/RobotoMono-OFL.txt.
 * - The size: small, normal or large, relative to the size of each value.
 */
public class FontSetting {
    public static final String PREFERENCE = "font";
    public static final String SIZE_PREFERENCE = "font_size";

    private static final String MONOSPACE = "monospace";
    private static final String SMALL = "small";
    private static final String LARGE = "large";

    private static Typeface regular;
    private static Typeface bold;

    public static boolean isMonospace(Context context) {
        return MONOSPACE.equals(PreferenceManager.getDefaultSharedPreferences(context).
                                getString(PREFERENCE, "default"));
    }

    /**
     * @return factor for the text sizes of the values, 1 for the normal size.
     */
    public static float getScale(Context context) {
        String size = PreferenceManager.getDefaultSharedPreferences(context).
                      getString(SIZE_PREFERENCE, "normal");
        if (SMALL.equals(size)) {
            return 0.85f;
        } else if (LARGE.equals(size)) {
            return 1.2f;
        }

        return 1;
    }

    /**
     * Use the selected font and size for the views with the given ids, see
     * apply(TextView).
     */
    public static void apply(Activity activity, int... ids) {
        for (int id : ids) {
            apply((TextView) activity.findViewById(id));
        }
    }

    /**
     * Use the selected font and size for a view. It must only be called once
     * for a view, since it scales the current size.
     */
    public static void apply(TextView view) {
        applyFont(view);

        float scale = getScale(view.getContext());
        if (scale != 1) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getTextSize() * scale);
        }
    }

    /**
     * Use the selected font for a view, without changing the size. Bold text
     * stays bold.
     */
    public static void applyFont(TextView view) {
        if (!isMonospace(view.getContext())) {
            return;
        }

        Typeface current = view.getTypeface();
        boolean isBold = current != null && current.isBold();
        view.setTypeface(getMonospace(view.getContext(), isBold));
    }

    private static synchronized Typeface getMonospace(Context context, boolean isBold) {
        if (regular == null) {
            regular = Typeface.createFromAsset(context.getAssets(),
                                               "fonts/RobotoMono-Regular.ttf");
            bold = Typeface.createFromAsset(context.getAssets(),
                                            "fonts/RobotoMono-Bold.ttf");
        }

        return isBold ? bold : regular;
    }
}
