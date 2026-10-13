package android.os;

/** FAKE */
public class Handler {
    private final Looper looper;

    public Handler(Looper looper) { this.looper = looper; }
    public final Looper getLooper() { return looper; }
    public final boolean post(Runnable r) { return looper.enqueue(r, 0L); }
    public final boolean postDelayed(Runnable r, long delayMillis) { return looper.enqueue(r, delayMillis); }
    public final void removeCallbacks(Runnable r) { looper.remove(r); }
}
