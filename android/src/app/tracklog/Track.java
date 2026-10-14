package app.tracklog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The path a car took in a recording, cleaned, as a polyline with the distance along it at every vertex. Plain Java with no Android
 * classes. Section 11.1 and 11.2 of docs/stage-algorithm.md.
 *
 * A Track is what the pin screen draws and what the pin builder puts the start and finish pins on. It is made from the recording's own
 * fixes and is not saved anywhere: it can always be made again from the file.
 *
 * Cleaning: a fix that is not accurate (worse than 30 m) or not moving (a known speed under 1 m/s: a parked phone reports a position
 * that is mostly noise) is left out, and a fix less than 3 m from the last vertex adds nothing but GPS noise and is left out too.
 *
 * It also remembers the holes in the recording: two fixes in a row, kept or not, more than 3 s apart (the scorer only accepts a line
 * crossed between fixes at most 3 s apart, so a line in a hole cannot be timed), so that the pin builder can refuse a pin in one.
 */
final class Track {

    static final double MAX_ACC_M = 30.0;
    static final double MIN_SPEED = 1.0;
    static final double DEDUP_M = 3.0;
    static final int MIN_VERTICES = 3;
    static final double SNAP_MAX_M = 60.0;          // a place further than this from the drive is not on it
    static final double PASS_WINDOW_M = 10.0;       // a pass this much further from the place than the nearest pass is another road
    static final double GAP_S = 20.0, GAP_M = 100.0; // two vertices further apart than both are a gap in the GPS
    static final double HOLE_S = StageRun.MAX_FIX_GAP_S;   // the scorer accepts a crossing only between fixes at most this far apart (section 6)
    static final double BREAK_S = 300.0, BREAK_M = 100.0;   // a hole this long, with the car this far apart across it, is a break between two drives

    /** A place where the track passes a given place: the position along the track and how far the place is from the road. */
    static final class Pass {
        final double s, offsetM;

        Pass(double s, double offsetM) {
            this.s = s;
            this.offsetM = offsetM;
        }
    }

    final Geo geo;                      // the track frame: origin at vertex 0
    final Route route;                  // the vertices in that frame, with the distance along
    final double[] lat, lon, t;         // per vertex: the fix's position and time
    final int n;
    final double length;
    private final List<double[]> holes;  // each pair of fixes in a row more than HOLE_S apart: {time of the first, time of the second, metres between them}

    private Track(Geo geo, Route route, double[] lat, double[] lon, double[] t, List<double[]> holes) {
        this.geo = geo;
        this.route = route;
        this.lat = lat;
        this.lon = lon;
        this.t = t;
        this.n = route.n;
        this.length = route.length;
        this.holes = holes;
    }

    /** The fixes of a recording in time order, without unusable ones and without a fix that is not later than the one before. */
    static List<Fix> tidy(List<Fix> fixes) {
        final List<Fix> sorted = new ArrayList<Fix>();
        for (Fix f : fixes) if (f != null && f.usable()) sorted.add(f);
        Collections.sort(sorted, new Comparator<Fix>() {
            @Override
            public int compare(Fix a, Fix b) {
                return Double.compare(a.t, b.t);
            }
        });
        final List<Fix> out = new ArrayList<Fix>();
        for (Fix f : sorted) if (out.isEmpty() || f.t > out.get(out.size() - 1).t) out.add(f);
        return out;
    }

    /** @throws PinBuilder.Refused (category no_drive) if the recording holds no drive */
    static Track of(List<Fix> fixes) {
        final List<Fix> all = tidy(fixes);
        final List<Fix> kept = new ArrayList<Fix>();
        for (Fix f : all) {
            if (f.hAcc > MAX_ACC_M) continue;
            if (f.speed >= 0.0 && f.speed < MIN_SPEED) continue;
            kept.add(f);
        }
        final List<Fix> v = new ArrayList<Fix>();
        if (!kept.isEmpty()) {
            final Geo g = new Geo(kept.get(0).lat, kept.get(0).lon);
            double lx = 0.0, ly = 0.0;
            for (Fix f : kept) {
                final double x = g.x(f.lon), y = g.y(f.lat);
                if (!v.isEmpty() && Math.hypot(x - lx, y - ly) < DEDUP_M) continue;
                v.add(f);
                lx = x;
                ly = y;
            }
        }
        if (v.size() < MIN_VERTICES) {
            throw new PinBuilder.Refused("no_drive", "There is no drive in this recording: the car hardly moved, or the GPS fixes were poor.");
        }
        final Geo geo = new Geo(v.get(0).lat, v.get(0).lon);
        final double[] x = new double[v.size()], y = new double[v.size()];
        final double[] la = new double[v.size()], lo = new double[v.size()], tt = new double[v.size()];
        for (int i = 0; i < x.length; i++) {
            final Fix f = v.get(i);
            x[i] = geo.x(f.lon);
            y[i] = geo.y(f.lat);
            la[i] = f.lat;
            lo[i] = f.lon;
            tt[i] = f.t;
        }
        final List<double[]> holes = new ArrayList<double[]>();
        for (int k = 1; k < all.size(); k++) {
            final Fix p = all.get(k - 1), q = all.get(k);
            if (q.t - p.t > HOLE_S) holes.add(new double[]{p.t, q.t, Math.hypot(geo.x(q.lon) - geo.x(p.lon), geo.y(q.lat) - geo.y(p.lat))});
        }
        return new Track(geo, new Route(x, y), la, lo, tt, holes);
    }

