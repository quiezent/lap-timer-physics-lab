package app.tracklog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stage code against a REAL recording: the 1 Hz GPS fixes of the Sepang session of 13 Oct 2026, 15 laps, a Samsung phone.
 * Real GPS has what no simulation has: its own noise, its own dropouts, the pit lane, an in-lap. It is a circuit, not a road, but the
 * stage code does not know the difference: a stage is two lines and a route between them.
 *
 *  - RL1  the Gate, fed the real fixes at the lap line, gives the lap times of LapTimer (the code that ran in the app on the day) to
 *         the last digit, and the lap times of the independent Python calculation (quick_laps.py) to 15 ms.
 *  - RL2  a pseudo-stage cut out of lap 3 (from 5 s after the line to 80 s after it) is built with StageBuilder and every lap of the
 *         session is scored against it: one finished run per pass, plausible times, profiles that add up.
 *
 * Run with -Dreal.log=PATH (the CSV in data/sepang-2026-10-13). Without it the checks are skipped and say so.
 */
final class StageRealTests {

    // lap times of the real log at the app's line, from the independent implementation (quick_laps.py: the same Hermite curve sampled every 10 ms)
    static final double[] REAL_REF = {162.930, 155.325, 158.593, 155.153, 156.163, 153.562, 157.831, 153.432, 153.651, 155.154,
            156.800, 153.076, 156.117, 166.799, 153.873};
    static final double REAL_FIRST_PASS = 125.35;

    private StageRealTests() { }

