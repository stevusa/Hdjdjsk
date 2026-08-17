package rs.stevusa.airboxreceiver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

final class PageShareServer {
    interface Handler {
        void onOpenPage(String url);
    }

    static final int HTTP_PORT = 8090;
    static final int DISCOVERY_PORT = 8091;
    private final Handler handler;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private Thread httpThread;
    private Thread discoveryThread;

    PageShareServer(Handler handler) {
        this.handler = handler;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        httpThread = new Thread(this::runHttp, "AirBox-PageHTTP");
        discoveryThread = new Thread(this::runDiscovery, "AirBox-Discovery");
        httpThread.start();
        discoveryThread.start();
    }

    synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (discoverySocket != null) discoverySocket.close();
    }

    private void runHttp() {
        try {
            serverSocket = new ServerSocket(HTTP_PORT);
            serverSocket.setReuseAddress(true);
            while (running) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handle(socket), "AirBox-PageClient").start();
            }
        } catch (Exception ignored) {
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(4000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int matched = 0;
            while (data.size() < 32768) {
                int b = in.read();
                if (b < 0) break;
                data.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') matched++;
                else if ((matched == 1 || matched == 3) && b == '\n') matched++;
                else matched = b == '\r' ? 1 : 0;
                if (matched == 4) break;
            }
            String request = data.toString("ISO-8859-1");
            String first = request.split("\\r?\\n", 2)[0];
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String target = parts[1];

            if (target.startsWith("/open?url=")) {
                String encoded = target.substring("/open?url=".length());
                String url = URLDecoder.decode(encoded, "UTF-8");
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    handler.onOpenPage(url);
                    respond(s.getOutputStream(), 200, "{\"ok\":true}");
                    return;
                }
                respond(s.getOutputStream(), 400, "{\"ok\":false}");
                return;
            }

            if (target.equals("/status")) {
                respond(s.getOutputStream(), 200,
                        "{\"name\":\"AirBox Receiver\",\"pageShare\":true,\"port\":" + HTTP_PORT + "}");
                return;
            }

            respond(s.getOutputStream(), 404, "{\"ok\":false}");
        } catch (Exception ignored) {
        }
    }

    private void respond(OutputStream out, int code, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : "Not Found";
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private void runDiscovery() {
        try {
            discoverySocket = new DatagramSocket(DISCOVERY_PORT);
            discoverySocket.setBroadcast(true);
            byte[] buffer = new byte[1024];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                discoverySocket.receive(packet);
                String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (!"AIRBOX_DISCOVER_V1".equals(message)) continue;
                String reply = "AIRBOX_RECEIVER_V1|" + NetworkUtils.getLocalIpv4() + "|" + HTTP_PORT;
                byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort());
                discoverySocket.send(response);
            }
        } catch (Exception ignored) {
        }
    }
}
