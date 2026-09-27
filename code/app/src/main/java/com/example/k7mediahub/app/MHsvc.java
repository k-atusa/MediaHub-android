package com.example.k7mediahub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import com.example.k7mediahub.IO1;
import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// foreground service managing data lifecycle
public class MHsvc extends Service {
    // helper modules and states
    public static MHcore core;
    public static MHstream streamer;
    public static MHcache cacheMgr;
    public static byte[] mediaData;
    public static final ConcurrentHashMap<String, byte[]> prefetchData = new ConcurrentHashMap<>();

    // service and constants
    private ExecutorService executor;
    private static final String CHANNEL_ID = "k7mediahub_service";
    private static final int NOTIF_ID = 1;

    // ===== service notification =====
    private volatile String lastNotifText = "";
    private volatile long lastNotifTime = 0;

    private void makeNotifChan() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MediaHub JE", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("MediaHub Foreground Service");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotif(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MediaHub")
                .setContentText(text)
                .setSmallIcon(R.drawable.icon_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private synchronized void updateNotif(String text) {
        long now = System.currentTimeMillis();
        if (text == null || text.equals(lastNotifText)) return;
        if (now - lastNotifTime < 100) return;
        lastNotifText = text;
        lastNotifTime = now;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif(text));
        } catch (Exception ignored) { }
    }

    // ===== activity registered =====
    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(4);
        cacheMgr = new MHcache(getApplicationContext());
        cacheMgr.EvictFileCache();
        clearTempFiles();

        makeNotifChan();
        startForeground(NOTIF_ID, buildNotif("MediaHub Service Initialized"));
        SVCC1.getChan().ToSvcBus.observeForever(cmdObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        SVCC1.getChan().ToSvcBus.removeObserver(cmdObserver);
        if (streamer != null) {
            streamer.Stop();
            streamer = null;
        }
        if (executor != null)
            executor.shutdown();
        core = null;
        mediaData = null;
        MHcache.ClearMemCache();
        prefetchData.clear();
        super.onDestroy();
    }

    // ===== helpers =====
    private void chkCore() {
        if (core == null) throw new IllegalStateException("Login required");
    }

    private void resetStreamer() throws Exception {
        if (streamer != null) {
            streamer.SetStatusCb(null);
            streamer.Stop();
        }
        streamer = new MHstream();
        streamer.SetStatusCb(this::updateNotif);
        streamer.Start(core);
    }

    private void sendToMain(String action, Bundle data) {
        SVCC1.getChan().SendToMain(action, data);
    }

    private Bundle bundleMsg(String msg) {
        Bundle b = new Bundle();
        b.putString("msg", msg);
        return b;
    }

    private File copyUriToTemp(Uri uri) {
        try {
            IO1.VFile vf = new IO1.VFile(uri, false);
            String name = vf.GetName(getApplicationContext());
            if (name == null || name.isEmpty()) name = "upload.bin";

            // stream copy
            File temp = new File(getCacheDir(), name);
            try (InputStream in = vf.OpenReader(getApplicationContext()); FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            return temp;
        } catch (Exception e) {
            return null;
        }
    }

    private void clearTempFiles() {
        File cacheDir = getCacheDir();
        if (cacheDir != null && cacheDir.isDirectory()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) f.delete(); // delete cache root file
                }
            }
        }
    }

    // command observe and execute
    private final Observer<SVCC1.VEvent> cmdObserver = event -> {
        if (event == null) return;
        executor.submit(() -> {
            try {
                handleCommand(event);
            } catch (Exception e) {
                String msg = e.getMessage();
                sendToMain("ERROR", bundleMsg(msg != null ? msg : "Unknown Error"));
            }
        });
    };

    // handle service command
    private void handleCommand(SVCC1.VEvent event) throws Exception {
        Bundle d = (event.data instanceof Bundle) ? (Bundle) event.data : new Bundle();
        switch (event.action) {
            case "LOGIN":
                doLogin(d);
                break;
            case "AUTO_LOGIN":
                doAutoLogin(d);
                break;
            case "GET_FOLDERS":
                doGetFolders();
                break;
            case "MK_FOLDER":
                doMkFolder(d);
                break;
            case "GET_FILES":
                doGetFiles(d);
                break;
            case "UPLOAD_FILES":
                doUploadFiles(d);
                break;
            case "DOWNLOAD_FILES":
                doDownFiles(d);
                break;
            case "STREAM_MEDIA":
                doStreamMedia(d);
                break;
            case "PREFETCH_MEDIA":
                doPrefetch(d);
                break;
        }
    }

    // execute manual login
    private void doLogin(Bundle d) throws Exception {
        String url = d.getString("url", "");
        String name = d.getString("name", "");
        String pw = d.getString("pw", "");
        boolean ignTLS = d.getBoolean("ignTLS", false);

        // get uMemo before login
        String savedMemo = "";
        try {
            MHcore tmp = new MHcore(false);
            tmp.LoadCfg(getApplicationContext());
            savedMemo = tmp.uMemo;
        } catch (Exception ignored) { }

        // start login
        core = new MHcore(ignTLS);
        core.cache = cacheMgr;
        core.srvUrl = url;
        core.uMemo = savedMemo;
        updateNotif("Manual login authenticating...");
        core.Login(name, pw);

        // check if account exists
        if (!core.CheckAcc()) {
            core = null;
            updateNotif("Login failed (No Account)");
            sendToMain("LOGIN_FAIL", bundleMsg("Cannot find account"));
            return;
        }
        core.SaveCfg(getApplicationContext(), url, name, ignTLS, core.uMemo);
        resetStreamer();

        // export auto login data
        byte[] autoData = core.ExportAutoLogin();
        Bundle result = new Bundle();
        if (autoData != null) result.putByteArray("autoLoginData", autoData);
        updateNotif("MediaHub service is ready");
        sendToMain("LOGIN_OK", result);
    }

    // execute auto login
    private void doAutoLogin(Bundle d) throws Exception {
        // parse login data
        byte[] loginData = d.getByteArray("loginData");
        String url = d.getString("url", "");
        String name = d.getString("name", "");
        boolean ignTLS = d.getBoolean("ignTLS", false);
        if (loginData == null) {
            sendToMain("LOGIN_FAIL", bundleMsg("No auto-login data"));
            return;
        }

        // get uMemo before login
        String savedMemo = "";
        try {
            MHcore tmp = new MHcore(false);
            tmp.LoadCfg(getApplicationContext());
            savedMemo = tmp.uMemo;
            if (url.isEmpty()) url = tmp.srvUrl;
            if (name.isEmpty()) name = tmp.uName;
        } catch (Exception ignored) { }

        // start login
        core = new MHcore(ignTLS);
        core.cache = cacheMgr;
        core.srvUrl = url;
        core.uName = name;
        core.uMemo = savedMemo;
        updateNotif("Auto login authenticating...");

        // check if account exists
        if (!core.LoadAutoLogin(loginData, url, name, ignTLS)) {
            core = null;
            updateNotif("Login failed (Invalid Autologin Data)");
            sendToMain("LOGIN_FAIL", bundleMsg("Invalid auto-login data"));
            return;
        }
        if (!core.CheckAcc()) {
            core = null;
            updateNotif("Login failed (No Account)");
            sendToMain("LOGIN_FAIL", bundleMsg("Cannot find account"));
            return;
        }

        core.SaveCfg(getApplicationContext(), url, name, ignTLS, core.uMemo);
        resetStreamer();
        updateNotif("MediaHub service is ready");
        sendToMain("LOGIN_OK", null);
    }

    // fetch folder list (folder map)
    private void doGetFolders() throws Exception {
        chkCore();
        updateNotif("Fetching folder map...");
        Map<String, byte[]> fm = core.GetFolderMap();
        ArrayList<String> names = new ArrayList<>(fm.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        Bundle b = new Bundle();
        b.putStringArrayList("names", names);
        updateNotif("MediaHub service is ready");
        sendToMain("FOLDERS_LOADED", b);
    }

    // make new folder
    private void doMkFolder(Bundle d) throws Exception {
        chkCore();
        String name = d.getString("name", "");
        if (name.isEmpty()) throw new IllegalArgumentException("Folder name is empty");
        updateNotif("Making new folder");
        core.MkFolder(name);
        doGetFolders();
    }

    // get file map from cache or network
    private MHcore.FileMap getFileMap(String folder) throws Exception {
        MHcore.FileMap fm = MHcache.GetMemFileMap(folder);
        if (fm == null) {
            updateNotif("Fetching file map");
            fm = core.GetFileMap(folder);
            MHcache.PutMemFileMap(folder, fm);
        }
        return fm;
    }

    // load thumbnail of files
    private void loadThumb(String folder, MHcore.FileMap fm, String fileName) {
        if (MHcache.HasMemThumb(folder + "/" + fileName)) return;
        try {
            byte[] thumb = core.DnMem(fm, fileName, true);
            if (thumb != null && thumb.length > 0) {
                MHcache.PutMemThumb(folder + "/" + fileName, thumb);
                SVCC1.getChan().SetInt(1, MHcache.GetMemThumbCount());
            }
        } catch (Exception ignored) { }
    }

    // fetch file list (file map)
    private void doGetFiles(Bundle d) throws Exception {
        chkCore();
        String folder = d.getString("folder", "");
        updateNotif("Fetching file map");
        MHcore.FileMap fm = core.GetFileMap(folder);
        MHcache.PutMemFileMap(folder, fm);

        // sort file names
        ArrayList<String> names = new ArrayList<>(fm.fileMap.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        Bundle b = new Bundle();
        b.putString("folder", folder);
        b.putStringArrayList("names", names);
        updateNotif("MediaHub service is ready");
        sendToMain("FILES_LOADED", b);

        // load thumbnails
        for (String name : names) {
            executor.submit(() -> loadThumb(folder, fm, name));
        }
    }

    // upload files
    private void doUploadFiles(Bundle d) throws Exception {
        // target folder
        chkCore();
        String folder = d.getString("folder", "");
        ArrayList<String> uriStrs = d.getStringArrayList("uris");
        if (uriStrs == null || uriStrs.isEmpty()) return;
        MHcore.FileMap fm = getFileMap(folder);

        // upload one by one
        int total = uriStrs.size();
        for (int i = 0; i < total; i++) {
            Uri uri = Uri.parse(uriStrs.get(i));
            File tempFile = copyUriToTemp(uri);
            if (tempFile == null) continue;

            try {
                updateNotif(String.format("Uploading files (%d/%d)", (i + 1), total));
                core.UpFile(getApplicationContext(), fm, tempFile);

                Bundle prog = new Bundle();
                prog.putString("fileName", tempFile.getName());
                prog.putInt("current", i + 1);
                prog.putInt("total", total);
                sendToMain("UPLOAD_PROGRESS", prog);
            } finally {
                tempFile.delete();
            }
        }

        // update notification
        updateNotif("MediaHub service is ready");
        Bundle done = new Bundle();
        done.putString("folder", folder);
        sendToMain("UPLOAD_DONE", done);
    }

    // download files
    private void doDownFiles(Bundle d) throws Exception {
        // target folder
        chkCore();
        String folder = d.getString("folder", "");
        ArrayList<String> fileNames = d.getStringArrayList("files");
        if (fileNames == null || fileNames.isEmpty()) return;
        MHcore.FileMap fm = getFileMap(folder);

        // download one by one
        int total = fileNames.size();
        for (int i = 0; i < total; i++) {
            String fileName = fileNames.get(i);
            updateNotif(String.format("Downloading files (%d/%d)", (i + 1), total));
            String resultUri = core.DnFile(getApplicationContext(), fm, fileName);

            Bundle prog = new Bundle();
            prog.putString("fileName", fileName);
            prog.putString("uri", resultUri);
            prog.putInt("current", i + 1);
            prog.putInt("total", total);
            sendToMain("DOWNLOAD_PROGRESS", prog);
        }

        // update notification
        updateNotif("MediaHub service is ready");
        Bundle done = new Bundle();
        done.putString("folder", folder);
        sendToMain("DOWNLOAD_DONE", done);
    }

    // stream media data
    private void doStreamMedia(Bundle d) throws Exception {
        // target folder and file
        chkCore();
        String folder = d.getString("folder", "");
        String fileName = d.getString("file", "");
        MHcore.FileMap fm = getFileMap(folder);

        // determine media type
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) ext = fileName.substring(dotIdx + 1).toLowerCase();

        String type;
        if (Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(ext)) {
            type = "image";
        } else if ("pdf".equals(ext)) {
            type = "pdf";
        } else if (Arrays.asList("mp4", "webm", "mov", "mkv", "avi").contains(ext)) {
            type = "video";
        } else {
            type = "text";
        }

        Bundle result = new Bundle();
        result.putString("type", type);
        result.putString("fileName", fileName);

        if ("image".equals(type) || "text".equals(type)) { // image text
            // check prefetch data
            String prefetchKey = folder + "/" + fileName;
            byte[] prefetched = prefetchData.remove(prefetchKey);

            if (prefetched != null) { // prefetch hit
                mediaData = prefetched;
                result.putInt("dataSize", mediaData.length);
                SVCC1.getChan().SetInt(0, 100);
                updateNotif("Loaded media (prefetch)");

            } else { // download data
                MHcore.ProgListener progListener = pct -> {
                    SVCC1.getChan().SetInt(0, pct);
                    if (pct < 100) {
                        updateNotif("Downloading media " + pct + "%");
                    } else {
                        updateNotif("Decrypting media");
                    }
                };
                SVCC1.getChan().SetInt(0, 0);
                mediaData = core.DnMem(fm, fileName, false, progListener);
                result.putInt("dataSize", mediaData != null ? mediaData.length : 0);
                SVCC1.getChan().SetInt(0, 100);
                updateNotif("Loaded media");
            }

        } else if ("video".equals(type) || "pdf".equals(type)) { // video pdf
            updateNotif("Making streaming session");
            MHcore.StreamMeta meta = core.GetStreamMeta(fm, fileName);
            if (meta == null) throw new IllegalArgumentException("Cannot find filemeta");

            // determine media type
            String mime;
            if ("pdf".equals(type)) {
                mime = "application/pdf";
            } else {
                mime = "video/mp4";
                switch (ext) {
                    case "webm":
                        mime = "video/webm";
                        break;
                    case "mkv":
                        mime = "video/x-matroska";
                        break;
                    case "mov":
                        mime = "video/quicktime";
                        break;
                    case "avi":
                        mime = "video/x-msvideo";
                        break;
                }
            }

            // put metadata into streamer
            if (streamer == null) throw new IllegalStateException("No streaming server");
            streamer.SetStatusCb(this::updateNotif);
            String url = streamer.AddSession(fm.folderPID, meta.fId, meta.fKey, meta.origSz, mime);
            MHcore.sclear(meta.fKey);
            result.putString("url", url);
            updateNotif("Started streaming session");
        }

        sendToMain("MEDIA_READY", result);
    }

    // prefetch media
    private void doPrefetch(Bundle d) throws Exception {
        // target folder and file
        chkCore();
        String folder = d.getString("folder", "");
        String fileName = d.getString("file", "");
        MHcore.FileMap fm = getFileMap(folder);

        // check file size and type
        long fileSize = core.GetFileSize(fm, fileName);
        if (fileSize < 0 || fileSize > 16 * 1048576) return;

        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) ext = fileName.substring(dotIdx + 1).toLowerCase();
        if (!Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(ext)) return;

        // set prefetch key and download
        String prefetchKey = folder + "/" + fileName;
        if (prefetchData.containsKey(prefetchKey)) return;
        try {
            byte[] data = core.DnMem(fm, fileName, false);
            if (data != null) {
                prefetchData.clear();
                prefetchData.put(prefetchKey, data);
                updateNotif("Prefetch complete");
            }
        } catch (Exception ignored) { }
    }
}
