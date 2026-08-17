package rs.stevusa.airboxsender;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareActivity extends Activity {
    private static final int DISCOVERY_PORT = 8091;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private EditText urlInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        String shared = null;
        Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        String url = extractUrl(shared);
        if (url != null) {
            urlInput.setText(url);
            sendPage(url);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 48, 36, 36);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("AirBox Sender");
        title.setTextSize(28f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("Pošalji web stranicu na AirBox Receiver na istoj Wi‑Fi mreži.");
        hint.setTextSize(17f);
        hint.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = 22;
        root.addView(hint, hintParams);

        urlInput = new EditText(this);
        urlInput.setHint("https://...");
        urlInput.setSingleLine(false);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = 24;
        root.addView(urlInput, inputParams);

        Button send = new Button(this);
        send.setText("Pošalji na TV");
        send.setOnClickListener(v -> sendPage(extractUrl(urlInput.getText().toString())));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = 18;
        root.addView(send, buttonParams);

        status = new TextView(this);
        status.setText("Spreman");
        status.setTextSize(17f);
        status.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = 24;
        root.addView(status, statusParams);

        setContentView(root);
    }

    private void sendPage(String url) {
        if (url == null) {
            setStatus("Nisam našao ispravnu http/https adresu.");
            return;
        }
        setStatus("Tražim AirBox Receiver...");
        new Thread(() -> {
            try {
                Receiver receiver = discoverReceiver();
                if (receiver == null) {
                    setStatus("TV nije pronađen. Pokreni AirBox Receiver i proveri da su uređaji na istoj mreži.");
                    return;
                }
                setStatus("Nađen TV: " + receiver.ip + " — šaljem stranicu...");
                String endpoint = "http://" + receiver.ip + ":" + receiver.port +
                        "/open?url=" + URLEncoder.encode(url, "UTF-8");
                HttpURLConnection connection = (HttpURLConnection) new java.net.URL(endpoint).openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (BufferedReader ignored = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        setStatus("Poslato. Stranica je otvorena na TV-u.");
                    }
                } else {
                    setStatus("TV je pronađen, ali slanje nije uspelo (HTTP " + code + ").");
                }
                connection.disconnect();
            } catch (Exception e) {
                setStatus("Greška pri slanju: " + e.getClass().getSimpleName());
            }
        }, "AirBox-Send").start();
    }

    private Receiver discoverReceiver() {
        String cachedIp = getPreferences(MODE_PRIVATE).getString("receiver_ip", null);
        if (cachedIp != null && testReceiver(cachedIp, 8090)) {
            return new Receiver(cachedIp, 8090);
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(2800);
            byte[] query = "AIRBOX_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
            DatagramPacket request = new DatagramPacket(
                    query, query.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(request);

            long deadline = System.currentTimeMillis() + 2800;
            byte[] buffer = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                String text = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8).trim();
                if (!text.startsWith("AIRBOX_RECEIVER_V1|")) continue;
                String[] parts = text.split("\\|");
                if (parts.length < 3) continue;
                String ip = parts[1];
                int port;
                try {
                    port = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                getPreferences(MODE_PRIVATE).edit().putString("receiver_ip", ip).apply();
                return new Receiver(ip, port);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean testReceiver(String ip, int port) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new java.net.URL("http://" + ip + ":" + port + "/status").openConnection();
            connection.setConnectTimeout(900);
            connection.setReadTimeout(900);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String extractUrl(String text) {
        if (text == null) return null;
        Matcher matcher = URL_PATTERN.matcher(text.trim());
        if (!matcher.find()) return null;
        String url = matcher.group();
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(",") || url.endsWith(".")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private static final class Receiver {
        final String ip;
        final int port;
        Receiver(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }
}
