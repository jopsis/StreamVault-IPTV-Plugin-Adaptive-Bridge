package com.streamvault.plugin.adaptivebridge;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Collections;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AdaptiveLocalServer {
    interface Handler {
        String playlist() throws Exception;

        String epgLocation() throws Exception;

        AdaptiveChannel channel(String id) throws Exception;

        String manifest(String id) throws Exception;

        String manifestResourceLocation(String id, String suffix) throws Exception;

        ProxiedResource manifestResource(String id, String suffix, Map<String, String> requestHeaders) throws Exception;

        JSONObject statusJson() throws Exception;
    }

    private static final String TAG = "StreamVaultBridgeServer";
    private static final int PREFERRED_PORT = 39078;
    private static final String EMPTY_XMLTV = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<tv generator-info-name=\"StreamVault Adaptive Bridge\">\n</tv>\n";

    private final Handler handler;
    private final ExecutorService clientExecutor = Executors.newFixedThreadPool(6);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    AdaptiveLocalServer(Handler handler) {
        this.handler = handler;
    }

    synchronized void start() throws Exception {
        if (running) return;
        try {
            serverSocket = new ServerSocket(PREFERRED_PORT, 50, InetAddress.getByName("127.0.0.1"));
        } catch (Exception busy) {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "streamvault-bridge-http");
        acceptThread.start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
    }

    boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    String baseUrl() {
        return port() > 0 ? "http://127.0.0.1:" + port() : "";
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                clientExecutor.execute(() -> handle(socket));
            } catch (Exception error) {
                if (running) Log.w(TAG, "accept failed", error);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket) {
            closeable.setSoTimeout(15_000);
            InputStream input = closeable.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);

            if ("GET".equalsIgnoreCase(method) && "/playlist.m3u".equals(path)) {
                respond(closeable, 200, "application/x-mpegurl; charset=utf-8", handler.playlist().getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equalsIgnoreCase(method) && "/epg.xml".equals(path)) {
                handleEpg(closeable);
            } else if ("GET".equalsIgnoreCase(method) && "/status.json".equals(path)) {
                respond(closeable, 200, "application/json; charset=utf-8", handler.statusJson().toString().getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/manifest/")) {
                handleManifestProxy(closeable, path, headers);
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/license/clearkey/")) {
                String id = path.substring("/license/clearkey/".length());
                AdaptiveChannel channel = handler.channel(id);
                if (channel == null) {
                    respond(closeable, 404, "application/json; charset=utf-8", "{\"error\":\"channel not found\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(closeable, 200, "application/json; charset=utf-8", channel.clearKeyJwk().toString().getBytes(StandardCharsets.UTF_8));
                }
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/play/")) {
                String id = path.substring("/play/".length());
                AdaptiveChannel channel = handler.channel(id);
                if (channel == null) {
                    respond(closeable, 404, "text/plain; charset=utf-8", "Channel not found".getBytes(StandardCharsets.UTF_8));
                } else {
                    redirect(closeable, channel.manifestUrl);
                }
            } else if ("OPTIONS".equalsIgnoreCase(method)) {
                respond(closeable, 204, "text/plain", new byte[0]);
            } else {
                respond(closeable, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            if (isClientDisconnect(error)) return;
            Log.w(TAG, "request failed", error);
        }
    }

    private void handleEpg(Socket socket) throws Exception {
        String location = handler.epgLocation();
        if (location == null || location.trim().isEmpty()) {
            respond(socket, 200, "application/xml; charset=utf-8", EMPTY_XMLTV.getBytes(StandardCharsets.UTF_8));
            return;
        }
        redirect(socket, location);
    }

    private boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof java.net.SocketException &&
                    message != null &&
                    message.toLowerCase(Locale.ROOT).contains("broken pipe")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void handleManifestProxy(Socket socket, String path, Map<String, String> requestHeaders) throws Exception {
        String rest = path.substring("/manifest/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash + 1 >= rest.length()) {
            respond(socket, 404, "text/plain; charset=utf-8", "Manifest not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String id = rest.substring(0, slash);
        String suffix = rest.substring(slash + 1);
        AdaptiveChannel channel = handler.channel(id);
        if (channel != null && channel.isManifestProxyEntry(suffix)) {
            try {
                respond(socket, 200, "text/xml; charset=utf-8", handler.manifest(id).getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                if (isClientDisconnect(error)) return;
                Log.w(TAG, "manifest proxy failed", error);
                try {
                    respond(socket, 502, "text/plain; charset=utf-8", "Manifest proxy failed".getBytes(StandardCharsets.UTF_8));
                } catch (Exception responseError) {
                    if (!isClientDisconnect(responseError)) throw responseError;
                }
            }
            return;
        }
        String location = handler.manifestResourceLocation(id, suffix);
        if (location == null || location.trim().isEmpty()) {
            respond(socket, 404, "text/plain; charset=utf-8", "Manifest resource not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ProxiedResource resource = handler.manifestResource(id, suffix, requestHeaders);
        if (resource != null) {
            respondProxy(socket, resource);
            return;
        }
        redirect(socket, location);
    }

    private void redirect(Socket socket, String location) throws Exception {
        String safeLocation = sanitizeHeaderValue(location);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("HTTP/1.1 302 Found\r\n" +
                "Location: " + safeLocation + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        OutputStream output = socket.getOutputStream();
        output.write(out.toByteArray());
        output.flush();
    }

    private void respond(Socket socket, int code, String contentType, byte[] body) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body;
        String headers = "HTTP/1.1 " + code + " " + reason(code) + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    private void respondProxy(Socket socket, ProxiedResource resource) throws Exception {
        try (ProxiedResource closeable = resource) {
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            headerBytes.write(("HTTP/1.1 " + closeable.code + " " + reason(closeable.code) + "\r\n").getBytes(StandardCharsets.UTF_8));
            String contentType = closeable.contentType == null || closeable.contentType.trim().isEmpty()
                    ? "application/octet-stream"
                    : closeable.contentType.trim();
            headerBytes.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (closeable.contentLength >= 0L) {
                headerBytes.write(("Content-Length: " + closeable.contentLength + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, String> header : closeable.responseHeaders.entrySet()) {
                if (!isForwardedResponseHeader(header.getKey())) continue;
                String value = sanitizeHeaderValue(header.getValue());
                if (value.isEmpty()) continue;
                headerBytes.write((header.getKey() + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            headerBytes.write(("Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: *\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            OutputStream output = socket.getOutputStream();
            output.write(headerBytes.toByteArray());
            if (closeable.input != null) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = closeable.input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.flush();
        }
    }

    private String reason(int code) {
        String reason;
        switch (code) {
            case 200:
                reason = "OK";
                break;
            case 204:
                reason = "No Content";
                break;
            case 206:
                reason = "Partial Content";
                break;
            case 404:
                reason = "Not Found";
                break;
            case 403:
                reason = "Forbidden";
                break;
            case 416:
                reason = "Range Not Satisfiable";
                break;
            case 502:
                reason = "Bad Gateway";
                break;
            default:
                reason = "Error";
                break;
        }
        return reason;
    }

    private boolean isForwardedResponseHeader(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.equals("accept-ranges") ||
                lower.equals("cache-control") ||
                lower.equals("content-range") ||
                lower.equals("etag") ||
                lower.equals("expires") ||
                lower.equals("last-modified");
    }

    private String sanitizeHeaderValue(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    static final class ProxiedResource implements AutoCloseable {
        final int code;
        final String contentType;
        final long contentLength;
        final Map<String, String> responseHeaders;
        final InputStream input;
        private final AutoCloseable closeable;

        ProxiedResource(
                int code,
                String contentType,
                long contentLength,
                Map<String, String> responseHeaders,
                InputStream input,
                AutoCloseable closeable
        ) {
            this.code = code;
            this.contentType = contentType == null ? "" : contentType;
            this.contentLength = contentLength;
            this.responseHeaders = responseHeaders == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(responseHeaders));
            this.input = input;
            this.closeable = closeable;
        }

        @Override
        public void close() throws Exception {
            if (input != null) input.close();
            if (closeable != null) closeable.close();
        }
    }
}
