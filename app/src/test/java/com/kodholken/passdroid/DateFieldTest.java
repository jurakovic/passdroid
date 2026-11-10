package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;

import org.junit.Test;

public class DateFieldTest {
    private static Date date(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute);
        return calendar.getTime();
    }

    @Test
    public void valuesWithAndWithoutTime() {
        assertEquals(date(2026, 10, 25, 14, 30), DateField.parse("2026-10-25T14:30"));
        assertTrue(DateField.hasTime("2026-10-25T14:30"));

        assertEquals(date(2026, 10, 25, 0, 0), DateField.parse("2026-10-25"));
        assertFalse(DateField.hasTime("2026-10-25"));

        // Midnight is a time like any other
        assertNotNull(DateField.parse("2026-10-25T00:00"));
        assertTrue(DateField.hasTime("2026-10-25T00:00"));
    }

    @Test
    public void invalidValues() {
        for (String value : new String [] {
                "2026-10-25T", "2026-13-01", "2026-02-30", "25.10.2026",
                "2026-10-25 14:30", "2026-10-25T14:30:00", "", null }) {
            assertNull(value, DateField.parse(value));
            assertFalse(value, DateField.hasTime(value));
        }
    }

    @Test
    public void isoFormat() {
        assertEquals("2026-10-25 14:30", DateField.formatIso("2026-10-25T14:30"));
        assertEquals("2026-10-25 00:00", DateField.formatIso("2026-10-25T00:00"));
        assertEquals("2026-10-25", DateField.formatIso("2026-10-25"));

        // Invalid values come back as they are
        assertEquals("25.10.2026", DateField.formatIso("25.10.2026"));
        assertEquals("", DateField.formatIso(""));
    }

    @Test
    public void toValue() {
        Date date = date(2026, 1, 5, 9, 7);
        assertEquals("2026-01-05T09:07", DateField.toValue(date, true));
        assertEquals("2026-01-05", DateField.toValue(date, false));
        assertEquals(date, DateField.parse(DateField.toValue(date, true)));
    }
}
