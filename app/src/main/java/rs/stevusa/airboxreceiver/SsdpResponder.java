package rs.stevusa.airboxreceiver;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class SsdpResponder {
    private static final String MULTICAST_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String UUID = "uuid:4c155456-20ff-4be8-9000-a1b0c0000001";
    private static final String MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private final int httpPort;
    private volatile boolean running;
    private MulticastSocket socket;
    private Thread thread;

    SsdpResponder(int httpPort) {
        this.httpPort = httpPort;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "AirBox-SSDP");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void runLoop() {
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(SSDP_PORT));
            socket.setTimeToLive(2);
            socket.setSoTimeout(1500);
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(group);

            byte[] buffer = new byte[8192];
            while (running) {
                try {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    socket.receive(request);
                    String message = new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8);
                    handleSearch(message, request);
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            running = false;
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleSearch(String message, DatagramPacket request) throws Exception {
        String upper = message.toUpperCase(Locale.US);
        if (!upper.startsWith("M-SEARCH") || !upper.contains("SSDP:DISCOVER")) return;

        String st = headerValue(message, "ST");
        if (st == null) return;
        String stLower = st.toLowerCase(Locale.US);

        if ("ssdp:all".equals(stLower)) {
            sendResponse(request, MEDIA_RENDERER, UUID + "::" + MEDIA_RENDERER);
            sendResponse(request, "upnp:rootdevice", UUID + "::upnp:rootdevice");
            sendResponse(request, UUID, UUID);
        } else if (stLower.contains("mediarenderer")) {
            sendResponse(request, MEDIA_RENDERER, UUID + "::" + MEDIA_RENDERER);
        } else if ("upnp:rootdevice".equals(stLower)) {
            sendResponse(request, "upnp:rootdevice", UUID + "::upnp:rootdevice");
        } else if (stLower.startsWith("uuid:")) {
            sendResponse(request, UUID, UUID);
        }
    }

    private void sendResponse(DatagramPacket request, String st, String usn) throws Exception {
        String ip = NetworkUtils.getLocalIpv4();
        if ("0.0.0.0".equals(ip)) return;

        String response = "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: http://" + ip + ":" + httpPort + "/device.xml\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 AirBoxReceiver/0.1\r\n" +
                "ST: " + st + "\r\n" +
                "USN: " + usn + "\r\n\r\n";

        byte[] data = response.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length, request.getAddress(), request.getPort());
        socket.send(packet);
    }

    private static String headerValue(String message, String wanted) {
        for (String line : message.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (wanted.equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
