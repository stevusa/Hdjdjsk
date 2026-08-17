package rs.stevusa.airboxreceiver;

import android.app.Application;

import com.google.android.gms.cast.tv.CastReceiverContext;

public class AirBoxApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            CastReceiverContext.initInstance(this);
        } catch (Throwable ignored) {
            // The rest of AirBox still works on devices without Cast Connect support.
        }
    }
}
