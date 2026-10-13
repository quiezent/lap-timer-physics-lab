package android.os;

/**
 * FAKE: elapsed realtime is System.nanoTime() plus OFFSET_NS.
 *
 * OFFSET_NS is a test hook (the real class has nothing like it). A scenario that plays a whole drive of GPS fixes in a few
 * milliseconds sets it so that "now" follows the time stamps of the fixes it delivers (see app.tracklog.Sim); every other scenario
 * leaves it at 0 and sees the plain monotonic clock. The message loops use System.nanoTime() directly and are not affected.
 */
public class SystemClock {
    public static volatile long OFFSET_NS;

    public static long elapsedRealtimeNanos() { return System.nanoTime() + OFFSET_NS; }
}