    /** The time at distance d along the track (d is clamped into the track): the times of the two vertices either side, in proportion. */
    double timeAt(double d) {
        final int j = route.segmentAt(d);
        final double dd = Math.max(0.0, Math.min(length, d));
        final double len = route.s[j + 1] - route.s[j];
        final double f = len > 0.0 ? (dd - route.s[j]) / len : 0.0;
        return t[j] + f * (t[j + 1] - t[j]);
    }

    /**
     * How many seconds apart the two fixes in a row are that the car was between when it was at distance d along the track, if they are
     * more than HOLE_S apart (no line at d can be timed from this recording); 0 if the fixes there are close together.
     */
    double holeAt(double d) {
        final double tp = timeAt(d);
        double worst = 0.0;
        for (double[] h : holes) if (h[0] <= tp && tp <= h[1]) worst = Math.max(worst, h[1] - h[0]);
        return worst;
    }

    /** The first break (a hole of more than BREAK_S, the car more than BREAK_M apart across it) between distances a and b: {seconds, metres}; null if none. */
    double[] breakBetween(double a, double b) {
        final double ta = timeAt(a), tb = timeAt(b);
        for (double[] h : holes) {
            if (h[1] - h[0] > BREAK_S && h[2] > BREAK_M && h[1] > ta && h[0] < tb) return new double[]{h[1] - h[0], h[2]};
        }
        return null;
    }

    /** {lat, lon} of the point at distance d along the track (d is clamped into the track). */
    double[] latLonAt(double d) {
        final double[] p = route.pointAt(d);
        return new double[]{geo.lat(p[1]), geo.lon(p[0])};
    }

    /**
     * The places where the track passes a place, in order along the track (section 11.2). Empty if it never comes within the snap limit.
     * Only the passes about as close as the closest one are kept: when the drive passes the place several times at very different
     * distances, the others are not the same road (the other leg of a hairpin, the other carriageway), and the driver meant the nearest.
     */
    List<Pass> passes(double placeLat, double placeLon) {
        final double px = geo.x(placeLon), py = geo.y(placeLat);
        final List<Pass> out = new ArrayList<Pass>();
        boolean inPass = false;
        double bestD = 0.0, bestS = 0.0;
        for (int j = 0; j < n - 1; j++) {
            final double[] near = nearest(j, px, py);
            if (near[0] <= SNAP_MAX_M) {
                if (!inPass) {
                    inPass = true;
                    bestD = near[0];
                    bestS = near[1];
                } else if (near[0] < bestD) {
                    bestD = near[0];
                    bestS = near[1];
                }
            } else if (inPass) {
                out.add(new Pass(bestS, bestD));
                inPass = false;
            }
        }
        if (inPass) out.add(new Pass(bestS, bestD));
        double nearest = Double.MAX_VALUE;
        for (Pass q : out) nearest = Math.min(nearest, q.offsetM);
        final List<Pass> kept = new ArrayList<Pass>();
        for (Pass q : out) if (q.offsetM <= nearest + PASS_WINDOW_M) kept.add(q);
        return kept;
    }

    /** How close the track comes to a place, in metres. */
    double closestM(double placeLat, double placeLon) {
        final double px = geo.x(placeLon), py = geo.y(placeLat);
        double best = Double.MAX_VALUE;
        for (int j = 0; j < n - 1; j++) best = Math.min(best, nearest(j, px, py)[0]);
        return best;
    }

    /** {distance from the point to segment j, the distance along the track of its nearest point} */
    private double[] nearest(int j, double px, double py) {
        final double ax = route.x[j], ay = route.y[j];
        final double dx = route.x[j + 1] - ax, dy = route.y[j + 1] - ay;
        final double len2 = dx * dx + dy * dy;
        double f = len2 > 0.0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0.0;
        f = Math.max(0.0, Math.min(1.0, f));
        final double qx = ax + f * dx, qy = ay + f * dy;
        return new double[]{Math.hypot(px - qx, py - qy), route.s[j] + f * Math.sqrt(len2)};
    }

    /** Gaps in the GPS between distance a and distance b along the track: {distance where the gap began, seconds}. */
    List<double[]> gaps(double a, double b) {
        final List<double[]> out = new ArrayList<double[]>();
        for (int j = 0; j < n - 1; j++) {
            if (route.s[j + 1] <= a || route.s[j] >= b) continue;
            final double dt = t[j + 1] - t[j], dm = route.s[j + 1] - route.s[j];
            if (dt > GAP_S && dm > GAP_M) out.add(new double[]{route.s[j], dt});
        }
        return out;
    }
}
