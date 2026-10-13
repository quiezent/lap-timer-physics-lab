package app.tracklog;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records raw accelerometer, gyroscope and GPS to ONE csv file.
 *
 * Every row starts with a kind letter and a time in microseconds since the start of the session
 * (all three sources use the Android "elapsed realtime" clock, so they share one time base):
 *
 *   A,t_us,ax,ay,az                                   accelerometer, m/s^2, gravity included, phone axes
 *   G,t_us,gx,gy,gz                                   gyroscope, rad/s, phone axes
 *   L,t_us,lat,lon,alt,speed,bearing,hAcc,sAcc,bAcc,utc_ms,lag_ms   GPS fix
 *        t_us is when the fix was MADE and lag_ms how late it arrived. The row is written on arrival, so L rows can
 *        sit slightly out of order and the first one can have a small negative t_us: sort by t_us.
 *        A value the phone did not provide is -1, except altitude, which is -9999.
 *   S,t_us,satellites_visible,satellites_used         GNSS status, about once a second
 *   E,t_us,NAME[,values...]                           events:
 *        START
 *        CAL,mx,my,mz,noise,bx,by,bz,ok               stationary calibration: mean accel (m/s^2), accel noise,
 *                                                     mean gyro (rad/s), ok = 1 if the car was judged still
 *        CALFAIL                                      too little accelerometer data to calibrate
 *        LAP,n,seconds                                the car passed the timing line (LapTimer): lap n took this long;
 *                                                     n = 0 is the first pass, which only starts the clock
 *        STAGE,id                                     this recording is a run of the stage with this id
 *        STAGE_START                                  the car crossed the stage's start line (t = the interpolated moment)
 *        STAGE_FINISH,seconds                         ... its finish line; the stage took this long
 *        STAGE_DNF,reason / STAGE_ABORT,reason        the run did not finish / never started
 *        MARK_START,lat,lon,acc / MARK_FINISH,lat,lon,acc   the driver parked and marked the start / finish of a new stage
 *        STOP                                         always the last row
 *   K,t_us,A|G,lag_ms                                 clock check: arrival time minus event time
 *
 * Nothing is processed on the phone. All the physics happens offline (analyze.py).
 */
final class Recorder implements SensorEventListener, LocationListener {

    static final int SENSOR_PERIOD_US = 10000;      // ask for 100 Hz
    static final double G = 9.80665;
    static final double CAL_FROM_S = 1.0;           // stationary calibration window after START
    static final double CAL_TO_S = 5.0;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long NONE = Long.MIN_VALUE;     // "no timestamp yet" (a real one may be negative)

    /** What the screen shows. Written on the sensor thread, read on the UI thread. */
    static final class Live {
        volatile boolean recording;
        volatile boolean saving;          // stopped, and the copy to Downloads is still running
        volatile boolean failed;
        volatile String error = "";
        volatile String fileName = "";
        volatile long startElapsedNs;
        volatile long bytes;
        volatile long rowsA, rowsG, rowsL;
        volatile double aHz, gHz, lHz;
        volatile boolean hasAccel, hasGyro;
        volatile boolean haveFix;
        volatile long lastFixElapsedNs;
        volatile float speedMs, hAcc;
        volatile int satsVisible, satsUsed;
        volatile boolean calDone, calOk;
        volatile String calText = "";
        volatile float horizG, vertG;
        volatile String exportText = "";
        volatile String lastExportUri = "";
        // lap timer (see LapTimer): written on the sensor thread when a GPS fix reveals a pass of the line
        volatile int lapNo;                 // complete laps this session
        volatile long lapStartNs;           // elapsed-realtime ns of the last pass of the line; 0 until the first pass
        volatile double lastLapS = -1, bestLapS = -1;
        volatile int bestLapNo;
        volatile String lapText = "";       // the laps so far, newest first, one per line
    }

    static final Live LIVE = new Live();

