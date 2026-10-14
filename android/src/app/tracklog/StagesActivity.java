package app.tracklog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.SeekBar;
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
 * the runs afterwards. The screen is a list of pages (HOME, DETAIL of one stage or one planned stage, COMPARE two runs, MARK a new stage)
 * shown one at a time inside one frame that stays put: the status of the armed phone, the road reminder, the result of the last
 * background job.
 *
 * Release 1.5 adds two ways to make a stage that need no parking on the road, both ending in the same kind of stage: NEW offers the ways,
 * PICK and PIN take a drive that is already recorded and let the person put the start pin and the finish pin on its line, and PLAN takes
 * the start and the finish as coordinates (typed or pasted from a map) and keeps them as a planned stage until the person makes the
 * stage from a recording of the drive (PICK and PIN open with the pins at the plan's two places). Arming a plan, so that its first drive
 * is recorded by itself, is release 1.6 and is not here. The pages with text boxes (MARK, PLAN, PIN) are not rebuilt every refresh, so
 * what is typed is not lost.
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

    private static final int HOME = 0, DETAIL = 1, COMPARE = 2, MARK = 3, NEW = 4, PLAN = 5, PICK = 6, PIN = 7;
    private static final int BEST = 0, PREVIOUS = 1, FIRST = 2;
    private static final int REQ_PERM = 21, REQ_RESTORE = 22, REQ_SCORE_FILE = 23, REQ_PIN_FILE = 24;
    private static final int PICK_LIST_MAX = 30;                       // the newest recordings listed on the page that picks a drive

    private static final String PREF_ACK = "stage_ack";
    private static final String PREF_MARK_NAME = "stage_mark_name";
    private static final String PREF_PLAN_NAME = "stage_plan_name", PREF_PLAN_START = "stage_plan_start", PREF_PLAN_FINISH = "stage_plan_finish";
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

    // the planning page (what is in the boxes is kept here when the page is rebuilt, and in the preferences when the screen goes away)
    private String planName = "", planStart = "", planFinish = "";
    private EditText etPlanName, etPlanStart, etPlanFinish;
    private TextView tvPlanStart, tvPlanFinish, tvPlanApart;
    private Button btnPlanSave, btnPlanStartMap, btnPlanFinishMap;

    // the pin page: the drive that was picked, where the two pins are, and the views that show them
    private Track pinTrack;
    private String pinSource = "";
    private boolean pinTruncated;
    private File pinFile;                           // the drive came from a file of the app's own ...
    private Uri pinUri;                             // ... or from a file the person picked
    private final double[] pinS = {-1.0, -1.0};     // the pins, as distances along the drive
    private int pinActive;                          // 0: the start pin is the one being moved, 1: the finish pin
    private boolean pinDirty, pinOk;
    private String pinNameText = "";
    private EditText etPinName;
    private TrackView overview, closeup;
    private SeekBar seek;
    private Button btnPinStart, btnPinFinish, btnPinSave;
    private TextView tvPinPos, tvPinCheck;
    // handed from the worker thread that reads a drive to the screen
    private volatile Track loadedTrack;
    private volatile boolean loadedTruncated;
    private volatile String loadedName = "";

    // ------------------------------------------------------------------ life

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new StageStore(new File(getFilesDir(), "stages"));
        seenMarkedSeq = StageLive.markedSeq;
        planName = prefs().getString(PREF_PLAN_NAME, "");
        planStart = prefs().getString(PREF_PLAN_START, "");
        planFinish = prefs().getString(PREF_PLAN_FINISH, "");
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
        saveForms();
        if (page == PLAN && stageId.length() == 0) {
            prefs().edit().putString(PREF_PLAN_NAME, planName).putString(PREF_PLAN_START, planStart).putString(PREF_PLAN_FINISH, planFinish).apply();
        }
    }

    @Override
    @SuppressWarnings("deprecation")        // targets API 33: the system still calls this on every Android version the app runs on
    public void onBackPressed() {
        if (page == COMPARE) go(DETAIL, stageId, "");
        else if (page == DETAIL || page == NEW) go(HOME, "", "");
        else if (page == PLAN || page == PICK) go(stageId.length() > 0 ? DETAIL : NEW, stageId, "");
        else if (page == PIN) go(PICK, stageId, "");
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
        if (page == MARK || page == PLAN || page == PIN) return "fixed" + page + "|" + stageId;      // pages with text boxes: rebuilt only when the person moves
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
            else if (page == PLAN) updatePlan();
            else if (page == PIN) updatePin();
            updateLive();
        } catch (RuntimeException e) {
            // this screen shares a process with the recording: a mistake here must not stop it
            setNotice("Screen problem (" + e + "). The recording is not affected.");
        }
    }

    private void go(int p, String sid, String rid) {
        saveForms();
        if (p != PIN) clearPin();
        if (p != page || !sid.equals(stageId) || !rid.equals(runId)) against = BEST;
        page = p;
        stageId = sid;
        runId = rid;
        setNotice("");
        rebuildNow();
    }

    private void rebuildNow() {
        builtKey = key();
        saveForms();
        body.removeAllViews();
        etName = null;
        tvMarkStep = null;
        btnMarkStart = null;
        btnMarkFinish = null;
        etPlanName = null;
        etPlanStart = null;
        etPlanFinish = null;
        tvPlanStart = null;
        tvPlanFinish = null;
        tvPlanApart = null;
        btnPlanSave = null;
        btnPlanStartMap = null;
        btnPlanFinishMap = null;
        etPinName = null;
        overview = null;
        closeup = null;
        seek = null;
        btnPinStart = null;
        btnPinFinish = null;
        btnPinSave = null;
        tvPinPos = null;
        tvPinCheck = null;
        switch (page) {
            case DETAIL: buildDetail(); break;
            case COMPARE: buildCompare(); break;
            case MARK: buildMark(); break;
            case NEW: buildNew(); break;
            case PLAN: buildPlan(); break;
            case PICK: buildPick(); break;
            case PIN: buildPin(); break;
            default: buildHome(); break;
        }
        btnBack.setText(page == HOME || page == MARK ? "Track Logger" : "Back");
        tvTitle.setText(titleOf());
        updateLive();
    }

    private String titleOf() {
        switch (page) {
            case MARK: return "NEW STAGE";
            case COMPARE: return "COMPARE";
            case DETAIL: return detailTitle();
            case NEW: return "ADD A STAGE";
            case PLAN: return stageId.length() > 0 ? "CHANGE THE PLACES" : "PLAN A STAGE";
            case PICK: return "PICK A DRIVE";
            case PIN: return "PLACE THE PINS";
            default: return "STAGES";
        }
    }

    private String detailTitle() {
        final Stage s = store.load(stageId);
        if (s != null) return s.name();
        final Plan p = store.loadPlan(stageId);
        return p == null ? "STAGE" : p.name();
    }

    /** What is typed in the text boxes of whichever page has them, kept so that rebuilding the page does not lose it. */
    private void saveForms() {
        saveMarkName();
        if (etPlanName != null) planName = etPlanName.getText().toString();
        if (etPlanStart != null) planStart = etPlanStart.getText().toString();
        if (etPlanFinish != null) planFinish = etPlanFinish.getText().toString();
        if (etPinName != null) pinNameText = etPinName.getText().toString();
    }

    // ------------------------------------------------------------------ HOME

    private void buildHome() {
        final boolean busy = StageLive.busy(), plain = plainRecording();
        final boolean numbers = StageLive.numbersAllowed();

        final Button addStage = button("Add a stage", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { if (canStartSomething()) go(NEW, "", ""); }
        });
        add(body, addStage, 0, 64);
        enable(addStage, !busy && !plain && !jobRunning);
        if (plain && !busy) add(body, text("A recording is running or being saved. Stop it before adding or arming a stage.", 13, AMBER, false, false), 6, 0);

        final List<Stage> stages = store.list();
        final List<Plan> plans = store.listPlans();
        if (stages.isEmpty() && plans.isEmpty()) {
            add(body, text("No stages yet.\n\nA stage is a stretch of road with a start and a finish. Add one without stopping on the road: "
                    + "drive it as usual and put a pin at each end of the drive afterwards, or copy the start and the finish from Google Maps.\n\n"
                    + "After that, ARM the stage and the phone records by itself from the start line to the finish line, every time you drive it.",
                    15, FG, false, false), 18, 0);
        }
        if (!plans.isEmpty()) {
            section("PLANNED STAGES");
            for (final Plan pl : plans) {
                add(body, card(StageText.planRow(pl), new View.OnClickListener() {
                    @Override public void onClick(View v) { go(DETAIL, pl.id, ""); }
                }), 8, 0);
            }
        }
        if (!stages.isEmpty()) {
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
            final Plan planned = store.loadPlan(stageId);
            if (planned != null) {
                buildPlanDetail(planned);
                return;
            }
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

    // ------------------------------------------------------------------ NEW: the ways to add a stage

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private void buildNew() {
        final boolean can = canStartSomething();
        add(body, text(StageText.ADD_INTRO, 15, FG, false, false), 0, 0);

        final Button pin = button(StageText.WAY_PIN_TITLE, GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { if (canStartSomething()) go(PICK, "", ""); }
        });
        add(body, pin, 18, 64);
        enable(pin, can);
        add(body, text(StageText.WAY_PIN_BODY, 13, DIM, false, false), 6, 0);

        final Button plan = button(StageText.WAY_PLAN_TITLE, GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { if (canStartSomething()) enterPlan(""); }
        });
        add(body, plan, 18, 64);
        enable(plan, can);
        add(body, text(StageText.WAY_PLAN_BODY, 13, DIM, false, false), 6, 0);

        final Button park = button(StageText.WAY_PARK_TITLE, PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onMarkNew(); }
        });
        add(body, park, 18, 64);
        enable(park, can);
        add(body, text(StageText.WAY_PARK_BODY, 13, DIM, false, false), 6, 0);
    }

    // ------------------------------------------------------------------ a planned stage

    private void buildPlanDetail(final Plan p) {
        final boolean busy = StageLive.busy(), plain = plainRecording();
        add(body, text(StageText.planDetail(p), 15, FG, false, false), 0, 0);

        section("WHEN YOU HAVE DRIVEN IT");
        add(body, text(StageText.PLAN_DRIVE_NOTE, 13, DIM, false, false), 4, 0);
        final Button pick = button("Make the stage from a recording", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { if (canStartSomething()) go(PICK, p.id, ""); }
        });
        add(body, pick, 10, 64);
        enable(pick, !busy && !plain && !jobRunning);

        section("THIS PLAN");
        add(body, button("Show the start on a map", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { showOnMap(p.startLat, p.startLon, p.name() + " start"); }
        }), 8, 52);
        add(body, button("Show the finish on a map", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { showOnMap(p.finishLat, p.finishLon, p.name() + " finish"); }
        }), 8, 52);
        final Button change = button("Change the places", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { enterPlan(p.id); }
        });
        add(body, change, 8, 52);
        enable(change, !busy);
        final Button rename = button("Rename", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { onRenamePlan(p); }
        });
        add(body, rename, 8, 52);
        enable(rename, !busy);
        final Button delete = button("Delete this plan", PANEL, RED, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDeletePlan(p); }
        });
        add(body, delete, 8, 52);
        enable(delete, !busy);
    }

    private void onRenamePlan(final Plan p) {
        final EditText et = field("", p.name(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this)
                .setTitle("Rename the plan")
                .setView(et)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            final Plan q = store.loadPlan(p.id);
                            if (q != null && !StageLive.busy()) {
                                q.rename(et.getText().toString());
                                store.savePlan(q);
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

    private void confirmDeletePlan(final Plan p) {
        new AlertDialog.Builder(this)
                .setTitle("Delete the plan " + p.name() + "?")
                .setMessage("The plan is removed from the phone. Nothing else is lost: it has no runs.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (!StageLive.busy()) store.delete(p.id);
                        dataVersion++;
                        go(HOME, "", "");
                        setNotice("Deleted the plan " + p.name() + ".");
                    }
                })
                .setNegativeButton("Keep it", null)
                .show();
    }

    // ------------------------------------------------------------------ PLAN: the start and the finish as coordinates

    private EditText field(String hint, String value, int inputType) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setTextColor(FG);
        et.setHintTextColor(DIM);
        et.setHint(hint);
        et.setBackgroundColor(PANEL);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setText(value);
        return et;
    }

    /** Two buttons side by side. */
    private LinearLayout row(Button... buttons) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < buttons.length; i++) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            lp.leftMargin = i == 0 ? 0 : dp(6);
            r.addView(buttons[i], lp);
        }
        return r;
    }

    private Button small(String label, int bg, int fg, float sp, View.OnClickListener l) {
        final Button bt = button(label, bg, fg, l);
        bt.setTextSize(sp);
        return bt;
    }

    /** Begin planning: a new plan (editId "") with what was typed last time, or the places of an existing plan. */
    private void enterPlan(String editId) {
        if (editId.length() == 0) {
            planName = prefs().getString(PREF_PLAN_NAME, "");
            planStart = prefs().getString(PREF_PLAN_START, "");
            planFinish = prefs().getString(PREF_PLAN_FINISH, "");
        } else {
            final Plan p = store.loadPlan(editId);
            if (p == null) {
                go(HOME, "", "");
                setNotice("That plan is not on the phone any more.");
                return;
            }
            planName = p.name();
            planStart = Coords.format(p.startLat, p.startLon);
            planFinish = Coords.format(p.finishLat, p.finishLon);
        }
        go(PLAN, editId, "");
    }

    private void buildPlan() {
        add(body, text(StageText.PLAN_HOW, 14, FG, false, false), 0, 0);
        add(body, button("Open Google Maps", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { openGoogleMaps(); }
        }), 12, 52);

        add(body, text("Name (you can change it later)", 13, DIM, true, false), 18, 0);
        etPlanName = field("For example Fraser to Bentong", planName.length() > 0 ? planName : defaultName(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        add(body, etPlanName, 6, 0);

        add(body, text("Start", 13, DIM, true, false), 18, 0);
        etPlanStart = field("Latitude, longitude", planStart, InputType.TYPE_CLASS_TEXT);
        add(body, etPlanStart, 6, 0);
        tvPlanStart = text("", 13, DIM, false, false);
        add(body, tvPlanStart, 6, 0);
        btnPlanStartMap = small("Show the start on a map", PANEL, FG, 13, new View.OnClickListener() {
            @Override public void onClick(View v) { showTyped(etPlanStart, "Start"); }
        });
        add(body, row(small("Paste the start", PANEL, FG, 13, new View.OnClickListener() {
            @Override public void onClick(View v) { onPaste(etPlanStart); }
        }), btnPlanStartMap), 8, 0);

        add(body, text("Finish", 13, DIM, true, false), 18, 0);
        etPlanFinish = field("Latitude, longitude", planFinish, InputType.TYPE_CLASS_TEXT);
        add(body, etPlanFinish, 6, 0);
        tvPlanFinish = text("", 13, DIM, false, false);
        add(body, tvPlanFinish, 6, 0);
        btnPlanFinishMap = small("Show the finish on a map", PANEL, FG, 13, new View.OnClickListener() {
            @Override public void onClick(View v) { showTyped(etPlanFinish, "Finish"); }
        });
        add(body, row(small("Paste the finish", PANEL, FG, 13, new View.OnClickListener() {
            @Override public void onClick(View v) { onPaste(etPlanFinish); }
        }), btnPlanFinishMap), 8, 0);

        add(body, button("Swap start and finish", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String a = etPlanStart.getText().toString();
                etPlanStart.setText(etPlanFinish.getText().toString());
                etPlanFinish.setText(a);
                updatePlan();
            }
        }), 14, 52);

        tvPlanApart = text("", 13, DIM, false, false);
        add(body, tvPlanApart, 12, 0);
        btnPlanSave = button(stageId.length() > 0 ? "Save the new places" : "Save the plan", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { onPlanSave(); }
        });
        add(body, btnPlanSave, 14, 64);
        add(body, text(StageText.PLAN_NEXT, 12, DIM, false, false), 10, 0);
        updatePlan();
    }

    /** Every refresh while the page is showing: say what was read from each box, and let SAVE through only when both can be used. */
    private void updatePlan() {
        if (etPlanStart == null || etPlanFinish == null || btnPlanSave == null) return;
        final String a = etPlanStart.getText().toString(), b = etPlanFinish.getText().toString();
        final Coords.Result ra = Coords.parse(a), rb = Coords.parse(b);
        final boolean emptyA = a.trim().length() == 0, emptyB = b.trim().length() == 0;
        tvPlanStart.setText(emptyA ? "Paste or type the place where the stage starts." : StageText.placeRead("Start", ra));
        tvPlanStart.setTextColor(emptyA || ra.ok ? DIM : AMBER);
        tvPlanFinish.setText(emptyB ? "Paste or type the place where the stage ends." : StageText.placeRead("Finish", rb));
        tvPlanFinish.setTextColor(emptyB || rb.ok ? DIM : AMBER);
        enable(btnPlanStartMap, ra.ok);
        enable(btnPlanFinishMap, rb.ok);
        boolean far = false;
        String apart = "";
        if (ra.ok && rb.ok) {
            final double d = Geo.distance(ra.lat, ra.lon, rb.lat, rb.lon);
            far = d >= PinBuilder.MIN_SEPARATION_M;
            apart = far ? StageText.planApart(d) : "These two places are only " + StageText.dist(d) + " apart. A stage runs from one place to "
                    + "another at least " + StageText.dist(PinBuilder.MIN_SEPARATION_M) + " away.";
        }
        tvPlanApart.setText(apart);
        tvPlanApart.setTextColor(ra.ok && rb.ok && !far ? AMBER : DIM);
        enable(btnPlanSave, ra.ok && rb.ok && far && canStartSomething());
    }

    private void onPlanSave() {
        if (!canStartSomething()) return;
        saveForms();
        final Coords.Result a = Coords.parse(planStart), b = Coords.parse(planFinish);
        if (!a.ok || !b.ok || Geo.distance(a.lat, a.lon, b.lat, b.lon) < PinBuilder.MIN_SEPARATION_M) return;
        final String name = Stage.cleanName(planName.trim().length() > 0 ? planName : defaultName());
        final String editId = stageId;
        try {
            final Plan p;
            if (editId.length() > 0) {
                final Plan old = store.loadPlan(editId);
                if (old == null) {
                    setNotice("That plan is not on the phone any more.");
                    return;
                }
                p = new Plan(old.id, name, old.createdUtcMs, a.lat, a.lon, b.lat, b.lon);
            } else {
                final long now = System.currentTimeMillis();
                p = new Plan(store.newId(now), name, now, a.lat, a.lon, b.lat, b.lon);
            }
            store.savePlan(p);
            dataVersion++;
            go(DETAIL, p.id, "");
            setNotice(editId.length() > 0 ? "The places are saved." : "Planned. Arm it when you are ready to drive the road once.");
            if (editId.length() == 0) {
                planName = "";
                planStart = "";
                planFinish = "";
                prefs().edit().remove(PREF_PLAN_NAME).remove(PREF_PLAN_START).remove(PREF_PLAN_FINISH).apply();
            }
        } catch (java.io.IOException e) {
            setNotice("Could not save the plan (is the phone full?).");
        }
    }

    /** The text on the clipboard, or "" if there is none or the phone will not say. */
    private String clipboardText() {
        try {
            final ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            final ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            final CharSequence t = cd.getItemAt(0).coerceToText(this);
            return t == null ? "" : t.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void onPaste(EditText target) {
        final String t = clipboardText().trim();
        if (t.length() == 0) {
            Toast.makeText(this, StageText.PASTE_EMPTY, Toast.LENGTH_LONG).show();
            return;
        }
        target.setText(t);
        updatePlan();
    }

    private void showTyped(EditText source, String label) {
        final Coords.Result r = Coords.parse(source.getText().toString());
        if (r.ok) showOnMap(r.lat, r.lon, label);
    }

    private void openGoogleMaps() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps")));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open Google Maps.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ PICK: which drive

    private void buildPick() {
        final boolean can = canStartSomething();
        final Plan plan = stageId.length() > 0 ? store.loadPlan(stageId) : null;
        add(body, text((plan != null ? "Making " + plan.name() + " from a drive. " : "") + StageText.PICK_INTRO, 15, FG, false, false), 0, 0);

        section("RECORDINGS ON THIS PHONE");
        final List<File> files = sessionFiles();
        if (files.isEmpty()) {
            add(body, text("There are no recordings on this phone yet. Record a drive with START on the main screen, then come back; "
                    + "or pick a recording file from somewhere else (below).", 14, FG, false, false), 8, 0);
        } else {
            Collections.reverse(files);                                    // newest first (the names start with the date)
            if (files.size() > PICK_LIST_MAX) {
                add(body, text("The newest " + PICK_LIST_MAX + " of " + files.size() + " recordings.", 12, DIM, false, false), 4, 0);
            }
            for (int i = 0; i < files.size() && i < PICK_LIST_MAX; i++) {
                final File f = files.get(i);
                final TextView row = card(StageText.recordingRow(f.getName(), f.length()), new View.OnClickListener() {
                    @Override public void onClick(View v) { onPickRecording(f); }
                });
                add(body, row, 8, 0);
                enable(row, can);
            }
        }
        section("FROM A FILE");
        final Button file = button("Pick a recording file", PANEL, FG, new View.OnClickListener() {
            @Override public void onClick(View v) { if (canStartSomething()) pickFile(REQ_PIN_FILE); }
        });
        add(body, file, 8, 52);
        enable(file, can);
    }

    private void onPickRecording(File f) {
        if (!canStartSomething()) return;
        loadDrive(f, null);
    }

    /** Reads a drive (a file of the app's own, or a picked one) on a worker thread, then shows the pin page. */
    private void loadDrive(final File file, final Uri uri) {
        final android.content.Context app = getApplicationContext();
        loadedTrack = null;
        runJob("Reading the drive...", new Callable<String>() {
            @Override public String call() {
                try {
                    final LogReader.Log log;
                    if (file != null) {
                        log = LogReader.read(file);
                    } else {
                        final java.io.InputStream in = app.getContentResolver().openInputStream(uri);
                        if (in == null) return "That file could not be opened.";
                        log = LogReader.read(in, StageExporter.displayName(app, uri));
                    }
                    loadedTruncated = log.truncated;
                    loadedTrack = Track.of(log.fixes);
                    loadedName = log.name;
                    return "";
                } catch (PinBuilder.Refused e) {
                    return e.getMessage();
                } catch (java.io.IOException e) {
                    return "That file could not be read.";
                } catch (RuntimeException e) {
                    return "That file could not be read.";
                }
            }
        }, new Runnable() {
            @Override public void run() {
                final Track t = loadedTrack;
                loadedTrack = null;
                if (t == null || page != PICK) return;                 // not read, or the person has gone back while it was being read
                pinTrack = t;
                pinSource = loadedName;
                pinTruncated = loadedTruncated;
                pinFile = file;
                pinUri = uri;
                final String note = initPins();
                go(PIN, stageId, "");
                if (note.length() > 0) setNotice(note);
            }
        });
    }

    // ------------------------------------------------------------------ PIN: put the start and the finish on the drive

    private void clearPin() {
        pinTrack = null;
        pinSource = "";
        pinTruncated = false;
        pinFile = null;
        pinUri = null;
        pinS[0] = -1.0;
        pinS[1] = -1.0;
        pinActive = 0;
        pinDirty = false;
        pinOk = false;
        pinNameText = "";
    }

    /** The first positions of the pins: the ends of the drive, or the places of the plan that is being completed. Returns a notice, "" if none. */
    private String initPins() {
        final double len = pinTrack.length;
        pinS[0] = Math.min(PinBuilder.MARGIN_M, len);
        pinS[1] = Math.max(0.0, len - PinBuilder.MARGIN_M);
        pinActive = 0;
        pinNameText = "";
        final Plan plan = stageId.length() > 0 ? store.loadPlan(stageId) : null;
        if (plan == null) return "";
        pinNameText = plan.name();
        final List<Track.Pass> as = pinTrack.passes(plan.startLat, plan.startLon), bs = pinTrack.passes(plan.finishLat, plan.finishLon);
        final StringBuilder note = new StringBuilder();
        if (as.isEmpty()) note.append("The start place of the plan is not on this drive, so the start pin is at the beginning of it. ");
        else pinS[0] = as.get(0).s;
        if (bs.isEmpty()) {
            note.append("The finish place of the plan is not on this drive, so the finish pin is at the end of it. ");
        } else {
            double pick = bs.get(0).s;
            for (Track.Pass q : bs) {
                if (q.s > pinS[0]) {
                    pick = q.s;
                    break;
                }
            }
            pinS[1] = pick;
        }
        return note.toString().trim();
    }

    private void buildPin() {
        if (pinTrack == null) {
            page = HOME;
            stageId = "";
            setNotice("That drive is not loaded any more. Pick it again.");
            builtKey = key();
            buildHome();
            return;
        }
        add(body, text(StageText.pinHeader(pinSource, pinTrack.length)
                + (pinTruncated ? " Only the first part of a very long recording was read." : ""), 14, DIM, false, false), 0, 0);
        add(body, text(StageText.PIN_HELP, 13, FG, false, false), 8, 0);

        final TrackView.Listener touch = new TrackView.Listener() {
            @Override public void onPick(double s) {
                pinS[pinActive] = s;
                pinMoved();
            }
        };
        overview = new TrackView(this, TrackView.OVERVIEW, density());
        overview.set(pinTrack);
        overview.setListener(touch);
        add(body, overview, 12, 240);
        closeup = new TrackView(this, TrackView.CLOSEUP, density());
        closeup.set(pinTrack);
        closeup.setListener(touch);
        add(body, closeup, 10, 200);

        btnPinStart = small("Start pin", GREEN, DARK_ON_GREEN, 14, new View.OnClickListener() {
            @Override public void onClick(View v) { setActivePin(0); }
        });
        btnPinFinish = small("Finish pin", PANEL, FG, 14, new View.OnClickListener() {
            @Override public void onClick(View v) { setActivePin(1); }
        });
        add(body, row(btnPinStart, btnPinFinish), 14, 0);
        tvPinPos = text("", 14, FG, false, false);
        add(body, tvPinPos, 10, 0);

        seek = new SeekBar(this);
        seek.setMax((int) Math.floor(pinTrack.length));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;                                 // the screen moving the thumb itself is not the person
                pinS[pinActive] = progress;
                pinMoved();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        add(body, seek, 8, 40);

        final String[] labels = {"-500 m", "-50 m", "-5 m", "+5 m", "+50 m", "+500 m"};
        final double[] steps = {-500.0, -50.0, -5.0, 5.0, 50.0, 500.0};
        final Button[] nudges = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final double step = steps[i];
            nudges[i] = small(labels[i], PANEL, FG, 12, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pinS[pinActive] += step;
                    pinMoved();
                }
            });
        }
        add(body, row(nudges), 6, 0);
        add(body, row(small("Open in Google Maps", PANEL, FG, 13, new View.OnClickListener() {
            @Override public void onClick(View v) { onPinMap(); }
        }), small("Set from coordinates", PANEL, FG, 13, new View.OnClickListener() {
            @Override public void onClick(View v) { onPinFromCoordinates(); }
        })), 10, 0);

        add(body, text("Name (you can change it later)", 13, DIM, true, false), 18, 0);
        etPinName = field("For example Fraser to Bentong", pinNameText.length() > 0 ? pinNameText : defaultName(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        add(body, etPinName, 6, 0);
        tvPinCheck = text("", 13, DIM, false, false);
        add(body, tvPinCheck, 14, 0);
        btnPinSave = button("Save the stage", GREEN, DARK_ON_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { onPinSave(); }
        });
        add(body, btnPinSave, 10, 64);
        add(body, text("The drive you picked is scored against the new stage as its first run. Pins go exactly where you put them.", 12, DIM, false, false), 10, 0);

        syncPin();
        updatePinCheck();
        enable(btnPinSave, pinOk && canStartSomething());
    }

    private void setActivePin(int which) {
        pinActive = which == 1 ? 1 : 0;
        pinMoved();
    }

    /** A pin was moved, or the other pin was chosen: what the screen said about the last thing done no longer applies. */
    private void pinMoved() {
        if (!jobRunning) setNotice("");
        syncPin();
    }

    /** Draws the pins where they are, in both pictures, the slider and the words; the check of the stage they make is done on the next refresh. */
    private void syncPin() {
        if (pinTrack == null || overview == null || seek == null) return;
        final double len = pinTrack.length;
        for (int k = 0; k < 2; k++) pinS[k] = Math.max(0.0, Math.min(len, pinS[k]));
        overview.setPins(pinS[0], pinS[1], pinActive);
        closeup.setPins(pinS[0], pinS[1], pinActive);
        seek.setProgress((int) Math.round(pinS[pinActive]));
        final double[] ll = pinTrack.latLonAt(pinS[pinActive]);
        tvPinPos.setText(StageText.pinPosition(pinActive, pinS[pinActive], len, ll[0], ll[1]));
        btnPinStart.setBackgroundColor(pinActive == 0 ? GREEN : PANEL);
        btnPinStart.setTextColor(pinActive == 0 ? DARK_ON_GREEN : FG);
        btnPinFinish.setBackgroundColor(pinActive == 1 ? RED : PANEL);
        btnPinFinish.setTextColor(pinActive == 1 ? 0xFFFFFFFF : FG);
        pinDirty = true;
    }

    private void updatePin() {
        if (pinTrack == null || btnPinSave == null) return;
        if (pinDirty) updatePinCheck();
        enable(btnPinSave, pinOk && canStartSomething());
    }

    /** What the two pins make: a stage (how long, and anything the person should know), or the reason they do not. */
    private void updatePinCheck() {
        pinDirty = false;
        if (pinTrack == null || tvPinCheck == null) return;
        try {
            final PinBuilder.Result r = PinBuilder.build("pin_check", "check", 0L, pinTrack,
                    PinBuilder.Pin.atPosition(pinS[0]), PinBuilder.Pin.atPosition(pinS[1]));
            pinOk = true;
            tvPinCheck.setText(StageText.pinCheck(r));
            tvPinCheck.setTextColor(GREEN);
        } catch (PinBuilder.Refused e) {
            pinOk = false;
            tvPinCheck.setText(e.getMessage());
            tvPinCheck.setTextColor(AMBER);
        }
    }

    private void onPinMap() {
        if (pinTrack == null) return;
        final double[] ll = pinTrack.latLonAt(pinS[pinActive]);
        showOnMap(ll[0], ll[1], pinActive == 0 ? "Start pin" : "Finish pin");
    }

    private void onPinFromCoordinates() {
        if (pinTrack == null) return;
        final double[] cur = pinTrack.latLonAt(pinS[pinActive]);
        final String clip = clipboardText().trim();
        final EditText et = field("Latitude, longitude", Coords.parse(clip).ok ? clip : Coords.format(cur[0], cur[1]), InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(pinActive == 0 ? "Start pin from coordinates" : "Finish pin from coordinates")
                .setMessage("Type or paste a place from a map. The pin goes to the nearest point of this drive.")
                .setView(et)
                .setPositiveButton("Set", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { setPinFromText(et.getText().toString()); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Moves the active pin to where the drive passes a place. A place the drive never goes near is refused with the reason. */
    private void setPinFromText(String text) {
        if (pinTrack == null) return;
        final Coords.Result r = Coords.parse(text);
        if (!r.ok) {
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
            return;
        }
        final List<Track.Pass> passes = pinTrack.passes(r.lat, r.lon);
        if (passes.isEmpty()) {
            Toast.makeText(this, "That place is not on this drive: the road you drove comes no closer than "
                    + StageText.dist(pinTrack.closestM(r.lat, r.lon)) + ". Check the numbers.", Toast.LENGTH_LONG).show();
            return;
        }
        Track.Pass use = passes.get(0);
        if (pinActive == 1) {
            for (Track.Pass q : passes) {
                if (q.s > pinS[0]) {
                    use = q;
                    break;
                }
            }
        }
        pinS[pinActive] = use.s;
        pinMoved();
        if (use.offsetM > PinBuilder.NOTE_OFFSET_M) {
            setNotice("That place is " + StageText.dist(use.offsetM) + " from the road you drove. The pin is put on the road, at the nearest point.");
        }
    }

    private void onPinSave() {
        if (!canStartSomething() || pinTrack == null) return;
        saveForms();
        final long now = System.currentTimeMillis();
        final Plan plan = stageId.length() > 0 ? store.loadPlan(stageId) : null;
        final String id = plan != null ? plan.id : store.newId(now);
        final String name = pinNameText.trim().length() > 0 ? pinNameText : plan != null ? plan.name() : defaultName();
        final PinBuilder.Result res;
        try {
            res = PinBuilder.build(id, name, now, pinTrack, PinBuilder.Pin.atPosition(pinS[0]), PinBuilder.Pin.atPosition(pinS[1]));
        } catch (PinBuilder.Refused e) {
            pinOk = false;
            tvPinCheck.setText(e.getMessage());
            tvPinCheck.setTextColor(AMBER);
            enable(btnPinSave, false);
            return;
        }
        try {
            store.save(res.stage);
        } catch (java.io.IOException e) {
            setNotice("Could not save the stage (is the phone full?).");
            return;
        }
        final Stage stage = res.stage;
        final File file = pinFile;
        final Uri uri = pinUri;
        final String csvName = pinSource;
        final android.content.Context app = getApplicationContext();
        runJob("Saving the stage...", new Callable<String>() {
            @Override public String call() {
                final String head = "Saved " + stage.name() + " (" + StageText.dist(stage.lengthM()) + "). ";
                try {
                    final StageRescore.Report rep;
                    if (file != null) {
                        rep = StageRescore.scoreFiles(store, stage, Collections.singletonList(file), null);
                    } else {
                        final java.io.InputStream in = uri == null ? null : app.getContentResolver().openInputStream(uri);
                        if (in == null) throw new java.io.IOException("no stream");
                        rep = StageRescore.scoreStream(store, stage, in, csvName);
                    }
                    return head + rep.text();
                } catch (java.io.IOException e) {
                    return head + "The drive could not be read again to score it. Use Score a recording file on the stage page.";
                } catch (RuntimeException e) {
                    return head + "The drive could not be read again to score it. Use Score a recording file on the stage page.";
                }
            }
        }, new Runnable() {
            @Override public void run() { go(DETAIL, stage.id, ""); }
        });
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
        runJob(startText, work, null);
    }

    /** @param after run on the screen's thread when the job has ended, before its result is shown (null for nothing) */
    private void runJob(String startText, final Callable<String> work, final Runnable after) {
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
                        if (after != null) after.run();
                        if (r.length() > 0 || after == null) setNotice(r);
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
        } else if (code == REQ_PIN_FILE) {
            loadDrive(null, uri);
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
