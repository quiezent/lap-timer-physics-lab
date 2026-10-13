package android.graphics;

/** FAKE */
public class Paint {
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public int color;
    public Style style = Style.FILL;
    public float strokeWidth, textSize;
    public void setAntiAlias(boolean b) { }
    public void setStyle(Style s) { style = s; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public void setColor(int c) { color = c; }
    public void setTextSize(float s) { textSize = s; }
}
