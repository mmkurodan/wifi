package com.example.wifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProxyService extends Service {
    public static final int DEFAULT_PORT = 8888;
    public static final String EXTRA_PORT = "extra_port";
    private static final String TAG = "ProxyService";
    private static final String CHANNEL_ID = "proxy_channel";
    private static final int NOTIFICATION_ID = 3;
    private static final long CLIENT_TTL_MS = 2 * 60 * 1000;
    private static final ConcurrentHashMap<String, Long> CLIENT_LAST_SEEN = new ConcurrentHashMap<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ExecutorService executor;
    private ServerSocket serverSocket;
    private volatile int proxyPort = DEFAULT_PORT;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopProxy();
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = AppPreferences.getProxyPort(this);
        if (intent != null && intent.hasExtra(EXTRA_PORT)) {
            port = intent.getIntExtra(EXTRA_PORT, port);
        }
        proxyPort = port;

        startForeground(NOTIFICATION_ID, createNotification());
        startProxy();
        return START_STICKY;
    }

    private void startProxy() {
        if (RUNNING.getAndSet(true)) {
            return;
        }
        executor = Executors.newCachedThreadPool();
        executor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", proxyPort));
            log("Proxy listening on port " + proxyPort);

            while (RUNNING.get()) {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30000);
                executor.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (RUNNING.get()) {
                log("Proxy error: " + e.getMessage());
            }
        } finally {
            closeServer();
            RUNNING.set(false);
        }
    }

    private void handleClient(Socket client) {
        try (Socket c = client) {
            recordClient(c);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            HttpRequest request = HttpRequest.read(in);
            if (request == null) {
                return;
            }

            if ("CONNECT".equalsIgnoreCase(request.method)) {
                handleConnect(request, in, out);
            } else {
                handleHttp(request, in, out);
            }
        } catch (IOException e) {
            log("Client error: " + e.getMessage());
        }
    }

    private void handleConnect(HttpRequest request, InputStream clientIn, OutputStream clientOut)
        throws IOException {
        HostPort target = parseHostPort(request.uri, 443);
        if (target.host == null) {
            sendError(clientOut, "400 Bad Request");
            return;
        }

        try (Socket remote = new Socket(target.host, target.port)) {
            remote.setSoTimeout(30000);
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            if (request.leftover.length > 0) {
                remote.getOutputStream().write(request.leftover);
                remote.getOutputStream().flush();
            }

            relayBidirectional(clientIn, clientOut, remote);
        }
    }

    private void handleHttp(HttpRequest request, InputStream clientIn, OutputStream clientOut)
        throws IOException {
        Target target = resolveTarget(request);
        if (target == null) {
            sendError(clientOut, "400 Bad Request");
            return;
        }

        try (Socket remote = new Socket(target.host, target.port)) {
            remote.setSoTimeout(30000);
            OutputStream remoteOut = remote.getOutputStream();

            byte[] headerBytes = buildForwardHeader(request, target);
            remoteOut.write(headerBytes);
            if (request.leftover.length > 0) {
                remoteOut.write(request.leftover);
            }
            remoteOut.flush();

            relayBidirectional(clientIn, clientOut, remote);
        }
    }

    private void relayBidirectional(InputStream clientIn, OutputStream clientOut, Socket remote)
        throws IOException {
        InputStream remoteIn = remote.getInputStream();
        OutputStream remoteOut = remote.getOutputStream();

        Thread upstream = new Thread(() -> copyStream(clientIn, remoteOut));
        Thread downstream = new Thread(() -> copyStream(remoteIn, clientOut));
        upstream.start();
        downstream.start();

        try {
            upstream.join();
            downstream.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void copyStream(InputStream in, OutputStream out) {
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Connection closed
        }
    }

    private byte[] buildForwardHeader(HttpRequest request, Target target) {
        StringBuilder builder = new StringBuilder();
        builder.append(request.method)
            .append(" ")
            .append(target.path)
            .append(" ")
            .append(request.protocol)
            .append("\r\n");

        boolean hasHost = false;
        for (String header : request.headerLines) {
            String lower = header.toLowerCase(Locale.US);
            if (lower.startsWith("host:")) {
                hasHost = true;
            }
            if (lower.startsWith("proxy-connection:") || lower.startsWith("connection:")) {
                continue;
            }
            builder.append(header).append("\r\n");
        }

        if (!hasHost && target.hostHeader != null) {
            builder.append("Host: ").append(target.hostHeader).append("\r\n");
        }

        builder.append("Connection: close\r\n\r\n");
        return builder.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private Target resolveTarget(HttpRequest request) throws IOException {
        if (request.uri.startsWith("http://") || request.uri.startsWith("https://")) {
            URI uri = URI.create(request.uri);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            int port = uri.getPort();
            boolean isHttps = "https".equalsIgnoreCase(uri.getScheme());
            if (port == -1) {
                port = isHttps ? 443 : 80;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            String hostHeader = host;
            if ((isHttps && port != 443) || (!isHttps && port != 80)) {
                hostHeader = host + ":" + port;
            }
            return new Target(host, port, path, hostHeader);
        }

        if (request.hostHeader == null) {
            return null;
        }
        HostPort hostPort = parseHostPort(request.hostHeader, 80);
        return new Target(hostPort.host, hostPort.port, request.uri, request.hostHeader);
    }

    private HostPort parseHostPort(String value, int defaultPort) throws IOException {
        String host = value;
        int port = defaultPort;

        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end == -1) {
                throw new IOException("Invalid host");
            }
            host = value.substring(1, end);
            if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                port = Integer.parseInt(value.substring(end + 2));
            }
        } else if (value.contains(":")) {
            String[] parts = value.split(":", 2);
            host = parts[0];
            port = Integer.parseInt(parts[1]);
        }

        return new HostPort(host, port);
    }

    private void sendError(OutputStream out, String status) throws IOException {
        String response = "HTTP/1.1 " + status + "\r\nConnection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void log(String message) {
        Log.i(TAG, message);
        AppLogBuffer.add(TAG, message);
    }

    private void stopProxy() {
        RUNNING.set(false);
        closeServer();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        CLIENT_LAST_SEEN.clear();
        log("Proxy stopped");
    }

    private void recordClient(Socket client) {
        if (client == null || client.getInetAddress() == null) {
            return;
        }
        String ip = client.getInetAddress().getHostAddress();
        if (ip != null && !ip.isEmpty()) {
            CLIENT_LAST_SEEN.put(ip, System.currentTimeMillis());
            AppLogBuffer.add(TAG, "Client connected: " + ip);
        }
    }

    public static int getActiveClientCount() {
        pruneClients();
        return CLIENT_LAST_SEEN.size();
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    private static void pruneClients() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : CLIENT_LAST_SEEN.entrySet()) {
            if (now - entry.getValue() > CLIENT_TTL_MS) {
                CLIENT_LAST_SEEN.remove(entry.getKey());
            }
        }
    }

    private void closeServer() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Ignore
            }
            serverSocket = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("HTTP proxy for hotspot clients");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, ProxyService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent exitIntent = new Intent(this, AppExitReceiver.class);
        exitIntent.setAction(AppExitReceiver.ACTION_EXIT_APP);
        PendingIntent exitPending = PendingIntent.getBroadcast(
            this, 1, exitIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPending = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("WiFi Proxy Active")
            .setContentText("Clients must set proxy to access the internet")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPending)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        stopProxy();
        super.onDestroy();
    }

    private static class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static class Target {
        final String host;
        final int port;
        final String path;
        final String hostHeader;

        Target(String host, int port, String path, String hostHeader) {
            this.host = host;
            this.port = port;
            this.path = path;
            this.hostHeader = hostHeader;
        }
    }

    private static class HttpRequest {
        private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        final String method;
        final String uri;
        final String protocol;
        final String hostHeader;
        final List<String> headerLines;
        final byte[] leftover;

        private HttpRequest(String method, String uri, String protocol, String hostHeader,
                            List<String> headerLines, byte[] leftover) {
            this.method = method;
            this.uri = uri;
            this.protocol = protocol;
            this.hostHeader = hostHeader;
            this.headerLines = headerLines;
            this.leftover = leftover;
        }

        static HttpRequest read(InputStream in) throws IOException {
            HeaderData headerData = readHeader(in);
            if (headerData == null) {
                return null;
            }
            String headerText = new String(headerData.headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                return null;
            }

            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 3) {
                return null;
            }

            String method = requestLine[0];
            String uri = requestLine[1];
            String protocol = requestLine[2];
            String hostHeader = null;
            List<String> headers = new ArrayList<>();

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    continue;
                }
                headers.add(line);
                if (line.toLowerCase(Locale.US).startsWith("host:")) {
                    hostHeader = line.substring(5).trim();
                }
            }

            return new HttpRequest(method, uri, protocol, hostHeader, headers, headerData.leftover);
        }

        private static HeaderData readHeader(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                byte[] data = buffer.toByteArray();
                int index = indexOf(data, HEADER_END);
                if (index >= 0) {
                    byte[] headerBytes = Arrays.copyOfRange(data, 0, index + HEADER_END.length);
                    byte[] leftover = Arrays.copyOfRange(data, index + HEADER_END.length, data.length);
                    return new HeaderData(headerBytes, leftover);
                }
                if (buffer.size() > 65536) {
                    throw new IOException("Header too large");
                }
            }
            return null;
        }

        private static int indexOf(byte[] data, byte[] pattern) {
            for (int i = 0; i <= data.length - pattern.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static class HeaderData {
        final byte[] headerBytes;
        final byte[] leftover;

        HeaderData(byte[] headerBytes, byte[] leftover) {
            this.headerBytes = headerBytes;
            this.leftover = leftover;
        }
    }
}
