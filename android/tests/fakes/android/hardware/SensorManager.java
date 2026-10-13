package android.hardware;

import android.os.Handler;

/** FAKE: abstract like the real one; the test subclasses it (app.tracklog.FakeSensorManager). */
public abstract class SensorManager {
    public abstract Sensor getDefaultSensor(int type);
    public abstract boolean registerListener(SensorEventListener listener, Sensor sensor,
                                             int samplingPeriodUs, int maxReportLatencyUs, Handler handler);
    public abstract void unregisterListener(SensorEventListener listener);
}
