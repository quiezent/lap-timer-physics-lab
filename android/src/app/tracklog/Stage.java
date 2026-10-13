package app.tracklog;

/**
 * A road stage: where it starts, where it ends, and the route in between. Plain Java with no Android classes.
 *
 * Everything is in one flat frame whose origin is the point where the driver marked the start (x east, y north, metres).
 * The start line and the finish line are Gates; the route runs from the centre of the start line (route distance 0) to the centre
 * of the finish line (route distance = length). A stage is made once, from a marking drive (StageBuilder), and does not change
 * afterwards except for its name, so the runs saved against it stay comparable.
 */
final class Stage {

    static final String KIND_ROAD = "road";
    static final double GATE_HALF_WIDTH_M = 25.0;     // a pass further than this from the centre of the line is not this line
    static final int NAME_MAX = 60;

    final String id;
    private volatile String name;
    final String kind;
    final long createdUtcMs;
    final Geo geo;                        // origin = the start mark
    final Gate start, finish;
    final Route route;
    final double startMarkLat, startMarkLon, finishMarkLat, finishMarkLon;

    Stage(String id, String name, String kind, long createdUtcMs, Geo geo, Gate start, Gate finish, Route route,
          double startMarkLat, double startMarkLon, double finishMarkLat, double finishMarkLon) {
        this.id = id;
        this.name = cleanName(name);
        this.kind = kind;
        this.createdUtcMs = createdUtcMs;
        this.geo = geo;
        this.start = start;
        this.finish = finish;
        this.route = route;
        this.startMarkLat = startMarkLat;
        this.startMarkLon = startMarkLon;
        this.finishMarkLat = finishMarkLat;
        this.finishMarkLon = finishMarkLon;
    }

    String name() {
        return name;
    }

    void rename(String newName) {
        name = cleanName(newName);
    }

    double lengthM() {
        return route.length;
    }

    /**
     * A run that takes longer than this is given up (a failsafe, not a rule): the length at 2.5 m/s (9 km/h) plus ten minutes,
     * between half an hour and six hours.
     */
    double maxRunS() {
        return Math.max(1800.0, Math.min(6.0 * 3600.0, route.length / 2.5 + 600.0));
    }

    /** One line of plain text with no commas or control characters, at most NAME_MAX long; never empty. */
    static String cleanName(String s) {
        final StringBuilder b = new StringBuilder();
        if (s != null) {
            for (int i = 0; i < s.length() && b.length() < NAME_MAX; i++) {
                final char c = s.charAt(i);
                if (c == ',' || c == ';') b.append(' ');
                else if (c < 32 || c == 127) b.append(' ');
                else b.append(c);
            }
        }
        final String out = b.toString().trim().replaceAll(" +", " ");
        return out.length() == 0 ? "Stage" : out;
    }
}
