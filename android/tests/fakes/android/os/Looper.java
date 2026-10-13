package android.os;

import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FAKE: a real single-thread message loop with Android's MessageQueue quit semantics:
 * quit() drops everything, quitSafely() drops only messages that are not yet due and runs the rest,
 * and nothing can be posted once quitting. A Throwable escaping a Runnable is recorded in UNCAUGHT and
 * printed (a real device would kill the process).
 */
public final class Looper {
    public static final List<Throwable> UNCAUGHT = new CopyOnWriteArrayList<>();

    private static final class Msg implements Comparable<Msg> {
        final Runnable r; final long when; final long seq;
        Msg(Runnable r, long when, long seq) { this.r = r; this.when = when; this.seq = seq; }
        @Override public int compareTo(Msg o) {
            int c = Long.compare(when, o.when);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private final PriorityQueue<Msg> q = new PriorityQueue<>();
    private final Thread thread;
    private boolean quitting;
    private long seq;

    Looper(Thread t) { thread = t; }

    private static volatile Looper main;
    /** The fake main thread's looper (set by the harness before any Service is created). */
    public static Looper getMainLooper() { return main; }
    public static void setMainLooper(Looper l) { main = l; }

    public Thread getThread() { return thread; }

    boolean enqueue(Runnable r, long delayMs) {
        long when = System.nanoTime() + Math.max(0L, delayMs) * 1_000_000L;
        synchronized (this) {
            if (quitting) return false;
            q.add(new Msg(r, when, seq++));
            notifyAll();
            return true;
        }
    }

    void remove(Runnable r) {
        synchronized (this) { q.removeIf(m -> m.r == r); }
    }

    public void quit() {
        synchronized (this) { quitting = true; q.clear(); notifyAll(); }
    }

    public void quitSafely() {
        synchronized (this) {
            quitting = true;
            long now = System.nanoTime();
            q.removeIf(m -> m.when - now > 0);
            notifyAll();
        }
    }

    void loop() {
        for (;;) {
            Runnable next;
            synchronized (this) {
                for (;;) {
                    Msg m = q.peek();
                    long now = System.nanoTime();
                    if (m == null) {
                        if (quitting) return;
                        try { wait(); } catch (InterruptedException ignored) { }
                    } else if (m.when - now <= 0) {
                        q.poll();
                        next = m.r;
                        break;
                    } else {
                        long ns = m.when - now;
                        try { wait(ns / 1_000_000L, (int) (ns % 1_000_000L)); } catch (InterruptedException ignored) { }
                    }
                }
            }
            try {
                next.run();
            } catch (Throwable t) {
                UNCAUGHT.add(t);
                System.err.println("UNCAUGHT on " + Thread.currentThread().getName() + ":");
                t.printStackTrace();
            }
        }
    }
}
