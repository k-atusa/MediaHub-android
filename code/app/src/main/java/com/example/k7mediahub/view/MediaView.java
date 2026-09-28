package com.example.k7mediahub.view;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.MHsvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

// media viewer activity
public class MediaView extends AppCompatActivity {
    // connection fields
    private WebView web;
    private ProgressBar prog;
    private TextView tStat;
    private LinearLayout layoutNav;
    private Button btnPrev, btnNext;
    private boolean isVideo;

    // info of current view
    private String folder;
    private String currentFile;
    private ArrayList<String> fileList;
    private int currentIndex;

    // register activity lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_media);

        // get UI components
        web = findViewById(R.id.webMedia);
        prog = findViewById(R.id.progressMedia);
        tStat = findViewById(R.id.txtStatus);
        layoutNav = findViewById(R.id.layoutNav);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        setupWebView(web);

        // get current view info
        folder = getIntent().getStringExtra("folder");
        currentFile = getIntent().getStringExtra("file");
        fileList = getIntent().getStringArrayListExtra("fileList");
        if (folder == null) folder = "";
        if (currentFile == null) currentFile = "";

        // find index in list
        currentIndex = -1;
        if (fileList != null) {
            currentIndex = fileList.indexOf(currentFile);
        }
        tStat.setText(currentFile);

        // handle navigation buttons
        btnPrev.setOnClickListener(v -> navigateTo(currentIndex - 1));
        btnNext.setOnClickListener(v -> navigateTo(currentIndex + 1));

        // observe download progress
        SVCC1.getChan().IntSlots[0].observe(this, pct -> {
            if (pct != null && pct > 0 && pct < 100 && prog.getVisibility() == View.VISIBLE) {
                tStat.setText("Downloading... " + pct + "%");
            }
        });

        // observe main bus
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

