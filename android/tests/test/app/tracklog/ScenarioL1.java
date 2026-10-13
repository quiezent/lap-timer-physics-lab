package app.tracklog;

import android.location.Location;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scenario L1: the lap timer inside the recorder. GPS fixes that cross the built-in Sepang line are fed through the
 * real Recorder.onLocationChanged (via the fake LocationManager) and the result is audited in two places:
 * the CSV (E,t_us,LAP,n,seconds rows) and Recorder.LIVE (what the screen reads).
 *   L1.1x  synthetic car, three passes with known lap times, 1 Hz fixes, random sub-second phase
 *   L1.2x  a second session on the same static LIVE: the lap state is reset and a drive that never touches the line adds no LAP row
 *   L1.3x  (optional, -Dreal.log=PATH) the 1 Hz GPS fixes of a real recorded session replayed through the recorder,
 *          compared with lap times computed independently (Python, 10 ms sampling of the same Hermite curve)
 */
final class ScenarioL1 {
    static final double V = 60.0;                       // m/s on the straight

    /** position of a car moving along `bearing` at v m/s that passes the line point at time tp (lateral offset in m, + = right) */
    static double[] pos(double bearing, double v, double tp, double offsetM, double t) {
        double b = Math.toRadians(bearing);
        double de = Math.sin(b) * v * (t - tp) + Math.cos(b) * offsetM;
        double dn = Math.cos(b) * v * (t - tp) - Math.sin(b) * offsetM;
        double R = 6378137.0;
        return new double[]{LapTimer.SEPANG_LAT + Math.toDegrees(dn / R),
                LapTimer.SEPANG_LON + Math.toDegrees(de / (R * Math.cos(Math.toRadians(LapTimer.SEPANG_LAT))))};
    }

    static Location fix(long stampNs, double lat, double lon, double speed, double bearing) {
        Location l = new Location("gps");
        l.setLatitude(lat);
        l.setLongitude(lon);
        l.setAltitude(40.0);
        l.setSpeed((float) speed);
        l.setBearing((float) bearing);
        l.setAccuracy(3.8f);
        l.setSpeedAccuracyMetersPerSecond(0.3f);
        l.setBearingAccuracyDegrees(5f);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(stampNs);
        return l;
    }

    static List<String[]> laps(CsvAudit a) {
        List<String[]> out = new ArrayList<>();
        for (String[] r : a.kind("E")) if (r[2].equals("LAP")) out.add(r);
        return out;
    }

