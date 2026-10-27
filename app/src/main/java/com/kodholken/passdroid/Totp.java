package com.kodholken.passdroid;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
            OtpAuthUri uri = OtpAuthUri.parse(key);
            if (uri == null) {
                return null;  // Counter based codes (HOTP) are not supported
            }

            key = uri.parameters.get("secret");
            if (key == null) {
                return null;
            }

            try {
                if (uri.parameters.containsKey("digits")) {
                    digits = Integer.parseInt(uri.parameters.get("digits"));
                }
                if (uri.parameters.containsKey("period")) {
                    period = Integer.parseInt(uri.parameters.get("period"));
                }
            } catch (NumberFormatException ex) {
                return null;
            }

            if (uri.parameters.containsKey("algorithm")) {
                algorithm = getAlgorithm(uri.parameters.get("algorithm"));
                if (algorithm == null) {
                    return null;
                }
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

    /**
     * Get the 2FA secret to store for a scanned QR code. For an otpauth URI
     * with the usual settings (SHA1, 6 digits, 30 seconds) only the key is
     * kept, the same key that services show for manual setup.
     *
     * @return null if the text is not a valid 2FA secret.
     */
    public static String getSecretToStore(String text) {
        Totp totp = parse(text);
        if (totp == null) {
            return null;
        }

        String trimmed = text.trim();
        OtpAuthUri uri = OtpAuthUri.parse(trimmed);
        if (uri != null && totp.algorithm.equals("HmacSHA1") &&
            totp.digits == 6 && totp.period == 30) {
            return uri.parameters.get("secret").trim();
        }

        return trimmed;
    }

    /**
     * Get an otpauth URI for a 2FA secret, e.g. for a QR code that an
     * authenticator app can scan. A secret that is a URI is returned as it
     * is.
     *
     * @param issuer  the service, e.g. "GitHub", or null.
     * @param account e.g. "alice@example.com", or null.
     * @return null if the secret is not valid.
     */
    public static String toUri(String secret, String issuer, String account) {
        if (parse(secret) == null) {
            return null;
        }

        String trimmed = secret.trim();
        if (trimmed.regionMatches(true, 0, "otpauth:", 0, 8)) {
            return trimmed;
        }

        // The key as services show it, without spaces, dashes and padding
        String key = trimmed.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);

        issuer = issuer != null ? issuer.trim() : "";
        account = account != null ? account.trim() : "";
        String label = issuer.length() > 0 && account.length() > 0 ?
                       encode(issuer) + ":" + encode(account) :
                       encode(issuer + account);

        return OtpAuthUri.PREFIX + label + "?secret=" + key +
               (issuer.length() > 0 ? "&issuer=" + encode(issuer) : "");
    }

    /**
     * Percent encode a part of a URI, with %20 for spaces.
     */
    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);  // UTF-8 is always supported
        }
    }

    /**
     * @return the service of an otpauth URI, e.g. "GitHub", or null.
     */
    public static String getIssuer(String text) {
        OtpAuthUri uri = OtpAuthUri.parse(text);
        if (uri == null) {
            return null;
        }

        String issuer = uri.parameters.get("issuer");
        if (issuer != null && issuer.trim().length() > 0) {
            return issuer.trim();
        }

        // The label may start with the issuer, "Issuer:account"
        int separator = uri.label.indexOf(':');
        return separator > 0 ? uri.label.substring(0, separator).trim() : null;
    }

    /**
     * @return the account of an otpauth URI, e.g. "alice@example.com", or
     *         null.
     */
    public static String getAccount(String text) {
        OtpAuthUri uri = OtpAuthUri.parse(text);
        if (uri == null) {
            return null;
        }

        int separator = uri.label.indexOf(':');
        String account = uri.label.substring(separator + 1).trim();
        return account.length() > 0 ? account : null;
    }

    /**
     * The decoded parts of an otpauth://totp/LABEL?PARAMETERS URI, see
     * https://github.com/google/google-authenticator/wiki/Key-Uri-Format
     */
    private static class OtpAuthUri {
        private static final String PREFIX = "otpauth://totp/";

        final String label;
        final Map<String, String> parameters;  // By lower case name

        private OtpAuthUri(String label, Map<String, String> parameters) {
            this.label = label;
            this.parameters = parameters;
        }

        /**
         * @return null if the text is not a valid otpauth://totp/ URI.
         */
        static OtpAuthUri parse(String text) {
            if (text == null) {
                return null;
            }

            String uri = text.trim();
            if (!uri.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                return null;
            }

            int query = uri.indexOf('?');
            if (query < 0) {
                query = uri.length();
            }

            // A '+' in the label is not a space, e.g. "alice+2fa@example.com"
            String label = decode(uri.substring(PREFIX.length(), query).replace("+", "%2B"));
            if (label == null) {
                return null;
            }

            Map<String, String> parameters = new HashMap<String, String>();
            if (query < uri.length()) {
                for (String parameter : uri.substring(query + 1).split("&")) {
                    int separator = parameter.indexOf('=');
                    if (separator < 0) {
                        continue;
                    }

                    String value = decode(parameter.substring(separator + 1));
                    if (value == null) {
                        return null;
                    }

                    parameters.put(parameter.substring(0, separator).toLowerCase(Locale.US),
                                   value);
                }
            }

            return new OtpAuthUri(label, parameters);
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException ex) {
                return null;
            }
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
