package app.tracklog;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * The fake Android "system" around a real RecorderService.
 * Assumed semantics (see the report):
 *  - one fake MAIN thread; Looper.getMainLooper() is its looper; onStartCommand/onDestroy run there;
 *  - startService() from any thread is delivered on main; if no live instance exists the system constructs a NEW one
 *    (on main) and calls onCreate() first;
 *  - stopSelf() on the live instance: the instance is dropped from the system immediately (a later startService()
 *    gets a new instance) and onDestroy() is delivered later on main; stopSelf()/stopForeground() from an instance
 *    that is already destroyed or no longer the live one are silent no-ops;
 *  - stopService() / system teardown: onDestroy() on main right away, no stopSelf involved.
 */
final class ServiceHost {
    final File root;
    volatile File ext;
    final FakeSensorManager sm = new FakeSensorManager();
    final FakeLocationManager lm = new FakeLocationManager();
    final FakeContentResolver cr = new FakeContentResolver();
    final FakePrefs prefs = new FakePrefs();
    final PowerManager pm = new PowerManager();
    final NotificationManager nm = new NotificationManager();
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<RecorderService> instances = new CopyOnWriteArrayList<>();
    final HandlerThread mainThread = new HandlerThread("main");
    final Handler mainHandler;
    final Context app = new App();
    final Probe probe = new Probe();
    final int baseSensors, baseStop, errBefore, loopBefore, thrBefore;
    volatile RecorderService current;
    volatile RuntimeException startForegroundFailure;
    volatile int destroyedCount;
    private int startId;

    private final class App extends Context {
        @Override public Context getApplicationContext() { return this; }
        @Override public Object getSystemService(String n) {
            if (SENSOR_SERVICE.equals(n)) return sm;
            if (LOCATION_SERVICE.equals(n)) return lm;
            if (POWER_SERVICE.equals(n)) return pm;
            if (NOTIFICATION_SERVICE.equals(n)) return nm;
            return null;
        }
        @Override public File getExternalFilesDir(String t) { return ext; }
        @Override public File getFilesDir() { return new File(root, "internal"); }
        @Override public ContentResolver getContentResolver() { return cr; }
        @Override public SharedPreferences getSharedPreferences(String n, int m) { return prefs; }
    }

    /** Samples Recorder.LIVE.recording / saving every 0.1 ms and keeps the sequence of distinct states (R=recording, S=saving). */
    final class Probe extends Thread {
        volatile boolean stop;
        final List<String> states = new CopyOnWriteArrayList<>();
        Probe() { super("probe"); setDaemon(true); }
        @Override public void run() {
            String last = "";
            while (!stop) {
                boolean sv = Recorder.LIVE.saving;          // saving is set before recording clears and cleared after
                boolean rc = Recorder.LIVE.recording;
                String s = (rc ? "R" : "-") + (sv ? "S" : "-");
                if (!s.equals(last)) { states.add(s); last = s; }
                LockSupport.parkNanos(100_000);
            }
        }
        String seq() { return String.join(">", states); }
    }

    ServiceHost(File outRoot, String tag) throws Exception {
        root = Files.createTempDirectory(outRoot.toPath(), tag + "_").toFile();
        ext = new File(root, "Android/data/app.tracklog/files");
        Recorder.Live l = Recorder.LIVE;
        l.saving = false; l.exportText = ""; l.lastExportUri = ""; l.failed = false; l.error = "";
        baseSensors = alive("tracklog-sensors");
        baseStop = alive("tracklog-stop");
        errBefore = Harness.errBytes();
        loopBefore = android.os.Looper.UNCAUGHT.size();
        thrBefore = Harness.UNCAUGHT_THREADS.size();
        mainThread.start();
        Looper.setMainLooper(mainThread.getLooper());
        mainHandler = new Handler(mainThread.getLooper());
        probe.start();
    }

    // ------------------------------------------------------------------ the "app" side
    Intent startIntent() { return new Intent(app, RecorderService.class).setAction(RecorderService.ACTION_START); }
    Intent stopIntent() { return new Intent(app, RecorderService.class).setAction(RecorderService.ACTION_STOP); }

    void startService(final Intent i) {
        mainHandler.post(new Runnable() { @Override public void run() { deliver(i); } });
    }

