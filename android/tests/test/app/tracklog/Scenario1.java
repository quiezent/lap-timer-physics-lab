package app.tracklog;

import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Scenario 1: nominal 12 s session, audited through the produced CSV and Recorder.LIVE. */
final class Scenario1 {

    static Feeder.Snap snapAt(Feeder fd, double t) {
        Feeder.Snap best = null;
        for (Feeder.Snap s : fd.snaps) if (best == null || Math.abs(s.t - t) < Math.abs(best.t - t)) best = s;
        return best;
    }

    static boolean within(double v, double lo, double hi) { return v >= lo && v <= hi; }

    static int count(List<String[]> ev, String name) {
        int n = 0;
        for (String[] r : ev) if (r[2].equals(name)) n++;
        return n;
    }

    static void run(File root) throws Exception {
        System.out.println("\n===== SCENARIO 1: nominal 12 s session =====");
        final File tmp = Files.createTempDirectory(root.toPath(), "s1_").toFile();
        final File ext = new File(tmp, "Android/data/app.tracklog/files");      // does not exist yet: start() must mkdirs it
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Recorder rec = new Recorder(new FakeContext(ext, sm, lm));
        final int errBefore = Harness.errBytes();
        final int loopErrBefore = Looper.UNCAUGHT.size();

        boolean started = rec.start();
        final File f = rec.getFile();
        Check.ok("S1.01 start() returned true; session file created in the (mkdirs'd) external files dir",
                started && f != null && f.isFile() && ext.equals(f.getParentFile()), String.valueOf(f));
        Check.ok("S1.02 registered accel+gyro listeners, location listener, GNSS callback",
                sm.regs.size() == 2 && lm.listener != null && lm.gnss != null,
                "sensorRegs=" + sm.regs.size() + " loc=" + (lm.listener != null) + " gnss=" + (lm.gnss != null));
        final Thread sensorThread = Util.sensorThread(sm);

        final Feeder fd = new Feeder(sm, lm, 12.0);
        fd.start();
        fd.join(60_000);
        Check.ok("S1.03 feeder delivered 12 s of data without error",
                !fd.isAlive() && fd.error == null, "sent A=" + fd.sentA + " G=" + fd.sentG + " L=" + fd.sentL + " gnss=" + fd.sentGnss + (fd.error != null ? " err=" + fd.error : ""));
        Check.ok("S1.04 sensor looper drained before stop", Util.drain(sm));
        final Feeder.Snap pre = Feeder.Snap.take(12.0);

        final long stopMs = Util.stopWithWatchdog(rec, 3000, "S1");
        Check.ok("S1.05 stop() returned within 3 s (watchdog thread), no deadlock", stopMs < 3000, stopMs + " ms");
        final Feeder.Snap post = Feeder.Snap.take(12.0);
        final long len1 = f.length();
        final long stop2Ms = Util.stopWithWatchdog(rec, 3000, "S1 second stop()");
        Check.ok("S1.06 a second stop() is a harmless no-op (fast, file unchanged, LIVE.recording still false)",
                stop2Ms < 500 && f.length() == len1 && !Recorder.LIVE.recording, stop2Ms + " ms");

        final CsvAudit a = new CsvAudit(f);
        Files.copy(f.toPath(), new File(root, "session_s1.csv").toPath(), StandardCopyOption.REPLACE_EXISTING);

        // ---------------------------------------------------------------- CSV
        String[] want = {"# TrackLogger", "# device,", "# android,", "# start_utc_ms,", "# start_elapsed_ns,", "# accel,", "# gyro,", "# rows:"};
        List<String> missing = new ArrayList<>();
        String startElapsedHdr = "", accelHdr = "";
        for (String w : want) {
            boolean found = false;
            for (String h : a.header) if (h.startsWith(w)) found = true;
            if (!found) missing.add(w);
        }
        for (String h : a.header) {
            if (h.startsWith("# start_elapsed_ns,")) startElapsedHdr = h.substring("# start_elapsed_ns,".length());
            if (h.startsWith("# accel,")) accelHdr = h;
        }
        Check.ok("S1.10 '#' header lines present (all 8, before the first data row, sensor-name commas sanitised)",
                missing.isEmpty() && !a.headerAfterData && accelHdr.split(",", -1).length == 6,
                a.header.size() + " header lines" + (missing.isEmpty() ? "" : ", missing " + missing));

        List<String> bad = a.badRows();
        Check.ok("S1.11 every data row has the documented field count and parses (A,G=5 L=12 S=4 K=4 E: START/STOP=3 CAL=11)",
                bad.isEmpty() && a.endsWithNewline && !a.blankLine,
                a.data.size() + " data rows, " + bad.size() + " bad" + (bad.isEmpty() ? "" : "; first: " + bad.get(0)));

        List<String[]> ev = a.kind("E");
        String mA = CsvAudit.monotonic(a.kind("A"), true), mG = CsvAudit.monotonic(a.kind("G"), true);
        String mL = CsvAudit.monotonic(a.kind("L"), true), mS = CsvAudit.monotonic(a.kind("S"), true);
        String mE = CsvAudit.monotonic(ev, false);
        String mK = CsvAudit.monotonic(a.kStream("A"), false);
        if (mK == null) mK = CsvAudit.monotonic(a.kStream("G"), false);
        int inversions = 0;
        long prev = Long.MIN_VALUE;
        for (String ln : a.data) {
            long t = Long.parseLong(ln.split(",", -1)[1]);
            if (t < prev) inversions++;
            prev = t;
        }
        Check.ok("S1.12 t_us monotonic within each stream (A,G,L,S strictly increasing; E,K non-decreasing)",
                mA == null && mG == null && mL == null && mS == null && mE == null && mK == null,
                "A=" + mA + " G=" + mG + " L=" + mL + " S=" + mS + " E=" + mE + " K=" + mK + "; whole-file order inversions=" + inversions + " (L is stamped 120 ms before delivery)");

        List<String[]> cals = new ArrayList<>();
        for (String[] r : ev) if (r[2].equals("CAL")) cals.add(r);
        boolean calGood = false;
        String calDetail = "n=" + cals.size();
        if (cals.size() == 1) {
            String[] c = cals.get(0);
            double mx = Double.parseDouble(c[3]), my = Double.parseDouble(c[4]), mz = Double.parseDouble(c[5]), nz = Double.parseDouble(c[6]);
            double bx = Double.parseDouble(c[7]), by = Double.parseDouble(c[8]), bz = Double.parseDouble(c[9]);
            long t = Long.parseLong(c[1]);
            calGood = c[10].equals("1") && Math.abs(mx - 0.3) < 0.01 && Math.abs(my - 0.2) < 0.01 && Math.abs(mz - 9.75) < 0.01
                    && within(nz, 0.02, 0.06) && Math.abs(bx - 0.001) < 0.0005 && Math.abs(by + 0.002) < 0.0005 && Math.abs(bz - 0.0005) < 0.0005
                    && t >= 5_000_000L && t < 5_200_000L;
            calDetail += " " + String.join(",", c);
        }
        Check.ok("S1.13 exactly one CAL event, calOk=1, mean ~ (0.3, 0.2, 9.75), gyro bias recovered, at t ~ 5.0 s", cals.size() == 1 && calGood, calDetail);

        boolean startFirst = !a.data.isEmpty() && a.data.get(0).equals("E,0,START");
        boolean stopLast = !a.data.isEmpty() && a.data.get(a.data.size() - 1).matches("E,\\d+,STOP");
        Check.ok("S1.14 exactly one START (first data row, t_us=0) and one STOP (last data row)",
                count(ev, "START") == 1 && count(ev, "STOP") == 1 && startFirst && stopLast,
                "START=" + count(ev, "START") + " STOP=" + count(ev, "STOP") + " first=" + (a.data.isEmpty() ? "" : a.data.get(0)) + " last=" + (a.data.isEmpty() ? "" : a.data.get(a.data.size() - 1)));

        List<String[]> kA = a.kStream("A"), kG = a.kStream("G");
        boolean kGood = kA.size() == 1 && kG.size() == 1;
        if (kGood) kGood = within(Long.parseLong(kA.get(0)[3]), 0, 100) && within(Long.parseLong(kG.get(0)[3]), 0, 100);
        Check.ok("S1.15 K rows present (one A, one G within 12 s) with sane lag_ms", kGood,
                "K,A rows=" + kA.size() + " K,G rows=" + kG.size() + (kA.isEmpty() ? "" : " " + String.join(",", kA.get(0)))
                        + (kG.isEmpty() ? "" : " " + String.join(",", kG.get(0))));

        int nA = a.kind("A").size(), nG = a.kind("G").size(), nL = a.kind("L").size(), nS = a.kind("S").size();
        Check.ok("S1.16 row counts about 1200 A / 1200 G / 12 L",
                within(nA, 1190, 1210) && within(nG, 1190, 1210) && within(nL, 11, 13),
                "A=" + nA + " G=" + nG + " L=" + nL + " S=" + nS + " E=" + ev.size() + " K=" + a.kind("K").size());

        List<String[]> L = a.kind("L");
        boolean lGood = L.size() == 12;
        double lastSpeed = -1, prevSpeed = -1;
        for (int i = 0; lGood && i < L.size(); i++) {
            String[] r = L.get(i);
            double spd = Double.parseDouble(r[5]), brg = Double.parseDouble(r[6]), lag = Long.parseLong(r[11]);
            boolean still = i < 6;
            lGood &= still ? (spd == 0.0 && brg == -1.0) : (spd >= prevSpeed && brg == 10.0);
            lGood &= within(lag, 100, 250) && Math.abs(Long.parseLong(r[10]) - System.currentTimeMillis()) < 60_000;
            lGood &= Double.parseDouble(r[7]) == 3.5 && Double.parseDouble(r[8]) == 0.25;
            prevSpeed = spd; lastSpeed = spd;
        }
        lGood &= lastSpeed == 15.0;
        Check.ok("S1.17 L rows: speed 0 (bearing -1 = not provided) for 6 s, then rising to 15; lag_ms ~120", lGood,
                L.isEmpty() ? "none" : "first=" + String.join(",", L.get(0)) + " last=" + String.join(",", L.get(L.size() - 1)));

        List<String[]> S = a.kind("S");
        String[][] wantS = {{"14", "6"}, {"16", "9"}, {"17", "10"}, {"18", "11"}};
        boolean sGood = S.size() == 4;
        for (int i = 0; sGood && i < 4; i++) {
            sGood &= S.get(i)[2].equals(wantS[i][0]) && S.get(i)[3].equals(wantS[i][1]);
            if (i > 0) sGood &= Long.parseLong(S.get(i)[1]) - Long.parseLong(S.get(i - 1)[1]) >= 1_000_000L;
        }
        Check.ok("S1.18 S rows: 6 GNSS callbacks -> 4 rows (rate-limited to >= 1 s apart) with the right counts", sGood, "S rows=" + S.size());

        // ---------------------------------------------------------------- LIVE
        Feeder.Snap s45 = snapAt(fd, 4.5), s55 = snapAt(fd, 5.5), s65 = snapAt(fd, 6.5), s70 = snapAt(fd, 7.0), s115 = snapAt(fd, 11.5);
        double peak = 0;
        for (Feeder.Snap s : fd.snaps) if (s.t >= 7.5 && s.t <= 9.0) peak = Math.max(peak, s.horizG);
        Check.ok("S1.20 LIVE: calDone false at 4.5 s, calDone && calOk true at 5.5 s, calText sensible",
                !s45.calDone && s55.calDone && s55.calOk && s55.calText.matches("gravity 9\\.7[56] m/s2, noise 0\\.0[2-4], gyro noise 0\\.[1-3] deg/s"),
                "calText='" + s55.calText + "'");
        Check.ok("S1.21 LIVE.horizG rises during the push (6-9 s) and falls back after it; vertG ~ 1 g",
                s55.horizG < 0.05 && s65.horizG - s55.horizG > 0.2 && within(peak, 0.45, 0.58) && s115.horizG < 0.05 && within(s70.vertG, 0.95, 1.05),
                String.format("horizG@5.5=%.3f @6.5=%.3f peak(7.5-9)=%.3f @11.5=%.3f; vertG@7=%.3f (push = 5/9.80665 = 0.51 g)", s55.horizG, s65.horizG, peak, s115.horizG, s70.vertG));
        double aMin = 1e9, aMax = -1e9, gMin = 1e9, gMax = -1e9, lMin = 1e9, lMax = -1e9;
        for (Feeder.Snap s : fd.snaps) {
            if (s.t < 2.0 || s.t > 11.5) continue;
            aMin = Math.min(aMin, s.aHz); aMax = Math.max(aMax, s.aHz);
            gMin = Math.min(gMin, s.gHz); gMax = Math.max(gMax, s.gHz);
            lMin = Math.min(lMin, s.lHz); lMax = Math.max(lMax, s.lHz);
        }
        Check.ok("S1.22 LIVE rates for t = 2-11.5 s: aHz and gHz about 100, lHz about 1",
                aMin >= 90 && aMax <= 110 && gMin >= 90 && gMax <= 110 && lMin >= 0.8 && lMax <= 1.2,
                String.format("aHz %.1f..%.1f  gHz %.1f..%.1f  lHz %.2f..%.2f", aMin, aMax, gMin, gMax, lMin, lMax));
        boolean hdrStartOk = !startElapsedHdr.isEmpty() && Long.parseLong(startElapsedHdr) == Recorder.LIVE.startElapsedNs;
        Check.ok("S1.23 LIVE before stop: recording, !failed, hasAccel/hasGyro, fix held, speed 15, sats 18/12, rows 1200/1200/12, error empty, fileName/startElapsedNs match",
                pre.recording && !pre.failed && Recorder.LIVE.hasAccel && Recorder.LIVE.hasGyro && pre.haveFix && pre.speedMs == 15.0f
                        && pre.satsV == 18 && pre.satsU == 12 && pre.rowsA == nA && pre.rowsG == nG && pre.rowsL == nL
                        && pre.error.isEmpty() && Recorder.LIVE.fileName.equals(f.getName()) && hdrStartOk,
                "rows=" + pre.rowsA + "/" + pre.rowsG + "/" + pre.rowsL + " speed=" + pre.speedMs + " sats=" + pre.satsV + "/" + pre.satsU + " error='" + pre.error + "'");
        Thread st = sensorThread;
        Check.ok("S1.24 after stop(): LIVE.recording=false, LIVE.bytes == file size, all listeners unregistered, sensor thread ended",
                !post.recording && Recorder.LIVE.bytes == f.length() && sm.regs.isEmpty() && lm.listener == null && lm.gnss == null && st != null && !st.isAlive(),
                "bytes=" + Recorder.LIVE.bytes + " file=" + f.length() + " regs=" + sm.regs.size() + " threadAlive=" + (st != null && st.isAlive()));
        boolean quiet = Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopErrBefore && Harness.UNCAUGHT_THREADS.isEmpty() && fd.error == null;
        Check.ok("S1.25 no exception printed or thrown (stderr, sensor-thread loop, other threads, feeder)", quiet,
                "stderr bytes=" + (Harness.errBytes() - errBefore) + " loopThrowables=" + (Looper.UNCAUGHT.size() - loopErrBefore));

        // ---------------------------------------------------------------- show
        System.out.println("--- CSV: " + f + "  (" + f.length() + " bytes, copy at " + new File(root, "session_s1.csv") + ")");
        System.out.println("--- first 12 lines:");
        System.out.print(a.firstLines(12));
        for (String k : new String[]{"A", "G", "L", "S", "E", "K"}) {
            System.out.println("--- 3 sample rows of kind " + k + " (first / middle / last):");
            for (String s : a.samples(k)) System.out.println(s);
        }
        System.out.println("--- LIVE time series (feeder snapshots every 0.5 s):");
        for (Feeder.Snap s : fd.snaps) System.out.println(s.line());
    }
}
