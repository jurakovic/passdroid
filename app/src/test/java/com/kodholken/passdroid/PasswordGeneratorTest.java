package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordGeneratorTest {
    @Test
    public void lengths() {
        for (int length : new int [] { 1, 16, 99 }) {
            assertEquals(length, PasswordGenerator.generate(length, 0).length());
        }
    }

    @Test
    public void charactersAreFromTheChosenSet() {
        for (int set = 0; set < 4; set++) {
            String charset = PasswordGenerator.getCharset(set);
            for (char c : PasswordGenerator.generate(500, set).toCharArray()) {
                assertTrue(set + ": " + c, charset.indexOf(c) >= 0);
            }
        }
    }

    @Test
    public void sets() {
        // Letters and digits, letters, digits, and those with 17 symbols
        assertEquals(62, PasswordGenerator.getCharset(0).length());
        assertEquals(52, PasswordGenerator.getCharset(1).length());
        assertEquals("0123456789", PasswordGenerator.getCharset(2));
        assertEquals(79, PasswordGenerator.getCharset(3).length());
    }

    @Test
    public void anUnknownSetIsLettersAndDigits() {
        assertEquals(PasswordGenerator.getCharset(0), PasswordGenerator.getCharset(-1));
        assertEquals(PasswordGenerator.getCharset(0), PasswordGenerator.getCharset(4));
    }

    @Test
    public void passwordsDiffer() {
        assertNotEquals(PasswordGenerator.generate(16, 0), PasswordGenerator.generate(16, 0));
    }
}
