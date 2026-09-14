package com.example.k7mediahub.view;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.k7mediahub.MHcore;
import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.SvcMH;

// User login activity
public class LoginView extends AppCompatActivity {
    private EditText eUrl, eName, ePw;
    private CheckBox cTls;
    private TextView tStat;
    private Button bLog;
    private ImageButton bMemo;
    private MHcore core;

    // Standard lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_login);

        eUrl = findViewById(R.id.edtServerUrl);
        eName = findViewById(R.id.edtUsername);
        ePw = findViewById(R.id.edtPassword);
        cTls = findViewById(R.id.chkTls);
        tStat = findViewById(R.id.txtStatus);
        bLog = findViewById(R.id.btnLogin);
        bMemo = findViewById(R.id.btnMemo);
        core = new MHcore(false);

        // Req permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }

        // Load saved config
        try {
            core.LoadCfg(this);
            eUrl.setText(core.srvUrl);
            eName.setText(core.uName);
        } catch (Exception ignored) {}

        // Handle memo
        bMemo.setOnClickListener(v -> showMemo());

        // Handle login result
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null) return;
            bLog.setEnabled(true);
            switch (ev.action) {
                case "LOGIN_OK":
                    startActivity(new Intent(this, FolderView.class));
                    finish();
                    break;
                case "LOGIN_FAIL":
                    Bundle d = (Bundle) ev.data;
                    tStat.setText(d.getString("msg", "Fail"));
                    break;
            }
        });

        // Trigger login
        bLog.setOnClickListener(v -> {
            String url = eUrl.getText().toString().trim();
            String name = eName.getText().toString().trim();
            String pw = ePw.getText().toString();

            if (url.isEmpty() || name.isEmpty() || pw.isEmpty()) {
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

    private void showMemo() {
        EditText edt = new EditText(this);
        edt.setText(core.uMemo);
        edt.setPadding(48, 48, 48, 48);

        new AlertDialog.Builder(this)
                .setTitle("Memo")
                .setView(edt)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        core.SaveCfg(this, eUrl.getText().toString().trim(), eName.getText().toString().trim(), edt.getText().toString());
                    } catch (Exception ignored) {}
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
