package com.kodholken.passdroid;

import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import com.kodholken.passdroid.db.SystemData;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

/**
 * Unlocking with a fingerprint, or another strong biometric, on Android 9 and
 * later.
 *
 * When it is turned on the key derived from the master password is encrypted
 * with a key in the Android Keystore and stored in the database. The Keystore
 * key can only be used right after biometric authentication, and the system
 * invalidates it when biometrics are added to the device.
 */
public class BiometricUnlock {
    /**
     * Name of the database attribute holding the encrypted master key.
     */
    public static final String DB_ATTRIBUTE = "biometric_key";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "passdroid_biometric_unlock";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    public interface Listener {
        /**
         * @param key the master key when unlocking, null when turning on
         *            biometric unlock.
         */
        void onSuccess(byte [] key);

        /**
         * @param message error to show to the user, or null if the user
         *                cancelled.
         */
        void onFailure(String message);
    }

    /**
     * @return true if the device can authenticate with strong biometrics.
     */
    public static boolean isAvailable(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager manager = context.getSystemService(BiometricManager.class);
            return manager != null &&
                   manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                       BiometricManager.BIOMETRIC_SUCCESS;
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            BiometricManager manager = context.getSystemService(BiometricManager.class);
            return manager != null &&
                   manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // Whether a fingerprint is enrolled is checked when the key is
            // generated.
            return context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_FINGERPRINT);
        }

        return false;
    }

    /**
     * @return true if biometric unlock has been turned on.
     */
    public static boolean isEnabled(Context context) {
        return new SystemData(context).hasAttribute(DB_ATTRIBUTE);
    }

    /**
     * Turn on biometric unlock. The user is asked to authenticate.
     *
     * @param masterKey the key derived from the master password.
     */
    public static void enable(final Activity activity, byte [] masterKey,
                              final Listener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            listener.onFailure(activity.getString(R.string.biometric_not_available));
            return;
        }

        final Cipher cipher;
        try {
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, generateKey());
        } catch (Exception ex) {
            ex.printStackTrace();
            listener.onFailure(activity.getString(R.string.biometric_enable_failed));
            return;
        }

        // The key of the session is wiped if the user is logged out while
        // the prompt is shown. The copy is wiped when the prompt is done.
        final byte [] key = masterKey.clone();
        final Listener wipingListener = new Listener() {
            @Override
            public void onSuccess(byte [] result) {
                listener.onSuccess(result);
            }

            @Override
            public void onFailure(String message) {
                Arrays.fill(key, (byte) 0);
                listener.onFailure(message);
            }
        };

        authenticate(activity, cipher, activity.getString(R.string.biometric_enable_title),
                     activity.getString(R.string.cancel), new Runnable() {
            @Override
            public void run() {
                try {
                    byte [] encrypted = cipher.doFinal(key);

                    SystemData system = new SystemData(activity);
                    if (system.hasAttribute(DB_ATTRIBUTE)) {
                        system.removeAttribute(DB_ATTRIBUTE);
                    }
                    system.setAttribute(DB_ATTRIBUTE, Base64.encode(cipher.getIV()) +
                                        "$" + Base64.encode(encrypted));
                } catch (Exception ex) {
                    ex.printStackTrace();
                    listener.onFailure(activity.getString(R.string.biometric_enable_failed));
                    return;
                } finally {
                    Arrays.fill(key, (byte) 0);
                }

                listener.onSuccess(null);
            }
        }, wipingListener);
    }

    /**
     * Get the master key. The user is asked to authenticate.
     */
    public static void unlock(final Activity activity, final Listener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            listener.onFailure(activity.getString(R.string.biometric_not_available));
            return;
        }

        final byte [] encrypted;
        final Cipher cipher;
        try {
            String [] parts = new SystemData(activity).getAttribute(DB_ATTRIBUTE).split("\\$");
            byte [] iv = Base64.decode(parts[0]);
            encrypted = Base64.decode(parts[1]);

            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (key == null) {
                // E.g. the database was restored from a backup on a new device
                throw new KeyPermanentlyInvalidatedException();
            }

            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        } catch (KeyPermanentlyInvalidatedException ex) {
            disable(activity);
            listener.onFailure(activity.getString(R.string.biometric_invalidated));
            return;
        } catch (Exception ex) {
            ex.printStackTrace();
            listener.onFailure(activity.getString(R.string.biometric_unlock_failed));
            return;
        }

        authenticate(activity, cipher, activity.getString(R.string.biometric_unlock_title),
                     activity.getString(R.string.biometric_use_password), new Runnable() {
            @Override
            public void run() {
                byte [] key;
                try {
                    key = cipher.doFinal(encrypted);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    listener.onFailure(activity.getString(R.string.biometric_unlock_failed));
                    return;
                }

                listener.onSuccess(key);
            }
        }, listener);
    }

    /**
     * Turn off biometric unlock.
     */
    public static void disable(Context context) {
        SystemData system = new SystemData(context);
        if (system.hasAttribute(DB_ATTRIBUTE)) {
            system.removeAttribute(DB_ATTRIBUTE);
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static SecretKey generateKey() throws Exception {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Every use of the key requires biometric authentication
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                                                          KEYSTORE);
        generator.init(builder.build());
        return generator.generateKey();
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static void authenticate(Activity activity, Cipher cipher, String title,
                                     String negativeButton, final Runnable onAuthenticated,
                                     final Listener listener) {
        Executor executor = activity.getMainExecutor();

        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton(negativeButton, executor,
                               new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    listener.onFailure(null);
                }
            });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        }

        builder.build().authenticate(new BiometricPrompt.CryptoObject(cipher),
                new CancellationSignal(), executor,
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                onAuthenticated.run();
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    listener.onFailure(null);
                } else {
                    listener.onFailure(errString.toString());
                }
            }
        });
    }
}
