package com.kodholken.passdroid;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Scans a QR code with the camera. The result is the text of the first QR
 * code that contains a 2FA secret, in EXTRA_TEXT.
 *
 * CameraX needs a LifecycleOwner. The activity implements it itself so that
 * it keeps the session handling of TimeoutActivity.
 */
public class ScanActivity extends TimeoutActivity implements LifecycleOwner {
    public static final String EXTRA_TEXT = "text";

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private ExecutorService analysisExecutor;
    private byte [] luminance;  // Reused for the frames, analysis thread only
    private TextView hint;
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.scan);
        hint = (TextView) findViewById(R.id.hint);

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        analysisExecutor = Executors.newSingleThreadExecutor();

        if (checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (savedInstanceState == null) {
            // The permission dialog pauses the activity, so the user gets
            // more time than usual before being logged out. A recreated
            // activity gets the result of the earlier request.
            Session.getInstance().setExtendedTimeout(true);
            requestPermissions(new String [] { Manifest.permission.CAMERA },
                               REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    @Override
    protected void onPause() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        analysisExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String [] permissions,
                                           int [] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CAMERA_PERMISSION) {
            return;
        }

        if (grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            showError(R.string.camera_permission_denied);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);

        future.addListener(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed()) {
                    return;
                }

                try {
                    bindCamera(future.get());
                } catch (ExecutionException | InterruptedException |
                         IllegalArgumentException | IllegalStateException ex) {
                    // IllegalArgumentException if there is no back camera
                    ex.printStackTrace();
                    showError(R.string.camera_failed);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(
                ((PreviewView) findViewById(R.id.preview)).getSurfaceProvider());

        // QR codes with a lot of text need more than the default 640x480
        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setResolutionSelector(new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();

        analysis.setAnalyzer(analysisExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(ImageProxy image) {
                final String text = decode(image);
                if (text != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onQrCode(text);
                        }
                    });
                }
            }
        });

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                                 preview, analysis);
    }

    /**
     * Look for a QR code in the brightness (Y plane) of a camera frame.
     */
    private String decode(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();

            if (luminance == null || luminance.length != width * height) {
                luminance = new byte[width * height];
            }

            // Rows may be padded, the pixels of a row are adjacent
            for (int row = 0; row < height; row++) {
                buffer.position(row * rowStride);
                buffer.get(luminance, row * width, width);
            }

            return QrDecoder.decodeLuminance(luminance, width, height);
        } catch (RuntimeException ex) {
            ex.printStackTrace();
            return null;
        } finally {
            image.close();
        }
    }

    private void onQrCode(String text) {
        if (done || isFinishing()) {
            return;
        }

        // Keep scanning, the user may be pointing at another QR code
        if (Totp.getSecretToStore(text) == null) {
            hint.setText(QrScan.getInvalidMessage(text));
            return;
        }

        done = true;
        Intent result = new Intent();
        result.putExtra(EXTRA_TEXT, text);
        setResult(RESULT_OK, result);
        finish();
    }

    private void showError(int message) {
        new AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    finish();
                }
            })
            .show();
    }
}
