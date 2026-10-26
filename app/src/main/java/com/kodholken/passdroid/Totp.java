package com.kodholken.passdroid;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238), the codes shown by authenticator
 * apps. The 2FA secret of an entry is either the base32 encoded key that
 * services show for manual setup, or an otpauth://totp/ URI as encoded in the
 * setup QR codes.
 */
public class Totp {
    private static final int [] POWERS_OF_TEN = {
        1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000
    };

    private final byte [] key;
    private final String algorithm;
    private final int digits;
    private final int period;  // Seconds each code is valid

    private Totp(byte [] key, String algorithm, int digits, int period) {
        this.key = key;
        this.algorithm = algorithm;
        this.digits = digits;
        this.period = period;
    }

    /**
     * Parse a 2FA secret.
     *
     * @param secret base32 encoded key or otpauth://totp/ URI. Spaces, dashes
     *               and padding in the key are ignored, as is the case.
     * @return null if the secret is not valid.
     */
    public static Totp parse(String secret) {
        if (secret == null) {
            return null;
        }

        String key = secret.trim();
        String algorithm = "HmacSHA1";
        int digits = 6;
        int period = 30;

        if (key.regionMatches(true, 0, "otpauth:", 0, 8)) {
            if (!key.regionMatches(true, 0, "otpauth://totp/", 0, 15)) {
                return null;  // Counter based codes (HOTP) are not supported
            }

            int query = key.indexOf('?');
            if (query < 0) {
                return null;
            }

            String parameters = key.substring(query + 1);
            key = null;
            for (String parameter : parameters.split("&")) {
                int separator = parameter.indexOf('=');
                if (separator < 0) {
                    continue;
                }

                String name = parameter.substring(0, separator).toLowerCase(Locale.US);
                String value = decode(parameter.substring(separator + 1));
                if (value == null) {
                    return null;
                }

                try {
                    if (name.equals("secret")) {
                        key = value;
                    } else if (name.equals("digits")) {
                        digits = Integer.parseInt(value);
                    } else if (name.equals("period")) {
                        period = Integer.parseInt(value);
                    } else if (name.equals("algorithm")) {
                        algorithm = getAlgorithm(value);
                        if (algorithm == null) {
                            return null;
                        }
                    }
                } catch (NumberFormatException ex) {
                    return null;
                }
            }

            if (key == null) {
                return null;
            }
        }

        if (digits < 6 || digits >= POWERS_OF_TEN.length || period < 1) {
            return null;
        }

        byte [] decoded = decodeBase32(key);
        if (decoded == null || decoded.length == 0) {
            return null;
        }

        return new Totp(decoded, algorithm, digits, period);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static String getAlgorithm(String name) {
        String upper = name.toUpperCase(Locale.US);
        if (upper.equals("SHA1")) {
            return "HmacSHA1";
        } else if (upper.equals("SHA256")) {
            return "HmacSHA256";
        } else if (upper.equals("SHA512")) {
            return "HmacSHA512";
        }

        return null;
    }

    /**
     * Decode base32 (RFC 4648) ignoring spaces, dashes, padding and case.
     *
     * @return null if the string contains other characters.
     */
    static byte [] decodeBase32(String s) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= '2' && c <= '7') {
                value = c - '2' + 26;
            } else if (c == ' ' || c == '-' || c == '=') {
                continue;
            } else {
                return null;
            }

            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output.write(buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }

        return output.toByteArray();
    }

    /**
     * @param time milliseconds since the epoch.
     * @return the code at the given time, with leading zeros.
     */
    public String getCode(long time) {
        long counter = time / 1000 / period;

        byte [] hash;
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
        } catch (GeneralSecurityException ex) {
            // All Android versions have these algorithms
            throw new IllegalStateException(ex);
        }

        int offset = hash[hash.length - 1] & 0xf;
        int binary = ((hash[offset] & 0x7f) << 24) |
                     ((hash[offset + 1] & 0xff) << 16) |
                     ((hash[offset + 2] & 0xff) << 8) |
                     (hash[offset + 3] & 0xff);

        String code = Integer.toString(binary % POWERS_OF_TEN[digits]);
        while (code.length() < digits) {
            code = "0" + code;
        }

        return code;
    }

    /**
     * Split a code in two groups for readability, e.g. "123 456".
     */
    public static String format(String code) {
        int half = code.length() / 2;
        return code.substring(0, half) + " " + code.substring(half);
    }

    /**
     * @return seconds each code is valid.
     */
    public int getPeriod() {
        return period;
    }

    /**
     * @param time milliseconds since the epoch.
     * @return seconds until the code changes, 1 to the period.
     */
    public int getSecondsLeft(long time) {
        return period - (int) (time / 1000 % period);
    }
}
