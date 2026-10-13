package app.tracklog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Stages (release 1.4): mark the start and finish of a road once, then drive it as often as you like with the phone armed, and compare
 * the runs afterwards. The screen is a list of pages (HOME, DETAIL of one stage, COMPARE two runs, MARK a new stage) shown one at a
 * time inside one frame that stays put: the status of the armed phone, the road reminder, the result of the last background job.
 *
 * The rule for open roads is kept here and nowhere else: while a stage is armed, recording or being marked and the car is not known to
 * be stopped (StageLive.numbersAllowed), no page shows a time, a difference or a comparison. The pages are rebuilt whenever that answer
 * changes. The screen also shares a process with the recording, so a mistake here would stop the recording: the refresh catches what
 * it can and says so on the screen instead.
 *
 * All the words and numbers come from StageText; this class only lays them out. The slow jobs (scoring recordings, backing up,
 * restoring) run on a worker thread and report in the notice line.
 */
public class StagesActivity extends Activity {

    private static final int BG = 0xFF0B0D10;
    private static final int FG = 0xFFE8EAED;
    private static final int DIM = 0xFF8B949E;
    private static final int GREEN = 0xFF2ECC71;
    private static final int RED = 0xFFE5484D;
    private static final int AMBER = 0xFFF5A524;
    private static final int PANEL = 0xFF1C2128;
    private static final int DARK_ON_GREEN = 0xFF04130A;

    private static final int HOME = 0, DETAIL = 1, COMPARE = 2, MARK = 3;
    private static final int BEST = 0, PREVIOUS = 1, FIRST = 2;
    private static final int REQ_PERM = 21, REQ_RESTORE = 22, REQ_SCORE_FILE = 23;

    private static final String PREF_ACK = "stage_ack";
    private static final String PREF_MARK_NAME = "stage_mark_name";
    private static final String NOTIF_PERMISSION = "android.permission.POST_NOTIFICATIONS";   // API 33; a string so this compiles against older stubs
    private static final String PREF_ASKED_NOTIF = "asked_notifications";                    // the same key MainActivity uses: asked once for both screens

