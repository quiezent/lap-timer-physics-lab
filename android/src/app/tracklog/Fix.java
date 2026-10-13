package app.tracklog;

/** One GPS fix as the stage code sees it. Plain data. Unknown values are negative (speed, bearing, hAcc), as in the log. */
final class Fix {
    final double t;          // seconds on any one clock (the recorder uses elapsed realtime)
    final double lat, lon;   // degrees
    final double speed;      // m/s over ground, negative if unknown
    final double bearing;    // degrees clockwise from north, negative if unknown
    final double hAcc;       // metres (68 % radius), negative if unknown

    Fix(double t, double lat, double lon, double speed, double bearing, double hAcc) {
        this.t = t;
        this.lat = lat;
        this.lon = lon;
        this.speed = speed;
        this.bearing = bearing;
        this.hAcc = hAcc;
    }

    /** False for a fix that cannot be used at all (NaN or out of range). */
    boolean usable() {
        return !Double.isNaN(t) && !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0;
    }
}
