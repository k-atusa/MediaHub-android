package com.example.k7mediahub.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

import com.example.k7mediahub.Bencode;
import com.example.k7mediahub.Bencrypt;
import com.example.k7mediahub.IO1;
import com.example.k7mediahub.Opsec;
import com.example.k7mediahub.SVCC1;

// core logic for auth, crypto, and network
public class MHcore {
    // constants
    private static final String PEPPER = "_PROJECT_WHY_MEDIAHUB_PEPPER_2026_!@#$";
    private static final String CFG_FILE = "config.dat";
    public static final int CHUNK_SZ = 1048576;

    // memory clear and register as dummy
    private static volatile Object DUMMY;
    public static void sclear(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
            DUMMY = data;
        }
    }
    public static void ClearDummy() {
        DUMMY = null;
    }

    // file map structure for a folder
    public static class FileMap {
        public String folderPID;
        public byte[] folderKey;
        public Map<String, byte[]> fileMap;

        public FileMap(String folderPID, byte[] folderKey, Map<String, byte[]> fileMap) {
            this.folderPID = folderPID;
            this.folderKey = folderKey;
            this.fileMap = fileMap;
        }
    }

    // get file size
    public long GetFileSize(FileMap fm, String fileName) {
        if (!fm.fileMap.containsKey(fileName)) return -1;
        byte[] fileInfo = unmask(fm.fileMap.get(fileName));
        byte[] sizeBytes = Arrays.copyOfRange(fileInfo, 44, 52);
        long sz = opsec.DecodeInt(sizeBytes);
        sclear(fileInfo);
        return sz;
    }

    // connection fields
    public String srvUrl;
    public String uName;
    public String uHash;
    public String uMemo = "";
    public boolean ignTLS = false;
    private byte[] uKey;
    public Map<String, byte[]> folderMap = new HashMap<>();
    private final Bencrypt.Masker masker;
    private final Bencrypt bencrypt;
    private final Opsec opsec;

    // cache manager
    public MHcache cache;

    // bypass SSL validation if ignTLS
    private void trustAllSSL() {
        try {
            @SuppressLint("CustomX509TrustManager") TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                        @SuppressLint("TrustAllX509TrustManager")
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }
                        @SuppressLint("TrustAllX509TrustManager")
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @SuppressLint("BadHostnameVerifier")
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception ignored) {}
    }

    // init core
    public MHcore(boolean ignTLS) {
        this.ignTLS = ignTLS;
        this.masker = Bencrypt.Masker.GetMasker();
        this.bencrypt = new Bencrypt();
        this.opsec = new Opsec();
        if (ignTLS) trustAllSSL();
    }

    // unmask master key
    private byte[] getPlainUkey() {
        return masker.XOR(uKey);
    }

    // unmask byte array
    private byte[] unmask(byte[] masked) {
        return masker.XOR(masked);
    }

    // mask map values
    private void maskMapValues(Map<String, byte[]> map) {
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            e.setValue(masker.XOR(e.getValue()));
        }
    }

    // unmask map and return copy
    private Map<String, byte[]> unmaskMapCopy(Map<String, byte[]> maskedMap) {
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, byte[]> e : maskedMap.entrySet()) {
            result.put(e.getKey(), masker.XOR(e.getValue()));
        }
        return result;
    }

    // zerorize map values
    private static void zeroMap(Map<String, byte[]> map) {
        for (byte[] v : map.values()) sclear(v);
    }

    // generate iv with counter
    private byte[] mkIv(byte[] gIv, long c) {
        byte[] iv = Arrays.copyOf(gIv, gIv.length);
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(c);
        byte[] ctr = b.array();
        for (int i = 0; i < 8; i++) iv[4 + i] ^= ctr[i];
        return iv;
    }

    // read all bytes from stream
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = in.read(b)) != -1) buf.write(b, 0, r);
        in.close();
        return buf.toByteArray();
    }

    // fetch network bytes
    private byte[] fetchNet(URL u, long start, long end) throws Exception {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        in.close();
        c.disconnect();
        return out.toByteArray();
    }

    // make thumbnail bitmap
    private byte[] mkThumb(Bitmap img) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        double r = (double) w / h;
        Bitmap out;

        if (r >= 0.6666 && r <= 1.5) { // rectangular thumb
            int nw, nh;
            if (w >= h) {
                nw = 256;
                nh = (int) (256 / r);
            } else {
                nw = (int) (256 * r);
                nh = 256;
            }
            out = Bitmap.createScaledBitmap(img, nw, nh, true);

        } else { // extreme ratio
            int s = Math.min(w, h);
            Bitmap cropped = Bitmap.createBitmap(img, 0, 0, s, s);
            out = Bitmap.createScaledBitmap(cropped, 256, 256, true);
            if (cropped != img) cropped.recycle();
        }

        // make with JPEG 70%
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        if (out != img) out.recycle();
        return baos.toByteArray();
    }

    // extract image thumbnail
    private byte[] imgThumb(String path) {
        Bitmap img = BitmapFactory.decodeFile(path);
        byte[] res = mkThumb(img);
        if (img != null) img.recycle();
        return res;
    }

    // extract video thumbnail
    private byte[] vidThumb(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = retriever.getFrameAtTime();
            byte[] res = mkThumb(frame);
            if (frame != null) frame.recycle();
            return res;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    // convert key to PID
    public String GetPID(byte[] key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 32; i < 44; i++) sb.append(String.format("%02x", key[i]));
        return sb.toString();
    }

    // load config data (serverUrl, userName, noTLS, memo)
    public void LoadCfg(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), CFG_FILE);
        if (!f.exists()) return;
        byte[] bytes = readAll(new FileInputStream(f));
        String[] pts = new String(bytes, StandardCharsets.UTF_8).split("\n", 4);
        if (pts.length >= 4) {
            this.srvUrl = pts[0];
            this.uName = pts[1];
            this.ignTLS = "1".equals(pts[2]);
            this.uMemo = pts[3];
        }
    }

    // save config data
    public void SaveCfg(Context ctx, String url, String name, boolean ignTLS, String memo) throws Exception {
        this.srvUrl = url;
        this.uName = name;
        this.ignTLS = ignTLS;
        this.uMemo = memo != null ? memo : "";
        File f = new File(ctx.getFilesDir(), CFG_FILE);
        FileOutputStream out = new FileOutputStream(f);
        String data = this.srvUrl + "\n" + this.uName + "\n" + (this.ignTLS ? "1" : "0") + "\n" + this.uMemo;
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    // check account
    public boolean CheckAcc() throws Exception {
        if (uHash == null) return false;
        if (cache != null && cache.GetFileCache("userdata", uHash) != null) return true;

        URL u = new URL(srvUrl + "/api/userdata/" + uHash);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("GET");
        int res = c.getResponseCode();
        c.disconnect();
        return res == 200;
    }

    // login and derive keys (uHash, uKey)
    public void Login(String name, String pw) throws Exception {
        this.uName = name;
        byte[] p = Bencode.NormPW(pw);
        byte[] s = Bencrypt.SHA3256((name + PEPPER).getBytes(StandardCharsets.UTF_8));
        Bencrypt.HashMaster hm = new Bencrypt.HashMaster("arg2st");
        byte[][] keys = hm.KDF(p, s);

        byte[] hash = Bencrypt.SHA3256(keys[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
        this.uHash = sb.toString();
        this.uKey = masker.XOR(keys[1]);

        sclear(p);
        sclear(keys[0]);
        sclear(keys[1]);
    }

    // export auto login data (uHash + uKey)
    public byte[] ExportAutoLogin() {
        if (uHash == null || uKey == null) return null;
        byte[] rawKey = getPlainUkey();
        if (rawKey == null) return null;
        String b64Key = java.util.Base64.getEncoder().encodeToString(rawKey);
        sclear(rawKey);
        String data = uHash + "\n" + b64Key;
        return data.getBytes(StandardCharsets.UTF_8);
    }

    // load auto login (uHash + uKey)
    public boolean LoadAutoLogin(byte[] data, String url, String name, boolean ignTLS) throws Exception {
        String str = new String(data, StandardCharsets.UTF_8);
        String[] parts = str.split("\n", 2);
        if (parts.length < 2) return false;
        this.uHash = parts[0];
        byte[] rawKey = java.util.Base64.getDecoder().decode(parts[1]);
        this.uKey = masker.XOR(rawKey);
        sclear(rawKey);

        this.ignTLS = ignTLS;
        if (ignTLS) trustAllSSL();
        return true;
    }

    // get folder map
    public Map<String, byte[]> GetFolderMap() throws Exception {
        if (uHash == null) throw new IllegalStateException("Not authenticated");
        URL u = new URL(srvUrl + "/api/userdata/" + uHash);

        // download data
        byte[] content = null;
        if (cache != null) {
            content = cache.GetOrFetchFileCache("userdata", uHash, () -> {
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    byte[] data = readAll(conn.getInputStream());
                    conn.disconnect();
                    return data;
                } else if (code == 404) {
                    conn.disconnect();
                    return new byte[0];
                } else {
                    conn.disconnect();
                    throw new RuntimeException("Connection Error: code " + code);
                }
            });
        } else {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            int resCode = c.getResponseCode();
            if (resCode == 200) {
                content = readAll(c.getInputStream());
            } else if (resCode == 404) {
                content = new byte[0];
            } else {
                c.disconnect();
                throw new RuntimeException("Connection Error: code " + resCode);
            }
            c.disconnect();
        }

        // decrypt and unpack
        if (content != null && content.length > 0) {
            byte[] rawUK = getPlainUkey();
            byte[] keySlice = Arrays.copyOfRange(rawUK, 0, 32);
            Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", keySlice);
            this.folderMap = opsec.DecodeCfg(sm.DeBin(content));
            sclear(rawUK);
            sclear(keySlice);
            maskMapValues(this.folderMap);
        } else {
            this.folderMap = new HashMap<>();
        }
        return this.folderMap;
    }

    // make new folder
    public void MkFolder(String name) throws Exception {
        if (this.folderMap.containsKey(name)) throw new IllegalArgumentException("Folder name already exists!");
        byte[] folderKey = bencrypt.Random(44);
        this.folderMap.put(name, masker.XOR(folderKey));

        // encrypt updated folder map
        Map<String, byte[]> plainMap = unmaskMapCopy(this.folderMap);
        byte[] rawUK = getPlainUkey();
        byte[] keySlice = Arrays.copyOfRange(rawUK, 0, 32);
        Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", keySlice);
        byte[] enc = sm.EnBin(opsec.EncodeCfg(plainMap));
        sclear(rawUK);
        sclear(keySlice);
        zeroMap(plainMap);

        // upload folder map
        URL u = new URL(srvUrl + "/api/userdata/" + uHash);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        OutputStream out = c.getOutputStream();
        out.write(enc);
        out.close();

        // update cache
        if (c.getResponseCode() != 200) throw new RuntimeException("Failed to create folder: code " + c.getResponseCode());
        c.disconnect();
        if (cache != null) cache.PutFileCache("userdata", uHash, enc);
    }

    // get file map
    public FileMap GetFileMap(String name) throws Exception {
        if (!this.folderMap.containsKey(name)) throw new IllegalArgumentException("Folder not found");
        byte[] folderKey = unmask(this.folderMap.get(name));
        String folderPID = GetPID(folderKey);

        // download data
        byte[] content = null;
        String cacheKey = folderPID + "/names";
        if (cache != null) {
            URL fUrl = new URL(srvUrl + "/api/storage/" + folderPID + "/names");
            content = cache.GetOrFetchFileCache("folders", cacheKey, () -> {
                HttpURLConnection conn = (HttpURLConnection) fUrl.openConnection();
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    byte[] data = readAll(conn.getInputStream());
                    conn.disconnect();
                    return data;
                }
                conn.disconnect();
                return new byte[0];
            });
        } else {
            URL u = new URL(srvUrl + "/api/storage/" + folderPID + "/names");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            int resCode = c.getResponseCode();
            if (resCode == 200) {
                content = readAll(c.getInputStream());
            }
            c.disconnect();
        }

        // decrypt and unpack
        Map<String, byte[]> fileMap = new HashMap<>();
        if (content != null && content.length > 0) {
            byte[] keySlice = Arrays.copyOfRange(folderKey, 0, 32);
            Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", keySlice);
            fileMap = opsec.DecodeCfg(sm.DeBin(content));
            sclear(keySlice);
            maskMapValues(fileMap);
        }
        byte[] maskedFolderKey = masker.XOR(folderKey);
        sclear(folderKey);
        return new FileMap(folderPID, maskedFolderKey, fileMap);
    }

    // upload file
    public boolean UpFile(Context ctx, FileMap fm, File file) throws Exception {
        String name = file.getName();
        long origSz = file.length();
        String path = file.getAbsolutePath();
        byte[] fileKey = bencrypt.Random(44);
        String filePID = GetPID(fileKey);

        // process thumbnail
        String ext = "";
        int idx = name.lastIndexOf('.');
        if (idx > 0)
            ext = name.substring(idx + 1).toLowerCase();
        byte[] thumb = null;
        if (Arrays.asList("mp4", "webm", "mov", "mkv").contains(ext)) {
            thumb = vidThumb(path);
        } else if (Arrays.asList("jpg", "jpeg", "png", "gif", "webp").contains(ext)) {
            thumb = imgThumb(path);
        }

        // upload thumbnail
        if (thumb != null) {
            byte[] fkSlice = Arrays.copyOfRange(fileKey, 0, 32);
            Bencrypt.SymMaster tSm = new Bencrypt.SymMaster("gcm1", fkSlice);
            byte[] encThumb = tSm.EnBin(thumb);
            URL tUrl = new URL(srvUrl + "/api/media/" + fm.folderPID + "/" + filePID + "/thumb");
            HttpURLConnection tc = (HttpURLConnection) tUrl.openConnection();
            tc.setRequestMethod("POST");
            tc.setRequestProperty("X-User-Hash", uHash);
            tc.setRequestProperty("Content-Type", "application/octet-stream");
            tc.setDoOutput(true);
            OutputStream tout = tc.getOutputStream();
            tout.write(encThumb);
            tout.close();
            tc.getResponseCode();
            tc.disconnect();
        }

        // encrypt temp file
        File tFile = new File(ctx.getCacheDir(), "up_" + filePID + ".temp");
        FileOutputStream tOut = new FileOutputStream(tFile);
        byte[] fkSlice = Arrays.copyOfRange(fileKey, 0, 32);
        Bencrypt.SymMaster smx = new Bencrypt.SymMaster("gcmx1", fkSlice);
        FileInputStream fis = new FileInputStream(file);
        smx.EnFile(fis, origSz, tOut);
        fis.close();

        // pad file
        tOut.flush();
        long encSz = tFile.length();
        long padSz = Opsec.PadLen(encSz);
        if (Opsec.PadLen(encSz) > 0) Opsec.PadFile(tOut, padSz);
        tOut.close();

        // upload data
        URL u = new URL(srvUrl + "/api/media/" + fm.folderPID + "/" + filePID + "/dat");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("X-User-Hash", uHash);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        long tLen = tFile.length();
        c.setFixedLengthStreamingMode(tLen);
        c.setDoOutput(true);

        FileInputStream tIn = new FileInputStream(tFile);
        OutputStream out = c.getOutputStream();
        byte[] buf = new byte[65536];
        int r;
        long cur = 0;
        while ((r = tIn.read(buf)) != -1) {
            out.write(buf, 0, r);
            cur += r;
            SVCC1.getChan().SetInt(0, (int) (cur * 100 / tLen));
        }
        out.flush();
        out.close();
        tIn.close();
        tFile.delete();
        if (c.getResponseCode() != 200) throw new RuntimeException("Upload failed: code " + c.getResponseCode());
        c.disconnect();

        // encode info to folder map
        byte[] sizeBuf = opsec.EncodeInt(origSz, 8);
        byte[] fileInfo = new byte[52];
        System.arraycopy(fileKey, 0, fileInfo, 0, 44);
        System.arraycopy(sizeBuf, 0, fileInfo, 44, 8);
        fm.fileMap.put(name, masker.XOR(fileInfo));

        byte[] plainFolderKey = unmask(fm.folderKey);
        Map<String, byte[]> plainFlMap = unmaskMapCopy(fm.fileMap);

        byte[] fKeySlice = Arrays.copyOfRange(plainFolderKey, 0, 32);
        Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", fKeySlice);
        byte[] encMap = sm.EnBin(opsec.EncodeCfg(plainFlMap));
        sclear(fileKey);
        sclear(fKeySlice);
        sclear(fkSlice);
        sclear(plainFolderKey);
        zeroMap(plainFlMap);

        // upload metadata
        URL uMap = new URL(srvUrl + "/api/storage/" + fm.folderPID + "/names");
        HttpURLConnection cMap = (HttpURLConnection) uMap.openConnection();
        cMap.setRequestMethod("POST");
        cMap.setRequestProperty("X-User-Hash", uHash);
        cMap.setDoOutput(true);
        cMap.setRequestProperty("Content-Type", "application/octet-stream");
        OutputStream outMap = cMap.getOutputStream();
        outMap.write(encMap);
        outMap.close();

        // update cache
        if (cMap.getResponseCode() != 200) throw new RuntimeException("Failed to sync metadata: code " + cMap.getResponseCode());
        cMap.disconnect();
        if (cache != null) cache.PutFileCache("folders", fm.folderPID + "/names", encMap);
        return true;
    }

    // download file
    public String DnFile(Context ctx, FileMap fm, String fileName) throws Exception {
        if (!fm.fileMap.containsKey(fileName)) throw new IllegalArgumentException("File not found in metadata");
        byte[] fileInfo = unmask(fm.fileMap.get(fileName));
        byte[] fileKey = Arrays.copyOfRange(fileInfo, 0, 44);
        byte[] sizeBytes = Arrays.copyOfRange(fileInfo, 44, 52);
        long origSz = opsec.DecodeInt(sizeBytes);
        String filePID = GetPID(fileKey);
        sclear(fileInfo);

        // ready to decrypt
        byte[] fkSlice = Arrays.copyOfRange(fileKey, 0, 32);
        Bencrypt.SymMaster smx = new Bencrypt.SymMaster("gcmx1", fkSlice);
        long ciphSz = smx.AfterSize(origSz);

        // download data
        URL u = new URL(srvUrl + "/api/media/" + fm.folderPID + "/" + filePID + "/dat");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("GET");
        int resCode = c.getResponseCode();

        if (resCode == 200 || resCode == 206) {
            IO1.VFile destFile = IO1.CreateDownloadsFile(ctx, fileName);
            if (destFile == null) throw new RuntimeException("Failed to create download file");
            OutputStream fos = destFile.OpenWriter(ctx, false);
            InputStream in = c.getInputStream();

            // download all
            new Thread(() -> {
                while (smx.Processed() < ciphSz) {
                    SVCC1.getChan().SetInt(0, (int) (smx.Processed() * 100 / ciphSz));
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                }
                SVCC1.getChan().SetInt(0, 100);
            }).start();

            smx.DeFile(in, ciphSz, fos);
            in.close();
            fos.close();
            c.disconnect();
            sclear(fkSlice);
            return destFile.GetUri().toString();
        } else {
            c.disconnect();
            sclear(fkSlice);
            throw new RuntimeException("Download failed: " + resCode);
        }
    }

    // progress listener
    public interface ProgListener {
        void onProgress(int percent);
    }

    // download file to memory
    public byte[] DnMem(FileMap fm, String fileName, boolean isThumbnail) throws Exception {
        return DnMem(fm, fileName, isThumbnail, null);
    }

    // download file to memory with progress
    public byte[] DnMem(FileMap fm, String fileName, boolean isThumbnail, ProgListener listener) throws Exception {
        if (!fm.fileMap.containsKey(fileName)) throw new IllegalArgumentException("File not found in metadata");
        byte[] fileInfo = unmask(fm.fileMap.get(fileName));

        // prepare keys
        byte[] fileKey = Arrays.copyOfRange(fileInfo, 0, 44);
        byte[] sizeBytes = Arrays.copyOfRange(fileInfo, 44, 52);
        long origSz = opsec.DecodeInt(sizeBytes);
        String filePID = GetPID(fileKey);
        sclear(fileInfo);
        String typ = isThumbnail ? "thumb" : "dat";
        String cacheKeyThumb = fm.folderPID + "/" + filePID + "/" + typ;

        // get thumbnail data from cache
        byte[] downloaded = null;
        if (isThumbnail && cache != null) {
            downloaded = cache.GetFileCache("thumbs", cacheKeyThumb);
        }

        // download data
        if (downloaded == null) {
            URL u = new URL(srvUrl + "/api/media/" + fm.folderPID + "/" + filePID + "/" + typ);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            if (isThumbnail) {
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
            }
            int resCode = c.getResponseCode();

            // download all
            if (resCode == 200 || resCode == 206) {
                long total = c.getContentLengthLong();
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                long cur = 0;
                int lastPct = -1;
                while ((r = in.read(buf)) != -1) {
                    bos.write(buf, 0, r);
                    cur += r;
                    if (listener != null && total > 0) {
                        int pct = (int) (cur * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            listener.onProgress(pct);
                        }
                    }
                }

                in.close();
                c.disconnect();
                downloaded = bos.toByteArray();
                if (isThumbnail && cache != null && downloaded.length > 0) cache.PutFileCache("thumbs", cacheKeyThumb, downloaded);
            } else {
                c.disconnect();
                throw new RuntimeException("Download failed: " + resCode);
            }
        }

        // decrypt in memory
        byte[] fkSlice = Arrays.copyOfRange(fileKey, 0, 32);
        byte[] plainData;
        if (isThumbnail) {
            Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", fkSlice);
            plainData = sm.DeBin(downloaded);
        } else {
            Bencrypt.SymMaster smx = new Bencrypt.SymMaster("gcmx1", fkSlice);
            long ciphSz = smx.AfterSize(origSz);
            byte[] pureEncBytes = Arrays.copyOfRange(downloaded, 0, (int) ciphSz);
            plainData = smx.DeBin(pureEncBytes);
        }
        sclear(fileKey);
        sclear(fkSlice);
        return plainData;
    }

    // streaming metadata
    public static class StreamMeta {
        public byte[] fKey;
        public String fId;
        public long origSz;
    }

    // get stream metadata
    public StreamMeta GetStreamMeta(FileMap fm, String fileName) {
        if (!fm.fileMap.containsKey(fileName)) return null;
        byte[] fileInfo = unmask(fm.fileMap.get(fileName));
        StreamMeta meta = new StreamMeta();
        meta.fKey = Arrays.copyOfRange(fileInfo, 0, 44);
        meta.fId = GetPID(meta.fKey);
        byte[] sizeBytes = Arrays.copyOfRange(fileInfo, 44, 52);
        meta.origSz = opsec.DecodeInt(sizeBytes);
        sclear(fileInfo);
        return meta;
    }

    // fetch global IV of file
    public byte[] FetchGIv(String fld, String fId) throws Exception {
        URL u = new URL(srvUrl + "/api/media/" + fld + "/" + fId + "/dat");
        return fetchNet(u, 0, 11);
    }

    // download single chunk of file
    public byte[] DlChunk(String fld, String fId, byte[] fKey, long origSz, byte[] gIv, long chunkIdx) throws Exception {
        return DlChunk(fld, fId, fKey, origSz, gIv, chunkIdx, null);
    }

    // download single chunk of file with runner
    public byte[] DlChunk(String fld, String fId, byte[] fKey, long origSz, byte[] gIv, long chunkIdx, Runnable onDecrypting) throws Exception {
        URL u = new URL(srvUrl + "/api/media/" + fld + "/" + fId + "/dat");

        // calculate position
        Bencrypt.SymMaster smx = new Bencrypt.SymMaster("gcmx1", Arrays.copyOfRange(fKey, 0, 32));
        long totCiph = smx.AfterSize(origSz);
        long reqStart = 12 + (chunkIdx * (CHUNK_SZ + 16));
        long reqEnd = 12 + ((chunkIdx + 1) * (CHUNK_SZ + 16)) - 1;
        if (reqEnd > 12 + totCiph - 13) reqEnd = 12 + totCiph - 13;
        if (reqStart > reqEnd) return new byte[0];

        // fetch data
        byte[] cData = fetchNet(u, reqStart, reqEnd);
        if (cData.length == 0) return new byte[0];
        if (onDecrypting != null) {
            onDecrypting.run();
        }

        // decrypt chunk
        byte[] kSlice = Arrays.copyOfRange(fKey, 0, 32);
        byte[] iv = mkIv(gIv, chunkIdx);
        Cipher ciph = Cipher.getInstance("AES/GCM/NoPadding");
        ciph.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kSlice, "AES"), new GCMParameterSpec(128, iv));
        byte[] plaintext = ciph.doFinal(cData);
        sclear(kSlice);
        return plaintext;
    }
}
