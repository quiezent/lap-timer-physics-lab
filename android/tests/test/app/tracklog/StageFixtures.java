package app.tracklog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Test code only: the shared test files for every implementation of the stage method (analysis/stage_fixtures).
 *
 * The directory holds INPUTS (recordings with only the GPS and event rows, marking drives, a table of line-crossing cases, and a small
 * description of a pseudo-stage cut out of the real Sepang log) and EXPECTED RESULTS (the stage files, the run files, the comparison
 * figures, the refusals, the crossing times) made by this Java code from those inputs. Another implementation (analysis/stage_compare.py
 * was written from docs/stage-algorithm.md alone) reads the same inputs and must reproduce the expected results.
 *
 *   generate(dir, realLog)  makes the inputs from synthetic roads with fixed seeds, then the expected results from the inputs
 *   check(dir, realLog)     recomputes the expected results from the inputs that are on disk and compares them with the files on disk
 *
 * The expected results are always computed from the files as they are on disk (a recording is read back through LogReader, a stage as
 * it is read back from its text), so what is tested is what a reader of those files has to do.
 */
final class StageFixtures {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    static final long CREATED_MS = 1791885600000L;          // 2026-10-13 10:00 UTC, the wall clock of the first recording

    private StageFixtures() { }

    // ------------------------------------------------------------------ writing the inputs

    private static String fl(double v) {
        return Float.toString((float) v);                    // as the recorder prints a float
    }

    private static String lrow(Fix f, long startUtcMs) {
        return "L," + Math.round(f.t * 1e6) + "," + f.lat + "," + f.lon + ",40.0," + fl(f.speed) + "," + fl(f.bearing) + "," + fl(f.hAcc)
                + ",0.3,5.0," + (startUtcMs + Math.round(f.t * 1000.0)) + ",150";
    }

