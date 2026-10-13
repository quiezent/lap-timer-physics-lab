package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test code only: a synthetic road (straights and arcs, exact geometry) and a driver on it, sampled the way a phone's GPS would
 * sample it. Because the road and the driver are formulas, the exact time at which the car passes any point is known, and the stage
 * code can be judged against it.
 *
 * The road runs from s = -pre to s = total + post (metres). s = 0 is where the driver parks to mark the start, s = total is where the
 * driver parks to mark the finish. Curvature is positive for a right turn (compass heading increases with s).
 */
final class SynthRoad {
    final double lat0, lon0;
    final Geo geo;
    final double pre, total, post;
    private final double[] segS, segK, segH, segX, segY;   // per segment: start s (from -pre), curvature, heading (rad), x, y
    private final int nSeg;
    final double sMin, sMax;

    /** @param segs rows {length_m, radius_m}: radius > 0 right turn, < 0 left turn, 0 straight */
    SynthRoad(double lat0, double lon0, double startHeadingDeg, double[][] segs, double pre, double post) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.geo = new Geo(lat0, lon0);
        this.pre = pre;
        this.post = post;
        double len = 0;
        for (double[] sg : segs) len += sg[0];
        this.total = len;
        // pre-straight, the segments, post-straight
        nSeg = segs.length + 2;
        segS = new double[nSeg + 1];
        segK = new double[nSeg];
        segH = new double[nSeg];
        segX = new double[nSeg];
        segY = new double[nSeg];
        final double[] lens = new double[nSeg];
        lens[0] = pre;
        segK[0] = 0;
        for (int i = 0; i < segs.length; i++) {
            lens[i + 1] = segs[i][0];
            segK[i + 1] = segs[i][1] == 0 ? 0.0 : 1.0 / segs[i][1];
        }
        lens[nSeg - 1] = post;
        segK[nSeg - 1] = 0;
        // integrate from the start of the pre-straight; the road is then shifted so that s = 0 is at x = y = 0
        double x = 0, y = 0, h = Math.toRadians(startHeadingDeg);
        for (int i = 0; i < nSeg; i++) {
            segS[i] = (i == 0 ? 0.0 : segS[i - 1] + lens[i - 1]);
            segH[i] = h;
            segX[i] = x;
            segY[i] = y;
            final double k = segK[i];
            final double h1 = h + k * lens[i];
            if (k == 0.0) {
                x += Math.sin(h) * lens[i];
                y += Math.cos(h) * lens[i];
            } else {
                x += (Math.cos(h) - Math.cos(h1)) / k;
                y += (Math.sin(h1) - Math.sin(h)) / k;
            }
            h = h1;
        }
        segS[nSeg] = segS[nSeg - 1] + lens[nSeg - 1];
        // shift so that s = 0 (index position pre) is the origin
        final double[] o = rawPos(pre);
        for (int i = 0; i < nSeg; i++) {
            segX[i] -= o[0];
            segY[i] -= o[1];
        }
        sMin = -pre;
        sMax = total + post;
    }

    private int seg(double sRaw) {
        int j = 0;
        while (j < nSeg - 1 && segS[j + 1] <= sRaw) j++;
        return j;
    }

    private double[] rawPos(double sRaw) {
        final double d = Math.max(0.0, Math.min(segS[nSeg], sRaw));
        final int j = seg(d);
        final double ds = d - segS[j], k = segK[j], h = segH[j];
        if (k == 0.0) return new double[]{segX[j] + Math.sin(h) * ds, segY[j] + Math.cos(h) * ds};
        final double h1 = h + k * ds;
        return new double[]{segX[j] + (Math.cos(h) - Math.cos(h1)) / k, segY[j] + (Math.sin(h1) - Math.sin(h)) / k};
    }

    /** Position (x east, y north, metres from the start mark) of the road centre line at s. */
    double[] pos(double s) {
        return rawPos(s + pre);
    }

    /** Compass heading in degrees at s. */
    double heading(double s) {
        final double d = Math.max(0.0, Math.min(segS[nSeg], s + pre));
        final int j = seg(d);
        final double h = segH[j] + segK[j] * (d - segS[j]);
        return ((Math.toDegrees(h) % 360.0) + 360.0) % 360.0;
    }

    double curvature(double s) {
        return segK[seg(Math.max(0.0, Math.min(segS[nSeg], s + pre)))];
    }

    double lat(double y) {
        return geo.lat(y);
    }

    double lon(double x) {
        return geo.lon(x);
    }

    // ------------------------------------------------------------------ a driver

    /** One drive over the road: speed at every metre of s from sFrom to sTo, and the time at which each metre is reached. */
    final class Drive {
        final double sFrom, sTo, ds;
        final double[] v, t;           // at s = sFrom + i * ds; t starts at 0 at sFrom

        Drive(double sFrom, double sTo, double ds, double[] v) {
            this.sFrom = sFrom;
            this.sTo = sTo;
            this.ds = ds;
            this.v = v;
            this.t = new double[v.length];
            for (int i = 1; i < v.length; i++) t[i] = t[i - 1] + 2.0 * ds / (v[i] + v[i - 1]);
        }

        private double idx(double s) {
            return Math.max(0.0, Math.min(v.length - 1.0, (s - sFrom) / ds));
        }

        double timeAt(double s) {
            final double f = idx(s);
            final int i = (int) Math.min(Math.floor(f), v.length - 2);
            return t[i] + (f - i) * (t[i + 1] - t[i]);
        }

        double speedAt(double s) {
            final double f = idx(s);
            final int i = (int) Math.min(Math.floor(f), v.length - 2);
            return v[i] + (f - i) * (v[i + 1] - v[i]);
        }

        double duration() {
            return t[t.length - 1];
        }

        /** The distance reached after time tt (seconds after sFrom), by inverting timeAt. */
        double sAtTime(double tt) {
            if (tt <= 0) return sFrom;
            if (tt >= t[t.length - 1]) return sTo;
            int lo = 0, hi = t.length - 1;
            while (hi - lo > 1) {
                final int mid = (lo + hi) >>> 1;
                if (t[mid] <= tt) lo = mid; else hi = mid;
            }
            final double f = (tt - t[lo]) / (t[hi] - t[lo]);
            return sFrom + (lo + f) * ds;
        }
    }

    /**
     * A drive limited by cornering grip (speed <= sqrt(aLat / |curvature|)), by a top speed, and by acceleration and braking.
     *
     * @param scale   multiplies the speed limits (1.03 is a driver 3 % faster everywhere)
     * @param tweak   optional extra factor on the limit as a function of s (null for none), e.g. 0.9 in one section
     */
    Drive drive(double sFrom, double sTo, double v0, double vEnd, double vmax, double aLat, double aAcc, double aBrk,
                double scale, java.util.function.DoubleUnaryOperator tweak) {
        final double ds = 1.0;
        final int n = (int) Math.round((sTo - sFrom) / ds) + 1;
        final double[] lim = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            final double s = sFrom + i * ds;
            final double k = Math.abs(curvature(s));
            double l = Math.min(vmax, k > 1e-9 ? Math.sqrt(aLat / k) : vmax) * scale;
            if (tweak != null) l *= tweak.applyAsDouble(s);
            lim[i] = Math.max(l, 1.0);
        }
        v[0] = Math.min(Math.max(v0, 0.3), lim[0]);
        for (int i = 1; i < n; i++) v[i] = Math.min(lim[i], Math.sqrt(v[i - 1] * v[i - 1] + 2 * aAcc * ds));
        v[n - 1] = Math.min(v[n - 1], Math.max(vEnd, 0.3));
        for (int i = n - 2; i >= 0; i--) v[i] = Math.min(v[i], Math.sqrt(v[i + 1] * v[i + 1] + 2 * aBrk * ds));
        return new Drive(sFrom, sTo, ds, v);
    }

    /**
     * Fixes of a car on this road: the car sits at sFrom until tDepart, then follows the drive, then sits at sTo.
     * The clock t is in seconds; fixes come every second at tFirst, tFirst + 1, ... up to tLast.
     *
     * @param sigma  GPS position noise (1 sigma per axis, metres), AR(1) with coefficient rho
     * @param lateral metres to the left of the centre line (+) or right (-)
     */
    List<Fix> fixes(Drive d, double tDepart, double tFirst, double tLast, double sigma, double rho, double lateral, Random rnd) {
        final List<Fix> out = new ArrayList<Fix>();
        double nx = 0, ny = 0;
        final double inno = Math.sqrt(1.0 - rho * rho);
        for (double t = tFirst; t <= tLast + 1e-9; t += 1.0) {
            final double s = d.sAtTime(t - tDepart);
            final double speed = (t - tDepart <= 0 || t - tDepart >= d.duration()) ? 0.0 : d.speedAt(s);
            final double[] p = pos(s);
            final double hd = Math.toRadians(heading(s));
            final double px = p[0] - Math.cos(hd) * lateral, py = p[1] + Math.sin(hd) * lateral;
            if (sigma > 0) {
                nx = rho * nx + inno * sigma * rnd.nextGaussian();
                ny = rho * ny + inno * sigma * rnd.nextGaussian();
            }
            final double sp = speed > 0.05 ? Math.max(0.0, speed + (sigma > 0 ? 0.12 * rnd.nextGaussian() : 0.0)) : (sigma > 0 ? Math.abs(0.05 * rnd.nextGaussian()) : 0.0);
            final double brg = speed >= 0.8 ? ((heading(s) + (sigma > 0 ? 2.0 * rnd.nextGaussian() : 0.0)) % 360.0 + 360.0) % 360.0 : -1.0;
            out.add(new Fix(t, geo.lat(py + ny), geo.lon(px + nx), sp, brg, 3.5));
        }
        return out;
    }

    /**
     * Fixes of a car whose distance along the road is any function of time (it may stop, reverse, turn round), one per second from
     * tFirst to tLast. Noise as in fixes(). Speed is the numerical derivative of s; bearing follows the direction of motion.
     */
    List<Fix> fixesFn(java.util.function.DoubleUnaryOperator sOfT, double tFirst, double tLast, double sigma, double rho, Random rnd) {
        final List<Fix> out = new ArrayList<Fix>();
        double nx = 0, ny = 0;
        final double inno = Math.sqrt(1.0 - rho * rho);
        for (double t = tFirst; t <= tLast + 1e-9; t += 1.0) {
            final double s = sOfT.applyAsDouble(t);
            final double ds = sOfT.applyAsDouble(t + 0.05) - sOfT.applyAsDouble(t - 0.05);
            final double v = ds / 0.1;
            final double[] p = pos(s);
            if (sigma > 0) {
                nx = rho * nx + inno * sigma * rnd.nextGaussian();
                ny = rho * ny + inno * sigma * rnd.nextGaussian();
            }
            final double dir = heading(s) + (v < 0 ? 180.0 : 0.0);
            final double sp = Math.abs(v);
            final double brg = sp >= 0.8 ? (dir % 360.0 + 360.0) % 360.0 : -1.0;
            out.add(new Fix(t, geo.lat(p[1] + ny), geo.lon(p[0] + nx), sp, brg, 3.5));
        }
        return out;
    }

    /** The default 5 km mountain-ish road used by most tests: straights, bends, a pair of hairpins. */
    static SynthRoad mountain() {
        return new SynthRoad(3.5600, 101.6600, 40.0, new double[][]{
                {350, 0}, {120, 80}, {300, 0}, {100, -60}, {420, 0}, {90, 45}, {260, 0},
                {75, 25}, {160, 0}, {75, -25}, {400, 0}, {110, -70}, {330, 0}, {130, 90}, {500, 0},
                {85, 30}, {120, 0}, {85, -30}, {450, 0}, {150, -120}, {400, 0}}, 700, 500);
    }
}
