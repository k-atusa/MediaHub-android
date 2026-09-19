package com.example.k7mediahub.view;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.k7mediahub.MHcore;
import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.CacheManager;
import com.example.k7mediahub.app.SvcMH;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import android.util.Log;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// User login activity
public class LoginView extends AppCompatActivity {
    private EditText eUrl, eName, ePw;
    private CheckBox cTls, cAutoLogin;
    private TextView tStat;
    private Button bLog, bClearCache;
    private ImageButton bMemo;
    private MHcore core;

    private static final String AUTO_LOGIN_FILE = "autologin.dat";
    private static final String KEYSTORE_ALIAS = "mh_autologin_key";
    private final Executor biometricExecutor = Executors.newSingleThreadExecutor();

    // Standard lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_login);

        eUrl = findViewById(R.id.edtServerUrl);
        eName = findViewById(R.id.edtUsername);
        ePw = findViewById(R.id.edtPassword);
        cTls = findViewById(R.id.chkTls);
        cAutoLogin = findViewById(R.id.chkAutoLogin);
        tStat = findViewById(R.id.txtStatus);
        bLog = findViewById(R.id.btnLogin);
        bClearCache = findViewById(R.id.btnClearCache);
        bMemo = findViewById(R.id.btnMemo);
        core = new MHcore(false);

