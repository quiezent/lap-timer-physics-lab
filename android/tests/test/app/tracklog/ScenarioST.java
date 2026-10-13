package app.tracklog;

import android.media.ToneGenerator;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Scenario ST: the stage feature inside the recorder and the service (release 1.4).
 *
 * StageTests checks the stage maths on a synthetic road and on the real Sepang log, with no Android classes at all. This scenario
 * checks the glue: that the recorder hands its GPS fixes to a stage session and writes the stage events into the CSV, that the result
 * of a run seen live is exactly the result of scoring the written file afterwards, and (ScenarioSTService) that the real
 * RecorderService, on the fake phone, marks a stage, arms, starts and stops the recording by itself at the lines, saves the runs,
 * arms again, and cleans up after itself.
 *
 *   ST.I1x  a recorder with a StageSession: live result == offline result of the file, E rows, DNF and abort on a manual stop,
 *           a stage session that throws cannot disturb the recording, a marking drive on a recorder, two recordings in one second
 *   ST.I2x .. ST.I8x  see ScenarioSTService
 *
 * Time is virtual (see Sim): a drive of 250 s is delivered in a fraction of a second, with the fake clock following the fixes.
 */
final class ScenarioST {

    static SynthRoad road;
    static StageTests.Marking marking;

    private ScenarioST() { }

    // ------------------------------------------------------------------ fixtures

    /** A drive over the road: the physics of the drive (for the exact times) and the fixes a phone would give. */
    static final class Trip {
        SynthRoad.Drive d;
        List<Fix> fixes;
        double tDepart;

        double lastT() {
            return fixes.get(fixes.size() - 1).t;
        }
    }

    /**
     * @param stillS seconds the car stands at sFrom before it sets off (fixes are given throughout)
     * @param tailS  seconds of fixes after the car reached sTo
     */
    static Trip trip(double sFrom, double sTo, double v0, double vEnd, double scale, double tFirst, double stillS, double tailS, double sigma, long seed) {
        final Trip t = new Trip();
        t.d = road.drive(sFrom, sTo, v0, vEnd, 30.0, 5.5, 3.0, 5.0, scale, null);
        t.tDepart = tFirst + stillS;
        t.fixes = road.fixes(t.d, t.tDepart, tFirst, t.tDepart + t.d.duration() + tailS, sigma, 0.7, 0.0, new Random(seed));
        return t;
    }

    /** The normal way to the stage: from 600 m before the start mark, rolling at 18 m/s, through the finish and on for 300 m. */
    static Trip fullRun(double scale, double tFirst, double stillS, double sigma, long seed) {
        return trip(-600.0, road.total + 300.0, 18.0, 15.0, scale, tFirst, stillS, 30.0, sigma, seed);
    }

    /** n fixes of a car parked at road position s, one second apart from tFirst. */
    static List<Fix> parked(double s, double tFirst, int n, double sigma, long seed) {
        final double sConst = s;
        return road.fixesFn(t -> sConst, tFirst, tFirst + n - 1, sigma, 0.7, new Random(seed));
    }

    /** A car that comes toward the start line, gets inside the 400 m zone, turns round and drives away: no start line is crossed. */
    static List<Fix> turnBack(double tFirst, double sigma, long seed) {
        final double t0 = tFirst;
        return road.fixesFn(t -> {
            final double w = t - t0 - 3.0;
            if (w <= 0.0) return -600.0;
            if (w <= 17.0) return -600.0 + 15.0 * w;                      // -345 m at w = 17: 365 m from the start line
            return Math.max(-700.0, -345.0 - 15.0 * (w - 17.0));         // and back, to the end of the road
        }, t0, t0 + 3.0 + 17.0 + 30.0, sigma, 0.7, new Random(seed));
    }

    static double dStart(Stage st, Fix f) {
        return Math.hypot(st.geo.x(f.lon) - st.start.cx, st.geo.y(f.lat) - st.start.cy);
    }

