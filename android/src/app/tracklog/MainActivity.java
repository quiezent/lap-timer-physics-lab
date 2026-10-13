package app.tracklog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/** One screen: start, stop, and enough live numbers to know the recording is healthy. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0B0D10;
    private static final int FG = 0xFFE8EAED;
    private static final int DIM = 0xFF8B949E;
    private static final int GREEN = 0xFF2ECC71;
    private static final int RED = 0xFFE5484D;
    private static final int AMBER = 0xFFF5A524;
    private static final int PANEL = 0xFF1C2128;
    private static final int REQ_PERM = 7;
    private static final String EXPORTING = "Exporting...";

    private TextView tvStatus, tvSpeed, tvGps, tvImu, tvG, tvLog, tvCal, tvMsg;
    private TextView tvLapHead, tvLapClock, tvLapLast, tvLapBest, tvLapListHead, tvLapList;
    private LinearLayout lapBlock, panel, stageBanner;
    private TextView tvStageWord, tvStageGuide;
    private String lapListShown = "";
    private Button btnStart, btnStop, btnExport, btnShare;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float sp, int color, boolean bold, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private Button button(String label, int bg, int fg, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setTextSize(18);
        bt.setTextColor(fg);
        bt.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bt.setBackgroundColor(bg);
        bt.setAllCaps(true);
        bt.setOnClickListener(l);
        return bt;
    }

    private void addBlock(LinearLayout root, View v, int topDp, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightDp > 0 ? dp(heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        root.addView(v, lp);
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header: the name of the app, and the way to the stages screen (release 1.4)
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text("TRACK LOGGER", 13, DIM, true, false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        Button btnStages = button("Stages", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { openStages(); }
        });
        btnStages.setTextSize(14);
        head.addView(btnStages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        addBlock(root, head, 0, 44);

        // while a stage is armed, recording or being marked, words stand in for the numbers (shown by refresh())
        stageBanner = new LinearLayout(this);
        stageBanner.setOrientation(LinearLayout.VERTICAL);
        stageBanner.setBackgroundColor(PANEL);
        stageBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvStageWord = text("", 30, FG, true, false);
        tvStageGuide = text("", 15, FG, false, false);
        TextView tvStageReminder = text(StageText.ROAD_REMINDER, 13, DIM, false, false);
        stageBanner.addView(tvStageWord);
        addBlock(stageBanner, tvStageGuide, 4, 0);
        addBlock(stageBanner, tvStageReminder, 8, 0);
        stageBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openStages(); }
        });
        stageBanner.setVisibility(View.GONE);
        addBlock(root, stageBanner, 12, 0);

        tvStatus = text("READY", 30, FG, true, false);
        addBlock(root, tvStatus, 4, 0);
        tvSpeed = text("-- km/h", 52, FG, true, false);
        addBlock(root, tvSpeed, 4, 0);

        // lap timer: the running lap, the last lap against the best, the best lap (hidden until a session is recording)
        lapBlock = new LinearLayout(this);
        lapBlock.setOrientation(LinearLayout.VERTICAL);
        tvLapHead = text("", 13, DIM, true, false);
        tvLapClock = text("", 46, FG, true, true);
        tvLapLast = text("", 20, FG, false, true);
        tvLapBest = text("", 20, FG, false, true);
        lapBlock.addView(tvLapHead);
        lapBlock.addView(tvLapClock);
        lapBlock.addView(tvLapLast);
        lapBlock.addView(tvLapBest);
        lapBlock.setVisibility(View.GONE);
        addBlock(root, lapBlock, 6, 0);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvGps = text("", 14, FG, false, true);
        tvImu = text("", 14, FG, false, true);
        tvG = text("", 14, FG, false, true);
        tvLog = text("", 14, FG, false, true);
        tvCal = text("", 12, DIM, false, true);
        panel.addView(tvGps);
        panel.addView(tvImu);
        panel.addView(tvG);
        panel.addView(tvLog);
        panel.addView(tvCal);
        addBlock(root, panel, 10, 0);

        tvMsg = text("", 14, AMBER, false, false);
        addBlock(root, tvMsg, 10, 0);

        btnStart = button("Start recording", GREEN, 0xFF04130A, new View.OnClickListener() {
            @Override public void onClick(View v) { onStartClicked(); }
        });
        addBlock(root, btnStart, 14, 68);

        btnStop = button("Stop & save", RED, 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { onStopClicked(); }
        });
        addBlock(root, btnStop, 10, 68);

        btnExport = button("Re-export last session", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onExportClicked(); }
        });
        addBlock(root, btnExport, 22, 52);

        btnShare = button("Share last export", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onShareClicked(); }
        });
        addBlock(root, btnShare, 8, 52);

        Button btnBattery = button("Battery: allow background", PANEL, DIM, new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        });
        addBlock(root, btnBattery, 8, 52);

        tvLapListHead = text("LAP TIMES", 13, DIM, true, false);
        tvLapListHead.setVisibility(View.GONE);
        addBlock(root, tvLapListHead, 22, 0);
        tvLapList = text("", 15, FG, false, true);
        tvLapList.setVisibility(View.GONE);
        addBlock(root, tvLapList, 4, 0);

        TextView how = text(
                "1  Fix the phone rigidly (any angle, but it must not move).\n"
                        + "2  Car parked: press START and keep the car still for 5 seconds.\n"
                        + "3  Drive. The screen can be locked: recording continues.\n"
                        + "4  Parked again: press STOP & SAVE.\n"
                        + "Laps are timed by GPS at one line on the Sepang main straight (not the official loop). "
                        + "The first pass starts the clock.\n"
                        + "Files: Downloads/TrackLogger/session_*.csv\n"
                        + "Do not touch the phone while driving.",
                12, DIM, false, false);
        addBlock(root, how, 22, 0);

        setContentView(sv);
    }

    // ------------------------------------------------------------------ actions

    private static final String NOTIF_PERMISSION = "android.permission.POST_NOTIFICATIONS";   // API 33; a string so this compiles against older stubs
    private static final String PREF_ASKED_NOTIF = "asked_notifications";

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 13+ hides the "recording" notification until this is granted; the recording itself does not need it. */
    private boolean wantsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(NOTIF_PERMISSION) != PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences(Exporter.PREFS, MODE_PRIVATE).getBoolean(PREF_ASKED_NOTIF, false);
    }

    private void onStartClicked() {
        final Recorder.Live L = Recorder.LIVE;
        if (L.recording || L.saving) return;
        if (!hasLocation() || wantsNotificationPermission()) {
            ArrayList<String> p = new ArrayList<String>();
            if (!hasLocation()) {
                p.add(Manifest.permission.ACCESS_FINE_LOCATION);
                p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (wantsNotificationPermission()) {
                p.add(NOTIF_PERMISSION);
                getSharedPreferences(Exporter.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ASKED_NOTIF, true).apply();   // ask once only
            }
            requestPermissions(p.toArray(new String[0]), REQ_PERM);
            return;
        }
        try {
            Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            if (hasLocation()) {
                Toast.makeText(this, "Location allowed. Press START again.", Toast.LENGTH_LONG).show();
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Android shows no dialog after two refusals, so asking again does nothing: send the user to Settings.
                Toast.makeText(this, "Location is blocked. In Permissions > Location choose Allow and turn on "
                        + "\"Use precise location\".", Toast.LENGTH_LONG).show();
                openAppSettings();
            } else {
                Toast.makeText(this, "Recording needs Precise location. Press START and choose Precise, then Allow.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings > Apps > Track Logger > Permissions", Toast.LENGTH_LONG).show();
        }
    }

    private void openStages() {
        startActivity(new Intent(this, StagesActivity.class));
    }

    private void onStopClicked() {
        if (StageLive.busy()) {              // a stage is armed, recording or being marked: this button ends it
            final int st = StageLive.state;
            final boolean marking = st == StageLive.MARK_START || st == StageLive.MARK_DRIVE;
            startService(new Intent(this, RecorderService.class)
                    .setAction(marking ? RecorderService.ACTION_MARK_CANCEL : RecorderService.ACTION_DISARM));
            return;
        }
        if (!Recorder.LIVE.recording) return;
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP);
        startService(i);
    }

    private void onExportClicked() {
        if (Recorder.LIVE.recording || Recorder.LIVE.saving || EXPORTING.equals(Recorder.LIVE.exportText)) return;
        final android.content.Context app = getApplicationContext();
        Recorder.LIVE.exportText = EXPORTING;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File f = Exporter.newestSession(app);
                Exporter.exportToDownloads(app, f);
            }
        }).start();
    }

    private void onShareClicked() {
        String u = Recorder.LIVE.lastExportUri;
        if (u == null || u.length() == 0) {
            u = getSharedPreferences(Exporter.PREFS, MODE_PRIVATE).getString(Exporter.KEY_URI, "");
        }
        if (u.length() == 0) {
            Toast.makeText(this, "Nothing exported yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/csv");
            s.putExtra(Intent.EXTRA_STREAM, Uri.parse(u));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Share session"));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot share: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e2) {
                Toast.makeText(this, "Open Settings > Apps > Track Logger > Battery > Unrestricted", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ periodic refresh

    void refresh() {
        final Recorder.Live L = Recorder.LIVE;
        final boolean rec = L.recording;
        final long nowNs = SystemClock.elapsedRealtimeNanos();
        final double el = rec ? (nowNs - L.startElapsedNs) / 1e9 : 0.0;

        // status line
        final boolean saving = L.saving;
        final String ex = L.exportText;
        String status;
        int color;
        String msg = "";
        int msgColor = AMBER;
        if (rec) {
            if (L.failed) {
                status = "ERROR";
                color = RED;
                msg = L.error;
                msgColor = RED;
            } else if (!L.calDone) {
                int n = (int) Math.ceil(Recorder.CAL_TO_S - el);
                status = "STAY STILL  " + Math.max(1, n);
                color = AMBER;
            } else {
                int s = (int) el;
                status = String.format(Locale.US, "REC  %02d:%02d", s / 60, s % 60);
                color = RED;
                if (!L.calOk) msg = "Calibration says the car was moving. Park, stop and restart if you can.";
            }
            if (!L.failed && L.error.length() > 0) msg = msg.length() > 0 ? msg + "\n" + L.error : L.error;
        } else if (saving || EXPORTING.equals(ex)) {
            status = "SAVING...";
            color = AMBER;
        } else if (ex.startsWith("Saved")) {
            status = "SAVED";
            color = GREEN;
            msg = ex;
            msgColor = GREEN;
            if (L.failed) {                                  // e.g. storage filled up during the session
                msg = ex + "\nThe recording stopped early: " + L.error;
                msgColor = AMBER;
            }
        } else if (ex.startsWith("EXPORT FAILED")) {
            status = "NOT SAVED";
            color = RED;
            msg = ex;
            msgColor = RED;
        } else if (L.failed) {
            status = "ERROR";
            color = RED;
            msg = L.error;
            msgColor = RED;
        } else {
            status = "READY";
            color = FG;
            if (ex.length() > 0) msg = ex;                   // "Nothing to export."
            else if (!hasLocation()) msg = "Press START and allow Precise location.";
        }
        tvStatus.setText(status);
        tvStatus.setTextColor(color);
        tvMsg.setText(msg);
        tvMsg.setTextColor(msgColor);
        tvMsg.setVisibility(msg.length() > 0 ? View.VISIBLE : View.GONE);

        // speed
        if (rec && L.haveFix) {
            tvSpeed.setText(String.format(Locale.US, "%.0f km/h", L.speedMs * 3.6));
            tvSpeed.setTextColor(FG);
        } else {
            tvSpeed.setText("-- km/h");
            tvSpeed.setTextColor(DIM);
        }

        // detail lines
        if (rec) {
            tvGps.setText(L.haveFix
                    ? String.format(Locale.US, "GPS  fix +/-%.1f m  %.1f Hz  sats %d/%d", L.hAcc, L.lHz, L.satsUsed, L.satsVisible)
                    : String.format(Locale.US, "GPS  searching...  sats %d/%d", L.satsUsed, L.satsVisible));
            tvImu.setText(String.format(Locale.US, "IMU  accel %.0f Hz   gyro %s", L.aHz,
                    L.hasGyro ? String.format(Locale.US, "%.0f Hz", L.gHz) : "NONE"));
            tvG.setText(L.calDone
                    ? String.format(Locale.US, "G    horizontal %.2f   vertical %.2f", L.horizG, L.vertG)
                    : "G    (shown after the 5 s calibration)");
            tvLog.setText(String.format(Locale.US, "LOG  %.1f MB   A %dk  G %dk  GPS %d",
                    L.bytes / 1048576.0, L.rowsA / 1000, L.rowsG / 1000, L.rowsL));
            tvCal.setText("CAL  " + L.calText + "\n" + L.fileName);
        } else {
            tvGps.setText("GPS  --");
            tvImu.setText("IMU  --");
            tvG.setText("G    --");
            tvLog.setText(L.fileName.length() > 0 ? String.format(Locale.US, "LOG  last: %s (%.1f MB)", L.fileName, L.bytes / 1048576.0) : "LOG  --");
            tvCal.setText(L.calText.length() > 0 ? "CAL  " + L.calText : "");
        }

        final boolean idle = !rec && !saving && !EXPORTING.equals(ex);
        btnStart.setEnabled(idle);
        btnStart.setAlpha(idle ? 1f : 0.35f);
        btnStop.setEnabled(rec);
        btnStop.setAlpha(rec ? 1f : 0.35f);
        btnExport.setEnabled(idle);
        btnExport.setAlpha(idle ? 1f : 0.35f);
        btnShare.setEnabled(idle);
        btnShare.setAlpha(idle ? 1f : 0.35f);

        try {
            refreshLaps(L, rec, nowNs);
        } catch (RuntimeException e) {          // the lap display must never take the screen down: it shares a process with the recording
            lapBlock.setVisibility(View.GONE);
            tvLapListHead.setVisibility(View.GONE);
            tvLapList.setVisibility(View.GONE);
        }
        try {
            applyStageMode(L);
        } catch (RuntimeException e) {
            stageBanner.setVisibility(View.GONE);
        }
    }

    /**
     * While a stage is armed, recording or being marked, this screen shows words and no numbers about the drive: no speed, no lap clock,
     * no G, no recording clock (the rule for open roads, StageLive.numbersAllowed; the stages screen has the details). It runs last so
     * that it overrides what the rest of refresh() has just put on the screen.
     */
    private void applyStageMode(Recorder.Live L) {
        final boolean stage = StageLive.busy();
        stageBanner.setVisibility(stage ? View.VISIBLE : View.GONE);
        panel.setVisibility(stage ? View.GONE : View.VISIBLE);
        tvSpeed.setVisibility(stage ? View.GONE : View.VISIBLE);
        tvStatus.setVisibility(stage ? View.GONE : View.VISIBLE);
        if (stage) {
            final int state = StageLive.state;
            tvStageWord.setText(StageText.stateWord(state));
            tvStageWord.setTextColor(state == StageLive.ARMED ? GREEN : state == StageLive.SAVING || state == StageLive.MARK_SAVING ? AMBER : RED);
            tvStageGuide.setText(StageText.guide(state, StageLive.stageName, StageLive.markHint, StageLive.message));
            lapBlock.setVisibility(View.GONE);
            tvLapListHead.setVisibility(View.GONE);
            tvLapList.setVisibility(View.GONE);
            btnStart.setEnabled(false);
            btnStart.setAlpha(0.35f);
            btnStop.setText(state == StageLive.MARK_START || state == StageLive.MARK_DRIVE ? "Cancel marking" : "Disarm");
            btnStop.setEnabled(state != StageLive.SAVING && state != StageLive.MARK_SAVING);
            btnStop.setAlpha(btnStop.isEnabled() ? 1f : 0.35f);
            btnExport.setEnabled(false);
            btnExport.setAlpha(0.35f);
            btnShare.setEnabled(false);
            btnShare.setAlpha(0.35f);
            if (StageLive.error.length() > 0) {
                tvMsg.setText(StageLive.error);
                tvMsg.setTextColor(RED);
                tvMsg.setVisibility(View.VISIBLE);
            } else {
                tvMsg.setVisibility(View.GONE);
            }
        } else {
            btnStop.setText("Stop & save");
        }
    }

    /** Lap clock, last and best lap, and the list. Reads Recorder.LIVE only. */
    private void refreshLaps(Recorder.Live L, boolean rec, long nowNs) {
        final int n = L.lapNo;                       // complete laps
        final long start = L.lapStartNs;             // 0 until the line has been crossed once
        final boolean timing = rec && start > 0L;
        if (!rec && n == 0) {                        // nothing to show
            lapBlock.setVisibility(View.GONE);
            tvLapListHead.setVisibility(View.GONE);
            tvLapList.setVisibility(View.GONE);
            return;
        }
        lapBlock.setVisibility(View.VISIBLE);

        if (timing) {
            tvLapHead.setText("LAP " + (n + 1));
            tvLapClock.setText(LapTimer.fmtTenths((nowNs - start) / 1e9));
            tvLapClock.setTextColor(FG);
            tvLapClock.setVisibility(View.VISIBLE);
        } else if (rec) {
            tvLapHead.setText("LAP TIMER  waiting for the line");
            tvLapClock.setText("-:--.-");
            tvLapClock.setTextColor(DIM);
            tvLapClock.setVisibility(View.VISIBLE);
        } else {                                     // stopped: the session's laps stay on screen
            tvLapHead.setText(n == 1 ? "LAST SESSION  1 lap" : "LAST SESSION  " + n + " laps");
            tvLapClock.setVisibility(View.GONE);
        }

        if (n > 0) {
            final boolean isBest = L.bestLapNo == n;
            tvLapLast.setText("LAST  " + LapTimer.fmt(L.lastLapS) + "  " + (isBest ? "best" : LapTimer.fmtDelta(L.lastLapS - L.bestLapS)));
            tvLapLast.setTextColor(isBest ? GREEN : FG);
            tvLapBest.setText("BEST  " + LapTimer.fmt(L.bestLapS) + "  lap " + L.bestLapNo);
            tvLapBest.setTextColor(GREEN);
        } else {
            tvLapLast.setText("LAST  --:--.--");
            tvLapLast.setTextColor(DIM);
            tvLapBest.setText("BEST  --:--.--");
            tvLapBest.setTextColor(DIM);
        }

        final String list = L.lapText;
        final boolean haveList = list.length() > 0;
        tvLapListHead.setVisibility(haveList ? View.VISIBLE : View.GONE);
        tvLapList.setVisibility(haveList ? View.VISIBLE : View.GONE);
        if (haveList && !list.equals(lapListShown)) {   // only touch the layout when a lap was added
            tvLapList.setText(list);
            lapListShown = list;
        }
    }
}
