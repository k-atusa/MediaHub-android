package com.example.k7mediahub.app;

import com.example.k7mediahub.MHcore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight HTTP proxy server for encrypted media streaming.
 * Runs on localhost, serves decrypted byte ranges via MHcore.DlPart.
 */
public class LocalStreamer {
    // io fields
    private MHcore core;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int port;
    private volatile boolean running;
    private final Map<String, StreamInfo> sessions = new ConcurrentHashMap<>();

    // streaming info
    public static class StreamInfo {
        public String fPid;
        public String fId;
        public byte[] fKey;
        public long origSz;
        public String mime;
    }

    // start streaming
    public void start(MHcore core) throws IOException {
        this.core = core;
        this.serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        this.port = serverSocket.getLocalPort();
        this.running = true;
        this.acceptThread = new Thread(this::acceptLoop, "LocalStreamer");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    // get port
    public int getPort() {
        return port;
    }

    // add streaming session
    public String addSession(String fPid, String fId, byte[] fKey, long origSz, String mime) {
        String sid = UUID.randomUUID().toString().replace("-", "");
        StreamInfo info = new StreamInfo();
        info.fPid = fPid;
        info.fId = fId;
        info.fKey = fKey;
        info.origSz = origSz;
        info.mime = mime;
        sessions.put(sid, info);
        return "http://127.0.0.1:" + port + "/" + sid;
    }

    // remove streaming session
    public void removeSession(String sid) {
        sessions.remove(sid);
    }

    // stop streaming
    public void stop() {
        running = false;
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (Exception ignored) {
        }
        sessions.clear();
    }

    // accept client
    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "Streamer-Client").start();
            } catch (Exception e) {
                if (!running)
                    break;
            }
        }
    }

    // client connection
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            // Parse request line: GET /sessionId HTTP/1.1
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

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }

            // Get session from path
            String sid = path.startsWith("/") ? path.substring(1) : path;
            // Remove query string if present
            int qIdx = sid.indexOf('?');
            if (qIdx >= 0)
                sid = sid.substring(0, qIdx);

            StreamInfo info = sessions.get(sid);
            if (info == null) {
                sendError(out, 404, "Not Found");
                client.close();
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(method)) {
                StringBuilder resp = new StringBuilder();
                resp.append("HTTP/1.1 204 No Content\r\n");
                resp.append("Access-Control-Allow-Origin: *\r\n");
                resp.append("Access-Control-Allow-Methods: GET, OPTIONS\r\n");
                resp.append("Access-Control-Allow-Headers: Range\r\n");
                resp.append("Access-Control-Max-Age: 86400\r\n");
                resp.append("Connection: close\r\n");
                resp.append("\r\n");
                out.write(resp.toString().getBytes());
                out.flush();
                client.close();
                return;
            }

            // Parse Range header
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

            // Clamp range
            if (rangeEnd >= info.origSz)
                rangeEnd = info.origSz - 1;

            // Build and send HTTP response headers
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

            // Stream data in chunks to keep memory bounded
            long streamChunk = 4 * 1024 * 1024; // 4MB per DlPart call
            long offset = rangeStart;
            long remaining = rangeEnd - rangeStart + 1;
            while (remaining > 0) {
                int len = (int) Math.min(streamChunk, remaining);
                byte[] data = core.DlPart(info.fPid, info.fId, info.fKey, info.origSz, offset, len);
                out.write(data);
                offset += data.length;
                remaining -= data.length;
                if (data.length == 0) break; // safety
            }
            out.flush();
            client.close();

        } catch (Exception e) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    // send error
    private void sendError(OutputStream out, int code, String msg) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + msg + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes());
        out.flush();
    }
}
