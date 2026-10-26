package com.kodholken.passdroid;

import java.util.EnumMap;
import java.util.Map;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * Finds and decodes a QR code in an image with ZXing.
 */
public class QrDecoder {
    /**
     * @param pixels ARGB pixels of an image, e.g. from Bitmap.getPixels().
     * @return the text of the QR code, or null if none was found.
     */
    public static String decodeArgb(int [] pixels, int width, int height) {
        return decode(new RGBLuminanceSource(width, height, pixels));
    }

    /**
     * @param luminance one byte per pixel, e.g. the Y plane of a camera frame.
     * @return the text of the QR code, or null if none was found.
     */
    public static String decodeLuminance(byte [] luminance, int width, int height) {
        return decode(new PlanarYUVLuminanceSource(luminance, width, height,
                                                   0, 0, width, height, false));
    }

    private static String decode(LuminanceSource source) {
        String text = decodeOnce(source);
        if (text == null) {
            // Light on dark QR codes, e.g. on a page with a dark theme
            text = decodeOnce(source.invert());
        }

        return text;
    }

    private static String decodeOnce(LuminanceSource source) {
        Map<DecodeHintType, Object> hints =
            new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        try {
            return new QRCodeReader().decode(
                    new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
        } catch (ReaderException ex) {
            return null;
        }
    }
}
