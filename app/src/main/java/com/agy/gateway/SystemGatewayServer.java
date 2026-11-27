package com.agy.gateway;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.agy.BuildConfig;
import com.agy.core.ApprovalActionReceiver;
import com.agy.core.CoreRuntimeState;
import com.agy.core.VoiceForegroundService;
import com.agy.log.CrashRecorder;
import com.agy.log.LogRingBuffer;
import com.agy.util.AtomicFileWriter;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight embedded HTTP Server listening strictly on 127.0.0.1 (default 45160, build-configurable).
 * Zero external dependencies; exposes the Android System Gateway to the Linux/agent layer.
 *
 * All /gateway* endpoints require a per-install shared secret token sent via
 * X-Agy-Gateway-Token or Authorization: Bearer <token> (stored in files/logs/gateway.token).
 */
public final class SystemGatewayServer {

    private static final String TAG = "SystemGateway";
    public static final int DEFAULT_PORT = 45160;
    public static final String TOKEN_HEADER = "x-agy-gateway-token";
    public static final String AUTH_HEADER = "authorization";
    private static final int CLIENT_SOCKET_TIMEOUT_MS = 30_000;
    private static volatile int activePort = DEFAULT_PORT;

    private final Context context;
    private volatile int port;
    private final GatewayDispatcher dispatcher;
    private final LogRingBuffer logBuffer;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final String gatewayToken;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public SystemGatewayServer(Context context, LogRingBuffer logBuffer) {
        this(context, logBuffer, DEFAULT_PORT);
    }

    public SystemGatewayServer(Context context, LogRingBuffer logBuffer, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
        activePort = port;
        this.logBuffer = logBuffer;
        this.dispatcher = new GatewayDispatcher(this.context, logBuffer);
        this.gatewayToken = loadOrCreateToken();
    }

    public int getPort() {
        return port;
    }

    public static int getActivePort() {
        return activePort;
    }

