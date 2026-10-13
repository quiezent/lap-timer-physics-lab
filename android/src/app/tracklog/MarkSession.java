package app.tracklog;

import java.util.Locale;

/**
 * The live side of marking a new stage: the recorder's fixes go to a StageMarker, and the two button presses (MARK START, MARK FINISH)
 * come in from the service. Marks are written into the log as events, so the file says where the driver parked. Plain Java with no
 * Android classes.
 *
 * Nothing here is shown while the car is moving: the screen gets StageLive.canMark and a sentence about what to do or what is wrong.
 */
final class MarkSession implements StageSink {

    private final StageMarker marker = new StageMarker();
    private Out out;
    private double lastNow = Double.NaN;

    @Override
    public synchronized void attach(Out o) {
        out = o;
        publish(0.0);
    }

    @Override
    public synchronized void onFix(Fix f, Out o) {
        marker.onFix(f);
        lastNow = f.t;
        publish(f.t);
    }

    @Override
    public synchronized void onTick(double nowS, Out o) {
        lastNow = nowS;
        publish(nowS);
    }

    @Override
    public synchronized void onStop(double nowS, Out o) {
        // nothing is open: a stopped marking drive simply leaves the file as an ordinary session
    }

    private void publish(double now) {
        final int phase = marker.phase();
        if (phase == StageMarker.WAIT_START) StageLive.state = StageLive.MARK_START;
        else if (phase == StageMarker.DRIVING) StageLive.state = StageLive.MARK_DRIVE;
        StageLive.canMark = phase != StageMarker.DONE && marker.canMark(now);
        StageLive.markHint = marker.hint(now);
    }

    /** MARK START pressed. @return "" on success, otherwise the reason in plain words */
    synchronized String markStart() {
        final Out o = out;
        if (o == null) return "Not recording.";
        final double now = o.now();
        final String why = marker.markStart(now);
        if (why.length() == 0) {
            o.event(now, String.format(Locale.US, "MARK_START,%.7f,%.7f,%.1f", marker.startLat(), marker.startLon(), marker.startAcc()));
            StageLive.startMarkText = String.format(Locale.US, "Start marked (GPS +/-%.0f m).", marker.startAcc());
        }
        publish(now);
        return why;
    }

    /** MARK FINISH pressed. On success stage() holds the new stage. @return "" on success, otherwise the reason */
    synchronized String markFinish(String id, String name, long createdUtcMs) {
        final Out o = out;
        if (o == null) return "Not recording.";
        final double now = o.now();
        final String why = marker.markFinish(now, id, name, createdUtcMs);
        if (why.length() == 0) {
            o.event(now, String.format(Locale.US, "MARK_FINISH,%.7f,%.7f,%.1f", marker.finishLat(), marker.finishLon(), marker.finishAcc()));
        }
        publish(now);
        return why;
    }

    synchronized Stage stage() {
        return marker.stage();
    }

    synchronized int phase() {
        return marker.phase();
    }
}
