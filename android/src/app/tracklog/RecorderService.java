package app.tracklog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;

/**
 * Keeps recording when the screen is off or another app is in front.
 *
 * All the state below is touched on the main thread only. The one slow job, stopping the recorder and copying the
 * file to Downloads, runs on a worker thread and hands back to the main thread when it is done. Until then 'rec'
 * stays set and 'stopping' is true, so a START pressed during the save cannot start a second recording that the
 * end of the save would then tear down.
 *
 * Stages (release 1.4) add three more things the service can be doing, all in the same foreground service:
 *  - MARKING: a recording whose fixes also go to a MarkSession; the driver marks the start and finish of a new stage on it.
 *  - ARMED: no recording, only the GPS, watched by an ArmedWatcher for the car coming to the start line of a stage.
 *  - a STAGE RUN: a recording started by the watcher, whose fixes also go to a StageSession. When the session says the run is
 *    over, the recording is stopped and saved by the same code as a manual stop, the run is saved next to the stage, and the service
 *    arms again. A recording in which the start line was never crossed is deleted.
 */
public class RecorderService extends Service {

    static final String ACTION_START = "app.tracklog.START";
    static final String ACTION_STOP = "app.tracklog.STOP";
    static final String ACTION_ARM = "app.tracklog.ARM";                  // extra EXTRA_STAGE_ID
    static final String ACTION_DISARM = "app.tracklog.DISARM";
    static final String ACTION_MARK_NEW = "app.tracklog.MARK_NEW";        // start a recording to mark a new stage on
    static final String ACTION_MARK_START = "app.tracklog.MARK_START";
    static final String ACTION_MARK_FINISH = "app.tracklog.MARK_FINISH";  // extra EXTRA_NAME
    static final String ACTION_MARK_CANCEL = "app.tracklog.MARK_CANCEL";
    static final String EXTRA_STAGE_ID = "stage_id";
    static final String EXTRA_NAME = "name";
    static final String PREF_BEEP = "stage_beep";

    /** An armed phone with no run for this long switches itself off (each completed run starts the time again). Not final: the tests shorten it. */
    static final long AUTO_DISARM_DEFAULT_MS = 3L * 3600L * 1000L;
    static volatile long autoDisarmMs = AUTO_DISARM_DEFAULT_MS;

    private static final String CHANNEL = "recording";
    private static final int NOTIF_ID = 17;

    private static int activeSaves;             // saves in flight over all instances of this service (main thread only)
    private final Handler main = new Handler(Looper.getMainLooper());
    private Recorder rec;                       // set while recording and while the last session is being saved
    private boolean stopping;                   // a save is running
    private PowerManager.WakeLock wakeLock;

