package com.example.k7mediahub.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/**
 * Disk cache for encrypted server data (userdata, folder metadata, thumbnails).
 * SECURITY: Only stores encrypted bytes from the server. Never stores decrypted content.
 * Expiry: files older than 2 weeks are evicted on access.
 */
public class CacheManager {
    private static final long EXPIRY_MS = 14L * 24 * 60 * 60 * 1000; // 2 weeks
    private final File cacheRoot;

    public CacheManager(Context ctx) {
        cacheRoot = new File(ctx.getCacheDir(), "mh_cache");
        ensureDirs();
    }

    private void ensureDirs() {
        new File(cacheRoot, "userdata").mkdirs();
        new File(cacheRoot, "folders").mkdirs();
        new File(cacheRoot, "thumbs").mkdirs();
    }

    // ===== Key hashing (prevent filename leaking info) =====
    private static String hashKey(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++)
                sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return key.replace("/", "_");
        }
    }

    // ===== Core API =====

    /**
     * Get cached encrypted bytes, or fetch and cache.
     * @param type "userdata", "folders", or "thumbs"
     * @param key unique identifier (e.g. uHash, fPid, fPid/fId)
     * @param fetcher lambda that fetches encrypted bytes from server
     * @return encrypted bytes (caller decrypts in memory)
     */
    public byte[] getCachedOrFetch(String type, String key, Fetcher fetcher) throws Exception {
        File dir = new File(cacheRoot, type);
        File file = new File(dir, hashKey(key) + ".bin");

        // Check existing cache
        if (file.exists()) {
            if (System.currentTimeMillis() - file.lastModified() < EXPIRY_MS) {
                return readFile(file);
            } else {
                file.delete(); // expired
            }
        }

        // Fetch from server
        byte[] data = fetcher.fetch();
        if (data != null && data.length > 0) {
            writeFile(file, data);
        }
        return data;
    }

    /**
     * Store encrypted bytes directly.
     */
    public void put(String type, String key, byte[] data) {
        try {
            File dir = new File(cacheRoot, type);
            File file = new File(dir, hashKey(key) + ".bin");
            writeFile(file, data);
        } catch (Exception ignored) {}
    }

    /**
     * Get cached encrypted bytes (null if not cached or expired).
     */
    public byte[] get(String type, String key) {
        try {
            File dir = new File(cacheRoot, type);
            File file = new File(dir, hashKey(key) + ".bin");
            if (file.exists()) {
                if (System.currentTimeMillis() - file.lastModified() < EXPIRY_MS) {
                    return readFile(file);
                } else {
                    file.delete();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Clear all cached data.
     */
    public void clearAll() {
        deleteDir(cacheRoot);
        ensureDirs();
    }

    /**
     * Remove expired entries.
     */
    public void evictExpired() {
        evictDir(new File(cacheRoot, "userdata"));
        evictDir(new File(cacheRoot, "folders"));
        evictDir(new File(cacheRoot, "thumbs"));
    }

    // ===== File I/O =====
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
            if (f.isFile() && now - f.lastModified() >= EXPIRY_MS) {
                f.delete();
            }
        }
    }

    // ===== Functional interface =====
    public interface Fetcher {
        byte[] fetch() throws Exception;
    }
}
