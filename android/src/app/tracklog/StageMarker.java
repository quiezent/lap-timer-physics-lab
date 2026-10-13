package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The marking drive, as a small state machine fed with GPS fixes. Plain Java with no Android classes.
 *
 * The driver parks at the start of the stage and presses MARK START; drives the stage; parks at the finish and presses MARK FINISH.
 * A mark can be set only while the car is parked, so the phone is never touched on the move. The mark is the average position of the
 * last few fixes taken while the car stood still: averaging a few fixes takes some of the 3 m of GPS noise out of it.
 *
 *   WAIT_START  recording, waiting for MARK START
 *   DRIVING     start marked; waiting for MARK FINISH
 *   DONE        finish marked and the stage built (stage() returns it)
 *
 * All methods are synchronized: the fixes arrive on the sensor thread, the button presses on the main thread.
 */
final class StageMarker {

    static final int WAIT_START = 0, DRIVING = 1, DONE = 2;

    static final int STILL_FIXES = 4;               // consecutive stationary fixes needed to mark
    static final int AVERAGE_FIXES = 5;             // at most this many are averaged
    static final double STILL_MAX_SPEED = 0.8;      // m/s
    static final double MAX_HACC_M = 12.0;          // reported accuracy of each of those fixes
    static final double MAX_SPREAD_M = 6.0;         // the fixes must lie within this of their average
    static final double MAX_FIX_AGE_S = 3.0;        // the latest fix must be this recent

    private final List<Fix> fixes = new ArrayList<Fix>();
    private int phase = WAIT_START;
    private int startIdx = -1;
    private double startLat, startLon, startAcc;
    private double finishLat, finishLon, finishAcc;
    private Stage built;

    synchronized void onFix(Fix f) {
        if (f == null || !f.usable()) return;
        if (!fixes.isEmpty() && !(f.t > fixes.get(fixes.size() - 1).t)) return;
        fixes.add(f);
    }

    synchronized int phase() {
        return phase;
    }

    synchronized Stage stage() {
        return built;
    }

    synchronized double startLat() { return startLat; }
    synchronized double startLon() { return startLon; }
    synchronized double startAcc() { return startAcc; }
    synchronized double finishLat() { return finishLat; }
    synchronized double finishLon() { return finishLon; }
    synchronized double finishAcc() { return finishAcc; }

    /** Time of the fix at which the start was marked (clock seconds), NaN before. */
    synchronized double startMarkTime() {
        return startIdx < 0 ? Double.NaN : fixes.get(startIdx).t;
    }

    /** Why a mark cannot be made now, in plain words; "" if it can. */
    synchronized String whyNotNow(double now) {
        if (phase == DONE) return "Stage already marked.";
        final int n = fixes.size();
        if (n == 0) return "Waiting for a GPS fix.";
        final Fix last = fixes.get(n - 1);
        if (now - last.t > MAX_FIX_AGE_S) return "Waiting for a GPS fix.";
        if (n < STILL_FIXES) return "Keep still while the GPS settles.";
        double cx = 0, cy = 0;
        final Geo g = new Geo(last.lat, last.lon);
        for (int i = n - STILL_FIXES; i < n; i++) {
            final Fix f = fixes.get(i);
            if (f.speed < 0.0 || f.speed > STILL_MAX_SPEED) return "Stop the car first.";
            if (f.hAcc < 0.0 || f.hAcc > MAX_HACC_M) return "Waiting for a better GPS fix.";
            cx += g.x(f.lon);
            cy += g.y(f.lat);
        }
        cx /= STILL_FIXES;
        cy /= STILL_FIXES;
        for (int i = n - STILL_FIXES; i < n; i++) {
            final Fix f = fixes.get(i);
            if (Math.hypot(g.x(f.lon) - cx, g.y(f.lat) - cy) > MAX_SPREAD_M) return "The position is still moving about. Keep still.";
        }
        return "";
    }

    synchronized boolean canMark(double now) {
        return whyNotNow(now).length() == 0;
    }

    /** The average of up to AVERAGE_FIXES of the latest stationary fixes: {lat, lon, accuracy}. Call only when canMark. */
    private double[] average() {
        final int n = fixes.size();
        int from = n;
        while (from > 0 && n - from < AVERAGE_FIXES) {
            final Fix f = fixes.get(from - 1);
            if (f.speed < 0.0 || f.speed > STILL_MAX_SPEED || f.hAcc < 0.0 || f.hAcc > MAX_HACC_M) break;
            from--;
        }
        final Fix ref = fixes.get(n - 1);
        final Geo g = new Geo(ref.lat, ref.lon);
        double cx = 0, cy = 0, acc = 0;
        for (int i = from; i < n; i++) {
            cx += g.x(fixes.get(i).lon);
            cy += g.y(fixes.get(i).lat);
            acc += fixes.get(i).hAcc;
        }
        final int m = n - from;
        return new double[]{g.lat(cy / m), g.lon(cx / m), acc / m};
    }

    /** Marks the start. Returns "" on success, otherwise the reason (plain words). */
    synchronized String markStart(double now) {
        if (phase != WAIT_START) return "The start is already marked.";
        final String why = whyNotNow(now);
        if (why.length() > 0) return why;
        final double[] a = average();
        startLat = a[0];
        startLon = a[1];
        startAcc = a[2];
        startIdx = fixes.size() - 1;
        phase = DRIVING;
        return "";
    }

    /**
     * Marks the finish and builds the stage from the drive. Returns "" on success (stage() is then set), otherwise the reason; after a
     * failure the marker stays in DRIVING so the driver can go on and mark again.
     */
    synchronized String markFinish(double now, String id, String name, long createdUtcMs) {
        if (phase != DRIVING) return "Mark the start first.";
        final String why = whyNotNow(now);
        if (why.length() > 0) return why;
        final double[] a = average();
        final List<Fix> drive = new ArrayList<Fix>(fixes.subList(startIdx + 1, fixes.size()));
        try {
            built = StageBuilder.build(id, name, createdUtcMs, startLat, startLon, a[0], a[1], drive);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        finishLat = a[0];
        finishLon = a[1];
        finishAcc = a[2];
        phase = DONE;
        return "";
    }

    /** Every fix seen so far, for scoring the marking drive as the first run of the new stage. */
    synchronized List<Fix> allFixes() {
        return new ArrayList<Fix>(fixes);
    }

    /** Words for the screen. */
    synchronized String hint(double now) {
        switch (phase) {
            case WAIT_START: {
                final String why = whyNotNow(now);
                return why.length() == 0 ? "Parked at the start? Press MARK START." : "Park at the start of the stage. " + why;
            }
            case DRIVING: {
                final String why = whyNotNow(now);
                return why.length() == 0 ? "Parked at the finish? Press MARK FINISH." : "Start marked. Drive the stage, then park at the finish.";
            }
            default:
                return String.format(Locale.US, "Stage marked.");
        }
    }
}