    static void run(File root) throws Exception {
        System.out.println("\n===== L1: lap timer inside the recorder =====");
        final File tmp = Files.createTempDirectory(root.toPath(), "l1_").toFile();
        final int errBefore = Harness.errBytes();
        final int loopErrBefore = Looper.UNCAUGHT.size();

        // ------------------------------------------------------------ synthetic session
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Recorder rec = new Recorder(new FakeContext(new File(tmp, "a"), sm, lm));
        final boolean started = rec.start();
        final File f = rec.getFile();
        final long t0 = Recorder.LIVE.startElapsedNs;
        Check.ok("L1.01 start() ok; lap state reset (lapNo 0, no pass yet, no best, empty list)",
                started && Recorder.LIVE.lapNo == 0 && Recorder.LIVE.lapStartNs == 0L && Recorder.LIVE.lastLapS < 0
                        && Recorder.LIVE.bestLapS < 0 && Recorder.LIVE.bestLapNo == 0 && Recorder.LIVE.lapText.isEmpty());

        final double[] P = new double[3];
        P[0] = 20.37;
        P[1] = P[0] + 153.047;
        P[2] = P[1] + 154.500;
        final double phase = 0.35;
        int sent = 0;
        double lastPassSeen = -1;
        for (int k = 1; k < (int) P[2] + 12; k++) {
            final double ts = k + phase;                              // session seconds of this fix
            double near = -1;
            for (double p : P) if (Math.abs(ts - p) <= 6.0) near = p;
            double lat, lon, spd, brg;
            if (near > 0) {                                           // on the main straight, crossing the line
                double[] q = pos(LapTimer.SEPANG_BEARING_DEG, V, near, 3.0, ts);
                lat = q[0]; lon = q[1]; spd = V; brg = LapTimer.SEPANG_BEARING_DEG;
            } else {                                                  // somewhere else on the circuit
                lat = 2.7550 + 0.0001 * k; lon = 101.7300; spd = 40.0; brg = 90.0;
            }
            lm.deliverLocation(fix(t0 + (long) (ts * 1e9), lat, lon, spd, brg));
            sent++;
        }
        Check.ok("L1.02 sensor looper drained after " + sent + " fixes", Util.drain(sm));

        final Recorder.Live L = Recorder.LIVE;
        final double lap1 = P[1] - P[0], lap2 = P[2] - P[1];
        final double startS = L.lapStartNs / 1e9 - t0 / 1e9;
        Check.ok("L1.03 LIVE after 3 passes: lapNo 2, last " + String.format(Locale.US, "%.3f", lap2) + ", best " + String.format(Locale.US, "%.3f", lap1) + " (lap 1)",
                L.lapNo == 2 && Math.abs(L.lastLapS - lap2) < 0.002 && Math.abs(L.bestLapS - lap1) < 0.002 && L.bestLapNo == 1,
                "lapNo=" + L.lapNo + " last=" + L.lastLapS + " best=" + L.bestLapS + " bestNo=" + L.bestLapNo);
        Check.ok("L1.04 LIVE.lapStartNs is the third pass (" + String.format(Locale.US, "%.3f", P[2]) + " s into the session)",
                Math.abs(startS - P[2]) < 0.002, "lapStart=" + startS + " s");
        final String[] lines = L.lapText.split("\n");
        Check.ok("L1.05 LIVE.lapText: newest first, two lines, best marked",
                lines.length == 2 && lines[0].startsWith("L2") && lines[0].contains(LapTimer.fmt(lap2)) && lines[0].contains("+1.45")
                        && lines[1].startsWith("L1") && lines[1].contains(LapTimer.fmt(lap1)) && lines[1].endsWith("best"),
                L.lapText.replace("\n", " | "));

        final long stopMs = Util.stopWithWatchdog(rec, 3000, "L1");
        Check.ok("L1.06 stop() returned within 3 s", stopMs < 3000, stopMs + " ms");
        final CsvAudit a = new CsvAudit(f);
        Files.copy(f.toPath(), new File(root, "session_l1.csv").toPath(), StandardCopyOption.REPLACE_EXISTING);

        final List<String[]> lr = laps(a);
        boolean rowsOk = lr.size() == 3;
        String detail = "n=" + lr.size();
        if (rowsOk) {
            final double[] want = {0.0, lap1, lap2};
            for (int i = 0; i < 3; i++) {
                final String[] r = lr.get(i);
                final double tRow = Long.parseLong(r[1]) / 1e6, sec = Double.parseDouble(r[4]);
                detail += "  " + String.join(",", r);
                rowsOk &= Integer.parseInt(r[3]) == i && Math.abs(tRow - P[i]) < 0.003
                        && (i == 0 ? sec == 0.0 : Math.abs(sec - want[i]) < 0.0015);
            }
        }
        Check.ok("L1.07 CSV has exactly three LAP rows: n=0 (clock started) and laps 1, 2 with the right times, stamped at the pass", rowsOk, detail);

        final List<String> bad = a.badRows();
        Check.ok("L1.08 every row of the file still has its documented field count (LAP rows have 5 fields)",
                bad.isEmpty() && a.endsWithNewline && !a.blankLine, a.data.size() + " rows, " + bad.size() + " bad" + (bad.isEmpty() ? "" : "; first: " + bad.get(0)));
        // (the STOP row is stamped with the wall clock while this scenario's fixes carry stamps minutes in the future, so only the LAP rows are compared with each other)
        final String mL = CsvAudit.monotonic(a.kind("L"), true), mLap = CsvAudit.monotonic(lr, true);
        Check.ok("L1.09 L rows: one per fix, strictly increasing t_us; LAP rows in time order; START first, STOP last",
                a.kind("L").size() == sent && mL == null && mLap == null && a.data.get(0).equals("E,0,START") && a.data.get(a.data.size() - 1).matches("E,\\d+,STOP"),
                "L=" + a.kind("L").size() + "/" + sent + " mL=" + mL + " mLap=" + mLap);

        // ------------------------------------------------------------ the next session starts from zero, and a drive that never touches the line adds nothing
        final FakeSensorManager sm2 = new FakeSensorManager();
        final FakeLocationManager lm2 = new FakeLocationManager();
        final Recorder rec2 = new Recorder(new FakeContext(new File(tmp, "b"), sm2, lm2));
        final boolean started2 = rec2.start();
        final File f2 = rec2.getFile();
        final long t02 = Recorder.LIVE.startElapsedNs;
        Check.ok("L1.20 second start(): LIVE lap fields back to zero (the first session's laps are gone)",
                started2 && L.lapNo == 0 && L.lapStartNs == 0L && L.lastLapS < 0 && L.bestLapS < 0 && L.bestLapNo == 0 && L.lapText.isEmpty(),
                "lapNo=" + L.lapNo + " text='" + L.lapText + "'");
        for (int k = 1; k <= 60; k++) {                                // 60 s on the straight, 700 m east of the line, never crossing it
            double[] q = pos(LapTimer.SEPANG_BEARING_DEG, V, -20.0, 0.0, k + phase);
            lm2.deliverLocation(fix(t02 + (long) ((k + phase) * 1e9), q[0], q[1], V, LapTimer.SEPANG_BEARING_DEG));
        }
        // and a parked car jittering across the line
        for (int k = 61; k <= 100; k++) {
            double j = ((k % 2) * 2 - 1) * 4.0;
            double[] q = pos(LapTimer.SEPANG_BEARING_DEG, 0.0, 0.0, 0.0, 0.0);
            lm2.deliverLocation(fix(t02 + (long) ((k + phase) * 1e9), q[0] + j / 111320.0, q[1] + j / 111320.0, 0.2, 10.0 * k % 360));
        }
        Check.ok("L1.21 sensor looper drained", Util.drain(sm2));
        Util.stopWithWatchdog(rec2, 3000, "L1b");
        final CsvAudit a2 = new CsvAudit(f2);
        Check.ok("L1.22 no pass of the line: no LAP row, LIVE.lapNo 0, lapStartNs 0; file still valid",
                laps(a2).isEmpty() && L.lapNo == 0 && L.lapStartNs == 0L && a2.badRows().isEmpty() && a2.kind("L").size() == 100,
                "LAP rows=" + laps(a2).size() + " L rows=" + a2.kind("L").size() + " bad=" + a2.badRows().size());

        // ------------------------------------------------------------ optional: a real recorded session
        final String realPath = System.getProperty("real.log");
        if (realPath != null && new File(realPath).isFile()) {
            replayReal(root, tmp, realPath);
        } else {
            System.out.println("(L1.3x skipped: pass -Dreal.log=<session csv> to replay a real recording)");
        }

        Check.ok("L1.90 no exception printed or thrown (stderr, sensor-thread loop, other threads)",
                Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopErrBefore && Harness.UNCAUGHT_THREADS.isEmpty(),
                "stderr bytes=" + (Harness.errBytes() - errBefore) + " loopThrowables=" + (Looper.UNCAUGHT.size() - loopErrBefore));
    }

