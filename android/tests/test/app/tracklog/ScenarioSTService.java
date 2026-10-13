package app.tracklog;

import android.app.PendingIntent;
import android.content.Intent;
import android.media.ToneGenerator;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The stage feature in the real RecorderService on the fake phone (see ScenarioST). The service is driven only the way the screens
 * drive it, with intents, and fed only what the GPS would give it; what it does about the recorder, the notification, the wake lock,
 * the beeps and the files is looked at from outside.
 *
 *   ST.I2x  marking a new stage end to end
 *   ST.I3x  armed: two runs in one arming, compared; the screen rule while moving; the files
 *   ST.I4x  an approach that comes to nothing, a run stopped by hand, a run cut short by the system
 *   ST.I5x  intents that do not fit the state; disarm; the beep setting; a phone that cannot beep; auto-disarm; GPS off
 */
final class ScenarioSTService {

    private ScenarioSTService() { }

    static Intent in(ServiceHost h, String action) {
        return new Intent(h.app, RecorderService.class).setAction(action);
    }

    static void arm(ServiceHost h, String id) {
        final Intent i = in(h, RecorderService.ACTION_ARM);
        if (id != null) i.putExtra(RecorderService.EXTRA_STAGE_ID, id);
        h.startService(i);
    }

    static StageStore storeOf(ServiceHost h) {
        return new StageStore(new File(h.root, "internal/stages"));
    }

    /** Fixes seen after which the screen would have been allowed to show a number although the car was moving: must stay 0. */
    static int movingShown;

    static void deliver(Sim sim, Fix f) throws InterruptedException {
        sim.deliver(f);
        if (f.speed > 3.0 && StageLive.numbersAllowed()) movingShown++;
    }

    /** Delivers the fixes until stop() says so after a fix; returns how many were delivered. */
    static int play(Sim sim, List<Fix> fixes, java.util.function.BooleanSupplier stop) throws InterruptedException {
        int n = 0;
        for (Fix f : fixes) {
            deliver(sim, f);
            n++;
            if (stop != null && stop.getAsBoolean()) break;
        }
        return n;
    }

    /** The service has stopped itself and its onDestroy has run. */
    static boolean idle(ServiceHost h, long ms) throws InterruptedException {
        final boolean ok = h.awaitIdle(ms);
        h.flushMain();
        h.flushMain();
        return ok;
    }

    static boolean isArmedListener(ServiceHost h) {
        return h.lm.listener instanceof ArmedWatcher;
    }

    /** True when the service is armed again (GPS only) after a run, with nothing left running. */
    static boolean rearmed(ServiceHost h, int runs) {
        return StageLive.state == StageLive.ARMED && StageLive.runsThisArming == runs && !Recorder.LIVE.saving && !Recorder.LIVE.recording
                && isArmedListener(h) && h.sm.regs.isEmpty();
    }

    static String text(File f) throws Exception {
        return StageStore.read(f);
    }

    static File runFile(StageStore store, String stageId, String runId) {
        return new File(new File(store.root(), stageId), "run_" + runId + ".txt");
    }

    static RunResult byId(List<RunResult> runs, String id) {
        for (RunResult r : runs) if (r.runId.equals(id)) return r;
        return null;
    }

    static void run(File root) throws Exception {
        movingShown = 0;
        marking(root);
        armed(root);
        odd(root);
        notificationTarget(root);
        Check.ok("ST.I5.11 across all the armed, recording and marking flows, the screen was never allowed to show a number while the car was moving",
                movingShown == 0, "fixes with a number allowed while moving: " + movingShown);
    }

    // ------------------------------------------------------------------ marking a stage end to end

