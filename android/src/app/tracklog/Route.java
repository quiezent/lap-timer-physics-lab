package app.tracklog;

/**
 * The reference path of a stage: a polyline from the start line to the finish line, with the distance along it at every vertex.
 * Plain Java with no Android classes.
 *
 * Its job is to give every run the same ruler. A GPS fix is placed on the route (its "route distance") and the time at which each
 * run reached each distance is what gets compared. Lateral GPS noise does not matter, because only the distance along the route is
 * used, and the route is the same for every run, so a systematic error in it (a corner cut by the 1 Hz fixes) is shared by all runs
 * and cancels in a comparison. Route distance is therefore a consistent coordinate, not a surveyed length: it comes out a little
 * short on bends.
 */
final class Route {

    final double[] x, y, s;      // vertices (metres in the stage frame) and the distance along the route to each
    final int n;                 // number of vertices
    final double length;         // metres

    Route(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) throw new IllegalArgumentException("a route needs at least two points");
        this.n = x.length;
        this.x = x.clone();
        this.y = y.clone();
        this.s = new double[n];
        for (int i = 1; i < n; i++) s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        this.length = s[n - 1];
    }

    /** Index j of the segment (j, j+1) that contains route distance d (clamped into the route). */
    int segmentAt(double d) {
        if (d <= 0.0) return 0;
        if (d >= length) return n - 2;
        int lo = 0, hi = n - 1;                    // s[lo] <= d < s[hi]
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (s[mid] <= d) lo = mid; else hi = mid;
        }
        return lo;
    }

    /** The point at route distance d (clamped), as {x, y}. */
    double[] pointAt(double d) {
        final double dd = Math.max(0.0, Math.min(length, d));
        final int j = segmentAt(dd);
        final double seg = s[j + 1] - s[j];
        final double f = seg > 0.0 ? (dd - s[j]) / seg : 0.0;
        return new double[]{x[j] + f * (x[j + 1] - x[j]), y[j] + f * (y[j + 1] - y[j])};
    }

    /** Compass bearing of the route at route distance d. */
    double bearingAt(double d) {
        final int j = segmentAt(Math.max(0.0, Math.min(length, d)));
        return Geo.bearing(x[j + 1] - x[j], y[j + 1] - y[j]);
    }

    /**
     * Places a point on the route. Only the segments from (hint - behind) to (hint + ahead) are looked at, so a route that doubles
     * back near itself (a hairpin) cannot pull a fix onto the wrong leg. Returns the route distance of the nearest point in that
     * window; out[0] is the distance from the route (metres), out[1] the route's compass bearing there.
     *
     * When the car's heading is known (headingDeg, compass degrees, NaN if not) a segment that runs the other way counts as
     * further away, by up to 40 m: the two legs of a hairpin run in opposite directions, so heading tells them apart where distance
     * alone, with several metres of GPS noise, may not.
     */
    double project(double px, double py, double hint, double behind, double ahead, double headingDeg, double[] out) {
        final int j0 = segmentAt(Math.max(0.0, hint - behind));
        final int j1 = segmentAt(Math.min(length, hint + ahead));
        double bestScore = Double.MAX_VALUE, bestD2 = Double.MAX_VALUE, bestS = Math.max(0.0, Math.min(length, hint));
        int bestJ = j0;
        for (int j = j0; j <= j1 && j < n - 1; j++) {
            final double ax = x[j], ay = y[j];
            final double dx = x[j + 1] - ax, dy = y[j + 1] - ay;
            final double len2 = dx * dx + dy * dy;
            double f = len2 > 0.0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0.0;
            f = Math.max(0.0, Math.min(1.0, f));
            final double qx = ax + f * dx, qy = ay + f * dy;
            final double d2 = (px - qx) * (px - qx) + (py - qy) * (py - qy);
            double score = Math.sqrt(d2);
            if (!Double.isNaN(headingDeg) && len2 > 0.0) {
                final double diff = Geo.angleDiff(headingDeg, Geo.bearing(dx, dy));
                if (diff > TURN_FREE_DEG) score += HEADING_PENALTY_M * (diff - TURN_FREE_DEG) / (180.0 - TURN_FREE_DEG);
            }
            if (score < bestScore) {
                bestScore = score;
                bestD2 = d2;
                bestS = s[j] + f * Math.sqrt(len2);
                bestJ = j;
            }
        }
        if (out != null) {
            out[0] = Math.sqrt(bestD2);
            out[1] = Geo.bearing(x[bestJ + 1] - x[bestJ], y[bestJ + 1] - y[bestJ]);
        }
        return bestS;
    }

    /** The same without a heading. */
    double project(double px, double py, double hint, double behind, double ahead, double[] out) {
        return project(px, py, hint, behind, ahead, Double.NaN, out);
    }

    static final double TURN_FREE_DEG = 60.0;       // a segment within this of the car's heading costs nothing extra
    static final double HEADING_PENALTY_M = 40.0;   // a segment running the opposite way counts this much further away
}
