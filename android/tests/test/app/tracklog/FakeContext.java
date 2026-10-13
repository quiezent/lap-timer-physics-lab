package app.tracklog;

import android.content.Context;
import android.hardware.SensorManager;
import android.location.LocationManager;
import java.io.File;

/** Fake Context: external files dir is a temp dir, system services are the fake managers. */
final class FakeContext extends Context {
    private final File dir;
    private final SensorManager sm;
    private final LocationManager lm;

    FakeContext(File dir, SensorManager sm, LocationManager lm) { this.dir = dir; this.sm = sm; this.lm = lm; }

    @Override public Context getApplicationContext() { return this; }
    @Override public File getExternalFilesDir(String type) { return dir; }
    @Override public File getFilesDir() { return dir; }
    @Override public Object getSystemService(String name) {
        if (SENSOR_SERVICE.equals(name)) return sm;
        if (LOCATION_SERVICE.equals(name)) return lm;
        return null;
    }
}
