package app.tracklog;

import android.location.GnssStatus;
import android.location.Location;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Synthetic phone: one thread, real-time paced at 100 Hz. Events carry timestamps from the schedule
 * (System.nanoTime based, 0 to 0.5 ms in the past), exactly like sensor timestamps on a device.
 *  - accelerometer: gravity (0.3, 0.2, 9.75) + N(0, 0.02); +5 m/s^2 on x from 6.0 s to 9.0 s
 *  - gyroscope: bias (0.001, -0.002, 0.0005) rad/s + N(0, 0.002)
 *  - GPS fix every second at 0.5, 1.5, ... (stamped 120 ms before delivery); speed 0 until 6 s, then 5 m/s^2 up to 15 m/s
 *  - GNSS status callbacks at 0.2 0.7 1.4 4.0 8.0 8.5 s (Recorder emits S rows at most once per second)
 */
final class Feeder extends Thread {
    static final class Snap {
        double t;
        boolean recording, failed, calDone, calOk, haveFix;
        float horizG, vertG, speedMs;
        double aHz, gHz, lHz;
        long rowsA, rowsG, rowsL, bytes;
        int satsV, satsU;
        String error, calText;

        static Snap take(double t) {
            Recorder.Live l = Recorder.LIVE;
            Snap s = new Snap();
            s.t = t; s.recording = l.recording; s.failed = l.failed; s.calDone = l.calDone; s.calOk = l.calOk;
            s.haveFix = l.haveFix; s.horizG = l.horizG; s.vertG = l.vertG; s.speedMs = l.speedMs;
            s.aHz = l.aHz; s.gHz = l.gHz; s.lHz = l.lHz; s.rowsA = l.rowsA; s.rowsG = l.rowsG; s.rowsL = l.rowsL;
            s.bytes = l.bytes; s.satsV = l.satsVisible; s.satsU = l.satsUsed; s.error = l.error; s.calText = l.calText;
            return s;
        }

        String line() {
            return String.format("t=%4.1f rec=%b fail=%b cal=%b/%b horizG=%.3f vertG=%.3f aHz=%6.1f gHz=%6.1f lHz=%4.1f spd=%5.1f fix=%b rows=%d/%d/%d sats=%d/%d bytes=%d",
                    t, recording, failed, calDone, calOk, horizG, vertG, aHz, gHz, lHz, speedMs, haveFix, rowsA, rowsG, rowsL, satsV, satsU, bytes);
        }
    }

    static final double GX = 0.3, GY = 0.2, GZ = 9.75;
    static final double PUSH_FROM = 6.0, PUSH_TO = 9.0, PUSH = 5.0;
    static final int[] GNSS_STEPS = {20, 70, 140, 400, 800, 850};
    static final int[][] GNSS_VAL = {{14, 6}, {15, 7}, {16, 9}, {17, 10}, {18, 11}, {18, 12}};

    final FakeSensorManager sm;
    final FakeLocationManager lm;
    final int steps;
    final Map<Integer, Runnable> hooks = new HashMap<>();
    final List<Snap> snaps = new ArrayList<>();
    volatile Throwable error;
    volatile long base;
    /** added to every SensorEvent.timestamp (foreign sensor clock); GPS fixes stay on the elapsed-realtime clock */
    volatile long sensorClockOffsetNs;
    /** deliver an accelerometer sample only every n-th 10 ms step (n=20 -> 5 Hz) */
    volatile int accelEvery = 1;
    int sentA, sentG, sentL, sentGnss;

    Feeder(FakeSensorManager sm, FakeLocationManager lm, double seconds) {
        super("feeder");
        this.sm = sm; this.lm = lm; this.steps = (int) Math.round(seconds * 100);
    }

    private static void sleepUntil(long due) {
        long d;
        while ((d = due - System.nanoTime()) > 0) LockSupport.parkNanos(d);
    }

    static double speedAt(double t) { return t < 6.0 ? 0.0 : 5.0 * Math.min(t - 6.0, 3.0); }

    @Override public void run() {
        try {
            final Random rnd = new Random(42);
            base = SystemClock.elapsedRealtimeNanos();
            double northing = 0;
            for (int k = 0; k < steps; k++) {
                final long due = base + k * 10_000_000L;
                sleepUntil(due);
                final double t = k / 100.0;
                if (k % 50 == 0) snaps.add(Snap.take(t));
                Runnable hook = hooks.get(k);
                if (hook != null) hook.run();

                double ax = GX + rnd.nextGaussian() * 0.02, ay = GY + rnd.nextGaussian() * 0.02, az = GZ + rnd.nextGaussian() * 0.02;
                if (t >= PUSH_FROM && t < PUSH_TO) ax += PUSH;
                long tsA = due - (long) (rnd.nextDouble() * 500_000) + sensorClockOffsetNs;
                if (k % accelEvery == 0) {
                    sm.deliver(sm.accel, (float) ax, (float) ay, (float) az, tsA);
                    sentA++;
                }

                double gx = 0.001 + rnd.nextGaussian() * 0.002, gy = -0.002 + rnd.nextGaussian() * 0.002, gz = 0.0005 + rnd.nextGaussian() * 0.002;
                sm.deliver(sm.gyro, (float) gx, (float) gy, (float) gz, due - (long) (rnd.nextDouble() * 500_000) + sensorClockOffsetNs);
                sentG++;

                if (k >= 50 && (k - 50) % 100 == 0) {
                    int n = (k - 50) / 100;
                    double spd = speedAt(t);
                    northing += spd;
                    Location l = new Location("gps");
                    l.setLatitude(47.0 + northing / 111320.0);
                    l.setLongitude(8.0);
                    l.setAltitude(512.3 + 0.05 * n);
                    l.setSpeed((float) spd);
                    if (spd > 0.5) { l.setBearing(10f); l.setBearingAccuracyDegrees(4f); }
                    l.setAccuracy(3.5f);
                    l.setSpeedAccuracyMetersPerSecond(0.25f);
                    l.setTime(System.currentTimeMillis() - 120);
                    l.setElapsedRealtimeNanos(due - 120_000_000L);
                    lm.deliverLocation(l);
                    sentL++;
                }
                for (int i = 0; i < GNSS_STEPS.length; i++) {
                    if (GNSS_STEPS[i] != k) continue;
                    int n = GNSS_VAL[i][0], u = GNSS_VAL[i][1];
                    boolean[] used = new boolean[n];
                    for (int j = 0; j < u; j++) used[j] = true;
                    lm.deliverGnss(new GnssStatus(n, used));
                    sentGnss++;
                }
            }
            snaps.add(Snap.take(steps / 100.0));
        } catch (Throwable t) {
            error = t;
        }
    }
}
