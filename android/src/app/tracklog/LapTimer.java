package app.tracklog;

import java.util.Locale;

/**
 * Lap times from GPS: every pass of one fixed line across the track is a timing point, and a lap is the time between
 * two passes. Plain Java with no Android classes, so the same code can be replayed on a computer against a recorded log.
 *
 * The line. It is a point and a direction of travel, with a half width. The built-in one is on the main straight of
 * Sepang (full Grand Prix layout), about 305 m after the exit of T15 and 580 m before the T1 braking zone, where a
 * circuit map puts the start of the lap. It is NOT surveyed and may be up to a hundred metres or so from the official
 * timing loop. That hardly matters: a lap is the time between two passes of the SAME line, so moving the line along a
 * straight changes a lap by a few hundredths of a second (the time to cover those metres hardly varies from lap to lap).
 * Lap-to-lap differences are exact whatever the line.
 *
 * Accuracy. A GPS fix arrives once a second, and the car covers 60 m in that time on the straight. The crossing is
 * found on a cubic curve through the two fixes either side of the line that uses each fix's position AND its velocity
 * (speed and course), which is accurate to a few hundredths of a second. Every fix is late by the same amount, and
 * that amount cancels in a lap time.
 *
 * Nothing here can throw into the recorder: the caller wraps it, and a bad fix is ignored.
 */
final class LapTimer {

    /** Sepang, full GP layout, main straight. Driving direction is west-south-west (the circuit runs clockwise). */
    static final double SEPANG_LAT = 2.760726;
    static final double SEPANG_LON = 101.738380;
    static final double SEPANG_BEARING_DEG = 265.0;

    static final double HALF_WIDTH_M = 40.0;      // a pass further than this from the line point is not this line
    static final double MIN_LAP_S = 30.0;         // two passes closer than this are one pass seen twice (GPS jitter)
    static final double MAX_FIX_GAP_S = 3.0;      // longer without a fix and the crossing is not trusted
    static final double MIN_SPEED_MS = 3.0;       // a parked car drifting over the line is not a lap
    static final int KEEP = 300;                  // laps remembered for the list
    private static final double R = 6378137.0;

    private final double lat0, lon0, ux, uy, cosLat0;

    private boolean havePrev;
    private double pt, px, py, pvx, pvy, pspeed;
    private double lastCross = Double.NaN;        // fix-clock seconds of the last pass
    private int laps;
    private double lastLap = Double.NaN, bestLap = Double.NaN;
    private int bestNo;
    private final double[] times = new double[KEEP];
    private int stored;

    LapTimer() {
        this(SEPANG_LAT, SEPANG_LON, SEPANG_BEARING_DEG);
    }

    LapTimer(double lat, double lon, double bearingDeg) {
        lat0 = lat;
        lon0 = lon;
        cosLat0 = Math.cos(Math.toRadians(lat));
        ux = Math.sin(Math.toRadians(bearingDeg));      // east component of the direction of travel
        uy = Math.cos(Math.toRadians(bearingDeg));      // north component
    }

    /**
     * Feeds one GPS fix: its time (any clock, in seconds), position, speed (m/s, negative if unknown) and course
     * (degrees clockwise from north, negative if unknown). Returns true when this fix revealed a pass of the line.
     */
    boolean onFix(double t, double lat, double lon, double speed, double bearingDeg) {
        if (Double.isNaN(t) || Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90.0 || Math.abs(lon) > 180.0) return false;
        final double x = Math.toRadians(lon - lon0) * R * cosLat0;
        final double y = Math.toRadians(lat - lat0) * R;
        double vx = 0.0, vy = 0.0;
        if (speed >= 0.0 && bearingDeg >= 0.0 && !Double.isNaN(speed) && !Double.isNaN(bearingDeg)) {
            vx = speed * Math.sin(Math.toRadians(bearingDeg));
            vy = speed * Math.cos(Math.toRadians(bearingDeg));
        } else if (havePrev && t > pt) {
            vx = (x - px) / (t - pt);
            vy = (y - py) / (t - pt);
        }
        final double sp = speed >= 0.0 ? speed : Math.hypot(vx, vy);
        boolean pass = false;
        if (havePrev) {
            if (t <= pt) return false;                  // out of order or repeated: keep the earlier fix as the reference
            if (t - pt <= MAX_FIX_GAP_S) pass = checkPass(pt, px, py, pvx, pvy, pspeed, t, x, y, vx, vy, sp);
        }
        pt = t; px = x; py = y; pvx = vx; pvy = vy; pspeed = sp;
        havePrev = true;
        return pass;
    }

    /** Position of the cubic Hermite curve through (t0, p0, v0) and (t1, p1, v1), at u in [0, 1]: along the line, and across it. */
    private double along(double u, double dt, double x0, double y0, double vx0, double vy0, double x1, double y1, double vx1, double vy1) {
        final double u2 = u * u, u3 = u2 * u;
        final double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        final double x = h00 * x0 + h10 * dt * vx0 + h01 * x1 + h11 * dt * vx1;
        final double y = h00 * y0 + h10 * dt * vy0 + h01 * y1 + h11 * dt * vy1;
        return x * ux + y * uy;
    }