    /** Road position of the centre of a gate. */
    static double gateS(Stage st, Gate g) {
        final double[] xy = StageTests.gateXY(road, st, g);
        return StageTests.roadS(road, xy[0], xy[1]);
    }

    /** What the exact time between the two lines of the stage is for a drive. */
    static double truth(Trip tr, Stage st) {
        return tr.d.timeAt(gateS(st, st.finish)) - tr.d.timeAt(gateS(st, st.start));
    }

    static final class Counter implements StageSession.Listener {
        volatile int started, finished, failed, ended;
        volatile String why = "";

        @Override public void runStarted() { started++; }
        @Override public void runFinished() { finished++; }
        @Override public void runFailed(String w) { failed++; why = w; }
        @Override public void ended() { ended++; }
    }

    static List<String> eNames(CsvAudit a) {
        final List<String> out = new ArrayList<String>();
        for (String[] r : a.kind("E")) out.add(r[2]);
        return out;
    }

    static String firstE(CsvAudit a, String name) {
        for (String[] r : a.kind("E")) if (r[2].equals(name)) return String.join(",", r);
        return null;
    }

    /** "" if two results are identical in every number and in their text form, else what differs first. */
    static String sameRun(RunResult a, RunResult b) {
        if (a == null || b == null) return "one is null";
        if (a.finished != b.finished) return "finished";
        if (Double.compare(a.totalS, b.totalS) != 0) return "totalS " + a.totalS + " vs " + b.totalS;
        if (a.t.length != b.t.length) return "profile length " + a.t.length + " vs " + b.t.length;
        for (int k = 0; k < a.t.length; k++) if (Double.compare(a.t[k], b.t[k]) != 0) return "t[" + k + "] " + a.t[k] + " vs " + b.t[k];
        if (a.startUtcMs != b.startUtcMs) return "startUtcMs " + a.startUtcMs + " vs " + b.startUtcMs;
        if (a.fixes != b.fixes) return "fixes " + a.fixes + " vs " + b.fixes;
        if (Double.compare(a.maxGapS, b.maxGapS) != 0 || Double.compare(a.maxAccM, b.maxAccM) != 0
                || Double.compare(a.offRouteMaxM, b.offRouteMaxM) != 0) return "quality figures";
        if (Double.compare(a.startSpeedMs, b.startSpeedMs) != 0 || Double.compare(a.finishSpeedMs, b.finishSpeedMs) != 0) return "line speeds";
        if (!a.runId.equals(b.runId) || !a.csvName.equals(b.csvName) || !a.statusText.equals(b.statusText)) return "ids or status";
        if (!StageFiles.formatRun(a).equals(StageFiles.formatRun(b))) return "text form differs";
        return "";
    }

    /** The stage rebuilt from a marking recording alone: the two MARK rows and the fixes between them. */
    static Stage rebuildFromMarks(File csv, String id, String name, long created) throws IOException {
        final LogReader.Log log = LogReader.read(csv);
        double tS = Double.NaN, tF = Double.NaN, la = 0, lo = 0, lb = 0, lc = 0;
        for (String line : Files.readAllLines(csv.toPath())) {
            if (!line.startsWith("E,")) continue;
            final String[] p = line.split(",", -1);
            if (p.length == 6 && p[2].equals("MARK_START")) {
                tS = Long.parseLong(p[1]) / 1e6;
                la = Double.parseDouble(p[3]);
                lo = Double.parseDouble(p[4]);
            } else if (p.length == 6 && p[2].equals("MARK_FINISH")) {
                tF = Long.parseLong(p[1]) / 1e6;
                lb = Double.parseDouble(p[3]);
                lc = Double.parseDouble(p[4]);
            }
        }
        // the drive as the app saw it: the fixes in the order they were written, a fix not later than the one before it left out
        final List<Fix> drive = new ArrayList<Fix>();
        double lastT = Double.NEGATIVE_INFINITY;
        for (Fix f : log.fixes) {
            if (!(f.t > lastT)) continue;
            lastT = f.t;
            if (f.t > tS && f.t <= tF) drive.add(f);
        }
        return StageBuilder.build(id, name, created, la, lo, lb, lc, drive);
    }