    private final Context ctx;
    private final SensorManager sm;
    private final LocationManager lm;
    private final Object lock = new Object();

    private HandlerThread thread;
    private Handler handler;
    private BufferedOutputStream out;
    private File file;
    private long t0ns;
    private long bytes;
    private volatile boolean running;     // rows are being written; fail() clears it
    private boolean started, stopped;     // guarded by lock; a Recorder is used once
    private GnssStatus.Callback gnssCb;
    private LapTimer lap;             // sensor thread only; null until start()
    private volatile StageSink stageSink;   // the stage feature (StageSession / MarkSession), null for an ordinary recording
    private volatile long startUtcMs;       // wall clock at START, for dating stage runs

    // per-second rate counters (sensor thread only)
    private long cntA, cntG, cntL, rateT0;
    private long lastGnssUs = -2000000L, lastClockA = NONE, lastClockG = NONE;

    // stationary calibration accumulators (sensor thread only)
    private long calT0Ns = NONE;     // timestamp of the first accelerometer event: the calibration window counts from here
    private int calN, calGN;
    private double sx, sy, sz, sxx, syy, szz, gx0, gy0, gz0, ggg;
    private double ux, uy, uz;       // unit "up" vector in phone axes, valid when calDone
    private float emaH, emaV;

    Recorder(Context c) {
        ctx = c.getApplicationContext();
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    File getFile() {
        return file;
    }

    /** Wall-clock milliseconds at START (the header's start_utc_ms); 0 before start(). */
    long startUtcMs() {
        return startUtcMs;
    }

    /**
     * Hands the recording to the stage feature: every GPS fix, a tick every second, and the stop are passed to it, and it can write
     * event rows. Call before start(). The calls are wrapped in try/catch: a fault in the stage code cannot disturb the recording.
     */
    void setStageSink(StageSink s) {
        stageSink = s;
        if (s != null) {
            try {
                s.attach(stageOut);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** The stage feature's way to write E rows and to read the recording clock (seconds since START). */
    private final StageSink.Out stageOut = new StageSink.Out() {
        @Override
        public void event(double tS, String text) {
            emit("E," + Math.round(tS * 1e6) + "," + text);
        }

        @Override
        public double now() {
            return usSince(SystemClock.elapsedRealtimeNanos()) / 1e6;
        }
    };

    /**
     * Passes a GPS fix that arrived before the recording started (the stage watcher's last fixes) to the recorder as if it had arrived
     * on its sensor thread. A fix more than 2 s older than START is dropped, like any stale fix. Safe to call from any thread.
     */
    void injectLocation(final Location l) {
        final Handler h = handler;
        if (h == null || l == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (running) onLocationChanged(l);
            }
        });
    }

    boolean start() {
        synchronized (lock) {
            if (started) return running;
            LIVE.error = "";
            LIVE.failed = false;
            LIVE.exportText = "";
            LIVE.calDone = false;
            LIVE.calOk = false;
            LIVE.calText = "";
            LIVE.rowsA = 0;
            LIVE.rowsG = 0;
            LIVE.rowsL = 0;
            LIVE.haveFix = false;
            LIVE.speedMs = 0f;
            LIVE.satsVisible = 0;
            LIVE.satsUsed = 0;
            LIVE.horizG = 0f;
            LIVE.vertG = 0f;
            LIVE.lapNo = 0;
            LIVE.lapStartNs = 0L;
            LIVE.lastLapS = -1;
            LIVE.bestLapS = -1;
            LIVE.bestLapNo = 0;
            LIVE.lapText = "";
            lap = new LapTimer();
            try {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
                dir.mkdirs();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                file = new File(dir, "session_" + stamp + ".csv");
                // two recordings can start within one second now (a stage run that ends, a car that triggers again): never overwrite
                for (char c = 'b'; file.exists() && c <= 'z'; c++) file = new File(dir, "session_" + stamp + c + ".csv");
                out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            } catch (Exception e) {
                LIVE.error = "Cannot create file: " + e.getMessage();
                LIVE.failed = true;
                return false;
            }

            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            LIVE.hasAccel = acc != null;
            LIVE.hasGyro = gyr != null;
            if (acc == null) {
                LIVE.error = "This phone has no accelerometer";
                LIVE.failed = true;
                closeQuietly();
                return false;
            }

            t0ns = SystemClock.elapsedRealtimeNanos();
            bytes = 0;
            cntA = cntG = cntL = 0;
            rateT0 = t0ns;
            calN = calGN = 0;
            calT0Ns = NONE;
            sx = sy = sz = sxx = syy = szz = gx0 = gy0 = gz0 = ggg = 0;
            lastGnssUs = -2000000L;
            lastClockA = lastClockG = NONE;
            emaH = emaV = 0f;

            try {
                StringBuilder h = new StringBuilder(1024);
                h.append("# TrackLogger 1.4 (raw phone-frame log; process offline with analyze.py)\n");
                h.append("# device,").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                h.append("# android,").append(Build.VERSION.RELEASE).append(",sdk,").append(Build.VERSION.SDK_INT).append('\n');
                startUtcMs = System.currentTimeMillis();
                h.append("# start_utc_ms,").append(startUtcMs).append('\n');
                h.append("# start_elapsed_ns,").append(t0ns).append('\n');
                h.append("# accel,").append(describe(acc)).append('\n');
                h.append("# gyro,").append(describe(gyr)).append('\n');
                h.append("# rows: A,t_us,ax,ay,az | G,t_us,gx,gy,gz | L,t_us,lat,lon,alt,speed,bearing,hAcc,sAcc,bAcc,utc_ms,lag_ms | S,t_us,vis,used | E,t_us,NAME,... | K,t_us,A/G,lag_ms\n");
                byte[] hb = h.toString().getBytes(UTF8);
                out.write(hb);
                bytes += hb.length;
            } catch (IOException e) {
                LIVE.error = "Cannot write file: " + e.getMessage();
                LIVE.failed = true;
                closeQuietly();
                return false;
            }

            started = true;
            running = true;
            LIVE.fileName = file.getName();
            LIVE.startElapsedNs = t0ns;
            LIVE.bytes = bytes;
            LIVE.recording = true;
        }

        emit("E," + 0 + ",START");

        thread = new HandlerThread("tracklog-sensors", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());

        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        try {
            if (acc != null) sm.registerListener(this, acc, SENSOR_PERIOD_US, 0, handler);
            if (gyr != null) sm.registerListener(this, gyr, SENSOR_PERIOD_US, 0, handler);
        } catch (Exception e) {
            LIVE.error = "Sensor error: " + e.getMessage();
        }

        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LIVE.error = "GPS is switched off. Turn Location on (swipe down, Location tile).";
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, thread.getLooper());
        } catch (SecurityException e) {
            LIVE.error = "Location permission missing (needs Precise location).";
        } catch (Exception e) {
            LIVE.error = "GPS error: " + e.getMessage();
        }

        try {
            gnssCb = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    onGnss(status);
                }
            };
            lm.registerGnssStatusCallback(gnssCb, handler);
        } catch (Exception e) {
            gnssCb = null;   // optional extra, not essential
        }

        handler.postDelayed(tick, 1000);
        return true;
    }

    /**
     * Stops everything and closes the file. Safe to call more than once and after a write error (fail() only
     * clears 'running', so it must not be the test here: the sensors, thread and file are still held).
     */
    void stop() {
        final StageSink ss = stageSink;
        if (ss != null) {
            boolean open;
            synchronized (lock) {
                open = started && !stopped;
            }
            if (open) {                                  // closes an open stage run: its row must come before STOP
                try {
                    ss.onStop(usSince(SystemClock.elapsedRealtimeNanos()) / 1e6, stageOut);
                } catch (RuntimeException ignored) {
                }
            }
        }
        synchronized (lock) {
            if (!started || stopped) return;
            stopped = true;
            // The STOP row and 'running = false' share one lock hold, so no sensor row can follow STOP.
            // After fail() the row is skipped (emit() does nothing) but the teardown below still runs.
            emit("E," + usSince(SystemClock.elapsedRealtimeNanos()) + ",STOP");
            running = false;
        }
        try { sm.unregisterListener(this); } catch (Exception ignored) { }
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        try { if (gnssCb != null) lm.unregisterGnssStatusCallback(gnssCb); } catch (Exception ignored) { }
        Handler h = handler;
        if (h != null) h.removeCallbacks(tick);
        HandlerThread t = thread;
        if (t != null) {
            t.quitSafely();
            try { t.join(2000); } catch (InterruptedException ignored) { }
        }
        synchronized (lock) {
            closeQuietly();
            LIVE.bytes = bytes;
            LIVE.recording = false;
        }
    }

    // ------------------------------------------------------------------ housekeeping, 1 Hz

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtimeNanos();
            double dt = (now - rateT0) / 1e9;
            if (dt > 0.2) {
                LIVE.aHz = cntA / dt;
                LIVE.gHz = cntG / dt;
                LIVE.lHz = cntL / dt;
                cntA = cntG = cntL = 0;
                rateT0 = now;
            }
            synchronized (lock) {
                if (running && out != null) {
                    try {
                        out.flush();
                    } catch (IOException e) {
                        fail("WRITE ERROR: " + e.getMessage());
                    }
                }
                LIVE.bytes = bytes;
            }
            if (LIVE.haveFix && (now - LIVE.lastFixElapsedNs) > 3000000000L) LIVE.haveFix = false;
            final StageSink ss = stageSink;
            if (ss != null) {
                try {
                    ss.onTick(usSince(now) / 1e6, stageOut);
                } catch (RuntimeException ignored) {
                }
            }
            Handler h = handler;
            if (h != null && running) h.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ sensors

    @Override
    public void onSensorChanged(SensorEvent e) {
        final int type = e.sensor.getType();
        final float x = e.values[0], y = e.values[1], z = e.values[2];
        final long evNs = e.timestamp;
        final long tUs = usSince(evNs);
        final StringBuilder b = new StringBuilder(80);

        if (type == Sensor.TYPE_ACCELEROMETER) {
            b.append("A,").append(tUs).append(',').append(x).append(',').append(y).append(',').append(z);
            emit(b.toString());
            cntA++;
            LIVE.rowsA++;
            onAccel(x, y, z, evNs);
            if (lastClockA == NONE || evNs - lastClockA > 30000000000L) {
                lastClockA = evNs;
                emit("K," + tUs + ",A," + ((SystemClock.elapsedRealtimeNanos() - evNs) / 1000000L));
            }
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            b.append("G,").append(tUs).append(',').append(x).append(',').append(y).append(',').append(z);
            emit(b.toString());
            cntG++;
            LIVE.rowsG++;
            onGyro(x, y, z, evNs);
            if (lastClockG == NONE || evNs - lastClockG > 30000000000L) {
                lastClockG = evNs;
                emit("K," + tUs + ",G," + ((SystemClock.elapsedRealtimeNanos() - evNs) / 1000000L));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * Stationary calibration (first seconds after START) + live horizontal / vertical g for the screen.
     * The window is measured from the first accelerometer event, so it still ends on a phone whose sensor
     * timestamps are not on the elapsed-realtime clock (the K rows then show the offset).
     */
    private void onAccel(float x, float y, float z, long evNs) {
        if (calT0Ns == NONE) calT0Ns = evNs;
        final double ts = (evNs - calT0Ns) / 1e9;
        if (!LIVE.calDone && ts >= CAL_FROM_S && ts < CAL_TO_S) {
            calN++;
            sx += x; sy += y; sz += z;
            sxx += x * x; syy += y * y; szz += z * z;
        } else if (!LIVE.calDone && ts >= CAL_TO_S) {
            finishCalibration(usSince(evNs));
        }
        if (LIVE.calDone) {
            double dot = x * ux + y * uy + z * uz;
            double hx = x - dot * ux, hy = y - dot * uy, hz = z - dot * uz;
            float h = (float) (Math.sqrt(hx * hx + hy * hy + hz * hz) / G);
            float v = (float) (dot / G);
            emaH += 0.04f * (h - emaH);
            emaV += 0.04f * (v - emaV);
            LIVE.horizG = emaH;
            LIVE.vertG = emaV;
        }
    }

    private void onGyro(float x, float y, float z, long evNs) {
        if (calT0Ns == NONE) return;                   // the window starts with the first accelerometer event
        final double ts = (evNs - calT0Ns) / 1e9;
        if (!LIVE.calDone && ts >= CAL_FROM_S && ts < CAL_TO_S) {
            calGN++;
            gx0 += x; gy0 += y; gz0 += z;
            ggg += x * x + y * y + z * z;
        }
    }

    private void finishCalibration(long tUs) {
        if (calN < 50) {
            LIVE.calText = "not enough accelerometer data";
            LIVE.calOk = false;
            LIVE.calDone = true;
            ux = 0; uy = 0; uz = 1;
            emit("E," + tUs + ",CALFAIL");
            return;
        }
        double mx = sx / calN, my = sy / calN, mz = sz / calN;
        double g = Math.sqrt(mx * mx + my * my + mz * mz);
        double var = (sxx / calN - mx * mx) + (syy / calN - my * my) + (szz / calN - mz * mz);
        double noise = Math.sqrt(Math.max(var, 0));
        double bx = 0, by = 0, bz = 0, gyroRms = 0;
        if (calGN > 20) {
            bx = gx0 / calGN; by = gy0 / calGN; bz = gz0 / calGN;
            double v2 = ggg / calGN - (bx * bx + by * by + bz * bz);
            gyroRms = Math.sqrt(Math.max(v2, 0));
        }
        ux = mx / g; uy = my / g; uz = mz / g;
        boolean moving = LIVE.speedMs > 1.0f && LIVE.haveFix;
        boolean ok = !moving && noise < 1.0 && gyroRms < 0.08 && Math.abs(g - G) < 1.0;   // idling engines shake the mount
        LIVE.calOk = ok;
        LIVE.calText = String.format(Locale.US, "gravity %.2f m/s2, noise %.2f, gyro noise %.1f deg/s%s",
                g, noise, Math.toDegrees(gyroRms), ok ? "" : "  <- NOT STILL");
        LIVE.calDone = true;
        emit("E," + tUs + ",CAL," + mx + "," + my + "," + mz + "," + noise + "," + bx + "," + by + "," + bz + "," + (ok ? 1 : 0));
    }

    // ------------------------------------------------------------------ GPS

    @Override
    public void onLocationChanged(Location l) {
        final long fixNs = l.getElapsedRealtimeNanos();
        if (fixNs < t0ns - 2000000000L) return;          // stale fix from before the session
        final long nowNs = SystemClock.elapsedRealtimeNanos();
        final StringBuilder b = new StringBuilder(160);
        b.append("L,").append(usSince(fixNs)).append(',')
                .append(l.getLatitude()).append(',')
                .append(l.getLongitude()).append(',')
                .append(l.hasAltitude() ? l.getAltitude() : -9999.0).append(',')
                .append(l.hasSpeed() ? l.getSpeed() : -1f).append(',')
                .append(l.hasBearing() ? l.getBearing() : -1f).append(',')
                .append(l.hasAccuracy() ? l.getAccuracy() : -1f).append(',')
                .append(l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : -1f).append(',')
                .append(l.hasBearingAccuracy() ? l.getBearingAccuracyDegrees() : -1f).append(',')
                .append(l.getTime()).append(',')
                .append((nowNs - fixNs) / 1000000L);
        emit(b.toString());
        cntL++;
        LIVE.rowsL++;
        LIVE.speedMs = l.hasSpeed() ? l.getSpeed() : 0f;
        LIVE.hAcc = l.hasAccuracy() ? l.getAccuracy() : -1f;
        LIVE.lastFixElapsedNs = nowNs;
        LIVE.haveFix = true;
        try {                                            // the lap timer must never be able to disturb the recording
            final LapTimer lt = lap;
            if (lt != null && lt.onFix(fixNs / 1e9, l.getLatitude(), l.getLongitude(),
                    l.hasSpeed() ? l.getSpeed() : -1f, l.hasBearing() ? l.getBearing() : -1f)) {
                lapPassed(lt);
            }
        } catch (RuntimeException ignored) {
        }
        final StageSink ss = stageSink;
        if (ss != null) {                                // the stage feature gets the fix exactly as the file has it
            try {
                ss.onFix(new Fix(usSince(fixNs) / 1e6, l.getLatitude(), l.getLongitude(),
                        l.hasSpeed() ? logged(l.getSpeed()) : -1.0, l.hasBearing() ? logged(l.getBearing()) : -1.0,
                        l.hasAccuracy() ? logged(l.getAccuracy()) : -1.0), stageOut);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** A float as the file prints it, read back as a double: what a later scoring of the file will see. */
    private static double logged(float v) {
        return Double.parseDouble(Float.toString(v));
    }

    /** The line was crossed: update the screen and leave a row in the log (E,t_us,LAP,n,seconds; n = 0 starts the clock). */
    private void lapPassed(LapTimer lt) {
        final long passNs = (long) (lt.lastPass() * 1e9);
        final int n = lt.laps();
        if (n > 0) {
            LIVE.lastLapS = lt.lastLap();
            LIVE.bestLapS = lt.bestLap();
            LIVE.bestLapNo = lt.bestLapNo();
            LIVE.lapText = lt.listText(60);
            LIVE.lapNo = n;
        }
        LIVE.lapStartNs = passNs;
        emit("E," + usSince(passNs) + ",LAP," + n + "," + (n > 0 ? Math.round(lt.lastLap() * 1000.0) / 1000.0 : 0.0));
    }

    private void onGnss(GnssStatus s) {
        long tUs = usSince(SystemClock.elapsedRealtimeNanos());
        int n = s.getSatelliteCount(), used = 0;
        for (int i = 0; i < n; i++) if (s.usedInFix(i)) used++;
        LIVE.satsVisible = n;
        LIVE.satsUsed = used;
        if (tUs - lastGnssUs >= 1000000L) {
            lastGnssUs = tUs;
            emit("S," + tUs + "," + n + "," + used);
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) {
        if (LIVE.error.startsWith("GPS")) LIVE.error = "";      // "GPS is switched off..." is no longer true
    }
    @Override public void onProviderDisabled(String provider) {
        LIVE.error = "GPS was switched off during the session";
    }

    // ------------------------------------------------------------------ plumbing

    private long usSince(long ns) {
        return (ns - t0ns) / 1000L;
    }

    private void emit(String line) {
        synchronized (lock) {
            if (!running || out == null) return;
            try {
                byte[] b = line.getBytes(UTF8);
                out.write(b);
                out.write('\n');
                bytes += b.length + 1;
            } catch (IOException e) {
                fail("WRITE ERROR: " + e.getMessage());
            }
        }
    }

    private void fail(String msg) {
        LIVE.error = msg;
        LIVE.failed = true;
        running = false;
    }

    private void closeQuietly() {
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (IOException ignored) {
        }
        out = null;
    }

    private static String describe(Sensor s) {
        if (s == null) return "none";
        return s.getName().replace(',', ' ') + "," + s.getVendor().replace(',', ' ') + ",range=" + s.getMaximumRange()
                + ",res=" + s.getResolution() + ",min_delay_us=" + s.getMinDelay();
    }
}
