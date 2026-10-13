package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One run of a stage, followed fix by fix: waiting for the start line, running, past the finish line. Plain Java with no Android
 * classes. It is driven only by the times of the fixes (and, in the app, a once-a-second tick), never by a wall clock, so replaying a
 * recorded log through it gives the same result as the live run did.
 *
 *   APPROACH  the recorder is on, the start line has not been crossed yet
 *   RUNNING   the start line was crossed; every fix is placed on the route
 *   POST      the finish line was crossed; recording goes on for a few seconds (the post-roll), then
 *   FINISHED  done, with a result
 *   DNF       given up after the start (too long, or stopped for too long): done, with a result that has no time
 *   ABORTED   the car never crossed the start line (live only): done, nothing to keep
 *
 * Failsafes (the approach timeout, the "moved away" abort, the run time limit and the long stop) apply only when 'live' is true;
 * when a finished log is scored (Scorer) the car may start anywhere and the run may include a stop.
 *
 * The times that matter are the interpolated moments the car crossed each line (Gate). Everything else (the profile, the quality
 * figures) is bookkeeping around them.
 */
final class StageRun {

    static final int APPROACH = 0, RUNNING = 1, POST = 2, FINISHED = 3, DNF = 4, ABORTED = 5;

    static final double GATE_MIN_SPEED = 1.5;        // m/s: slower than this, a "crossing" is GPS jitter
    static final double GATE_MIN_FORWARD = 0.25;     // the step across a line must be at least this fraction forward
    static final double MAX_FIX_GAP_S = 3.0;         // fixes further apart than this are not interpolated across a line
    static final double POST_ROLL_S = 5.0;           // keep recording this long after the finish line
    static final double APPROACH_TIMEOUT_S = 300.0;  // live: no start line within this long after recording began
    static final double APPROACH_ABORT_M = 700.0;    // live: further than this from the start line while approaching
    static final double RESTART_WITHIN_M = 300.0;    // a second pass of the start line this early in the run restarts it
    static final double IDLE_DNF_S = 1200.0;         // live: stopped for this long during the run
    static final double STILL_SPEED = 0.55;          // m/s, 2 km/h
    static final double FINISH_MIN_PROGRESS = 0.7;   // the car must be this far along the route for the finish line to count
    static final double MIN_RUN_S = 10.0;
    static final double HEADING_MIN_SPEED = 3.0;     // m/s: from this speed the Doppler course is used to place a fix on the route

    /** Something that happened at an instant: the name and values become an E row of the log. */
    static final class Event {
        final double t;          // clock seconds of the event (for a line, the interpolated moment of crossing)
        final String name;       // STAGE_START, STAGE_FINISH, STAGE_DNF, STAGE_ABORT
        final String values;     // "" or ",12.345" (with the leading comma), ready to append to the name

        Event(double t, String name, String values) {
            this.t = t;
            this.name = name;
            this.values = values;
        }
    }

    private final Stage stage;
    private final boolean live;
    private final List<Event> events = new ArrayList<Event>();

    private int phase = APPROACH;
    private Pt prev;
    private double lastFixT = Double.NaN;
    private double tApproach = Double.NaN, tStart = Double.NaN, tFinish = Double.NaN;
    private double sNow;                                 // route distance of the latest placed fix, never decreasing
    private double[] pt = new double[512], ps = new double[512];
    private int pn;
    private double stillSince = Double.NaN;
    private String reason = "";

    // quality, for the fixes between the lines
    private int nFix;
    private double maxGap, maxGapAt, maxAcc = -1.0, offRouteMax;
    private double startGap, finishGap;
    private double startSpeed = -1.0, finishSpeed = -1.0;   // speed at each line, interpolated between the two fixes either side
    private double[] profile;                            // set when the finish line is crossed
    private final double[] tmp = new double[2];

    StageRun(Stage stage, boolean live) {
        this.stage = stage;
        this.live = live;
    }

    int phase() {
        return phase;
    }

    boolean done() {
        return phase == FINISHED || phase == DNF || phase == ABORTED;
    }

    /** Why the run ended early (DNF or ABORTED), in plain words; "" otherwise. */
    String reason() {
        return reason;
    }

    /** Clock time of the start line crossing, NaN before it. */
    double startTime() {
        return tStart;
    }

    double finishTime() {
        return tFinish;
    }

    /** Route distance of the latest fix placed on the route (metres), 0 before the start line. */
    double progressM() {
        return sNow;
    }

    /** The events since the last call, oldest first. */
    List<Event> drainEvents() {
        final List<Event> out = new ArrayList<Event>(events);
        events.clear();
        return out;
    }

    // ------------------------------------------------------------------ fixes

    void onFix(Fix f) {
        if (done() || f == null || !f.usable()) return;
        if (!Double.isNaN(lastFixT) && !(f.t > lastFixT)) return;       // repeated or out of order: keep the earlier one
        final Pt cur = Pt.make(stage.geo, f, prev);
        if (Double.isNaN(tApproach)) tApproach = f.t;
        switch (phase) {
            case APPROACH:
                approach(cur, f);
                break;
            case RUNNING:
                running(cur, f);
                break;
            case POST:
                if (f.t >= tFinish + POST_ROLL_S) phase = FINISHED;
                break;
            default:
                break;
        }
        prev = cur;
        lastFixT = f.t;
    }

