package android.net;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** FAKE */
public class Uri {
    private final String s;
    private Uri(String s) { this.s = s; }
    public static Uri parse(String s) { return new Uri(s); }
    public static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override public String toString() { return s; }
    public String getLastPathSegment() { int i = s.lastIndexOf('/'); return i < 0 ? s : s.substring(i + 1); }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).s.equals(s); }
    @Override public int hashCode() { return s.hashCode(); }
}
