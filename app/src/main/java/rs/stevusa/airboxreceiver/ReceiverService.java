package rs.stevusa.airboxreceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class ReceiverService extends Service implements SimpleHttpServer.ControlHandler, PageShareServer.Handler {
    static final String ACTION_STOP_PLAYBACK = "rs.stevusa.airboxreceiver.STOP_PLAYBACK";
    private static final String CHANNEL_ID = "airbox_receiver";
    private static final int NOTIFICATION_ID = 1001;

    private SimpleHttpServer httpServer;
    private SsdpResponder ssdpResponder;
    private AirPlayReceiver airPlayReceiver;
    private PageShareServer pageShareServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        startServers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServers();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (pageShareServer != null) {
            pageShareServer.stop();
            pageShareServer = null;
        }
        if (airPlayReceiver != null) {
            airPlayReceiver.stop();
            airPlayReceiver = null;
        }
        if (ssdpResponder != null) {
            ssdpResponder.stop();
            ssdpResponder = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onPlay(String url) {
        if (url == null) return;
        String trimmed = url.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtsp://"))) {
            return;
        }

        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra(PlayerActivity.EXTRA_URL, trimmed);
        player.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(player);
    }

    @Override
    public void onOpenPage(String url) {
        if (url == null) return;
        String trimmed = url.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) return;
        Intent browser = new Intent(this, BrowserActivity.class);
        browser.putExtra(BrowserActivity.EXTRA_URL, trimmed);
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(browser);
    }

    @Override
    public void onStopPlayback() {
        Intent stop = new Intent(ACTION_STOP_PLAYBACK);
        stop.setPackage(getPackageName());
        sendBroadcast(stop);
    }

    private synchronized void startServers() {
        if (httpServer == null) {
            httpServer = new SimpleHttpServer(8080, this);
            httpServer.start();
        }
        if (ssdpResponder == null) {
            ssdpResponder = new SsdpResponder(8080);
            ssdpResponder.start();
        }
        if (airPlayReceiver == null) {
            airPlayReceiver = new AirPlayReceiver(this, this);
            airPlayReceiver.start();
        }
        if (pageShareServer == null) {
            pageShareServer = new PageShareServer(this);
            pageShareServer.start();
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "AirBox Receiver",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mrežni prijemnik je aktivan");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String ip = NetworkUtils.getLocalIpv4();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AirBox Receiver radi")
                .setContentText("DLNA / HTTP / AirPlay / Web stranice na " + ip)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }
}
