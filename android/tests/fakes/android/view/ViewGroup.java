package android.view;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** FAKE */
public class ViewGroup extends View {

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
        public int width, height;
        public LayoutParams(int w, int h) { width = w; height = h; }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
        public MarginLayoutParams(int w, int h) { super(w, h); }
    }

    private final List<View> children = new ArrayList<>();

    public ViewGroup(Context c) { super(c); }

    public void addView(View v) {
        if (v.parent != null) throw new IllegalStateException("The specified child already has a parent. You must call removeView() on the child's parent first.");
        children.add(v);
        v.parent = this;
    }

    public void addView(View v, LayoutParams p) {
        v.setLayoutParams(p);
        addView(v);
    }

    public void removeAllViews() {
        for (View v : children) v.parent = null;
        children.clear();
    }

    public int getChildCount() { return children.size(); }
    public View getChildAt(int i) { return children.get(i); }

    /** Test hook: this view and everything below it, in order. */
    public void collect(List<View> out) {
        out.add(this);
        for (View v : children) {
            if (v instanceof ViewGroup) ((ViewGroup) v).collect(out);
            else out.add(v);
        }
    }
}
