package rs.stevusa.airboxreceiver;

import android.app.Activity;
import android.content.Intent;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.tv.CastReceiverContext;
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback;
import com.google.android.gms.cast.tv.media.MediaManager;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

final class CastConnectBridge {
    private CastConnectBridge() {
    }

    static void start(Activity activity, Intent launchIntent) {
        try {
            CastReceiverContext context = CastReceiverContext.getInstance();
            MediaManager mediaManager = context.getMediaManager();
            mediaManager.setMediaLoadCommandCallback(new MediaLoadCommandCallback() {
                @Override
                public Task<MediaLoadRequestData> onLoad(
                        String senderId,
                        MediaLoadRequestData requestData) {
                    return Tasks.call(() -> {
                        MediaInfo info = requestData.getMediaInfo();
                        if (info == null) {
                            throw new IllegalArgumentException("Cast LOAD has no MediaInfo");
                        }

                        String url = info.getContentUrl();
                        if (url == null || url.trim().isEmpty()) {
                            url = info.getContentId();
                        }
                        if (url == null || url.trim().isEmpty()) {
                            throw new IllegalArgumentException("Cast LOAD has no media URL");
                        }

                        String finalUrl = url.trim();
                        activity.runOnUiThread(() -> {
                            Intent player = new Intent(activity, PlayerActivity.class);
                            player.putExtra(PlayerActivity.EXTRA_URL, finalUrl);
                            player.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            activity.startActivity(player);
                        });

                        mediaManager.setDataFromLoad(requestData);
                        mediaManager.broadcastMediaStatus();
                        return requestData;
                    });
                }
            });

            if (launchIntent != null) {
                mediaManager.onNewIntent(launchIntent);
            }
            context.start();
        } catch (Throwable ignored) {
            // Cast Connect is optional; DLNA/HTTP/AirPlay remain available.
        }
    }

    static void onNewIntent(Intent intent) {
        try {
            CastReceiverContext.getInstance().getMediaManager().onNewIntent(intent);
        } catch (Throwable ignored) {
        }
    }

    static void stop() {
        try {
            CastReceiverContext.getInstance().stop();
        } catch (Throwable ignored) {
        }
    }
}
