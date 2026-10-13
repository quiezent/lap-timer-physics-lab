package app.tracklog;

/**
 * The rule for open roads: numbers about a run (a time, a difference, a comparison) are shown only when the car has stopped. Plain
 * Java with no Android classes, so the rule itself is tested.
 *
 * The car counts as stopped when the speed has been below 2 km/h for 5 seconds and a fix arrived in the last 3 seconds. If the GPS
 * has gone quiet, or the speed is unknown, the answer is "not stopped": when in doubt the screen shows nothing.
 */
final class ResultGuard {

    static final double STILL_SPEED_MS = 0.55;     // 2 km/h
    static final double STILL_FOR_S = 5.0;
    static final double MAX_FIX_AGE_S = 3.0;

    private double stillSince = Double.NaN;
    private double lastT = Double.NaN;

    /** Feed every GPS fix: its time (any clock, seconds) and speed (m/s, negative if unknown). */
    void onFix(double t, double speed) {
        if (Double.isNaN(t)) return;
        if (!Double.isNaN(lastT) && t <= lastT) return;
        lastT = t;
        if (speed >= 0.0 && speed < STILL_SPEED_MS) {
            if (Double.isNaN(stillSince)) stillSince = t;
        } else {
            stillSince = Double.NaN;
        }
    }

    /** @param now the same clock as the fixes */
    boolean stopped(double now) {
        if (Double.isNaN(stillSince) || Double.isNaN(lastT)) return false;
        if (now - lastT > MAX_FIX_AGE_S) return false;
        return lastT - stillSince >= STILL_FOR_S;
    }

    void reset() {
        stillSince = Double.NaN;
        lastT = Double.NaN;
    }
}
