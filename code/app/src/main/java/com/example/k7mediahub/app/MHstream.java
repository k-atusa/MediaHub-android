package com.example.k7mediahub.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.k7mediahub.Bencrypt;

// http proxy server for encrypted media streaming
public class MHstream {
    // ===== status notification =====
    private volatile java.util.function.Consumer<String> statusConsumer;

    public void SetStatusCb(java.util.function.Consumer<String> consumer) {
        this.statusConsumer = consumer;
    }

    private void notifyStatus(String status) {
        if (statusConsumer != null) {
            statusConsumer.accept(status);
        }
    }

    // ===== chunk plaintext LRU cache =====
    private static final long STREAM_CHUNK = 2 * 1048576;
    private static final int CHUNK_CACHE_SIZE = 16;
    private final LinkedHashMap<String, byte[]> ptCache = new LinkedHashMap<>(CHUNK_CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            if (size() > CHUNK_CACHE_SIZE) {
                if (eldest.getValue() != null) MHcore.sclear(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    // streaming info
    public static class StreamInfo {
        public String fPid;
        public String fId;
        public byte[] fKey;
        public long origSz;
        public String mime;
        public byte[] gIv;
    }

    // io connection fields
    private MHcore core;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientExecutor;
    private int port;
    private volatile boolean running;
    private volatile StreamInfo activeSession;
    private volatile String activeSid;
    private final Bencrypt.Masker masker = Bencrypt.Masker.GetMasker();

    // start streaming server
    public void Start(MHcore core) throws IOException {
        this.core = core;
        this.serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        this.port = serverSocket.getLocalPort();
        this.running = true;
        this.clientExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Streamer-Client");
            t.setDaemon(true);
            return t;
        });
        this.acceptThread = new Thread(this::acceptLoop, "k7MHstream");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    // stop streaming server
    public void Stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            clientExecutor = null;
        }
        clearActiveSession();
    }

    // add streaming session (replaces any existing session)
    public synchronized String AddSession(String fPid, String fId, byte[] fKey, long origSz, String mime) {
        clearActiveSession();

        String sid = UUID.randomUUID().toString().replace("-", "");
        StreamInfo info = new StreamInfo();
        info.fPid = fPid;
        info.fId = fId;
        info.fKey = masker.XOR(fKey);
        info.origSz = origSz;
        info.mime = mime;

        this.activeSession = info;
        this.activeSid = sid;
        return "http://127.0.0.1:" + port + "/" + sid;
    }

    // clear active session and zeroize secrets
    private synchronized void clearActiveSession() {
        if (activeSession != null) {
            if (activeSession.fKey != null) {
                MHcore.sclear(activeSession.fKey);
                activeSession.fKey = null;
            }
            if (activeSession.gIv != null) {
                MHcore.sclear(activeSession.gIv);
                activeSession.gIv = null;
            }
            activeSession = null;
        }
        activeSid = null;
        clearPtCache();
    }

    // clear plaintext chunk cache
    private void clearPtCache() {
        synchronized (ptCache) {
            for (byte[] b : ptCache.values()) {
                if (b != null) MHcore.sclear(b);
            }
            ptCache.clear();
        }
    }

    // get decrypted plaintext chunk
    private byte[] getPlainChunk(StreamInfo info, long chunkIdx) throws Exception {
        // check if chunk is cached
        String cacheKey = info.fPid + "/" + info.fId + "@" + chunkIdx;
        synchronized (ptCache) {
            byte[] cached = ptCache.get(cacheKey);
            if (cached != null) {
                notifyStatus("Loaded chunk " + chunkIdx + " (Cached)");
                return cached;
            }
        }

        // load global IV
        if (info.gIv == null) {
            notifyStatus("Fetching global IV...");
            info.gIv = core.FetchGIv(info.fPid, info.fId);
            if (info.gIv == null || info.gIv.length < 12) {
                return new byte[0];
            }
        }

        // download and decrypt chunk
        notifyStatus("Fetching chunk " + chunkIdx);
        byte[] rawKey = masker.XOR(info.fKey);
        byte[] plaintext;
        try {
            plaintext = core.DlChunk(info.fPid, info.fId, rawKey, info.origSz, info.gIv, chunkIdx, () -> {
                notifyStatus("Decrypting chunk " + chunkIdx);
            });
        } finally {
            MHcore.sclear(rawKey);
        }

        // put chunk into cache
        if (plaintext != null && plaintext.length > 0) {
            synchronized (ptCache) {
                ptCache.put(cacheKey, plaintext);
            }
            notifyStatus("Loaded chunk " + chunkIdx);
        }
        return plaintext != null ? plaintext : new byte[0];
    }