    private File logsDir() {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create logs dir: " + dir);
        }
        return dir;
    }

    private String loadOrCreateToken() {
        File tokenFile = new File(logsDir(), "gateway.token");
        try {
            if (tokenFile.isFile()) {
                byte[] buf = new byte[256];
                int n;
                try (FileInputStream in = new FileInputStream(tokenFile)) {
                    n = in.read(buf);
                }
                if (n > 0) {
                    String existing = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                    if (existing.length() >= 32) {
                        enforcePrivatePermissions(tokenFile);
                        return existing;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read gateway token: " + e.getMessage());
        }

        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder(rnd.length * 2);
        for (byte b : rnd) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        String token = sb.toString();
        try {
            AtomicFileWriter.writeText(tokenFile, token + "\n");
            enforcePrivatePermissions(tokenFile);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist gateway token: " + e.getMessage());
        }
        return token;
    }

    private static void enforcePrivatePermissions(File file) {
        try {
            Os.chmod(file.getAbsolutePath(), 0600);
        } catch (Throwable ignored) {
        }
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        acceptThread = new Thread(() -> {
            try {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                } catch (IOException e) {
                    Log.w(TAG, "Port " + port + " in use, trying fallback " + (port + 2));
                    port = port + 2;
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                }
                activePort = port;
                Log.i(TAG, "System Gateway listening on http://127.0.0.1:" + port + "/gateway");
                try {
                    File pFile = new File(logsDir(), "gateway.port");
                    AtomicFileWriter.writeText(pFile, port + "\n");
                } catch (Throwable ignored) {}

                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setSoTimeout(CLIENT_SOCKET_TIMEOUT_MS);
                        executor.submit(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Gateway server bind failed on port " + port + ": " + e.getMessage());
            }
        }, "AgyGatewayServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        dispatcher.releaseResources();
        executor.shutdownNow();
        Log.i(TAG, "System Gateway stopped.");
    }

    private void handleClient(Socket client) {
        try (Socket sock = client;
             InputStream in = new BufferedInputStream(sock.getInputStream(), 8192);
             OutputStream out = sock.getOutputStream()) {

            String reqLine = readHeaderLine(in);
            if (reqLine == null) return;

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];

            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            String line;
            while ((line = readHeaderLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
                if ("content-length".equals(key)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = -1;
                    }
                }
            }

            if (contentLength < 0) {
                throw new IOException("Invalid Content-Length: " + contentLength);
            }
            if (contentLength > 4 * 1024 * 1024) {
                throw new IOException("Payload too large: " + contentLength);
            }

            JSONObject responseJson = new JSONObject();
            int statusCode = 200;
            String statusText = "OK";

            if (path.startsWith("/gateway")) {
                if (!isAuthorized(gatewayToken, headers)) {
                    statusCode = 401;
                    statusText = "Unauthorized";
                    responseJson.put("ok", false);
                    responseJson.put("error", "unauthorized");
                } else if ("GET".equals(method) && (path.startsWith("/gateway/status") || path.startsWith("/gateway/health"))) {
                    responseJson = buildStatusJson();
                } else if ("GET".equals(method) && path.startsWith("/gateway/logs")) {
                    responseJson.put("ok", true);
                    responseJson.put("logs", LogRingBuffer.toJsonArray(logBuffer.getRecent(50)));
                } else if ("GET".equals(method) && path.startsWith("/gateway/commands")) {
                    responseJson.put("ok", true);
                    responseJson.put("commands", com.agy.log.CommandHistory.toJsonArray(dispatcher.getCommandHistory().getRecent(20)));
                } else if ("GET".equals(method) && path.startsWith("/gateway/crash")) {
                    JSONObject crash = CrashRecorder.getLatestCrash();
                    responseJson.put("ok", true);
                    responseJson.put("crash", crash != null ? crash : JSONObject.NULL);
                } else if ("GET".equals(method) && path.startsWith("/gateway/approval")) {
                    String id = parseQueryParam(path, "id");
                    long waitMs = parseLongQueryParam(path, "wait_ms", 0L);
                    responseJson = ApprovalActionReceiver.poll(context, id, waitMs);
                } else if ("GET".equals(method) && path.startsWith("/gateway/voice/status")) {
                    responseJson = VoiceForegroundService.getStatusJson();
                } else if ("POST".equals(method) && ("/gateway".equals(path) || path.startsWith("/gateway?")
                        || "/gateway/notify".equals(path) || path.startsWith("/gateway/notify?"))) {
                    String bodyStr = readBody(in, contentLength);
                    JSONObject req = !bodyStr.isEmpty() ? new JSONObject(bodyStr) : new JSONObject();
                    if (path.startsWith("/gateway/notify")) {
                        JSONObject payload = req.optJSONObject("payload");
                        responseJson = dispatcher.dispatch("notify", payload != null ? payload : req);
                    } else {
                        String cmd = req.optString("command", "");
                        JSONObject payload = req.optJSONObject("payload");
                        responseJson = dispatcher.dispatch(cmd, payload);
                    }
                } else {
                    statusCode = 404;
                    statusText = "Not Found";
                    responseJson.put("ok", false);
                    responseJson.put("error", "not found");
                }
            } else {
                statusCode = 404;
                statusText = "Not Found";
                responseJson.put("ok", false);
                responseJson.put("error", "not found");
            }

            byte[] bytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.d(TAG, "client error: " + e.getMessage());
        }
    }

    private JSONObject buildStatusJson() {
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("status", "online");
            resp.put("gateway_port", port);
            resp.put("port", port);
            try { resp.put("app_version", BuildConfig.APP_VERSION_NAME); } catch (Throwable ignored) {}
            try { resp.put("core_version", BuildConfig.CORE_VERSION); } catch (Throwable ignored) {}
            try { resp.put("package", BuildConfig.APPLICATION_ID); } catch (Throwable ignored) {}

            JSONObject core = new JSONObject();
            boolean ready = CoreRuntimeState.serverUrl != null && !CoreRuntimeState.serverUrl.trim().isEmpty();
            core.put("ready", ready);
            core.put("restarting", CoreRuntimeState.restarting);
            core.put("service_active", CoreRuntimeState.serviceActive);
            core.put("https_port", CoreRuntimeState.httpsPort);
            core.put("ready_generation", CoreRuntimeState.readyGeneration);
            if (CoreRuntimeState.lastError != null) {
                core.put("last_error", CoreRuntimeState.lastError);
            }
            resp.put("core", core);

            File files = context.getFilesDir();
            JSONObject env = new JSONObject();
            env.put("files_dir", files.getAbsolutePath());
            env.put("usr_bash", new File(files, "usr/bin/bash").isFile());
            env.put("runtime_dir", new File(files, "runtime").isDirectory());
            env.put("web_dir", new File(files, "web").isDirectory());
            env.put("storage_free_mb", files.getUsableSpace() / (1024 * 1024));
            resp.put("env", env);

            Runtime rt = Runtime.getRuntime();
            JSONObject mem = new JSONObject();
            long max = rt.maxMemory();
            long total = rt.totalMemory();
            long free = rt.freeMemory();
            long used = total - free;
            mem.put("heap_used_mb", used / (1024 * 1024));
            mem.put("heap_max_mb", max / (1024 * 1024));
            resp.put("memory", mem);
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return resp;
    }

    /** Extracts X-Agy-Gateway-Token or Authorization: Bearer token from HTTP headers (case-insensitive keys). */
    public static String extractTokenFromHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        String provided = getHeaderIgnoreCase(headers, TOKEN_HEADER);
        if (provided != null && !provided.trim().isEmpty()) {
            return provided.trim();
        }
        String auth = getHeaderIgnoreCase(headers, AUTH_HEADER);
        if (auth != null) {
            String prefix = "Bearer ";
            if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String bearer = auth.substring(prefix.length()).trim();
                if (!bearer.isEmpty()) {
                    return bearer;
                }
            }
        }
        return null;
    }

    private static String getHeaderIgnoreCase(Map<String, String> headers, String lowerKey) {
        String direct = headers.get(lowerKey);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String k = entry.getKey();
            if (k != null && k.equalsIgnoreCase(lowerKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Constant-time token validation against incoming lowercased request headers. */
    public static boolean isAuthorized(String expectedToken, Map<String, String> headers) {
        if (expectedToken == null || expectedToken.isEmpty()) {
            return false;
        }
        String provided = extractTokenFromHeaders(headers);
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(InputStream in, int contentLength) throws IOException {
        if (contentLength <= 0) return "";
        byte[] payloadBytes = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int r = in.read(payloadBytes, read, contentLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read < contentLength) {
            throw new IOException("Unexpected EOF while reading body: expected " + contentLength + ", got " + read);
        }
        return new String(payloadBytes, StandardCharsets.UTF_8);
    }

    private static final int MAX_HEADER_LINE_BYTES = 8192;

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                break;
            }
            if (baos.size() >= MAX_HEADER_LINE_BYTES) {
                throw new IOException("HTTP header line exceeded limit of " + MAX_HEADER_LINE_BYTES + " bytes");
            }
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) {
            return null;
        }
        return baos.toString("UTF-8");
    }

    private static String parseQueryParam(String path, String key) {
        if (path == null || key == null) {
            return "";
        }
        int q = path.indexOf('?');
        if (q < 0 || q + 1 >= path.length()) {
            return "";
        }
        String query = path.substring(q + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, "UTF-8");
                if (key.equals(k)) {
                    return URLDecoder.decode(v, "UTF-8");
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static long parseLongQueryParam(String path, String key, long fallback) {
        String raw = parseQueryParam(path, key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
