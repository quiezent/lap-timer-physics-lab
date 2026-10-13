package app.tracklog;

/**
 * A timing line: a segment centred on a point, perpendicular to the direction of travel, counted only for a car going that way.
 * Plain Java with no Android classes.
 *
 * Finding the moment of crossing. GPS gives a fix once a second, and a car covers 25 to 60 m in that time, so the crossing happens
 * between two fixes. The path between them is taken as a cubic Hermite curve that passes through both fixes and has, at each end, the
 * velocity the phone reported (Doppler speed and course). That is the method LapTimer uses for laps, with the same arithmetic; the
 * difference is only in what is around it: a lap is the time between two crossings of ONE line, a stage is the time between crossings
 * of TWO lines, so an error in where a line is goes straight into the stage time (1 m is 0.033 s at 30 m/s).
 *
 * A crossing is accepted only when
 *  - the first fix is before the line and the second on or after it (so a car going the other way, or sitting still, does not count),
 *  - the car is moving (a car parked beside the line, drifting over it with the GPS noise, is not a crossing),
 *  - the curve crosses the line within the half width of the centre, and
 *  - (optional) the car is heading mostly forward through the line, not along it. The heading is the one the phone reports (the
 *    Doppler course) at the faster of the two fixes. The step between two fixes is not used for this: a car braking to a stop
 *    moves less between fixes than the GPS noise, and the step then points anywhere.
 */
final class Gate {

    private static final int STEPS = 16;
    private static final int BISECTIONS = 40;

    final double cx, cy;          // centre of the line, metres east / north of the frame origin
    final double bearingDeg;      // direction of travel
    final double ux, uy;          // the same as a unit vector (east, north)
    final double half;            // half width of the line, m

    Gate(double cx, double cy, double bearingDeg, double halfWidthM) {
        this.cx = cx;
        this.cy = cy;
        this.bearingDeg = bearingDeg;
        this.ux = Math.sin(Math.toRadians(bearingDeg));
        this.uy = Math.cos(Math.toRadians(bearingDeg));
        this.half = halfWidthM;
    }

    /** Metres from the line along the direction of travel: negative before it, positive after it. */
    double along(double x, double y) {
        return (x - cx) * ux + (y - cy) * uy;
    }

    /** Metres to the left of the centre of the line, looking along the direction of travel (negative = right). */
    double across(double x, double y) {
        return -(x - cx) * uy + (y - cy) * ux;
    }

    private double hx(double u, double dt, Pt a, Pt b) {
        final double u2 = u * u, u3 = u2 * u;
        final double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        return h00 * a.x + h10 * dt * a.vx + h01 * b.x + h11 * dt * b.vx;
    }

    private double hy(double u, double dt, Pt a, Pt b) {
        final double u2 = u * u, u3 = u2 * u;
        final double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        return h00 * a.y + h10 * dt * a.vy + h01 * b.y + h11 * dt * b.vy;
    }

    /**
     * The time at which the path from fix a to the next fix b crosses this line, on the clock of a.t and b.t; NaN if it does not.
     *
     * @param minSpeed    the larger of the two fix speeds must reach this (m/s), or the "crossing" is GPS noise
     * @param minForward  0 to switch the test off; otherwise the velocity the phone reported, at the faster of the two fixes, must be at
     *                    least this fraction forward (the cosine of the angle between the velocity and the direction of travel)
     */
    double crossing(Pt a, Pt b, double minSpeed, double minForward) {
        final double q0 = along(a.x, a.y), q1 = along(b.x, b.y);
        if (!(q0 < 0.0 && q1 >= 0.0)) return Double.NaN;
        if (Math.max(a.speed, b.speed) < minSpeed) return Double.NaN;
        if (minForward > 0.0) {
            final Pt f = a.speed >= b.speed ? a : b;
            final double vn = Math.hypot(f.vx, f.vy);
            if (!(vn > 0.0) || (f.vx * ux + f.vy * uy) / vn < minForward) return Double.NaN;
        }
        final double dt = b.t - a.t;
        if (!(dt > 0.0)) return Double.NaN;
        // the first sign change of the curve, then bisection
        double lo = 0.0, hi = 1.0;
        double prev = along(hx(0.0, dt, a, b), hy(0.0, dt, a, b));
        for (int i = 1; i <= STEPS; i++) {
            final double u = i / (double) STEPS;
            final double q = along(hx(u, dt, a, b), hy(u, dt, a, b));
            if (prev < 0.0 && q >= 0.0) {
                lo = (i - 1) / (double) STEPS;
                hi = u;
                break;
            }
            prev = q;
        }
        for (int i = 0; i < BISECTIONS; i++) {
            final double mid = 0.5 * (lo + hi);
            if (along(hx(mid, dt, a, b), hy(mid, dt, a, b)) < 0.0) lo = mid; else hi = mid;
        }
        final double u = 0.5 * (lo + hi);
        if (Math.abs(across(hx(u, dt, a, b), hy(u, dt, a, b))) > half) return Double.NaN;
        return a.t + u * dt;
    }
}
