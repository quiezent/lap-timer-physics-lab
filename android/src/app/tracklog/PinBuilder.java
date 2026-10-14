package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes a Stage from an ordinary recording and two pins. Plain Java with no Android classes. Section 11 of docs/stage-algorithm.md is
 * the method; this class follows it step by step.
 *
 * Why this exists: StageBuilder needs the driver to park on both marks, and on a public road there is often nowhere safe to park.
 * Here the driver drives the road as usual (or has already), and says where the stage starts and ends by putting a pin on the drive
 * (by position, from the pin screen) or by giving a place (a latitude and longitude from a map). The lines go exactly where the pins
 * are, perpendicular to the road, and the stage is the same kind of stage in the same file format, so timing and comparison are
 * unchanged.
 *
 * What it refuses (each with a category and a sentence): a recording with no drive, a place that is not on the drive, a finish before
 * the start (the road was driven the other way), a pin too near the end of the recording, a stage under 300 m, a loop, a pin in a hole
 * in the GPS (the phone could not time a line there), a break between the pins (two drives, not one), and a road that crosses a line
 * again (hairpins stacked closer than a line is wide), which would be mistimed.
 */
final class PinBuilder {

    static final double MARGIN_M = 20.0;               // road the recording must show before the start pin and after the finish pin
    static final double MIN_ROUTE_M = 300.0;           // as StageBuilder
    static final double MIN_SEPARATION_M = 150.0;      // as StageBuilder
    static final double LINE_SPAN_M = 20.0;            // the direction of a line is that of the road over 20 m either side
    static final double GUARD_HALF_M = 35.0;           // the width, either side, within which a second crossing counts
    static final double GUARD_START_FROM_M = 30.0, GUARD_START_TO_M = 300.0;
    static final double GUARD_FINISH_FRACTION = 0.7, GUARD_FINISH_END_M = 30.0;
    static final double NOTE_OFFSET_M = 5.0;           // a place further than this from the road is mentioned

    private PinBuilder() { }

    /** A refusal: a category that stays the same (tests and other implementations use it) and a sentence for the driver. */
    static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final String category;

