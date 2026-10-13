package app.tracklog;

import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Part 1: sensor clock that is not the elapsed-realtime clock (+/- 1 h), and too little accelerometer data (5 Hz). */
final class ScenarioP1 {

    static void run(File root) throws Exception {
        clock(root, "P1a", 3_600_000_000_000L, -3_600_000L);
        clock(root, "P1b", -3_600_000_000_000L, 3_600_000L);
        // host-independent negative case: the first sensor timestamp is about -100 s whatever this host's uptime is
        long off = -(System.nanoTime() + 100_000_000_000L);
        clock(root, "P1d", off, -off / 1_000_000L);
        fewAccel(root);
    }

    static Feeder.Snap snapAt(Feeder fd, double t) { return Scenario1.snapAt(fd, t); }

    static void clock(File root, String tag, long offNs, long expectLagMs) throws Exception {
        System.out.println("\n===== " + tag + ": foreign sensor clock, every SensorEvent.timestamp " + (offNs > 0 ? "+" : "") + offNs + " ns =====");
        final File tmp = Files.createTempDirectory(root.toPath(), tag.toLowerCase() + "_").toFile();
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Recorder rec = new Recorder(new FakeContext(new File(tmp, "files"), sm, lm));
        final int errBefore = Harness.errBytes(), loopBefore = Looper.UNCAUGHT.size();

        Check.ok(tag + ".01 start() returned true", rec.start());
        final long nanoNow = System.nanoTime();
        System.out.println("info  System.nanoTime() now = " + nanoNow + " ns, so the first sensor timestamp is about " + (nanoNow + offNs) + " ns ("
                + (nanoNow + offNs < 0 ? "NEGATIVE" : "positive") + ")");
        final Feeder fd = new Feeder(sm, lm, 8.0);
        fd.sensorClockOffsetNs = offNs;
        fd.start();
        fd.join(60_000);
        Util.drain(sm);
        final long ms = Util.stopWithWatchdog(rec, 3000, tag);
        Check.ok(tag + ".02 no hang: feeder finished, stop() returned within 3 s", !fd.isAlive() && fd.error == null && ms < 3000, "stop " + ms + " ms");

        final CsvAudit a = new CsvAudit(rec.getFile());
        final Feeder.Snap s45 = snapAt(fd, 4.5), s55 = snapAt(fd, 5.5), s75 = snapAt(fd, 7.5);
        Check.ok(tag + ".03 LIVE calDone false at 4.5 s, calDone && calOk true at 5.5 s (window anchored to the first accel event)",
                !s45.calDone && s55.calDone && s55.calOk, "calText='" + s55.calText + "'");

        List<String[]> cals = new ArrayList<>(), calfails = new ArrayList<>();
        for (String[] r : a.kind("E")) { if (r[2].equals("CAL")) cals.add(r); if (r[2].equals("CALFAIL")) calfails.add(r); }
        boolean calGood = cals.size() == 1 && calfails.isEmpty();
        String calDetail = "CAL rows=" + cals.size() + " CALFAIL rows=" + calfails.size();
        if (cals.size() == 1) {
            String[] c = cals.get(0);
            long t = Long.parseLong(c[1]);
            long expectT = offNs / 1000 + 5_000_000L;
            calGood &= c[10].equals("1") && Math.abs(Double.parseDouble(c[3]) - 0.3) < 0.01 && Math.abs(Double.parseDouble(c[4]) - 0.2) < 0.01
                    && Math.abs(Double.parseDouble(c[5]) - 9.75) < 0.01 && Math.abs(t - expectT) < 200_000L;
            calDetail += " t_us=" + t + " (expected ~" + expectT + ") ok=" + c[10] + " mean=" + c[3] + "," + c[4] + "," + c[5];
        }
        Check.ok(tag + ".04 exactly one E,CAL row (ok=1, mean ~ (0.3, 0.2, 9.75), t_us on the sensor clock), no CALFAIL", calGood, calDetail);

        List<String[]> kA = a.kStream("A"), kG = a.kStream("G");
        boolean kGood = kA.size() == 1 && kG.size() == 1
                && Math.abs(Long.parseLong(kA.get(0)[3]) - expectLagMs) < 200 && Math.abs(Long.parseLong(kG.get(0)[3]) - expectLagMs) < 200;
        Check.ok(tag + ".05 K rows show the clock offset: lag_ms about " + expectLagMs, kGood,
                (kA.isEmpty() ? "no K,A" : String.join(",", kA.get(0))) + " | " + (kG.isEmpty() ? "no K,G" : String.join(",", kG.get(0))));

        List<String[]> A = a.kind("A");
        String mA = CsvAudit.monotonic(A, true), mG = CsvAudit.monotonic(a.kind("G"), true);
        long firstA = A.isEmpty() ? 0 : Long.parseLong(A.get(0)[1]);
        List<String> bad = a.badRows();
        Check.ok(tag + ".06 CSV well formed: field counts OK, A and G t_us strictly increasing, A,G rows ~800, A t_us offset by the same hour",
                bad.isEmpty() && mA == null && mG == null && A.size() >= 790 && A.size() <= 810 && a.kind("G").size() >= 790
                        && Math.abs(firstA - offNs / 1000) < 300_000L,
                "bad=" + bad.size() + " A=" + A.size() + " G=" + a.kind("G").size() + " first A t_us=" + firstA);
        Check.ok(tag + ".07 live horizG still works with the foreign clock (0.0 before the push, > 0.4 at 7.5 s)",
                s55.horizG < 0.05 && s75.horizG > 0.4, String.format("horizG@5.5=%.3f @7.5=%.3f", s55.horizG, s75.horizG));
        Check.ok(tag + ".08 no exception printed or thrown", Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopBefore && Harness.UNCAUGHT_THREADS.isEmpty());

        // information, not a check: the E stream mixes two time bases when the sensor clock is foreign
        List<String> es = new ArrayList<>();
        for (String[] r : a.kind("E")) es.add(r[2] + "@" + r[1]);
        System.out.println("info  E rows (event@t_us): " + es + "  (CAL is stamped on the sensor clock, START/STOP on the elapsed clock)");
        System.out.println("info  A first/last: " + A.get(0)[1] + " .. " + A.get(A.size() - 1)[1] + " ; K rows: " + (kA.isEmpty() ? "" : String.join(",", kA.get(0))) + " ; " + (kG.isEmpty() ? "" : String.join(",", kG.get(0))));
    }

