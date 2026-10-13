package android.content;

import java.util.LinkedHashMap;
import java.util.Map;

/** FAKE */
public final class ContentValues {
    private final Map<String, Object> m = new LinkedHashMap<>();
    public void put(String k, String v) { m.put(k, v); }
    public void put(String k, Integer v) { m.put(k, v); }
    public void put(String k, Long v) { m.put(k, v); }
    public Object get(String k) { return m.get(k); }
    public boolean containsKey(String k) { return m.containsKey(k); }
    public Integer getAsInteger(String k) { Object o = m.get(k); return o == null ? null : Integer.valueOf(((Number) o).intValue()); }
    public String getAsString(String k) { Object o = m.get(k); return o == null ? null : o.toString(); }
}
