package rs.stevusa.airboxreceiver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small AirPlay-1-style HTTP receiver.
 *
 * It intentionally advertises only a minimal video capability and implements the
 * classic /server-info, /play, /stop, /scrub and /playback-info endpoints.
 * Modern encrypted AirPlay 2, FairPlay and screen mirroring are outside this
 * implementation and are not advertised as supported.
 */
final class AirPlayReceiver {
    private static final int PORT = 7000;
    private static final String SERVICE_NAME = "AirBox Receiver";
    private static final String DEVICE_ID = "02:00:00:00:00:01";

    private final Context context;
    private final SimpleHttpServer.ControlHandler handler;
    private final ExecutorService clients = Executors.newCachedThreadPool();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;

    AirPlayReceiver(Context context, SimpleHttpServer.ControlHandler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "AirBox-AirPlay");
        acceptThread.start();
        registerBonjour();
    }

    synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        unregisterBonjour();
        clients.shutdownNow();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);
            while (running) {
                Socket socket = serverSocket.accept();
                clients.execute(() -> handle(socket));
            }
        } catch (SocketException ignored) {
        } catch (IOException ignored) {
        } finally {
            running = false;
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(5000);
            Request request = readRequest(client.getInputStream());
            if (request == null) return;
            route(request, client.getOutputStream());
        } catch (Exception ignored) {
        }
    }

    private void route(Request request, OutputStream out) throws IOException {
        String path = request.path;

        if ("GET".equals(request.method) && "/server-info".equals(path)) {
            write(out, 200, "text/x-apple-plist+xml", serverInfo());
            return;
        }

        if ("POST".equals(request.method) && "/play".equals(path)) {
            String url = request.headers.get("content-location");
            if (url == null || url.isEmpty()) {
                url = bodyField(request.body, "Content-Location");
            }
            if (url != null && !url.trim().isEmpty()) {
                handler.onPlay(url.trim());
                write(out, 200, "text/plain", "");
            } else {
                write(out, 400, "text/plain", "Missing Content-Location");
            }
            return;
        }

        if ("POST".equals(request.method) && "/stop".equals(path)) {
            handler.onStopPlayback();
            write(out, 200, "text/plain", "");
            return;
        }

        if ("GET".equals(request.method) && "/scrub".equals(path)) {
            write(out, 200, "text/parameters", "duration: 0.000000\nposition: 0.000000\n");
            return;
        }

        if ("POST".equals(request.method) && ("/scrub".equals(path) || "/rate".equals(path))) {
            write(out, 200, "text/plain", "");
            return;
        }

        if ("GET".equals(request.method) && "/playback-info".equals(path)) {
            write(out, 200, "text/x-apple-plist+xml", playbackInfo());
            return;
        }

        if ("POST".equals(request.method) && "/reverse".equals(path)) {
            writeSwitchingProtocols(out);
            return;
        }

        write(out, 404, "text/plain", "Not found");
    }

    private void registerBonjour() {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            NsdServiceInfo service = new NsdServiceInfo();
            service.setServiceName(SERVICE_NAME);
            service.setServiceType("_airplay._tcp.");
            service.setPort(PORT);
            service.setAttribute("deviceid", DEVICE_ID);
            service.setAttribute("features", "0x1");
            service.setAttribute("model", "AirBoxReceiver1,1");
            service.setAttribute("srcvers", "220.68");
            service.setAttribute("flags", "0x4");

            registrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                }
            };
            nsdManager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Throwable ignored) {
        }
    }

    private void unregisterBonjour() {
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager.unregisterService(registrationListener);
            }
        } catch (Throwable ignored) {
        } finally {
            registrationListener = null;
            nsdManager = null;
        }
    }

    private Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int state = 0;
        while (headerBytes.size() < 65536) {
            int b = in.read();
            if (b < 0) return null;
            headerBytes.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = b == '\r' ? 1 : 0;
            if (state == 4) break;
        }

        String rawHeaders = headerBytes.toString("ISO-8859-1");
        String[] lines = rawHeaders.split("\\r\\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }

        int length = 0;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        length = Math.max(0, Math.min(length, 1024 * 1024));
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(bytes, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }

        String target = first[1];
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        String body = new String(bytes, 0, offset, StandardCharsets.UTF_8);
        return new Request(first[0].toUpperCase(Locale.US), path, headers, body);
    }

    private void write(OutputStream out, int code, String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String reason;
        switch (code) {
            case 200: reason = "OK"; break;
            case 400: reason = "Bad Request"; break;
            default: reason = "Not Found"; break;
        }
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Server: AirTunes/220.68\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bodyBytes);
        out.flush();
    }

    private void writeSwitchingProtocols(OutputStream out) throws IOException {
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: PTTH/1.0\r\n" +
                "Connection: Upgrade\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private String serverInfo() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">" +
                "<plist version=\"1.0\"><dict>" +
                "<key>deviceid</key><string>" + DEVICE_ID + "</string>" +
                "<key>features</key><integer>1</integer>" +
                "<key>model</key><string>AirBoxReceiver1,1</string>" +
                "<key>protovers</key><string>1.0</string>" +
                "<key>srcvers</key><string>220.68</string>" +
                "</dict></plist>";
    }

    private String playbackInfo() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">" +
                "<plist version=\"1.0\"><dict>" +
                "<key>duration</key><real>0.0</real>" +
                "<key>position</key><real>0.0</real>" +
                "<key>rate</key><real>1.0</real>" +
                "<key>readyToPlay</key><true/>" +
                "<key>playbackBufferEmpty</key><false/>" +
                "<key>playbackBufferFull</key><true/>" +
                "<key>playbackLikelyToKeepUp</key><true/>" +
                "</dict></plist>";
    }

    private static String bodyField(String body, String key) {
        for (String line : body.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && key.equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        Request(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }
}
