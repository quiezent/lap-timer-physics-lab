package app.tracklog;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory SharedPreferences. */
final class FakePrefs implements SharedPreferences {
    final Map<String, String> map = new ConcurrentHashMap<>();

    @Override public String getString(String key, String def) { String v = map.get(key); return v == null ? def : v; }

    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, String> pending = new ConcurrentHashMap<>();
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public void apply() { map.putAll(pending); }
            @Override public boolean commit() { map.putAll(pending); return true; }
        };
    }
}