    /** Time passing without a fix (the app calls this once a second): the failsafes and the end of the post-roll. */
    void onTick(double now) {
        if (done() || Double.isNaN(now)) return;
        switch (phase) {
            case POST:
                if (now >= tFinish + POST_ROLL_S) phase = FINISHED;
                break;
            case APPROACH:
                if (live && !Double.isNaN(tApproach) && now - tApproach > APPROACH_TIMEOUT_S) {
                    abort("the start line was not crossed within 5 minutes", now);
                }
                break;
            case RUNNING:
                if (live && now - tStart > stage.maxRunS()) dnf("the run took too long", now);
                break;
            default:
                break;
        }
    }

    /** The recording is being stopped by hand while the run is open: close it as it stands. */
    void endNow(String why) {
        if (done()) return;
        final double t = Double.isNaN(lastFixT) ? 0.0 : lastFixT;
        if (phase == POST) {
            phase = FINISHED;
        } else if (phase == RUNNING) {
            dnf(why, t);
        } else {
            abort(why, t);
        }
    }

    // ------------------------------------------------------------------ phases

    private void approach(Pt cur, Fix f) {
        if (prev != null && cur.t - prev.t <= MAX_FIX_GAP_S) {
            final double tc = stage.start.crossing(prev, cur, GATE_MIN_SPEED, GATE_MIN_FORWARD);
            if (!Double.isNaN(tc)) {
                begin(tc, cur, f);
                return;
            }
        }
        if (!live) return;
        if (f.t - tApproach > APPROACH_TIMEOUT_S) {
            abort("the start line was not crossed within 5 minutes", f.t);
        } else if (Math.hypot(cur.x - stage.start.cx, cur.y - stage.start.cy) > APPROACH_ABORT_M) {
            abort("moved away from the start line", f.t);
        }
    }

    private void begin(double tc, Pt cur, Fix f) {
        tStart = tc;
        phase = RUNNING;
        pn = 0;
        sNow = 0.0;
        stillSince = Double.NaN;
        nFix = 0;
        maxGap = 0.0;
        maxGapAt = 0.0;
        maxAcc = -1.0;
        offRouteMax = 0.0;
        startGap = cur.t - prev.t;
        startSpeed = speedAt(tc, prev, cur);
        events.add(new Event(tc, "STAGE_START", ""));
        final double[] where = locate(cur);
        place(cur, f, where[0], where[1]);
    }

    private void running(Pt cur, Fix f) {
        final boolean near = cur.t - prev.t <= MAX_FIX_GAP_S;
        if (near && sNow < RESTART_WITHIN_M) {                          // a second pass of the start line very early: start again from it
            final double tc = stage.start.crossing(prev, cur, GATE_MIN_SPEED, GATE_MIN_FORWARD);
            if (!Double.isNaN(tc)) {
                begin(tc, cur, f);
                return;
            }
        }
        final double[] where = locate(cur);
        if (near) {
            final double tf = stage.finish.crossing(prev, cur, GATE_MIN_SPEED, GATE_MIN_FORWARD);
            if (!Double.isNaN(tf) && tf - tStart >= MIN_RUN_S && where[0] >= FINISH_MIN_PROGRESS * stage.route.length) {
                sNow = where[0];
                finish(tf, cur);                                        // the fix after the line is not part of the run: not placed, not counted
                return;
            }
        }
        place(cur, f, where[0], where[1]);
        if (!live) return;
        if (cur.t - tStart > stage.maxRunS()) {
            dnf("the run took too long", f.t);
        } else if (!Double.isNaN(stillSince) && cur.t - stillSince > IDLE_DNF_S) {
            dnf("stopped for 20 minutes", f.t);
        }
    }

    /**
     * Where on the route a fix is: {route distance, distance of the fix from the route}. The route distance is never behind the last
     * one and never past the end. Changes nothing, so the finish test can use it before the fix is counted as part of the run.
     */
    private double[] locate(Pt cur) {
        final double dt = cur.t - prev.t;
        // look only a little way along the route from the last place: far enough for the car's speed and the time since the last fix, but
        // not so far that the leg of a hairpin 30 m away, which is hundreds of metres further along the route, can pull the fix onto it
        final double ahead = 1.5 * Math.max(cur.speed, 5.0) * Math.max(dt, 1.0) + 60.0;
        // the heading the phone reports tells the two legs of a hairpin apart; at low speed the course means nothing, so none is used
        final double heading = cur.speed >= HEADING_MIN_SPEED ? Geo.bearing(cur.vx, cur.vy) : Double.NaN;
        double s = stage.route.project(cur.x, cur.y, sNow, 40.0, ahead, heading, tmp);
        double dist = tmp[0];
        if (dist > 60.0) {                                              // lost (a GPS gap, a detour): look along the whole rest of the route
            final double s2 = stage.route.project(cur.x, cur.y, sNow, 40.0, stage.route.length, heading, tmp);
            if (tmp[0] + 20.0 < dist) {
                s = s2;
                dist = tmp[0];
            }
        }
        if (s < sNow) s = sNow;                                         // the car does not go backwards along the route
        if (s > stage.route.length) s = stage.route.length;
        return new double[]{s, dist};
    }

