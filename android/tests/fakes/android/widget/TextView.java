package android.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;

/** FAKE: keeps the text and the colour; the rest is accepted and forgotten. */
public class TextView extends View {
    private CharSequence text = "";
    private int color;
    public float textSize;

    public TextView(Context c) { super(c); }

    public void setText(CharSequence t) { text = t == null ? "" : t; }
    public CharSequence getText() { return text; }
    public void setTextSize(float sp) { textSize = sp; }
    public void setTextColor(int c) { color = c; }
    public int getCurrentTextColor() { return color; }
    public void setTypeface(Typeface tf, int style) { }
    public void setGravity(int g) { }
    public void setSingleLine(boolean b) { }
    public void setHint(CharSequence h) { }
    public void setHintTextColor(int c) { }
    public void setInputType(int t) { }
    public void setAllCaps(boolean b) { }
}