    // stages (main thread only)
    private Stage armedStage;                   // set from ARM until the end of the armed state, also during a run of it
    private StageWatcher watcher;               // remembers, across runs, that the car must leave the start area first
    private ArmedWatcher armedGps;              // non-null while the GPS-only listener is registered
    private StageSession session;               // non-null during a stage run
    private MarkSession marking;                // non-null while marking a new stage
    private boolean reArmAfter;                 // the save in flight was started by the end of a run: arm again afterwards
    private StageStore store;
    private volatile Beeper beeper;             // used on the sensor thread (the beeps) and the main thread (the setting)
    private final Runnable armedTick = new Runnable() {
        @Override
        public void run() {
            final ArmedWatcher w = armedGps;
            if (w == null) return;
            w.tick(SystemClock.elapsedRealtimeNanos() / 1e9);
            main.postDelayed(this, 1000);
        }
    };
    private final Runnable autoDisarm = new Runnable() {
        @Override
        public void run() {
            if (armedGps != null && rec == null) disarm("Switched off after 3 hours without a run.");
        }
    };

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
        } else if (ACTION_ARM.equals(action)) {
            arm(intent.getStringExtra(EXTRA_STAGE_ID));
        } else if (ACTION_DISARM.equals(action)) {
            if (rec == null && armedStage == null) stopIfIdle(); else disarm("Disarmed.");
        } else if (ACTION_MARK_NEW.equals(action)) {
            startMarking();
        } else if (ACTION_MARK_START.equals(action)) {
            if (marking == null) stopIfIdle(); else markStart();
        } else if (ACTION_MARK_FINISH.equals(action)) {
            if (marking == null) stopIfIdle(); else markFinish(intent.getStringExtra(EXTRA_NAME));
        } else if (ACTION_MARK_CANCEL.equals(action)) {
            if (marking == null) stopIfIdle(); else stopRecording();    // a marking drive that is given up is an ordinary session
        } else if (rec == null && armedStage == null) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** A stray intent reached a service that has nothing to do: it must not stay behind idle. */
    private void stopIfIdle() {
        if (rec != null || armedStage != null || stopping) return;
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ plain recording

    private void startRecording() {
        if (rec != null || stopping || armedStage != null) return;     // already recording, armed, or the last session is still being saved
        beginRecording(null, "Track Logger is recording", "GPS + motion sensors. Tap to open and stop.");
    }

    /**
     * Foreground notification, wake lock, recorder.
     *
     * @param sink the stage feature to give the recording to, or null for an ordinary recording
     * @return the recorder, or null if it could not start (the failure is in Recorder.LIVE.error)
     */
    private Recorder beginRecording(StageSink sink, String title, String text) {
        if (!foreground(title, text)) {
            if (armedStage == null) stopSelf();
            return null;
        }
        acquireWakeLock();
        Recorder r = new Recorder(this);
        if (sink != null) r.setStageSink(sink);
        rec = r;
        if (!r.start()) {
            rec = null;
            if (armedStage == null) {
                releaseWakeLock();
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return null;
        }
        return r;
    }

    /** Puts the service in the foreground (or updates its notification). False if the system refused. */
    private boolean foreground(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
            // in a stage mode the notification leads to the stages screen, otherwise to the recorder screen
            Intent open = new Intent(this, armedStage != null || marking != null ? StagesActivity.class : MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            return true;
        } catch (Exception e) {
            Recorder.LIVE.error = "Cannot run in background: " + e.getMessage();
            Recorder.LIVE.failed = true;
            return false;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracklog:recording");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();                  // released by stop, failure to start, and onDestroy
        } catch (Exception ignored) {
            wakeLock = null;
        }
    }

    // ------------------------------------------------------------------ stopping (manual, or the end of a stage run)

    private void stopRecording() {
        if (stopping) return;                   // a second tap while the first save is running
        if (rec == null) {
            if (armedStage != null) {           // armed, not recording: STOP means "disarm"
                disarm("Disarmed.");
                return;
            }
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        stopAndSave(false);
    }

    /**
     * Stops the recording and saves it, with whatever the stage feature has to save beside it.
     *
     * @param reArm true when the end of a stage run caused this: after the save the service arms again. False for a manual stop,
     *              which ends the armed state too.
     */
    private void stopAndSave(final boolean reArm) {
        final Recorder r = rec;
        if (r == null || stopping) return;
        stopping = true;
        activeSaves++;
        Recorder.LIVE.saving = true;
        reArmAfter = reArm && armedStage != null && session != null;
        final StageSession ss = session;
        final MarkSession ms = marking;
        final Stage newStage = ms == null ? null : ms.stage();
        final StageStore st = ss != null || newStage != null ? store() : null;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean keepFile = true;
                try {
                    r.stop();                    // closes an open stage run first (a DNF row), then writes STOP
                    final File f = r.getFile();
                    final String name = f == null ? "" : f.getName();
                    if (ss != null && st != null) {
                        if (ss.aborted()) {
                            keepFile = false;                                   // the start line was never crossed: nothing worth keeping
                        } else {
                            final RunResult res = ss.result(Scorer.runIdFor(name, 1), name, r.startUtcMs());
                            if (res != null) {
                                st.saveRun(res);
                                StageLive.lastRun = res;
                                StageLive.lastRunSeq++;
                            }
                            StageLive.runsThisArming++;
                        }
                    } else if (newStage != null && st != null) {
                        saveMarkedStage(st, newStage, f, r.startUtcMs());
                    }
                    if (keepFile) {
                        Exporter.exportToDownloads(app, f);
                    } else {
                        if (f != null) f.delete();
                        Recorder.LIVE.exportText = "No run was recorded, so the recording was not kept.";
                    }
                } catch (Throwable t) {
                    StageLive.error = "Could not save the stage run (" + t + ").";
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

    /** Worker thread: the new stage is written, and the marking drive scored against it as its first run. */
    private void saveMarkedStage(StageStore st, Stage built, File csv, long startUtcMs) {
        Stage stage = built;
        try {
            st.save(built);
            // Every run is scored against the stage as the file holds it (the file keeps a fixed number of decimals), so that scoring
            // this recording again later, from the stored stage, gives the same numbers.
            final Stage onDisk = st.load(built.id);
            if (onDisk != null) stage = onDisk;
            StageLive.markedStageId = built.id;
            StageLive.markedSeq++;
        } catch (Exception e) {
            StageLive.error = "Could not save the stage (" + e.getMessage() + ").";
            return;
        }
        try {
            final LogReader.Log log = LogReader.read(csv);
            for (RunResult r : Scorer.scoreAll(stage, log.fixes, csv.getName(), (double) startUtcMs)) st.saveRun(r);
        } catch (Exception e) {
            StageLive.error = "The stage is saved, but its first run could not be scored (" + e.getMessage() + ").";
        }
    }

    /** Main thread, after the save. */
    private void finishStop(Recorder r) {
        if (rec == r) rec = null;
        stopping = false;
        final boolean again = reArmAfter && armedStage != null;
        reArmAfter = false;
        session = null;
        marking = null;
        if (again) {
            armAgain();                         // GPS only; the service stays in the foreground with its wake lock
        } else {
            clearArmed();
            releaseWakeLock();
            try {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            } catch (Exception ignored) {
            }
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

    // ------------------------------------------------------------------ marking a new stage

    private void startMarking() {
        if (rec != null || stopping || armedStage != null) return;
        StageLive.reset();
        StageLive.state = StageLive.MARK_START;
        final MarkSession ms = new MarkSession();
        marking = ms;
        final Recorder r = beginRecording(ms, "Track Logger is recording", "Marking a stage. Tap to open.");
        if (r == null) {
            marking = null;
            StageLive.state = StageLive.IDLE;
            StageLive.error = Recorder.LIVE.error;
        }
    }

    private void markStart() {
        final MarkSession ms = marking;
        if (ms == null) return;
        final String why = ms.markStart();
        StageLive.error = why;
    }

    private void markFinish(String name) {
        final MarkSession ms = marking;
        if (ms == null || rec == null || stopping) return;
        final String id = store().newId(System.currentTimeMillis());
        final String why = ms.markFinish(id, name == null ? "Stage" : name, System.currentTimeMillis());
        StageLive.error = why;
        if (why.length() == 0) {
            StageLive.state = StageLive.MARK_SAVING;
            StageLive.message = "Stage marked. Saving.";
            stopAndSave(false);                  // the stage is saved by the worker after the recording is closed
        }
    }

    // ------------------------------------------------------------------ armed

    private StageStore store() {
        if (store == null) store = new StageStore(new File(getFilesDir(), "stages"));
        return store;
    }

    private Beeper beeper() {
        if (beeper == null) beeper = new Beeper();
        return beeper;
    }

    /** The beep switch on the stages screen is read each time the phone is armed. */
    private void readBeepSetting() {
        boolean on = true;
        try {
            on = getSharedPreferences(Exporter.PREFS, MODE_PRIVATE).getBoolean(PREF_BEEP, true);
        } catch (RuntimeException ignored) {
        }
        beeper().setEnabled(on);
    }

    private void arm(String stageId) {
        if (rec != null || stopping || armedStage != null) return;
        final Stage st = stageId == null ? null : store().load(stageId);
        if (st == null) {
            // the service was started in the foreground and owes the system a notification, even if it has nothing to do
            foreground("Track Logger", "That stage could not be read.");
            StageLive.error = "That stage could not be read.";
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        StageLive.reset();
        armedStage = st;
        watcher = new StageWatcher(st);
        if (!foreground("Track Logger is armed", "Waiting for the start of " + st.name() + ". Tap to open.")) {
            armedStage = null;
            watcher = null;
            StageLive.error = Recorder.LIVE.error;
            stopSelf();
            return;
        }
        acquireWakeLock();
        readBeepSetting();
        StageLive.stageId = st.id;
        StageLive.stageName = st.name();
        registerArmed();
    }

    /** Puts the GPS-only listener on and updates the screen. */
    private void registerArmed() {
        final Stage st = armedStage;
        final StageWatcher w = watcher;
        if (st == null || w == null) return;
        final ArmedWatcher aw = new ArmedWatcher(w, new ArmedWatcher.Callback() {
            @Override
            public void trigger(Location previous, Location trig) {
                onTrigger(previous, trig);
            }
        });
        armedGps = aw;
        try {
            final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                StageLive.error = "GPS is switched off. Turn Location on (swipe down, Location tile).";
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, aw, Looper.getMainLooper());
        } catch (SecurityException e) {
            StageLive.error = "Location permission missing (needs Precise location).";
        } catch (Exception e) {
            StageLive.error = "GPS error: " + e.getMessage();
        }
        StageLive.state = StageLive.ARMED;
        StageLive.message = "Armed. Waiting for the start of " + st.name() + ".";
        main.removeCallbacks(armedTick);
        main.postDelayed(armedTick, 1000);
        main.removeCallbacks(autoDisarm);
        main.postDelayed(autoDisarm, autoDisarmMs);
    }

    /** Takes the GPS-only listener off. The armed stage stays. */
    private void unregisterArmed() {
        final ArmedWatcher aw = armedGps;
        armedGps = null;
        main.removeCallbacks(armedTick);
        if (aw != null) {
            try {
                ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(aw);
            } catch (Exception ignored) {
            }
        }
    }

    /** The car came moving into the approach zone: start recording, with the stage session on it. */
    private void onTrigger(Location previous, Location trig) {
        final Stage st = armedStage;
        if (st == null || rec != null || stopping || armedGps == null) return;
        unregisterArmed();
        main.removeCallbacks(autoDisarm);
        final SessionListener l = new SessionListener();
        final StageSession ss = new StageSession(st, l);
        l.session = ss;
        session = ss;
        StageLive.state = StageLive.RECORDING;
        StageLive.message = "Recording. Waiting for the start line.";
        final Recorder r = beginRecording(ss, "Track Logger is recording", "Stage: " + st.name() + ". Tap to open.");
        if (r == null) {                                       // could not start: stay armed, but not retry on every fix
            session = null;
            StageLive.error = Recorder.LIVE.error;
            if (watcher != null) watcher.afterRun();
            registerArmed();
            return;
        }
        // the fixes that made the watcher trigger go into the recording too, so a start line crossed right after the trigger is not missed
        if (previous != null && trig.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos() <= 3000000000L) r.injectLocation(previous);
        r.injectLocation(trig);
    }

    /** After a run: GPS only again, and the car must leave the start area before it can trigger again. */
    private void armAgain() {
        final Stage st = armedStage;
        final StageWatcher w = watcher;
        if (st == null || w == null) return;
        w.afterRun();
        StageLive.stopped = false;
        registerArmed();
        foreground("Track Logger is armed", "Waiting for the start of " + st.name() + ". Tap to open.");
    }

    /** User or timer: the armed state ends. A recording in progress is stopped and saved (no new arming afterwards). */
    private void disarm(String why) {
        if (rec != null) {
            stopRecording();                     // ends the run as a manual stop does; finishStop clears the armed state
            return;
        }
        if (armedStage == null) {
            return;
        }
        clearArmed();
        StageLive.message = why;
        releaseWakeLock();
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        } catch (Exception ignored) {
        }
    }

    private void clearArmed() {
        unregisterArmed();
        main.removeCallbacks(autoDisarm);
        armedStage = null;
        watcher = null;
        session = null;
        marking = null;
        final String keepError = StageLive.error;
        final RunResult last = StageLive.lastRun;
        StageLive.reset();
        StageLive.error = keepError;
        StageLive.lastRun = last;
    }

    /** What the stage session tells the service; called on the sensor thread, so it only beeps and posts to the main thread. */
    private final class SessionListener implements StageSession.Listener {
        volatile StageSession session;

        @Override public void runStarted() { beeper().start(); }
        @Override public void runFinished() { beeper().finish(); }
        @Override public void runFailed(String why) { beeper().fail(); }

        @Override
        public void ended() {
            final StageSession s = session;
            main.post(new Runnable() {
                @Override
                public void run() {
                    onRunEnded(s);
                }
            });
        }
    }

    /** Main thread: the stage session says the run is over. */
    private void onRunEnded(StageSession s) {
        if (s == null || session != s || rec == null || stopping) return;
        stopAndSave(true);
    }

    @Override
    public void onDestroy() {
        Recorder r = rec;
        rec = null;
        if (r != null) r.stop();      // e.g. the system tears the service down: keep what we have (stop() is idempotent)
        unregisterArmed();
        main.removeCallbacks(autoDisarm);
        armedStage = null;
        watcher = null;
        session = null;
        marking = null;
        if (StageLive.state != StageLive.IDLE) StageLive.reset();
        if (beeper != null) beeper.release();
        releaseWakeLock();
        super.onDestroy();
    }
}
