package app.tracklog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;

/**
 * Test code only: the shared test files for every implementation of the pin method (analysis/stage_fixtures/pins, described in the
 * README there). Like StageFixtures, the folder holds INPUTS (recordings, texts to read, places to snap, pairs of pins) and EXPECTED
 * RESULTS made by this Java code from the inputs; analysis/pin_compare.py was written from section 11 of docs/stage-algorithm.md alone
 * and must reproduce them.
 *
 *   generate(dir, realLog)  makes the new recordings and the lists of what to ask, then the results
 *   check(dir, realLog)     recomputes the results from the inputs on disk and compares them with the files on disk (PN.FX1)
 *
 * The results are always computed from the files as they are on disk: a recording is read back through LogReader, a stage is parsed
 * back from its text before it is scored, a coordinate is read from the text as printed.
 *
 * Lines that name the real Sepang recording (REAL) are only checked when it is given (-Dreal.log=...); without it the line on disk is
 * taken as it is.
 */
final class PinFixtures {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    static final String REAL = "REAL";
    private static final long U0 = StageFixtures.CREATED_MS;

    private static final String COORDS_HEAD = "# text<TAB>result. In the text, every UTF-16 code unit that is not printable ASCII, and the backslash, is written \\uXXXX (lower-case hex). "
            + "result: 'ok LAT LON' (9 decimals) or 'refused'";
    private static final String TRACKS_HEAD = "# recording<TAB>vertices<TAB>length_m of the track (section 11.1); no_drive twice if the recording holds no drive";
    private static final String PASSES_HEAD = "# recording<TAB>lat<TAB>lon<TAB>the passes of that place on the track (section 11.2): s:offset joined by ';' (3 decimals each), or none";
    private static final String CASES_HEAD = "# case<TAB>recording<TAB>start<TAB>finish<TAB>result: ok, or the category of the refusal (section 11.3 to 11.6). "
            + "start and finish: pos:METRES (a position along the track) or at:LAT,LON (a place)";

    private PinFixtures() { }

    // ------------------------------------------------------------------ the recordings made for this folder

    /** Fixes of a car whose position along the road and sideways offset (to the left, metres) are functions of time, one per second. */
    private static List<Fix> fixesLane(SynthRoad rd, DoubleUnaryOperator sOfT, DoubleUnaryOperator leftOfT, double tLast, double sigma, double rho, Random rnd) {
        final List<Fix> out = new ArrayList<Fix>();
        double nx = 0, ny = 0;
        final double inno = Math.sqrt(1.0 - rho * rho);
        for (double t = 0.0; t <= tLast + 1e-9; t += 1.0) {
            final double s = sOfT.applyAsDouble(t);
            final double v = (sOfT.applyAsDouble(t + 0.05) - sOfT.applyAsDouble(t - 0.05)) / 0.1;
            final double[] p = rd.pos(s);
            final double hd = Math.toRadians(rd.heading(s));
            final double left = leftOfT.applyAsDouble(t);
            final double px = p[0] - Math.cos(hd) * left, py = p[1] + Math.sin(hd) * left;
            if (sigma > 0) {
                nx = rho * nx + inno * sigma * rnd.nextGaussian();
                ny = rho * ny + inno * sigma * rnd.nextGaussian();
            }
            final double dir = rd.heading(s) + (v < 0 ? 180.0 : 0.0);
            final double sp = Math.abs(v);
            final double brg = sp >= 0.8 ? (dir % 360.0 + 360.0) % 360.0 : -1.0;
            out.add(new Fix(t, rd.geo.lat(py + ny), rd.geo.lon(px + nx), sp, brg, 3.5));
        }
        return out;
    }

    /** Every `every`-th fix (from offset) moved northM metres north and reported with accuracy acc. */
    private static List<Fix> displaced(List<Fix> in, int every, int offset, double northM, double acc) {
        final List<Fix> out = new ArrayList<Fix>();
        for (int i = 0; i < in.size(); i++) {
            final Fix f = in.get(i);
            out.add(i % every == offset ? new Fix(f.t, f.lat + Math.toDegrees(northM / Geo.R), f.lon, f.speed, f.bearing, acc) : f);
        }
        return out;
    }

    private static final double[] STACK_R = {6.0, 8.0, 10.0, 15.0};

    static String stackName(boolean atFinish, double r) {
        return "pins/stack_" + (atFinish ? "finish_" : "start_") + (int) Math.round(2.0 * r) + ".csv";
    }

    static SynthRoad stackRoad(boolean atFinish, double r) {
        return atFinish ? PinTests.stack(1500.0, 200.0, r, 200.0) : PinTests.stack(300.0, 100.0, r, 1500.0);
    }

