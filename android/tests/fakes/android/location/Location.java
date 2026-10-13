package android.location;

/** FAKE: setters flip the has* flags exactly like the real class. */
public class Location {
    private final String provider;
    private double lat, lon, alt;
    private float speed, bearing, accuracy, speedAcc, bearingAcc;
    private boolean hAlt, hSpeed, hBearing, hAcc, hSpeedAcc, hBearingAcc;
    private long time, elapsedNs;

    public Location(String provider) { this.provider = provider; }

    public String getProvider() { return provider; }
    public double getLatitude() { return lat; }
    public void setLatitude(double v) { lat = v; }
    public double getLongitude() { return lon; }
    public void setLongitude(double v) { lon = v; }
    public boolean hasAltitude() { return hAlt; }
    public double getAltitude() { return alt; }
    public void setAltitude(double v) { alt = v; hAlt = true; }
    public boolean hasSpeed() { return hSpeed; }
    public float getSpeed() { return speed; }
    public void setSpeed(float v) { speed = v; hSpeed = true; }
    public boolean hasBearing() { return hBearing; }
    public float getBearing() { return bearing; }
    public void setBearing(float v) { bearing = v; hBearing = true; }
    public boolean hasAccuracy() { return hAcc; }
    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float v) { accuracy = v; hAcc = true; }
    public boolean hasSpeedAccuracy() { return hSpeedAcc; }
    public float getSpeedAccuracyMetersPerSecond() { return speedAcc; }
    public void setSpeedAccuracyMetersPerSecond(float v) { speedAcc = v; hSpeedAcc = true; }
    public boolean hasBearingAccuracy() { return hBearingAcc; }
    public float getBearingAccuracyDegrees() { return bearingAcc; }
    public void setBearingAccuracyDegrees(float v) { bearingAcc = v; hBearingAcc = true; }
    public long getTime() { return time; }
    public void setTime(long v) { time = v; }
    public long getElapsedRealtimeNanos() { return elapsedNs; }
    public void setElapsedRealtimeNanos(long v) { elapsedNs = v; }
}