    static void run() throws Exception {
        final String path = System.getProperty("real.log");
        if (path == null || !new File(path).isFile()) {
            System.out.println("(ST.RL skipped: pass -Dreal.log=<session csv> to run the stage code on the real Sepang recording)");
            return;
        }
        System.out.println("\n===== ST.RL: stage code on the real Sepang recording =====");
        final LogReader.Log log = LogReader.read(new File(path));

        // ---------------------------------------------------------------- RL1
        final Geo geo = new Geo(LapTimer.SEPANG_LAT, LapTimer.SEPANG_LON);
        final Gate line = new Gate(0.0, 0.0, LapTimer.SEPANG_BEARING_DEG, LapTimer.HALF_WIDTH_M);
        final LapTimer lt = new LapTimer();
        final List<Double> passes = new ArrayList<Double>();
        Pt prev = null;
        double lastCross = Double.NaN;
        for (Fix f : log.fixes) {
            lt.onFix(f.t, f.lat, f.lon, f.speed, f.bearing);
            if (prev != null && !(f.t > prev.t)) continue;
            final Pt cur = Pt.make(geo, f, prev);
            if (prev != null && cur.t - prev.t <= LapTimer.MAX_FIX_GAP_S) {
                final double tc = line.crossing(prev, cur, LapTimer.MIN_SPEED_MS, 0.0);
                if (!Double.isNaN(tc) && (Double.isNaN(lastCross) || tc - lastCross >= LapTimer.MIN_LAP_S)) {
                    passes.add(tc);
                    lastCross = tc;
                }
            }
            prev = cur;
        }
        double worstLt = 0, worstRef = 0;
        boolean sameCount = passes.size() == lt.laps() + 1 && lt.laps() == REAL_REF.length;
        for (int i = 1; sameCount && i < passes.size(); i++) {
            final double lap = passes.get(i) - passes.get(i - 1);
            worstLt = Math.max(worstLt, Math.abs(lap - lt.lap(i)));
            worstRef = Math.max(worstRef, Math.abs(lap - REAL_REF[i - 1]));
        }
        Check.ok(String.format(Locale.US, "ST.RL1 the Gate on the real fixes at the lap line: %d passes, %d laps; lap times differ from LapTimer by at most %.1e s and from the independent Python calculation by at most %.1f ms; first pass at %.2f s",
                passes.size(), passes.size() - 1, worstLt, worstRef * 1000.0, passes.isEmpty() ? Double.NaN : passes.get(0)),
                sameCount && worstLt < 1e-9 && worstRef < 0.015 && Math.abs(passes.get(0) - REAL_FIRST_PASS) < 0.02, "passes=" + passes.size() + " laps=" + lt.laps());
        if (!sameCount) return;

        // ---------------------------------------------------------------- RL2
        // the pseudo-stage: from lap 3, the fixes between 5 s and 80 s after the line
        final double tA = passes.get(2) + 5.0, tB = passes.get(2) + 80.0;
        int iA = -1, iB = -1;
        for (int i = 0; i < log.fixes.size(); i++) {
            final double t = log.fixes.get(i).t;
            if (iA < 0 && t >= tA) iA = i;
            if (iB < 0 && t >= tB) iB = i;
        }
        final Fix mA = log.fixes.get(iA), mB = log.fixes.get(iB);
        final List<Fix> drive = new ArrayList<Fix>(log.fixes.subList(iA + 1, iB));
        final Stage st = StageBuilder.build("st_real", "Sepang pseudo-stage", 0L, mA.lat, mA.lon, mB.lat, mB.lon, drive);
        final List<RunResult> runs = Scorer.scoreAll(st, log.fixes, log.name, (double) log.startUtcMs);
        int finished = 0;
        double lo = Double.MAX_VALUE, hi = 0, sum = 0;
        RunResult best = null;
        for (RunResult r : runs) {
            if (!r.finished) continue;
            finished++;
            sum += r.totalS;
            lo = Math.min(lo, r.totalS);
            hi = Math.max(hi, r.totalS);
            if (best == null || r.totalS < best.totalS) best = r;
        }
        final double mean = finished > 0 ? sum / finished : Double.NaN;
        // every pass of the lap line is followed by a pass of the stage; the stage starts 5 s after the line, so one run per pass,
        // except that the very last pass may be cut by the end of the recording
        Check.ok(String.format(Locale.US, "ST.RL2a pseudo-stage cut from lap 3 (%.0f m of route): %d runs found in the 16 passes of the line, %d finished, times %.1f to %.1f s (mean %.1f s)",
                st.lengthM(), runs.size(), finished, lo, hi, mean),
                finished >= passes.size() - 2 && finished <= passes.size() && lo > mean - 20.0 && hi < mean + 20.0 && Math.abs(mean - 75.0) < 8.0, "");

        // the profiles add up and the comparison against the best run makes sense for every run
        boolean profilesOk = best != null, compareOk = best != null;
        double worstSum = 0, worstMono = 0;
        if (best != null) {
            for (RunResult r : runs) {
                if (!r.finished) continue;
                for (int k = 1; k < r.t.length; k++) if (r.t[k] < r.t[k - 1]) profilesOk = false;
                profilesOk &= r.t[0] == 0.0 && Math.abs(r.t[r.t.length - 1] - r.totalS) < 1e-9;
                final Compare c = Compare.of(r, best);
                double ssum = 0;
                for (Compare.Section s : c.sections) ssum += s.deltaS();
                worstSum = Math.max(worstSum, Math.abs(ssum - c.totalDeltaS));
                worstMono = Math.max(worstMono, Math.abs(c.delta[c.delta.length - 1] - c.totalDeltaS));
            }
            compareOk = worstSum < 1e-6 && worstMono < 1e-6;
        }
        Check.ok(String.format(Locale.US, "ST.RL2b real runs: every profile starts at 0, never decreases and ends at the stage time; against the best run the sections add up to the total (worst %.1e s)", worstSum),
                profilesOk && compareOk, "");

        // each run starts at a crossing of the start line that can be found again by hand, and its time is the next finish crossing
        final List<Double> starts = new ArrayList<Double>(), finishes = new ArrayList<Double>();
        Pt p2 = null;
        for (Fix f : log.fixes) {
            if (p2 != null && !(f.t > p2.t)) continue;
            final Pt cur = Pt.make(st.geo, f, p2);
            if (p2 != null && cur.t - p2.t <= StageRun.MAX_FIX_GAP_S) {
                final double a = st.start.crossing(p2, cur, StageRun.GATE_MIN_SPEED, StageRun.GATE_MIN_FORWARD);
                if (!Double.isNaN(a)) starts.add(a);
                final double b = st.finish.crossing(p2, cur, StageRun.GATE_MIN_SPEED, StageRun.GATE_MIN_FORWARD);
                if (!Double.isNaN(b)) finishes.add(b);
            }
            p2 = cur;
        }
        int matched = 0;
        double worstT = 0;
        for (RunResult r : runs) {
            if (!r.finished) continue;
            // the run's start must be one of the start crossings, and its finish the first finish crossing after it
            double s0 = Double.NaN;
            for (double s : starts) {
                double f0 = Double.NaN;
                for (double fnd : finishes) if (fnd > s) { f0 = fnd; break; }
                if (!Double.isNaN(f0) && Math.abs((f0 - s) - r.totalS) < 1e-6) { s0 = s; worstT = Math.max(worstT, Math.abs((f0 - s) - r.totalS)); break; }
            }
            if (!Double.isNaN(s0)) matched++;
        }
        Check.ok(String.format(Locale.US, "ST.RL2c each of the %d finished runs equals (finish crossing - start crossing) found by hand on the same fixes: %d matched, worst difference %.1e s; %d start crossings and %d finish crossings in the file",
                finished, matched, worstT, starts.size(), finishes.size()), matched == finished && finished > 0, "");

        // what the app would say about the slowest run against the best, for the record
        RunResult worst = null;
        for (RunResult r : runs) if (r.finished && (worst == null || r.totalS > worst.totalS)) worst = r;
        if (worst != null && best != null && worst != best) {
            final Compare c = Compare.of(worst, best);
            System.out.println(StageText.compareText(st, c, "best"));
        }
    }
}