    /** The new recordings, by path relative to the fixtures folder. */
    static Map<String, String> recordings() {
        final Map<String, String> in = new TreeMap<String, String>();
        final SynthRoad r1 = SynthRoad.mountain();
        final List<String> noEvents = new ArrayList<String>();

        // up the road and back down the other lane, 14 m to the side: two passes of every place, the second the wrong way round
        {
            final double v = 14.0, tot = r1.total, up = (tot + 600.0) / v, stand = 20.0;
            final DoubleUnaryOperator sOfT = new DoubleUnaryOperator() {
                @Override public double applyAsDouble(double t) {
                    return t <= up ? -300.0 + v * t : (t <= up + stand ? tot + 300.0 : Math.max(-300.0, tot + 300.0 - v * (t - up - stand)));
                }
            };
            final DoubleUnaryOperator left = new DoubleUnaryOperator() {
                @Override public double applyAsDouble(double t) {
                    return t <= up + stand ? 0.0 : 14.0;
                }
            };
            final List<Fix> f = fixesLane(r1, sOfT, left, up + stand + (tot + 600.0) / v + 8.0, 2.5, 0.7, new Random(501));
            in.put("pins/outback.csv", StageFixtures.csv(U0 + 10000000L, "up the mountain road and back down the other lane, 14 m to the side of the way up", f, noEvents));
        }

        // a long stand before and after: GPS noise while parked, with no speed before the drive (kept) and a known speed of about 0 after it (left out)
        {
            List<Fix> pk = StageFixtures.trip(r1, 0.0, r1.total, 0.3, 0.3, 1.0, 0.0, 120.0, 120.0, 3.0, 0.0, 502);
            pk = StageFixtures.noDoppler(pk, 0.0, 119.0);
            in.put("pins/parked.csv", StageFixtures.csv(U0 + 11000000L, "two minutes parked before the drive with no speed reported, two minutes after it with a speed of about 0", pk, noEvents));
        }

        // poor fixes: every 7th is 40 m out and says so (accuracy 45 m: left out), every 13th has no accuracy (kept), a stretch with no speed or course (kept)
        {
            List<Fix> bf = StageFixtures.trip(r1, -400.0, r1.total + 300.0, 18.0, 15.0, 1.0, 0.0, 10.0, 30.0, 3.0, 0.0, 503);
            bf = displaced(bf, 7, 2, 40.0, 45.0);
            final List<Fix> tmp = new ArrayList<Fix>();
            for (int i = 0; i < bf.size(); i++) {
                final Fix f = bf.get(i);
                tmp.add(i % 13 == 5 && f.hAcc <= 30.0 ? new Fix(f.t, f.lat, f.lon, f.speed, f.bearing, -1.0) : f);
            }
            bf = StageFixtures.noDoppler(tmp, 100.0, 140.0);
            in.put("pins/badfix.csv", StageFixtures.csv(U0 + 12000000L, "every 7th fix 40 m out with accuracy 45 m, every 13th with no accuracy, 40 s with no speed or course", bf, noEvents));
        }

        // 9.6 m of driving (a track of four vertices, but no stage), and a recording of a car that never moved
        {
            final Geo g = new Geo(3.4000, 101.7000);
            final List<Fix> s9 = new ArrayList<Fix>();
            for (int i = 0; i < 6; i++) s9.add(new Fix(i, g.lat(0.0), g.lon(0.0), 0.05, -1.0, 3.5));
            for (int i = 0; i < 4; i++) s9.add(new Fix(6 + i, g.lat(0.0), g.lon(3.2 * i), -1.0, -1.0, 3.5));
            for (int i = 0; i < 6; i++) s9.add(new Fix(10 + i, g.lat(0.0), g.lon(9.6), 0.05, -1.0, 3.5));
            in.put("pins/short9.csv", StageFixtures.csv(U0 + 13000000L, "9.6 m of driving with no speed reported, between two stands", s9, noEvents));
            final List<Fix> still = new ArrayList<Fix>();
            for (int i = 0; i < 60; i++) still.add(new Fix(i, g.lat(0.3 * Math.sin(i)), g.lon(0.3 * Math.cos(i)), 0.05, -1.0, 3.5));
            in.put("pins/nodrive.csv", StageFixtures.csv(U0 + 14000000L, "a minute parked, a speed of about 0 in every fix", still, noEvents));
        }

        // switchbacks whose legs are 12, 16, 20 and 30 m apart: at the end of a 1.9 km stage, and right after the start
        long k = 15000000L;
        for (int i = 0; i < STACK_R.length; i++) {
            final double r = STACK_R[i];
            final SynthRoad rf = stackRoad(true, r);
            in.put(stackName(true, r), StageFixtures.csv(U0 + k, "switchbacks, the legs " + (int) (2 * r) + " m apart, at the end of the road",
                    PinTests.drive(rf, -250.0, rf.total + 150.0, 12.0, 1.0, 20.0, 20.0, 0.0, 6), noEvents));
            k += 1000000L;
            final SynthRoad rs = stackRoad(false, r);
            in.put(stackName(false, r), StageFixtures.csv(U0 + k, "switchbacks, the legs " + (int) (2 * r) + " m apart, right after the start of the road",
                    PinTests.drive(rs, -250.0, rs.total + 150.0, 12.0, 1.0, 20.0, 20.0, 0.0, 7), noEvents));
            k += 1000000L;
        }

        // straight roads, for the edges of the rules about holes, breaks and the cleaning of a recording (all at 15 m/s, one fix a second)
        in.put("pins/join_wait300.csv", StageFixtures.csv(U0 + 30000000L, "a straight road driven to 900 m, nothing for exactly 300 s, then from 1500 m on", joined(300.0, 600.0), noEvents));
        in.put("pins/join_wait301.csv", StageFixtures.csv(U0 + 31000000L, "a straight road driven to 900 m, nothing for 301 s, then from 1500 m on", joined(301.0, 600.0), noEvents));
        in.put("pins/join_jump99.csv", StageFixtures.csv(U0 + 32000000L, "a straight road driven to 900 m, nothing for 400 s, then from 999.5 m on (99.5 m further)", joined(400.0, 99.5), noEvents));
        in.put("pins/join_jump101.csv", StageFixtures.csv(U0 + 33000000L, "a straight road driven to 900 m, nothing for 400 s, then from 1000.5 m on (100.5 m further)", joined(400.0, 100.5), noEvents));
        in.put("pins/join_still.csv", StageFixtures.csv(U0 + 34000000L, "a straight road driven to 900 m, nothing for 400 s with the car standing, then on from the same place", joined(400.0, 0.0), noEvents));
        in.put("pins/edges.csv", StageFixtures.csv(U0 + 35000000L, "twelve fixes along a line, each testing an edge of the cleaning: accuracy 30.0, 30.1 and unknown, speed 0.99, 1.0 and unknown, steps of 2.9, 3.1, 2.95 and 3.05 m", edgeFixes(), noEvents));
        {
            final List<Fix> v2 = PinTests.line(STRAIGHT, 0.0, 20.0, 15.0, 1.0, 0.0), v3 = PinTests.line(STRAIGHT, 0.0, 40.0, 15.0, 1.0, 0.0);
            in.put("pins/vertices2.csv", StageFixtures.csv(U0 + 36000000L, "two fixes 20 m apart", v2.subList(0, 2), noEvents));
            in.put("pins/vertices3.csv", StageFixtures.csv(U0 + 37000000L, "three fixes 20 m apart", v3.subList(0, 3), noEvents));
        }
        in.put("pins/lanes.csv", StageFixtures.csv(U0 + 38000000L, "a straight road east along y = 0, then back west along y = 11 m, with no noise", laneFixes(), noEvents));
        return in;
    }

    private static final Geo STRAIGHT = new Geo(3.5000, 101.7000);

    /** Two drives of a straight road in one recording: the first to 900 m (at 60 s), the second from 900 m + jumpM, waitS seconds after the first. */
    static List<Fix> joined(double waitS, double jumpM) {
        final List<Fix> fx = new ArrayList<Fix>(PinTests.line(STRAIGHT, 0.0, 900.0, 15.0, 1.0, 0.0));
        fx.addAll(PinTests.line(STRAIGHT, 900.0 + jumpM, 2700.0 + jumpM, 15.0, 1.0, 60.0 + waitS));
        return fx;
    }

