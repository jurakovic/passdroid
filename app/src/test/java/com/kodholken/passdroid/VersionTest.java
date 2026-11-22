package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class VersionTest {
    @Test
    public void equalVersionsHaveEqualHashCodes() {
        Version a = new Version(2, 3);
        Version b = Version.parse("2.3");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Version(3, 2));
    }
}
