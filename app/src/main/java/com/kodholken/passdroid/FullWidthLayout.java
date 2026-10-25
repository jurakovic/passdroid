package com.kodholken.passdroid;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * A LinearLayout that takes all the width it may have, for the search field
 * in the action bar. The action bar adds an expanded action view with
 * wrap_content layout params, whatever the layout says, which made the field
 * only as wide as its hint. The platform SearchView measures itself the same
 * way.
 */
public class FullWidthLayout extends LinearLayout {
    public FullWidthLayout(Context context) {
        super(context);
    }

    public FullWidthLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                    MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
