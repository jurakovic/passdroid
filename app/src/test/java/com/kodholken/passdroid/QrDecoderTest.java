package com.kodholken.passdroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Random;

import org.junit.Test;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QrDecoderTest {
    private static final String URI =
        "otpauth://totp/ACME%20Co:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co";

    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffffffff;

    /**
     * An image of the QR code for the text in the middle of a larger image.
     */
    private static int [] createImage(String text, int width, int height,
                                      int foreground, int background) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300);
        int [] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, background);

        int left = (width - matrix.getWidth()) / 2;
        int top = (height - matrix.getHeight()) / 2;
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                pixels[(top + y) * width + left + x] = matrix.get(x, y) ? foreground : background;
            }
        }

        return pixels;
    }

    @Test
    public void decodesArgbImage() throws Exception {
        int [] pixels = createImage(URI, 1080, 2340, BLACK, WHITE);
        assertEquals(URI, QrDecoder.decodeArgb(pixels, 1080, 2340));
    }

    @Test
    public void decodesLightOnDark() throws Exception {
        int [] pixels = createImage(URI, 600, 800, WHITE, 0xff202124);
        assertEquals(URI, QrDecoder.decodeArgb(pixels, 600, 800));
    }

    @Test
    public void decodesLuminance() throws Exception {
        int [] pixels = createImage(URI, 640, 480, BLACK, WHITE);
        byte [] luminance = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            luminance[i] = (byte) (pixels[i] & 0xff);
        }
        assertEquals(URI, QrDecoder.decodeLuminance(luminance, 640, 480));
    }

    @Test
    public void noQrCode() {
        int [] pixels = new int[400 * 300];
        Random random = new Random(1);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextBoolean() ? BLACK : WHITE;
        }
        assertNull(QrDecoder.decodeArgb(pixels, 400, 300));
        assertNull(QrDecoder.decodeArgb(new int[400 * 300], 400, 300));
    }
}
