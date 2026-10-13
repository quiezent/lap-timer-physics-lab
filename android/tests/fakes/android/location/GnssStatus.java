package android.location;

/** FAKE */
public final class GnssStatus {
    private final int count;
    private final boolean[] used;

    public GnssStatus(int satelliteCount, boolean[] usedInFix) {
        this.count = satelliteCount;
        this.used = usedInFix;
    }
    public int getSatelliteCount() { return count; }
    public boolean usedInFix(int satelliteIndex) { return used[satelliteIndex]; }

    public abstract static class Callback {
        public void onStarted() { }
        public void onStopped() { }
        public void onFirstFix(int ttffMillis) { }
        public void onSatelliteStatusChanged(GnssStatus status) { }
    }
}
