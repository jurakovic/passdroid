package com.kodholken.passdroid;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;
import android.widget.TimePicker;

/**
 * The date of an entry, e.g. when the account was created. The date is stored
 * as local time without a time zone, so it shows the same time after the time
 * zone changes: "yyyy-MM-ddTHH:mm", or "yyyy-MM-dd" for a date without a
 * time. It is shown in the format of the device, or as "yyyy-MM-dd HH:mm"
 * with the date format setting, without a time if it has none.
 *
 * On the add and edit screens a DateField object lets the user pick the date
 * and, optionally, the time, or clear it.
 */
public class DateField {
    /** The setting of the date format, "device" or ISO */
    public static final String PREFERENCE = "date_format";
    private static final String ISO = "iso";

    private static final String FORMAT = "yyyy-MM-dd'T'HH:mm";
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private final TextView view;
    private final View clearButton;
    private String value;

    public DateField(TextView view, View clearButton, String value) {
        this.view = view;
        this.clearButton = clearButton;

        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });

        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setValue("");
            }
        });

        setValue(value);
    }

    /**
     * @return the stored value, empty if no date is set.
     */
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value != null ? value : "";
        view.setText(format(view.getContext(), this.value));
        clearButton.setVisibility(this.value.length() > 0 ? View.VISIBLE :
                                                            View.INVISIBLE);
    }

    /**
     * Let the user pick a date and then the time. The time picker starts at
     * the time of the value, or at the current time if the value has none.
     * Its "No time" button stores the date alone, and cancelling it keeps the
     * time the value had, or no time.
     */
    private void pickDate() {
        final Context context = view.getContext();
        final Calendar calendar = Calendar.getInstance();
        final boolean hadTime = hasTime(value);
        Date date = parse(value);
        if (date != null) {
            Calendar old = Calendar.getInstance();
            old.setTime(date);
            calendar.set(old.get(Calendar.YEAR), old.get(Calendar.MONTH),
                         old.get(Calendar.DAY_OF_MONTH));
            if (hadTime) {
                calendar.set(Calendar.HOUR_OF_DAY, old.get(Calendar.HOUR_OF_DAY));
                calendar.set(Calendar.MINUTE, old.get(Calendar.MINUTE));
            }
        }

        new DatePickerDialog(context, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker picker, int year, int month, int day) {
                calendar.set(year, month, day);
                pickTime(context, calendar, hadTime);
            }
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
           calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(Context context, final Calendar calendar,
                          final boolean hadTime) {
        TimePickerDialog dialog = new TimePickerDialog(context, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker picker, int hour, int minute) {
                calendar.set(Calendar.HOUR_OF_DAY, hour);
                calendar.set(Calendar.MINUTE, minute);
                setValue(toValue(calendar.getTime(), true));
            }
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
           DateFormat.is24HourFormat(context));

        dialog.setButton(DialogInterface.BUTTON_NEUTRAL,
                         context.getString(R.string.date_no_time),
                         new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                setValue(toValue(calendar.getTime(), false));
            }
        });

        // Cancel, back or a tap outside: the picked date with the old time
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                setValue(toValue(calendar.getTime(), hadTime));
            }
        });

        dialog.show();
    }

    /**
     * @return the value for the current date and time.
     */
    public static String now() {
        return toValue(new Date(), true);
    }

    static String toValue(Date date, boolean withTime) {
        return new SimpleDateFormat(withTime ? FORMAT : DATE_FORMAT, Locale.US).format(date);
    }

    /**
     * @return the date of a value with or without a time, or null if it is
     *         not a valid value.
     */
    static Date parse(String value) {
        Date date = parse(value, FORMAT);
        return date != null ? date : parse(value, DATE_FORMAT);
    }

    /**
     * @return whether the value is a valid date with a time.
     */
    static boolean hasTime(String value) {
        return parse(value, FORMAT) != null;
    }

    private static Date parse(String value, String pattern) {
        if (value == null) {
            return null;
        }

        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(value, position);
        return position.getIndex() == value.length() ? date : null;
    }

    /**
     * @return the value in the date format of the setting, the date and time
     *         format of the device or ISO, the date alone if it has no time,
     *         or the value as it is if it is not a valid value, e.g. from an
     *         edited export file.
     */
    public static String format(Context context, String value) {
        String setting = PreferenceManager.getDefaultSharedPreferences(context).
            getString(PREFERENCE, "device");
        if (ISO.equals(setting)) {
            return formatIso(value);
        }

        Date date = parse(value);
        if (date == null) {
            return value;
        }

        String text = DateFormat.getMediumDateFormat(context).format(date);
        if (hasTime(value)) {
            text += " " + DateFormat.getTimeFormat(context).format(date);
        }
        return text;
    }

    /**
     * @return the value as "yyyy-MM-dd HH:mm", with a 24 hour clock, or
     *         "yyyy-MM-dd" if it has no time, or the value as it is if it is
     *         not a valid value.
     */
    static String formatIso(String value) {
        Date date = parse(value);
        if (date == null) {
            return value;
        }

        String pattern = hasTime(value) ? "yyyy-MM-dd HH:mm" : DATE_FORMAT;
        return new SimpleDateFormat(pattern, Locale.US).format(date);
    }
}
