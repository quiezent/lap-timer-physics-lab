package android.view;

import android.content.Context;
import android.graphics.Canvas;

/**
 * FAKE: a tree of views that remembers what the app set on it, so that a test can read the screen and press its buttons.
 * Nothing is laid out or drawn; a view is 1000 x 400 unless a test says otherwise.
 */
public class View {
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;

    public interface OnClickListener { void onClick(View v); }
    public interface OnLongClickListener { boolean onLongClick(View v); }

    private final Context context;
    ViewGroup parent;
    private int visibility = VISIBLE;
    private boolean enabled = true;
    private float alpha = 1f;
    private ViewGroup.LayoutParams lp;
    private OnClickListener click;
    private OnLongClickListener longClick;
    public int width = 1000, height = 400;
    public int backgroundColor;
    public int invalidations;

    public View(Context c) { context = c; }

    public Context getContext() { return context; }
    public void setVisibility(int v) { visibility = v; }
    public int getVisibility() { return visibility; }
    public void setEnabled(boolean e) { enabled = e; }
    public boolean isEnabled() { return enabled; }
    public void setAlpha(float a) { alpha = a; }
    public float getAlpha() { return alpha; }
    public void setBackgroundColor(int c) { backgroundColor = c; }
    public void setPadding(int l, int t, int r, int b) { }
    public void setOnClickListener(OnClickListener l) { click = l; }
    public void setOnLongClickListener(OnLongClickListener l) { longClick = l; }
    public void setLayoutParams(ViewGroup.LayoutParams p) { lp = p; }
    public ViewGroup.LayoutParams getLayoutParams() { return lp; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void invalidate() { invalidations++; }
    protected void onDraw(Canvas c) { }

    // ---- test hooks, not part of the Android API ----

    /** True if this view and every view above it is VISIBLE. */
    public boolean shown() {
        for (View v = this; v != null; v = v.parent) if (v.visibility != VISIBLE) return false;
        return true;
    }

    /**
     * What a finger does: it reaches the nearest view at or above this one that has a listener (a touch that a child does not use goes
     * to its parent), and a disabled view that listens takes the touch and does nothing.
     */
    public boolean tap() {
        if (!shown()) return false;
        for (View v = this; v != null; v = v.parent) {
            if (v.click == null) continue;
            if (!v.enabled) return false;
            v.click.onClick(v);
            return true;
        }
        return false;
    }

    public boolean hold() {
        if (!shown()) return false;
        for (View v = this; v != null; v = v.parent) {
            if (v.longClick == null) continue;
            if (!v.enabled) return false;
            return v.longClick.onLongClick(v);
        }
        return false;
    }

    public boolean clickable() { return click != null; }

    public void drawInto(Canvas c) { onDraw(c); }
}
