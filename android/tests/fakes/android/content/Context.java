package android.content;

import java.io.File;

/** FAKE: only what Recorder, RecorderService and Exporter touch. */
public abstract class Context {
    public static final String SENSOR_SERVICE = "sensor";
    public static final String LOCATION_SERVICE = "location";
    public static final String POWER_SERVICE = "power";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final int MODE_PRIVATE = 0;

    public abstract Context getApplicationContext();
    public abstract Object getSystemService(String name);
    public abstract File getExternalFilesDir(String type);
    public abstract File getFilesDir();
    public ContentResolver getContentResolver() { throw new UnsupportedOperationException("fake Context: no ContentResolver"); }
    public SharedPreferences getSharedPreferences(String name, int mode) { throw new UnsupportedOperationException("fake Context: no SharedPreferences"); }
}
