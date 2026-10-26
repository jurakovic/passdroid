package com.kodholken.passdroid;

import java.io.IOException;
import java.io.InputStream;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;

/**
 * The scan button of the 2FA secret on the add and edit screens. It fills in
 * the 2FA secret from a QR code, scanned with the camera or read from an
 * image such as a screenshot. Empty system, email and username fields are
 * filled in from the QR code as well.
 *
 * The activity must pass its results to onActivityResult().
 */
public class QrScan {
    private static final int REQUEST_CAMERA = 0x51;
    private static final int REQUEST_IMAGE = 0x52;

    // Larger images are scaled down, e.g. photos. Screenshots are not.
    private static final int MAX_IMAGE_SIZE = 2500;

    private final Activity activity;

    public QrScan(Activity activity, View button) {
        this.activity = activity;

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSource();
            }
        });
    }

    /**
     * @return message explaining why a QR code can not be used.
     */
    public static int getInvalidMessage(String text) {
        if (text.regionMatches(true, 0, "otpauth-migration:", 0, 18)) {
            return R.string.qr_migration;
        }

        return R.string.qr_not_totp;
    }

    private void chooseSource() {
        CharSequence [] sources = {
            activity.getString(R.string.scan_with_camera),
            activity.getString(R.string.scan_from_image)
        };

        new AlertDialog.Builder(activity)
            .setTitle(R.string.scan_qr_code)
            .setItems(sources, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        activity.startActivityForResult(
                                new Intent(activity, ScanActivity.class),
                                REQUEST_CAMERA);
                    } else {
                        pickImage();
                    }
                }
            })
            .show();
    }

    private void pickImage() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }

        // The picker is another application so we give the user more time
        // than usual before being logged out.
        Session.getInstance().setExtendedTimeout(true);
        try {
            activity.startActivityForResult(intent, REQUEST_IMAGE);
        } catch (ActivityNotFoundException ex) {
            Session.getInstance().setExtendedTimeout(false);
            Utils.alertDialog(activity, activity.getString(R.string.failure),
                              activity.getString(R.string.no_image_picker));
        }
    }

    /**
     * @return true if the result was for a scan.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CAMERA && requestCode != REQUEST_IMAGE) {
            return false;
        }

        // This is called before onResume() so check for timeout here as well
        Session.clearTimeoutTimer(activity);
        if (!Session.getInstance().isLoggedIn()) {
            activity.finish();
            return true;
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            return true;
        }

        if (requestCode == REQUEST_CAMERA) {
            apply(data.getStringExtra(ScanActivity.EXTRA_TEXT));
        } else if (data.getData() != null) {
            readImage(data.getData());
        }

        return true;
    }

    /**
     * Look for a QR code in an image. Decoding a large image takes a moment
     * so it is done in the background.
     */
    private void readImage(final Uri uri) {
        final String [] text = new String[1];

        Utils.runWithProgress(activity, activity.getString(R.string.scan_reading_image),
                new Runnable() {
            @Override
            public void run() {
                text[0] = decodeImage(uri);
            }
        }, new Runnable() {
            @Override
            public void run() {
                if (text[0] == null) {
                    Utils.alertDialog(activity, activity.getString(R.string.failure),
                                      activity.getString(R.string.qr_not_found));
                } else {
                    apply(text[0]);
                }
            }
        });
    }

    private String decodeImage(Uri uri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            decode(uri, options);

            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) /
                   options.inSampleSize > MAX_IMAGE_SIZE) {
                options.inSampleSize *= 2;
            }

            Bitmap bitmap = decode(uri, options);
            if (bitmap == null) {
                return null;
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int [] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();

            return QrDecoder.decodeArgb(pixels, width, height);
        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private Bitmap decode(Uri uri, BitmapFactory.Options options) throws IOException {
        InputStream input = activity.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("Unable to open " + uri);
        }

        try {
            return BitmapFactory.decodeStream(input, null, options);
        } finally {
            input.close();
        }
    }

    private void apply(String text) {
        String secret = text != null ? Totp.getSecretToStore(text) : null;
        if (secret == null) {
            Utils.alertDialog(activity, activity.getString(R.string.failure),
                              activity.getString(text != null ?
                                      getInvalidMessage(text) :
                                      R.string.qr_not_totp));
            return;
        }

        ((EditText) activity.findViewById(R.id.totp)).setText(secret);

        fillIfEmpty(R.id.system, Totp.getIssuer(text));

        // The account is usually an email address
        String account = Totp.getAccount(text);
        if (account != null && account.contains("@")) {
            fillIfEmpty(R.id.email, account);
        } else {
            fillIfEmpty(R.id.username, account);
        }
    }

    private void fillIfEmpty(int id, String value) {
        EditText field = (EditText) activity.findViewById(id);
        if (value != null && field.getText().length() == 0) {
            field.setText(value);
        }
    }
}
