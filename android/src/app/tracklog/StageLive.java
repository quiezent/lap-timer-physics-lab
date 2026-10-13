package app.tracklog;

/**
 * What the stage screens show, written by the service and the stage session, read by the screen. Static, like Recorder.LIVE, because
 * the service and the screen share one process and the screen can come and go.
 *
 * The rule for open roads is kept here: while the car is moving, the screen gets words about the state and no number about the run.
 * numbersAllowed() is the one place that decides; the screen asks it before showing a time.
 */
final class StageLive {

    static final int IDLE = 0;           // nothing armed
    static final int ARMED = 1;          // GPS only, waiting for the car to come to the start
    static final int RECORDING = 2;      // recording, start line not crossed yet
    static final int RUNNING = 3;        // start line crossed, finish not yet
    static final int FINISHING = 4;      // finish line crossed, the few seconds after it are being recorded
    static final int SAVING = 5;         // the run is over, being saved
    static final int MARK_START = 6;     // marking a new stage: waiting for MARK START
    static final int MARK_DRIVE = 7;     // start marked, driving to the finish
    static final int MARK_SAVING = 8;    // finish marked, stage being saved

    static volatile int state = IDLE;
    static volatile String stageId = "";
    static volatile String stageName = "";
    static volatile String message = "";            // one line of plain words for the screen ("Waiting for the start line"), "" if none
    static volatile String error = "";              // something the driver should know went wrong
    static volatile boolean stopped;                // ResultGuard: the car has been still for 5 s, fixes are fresh
    static volatile boolean haveFix;
    static volatile boolean canMark;                // marking: the car is still enough to take a mark now
    static volatile String markHint = "";           // marking: why not, or what to do next
    static volatile String startMarkText = "";      // marking: "Start marked (+/-3 m)"
    static volatile RunResult lastRun;              // the most recent run of this session of arming, null if none
    static volatile long lastRunSeq;                // incremented each time lastRun changes
    static volatile String markedStageId = "";      // marking: set when a new stage has been saved
    static volatile long markedSeq;
    static volatile int runsThisArming;             // runs completed since ARM

    private StageLive() { }

    /** False while armed or recording and the car is not known to be stopped: the screen then shows no time. */
    static boolean numbersAllowed() {
        final int s = state;
        return s == IDLE || stopped;
    }

    static boolean busy() {
        return state != IDLE;
    }

    static void reset() {
        state = IDLE;
        stageId = "";
        stageName = "";
        message = "";
        error = "";
        stopped = false;
        haveFix = false;
        canMark = false;
        markHint = "";
        startMarkText = "";
        runsThisArming = 0;
    }
}