        // Req permission
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, 101);
        }

        // Load saved config
        try {
            core.LoadCfg(this);
            eUrl.setText(core.srvUrl);
            eName.setText(core.uName);
            cTls.setChecked(core.ignTLS);
        } catch (Exception ignored) {
        }

        // Handle memo
        bMemo.setOnClickListener(v -> showMemo());

        // Handle cache clear
        bClearCache.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Cache")
                    .setMessage("Delete all cached data? This includes thumbnails and folder data.")
                    .setPositiveButton("Clear", (d, w) -> {
                        if (SvcMH.cacheManager != null) {
                            SvcMH.cacheManager.clearAll();
                        } else {
                            new CacheManager(this).clearAll();
                        }
                        SvcMH.thumbCache.clear();
                        SvcMH.ffCache.clear();
                        tStat.setText("Cache cleared");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Handle login result
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null)
                return;
            bLog.setEnabled(true);
            switch (ev.action) {
                case "LOGIN_OK":
                    Runnable proceed = () -> {
                        startActivity(new Intent(this, FolderView.class));
                        finish();
                    };
                    // Check if auto-login should be saved
                    if (cAutoLogin.isChecked() && ev.data instanceof Bundle) {
                        Bundle data = (Bundle) ev.data;
                        byte[] autoData = data.getByteArray("autoLoginData");
                        if (autoData != null) {
                            saveAutoLogin(autoData, proceed);
                            return;
                        }
                    }
                    proceed.run();
                    break;
                case "LOGIN_FAIL":
                    Bundle d = (Bundle) ev.data;
                    tStat.setText(d.getString("msg", "Fail"));
                    break;
                case "ERROR":
                    Bundle err = (Bundle) ev.data;
                    tStat.setText(err.getString("msg", "Network/Server Error"));
                    break;
            }
        });

        // Trigger login
        bLog.setOnClickListener(v -> {
            String pw = ePw.getText().toString();

            // If password is empty, attempt auto-login if autologin.dat exists
            if (pw.isEmpty()) {
                File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
                if (f.exists()) {
                    tryAutoLogin();
                    return;
                }
                tStat.setText("Password required (no auto-login saved)");
                return;
            }

            String url = eUrl.getText().toString().trim();
            String name = eName.getText().toString().trim();

            if (url.isEmpty() || name.isEmpty()) {
                tStat.setText("Check Inputs");
                return;
            }

            bLog.setEnabled(false);
            tStat.setText("Connecting...");
            startSvc();

            Bundle req = new Bundle();
            req.putString("url", url);
            req.putString("name", name);
            req.putString("pw", pw);
            req.putBoolean("ignTLS", cTls.isChecked());
            SVCC1.getChan().SendToSvc("LOGIN", req);
        });
    }

    // ===== Auto Login: Save (AES-256-GCM Symmetric Key + Biometric Auth) =====
    // Saves only uHash and uKey to autologin.dat (server URL, username, and TLS
    // option are in config.dat)
    private void saveAutoLogin(byte[] plainData, Runnable onComplete) {
        try {
            BiometricManager bm = BiometricManager.from(this);
            int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            boolean hasBiometrics = (canAuth == BiometricManager.BIOMETRIC_SUCCESS);

            if (!hasBiometrics) {
                // Fallback: 등록된 지문이 없으면 토스트 알림 후 생체 인증 없이 바로 암호화 저장
                Toast.makeText(this, "등록된 지문이 없어 생체 인증을 건너뛰고 저장합니다.", Toast.LENGTH_SHORT).show();
                generateKeyIfNeeded(false);

                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key);

                byte[] encrypted = cipher.doFinal(plainData);
                byte[] iv = cipher.getIV();

                byte[] output = new byte[1 + iv.length + encrypted.length];
                output[0] = (byte) iv.length;
                System.arraycopy(iv, 0, output, 1, iv.length);
                System.arraycopy(encrypted, 0, output, 1 + iv.length, encrypted.length);

                File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(output);
                fos.close();

                Arrays.fill(plainData, (byte) 0);
                Log.d("LoginView", "Auto-login saved without biometrics");
                onComplete.run();
                return;
            }

            generateKeyIfNeeded(true);

            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);

            // Authenticate with fingerprint to unlock AES cipher
            BiometricPrompt.CryptoObject cryptoObj = new BiometricPrompt.CryptoObject(cipher);
            BiometricPrompt prompt = new BiometricPrompt(this, biometricExecutor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            try {
                                Cipher authCipher = result.getCryptoObject().getCipher();
                                byte[] encrypted = authCipher.doFinal(plainData);
                                byte[] iv = authCipher.getIV();

                                // Store only: [IV length (1 byte)] + [IV (12 bytes)] + [encrypted data (uHash + uKey)]
                                byte[] output = new byte[1 + iv.length + encrypted.length];
                                output[0] = (byte) iv.length;
                                System.arraycopy(iv, 0, output, 1, iv.length);
                                System.arraycopy(encrypted, 0, output, 1 + iv.length, encrypted.length);

                                File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
                                FileOutputStream fos = new FileOutputStream(f);
                                fos.write(output);
                                fos.close();

                                Arrays.fill(plainData, (byte) 0);
                                Log.d("LoginView", "Auto-login credentials (uHash + uKey) saved to autologin.dat");
                            } catch (Exception e) {
                                Log.e("LoginView", "Failed to encrypt and save auto-login", e);
                            } finally {
                                runOnUiThread(onComplete);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            Log.w("LoginView", "Fingerprint save cancelled/error: " + errString);
                            runOnUiThread(onComplete);
                        }
                    });

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("MediaHub Auto Login")
                    .setSubtitle("Touch fingerprint sensor to register auto-login")
                    .setNegativeButtonText("Skip")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();

            prompt.authenticate(info, cryptoObj);

        } catch (Exception e) {
            Log.e("LoginView", "saveAutoLogin initialization error", e);
            onComplete.run();
        }
    }

    // ===== Auto Login: Load & Execute (AES-256-GCM Symmetric Key + Biometric Auth) =====
    private void tryAutoLogin() {
        File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
        if (!f.exists()) {
            tStat.setText("No saved login found");
            return;
        }

        BiometricManager bm = BiometricManager.from(this);
        int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        boolean hasBiometrics = (canAuth == BiometricManager.BIOMETRIC_SUCCESS);

        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] stored = new byte[(int) f.length()];
            fis.read(stored);
            fis.close();

            if (stored.length < 14) {
                f.delete();
                tStat.setText("Invalid auto-login file");
                return;
            }

            int ivLen;
            byte[] iv;
            byte[] encrypted;

            // Check if stored in new format [ivLen (1)] + [iv] + [data]
            // or legacy format [ignTLS (1)] + [ivLen (1)] + [iv] + [data]
            if (stored[0] == 12) {
                // New format: first byte is ivLen=12
                ivLen = 12;
                iv = Arrays.copyOfRange(stored, 1, 1 + ivLen);
                encrypted = Arrays.copyOfRange(stored, 1 + ivLen, stored.length);
            } else if ((stored[0] == 0 || stored[0] == 1) && stored[1] == 12) {
                // Legacy format: first byte is ignTLS, second byte is ivLen=12
                ivLen = 12;
                iv = Arrays.copyOfRange(stored, 2, 2 + ivLen);
                encrypted = Arrays.copyOfRange(stored, 2 + ivLen, stored.length);
            } else {
                f.delete();
                tStat.setText("Invalid auto-login format");
                return;
            }

            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
            if (key == null) {
                f.delete();
                tStat.setText("Hardware key missing, please login manually");
                return;
            }

            // Fallback: 등록된 지문이 없으면 토스트 알림 후 생체 인증을 통과한 것으로 간주하여 즉시 복호화
            if (!hasBiometrics) {
                Toast.makeText(this, "등록된 지문이 없어 생체 인증을 건너뜁니다.", Toast.LENGTH_SHORT).show();
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                    byte[] decrypted = cipher.doFinal(encrypted);

                    bLog.setEnabled(false);
                    tStat.setText("Auto login...");
                    startSvc();

                    String url = eUrl.getText().toString().trim();
                    String name = eName.getText().toString().trim();
                    boolean ignTLS = cTls.isChecked();

                    Bundle req = new Bundle();
                    req.putByteArray("loginData", decrypted);
                    req.putString("url", url);
                    req.putString("name", name);
                    req.putBoolean("ignTLS", ignTLS);
                    SVCC1.getChan().SendToSvc("AUTO_LOGIN", req);
                    return;
                } catch (Exception e) {
                    Log.e("LoginView", "Fallback decrypt failed", e);
                    f.delete();
                    tStat.setText("Auto login expired, please login manually");
                    return;
                }
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

            // Authenticate with fingerprint to unlock AES cipher
            BiometricPrompt.CryptoObject cryptoObj = new BiometricPrompt.CryptoObject(cipher);
            BiometricPrompt prompt = new BiometricPrompt(this, biometricExecutor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            try {
                                Cipher authCipher = result.getCryptoObject().getCipher();
                                byte[] decrypted = authCipher.doFinal(encrypted);

                                runOnUiThread(() -> {
                                    bLog.setEnabled(false);
                                    tStat.setText("Auto login...");
                                    startSvc();

                                    String url = eUrl.getText().toString().trim();
                                    String name = eName.getText().toString().trim();
                                    boolean ignTLS = cTls.isChecked();

                                    Bundle req = new Bundle();
                                    req.putByteArray("loginData", decrypted);
                                    req.putString("url", url);
                                    req.putString("name", name);
                                    req.putBoolean("ignTLS", ignTLS);
                                    SVCC1.getChan().SendToSvc("AUTO_LOGIN", req);
                                });
                            } catch (Exception e) {
                                Log.e("LoginView", "Decryption failed", e);
                                runOnUiThread(() -> {
                                    f.delete();
                                    tStat.setText("Auto login expired, please login manually");
                                });
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                    || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                runOnUiThread(() -> tStat.setText("Auto login cancelled"));
                            } else {
                                runOnUiThread(() -> {
                                    tStat.setText("Biometric error: " + errString);
                                });
                            }
                        }
                    });

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("MediaHub Auto Login")
                    .setSubtitle("Touch fingerprint sensor to log in")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();

            tStat.setText("Touch fingerprint to auto login");
            prompt.authenticate(info, cryptoObj);

        } catch (Exception e) {
            Log.e("LoginView", "Auto login error", e);
            f.delete();
            tStat.setText("Auto login error, please login manually");
        }
    }

    // ===== AndroidKeyStore AES-256 Symmetric Key Generation =====
    private void generateKeyIfNeeded(boolean requireAuth) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            try {
                SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
                Cipher test = Cipher.getInstance("AES/GCM/NoPadding");
                test.init(Cipher.ENCRYPT_MODE, key);
                // test.init succeeded without auth requirement
                if (!requireAuth) {
                    return; // Key exists and works without auth
                }
                // requireAuth is true, but key didn't require auth: recreate
                ks.deleteEntry(KEYSTORE_ALIAS);
            } catch (Exception e) {
                // Key required auth
                if (requireAuth) {
                    return; // Key requires auth as desired
                }
                // requireAuth is false, delete old auth-requiring key
                ks.deleteEntry(KEYSTORE_ALIAS);
            }
        }

        KeyGenerator keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);

        if (requireAuth) {
            builder.setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true);
        } else {
            builder.setUserAuthenticationRequired(false);
        }

        keyGen.init(builder.build());
        keyGen.generateKey();
    }

    private void showMemo() {
        EditText edt = new EditText(this);
        edt.setText(core.uMemo);
        edt.setPadding(48, 48, 48, 48);

        new AlertDialog.Builder(this)
                .setTitle("Memo")
                .setView(edt)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        core.SaveCfg(this, eUrl.getText().toString().trim(), eName.getText().toString().trim(),
                                cTls.isChecked(), edt.getText().toString());
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Ensure service is running
    private void startSvc() {
        Intent it = new Intent(this, SvcMH.class);
        startForegroundService(it);
    }
}
