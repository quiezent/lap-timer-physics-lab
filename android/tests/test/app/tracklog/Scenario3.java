package app.tracklog;

import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Scenario 3 (extra probe, not part of the requested checks): stop() while the sensor thread has a large backlog.
 * Recorder.stop() emits STOP and only afterwards, in a second synchronized block, sets running=false, so a
 * queued sensor row can be written between the two. Counts rows that follow the STOP row.
 */
final class Scenario3 {
    static void run(File root) throws Exception {
        final int cycles = 120;
        System.out.println("\n===== S3: stop() with a 20000-event backlog on the sensor thread, " + cycles + " cycles =====");
        final File base = Files.createTempDirectory(root.toPath(), "s3_").toFile();
        final int errBefore = Harness.errBytes();
        final int loopBefore = Looper.UNCAUGHT.size();
        int cyclesWithRowsAfterStop = 0, maxAfter = 0, totalAfter = 0, noStop = 0, startFailed = 0;
        long maxStopMs = 0;
        String example = "";
        for (int i = 0; i < cycles; i++) {
            final FakeSensorManager sm = new FakeSensorManager();
            final FakeLocationManager lm = new FakeLocationManager();
            final Recorder rec = new Recorder(new FakeContext(new File(base, "c" + i), sm, lm));
            if (!rec.start()) { startFailed++; continue; }
            final File f = rec.getFile();
            final long t = SystemClock.elapsedRealtimeNanos();
            for (int k = 0; k < 20000; k++) sm.deliver(sm.accel, 0.3f, 0.2f, 9.75f, t + k * 10_000L);
            maxStopMs = Math.max(maxStopMs, Util.stopWithWatchdog(rec, 3000, "S3"));
            List<String> lines = Files.readAllLines(f.toPath());
            int idx = -1;
            for (int j = 0; j < lines.size(); j++) if (lines.get(j).matches("E,\\d+,STOP")) idx = j;
            if (idx < 0) { noStop++; continue; }
            int after = lines.size() - 1 - idx;
            if (after > 0) {
                cyclesWithRowsAfterStop++;
                totalAfter += after;
                maxAfter = Math.max(maxAfter, after);
                if (example.isEmpty()) example = lines.get(idx) + "  then  " + lines.get(idx + 1);
            }
        }
        Check.ok("S3.01 every cycle: start() ok, STOP row present, stop() < 3 s, nothing thrown",
                startFailed == 0 && noStop == 0 && maxStopMs < 3000 && Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopBefore,
                "startFailed=" + startFailed + " noStop=" + noStop + " maxStopMs=" + maxStopMs);
        Check.defect("D2 STOP is the last row of the file (no sensor row written after STOP)",
                cyclesWithRowsAfterStop == 0,
                cyclesWithRowsAfterStop + "/" + cycles + " cycles had rows after STOP (max " + maxAfter + " rows, total " + totalAfter + ")"
                        + (example.isEmpty() ? "" : "; e.g. " + example));
    }
}