    /** Largest difference between two stages in metres (gate centres, route vertices) and degrees (gate directions), in a's frame. */
    static double[] stageDiff(Stage a, Stage b) {
        double m = 0;
        final Gate[] ga = {a.start, a.finish}, gb = {b.start, b.finish};
        double deg = 0;
        for (int i = 0; i < 2; i++) {
            final double bx = a.geo.x(b.geo.lon(gb[i].cx)), by = a.geo.y(b.geo.lat(gb[i].cy));
            m = Math.max(m, Math.hypot(bx - ga[i].cx, by - ga[i].cy));
            deg = Math.max(deg, Geo.angleDiff(ga[i].bearingDeg, gb[i].bearingDeg));
        }
        if (a.route.n != b.route.n) return new double[]{1e9, deg};
        for (int i = 0; i < a.route.n; i++) {
            final double bx = a.geo.x(b.geo.lon(b.route.x[i])), by = a.geo.y(b.geo.lat(b.route.y[i]));
            m = Math.max(m, Math.hypot(bx - a.route.x[i], by - a.route.y[i]));
        }
        m = Math.max(m, Math.abs(a.route.length - b.route.length));
        return new double[]{m, deg};
    }

    // ------------------------------------------------------------------ entry

    static void run(File root) throws Exception {
        System.out.println("\n===== ST (integration): the stage feature inside the recorder and the service =====");
        final int errBefore = Harness.errBytes();
        final int loopBefore = Looper.UNCAUGHT.size();
        road = SynthRoad.mountain();
        marking = StageTests.mark(road, 0.0, 5);                   // the stage under test: marked on a noise-free drive
        Check.ok("ST.I0 fixture: the stage for the integration tests exists", marking.stage != null, marking.markStartError + " / " + marking.markFinishError);
        if (marking.stage == null) return;
        try {
            recorderLevel(root);
            ScenarioSTService.run(root);
        } finally {
            SystemClock.OFFSET_NS = 0L;
            RecorderService.autoDisarmMs = RecorderService.AUTO_DISARM_DEFAULT_MS;
            ToneGenerator.FAIL_CREATE = false;
            StageLive.reset();
        }
        Check.ok("ST.I99 no exception printed or thrown anywhere in the stage integration tests (stderr, sensor and main loops, other threads)",
                Harness.errBytes() == errBefore && Looper.UNCAUGHT.size() == loopBefore && Harness.UNCAUGHT_THREADS.isEmpty(),
                "stderr bytes=" + (Harness.errBytes() - errBefore) + " loopThrowables=" + (Looper.UNCAUGHT.size() - loopBefore));
    }

    // ------------------------------------------------------------------ recorder level

    private static final class Rig {
        final FakeSensorManager sm = new FakeSensorManager();
        final FakeLocationManager lm = new FakeLocationManager();
        final Sim sim = new Sim(lm, sm, null);
        final Recorder rec;

        Rig(File dir) {
            rec = new Recorder(new FakeContext(dir, sm, lm));
        }
    }

