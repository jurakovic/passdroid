package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PasswordStrengthTest {
    @Test
    public void bits() {
        // Letters and digits
        assertEquals(59, PasswordStrength.getBits(10, 62));
        assertEquals(83, PasswordStrength.getBits(14, 62));
        assertEquals(95, PasswordStrength.getBits(16, 62));
        // Digits only
        assertEquals(53, PasswordStrength.getBits(16, 10));
        // Letters, digits and the 17 symbols
        assertEquals(100, PasswordStrength.getBits(16, 79));

        assertEquals(0, PasswordStrength.getBits(0, 62));
        assertEquals(0, PasswordStrength.getBits(10, 1));
    }

    @Test
    public void ratings() {
        assertEquals(PasswordStrength.VERY_WEAK, PasswordStrength.getRating(0));
        assertEquals(PasswordStrength.VERY_WEAK, PasswordStrength.getRating(39));
        assertEquals(PasswordStrength.WEAK, PasswordStrength.getRating(40));
        assertEquals(PasswordStrength.WEAK, PasswordStrength.getRating(64));
        assertEquals(PasswordStrength.FAIR, PasswordStrength.getRating(65));
        assertEquals(PasswordStrength.FAIR, PasswordStrength.getRating(74));
        assertEquals(PasswordStrength.GOOD, PasswordStrength.getRating(75));
        assertEquals(PasswordStrength.GOOD, PasswordStrength.getRating(99));
        assertEquals(PasswordStrength.EXCELLENT, PasswordStrength.getRating(100));
        assertEquals(PasswordStrength.EXCELLENT, PasswordStrength.getRating(600));
    }
}
