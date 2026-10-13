package app.tracklog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Tests of the pure stage code (Gate, Route, StageBuilder, StageMarker, StageRun, Scorer) against a synthetic road where the exact
 * time of every passage is known. No Android classes are involved. Run on its own with
 *   java -cp <classes> app.tracklog.StageTests
 * or as part of the harness (mode "st").
 */
final class StageTests {

    // ------------------------------------------------------------------ helpers

    /** The marking drive of a synthetic road: parked 25 s, drive, parked 15 s. */
    static final class Marking {
        SynthRoad road;
        SynthRoad.Drive drive;
        double tDepart = 25.0;
        List<Fix> fixes;
        Stage stage;
        String markStartError = "", markFinishError = "";
    }

    static Marking mark(SynthRoad road, double sigma, long seed) {
        final Marking m = new Marking();
        m.road = road;
        m.drive = road.drive(0.0, road.total, 0.3, 0.3, 28.0, 5.0, 3.0, 5.0, 1.0, null);
        m.fixes = road.fixes(m.drive, m.tDepart, 0.0, m.tDepart + m.drive.duration() + 15.0, sigma, 0.7, 0.0, new Random(seed));
        final StageMarker mk = new StageMarker();
        boolean started = false;
        for (Fix f : m.fixes) {
            mk.onFix(f);
            if (!started && f.t >= 15.0 && f.t < m.tDepart - 1.0) {
                m.markStartError = mk.markStart(f.t);
                started = true;
            }
            if (started && mk.phase() == StageMarker.DRIVING && f.t >= m.tDepart + m.drive.duration() + 8.0) {
                m.markFinishError = mk.markFinish(f.t, "st_test", "Test stage", 1L);
            }
        }
        m.stage = mk.stage();
        return m;
    }

    /** Route distance on the road (s) of the point nearest to (x, y). */
    static double roadS(SynthRoad road, double x, double y) {
        double best = Double.MAX_VALUE, bs = 0;
        for (double s = road.sMin; s <= road.sMax; s += 0.25) {
            final double[] p = road.pos(s);
            final double d = Math.hypot(p[0] - x, p[1] - y);
            if (d < best) { best = d; bs = s; }
        }
        return bs;
    }

    static double[] gateXY(SynthRoad road, Stage st, Gate g) {
        // the stage frame has its origin at the start mark; the road frame at the true start point
        final double lat = st.geo.lat(g.cy), lon = st.geo.lon(g.cx);
        return new double[]{road.geo.x(lon), road.geo.y(lat)};
    }

    static Pt pt(Geo g, double t, double lat, double lon, double speed, double bearing, Pt prev) {
        return Pt.make(g, new Fix(t, lat, lon, speed, bearing, 3.0), prev);
    }

    // ------------------------------------------------------------------ the tests

    static void run(File root) {
        System.out.println("\n===== ST: stage code on a synthetic road =====");
        gateTests();
        builderTests();
        runTests();
        noiseTests();
        oddRunTests();
        failsafeTests();
        compareTests();
        storeTests(root);
        textTests();
        markerWatcherGuardTests();
        StageBundleTests.run();
        StageAppTests.run(root);
        try {
            StageRealTests.run();
        } catch (Exception e) {
            Check.ok("ST.RL real-recording tests ran without an exception", false, e.toString());
        }
        fixturesCheck();
    }

    /** The committed test files for other implementations are what this code makes (see StageFixtures). */
    static void fixturesCheck() {
        final String fx = System.getProperty("fixtures.dir");
        if (fx == null || !new File(fx).isDirectory()) {
            System.out.println("(ST.FX skipped: pass -Dfixtures.dir=<analysis/stage_fixtures>; tests/run.sh does when the folder exists)");
            return;
        }
        final String real = System.getProperty("real.log");
        try {
            StageFixtures.check(new File(fx), real != null && new File(real).isFile() ? new File(real) : null);
        } catch (Exception e) {
            Check.ok("ST.FX the stage fixtures were checked without an exception", false, e.toString());
        }
    }

    static void gateTests() {
        // a straight road heading east (bearing 90), the line at x = 0
        final Geo g = new Geo(3.5, 101.5);
        final Gate gate = new Gate(0.0, 0.0, 90.0, 25.0);
        final Random rnd = new Random(1);
        double worst = 0;
        for (int i = 0; i < 400; i++) {
            final double v = 5.0 + rnd.nextDouble() * 35.0, tc = 100.0 + rnd.nextDouble() * 10.0, lat = rnd.nextDouble() * 20.0 - 10.0;
            final double phase = rnd.nextDouble();
            // two fixes either side, exactly one second apart, the first before the line
            final double t0 = tc - phase, t1 = t0 + 1.0;
            final Pt a = new Pt(t0, -v * phase, lat, v, 0.0, v), b = new Pt(t1, v * (1.0 - phase), lat, v, 0.0, v);
            final double got = gate.crossing(a, b, 1.5, 0.25);
            worst = Math.max(worst, Math.abs(got - tc));
        }
        Check.ok("ST.G1 straight road, constant speed, 400 random speeds and phases: crossing time exact to 0.1 ms",
                worst < 1e-4, String.format(Locale.US, "worst %.6f s", worst));

        // an arc: radius 60 m, speed 15 m/s, the line across the road at the 45 degree point
        worst = 0;
        for (int i = 0; i < 200; i++) {
            final double R = 60.0, v = 15.0, w = v / R;                       // angular rate
            final double th0 = Math.toRadians(45.0);                         // line at angle th0 from the start of the arc
            final double tc = 50.0;
            final double phase = 0.05 + 0.9 * rnd.nextDouble();
            final double t0 = tc - phase, t1 = t0 + 1.0;
            // a car on a circle of radius R about (0, R): position (R sin th, R - R cos th), heading th (compass: east=90, so use math)
            final double th_a = th0 + w * (t0 - tc), th_b = th0 + w * (t1 - tc);
            final Pt a = new Pt(t0, R * Math.sin(th_a), R - R * Math.cos(th_a), v * Math.cos(th_a), v * Math.sin(th_a), v);
            final Pt b = new Pt(t1, R * Math.sin(th_b), R - R * Math.cos(th_b), v * Math.cos(th_b), v * Math.sin(th_b), v);
            // line through the 45 degree point, perpendicular to the heading there (heading east-north-east)
            final double bx = R * Math.sin(th0), by = R - R * Math.cos(th0);
            final double hdg = Geo.bearing(Math.cos(th0), Math.sin(th0));
            final Gate gate2 = new Gate(bx, by, hdg, 25.0);
            final double got = gate2.crossing(a, b, 1.5, 0.25);
            worst = Math.max(worst, Math.abs(got - tc));
        }
        Check.ok("ST.G2 car on a 60 m radius bend at 15 m/s, fixes 1 s apart: crossing time within 3 ms of the exact value",
                worst < 0.003, String.format(Locale.US, "worst %.4f s", worst));

        // standing start with constant acceleration: the cubic is exact
        worst = 0;
        for (int i = 0; i < 200; i++) {
            final double acc = 1.5 + rnd.nextDouble() * 4.0, line = 8.0, tdep = 10.0 + rnd.nextDouble();
            final double tc = tdep + Math.sqrt(2.0 * line / acc);
            final double ta = tc - (0.1 + 0.8 * rnd.nextDouble()), tb = ta + 1.0;
            final double xa = ta <= tdep ? 0.0 : 0.5 * acc * (ta - tdep) * (ta - tdep), xb = 0.5 * acc * (tb - tdep) * (tb - tdep);
            final double va = ta <= tdep ? 0.0 : acc * (ta - tdep), vb = acc * (tb - tdep);
            final Gate gate3 = new Gate(line, 0.0, 90.0, 25.0);
            final Pt a = new Pt(ta, xa, 0.0, va, 0.0, va), b = new Pt(tb, xb, 0.0, vb, 0.0, vb);
            final double got = gate3.crossing(a, b, 1.5, 0.25);
            worst = Math.max(worst, Math.abs(got - tc));
        }
        Check.ok("ST.G3 standing start with constant acceleration, line 8 m ahead: crossing time exact to 1 ms",
                worst < 0.001, String.format(Locale.US, "worst %.6f s", worst));

        // what must not count
        final Pt p0 = new Pt(0, -20, 0, 20, 0, 20), p1 = new Pt(1, 0, 0, 20, 0, 20), p2 = new Pt(1, 0, 30, 20, 0, 20);
        final boolean wrongWay = Double.isNaN(gate.crossing(new Pt(0, 20, 0, -20, 0, 20), new Pt(1, 0, 0, -20, 0, 20), 1.5, 0.25));
        final boolean parked = Double.isNaN(gate.crossing(new Pt(0, -1.5, 0, 0.1, 0, 0.1), new Pt(1, 1.5, 0, 0.1, 0, 0.1), 1.5, 0.25));
        final boolean sideways = Double.isNaN(gate.crossing(new Pt(0, -1, -20, 0, 20, 20), new Pt(1, 1, 0, 0, 20, 20), 1.5, 0.25));
        final boolean far = Double.isNaN(gate.crossing(new Pt(0, -20, 30, 20, 0, 20), new Pt(1, 0, 30, 20, 0, 20), 1.5, 0.25));
        final boolean near = !Double.isNaN(gate.crossing(new Pt(0, -20, 24, 20, 0, 20), new Pt(1, 0, 24, 20, 0, 20), 1.5, 0.25));
        final boolean ok = !Double.isNaN(gate.crossing(p0, p1, 1.5, 0.25));
        Check.ok("ST.G4 not counted: the other way, parked and jittering, moving along the line, 30 m off the centre; counted: 24 m off, and a plain pass",
                wrongWay && parked && sideways && far && near && ok && p2 != null,
                "wrongWay=" + wrongWay + " parked=" + parked + " sideways=" + sideways + " far=" + far + " near=" + near + " ok=" + ok);
        if (g == null) throw new AssertionError();
    }

