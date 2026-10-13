package android.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FAKE. Lifecycle (who calls onStartCommand / onDestroy, on which thread) is done by the fake "system"
 * (app.tracklog.ServiceHost); this class only records the calls the app makes and forwards Context calls.
 * startForeground / stopForeground / stopSelf never throw unless a failure is injected.
 */
public abstract class Service extends Context {
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int STOP_FOREGROUND_REMOVE = 1;
    public static final int STOP_FOREGROUND_DETACH = 2;

    private Context base;

    // ---- test hooks, not part of the Android API ----
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public volatile List<String> sink;                    // shared log owned by the fake system
    public volatile Runnable stopSelfHook;                // the fake system is told about stopSelf()
    public volatile boolean destroyed;                    // set by the fake system once onDestroy has run
    public volatile boolean foreground;
    public volatile RuntimeException startForegroundFailure;
    public volatile int startForegroundCalls, stopForegroundCalls, stopSelfCalls, lastForegroundType = -1;

    public void attach(Context base) { this.base = base; }

    private void record(String what) {
        String line = what + " @" + Thread.currentThread().getName() + (destroyed ? " [instance already destroyed]" : "");
        calls.add(line);
        List<String> k = sink;
        if (k != null) k.add(line);
    }

    public void onCreate() { }
    public abstract IBinder onBind(Intent intent);
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    public void onDestroy() { }

    public final void startForeground(int id, Notification n, int type) {
        record("startForeground(id=" + id + ",type=" + type + ")");
        startForegroundCalls++;
        lastForegroundType = type;
        RuntimeException f = startForegroundFailure;
        if (f != null) throw f;
        foreground = true;
    }

    public final void stopForeground(int flags) {
        record("stopForeground(" + flags + ")");
        stopForegroundCalls++;
        foreground = false;
    }

    public final void stopSelf() {
        record("stopSelf()");
        stopSelfCalls++;
        Runnable h = stopSelfHook;
        if (h != null) h.run();
    }

    @Override public Context getApplicationContext() { return base.getApplicationContext(); }
    @Override public Object getSystemService(String name) { return base.getSystemService(name); }
    @Override public File getExternalFilesDir(String type) { return base.getExternalFilesDir(type); }
    @Override public File getFilesDir() { return base.getFilesDir(); }
    @Override public ContentResolver getContentResolver() { return base.getContentResolver(); }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(name, mode); }
}
