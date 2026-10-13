package android.widget;

import android.content.Context;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** FAKE: remembers what was shown. */
public class Toast {
    public static final int LENGTH_SHORT = 0, LENGTH_LONG = 1;
    public static final List<String> SHOWN = new CopyOnWriteArrayList<>();
    private final String text;
    private Toast(String t) { text = t; }
    public static Toast makeText(Context c, CharSequence t, int duration) { return new Toast(String.valueOf(t)); }
    public void show() { SHOWN.add(text); }
}