    private StageStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 300);
        }
    };

    // the frame, built once
    private LinearLayout body, liveBox, lastBox;
    private TextView tvTitle, tvWord, tvGuide, tvReminder, tvError, tvLastHead, tvLastRun, tvNotice;
    private Button btnBack, btnLive;

    // where the screen is
    private int page = HOME;
    private String stageId = "", runId = "";
    private int against = BEST;
    private String builtKey = "";
    private int dataVersion;
    private long seenMarkedSeq, shownLastRunSeq = -1L;
    private String lastRunText = "";
    volatile boolean jobRunning;                // package-visible: the tests wait for a job to end
    private Runnable afterAck;

    // the marking page
    private TextView tvMarkStep;
    private EditText etName;
    private Button btnMarkStart, btnMarkFinish;

    // ------------------------------------------------------------------ life

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new StageStore(new File(getFilesDir(), "stages"));
        seenMarkedSeq = StageLive.markedSeq;
        buildFrame();
        rebuildNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        builtKey = "";
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
        saveMarkName();
    }

    @Override
    @SuppressWarnings("deprecation")        // targets API 33: the system still calls this on every Android version the app runs on
    public void onBackPressed() {
        if (page == COMPARE) go(DETAIL, stageId, "");
        else if (page == DETAIL) go(HOME, "", "");
        else finish();
    }

    // ------------------------------------------------------------------ small helpers

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float sp, int color, boolean bold, boolean mono) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private Button button(String label, int bg, int fg, View.OnClickListener l) {
        final Button bt = new Button(this);
        bt.setText(label);
        bt.setTextSize(17);
        bt.setTextColor(fg);
        bt.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bt.setBackgroundColor(bg);
        bt.setAllCaps(true);
        bt.setOnClickListener(l);
        return bt;
    }

    private void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    private void add(LinearLayout parent, View v, int topDp, int heightDp) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightDp > 0 ? dp(heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private TextView card(String s, View.OnClickListener l) {
        final TextView t = text(s, 16, FG, false, false);
        t.setBackgroundColor(PANEL);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void section(String label) {
        add(body, text(label, 13, DIM, true, false), 24, 0);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Exporter.PREFS, MODE_PRIVATE);
    }

    private void setNotice(String s) {
        tvNotice.setText(s);
        tvNotice.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private boolean plainRecording() {
        return Recorder.LIVE.recording || Recorder.LIVE.saving;
    }

    private void send(String action, boolean foreground, String extraName, String extraValue) {
        try {
            final Intent i = new Intent(this, RecorderService.class).setAction(action);
            if (extraName != null) i.putExtra(extraName, extraValue);
            if (foreground) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot do that: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ the frame

    private void buildFrame() {
        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        btnBack = button("Back", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        btnBack.setTextSize(14);
        head.addView(btnBack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        tvTitle = text("STAGES", 20, FG, true, false);
        tvTitle.setPadding(dp(14), 0, 0, 0);
        head.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(root, head, 0, 0);

        // what the armed phone is doing, and how to stop it
        liveBox = new LinearLayout(this);
        liveBox.setOrientation(LinearLayout.VERTICAL);
        liveBox.setBackgroundColor(PANEL);
        liveBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvWord = text("", 30, FG, true, false);
        tvGuide = text("", 15, FG, false, false);
        tvReminder = text(StageText.ROAD_REMINDER, 13, DIM, false, false);
        tvError = text("", 14, RED, false, false);
        tvError.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { StageLive.error = ""; updateLive(); }
        });
        btnLive = button("Disarm", RED, 0xFFFFFFFF, new View.OnClickListener() {
            @Override public void onClick(View v) { onLiveButton(); }
        });
        liveBox.addView(tvWord);
        add(liveBox, tvGuide, 4, 0);
        add(liveBox, tvReminder, 8, 0);
        add(liveBox, tvError, 8, 0);
        add(liveBox, btnLive, 12, 56);
        add(root, liveBox, 14, 0);

        // the result of the last run, only when the car has stopped
        lastBox = new LinearLayout(this);
        lastBox.setOrientation(LinearLayout.VERTICAL);
        lastBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        lastBox.setBackgroundColor(PANEL);
        tvLastHead = text("LAST RUN", 13, DIM, true, false);
        tvLastRun = text("", 16, FG, false, false);
        lastBox.addView(tvLastHead);
        add(lastBox, tvLastRun, 4, 0);
        lastBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { StageLive.lastRun = null; updateLive(); }
        });
        add(root, lastBox, 10, 0);

        tvNotice = text("", 14, AMBER, false, false);
        tvNotice.setVisibility(View.GONE);
        add(root, tvNotice, 10, 0);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        add(root, body, 6, 0);

        setContentView(sv);
    }

    private void onLiveButton() {
        final int s = StageLive.state;
        if (s == StageLive.MARK_START || s == StageLive.MARK_DRIVE) send(RecorderService.ACTION_MARK_CANCEL, false, null, null);
        else if (s != StageLive.IDLE) send(RecorderService.ACTION_DISARM, false, null, null);
    }

    /** The status box and the last-run box, in place. */
    private void updateLive() {
        final int state = StageLive.state;
        final boolean busy = state != StageLive.IDLE;
        final boolean numbers = StageLive.numbersAllowed();
        String err = StageLive.error;
        if (Recorder.LIVE.failed && Recorder.LIVE.error.length() > 0 && !err.contains(Recorder.LIVE.error)) {
            err = err.length() > 0 ? err + "\n" + Recorder.LIVE.error : Recorder.LIVE.error;
        }
        liveBox.setVisibility(busy || err.length() > 0 ? View.VISIBLE : View.GONE);
        tvWord.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvGuide.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvReminder.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            tvWord.setText(StageText.stateWord(state));
            tvWord.setTextColor(state == StageLive.ARMED ? GREEN : state == StageLive.SAVING || state == StageLive.MARK_SAVING ? AMBER : RED);
            tvGuide.setText(StageText.guide(state, StageLive.stageName, StageLive.markHint, StageLive.message));
        }
        tvError.setText(err.length() > 0 ? err + "\n(tap to dismiss)" : "");
        tvError.setVisibility(err.length() > 0 ? View.VISIBLE : View.GONE);
        final boolean stoppable = busy && state != StageLive.SAVING && state != StageLive.MARK_SAVING;
        btnLive.setVisibility(stoppable ? View.VISIBLE : View.GONE);
        if (stoppable) {
            btnLive.setText(state == StageLive.ARMED ? "Disarm" : state == StageLive.MARK_START || state == StageLive.MARK_DRIVE ? "Cancel marking" : "Stop and disarm");
        }

        final RunResult last = StageLive.lastRun;
        final boolean showLast = last != null && numbers;
        lastBox.setVisibility(showLast ? View.VISIBLE : View.GONE);
        if (showLast) {
            if (shownLastRunSeq != StageLive.lastRunSeq) {
                final List<RunResult> others = new ArrayList<RunResult>();
                for (RunResult r : store.runs(last.stageId)) if (!r.runId.equals(last.runId) && r.finished) others.add(r);
                final Stage st = store.load(last.stageId);
                lastRunText = (st == null ? "" : st.name() + ", ") + StageText.when(last.startUtcMs) + "\n" + StageText.lastRunLine(last, others);
                shownLastRunSeq = StageLive.lastRunSeq;
            }
            tvLastRun.setText(lastRunText);
        } else {
            shownLastRunSeq = -1L;
        }
    }

    // ------------------------------------------------------------------ refresh and navigation

    private String key() {
        if (page == MARK) return "mark";
        return page + "|" + stageId + "|" + runId + "|" + against + "|" + StageLive.busy() + "|" + plainRecording() + "|"
                + StageLive.numbersAllowed() + "|" + dataVersion + "|" + StageLive.lastRunSeq + "|" + jobRunning;
    }

    /** Every 300 ms while the screen is showing (package-visible: the tests step it by hand). */
    void refresh() {
        try {
            final int state = StageLive.state;
            final boolean marking = state == StageLive.MARK_START || state == StageLive.MARK_DRIVE || state == StageLive.MARK_SAVING;
            if (marking && page != MARK) {
                go(MARK, "", "");
            } else if (!marking && page == MARK) {
                if (StageLive.markedSeq != seenMarkedSeq) {
                    seenMarkedSeq = StageLive.markedSeq;
                    final String id = StageLive.markedStageId;
                    if (id.length() > 0 && store.load(id) != null) {
                        dataVersion++;
                        go(DETAIL, id, "");
                        setNotice("Stage saved. Arm it when you are ready to drive it.");
                    } else {
                        go(HOME, "", "");
                    }
                } else if (state == StageLive.IDLE) {
                    go(HOME, "", "");
                }
            }
            if (!key().equals(builtKey)) rebuildNow();
            else if (page == MARK) updateMark();
            updateLive();
        } catch (RuntimeException e) {
            // this screen shares a process with the recording: a mistake here must not stop it
            setNotice("Screen problem (" + e + "). The recording is not affected.");
        }
    }

    private void go(int p, String sid, String rid) {
        saveMarkName();
        if (p != page || !sid.equals(stageId) || !rid.equals(runId)) against = BEST;
        page = p;
        stageId = sid;
        runId = rid;
        setNotice("");
        rebuildNow();
    }

    private void rebuildNow() {
        builtKey = key();
        saveMarkName();
        body.removeAllViews();
        etName = null;
        tvMarkStep = null;
        btnMarkStart = null;
        btnMarkFinish = null;
        switch (page) {
            case DETAIL: buildDetail(); break;
            case COMPARE: buildCompare(); break;
            case MARK: buildMark(); break;
            default: buildHome(); break;
        }
        btnBack.setText(page == HOME || page == MARK ? "Track Logger" : "Back");
        tvTitle.setText(page == MARK ? "NEW STAGE" : page == COMPARE ? "COMPARE" : page == DETAIL ? detailTitle() : "STAGES");
        updateLive();
    }

    private String detailTitle() {
        final Stage s = store.load(stageId);
        return s == null ? "STAGE" : s.name();
    }

    // ------------------------------------------------------------------ HOME

    private void buildHome() {
        final boolean busy = StageLive.busy(), plain = plainRecording();
        final boolean numbers = StageLive.numbersAllowed();

        final Button mark = button("Mark a new stage", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { onMarkNew(); }
        });
        add(body, mark, 0, 64);
        enable(mark, !busy && !plain && !jobRunning);
        if (plain && !busy) add(body, text("A recording is running or being saved. Stop it before marking or arming a stage.", 13, AMBER, false, false), 6, 0);

        final List<Stage> stages = store.list();
        if (stages.isEmpty()) {
            add(body, text("No stages yet.\n\nA stage is a stretch of road with a start and a finish. To make one, drive it once: park at the "
                    + "start, press MARK START, drive to the finish, park, press MARK FINISH.\n\nAfter that, ARM the stage and the phone "
                    + "records by itself from the start line to the finish line, every time you drive it.", 15, FG, false, false), 18, 0);
        } else {
            section("YOUR STAGES");
            for (final Stage s : stages) {
                final List<RunResult> runs = store.runs(s.id);
                add(body, card(StageText.stageRow(s, runs, numbers), new View.OnClickListener() {
                    @Override public void onClick(View v) { go(DETAIL, s.id, ""); }
                }), 8, 0);
            }
            if (!numbers) add(body, text(StageText.HIDDEN_NOTE, 13, DIM, false, false), 8, 0);
        }

        section("KEEP YOUR STAGES SAFE");
        final Button backup = button("Back up stages", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onBackUp(); }
        });
        add(body, backup, 8, 52);
        enable(backup, !jobRunning && !stages.isEmpty());
        final Button restore = button("Restore from a file", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onRestore(); }
        });
        add(body, restore, 8, 52);
        enable(restore, !busy && !jobRunning);
        final String lastBackup = prefs().getString(StageExporter.KEY_URI, "");
        if (lastBackup.length() > 0) {
            add(body, button("Share the last backup", PANEL, FG, new View.OnClickListener() {
                @Override public void onClick(View v) { onShareBackup(); }
            }), 8, 52);
        }
        add(body, text("The app's own files are removed if you uninstall it. A backup in Downloads/TrackLogger is the only copy that "
                + "survives that, and it can be sent to another phone.", 12, DIM, false, false), 6, 0);

        section("SETTINGS");
        final boolean beep = prefs().getBoolean(RecorderService.PREF_BEEP, true);
        add(body, button(beep ? "Beeps: on" : "Beeps: off", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onToggleBeep(); }
        }), 8, 52);
        add(body, text("One beep at the start line, two at the finish line, a low tone if the run is given up. It is the only thing the "
                + "phone does while you drive. The beeps use the media volume.", 12, DIM, false, false), 6, 0);
        add(body, button("Battery: allow background", PANEL, DIM, new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        }), 8, 52);

        section("HOW IT WORKS");
        add(body, text("1  Mount the phone. Open a stage and press ARM.\n"
                + "2  Drive to the start. Recording starts by itself as you come to the start line.\n"
                + "3  The run ends after the finish line. The phone arms itself again for the next run.\n"
                + "4  Park somewhere safe and look at the result. Compare it with your other runs of the same road.\n\n"
                + "Times come from the phone's GPS. Under a clear sky they are good to a few tenths of a second; under trees or between "
                + "cuttings less. The comparison says when two runs are too close to tell apart.", 12, DIM, false, false), 6, 0);
    }

    // ------------------------------------------------------------------ DETAIL

    private void buildDetail() {
        final Stage st = store.load(stageId);
        if (st == null) {
            page = HOME;
            stageId = "";
            setNotice("That stage is not on the phone any more.");
            builtKey = key();
            buildHome();
            return;
        }
        final boolean busy = StageLive.busy(), plain = plainRecording();
        final boolean numbers = StageLive.numbersAllowed();
        final List<RunResult> runs = store.runs(st.id);
        final RunResult best = Compare.best(runs);

        add(body, text(StageText.dist(st.lengthM()) + "   marked " + StageText.when(st.createdUtcMs), 14, DIM, false, false), 0, 0);

        final Button arm = button("Arm this stage", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { onArm(); }
        });
        add(body, arm, 14, 64);
        enable(arm, !busy && !plain && !jobRunning);
        add(body, text("Press ARM, put the phone in its mount and drive to the start. It records by itself from the start line to the "
                + "finish line, then arms itself again. Leave it alone while you drive.", 13, DIM, false, false), 6, 0);

        section("RUNS");
        if (runs.isEmpty()) {
            add(body, text("No runs yet. They appear here after you drive the stage with the phone armed, or when you score recordings "
                    + "you already have (below).", 14, FG, false, false), 8, 0);
        } else {
            add(body, text("Tap a run to compare it. Press and hold to delete it.", 12, DIM, false, false), 4, 0);
            final List<RunResult> newestFirst = new ArrayList<RunResult>(runs);
            Collections.reverse(newestFirst);
            for (final RunResult r : newestFirst) {
                final TextView row = card(StageText.runLine(r, best, numbers), new View.OnClickListener() {
                    @Override public void onClick(View v) { go(COMPARE, st.id, r.runId); }
                });
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { confirmDeleteRun(st, r); return true; }
                });
                add(body, row, 8, 0);
            }
            if (!numbers) add(body, text(StageText.HIDDEN_NOTE, 13, DIM, false, false), 8, 0);
        }

        section("RECORDINGS YOU ALREADY HAVE");
        add(body, text("A drive recorded before the stage existed, or with the phone not armed, can be scored against it.", 12, DIM, false, false), 4, 0);
        final Button phone = button("Score the recordings on this phone", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onScorePhone(); }
        });
        add(body, phone, 8, 52);
        enable(phone, !busy && !plain && !jobRunning);
        final Button file = button("Score a recording file", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onScoreFile(); }
        });
        add(body, file, 8, 52);
        enable(file, !busy && !plain && !jobRunning);

        section("THIS STAGE");
        add(body, button("Show the start on a map", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { showOnMap(st.startMarkLat, st.startMarkLon, st.name() + " start"); }
        }), 8, 52);
        add(body, button("Show the finish on a map", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { showOnMap(st.finishMarkLat, st.finishMarkLon, st.name() + " finish"); }
        }), 8, 52);
        final Button rename = button("Rename", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onRename(st); }
        });
        add(body, rename, 8, 52);
        enable(rename, !busy);
        final Button delete = button("Delete this stage", PANEL, RED, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDeleteStage(st, runs.size()); }
        });
        add(body, delete, 8, 52);
        enable(delete, !busy);
    }

    // ------------------------------------------------------------------ COMPARE

    private RunResult findRun(List<RunResult> runs, String id) {
        for (RunResult r : runs) if (r.runId.equals(id)) return r;
        return null;
    }

    private void buildCompare() {
        final Stage st = store.load(stageId);
        final List<RunResult> runs = st == null ? new ArrayList<RunResult>() : store.runs(stageId);
        final RunResult a = findRun(runs, runId);
        if (st == null || a == null) {
            go(st == null ? HOME : DETAIL, st == null ? "" : stageId, "");
            return;
        }
        if (!StageLive.numbersAllowed()) {
            add(body, text(st.name(), 20, FG, true, false), 0, 0);
            add(body, text(StageText.HIDDEN_NOTE, 15, FG, false, false), 12, 0);
            return;
        }

        add(body, text(st.name() + "   " + StageText.when(a.startUtcMs), 15, DIM, false, false), 0, 0);

        // what to compare with
        final LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        final String[] names = {"Best", "Previous", "First"};
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            final Button bt = button(names[m], m == against ? GREEN : PANEL, m == against ? DARK_ON_GREEN : FG, new View.OnClickListener() {
                @Override public void onClick(View v) { against = mode; rebuildNow(); }
            });
            bt.setTextSize(14);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            lp.leftMargin = m == 0 ? 0 : dp(8);
            modes.addView(bt, lp);
        }
        add(body, modes, 12, 0);

        final List<RunResult> others = new ArrayList<RunResult>();
        for (RunResult r : runs) if (r.finished && !r.runId.equals(a.runId)) others.add(r);
        RunResult b;
        String bName;
        if (against == PREVIOUS) {
            b = Compare.previous(runs, a);
            bName = "previous run";
        } else if (against == FIRST) {
            b = others.isEmpty() ? null : others.get(0);
            bName = "first run";
        } else {
            b = Compare.best(others);
            bName = a.finished && b != null && Compare.best(runs) == a ? "next best run" : "best run";
        }

        if (!a.finished) {
            add(body, text("This run did not finish" + (a.statusText.length() > 0 ? " (" + a.statusText + ")" : "")
                    + ", so there is nothing to compare.", 15, FG, false, false), 14, 0);
        } else if (b == null) {
            add(body, text(others.isEmpty() ? "This is the only finished run of the stage so far. Drive it again, or score another recording, "
                    + "and there will be something to compare it with." : "There is no " + bName + " to compare it with.", 15, FG, false, false), 14, 0);
        } else if (!a.comparableWith(b)) {
            add(body, text("These two runs do not line up (they were not timed against the same road), so they cannot be compared.", 15, FG, false, false), 14, 0);
        } else {
            final Compare c = Compare.of(a, b);
            final Plot plot = new Plot(this);
            plot.set(c.delta, c.a.stepM, c.a.lengthM, c.sameBandS());
            add(body, plot, 14, 230);
            add(body, text(StageText.compareText(st, c, bName), 13, FG, false, true), 12, 0);
        }

        final Button del = button("Delete this run", PANEL, RED, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDeleteRun(st, a); }
        });
        add(body, del, 22, 52);
        enable(del, !StageLive.busy());
    }

    /** The comparison as a picture: how far ahead of the other run this one was at every point of the road. Up is ahead, down is behind. */
    static final class Plot extends View {
        private double[] ahead = new double[0];
        private double stepM, lengthM, bandS;
        private final Paint line = new Paint(), grid = new Paint(), band = new Paint(), label = new Paint();
        private final Path path = new Path();

        Plot(android.content.Context c) {
            super(c);
            line.setAntiAlias(true);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4f);
            line.setColor(GREEN);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(1f);
            grid.setColor(0xFF3A424D);
            band.setStyle(Paint.Style.FILL);
            band.setColor(0x33FFFFFF);
            label.setAntiAlias(true);
            label.setColor(DIM);
            label.setTextSize(24f);
        }

        void set(double[] delta, double stepM, double lengthM, double bandS) {
            this.ahead = new double[delta.length];
            for (int i = 0; i < delta.length; i++) this.ahead[i] = -delta[i];
            this.stepM = stepM;
            this.lengthM = lengthM;
            this.bandS = bandS;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || ahead.length < 2 || lengthM <= 0.0) return;
            final float left = 70f, right = w - 16f, top = 16f, bottom = h - 40f;
            double m = 1.5 * bandS;
            for (double v : ahead) m = Math.max(m, Math.abs(v));
            final double ymax = StageText.niceCeil(m);
            final float mid = (top + bottom) / 2f;
            final float half = (bottom - top) / 2f;

            cv.drawRect(left, (float) (mid - bandS / ymax * half), right, (float) (mid + bandS / ymax * half), band);
            cv.drawLine(left, mid, right, mid, grid);
            cv.drawLine(left, top, right, top, grid);
            cv.drawLine(left, bottom, right, bottom, grid);
            cv.drawText("+" + trim(ymax) + " s", 4f, top + 20f, label);
            cv.drawText("0", 4f, mid + 8f, label);
            cv.drawText("-" + trim(ymax) + " s", 4f, bottom, label);
            cv.drawText("ahead", left + 6f, top + 22f, label);
            cv.drawText("behind", left + 6f, bottom - 6f, label);
            final double tick = StageText.tickStepM(lengthM);
            for (double s = 0.0; s <= lengthM + 1e-6; s += tick) {
                final float x = (float) (left + s / lengthM * (right - left));
                cv.drawLine(x, bottom, x, bottom + 8f, grid);
                cv.drawText(s >= 1000.0 || tick >= 1000.0 ? trim(s / 1000.0) + " km" : String.format(Locale.US, "%.0f m", s), x - 18f, bottom + 32f, label);
            }

            path.reset();
            for (int k = 0; k < ahead.length; k++) {
                final double s = Math.min(k * stepM, lengthM);
                final float x = (float) (left + s / lengthM * (right - left));
                final float y = (float) (mid - ahead[k] / ymax * half);
                if (k == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            cv.drawPath(path, line);
        }

        private static String trim(double v) {
            return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
        }
    }

    // ------------------------------------------------------------------ MARK

    private String defaultName() {
        return "Stage " + new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date());
    }

    private String markName() {
        final String s = prefs().getString(PREF_MARK_NAME, "");
        return s.length() > 0 ? s : defaultName();
    }

    private void saveMarkName() {
        if (etName == null) return;
        final String s = etName.getText().toString().trim();
        prefs().edit().putString(PREF_MARK_NAME, s).apply();
    }

    private void buildMark() {
        tvMarkStep = text("", 16, FG, false, false);
        add(body, tvMarkStep, 6, 0);

        add(body, text("Name (you can change it later)", 13, DIM, true, false), 18, 0);
        etName = new EditText(this);
        etName.setSingleLine(true);
        etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etName.setTextColor(FG);
        etName.setHintTextColor(DIM);
        etName.setHint("For example Fraser to Bentong");
        etName.setBackgroundColor(PANEL);
        etName.setPadding(dp(14), dp(12), dp(14), dp(12));
        etName.setText(markName());
        add(body, etName, 6, 0);

        btnMarkStart = button("Mark start", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { onMarkStart(); }
        });
        add(body, btnMarkStart, 16, 68);
        btnMarkFinish = button("Mark finish", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { onMarkFinish(); }
        });
        add(body, btnMarkFinish, 16, 68);
        add(body, text("Mark only when you are parked somewhere safe. The phone takes the mark from a few seconds of still GPS, so it "
                + "is better to stop a metre or two before the line you want than to roll over it.", 12, DIM, false, false), 14, 0);
        updateMark();
    }

    private void updateMark() {
        if (tvMarkStep == null) return;
        final int s = StageLive.state;
        tvMarkStep.setText(s == StageLive.MARK_START
                ? "Step 1 of 2. Park at the start of the stage, off the road. Keep the car still until MARK START turns green, then press it."
                : s == StageLive.MARK_DRIVE
                ? "Step 2 of 2. Drive the stage as you normally would. Nothing is shown while you drive. Park at the finish, off the road, and press MARK FINISH."
                : "Saving the stage.");
        etName.setVisibility(s == StageLive.MARK_START ? View.VISIBLE : View.GONE);
        btnMarkStart.setVisibility(s == StageLive.MARK_START ? View.VISIBLE : View.GONE);
        btnMarkFinish.setVisibility(s == StageLive.MARK_DRIVE ? View.VISIBLE : View.GONE);
        enable(btnMarkStart, StageLive.canMark);
        enable(btnMarkFinish, StageLive.canMark);
    }

    private void onMarkStart() {
        saveMarkName();
        send(RecorderService.ACTION_MARK_START, false, null, null);
    }

    private void onMarkFinish() {
        send(RecorderService.ACTION_MARK_FINISH, false, RecorderService.EXTRA_NAME, markName());
    }

    // ------------------------------------------------------------------ starting things

    private boolean canStartSomething() {
        if (StageLive.busy() || plainRecording() || jobRunning) return false;
        return true;
    }

    private void onMarkNew() {
        if (!canStartSomething()) return;
        afterFirstUse(new Runnable() {
            @Override public void run() {
                if (!ensurePermissions("MARK A NEW STAGE")) return;
                send(RecorderService.ACTION_MARK_NEW, true, null, null);
            }
        });
    }

    private void onArm() {
        if (!canStartSomething() || stageId.length() == 0) return;
        final String id = stageId;
        afterFirstUse(new Runnable() {
            @Override public void run() {
                if (!ensurePermissions("ARM")) return;
                send(RecorderService.ACTION_ARM, true, RecorderService.EXTRA_STAGE_ID, id);
            }
        });
    }

    /** The first time, the text about timing on the road comes first, and what the person asked for happens after they accept it. */
    private void afterFirstUse(final Runnable then) {
        if (prefs().getBoolean(PREF_ACK, false)) {
            then.run();
            return;
        }
        afterAck = then;
        new AlertDialog.Builder(this)
                .setTitle(StageText.FIRST_USE_TITLE)
                .setMessage(StageText.FIRST_USE_BODY)
                .setPositiveButton("I understand", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs().edit().putBoolean(PREF_ACK, true).apply();
                        final Runnable r = afterAck;
                        afterAck = null;
                        if (r != null) r.run();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { afterAck = null; }
                })
                .show();
    }

    // ------------------------------------------------------------------ permissions (as MainActivity does it)

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean wantsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(NOTIF_PERMISSION) != PackageManager.PERMISSION_GRANTED
                && !prefs().getBoolean(PREF_ASKED_NOTIF, false);
    }

    /** True if the app may go ahead; otherwise the permission dialog is shown and the person presses the button again. */
    private boolean ensurePermissions(String button) {
        if (hasLocation() && !wantsNotificationPermission()) return true;
        final ArrayList<String> p = new ArrayList<String>();
        if (!hasLocation()) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
            p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (wantsNotificationPermission()) {
            p.add(NOTIF_PERMISSION);
            prefs().edit().putBoolean(PREF_ASKED_NOTIF, true).apply();       // ask once only
        }
        permissionButton = button;
        requestPermissions(p.toArray(new String[0]), REQ_PERM);
        return false;
    }

    private String permissionButton = "";

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        if (hasLocation()) {
            Toast.makeText(this, "Allowed. Press " + permissionButton + " again.", Toast.LENGTH_LONG).show();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Location is blocked. In Permissions > Location choose Allow and turn on \"Use precise location\".", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "Open Settings > Apps > Track Logger > Permissions", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Stages need Precise location. Press " + permissionButton + " and choose Precise, then Allow.", Toast.LENGTH_LONG).show();
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

    // ------------------------------------------------------------------ things to do with a stage

    private void onToggleBeep() {
        final boolean now = prefs().getBoolean(RecorderService.PREF_BEEP, true);
        prefs().edit().putBoolean(RecorderService.PREF_BEEP, !now).apply();
        dataVersion++;
    }

    private void showOnMap(double lat, double lon, String label) {
        try {
            final String q = String.format(Locale.US, "%.6f,%.6f", lat, lon);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + q + "?q=" + q + "(" + Uri.encode(label) + ")")));
        } catch (Exception e) {
            Toast.makeText(this, "No map app found.", Toast.LENGTH_LONG).show();
        }
    }

    private void onRename(final Stage st) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(st.name());
        new AlertDialog.Builder(this)
                .setTitle("Rename the stage")
                .setView(et)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            final Stage s = store.load(st.id);
                            if (s != null && !StageLive.busy()) {
                                s.rename(et.getText().toString());
                                store.save(s);
                            }
                        } catch (java.io.IOException e) {
                            Toast.makeText(StagesActivity.this, "Could not save the new name.", Toast.LENGTH_LONG).show();
                        }
                        dataVersion++;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteStage(final Stage st, int runCount) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + st.name() + "?")
                .setMessage("The stage and its " + runCount + (runCount == 1 ? " run are" : " runs are")
                        + " removed from the phone. The recordings stay where they are, and a backup you made still has them.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (!StageLive.busy()) store.delete(st.id);
                        dataVersion++;
                        go(HOME, "", "");
                        setNotice("Deleted " + st.name() + ".");
                    }
                })
                .setNegativeButton("Keep it", null)
                .show();
    }

    private void confirmDeleteRun(final Stage st, final RunResult r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this run?")
                .setMessage(StageText.when(r.startUtcMs) + (r.finished ? "" : ", did not finish")
                        + ". The recording it came from is not deleted, and scoring it again brings the run back.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (!StageLive.busy()) store.deleteRun(st.id, r.runId);
                        dataVersion++;
                        go(DETAIL, st.id, "");
                    }
                })
                .setNegativeButton("Keep it", null)
                .show();
    }

    // ------------------------------------------------------------------ jobs on a worker thread

    private void runJob(String startText, final Callable<String> work) {
        if (jobRunning) return;
        jobRunning = true;
        setNotice(startText);
        rebuildNow();
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    result = work.call();
                } catch (Throwable t) {
                    result = "That did not work (" + t + ").";
                }
                final String r = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        jobRunning = false;
                        dataVersion++;
                        setNotice(r);
                    }
                });
            }
        }, "stages-job").start();
    }

    private void progress(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (jobRunning) setNotice(s);
            }
        });
    }

    /** The session files the recorder keeps in the app's own folders. */
    private List<File> sessionFiles() {
        final List<File> out = new ArrayList<File>();
        final java.util.HashSet<String> seen = new java.util.HashSet<String>();
        final File[] dirs = {getExternalFilesDir(null), getFilesDir()};
        for (File d : dirs) {
            if (d == null) continue;
            final File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().startsWith("session_") || !f.getName().endsWith(".csv")) continue;
                String key = f.getAbsolutePath();
                try {
                    key = f.getCanonicalPath();
                } catch (java.io.IOException e) {
                    // the absolute path will do
                }
                if (seen.add(key)) out.add(f);          // never the same recording twice
            }
        }
        Collections.sort(out);
        return out;
    }

    private void onScorePhone() {
        if (!canStartSomething()) return;
        final Stage st = store.load(stageId);
        if (st == null) return;
        final List<File> files = sessionFiles();
        if (files.isEmpty()) {
            setNotice("There are no recordings stored in the app yet.");
            return;
        }
        runJob("Scoring " + files.size() + (files.size() == 1 ? " recording" : " recordings") + "...", new Callable<String>() {
            @Override public String call() {
                return StageRescore.scoreFiles(store, st, files, new StageRescore.Progress() {
                    @Override public void step(int done, int total, String name) {
                        if (done < total) progress("Scoring recording " + (done + 1) + " of " + total + "...");
                    }
                }).text();
            }
        });
    }

    private void onScoreFile() {
        if (!canStartSomething()) return;
        pickFile(REQ_SCORE_FILE);
    }

    private void onRestore() {
        if (!canStartSomething()) return;
        pickFile(REQ_RESTORE);
    }

    private void pickFile(int code) {
        try {
            final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            if (code == REQ_RESTORE) {
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
            }
            startActivityForResult(i, code);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open the file picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final android.content.Context app = getApplicationContext();
        if (code == REQ_RESTORE) {
            runJob("Reading the file...", new Callable<String>() {
                @Override public String call() {
                    return StageExporter.restore(app, uri, store, System.currentTimeMillis());
                }
            });
        } else if (code == REQ_SCORE_FILE) {
            final Stage st = store.load(stageId);
            if (st == null) return;
            runJob("Scoring the file...", new Callable<String>() {
                @Override public String call() throws Exception {
                    final java.io.InputStream in = app.getContentResolver().openInputStream(uri);
                    if (in == null) return "That file could not be opened.";
                    return StageRescore.scoreStream(store, st, in, StageExporter.displayName(app, uri)).text();
                }
            });
        }
    }

    private void onBackUp() {
        if (jobRunning) return;
        final android.content.Context app = getApplicationContext();
        runJob("Saving the backup...", new Callable<String>() {
            @Override public String call() {
                return StageExporter.backUp(app, store, System.currentTimeMillis()).message;
            }
        });
    }

    private void onShareBackup() {
        final String u = prefs().getString(StageExporter.KEY_URI, "");
        if (u.length() == 0) {
            Toast.makeText(this, "No backup yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            final Intent s = new Intent(Intent.ACTION_SEND);
            s.setType(StageExporter.MIME);
            s.putExtra(Intent.EXTRA_STREAM, Uri.parse(u));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Share the stages backup"));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot share: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
