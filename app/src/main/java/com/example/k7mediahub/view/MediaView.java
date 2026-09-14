package com.example.k7mediahub.view;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.SvcMH;

import java.nio.charset.StandardCharsets;

// Media viewer activity
public class MediaView extends AppCompatActivity {
    private WebView web;
    private ProgressBar prog;
    private TextView tStat;
    private boolean isVideo;

    // Standard lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_media);

        web = findViewById(R.id.webMedia);
        prog = findViewById(R.id.progressMedia);
        tStat = findViewById(R.id.txtStatus);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            private View cView;
            private WebChromeClient.CustomViewCallback cCall;

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback call) {
                if (cView != null) {
                    call.onCustomViewHidden();
                    return;
                }
                cView = view;
                cCall = call;
                cView.setFitsSystemWindows(true);
                ((ViewGroup) getWindow().getDecorView()).addView(cView, new ViewGroup.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
                
                // Force landscape for fullscreen video
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }

            @Override
            public void onHideCustomView() {
                if (cView == null) return;
                ((ViewGroup) getWindow().getDecorView()).removeView(cView);
                cView = null;
                cCall.onCustomViewHidden();
                web.setVisibility(View.VISIBLE);

                // Restore orientation
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.show(WindowInsets.Type.statusBars());
                    if (isVideo) {
                        hideNavBar();
                    } else {
                        ic.show(WindowInsets.Type.navigationBars());
                    }
                }
            }
        });

        String fld = getIntent().getStringExtra("folder");
        String fl = getIntent().getStringExtra("file");
        if (fld == null) fld = "";
        if (fl == null) fl = "";

        tStat.setText(fl);

        // Download progress observer
        SVCC1.getChan().IntSlots[0].observe(this, pct -> {
            if (pct != null && pct > 0 && pct < 100 && prog.getVisibility() == View.VISIBLE) {
                tStat.setText("Downloading... " + pct + "%");
            }
        });

        // Listen for data
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null) return;
            Bundle d = (ev.data instanceof Bundle) ? (Bundle) ev.data : new Bundle();

            switch (ev.action) {
                case "MEDIA_READY":
                    prog.setVisibility(View.GONE);
                    show(d);
                    break;
                case "ERROR":
                    prog.setVisibility(View.GONE);
                    tStat.setText("Error: " + d.getString("msg", ""));
                    break;
            }
        });

        // Request media
        Bundle r = new Bundle();
        r.putString("folder", fld);
        r.putString("file", fl);
        SVCC1.getChan().SendToSvc("STREAM_MEDIA", r);
    }

    // Switch view type
    @SuppressLint("SetJavaScriptEnabled")
    private void show(Bundle d) {
        String type = d.getString("type", "unknown");
        String name = d.getString("fileName", "");

        switch (type) {
            case "image":
                byte[] imgD = SvcMH.mediaData;
                if (imgD != null) {
                    setWeb();
                    web.getSettings().setBuiltInZoomControls(true);
                    web.getSettings().setDisplayZoomControls(false);
                    web.getSettings().setUseWideViewPort(true);
                    web.getSettings().setLoadWithOverviewMode(true);
                    
                    String b64 = android.util.Base64.encodeToString(imgD, android.util.Base64.NO_WRAP);
                    String h = "<!DOCTYPE html><html><head><style>"
                        + "body{margin:0;background:#000;display:flex;justify-content:center;align-items:center;min-height:100vh}"
                        + "img{max-width:100%;height:auto}</style></head>"
                        + "<body><img src='data:image/jpeg;base64," + b64 + "'></body></html>";
                    web.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null);
                    SvcMH.mediaData = null;
                    tStat.setText(name);
                }
                break;
            case "text":
                byte[] txtD = SvcMH.mediaData;
                if (txtD != null) {
                    setWeb();
                    String textContent = new String(txtD, StandardCharsets.UTF_8);
                    // XSS Prevention: Escape HTML content
                    String escapedText = TextUtils.htmlEncode(textContent);
                    
                    String h = "<!DOCTYPE html><html><head><style>"
                        + "body{margin:0;padding:32px 16px;background:#121212;color:#fff;font-family:sans-serif;white-space:pre-wrap;word-wrap:break-word;}"
                        + "</style></head><body>" + escapedText + "</body></html>";
                    
                    web.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null);
                    SvcMH.mediaData = null;
                    tStat.setText(name);
                }
                break;
            case "video":
                String vUrl = d.getString("url", "");
                if (!vUrl.isEmpty()) {
                    setWeb();
                    String h = "<!DOCTYPE html><html><head><style>"
                        + "*{margin:0;padding:0;overflow:hidden}body{background:#000;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh}"
                        + "video{width:100%;height:100%;object-fit:contain}</style></head>"
                        + "<body><video controls autoplay playsinline>"
                        + "<source src='" + vUrl + "' type='video/mp4'></video></body></html>";
                    web.loadDataWithBaseURL("http://127.0.0.1/", h, "text/html", "UTF-8", null);
                    tStat.setText(name);
                    isVideo = true;
                    hideNavBar();
                }
                break;
            case "pdf":
                String pUrl = d.getString("url", "");
                if (!pUrl.isEmpty()) {
                    setWeb();
                    web.getSettings().setBuiltInZoomControls(true);
                    web.getSettings().setDisplayZoomControls(false);
                    web.getSettings().setUseWideViewPort(true);
                    web.getSettings().setLoadWithOverviewMode(true);
                    try {
                        String u = "file:///android_asset/pdf_viewer.html?file=" 
                            + java.net.URLEncoder.encode(pUrl, "UTF-8");
                        web.loadUrl(u);
                    } catch (Exception e) {
                        web.loadUrl(pUrl);
                    }
                    tStat.setText(name);
                }
                break;
        }
    }

    // Configure WebView
    private void setWeb() {
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.setVisibility(View.VISIBLE);
    }

    // Hide navigation bar for video playback
    private void hideNavBar() {
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(WindowInsets.Type.navigationBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // Cleanup WebView and improve security
    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.clearCache(true);
            web.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
            web.destroy();
        }
        super.onDestroy();
    }
}
