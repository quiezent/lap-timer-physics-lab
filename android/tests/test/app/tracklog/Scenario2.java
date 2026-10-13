package app.tracklog;

import android.os.Looper;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Scenario 2: the output stream starts failing mid-run.
 *  S2a: Recorder's BufferedOutputStream is closed (so its file descriptor is closed) at t = 1.5 s.
 *  S2b: the session file is a symlink to /dev/full, so the first flush fails with ENOSPC.
 * Required: LIVE.failed becomes true and nothing throws on the sensor thread.
 * Extra probe D1: after the failure, does stop() actually release the sensors, GPS and the thread?
 */
final class Scenario2 {

    static void closeFd(Recorder r) {
        try {
            Field fl = Recorder.class.getDeclaredField("out");
            fl.setAccessible(true);
            ((OutputStream) fl.get(r)).close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void run(File root) throws Exception {
        variant(root, "S2a", "output stream closed mid-run (fd closed at t=1.5 s)", false);
        if (Files.exists(Paths.get("/dev/full"))) variant(root, "S2b", "session file is /dev/full (ENOSPC at first flush)", true);
        else System.out.println("S2b skipped: /dev/full not present");
    }

    static void variant(File root, String tag, String what, boolean devFull) throws Exception {
        System.out.println("\n===== " + tag + ": write failure, " + what + " =====");
        final File tmp = Files.createTempDirectory(root.toPath(), tag.toLowerCase() + "_").toFile();
        final File ext = new File(tmp, "files");
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Recorder rec = new Recorder(new FakeContext(ext, sm, lm));
        final int errBefore = Harness.errBytes();
        final int loopBefore = Looper.UNCAUGHT.size();
        final int thrBefore = Harness.UNCAUGHT_THREADS.size();

        if (devFull) {
            ext.mkdirs();
            long now = System.currentTimeMillis();
            for (long ms : new long[]{now, now + 1000L}) {      // the Recorder names the file after the current second
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(ms));
                Files.createSymbolicLink(new File(ext, "session_" + stamp + ".csv").toPath(), Paths.get("/dev/full"));
            }
        }
        boolean started = rec.start();
        Check.ok(tag + ".01 start() returned true", started);
        final Thread sensorThread = Util.sensorThread(sm);

        final Feeder fd = new Feeder(sm, lm, 4.0);
        if (!devFull) fd.hooks.put(150, () -> closeFd(rec));
        fd.start();
        fd.join(60_000);
        Util.drain(sm);
        final Feeder.Snap end = Feeder.Snap.take(4.0);

        double tFail = -1;
        for (Feeder.Snap s : fd.snaps) if (s.failed) { tFail = s.t; break; }
        Check.ok(tag + ".02 Recorder.LIVE.failed becomes true after the write error", end.failed,
                "first seen at feeder t=" + tFail + " s; LIVE.error='" + end.error + "'");
        Check.ok(tag + ".03 LIVE.error is a WRITE ERROR message", end.error.startsWith("WRITE ERROR"), end.error);
        boolean quiet = Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopBefore
                && Harness.UNCAUGHT_THREADS.size() == thrBefore && fd.error == null;
        Check.ok(tag + ".04 nothing threw on the sensor thread / elsewhere, nothing printed to stderr", quiet,
                "stderr bytes=" + (Harness.errBytes() - errBefore) + " loopThrowables=" + (Looper.UNCAUGHT.size() - loopBefore)
                        + " threadThrowables=" + (Harness.UNCAUGHT_THREADS.size() - thrBefore) + " feederErr=" + fd.error);
        Check.ok(tag + ".05 sensor thread still alive and still servicing callbacks after the failure (LIVE.rowsA ~ 400)",
                sensorThread != null && sensorThread.isAlive() && end.rowsA >= 390 && end.rowsA <= 410,
                "rowsA=" + end.rowsA + " rowsG=" + end.rowsG + " rowsL=" + end.rowsL);
        if (!devFull) {
            CsvAudit pa = new CsvAudit(rec.getFile());
            Check.ok(tag + ".06 partial CSV written before the failure is intact (no torn rows, START present, ~150 A rows)",
                    pa.badRows().isEmpty() && pa.kind("E").size() >= 1 && pa.kind("A").size() >= 140 && pa.kind("A").size() <= 160,
                    pa.data.size() + " data rows, A=" + pa.kind("A").size());
        }

        final long ms = Util.stopWithWatchdog(rec, 3000, tag);
        Check.ok(tag + ".07 stop() after the failure returns within 3 s (watchdog), no throw", ms < 3000, ms + " ms");
        final Feeder.Snap post = Feeder.Snap.take(4.0);

        boolean released = !post.recording && sm.regs.isEmpty() && lm.listener == null && lm.gnss == null
                && sensorThread != null && !sensorThread.isAlive();
        Check.defect("D1." + tag + " after failure, stop() releases everything (LIVE.recording=false, sensor+GPS+GNSS listeners removed, thread ended)",
                released,
                "LIVE.recording=" + post.recording + " sensorRegs=" + sm.regs.size() + " gpsListener=" + (lm.listener != null)
                        + " gnssCb=" + (lm.gnss != null) + " sensorThreadAlive=" + (sensorThread != null && sensorThread.isAlive())
                        + " unregisterCalls=" + sm.unregisterCalls + " removeUpdatesCalls=" + lm.removeCalls);
        System.out.println("--- LIVE time series (feeder snapshots every 0.5 s; look at the fail column):");
        for (Feeder.Snap s : fd.snaps) System.out.println(s.line());
        Util.forceRelease(sm, lm, sensorThread);      // test cleanup only, so the next variant starts clean
    }
}
