package app.tracklog;

/**
 * A GPS fix placed in a local frame, with the velocity the timing curve needs: the position (x east, y north, metres), the velocity
 * (vx, vy, m/s) and the speed. The velocity comes from the fix's Doppler speed and course when the phone gave them, otherwise from the
 * step to the previous fix. This is exactly what LapTimer does with each fix.
 */
final class Pt {
    final double t, x, y, vx, vy, speed;

    Pt(double t, double x, double y, double vx, double vy, double speed) {
        this.t = t;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.speed = speed;
    }

    static Pt make(Geo g, Fix f, Pt prev) {
        final double x = g.x(f.lon), y = g.y(f.lat);
        double vx = 0.0, vy = 0.0;
        if (f.speed >= 0.0 && f.bearing >= 0.0 && !Double.isNaN(f.speed) && !Double.isNaN(f.bearing)) {
            vx = f.speed * Math.sin(Math.toRadians(f.bearing));
            vy = f.speed * Math.cos(Math.toRadians(f.bearing));
        } else if (prev != null && f.t > prev.t) {
            vx = (x - prev.x) / (f.t - prev.t);
            vy = (y - prev.y) / (f.t - prev.t);
        }
        final double sp = f.speed >= 0.0 ? f.speed : Math.hypot(vx, vy);
        return new Pt(f.t, x, y, vx, vy, sp);
    }
}
