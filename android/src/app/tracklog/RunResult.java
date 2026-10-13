package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What one run of a stage came to: the time between the two lines and the time at which the car had reached each 25 m of the
 * route (the "profile", seconds after the start line). Two runs of the same stage are compared by their profiles.
 *
 * The recorded CSV stays the source of truth. A RunResult is a small summary that can be rebuilt from it at any time (Scorer),
 * so it is safe to lose. Plain Java with no Android classes; the text form is in StageFiles.
 */
final class RunResult {

    static final double STEP_M = 25.0;

    final String stageId;
    final String runId;
    final String csvName;             // the session file this run was recorded in ("" if unknown)
    final long startUtcMs;            // wall clock at the start line, 0 if unknown
    final boolean finished;           // false = did not finish: no time, no profile
    final String statusText;          // "ok", or why it did not finish (plain words, no commas)
    final double totalS;              // finish minus start, NaN if not finished
    final double lengthM;             // length of the stage's route
    final double stepM;
    final double[] t;                 // seconds from the start line at route distance k*stepM (the last entry at lengthM); empty if not finished
    final int fixes;                  // GPS fixes between the lines
    final double maxGapS;             // longest time between two fixes between the lines
    final double maxGapAtM;           // route distance where that gap began
    final double maxAccM;             // worst reported accuracy between the lines (-1 if none reported)
    final double offRouteMaxM;        // furthest the car was from the route (m)
    final double startGapS, finishGapS;   // time between the two fixes either side of each line (the crossing is interpolated across it)
    final double startSpeedMs, finishSpeedMs;   // speed at each line (m/s), negative if unknown: a crossing at low speed is timed less well

    /** For runs whose line speeds are not known (files from before they were kept). */
    RunResult(String stageId, String runId, String csvName, long startUtcMs, boolean finished, String statusText, double totalS,
              double lengthM, double stepM, double[] t, int fixes, double maxGapS, double maxGapAtM, double maxAccM,
              double offRouteMaxM, double startGapS, double finishGapS) {
        this(stageId, runId, csvName, startUtcMs, finished, statusText, totalS, lengthM, stepM, t, fixes, maxGapS, maxGapAtM, maxAccM,
                offRouteMaxM, startGapS, finishGapS, -1.0, -1.0);
    }

    RunResult(String stageId, String runId, String csvName, long startUtcMs, boolean finished, String statusText, double totalS,
              double lengthM, double stepM, double[] t, int fixes, double maxGapS, double maxGapAtM, double maxAccM,
              double offRouteMaxM, double startGapS, double finishGapS, double startSpeedMs, double finishSpeedMs) {
        this.stageId = stageId;
        this.runId = runId;
        this.csvName = csvName == null ? "" : csvName;
        this.startUtcMs = startUtcMs;
        this.finished = finished;
        this.statusText = statusText;
        this.totalS = totalS;
        this.lengthM = lengthM;
        this.stepM = stepM;
        this.t = t == null ? new double[0] : t;
        this.fixes = fixes;
        this.maxGapS = maxGapS;
        this.maxGapAtM = maxGapAtM;
        this.maxAccM = maxAccM;
        this.offRouteMaxM = offRouteMaxM;
        this.startGapS = startGapS;
        this.finishGapS = finishGapS;
        this.startSpeedMs = startSpeedMs;
        this.finishSpeedMs = finishSpeedMs;
    }

    /** Things a reader of the comparison should know before trusting it. Empty if the run looks clean. */
    List<String> warnings() {
        final List<String> w = new ArrayList<String>();
        if (!finished) return w;
        if (maxGapS > 5.0) w.add(String.format(Locale.US, "GPS gap of %.0f s near km %.1f", maxGapS, maxGapAtM / 1000.0));
        if (offRouteMaxM > 100.0) w.add(String.format(Locale.US, "left the route by %.0f m", offRouteMaxM));
        if (startGapS > 2.5 || finishGapS > 2.5) w.add("a line was crossed between fixes that were more than 2 s apart");
        if (maxAccM > 25.0) w.add(String.format(Locale.US, "poor GPS (worst %.0f m)", maxAccM));
        return w;
    }

    /** True if both runs are of the same stage geometry, so their profiles line up. */
    boolean comparableWith(RunResult o) {
        return finished && o.finished && stageId.equals(o.stageId) && stepM == o.stepM && t.length == o.t.length
                && Math.abs(lengthM - o.lengthM) < 0.5;
    }
}
