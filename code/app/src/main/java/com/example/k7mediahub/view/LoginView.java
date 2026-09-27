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

import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.MHcache;
import com.example.k7mediahub.app.MHcore;
import com.example.k7mediahub.app.MHsvc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// user login activity
public class LoginView extends AppCompatActivity {
    // connection fields
    private EditText eUrl, eName, ePw;
    private CheckBox cTls, cAutoLogin;
    private TextView tStat;
    private Button bLog, bClearCache;
    private ImageButton bMemo;
    private MHcore core;

    // constants
    private static final String AUTO_LOGIN_FILE = "autologin.dat";
    private static final String KEYSTORE_ALIAS = "k7mediahub_key";
    private final Executor biometricExec = Executors.newSingleThreadExecutor();

    // register activity
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_login);

        // link UI components
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

        // check notification permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, 101);
        }

        // load saved configuration
        try {
            core.LoadCfg(this);
            eUrl.setText(core.srvUrl);
            eName.setText(core.uName);
            cTls.setChecked(core.ignTLS);
        } catch (Exception ignored) {}

        // handle memo dialog
        bMemo.setOnClickListener(v -> showMemo());

        // handle clear cache
        bClearCache.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Clear Cache")
                    .setMessage("Delete all cached data? This includes thumbnails and folder data.")
                    .setPositiveButton("Clear", (d, w) -> {
                        if (MHsvc.cacheMgr != null) {
                            MHsvc.cacheMgr.ClearAllCache();
                        } else {
                            new MHcache(this).ClearAllCache();
                        }
                        tStat.setText("Cache cleared");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // observe main bus
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null) return;
            bLog.setEnabled(true);
            switch (ev.action) {
                case "LOGIN_OK":
                    Runnable proceed = () -> {
                        startActivity(new Intent(this, FolderView.class));
                        finish();
                    };
                    if (cAutoLogin.isChecked() && ev.data instanceof Bundle) { // save autologin
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

        // trigger login
        bLog.setOnClickListener(v -> {
            String pw = ePw.getText().toString();

            // try autologin
            if (pw.isEmpty()) {
                File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
                if (f.exists()) {
                    tryAutoLogin();
                    return;
                }
                tStat.setText("Password required");
                return;
            }

            // check url and username
            String url = eUrl.getText().toString().trim();
            String name = eName.getText().toString().trim();
            if (url.isEmpty() || name.isEmpty()) {
                tStat.setText("Check Inputs");
                return;
            }

            // transmit manual login order
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

    // show user memo
    private void showMemo() {
        EditText edt = new EditText(this);
        edt.setText(core.uMemo);
        edt.setPadding(48, 48, 48, 48);

        new AlertDialog.Builder(this)
                .setTitle("Memo")
                .setView(edt)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        core.SaveCfg(this, eUrl.getText().toString().trim(), eName.getText().toString().trim(), cTls.isChecked(), edt.getText().toString());
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // start service
    private void startSvc() {
        Intent it = new Intent(this, MHsvc.class);
        startForegroundService(it);
    }

    // write encrypted auto-login data
    private void writeEncLogin(Cipher cipher, byte[] plainData) throws Exception {
        byte[] encrypted = cipher.doFinal(plainData);
        byte[] iv = cipher.getIV();

        // 1B IV-len + 12B IV + data
        byte[] output = new byte[1 + iv.length + encrypted.length];
        output[0] = (byte) iv.length;
        System.arraycopy(iv, 0, output, 1, iv.length);
        System.arraycopy(encrypted, 0, output, 1 + iv.length, encrypted.length);

        File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(output);
        }
        MHcore.sclear(plainData);
    }

    // generate keystore key if needed
    private void genKeyIfNeeded(boolean requireAuth) throws Exception {
        // AES-GCM keystore
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            try {
                SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
                Cipher test = Cipher.getInstance("AES/GCM/NoPadding");
                test.init(Cipher.ENCRYPT_MODE, key);
                if (!requireAuth) return;
                ks.deleteEntry(KEYSTORE_ALIAS);
            } catch (Exception e) {
                if (requireAuth) return;
                ks.deleteEntry(KEYSTORE_ALIAS);
            }
        }

        // AES-256 keystore
        KeyGenerator keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);

        // check if biometric enabled
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

    // save auto login data
    private void saveAutoLogin(byte[] plainData, Runnable onComplete) {
        try {
            // get biometric secure
            BiometricManager bm = BiometricManager.from(this);
            int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            boolean hasBiometrics = (canAuth == BiometricManager.BIOMETRIC_SUCCESS);
            genKeyIfNeeded(hasBiometrics);

            // get android keystore
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);

            // fallback if no biometric
            if (!hasBiometrics) {
                Toast.makeText(this, "Skipped biometric auth (no fingerprint)", Toast.LENGTH_SHORT).show();
                writeEncLogin(cipher, plainData);
                onComplete.run();
                return;
            }

            // setup fingerprint view
            BiometricPrompt.CryptoObject cryptoObj = new BiometricPrompt.CryptoObject(cipher);
            BiometricPrompt prompt = new BiometricPrompt(this, biometricExec,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            try {
                                writeEncLogin(result.getCryptoObject().getCipher(), plainData);
                            } catch (Exception ignored) {
                            } finally {
                                runOnUiThread(onComplete);
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            runOnUiThread(onComplete);
                        }
                    });

            // launch fingerprint view
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Auto Login")
                    .setSubtitle("Touch fingerprint sensor to register auto-login")
                    .setNegativeButtonText("Skip")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            prompt.authenticate(info, cryptoObj);

        } catch (Exception e) {
            onComplete.run();
        }
    }

    // try auto-login
    private void tryAutoLogin() {
        File f = new File(getFilesDir(), AUTO_LOGIN_FILE);
        if (!f.exists()) {
            tStat.setText("No saved auto-login found");
            return;
        }

        // get biometric secure
        BiometricManager bm = BiometricManager.from(this);
        int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        boolean hasBiometrics = (canAuth == BiometricManager.BIOMETRIC_SUCCESS);

        try {
            byte[] stored = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                fis.read(stored);
            }

            // check saved data format
            if (stored.length < 14) {
                f.delete();
                tStat.setText("Invalid auto-login file");
                return;
            }
            if (stored[0] != 12) {
                f.delete();
                tStat.setText("Invalid auto-login format");
                return;
            }
            int ivLen = 12;
            byte[] iv = Arrays.copyOfRange(stored, 1, 1 + ivLen);
            byte[] encrypted = Arrays.copyOfRange(stored, 1 + ivLen, stored.length);

            // get android keystore
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
            if (key == null) {
                f.delete();
                tStat.setText("Hardware key missing, please login manually");
                return;
            }

            // prepare cipher and check biometric
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            if (!hasBiometrics) {
                Toast.makeText(this, "Skipped biometric auth (no fingerprint)", Toast.LENGTH_SHORT).show();
                byte[] decrypted = cipher.doFinal(encrypted);
                sendAutoLogin(decrypted);
                return;
            }

            // setup fingerprint view
            BiometricPrompt.CryptoObject cryptoObj = new BiometricPrompt.CryptoObject(cipher);
            BiometricPrompt prompt = new BiometricPrompt(this, biometricExec,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            try {
                                Cipher authCipher = result.getCryptoObject().getCipher();
                                byte[] decrypted = authCipher.doFinal(encrypted);
                                runOnUiThread(() -> sendAutoLogin(decrypted));
                            } catch (Exception e) {
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

            // launch fingerprint view
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("MediaHub Auto Login")
                    .setSubtitle("Touch fingerprint sensor to auto-login")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            tStat.setText("Biometric authentication ready");
            prompt.authenticate(info, cryptoObj);

        } catch (Exception e) {
            f.delete();
            tStat.setText("Auto login error, please login manually");
        }
    }

    // send auto login command
    private void sendAutoLogin(byte[] decrypted) {
        bLog.setEnabled(false);
        tStat.setText("Connecting...");
        startSvc();

        // pack url and username
        String url = eUrl.getText().toString().trim();
        String name = eName.getText().toString().trim();
        boolean ignTLS = cTls.isChecked();

        // send auto login order
        Bundle req = new Bundle();
        req.putByteArray("loginData", decrypted);
        req.putString("url", url);
        req.putString("name", name);
        req.putBoolean("ignTLS", ignTLS);
        SVCC1.getChan().SendToSvc("AUTO_LOGIN", req);
    }
}
