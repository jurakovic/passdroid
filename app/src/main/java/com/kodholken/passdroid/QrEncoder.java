package com.kodholken.passdroid;

import java.util.EnumMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * Creates QR codes with ZXing, e.g. to move a 2FA secret to an authenticator
 * app. See QrDecoder for reading them.
 */
public class QrEncoder {
    private static final int BLACK = 0xff000000;
    private static final int WHITE = 0xffffffff;

    /**
     * @param size width and height in pixels.
     * @return ARGB pixels of a black on white QR code with a quiet zone, e.g.
     *         for Bitmap.createBitmap(), or null if the text does not fit.
     */
    public static int [] encodeArgb(String text, int size) {
        Map<EncodeHintType, Object> hints =
            new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 4);  // The standard quiet zone
        // Only when needed, since it adds a marker some scanners do not know
        if (!text.matches("\\A\\p{ASCII}*\\z")) {
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        }

        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE,
                                               size, size, hints);
        } catch (WriterException ex) {
            ex.printStackTrace();
            return null;
        }

        // Larger if the size is too small for the QR code
        if (matrix.getWidth() != size || matrix.getHeight() != size) {
            return null;
        }

        int [] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? BLACK : WHITE;
            }
        }

        return pixels;
    }
}
