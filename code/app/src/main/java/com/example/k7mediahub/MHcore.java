package com.example.k7mediahub;

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

import com.example.k7mediahub.app.CacheManager;

// Core logic for auth, crypto, and network
public class MHcore {
    // Shared pepper string
    private static final String PEPPER = "_PROJECT_WHY_MEDIAHUB_PEPPER_2026_!@#$";
    // Config filename
    private static final String CFG_FILE = "config.dat";
    // Data chunk size
    private static final int CHUNK_SZ = 1048576;

    // connection
    public String srvUrl;
    public String uName;
    public String uHash;
    public String uMemo = "";
    public boolean ignTLS = false;
    private byte[] uKey;
    public Map<String, byte[]> fldMap = new HashMap<>();
    private final Bencrypt.Masker masker;
    private final Bencrypt bencrypt;
    private final Opsec opsec;

    // Cache manager (set externally by SvcMH)
    public CacheManager cache;

    // folder structure
    public static class FolderFiles {
        public String fPid;
        public byte[] fKey; // masked
        public Map<String, byte[]> flMap; // values masked

        public FolderFiles(String fPid, byte[] fKey, Map<String, byte[]> flMap) {
            this.fPid = fPid;
            this.fKey = fKey;
            this.flMap = flMap;
        }
    }

    // Init core and trust all SSL
    public MHcore(boolean ignTLS) {
        this.ignTLS = ignTLS;
        this.masker = Bencrypt.Masker.GetMasker();
        this.bencrypt = new Bencrypt();
        this.opsec = new Opsec();
        if (ignTLS) trustAllSsl();
    }

    // Bypass SSL validation
    private void trustAllSsl() {
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

    // ===== Key Masking Helpers =====
    // Unmask master key securely
    private byte[] getUnmaskedKey() {
        return masker.XOR(uKey);
    }

    // Unmask a masked byte array, caller must zero the result after use
    private byte[] unmask(byte[] masked) {
        return masker.XOR(masked);
    }

    // Mask all byte[] values in a map (in-place)
    private void maskMapValues(Map<String, byte[]> map) {
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            e.setValue(masker.XOR(e.getValue()));
        }
    }

    // Create a copy of a map with all byte[] values unmasked (caller must zero values after use)
    private Map<String, byte[]> unmaskMapCopy(Map<String, byte[]> maskedMap) {
        Map<String, byte[]> result = new HashMap<>();
        for (Map.Entry<String, byte[]> e : maskedMap.entrySet()) {
            result.put(e.getKey(), masker.XOR(e.getValue()));
        }
        return result;
    }

    // Zero all byte[] values in a map
    private static void zeroMap(Map<String, byte[]> map) {
        for (byte[] v : map.values()) {
            Arrays.fill(v, (byte) 0);
        }
    }

    // Generate dedicated IV slice with incremental block counter offset
    private byte[] mkIv(byte[] gIv, long c) {
        byte[] iv = Arrays.copyOf(gIv, gIv.length);
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(c);
        byte[] ctr = b.array();
        for (int i = 0; i < 8; i++)
            iv[4 + i] ^= ctr[i];
        return iv;
    }

