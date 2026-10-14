package app.tracklog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * A drive drawn as a line on a plain background, with the start pin (green) and the finish pin (red) on it. It is what the pin screen
 * shows. There is no map under it on purpose: the app has no internet permission and nothing here is sent anywhere; the "Open in Google
 * Maps" button next to the pins is how a pin is looked at on a real map.
 *
 * Two kinds. OVERVIEW fits the whole drive in the view, so a pin can be put roughly where it belongs with one touch. CLOSEUP shows
 * about 100 m of road each side of the active pin at a fixed scale, with the timing line drawn across the road exactly as the stage
 * will have it (and a short stroke forward for the direction), so the last metres can be set by eye. A touch on the line asks the
 * listener to move the active pin to the nearest point of the drive; a touch away from it does nothing.
 *
 * Where the drive passes the touched place more than once (out and back along the same road) the first pass is taken; the slider
 * under the pictures reaches the others.
 */
final class TrackView extends View {

    static final int OVERVIEW = 0, CLOSEUP = 1;
    static final double CLOSEUP_HALF_M = 100.0;        // the shorter side of a close-up shows this much road each side of the pin
    static final double HIT_DP = 28.0;                 // a touch further than this from the line is not on it
    static final double TIE_DP = 1.0, TIE_M = 4.0;     // passes this close to one another (a dp on the screen, or 4 m on the ground) count as the same place

    private static final int BG = 0xFF11151A;
    private static final int OFF = 0xFF55606C;         // the drive outside the stage
    private static final int ON = 0xFFE8EAED;          // the drive between the pins
    private static final int GREEN = 0xFF2ECC71;
    private static final int RED = 0xFFE5484D;
    private static final int GATE = 0xFFF5A524;
    private static final int TEXT = 0xFF8B949E;

    /** The person touched the line. @param s the distance along the drive of the nearest point */
    interface Listener {
        void onPick(double s);
    }

    private final int mode;
    private final float density;
    private final Paint off = new Paint(), on = new Paint(), gate = new Paint(), ring = new Paint(), greenFill = new Paint(),
            redFill = new Paint(), label = new Paint();
    private final Path pathOff = new Path(), pathOn = new Path();

    private Track track;
    private double bx0, bx1, by0, by1;                  // the drive's bounding box
    private double startS = -1.0, finishS = -1.0;       // the pins, as distances along the drive; negative: none
    private int active;                                 // 0 the start pin is the active one, 1 the finish pin
    private Listener listener;

    // the picture as last laid out: world point (wx, wy) is at the middle of the view, scale pixels to the metre
    private double wx, wy, scale = 1.0;

    TrackView(Context c, int mode, float density) {
        super(c);
        this.mode = mode;
        this.density = density;
        setBackgroundColor(BG);
        off.setAntiAlias(true);
        off.setStyle(Paint.Style.STROKE);
        off.setStrokeWidth(2f * density);
        off.setColor(OFF);
        on.setAntiAlias(true);
        on.setStyle(Paint.Style.STROKE);
        on.setStrokeWidth(3f * density);
        on.setColor(ON);
        gate.setAntiAlias(true);
        gate.setStyle(Paint.Style.STROKE);
        gate.setStrokeWidth(3f * density);
        gate.setColor(GATE);
        ring.setAntiAlias(true);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * density);
        ring.setColor(0xFFFFFFFF);
        greenFill.setAntiAlias(true);
        greenFill.setStyle(Paint.Style.FILL);
        greenFill.setColor(GREEN);
        redFill.setAntiAlias(true);
        redFill.setStyle(Paint.Style.FILL);
        redFill.setColor(RED);
        label.setAntiAlias(true);
        label.setColor(TEXT);
        label.setStrokeWidth(2f * density);
        label.setTextSize(12f * density);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** A new drive; the pins are cleared. */
    void set(Track t) {
        track = t;
        startS = -1.0;
        finishS = -1.0;
        active = 0;
        bx0 = Double.MAX_VALUE;
        by0 = Double.MAX_VALUE;
        bx1 = -Double.MAX_VALUE;
        by1 = -Double.MAX_VALUE;
        if (t != null) {
            for (int i = 0; i < t.n; i++) {
                bx0 = Math.min(bx0, t.route.x[i]);
                bx1 = Math.max(bx1, t.route.x[i]);
                by0 = Math.min(by0, t.route.y[i]);
                by1 = Math.max(by1, t.route.y[i]);
            }
        }
        invalidate();
    }