    static void fewAccel(File root) throws Exception {
        final String tag = "P1c";
        System.out.println("\n===== P1c: too little accelerometer data (5 Hz), gyro 100 Hz =====");
        final File tmp = Files.createTempDirectory(root.toPath(), "p1c_").toFile();
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Recorder rec = new Recorder(new FakeContext(new File(tmp, "files"), sm, lm));
        final int errBefore = Harness.errBytes(), loopBefore = Looper.UNCAUGHT.size();

        Check.ok(tag + ".01 start() returned true", rec.start());
        final Feeder fd = new Feeder(sm, lm, 8.0);
        fd.accelEvery = 20;
        fd.start();
        fd.join(60_000);
        Util.drain(sm);
        final long ms = Util.stopWithWatchdog(rec, 3000, tag);
        Check.ok(tag + ".02 no crash/hang: feeder finished, stop() returned within 3 s", !fd.isAlive() && fd.error == null && ms < 3000, "stop " + ms + " ms");

        final CsvAudit a = new CsvAudit(rec.getFile());
        final Feeder.Snap s45 = snapAt(fd, 4.5), s60 = snapAt(fd, 6.0), end = fd.snaps.get(fd.snaps.size() - 1);
        List<String[]> cals = new ArrayList<>(), fails = new ArrayList<>();
        for (String[] r : a.kind("E")) { if (r[2].equals("CAL")) cals.add(r); if (r[2].equals("CALFAIL")) fails.add(r); }
        long tFail = fails.size() == 1 ? Long.parseLong(fails.get(0)[1]) : -1;
        Check.ok(tag + ".03 exactly one E,CALFAIL row (3 fields) at about 5 s, and no E,CAL row",
                fails.size() == 1 && cals.isEmpty() && fails.get(0).length == 3 && tFail >= 5_000_000L && tFail < 5_500_000L,
                "CALFAIL rows=" + fails.size() + " CAL rows=" + cals.size() + " row=" + (fails.isEmpty() ? "" : String.join(",", fails.get(0))));
        Check.ok(tag + ".04 LIVE: calDone false at 4.5 s, then calDone true and calOk false, calText says not enough data",
                !s45.calDone && s60.calDone && !s60.calOk && "not enough accelerometer data".equals(s60.calText), "calText='" + s60.calText + "'");
        List<String> bad = a.badRows();
        Check.ok(tag + ".05 CSV well formed; A rows ~40 (5 Hz x 8 s), G rows ~800; START first, STOP last",
                bad.isEmpty() && a.kind("A").size() >= 38 && a.kind("A").size() <= 42 && a.kind("G").size() >= 790
                        && a.data.get(0).equals("E,0,START") && a.data.get(a.data.size() - 1).matches("E,\\d+,STOP"),
                "bad=" + bad.size() + " A=" + a.kind("A").size() + " G=" + a.kind("G").size());
        Check.ok(tag + ".06 live g values stay finite after CALFAIL (fallback up = z axis)",
                !Float.isNaN(end.horizG) && !Float.isNaN(end.vertG) && end.horizG >= 0, String.format("horizG=%.3f vertG=%.3f", end.horizG, end.vertG));
        Check.ok(tag + ".07 no exception printed or thrown", Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopBefore && Harness.UNCAUGHT_THREADS.isEmpty());
    }
}