    // Pull specific remote byte array range chunks over HTTP
    private byte[] fetchNet(URL u, long start, long end) throws Exception {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1)
            out.write(buf, 0, r);
        in.close();
        c.disconnect();
        return out.toByteArray();
    }

    // Generate scaled or center-cropped thumbnail byte array from bitmap
    private byte[] mkThumb(Bitmap img) {
        if (img == null)
            return null;
        int w = img.getWidth();
        int h = img.getHeight();
        double r = (double) w / h;
        Bitmap out;

        if (r >= 0.6666 && r <= 1.5) { // normal ratio
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

        // JPEG 70%
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        if (out != img) out.recycle();
        return baos.toByteArray();
    }

    // Extract thumbnail from local image path
    private byte[] imgThumb(String path) {
        Bitmap img = BitmapFactory.decodeFile(path);
        byte[] res = mkThumb(img);
        if (img != null) img.recycle();
        return res;
    }

    // Extract video frame thumbnail from local video path
    private byte[] vidThumb(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null)
                frame = retriever.getFrameAtTime();
            byte[] res = mkThumb(frame);
            if (frame != null)
                frame.recycle();
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

    // Convert key chunk to object PID string
    public String ObjPid(byte[] key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 32; i < 44; i++)
            sb.append(String.format("%02x", key[i]));
        return sb.toString();
    }

    // Load local client settings from file (url, username, ignTLS, memo)
    public void LoadCfg(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), CFG_FILE);
        if (!f.exists())
            return;
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int r;
        while ((r = in.read(b)) != -1)
            buf.write(b, 0, r);
        in.close();
        String[] pts = buf.toString("UTF-8").split("\n", 4);
        if (pts.length >= 2) {
            this.srvUrl = pts[0];
            this.uName = pts[1];
            if (pts.length >= 3) {
                if ("1".equals(pts[2])) {
                    this.ignTLS = true;
                    if (pts.length == 4) this.uMemo = pts[3];
                } else if ("0".equals(pts[2])) {
                    this.ignTLS = false;
                    if (pts.length == 4) this.uMemo = pts[3];
                } else {
                    // Legacy format compatibility (pts[2] was uMemo)
                    this.uMemo = pts[2];
                }
            }
        }
    }

    // Save local client settings to file
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

    public void SaveCfg(Context ctx, String url, String name, String memo) throws Exception {
        SaveCfg(ctx, url, name, this.ignTLS, memo);
    }

    // Derive keys and set user hash
    public void Login(String name, String pw) throws Exception {
        this.uName = name;
        byte[] p = Bencode.NormPW(pw);
        byte[] s = Bencrypt.SHA3256((name + PEPPER).getBytes(StandardCharsets.UTF_8));
        Bencrypt.HashMaster hm = new Bencrypt.HashMaster("arg2st");
        byte[][] keys = hm.KDF(p, s);

        byte[] hash = Bencrypt.SHA3256(keys[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++)
            sb.append(String.format("%02x", hash[i]));
        this.uHash = sb.toString();
        this.uKey = masker.XOR(keys[1]);

        Arrays.fill(p, (byte) 0);
        Arrays.fill(keys[0], (byte) 0);
        Arrays.fill(keys[1], (byte) 0);
    }

    // ===== Auto Login Key Serialization =====
    // Export session credentials for auto-login storage: only uHash + unmasked raw uKey
    // Returns: uHash(hex, 32 chars) + "|" + Base64(rawUKey)
    public byte[] exportAutoLoginData() {
        if (uHash == null || uKey == null) return null;
        byte[] rawKey = getUnmaskedKey();
        if (rawKey == null) return null;
        String b64Key = java.util.Base64.getEncoder().encodeToString(rawKey);
        Arrays.fill(rawKey, (byte) 0);
        String data = uHash + "|" + b64Key;
        return data.getBytes(StandardCharsets.UTF_8);
    }

    // Restore session from auto-login data (skips Argon2 KDF)
    public boolean restoreAutoLogin(byte[] data, String url, String name, boolean ignTLS) throws Exception {
        String str = new String(data, StandardCharsets.UTF_8);
        String[] parts = str.split("\\|", 4);
        byte[] rawKey;
        if (parts.length == 2) {
            // New format: uHash + "|" + b64Key (raw key)
            this.uHash = parts[0];
            rawKey = java.util.Base64.getDecoder().decode(parts[1]);
        } else if (parts.length == 4) {
            // Legacy format: uHash + "|" + srvUrl + "|" + uName + "|" + b64Key
            this.uHash = parts[0];
            rawKey = java.util.Base64.getDecoder().decode(parts[3]);
        } else {
            return false;
        }

        // Mask the raw key with the current process's Masker instance
        this.uKey = masker.XOR(rawKey);
        Arrays.fill(rawKey, (byte) 0);

        this.srvUrl = (url != null && !url.isEmpty()) ? url : (parts.length == 4 ? parts[1] : "");
        this.uName = (name != null && !name.isEmpty()) ? name : (parts.length == 4 ? parts[2] : "");
        this.ignTLS = ignTLS;

        if (ignTLS) trustAllSsl();
        return true;
    }

    // Check account existence
    public boolean CheckAcc() throws Exception {
        URL u = new URL(srvUrl + "/api/userdata/" + uHash);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("GET");
        int res = c.getResponseCode();
        c.disconnect();
        return res == 200;
    }

    // Fetch and sync folder map from remote server
    public Map<String, byte[]> GetFlds() throws Exception {
        // download folder map
        if (uHash == null) throw new IllegalStateException("Not authenticated");
        URL u = new URL(srvUrl + "/api/userdata/" + uHash);

        // Try cache first, fetch if missing
        byte[] content = null;
        if (cache != null) {
            content = cache.getCachedOrFetch("userdata", uHash, () -> {
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int r;
                    while ((r = in.read(b)) != -1) buf.write(b, 0, r);
                    in.close();
                    conn.disconnect();
                    return buf.toByteArray();
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
                InputStream in = c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int r;
                while ((r = in.read(b)) != -1) buf.write(b, 0, r);
                in.close();
                content = buf.toByteArray();
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
            byte[] rawUK = getUnmaskedKey();
            byte[] keySlice = Arrays.copyOfRange(rawUK, 0, 32);
            Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", keySlice);
            this.fldMap = opsec.DecodeCfg(sm.DeBin(content));
            Arrays.fill(rawUK, (byte) 0);
            Arrays.fill(keySlice, (byte) 0);
            // Mask all folder key values at rest
            maskMapValues(this.fldMap);
        } else {
            this.fldMap = new HashMap<>();
        }

        return this.fldMap;
    }

    // Create a new folder storage container on server
    public void MkFld(String name) throws Exception {
        // Check with unmasked keys
        if (this.fldMap.containsKey(name)) throw new IllegalArgumentException("Folder name already exists!");
        byte[] folderKey = new byte[44];
        System.arraycopy(bencrypt.Random(32), 0, folderKey, 0, 32);
        System.arraycopy(bencrypt.Random(12), 0, folderKey, 32, 12);
        // Store masked
        this.fldMap.put(name, masker.XOR(folderKey));

        // Unmask all values for encoding, then zero
        Map<String, byte[]> plainMap = unmaskMapCopy(this.fldMap);

        byte[] rawUK = getUnmaskedKey();
        byte[] keySlice = Arrays.copyOfRange(rawUK, 0, 32);
        Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", keySlice);
        byte[] enc = sm.EnBin(opsec.EncodeCfg(plainMap));
        Arrays.fill(rawUK, (byte) 0);
        Arrays.fill(keySlice, (byte) 0);
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

        if (c.getResponseCode() != 200) {
            throw new RuntimeException("Failed to create folder: code " + c.getResponseCode());
        }
        c.disconnect();

        // Invalidate userdata cache
        if (cache != null) cache.put("userdata", uHash, enc);
    }

    // Fetch and decrypt file mapping for specific folder
    public FolderFiles GetFiles(String name) throws Exception {
        if (!this.fldMap.containsKey(name)) throw new IllegalArgumentException("Folder not found");
        // Unmask folder key for use
        byte[] fKey = unmask(this.fldMap.get(name));
        String fPid = ObjPid(fKey);

        byte[] content = null;
        String cacheKey = fPid + "/names";

        // Try cache first
        if (cache != null) {
            URL fUrl = new URL(srvUrl + "/api/storage/" + fPid + "/names");
            content = cache.getCachedOrFetch("folders", cacheKey, () -> {
                HttpURLConnection conn = (HttpURLConnection) fUrl.openConnection();
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int r;
                    while ((r = in.read(b)) != -1) buf.write(b, 0, r);
                    in.close();
                    conn.disconnect();
                    return buf.toByteArray();
                }
                conn.disconnect();
                return new byte[0];
            });
        } else {
            URL u = new URL(srvUrl + "/api/storage/" + fPid + "/names");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            int resCode = c.getResponseCode();
            if (resCode == 200) {
                InputStream in = c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int r;
                while ((r = in.read(b)) != -1) buf.write(b, 0, r);
                in.close();
                content = buf.toByteArray();
            }
            c.disconnect();
        }

        Map<String, byte[]> flMap = new HashMap<>();
        if (content != null && content.length > 0) {
            byte[] keySlice = Arrays.copyOfRange(fKey, 0, 32);
            Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", keySlice);
            flMap = opsec.DecodeCfg(sm.DeBin(content));
            Arrays.fill(keySlice, (byte) 0);
            // Mask all file info values at rest
            maskMapValues(flMap);
        }

        // Store fKey as masked, zero plain copy
        byte[] maskedFKey = masker.XOR(fKey);
        Arrays.fill(fKey, (byte) 0);
        return new FolderFiles(fPid, maskedFKey, flMap);
    }

    // Encrypt, pad, and upload media file along with thumbnail to server
    public boolean UpFile(Context ctx, FolderFiles ff, File file) throws Exception {
        // get info and make new file key
        String name = file.getName();
        long origSz = file.length();
        String path = file.getAbsolutePath();

        byte[] fk = new byte[44];
        System.arraycopy(bencrypt.Random(32), 0, fk, 0, 32);
        System.arraycopy(bencrypt.Random(12), 0, fk, 32, 12);
        String fpid = ObjPid(fk);

        // get thumbnail
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
            byte[] fkSlice = Arrays.copyOfRange(fk, 0, 32);
            Bencrypt.SymMaster tSm = new Bencrypt.SymMaster("gcm1", fkSlice);
            byte[] encThumb = tSm.EnBin(thumb);
            URL tUrl = new URL(srvUrl + "/api/media/" + ff.fPid + "/" + fpid + "/thumb");
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

        // encrypt to temp file
        File tFile = new File(ctx.getCacheDir(), "up_" + fpid + ".dat");
        FileOutputStream tOut = new FileOutputStream(tFile);
        byte[] fkSlice = Arrays.copyOfRange(fk, 0, 32);
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

        // upload file
        URL u = new URL(srvUrl + "/api/media/" + ff.fPid + "/" + fpid + "/dat");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("X-User-Hash", uHash);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        long tLen = tFile.length();
        c.setFixedLengthStreamingMode(tLen);
        c.setDoOutput(true);

        // Upload stream
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

        // encode original size
        byte[] sizeBuf = opsec.EncodeInt(origSz, 8);
        byte[] flInfo = new byte[52];
        System.arraycopy(fk, 0, flInfo, 0, 44);
        System.arraycopy(sizeBuf, 0, flInfo, 44, 8);
        // Store masked
        ff.flMap.put(name, masker.XOR(flInfo));

        // Unmask folder key and file map for encoding
        byte[] plainFKey = unmask(ff.fKey);
        Map<String, byte[]> plainFlMap = unmaskMapCopy(ff.flMap);

        byte[] fKeySlice = Arrays.copyOfRange(plainFKey, 0, 32);
        Bencrypt.SymMaster sm = new Bencrypt.SymMaster("gcm1", fKeySlice);
        byte[] encMap = sm.EnBin(opsec.EncodeCfg(plainFlMap));
        Arrays.fill(fKeySlice, (byte) 0);
        Arrays.fill(fkSlice, (byte) 0);
        Arrays.fill(plainFKey, (byte) 0);
        zeroMap(plainFlMap);

        // upload file map
        URL uMap = new URL(srvUrl + "/api/storage/" + ff.fPid + "/names");
        HttpURLConnection cMap = (HttpURLConnection) uMap.openConnection();
        cMap.setRequestMethod("POST");
        cMap.setRequestProperty("X-User-Hash", uHash);
        cMap.setDoOutput(true);
        cMap.setRequestProperty("Content-Type", "application/octet-stream");
        OutputStream outMap = cMap.getOutputStream();
        outMap.write(encMap);
        outMap.close();

        if (cMap.getResponseCode() != 200) throw new RuntimeException("Failed to sync metadata: code " + cMap.getResponseCode());
        cMap.disconnect();

        // Update folder cache
        if (cache != null) cache.put("folders", ff.fPid + "/names", encMap);
        return true;
    }

    // Download media file to Download folder
    public String DnFile(Context ctx, FolderFiles ff, String fileName) throws Exception {
        // check validity, get file key (unmask)
        if (!ff.flMap.containsKey(fileName)) throw new IllegalArgumentException("File not found in metadata");
        byte[] flInfo = unmask(ff.flMap.get(fileName));

        byte[] fk = Arrays.copyOfRange(flInfo, 0, 44);
        byte[] sizeBytes = Arrays.copyOfRange(flInfo, 44, 52);
        long origSz = opsec.DecodeInt(sizeBytes);
        String fpid = ObjPid(fk);
        Arrays.fill(flInfo, (byte) 0);

        byte[] fkSlice = Arrays.copyOfRange(fk, 0, 32);
        Bencrypt.SymMaster smx = new Bencrypt.SymMaster("gcmx1", fkSlice);
        long ciphSz = smx.AfterSize(origSz);

        // download file
        URL u = new URL(srvUrl + "/api/media/" + ff.fPid + "/" + fpid + "/dat");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("GET");
        int resCode = c.getResponseCode();

        // download and decrypt
        if (resCode == 200 || resCode == 206) {
            IO1.VFile destFile = IO1.CreateDownloadsFile(ctx, fileName);
            if (destFile == null) throw new RuntimeException("Failed to create download file");
            OutputStream fos = destFile.OpenWriter(ctx, false);
            InputStream in = c.getInputStream();
            
            // Decrypt stream with progress
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
            Arrays.fill(fkSlice, (byte) 0);
            return destFile.GetUri().toString();
        } else {
            c.disconnect();
            Arrays.fill(fkSlice, (byte) 0);
            throw new RuntimeException("Download failed: " + resCode);
        }
    }

    // Progress callback for download operations
    public interface ProgressListener {
        void onProgress(int percent);
    }

    // Download and decrypt entire file or thumbnail directly into memory
    public byte[] DnMem(FolderFiles ff, String fileName, boolean isThumbnail) throws Exception {
        return DnMem(ff, fileName, isThumbnail, null);
    }

    // Download and decrypt with progress reporting
    public byte[] DnMem(FolderFiles ff, String fileName, boolean isThumbnail, ProgressListener listener) throws Exception {
        // check validity, get file key (unmask)
        if (!ff.flMap.containsKey(fileName)) throw new IllegalArgumentException("File not found in metadata");
        byte[] flInfo = unmask(ff.flMap.get(fileName));

        byte[] fk = Arrays.copyOfRange(flInfo, 0, 44);
        byte[] sizeBytes = Arrays.copyOfRange(flInfo, 44, 52);
        long origSz = opsec.DecodeInt(sizeBytes);
        String fpid = ObjPid(fk);
        Arrays.fill(flInfo, (byte) 0);

        // check if thumbnail
        String typ = isThumbnail ? "thumb" : "dat";
        String cacheKeyThumb = ff.fPid + "/" + fpid + "/" + typ;

        // Try cache for thumbnails (encrypted bytes)
        byte[] downloaded = null;
        if (isThumbnail && cache != null) {
            downloaded = cache.get("thumbs", cacheKeyThumb);
        }

        if (downloaded == null) {
            URL u = new URL(srvUrl + "/api/media/" + ff.fPid + "/" + fpid + "/" + typ);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            int resCode = c.getResponseCode();

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

                // Cache thumbnail encrypted bytes
                if (isThumbnail && cache != null && downloaded.length > 0) {
                    cache.put("thumbs", cacheKeyThumb, downloaded);
                }
            } else {
                c.disconnect();
                throw new RuntimeException("Download failed: " + resCode);
            }
        }

        // decrypt in-memory
        byte[] fkSlice = Arrays.copyOfRange(fk, 0, 32);
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
        Arrays.fill(fkSlice, (byte) 0);
        return plainData;
    }

    // Get original file size from masked flMap entry
    public long GetFileSize(FolderFiles ff, String fileName) {
        if (!ff.flMap.containsKey(fileName)) return -1;
        byte[] flInfo = unmask(ff.flMap.get(fileName));
        byte[] sizeBytes = Arrays.copyOfRange(flInfo, 44, 52);
        long sz = opsec.DecodeInt(sizeBytes);
        Arrays.fill(flInfo, (byte) 0);
        return sz;
    }

    // Partial decrypt for streaming
    public byte[] DlPart(String fld, String fId, byte[] fKey, long origSz, long ptStart, int ptLen) throws Exception {
        URL u = new URL(srvUrl + "/api/media/" + fld + "/" + fId + "/dat");
        byte[] gIv = fetchNet(u, 0, 11);
        if (gIv.length < 12) return new byte[0];

        // calculate offset
        Bencrypt.SymMaster smx = new Bencrypt.SymMaster("gcmx1", Arrays.copyOfRange(fKey, 0, 32));
        long totCiph = smx.AfterSize(origSz);
        long sChunk = ptStart / CHUNK_SZ;
        long eChunk = (ptStart + ptLen - 1) / CHUNK_SZ;
        long reqStart = 12 + (sChunk * (CHUNK_SZ + 16));
        long reqEnd = 12 + ((eChunk + 1) * (CHUNK_SZ + 16)) - 1;
        if (reqEnd > 12 + totCiph - 13) reqEnd = 12 + totCiph - 13;

        // prepare manual decrypt
        byte[] cData = fetchNet(u, reqStart, reqEnd);
        Cipher ciph = Cipher.getInstance("AES/GCM/NoPadding");
        ByteArrayOutputStream ptBuf = new ByteArrayOutputStream();
        int off = 0;
        long curC = sChunk;
        byte[] kSlice = Arrays.copyOfRange(fKey, 0, 32);

        // decrypt
        while (off < cData.length) {
            int bLen = Math.min(CHUNK_SZ + 16, cData.length - off);
            byte[] iv = mkIv(gIv, curC++);
            ciph.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kSlice, "AES"), new GCMParameterSpec(128, iv));
            ptBuf.write(ciph.doFinal(cData, off, bLen));
            off += bLen;
        }

        // cut to requested size
        byte[] fullPt = ptBuf.toByteArray();
        int sIdx = (int) (ptStart % CHUNK_SZ);
        int fLen = Math.min(ptLen, fullPt.length - sIdx);
        if (sIdx < 0 || sIdx >= fullPt.length) return new byte[0];
        return Arrays.copyOfRange(fullPt, sIdx, sIdx + fLen);
    }
}