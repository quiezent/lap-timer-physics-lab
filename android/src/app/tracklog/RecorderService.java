package app.tracklog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps recording when the screen is off or another app is in front.
 *
 * All the state below is touched on the main thread only. The one slow job, stopping the recorder and copying the
 * file to Downloads, runs on a worker thread and hands back to the main thread when it is done. Until then 'rec'
 * stays set and 'stopping' is true, so a START pressed during the save cannot start a second recording that the
 * end of the save would then tear down.
 */
public class RecorderService extends Service {

    static final String ACTION_START = "app.tracklog.START";
    static final String ACTION_STOP = "app.tracklog.STOP";
    private static final String CHANNEL = "recording";
    private static final int NOTIF_ID = 17;

    private static int activeSaves;             // saves in flight over all instances of this service (main thread only)
    private final Handler main = new Handler(Looper.getMainLooper());
    private Recorder rec;                       // set while recording and while the last session is being saved
    private boolean stopping;                   // a save is running
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_START.equals(action)) {
            startRecording();
        } else if (rec == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (rec != null || stopping) return;     // already recording, or the last session is still being saved
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Track Logger is recording")
                    .setContentText("GPS + motion sensors. Tap to open and stop.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Exception e) {
            Recorder.LIVE.error = "Cannot run in background: " + e.getMessage();
            Recorder.LIVE.failed = true;
            stopSelf();
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracklog:recording");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();                  // released by stop, failure to start, and onDestroy
        } catch (Exception ignored) {
        }
        Recorder r = new Recorder(this);
        rec = r;
        if (!r.start()) {
            rec = null;
            releaseWakeLock();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopRecording() {
        if (stopping) return;                   // a second tap while the first save is running
        final Recorder r = rec;
        if (r == null) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        stopping = true;
        activeSaves++;
        Recorder.LIVE.saving = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    r.stop();
                    Exporter.exportToDownloads(app, r.getFile());
                } catch (Throwable t) {
                    Recorder.LIVE.exportText = "EXPORT FAILED (" + t + "). The recording is safe inside the app: "
                            + "tap Re-export last session.";
                } finally {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            finishStop(r);
                        }
                    });
                }
            }
        }, "tracklog-stop").start();
    }

    /** Main thread, after the save. */
    private void finishStop(Recorder r) {
        if (rec == r) rec = null;
        stopping = false;
        releaseWakeLock();
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        } catch (Exception ignored) {
        }
        // Last: the screen enables START only after the service is told to stop. A counter, because the system can
        // destroy this instance mid-save and a later instance may be saving another session when this one finishes.
        activeSaves--;
        Recorder.LIVE.saving = activeSaves > 0;
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        Recorder r = rec;
        rec = null;
        if (r != null) r.stop();      // e.g. the system tears the service down: keep what we have (stop() is idempotent)
        releaseWakeLock();
        super.onDestroy();
    }
}
