package app.tracklog;

import android.os.Looper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class Util {
    /** Blocks until everything already queued on the recorder's sensor looper has run. */
    static boolean drain(FakeSensorManager sm) throws InterruptedException {
        if (sm.regs.isEmpty()) return false;
        CountDownLatch l = new CountDownLatch(1);
        sm.regs.get(0).handler.post(l::countDown);
        return l.await(3, TimeUnit.SECONDS);
    }

    /** The thread behind the Handler that Recorder registered with the sensor manager (null if none). */
    static Thread sensorThread(FakeSensorManager sm) {
        if (sm.regs.isEmpty()) return null;
        Looper lp = sm.regs.get(0).handler.getLooper();
        return lp == null ? null : lp.getThread();
    }

    /**
     * Calls r.stop() with a watchdog thread: if stop() has not returned after limitMs the watchdog dumps every
     * thread's stack, prints a FAIL line and halts the JVM (a hung stop() cannot be recovered from).
     * Returns the time stop() took in ms.
     */
    static long stopWithWatchdog(final Recorder r, final long limitMs, final String tag) {
        final CountDownLatch done = new CountDownLatch(1);
        Thread wd = new Thread(() -> {
            try {
                if (!done.await(limitMs, TimeUnit.MILLISECONDS)) {
                    System.out.println("FAIL  " + tag + " stop() did not return within " + limitMs + " ms -> DEADLOCK/HANG. Thread dump:");
                    for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        System.out.println("  --- " + e.getKey().getName() + " (" + e.getKey().getState() + ")");
                        for (StackTraceElement el : e.getValue()) System.out.println("      at " + el);
                    }
                    System.out.flush();
                    Runtime.getRuntime().halt(3);
                }
            } catch (InterruptedException ignored) { }
        }, "watchdog-stop");
        wd.setDaemon(true);
        wd.start();
        long t0 = System.nanoTime();
        r.stop();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        done.countDown();
        return ms;
    }

    /** Test cleanup only: kill any loop the recorder left behind so the next scenario starts clean. */
    static void forceRelease(FakeSensorManager sm, FakeLocationManager lm, Thread sensorThread) {
        try {
            for (FakeSensorManager.Reg g : sm.regs) if (g.handler != null) g.handler.getLooper().quit();
            if (lm.listenerLooper != null) lm.listenerLooper.quit();
            if (sensorThread != null) sensorThread.join(1500);
        } catch (Exception ignored) { }
    }
}
