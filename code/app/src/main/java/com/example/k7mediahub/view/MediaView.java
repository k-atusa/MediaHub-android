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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.SvcMH;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

// Media viewer activity
public class MediaView extends AppCompatActivity {
    private WebView web;
    private ProgressBar prog;
    private TextView tStat;
    private LinearLayout layoutNav;
    private Button btnPrev, btnNext;
    private boolean isVideo;

    private String folder;
    private String currentFile;
    private ArrayList<String> fileList;
    private int currentIndex;

    // Standard lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_media);

        web = findViewById(R.id.webMedia);
        prog = findViewById(R.id.progressMedia);
        tStat = findViewById(R.id.txtStatus);
        layoutNav = findViewById(R.id.layoutNav);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);

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
                layoutNav.setVisibility(View.GONE);
                
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
                        updateNavButtons();
                    }
                }
            }
        });

        folder = getIntent().getStringExtra("folder");
        currentFile = getIntent().getStringExtra("file");
        fileList = getIntent().getStringArrayListExtra("fileList");
        if (folder == null) folder = "";
        if (currentFile == null) currentFile = "";

        // Find current index in file list
        currentIndex = -1;
        if (fileList != null) {
            currentIndex = fileList.indexOf(currentFile);
        }

        tStat.setText(currentFile);

        // Prev/Next button handlers
        btnPrev.setOnClickListener(v -> navigateTo(currentIndex - 1));
        btnNext.setOnClickListener(v -> navigateTo(currentIndex + 1));

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
        requestMedia(currentFile);
    }

    // Navigate to another file in the list
    private void navigateTo(int newIndex) {
        if (fileList == null || newIndex < 0 || newIndex >= fileList.size()) return;

        currentIndex = newIndex;
        currentFile = fileList.get(currentIndex);
        isVideo = false;

        // Reset UI
        web.stopLoading();
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        prog.setVisibility(View.VISIBLE);
        layoutNav.setVisibility(View.GONE);
        tStat.setText(currentFile);

        // Show system bars
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }

        // Request new media
        requestMedia(currentFile);
    }

    // Request media from service
    private void requestMedia(String fileName) {
        Bundle r = new Bundle();
        r.putString("folder", folder);
        r.putString("file", fileName);
        SVCC1.getChan().SendToSvc("STREAM_MEDIA", r);
    }

    // Trigger prefetch for next file
    private void triggerPrefetch() {
        if (fileList == null || currentIndex < 0) return;
        int nextIdx = currentIndex + 1;
        if (nextIdx >= fileList.size()) return;

        String nextFile = fileList.get(nextIdx);
        Bundle req = new Bundle();
        req.putString("folder", folder);
        req.putString("file", nextFile);
        SVCC1.getChan().SendToSvc("PREFETCH_MEDIA", req);
    }

    // Update navigation button visibility
    private void updateNavButtons() {
        if (fileList == null || fileList.size() <= 1 || isVideo) {
            layoutNav.setVisibility(View.GONE);
            return;
        }
        layoutNav.setVisibility(View.VISIBLE);
        btnPrev.setEnabled(currentIndex > 0);
        btnNext.setEnabled(currentIndex < fileList.size() - 1);
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
                    updateNavButtons();
                    triggerPrefetch();
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
                    updateNavButtons();
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
                    layoutNav.setVisibility(View.GONE); // No navigation for video
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
                    layoutNav.setVisibility(View.GONE); // No navigation for PDF
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
