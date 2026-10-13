package app.tracklog;

/**
 * The armed state: the app is waiting for the car to come to the start of a stage, with only the GPS on. Plain Java with no Android
 * classes. It says when to start recording.
 *
 * Recording starts when the car is moving (at least 2 m/s) within 400 m of the start line. A car that is parked inside that circle
 * does not start it; moving off does. That way waiting at a roadside, in a queue or at a gate near the start costs no recording, and
 * the recording begins with the first movement toward the line.
 *
 * After a run, or after an approach that came to nothing, the watcher will not trigger again until the car has been more than 600 m
 * from the start line, so that finishing a run near the start (or turning round near it) does not start a recording by itself.
 */
final class StageWatcher {

    static final double ZONE_M = 400.0;
    static final double REARM_M = 600.0;
    static final double TRIGGER_SPEED = 2.0;      // m/s

    private final Stage stage;
    private boolean needExit;
    private double lastDistM = Double.NaN;

    StageWatcher(Stage stage) {
        this.stage = stage;
    }

    /** Distance of the latest fix from the middle of the start line (metres), NaN before the first fix. */
    double distanceM() {
        return lastDistM;
    }

    boolean needsExit() {
        return needExit;
    }

    /** A run or an approach has just ended: wait for the car to leave the area before triggering again. */
    void afterRun() {
        needExit = true;
    }

    /** @return true when the recorder should be started now */
    boolean onFix(Fix f) {
        if (f == null || !f.usable()) return false;
        final double d = Math.hypot(stage.geo.x(f.lon) - stage.start.cx, stage.geo.y(f.lat) - stage.start.cy);
        lastDistM = d;
        if (needExit) {
            if (d > REARM_M) needExit = false;
            return false;
        }
        return d <= ZONE_M && f.speed >= TRIGGER_SPEED;
    }
}
