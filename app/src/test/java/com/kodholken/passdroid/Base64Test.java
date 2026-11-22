package com.kodholken.passdroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class Base64Test {
    @Test
    public void roundTrip() throws Exception {
        for (int length = 1; length <= 4; length++) {
            byte [] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (0xfb - i);
            }
            assertArrayEquals(data, Base64.decode(Base64.encode(data)));
        }
    }

    @Test
    public void decodeRejectsEmptyAndTruncatedInput() {
        for (String input : new String[] { "", "QQ", "QUJD=" }) {
            try {
                Base64.decode(input);
                fail("Decoded \"" + input + "\"");
            } catch (Base64Exception ex) {
                // Expected
            }
        }
    }
}
