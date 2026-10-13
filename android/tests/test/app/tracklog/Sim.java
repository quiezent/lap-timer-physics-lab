package app.tracklog;

import android.location.Location;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test code only: plays GPS fixes into the fake phone on a virtual timeline.
 *
 * A drive of a few hundred seconds is delivered in a fraction of a second, so the fake clock (SystemClock.OFFSET_NS) is moved along
 * with it: before each fix "now" is set to the fix's own time plus a delivery delay of 0.15 s, as on a phone. Everything the recorder
 * and the service read from the clock (the once-a-second tick, the time a button is pressed, how old the last fix is) then agrees with
 * the time stamps of the fixes, exactly as it does on a real phone. The clock only ever moves forward.
 *
 * After each fix, settle() waits until the fix and everything it set going (the main thread, the sensor thread, a start of the
 * recorder) has been dealt with, so the scenario can look at the state right after a fix.
 */
final class Sim {

    static final double LATENCY_S = 0.15;
    static final long UTC_BASE_MS = 1760000000000L;      // 9 Oct 2025, an arbitrary wall clock for the fixes' own time

    final FakeLocationManager lm;
    final FakeSensorManager sm;
    final ServiceHost host;                              // null when there is no service, only a recorder
    final long base = SystemClock.elapsedRealtimeNanos();   // virtual time 0 (continues from wherever the clock is)
    int delivered;
    double lastT = 0.0;                                  // virtual time of the latest fix delivered

    Sim(FakeLocationManager lm, FakeSensorManager sm, ServiceHost host) {
        this.lm = lm;
        this.sm = sm;
        this.host = host;
    }

    /** Moves "now" to virtual time v (plus the delivery delay), never backwards. */
    void at(double v) {
        final long want = base + Math.round((v + LATENCY_S) * 1e9);
        final long now = SystemClock.elapsedRealtimeNanos();
        if (want > now) SystemClock.OFFSET_NS += want - now;
    }

    /** The elapsed-realtime stamp of a fix made at virtual time v: a whole number of microseconds from the start of the timeline. */
    long stamp(double v) {
        return base + Math.round(v * 1e6) * 1000L;
    }

    Location loc(Fix f) {
        final Location l = new Location("gps");
        l.setLatitude(f.lat);
        l.setLongitude(f.lon);
        l.setAltitude(40.0);
        if (f.speed >= 0.0) l.setSpeed((float) f.speed);
        if (f.bearing >= 0.0) l.setBearing((float) f.bearing);
        if (f.hAcc >= 0.0) l.setAccuracy((float) f.hAcc);
        l.setSpeedAccuracyMetersPerSecond(0.3f);
        l.setBearingAccuracyDegrees(5f);
        l.setTime(UTC_BASE_MS + Math.round(f.t * 1000.0));
        l.setElapsedRealtimeNanos(stamp(f.t));
        return l;
    }

    /** One fix: the clock moves to its time, the fix is delivered to whoever listens, and everything it caused is waited for. */
    void deliver(Fix f) throws InterruptedException {
        at(f.t);
        lm.deliverLocation(loc(f));
        delivered++;
        lastT = f.t;
        settle();
    }

    /** Waits until the main thread and the sensor thread have nothing left from what was posted so far (two rounds: each can post to the other). */
    void settle() throws InterruptedException {
        for (int i = 0; i < 2; i++) {
            if (host != null) host.flushMain();
            drainSensor();
        }
        if (host != null) host.flushMain();
    }

    private void drainSensor() throws InterruptedException {
        final FakeSensorManager.Reg r;
        try {
            r = sm.regs.get(0);
        } catch (IndexOutOfBoundsException e) {
            return;                                     // no recorder is registered (any more)
        }
        if (r.handler == null) return;
        final CountDownLatch l = new CountDownLatch(1);
        if (r.handler.post(new Runnable() { @Override public void run() { l.countDown(); } })) l.await(3, TimeUnit.SECONDS);
    }
}
