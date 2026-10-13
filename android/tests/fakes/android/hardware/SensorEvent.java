package android.hardware;

/** FAKE: fields are public and settable by the test (real Android reuses one instance per callback). */
public class SensorEvent {
    public float[] values;
    public Sensor sensor;
    public int accuracy;
    public long timestamp;

    public SensorEvent() { this(3); }
    public SensorEvent(int valueSize) { values = new float[valueSize]; }
}