    void setPins(double startS, double finishS, int active) {
        this.startS = startS;
        this.finishS = finishS;
        this.active = active == 1 ? 1 : 0;
        invalidate();
    }

    // ------------------------------------------------------------------ the picture's geometry

    private void layout(int w, int h) {
        final float pad = 12f * density;
        if (mode == OVERVIEW) {
            final double dx = Math.max(bx1 - bx0, 1.0), dy = Math.max(by1 - by0, 1.0);
            scale = Math.min((w - 2 * pad) / dx, (h - 2 * pad) / dy);
            wx = (bx0 + bx1) / 2.0;
            wy = (by0 + by1) / 2.0;
        } else {
            scale = (Math.min(w, h) - 2 * pad) / (2.0 * CLOSEUP_HALF_M);
            final double[] c = track.route.pointAt(Math.max(0.0, active == 0 ? startS : finishS));
            wx = c[0];
            wy = c[1];
        }
        if (!(scale > 0.0) || Double.isInfinite(scale)) scale = 1.0;
    }

    private float sx(double x) {
        return (float) (getWidth() / 2.0 + (x - wx) * scale);
    }

    private float sy(double y) {
        return (float) (getHeight() / 2.0 - (y - wy) * scale);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas cv) {
        final int w = getWidth(), h = getHeight();
        if (track == null || w <= 0 || h <= 0) return;
        layout(w, h);
        final Route r = track.route;
        final boolean stage = startS >= 0.0 && finishS > startS;
        final double a = stage ? startS : r.length + 1.0, b = stage ? finishS : r.length + 1.0;

        pathOff.reset();
        pathOn.reset();
        if (mode == OVERVIEW) {
            overviewRun(0.0, Math.min(a, r.length), pathOff);
            if (stage) {
                overviewRun(a, b, pathOn);
                overviewRun(b, r.length, pathOff);
            }
        } else {
            closeupSegments(a, b);
        }
        cv.drawPath(pathOff, off);
        if (stage) cv.drawPath(pathOn, on);

        if (mode == CLOSEUP && (active == 0 ? startS : finishS) >= 0.0) drawGate(cv, active == 0 ? startS : finishS);
        // the pins, the active one last and ringed
        for (int k = 0; k < 2; k++) {
            final int which = k == 0 ? 1 - active : active;
            final double s = which == 0 ? startS : finishS;
            if (s < 0.0) continue;
            final double[] p = r.pointAt(s);
            final float x = sx(p[0]), y = sy(p[1]);
            if (x < -20f || y < -20f || x > w + 20f || y > h + 20f) continue;
            final float rad = (which == active ? 8f : 6f) * density;
            cv.drawCircle(x, y, rad, which == 0 ? greenFill : redFill);
            if (which == active) cv.drawCircle(x, y, rad + 4f * density, ring);
        }
        if (mode == CLOSEUP) {
            final float len = (float) (50.0 * scale);
            final float x0 = 12f * density, y0 = h - 14f * density;
            cv.drawLine(x0, y0, x0 + len, y0, label);
            cv.drawText("50 m", x0, y0 - 6f * density, label);
        }
    }

    /** The drive from distance s0 to s1 as one connected line, leaving out vertices less than two pixels from the last one drawn. */
    private void overviewRun(double s0, double s1, Path path) {
        if (!(s1 > s0)) return;
        final Route r = track.route;
        final double[] p0 = r.pointAt(s0);
        float lx = sx(p0[0]), ly = sy(p0[1]);
        path.moveTo(lx, ly);
        for (int i = r.segmentAt(s0) + 1; i < r.n && r.s[i] < s1; i++) {
            final float x = sx(r.x[i]), y = sy(r.y[i]);
            if (Math.abs(x - lx) + Math.abs(y - ly) < 2f) continue;
            path.lineTo(x, y);
            lx = x;
            ly = y;
        }
        final double[] p1 = r.pointAt(s1);
        path.lineTo(sx(p1[0]), sy(p1[1]));
    }

