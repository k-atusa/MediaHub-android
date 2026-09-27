package com.example.k7mediahub.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// cache manager (memory and file)
public class MHcache {
    // ===== memory cache engine =====
    public static final int MAX_THUMBNAIL = 512;
    public static final int MAX_FILEMAP = 8;

    public static class MemCache<K, V> {
        private final int limit;
        private final Map<K, V> map;

        public MemCache(int limit) {
            this.limit = limit;
            this.map = Collections.synchronizedMap(new LinkedHashMap<K, V>(limit + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    if (size() > MemCache.this.limit) {
                        if (eldest.getValue() instanceof byte[]) {
                            MHcore.sclear((byte[]) eldest.getValue());
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        public V Get(K key) { return map.get(key); }
        public void Put(K key, V value) { map.put(key, value); }
        public boolean ContainsKey(K key) { return map.containsKey(key); }
        public int Size() { return map.size(); }
        public void Clear() { map.clear(); }
    }

    // LRU memory cache for thumbnail and filemap
    public static final MemCache<String, byte[]> thumbMemCache = new MemCache<>(MAX_THUMBNAIL);
    public static final MemCache<String, MHcore.FileMap> fileMapMemCache = new MemCache<>(MAX_FILEMAP);

    // ===== memory cache access =====
    public static byte[] GetMemThumb(String key) { return thumbMemCache.Get(key); }
    public static void PutMemThumb(String key, byte[] thumb) { thumbMemCache.Put(key, thumb); }
    public static boolean HasMemThumb(String key) { return thumbMemCache.ContainsKey(key); }
    public static int GetMemThumbCount() { return thumbMemCache.Size(); }
    public static MHcore.FileMap GetMemFileMap(String key) { return fileMapMemCache.Get(key); }
    public static void PutMemFileMap(String key, MHcore.FileMap fm) { fileMapMemCache.Put(key, fm); }
    public static void ClearMemCache() {
        thumbMemCache.Clear();
        fileMapMemCache.Clear();
    }

    // ===== file cache =====
    public static final long FILE_EXPIRE_MS = 14L * 24 * 60 * 60 * 1000;
    public static final int MAX_FILE_THUMB = 4096;
    private final File fileCacheRoot;
    public MHcache(Context ctx) {
        fileCacheRoot = new File(ctx.getCacheDir(), "filecache");
        ensureFileDirs();
        TrimFileThumbs();
    }

    // create cache directories
    private void ensureFileDirs() {
        new File(fileCacheRoot, "userdata").mkdirs();
        new File(fileCacheRoot, "folders").mkdirs();
        new File(fileCacheRoot, "thumbs").mkdirs();
    }

    // get cached file path
    private File getFileCachePath(String type, String key) {
        String pos;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", digest[i]));
            pos = sb.toString();
        } catch (Exception e) {
            pos = key.replace("/", "_");
        }
        return new File(new File(fileCacheRoot, type), pos + ".bin");
    }

    // fetcher interface
    public interface Fetcher {
        byte[] Fetch() throws Exception;
    }

    // get cached bytes from file
    public byte[] GetFileCache(String type, String key) {
        try {
            File file = getFileCachePath(type, key);
            if (file.exists()) {
                if (System.currentTimeMillis() - file.lastModified() < FILE_EXPIRE_MS) {
                    return readFile(file);
                }
                file.delete(); // delete if expired
            }
        } catch (Exception ignored) { }
        return null;
    }

    // get file cache or fetch from network
    public byte[] GetOrFetchFileCache(String type, String key, Fetcher fetcher) throws Exception {
        byte[] cached = GetFileCache(type, key);
        if (cached != null) return cached;
        byte[] data = fetcher.Fetch();
        if (data != null && data.length > 0) PutFileCache(type, key, data);
        return data;
    }

    // store encrypted bytes in file cache
    public void PutFileCache(String type, String key, byte[] data) {
        try {
            writeFile(getFileCachePath(type, key), data);
        } catch (Exception ignored) { }
    }

    // clear all file cache
    public void ClearFileCache() {
        deleteDir(fileCacheRoot);
        ensureFileDirs();
    }

    // evict expired file cache entries
    public void EvictFileCache() {
        evictDir(new File(fileCacheRoot, "userdata"));
        evictDir(new File(fileCacheRoot, "folders"));
        evictDir(new File(fileCacheRoot, "thumbs"));
    }

    // trim file thumbnail cache
    public void TrimFileThumbs() {
        File thumbDir = new File(fileCacheRoot, "thumbs");
        if (!thumbDir.isDirectory()) return;
        File[] files = thumbDir.listFiles();
        if (files == null || files.length <= MAX_FILE_THUMB) return;

        // leave 2048 thumbnails
        List<File> list = new ArrayList<>(files.length);
        for (File f : files) {
            if (f.isFile()) list.add(f);
        }
        if (list.size() <= MAX_FILE_THUMB) return;

        Collections.shuffle(list);
        int toDelete = list.size() - 2048;
        for (int i = 0; i < toDelete; i++) list.get(i).delete();
    }

    // ===== helpers =====
    public void ClearAllCache() {
        ClearFileCache();
        ClearMemCache();
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = in.read(b)) != -1) buf.write(b, 0, r);
        in.close();
        return buf.toByteArray();
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }

    private static void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) deleteDir(child);
            }
        }
        dir.delete();
    }

    private static void evictDir(File dir) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (f.isFile() && now - f.lastModified() >= FILE_EXPIRE_MS) {
                f.delete();
            }
        }
    }
}