    static void recorderLevel(File root) throws Exception {
        final File tmp = Files.createTempDirectory(root.toPath(), "st1_").toFile();
        final Stage st = marking.stage;

        // ---------------------------------------------------------------- I1: a full run, live against offline
        {
            final Rig g = new Rig(new File(tmp, "a"));
            final Counter ln = new Counter();
            final StageSession ss = new StageSession(st, ln);
            g.rec.setStageSink(ss);
            g.sim.at(0.0);
            final boolean started = g.rec.start();
            final Trip tr = fullRun(1.0, 1.0, 3.0, 2.5, 11);
            int sent = 0, firstRunning = -1;
            for (Fix f : tr.fixes) {
                g.sim.deliver(f);
                sent++;
                if (firstRunning < 0 && ss.phase() == StageRun.RUNNING) firstRunning = sent;
                if (ss.phase() == StageRun.FINISHED) break;
            }
            final File f = g.rec.getFile();
            final long utc = g.rec.startUtcMs();
            Util.stopWithWatchdog(g.rec, 3000, "ST1");
            final RunResult live = ss.result(Scorer.runIdFor(f.getName(), 1), f.getName(), (double) utc);
            final LogReader.Log log = LogReader.read(f);
            final List<RunResult> off = Scorer.scoreAll(st, log.fixes, f.getName(), (double) log.startUtcMs);
            Check.ok("ST.I1.1 recorder + stage session, 2.5 m GPS noise, rolling start: the run starts, finishes and is over after the 5 s post-roll; the listener is told once each",
                    started && ss.phase() == StageRun.FINISHED && ln.started == 1 && ln.finished == 1 && ln.failed == 0 && ln.ended == 1 && firstRunning > 0,
                    String.format(Locale.US, "phase=%d started=%d finished=%d failed=%d ended=%d, %d fixes sent", ss.phase(), ln.started, ln.finished, ln.failed, ln.ended, sent));
            final String diff = off.size() == 1 ? sameRun(live, off.get(0)) : "offline found " + off.size() + " runs";
            Check.ok(String.format(Locale.US, "ST.I1.2 the run as seen live (%.3f s, %d profile entries) is IDENTICAL, to the last bit, to scoring the written file afterwards",
                    live == null ? Double.NaN : live.totalS, live == null ? 0 : live.t.length), diff.isEmpty(), diff);
            final CsvAudit a = new CsvAudit(f);
            final List<String> names = eNames(a);
            final String fin = firstE(a, "STAGE_FINISH");
            final boolean finOk = fin != null && live != null && Math.abs(Double.parseDouble(fin.split(",")[3]) - live.totalS) < 0.0006;
            Check.ok("ST.I1.3 the CSV has the events in order START, STAGE, STAGE_START, STAGE_FINISH, STOP; STAGE names the stage, STAGE_FINISH says the stage time",
                    names.equals(java.util.Arrays.asList("START", "STAGE", "STAGE_START", "STAGE_FINISH", "STOP"))
                            && firstE(a, "STAGE").endsWith("," + st.id) && finOk && a.badRows().isEmpty() && a.kind("L").size() == sent,
                    names + " " + (fin == null ? "" : fin) + " L=" + a.kind("L").size() + "/" + sent + " bad=" + a.badRows().size());
            Check.ok("ST.I1.4 the last row of the file is STOP, and the stage events did not break the order of the L rows (strictly increasing)",
                    a.data.get(a.data.size() - 1).matches("E,\\d+,STOP") && CsvAudit.monotonic(a.kind("L"), true) == null,
                    a.data.get(a.data.size() - 1));
        }

        // ---------------------------------------------------------------- I1.5: stopped by hand in the middle of the run
        {
            final Rig g = new Rig(new File(tmp, "b"));
            final Counter ln = new Counter();
            final StageSession ss = new StageSession(st, ln);
            g.rec.setStageSink(ss);
            g.sim.at(0.0);
            g.rec.start();
            final Trip tr = fullRun(1.0, 1.0, 3.0, 2.5, 12);
            int sinceStart = 0;
            for (Fix f : tr.fixes) {
                g.sim.deliver(f);
                if (ss.phase() == StageRun.RUNNING) sinceStart++;
                if (sinceStart >= 60) break;
            }
            final File f = g.rec.getFile();
            final long utc = g.rec.startUtcMs();
            Util.stopWithWatchdog(g.rec, 3000, "ST1b");
            final RunResult r = ss.result(Scorer.runIdFor(f.getName(), 1), f.getName(), (double) utc);
            final CsvAudit a = new CsvAudit(f);
            final List<String> names = eNames(a);
            final String dnf = firstE(a, "STAGE_DNF");
            Check.ok("ST.I1.5 stopped by hand 60 s into the run: a STAGE_DNF row comes before STOP, the result is 'did not finish' with the reason, the listener is told",
                    names.equals(java.util.Arrays.asList("START", "STAGE", "STAGE_START", "STAGE_DNF", "STOP")) && dnf != null && dnf.endsWith("the recording was stopped")
                            && r != null && !r.finished && r.statusText.equals("the recording was stopped") && ln.failed == 1 && ln.ended == 1 && !ss.aborted(),
                    names + " " + dnf + " failed=" + ln.failed + " ended=" + ln.ended + " result=" + (r == null ? "null" : r.statusText));
        }

        // ---------------------------------------------------------------- I1.6: stopped by hand before the start line
        {
            final Rig g = new Rig(new File(tmp, "c"));
            final Counter ln = new Counter();
            final StageSession ss = new StageSession(st, ln);
            g.rec.setStageSink(ss);
            g.sim.at(0.0);
            g.rec.start();
            final Trip tr = fullRun(1.0, 1.0, 3.0, 2.5, 13);
            for (int i = 0; i < 8; i++) g.sim.deliver(tr.fixes.get(i));
            final File f = g.rec.getFile();
            Util.stopWithWatchdog(g.rec, 3000, "ST1c");
            final CsvAudit a = new CsvAudit(f);
            final List<String> names = eNames(a);
            Check.ok("ST.I1.6 stopped by hand before the start line: STAGE_ABORT before STOP, the session says 'aborted' and has no result to keep",
                    names.equals(java.util.Arrays.asList("START", "STAGE", "STAGE_ABORT", "STOP")) && ss.aborted()
                            && ss.result("x", f.getName(), 0.0) == null && ln.failed == 1 && ln.ended == 1,
                    names + " aborted=" + ss.aborted());
        }

        // ---------------------------------------------------------------- I1.7: a stage session that throws
        {
            final Rig g = new Rig(new File(tmp, "d"));
            final StageSink bad = new StageSink() {
                @Override public void attach(Out out) { throw new IllegalStateException("attach"); }
                @Override public void onFix(Fix f, Out out) { throw new IllegalStateException("fix"); }
                @Override public void onTick(double nowS, Out out) { throw new IllegalStateException("tick"); }
                @Override public void onStop(double nowS, Out out) { throw new IllegalStateException("stop"); }
            };
            final int err0 = Harness.errBytes(), loop0 = Looper.UNCAUGHT.size();
            g.rec.setStageSink(bad);
            g.sim.at(0.0);
            final boolean started = g.rec.start();
            final Trip tr = fullRun(1.0, 1.0, 3.0, 2.5, 14);
            int sent = 0;
            for (int i = 0; i < 40; i++) {
                g.sim.deliver(tr.fixes.get(i));
                sent++;
            }
            Thread.sleep(1200);                                  // let the once-a-second tick run, and throw, at least once
            final File f = g.rec.getFile();
            Util.stopWithWatchdog(g.rec, 3000, "ST1d");
            final CsvAudit a = new CsvAudit(f);
            Check.ok("ST.I1.7 a stage session that throws in attach, onFix, onTick and onStop cannot disturb the recording: all " + sent + " fixes in the file, STOP last, nothing printed",
                    started && a.kind("L").size() == sent && a.data.get(a.data.size() - 1).matches("E,\\d+,STOP") && a.badRows().isEmpty()
                            && Harness.errBytes() == err0 && Looper.UNCAUGHT.size() == loop0 && !Recorder.LIVE.failed,
                    "L=" + a.kind("L").size() + "/" + sent + " bad=" + a.badRows().size() + " stderr=" + (Harness.errBytes() - err0));
        }

        // ---------------------------------------------------------------- I1.8: marking a stage on a recorder
        {
            final Rig g = new Rig(new File(tmp, "e"));
            final MarkSession ms = new MarkSession();
            StageLive.reset();
            g.rec.setStageSink(ms);
            g.sim.at(-1.0);
            g.rec.start();
            final StageTests.Marking m = StageTests.mark(road, 2.5, 7);
            boolean startedMark = false, finishedMark = false, movingRefused = false, earlyRefused = false, movingCanMark = false;
            String errStart = "?", errFinish = "?", errMoving = "", errEarly = "";
            for (Fix f : m.fixes) {
                g.sim.deliver(f);
                if (!startedMark && f.t >= 12.0 && StageLive.canMark) {
                    errStart = ms.markStart();
                    startedMark = errStart.isEmpty();
                    errEarly = ms.markFinish("st_live", "Live stage", 1L);             // parked, 0 m driven: refused, and the recording goes on
                    earlyRefused = !errEarly.isEmpty() && ms.stage() == null;
                } else if (startedMark && !finishedMark && f.speed > 5.0) {
                    if (StageLive.canMark) movingCanMark = true;
                    if (!movingRefused) {
                        errMoving = ms.markFinish("st_live", "Live stage", 1L);        // while moving: refused
                        movingRefused = !errMoving.isEmpty() && ms.stage() == null;
                    }
                } else if (startedMark && !finishedMark && f.t >= m.tDepart + m.drive.duration() + 8.0 && StageLive.canMark) {
                    errFinish = ms.markFinish("st_live", "Live stage", 1L);
                    finishedMark = errFinish.isEmpty();
                    break;
                }
            }
            final File f = g.rec.getFile();
            Util.stopWithWatchdog(g.rec, 3000, "ST1e");
            final CsvAudit a = new CsvAudit(f);
            final List<String> names = eNames(a);
            Check.ok("ST.I1.8a marking drive on a recorder: MARK START accepted while parked, a MARK FINISH 0 m later and one while moving are refused with words (" + errEarly + " / " + errMoving + "), the final one is accepted",
                    startedMark && finishedMark && earlyRefused && movingRefused && !movingCanMark && ms.stage() != null,
                    "start='" + errStart + "' early='" + errEarly + "' moving='" + errMoving + "' finish='" + errFinish + "' canMarkWhileMoving=" + movingCanMark);
            Check.ok("ST.I1.8b the CSV has START, MARK_START, MARK_FINISH, STOP in that order, each mark with latitude, longitude and accuracy",
                    names.equals(java.util.Arrays.asList("START", "MARK_START", "MARK_FINISH", "STOP")) && a.badRows().isEmpty(),
                    names + " bad=" + a.badRows().size());
            if (ms.stage() != null && names.contains("MARK_FINISH")) {
                final Stage rebuilt = rebuildFromMarks(f, "st_live", "Live stage", 1L);
                final double[] d = stageDiff(ms.stage(), rebuilt);
                Check.ok(String.format(Locale.US, "ST.I1.8c the stage made live and the stage rebuilt from the file alone (two MARK rows + the fixes between) agree to %.1f cm and %.3f degrees (the marks are written to 7 decimals)",
                        d[0] * 100.0, d[1]), d[0] < 0.05 && d[1] < 0.05, String.format(Locale.US, "worst %.4f m, %.4f deg", d[0], d[1]));
            }
        }

        // ---------------------------------------------------------------- I1.9: two recordings in the same second keep both files
        {
            final File dir = new File(tmp, "f");
            final List<Recorder> recs = new ArrayList<Recorder>();
            final List<FakeLocationManager> lms = new ArrayList<FakeLocationManager>();
            for (int i = 0; i < 3; i++) {
                final FakeSensorManager sm = new FakeSensorManager();
                final FakeLocationManager lm = new FakeLocationManager();
                lms.add(lm);
                final Recorder r = new Recorder(new FakeContext(dir, sm, lm));
                r.start();
                recs.add(r);
            }
            final List<String> names = new ArrayList<String>();
            boolean allExist = true;
            for (Recorder r : recs) {
                names.add(r.getFile().getName());
                Util.stopWithWatchdog(r, 3000, "ST1f");
                allExist &= r.getFile().exists() && r.getFile().length() > 0;
            }
            final java.util.Set<String> uniq = new java.util.HashSet<String>(names);
            Check.ok("ST.I1.9 three recordings started within the same moment get three different files, none overwritten", uniq.size() == 3 && allExist, names.toString());
        }
    }
}
