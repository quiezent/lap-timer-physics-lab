package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/** FAKE */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0, VERTICAL = 1;
    private int orientation = HORIZONTAL;

    public LinearLayout(Context c) { super(c); }
    public void setOrientation(int o) { orientation = o; }
    public int getOrientation() { return orientation; }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, float weight) { super(w, h); this.weight = weight; }
    }
}
