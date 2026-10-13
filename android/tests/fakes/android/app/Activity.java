package android.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * FAKE. Lifecycle is driven by the test (it calls onCreate, onResume, ...); this class records what the app asks the system to do
 * (services started, activities opened, permissions requested) and forwards Context calls to a base context.
 */
public class Activity extends Context {
    public static final int RESULT_CANCELED = 0, RESULT_OK = -1;

    private Context base;
    private final Resources resources = new Resources();
    private final Window window = new Window();

    // ---- test hooks, not part of the Android API ----
    public final List<Intent> startedServices = new ArrayList<>();            // by startService
    public final List<Intent> startedForegroundServices = new ArrayList<>();  // by startForegroundService
    public final List<Intent> startedActivities = new ArrayList<>();
    public final List<Integer> resultRequests = new ArrayList<>();            // request codes of startActivityForResult
    public final List<String[]> permissionRequests = new ArrayList<>();
    public final Set<String> granted = new HashSet<>();
    public boolean rationale;                                                 // answer of shouldShowRequestPermissionRationale
    public boolean finished;
    public View content;
    public final ConcurrentLinkedQueue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    public Thread uiThread = Thread.currentThread();

    public void attach(Context base) { this.base = base; }

    /** Runs what other threads handed to runOnUiThread, on the calling (test) thread. */
    public int pumpUi() {
        int n = 0;
        Runnable r;
        while ((r = uiQueue.poll()) != null) { r.run(); n++; }
        return n;
    }

    // ---- the API ----
    protected void onCreate(Bundle b) { }
    protected void onResume() { }
    protected void onPause() { }
    public void onBackPressed() { finish(); }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) { }
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) { }

    public void setContentView(View v) { content = v; }
    public void finish() { finished = true; }
    public Window getWindow() { return window; }
    public Resources getResources() { return resources; }
    public String getPackageName() { return "app.tracklog"; }

    public final void runOnUiThread(Runnable r) {
        if (Thread.currentThread() == uiThread) r.run();
        else uiQueue.add(r);
    }

    public int checkSelfPermission(String p) { return granted.contains(p) ? 0 : -1; }
    public final void requestPermissions(String[] p, int code) { permissionRequests.add(p); }
    public boolean shouldShowRequestPermissionRationale(String p) { return rationale; }

    public void startActivity(Intent i) { startedActivities.add(i); }
    public void startActivityForResult(Intent i, int code) { startedActivities.add(i); resultRequests.add(code); }
    public void startService(Intent i) { startedServices.add(i); }
    public void startForegroundService(Intent i) { startedForegroundServices.add(i); }

    @Override public Context getApplicationContext() { return base.getApplicationContext(); }
    @Override public Object getSystemService(String name) { return base.getSystemService(name); }
    @Override public File getExternalFilesDir(String type) { return base.getExternalFilesDir(type); }
    @Override public File getFilesDir() { return base.getFilesDir(); }
    @Override public ContentResolver getContentResolver() { return base.getContentResolver(); }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(name, mode); }
}
