package rs.stevusa.airboxreceiver;

import android.content.Context;

import com.google.android.gms.cast.tv.CastReceiverOptions;
import com.google.android.gms.cast.tv.ReceiverOptionsProvider;

public class AirBoxReceiverOptionsProvider implements ReceiverOptionsProvider {
    @Override
    public CastReceiverOptions getOptions(Context context) {
        return new CastReceiverOptions.Builder(context)
                .setStatusText("AirBox Receiver")
                .build();
    }
}
