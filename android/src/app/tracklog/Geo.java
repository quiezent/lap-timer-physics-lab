package app.tracklog;

/**
 * A flat local frame: metres east (x) and north (y) of one origin point. Plain Java with no Android classes, so it can be
 * tested on a computer. The same sphere radius and the same formula as LapTimer, so the two agree on where a point is.
 *
 * Over the length of a road stage (tens of kilometres) the flat frame is off by well under a tenth of a percent, and it is
 * the same for every run of a stage, so a comparison between runs does not feel it.
 */
final class Geo {

    static final double R = 6378137.0;

    final double lat0, lon0, cosLat0;

    Geo(double lat0, double lon0) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.cosLat0 = Math.cos(Math.toRadians(lat0));
    }

    double x(double lon) {
        return Math.toRadians(lon - lon0) * R * cosLat0;
    }

    double y(double lat) {
        return Math.toRadians(lat - lat0) * R;
    }

    double lat(double y) {
        return lat0 + Math.toDegrees(y / R);
    }

    double lon(double x) {
        return lon0 + Math.toDegrees(x / (R * cosLat0));
    }

    /** Compass bearing in degrees, [0, 360), of the vector (dx east, dy north). */
    static double bearing(double dx, double dy) {
        final double b = Math.toDegrees(Math.atan2(dx, dy));
        return b < 0.0 ? b + 360.0 : b;
    }

    /** Smallest difference between two compass bearings, 0 to 180 degrees. */
    static double angleDiff(double a, double b) {
        final double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    /** Distance in metres between two points given in degrees (flat frame at the first point). */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        final Geo g = new Geo(lat1, lon1);
        return Math.hypot(g.x(lon2), g.y(lat2));
    }
}