    // lap times of the real log at the app's line, from an independent implementation (quick_laps.py: the same Hermite curve sampled every 10 ms)
    static final double[] REAL_REF = {162.930, 155.325, 158.593, 155.153, 156.163, 153.562, 157.831, 153.432, 153.651, 155.154,
            156.800, 153.076, 156.117, 166.799, 153.873};
    static final double REAL_FIRST_PASS = 125.35;

    static void replayReal(File root, File tmp, String path) throws Exception {
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Recorder rec = new Recorder(new FakeContext(new File(tmp, "real"), sm, lm));
        final boolean started = rec.start();
        final File f = rec.getFile();
        final long t0 = Recorder.LIVE.startElapsedNs;
        int n = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("L,")) continue;
                final String[] p = line.split(",", -1);
                if (p.length != 12) continue;
                final Location l = new Location("gps");
                l.setLatitude(Double.parseDouble(p[2]));
                l.setLongitude(Double.parseDouble(p[3]));
                l.setAltitude(Double.parseDouble(p[4]));
                l.setSpeed(Float.parseFloat(p[5]));
                l.setBearing(Float.parseFloat(p[6]));
                l.setAccuracy(Float.parseFloat(p[7]));
                l.setSpeedAccuracyMetersPerSecond(Float.parseFloat(p[8]));
                l.setBearingAccuracyDegrees(Float.parseFloat(p[9]));
                l.setTime(Long.parseLong(p[10]));
                l.setElapsedRealtimeNanos(t0 + Long.parseLong(p[1]) * 1000L);
                lm.deliverLocation(l);
                n++;
            }
        }
        Check.ok("L1.30 real log: " + n + " GPS fixes replayed, sensor looper drained", started && n > 1000 && Util.drain(sm));
        final Recorder.Live L = Recorder.LIVE;
        final String liveText = L.lapText;
        final int liveLaps = L.lapNo;
        final double liveLast = L.lastLapS, liveBest = L.bestLapS;
        final int liveBestNo = L.bestLapNo;
        Util.stopWithWatchdog(rec, 3000, "L1c");
        final CsvAudit a = new CsvAudit(f);
        Files.copy(f.toPath(), new File(root, "session_l1_real.csv").toPath(), StandardCopyOption.REPLACE_EXISTING);
        final List<String[]> lr = laps(a);
        boolean ok = lr.size() == REAL_REF.length + 1;
        double worst = 0;
        String first = "";
        if (ok) {
            first = String.join(",", lr.get(0));
            ok &= Integer.parseInt(lr.get(0)[3]) == 0 && Math.abs(Long.parseLong(lr.get(0)[1]) / 1e6 - REAL_FIRST_PASS) < 0.02;
            for (int i = 1; i < lr.size(); i++) {
                ok &= Integer.parseInt(lr.get(i)[3]) == i;
                worst = Math.max(worst, Math.abs(Double.parseDouble(lr.get(i)[4]) - REAL_REF[i - 1]));
            }
            ok &= worst < 0.015;
        }
        Check.ok("L1.31 real log: 15 laps from the recorder's LAP rows agree with the independent calculation (worst difference "
                + String.format(Locale.US, "%.1f", worst * 1000) + " ms)", ok, "LAP rows=" + lr.size() + "  first=" + first);
        double best = 1e9;
        int bestNo = 0;
        for (int i = 0; i < REAL_REF.length; i++) if (REAL_REF[i] < best) { best = REAL_REF[i]; bestNo = i + 1; }
        Check.ok("L1.32 real log: LIVE has 15 laps, last " + LapTimer.fmt(REAL_REF[14]) + ", best " + LapTimer.fmt(best) + " (lap " + bestNo + ")",
                liveLaps == 15 && Math.abs(liveLast - REAL_REF[14]) < 0.015 && Math.abs(liveBest - best) < 0.015 && liveBestNo == bestNo,
                "laps=" + liveLaps + " last=" + liveLast + " best=" + liveBest + " bestNo=" + liveBestNo);
        Check.ok("L1.33 real log: the rest of the file is intact (documented field counts, L rows all there, strictly increasing)",
                a.badRows().isEmpty() && a.kind("L").size() == n && CsvAudit.monotonic(a.kind("L"), true) == null,
                "bad=" + a.badRows().size() + " L=" + a.kind("L").size() + "/" + n);
        System.out.println(liveText);
    }
}