    private double across(double u, double dt, double x0, double y0, double vx0, double vy0, double x1, double y1, double vx1, double vy1) {
        final double u2 = u * u, u3 = u2 * u;
        final double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        final double x = h00 * x0 + h10 * dt * vx0 + h01 * x1 + h11 * dt * vx1;
        final double y = h00 * y0 + h10 * dt * vy0 + h01 * y1 + h11 * dt * vy1;
        return -x * uy + y * ux;
    }

    private boolean checkPass(double t0, double x0, double y0, double vx0, double vy0, double s0,
                              double t1, double x1, double y1, double vx1, double vy1, double s1) {
        final double q0 = x0 * ux + y0 * uy, q1 = x1 * ux + y1 * uy;
        if (!(q0 < 0.0 && q1 >= 0.0)) return false;                       // not from before the line to after it
        if (Math.max(s0, s1) < MIN_SPEED_MS) return false;
        final double dt = t1 - t0;
        // the first sign change of the curve, then bisection
        double lo = 0.0, hi = 1.0;
        final int steps = 16;
        double prev = along(0.0, dt, x0, y0, vx0, vy0, x1, y1, vx1, vy1);
        for (int i = 1; i <= steps; i++) {
            final double u = i / (double) steps;
            final double q = along(u, dt, x0, y0, vx0, vy0, x1, y1, vx1, vy1);
            if (prev < 0.0 && q >= 0.0) {
                lo = (i - 1) / (double) steps;
                hi = u;
                break;
            }
            prev = q;
        }
        for (int i = 0; i < 40; i++) {
            final double mid = 0.5 * (lo + hi);
            if (along(mid, dt, x0, y0, vx0, vy0, x1, y1, vx1, vy1) < 0.0) lo = mid; else hi = mid;
        }
        final double u = 0.5 * (lo + hi);
        if (Math.abs(across(u, dt, x0, y0, vx0, vy0, x1, y1, vx1, vy1)) > HALF_WIDTH_M) return false;
        final double tc = t0 + u * dt;
        if (!Double.isNaN(lastCross) && tc - lastCross < MIN_LAP_S) return false;
        if (!Double.isNaN(lastCross)) {
            final double lap = tc - lastCross;
            laps++;
            lastLap = lap;
            if (Double.isNaN(bestLap) || lap < bestLap) {
                bestLap = lap;
                bestNo = laps;
            }
            if (stored < KEEP) times[stored++] = lap;
        }
        lastCross = tc;
        return true;
    }

    // ------------------------------------------------------------------ results

    /** Complete laps so far (the first pass only starts the clock, so it is not a lap). */
    int laps() { return laps; }
    double lastLap() { return lastLap; }
    double bestLap() { return bestLap; }
    int bestLapNo() { return bestNo; }
    /** Time of the last pass on the clock of the fixes, or NaN before the first pass. */
    double lastPass() { return lastCross; }
    /** Time of lap number n (1-based), or NaN. */
    double lap(int n) { return (n >= 1 && n <= stored) ? times[n - 1] : Double.NaN; }

    // ------------------------------------------------------------------ text

    /** 2:33.05 (hundredths, rounded once so 59.996 cannot print as 0:60.00). */
    static String fmt(double s) {
        if (Double.isNaN(s) || s < 0.0 || s > 5999.0) return "--:--.--";
        final long cs = Math.round(s * 100.0);
        return String.format(Locale.US, "%d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    /** Tenths, for a running clock: 1:12.3 */
    static String fmtTenths(double s) {
        if (Double.isNaN(s) || s < 0.0 || s > 5999.0) return "-:--.-";
        final long ds = (long) Math.floor(s * 10.0);
        return String.format(Locale.US, "%d:%02d.%d", ds / 600, (ds / 10) % 60, ds % 10);
    }

    /** "+0.35" / "-0.12": a lap against the best. */
    static String fmtDelta(double d) {
        if (Double.isNaN(d)) return "";
        return String.format(Locale.US, "%+.2f", Math.round(d * 100.0) / 100.0);
    }

    /** The most recent laps, newest first, one per line: "L12  2:33.05  best", "L11  2:36.80  +3.75". */
    String listText(int max) {
        final StringBuilder b = new StringBuilder();
        int shown = 0;
        for (int n = Math.min(laps, stored); n >= 1 && shown < max; n--, shown++) {
            final double lap = times[n - 1];
            if (b.length() > 0) b.append('\n');
            b.append(String.format(Locale.US, "L%-3d %s  ", n, fmt(lap)));
            b.append(n == bestNo ? "best" : fmtDelta(lap - bestLap));
        }
        return b.toString();
    }
}
