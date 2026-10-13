package android.os;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FAKE. WakeLock semantics follow AOSP: reference-counted by default (release() below zero throws
 * "WakeLock under-locked"); with setReferenceCounted(false) acquire() just sets held and release() clears it,
 * and release() on a lock that is not held is a silent no-op. No timeouts.
 */
public final class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;

    /** test hook: every lock ever created */
    public final List<WakeLock> locks = new CopyOnWriteArrayList<>();

    public WakeLock newWakeLock(int levelAndFlags, String tag) {
        WakeLock w = new WakeLock(tag);
        locks.add(w);
        return w;
    }

    public static final class WakeLock {
        public final String tag;
        private boolean refCounted = true;
        private int count;
        /** test hooks */
        public volatile int acquireCalls, effectiveReleases, ignoredReleases;

        WakeLock(String tag) { this.tag = tag; }

        public synchronized void setReferenceCounted(boolean v) { refCounted = v; }

        public synchronized void acquire() {
            acquireCalls++;
            if (refCounted) count++; else count = 1;
        }

        public synchronized void release() {
            if (refCounted) {
                if (count == 0) throw new RuntimeException("WakeLock under-locked " + tag);
                count--;
                effectiveReleases++;
            } else {
                if (count == 0) { ignoredReleases++; return; }
                count = 0;
                effectiveReleases++;
            }
        }

        public synchronized boolean isHeld() { return count > 0; }
    }
}
