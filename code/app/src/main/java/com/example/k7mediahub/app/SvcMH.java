package com.example.k7mediahub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import com.example.k7mediahub.MHcore;
import com.example.k7mediahub.Opsec;
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

/**
 * Core foreground service that owns all data lifecycle:
 * - MHcore instance (authentication, crypto, network)
 * - LocalStreamer (media streaming proxy)
 * - Folder/File caches and thumbnail cache
 *
 * Activities communicate exclusively via SVCC1 LiveData bus.
 */
public class SvcMH extends Service {
    // ===== Static shared state for Activity access =====
    public static MHcore core;
    public static LocalStreamer streamer;
    public static byte[] mediaData;
    public static final ConcurrentHashMap<String, byte[]> thumbCache = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, MHcore.FolderFiles> ffCache = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private static final String CHANNEL_ID = "svc_mh";
    private static final int NOTIF_ID = 1;

    // ===== Lifecycle =====
    private final Observer<SVCC1.VEvent> cmdObserver = event -> {
        if (event == null)
            return;
        executor.submit(() -> {
            try {
                handleCommand(event);
            } catch (Exception e) {
                String msg = e.getMessage();
                sendToMain("ERROR", bundleMsg(msg != null ? msg : "Unknown Error"));
            }
        });
    };

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newFixedThreadPool(4);
        createNotifChannel();
        startForeground(NOTIF_ID, buildNotif("MediaHub JE Preparing"));
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
            streamer.stop();
            streamer = null;
        }
        if (executor != null)
            executor.shutdown();
        core = null;
        mediaData = null;
        thumbCache.clear();
        ffCache.clear();
        super.onDestroy();
    }

    // ===== Command Router =====
    private void handleCommand(SVCC1.VEvent event) throws Exception {
        Bundle d = (event.data instanceof Bundle) ? (Bundle) event.data : new Bundle();
        switch (event.action) {
            case "LOGIN":
                doLogin(d);
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
                doDownloadFiles(d);
                break;
            case "STREAM_MEDIA":
                doStreamMedia(d);
                break;
        }
    }

    // ===== Login =====
    private void doLogin(Bundle d) throws Exception {
        String url = d.getString("url", "");
        String name = d.getString("name", "");
        String pw = d.getString("pw", "");
        boolean ignTLS = d.getBoolean("ignTLS", false);

        // Preserve existing memo before creating new core instance
        String savedMemo = "";
        try {
            MHcore tmp = new MHcore(false);
            tmp.LoadCfg(getApplicationContext());
            savedMemo = tmp.uMemo;
        } catch (Exception ignored) {}

        core = new MHcore(ignTLS);
        core.srvUrl = url;
        core.uMemo = savedMemo;
        core.Login(name, pw);

        if (!core.CheckAcc()) {
            core = null;
            sendToMain("LOGIN_FAIL", bundleMsg("Cannot find account"));
            return;
        }
        core.SaveCfg(getApplicationContext(), url, name, core.uMemo);

        // Start local streaming proxy
        if (streamer != null)
            streamer.stop();
        streamer = new LocalStreamer();
        streamer.start(core);

        updateNotif("MediaHub JE Service");
        sendToMain("LOGIN_OK", null);
    }

    // ===== Folders =====
    private void doGetFolders() throws Exception {
        requireCore();
        Map<String, byte[]> flds = core.GetFlds();
        ArrayList<String> names = new ArrayList<>(flds.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        Bundle b = new Bundle();
        b.putStringArrayList("names", names);
        sendToMain("FOLDERS_LOADED", b);
    }

    private void doMkFolder(Bundle d) throws Exception {
        requireCore();
        String name = d.getString("name", "");
        if (name.isEmpty())
            throw new IllegalArgumentException("Folder name is empty");
        core.MkFld(name);
        doGetFolders(); // auto-refresh
    }

    // ===== Files =====
    private void doGetFiles(Bundle d) throws Exception {
        requireCore();
        String folder = d.getString("folder", "");
        MHcore.FolderFiles ff = core.GetFiles(folder);
        ffCache.put(folder, ff);

        ArrayList<String> names = new ArrayList<>(ff.flMap.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        Bundle b = new Bundle();
        b.putString("folder", folder);
        b.putStringArrayList("names", names);
        sendToMain("FILES_LOADED", b);

        // Load thumbnails in parallel
        for (String name : names) {
            executor.submit(() -> loadThumb(folder, ff, name));
        }
    }

    private void loadThumb(String folder, MHcore.FolderFiles ff, String fileName) {
        if (thumbCache.containsKey(folder + "/" + fileName)) return;
        try {
            byte[] thumb = core.DnMem(ff, fileName, true);
            if (thumb != null && thumb.length > 0) {
                thumbCache.put(folder + "/" + fileName, thumb);
                // Notify UI via IntSlots[1] (avoids ToMainBus postValue coalescing)
                SVCC1.getChan().SetInt(1, thumbCache.size());
            }
        } catch (Exception ignored) {
            // No thumbnail available for this file type
        }
    }

    private void doUploadFiles(Bundle d) throws Exception {
        requireCore();
        String folder = d.getString("folder", "");
        ArrayList<String> uriStrs = d.getStringArrayList("uris");
        if (uriStrs == null || uriStrs.isEmpty())
            return;

        MHcore.FolderFiles ff = ffCache.get(folder);
        if (ff == null) {
            ff = core.GetFiles(folder);
            ffCache.put(folder, ff);
        }

        int total = uriStrs.size();
        for (int i = 0; i < total; i++) {
            Uri uri = Uri.parse(uriStrs.get(i));
            File tempFile = copyUriToTemp(uri);
            if (tempFile == null)
                continue;

            try {
                updateNotif("Upload: " + tempFile.getName());
                core.UpFile(getApplicationContext(), ff, tempFile);

                Bundle prog = new Bundle();
                prog.putString("fileName", tempFile.getName());
                prog.putInt("current", i + 1);
                prog.putInt("total", total);
                sendToMain("UPLOAD_PROGRESS", prog);
            } finally {
                tempFile.delete();
            }
        }
        updateNotif("MediaHub JE Service");

        Bundle done = new Bundle();
        done.putString("folder", folder);
        sendToMain("UPLOAD_DONE", done);
    }

    private void doDownloadFiles(Bundle d) throws Exception {
        requireCore();
        String folder = d.getString("folder", "");
        ArrayList<String> fileNames = d.getStringArrayList("files");
        if (fileNames == null || fileNames.isEmpty())
            return;

        MHcore.FolderFiles ff = ffCache.get(folder);
        if (ff == null) {
            ff = core.GetFiles(folder);
            ffCache.put(folder, ff);
        }

        int total = fileNames.size();
        for (int i = 0; i < total; i++) {
            String fileName = fileNames.get(i);
            updateNotif("Download: " + fileName);
            String resultUri = core.DnFile(getApplicationContext(), ff, fileName);

            Bundle prog = new Bundle();
            prog.putString("fileName", fileName);
            prog.putString("uri", resultUri);
            prog.putInt("current", i + 1);
            prog.putInt("total", total);
            sendToMain("DOWNLOAD_PROGRESS", prog);
        }
        updateNotif("MediaHub JE Service");

        Bundle done = new Bundle();
        done.putString("folder", folder);
        sendToMain("DOWNLOAD_DONE", done);
    }

    // ===== Media Streaming =====
    private void doStreamMedia(Bundle d) throws Exception {
        requireCore();
        String folder = d.getString("folder", "");
        String fileName = d.getString("file", "");

        MHcore.FolderFiles ff = ffCache.get(folder);
        if (ff == null) {
            ff = core.GetFiles(folder);
            ffCache.put(folder, ff);
        }

        // Determine media type by extension
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0)
            ext = fileName.substring(dotIdx + 1).toLowerCase();

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

        if ("image".equals(type) || "text".equals(type)) {
            // Download entire file into memory with progress
            MHcore.ProgressListener progListener = pct ->
                    SVCC1.getChan().SetInt(0, pct);
            SVCC1.getChan().SetInt(0, 0);
            mediaData = core.DnMem(ff, fileName, false, progListener);
            result.putInt("dataSize", mediaData != null ? mediaData.length : 0);
            SVCC1.getChan().SetInt(0, 100);

        } else if ("video".equals(type) || "pdf".equals(type)) {
            // Set up streaming session
            byte[] flInfo = ff.flMap.get(fileName);
            if (flInfo == null)
                throw new IllegalArgumentException("Cannot find filemeta");

            byte[] fk = Arrays.copyOfRange(flInfo, 0, 44);
            String fId = core.ObjPid(fk);
            byte[] sizeBytes = Arrays.copyOfRange(flInfo, 44, 52);
            Opsec opsec = new Opsec();
            long origSz = opsec.DecodeInt(sizeBytes);

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

            if (streamer == null)
                throw new IllegalStateException("No streaming server");
            String url = streamer.addSession(ff.fPid, fId, fk, origSz, mime);
            result.putString("url", url);
        }

        sendToMain("MEDIA_READY", result);
    }

    // ===== Helpers =====
    private void requireCore() {
        if (core == null)
            throw new IllegalStateException("Login required");
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
            String name = "upload_temp";
            try (Cursor cursor = getContentResolver().query(
                    uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String n = cursor.getString(0);
                    if (n != null && !n.isEmpty())
                        name = n;
                }
            }
            File temp = new File(getCacheDir(), name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null)
                    return null;
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) != -1)
                    out.write(buf, 0, r);
            }
            return temp;
        } catch (Exception e) {
            return null;
        }
    }

    // ===== Notification =====
    private void createNotifChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "MediaHub JE", NotificationManager.IMPORTANCE_LOW);
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

    private void updateNotif(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif(text));
    }
}
