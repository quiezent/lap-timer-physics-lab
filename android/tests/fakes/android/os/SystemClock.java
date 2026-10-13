package android.os;

/** FAKE: elapsed realtime is System.nanoTime(). */
public class SystemClock {
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
}
