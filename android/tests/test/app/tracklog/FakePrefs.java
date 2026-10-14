package app.tracklog;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory SharedPreferences. */
final class FakePrefs implements SharedPreferences {
    final Map<String, String> map = new ConcurrentHashMap<>();

    @Override public String getString(String key, String def) { String v = map.get(key); return v == null ? def : v; }

    @Override public boolean getBoolean(String key, boolean def) { String v = map.get(key); return v == null ? def : v.equals("true"); }

    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, String> pending = new ConcurrentHashMap<>();
            private final java.util.Set<String> removed = new java.util.HashSet<>();
            @Override public Editor putString(String k, String v) { pending.put(k, v); removed.remove(k); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v ? "true" : "false"); removed.remove(k); return this; }
            @Override public Editor remove(String k) { pending.remove(k); removed.add(k); return this; }
            @Override public void apply() { for (String k : removed) map.remove(k); map.putAll(pending); }
            @Override public boolean commit() { apply(); return true; }
        };
    }
}