    /** Counts the fix as part of the run: keeps its place on the route and the figures about it. */
    private void place(Pt cur, Fix f, double s, double dist) {
        final double dt = cur.t - prev.t;
        nFix++;
        if (dt > maxGap) {
            maxGap = dt;
            maxGapAt = sNow;
        }
        if (f.hAcc > maxAcc) maxAcc = f.hAcc;
        sNow = s;
        if (dist > offRouteMax) offRouteMax = dist;
        if (pn == pt.length) {
            pt = java.util.Arrays.copyOf(pt, pn * 2);
            ps = java.util.Arrays.copyOf(ps, pn * 2);
        }
        pt[pn] = cur.t;
        ps[pn] = s;
        pn++;
        if (cur.speed >= 0.0 && cur.speed < STILL_SPEED) {
            if (Double.isNaN(stillSince)) stillSince = cur.t;
        } else {
            stillSince = Double.NaN;
        }
    }

    private void finish(double tf, Pt cur) {
        tFinish = tf;
        finishGap = cur.t - prev.t;
        finishSpeed = speedAt(tf, prev, cur);
        profile = buildProfile();
        phase = POST;
        events.add(new Event(tf, "STAGE_FINISH", String.format(Locale.US, ",%.3f", tf - tStart)));
    }

    /** The speed at time tc between two fixes: linear between their Doppler speeds. */
    private static double speedAt(double tc, Pt a, Pt b) {
        final double f = b.t > a.t ? Math.max(0.0, Math.min(1.0, (tc - a.t) / (b.t - a.t))) : 1.0;
        return a.speed + f * (b.speed - a.speed);
    }

    private void dnf(String why, double t) {
        reason = why;
        phase = DNF;
        events.add(new Event(t, "STAGE_DNF", "," + clean(why)));
    }

    private void abort(String why, double t) {
        reason = why;
        phase = ABORTED;
        events.add(new Event(t, "STAGE_ABORT", "," + clean(why)));
    }

    private static String clean(String s) {
        return s.replace(',', ' ').replace('\n', ' ');
    }

    // ------------------------------------------------------------------ result

    /** Seconds after the start line at route distance k * 25 m (last entry at the end of the route). */
    private double[] buildProfile() {
        final double len = stage.route.length;
        final double[] tt = new double[pn + 2], ss = new double[pn + 2];
        int m = 0;
        tt[m] = tStart;
        ss[m] = 0.0;
        m++;
        for (int i = 0; i < pn; i++) {
            if (pt[i] <= tStart || pt[i] >= tFinish) continue;
            final double s = ps[i];
            if (s <= ss[m - 1] + 0.01) continue;                        // stopped or jittering: keep the earliest time at each distance
            if (s >= len - 0.01) continue;                              // the end of the route belongs to the finish crossing
            tt[m] = pt[i];
            ss[m] = s;
            m++;
        }
        tt[m] = tFinish;
        ss[m] = len;
        m++;
        final int n = (int) Math.ceil(len / RunResult.STEP_M);
        final double[] out = new double[n + 1];
        int j = 0;
        for (int k = 0; k <= n; k++) {
            final double s = Math.min(k * RunResult.STEP_M, len);
            while (j < m - 2 && ss[j + 1] < s) j++;
            double f = ss[j + 1] > ss[j] ? (s - ss[j]) / (ss[j + 1] - ss[j]) : 0.0;
            f = Math.max(0.0, Math.min(1.0, f));
            out[k] = (tt[j] + f * (tt[j + 1] - tt[j])) - tStart;
        }
        return out;
    }

    /**
     * The result, once the run is FINISHED or DNF (null before that, and for ABORTED).
     *
     * @param clockToUtcMs add this to a clock time in seconds, times 1000, to get wall-clock milliseconds (0 if unknown)
     */
    RunResult result(String runId, String csvName, double clockToUtcMs) {
        if (phase != FINISHED && phase != DNF && phase != POST) return null;
        final long utc = Double.isNaN(tStart) ? 0L : Math.round(tStart * 1000.0 + clockToUtcMs);
        if (phase == DNF) {
            return new RunResult(stage.id, runId, csvName, utc, false, reason, Double.NaN, stage.route.length, RunResult.STEP_M, null,
                    nFix, maxGap, maxGapAt, maxAcc, offRouteMax, startGap, 0.0, startSpeed, -1.0);
        }
        return new RunResult(stage.id, runId, csvName, utc, true, "ok", tFinish - tStart, stage.route.length, RunResult.STEP_M, profile,
                nFix, maxGap, maxGapAt, maxAcc, offRouteMax, startGap, finishGap, startSpeed, finishSpeed);
    }
}
