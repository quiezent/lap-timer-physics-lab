package android.graphics;

import java.util.ArrayList;
import java.util.List;

/** FAKE: remembers what was drawn, as text. */
public class Canvas {
    public final List<String> ops = new ArrayList<>();
    public void drawLine(float x0, float y0, float x1, float y1, Paint p) { ops.add("line"); }
    public void drawRect(float l, float t, float r, float b, Paint p) { ops.add("rect"); }
    public void drawText(String s, float x, float y, Paint p) { ops.add("text:" + s); }
    public void drawPath(Path path, Paint p) { ops.add("path:" + (path.moves + path.lines)); }
}
