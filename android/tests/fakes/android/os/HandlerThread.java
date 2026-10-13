package android.os;

/** FAKE: a Thread that owns a Looper; getLooper() blocks until the loop exists, like the real one. */
public class HandlerThread extends Thread {
    private final int requestedPriority;
    private Looper looper;

    public HandlerThread(String name) { this(name, 0); }
    public HandlerThread(String name, int priority) { super(name); this.requestedPriority = priority; }

    public int getRequestedPriority() { return requestedPriority; }

    @Override public void run() {
        synchronized (this) { looper = new Looper(this); notifyAll(); }
        looper.loop();
    }

    public Looper getLooper() {
        if (!isAlive()) return null;
        synchronized (this) {
            while (isAlive() && looper == null) {
                try { wait(); } catch (InterruptedException ignored) { }
            }
        }
        return looper;
    }

    public boolean quit() {
        Looper l = getLooper();
        if (l != null) { l.quit(); return true; }
        return false;
    }

    public boolean quitSafely() {
        Looper l = getLooper();
        if (l != null) { l.quitSafely(); return true; }
        return false;
    }
}
