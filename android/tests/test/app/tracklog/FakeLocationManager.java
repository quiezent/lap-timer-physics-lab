package app.tracklog;

import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

/** Fake LocationManager; fixes and GNSS status are posted to the registered looper / handler thread. */
final class FakeLocationManager extends LocationManager {
    volatile boolean gpsEnabled = true;
    volatile LocationListener listener;
    volatile Looper listenerLooper;
    volatile GnssStatus.Callback gnss;
    volatile Handler gnssHandler;
    volatile int requestCalls, removeCalls, gnssRegCalls, gnssUnregCalls;

    @Override public boolean isProviderEnabled(String provider) { return gpsEnabled; }

    @Override public void requestLocationUpdates(String provider, long minTimeMs, float minDistM, LocationListener l, Looper looper) {
        listener = l; listenerLooper = looper; requestCalls++;
    }

    @Override public void removeUpdates(LocationListener l) {
        if (listener == l) { listener = null; listenerLooper = null; }
        removeCalls++;
    }

    @Override public boolean registerGnssStatusCallback(GnssStatus.Callback cb, Handler h) {
        gnss = cb; gnssHandler = h; gnssRegCalls++;
        return true;
    }

    @Override public void unregisterGnssStatusCallback(GnssStatus.Callback cb) {
        if (gnss == cb) { gnss = null; gnssHandler = null; }
        gnssUnregCalls++;
    }

    void deliverLocation(final Location loc) {
        final LocationListener l = listener; final Looper lp = listenerLooper;
        if (l == null || lp == null) return;
        new Handler(lp).post(() -> l.onLocationChanged(loc));
    }

    void deliverGnss(final GnssStatus st) {
        final GnssStatus.Callback cb = gnss; final Handler h = gnssHandler;
        if (cb == null) return;
        if (h != null) h.post(() -> cb.onSatelliteStatusChanged(st)); else cb.onSatelliteStatusChanged(st);
    }
}