        Refused(String category, String message) {
            super(message);
            this.category = category;
        }
    }

    /** How a pin is given: a distance along the track, or a place (latitude, longitude) to be snapped to it. */
    static final class Pin {
        final boolean place;
        final double s;
        final double lat, lon;

        private Pin(boolean place, double s, double lat, double lon) {
            this.place = place;
            this.s = s;
            this.lat = lat;
            this.lon = lon;
        }

        static Pin atPosition(double s) {
            return new Pin(false, s, Double.NaN, Double.NaN);
        }

        static Pin atPlace(double lat, double lon) {
            return new Pin(true, Double.NaN, lat, lon);
        }
    }

    /** The stage and what was decided to make it (for the screen; none of this is in the stage file). */
    static final class Result {
        final Stage stage;
        final double startS, finishS;                  // where the lines are, along the track
        final double startOffsetM, finishOffsetM;      // how far a place was from the road (0 for a pin given by position)
        final int startPasses, finishPasses;           // how many times the track passes a place (0 for a pin given by position)
        final List<String> notes;

        Result(Stage stage, double startS, double finishS, double startOffsetM, double finishOffsetM,
               int startPasses, int finishPasses, List<String> notes) {
            this.stage = stage;
            this.startS = startS;
            this.finishS = finishS;
            this.startOffsetM = startOffsetM;
            this.finishOffsetM = finishOffsetM;
            this.startPasses = startPasses;
            this.finishPasses = finishPasses;
            this.notes = notes;
        }
    }

    private static List<Track.Pass> candidates(Track tr, Pin p, boolean start) {
        final List<Track.Pass> out = new ArrayList<Track.Pass>();
        if (!p.place) {
            if (Double.isNaN(p.s) || Double.isInfinite(p.s)) {
                throw new Refused("near_end", "That pin is not on the recording.");
            }
            out.add(new Track.Pass(p.s, 0.0));
            return out;
        }
        out.addAll(tr.passes(p.lat, p.lon));
        if (out.isEmpty()) {
            final double d = tr.closestM(p.lat, p.lon);
            throw new Refused(start ? "no_pass_start" : "no_pass_finish", String.format(Locale.US,
                    "The %s place is not on this drive: the road you drove comes no closer than %s. Check the numbers, or pick another drive.",
                    start ? "start" : "finish", StageText.dist(d)));
        }
        return out;
    }

    /** The first reason, in the order of section 11.3, why positions a and b cannot be a stage on this track; null if they can. */
    private static Refused why(Track tr, double a, double b) {
        if (!(b > a)) {
            return new Refused("wrong_way", "The finish comes before the start on this drive. It looks as if the road was driven the other way "
                    + "round, and a stage can only be driven the way it was recorded.");
        }
        if (!(a >= MARGIN_M)) {
            return new Refused("near_end", String.format(Locale.US, "The start is only %s from the beginning of the recording. The recording "
                    + "has to show the car arriving at the start, with at least %.0f m of road before it.", StageText.dist(Math.max(0.0, a)), MARGIN_M));
        }
        if (!(b <= tr.length - MARGIN_M)) {
            return new Refused("near_end", String.format(Locale.US, "The finish is only %s from the end of the recording. The recording "
                    + "has to show the car leaving the finish, with at least %.0f m of road after it.", StageText.dist(Math.max(0.0, tr.length - b)), MARGIN_M));
        }
        if (!(b - a >= MIN_ROUTE_M)) {
            return new Refused("too_short", String.format(Locale.US, "The road between the two pins is only %s. A stage needs at least %.0f m.",
                    StageText.dist(b - a), MIN_ROUTE_M));
        }
        final double[] pa = tr.route.pointAt(a), pb = tr.route.pointAt(b);
        final double sep = Math.hypot(pb[0] - pa[0], pb[1] - pa[1]);
        if (!(sep >= MIN_SEPARATION_M)) {
            return new Refused("loop", String.format(Locale.US, "The two pins are only %s apart in a straight line. A stage runs from one place "
                    + "to another, not round a loop.", StageText.dist(sep)));
        }
        final double holeA = tr.holeAt(a);
        if (holeA > 0.0) {
            return new Refused("hole_start", String.format(Locale.US, "The GPS gave no position for %.0f s around the start pin, so the phone "
                    + "could not see the car cross a line there. Move the start pin to where the GPS was working.", holeA));
        }
        final double holeB = tr.holeAt(b);
        if (holeB > 0.0) {
            return new Refused("hole_finish", String.format(Locale.US, "The GPS gave no position for %.0f s around the finish pin, so the phone "
                    + "could not see the car cross a line there. Move the finish pin to where the GPS was working.", holeB));
        }
        final double[] br = tr.breakBetween(a, b);
        if (br != null) {
            return new Refused("break", String.format(Locale.US, "Between the two pins the recording has a break of %.0f s, with the car %s apart "
                    + "across it: the GPS was lost, or this is two drives. A stage has to be one continuous drive. Put both pins on one stretch.",
                    br[0], StageText.dist(br[1])));
        }
        return null;
    }

    /**
     * @param id        the stage's id
     * @param startPin  where the stage starts
     * @param finishPin where it ends
     * @throws Refused if the pins cannot make a stage on this track (see the class comment)
     */
    static Result build(String id, String name, long createdUtcMs, Track tr, Pin startPin, Pin finishPin) {
        final List<Track.Pass> as = candidates(tr, startPin, true), bs = candidates(tr, finishPin, false);

        // the first pair, in order along the track, that makes a stage
        Track.Pass ca = null, cb = null;
        Refused firstWhy = null;
        for (Track.Pass a : as) {
            for (Track.Pass b : bs) {
                if (!(b.s > a.s)) continue;
                final Refused w = why(tr, a.s, b.s);
                if (w == null) {
                    ca = a;
                    cb = b;
                    break;
                }
                if (firstWhy == null) firstWhy = w;
            }
            if (ca != null) break;
        }
        if (ca == null) {
            if (firstWhy != null) throw firstWhy;
            final Refused w = why(tr, as.get(0).s, bs.get(0).s);          // no finish after any start
            throw w != null ? w : new Refused("wrong_way", "The finish comes before the start on this drive.");
        }
        final double a = ca.s, b = cb.s;

        // the lines: exactly at the pins, perpendicular to the road over LINE_SPAN_M either side
        final double bearingStart = lineBearing(tr.route, a);
        final double bearingFinish = lineBearing(tr.route, b);

        // the marks, and the frame of the stage (origin = the start mark, unrounded)
        final double[] llA = tr.latLonAt(a), llB = tr.latLonAt(b);
        final double startMarkLat = startPin.place ? startPin.lat : llA[0], startMarkLon = startPin.place ? startPin.lon : llA[1];
        final double finishMarkLat = finishPin.place ? finishPin.lat : llB[0], finishMarkLon = finishPin.place ? finishPin.lon : llB[1];
        final Geo geo = new Geo(startMarkLat, startMarkLon);

        // the route of the stage, every point placed from its latitude and longitude
        final List<double[]> r = new ArrayList<double[]>();
        r.add(new double[]{geo.x(llA[1]), geo.y(llA[0])});
        for (int i = 0; i < tr.n; i++) {
            if (tr.route.s[i] > a + 0.01 && tr.route.s[i] < b - 0.01) r.add(new double[]{geo.x(tr.lon[i]), geo.y(tr.lat[i])});
        }
        r.add(new double[]{geo.x(llB[1]), geo.y(llB[0])});
        final double[] rx = new double[r.size()], ry = new double[r.size()];
        for (int i = 0; i < rx.length; i++) {
            rx[i] = r.get(i)[0];
            ry[i] = r.get(i)[1];
        }
        final Route route = new Route(rx, ry);
        final Gate gs = new Gate(rx[0], ry[0], bearingStart, Stage.GATE_HALF_WIDTH_M);
        final Gate gf = new Gate(rx[rx.length - 1], ry[ry.length - 1], bearingFinish, Stage.GATE_HALF_WIDTH_M);

        // the guard: a road that crosses a line again would be mistimed (section 11.6)
        for (double u : crossings(gs, route)) {
            if (u > GUARD_START_FROM_M && u <= GUARD_START_TO_M) {
                throw new Refused("guard_start", String.format(Locale.US, "The road crosses the start line again %s into the stage (a loop or "
                        + "tight bends close to it). A run would be timed wrongly. Move the start pin to a straighter place.", StageText.dist(u)));
            }
        }
        for (double u : crossings(gf, route)) {
            if (u >= GUARD_FINISH_FRACTION * route.length && u < route.length - GUARD_FINISH_END_M) {
                throw new Refused("guard_finish", String.format(Locale.US, "The road crosses the finish line already %s into the stage (a loop or "
                        + "tight bends close to it). A run would be timed wrongly. Move the finish pin to a straighter place.", StageText.dist(u)));
            }
        }

        final Stage stage = new Stage(id, name, Stage.KIND_ROAD, createdUtcMs, geo, gs, gf, route,
                startMarkLat, startMarkLon, finishMarkLat, finishMarkLon);

        final List<String> notes = new ArrayList<String>();
        if (startPin.place && ca.offsetM > NOTE_OFFSET_M) {
            notes.add(String.format(Locale.US, "The start place is %s from the road you drove. The line is on the road, at the nearest point.", StageText.dist(ca.offsetM)));
        }
        if (finishPin.place && cb.offsetM > NOTE_OFFSET_M) {
            notes.add(String.format(Locale.US, "The finish place is %s from the road you drove. The line is on the road, at the nearest point.", StageText.dist(cb.offsetM)));
        }
        if (startPin.place && as.size() > 1) {
            notes.add(String.format(Locale.US, "The drive passes the start place %d times. I used the first one that makes a stage, %s into the recording.", as.size(), StageText.dist(a)));
        }
        if (finishPin.place && bs.size() > 1) {
            notes.add(String.format(Locale.US, "The drive passes the finish place %d times. I used the first one that makes a stage, %s into the recording.", bs.size(), StageText.dist(b)));
        }
        for (double[] g : tr.gaps(a, b)) {
            notes.add(String.format(Locale.US, "The GPS had no fixes for %.0f s at %s into the recording. The road is a straight line there, so later drives are placed on it less well.", g[1], StageText.dist(g[0])));
        }
        return new Result(stage, a, b, ca.offsetM, cb.offsetM, startPin.place ? as.size() : 0, finishPin.place ? bs.size() : 0, notes);
    }

    /** The direction a line at distance d along a route counts: the way the road runs over LINE_SPAN_M either side (compass degrees). */
    static double lineBearing(Route r, double d) {
        final double[] p1 = r.pointAt(d - LINE_SPAN_M), p2 = r.pointAt(d + LINE_SPAN_M);
        return Geo.bearing(p2[0] - p1[0], p2[1] - p1[1]);
    }

    /** Route distances at which the route crosses the gate forwards (before to on-or-after) within GUARD_HALF_M of its centre. */
    static List<Double> crossings(Gate g, Route r) {
        final List<Double> out = new ArrayList<Double>();
        for (int k = 0; k < r.n - 1; k++) {
            final double q0 = g.along(r.x[k], r.y[k]), q1 = g.along(r.x[k + 1], r.y[k + 1]);
            if (!(q0 < 0.0 && q1 >= 0.0)) continue;
            final double f = -q0 / (q1 - q0);
            final double px = r.x[k] + f * (r.x[k + 1] - r.x[k]), py = r.y[k] + f * (r.y[k + 1] - r.y[k]);
            if (Math.abs(g.across(px, py)) > GUARD_HALF_M) continue;
            out.add(r.s[k] + f * (r.s[k + 1] - r.s[k]));
        }
        return out;
    }
}