    /** The text of a reduced session file: header, START, the GPS rows and the extra events in time order, STOP. */
    static String csv(long startUtcMs, String what, List<Fix> fixes, List<String> events) {
        final List<long[]> keys = new ArrayList<long[]>();      // {t_us, index into rows}
        final List<String> rows = new ArrayList<String>();
        long last = 0L;
        for (Fix f : fixes) {
            rows.add(lrow(f, startUtcMs));
            final long us = Math.round(f.t * 1e6);
            keys.add(new long[]{us, rows.size() - 1});
            last = Math.max(last, us);
        }
        for (String e : events) {
            rows.add(e);
            final long us = Long.parseLong(e.split(",")[1]);
            keys.add(new long[]{us, rows.size() - 1});
            last = Math.max(last, us);
        }
        Collections.sort(keys, new Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) {
                return a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]);
            }
        });
        final StringBuilder b = new StringBuilder();
        b.append("# TrackLogger 1.4 (reduced file for the stage fixtures: only the GPS rows and the events. ").append(what).append(")\n");
        b.append("# device,synthetic\n");
        b.append("# start_utc_ms,").append(startUtcMs).append('\n');
        b.append("# rows: L,t_us,lat,lon,alt,speed,bearing,hAcc,sAcc,bAcc,utc_ms,lag_ms | E,t_us,NAME,...\n");
        b.append("E,0,START\n");
        for (long[] k : keys) b.append(rows.get((int) k[1])).append('\n');
        b.append("E,").append(last + 1000000L).append(",STOP\n");
        return b.toString();
    }

    /** Changes the text of a session file the way a real file is sometimes damaged: one row twice, two rows swapped. */
    private static String messUp(String csv, int dupLine, int swapLine) {
        final List<String> lines = new ArrayList<String>(java.util.Arrays.asList(csv.split("\n")));
        lines.add(dupLine + 1, lines.get(dupLine));
        final String x = lines.get(swapLine);
        lines.set(swapLine, lines.get(swapLine + 1));
        lines.set(swapLine + 1, x);
        final StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }

    static List<Fix> shift(List<Fix> in, double dt) {
        final List<Fix> out = new ArrayList<Fix>();
        for (Fix f : in) out.add(new Fix(f.t + dt, f.lat, f.lon, f.speed, f.bearing, f.hAcc));
        return out;
    }

    /** Speed and course made unknown for the fixes with from <= t <= to (the phone gave no Doppler there). */
    static List<Fix> noDoppler(List<Fix> in, double from, double to) {
        final List<Fix> out = new ArrayList<Fix>();
        for (Fix f : in) out.add(f.t >= from && f.t <= to ? new Fix(f.t, f.lat, f.lon, -1.0, -1.0, f.hAcc) : f);
        return out;
    }

    static List<Fix> withAcc(List<Fix> in, int every, int offset, double acc) {
        final List<Fix> out = new ArrayList<Fix>();
        for (int i = 0; i < in.size(); i++) {
            final Fix f = in.get(i);
            out.add(i % every == offset ? new Fix(f.t, f.lat, f.lon, f.speed, f.bearing, acc) : f);
        }
        return out;
    }

    static List<Fix> trip(SynthRoad road, double sFrom, double sTo, double v0, double vEnd, double scale, double tFirst, double stillS,
                          double tailS, double sigma, double lateral, long seed) {
        final SynthRoad.Drive d = road.drive(sFrom, sTo, v0, vEnd, 30.0, 5.5, 3.0, 5.0, scale, null);
        final double tDepart = tFirst + stillS;
        return road.fixes(d, tDepart, tFirst, tDepart + d.duration() + tailS, sigma, 0.7, lateral, new Random(seed));
    }

    static double lastT(List<Fix> f) {
        return f.get(f.size() - 1).t;
    }

    /** What the marking drive of a road gave: the fixes and the two mark events as the recorder writes them. */
    static final class Marked {
        List<Fix> fixes;
        List<String> events = new ArrayList<String>();
        boolean ok;
    }

    /** A marking drive from road position s0 to s1 with the real StageMarker pressing the buttons, as StageTests.mark does. */
    static Marked markingDrive(SynthRoad road, double s0, double s1, double sigma, long seed) {
        final Marked m = new Marked();
        final SynthRoad.Drive d = road.drive(s0, s1, 0.3, 0.3, 28.0, 5.0, 3.0, 5.0, 1.0, null);
        final double tDepart = 25.0;
        m.fixes = road.fixes(d, tDepart, 0.0, tDepart + d.duration() + 15.0, sigma, 0.7, 0.0, new Random(seed));
        final StageMarker mk = new StageMarker();
        boolean started = false;
        for (Fix f : m.fixes) {
            mk.onFix(f);
            if (!started && f.t >= 15.0 && f.t < tDepart - 1.0 && mk.markStart(f.t).length() == 0) {
                started = true;
                m.events.add(String.format(Locale.US, "E,%d,MARK_START,%.7f,%.7f,%.1f", Math.round(f.t * 1e6), mk.startLat(), mk.startLon(), mk.startAcc()));
            }
            if (started && !m.ok && mk.phase() == StageMarker.DRIVING && f.t >= tDepart + d.duration() + 8.0
                    && mk.markFinish(f.t, "x", "x", 1L).length() == 0) {
                m.ok = true;
                m.events.add(String.format(Locale.US, "E,%d,MARK_FINISH,%.7f,%.7f,%.1f", Math.round(f.t * 1e6), mk.finishLat(), mk.finishLon(), mk.finishAcc()));
            }
        }
        return m;
    }

    /** Marks put by hand on fixes (for the drives the real marker would not have accepted). */
    static Marked handMarks(List<Fix> fixes, int iStart, int iFinish) {
        final Marked m = new Marked();
        m.fixes = fixes;
        final Fix a = fixes.get(iStart), b = fixes.get(iFinish);
        m.events.add(String.format(Locale.US, "E,%d,MARK_START,%.7f,%.7f,%.1f", Math.round(a.t * 1e6), a.lat, a.lon, 3.5));
        m.events.add(String.format(Locale.US, "E,%d,MARK_FINISH,%.7f,%.7f,%.1f", Math.round(b.t * 1e6), b.lat, b.lon, 3.5));
        m.ok = true;
        return m;
    }

    // ------------------------------------------------------------------ the inputs

    static SynthRoad zigzag() {
        return new SynthRoad(3.45, 101.74, 90.0, new double[][]{
                {250, 0}, {Math.PI * 15, 15}, {250, 0}, {Math.PI * 15, -15}, {250, 0}, {Math.PI * 15, 15}, {250, 0}, {Math.PI * 15, -15}, {250, 0}}, 400, 300);
    }

    /** The false start of ST.R7 on the given road: over the line, stop, back up, and go again. */
    static List<Fix> falseStart(final SynthRoad road, double sigma, long seed) {
        final double total = road.total;
        final java.util.function.DoubleUnaryOperator sOfT = new java.util.function.DoubleUnaryOperator() {
            @Override public double applyAsDouble(double t) {
                if (t < 30) return -300 + 10 * t;
                if (t < 38) return 70.0 * (t - 30) / 8.0;
                if (t < 50) return 70.0 - 110.0 * (t - 38) / 12.0;
                if (t < 55) return -40.0;
                return Math.min(total + 300.0, -40.0 + 12.0 * (t - 55));
            }
        };
        return road.fixesFn(sOfT, 0.0, 55 + (total + 340.0) / 12.0, sigma, 0.7, new Random(seed));
    }

    /** All the input files, by path relative to the fixtures folder. The real-log part is not here: it is described by real/marks.txt. */
    static Map<String, String> inputs() {
        final Map<String, String> in = new TreeMap<String, String>();
        final long u0 = CREATED_MS;

        // ---- road1: the 4.7 km mountain road of the tests
        final SynthRoad r1 = SynthRoad.mountain();
        final Marked m1 = markingDrive(r1, 0.0, r1.total, 3.0, 101);
        in.put("road1/marking.csv", csv(u0 - 3600000L, "a marking drive: parked, MARK_START, drive, parked, MARK_FINISH", m1.fixes, m1.events));

        // A: a rolling run, then (400 s later) a standing start at the mark and a braking stop at the finish mark
        final List<Fix> a1 = trip(r1, -600.0, r1.total + 300.0, 18.0, 15.0, 1.02, 0.37, 3.0, 30.0, 2.5, 0.0, 201);
        final List<Fix> a2 = shift(trip(r1, 0.0, r1.total, 0.3, 0.3, 1.0, 0.0, 20.0, 30.0, 3.0, 0.0, 202), lastT(a1) + 400.0);
        final List<Fix> a = new ArrayList<Fix>(a1);
        a.addAll(a2);
        in.put("road1/session_20261013_100000.csv", csv(u0, "a rolling run, then a standing start and a braking stop", a, new ArrayList<String>()));

        // B: the false start (cross, stop, back up, go again), with stretches where the phone gave no speed or course, two fixes with a
        // worse or an unknown accuracy, one row twice and two rows out of order
        List<Fix> b = falseStart(r1, 2.5, 203);
        b = noDoppler(b, 31.0, 34.0);
        b = noDoppler(b, 200.0, 215.0);
        b = withAcc(b, 37, 5, 40.0);
        b = withAcc(b, 53, 11, -1.0);
        in.put("road1/session_20261013_103000.csv", messUp(csv(u0 + 1800000L, "a false start; some fixes without speed or course; one row twice, two rows swapped", b, new ArrayList<String>()), 60, 120));

        // C: a run with a 12 s hole in the GPS, then a run that the recording does not outlast (stopped at 55 % of the road)
        final List<Fix> c1all = trip(r1, -600.0, r1.total + 300.0, 18.0, 15.0, 0.98, 0.0, 5.0, 30.0, 2.5, 0.0, 204);
        final SynthRoad.Drive dC = r1.drive(-600.0, r1.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 0.98, null);
        final double tHole = 5.0 + dC.timeAt(2000.0);
        final List<Fix> c1 = new ArrayList<Fix>();
        for (Fix f : c1all) if (!(f.t > tHole && f.t < tHole + 12.0)) c1.add(f);
        final List<Fix> c2all = shift(trip(r1, -600.0, r1.total + 300.0, 18.0, 15.0, 1.01, 0.0, 5.0, 30.0, 2.5, 0.0, 205), lastT(c1) + 300.0);
        final SynthRoad.Drive dC2 = r1.drive(-600.0, r1.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 1.01, null);
        final double tCut = lastT(c1) + 300.0 + 5.0 + dC2.timeAt(0.55 * r1.total);
        final List<Fix> c = new ArrayList<Fix>(c1);
        for (Fix f : c2all) if (f.t <= tCut) c.add(f);
        in.put("road1/session_20261013_110000.csv", csv(u0 + 3600000L, "a run with a 12 s GPS hole, then a run cut short by the end of the recording", c, new ArrayList<String>()));

        // D, E: two more rolling runs at other paces, noise and lanes (for the comparisons)
        in.put("road1/session_20261013_111500.csv", csv(u0 + 4500000L, "a faster rolling run, in the right-hand lane",
                trip(r1, -600.0, r1.total + 300.0, 18.0, 15.0, 1.05, 0.21, 4.0, 30.0, 2.0, -2.0, 206), new ArrayList<String>()));
        in.put("road1/session_20261013_113000.csv", csv(u0 + 5400000L, "a slower rolling run with more GPS noise, in the left-hand lane",
                trip(r1, -600.0, r1.total + 300.0, 18.0, 15.0, 0.95, 0.66, 4.0, 30.0, 3.5, 3.0, 207), new ArrayList<String>()));

        // ---- road2: a hillside road with hairpin legs 30 m apart
        final SynthRoad r2 = zigzag();
        final Marked m2 = markingDrive(r2, 0.0, r2.total, 3.0, 301);
        in.put("road2/marking.csv", csv(u0 + 7200000L, "a marking drive on a hairpin road", m2.fixes, m2.events));
        in.put("road2/session_20261013_120000.csv", csv(u0 + 8000000L, "a run on the hairpin road, 3 m noise, 1.5 m off the centre line",
                trip(r2, -400.0, r2.total + 300.0, 12.0, 10.0, 1.0, 0.5, 3.0, 30.0, 3.0, 1.5, 302), new ArrayList<String>()));
        in.put("road2/session_20261013_121500.csv", csv(u0 + 8900000L, "a run on the hairpin road with no speed or course in any fix",
                noDoppler(trip(r2, -400.0, r2.total + 300.0, 12.0, 10.0, 0.95, 0.5, 3.0, 30.0, 3.0, 0.0, 303), -1e9, 1e9), new ArrayList<String>()));

        // ---- drives that cannot make a stage
        final Marked shortM = markingDrive(r1, 0.0, 200.0, 3.0, 401);
        in.put("refusals/short.csv", csv(u0, "a marking drive of 200 m", shortM.fixes,
                handMarks(shortM.fixes, 16, shortM.fixes.size() - 3).events));
        final SynthRoad uturn = new SynthRoad(3.5, 101.7, 0.0, new double[][]{{300, 0}, {Math.PI * 40, 40}, {300, 0}}, 100, 100);
        final Marked loopM = markingDrive(uturn, 0.0, uturn.total, 3.0, 402);
        in.put("refusals/loop.csv", csv(u0, "a marking drive whose finish is 80 m from its start", loopM.fixes,
                handMarks(loopM.fixes, 16, loopM.fixes.size() - 3).events));
        final List<Fix> parked = r1.fixesFn(new java.util.function.DoubleUnaryOperator() {
            @Override public double applyAsDouble(double t) { return 0.0; }
        }, 0.0, 39.0, 3.0, 0.7, new Random(403));
        in.put("refusals/nomove.csv", csv(u0, "two marks on a car that never moved", parked, handMarks(parked, 10, 35).events));
        final List<Fix> few = new ArrayList<Fix>();
        final Geo g3 = new Geo(3.56, 101.66);
        for (int i = 0; i < 12; i++) few.add(new Fix(i, g3.lat(0.0), g3.lon(0.0), i == 5 ? 2.0 : 0.0, i == 5 ? 40.0 : -1.0, 3.0));
        final Fix farEnd = new Fix(12.0, g3.lat(500.0 * Math.cos(Math.toRadians(40.0))), g3.lon(500.0 * Math.sin(Math.toRadians(40.0))), 0.0, -1.0, 3.0);
        few.add(farEnd);
        in.put("refusals/fewfixes.csv", csv(u0, "one moving fix, right at the start", few, handMarks(few, 2, few.size() - 1).events));

        // ---- the crossing cases
        in.put("gates.csv", gateCases());

        // ---- the ids of the runs found in odd file names
        in.put("run_ids.csv", runIdCases());

        // ---- the real log: which two fixes of it are the marks of the pseudo-stage is filled in by generate()
        return in;
    }

    // ------------------------------------------------------------------ line-crossing cases

    private static final String GATE_HEAD = "# case,lat0,lon0,gate_cx,gate_cy,gate_bearing,half,min_speed,min_forward,ta,lata,lona,spa,bra,tb,latb,lonb,spb,brb,expected_crossing_s_or_none";

    private static String gateCases() {
        final StringBuilder b = new StringBuilder(GATE_HEAD).append('\n');
        final Random rnd = new Random(5);
        for (int i = 0; i < 400; i++) {
            final double lat0 = 2.5 + rnd.nextDouble() * 2.0, lon0 = 101.0 + rnd.nextDouble();
            final Geo g = new Geo(lat0, lon0);
            final double gb = rnd.nextDouble() * 360.0;
            final double cx = (rnd.nextDouble() - 0.5) * 200.0, cy = (rnd.nextDouble() - 0.5) * 200.0;
            final double half = i % 5 == 0 ? 12.0 : 25.0;
            final boolean lapStyle = i % 7 == 0;
            final double minSpeed = lapStyle ? 3.0 : 1.5, minFwd = lapStyle ? 0.0 : 0.25;
            final double v = i % 9 == 0 ? 0.3 + rnd.nextDouble() * 1.5 : 3.0 + rnd.nextDouble() * 35.0;
            final double dt = 0.5 + rnd.nextDouble() * 3.5;
            final double th = i % 4 == 0 ? (rnd.nextDouble() - 0.5) * 360.0 : (rnd.nextDouble() - 0.5) * 60.0;
            final double step = v * dt;
            final double ux = Math.sin(Math.toRadians(gb)), uy = Math.cos(Math.toRadians(gb));
            final double along1 = rnd.nextDouble() * 40.0 - 5.0, across1 = (rnd.nextDouble() - 0.5) * 60.0;
            final double bx = cx + along1 * ux - across1 * uy, by = cy + along1 * uy + across1 * ux;
            final double dir = Math.toRadians(gb + th);
            final double ax = bx - step * Math.sin(dir), ay = by - step * Math.cos(dir);
            double spa = v * (1.0 + 0.03 * rnd.nextGaussian()), spb = v * (1.0 + 0.2 * (rnd.nextDouble() - 0.5));
            double bra = ((gb + th + 3.0 * rnd.nextGaussian()) % 360.0 + 360.0) % 360.0, brb = ((gb + th + 3.0 * rnd.nextGaussian()) % 360.0 + 360.0) % 360.0;
            final int unknown = i % 11;
            if (unknown == 3) { spa = -1.0; bra = -1.0; }                 // a has no Doppler
            if (unknown == 5) { spb = -1.0; brb = -1.0; }                 // b has none
            if (unknown == 7) { bra = -1.0; }                             // speed but no course
            final double ta = 100.0 + rnd.nextDouble() * 1000.0;
            b.append(String.format(Locale.US, "%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,none%n", i,
                    d(lat0), d(lon0), d(cx), d(cy), d(gb), d(half), d(minSpeed), d(minFwd),
                    d(ta), d(g.lat(ay)), d(g.lon(ax)), d(spa), d(bra), d(ta + dt), d(g.lat(by)), d(g.lon(bx)), d(spb), d(brb)));
        }
        return b.toString();
    }

    private static String d(double v) {
        return Double.toString(v);
    }

    private static String gateExpected(String text) {
        final StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("#") || line.trim().length() == 0) {
                out.append(line).append('\n');
                continue;
            }
            final String[] p = line.split(",");
            final Geo g = new Geo(Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            final Gate gate = new Gate(Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]));
            final Fix fa = new Fix(Double.parseDouble(p[9]), Double.parseDouble(p[10]), Double.parseDouble(p[11]), Double.parseDouble(p[12]), Double.parseDouble(p[13]), 3.0);
            final Fix fb = new Fix(Double.parseDouble(p[14]), Double.parseDouble(p[15]), Double.parseDouble(p[16]), Double.parseDouble(p[17]), Double.parseDouble(p[18]), 3.0);
            final Pt a = Pt.make(g, fa, null), b = Pt.make(g, fb, a);
            final double tc = gate.crossing(a, b, Double.parseDouble(p[7]), Double.parseDouble(p[8]));
            for (int i = 0; i < 19; i++) out.append(p[i]).append(',');
            out.append(Double.isNaN(tc) ? "none" : Double.toString(tc)).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ run ids for odd file names

    private static final String ID_HEAD = "# case<TAB>name of the session file<TAB>run id of the 3rd run found in it. In the name, every UTF-16 code unit that is not printable ASCII, and the backslash, is written \\uXXXX (lower-case hex)";

    private static String lenName(char c, int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append((char) (c + (i % 26)));
        return b.toString();
    }

    private static String[] runIdNames() {
        final String s56 = lenName('a', 56), s57 = lenName('a', 57), s64 = lenName('A', 64), s30 = lenName('a', 30);
        return new String[]{
                "session_20261013_095638.csv", "session_20261013_095638b.csv", "session_20261013_095638z.csv", "My Drive.csv", "a b.csv", "a_b.csv",
                "KKB to Fraser (1).csv", "session_x.csv", "", ".csv", "session_.csv", "dir/sub/session_20261013_121500.csv",
                "C:\\Users\\me\\Downloads\\run 3.csv", "caf\u00e9 stage.csv", "emoji \uD83D\uDE97 run.csv", "lone\uD83Dsurrogate.csv",
                s56 + ".csv", s57 + ".csv", s64 + ".csv", s30 + " " + s30 + ".csv", "session_a-b_c.CSV", "weird,name;1.csv", "x.csv.csv",
                "session_session_1.csv", "UPPER_and_lower-123.csv", "trailing space .csv", "tab\there.csv", "12345.csv", "-.csv", "_.csv",
                "\u0645\u0631\u062d\u0628\u0627.csv", "session_\u00e9.csv", "no extension", "session_20261013_095638", "a.csv/"
        };
    }

    static String escapeName(String n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            final char c = n.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '\\') b.append(String.format(Locale.US, "\\u%04x", (int) c)); else b.append(c);
        }
        return b.toString();
    }

    static String unescapeName(String e) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < e.length(); i++) {
            final char c = e.charAt(i);
            if (c == '\\' && i + 5 < e.length() && e.charAt(i + 1) == 'u') {
                b.append((char) Integer.parseInt(e.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String runIdCases() {
        final StringBuilder b = new StringBuilder(ID_HEAD).append('\n');
        final String[] names = runIdNames();
        for (int i = 0; i < names.length; i++) b.append(i).append('\t').append(escapeName(names[i])).append("\t?\n");
        return b.toString();
    }

    private static String runIdExpected(String text) {
        final StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("#") || line.trim().length() == 0) {
                out.append(line).append('\n');
                continue;
            }
            final String[] p = line.split("\t", -1);
            out.append(p[0]).append('\t').append(p[1]).append('\t').append(Scorer.runIdFor(unescapeName(p[1]), 3)).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the expected results

    private static String refusalCode(String message) {
        if (message.startsWith("The car did not move")) return "no_movement";
        if (message.startsWith("Too few GPS fixes")) return "too_few_fixes";
        if (message.startsWith("The drive between the marks")) return "route_too_short";
        if (message.startsWith("The finish mark is only")) return "loop";
        return "other:" + message;
    }

    private static String compareLines(List<RunResult> stored) {
        final List<RunResult> fin = new ArrayList<RunResult>();
        for (RunResult r : stored) if (r.finished) fin.add(r);
        Collections.sort(fin, new Comparator<RunResult>() {
            @Override public int compare(RunResult x, RunResult y) {
                return x.runId.compareTo(y.runId);
            }
        });
        final StringBuilder b = new StringBuilder("# every ordered pair of finished runs of one stage: A against B (positive = A slower)\n");
        for (RunResult x : fin) {
            for (RunResult y : fin) {
                if (x == y) continue;
                final Compare c = Compare.of(x, y);
                b.append(String.format(Locale.US, "pair %s %s%ntotal %.6f%nsigma %.6f%nband %.6f%nsection_m %.1f%n", x.runId, y.runId, c.totalDeltaS, c.sigmaS(), c.sameBandS(), c.sectionM));
                for (Compare.Section s : c.sections) {
                    b.append(String.format(Locale.US, "sec %.1f %.1f %.6f %.6f %.6f%n", s.fromM, s.toM, s.aS, s.bS, s.deltaS()));
                }
                b.append("most_lost ").append(c.mostLost() == null ? "none" : String.format(Locale.US, "%.1f", c.mostLost().fromM)).append('\n');
                b.append("most_gained ").append(c.mostGained() == null ? "none" : String.format(Locale.US, "%.1f", c.mostGained().fromM)).append('\n');
            }
        }
        b.append("end\n");
        return b.toString();
    }

    private static File[] sorted(File dir, final String prefix, final String suffix) {
        final File[] fs = dir.listFiles();
        if (fs == null) return new File[0];
        final List<File> out = new ArrayList<File>();
        for (File f : fs) if (f.getName().startsWith(prefix) && f.getName().endsWith(suffix)) out.add(f);
        Collections.sort(out);
        return out.toArray(new File[0]);
    }

    /** Builds the stage of a marking recording the way a reader of the file has to: the two MARK rows and the fixes between them. */
    static Stage rebuild(File csv, String id, String name) throws IOException {
        return ScenarioST.rebuildFromMarks(csv, id, name, CREATED_MS);
    }

    /** Every expected result, by path relative to the fixtures folder, computed from the input files on disk. */
    static Map<String, String> expected(File dir, File realLog) throws Exception {
        final Map<String, String> out = new TreeMap<String, String>();
        for (File road : sorted(dir, "road", "")) {
            if (!road.isDirectory()) continue;
            final String r = road.getName();
            final Stage built = rebuild(new File(road, "marking.csv"), "st_" + r, "Fixture " + r);
            final String stageText = StageFiles.formatStage(built);
            out.put(r + "/stage.txt", stageText);
            final Stage st = StageFiles.parseStage(stageText);
            final List<RunResult> stored = new ArrayList<RunResult>();
            for (File s : sorted(road, "session_", ".csv")) {
                final LogReader.Log log = LogReader.read(s);
                for (RunResult rr : Scorer.scoreAll(st, log.fixes, s.getName(), (double) log.startUtcMs)) {
                    final String text = StageFiles.formatRun(rr);
                    out.put(r + "/expected/run_" + rr.runId + ".txt", text);
                    stored.add(StageFiles.parseRun(text));
                }
            }
            out.put(r + "/compare_expected.txt", compareLines(stored));
        }
        final StringBuilder ref = new StringBuilder("# the drive in each file cannot make a stage; the reason, as a code\n");
        for (File f : sorted(new File(dir, "refusals"), "", ".csv")) {
            String code;
            try {
                rebuild(f, "x", "x");
                code = "built";
            } catch (IllegalArgumentException e) {
                code = refusalCode(e.getMessage());
            }
            ref.append(f.getName()).append(' ').append(code).append('\n');
        }
        out.put("refusals/expected.txt", ref.toString());
        out.put("gates.csv", gateExpected(new String(Files.readAllBytes(new File(dir, "gates.csv").toPath()), UTF8)));
        out.put("run_ids.csv", runIdExpected(new String(Files.readAllBytes(new File(dir, "run_ids.csv").toPath()), UTF8)));
        if (realLog != null && new File(dir, "real/marks.txt").isFile()) out.putAll(realExpected(dir, realLog));
        return out;
    }

    // ------------------------------------------------------------------ the real log

    private static long markValue(String text, String key) {
        for (String l : text.split("\n")) if (l.startsWith(key + " ")) return Long.parseLong(l.substring(key.length() + 1).trim());
        throw new IllegalArgumentException("no " + key);
    }

    static Map<String, String> realExpected(File dir, File realLog) throws Exception {
        final Map<String, String> out = new TreeMap<String, String>();
        final String marks = new String(Files.readAllBytes(new File(dir, "real/marks.txt").toPath()), UTF8);
        final long usA = markValue(marks, "start_fix_t_us"), usB = markValue(marks, "finish_fix_t_us");
        final LogReader.Log log = LogReader.read(realLog);
        int iA = -1, iB = -1;
        for (int i = 0; i < log.fixes.size(); i++) {
            final double t = log.fixes.get(i).t;
            if (t == usA / 1e6) iA = i;
            if (t == usB / 1e6) iB = i;
        }
        if (iA < 0 || iB <= iA) throw new IllegalArgumentException("the marks of real/marks.txt are not in the recording");
        final Fix mA = log.fixes.get(iA), mB = log.fixes.get(iB);
        final Stage st0 = StageBuilder.build("st_real", "Sepang pseudo-stage", CREATED_MS, mA.lat, mA.lon, mB.lat, mB.lon,
                new ArrayList<Fix>(log.fixes.subList(iA + 1, iB)));
        final String stageText = StageFiles.formatStage(st0);
        out.put("real/stage.txt", stageText);
        final Stage st = StageFiles.parseStage(stageText);
        for (RunResult rr : Scorer.scoreAll(st, log.fixes, realLog.getName(), (double) log.startUtcMs)) {
            out.put("real/expected/run_" + rr.runId + ".txt", StageFiles.formatRun(rr));
        }
        return out;
    }

    /** The pseudo-stage of StageRealTests: from 5 s to 80 s after the third pass of the lap line; returns the text of real/marks.txt. */
    static String realMarks(File realLog) throws IOException {
        final LogReader.Log log = LogReader.read(realLog);
        final Geo geo = new Geo(LapTimer.SEPANG_LAT, LapTimer.SEPANG_LON);
        final Gate line = new Gate(0.0, 0.0, LapTimer.SEPANG_BEARING_DEG, LapTimer.HALF_WIDTH_M);
        final List<Double> passes = new ArrayList<Double>();
        Pt prev = null;
        double lastCross = Double.NaN;
        for (Fix f : log.fixes) {
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
        final double tA = passes.get(2) + 5.0, tB = passes.get(2) + 80.0;
        Fix a = null, bb = null;
        for (Fix f : log.fixes) {
            if (a == null && f.t >= tA) a = f;
            if (bb == null && f.t >= tB) bb = f;
        }
        return "# A stage cut out of the real Sepang recording (a circuit, but the stage code does not know the difference):\n"
                + "# the marks are two of its GPS fixes, the drive is every fix between them, as a marking drive would give.\n"
                + "# They are the fixes 5 s and 80 s after the third pass of the lap line.\n"
                + "log data/sepang-2026-10-13/session_20261013_095638.csv\n"
                + "start_fix_t_us " + Math.round(a.t * 1e6) + "\n"
                + "finish_fix_t_us " + Math.round(bb.t * 1e6) + "\n";
    }

    // ------------------------------------------------------------------ generate and check

    static void generate(File dir, File realLog) throws Exception {
        final Map<String, String> in = inputs();
        if (realLog != null) in.put("real/marks.txt", realMarks(realLog));
        for (Map.Entry<String, String> e : in.entrySet()) write(new File(dir, e.getKey()), e.getValue());
        for (Map.Entry<String, String> e : expected(dir, realLog).entrySet()) write(new File(dir, e.getKey()), e.getValue());
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(UTF8));
    }

    /** "" if the two texts have the same words and the numbers among them agree to tol, otherwise what differs first. */
    static String closeText(String want, String got, double tol) {
        final String[] a = want.trim().split("[\\s,]+"), b = got.trim().split("[\\s,]+");
        if (a.length != b.length) return "token count " + a.length + " vs " + b.length;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(b[i])) continue;
            try {
                final double x = Double.parseDouble(a[i]), y = Double.parseDouble(b[i]);
                if (Math.abs(x - y) <= tol + 1e-12 * Math.max(Math.abs(x), Math.abs(y))) continue;
                return "token " + i + ": " + a[i] + " vs " + b[i];
            } catch (NumberFormatException e) {
                return "token " + i + ": " + a[i] + " vs " + b[i];
            }
        }
        return "";
    }

    static void check(File dir, File realLog) throws Exception {
        final Map<String, String> exp = expected(dir, realLog);
        int ok = 0, bad = 0;
        final StringBuilder why = new StringBuilder();
        for (Map.Entry<String, String> e : exp.entrySet()) {
            final File f = new File(dir, e.getKey());
            if (!f.isFile()) { bad++; why.append(" missing:").append(e.getKey()); continue; }
            final String onDisk = new String(Files.readAllBytes(f.toPath()), UTF8);
            final String diff = closeText(onDisk, e.getValue(), e.getKey().equals("gates.csv") ? 1e-9 : 2e-3);
            if (diff.length() == 0) ok++; else { bad++; why.append(' ').append(e.getKey()).append('(').append(diff).append(')'); }
        }
        // nothing stale: every expected-type file on disk is one the code makes now
        int stale = 0;
        for (File road : sorted(dir, "road", "")) {
            final File[] runs = sorted(new File(road, "expected"), "run_", ".txt");
            for (File f : runs) if (!exp.containsKey(road.getName() + "/expected/" + f.getName())) { stale++; why.append(" stale:").append(f.getName()); }
        }
        if (realLog != null) {
            for (File f : sorted(new File(dir, "real/expected"), "run_", ".txt")) if (!exp.containsKey("real/expected/" + f.getName())) { stale++; why.append(" stale:").append(f.getName()); }
        }
        Check.ok(String.format(Locale.US, "ST.FX1 the committed test files for other implementations (analysis/stage_fixtures) are exactly what this code makes from their inputs: %d files checked%s",
                ok + bad, realLog == null ? " (without the real-log part)" : ""), bad == 0 && stale == 0 && ok > (realLog == null ? 10 : 20), "differences:" + why);

        // the inputs are worth having: the crossing table has both kinds of answer, and the roads give the runs they were made to give
        final String g = exp.get("gates.csv");
        int cross = 0, none = 0;
        for (String l : g.split("\n")) if (!l.startsWith("#") && l.length() > 0) { if (l.endsWith(",none")) none++; else cross++; }
        int finished = 0, notFinished = 0;
        for (Map.Entry<String, String> e : exp.entrySet()) {
            if (!e.getKey().startsWith("road") || !e.getKey().contains("/expected/")) continue;
            if (e.getValue().contains("\nfinished 1\n")) finished++; else notFinished++;
        }
        Check.ok(String.format(Locale.US, "ST.FX2 the test files cover what they should: %d crossings and %d non-crossings in the line table; %d finished runs and %d that did not finish on the two roads; the four drives that cannot make a stage are refused for four different reasons",
                cross, none, finished, notFinished),
                cross >= 100 && none >= 100 && finished >= 7 && notFinished >= 1 && exp.get("refusals/expected.txt").contains("no_movement")
                        && exp.get("refusals/expected.txt").contains("too_few_fixes") && exp.get("refusals/expected.txt").contains("route_too_short")
                        && exp.get("refusals/expected.txt").contains("loop"), "");
    }
}