    static void builderTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Marking m = mark(road, 0.0, 5);
        Check.ok("ST.B1 a noise-free marking drive: both marks accepted and a stage built",
                m.markStartError.isEmpty() && m.markFinishError.isEmpty() && m.stage != null, m.markStartError + " / " + m.markFinishError);
        if (m.stage == null) return;
        final double[] gs = gateXY(road, m.stage, m.stage.start), gf = gateXY(road, m.stage, m.stage.finish);
        final double sS = roadS(road, gs[0], gs[1]), sF = roadS(road, gf[0], gf[1]);
        final double hS = road.heading(sS), hF = road.heading(sF);
        Check.ok("ST.B2 the start line is 20 m after the start mark and the finish line 20 m before the finish mark (within 0.5 m)",
                Math.abs(sS - StageBuilder.START_SHIFT_M) < 0.5 && Math.abs(sF - (road.total - StageBuilder.FINISH_SHIFT_M)) < 0.5,
                String.format(Locale.US, "start at s=%.2f finish at s=%.2f of %.1f", sS, sF, road.total));
        Check.ok("ST.B3 the line directions follow the road (within 2 degrees) and the route is about the road's length minus 40 m (within 0.5 %)",
                Geo.angleDiff(m.stage.start.bearingDeg, hS) < 2.0 && Geo.angleDiff(m.stage.finish.bearingDeg, hF) < 2.0
                        && Math.abs(m.stage.lengthM() - (road.total - StageBuilder.START_SHIFT_M - StageBuilder.FINISH_SHIFT_M)) < 0.005 * road.total,
                String.format(Locale.US, "start %.1f vs %.1f, finish %.1f vs %.1f, length %.1f vs %.1f", m.stage.start.bearingDeg, hS,
                        m.stage.finish.bearingDeg, hF, m.stage.lengthM(), road.total - StageBuilder.START_SHIFT_M - StageBuilder.FINISH_SHIFT_M));