    /** Every piece of the drive that is in the close-up's window, each on the line it belongs to (outside the stage, or between the pins). */
    private void closeupSegments(double a, double b) {
        final Route r = track.route;
        final double halfW = getWidth() / 2.0 / scale + 5.0, halfH = getHeight() / 2.0 / scale + 5.0;
        for (int j = 0; j < r.n - 1; j++) {
            final double x0 = r.x[j], y0 = r.y[j], x1 = r.x[j + 1], y1 = r.y[j + 1];
            if (Math.max(x0, x1) < wx - halfW || Math.min(x0, x1) > wx + halfW
                    || Math.max(y0, y1) < wy - halfH || Math.min(y0, y1) > wy + halfH) continue;
            final double mid = (r.s[j] + r.s[j + 1]) / 2.0;
            final Path p = mid >= a && mid <= b ? pathOn : pathOff;
            p.moveTo(sx(x0), sy(y0));
            p.lineTo(sx(x1), sy(y1));
        }
    }

    /** The timing line across the road at distance s along the drive, and a stroke forward along the way the stage runs. */
    private void drawGate(Canvas cv, double s) {
        final double[] p = track.route.pointAt(s);
        final double th = Math.toRadians(PinBuilder.lineBearing(track.route, s));
        final double half = Stage.GATE_HALF_WIDTH_M;
        final double px = Math.cos(th), py = -Math.sin(th);               // across the road
        final double fx = Math.sin(th), fy = Math.cos(th);                // along it, forwards
        cv.drawLine(sx(p[0] - half * px), sy(p[1] - half * py), sx(p[0] + half * px), sy(p[1] + half * py), gate);
        cv.drawLine(sx(p[0]), sy(p[1]), sx(p[0] + 15.0 * fx), sy(p[1] + 15.0 * fy), gate);
    }

    /** Where on the screen the point at distance s along the drive is drawn (as the picture is laid out now). For the tests. */
    float[] pixelOf(double s) {
        layout(getWidth(), getHeight());
        final double[] p = track.route.pointAt(s);
        return new float[]{sx(p[0]), sy(p[1])};
    }

    double startPin() {
        return startS;
    }

    double finishPin() {
        return finishS;
    }

    int activePin() {
        return active;
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (track == null || listener == null || getWidth() <= 0 || getHeight() <= 0) return false;
        final int action = e.getAction();
        if (action == MotionEvent.ACTION_DOWN) return true;                // the touch is ours, so that the lift reaches us
        if (action == MotionEvent.ACTION_UP) {
            layout(getWidth(), getHeight());
            final double s = sAtPixel(e.getX(), e.getY());
            if (!Double.isNaN(s)) listener.onPick(s);
            return true;
        }
        return false;
    }

    /** The distance along the drive of the point of it nearest to a touch, NaN if no part of the drive is within HIT_DP of it. */
    double sAtPixel(float px, float py) {
        if (track == null || getWidth() <= 0) return Double.NaN;
        layout(getWidth(), getHeight());
        final double x = wx + (px - getWidth() / 2.0) / scale, y = wy - (py - getHeight() / 2.0) / scale;
        final Route r = track.route;
        double dMin = Double.MAX_VALUE;
        for (int j = 0; j < r.n - 1; j++) dMin = Math.min(dMin, nearest(r, j, x, y)[0]);
        if (dMin * scale > HIT_DP * density) return Double.NaN;
        final double tie = Math.max(TIE_DP * density / scale, TIE_M);
        for (int j = 0; j < r.n - 1; j++) {
            final double[] q = nearest(r, j, x, y);
            if (q[0] <= dMin + tie) return q[1];
        }
        return Double.NaN;
    }

    /** {distance from the point to segment j, the distance along the drive of the nearest point of the segment} */
    private static double[] nearest(Route r, int j, double px, double py) {
        final double ax = r.x[j], ay = r.y[j];
        final double dx = r.x[j + 1] - ax, dy = r.y[j + 1] - ay;
        final double len2 = dx * dx + dy * dy;
        double f = len2 > 0.0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0.0;
        f = Math.max(0.0, Math.min(1.0, f));
        return new double[]{Math.hypot(px - (ax + f * dx), py - (ay + f * dy)), r.s[j] + f * Math.sqrt(len2)};
    }
}