    /**
     * Twelve fixes east along a line. Kept in the track: 0, 1 (accuracy 30.0), 4 (speed 1.0), 5 (no speed), 6 (no accuracy), 7, 9 (3.1 m
     * from the last vertex) and 11 (3.05 m from it); left out: 2 (accuracy 30.1), 3 (speed 0.99), 8 (2.9 m from the last vertex), 10 (2.95 m).
     */
    static List<Fix> edgeFixes() {
        final double[] x = {0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 142.9, 143.1, 146.05, 146.15};
        final double[] acc = {5.0, 30.0, 30.1, 5.0, 5.0, 5.0, -1.0, 5.0, 5.0, 5.0, 5.0, 5.0};
        final double[] sp = {10.0, 10.0, 10.0, 0.99, 1.0, -1.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0};
        final List<Fix> out = new ArrayList<Fix>();
        for (int i = 0; i < x.length; i++) out.add(new Fix(i, STRAIGHT.lat(0.0), STRAIGHT.lon(x[i]), sp[i], 90.0, acc[i]));
        return out;
    }

    /** East along y = 0 for 990 m, then west along y = 11 m (to the north) for 990 m, at 15 m/s, one fix a second, without noise. */
    static List<Fix> laneFixes() {
        final List<Fix> out = new ArrayList<Fix>();
        for (int i = 0; i <= 66; i++) out.add(new Fix(i, STRAIGHT.lat(0.0), STRAIGHT.lon(15.0 * i), 15.0, 90.0, 5.0));
        for (int i = 0; i <= 66; i++) out.add(new Fix(67 + i, STRAIGHT.lat(11.0), STRAIGHT.lon(990.0 - 15.0 * i), 15.0, 270.0, 5.0));
        return out;
    }

    // ------------------------------------------------------------------ what a recording is, by its path

    private static final class Loader {
        final File dir;
        final File realLog;
        private final Map<String, LogReader.Log> logs = new HashMap<String, LogReader.Log>();
        private final Map<String, Object> tracks = new HashMap<String, Object>();       // a Track, or the String "no_drive"

        Loader(File dir, File realLog) {
            this.dir = dir;
            this.realLog = realLog;
        }

        boolean available(String rec) {
            return !rec.equals(REAL) || realLog != null;
        }

        File file(String rec) {
            return rec.equals(REAL) ? realLog : new File(dir, rec);
        }

        /** The name the recorder would have given the file (the run ids are made from it). */
        String csvName(String rec) {
            return file(rec).getName();
        }

        LogReader.Log log(String rec) throws IOException {
            LogReader.Log l = logs.get(rec);
            if (l == null) {
                l = LogReader.read(file(rec));
                logs.put(rec, l);
            }
            return l;
        }

        /** The track of the recording, or null if it holds no drive. */
        Track track(String rec) throws IOException {
            Object o = tracks.get(rec);
            if (o == null) {
                try {
                    o = Track.of(log(rec).fixes);
                } catch (PinBuilder.Refused e) {
                    o = e.category;
                }
                tracks.put(rec, o);
            }
            return o instanceof Track ? (Track) o : null;
        }
    }

    // ------------------------------------------------------------------ the lists of what to ask

    private static String pos(double s) {
        return String.format(Locale.US, "pos:%.3f", s);
    }

    private static String at(double[] ll) {
        return String.format(Locale.US, "at:%.7f,%.7f", ll[0], ll[1]);
    }

    private static String atRaw(double lat, double lon) {
        return String.format(Locale.US, "at:%.7f,%.7f", lat, lon);
    }