        // refusals
        final StageMarker mk = new StageMarker();
        for (Fix f : m.fixes) { if (f.t < 20.0) mk.onFix(f); }
        final String early = mk.markFinish(19.0, "x", "x", 1L);
        Check.ok("ST.B4 finish before start is refused; a mark while moving is refused",
                early.length() > 0 && new StageMarker().markStart(0.0).length() > 0, early);
        // too short: park, drive 200 m, park
        final SynthRoad shortRoad = new SynthRoad(3.5, 101.5, 90.0, new double[][]{{200, 0}}, 100, 100);
        final Marking sm = mark(shortRoad, 0.0, 3);
        Check.ok("ST.B5 a drive of 200 m cannot make a stage (the message says so), and the marker can still be used to mark again",
                sm.stage == null && sm.markFinishError.contains("only"), sm.markFinishError);
        final SynthRoad loop = new SynthRoad(3.5, 101.5, 90.0, new double[][]{{2 * Math.PI * 100.0, 100}}, 100, 100);
        final Marking lm = mark(loop, 0.0, 3);
        Check.ok("ST.B6 a drive that ends near where it began is refused as a loop",
                lm.stage == null && lm.markFinishError.length() > 0, lm.markFinishError);
    }

    static void runTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Marking m = mark(road, 0.0, 5);
        final Stage st = m.stage;
        final double[] gs = gateXY(road, st, st.start), gf = gateXY(road, st, st.finish);
        final double sS = roadS(road, gs[0], gs[1]), sF = roadS(road, gf[0], gf[1]);

        // 1 standing start (the marking drive itself), noise free
        final List<RunResult> r1 = Scorer.scoreAll(st, m.fixes, "session_test.csv", 0);
        final double truth1 = m.drive.timeAt(sF) - m.drive.timeAt(sS);
        Check.ok("ST.R1 marking drive scored as a run (noise-free): one finished run, stage time within 20 ms of the exact time between the lines",
                r1.size() == 1 && r1.get(0).finished && Math.abs(r1.get(0).totalS - truth1) < 0.02,
                r1.size() == 1 ? String.format(Locale.US, "measured %.4f exact %.4f", r1.get(0).totalS, truth1) : "runs=" + r1.size());

        // 2 rolling start: approach from 600 m before the start mark at 18 m/s, noise free, different speed
        final SynthRoad.Drive d2 = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.05, null);
        final List<Fix> f2 = road.fixes(d2, 5.0, 0.0, 5.0 + d2.duration(), 0.0, 0.7, 0.0, new Random(1));
        final List<RunResult> r2 = Scorer.scoreAll(st, f2, "session_roll.csv", 0);
        final double truth2 = d2.timeAt(sF) - d2.timeAt(sS);
        Check.ok("ST.R2 rolling start through the whole stage at 5 % faster pace (noise-free): stage time within 30 ms",
                r2.size() == 1 && r2.get(0).finished && Math.abs(r2.get(0).totalS - truth2) < 0.03,
                r2.size() == 1 ? String.format(Locale.US, "measured %.4f exact %.4f", r2.get(0).totalS, truth2) : "runs=" + r2.size());

        // 3 the profile follows the exact times (noise free) and ends at the total
        if (r2.size() == 1) {
            final RunResult r = r2.get(0);
            double worst = 0;
            boolean mono = true;
            final double base = d2.timeAt(sS);
            for (int k = 0; k < r.t.length; k++) {
                // the route is a polyline through GPS fixes, a little shorter than the road on bends, so grid point k is found on the
                // road by its position, not by adding 25 m * k
                final double[] q = st.route.pointAt(Math.min(k * r.stepM, r.lengthM));
                final double[] xy = {road.geo.x(st.geo.lon(q[0])), road.geo.y(st.geo.lat(q[1]))};
                final double s = roadS(road, xy[0], xy[1]);
                worst = Math.max(worst, Math.abs(r.t[k] - (d2.timeAt(s) - base)));
                if (k > 0 && r.t[k] < r.t[k - 1]) mono = false;
            }
            Check.ok("ST.R3 profile (noise-free): " + r.t.length + " entries every 25 m, never decreasing, within 60 ms of the exact time at each point, last entry = stage time",
                    mono && worst < 0.06 && Math.abs(r.t[r.t.length - 1] - r.totalS) < 1e-9 && r.t[0] == 0.0,
                    String.format(Locale.US, "worst %.4f s", worst));
        }
    }


    // ------------------------------------------------------------------ noise

    static double[] meanStd(double[] v, int n) {
        double m = 0;
        for (int i = 0; i < n; i++) m += v[i];
        m /= n;
        double q = 0;
        for (int i = 0; i < n; i++) q += (v[i] - m) * (v[i] - m);
        return new double[]{m, Math.sqrt(q / (n - 1))};
    }

    static void noiseTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Marking m0 = mark(road, 0.0, 5);
        final Stage st = m0.stage;
        final double[] gs = gateXY(road, st, st.start), gf = gateXY(road, st, st.finish);
        final double sS = roadS(road, gs[0], gs[1]), sF = roadS(road, gf[0], gf[1]);

        // R4: rolling starts, random pace, phase of the 1 Hz fixes, and lane, with GPS noise of 2.5 m (AR(1), 0.7)
        final int N = 300;
        final double[] err = new double[N];
        int n = 0, bad = 0;
        double model4 = 0;
        for (int i = 0; i < N; i++) {
            final Random rnd = new Random(1000 + i);
            final double scale = 0.9 + 0.2 * rnd.nextDouble();
            final SynthRoad.Drive d = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, scale, null);
            final double t0 = 5.0 + rnd.nextDouble();
            final List<Fix> fx = road.fixes(d, t0, rnd.nextDouble(), t0 + d.duration(), 2.5, 0.7, (rnd.nextDouble() - 0.5) * 6.0, rnd);
            final List<RunResult> r = Scorer.scoreAll(st, fx, "x", 0);
            if (r.size() != 1 || !r.get(0).finished) { bad++; continue; }
            err[n++] = r.get(0).totalS - (d.timeAt(sF) - d.timeAt(sS));
            model4 += modelVar(r.get(0));
        }
        final double[] ms = meanStd(err, n);
        final double model4sd = Math.sqrt(model4 / n);
        Check.ok(String.format(Locale.US, "ST.R4 %d rolling-start runs with 2.5 m GPS noise, random pace and lane: all timed; error of the stage time: mean %+.3f s, spread (1 sigma) %.3f s (the model with %.0f m says %.3f s)",
                N, ms[0], ms[1], Compare.GPS_ALONG_M, model4sd), bad == 0 && Math.abs(ms[0]) < 0.02 && ms[1] < 0.15 && ms[1] < 1.15 * model4sd && ms[1] > 0.4 * model4sd, "failed to time " + bad);

        // R5: a start line placed 5 m too far along the road shifts the stage time by 5 m / speed
        final SynthRoad.Drive d5 = road.drive(-600.0, road.total + 300.0, 25.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.0, null);
        final List<Fix> f5 = road.fixes(d5, 5.0, 0.0, 5.0 + d5.duration(), 0.0, 0.7, 0.0, new Random(1));
        final Gate g5 = new Gate(st.start.cx + 5.0 * st.start.ux, st.start.cy + 5.0 * st.start.uy, st.start.bearingDeg, st.start.half);
        final Stage st5 = new Stage(st.id, st.name(), st.kind, st.createdUtcMs, st.geo, g5, st.finish, st.route,
                st.startMarkLat, st.startMarkLon, st.finishMarkLat, st.finishMarkLon);
        final List<RunResult> a5 = Scorer.scoreAll(st, f5, "x", 0), b5 = Scorer.scoreAll(st5, f5, "x", 0);
        final double vGate = d5.speedAt(sS);
        final double shift = a5.get(0).totalS - b5.get(0).totalS;
        Check.ok(String.format(Locale.US, "ST.R5 start line 5 m too far along: the stage time falls by %.3f s; 5 m / %.1f m/s = %.3f s", shift, vGate, 5.0 / vGate),
                a5.size() == 1 && b5.size() == 1 && Math.abs(shift - 5.0 / vGate) < 0.01, "");

        // R6: standing starts at the start mark, braking to a stop at the finish mark (the marking drive again), 3 m GPS noise
        final int M = 500;
        int missed = 0, ok6 = 0;
        double model6 = 0;
        final double[] e6 = new double[M];
        for (int i = 0; i < M; i++) {
            final Random rnd = new Random(7000 + i);
            final SynthRoad.Drive d = road.drive(0.0, road.total, 0.3, 0.3, 28.0, 5.0, 3.0, 5.0, 1.0, null);
            final double tDep = 20.0 + 10.0 * rnd.nextDouble();
            final List<Fix> fx = road.fixes(d, tDep, 0.0, tDep + d.duration() + 15.0, 3.0, 0.7, 0.0, rnd);
            final List<RunResult> r = Scorer.scoreAll(st, fx, "x", 0);
            if (r.size() != 1 || !r.get(0).finished) { missed++; continue; }
            e6[ok6++] = r.get(0).totalS - (d.timeAt(sF) - d.timeAt(sS));
            model6 += modelVar(r.get(0));
        }
        final double[] ms6 = meanStd(e6, ok6);
        final double model6sd = Math.sqrt(model6 / ok6);
        Check.ok(String.format(Locale.US, "ST.R6 %d standing starts at the start mark, stopping at the finish mark, 3 m GPS noise: %d timed, %d missed; error: mean %+.3f s, spread %.3f s (the model says %.3f s)",
                M, ok6, missed, ms6[0], ms6[1], model6sd),
                missed == 0 && Math.abs(ms6[0]) < 0.05 && ms6[1] < 0.45 && ms6[1] < 1.15 * model6sd && ms6[1] > 0.4 * model6sd, "");

        // R17: standing start with the car parked 10 m BEYOND the start mark (so 10 m short of the line), 3 m noise, running on through the finish
        int missed17 = 0, ok17 = 0;
        final double[] e17 = new double[M];
        for (int i = 0; i < M; i++) {
            final Random rnd = new Random(8000 + i);
            final SynthRoad.Drive d = road.drive(10.0, road.total + 300.0, 0.3, 15.0, 28.0, 5.0, 3.0, 5.0, 1.0, null);
            final double tDep = 20.0 + 10.0 * rnd.nextDouble();
            final List<Fix> fx = road.fixes(d, tDep, 0.0, tDep + d.duration(), 3.0, 0.7, 0.0, rnd);
            final List<RunResult> r = Scorer.scoreAll(st, fx, "x", 0);
            if (r.size() != 1 || !r.get(0).finished) { missed17++; continue; }
            e17[ok17++] = r.get(0).totalS - (d.timeAt(sF) - d.timeAt(sS));
        }
        final double[] ms17 = meanStd(e17, ok17);
        Check.ok(String.format(Locale.US, "ST.R17 %d standing starts with the car parked 10 m past the start mark (10 m short of the line), 3 m noise: %d timed, %d missed; error: mean %+.3f s, spread %.3f s",
                M, ok17, missed17, ms17[0], ms17[1]), missed17 <= M / 200 && Math.abs(ms17[0]) < 0.05 && ms17[1] < 0.45, "");
    }

    /** Variance (s squared) that Compare's model gives for the stage time of one run: GPS_ALONG_M over the speed at each line. */
    static double modelVar(RunResult r) {
        final double a = Compare.GPS_ALONG_M / Math.max(r.startSpeedMs, Compare.MIN_LINE_SPEED), b = Compare.GPS_ALONG_M / Math.max(r.finishSpeedMs, Compare.MIN_LINE_SPEED);
        return a * a + b * b;
    }

    // ------------------------------------------------------------------ odd runs

    static void oddRunTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Marking m0 = mark(road, 0.0, 5);
        final Stage st = m0.stage;
        final double[] gs = gateXY(road, st, st.start), gf = gateXY(road, st, st.finish);
        final double sS = roadS(road, gs[0], gs[1]), sF = roadS(road, gf[0], gf[1]);

        // R7: a false start. The car crosses the start line, stops 70 m on, backs up, and goes again: the second crossing is the start.
        final double total = road.total;
        final java.util.function.DoubleUnaryOperator sOfT = new java.util.function.DoubleUnaryOperator() {
            @Override
            public double applyAsDouble(double t) {
                if (t < 30) return -300 + 10 * t;
                if (t < 38) return 70.0 * (t - 30) / 8.0;
                if (t < 50) return 70.0 - 110.0 * (t - 38) / 12.0;
                if (t < 55) return -40.0;
                return Math.min(total + 300.0, -40.0 + 12.0 * (t - 55));
            }
        };
        final double tEnd = 55 + (total + 340.0) / 12.0;
        final List<Fix> f7 = road.fixesFn(sOfT, 0.0, tEnd, 0.0, 0.7, new Random(3));
        final StageRun live7 = new StageRun(st, true);
        int starts = 0;
        for (Fix f : f7) {
            live7.onFix(f);
            for (StageRun.Event e : live7.drainEvents()) if (e.name.equals("STAGE_START")) starts++;
        }
        final List<RunResult> r7 = Scorer.scoreAll(st, f7, "x", 0);
        final double t2 = 55 + (sS + 40.0) / 12.0, tFin = 55 + (sF + 40.0) / 12.0;
        Check.ok(String.format(Locale.US, "ST.R7 false start (cross, stop, reverse, go again): two STAGE_START events, the run is timed from the second one: %.2f s (exact %.2f s)",
                r7.isEmpty() ? Double.NaN : r7.get(0).totalS, tFin - t2),
                starts == 2 && r7.size() == 1 && r7.get(0).finished && Math.abs(r7.get(0).totalS - (tFin - t2)) < 0.05, "starts=" + starts);

        // R8: crossing the finish line when the car is only 32 % of the way along the route does not finish the run; at 80 % it does
        final SynthRoad.Drive d8 = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.0, null);
        final List<Fix> f8 = road.fixes(d8, 5.0, 0.0, 5.0 + d8.duration(), 0.0, 0.7, 0.0, new Random(1));
        final double L = st.lengthM();
        final double[] early = st.route.pointAt(0.32 * L), late = st.route.pointAt(0.8 * L);
        final Stage stEarly = new Stage(st.id, st.name(), st.kind, st.createdUtcMs, st.geo, st.start,
                new Gate(early[0], early[1], st.route.bearingAt(0.32 * L), 25.0), st.route, st.startMarkLat, st.startMarkLon, st.finishMarkLat, st.finishMarkLon);
        final Stage stLate = new Stage(st.id, st.name(), st.kind, st.createdUtcMs, st.geo, st.start,
                new Gate(late[0], late[1], st.route.bearingAt(0.8 * L), 25.0), st.route, st.startMarkLat, st.startMarkLon, st.finishMarkLat, st.finishMarkLon);
        final List<RunResult> re = Scorer.scoreAll(stEarly, f8, "x", 0), rl = Scorer.scoreAll(stLate, f8, "x", 0);
        Check.ok("ST.R8 a finish line crossed at 32 % of the route is ignored (the run stays open and is reported as not finished); at 80 % it ends the run",
                re.size() == 1 && !re.get(0).finished && rl.size() == 1 && rl.get(0).finished,
                "early: " + (re.isEmpty() ? "none" : re.get(0).finished + " " + re.get(0).statusText) + "; late: " + (rl.isEmpty() ? "none" : "" + rl.get(0).finished));

        // R9: a 12 s GPS gap in the middle. The run is still timed, the gap is reported, and the profile is right again after the gap
        final List<Fix> f9 = new ArrayList<Fix>();
        final double tG0 = 5.0 + d8.timeAt(2000.0), tG1 = tG0 + 12.0;
        for (Fix f : f8) if (!(f.t > tG0 && f.t < tG1)) f9.add(f);
        final List<RunResult> r9 = Scorer.scoreAll(st, f9, "x", 0), r9ok = Scorer.scoreAll(st, f8, "x", 0);
        boolean after = r9.size() == 1 && r9.get(0).finished;
        // the stretch of road between the last fix before the gap and the first one after it: only interpolated there
        double gapFrom = Double.MAX_VALUE, gapTo = -Double.MAX_VALUE;
        for (int i = 1; i < f9.size(); i++) {
            if (f9.get(i).t - f9.get(i - 1).t > 5.0) {
                gapFrom = d8.sAtTime(f9.get(i - 1).t - 5.0);
                gapTo = d8.sAtTime(f9.get(i).t - 5.0);
            }
        }
        double worstAfter = 0, worstIn = 0;
        if (after) {
            for (int k = 0; k < r9.get(0).t.length; k++) {
                final double[] q = st.route.pointAt(Math.min(k * 25.0, L));
                final double s = StageTests.roadS(road, road.geo.x(st.geo.lon(q[0])), road.geo.y(st.geo.lat(q[1])));
                final double dev = Math.abs(r9.get(0).t[k] - r9ok.get(0).t[k]);
                if (s > gapFrom - 30.0 && s < gapTo + 30.0) worstIn = Math.max(worstIn, dev);
                else worstAfter = Math.max(worstAfter, dev);
            }
        }
        Check.ok(String.format(Locale.US, "ST.R9 12 s without GPS at km 2 (%.0f m of road with no fix): stage time unchanged (%.3f vs %.3f s), profile outside the gap within 0.05 s (%.3f), inside it within 1.5 s of the full-fix run (%.2f), the gap is a warning (%s)",
                gapTo - gapFrom, after ? r9.get(0).totalS : Double.NaN, r9ok.get(0).totalS, worstAfter, worstIn, after ? r9.get(0).warnings() : "-"),
                after && Math.abs(r9.get(0).totalS - r9ok.get(0).totalS) < 0.01 && worstAfter < 0.05 && worstIn < 1.5 && r9.get(0).maxGapS > 11.0
                        && !r9.get(0).warnings().isEmpty(), "");

        // R10: a zigzag hillside road, hairpin legs 30 m apart, 3 m GPS noise. The error of the profile is judged in metres (time error
        // times the speed there), which is what GPS noise gives; a fix placed on the wrong leg of a hairpin would be 40 m or more out.
        final SynthRoad zig = new SynthRoad(3.45, 101.74, 90.0, new double[][]{
                {250, 0}, {Math.PI * 15, 15}, {250, 0}, {Math.PI * 15, -15}, {250, 0}, {Math.PI * 15, 15}, {250, 0}, {Math.PI * 15, -15}, {250, 0}}, 400, 300);
        final Marking zm = mark(zig, 0.0, 9);
        int okZ = 0, badZ = 0, nZ = 0;
        double sumSqZ = 0, worstZ = 0;
        if (zm.stage != null) {
            final double[] zs = gateXY(zig, zm.stage, zm.stage.start);
            final double zsS = roadS(zig, zs[0], zs[1]);
            for (int i = 0; i < 40; i++) {
                final Random rnd = new Random(500 + i);
                final SynthRoad.Drive d = zig.drive(-400.0, zig.total + 300.0, 12.0, 10.0, 25.0, 5.0, 3.0, 5.0, 0.9 + 0.2 * rnd.nextDouble(), null);
                final List<Fix> fx = zig.fixes(d, 3.0, rnd.nextDouble(), 3.0 + d.duration(), 3.0, 0.7, (rnd.nextDouble() - 0.5) * 4.0, rnd);
                final List<RunResult> r = Scorer.scoreAll(zm.stage, fx, "x", 0);
                if (r.size() != 1 || !r.get(0).finished) { badZ++; continue; }
                okZ++;
                final RunResult rr = r.get(0);
                final double base = d.timeAt(zsS);
                for (int k = 0; k < rr.t.length; k++) {
                    final double[] q = zm.stage.route.pointAt(Math.min(k * rr.stepM, rr.lengthM));
                    final double s = roadS(zig, zig.geo.x(zm.stage.geo.lon(q[0])), zig.geo.y(zm.stage.geo.lat(q[1])));
                    final double em = Math.abs(rr.t[k] - (d.timeAt(s) - base)) * d.speedAt(s);
                    sumSqZ += em * em;
                    nZ++;
                    worstZ = Math.max(worstZ, em);
                }
            }
        }
        final double rmsZ = nZ > 0 ? Math.sqrt(sumSqZ / nZ) : Double.NaN;
        Check.ok(String.format(Locale.US, "ST.R10 zigzag road with hairpin legs 30 m apart, 3 m GPS noise, 40 runs: all timed; profile error (time error x speed) rms %.1f m, worst %.1f m (a wrong-leg fix would be 40 m or more)", rmsZ, worstZ),
                zm.stage != null && badZ == 0 && okZ == 40 && rmsZ < 5.0 && worstZ < 25.0, "bad=" + badZ);

        // P1: Route.project with and without the car's heading, on a hairpin road with legs 16 m apart and 3 m GPS noise
        final SynthRoad tight = new SynthRoad(3.45, 101.74, 90.0, new double[][]{
                {200, 0}, {Math.PI * 8, 8}, {100, 0}, {Math.PI * 8, -8}, {100, 0}, {Math.PI * 8, 8}, {100, 0}, {Math.PI * 8, -8}, {200, 0}}, 100, 100);
        final int NP = 20;
        final double[] rx = new double[(int) (tight.total / 5.0) + 1], ry = new double[rx.length];
        for (int i = 0; i < rx.length; i++) {
            final double[] p = tight.pos(Math.min(tight.total, i * 5.0));
            rx[i] = p[0];
            ry[i] = p[1];
        }
        final Route tr = new Route(rx, ry);
        int wrongNoHeading = 0, wrongHeading = 0, tries = 0;
        final Random rp = new Random(77);
        for (int i = 0; i < 4000; i++) {
            final double sTrue = 5.0 + rp.nextDouble() * (tr.length - 10.0);
            final double[] p = tr.pointAt(sTrue);
            final double px = p[0] + 3.0 * rp.nextGaussian(), py = p[1] + 3.0 * rp.nextGaussian();
            final double hdg = tr.bearingAt(sTrue) + 3.0 * rp.nextGaussian();
            final double hint = Math.max(0.0, sTrue - 10.0 - 15.0 * rp.nextDouble());          // where the previous fix put the car
            final double[] o = new double[2];
            final double a0 = tr.project(px, py, hint, 40.0, 75.0, o), a1 = tr.project(px, py, hint, 40.0, 75.0, hdg, o);
            tries++;
            if (Math.abs(a0 - sTrue) > 25.0) wrongNoHeading++;
            if (Math.abs(a1 - sTrue) > 25.0) wrongHeading++;
        }
        Check.ok(String.format(Locale.US, "ST.P1 placing a fix on a route with hairpin legs 16 m apart and 3 m noise: %d of %d fixes land more than 25 m of route away from the truth without the car's heading, %d with it", wrongNoHeading, tries, wrongHeading),
                wrongHeading * 5 <= wrongNoHeading + 1 && wrongHeading < tries / 100 && NP > 0, "");
    }

    static void failsafeTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Marking m0 = mark(road, 0.0, 5);
        final Stage st = m0.stage;
        final SynthRoad.Drive d = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.0, null);
        final List<Fix> fx = road.fixes(d, 5.0, 0.0, 5.0 + d.duration(), 0.0, 0.7, 0.0, new Random(1));

        // R11: live and offline give the same numbers, to the last bit
        final StageRun live = new StageRun(st, true);
        for (Fix f : fx) live.onFix(f);
        live.onTick(fx.get(fx.size() - 1).t + 10.0);
        final RunResult rl = live.result("r", "x.csv", 0);
        final RunResult ro = Scorer.scoreAll(st, fx, "x.csv", 0).get(0);
        boolean same = rl != null && rl.finished && Double.compare(rl.totalS, ro.totalS) == 0 && rl.t.length == ro.t.length;
        for (int k = 0; same && k < rl.t.length; k++) same = Double.compare(rl.t[k], ro.t[k]) == 0;
        Check.ok("ST.R11 the same fixes through the live run and through the offline scorer give identical stage time and profile (bit for bit)", same,
                rl == null ? "no result" : String.format(Locale.US, "%.6f / %.6f", rl.totalS, ro.totalS));

        // R12: repeated and out-of-order fixes change nothing
        final List<Fix> messy = new ArrayList<Fix>(fx);
        messy.add(5, fx.get(4));
        messy.add(40, fx.get(30));
        final StageRun live12 = new StageRun(st, true);
        for (Fix f : messy) live12.onFix(f);
        live12.onTick(fx.get(fx.size() - 1).t + 10.0);
        final RunResult r12 = live12.result("r", "x.csv", 0);
        final List<Fix> shuffled = new ArrayList<Fix>(fx);
        java.util.Collections.shuffle(shuffled, new Random(4));
        final List<RunResult> r12b = Scorer.scoreAll(st, shuffled, "x.csv", 0);
        Check.ok("ST.R12 duplicated or late fixes are ignored by the live run, and the offline scorer puts shuffled fixes in time order: the same stage time",
                r12 != null && Double.compare(r12.totalS, ro.totalS) == 0 && r12b.size() == 1 && Double.compare(r12b.get(0).totalS, ro.totalS) == 0, "");

        // R13: the post-roll. After the finish line the run stays open for 5 s of recording, then it is done; a tick can close it too
        final StageRun pr = new StageRun(st, true);
        double tFinish = Double.NaN;
        int phaseAtFinish = -1;
        for (Fix f : fx) {
            pr.onFix(f);
            for (StageRun.Event e : pr.drainEvents()) if (e.name.equals("STAGE_FINISH")) { tFinish = e.t; phaseAtFinish = pr.phase(); }
            if (!Double.isNaN(tFinish) && f.t >= tFinish + 6.0) break;
        }
        final StageRun pr2 = new StageRun(st, true);
        boolean tickClosed = false;
        for (Fix f : fx) {
            pr2.onFix(f);
            if (pr2.phase() == StageRun.POST) {
                final boolean before = pr2.done();
                pr2.onTick(pr2.finishTime() + 2.0);
                final boolean mid = pr2.done();
                pr2.onTick(pr2.finishTime() + 5.01);
                tickClosed = !before && !mid && pr2.done() && pr2.phase() == StageRun.FINISHED;
                break;
            }
        }
        Check.ok("ST.R13 post-roll: the run is POST when the finish line is crossed and FINISHED 5 s later (by a fix, or by the once-a-second tick when the GPS is silent)",
                phaseAtFinish == StageRun.POST && pr.phase() == StageRun.FINISHED && tickClosed, "phaseAtFinish=" + phaseAtFinish);

        // R14: live failsafes
        // (a) parked 60 m before the line for 6 minutes: ABORTED by time
        final StageRun a = new StageRun(st, true);
        final SynthRoad.Drive park = road.drive(-60.0, -59.0, 0.3, 0.3, 5.0, 5.0, 3.0, 5.0, 1.0, null);
        for (int i = 0; i < 400 && !a.done(); i++) a.onFix(new Fix(i, st.geo.lat(road.pos(-60.0)[1] - 0), st.geo.lon(road.pos(-60.0)[0]), 0.0, -1.0, 3.0));
        // (b) driving away from the start
        final StageRun b = new StageRun(st, true);
        for (int i = 0; i < 100 && !b.done(); i++) {
            final double[] p = road.pos(-300.0 - 25.0 * i);
            b.onFix(new Fix(i, road.lat(p[1]), road.lon(p[0]), 25.0, (road.heading(-300.0 - 25.0 * i) + 180.0) % 360.0, 3.0));
        }
        // (c) tick only: no fixes at all after the first
        final StageRun c = new StageRun(st, true);
        c.onFix(new Fix(0.0, road.lat(road.pos(-200.0)[1]), road.lon(road.pos(-200.0)[0]), 10.0, road.heading(-200.0), 3.0));
        c.onTick(299.0);
        final boolean notYet = !c.done();
        c.onTick(301.0);
        Check.ok("ST.R14 approach failsafes: ABORTED after 5 minutes without a start line, when moving away (>700 m), and by the tick alone; nothing is kept",
                a.phase() == StageRun.ABORTED && b.phase() == StageRun.ABORTED && notYet && c.phase() == StageRun.ABORTED
                        && a.result("r", "x", 0) == null && park != null,
                "a=" + a.phase() + " " + a.reason() + "; b=" + b.phase() + " " + b.reason() + "; c=" + c.phase());

        // (d) after the start: 21 minutes stopped is a DNF; the run record says so and has no time
        final StageRun dnf = new StageRun(st, true);
        final SynthRoad.Drive d0 = road.drive(-600.0, 200.0, 18.0, 0.3, 30.0, 5.5, 3.0, 5.0, 1.0, null);
        final List<Fix> fa = road.fixes(d0, 5.0, 0.0, 5.0 + d0.duration(), 0.0, 0.7, 0.0, new Random(1));
        for (Fix f : fa) dnf.onFix(f);
        final double tLast = fa.get(fa.size() - 1).t;
        final double[] pEnd = road.pos(200.0);
        for (int i = 1; i <= 1300 && !dnf.done(); i++) dnf.onFix(new Fix(tLast + i, road.lat(pEnd[1]), road.lon(pEnd[0]), 0.0, -1.0, 3.0));
        final RunResult rd = dnf.result("r", "x.csv", 0);
        Check.ok("ST.R15 stopped on the stage for more than 20 minutes: DNF, with a run record that has no time and no profile",
                dnf.phase() == StageRun.DNF && rd != null && !rd.finished && Double.isNaN(rd.totalS) && rd.t.length == 0 && rd.statusText.contains("stopped"),
                "phase=" + dnf.phase() + " " + dnf.reason());

        // (e) the same stop scored offline is not given up: a long wait mid-stage is just time
        final List<Fix> fb = new ArrayList<Fix>(fa);
        for (int i = 1; i <= 1300; i++) fb.add(new Fix(tLast + i, road.lat(pEnd[1]), road.lon(pEnd[0]), 0.0, -1.0, 3.0));
        final List<RunResult> ob = Scorer.scoreAll(st, fb, "x.csv", 0);
        Check.ok("ST.R16 scored offline, a recording that ends mid-stage gives one run that did not finish (the CSV is not judged)",
                ob.size() == 1 && !ob.get(0).finished && ob.get(0).statusText.contains("recording ended"), ob.isEmpty() ? "none" : ob.get(0).statusText);
    }

    // ------------------------------------------------------------------ comparing

    static void compareTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Marking m0 = mark(road, 0.0, 5);
        final Stage st = m0.stage;
        final SynthRoad.Drive dB = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.0, null);
        // A is 7 % slower on the road between km 1.5 and km 2.0 only
        final SynthRoad.Drive dA = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.0, new java.util.function.DoubleUnaryOperator() {
            @Override public double applyAsDouble(double s) { return (s >= 1500.0 && s <= 2000.0) ? 0.93 : 1.0; }
        });
        final RunResult rB = Scorer.scoreAll(st, road.fixes(dB, 5.0, 0.0, 5.0 + dB.duration(), 0.0, 0.7, 0.0, new Random(1)), "b.csv", 0).get(0);
        final RunResult rA = Scorer.scoreAll(st, road.fixes(dA, 5.0, 0.0, 5.0 + dA.duration(), 0.0, 0.7, 0.0, new Random(1)), "a.csv", 0).get(0);
        final Compare c = Compare.of(rA, rB);
        double sum = 0;
        Compare.Section big = null;
        for (Compare.Section s : c.sections) {
            sum += s.deltaS();
            if (big == null || Math.abs(s.deltaS()) > Math.abs(big.deltaS())) big = s;
        }
        final double truth = (dA.timeAt(dA.sTo) - dA.timeAt(dA.sFrom)) - (dB.timeAt(dB.sTo) - dB.timeAt(dB.sFrom));
        Check.ok(String.format(Locale.US, "ST.C1 compare: A slower by %+.2f s on km 1.5-2.0 only; sections add up to the total (%.3f vs %.3f s); the biggest section is %.1f-%.1f km (%+.2f s)",
                c.totalDeltaS, sum, c.totalDeltaS, big.fromM / 1000.0, big.toM / 1000.0, big.deltaS()),
                Math.abs(sum - c.totalDeltaS) < 1e-9 && Math.abs(c.delta[c.delta.length - 1] - c.totalDeltaS) < 1e-9
                        && big.fromM <= 2000.0 && big.toM >= 1500.0 && c.totalDeltaS > 0.5 && truth != 0,
                "sections=" + c.sections.size());

        final List<RunResult> list = new ArrayList<RunResult>();
        final RunResult early = new RunResult(rB.stageId, "r1", "", 1000L, true, "ok", 100.0, rB.lengthM, rB.stepM, rB.t, 10, 1, 0, 3, 5, 1, 1);
        final RunResult mid = new RunResult(rB.stageId, "r2", "", 2000L, true, "ok", 95.0, rB.lengthM, rB.stepM, rB.t, 10, 1, 0, 3, 5, 1, 1);
        final RunResult dnf = new RunResult(rB.stageId, "r3", "", 3000L, false, "stopped", Double.NaN, rB.lengthM, rB.stepM, null, 10, 1, 0, 3, 5, 1, 1);
        final RunResult last = new RunResult(rB.stageId, "r4", "", 4000L, true, "ok", 97.0, rB.lengthM, rB.stepM, rB.t, 10, 1, 0, 3, 5, 1, 1);
        list.add(early); list.add(mid); list.add(dnf); list.add(last);
        Check.ok("ST.C2 best = the fastest finished run (r2); previous skips a run that did not finish (r4 -> r2); the first run has no previous",
                Compare.best(list) == mid && Compare.previous(list, last) == mid && Compare.previous(list, early) == null && Compare.previous(list, dnf) == mid,
                "");

        boolean refused = false;
        try { Compare.of(rA, dnf); } catch (IllegalArgumentException e) { refused = true; }
        final RunResult other = new RunResult("other", "x", "", 0L, true, "ok", 50.0, rB.lengthM, rB.stepM, rB.t, 1, 1, 0, 3, 5, 1, 1);
        boolean refused2 = false;
        try { Compare.of(rA, other); } catch (IllegalArgumentException e) { refused2 = true; }
        Check.ok("ST.C3 two runs of different stages, or a run that did not finish, are refused", refused && refused2, "");

        Check.ok("ST.C4 section length: 250 m for 2 km, 500 m for 4.7 km, 1 km for 12 km, 5 km for 38 km, never more than 12 sections",
                Compare.sectionLength(2000) == 250 && Compare.sectionLength(4690) == 500 && Compare.sectionLength(12000) == 1000
                        && Compare.sectionLength(38400) == 5000 && Compare.sectionLength(150000) == 10000, "");
    }

    // ------------------------------------------------------------------ files

    static void storeTests(File root) {
        File dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("st_store_").toFile();
        } catch (java.io.IOException e) {
            Check.ok("ST.S0 temp dir", false, e.toString());
            return;
        }
        final StageStore store = new StageStore(dir);
        final SynthRoad road = SynthRoad.mountain();
        final Marking m0 = mark(road, 2.0, 5);
        final Stage st = m0.stage;
        try {
            store.save(st);
            final Stage back = store.load(st.id);
            boolean same = back != null && back.id.equals(st.id) && back.name().equals(st.name()) && back.route.n == st.route.n
                    && Math.abs(back.lengthM() - st.lengthM()) < 0.01
                    && Math.abs(back.start.cx - st.start.cx) < 0.001 && Math.abs(back.finish.cy - st.finish.cy) < 0.001
                    && Math.abs(back.start.bearingDeg - st.start.bearingDeg) < 0.001 && back.kind.equals(Stage.KIND_ROAD)
                    && Math.abs(back.geo.lat0 - st.geo.lat0) < 1e-7;
            Check.ok("ST.S1 a stage written to a file and read back: same id, name, route (" + st.route.n + " points), lines and origin", same, "");

            // the same run scored against the stage that was saved and loaded gives the same time to a millisecond
            final SynthRoad.Drive d = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.0, null);
            final List<Fix> fx = road.fixes(d, 5.0, 0.0, 5.0 + d.duration(), 2.5, 0.7, 0.0, new Random(2));
            final RunResult r1 = Scorer.scoreAll(st, fx, "s_1.csv", 1e12).get(0), r2 = Scorer.scoreAll(back, fx, "s_1.csv", 1e12).get(0);
            Check.ok(String.format(Locale.US, "ST.S2 the same recording scored against the stage before and after saving: %.4f s and %.4f s (within 1 ms)", r1.totalS, r2.totalS),
                    Math.abs(r1.totalS - r2.totalS) < 0.001, "");

            store.saveRun(r1);
            final RunResult rr = new RunResult(r1.stageId, "dnf_1", "x.csv", r1.startUtcMs + 5000, false, "stopped for 20 minutes", Double.NaN, r1.lengthM, r1.stepM, null,
                    12, 2.0, 100.0, 4.0, 6.0, 1.0, 0.0);
            store.saveRun(rr);
            final List<RunResult> runs = store.runs(st.id);
            boolean runsOk = runs.size() == 2 && runs.get(0).runId.equals(r1.runId) && runs.get(1).runId.equals("dnf_1");
            runsOk &= Math.abs(runs.get(0).totalS - r1.totalS) < 0.0005 && runs.get(0).t.length == r1.t.length && !runs.get(1).finished
                    && runs.get(1).statusText.equals("stopped for 20 minutes") && runs.get(0).startUtcMs == r1.startUtcMs;
            for (int k = 0; runsOk && k < r1.t.length; k++) runsOk = Math.abs(runs.get(0).t[k] - r1.t[k]) < 0.0006;
            Check.ok("ST.S3 two runs (one finished, one not) saved and read back in time order; the profile survives to a millisecond", runsOk, "runs=" + runs.size());

            // damaged files are skipped, never fatal
            final File sd = new File(dir, st.id);
            java.io.FileWriter w = new java.io.FileWriter(new File(sd, "run_cut.txt"));
            w.write(StageFiles.formatRun(r1).substring(0, 200));
            w.close();
            w = new java.io.FileWriter(new File(sd, "run_junk.txt"));
            w.write("hello\n");
            w.close();
            w = new java.io.FileWriter(new File(sd, "run_x.txt.tmp"));
            w.write("TRACKLOGGER-RUN 1\n");
            w.close();
            final File badStage = new File(dir, "st_broken");
            badStage.mkdirs();
            w = new java.io.FileWriter(new File(badStage, "stage.txt"));
            w.write(StageFiles.formatStage(st).substring(0, 500));
            w.close();
            final File emptyDir = new File(dir, "st_empty");
            emptyDir.mkdirs();
            final List<Stage> listed = store.list();
            Check.ok("ST.S4 a cut-short run file, a junk file, a leftover .tmp file, a cut-short stage file and an empty folder are all skipped: one stage, two runs",
                    listed.size() == 1 && listed.get(0).id.equals(st.id) && store.runs(st.id).size() == 2, "stages=" + listed.size() + " runs=" + store.runs(st.id).size());

            // rename, delete
            back.rename("Fraser to Bentong, \n1st; try");
            store.save(back);
            final Stage ren = store.load(st.id);
            Check.ok("ST.S5 rename: commas, semicolons and line breaks are turned into spaces; the new name is saved; the runs stay",
                    ren != null && ren.name().equals("Fraser to Bentong 1st try") && store.runs(st.id).size() == 2, ren == null ? "null" : ren.name());
            store.deleteRun(st.id, "dnf_1");
            final int afterRunDelete = store.runs(st.id).size();
            store.delete(st.id);
            Check.ok("ST.S6 deleting a run leaves the other; deleting the stage removes it and its runs", afterRunDelete == 1 && store.load(st.id) == null && store.runs(st.id).isEmpty(), "");

            // ids that could escape the folder are refused
            boolean refusedId = false;
            try {
                store.saveRun(new RunResult("../x", "r", "", 0L, false, "x", Double.NaN, 1, 25, null, 0, 0, 0, 0, 0, 0, 0));
            } catch (java.io.IOException e) {
                refusedId = true;
            }
            Check.ok("ST.S7 a stage or run id with a path in it is refused (nothing is written outside the stage folder)", refusedId && store.load("../x") == null, "");
        } catch (java.io.IOException e) {
            Check.ok("ST.S1-7 store", false, e.toString());
        }
    }

    static void textTests() {
        Check.ok("ST.T1 times: 0:00.0, 59.96 -> 1:00.0, 3599.96 -> 1:00:00.0, 1872.44 -> 31:12.4, NaN -> --:--.-",
                StageText.time(0).equals("0:00.0") && StageText.time(59.96).equals("1:00.0") && StageText.time(3599.96).equals("1:00:00.0")
                        && StageText.time(1872.44).equals("31:12.4") && StageText.time(Double.NaN).equals("--:--.-") && StageText.time(4512.3).equals("1:15:12.3"),
                StageText.time(59.96) + " " + StageText.time(3599.96));
        Check.ok("ST.T2 differences: +24.3 s, -0.4 s, 0.0 s for under 0.05 s; distances 38.4 km and 850 m",
                StageText.delta(24.34).equals("+24.3 s") && StageText.delta(-0.44).equals("-0.4 s") && StageText.delta(0.04).equals("0.0 s")
                        && StageText.delta(-0.04).equals("0.0 s") && StageText.dist(38412).equals("38.4 km") && StageText.dist(850).equals("850 m"),
                StageText.delta(0.04) + " " + StageText.delta(-0.04));
        Check.ok("ST.T3 under 0.4 s is 'about the same'; over it says faster or slower",
                StageText.verdict(0.3, 0.4, "best").equals("about the same as best") && StageText.verdict(-0.39, 0.4, "best").startsWith("about the same")
                        && StageText.verdict(2.0, 0.4, "best").equals("+2.0 s slower than best") && StageText.verdict(-1.5, 0.4, "previous").equals("-1.5 s faster than previous"),
                StageText.verdict(2.0, 0.4, "best"));
    }

    // ------------------------------------------------------------------ marker, watcher, guard

    static void markerWatcherGuardTests() {
        final SynthRoad road = SynthRoad.mountain();
        final Stage st = mark(road, 0.0, 5).stage;

        // marker: reasons, in plain words
        final StageMarker mk = new StageMarker();
        final String noFix = mk.markStart(0.0);
        mk.onFix(new Fix(1.0, 3.5, 101.5, 0.0, -1, 3.0));
        final String fewFix = mk.markStart(1.0);
        for (int i = 2; i <= 6; i++) mk.onFix(new Fix(i, 3.5, 101.5, 6.0, 90, 3.0));
        final String moving = mk.markStart(6.0);
        final StageMarker mk2 = new StageMarker();
        for (int i = 1; i <= 6; i++) mk2.onFix(new Fix(i, 3.5, 101.5, 0.0, -1, 20.0));
        final String poor = mk2.markStart(6.0);
        final StageMarker mk3 = new StageMarker();
        for (int i = 1; i <= 6; i++) mk3.onFix(new Fix(i, 3.5 + 0.0001 * i, 101.5, 0.1, -1, 3.0));
        final String drifting = mk3.markStart(6.0);
        final StageMarker mk4 = new StageMarker();
        for (int i = 1; i <= 6; i++) mk4.onFix(new Fix(i, 3.5, 101.5, 0.0, -1, 3.0));
        final String stale = mk4.markStart(20.0);
        final String good = mk4.markStart(6.5);
        final String twice = mk4.markStart(7.0);
        Check.ok("ST.M1 the marker says why it will not mark: no fix, too few fixes, moving, poor accuracy, drifting position, old fix; marks when still; refuses a second start mark",
                noFix.contains("GPS") && fewFix.contains("still") && moving.contains("Stop") && poor.contains("better GPS") && drifting.contains("moving about")
                        && stale.contains("GPS") && good.isEmpty() && twice.contains("already"),
                noFix + " | " + fewFix + " | " + moving + " | " + poor + " | " + drifting + " | " + stale + " | " + good + " | " + twice);

        // the mark is an average of up to 5 still fixes. GPS noise on consecutive fixes is correlated (AR(1), 0.7 here), so averaging
        // takes only part of it out. A driver who is told to wait just waits: the marker is tried on every new fix, as the button would be.
        double sumMark = 0, sumFix = 0, sumWait = 0;
        int marked = 0;
        final int T = 400;
        for (int i = 0; i < T; i++) {
            final Random rnd = new Random(300 + i);
            final StageMarker mm = new StageMarker();
            final Geo g = new Geo(3.5, 101.5);
            double nx = 0, ny = 0, lastErr = 0;
            final double sig = 3.0, rho = 0.7, inno = Math.sqrt(1.0 - rho * rho);
            for (int k = 1; k <= 60; k++) {
                nx = rho * nx + inno * sig * rnd.nextGaussian();
                ny = rho * ny + inno * sig * rnd.nextGaussian();
                mm.onFix(new Fix(k, g.lat(ny), g.lon(nx), 0.05, -1, 4.0));
                lastErr = Math.hypot(nx, ny);
                if (k >= 4 && mm.markStart(k).isEmpty()) {
                    sumMark += Math.hypot(g.x(mm.startLon()), g.y(mm.startLat()));
                    sumFix += lastErr;
                    sumWait += k;
                    marked++;
                    break;
                }
            }
        }
        Check.ok(String.format(Locale.US, "ST.M2 marking while parked, GPS noise 3 m per axis (correlated): %d of %d trials could mark within a minute, after %.1f s on average; the mark is off by %.1f m on average against %.1f m for the single latest fix",
                marked, T, sumWait / Math.max(1, marked), sumMark / Math.max(1, marked), sumFix / Math.max(1, marked)),
                marked >= T * 97 / 100 && sumMark < 0.95 * sumFix && sumWait / Math.max(1, marked) < 12.0, "");

        // watcher
        final StageWatcher w = new StageWatcher(st);
        final double[] near = road.pos(-300.0), far = road.pos(-1500.0);
        final boolean outside = w.onFix(new Fix(1, road.lat(far[1]), road.lon(far[0]), 20.0, 40, 3.0));
        final boolean parkedInside = w.onFix(new Fix(2, road.lat(near[1]), road.lon(near[0]), 0.1, -1, 3.0));
        final boolean slowInside = w.onFix(new Fix(3, road.lat(near[1]), road.lon(near[0]), 1.0, 40, 3.0));
        final boolean movingInside = w.onFix(new Fix(4, road.lat(near[1]), road.lon(near[0]), 5.0, 40, 3.0));
        w.afterRun();
        final boolean afterRun = w.onFix(new Fix(5, road.lat(near[1]), road.lon(near[0]), 15.0, 40, 3.0));
        final boolean farAgain = w.onFix(new Fix(6, road.lat(far[1]), road.lon(far[0]), 15.0, 40, 3.0));
        final boolean rearmed = w.onFix(new Fix(7, road.lat(near[1]), road.lon(near[0]), 15.0, 40, 3.0));
        Check.ok("ST.W1 watcher: no trigger 1.5 km out, parked inside 400 m, or below 2 m/s; triggers moving inside 400 m; after a run it waits until the car has been 600 m away",
                !outside && !parkedInside && !slowInside && movingInside && !afterRun && !farAgain && rearmed,
                "" + outside + parkedInside + slowInside + movingInside + afterRun + farAgain + rearmed);

        // guard
        final ResultGuard g = new ResultGuard();
        for (int i = 0; i < 10; i++) g.onFix(i, 20.0);
        final boolean movingNow = g.stopped(9.5);
        for (int i = 10; i < 14; i++) g.onFix(i, 0.2);
        final boolean tooShort = g.stopped(13.5);
        g.onFix(14, 0.2);
        g.onFix(15, 0.3);
        final boolean stoppedNow = g.stopped(15.5);
        final boolean silent = g.stopped(19.0);
        g.onFix(20, 3.0);
        final boolean movedAgain = g.stopped(20.5);
        g.reset();
        g.onFix(30, -1.0);
        g.onFix(40, -1.0);
        final boolean unknownSpeed = g.stopped(40.5);
        Check.ok("ST.W2 results guard: not while moving, not before 5 s still, yes after 5 s at under 2 km/h, not when the GPS has been silent for 3 s, not when it moves again, not when the speed is unknown",
                !movingNow && !tooShort && stoppedNow && !silent && !movedAgain && !unknownSpeed,
                "" + movingNow + tooShort + stoppedNow + silent + movedAgain + unknownSpeed);
    }

    public static void main(String[] a) {
        run(new File(System.getProperty("java.io.tmpdir")));
        System.out.println("\nchecks: " + Check.pass + " PASS, " + Check.fail + " FAIL");
        System.exit(Check.fail == 0 ? 0 : 1);
    }

    @SuppressWarnings("unused")
    private static List<Object> unused() {
        return new ArrayList<Object>();
    }
}
