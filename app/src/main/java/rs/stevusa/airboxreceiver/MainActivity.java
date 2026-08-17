package rs.stevusa.airboxreceiver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        startReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 42, 48, 42);
        root.setBackgroundColor(Color.rgb(16, 16, 16));

        TextView title = new TextView(this);
        title.setText("AirBox Receiver 0.3");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("TV box mrežni prijemnik — AirScreen smer");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(18f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = 12;
        root.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(19f);
        statusView.setLineSpacing(6f, 1f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = 34;
        root.addView(statusView, statusParams);

        Button refresh = makeButton("OSVEŽI CAST STATUS");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh, buttonParams());

        Button start = makeButton("POKRENI PRIJEMNIK");
        start.setOnClickListener(v -> {
            startReceiver();
            refreshStatus();
        });
        root.addView(start, buttonParams());

        Button stop = makeButton("ZAUSTAVI PRIJEMNIK");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ReceiverService.class));
            refreshStatus();
        });
        root.addView(stop, buttonParams());

        TextView note = new TextView(this);
        note.setText(
                "DLNA/UPnP: aktivno\n" +
                "HTTP media prijem: aktivno\n" +
                "AirPlay video: osnovna podrška\n" +
                "CastV2 discovery: _googlecast._tcp / port 8009\n" +
                "CastV2 TLS + device-auth: sledeća faza\n" +
                "Mirroring RTP/H.264: posle CastV2 autentikacije");
        note.setTextColor(Color.GRAY);
        note.setTextSize(16f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = 28;
        root.addView(note, noteParams);

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17f);
        button.setAllCaps(false);
        button.setFocusable(true);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = 14;
        return params;
    }

    private void startReceiver() {
        Intent intent = new Intent(this, ReceiverService.class);
        startForegroundService(intent);
    }

    private void refreshStatus() {
        if (statusView == null) return;
        String ip = NetworkUtils.getLocalIpv4();
        statusView.setText(
                "Status: spreman za prijem\n" +
                "IP: " + ip + "\n" +
                "DLNA/HTTP: " + ip + ":8080\n" +
                "AirPlay: " + ip + ":7000\n" +
                "CastV2: " + ip + ":8009\n" +
                "Cast veze: " + CastV2DiscoveryServer.getConnectionCount() + "\n" +
                "Cast status: " + CastV2DiscoveryServer.getLastStatus());
    }
}
