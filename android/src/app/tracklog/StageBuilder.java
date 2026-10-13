package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes a Stage from a marking drive: the driver parked at the start and marked it, drove the stage, parked at the finish and marked
 * that. Plain Java with no Android classes.
 *
 * From the fixes of the drive it takes the route, then puts the lines on it:
 *  - the START line is 20 m along the route from the start mark. The car was parked on the mark, so a line exactly on it could be
 *    "crossed" by GPS jitter before the car moves, or missed if a jittering fix lies beyond it. 20 m ahead, a car that starts from
 *    rest at the mark crosses it at about 40 km/h, where one metre of position error is a tenth of a second and not a third, and
 *    one that was parked up to 10 m beyond the mark still starts behind the line. A rolling start does not care. Every run is timed
 *    from this same line, so the 20 m is shared by all of them and cancels in a comparison.
 *  - the FINISH line is 20 m BEFORE the finish mark, for the same reason: a car that brakes to a stop on the mark crosses it
 *    at about 50 km/h instead of crawling, and a car that does not stop crosses it at its running speed.
 *  - each line is perpendicular to the direction of the route over the 40 m after (start) or before (finish) it.
 */
final class StageBuilder {

    static final double START_SHIFT_M = 20.0;
    static final double FINISH_SHIFT_M = 20.0;
    static final double BEARING_SPAN_M = 40.0;
    static final double MIN_ROUTE_M = 300.0;           // the route from the start mark to the finish mark
    static final double MIN_SEPARATION_M = 150.0;      // start mark to finish mark in a straight line (a stage is not a loop)
    static final double DEDUP_M = 3.0;                 // fixes closer than this to the last vertex add nothing but GPS noise
    static final double MAX_VERTEX_ACC_M = 30.0;       // a fix with a worse reported accuracy is not used for the route
    static final double MOVING_SPEED = 1.5;            // m/s: the stationary fixes at either end are left out

    private StageBuilder() { }

    /**
     * @param drive the GPS fixes recorded after the start was marked, up to the finish mark, in time order
     * @throws IllegalArgumentException with a message in plain words for the driver, if the drive cannot make a stage
     */
    static Stage build(String id, String name, long createdUtcMs, double startLat, double startLon,
                       double finishLat, double finishLon, List<Fix> drive) {
        final Geo geo = new Geo(startLat, startLon);
        final double fx = geo.x(finishLon), fy = geo.y(finishLat);

        // the moving part of the drive: from the first fix at speed to the last one
        int i0 = -1, i1 = -1;
        for (int i = 0; i < drive.size(); i++) {
            final Fix f = drive.get(i);
            if (f.usable() && f.speed >= MOVING_SPEED) {
                if (i0 < 0) i0 = i;
                i1 = i;
            }
        }
        if (i0 < 0) throw new IllegalArgumentException("The car did not move between the two marks.");

        final List<double[]> v = new ArrayList<double[]>();
        v.add(new double[]{0.0, 0.0});
        for (int i = i0; i <= i1; i++) {
            final Fix f = drive.get(i);
            if (!f.usable() || (f.hAcc > MAX_VERTEX_ACC_M)) continue;
            final double x = geo.x(f.lon), y = geo.y(f.lat);
            final double[] last = v.get(v.size() - 1);
            if (Math.hypot(x - last[0], y - last[1]) < DEDUP_M) continue;
            v.add(new double[]{x, y});
        }
        final double[] last = v.get(v.size() - 1);
        if (v.size() > 1 && Math.hypot(fx - last[0], fy - last[1]) < DEDUP_M) v.set(v.size() - 1, new double[]{fx, fy});
        else v.add(new double[]{fx, fy});
        if (v.size() < 3) throw new IllegalArgumentException("Too few GPS fixes between the two marks to make a route.");

        final double[] xs = new double[v.size()], ys = new double[v.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = v.get(i)[0];
            ys[i] = v.get(i)[1];
        }
        final Route raw = new Route(xs, ys);
        if (raw.length < MIN_ROUTE_M) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "The drive between the marks was only %.0f m. A stage needs at least %.0f m.", raw.length, MIN_ROUTE_M));
        }
        if (Math.hypot(fx, fy) < MIN_SEPARATION_M) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "The finish mark is only %.0f m from the start mark. A stage runs from one place to another, not round a loop.",
                    Math.hypot(fx, fy)));
        }

        final double s0 = START_SHIFT_M, s1 = raw.length - FINISH_SHIFT_M;
        final double[] pStart = raw.pointAt(s0), pFin = raw.pointAt(s1);
        final double[] pAfter = raw.pointAt(Math.min(s0 + BEARING_SPAN_M, s1));
        final double[] pBefore = raw.pointAt(Math.max(s1 - BEARING_SPAN_M, s0));
        final double bStart = Geo.bearing(pAfter[0] - pStart[0], pAfter[1] - pStart[1]);
        final double bFin = Geo.bearing(pFin[0] - pBefore[0], pFin[1] - pBefore[1]);

        // the route between the two lines
        final List<double[]> r = new ArrayList<double[]>();
        r.add(pStart);
        for (int i = 0; i < raw.n; i++) if (raw.s[i] > s0 + 0.01 && raw.s[i] < s1 - 0.01) r.add(new double[]{raw.x[i], raw.y[i]});
        r.add(pFin);
        final double[] rx = new double[r.size()], ry = new double[r.size()];
        for (int i = 0; i < rx.length; i++) {
            rx[i] = r.get(i)[0];
            ry[i] = r.get(i)[1];
        }
        final Route route = new Route(rx, ry);
        return new Stage(id, name, Stage.KIND_ROAD, createdUtcMs, geo,
                new Gate(pStart[0], pStart[1], bStart, Stage.GATE_HALF_WIDTH_M),
                new Gate(pFin[0], pFin[1], bFin, Stage.GATE_HALF_WIDTH_M),
                route, startLat, startLon, finishLat, finishLon);
    }
}
