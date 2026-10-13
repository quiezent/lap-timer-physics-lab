package app.tracklog;

import java.util.ArrayList;
import java.util.List;

/**
 * Two runs of the same stage, compared by where they were at each distance. Plain Java with no Android classes.
 *
 * Both runs are timed from the same start line over the same route, so the difference at any route distance is the time one run was
 * ahead of or behind the other there. The total is the difference at the end; the sections say where it came from. Run A is the one
 * being looked at, B the baseline (the best run, or the previous one): a positive number means A was slower.
 *
 * What it cannot tell: phone GPS puts each crossing of a line some metres out, and a metre is a tenth of a second at 36 km/h. How
 * much that matters depends on how fast the car was going at the lines (sigmaS), so a difference smaller than twice that, and never
 * less than 0.4 s, is reported as "about the same" (sameBandS). Traffic, a stop, a wet road all show up here as time, and the numbers
 * cannot say which.
 */
final class Compare {

    /** Differences smaller than this are always reported as "about the same". See docs/stages.md for where the figure comes from. */
    static final double SAME_S = 0.4;

    /**
     * One standard deviation of where phone GPS puts a line crossing along the road, in metres. From the simulations in
     * docs/validation.md: 3 m of GPS noise (the phone's usual reported accuracy) gives 2.9 to 3.1 m here.
     */
    static final double GPS_ALONG_M = 3.0;
    static final double MIN_LINE_SPEED = 3.0;          // m/s, floor when working out the above
    static final double UNKNOWN_LINE_SPEED = 12.0;     // m/s, assumed for a run whose line speeds were not kept

    static final class Section {
        final double fromM, toM;     // route distance
        final double aS, bS;         // time each run took over the section
        Section(double fromM, double toM, double aS, double bS) {
            this.fromM = fromM;
            this.toM = toM;
            this.aS = aS;
            this.bS = bS;
        }
        double deltaS() {
            return aS - bS;
        }
    }

    final RunResult a, b;
    final double totalDeltaS;          // a - b at the end
    final double[] delta;              // a.t[k] - b.t[k] at every 25 m of the route
    final double sectionM;
    final List<Section> sections;

    private Compare(RunResult a, RunResult b, double[] delta, double sectionM, List<Section> sections) {
        this.a = a;
        this.b = b;
        this.delta = delta;
        this.totalDeltaS = a.totalS - b.totalS;
        this.sectionM = sectionM;
        this.sections = sections;
    }

    private static double lineSpeed(double v) {
        return v < 0.0 ? UNKNOWN_LINE_SPEED : Math.max(v, MIN_LINE_SPEED);
    }

    /**
     * One standard deviation of the difference between the two total times, in seconds: the four crossings (start and finish, of
     * both runs) each put some metres out, and a metre is 1/speed seconds. Fast crossings are timed well, slow ones (a standing start)
     * badly. It does not include anything the driver did differently.
     */
    double sigmaS() {
        final double sa = lineSpeed(a.startSpeedMs), fa = lineSpeed(a.finishSpeedMs), sb = lineSpeed(b.startSpeedMs), fb = lineSpeed(b.finishSpeedMs);
        return GPS_ALONG_M * Math.sqrt(1.0 / (sa * sa) + 1.0 / (fa * fa) + 1.0 / (sb * sb) + 1.0 / (fb * fb));
    }

    /** A difference smaller than this is "about the same": twice sigmaS, and at least SAME_S. */
    double sameBandS() {
        return Math.max(SAME_S, 2.0 * sigmaS());
    }

    /** @throws IllegalArgumentException if the two runs are not of the same stage, or one did not finish */
    static Compare of(RunResult a, RunResult b) {
        if (!a.comparableWith(b)) throw new IllegalArgumentException("these two runs cannot be compared");
        final int n = a.t.length;
        final double[] d = new double[n];
        for (int k = 0; k < n; k++) d[k] = a.t[k] - b.t[k];
        final double secM = sectionLength(a.lengthM);
        final int per = (int) Math.round(secM / a.stepM);
        final List<Section> secs = new ArrayList<Section>();
        for (int k0 = 0; k0 < n - 1; k0 += per) {
            final int k1 = Math.min(n - 1, k0 + per);
            if (k1 - k0 < 1) break;
            secs.add(new Section(k0 * a.stepM, Math.min(k1 * a.stepM, a.lengthM), a.t[k1] - a.t[k0], b.t[k1] - b.t[k0]));
        }
        return new Compare(a, b, d, secM, secs);
    }

    /** Section length for a stage: the smallest of 250 m, 500 m, 1, 2, 5, 10 km that gives at most 12 sections. */
    static double sectionLength(double lengthM) {
        final double[] opts = {250.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0};
        for (double o : opts) if (lengthM / o <= 12.0) return o;
        return 10000.0;
    }

    /** The fastest finished run (the earliest if two are equal), or null. */
    static RunResult best(List<RunResult> runs) {
        RunResult best = null;
        for (RunResult r : runs) if (r.finished && (best == null || r.totalS < best.totalS)) best = r;
        return best;
    }

    /** The finished run before r in time (runs are in time order), or null. */
    static RunResult previous(List<RunResult> runs, RunResult r) {
        RunResult prev = null;
        for (RunResult x : runs) {
            if (x == r || (x.runId.equals(r.runId) && x.stageId.equals(r.stageId))) return prev;
            if (x.finished) prev = x;
        }
        return null;
    }

    /** The section with the largest time lost (largest positive delta), or null if none lost anything. */
    Section mostLost() {
        Section w = null;
        for (Section s : sections) if (s.deltaS() > 0 && (w == null || s.deltaS() > w.deltaS())) w = s;
        return w;
    }

    /** The section with the largest time gained, or null. */
    Section mostGained() {
        Section w = null;
        for (Section s : sections) if (s.deltaS() < 0 && (w == null || s.deltaS() < w.deltaS())) w = s;
        return w;
    }
}