    /** The system tears the live service down (Context.stopService / low-memory teardown): onDestroy on main, no stopSelf. */
    void stopService() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                RecorderService s = current;
                if (s == null) return;
                current = null;
                destroy(s);
            }
        });
    }

    // ------------------------------------------------------------------ the "system" side (main thread)
    private void deliver(Intent i) {
        RecorderService s = current;
        if (s == null) {
            final RecorderService created = new RecorderService();     // constructed on main, like ActivityThread does
            created.attach(app);
            created.sink = log;
            created.startForegroundFailure = startForegroundFailure;
            created.stopSelfHook = new Runnable() { @Override public void run() { onStopSelf(created); } };
            instances.add(created);
            current = created;
            log.add("-- system: created instance #" + instances.size());
            created.onCreate();
            s = created;
        }
        s.onStartCommand(i, 0, ++startId);
    }

    private void onStopSelf(final RecorderService s) {
        if (s.destroyed || current != s) {
            log.add("-- system: stopSelf() from a destroyed/old instance ignored");
            return;
        }
        current = null;
        mainHandler.post(new Runnable() { @Override public void run() { destroy(s); } });
    }

    private void destroy(RecorderService s) {
        if (s.destroyed) return;
        log.add("-- system: onDestroy of instance #" + (instances.indexOf(s) + 1));
        s.onDestroy();
        s.destroyed = true;
        destroyedCount++;
    }

    // ------------------------------------------------------------------ helpers for scenarios
    static int alive(String name) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && name.equals(t.getName())) n++;
        return n;
    }

    boolean awaitTrue(BooleanSupplier c, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return c.getAsBoolean();
    }

    boolean flushMain() throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        mainHandler.post(l::countDown);
        return l.await(3, TimeUnit.SECONDS);
    }

    boolean awaitRecording(long ms) throws InterruptedException {
        return awaitTrue(() -> Recorder.LIVE.recording && sm.regs.size() == 2 && lm.listener != null && lm.gnss != null, ms);
    }

    /** No live service instance, no save running, no export worker thread, main queue flushed. */
    boolean awaitIdle(long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            if (current == null && !Recorder.LIVE.saving && alive("tracklog-stop") <= baseStop && flushMain()
                    && current == null && !Recorder.LIVE.saving) return true;
            Thread.sleep(10);
        }
        return false;
    }

    /** Waits for the recorder to be registered, then feeds synthetic sensor data in real time. */
    Feeder feed(double seconds) throws Exception {
        awaitTrue(() -> sm.regs.size() == 2 && lm.listener != null, 3000);
        Feeder fd = new Feeder(sm, lm, seconds);
        fd.start();
        fd.join((long) (seconds * 1000) + 15_000);
        Util.drain(sm);
        return fd;
    }

    int n(String callPrefix) {
        int c = 0;
        for (String s : log) if (s.startsWith(callPrefix)) c++;
        return c;
    }

    boolean serviceCallsAllOnMain() {
        for (String s : log) if (!s.startsWith("--") && !s.contains("@main")) return false;
        return true;
    }

    PowerManager.WakeLock lock(int i) { return pm.locks.get(i); }

    int acquires() { int c = 0; for (PowerManager.WakeLock w : pm.locks) c += w.acquireCalls; return c; }
    int releases() { int c = 0; for (PowerManager.WakeLock w : pm.locks) c += w.effectiveReleases; return c; }
    boolean anyHeld() { for (PowerManager.WakeLock w : pm.locks) if (w.isHeld()) return true; return false; }

    File[] sessionFiles() {
        File[] fs = ext.listFiles((d, name) -> name.startsWith("session_") && name.endsWith(".csv"));
        return fs == null ? new File[0] : fs;
    }

    /** Empty string if nothing is left running/held, else a description of what leaked. */
    String leaks() {
        StringBuilder b = new StringBuilder();
        if (alive("tracklog-sensors") > baseSensors) b.append(" sensorThread");
        if (alive("tracklog-stop") > baseStop) b.append(" stopThread");
        if (!sm.regs.isEmpty()) b.append(" sensorListeners=").append(sm.regs.size());
        if (lm.listener != null) b.append(" gpsListener");
        if (lm.gnss != null) b.append(" gnssCallback");
        if (anyHeld()) b.append(" wakeLockHeld");
        if (current != null) b.append(" liveServiceInstance");
        if (Recorder.LIVE.recording) b.append(" LIVE.recording");
        if (Recorder.LIVE.saving) b.append(" LIVE.saving");
        return b.toString().trim();
    }

    /** True if nothing was thrown/printed anywhere since this host was created. */
    boolean quiet() {
        return Harness.errBytes() == errBefore && android.os.Looper.UNCAUGHT.size() == loopBefore
                && Harness.UNCAUGHT_THREADS.size() == thrBefore;
    }

    String quietDetail() {
        return "stderrBytes=" + (Harness.errBytes() - errBefore) + " loopThrowables=" + (android.os.Looper.UNCAUGHT.size() - loopBefore)
                + " threadThrowables=" + (Harness.UNCAUGHT_THREADS.size() - thrBefore);
    }

    void close() throws InterruptedException {
        flushMain();
        probe.stop = true;
        mainThread.quitSafely();
        mainThread.join(2000);
        // test cleanup only: if the app under test leaked a recorder, kill it so later scenarios start clean
        for (FakeSensorManager.Reg g : sm.regs) if (g.handler != null) g.handler.getLooper().quit();
    }
}
