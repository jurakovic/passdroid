package com.kodholken.passdroid;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;
import android.widget.TimePicker;

/**
 * The date of an entry, e.g. when the account was created. The date is stored
 * as local time without a time zone, "yyyy-MM-ddTHH:mm", so it shows the same
 * time after the time zone changes. It is shown in the format of the device.
 *
 * On the add and edit screens a DateField object lets the user pick the date
 * and time, or clear it.
 */
public class DateField {
    private static final String FORMAT = "yyyy-MM-dd'T'HH:mm";

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
     * Let the user pick a date and then the time. The date is set even if the
     * time picker is cancelled.
     */
    private void pickDate() {
        final Context context = view.getContext();
        final Calendar calendar = Calendar.getInstance();
        Date date = parse(value);
        if (date != null) {
            calendar.setTime(date);
        }

        new DatePickerDialog(context, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker picker, int year, int month, int day) {
                calendar.set(year, month, day);
                setValue(toValue(calendar.getTime()));

                new TimePickerDialog(context, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker picker, int hour, int minute) {
                        calendar.set(Calendar.HOUR_OF_DAY, hour);
                        calendar.set(Calendar.MINUTE, minute);
                        setValue(toValue(calendar.getTime()));
                    }
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                   DateFormat.is24HourFormat(context)).show();
            }
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
           calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * @return the value for the current date and time.
     */
    public static String now() {
        return toValue(new Date());
    }

    private static String toValue(Date date) {
        return new SimpleDateFormat(FORMAT, Locale.US).format(date);
    }

    private static Date parse(String value) {
        if (value == null) {
            return null;
        }

        SimpleDateFormat format = new SimpleDateFormat(FORMAT, Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(value, position);
        return position.getIndex() == value.length() ? date : null;
    }

    /**
     * @return the value in the date and time format of the device, or as it
     *         is if it is not a valid value, e.g. from an edited export file.
     */
    public static String format(Context context, String value) {
        Date date = parse(value);
        if (date == null) {
            return value;
        }

        return DateFormat.getMediumDateFormat(context).format(date) + " " +
               DateFormat.getTimeFormat(context).format(date);
    }
}
