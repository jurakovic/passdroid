package com.kodholken.passdroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class TotpTest {
    // The keys of the RFC 6238 test vectors, base32 encoded
    private static final String SHA1_KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String SHA256_KEY =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA====";
    private static final String SHA512_KEY =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA=";

    // Seconds since the epoch and the expected 8 digit codes for SHA1,
    // SHA256 and SHA512 from RFC 6238 appendix B
    private static final Object [][] VECTORS = {
        { 59L,          "94287082", "46119246", "90693936" },
        { 1111111109L,  "07081804", "68084774", "25091201" },
        { 1111111111L,  "14050471", "67062674", "99943326" },
        { 1234567890L,  "89005924", "91819424", "93441116" },
        { 2000000000L,  "69279037", "90698825", "38618901" },
        { 20000000000L, "65353130", "77737706", "47863826" },
    };

    private static Totp uri(String key, String algorithm) {
        Totp totp = Totp.parse("otpauth://totp/Example:alice@example.com?secret=" +
                               key + "&issuer=Example&algorithm=" + algorithm +
                               "&digits=8&period=30");
        assertNotNull(totp);
        return totp;
    }

    @Test
    public void rfc6238Vectors() {
        Totp sha1 = uri(SHA1_KEY, "SHA1");
        Totp sha256 = uri(SHA256_KEY, "SHA256");
        Totp sha512 = uri(SHA512_KEY, "SHA512");

        for (Object [] vector : VECTORS) {
            long time = (Long) vector[0] * 1000;
            assertEquals(vector[1], sha1.getCode(time));
            assertEquals(vector[2], sha256.getCode(time));
            assertEquals(vector[3], sha512.getCode(time));
        }
    }

    @Test
    public void plainKeyUsesSixDigitsAndThirtySeconds() {
        Totp totp = Totp.parse(SHA1_KEY);
        assertNotNull(totp);

        // The last 6 digits of the 8 digit codes
        assertEquals("287082", totp.getCode(59000));
        assertEquals("081804", totp.getCode(1111111109000L));

        assertEquals(1, totp.getSecondsLeft(59000));
        assertEquals(30, totp.getSecondsLeft(60000));
        assertEquals(30, totp.getSecondsLeft(60999));
        assertEquals(29, totp.getSecondsLeft(61000));
    }

    @Test
    public void keyFormattingIsIgnored() {
        Totp totp = Totp.parse("  gezd gnbv-gy3t qojq gezd gnbv gy3t qojq  ");
        assertNotNull(totp);
        assertEquals("287082", totp.getCode(59000));
    }

    @Test
    public void uriParameters() {
        // Lower case scheme and parameter names, encoded label, default
        // algorithm, digits and period
        Totp totp = Totp.parse("OTPAUTH://TOTP/ACME%20Co:john@example.com?SECRET=" +
                               SHA1_KEY + "&issuer=ACME%20Co");
        assertNotNull(totp);
        assertEquals("287082", totp.getCode(59000));

        totp = Totp.parse("otpauth://totp/x?secret=" + SHA1_KEY + "&period=60");
        assertNotNull(totp);
        assertEquals(60, totp.getSecondsLeft(60000));
    }

    @Test
    public void invalidSecrets() {
        assertNull(Totp.parse(null));
        assertNull(Totp.parse(""));
        assertNull(Totp.parse("   "));
        assertNull(Totp.parse("not base32!"));
        assertNull(Totp.parse("ABC1"));  // 1 is not in the alphabet
        assertNull(Totp.parse("otpauth://hotp/x?secret=" + SHA1_KEY + "&counter=1"));
        assertNull(Totp.parse("otpauth://totp/x"));
        assertNull(Totp.parse("otpauth://totp/x?issuer=x"));
        assertNull(Totp.parse("otpauth://totp/x?secret=" + SHA1_KEY + "&algorithm=MD5"));
        assertNull(Totp.parse("otpauth://totp/x?secret=" + SHA1_KEY + "&digits=4"));
        assertNull(Totp.parse("otpauth://totp/x?secret=" + SHA1_KEY + "&digits=x"));
        assertNull(Totp.parse("otpauth://totp/x?secret=" + SHA1_KEY + "&period=0"));
    }

    @Test
    public void secretToStore() {
        String key = "JBSWY3DPEHPK3PXP";

        // Usual settings, only the key is kept
        assertEquals(key, Totp.getSecretToStore(
                "otpauth://totp/ACME%20Co:alice@example.com?secret=" + key +
                "&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30"));
        assertEquals(key, Totp.getSecretToStore(" otpauth://totp/x?secret=" + key + " "));

        // Other settings need the whole URI
        String uri = "otpauth://totp/x?secret=" + key + "&digits=8";
        assertEquals(uri, Totp.getSecretToStore(uri));
        uri = "otpauth://totp/x?secret=" + key + "&algorithm=SHA256";
        assertEquals(uri, Totp.getSecretToStore(uri));

        // A plain key is kept as it is
        assertEquals("jbsw y3dp ehpk 3pxp", Totp.getSecretToStore(" jbsw y3dp ehpk 3pxp "));

        assertNull(Totp.getSecretToStore("https://example.com/"));
        assertNull(Totp.getSecretToStore("otpauth-migration://offline?data=CjEKCkhlbGxv"));
        assertNull(Totp.getSecretToStore("otpauth://hotp/x?secret=" + key));
    }

    @Test
    public void issuerAndAccount() {
        String uri = "otpauth://totp/ACME%20Co:alice+2fa@example.com?secret=JBSWY3DPEHPK3PXP" +
                     "&issuer=ACME+Corp";
        assertEquals("ACME Corp", Totp.getIssuer(uri));
        assertEquals("alice+2fa@example.com", Totp.getAccount(uri));

        // The issuer from the label
        uri = "otpauth://totp/GitHub:%20alice?secret=JBSWY3DPEHPK3PXP";
        assertEquals("GitHub", Totp.getIssuer(uri));
        assertEquals("alice", Totp.getAccount(uri));

        // No issuer
        uri = "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP";
        assertNull(Totp.getIssuer(uri));
        assertEquals("alice", Totp.getAccount(uri));

        assertNull(Totp.getIssuer("JBSWY3DPEHPK3PXP"));
        assertNull(Totp.getAccount("JBSWY3DPEHPK3PXP"));
        assertNull(Totp.getAccount("otpauth://totp/?secret=JBSWY3DPEHPK3PXP"));
    }

    @Test
    public void base32() {
        assertArrayEquals("12345678901234567890".getBytes(StandardCharsets.US_ASCII),
                          Totp.decodeBase32(SHA1_KEY));
        assertArrayEquals("f".getBytes(StandardCharsets.US_ASCII),
                          Totp.decodeBase32("MY======"));
        assertArrayEquals("foobar".getBytes(StandardCharsets.US_ASCII),
                          Totp.decodeBase32("MZXW6YTBOI======"));
    }

    @Test
    public void uriForQrCode() {
        String uri = Totp.toUri("jbsw y3dp-ehpk 3pxp==", "Git Hub", "alice+test@example.com");
        assertEquals("otpauth://totp/Git%20Hub:alice%2Btest%40example.com" +
                     "?secret=JBSWY3DPEHPK3PXP&issuer=Git%20Hub", uri);

        // An authenticator app reads the same issuer, account and codes
        assertEquals("Git Hub", Totp.getIssuer(uri));
        assertEquals("alice+test@example.com", Totp.getAccount(uri));
        assertEquals("JBSWY3DPEHPK3PXP", Totp.getSecretToStore(uri));
        long time = 1700000000000L;
        assertEquals(Totp.parse("JBSWY3DPEHPK3PXP").getCode(time),
                     Totp.parse(uri).getCode(time));
    }

    @Test
    public void uriWithoutAccount() {
        assertEquals("otpauth://totp/GitHub?secret=JBSWY3DPEHPK3PXP&issuer=GitHub",
                     Totp.toUri("JBSWY3DPEHPK3PXP", "GitHub", ""));
        assertEquals("otpauth://totp/GitHub?secret=JBSWY3DPEHPK3PXP&issuer=GitHub",
                     Totp.toUri("JBSWY3DPEHPK3PXP", "GitHub", null));
    }

    @Test
    public void uriSecretIsKept() {
        // E.g. with settings other than the defaults
        String uri = "otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&digits=8&issuer=A";
        assertEquals(uri, Totp.toUri("  " + uri + " ", "Other", "other"));
    }

    @Test
    public void noUriForInvalidSecret() {
        assertNull(Totp.toUri("not base32!", "GitHub", "alice"));
        assertNull(Totp.toUri("", "GitHub", "alice"));
        assertNull(Totp.toUri(null, "GitHub", "alice"));
    }
}
