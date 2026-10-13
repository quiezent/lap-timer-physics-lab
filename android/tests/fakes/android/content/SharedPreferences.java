package android.content;

/** FAKE */
public interface SharedPreferences {
    String getString(String key, String defValue);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        void apply();
        boolean commit();
    }
}
