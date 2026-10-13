package android.location;

import android.os.Handler;
import android.os.Looper;

/** FAKE: abstract like the real one; the test subclasses it (app.tracklog.FakeLocationManager). */
public abstract class LocationManager {
    public static final String GPS_PROVIDER = "gps";

    public abstract boolean isProviderEnabled(String provider);
    public abstract void requestLocationUpdates(String provider, long minTimeMs, float minDistanceM,
                                                LocationListener listener, Looper looper);
    public abstract void removeUpdates(LocationListener listener);
    public abstract boolean registerGnssStatusCallback(GnssStatus.Callback callback, Handler handler);
    public abstract void unregisterGnssStatusCallback(GnssStatus.Callback callback);
}