    private static void line(StringBuilder b, String... cols) {
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) b.append('\t');
            b.append(cols[i]);
        }
        b.append('\n');
    }

    private static void addCase(StringBuilder b, String id, String rec, String start, String finish) {
        line(b, id, rec, start, finish, "?");
    }

    private static void addPlace(StringBuilder b, String rec, double[] ll) {
        line(b, rec, String.format(Locale.US, "%.7f", ll[0]), String.format(Locale.US, "%.7f", ll[1]), "?");
    }

    /** The texts to read: those of PinTests, and a few hundred places written eight ways. */
    private static String coordsList() {
        final StringBuilder b = new StringBuilder(COORDS_HEAD).append('\n');
        for (String t : PinTests.READ_TEXT) line(b, StageFixtures.escapeName(t), "?");
        for (String t : PinTests.READ_REFUSED) line(b, StageFixtures.escapeName(t), "?");
        final Random rnd = new Random(2026);
        for (int i = 0; i < 30; i++) {
            final double lat = Math.rint((rnd.nextDouble() * 2 - 1) * 89.0 * 1e7) / 1e7, lon = Math.rint((rnd.nextDouble() * 2 - 1) * 179.0 * 1e7) / 1e7;
            final String[] forms = {
                    String.format(Locale.US, "%.7f, %.7f", lat, lon),
                    String.format(Locale.US, "%.7f %.7f", lat, lon),
                    String.format(Locale.US, "%.6f° %s, %.6f° %s", Math.abs(lat), lat < 0 ? "S" : "N", Math.abs(lon), lon < 0 ? "W" : "E"),
                    String.format(Locale.US, "%s %.6f %s %.6f", lat < 0 ? "S" : "N", Math.abs(lat), lon < 0 ? "W" : "E", Math.abs(lon)),
                    PinTests.dms(lat, "N", "S") + " " + PinTests.dms(lon, "E", "W"),
                    String.format(Locale.US, "https://www.google.com/maps/@%.7f,%.7f,15z", lat, lon),
                    String.format(Locale.US, "https://www.google.com/maps?q=%.7f%%2C%.7f", lat, lon),
                    String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(Start)", lat, lon, lat, lon)
            };
            for (String f : forms) line(b, StageFixtures.escapeName(f), "?");
        }
        return b.toString();
    }

    /** {distance at the first, distance at the second} of the first two vertices of the track whose times are between lo and hi seconds apart (the longest if hi is huge). */
    private static double[] vertexGap(Track tr, double lo, double hi) {
        int best = -1;
        double bestDt = 0.0;
        for (int i = 0; i + 1 < tr.n; i++) {
            final double dt = tr.t[i + 1] - tr.t[i];
            if (dt >= lo && dt <= hi && dt > bestDt) {
                best = i;
                bestDt = dt;
            }
        }
        if (best < 0) throw new IllegalStateException("no gap of " + lo + " to " + hi + " s in the track");
        return new double[]{tr.route.s[best], tr.route.s[best + 1]};
    }

    private static String[] allRecordings(boolean withReal) {
        final List<String> l = new ArrayList<String>();
        l.add("road1/session_20261013_100000.csv");
        l.add("road1/session_20261013_103000.csv");
        l.add("road1/session_20261013_110000.csv");
        l.add("road1/session_20261013_111500.csv");
        l.add("road1/session_20261013_113000.csv");
        l.add("road2/session_20261013_120000.csv");
        l.add("road2/session_20261013_121500.csv");
        l.add("pins/outback.csv");
        l.add("pins/parked.csv");
        l.add("pins/badfix.csv");
        l.add("pins/short9.csv");
        l.add("pins/nodrive.csv");
        for (String j : JOINS) l.add(j);
        l.add(EDGES);
        l.add(V2);
        l.add(V3);
        l.add(LANES);
        for (double r : STACK_R) {
            l.add(stackName(true, r));
            l.add(stackName(false, r));
        }
        if (withReal) l.add(REAL);
        return l.toArray(new String[0]);
    }

    private static final String A = "road1/session_20261013_100000.csv", B = "road1/session_20261013_103000.csv",
            C = "road1/session_20261013_110000.csv", D = "road1/session_20261013_111500.csv",
            H1 = "road2/session_20261013_120000.csv", H2 = "road2/session_20261013_121500.csv",
            OUT = "pins/outback.csv", PARKED = "pins/parked.csv", BAD = "pins/badfix.csv", S9 = "pins/short9.csv", NODRIVE = "pins/nodrive.csv",
            J300 = "pins/join_wait300.csv", J301 = "pins/join_wait301.csv", JJ99 = "pins/join_jump99.csv", JJ101 = "pins/join_jump101.csv", JSTILL = "pins/join_still.csv",
            EDGES = "pins/edges.csv", V2 = "pins/vertices2.csv", V3 = "pins/vertices3.csv", LANES = "pins/lanes.csv";
    private static final String[] JOINS = {J300, J301, JJ99, JJ101, JSTILL};

    /** The lists of tracks, places and pairs, as files with a ? where a result goes. */
    static Map<String, String> lists(File dir, File realLog) throws Exception {
        final Map<String, String> out = new TreeMap<String, String>();
        final Loader ld = new Loader(dir, realLog);
        final SynthRoad r1 = SynthRoad.mountain(), r2 = StageFixtures.zigzag();

        out.put("pins/coords.tsv", coordsList());

        final StringBuilder tracks = new StringBuilder(TRACKS_HEAD).append('\n');
        for (String rec : allRecordings(realLog != null)) line(tracks, rec, "?", "?");
        out.put("pins/tracks.tsv", tracks.toString());

        // ---- places
        final StringBuilder ps = new StringBuilder(PASSES_HEAD).append('\n');
        final double[] sides = {0.0, 8.0, -30.0, 50.0, 66.0};
        for (double s : new double[]{0.0, 1000.0, 2500.0, 4000.0}) for (double left : sides) addPlace(ps, D, PinTests.place(r1, s, left));
        addPlace(ps, D, PinTests.place(r1, 2000.0, 5000.0));                 // far away: none
        for (double s : new double[]{600.0, 3200.0}) for (double left : new double[]{0.0, 8.0}) addPlace(ps, A, PinTests.place(r1, s, left));
        addPlace(ps, B, PinTests.place(r1, 30.0, 0.0));                      // where the false start went forwards, back and forwards again
        addPlace(ps, B, PinTests.place(r1, 2000.0, 0.0));
        for (double s : new double[]{1000.0, 2000.0, 3500.0}) for (double left : new double[]{0.0, 3.0, 7.0, -3.0, 20.0}) addPlace(ps, OUT, PinTests.place(r1, s, left));
        for (double s : new double[]{300.0, 273.6, 700.0}) addPlace(ps, H1, PinTests.place(r2, s, 0.0));
        addPlace(ps, S9, new double[]{3.4000, 101.7000 + Math.toDegrees(3.2 / (Geo.R * Math.cos(Math.toRadians(3.4))))});
        // the straight roads: on the line and 59.5 and 60.5 m from it (the snap limit is 60 m); two lanes 11 m apart, a place 0.75 and 0.25 m north
        // of the first (the second lane is 10.25 and 10.75 m from it: within 10 m of the nearest pass, or not)
        addPlace(ps, EDGES, new double[]{STRAIGHT.lat(0.0), STRAIGHT.lon(100.0)});
        addPlace(ps, EDGES, new double[]{STRAIGHT.lat(59.5), STRAIGHT.lon(100.0)});
        addPlace(ps, EDGES, new double[]{STRAIGHT.lat(60.5), STRAIGHT.lon(100.0)});
        addPlace(ps, LANES, new double[]{STRAIGHT.lat(0.75), STRAIGHT.lon(500.0)});
        addPlace(ps, LANES, new double[]{STRAIGHT.lat(0.25), STRAIGHT.lon(500.0)});
        addPlace(ps, J301, new double[]{STRAIGHT.lat(0.0), STRAIGHT.lon(300.0)});
        addPlace(ps, J301, new double[]{STRAIGHT.lat(0.0), STRAIGHT.lon(1200.0)});
        for (double r : new double[]{6.0, 15.0}) {
            final SynthRoad rf = stackRoad(true, r);
            addPlace(ps, stackName(true, r), PinTests.place(rf, 1500.0 + 200.0 + Math.PI * r + 100.0, 0.0));      // the middle of the second leg, between the other two
            addPlace(ps, stackName(true, r), PinTests.place(rf, 50.0, 0.0));
        }
        if (realLog != null) {
            final LogReader.Log log = ld.log(REAL);
            final double[] pl = PinTests.realStretchPlaces(log, 8.0);
            addPlace(ps, REAL, new double[]{pl[0], pl[1]});
            addPlace(ps, REAL, new double[]{pl[2], pl[3]});
            addPlace(ps, REAL, new double[]{LapTimer.SEPANG_LAT, LapTimer.SEPANG_LON});
        }
        out.put("pins/passes.tsv", ps.toString());

        // ---- pairs of pins
        final StringBuilder cs = new StringBuilder(CASES_HEAD).append('\n');

        // D: one clean drive of the road, in the right-hand lane
        final Track td = ld.track(D);
        final double sd1 = PinTests.trackS(td, r1, 600.0), sd2 = PinTests.trackS(td, r1, 3200.0), sdShort = PinTests.trackS(td, r1, 10.0);
        final double len = td.length;
        addCase(cs, "d_pos_ok", D, pos(sd1), pos(sd2));
        addCase(cs, "d_at_ok", D, at(PinTests.place(r1, 600.0, 0.0)), at(PinTests.place(r1, 3200.0, 0.0)));
        addCase(cs, "d_at_off8", D, at(PinTests.place(r1, 600.0, 8.0)), at(PinTests.place(r1, 3200.0, -8.0)));
        addCase(cs, "d_at_off50", D, at(PinTests.place(r1, 600.0, 50.0)), at(PinTests.place(r1, 3200.0, -50.0)));
        addCase(cs, "d_nopass_start", D, at(PinTests.place(r1, 600.0, 66.0)), at(PinTests.place(r1, 3200.0, 0.0)));
        addCase(cs, "d_nopass_finish", D, at(PinTests.place(r1, 600.0, 0.0)), at(PinTests.place(r1, 3200.0, 66.0)));
        addCase(cs, "d_nopass_both", D, at(PinTests.place(r1, 600.0, 66.0)), at(PinTests.place(r1, 3200.0, -66.0)));
        addCase(cs, "d_mixed_ps", D, pos(sd1), at(PinTests.place(r1, 3200.0, 8.0)));
        addCase(cs, "d_mixed_sp", D, at(PinTests.place(r1, 600.0, -8.0)), pos(sd2));
        addCase(cs, "d_wrong_pos", D, pos(sd2), pos(sd1));
        addCase(cs, "d_wrong_at", D, at(PinTests.place(r1, 3200.0, 0.0)), at(PinTests.place(r1, 600.0, 0.0)));
        addCase(cs, "d_same_pos", D, pos(sd1), pos(sd1));
        addCase(cs, "d_start_edge_ok", D, pos(20.010), pos(sd2));
        addCase(cs, "d_start_edge_bad", D, pos(19.990), pos(sd2));
        addCase(cs, "d_start_negative", D, pos(-5.0), pos(sd2));
        addCase(cs, "d_end_edge_ok", D, pos(sd1), pos(Math.floor((len - 20.01) * 1000.0) / 1000.0));
        addCase(cs, "d_end_edge_bad", D, pos(sd1), pos(Math.ceil((len - 19.99) * 1000.0) / 1000.0));
        addCase(cs, "d_end_beyond", D, pos(sd1), pos(len + 5.0));
        addCase(cs, "d_short_299", D, pos(sdShort), pos(sdShort + 299.9));
        addCase(cs, "d_short_301", D, pos(sdShort), pos(sdShort + 300.1));
        addCase(cs, "d_loop_pos", D, pos(PinTests.trackS(td, r1, 1200.0)), pos(PinTests.trackS(td, r1, 1900.0)));
        addCase(cs, "d_loop_at", D, at(PinTests.place(r1, 1200.0, 0.0)), at(PinTests.place(r1, 1900.0, 0.0)));
        addCase(cs, "d_long_pos", D, pos(sd1 - 500.0), pos(len - 25.0));
        addCase(cs, "d_start_20", D, pos(20.000), pos(sd2));
        addCase(cs, "d_short_straight", D, pos(1000.0), pos(1100.0));              // too short AND too close: the first rule gives the answer
        double exact = Double.NaN;
        for (double a = 100.0; a < len - 400.0 && Double.isNaN(exact); a += 50.0) {
            try {
                PinBuilder.build("x", "x", 0L, td, PinBuilder.Pin.atPosition(a), PinBuilder.Pin.atPosition(a + 300.0));
                exact = a;
            } catch (PinBuilder.Refused e) {
                // try the next
            }
        }
        addCase(cs, "d_short_exact", D, pos(exact), pos(exact + 300.0));           // 300 m of road exactly is long enough
        addCase(cs, "d_short_just", D, pos(exact), pos(exact + 299.999));
        addCase(cs, "d_nan_start", D, pos(Double.NaN), pos(sd2));
        addCase(cs, "d_nan_finish", D, pos(sd1), pos(Double.NaN));
        addCase(cs, "d_nan_both", D, pos(Double.NaN), pos(Double.NaN));
        addCase(cs, "d_inf_finish", D, pos(sd1), pos(Double.POSITIVE_INFINITY));
        addCase(cs, "d_ninf_start", D, pos(Double.NEGATIVE_INFINITY), pos(sd2));
        addCase(cs, "d_nan_start_nopass_finish", D, pos(Double.NaN), at(PinTests.place(r1, 3200.0, 66.0)));    // the start pin is looked at first
        addCase(cs, "d_nopass_start_nan_finish", D, at(PinTests.place(r1, 600.0, 66.0)), pos(Double.NaN));

        // A: the road twice the same way (the cars joins the end of the first drive to the start of the second by a straight line)
        final Track ta = ld.track(A);
        final List<Track.Pass> a600 = ta.passes(PinTests.place(r1, 600.0, 0.0)[0], PinTests.place(r1, 600.0, 0.0)[1]);
        final List<Track.Pass> a3200 = ta.passes(PinTests.place(r1, 3200.0, 0.0)[0], PinTests.place(r1, 3200.0, 0.0)[1]);
        addCase(cs, "a_at_twice", A, at(PinTests.place(r1, 600.0, 0.0)), at(PinTests.place(r1, 3200.0, 0.0)));
        addCase(cs, "a_at_twice_off8", A, at(PinTests.place(r1, 600.0, 8.0)), at(PinTests.place(r1, 3200.0, 8.0)));
        addCase(cs, "a_pos_first", A, pos(a600.get(0).s), pos(a3200.get(0).s));
        if (a600.size() > 1 && a3200.size() > 1) addCase(cs, "a_pos_second", A, pos(a600.get(1).s), pos(a3200.get(1).s));
        addCase(cs, "a_at_reversed", A, at(PinTests.place(r1, 3200.0, 0.0)), at(PinTests.place(r1, 600.0, 0.0)));   // the only pair that goes forwards joins the two drives
        if (a3200.size() > 1) addCase(cs, "a_pos_across_break", A, pos(a600.get(0).s), pos(a3200.get(1).s));

        // B: a false start (forwards, back, forwards again), a row twice, two rows swapped, stretches with no speed or course
        addCase(cs, "b_at_ok", B, at(PinTests.place(r1, 300.0, 0.0)), at(PinTests.place(r1, 4000.0, 0.0)));
        addCase(cs, "b_at_false_start", B, at(PinTests.place(r1, 30.0, 0.0)), at(PinTests.place(r1, 4000.0, 0.0)));
        addCase(cs, "b_pos_ok", B, pos(PinTests.trackS(ld.track(B), r1, 300.0)), pos(PinTests.trackS(ld.track(B), r1, 4000.0)));

        // C: a 12 s hole in the GPS, then a run that the recording does not outlast
        addCase(cs, "c_at_before_hole", C, at(PinTests.place(r1, 500.0, 0.0)), at(PinTests.place(r1, 1800.0, 0.0)));
        addCase(cs, "c_at_across_hole", C, at(PinTests.place(r1, 1500.0, 0.0)), at(PinTests.place(r1, 3500.0, 0.0)));
        addCase(cs, "c_at_end", C, at(PinTests.place(r1, 2500.0, 0.0)), at(PinTests.place(r1, 4500.0, 0.0)));
        // the 13 s hole (a pin in it cannot be timed) and the 300 s wait between the two drives (not more than 300 s: not a break)
        final Track tcc = ld.track(C);
        final double[] hole = vertexGap(tcc, 12.5, 14.0), wait = vertexGap(tcc, 100.0, 1e9);
        final double hs = hole[0], he = hole[1], fin = he + 1200.0, sta = hs - 1200.0;
        addCase(cs, "c_s_before", C, pos(hs - 5.0), pos(fin));
        addCase(cs, "c_s_in_first", C, pos(hs + 5.0), pos(fin));
        addCase(cs, "c_s_in_middle", C, pos((hs + he) / 2.0), pos(fin));
        addCase(cs, "c_s_in_last", C, pos(he - 5.0), pos(fin));
        addCase(cs, "c_s_after", C, pos(he + 5.0), pos(fin));
        addCase(cs, "c_f_before", C, pos(sta), pos(hs - 5.0));
        addCase(cs, "c_f_in_first", C, pos(sta), pos(hs + 5.0));
        addCase(cs, "c_f_in_middle", C, pos(sta), pos((hs + he) / 2.0));
        addCase(cs, "c_f_in_last", C, pos(sta), pos(he - 5.0));
        addCase(cs, "c_f_after", C, pos(sta), pos(he + 5.0));
        addCase(cs, "c_across_300", C, pos(wait[0] - 800.0), pos(wait[1] + 800.0));        // exactly 300 s: not a break, whatever the distance
        addCase(cs, "c_at_skip_hole", C, at(PinTests.place(r1, 2000.0, 0.0)), at(PinTests.place(r1, 2500.0, 0.0)));   // the first pass of the start place is in the hole: the second drive is used

        // the hairpin road, legs 30 m apart and 250 m long: two points are 150 m or more apart in a straight line only near its two ends
        addCase(cs, "h_pos_ok", H1, pos(PinTests.trackS(ld.track(H1), r2, 20.0)), pos(PinTests.trackS(ld.track(H1), r2, 1400.0)));
        addCase(cs, "h_at_ok", H1, at(PinTests.place(r2, 20.0, 0.0)), at(PinTests.place(r2, 1400.0, 0.0)));
        addCase(cs, "h_at_in_turn", H1, at(PinTests.place(r2, 273.6, 0.0)), at(PinTests.place(r2, 1288.0, 0.0)));
        addCase(cs, "h_at_loop", H1, at(PinTests.place(r2, 100.0, 0.0)), at(PinTests.place(r2, 720.0, 0.0)));
        addCase(cs, "h_nodoppler_ok", H2, at(PinTests.place(r2, 20.0, 0.0)), at(PinTests.place(r2, 1400.0, 0.0)));

        // up and back down the other lane: places on the way up are 14 m from the way down
        final Track to = ld.track(OUT);
        final double[] o1000 = PinTests.place(r1, 1000.0, 7.0), o3500 = PinTests.place(r1, 3500.0, 7.0);
        final List<Track.Pass> p1000 = to.passes(o1000[0], o1000[1]), p3500 = to.passes(o3500[0], o3500[1]);
        addCase(cs, "o_up_at", OUT, at(PinTests.place(r1, 1000.0, 0.0)), at(PinTests.place(r1, 3500.0, 0.0)));
        addCase(cs, "o_both_lanes_at", OUT, at(o1000), at(o3500));
        addCase(cs, "o_reversed_at", OUT, at(o3500), at(o1000));
        if (p1000.size() > 1 && p3500.size() > 1) addCase(cs, "o_down_pos", OUT, pos(p3500.get(1).s), pos(p1000.get(1).s));
        addCase(cs, "o_up_pos", OUT, pos(p1000.get(0).s), pos(p3500.get(0).s));
        addCase(cs, "o_down_lane_at", OUT, at(PinTests.place(r1, 3500.0, 14.0)), at(PinTests.place(r1, 1000.0, 14.0)));

        // two drives of a straight road in one recording
        final double lat0 = STRAIGHT.lat(0.0);
        final String at300 = atRaw(lat0, STRAIGHT.lon(300.0)), at800 = atRaw(lat0, STRAIGHT.lon(800.0)), at2000 = atRaw(lat0, STRAIGHT.lon(2000.0));
        addCase(cs, "j300_pos", J300, pos(300.0), pos(2000.0));
        addCase(cs, "j300_at", J300, at300, at2000);
        addCase(cs, "j301_pos", J301, pos(300.0), pos(2000.0));
        addCase(cs, "j301_at", J301, at300, at2000);
        addCase(cs, "j301_first", J301, pos(300.0), pos(800.0));
        addCase(cs, "j301_first_at", J301, at300, at800);
        addCase(cs, "j301_second", J301, pos(1600.0), pos(2300.0));
        addCase(cs, "j301_start_in_hole", J301, pos(1200.0), pos(2000.0));
        addCase(cs, "j301_finish_in_hole", J301, pos(300.0), pos(1200.0));
        addCase(cs, "j301_start_at_hole", J301, atRaw(lat0, STRAIGHT.lon(1200.0)), at2000);
        addCase(cs, "j301_before_hole", J301, pos(890.0), pos(2000.0));
        addCase(cs, "j301_after_hole", J301, pos(300.0), pos(1510.0));
        addCase(cs, "jj99_pos", JJ99, pos(300.0), pos(2000.0));
        addCase(cs, "jj101_pos", JJ101, pos(300.0), pos(2000.0));
        addCase(cs, "jstill_pos", JSTILL, pos(300.0), pos(2000.0));
        addCase(cs, "jstill_at", JSTILL, at300, at2000);

        addCase(cs, "p_pos_ok", PARKED, pos(PinTests.trackS(ld.track(PARKED), r1, 100.0)), pos(PinTests.trackS(ld.track(PARKED), r1, 4000.0)));
        addCase(cs, "p_at_ok", PARKED, at(PinTests.place(r1, 100.0, 0.0)), at(PinTests.place(r1, 4000.0, 0.0)));
        addCase(cs, "x_pos_ok", BAD, pos(PinTests.trackS(ld.track(BAD), r1, 100.0)), pos(PinTests.trackS(ld.track(BAD), r1, 4000.0)));
        addCase(cs, "x_at_ok", BAD, at(PinTests.place(r1, 100.0, 0.0)), at(PinTests.place(r1, 4000.0, 0.0)));
        addCase(cs, "s_pos_near", S9, pos(3.0), pos(6.0));
        addCase(cs, "n_pos_nodrive", NODRIVE, pos(0.0), pos(100.0));

        // the guard: switchbacks 12, 16, 20 and 30 m apart under the finish line, and under the start line
        for (double r : STACK_R) {
            final int w = (int) Math.round(2.0 * r);
            final SynthRoad rf = stackRoad(true, r), rs = stackRoad(false, r);
            final Track tf = ld.track(stackName(true, r)), tsk = ld.track(stackName(false, r));
            final double finishMid = 1500.0 + 200.0 + Math.PI * r + 200.0 + Math.PI * r + 100.0;
            addCase(cs, "gf_" + w + "_pos", stackName(true, r), pos(PinTests.trackS(tf, rf, 50.0)), pos(PinTests.trackS(tf, rf, finishMid)));
            addCase(cs, "gf_" + w + "_at", stackName(true, r), at(PinTests.place(rf, 50.0, 0.0)), at(PinTests.place(rf, finishMid, 0.0)));
            addCase(cs, "gs_" + w + "_pos", stackName(false, r), pos(PinTests.trackS(tsk, rs, 350.0)), pos(PinTests.trackS(tsk, rs, rs.total - 150.0)));
            addCase(cs, "gs_" + w + "_at", stackName(false, r), at(PinTests.place(rs, 350.0, 0.0)), at(PinTests.place(rs, rs.total - 150.0, 0.0)));
        }

        // the real recording
        if (realLog != null) {
            final LogReader.Log log = ld.log(REAL);
            final Track tr = ld.track(REAL);
            final double[] pl = PinTests.realStretchPlaces(log, 8.0);
            addCase(cs, "real_at_stretch", REAL, atRaw(pl[0], pl[1]), atRaw(pl[2], pl[3]));
            addCase(cs, "real_at_reversed", REAL, atRaw(pl[2], pl[3]), atRaw(pl[0], pl[1]));
            final List<Track.Pass> lapLine = tr.passes(LapTimer.SEPANG_LAT, LapTimer.SEPANG_LON);
            addCase(cs, "real_pos_lap", REAL, pos(lapLine.get(2).s), pos(lapLine.get(3).s));
            addCase(cs, "real_pos_wrong", REAL, pos(30000.0), pos(10000.0));
            addCase(cs, "real_pos_short", REAL, pos(30000.0), pos(30200.0));
            addCase(cs, "real_pos_start_edge", REAL, pos(5.0), pos(1000.0));
            addCase(cs, "real_pos_two_km", REAL, pos(30000.0), pos(32000.0));
            addCase(cs, "real_pos_six_km", REAL, pos(50000.0), pos(56000.0));
        }
        out.put("pins/cases.tsv", cs.toString());
        return out;
    }

    // ------------------------------------------------------------------ the results

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), UTF8);
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(UTF8));
    }

    private static boolean comment(String l) {
        return l.startsWith("#") || l.trim().length() == 0;
    }

    private static String coordsExpected(String text) {
        final StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) {
            if (l.length() == 0) continue;
            if (comment(l)) {
                b.append(l).append('\n');
                continue;
            }
            final String[] p = l.split("\t", -1);
            final Coords.Result r = Coords.parse(StageFixtures.unescapeName(p[0]));
            line(b, p[0], r.ok ? String.format(Locale.US, "ok %.9f %.9f", r.lat, r.lon) : "refused");
        }
        return b.toString();
    }

    private static String tracksExpected(String text, Loader ld) throws IOException {
        final StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) {
            if (l.length() == 0) continue;
            if (comment(l)) {
                b.append(l).append('\n');
                continue;
            }
            final String[] p = l.split("\t", -1);
            if (!ld.available(p[0])) {
                b.append(l).append('\n');
                continue;
            }
            final Track tr = ld.track(p[0]);
            if (tr == null) line(b, p[0], "no_drive", "no_drive");
            else line(b, p[0], String.valueOf(tr.n), String.format(Locale.US, "%.3f", tr.length));
        }
        return b.toString();
    }

    private static String passesExpected(String text, Loader ld) throws IOException {
        final StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) {
            if (l.length() == 0) continue;
            if (comment(l)) {
                b.append(l).append('\n');
                continue;
            }
            final String[] p = l.split("\t", -1);
            if (!ld.available(p[0])) {
                b.append(l).append('\n');
                continue;
            }
            final Track tr = ld.track(p[0]);
            final List<Track.Pass> ps = tr.passes(Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            final StringBuilder q = new StringBuilder();
            for (Track.Pass x : ps) {
                if (q.length() > 0) q.append(';');
                q.append(String.format(Locale.US, "%.3f:%.3f", x.s, x.offsetM));
            }
            line(b, p[0], p[1], p[2], ps.isEmpty() ? "none" : q.toString());
        }
        return b.toString();
    }

    private static PinBuilder.Pin pin(String spec) {
        if (spec.startsWith("pos:")) return PinBuilder.Pin.atPosition(Double.parseDouble(spec.substring(4)));
        if (spec.startsWith("at:")) {
            final String[] q = spec.substring(3).split(",");
            return PinBuilder.Pin.atPlace(Double.parseDouble(q[0]), Double.parseDouble(q[1]));
        }
        throw new IllegalArgumentException("bad pin " + spec);
    }

    /** The cases file with results, and the stage and the first run of every case that makes a stage, into out (paths relative to dir). */
    private static String casesExpected(String text, Loader ld, Map<String, String> out) throws IOException {
        final StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) {
            if (l.length() == 0) continue;
            if (comment(l)) {
                b.append(l).append('\n');
                continue;
            }
            final String[] p = l.split("\t", -1);
            if (!ld.available(p[1])) {
                b.append(l).append('\n');
                continue;
            }
            String result;
            final Track tr = ld.track(p[1]);
            if (tr == null) {
                result = "no_drive";
            } else {
                try {
                    final PinBuilder.Result r = PinBuilder.build("pin_" + p[0], "Pin case " + p[0], U0, tr, pin(p[2]), pin(p[3]));
                    final String stageText = StageFiles.formatStage(r.stage);
                    out.put("pins/stages/" + p[0] + ".txt", stageText);
                    final Stage st = StageFiles.parseStage(stageText);
                    final LogReader.Log log = ld.log(p[1]);
                    final List<RunResult> runs = Scorer.scoreAll(st, log.fixes, ld.csvName(p[1]), (double) log.startUtcMs);
                    if (!runs.isEmpty()) out.put("pins/runs/" + p[0] + ".txt", StageFiles.formatRun(runs.get(0)));
                    result = "ok";
                } catch (PinBuilder.Refused e) {
                    result = e.category;
                }
            }
            line(b, p[0], p[1], p[2], p[3], result);
        }
        return b.toString();
    }

    /** Every expected result, by path relative to the fixtures folder, computed from the input files on disk. */
    static Map<String, String> expected(File dir, File realLog) throws Exception {
        final Map<String, String> out = new TreeMap<String, String>();
        final File pins = new File(dir, "pins");
        final Loader ld = new Loader(dir, realLog);
        out.put("pins/coords.tsv", coordsExpected(read(new File(pins, "coords.tsv"))));
        out.put("pins/tracks.tsv", tracksExpected(read(new File(pins, "tracks.tsv")), ld));
        out.put("pins/passes.tsv", passesExpected(read(new File(pins, "passes.tsv")), ld));
        out.put("pins/cases.tsv", casesExpected(read(new File(pins, "cases.tsv")), ld, out));
        return out;
    }

    // ------------------------------------------------------------------ generate and check

    static void generate(File dir, File realLog) throws Exception {
        for (Map.Entry<String, String> e : recordings().entrySet()) write(new File(dir, e.getKey()), e.getValue());
        // stale results from an earlier generation go
        for (String sub : new String[]{"pins/stages", "pins/runs"}) {
            final File[] old = new File(dir, sub).listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        for (Map.Entry<String, String> e : lists(dir, realLog).entrySet()) write(new File(dir, e.getKey()), e.getValue());
        for (Map.Entry<String, String> e : expected(dir, realLog).entrySet()) write(new File(dir, e.getKey()), e.getValue());
    }

    private static Set<String> realCaseIds(File dir) throws IOException {
        final Set<String> ids = new HashSet<String>();
        final File f = new File(dir, "pins/cases.tsv");
        if (!f.isFile()) return ids;
        for (String l : read(f).split("\n")) {
            if (comment(l)) continue;
            final String[] p = l.split("\t", -1);
            if (p.length > 1 && p[1].equals(REAL)) ids.add(p[0]);
        }
        return ids;
    }

    static void check(File dir, File realLog) throws Exception {
        if (!new File(dir, "pins/cases.tsv").isFile()) {
            Check.ok("PN.FX1 the committed test files for the pin method (analysis/stage_fixtures/pins) are there", false, "pins/cases.tsv is missing");
            return;
        }
        final Map<String, String> exp = expected(dir, realLog);
        int ok = 0, bad = 0;
        final StringBuilder why = new StringBuilder();
        for (Map.Entry<String, String> e : exp.entrySet()) {
            final File f = new File(dir, e.getKey());
            if (!f.isFile()) {
                bad++;
                why.append(" missing:").append(e.getKey());
                continue;
            }
            final String onDisk = read(f);
            final String diff = StageFixtures.closeText(onDisk, e.getValue(), e.getKey().equals("pins/coords.tsv") ? 5e-10 : 2e-3);
            if (diff.length() == 0) {
                ok++;
            } else {
                bad++;
                why.append(' ').append(e.getKey()).append('(').append(diff).append(')');
            }
        }
        final Set<String> real = realCaseIds(dir);
        int stale = 0;
        for (String sub : new String[]{"stages", "runs"}) {
            final File[] fs = new File(dir, "pins/" + sub).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                final String id = f.getName().replace(".txt", "");
                if (!exp.containsKey("pins/" + sub + "/" + f.getName()) && !(realLog == null && real.contains(id))) {
                    stale++;
                    why.append(" stale:").append(sub).append('/').append(f.getName());
                }
            }
        }
        // the recordings made for this folder are there (they are inputs: they are not made again here, since the last digit of a sine may differ between machines)
        for (String rec : recordings().keySet()) {
            if (!new File(dir, rec).isFile()) {
                bad++;
                why.append(" missing recording:").append(rec);
            } else {
                ok++;
            }
        }
        Check.ok(String.format(Locale.US, "PN.FX1 the committed test files for the pin method (analysis/stage_fixtures/pins) are exactly what this code makes from their inputs: %d files checked%s",
                ok + bad, realLog == null ? " (without the real-log lines)" : ""), bad == 0 && stale == 0 && ok > 40, "differences:" + why);

        // the inputs are worth having: every kind of answer is there
        final Map<String, Integer> cat = new TreeMap<String, Integer>();
        for (String l : exp.get("pins/cases.tsv").split("\n")) {
            if (comment(l)) continue;
            final String[] p = l.split("\t", -1);
            final String r = p[p.length - 1];
            cat.put(r, cat.containsKey(r) ? cat.get(r) + 1 : 1);
        }
        int refusedTexts = 0, readTexts = 0;
        for (String l : exp.get("pins/coords.tsv").split("\n")) {
            if (comment(l)) continue;
            if (l.endsWith("\trefused")) refusedTexts++; else readTexts++;
        }
        int zero = 0, one = 0, many = 0;
        for (String l : exp.get("pins/passes.tsv").split("\n")) {
            if (comment(l)) continue;
            final String[] p = l.split("\t", -1);
            if (p[3].equals("none")) zero++; else if (p[3].contains(";")) many++; else one++;
        }
        final String[] want = {"ok", "wrong_way", "near_end", "too_short", "loop", "hole_start", "hole_finish", "break", "guard_start", "guard_finish", "no_pass_start", "no_pass_finish", "no_drive"};
        boolean all = true;
        for (String w : want) if (!cat.containsKey(w)) all = false;
        Check.ok(String.format(Locale.US, "PN.FX2 the pin test files cover what they should: every answer for a pair of pins %s; %d texts read and %d refused; places with no pass (%d), one pass (%d) and several (%d)",
                cat, readTexts, refusedTexts, zero, one, many),
                all && cat.get("ok") >= 30 && readTexts >= 100 && refusedTexts >= 40 && zero >= 3 && one >= 10 && many >= 3, "");
    }
}