        // request media
        requestMedia(currentFile);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isVideo) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (web != null) {
            web.stopLoading();
            web.clearCache(true);
            web.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
            web.destroy();
        }
        super.onDestroy();
    }

    // configure webview
    private void setupWebView(WebView v) {
        if (v == null) return;
        v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);

        // set parent view of webview
        v.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.startsWith("https://appassets.android.local/")) {
                        try {
                            String path = request.getUrl().getPath();
                            if (path != null && path.startsWith("/")) {
                                path = path.substring(1);
                            }
                            String mime = "text/html";
                            if (path.endsWith(".mjs") || path.endsWith(".js")) mime = "text/javascript";
                            else if (path.endsWith(".css")) mime = "text/css";
                            InputStream is = getAssets().open(path); // response with assets instead
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Access-Control-Allow-Origin", "*");
                            return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, is);
                        } catch (Exception ignored) { }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (web != null) {
                    ViewGroup parent = (ViewGroup) web.getParent();
                    if (parent != null) {
                        parent.removeView(web);
                    }
                    web.destroy();
                    web = null;
                }

                // crash handler
                boolean didCrash = (detail != null && detail.didCrash());
                String toastMsg = didCrash
                        ? "WebView process crashed unexpectedly"
                        : "WebView process terminated due to memory over";
                Toast.makeText(MediaView.this, toastMsg, Toast.LENGTH_LONG).show();
                return true;
            }
        });

        // register webview lifecycle
        v.setWebChromeClient(new WebChromeClient() {
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
                if (web != null) web.setVisibility(View.GONE);
                layoutNav.setVisibility(View.GONE);

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
                if (web != null) web.setVisibility(View.VISIBLE);

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.show(WindowInsets.Type.statusBars());
                    if (isVideo) {
                        hideNavBar();
                    } else {
                        ic.show(WindowInsets.Type.navigationBars());
                        updateNavBtns();
                    }
                }
            }
        });
    }

    // navigate file list
    private void navigateTo(int newIndex) {
        if (fileList == null || newIndex < 0 || newIndex >= fileList.size()) return;
        currentIndex = newIndex;
        currentFile = fileList.get(currentIndex);
        isVideo = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // reset webview state
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.setVisibility(View.GONE);
        }
        prog.setVisibility(View.VISIBLE);
        layoutNav.setVisibility(View.GONE);
        tStat.setText(currentFile);

        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }
        requestMedia(currentFile);
    }

    // request media stream
    private void requestMedia(String fileName) {
        Bundle r = new Bundle();
        r.putString("folder", folder);
        r.putString("file", fileName);
        SVCC1.getChan().SendToSvc("STREAM_MEDIA", r);
    }

    // trigger prefetch
    private void startPrefetch() {
        if (fileList == null || currentIndex < 0) return;
        int nextIdx = currentIndex + 1;
        if (nextIdx >= fileList.size()) return;

        String nextFile = fileList.get(nextIdx);
        Bundle req = new Bundle();
        req.putString("folder", folder);
        req.putString("file", nextFile);
        SVCC1.getChan().SendToSvc("PREFETCH_MEDIA", req);
    }

    // update navigation buttons
    private void updateNavBtns() {
        if (fileList == null || fileList.size() <= 1 || isVideo) {
            layoutNav.setVisibility(View.GONE);
            return;
        }
        layoutNav.setVisibility(View.VISIBLE);
        btnPrev.setEnabled(currentIndex > 0);
        btnNext.setEnabled(currentIndex < fileList.size() - 1);
    }

    // display media content
    @SuppressLint("SetJavaScriptEnabled")
    private void show(Bundle d) {
        String type = d.getString("type", "unknown");
        String name = d.getString("fileName", "");

        switch (type) {
            case "image":
                byte[] imgD = MHsvc.mediaData;
                if (imgD != null) {
                    setWeb();
                    String b64 = android.util.Base64.encodeToString(imgD, android.util.Base64.NO_WRAP);
                    String h = "<!DOCTYPE html><html><head><style>"
                        + "body{margin:0;background:#000;display:flex;justify-content:center;align-items:center;min-height:100vh}"
                        + "img{max-width:100%;height:auto}</style></head>"
                        + "<body><img src='data:image/jpeg;base64," + b64 + "'></body></html>";
                    web.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null);
                    MHsvc.mediaData = null;
                    tStat.setText(name);
                    updateNavBtns();
                    startPrefetch();
                }
                break;

            case "text":
                byte[] txtD = MHsvc.mediaData;
                if (txtD != null) {
                    setWeb();
                    String textContent = new String(txtD, StandardCharsets.UTF_8);
                    String escapedText = TextUtils.htmlEncode(textContent);
                    String h = "<!DOCTYPE html><html><head><style>"
                        + "body{margin:0;padding:64px 16px;background:#121212;color:#fff;font-family:sans-serif;font-size:16px;line-height:1.6;white-space:pre-wrap;word-wrap:break-word;}"
                        + "</style></head><body>" + escapedText + "</body></html>";
                    web.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null);
                    MHsvc.mediaData = null;
                    tStat.setText(name);
                    updateNavBtns();
                }
                break;

            case "video":
                String vUrl = d.getString("url", "");
                if (!vUrl.isEmpty()) {
                    setWeb();
                    String h = "<!DOCTYPE html><html><head>"
                        + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>"
                        + "<style>*{margin:0;padding:0;overflow:hidden}body{background:#000;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh}"
                        + "video{width:100%;height:100%;object-fit:contain}</style></head>"
                        + "<body><video controls autoplay playsinline>"
                        + "<source src='" + vUrl + "' type='video/mp4'></video></body></html>";
                    web.loadDataWithBaseURL("http://127.0.0.1/", h, "text/html", "UTF-8", null);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    tStat.setText(name);
                    isVideo = true;
                    hideNavBar();
                    layoutNav.setVisibility(View.GONE);
                }
                break;

            case "pdf":
                String pUrl = d.getString("url", "");
                if (!pUrl.isEmpty()) {
                    setWeb();
                    try {
                        String u = "https://appassets.android.local/pdf_viewer.html?file=" + java.net.URLEncoder.encode(pUrl, "UTF-8");
                        web.loadUrl(u);
                    } catch (Exception e) {
                        web.loadUrl(pUrl);
                    }
                    tStat.setText(name);
                    layoutNav.setVisibility(View.GONE);
                }
                break;
        }
    }

    // initialize webview
    @SuppressLint("SetJavaScriptEnabled")
    private void setWeb() {
        if (web == null) {
            web = new WebView(this);
            ViewGroup root = findViewById(android.R.id.content);
            if (root != null) {
                root.addView(web, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            setupWebView(web);
        }
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.setVisibility(View.VISIBLE);
    }

    // hide navigation bar
    private void hideNavBar() {
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(WindowInsets.Type.navigationBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
