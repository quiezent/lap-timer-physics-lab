package app.tracklog;

import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;

/**
 * The armed state's GPS listener: only the GPS is on, nothing is recorded. It feeds the fixes to a StageWatcher and, when the car
 * comes moving into the approach zone of the start line, calls back once. Runs on the main looper. It also keeps the "car has
 * stopped" answer for the screen (ResultGuard) up to date.
 */
final class ArmedWatcher implements LocationListener {

    interface Callback {
        /** The recorder should start now. 'previous' is the fix before the trigger one (null if there was none). */
        void trigger(Location previous, Location trigger);
    }

    private final StageWatcher watcher;
    private final Callback cb;
    private final ResultGuard guard = new ResultGuard();
    private Location last;
    private boolean fired;

    ArmedWatcher(StageWatcher watcher, Callback cb) {
        this.watcher = watcher;
        this.cb = cb;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (fired || l == null) return;
        final double t = l.getElapsedRealtimeNanos() / 1e9;
        final double speed = l.hasSpeed() ? l.getSpeed() : -1.0;
        guard.onFix(t, speed);
        StageLive.haveFix = true;
        StageLive.stopped = guard.stopped(t);
        final Location before = last;
        last = l;
        final Fix f = new Fix(t, l.getLatitude(), l.getLongitude(), speed, l.hasBearing() ? l.getBearing() : -1.0,
                l.hasAccuracy() ? l.getAccuracy() : -1.0);
        if (watcher.onFix(f)) {
            fired = true;
            cb.trigger(before, l);
        }
    }

    /** Once a second, so "stopped" turns false when the GPS goes quiet. @param nowS elapsed-realtime seconds */
    void tick(double nowS) {
        if (!fired) {
            StageLive.stopped = guard.stopped(nowS);
            final Location l = last;
            StageLive.haveFix = l != null && nowS - l.getElapsedRealtimeNanos() / 1e9 <= ResultGuard.MAX_FIX_AGE_S;
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
}