    static void marking(File root) throws Exception {
        final ServiceHost h = new ServiceHost(root, "st_mark");
        final Sim sim = new Sim(h.lm, h.sm, h);
        final StageStore store = storeOf(h);
        StageLive.reset();
        StageLive.markedStageId = "";
        final long seq0 = StageLive.markedSeq;
        ToneGenerator.TONES.clear();
        final StageTests.Marking m = StageTests.mark(ScenarioST.road, 2.5, 7);

        sim.at(0.0);
        h.startService(in(h, RecorderService.ACTION_MARK_NEW));
        final boolean rec = h.awaitRecording(3000);
        final RecorderService svc = h.current;
        Check.ok("ST.I2.1 MARK_NEW: the service goes to the foreground, takes the wake lock and starts recording; the screen is told 'waiting for MARK START' and may not mark yet",
                rec && StageLive.state == StageLive.MARK_START && !StageLive.canMark && svc != null && svc.foreground && h.anyHeld() && h.sessionFiles().length == 1,
                "rec=" + rec + " state=" + StageLive.state + " canMark=" + StageLive.canMark + " files=" + h.sessionFiles().length);

        boolean pressedStart = false, pressedFinish = false, movingPressed = false;
        String errEarly = "", errMoving = "", textStart = "", errStart = "?";
        int stateAfterEarly = -1;
        boolean recAfterEarly = false, canMarkWhileMoving = false;
        for (Fix f : m.fixes) {
            deliver(sim, f);
            if (!pressedStart) {
                if (f.t >= 12.0 && StageLive.canMark) {
                    h.startService(in(h, RecorderService.ACTION_MARK_START));
                    h.flushMain();
                    pressedStart = true;
                    errStart = StageLive.error;
                    textStart = StageLive.startMarkText;
                    // a MARK FINISH straight away is refused in words and the recording goes on
                    StageLive.error = "";
                    h.startService(in(h, RecorderService.ACTION_MARK_FINISH).putExtra(RecorderService.EXTRA_NAME, "too soon"));
                    h.flushMain();
                    errEarly = StageLive.error;
                    stateAfterEarly = StageLive.state;
                    recAfterEarly = Recorder.LIVE.recording && !Recorder.LIVE.saving;
                }
            } else if (f.t >= m.tDepart + m.drive.duration() + 8.0 && StageLive.canMark) {
                h.startService(in(h, RecorderService.ACTION_MARK_FINISH).putExtra(RecorderService.EXTRA_NAME, "KKB to Fraser, test"));
                h.flushMain();
                pressedFinish = true;
                break;
            } else if (f.speed > 5.0) {
                if (StageLive.canMark) canMarkWhileMoving = true;
                if (!movingPressed) {
                    movingPressed = true;
                    StageLive.error = "";
                    h.startService(in(h, RecorderService.ACTION_MARK_FINISH).putExtra(RecorderService.EXTRA_NAME, "while moving"));
                    h.flushMain();
                    errMoving = StageLive.error;
                }
            }
        }
        Check.ok("ST.I2.2 MARK_START while parked: accepted, the screen gets 'Start marked (GPS +/-n m)', state 'driving to the finish'",
                pressedStart && errStart.isEmpty() && textStart.startsWith("Start marked"), "error='" + errStart + "' text='" + textStart + "'");
        Check.ok("ST.I2.3 MARK_FINISH straight after (0 m driven) is refused with words, the state and the recording are unchanged, nothing is saved; one while moving is refused too; canMark is false whenever the car moves",
                !errEarly.isEmpty() && stateAfterEarly == StageLive.MARK_DRIVE && recAfterEarly && !errMoving.isEmpty() && !canMarkWhileMoving && store.list().isEmpty(),
                "early='" + errEarly + "' moving='" + errMoving + "' canMarkWhileMoving=" + canMarkWhileMoving + " stages=" + store.list().size());
        final boolean idle = pressedFinish && idle(h, 10000);
        final List<Stage> made = store.list();
        final Stage stage = made.size() == 1 ? made.get(0) : null;
        Check.ok("ST.I2.4 MARK_FINISH while parked at the end: the recording is stopped and saved, the stage is stored under a new id with the name from the screen (comma removed), the service stops itself",
                idle && stage != null && stage.name().equals("KKB to Fraser test") && stage.id.matches("st\\d{8}_\\d{6}[b-z]?") && stage.id.equals(StageLive.markedStageId)
                        && StageLive.markedSeq == seq0 + 1 && StageLive.state == StageLive.IDLE,
                "idle=" + idle + " stages=" + made.size() + (stage == null ? "" : " name='" + stage.name() + "' id=" + stage.id) + " markedId=" + StageLive.markedStageId);
        if (stage == null) {
            h.close();
            return;
        }
        final double sS = ScenarioST.gateS(stage, stage.start), sF = ScenarioST.gateS(stage, stage.finish);
        Check.ok(String.format(Locale.US, "ST.I2.5 the lines are where they should be on the road, within the 2.5 m GPS noise of the two marks: start line at s=%.1f (want 20), finish line at s=%.1f (want %.0f), stage %.0f m",
                sS, sF, ScenarioST.road.total - 20.0, stage.lengthM()),
                Math.abs(sS - 20.0) < 9.0 && Math.abs(sF - (ScenarioST.road.total - 20.0)) < 9.0, "");

        final File[] files = h.sessionFiles();
        final File csv = files.length == 1 ? files[0] : null;
        final CsvAudit a = csv == null ? null : new CsvAudit(csv);
        final List<String> names = a == null ? new ArrayList<String>() : ScenarioST.eNames(a);
        Check.ok("ST.I2.6 the CSV has START, MARK_START, MARK_FINISH, STOP in order and is valid; the same file is in Downloads/TrackLogger, byte for byte",
                a != null && names.equals(Arrays.asList("START", "MARK_START", "MARK_FINISH", "STOP")) && a.badRows().isEmpty() && h.cr.visibleCount() == 1
                        && h.cr.rows().get(0).name.equals(csv.getName()) && Arrays.equals(h.cr.rows().get(0).data, Files.readAllBytes(csv.toPath())),
                names + " exported=" + h.cr.visibleCount());
        if (csv != null) {
            final List<RunResult> runs = store.runs(stage.id);
            final LogReader.Log log = LogReader.read(csv);
            final List<RunResult> off = Scorer.scoreAll(stage, log.fixes, csv.getName(), (double) log.startUtcMs);
            final double truth = m.drive.timeAt(sF) - m.drive.timeAt(sS);
            final boolean one = runs.size() == 1 && runs.get(0).finished && off.size() == 1;
            Check.ok(String.format(Locale.US, "ST.I2.7 the marking drive was scored as the stage's first run: %s, exact time %.2f s (standing start and braking stop: tenths of a second, not hundredths)",
                    one ? StageText.time(runs.get(0).totalS) : "no run", truth),
                    one && Math.abs(runs.get(0).totalS - truth) < 1.2, one ? String.format(Locale.US, "measured %.3f exact %.3f", runs.get(0).totalS, truth) : "runs=" + runs.size());
            final String stored = one ? text(runFile(store, stage.id, runs.get(0).runId)) : "";
            Check.ok("ST.I2.8 that stored run is exactly what scoring the written file against the stored stage gives (same text, to the last digit)",
                    one && stored.equals(StageFiles.formatRun(off.get(0))) && runs.get(0).runId.equals(Scorer.runIdFor(csv.getName(), 1)), "");
            final Stage rebuilt = ScenarioST.rebuildFromMarks(csv, stage.id, "x", stage.createdUtcMs);
            final double[] d = ScenarioST.stageDiff(stage, rebuilt);
            Check.ok(String.format(Locale.US, "ST.I2.9 the stored stage can be rebuilt from the recording alone (two MARK rows + the fixes between): same lines and route to %.1f cm", d[0] * 100.0),
                    d[0] < 0.05 && d[1] < 0.05, String.format(Locale.US, "worst %.4f m %.4f deg", d[0], d[1]));
        }
        Check.ok("ST.I2.10 clean-up after marking: one wake lock taken and released, no listeners or threads left, nothing printed, every service call on the main thread",
                h.acquires() == 1 && h.releases() == 1 && !h.anyHeld() && h.leaks().isEmpty() && h.quiet() && h.serviceCallsAllOnMain(),
                "acq=" + h.acquires() + " rel=" + h.releases() + " leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();

        // ---------------------------------------------------------------- marking that does not work out
        final ServiceHost g = new ServiceHost(root, "st_mark2");
        final Sim sim2 = new Sim(g.lm, g.sm, g);
        final StageStore store2 = storeOf(g);
        StageLive.reset();
        sim2.at(0.0);
        g.startService(in(g, RecorderService.ACTION_MARK_NEW));
        g.awaitRecording(3000);
        final List<Fix> moving = ScenarioST.fullRun(1.0, 1.0, 0.0, 0.0, 61).fixes;
        for (int i = 0; i < 12; i++) deliver(sim2, moving.get(i));
        g.startService(in(g, RecorderService.ACTION_MARK_START));
        g.flushMain();
        final String errMove = StageLive.error;
        g.startService(in(g, RecorderService.ACTION_MARK_FINISH).putExtra(RecorderService.EXTRA_NAME, "x"));
        g.flushMain();
        final String errNoStart = StageLive.error;
        final boolean stillMarking = StageLive.state == StageLive.MARK_START && !StageLive.canMark && Recorder.LIVE.recording;
        g.startService(in(g, RecorderService.ACTION_MARK_CANCEL));
        final boolean idle2 = idle(g, 8000);
        final File[] fs2 = g.sessionFiles();
        final CsvAudit a2 = fs2.length == 1 ? new CsvAudit(fs2[0]) : null;
        final boolean noMarkRow = a2 != null && a2.kind("E").stream().noneMatch(r -> r[2].startsWith("MARK"));
        Check.ok("ST.I2.11 MARK_START while the car is moving and MARK_FINISH before any start are refused with words and write nothing; MARK_CANCEL then ends the drive as an ordinary session (saved, exported), no stage, service stops",
                !errMove.isEmpty() && !errNoStart.isEmpty() && stillMarking && idle2 && a2 != null && noMarkRow && g.cr.visibleCount() == 1 && store2.list().isEmpty()
                        && g.leaks().isEmpty() && g.quiet(),
                "moving='" + errMove + "' noStart='" + errNoStart + "' idle=" + idle2 + " files=" + fs2.length + " exported=" + g.cr.visibleCount() + " leaks='" + g.leaks() + "'");
        g.close();
    }

    // ------------------------------------------------------------------ armed: runs, compare, abort, stop

    static void armed(File root) throws Exception {
        final ServiceHost h = new ServiceHost(root, "st_arm");
        final Sim sim = new Sim(h.lm, h.sm, h);
        final StageStore store = storeOf(h);
        store.save(ScenarioST.marking.stage);
        final Stage st = store.load(ScenarioST.marking.stage.id);
        final String id = st.id;
        StageLive.reset();
        ToneGenerator.TONES.clear();
        h.prefs.map.remove(RecorderService.PREF_BEEP);

        sim.at(0.0);
        arm(h, id);
        final boolean armedUp = h.awaitTrue(() -> StageLive.state == StageLive.ARMED && h.lm.listener != null, 3000);
        final RecorderService svc = h.current;
        Check.ok("ST.I3.1 ARM: foreground, wake lock, only the GPS is listened to (no sensors, no recording, no file); the screen is told which stage is armed",
                armedUp && isArmedListener(h) && h.sm.regs.isEmpty() && !Recorder.LIVE.recording && h.sessionFiles().length == 0 && svc != null && svc.foreground
                        && h.anyHeld() && StageLive.stageId.equals(id) && StageLive.stageName.equals(st.name()) && StageLive.message.contains(st.name()),
                "state=" + StageLive.state + " msg='" + StageLive.message + "'");

        // ---------------------------------------------------------------- run 1: noise-free, so the exact time is known
        final ScenarioST.Trip t1 = ScenarioST.fullRun(1.0, 1.0, 7.0, 0.0, 31);
        int iTrig = -1;
        for (int i = 0; i < t1.fixes.size(); i++) {
            final Fix f = t1.fixes.get(i);
            if (ScenarioST.dStart(st, f) <= 400.0 && f.speed >= 2.0) {
                iTrig = i;
                break;
            }
        }
        int firstRecording = -1, firstRunning = -1, firstFinishing = -1, firstSaving = -1, tonesAtRunning = -1;
        boolean allowedFirst = true, allowedParked = false, allowedMoving = true;
        int delivered = 0;
        for (Fix f : t1.fixes) {
            deliver(sim, f);
            final int i = delivered++;
            final int s = StageLive.state;
            if (i == 0) allowedFirst = StageLive.numbersAllowed();
            if (i == 5) allowedParked = StageLive.numbersAllowed();                  // 6 fixes, 5 s still
            if (i == 9) allowedMoving = StageLive.numbersAllowed();                  // moving again
            if (firstRecording < 0 && s == StageLive.RECORDING) firstRecording = i;
            if (firstRunning < 0 && s == StageLive.RUNNING) { firstRunning = i; tonesAtRunning = ToneGenerator.TONES.size(); }
            if (firstFinishing < 0 && s == StageLive.FINISHING) firstFinishing = i;
            if (s == StageLive.SAVING) { firstSaving = i; break; }
        }
        Check.ok("ST.I3.2 the screen rule while armed: no number after the first fix, a number allowed once the car has been still for 5 s with fresh fixes, none again as soon as it moves",
                !allowedFirst && allowedParked && !allowedMoving, "first=" + allowedFirst + " parked=" + allowedParked + " moving=" + allowedMoving);
        Check.ok("ST.I3.3 the recording starts by itself at the first fix that is moving (>= 2 m/s) inside 400 m of the start line (fix #" + iTrig + "), not before; then RECORDING, RUNNING, FINISHING, SAVING in that order",
                iTrig > 0 && firstRecording == iTrig && firstRunning > firstRecording && firstFinishing > firstRunning && firstSaving > firstFinishing,
                "trigger=" + iTrig + " recording=" + firstRecording + " running=" + firstRunning + " finishing=" + firstFinishing + " saving=" + firstSaving);
        Check.ok("ST.I3.4 one beep at the start line (already sounded when the screen said RUNNING), a second sound at the finish line, nothing else",
                tonesAtRunning == 1 && ToneGenerator.TONES.equals(Arrays.asList("start", "finish")), "tones=" + ToneGenerator.TONES + " atRunning=" + tonesAtRunning);
        final boolean back1 = h.awaitTrue(() -> rearmed(h, 1), 8000);
        Check.ok("ST.I3.5 when the run is over the recording is stopped and saved by itself and the phone arms again (GPS only, same stage); the screen counts one run",
                back1 && StageLive.lastRun != null && StageLive.lastRunSeq >= 1 && h.n("stopSelf") == 0 && svc.foreground && h.anyHeld(),
                "state=" + StageLive.state + " runs=" + StageLive.runsThisArming + " saving=" + Recorder.LIVE.saving);
        final List<RunResult> runs1 = store.runs(id);
        final File[] files1 = h.sessionFiles();
        final double truth1 = ScenarioST.truth(t1, st);
        final RunResult r1 = runs1.size() == 1 ? runs1.get(0) : null;
        Check.ok(String.format(Locale.US, "ST.I3.6 run 1 is stored: %s, exact time between the lines %.3f s (noise-free rolling start: within 30 ms)", r1 == null ? "none" : StageText.time(r1.totalS), truth1),
                r1 != null && r1.finished && Math.abs(r1.totalS - truth1) < 0.03 && files1.length == 1 && r1.csvName.equals(files1[0].getName()),
                r1 == null ? "runs=" + runs1.size() : String.format(Locale.US, "measured %.4f", r1.totalS));
        if (r1 != null && files1.length == 1) {
            final LogReader.Log log = LogReader.read(files1[0]);
            final List<RunResult> off = Scorer.scoreAll(st, log.fixes, files1[0].getName(), (double) log.startUtcMs);
            final CsvAudit a = new CsvAudit(files1[0]);
            final List<String> names = ScenarioST.eNames(a);
            Check.ok("ST.I3.7 the stored run is exactly what scoring the written file gives (same text to the last digit); the file has START, STAGE, STAGE_START, STAGE_FINISH, STOP, and is valid",
                    off.size() == 1 && text(runFile(store, id, r1.runId)).equals(StageFiles.formatRun(off.get(0))) && names.equals(Arrays.asList("START", "STAGE", "STAGE_START", "STAGE_FINISH", "STOP"))
                            && a.badRows().isEmpty() && h.cr.visibleCount() == 1 && StageLive.lastRun.runId.equals(r1.runId),
                    names + " offline runs=" + off.size() + " exported=" + h.cr.visibleCount());
            final int lrows = a.kind("L").size();
            final int want = 1 + (firstSaving - iTrig + 1);          // the fix before the trigger, the trigger fix, and every fix after it up to the end of the run
            Check.ok("ST.I3.8 the fix before the trigger and the trigger fix were put into the recording (the file's first GPS row is 1 s before its own START), and every later fix is there once: " + lrows + " GPS rows, " + want + " expected",
                    Long.parseLong(a.kind("L").get(0)[1]) < 0 && lrows == want && CsvAudit.monotonic(a.kind("L"), true) == null, "first L t_us=" + a.kind("L").get(0)[1] + " rows=" + lrows + " want=" + want);
        }

        // ---------------------------------------------------------------- stopped at the end: numbers allowed
        for (Fix f : ScenarioST.parked(ScenarioST.road.total + 100.0, sim.lastT + 3.0, 7, 0.0, 41)) deliver(sim, f);
        Check.ok("ST.I3.9 parked after the run: the armed screen may show the last run (a number is allowed once the car has stood still for 5 s)",
                StageLive.numbersAllowed() && StageLive.lastRun != null && r1 != null && StageLive.lastRun.runId.equals(r1.runId) && StageLive.state == StageLive.ARMED, "");

        // ---------------------------------------------------------------- run 2 in the same arming, with GPS noise, 3 % faster
        final ScenarioST.Trip t2 = ScenarioST.fullRun(1.03, sim.lastT + 4.0, 3.0, 2.5, 32);
        play(sim, t2.fixes, () -> StageLive.state == StageLive.SAVING);
        final boolean back2 = h.awaitTrue(() -> rearmed(h, 2), 8000);
        final List<RunResult> runs2 = store.runs(id);
        final File[] files2 = h.sessionFiles();
        RunResult r2 = null;
        for (RunResult r : runs2) if (r1 == null || !r.runId.equals(r1.runId)) r2 = r;
        final double truth2 = ScenarioST.truth(t2, st);
        Check.ok(String.format(Locale.US, "ST.I3.10 a second run in the same arming (no new button press) is recorded and stored as its own run in its own file: %s, exact %.2f s", r2 == null ? "none" : StageText.time(r2.totalS), truth2),
                back2 && runs2.size() == 2 && r2 != null && r2.finished && Math.abs(r2.totalS - truth2) < 0.9 && files2.length == 2 && !r2.csvName.equals(r1.csvName)
                        && h.cr.visibleCount() == 2 && ToneGenerator.TONES.equals(Arrays.asList("start", "finish", "start", "finish")),
                "back=" + back2 + " runs=" + runs2.size() + " files=" + files2.length + " tones=" + ToneGenerator.TONES);
        if (r1 != null && r2 != null) {
            final Compare c = Compare.of(r2, r1);
            final double want = r2.totalS - r1.totalS, trueDiff = truth2 - truth1;
            final String words = StageText.compareText(st, c, "run 1");
            Check.ok(String.format(Locale.US, "ST.I3.11 the second run against the first: %s (the exact difference is %s); sections add up to the total; the words are produced",
                    StageText.delta(c.totalDeltaS), StageText.delta(trueDiff)),
                    Math.abs(c.totalDeltaS - want) < 1e-9 && Math.abs(c.totalDeltaS - trueDiff) < 1.0 && c.totalDeltaS < -3.0 && words.length() > 40,
                    String.format(Locale.US, "delta %.3f true %.3f band %.2f", c.totalDeltaS, trueDiff, c.sameBandS()));
        }
        for (Fix f : ScenarioST.parked(ScenarioST.road.total + 100.0, sim.lastT + 3.0, 2, 0.0, 42)) deliver(sim, f);

        // ---------------------------------------------------------------- an approach that comes to nothing
        final int filesBefore = h.sessionFiles().length, exportsBefore = h.cr.visibleCount(), runsBefore = store.runs(id).size();
        final int tonesBefore = ToneGenerator.TONES.size();
        final List<Fix> tb = ScenarioST.turnBack(sim.lastT + 4.0, 2.5, 51);
        int iRec = -1, n = 0;
        for (Fix f : tb) {
            deliver(sim, f);
            if (iRec < 0 && StageLive.state == StageLive.RECORDING) iRec = n;
            n++;
            if (StageLive.state == StageLive.SAVING) break;
        }
        final boolean backAbort = h.awaitTrue(() -> rearmed(h, 2), 8000);
        Check.ok("ST.I4.1 a car that comes within the zone, turns round and leaves is recorded for a while and then given up ('moved away from the start line'): the recording is deleted, nothing is stored or exported, a low sound, armed again, the run count unchanged",
                iRec > 0 && backAbort && h.sessionFiles().length == filesBefore && h.cr.visibleCount() == exportsBefore && store.runs(id).size() == runsBefore
                        && ToneGenerator.TONES.size() == tonesBefore + 1 && ToneGenerator.TONES.get(tonesBefore).equals("fail")
                        && Recorder.LIVE.exportText.contains("No run was recorded"),
                "recAt=" + iRec + " files " + filesBefore + "->" + h.sessionFiles().length + " exports " + exportsBefore + "->" + h.cr.visibleCount()
                        + " tones=" + ToneGenerator.TONES + " export='" + Recorder.LIVE.exportText + "'");

        // ---------------------------------------------------------------- run 3 after the abort: arming still works
        final ScenarioST.Trip t3 = ScenarioST.trip(-700.0, ScenarioST.road.total + 300.0, 18.0, 15.0, 0.99, sim.lastT + 8.0, 3.0, 30.0, 2.5, 52);
        play(sim, t3.fixes, () -> StageLive.state == StageLive.SAVING);
        final boolean back3 = h.awaitTrue(() -> rearmed(h, 3), 8000);
        final List<RunResult> runs3 = store.runs(id);
        int fin = 0;
        for (RunResult r : runs3) if (r.finished) fin++;
        Check.ok("ST.I3.12 after an abort the next approach triggers and records normally: three finished runs of the stage are stored",
                back3 && runs3.size() == 3 && fin == 3 && h.sessionFiles().length == 3 && h.cr.visibleCount() == 3, "runs=" + runs3.size() + " files=" + h.sessionFiles().length);

        // ---------------------------------------------------------------- buttons that do not fit the state
        final int fgBefore = h.n("startForeground");
        h.startService(h.startIntent());
        arm(h, id);
        h.startService(in(h, RecorderService.ACTION_MARK_NEW));
        h.startService(in(h, RecorderService.ACTION_MARK_START));
        h.startService(in(h, RecorderService.ACTION_MARK_FINISH));
        h.startService(in(h, RecorderService.ACTION_MARK_CANCEL));
        h.flushMain();
        Check.ok("ST.I5.1 while armed, START, a second ARM, MARK_NEW, MARK_START, MARK_FINISH and MARK_CANCEL are all ignored: still armed, no recording, no new notification",
                rearmed(h, 3) && h.n("startForeground") == fgBefore && h.n("stopSelf") == 0, "state=" + StageLive.state + " fg " + fgBefore + "->" + h.n("startForeground"));

        // ---------------------------------------------------------------- run 4, stopped by hand in the middle
        final ScenarioST.Trip t4 = ScenarioST.fullRun(0.97, sim.lastT + 4.0, 3.0, 2.5, 34);
        int sinceRun = 0;
        for (Fix f : t4.fixes) {
            deliver(sim, f);
            if (StageLive.state == StageLive.RUNNING) sinceRun++;
            if (sinceRun >= 50) break;
        }
        final int tones4 = ToneGenerator.TONES.size();
        h.startService(h.stopIntent());
        final boolean idle4 = idle(h, 10000);
        final List<RunResult> runs4 = store.runs(id);
        RunResult r4 = null;
        for (RunResult r : runs4) if (!r.finished) r4 = r;
        final File[] files4 = h.sessionFiles();
        CsvAudit a4 = null;
        for (File f : files4) {
            final CsvAudit x = new CsvAudit(f);
            if (x.kind("E").stream().anyMatch(r -> r[2].equals("STAGE_DNF"))) a4 = x;
        }
        Check.ok("ST.I4.2 STOP in the middle of a run: the recording is closed with STAGE_DNF before STOP, a 'did not finish' run is stored with the reason, the file is exported, and (a manual stop ends the armed state) the service stops itself",
                idle4 && r4 != null && r4.statusText.equals("the recording was stopped") && runs4.size() == 4 && files4.length == 4 && h.cr.visibleCount() == 4 && a4 != null
                        && ScenarioST.eNames(a4).equals(Arrays.asList("START", "STAGE", "STAGE_START", "STAGE_DNF", "STOP")) && StageLive.state == StageLive.IDLE
                        && Recorder.LIVE.exportText.startsWith("Saved") && ToneGenerator.TONES.size() == tones4 + 1,
                "idle=" + idle4 + " runs=" + runs4.size() + " files=" + files4.length + " exported=" + h.cr.visibleCount() + " dnf=" + (r4 == null ? "none" : r4.statusText));

        // every finished run in the store is exactly what scoring its file now gives
        boolean allSame = true;
        int checked = 0;
        String firstBad = "";
        for (RunResult r : store.runs(id)) {
            if (!r.finished) continue;
            final File f = new File(h.ext, r.csvName);
            final LogReader.Log log = LogReader.read(f);
            final List<RunResult> off = Scorer.scoreAll(st, log.fixes, f.getName(), (double) log.startUtcMs);
            final boolean same = off.size() == 1 && off.get(0).runId.equals(r.runId) && text(runFile(store, id, r.runId)).equals(StageFiles.formatRun(off.get(0)));
            checked++;
            if (!same && allSame) { allSame = false; firstBad = r.runId; }
        }
        Check.ok("ST.I3.13 all " + checked + " finished runs in the store are exactly what scoring their own recordings against the stored stage gives",
                allSame && checked == 3, "first mismatch: " + firstBad);

        Check.ok("ST.I3.14 clean-up after a whole day of runs: one wake lock taken and released, no listeners, threads or notification left, nothing printed, every service call on the main thread",
                h.acquires() == 1 && h.releases() == 1 && !h.anyHeld() && h.leaks().isEmpty() && h.quiet() && h.serviceCallsAllOnMain()
                        && h.instances.size() == 1 && h.instances.get(0).startForegroundCalls == h.n("startForeground") && h.instances.get(0).stopForegroundCalls >= 1,
                "acq=" + h.acquires() + " rel=" + h.releases() + " leaks='" + h.leaks() + "' " + h.quietDetail() + " instances=" + h.instances.size());
        h.close();
    }

    // ------------------------------------------------------------------ odd intents, disarm, beeps, auto-disarm, GPS off, teardown

    private static Class<?> lastTarget() {
        return PendingIntent.CREATED.isEmpty() ? null : PendingIntent.CREATED.get(PendingIntent.CREATED.size() - 1).getTarget();
    }

    /** Tapping the notification opens the screen that fits what the service is doing. */
    static void notificationTarget(File root) throws Exception {
        final ServiceHost h = new ServiceHost(root, "st_notif");
        try {
            final Stage st0 = ScenarioST.marking.stage;
            storeOf(h).save(st0);
            StageLive.reset();
            PendingIntent.CREATED.clear();

            h.startService(h.startIntent());
            final boolean rec = h.awaitRecording(3000);
            final Class<?> plain = lastTarget();
            h.startService(h.stopIntent());
            final boolean idle1 = idle(h, 8000);

            PendingIntent.CREATED.clear();
            arm(h, st0.id);
            final boolean armed = h.awaitTrue(() -> StageLive.state == StageLive.ARMED, 3000);
            final Class<?> armedTarget = lastTarget();
            h.startService(in(h, RecorderService.ACTION_DISARM));
            final boolean idle2 = idle(h, 4000);

            PendingIntent.CREATED.clear();
            h.startService(in(h, RecorderService.ACTION_MARK_NEW));
            final boolean marking = h.awaitRecording(3000) && StageLive.state == StageLive.MARK_START;
            final Class<?> markTarget = lastTarget();
            h.startService(in(h, RecorderService.ACTION_MARK_CANCEL));
            final boolean idle3 = idle(h, 8000);

            Check.ok("ST.I5.12 the notification opens the recorder screen for an ordinary recording, and the stages screen while a stage is armed or being marked",
                    rec && armed && marking && idle1 && idle2 && idle3 && plain == MainActivity.class && armedTarget == StagesActivity.class && markTarget == StagesActivity.class
                            && h.leaks().isEmpty() && h.quiet(),
                    "plain=" + plain + " armed=" + armedTarget + " marking=" + markTarget + " leaks='" + h.leaks() + "'");
        } finally {
            h.close();
        }
    }

    static void odd(File root) throws Exception {
        final Stage st0 = ScenarioST.marking.stage;

        // ---------------------------------------------------------------- an ARM that cannot work
        {
            final ServiceHost h = new ServiceHost(root, "st_bad");
            StageLive.reset();
            boolean ok = true;
            String detail = "";
            final String[] ids = {"nope", null, "../../etc/passwd", ""};
            for (int k = 0; k < ids.length; k++) {
                StageLive.error = "";
                arm(h, ids[k]);
                final boolean idle = idle(h, 4000);
                final RecorderService s = k < h.instances.size() ? h.instances.get(k) : null;
                final boolean good = idle && s != null && s.startForegroundCalls == 1 && s.stopForegroundCalls == 1 && s.stopSelfCalls == 1
                        && StageLive.error.equals("That stage could not be read.") && StageLive.state == StageLive.IDLE;
                if (!good) { ok = false; detail += " [" + ids[k] + ": idle=" + idle + " err='" + StageLive.error + "']"; }
            }
            Check.ok("ST.I5.2 ARM with an unknown, missing, empty or path-like stage id: the service satisfies the foreground rule (startForeground once), says why, takes the notification down and stops itself; four times in a row",
                    ok && h.instances.size() == 4 && h.leaks().isEmpty() && h.acquires() == 0 && h.quiet(), detail + " instances=" + h.instances.size() + " leaks='" + h.leaks() + "'");
            h.close();
        }

        // ---------------------------------------------------------------- an ordinary recording blocks ARM and MARK_NEW
        {
            final ServiceHost h = new ServiceHost(root, "st_plain");
            final StageStore store = storeOf(h);
            store.save(st0);
            StageLive.reset();
            h.startService(h.startIntent());
            final boolean rec = h.awaitRecording(3000);
            final int fg = h.n("startForeground");
            arm(h, st0.id);
            h.startService(in(h, RecorderService.ACTION_MARK_NEW));
            h.startService(in(h, RecorderService.ACTION_MARK_START));
            h.flushMain();
            final boolean ignored = StageLive.state == StageLive.IDLE && Recorder.LIVE.recording && h.n("startForeground") == fg && !isArmedListener(h);
            h.startService(h.stopIntent());
            final boolean idle = idle(h, 8000);
            Check.ok("ST.I5.3 during an ordinary recording ARM, MARK_NEW and MARK_START are ignored (the recording goes on untouched); STOP then saves it as before",
                    rec && ignored && idle && h.cr.visibleCount() == 1 && h.leaks().isEmpty() && h.quiet() && store.runs(st0.id).isEmpty(),
                    "rec=" + rec + " ignored=" + ignored + " idle=" + idle);
            h.close();
        }

        // ---------------------------------------------------------------- stray stage buttons on an idle service
        {
            final ServiceHost h = new ServiceHost(root, "st_stray");
            StageLive.reset();
            final String[] acts = {RecorderService.ACTION_MARK_START, RecorderService.ACTION_MARK_FINISH, RecorderService.ACTION_MARK_CANCEL, RecorderService.ACTION_DISARM};
            boolean ok = true;
            for (int k = 0; k < acts.length; k++) {
                h.startService(in(h, acts[k]));
                ok &= idle(h, 3000) && h.instances.size() == k + 1 && h.instances.get(k).stopSelfCalls == 1;
            }
            Check.ok("ST.I5.4 MARK_START, MARK_FINISH, MARK_CANCEL and DISARM reaching a service that has nothing to do each end it again at once (no idle service left behind)",
                    ok && h.leaks().isEmpty() && h.acquires() == 0 && h.quiet(), "instances=" + h.instances.size());
            h.close();
        }

        // ---------------------------------------------------------------- DISARM and STOP while armed
        for (int variant = 0; variant < 2; variant++) {
            final ServiceHost h = new ServiceHost(root, "st_dis" + variant);
            final Sim sim = new Sim(h.lm, h.sm, h);
            final StageStore store = storeOf(h);
            store.save(st0);
            StageLive.reset();
            sim.at(0.0);
            arm(h, st0.id);
            h.awaitTrue(() -> StageLive.state == StageLive.ARMED, 3000);
            for (Fix f : ScenarioST.parked(-600.0, 1.0, 3, 0.0, 70 + variant)) deliver(sim, f);
            final boolean wasArmed = StageLive.state == StageLive.ARMED && h.anyHeld() && isArmedListener(h);
            h.startService(variant == 0 ? in(h, RecorderService.ACTION_DISARM) : h.stopIntent());
            final boolean idle = idle(h, 4000);
            Check.ok("ST.I5." + (5 + variant) + " " + (variant == 0 ? "DISARM" : "STOP") + " while armed: the GPS listener is removed, the wake lock released, the notification taken down, the service stops; no recording was made",
                    wasArmed && idle && StageLive.state == StageLive.IDLE && h.lm.listener == null && !h.anyHeld() && h.sessionFiles().length == 0 && h.leaks().isEmpty()
                            && h.acquires() == 1 && h.releases() == 1 && h.quiet() && h.serviceCallsAllOnMain(),
                    "armed=" + wasArmed + " idle=" + idle + " leaks='" + h.leaks() + "'");
            h.close();
        }

        // ---------------------------------------------------------------- auto-disarm
        {
            final ServiceHost h = new ServiceHost(root, "st_auto");
            final StageStore store = storeOf(h);
            store.save(st0);
            StageLive.reset();
            RecorderService.autoDisarmMs = 300L;
            arm(h, st0.id);
            final boolean idle = idle(h, 4000);
            RecorderService.autoDisarmMs = RecorderService.AUTO_DISARM_DEFAULT_MS;
            Check.ok("ST.I5.7 an armed phone nobody uses switches itself off after the set time (3 hours in the app): the screen is told why, the service stops, nothing is held",
                    idle && StageLive.state == StageLive.IDLE && StageLive.message.startsWith("Switched off") && !h.anyHeld() && h.leaks().isEmpty() && h.quiet(),
                    "idle=" + idle + " msg='" + StageLive.message + "'");
            h.close();
        }

        // ---------------------------------------------------------------- GPS switched off
        {
            final ServiceHost h = new ServiceHost(root, "st_gps");
            final StageStore store = storeOf(h);
            store.save(st0);
            StageLive.reset();
            h.lm.gpsEnabled = false;
            arm(h, st0.id);
            h.awaitTrue(() -> StageLive.state == StageLive.ARMED, 3000);
            final boolean said = StageLive.error.startsWith("GPS is switched off");
            h.startService(in(h, RecorderService.ACTION_DISARM));
            final boolean idle = idle(h, 4000);
            Check.ok("ST.I5.8 arming with GPS switched off still arms (the GPS may come on) but the screen is told in words to turn Location on",
                    said && idle && h.leaks().isEmpty(), "error='" + StageLive.error + "'");
            h.close();
        }

        // ---------------------------------------------------------------- beep setting, and a phone that cannot beep
        for (int variant = 0; variant < 2; variant++) {
            final ServiceHost h = new ServiceHost(root, "st_beep" + variant);
            final Sim sim = new Sim(h.lm, h.sm, h);
            final StageStore store = storeOf(h);
            store.save(st0);
            StageLive.reset();
            ToneGenerator.TONES.clear();
            if (variant == 0) h.prefs.map.put(RecorderService.PREF_BEEP, "false");
            else { h.prefs.map.put(RecorderService.PREF_BEEP, "true"); ToneGenerator.FAIL_CREATE = true; }
            sim.at(0.0);
            arm(h, st0.id);
            h.awaitTrue(() -> StageLive.state == StageLive.ARMED, 3000);
            final List<Fix> tb = ScenarioST.turnBack(1.0, 0.0, 80 + variant);
            boolean sawRecording = false;
            for (Fix f : tb) {
                deliver(sim, f);
                if (StageLive.state == StageLive.RECORDING || Recorder.LIVE.recording) sawRecording = true;
                if (StageLive.state == StageLive.SAVING) break;
            }
            final boolean back = h.awaitTrue(() -> StageLive.state == StageLive.ARMED && !Recorder.LIVE.saving && !Recorder.LIVE.recording && isArmedListener(h), 8000);
            ToneGenerator.FAIL_CREATE = false;
            h.startService(in(h, RecorderService.ACTION_DISARM));
            final boolean idle = idle(h, 4000);
            Check.ok("ST.I5." + (9 + variant) + " " + (variant == 0 ? "with the beep switched off in the settings the aborted approach is silent" : "a phone that cannot make the sound (no audio resources) gets through an aborted approach without any error")
                            + ": the approach really was recorded, is still given up and the phone armed again (and the throw-away recording is deleted)",
                    sawRecording && back && idle && ToneGenerator.TONES.isEmpty() && h.sessionFiles().length == 0 && h.leaks().isEmpty() && h.quiet(),
                    "sawRecording=" + sawRecording + " back=" + back + " tones=" + ToneGenerator.TONES + " files=" + h.sessionFiles().length + " " + h.quietDetail());
            h.close();
        }

        // ---------------------------------------------------------------- the system takes the service away in the middle of a run
        {
            final ServiceHost h = new ServiceHost(root, "st_kill");
            final Sim sim = new Sim(h.lm, h.sm, h);
            final StageStore store = storeOf(h);
            store.save(st0);
            StageLive.reset();
            sim.at(0.0);
            arm(h, st0.id);
            h.awaitTrue(() -> StageLive.state == StageLive.ARMED, 3000);
            final ScenarioST.Trip tr = ScenarioST.fullRun(1.0, 1.0, 3.0, 2.5, 90);
            int since = 0;
            for (Fix f : tr.fixes) {
                deliver(sim, f);
                if (StageLive.state == StageLive.RUNNING) since++;
                if (since >= 40) break;
            }
            h.stopService();
            h.flushMain();
            final File[] fs = h.sessionFiles();
            final CsvAudit a = fs.length == 1 ? new CsvAudit(fs[0]) : null;
            final List<String> names = a == null ? new ArrayList<String>() : ScenarioST.eNames(a);
            Check.ok("ST.I6.1 the system destroys the service 40 s into a run: the recording is closed properly (STAGE_DNF, STOP), nothing is left running or held; the run is not in the stage's list (the file is, and 'score old recordings' finds the run in it)",
                    a != null && names.equals(Arrays.asList("START", "STAGE", "STAGE_START", "STAGE_DNF", "STOP")) && a.badRows().isEmpty() && h.leaks().isEmpty()
                            && store.runs(st0.id).isEmpty() && StageLive.state == StageLive.IDLE && h.quiet(),
                    names + " leaks='" + h.leaks() + "'");
            if (a != null) {
                final LogReader.Log log = LogReader.read(fs[0]);
                final List<RunResult> off = Scorer.scoreAll(st0, log.fixes, fs[0].getName(), (double) log.startUtcMs);
                Check.ok("ST.I6.2 scoring that cut-short recording later against the stage gives one run that did not finish, as it should",
                        off.size() == 1 && !off.get(0).finished, "runs=" + off.size());
            }
            h.close();
        }
    }
}
