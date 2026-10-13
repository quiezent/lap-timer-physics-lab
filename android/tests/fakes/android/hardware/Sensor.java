package android.hardware;

/** FAKE */
public final class Sensor {
    public static final int TYPE_ACCELEROMETER = 1;
    public static final int TYPE_GYROSCOPE = 4;

    private final int type, minDelay;
    private final String name, vendor;
    private final float maxRange, resolution;

    public Sensor(int type, String name, String vendor, float maxRange, float resolution, int minDelayUs) {
        this.type = type; this.name = name; this.vendor = vendor;
        this.maxRange = maxRange; this.resolution = resolution; this.minDelay = minDelayUs;
    }
    public int getType() { return type; }
    public String getName() { return name; }
    public String getVendor() { return vendor; }
    public float getMaximumRange() { return maxRange; }
    public float getResolution() { return resolution; }
    public int getMinDelay() { return minDelay; }
}
