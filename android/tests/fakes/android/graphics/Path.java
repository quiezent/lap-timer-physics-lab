package android.graphics;

/** FAKE: counts the points. */
public class Path {
    public int moves, lines;
    public void reset() { moves = 0; lines = 0; }
    public void moveTo(float x, float y) { moves++; }
    public void lineTo(float x, float y) { lines++; }
}
