package app.tracklog;

/**
 * The live side of one run of a stage: it takes the recorder's GPS fixes, runs them through a StageRun, writes the events into the
 * log, tells the service when the run is over, and keeps StageLive up to date for the screen. Plain Java with no Android classes.
 *
 * It does what the offline Scorer does with the same fixes (the same StageRun class, with the live failsafes switched on), and the
 * fixes are the ones written to the file, so the result it gives is the result a later scoring of the file gives. The tests check this.
 *
 * Nothing here shows the driver a number. StageLive gets state words, and "stopped" from a ResultGuard.
 *
 * All methods are synchronized: fixes arrive on the sensor thread, onStop on the thread that stops the recording, results are read on
 * the thread that saves them.
 */
final class StageSession implements StageSink {

    /** What the service does about the run. Called with this object's monitor held, on the sensor thread or the stopping thread: must not block. */
    interface Listener {
        void runStarted();                  // the start line was crossed
        void runFinished();                 // the finish line was crossed
        void runFailed(String why);         // the run did not finish, or never started
        void ended();                       // the run is over: the recording should now be stopped and the run saved
    }

    private final Stage stage;
    private final StageRun run;
    private final Listener listener;
    private final ResultGuard guard = new ResultGuard();
    private boolean announced, endedSent;

    StageSession(Stage stage, Listener listener) {
        this.stage = stage;
        this.listener = listener;
        this.run = new StageRun(stage, true);
    }

    Stage stage() {
        return stage;
    }

    @Override
    public synchronized void attach(Out out) {
        // nothing to keep: every call brings its Out
    }

    @Override
    public synchronized void onFix(Fix f, Out out) {
        if (!announced) {
            announced = true;
            out.event(f.t, "STAGE," + stage.id);
        }
        guard.onFix(f.t, f.speed);
        run.onFix(f);
        drain(out);
        publish(f.t);
    }

    @Override
    public synchronized void onTick(double nowS, Out out) {
        run.onTick(nowS);
        drain(out);
        publish(nowS);
    }

    @Override
    public synchronized void onStop(double nowS, Out out) {
        run.endNow("the recording was stopped");
        drain(out);
        publish(nowS);
    }

    /** Events into the log, and the listener told. */
    private void drain(Out out) {
        for (StageRun.Event e : run.drainEvents()) {
            out.event(e.t, e.name + e.values);
            if (e.name.equals("STAGE_START")) listener.runStarted();
            else if (e.name.equals("STAGE_FINISH")) listener.runFinished();
            else if (e.name.equals("STAGE_DNF") || e.name.equals("STAGE_ABORT")) listener.runFailed(run.reason());
        }
        if (run.done() && !endedSent) {
            endedSent = true;
            listener.ended();
        }
    }

    private void publish(double now) {
        switch (run.phase()) {
            case StageRun.APPROACH:
                StageLive.state = StageLive.RECORDING;
                StageLive.message = "Recording. Waiting for the start line.";
                break;
            case StageRun.RUNNING:
                StageLive.state = StageLive.RUNNING;
                StageLive.message = "Run in progress.";
                break;
            case StageRun.POST:
                StageLive.state = StageLive.FINISHING;
                StageLive.message = "Finish line crossed.";
                break;
            case StageRun.FINISHED:
                StageLive.state = StageLive.SAVING;
                StageLive.message = "Run finished. Saving.";
                break;
            case StageRun.DNF:
                StageLive.state = StageLive.SAVING;
                StageLive.message = "Did not finish: " + run.reason() + ". Saving.";
                break;
            default:
                StageLive.state = StageLive.SAVING;
                StageLive.message = "No run recorded: " + run.reason() + ".";
                break;
        }
        StageLive.stopped = guard.stopped(now);
    }

    // ------------------------------------------------------------------ what the service asks afterwards

    synchronized int phase() {
        return run.phase();
    }

    synchronized boolean aborted() {
        return run.phase() == StageRun.ABORTED;
    }

    synchronized String reason() {
        return run.reason();
    }

    /** The result (null if the run was aborted or has not ended): see StageRun.result. */
    synchronized RunResult result(String runId, String csvName, double clockToUtcMs) {
        return run.result(runId, csvName, clockToUtcMs);
    }
}
