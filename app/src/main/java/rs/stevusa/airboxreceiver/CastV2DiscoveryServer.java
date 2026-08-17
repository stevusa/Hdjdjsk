package rs.stevusa.airboxreceiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 1 of the native CastV2 receiver.
 *
 * Publishes AirBox as a _googlecast._tcp service and listens on the standard
 * Cast control port 8009. Chrome discovers receivers through this DNS-SD
 * service before attempting TLS + CastV2 device authentication.
 */
final class CastV2DiscoveryServer {
    static final int CAST_PORT = 8009;
    private static final String SERVICE_TYPE = "_googlecast._tcp.";
    private static final String PREFS = "cast_v2";
    private static final String KEY_ID = "device_id";

    private static volatile String lastStatus = "nije pokrenut";
    private static final AtomicInteger connectionCount = new AtomicInteger();

    private final Context context;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private String deviceId;

    CastV2DiscoveryServer(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start() {
        if (running) return;
        running = true;
        deviceId = loadOrCreateDeviceId();
        startTcpListener();
        registerMdns();
    }

    synchronized void stop() {
        running = false;
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
        }
        registrationListener = null;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
        lastStatus = "zaustavljen";
    }

    static String getLastStatus() {
        return lastStatus;
    }

    static int getConnectionCount() {
        return connectionCount.get();
    }

    private void registerMdns() {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                lastStatus = "NSD nije dostupan";
                return;
            }

            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("AirBox Receiver");
            info.setServiceType(SERVICE_TYPE);
            info.setPort(CAST_PORT);

            // TXT records used by Chromium's Cast discovery parser.
            info.setAttribute("id", deviceId);
            info.setAttribute("fn", "AirBox Receiver");
            info.setAttribute("md", "AirBox");
            info.setAttribute("ve", "05");
            info.setAttribute("ca", "5");
            info.setAttribute("st", "0");
            info.setAttribute("rs", "AirBox Receiver ready");

            registrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    lastStatus = "mDNS greška " + errorCode;
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    lastStatus = "mDNS unregister greška " + errorCode;
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    lastStatus = "Cast discovery aktivan: " + serviceInfo.getServiceName();
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                    lastStatus = "Cast discovery zaustavljen";
                }
            };

            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Throwable t) {
            lastStatus = "mDNS: " + t.getClass().getSimpleName();
        }
    }

    private void startTcpListener() {
        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(CAST_PORT);
                serverSocket.setReuseAddress(true);
                lastStatus = "port 8009 sluša; registrujem mDNS";
                while (running) {
                    Socket socket = serverSocket.accept();
                    connectionCount.incrementAndGet();
                    new Thread(() -> inspectClient(socket), "AirBox-CastV2-client").start();
                }
            } catch (Exception e) {
                if (running) lastStatus = "port 8009: " + e.getClass().getSimpleName();
            }
        }, "AirBox-CastV2-accept");
        acceptThread.start();
    }

    private void inspectClient(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(2500);
            InputStream in = client.getInputStream();
            byte[] header = new byte[16];
            int read = in.read(header);
            String remote = String.valueOf(client.getInetAddress().getHostAddress());
            if (read > 2 && (header[0] & 0xff) == 0x16 && (header[1] & 0xff) == 0x03) {
                lastStatus = "Chrome/Cast TLS pokušaj sa " + remote +
                        " (sledeće: device-auth)";
            } else if (read > 0) {
                lastStatus = "Cast TCP veza sa " + remote + " (" + read + " bajtova)";
            } else {
                lastStatus = "Cast TCP veza sa " + remote;
            }
        } catch (Exception ignored) {
        }
    }

    private String loadOrCreateDeviceId() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_ID, null);
        if (id != null && !id.isEmpty()) return id;

        String seed = UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.US);
        id = seed.substring(0, Math.min(32, seed.length()));
        prefs.edit().putString(KEY_ID, id).apply();
        return id;
    }
}