    // serve range bytes
    private void serveRange(StreamInfo info, long rangeStart, long rangeEnd, OutputStream out) throws Exception {
        // calculate chunks required
        long firstChunk = rangeStart / MHcore.CHUNK_SZ;
        long lastChunk = rangeEnd / MHcore.CHUNK_SZ;

        // get plaintext chunks and slice
        for (long ci = firstChunk; ci <= lastChunk; ci++) {
            byte[] pt = getPlainChunk(info, ci);
            if (pt.length == 0) break;

            long chunkGlobal = ci * MHcore.CHUNK_SZ;
            int sliceStart = (int) Math.max(0, rangeStart - chunkGlobal);
            int sliceEnd = (int) Math.min(pt.length, rangeEnd - chunkGlobal + 1);
            if (sliceStart >= sliceEnd) continue;

            out.write(pt, sliceStart, sliceEnd - sliceStart);
            out.flush();
        }
    }

    // accept client loop
    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                if (clientExecutor != null && !clientExecutor.isShutdown()) {
                    clientExecutor.submit(() -> handleClient(client));
                } else {
                    client.close();
                }
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    // handle client connection
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000); // timeout 30s
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            // parse request
            String requestLine = in.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendError(out, 400, "Bad Request");
                client.close();
                return;
            }
            String method = parts[0];
            String path = parts[1];

            // make header into map
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }

            // check session ID
            String sid = path.startsWith("/") ? path.substring(1) : path;
            int qIdx = sid.indexOf('?');
            if (qIdx >= 0)
                sid = sid.substring(0, qIdx);

            StreamInfo info = activeSession;
            String curSid = activeSid;
            if (info == null || curSid == null || !curSid.equals(sid)) {
                sendError(out, 404, "Not Found");
                client.close();
                return;
            }

            // CORS settings
            if ("OPTIONS".equalsIgnoreCase(method)) {
                String resp = "HTTP/1.1 204 No Content\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Range\r\n"
                        + "Access-Control-Max-Age: 86400\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(resp.getBytes());
                out.flush();
                client.close();
                return;
            }

            // parse range header
            String rangeHeader = headers.get("range");
            long rangeStart = 0;
            long rangeEnd = info.origSz - 1;
            boolean isRange = false;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isRange = true;
                String range = rangeHeader.substring(6);
                String[] rangeParts = range.split("-", 2);
                if (!rangeParts[0].isEmpty()) {
                    rangeStart = Long.parseLong(rangeParts[0].trim());
                }
                if (rangeParts.length > 1 && !rangeParts[1].isEmpty()) {
                    rangeEnd = Long.parseLong(rangeParts[1].trim());
                }
            }
            if (rangeEnd >= info.origSz) rangeEnd = info.origSz - 1;

            // make response
            StringBuilder resp = new StringBuilder();
            if (isRange) {
                resp.append("HTTP/1.1 206 Partial Content\r\n");
                resp.append("Content-Range: bytes ").append(rangeStart).append("-")
                        .append(rangeEnd).append("/").append(info.origSz).append("\r\n");
                resp.append("Content-Length: ").append(rangeEnd - rangeStart + 1).append("\r\n");
            } else {
                resp.append("HTTP/1.1 200 OK\r\n");
                resp.append("Content-Length: ").append(info.origSz).append("\r\n");
            }
            resp.append("Content-Type: ").append(info.mime).append("\r\n");
            resp.append("Accept-Ranges: bytes\r\n");
            resp.append("Connection: close\r\n");
            resp.append("Access-Control-Allow-Origin: *\r\n");
            resp.append("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
            resp.append("Access-Control-Allow-Headers: Range\r\n");
            resp.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
            resp.append("\r\n");
            out.write(resp.toString().getBytes());

            // serve data
            long offset = rangeStart;
            long remaining = rangeEnd - rangeStart + 1;
            while (remaining > 0) {
                long serveLen = Math.min(STREAM_CHUNK, remaining);
                long serveEnd = offset + serveLen - 1;
                serveRange(info, offset, serveEnd, out);
                offset += serveLen;
                remaining -= serveLen;
            }
            out.flush();
            client.close();

        } catch (Exception e) {
            try {
                client.close();
            } catch (Exception ignored) { }
        }
    }

    // send error response
    private void sendError(OutputStream out, int code, String msg) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\n" + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes());
        out.flush();
    }
}
