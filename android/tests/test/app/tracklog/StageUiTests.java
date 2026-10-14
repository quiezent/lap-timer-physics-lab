package app.tracklog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The stages screen and the main screen, the real classes, run on a computer against fakes of the few Android widgets they use
 * (tests/fakes/android/view, widget, app). The fakes keep what the app puts on the screen, so a test can read it, press the buttons
 * and see which intents go to the service.
 *
 * The most important check is the rule for open roads: with a stage armed, recording or being marked and the car not known to be
 * stopped, nothing on either screen is a time, a difference or a comparison (ST.UI5, and a random walk through the screens in ST.UI24).
 *
 * What this cannot show: how the screens look, whether a button is big enough to hit, how Android lays out the widgets. That needs a phone.
 */
final class StageUiTests {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** A time with tenths (31:12.4) or a difference (+24.3 s): the two ways a screen would show how long a run took. */
    private static final Pattern NUMBER = Pattern.compile("\\d+:\\d\\d\\.\\d|[+\\-]\\d+\\.\\d s|\\bbest \\d");

    private StageUiTests() { }

    // ------------------------------------------------------------------ the rigs

    /** Reading and pressing a screen made of the fake widgets. */
    static class Screen {
        final Activity base;

        Screen(Activity base) {
            this.base = base;
        }

        List<View> views() {
            final List<View> out = new ArrayList<View>();
            ((ViewGroup) base.content).collect(out);
            return out;
        }

        /** The text of everything that can be seen, one view per line. */
        String screen() {
            final StringBuilder b = new StringBuilder();
            for (View v : views()) {
                if (!(v instanceof TextView) || !v.shown()) continue;
                final String t = ((TextView) v).getText().toString();
                if (t.length() > 0) b.append(t).append('\n');
            }
            return b.toString();
        }

        /** The visible text view that says s: exactly, or else starting with it, or else containing it (ignoring case). */
        TextView find(String s) {
            TextView starts = null, contains = null;
            for (View v : views()) {
                if (!(v instanceof TextView) || !v.shown()) continue;
                final String t = ((TextView) v).getText().toString();
                if (t.equalsIgnoreCase(s)) return (TextView) v;
                if (starts == null && t.toLowerCase().startsWith(s.toLowerCase())) starts = (TextView) v;
                if (contains == null && t.toLowerCase().contains(s.toLowerCase())) contains = (TextView) v;
            }
            return starts != null ? starts : contains;
        }

        boolean has(String s) {
            return find(s) != null;
        }

        /** A visible text view that says exactly s (ignoring case): for the one-word states, which also turn up inside other sentences. */
        boolean exact(String s) {
            for (View v : views()) {
                if (v instanceof TextView && v.shown() && ((TextView) v).getText().toString().equalsIgnoreCase(s)) return true;
            }
            return false;
        }

        boolean enabled(String s) {
            final TextView t = find(s);
            return t != null && t.isEnabled();
        }

        boolean tap(String s) {
            final TextView t = find(s);
            return t != null && t.tap();
        }

        EditText editText() {
            for (View v : views()) if (v instanceof EditText && v.shown()) return (EditText) v;
            return null;
        }

        List<Intent> allServiceIntents() {
            final List<Intent> all = new ArrayList<Intent>(base.startedServices);
            all.addAll(base.startedForegroundServices);
            return all;
        }

        Intent lastService() {
            final List<Intent> all = allServiceIntents();
            return all.isEmpty() ? null : all.get(all.size() - 1);
        }
    }

    /** The stages screen on a fake phone: a folder for the app's files, a content resolver, preferences. */
    static final class Rig extends Screen {
        final StagesActivity act;
        final File dir;
        final FakeContentResolver cr;
        final FakePrefs prefs;
        final StageStore store;

        Rig(File dir, FakeContentResolver cr, FakePrefs prefs) {
            this(dir, cr, prefs, StageAppTests.context(dir, cr, prefs));
        }

        Rig(File dir, FakeContentResolver cr, FakePrefs prefs, Context ctx) {
            super(new StagesActivity());
            this.act = (StagesActivity) base;
            this.dir = dir;
            this.cr = cr;
            this.prefs = prefs;
            this.store = new StageStore(new File(ctx.getFilesDir(), "stages"));
            act.attach(ctx);
            act.granted.add("android.permission.ACCESS_FINE_LOCATION");
            act.granted.add("android.permission.ACCESS_COARSE_LOCATION");
            act.granted.add("android.permission.POST_NOTIFICATIONS");
            act.onCreate(null);
        }

        void refresh() {
            act.refresh();
        }
    }

    /** The main screen on a fake phone. */
    static final class MainRig extends Screen {
        final MainActivity act;

        MainRig(Context ctx) {
            super(new MainActivity());
            this.act = (MainActivity) base;
            act.attach(ctx);
            act.onCreate(null);
        }

        void refresh() {
            act.refresh();
        }
    }

    static void live(int state, boolean stopped) {
        StageLive.reset();
        StageLive.state = state;
        StageLive.stopped = stopped;
        StageLive.stageName = "Mountain road";
    }

    static String leak(String screen) {
        final java.util.regex.Matcher m = NUMBER.matcher(screen);
        return m.find() ? m.group() : "";
    }

    static Rig rigWithStages(File root, String tag, int runsA, int runsB) throws Exception {
        final File dir = Files.createTempDirectory(root.toPath(), "ui_" + tag + "_").toFile();
        final File stages = new File(dir, "stages");
        if (runsA >= 0) StageBundleTests.storeWith(stages, 61, runsA, "st_a", "Fraser to Bentong");
        if (runsB >= 0) StageBundleTests.storeWith(stages, 62, runsB, "st_b", "KKB to Fraser");
        return new Rig(dir, new FakeContentResolver(), new FakePrefs());
    }

    /** A phone that has one stage, "Mountain road" (made from the fixtures' marking drive), and nothing else. */
    static Rig rigWithRoad(File root, String tag, boolean withRecordings) throws Exception {
        final File dir = Files.createTempDirectory(root.toPath(), "ui_" + tag + "_").toFile();
        final Map<String, String> inputs = StageFixtures.inputs();
        StageAppTests.write(new File(dir, "marking.csv"), inputs.get("road1/marking.csv"));
        final StageStore store = new StageStore(new File(dir, "stages"));
        store.save(StageFiles.parseStage(StageFiles.formatStage(StageFixtures.rebuild(new File(dir, "marking.csv"), "st_road1", "Mountain road"))));
        if (withRecordings) {
            for (Map.Entry<String, String> e : inputs.entrySet()) {
                if (e.getKey().startsWith("road1/session_")) StageAppTests.write(new File(dir, e.getKey().substring("road1/".length())), e.getValue());
            }
        }
        return new Rig(dir, new FakeContentResolver(), new FakePrefs());
    }

    static void reset() {
        StageLive.reset();
        StageLive.lastRun = null;
        StageLive.markedStageId = "";
        Recorder.LIVE.recording = false;
        Recorder.LIVE.saving = false;
        Recorder.LIVE.failed = false;
        Recorder.LIVE.error = "";
        AlertDialog.SHOWN.clear();
        Toast.SHOWN.clear();
    }

    // ------------------------------------------------------------------ all of it

    static void run(File root) {
        System.out.println("\n===== ST.UI: the stages screen and the main screen on fake widgets =====");
        final Looper keep = Looper.getMainLooper();
        Looper.setMainLooper(Looper.newDetached());
        try {
            reset();
            home(root);
            detailAndCompare(root);
            numbersRule(root);
            armFlow(root);
            liveButtons(root);
            markingFlow(root);
            lastRun(root);
            jobs(root);
            editing(root);
            lifecycle(root);
            walk(root);
            mainScreen(root);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Check.ok("ST.UI the screen tests ran without an exception", false, t.toString());
        } finally {
            reset();
            Looper.setMainLooper(keep);
        }
        try {
            integration(root);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Check.ok("ST.UI the screen and service tests ran without an exception", false, t.toString());
        } finally {
            reset();
        }
    }

    // ------------------------------------------------------------------ the list of stages

    static void home(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "home", 3, 2);
        final String s = r.screen();
        Check.ok("ST.UI1 the stage list: both stages by name, with length, number of runs and best time; ADD A STAGE, BACK UP and RESTORE are there and can be pressed; no frame box and no notice while nothing is going on",
                s.contains("Fraser to Bentong\n4.7 km   3 runs   best ") && s.contains("KKB to Fraser\n4.7 km   2 runs   best ") && !leak(s).isEmpty()
                        && r.enabled("Add a stage") && r.enabled("Back up stages") && r.enabled("Restore from a file") && r.has("Beeps: on")
                        && !r.has("Share the last backup") && !r.exact("ARMED") && !r.has("tap to dismiss"), s.replace('\n', '|'));

        final Rig empty = rigWithStages(root, "empty", -1, -1);
        Check.ok("ST.UI2 with no stages the screen explains what a stage is and how to make one, and there is nothing to back up",
                empty.has("No stages yet.") && empty.has("put a pin at each end of the drive") && !empty.enabled("Back up stages") && empty.enabled("Add a stage"),
                empty.screen().replace('\n', '|'));
    }

    // ------------------------------------------------------------------ one stage, and comparing two runs

    static void detailAndCompare(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "detail", 4, 1);
        Check.ok("ST.UI3a tapping a stage opens it: its name as title, ARM enabled, the runs newest first with their times, the tools below",
                r.tap("Fraser to Bentong"), r.screen());
        final String s = r.screen();
        final List<RunResult> runs = r.store.runs("st_a");
        final RunResult best = Compare.best(runs);
        final RunResult newest = runs.get(runs.size() - 1);
        final int iNewest = s.indexOf(StageText.runLine(newest, best, true)), iOldest = s.indexOf(StageText.runLine(runs.get(0), best, true));
        Check.ok("ST.UI3 the stage page: ARM enabled; 4 runs listed newest first, each with the times StageText gives; scoring, map, rename and delete offered",
                r.enabled("Arm this stage") && iNewest >= 0 && iOldest > iNewest && r.has("Score the recordings on this phone") && r.has("Show the start on a map")
                        && r.has("Rename") && r.has("Delete this stage") && r.has("Tap a run to compare it"), s.replace('\n', '|'));

        // comparing: the newest run against the best
        final TextView row = r.find(StageText.runLine(newest, best, true));
        Check.ok("ST.UI4a tapping a run opens the comparison", row != null && row.tap());
        final String c = r.screen();
        {
            StagesActivity.Plot plot = null;
            for (View v : r.views()) if (v instanceof StagesActivity.Plot) plot = (StagesActivity.Plot) v;
            final Canvas cv = new Canvas();
            if (plot != null) plot.drawInto(cv);
            boolean path = false, labels = false;
            for (String op : cv.ops) {
                if (op.startsWith("path:") && Integer.parseInt(op.substring(5)) == newest.t.length) path = true;
                if (op.equals("text:ahead") || op.equals("text:behind")) labels = true;
            }
            Check.ok("ST.UI4 the comparison page: the graph is there and draws one point for every 25 m of the road (" + newest.t.length + "), with 'ahead' and 'behind' on it; the text says which run was compared and where the difference came from; the three choices Best, Previous, First are there",
                    plot != null && path && labels && c.contains("This run") && c.contains("Where the difference came from") && c.contains("Next best run   ") && r.has("Previous") && r.has("First")
                            && r.has("Delete this run"), c.replace('\n', '|') + " ops=" + cv.ops.size());
        }
        // the choices change what it is compared with
        r.tap("Previous");
        final String p = r.screen();
        r.tap("First");
        final String f = r.screen();
        Check.ok("ST.UI4b choosing Previous or First compares with the run before, or the first run: the labels in the text change with it",
                p.contains("Previous run") && f.contains("First run") && !f.contains("Previous run"), p.replace('\n', '|') + " // " + f.replace('\n', '|'));

        // the oldest run has no previous run
        r.act.onBackPressed();
        final TextView oldestRow = r.find(StageText.runLine(runs.get(0), best, true));
        oldestRow.tap();
        r.tap("Previous");
        Check.ok("ST.UI4c the first run has no previous run to compare with, and the page says so in words instead of showing nothing",
                r.has("There is no previous run to compare it with."), r.screen().replace('\n', '|'));

        // a run that did not finish
        final Rig d = rigWithRoad(root, "dnf", false);
        final File csv = new File(d.dir, "session_20261013_110000.csv");
        StageAppTests.write(csv, StageFixtures.inputs().get("road1/session_20261013_110000.csv"));
        StageRescore.scoreFiles(d.store, d.store.load("st_road1"), Arrays.asList(csv), null);
        final Rig d2 = new Rig(d.dir, d.cr, d.prefs);
        d2.tap("Mountain road");
        final List<RunResult> druns = d2.store.runs("st_road1");
        RunResult dnf = null;
        for (RunResult x : druns) if (!x.finished) dnf = x;
        d2.find(StageText.runLine(dnf, Compare.best(druns), true)).tap();
        Check.ok("ST.UI4d a run that did not finish says why and offers no comparison", d2.has("This run did not finish") && !d2.has("Where the difference came from"), d2.screen().replace('\n', '|'));
    }

    // ------------------------------------------------------------------ the rule for open roads

    static void numbersRule(File root) throws Exception {
        reset();
        final int[] states = {StageLive.ARMED, StageLive.RECORDING, StageLive.RUNNING, StageLive.FINISHING, StageLive.SAVING,
                StageLive.MARK_START, StageLive.MARK_DRIVE, StageLive.MARK_SAVING};
        final String[] names = {"ARMED", "RECORDING", "RUNNING", "FINISHING", "SAVING", "MARK_START", "MARK_DRIVE", "MARK_SAVING"};
        final StringBuilder bad = new StringBuilder();
        int looked = 0, pagesWithTimes = 0;
        for (int k = 0; k < states.length; k++) {
            for (int variant = 0; variant < 2; variant++) {
                // variant 0: the car is moving; variant 1: moving, with an error and a last run on the screen's books
                reset();
                final Rig r = rigWithStages(root, "rule" + k + "_" + variant, 3, 2);
                final List<RunResult> runs = r.store.runs("st_a");
                // the screen is already on a stage page when the state turns busy
                r.tap("Fraser to Bentong");
                final RunResult best = Compare.best(runs);
                r.find(StageText.runLine(runs.get(runs.size() - 1), best, true)).tap();      // on the comparison page
                live(states[k], false);
                if (variant == 1) {
                    StageLive.error = "Something to know";
                    StageLive.lastRun = runs.get(runs.size() - 1);
                    StageLive.lastRunSeq++;
                    StageLive.message = "Run finished. Saving.";
                }
                r.refresh();                                                    // the compare page, or the marking page
                String seen = r.screen();
                looked++;
                if (leak(seen).length() > 0) bad.append(' ').append(names[k]).append(variant).append(":compare:").append(leak(seen));
                // to the stage page and the list, as far as the state lets the person go
                for (int back = 0; back < 3; back++) {
                    r.act.onBackPressed();
                    r.refresh();
                    seen = r.screen();
                    looked++;
                    if (leak(seen).length() > 0) bad.append(' ').append(names[k]).append(variant).append(":back").append(back).append(":").append(leak(seen));
                }
                // down again, by tapping, if the state allows the person to leave the marking page
                if (r.has("Fraser to Bentong") && r.tap("Fraser to Bentong")) {
                    seen = r.screen();
                    looked++;
                    if (leak(seen).length() > 0) bad.append(' ').append(names[k]).append(variant).append(":stage:").append(leak(seen));
                }
            }
        }
        // the same screens with the car stopped DO show times (the rule is about moving, not about hiding the results for good)
        reset();
        final Rig st = rigWithStages(root, "stopped", 3, 2);
        live(StageLive.ARMED, true);
        st.refresh();
        final String stopped = st.screen();
        if (!leak(stopped).isEmpty()) pagesWithTimes++;
        live(StageLive.ARMED, false);
        st.refresh();
        final String moving = st.screen();
        live(StageLive.IDLE, false);
        st.refresh();
        final String idle = st.screen();
        Check.ok("ST.UI5 with a stage armed, recording or being marked and the car moving, no page of the stages screen shows a time or a difference (" + looked
                        + " screens looked at across the 8 states, on the comparison page, the stage page and the list); stopped, or with nothing armed, they are back",
                bad.length() == 0 && pagesWithTimes == 1 && leak(moving).isEmpty() && moving.contains(StageText.HIDDEN_NOTE) && !leak(idle).isEmpty(), bad + " | moving=" + moving.replace('\n', '|'));
    }

    // ------------------------------------------------------------------ ARM

    static void armFlow(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "arm", 3, 2);
        r.act.granted.clear();
        r.tap("Fraser to Bentong");
        final String id = "st_a";

        // 1. the first time, the text about timing on the road comes first, and nothing starts before it is accepted
        r.tap("Arm this stage");
        final AlertDialog first = AlertDialog.last();
        final boolean noStartYet = r.allServiceIntents().isEmpty() && r.act.permissionRequests.isEmpty();
        first.click(DialogInterface.BUTTON_NEGATIVE);
        final boolean cancelledNothing = r.allServiceIntents().isEmpty() && r.act.permissionRequests.isEmpty() && !r.prefs.getBoolean("stage_ack", false);
        AlertDialog.SHOWN.clear();
        r.tap("Arm this stage");
        final AlertDialog second = AlertDialog.last();
        Check.ok("ST.UI6a the first time ARM is pressed the road text comes first (its title and its body are StageText's), nothing starts behind it, and Cancel leaves everything as it was",
                first != null && first.title.equals(StageText.FIRST_USE_TITLE) && first.message.equals(StageText.FIRST_USE_BODY) && "I understand".equals(first.positive)
                        && noStartYet && cancelledNothing && second != null, "title=" + (first == null ? "-" : first.title));

        // 2. accepting it: location is not allowed yet, so the permission dialog comes (location and, once, notifications), and the service is not started
        second.click(DialogInterface.BUTTON_POSITIVE);
        final boolean asked = r.act.permissionRequests.size() == 1 && Arrays.asList(r.act.permissionRequests.get(0)).contains("android.permission.ACCESS_FINE_LOCATION")
                && Arrays.asList(r.act.permissionRequests.get(0)).contains("android.permission.POST_NOTIFICATIONS")
                && r.allServiceIntents().isEmpty() && r.prefs.getBoolean("stage_ack", false);
        // 3. the person allows location (and says no to notifications) and presses ARM again: no text, no second question, and the service
        //    gets ARM with the stage's id as a foreground start
        r.act.granted.add("android.permission.ACCESS_FINE_LOCATION");
        r.act.granted.add("android.permission.ACCESS_COARSE_LOCATION");
        r.act.onRequestPermissionsResult(21, new String[0], new int[0]);
        final String toast = Toast.SHOWN.isEmpty() ? "" : Toast.SHOWN.get(Toast.SHOWN.size() - 1);
        AlertDialog.SHOWN.clear();
        r.tap("Arm this stage");
        final boolean askedOnce = r.act.permissionRequests.size() == 1;
        final Intent i = r.act.startedForegroundServices.isEmpty() ? null : r.act.startedForegroundServices.get(0);
        Check.ok("ST.UI6 ARM end to end: the road text once, then one permission dialog (location, and notifications that are asked for only this once), then ARM with this stage's id as a foreground start of the service; neither question is asked again",
                asked && toast.startsWith("Allowed. Press ARM again.") && askedOnce && AlertDialog.SHOWN.isEmpty() && i != null && RecorderService.ACTION_ARM.equals(i.getAction())
                        && id.equals(i.getStringExtra(RecorderService.EXTRA_STAGE_ID)) && r.act.startedForegroundServices.size() == 1 && i.getTarget() == RecorderService.class,
                "asked=" + asked + " askedOnce=" + askedOnce + " toast=" + toast + " intent=" + (i == null ? "-" : i.getAction()));

        // 4. refused for good: the person is sent to the settings
        final Rig q = rigWithStages(root, "arm_refused", 1, -1);
        q.act.granted.clear();
        q.prefs.edit().putBoolean("stage_ack", true).apply();
        q.tap("Fraser to Bentong");
        q.tap("Arm this stage");
        q.act.rationale = false;
        q.act.onRequestPermissionsResult(21, new String[0], new int[0]);
        final Intent settings = q.act.startedActivities.isEmpty() ? null : q.act.startedActivities.get(q.act.startedActivities.size() - 1);
        Check.ok("ST.UI6b location refused with 'don't ask again': a sentence, and the app's settings page opens",
                settings != null && "android.settings.APPLICATION_DETAILS_SETTINGS".equals(settings.getAction()) && Toast.SHOWN.get(Toast.SHOWN.size() - 1).startsWith("Location is blocked"), "");

        // 5. a plain recording is running: ARM does nothing, the buttons are off, and the page says why
        reset();
        final Rig p = rigWithStages(root, "arm_plain", 2, -1);
        p.prefs.edit().putBoolean("stage_ack", true).apply();
        Recorder.LIVE.recording = true;
        p.refresh();
        p.tap("Fraser to Bentong");
        p.refresh();
        final boolean off = !p.enabled("Arm this stage") && !p.tap("Arm this stage") && p.allServiceIntents().isEmpty();
        p.act.onBackPressed();
        p.refresh();
        final boolean offHome = !p.enabled("Add a stage") && p.has("A recording is running or being saved");
        Recorder.LIVE.recording = false;
        Check.ok("ST.UI6c while an ordinary recording is running or being saved, ARM and ADD A STAGE are off, press nothing, and the page says to stop the recording first",
                off && offHome, "");
    }

    // ------------------------------------------------------------------ the status box and its button

    static void liveButtons(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "live", 2, -1);
        final int[][] cases = {
                {StageLive.ARMED, 1}, {StageLive.RECORDING, 2}, {StageLive.RUNNING, 2}, {StageLive.FINISHING, 2},
                {StageLive.MARK_START, 3}, {StageLive.MARK_DRIVE, 3}};
        final String[] label = {"Disarm", "Stop and disarm", "Stop and disarm", "Stop and disarm", "Cancel marking", "Cancel marking"};
        final String[] action = {RecorderService.ACTION_DISARM, RecorderService.ACTION_DISARM, RecorderService.ACTION_DISARM, RecorderService.ACTION_DISARM,
                RecorderService.ACTION_MARK_CANCEL, RecorderService.ACTION_MARK_CANCEL};
        String bad = "";
        for (int k = 0; k < cases.length; k++) {
            live(cases[k][0], false);
            r.act.startedServices.clear();
            r.refresh();
            final TextView b = r.find(label[k]);
            final String word = StageText.stateWord(cases[k][0]);
            if (b == null || !r.exact(word) || !r.has(StageText.ROAD_REMINDER)) { bad += " nobutton" + k; continue; }
            b.tap();
            final Intent in = r.act.startedServices.isEmpty() ? null : r.act.startedServices.get(0);
            if (in == null || !action[k].equals(in.getAction()) || in.getTarget() != RecorderService.class) bad += " action" + k;
        }
        live(StageLive.SAVING, false);
        r.refresh();
        final boolean noButtonWhileSaving = !r.has("Disarm") && !r.has("Stop and disarm") && !r.has("Cancel marking") && r.exact("SAVING");
        live(StageLive.IDLE, false);
        r.refresh();
        final boolean boxGone = !r.exact("ARMED") && !r.has(StageText.ROAD_REMINDER);
        // an error shows even when nothing is armed, and tapping it dismisses it
        StageLive.error = "That stage could not be read.";
        r.refresh();
        final boolean errShown = r.has("That stage could not be read.") && r.has("tap to dismiss");
        r.find("That stage could not be read.").tap();
        r.refresh();
        final boolean errGone = !r.has("That stage could not be read.");
        // a failure of the recorder is shown too
        Recorder.LIVE.failed = true;
        Recorder.LIVE.error = "Storage is full.";
        r.refresh();
        final boolean recErr = r.has("Storage is full.");
        Recorder.LIVE.failed = false;
        Recorder.LIVE.error = "";
        Check.ok("ST.UI7 the status box: each state shows its word and the road reminder; the button reads Disarm, Stop and disarm or Cancel marking and sends DISARM or MARK_CANCEL to the service; no button while saving; the box is gone with nothing armed; errors show and can be dismissed",
                bad.isEmpty() && noButtonWhileSaving && boxGone && errShown && errGone && recErr, bad + " " + noButtonWhileSaving + boxGone + errShown + errGone + recErr);

        // while armed, what cannot be done is off
        live(StageLive.ARMED, true);
        r.refresh();
        final boolean homeOff = !r.enabled("Add a stage") && !r.enabled("Restore from a file");
        r.tap("Fraser to Bentong");
        final boolean detailOff = !r.enabled("Arm this stage") && !r.enabled("Rename") && !r.enabled("Delete this stage") && !r.enabled("Score the recordings on this phone")
                && !r.enabled("Score a recording file");
        Check.ok("ST.UI8 while a stage is armed, marking, restoring, arming, scoring, renaming and deleting are off (so nothing under the armed stage can change)", homeOff && detailOff, homeOff + " " + detailOff);
    }

    // ------------------------------------------------------------------ marking a stage

    static void markingFlow(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "mark", 1, -1);
        r.prefs.edit().putBoolean("stage_ack", true).apply();
        r.tap("Add a stage");
        r.tap("Mark it by parking at both ends");
        final Intent first = r.act.startedForegroundServices.isEmpty() ? null : r.act.startedForegroundServices.get(0);
        // the service has started the recording and says it is waiting for MARK START
        live(StageLive.MARK_START, false);
        StageLive.markHint = "Park at the start of the stage. Keep still while the GPS settles.";
        r.refresh();
        final boolean page1 = r.has("Step 1 of 2") && r.exact("MARKING") && r.has("Keep still while the GPS settles.") && !r.enabled("Mark start")
                && r.editText() != null && !r.has("Mark finish") && r.has("Cancel marking");
        StageLive.canMark = true;
        StageLive.markHint = "Parked at the start? Press MARK START.";
        r.refresh();
        final boolean canPress = r.enabled("Mark start");
        r.editText().setText("Fraser to Bentong");
        r.tap("Mark start");
        final Intent ms = r.act.startedServices.isEmpty() ? null : r.act.startedServices.get(r.act.startedServices.size() - 1);
        // start marked: the name box goes, MARK FINISH comes (off while moving)
        live(StageLive.MARK_DRIVE, false);
        StageLive.markHint = "Start marked. Drive the stage, then park at the finish.";
        r.refresh();
        final boolean page2 = r.has("Step 2 of 2") && r.editText() == null && !r.has("Mark start") && r.has("Mark finish") && !r.enabled("Mark finish")
                && !leak(r.screen()).equals("-") && leak(r.screen()).isEmpty();
        StageLive.canMark = true;
        r.refresh();
        r.tap("Mark finish");
        final Intent mf = r.act.startedServices.get(r.act.startedServices.size() - 1);
        Check.ok("ST.UI9 marking a stage (ADD A STAGE, then MARK IT BY PARKING AT BOTH ENDS): the choice starts the service in the foreground; step 1 shows the hint and keeps MARK START off until the car is still, with a name box; step 2 hides the box and offers MARK FINISH; "
                        + "MARK START and MARK FINISH reach the service, FINISH with the typed name",
                first != null && RecorderService.ACTION_MARK_NEW.equals(first.getAction()) && page1 && canPress && ms != null && RecorderService.ACTION_MARK_START.equals(ms.getAction())
                        && page2 && RecorderService.ACTION_MARK_FINISH.equals(mf.getAction()) && "Fraser to Bentong".equals(mf.getStringExtra(RecorderService.EXTRA_NAME)),
                "page1=" + page1 + " canPress=" + canPress + " page2=" + page2 + " name=" + mf.getStringExtra(RecorderService.EXTRA_NAME));

        // the stage is saved by the service: MARK_SAVING, then the screen is back at idle with a new stage id
        live(StageLive.MARK_SAVING, false);
        r.refresh();
        final boolean saving = r.has("Saving the stage.") && !r.has("Mark finish");
        final Stage fresh = StageFiles.parseStage(StageFiles.formatStage(r.store.load("st_a")));
        final Stage added = new Stage("st_new", "Fraser to Bentong", fresh.kind, fresh.createdUtcMs, fresh.geo, fresh.start, fresh.finish, fresh.route,
                fresh.startMarkLat, fresh.startMarkLon, fresh.finishMarkLat, fresh.finishMarkLon);
        r.store.save(added);
        StageLive.markedStageId = "st_new";
        StageLive.markedSeq++;
        live(StageLive.IDLE, false);
        r.refresh();
        Check.ok("ST.UI10 when the stage has been saved the screen leaves the marking page for the new stage's page, with a notice; name and all",
                saving && r.has("Stage saved. Arm it when you are ready") && r.has("Arm this stage") && r.find("4.7 km   marked") != null,
                r.screen().replace('\n', '|'));

        // cancelled: back to the list, nothing new
        reset();
        final Rig c = rigWithStages(root, "mark_cancel", 1, -1);
        live(StageLive.MARK_START, false);
        c.refresh();
        c.tap("Cancel marking");
        final boolean sent = RecorderService.ACTION_MARK_CANCEL.equals(c.lastService().getAction());
        live(StageLive.IDLE, false);
        c.refresh();
        Check.ok("ST.UI11 cancelling the marking sends MARK_CANCEL, and once the service is idle the screen is back at the list (no stage saved, no notice)",
                sent && c.has("YOUR STAGES") && !c.has("Stage saved"), c.screen().replace('\n', '|'));

        // the marking page comes up by itself when the screen is opened while the service is already marking
        reset();
        final Rig late = rigWithStages(root, "mark_late", 1, -1);
        live(StageLive.MARK_DRIVE, false);
        late.refresh();
        Check.ok("ST.UI12 a screen opened while the service is already marking goes straight to the marking page", late.has("Step 2 of 2"), late.screen().replace('\n', '|'));
    }

    // ------------------------------------------------------------------ the result after a run

    static void lastRun(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "last", 3, -1);
        final List<RunResult> runs = r.store.runs("st_a");
        final RunResult last = runs.get(runs.size() - 1);
        StageLive.lastRun = last;
        StageLive.lastRunSeq++;
        r.refresh();
        final boolean idleShows = r.has("LAST RUN") && r.has("That run: " + StageText.time(last.totalS)) && r.has("Fraser to Bentong, ");
        live(StageLive.ARMED, false);
        r.refresh();
        final boolean movingHides = !r.has("LAST RUN") && !r.has("That run:");
        StageLive.stopped = true;
        r.refresh();
        final boolean stoppedShows = r.has("LAST RUN") && r.has("That run: " + StageText.time(last.totalS));
        r.find("That run:").tap();
        r.refresh();
        final boolean dismissed = !r.has("LAST RUN");
        Check.ok("ST.UI13 the result of the last run: shown (stage, when, time and how it compares) when nothing is armed or the car has stopped; gone while moving; tapping it dismisses it",
                idleShows && movingHides && stoppedShows && dismissed, idleShows + " " + movingHides + " " + stoppedShows + " " + dismissed);
    }

    // ------------------------------------------------------------------ the jobs on a worker thread

    static void jobs(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "jobs", 3, 2);
        // back up
        r.tap("Back up stages");
        final boolean busyNow = !r.enabled("Back up stages") || r.has("Saving the backup");
        spin(r);
        final String backupText = r.screen();
        final List<FakeContentResolver.Row> rows = r.cr.rows();
        Check.ok("ST.UI14 BACK UP STAGES works on a worker thread and reports in the notice line (name, counts); one zip is in Downloads/TrackLogger; SHARE THE LAST BACKUP then appears and sends it as application/zip",
                busyNow && backupText.contains("Saved Downloads/TrackLogger/TrackLogger-stages-") && backupText.contains("2 stages, 5 runs") && rows.size() == 1
                        && r.has("Share the last backup") && shareOk(r, rows.get(0)), backupText.replace('\n', '|'));

        // restore into a second phone
        final Rig p2 = rigWithStages(root, "jobs2", -1, -1);
        p2.tap("Restore from a file");
        final Intent pick = p2.act.startedActivities.isEmpty() ? null : p2.act.startedActivities.get(0);
        final Object mimes = pick == null ? null : pick.getExtra(Intent.EXTRA_MIME_TYPES);
        p2.cr.documents.put("content://docs/backup", rows.get(0).data);
        p2.act.onActivityResult(22, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/backup")));
        spin(p2);
        final String restored = p2.screen();
        Check.ok("ST.UI15 RESTORE FROM A FILE opens the picker (open document, openable, zip types) and a chosen backup is merged in: two stages appear in the list",
                pick != null && Intent.ACTION_OPEN_DOCUMENT.equals(pick.getAction()) && pick.getCategories().contains(Intent.CATEGORY_OPENABLE) && "*/*".equals(pick.getType())
                        && mimes instanceof String[] && Arrays.asList((String[]) mimes).contains("application/zip") && p2.act.resultRequests.contains(22)
                        && restored.contains("2 stages added and 5 runs added.") && restored.contains("Fraser to Bentong") && restored.contains("KKB to Fraser"), restored.replace('\n', '|'));

        // score the recordings the app holds
        final Rig s = rigWithRoad(root, "jobs3", false);
        final Map<String, String> in = StageFixtures.inputs();
        s.tap("Mountain road");
        s.tap("Score the recordings on this phone");
        final String none = s.screen();
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e.getKey().startsWith("road1/session_")) StageAppTests.write(new File(s.dir, e.getKey().substring("road1/".length())), e.getValue());
        }
        s.refresh();
        s.tap("Score the recordings on this phone");
        spin(s);
        final String scored = s.screen();
        final boolean pressed = s.tap("Score a recording file");
        final Intent pickCsv = s.act.startedActivities.isEmpty() ? null : s.act.startedActivities.get(s.act.startedActivities.size() - 1);
        s.cr.documents.put("content://docs/one", in.get("road1/session_20261013_100000.csv").getBytes(UTF8));
        s.cr.displayNames.put("content://docs/one", "session_20261013_100000.csv");
        s.act.onActivityResult(23, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/one")));
        spin(s);
        final String again = s.screen();
        Check.ok("ST.UI16 SCORE THE RECORDINGS ON THIS PHONE: with none stored it says so; with five it reads them on a worker thread and reports 7 runs (6 finished, 1 did not); SCORE A RECORDING FILE reads a picked file and says those runs were already saved; the runs are on the page",
                none.contains("There are no recordings stored in the app yet.") && scored.contains("Read 5 recordings. Found 7 runs of Mountain road (6 finished, 1 did not finish): 7 new.")
                        && pickCsv != null && "*/*".equals(pickCsv.getType()) && pickCsv.getExtra(Intent.EXTRA_MIME_TYPES) == null
                        && again.contains("Read 1 recording. Found 2 runs of Mountain road: all were already saved.") && again.contains("did not finish"),
                "pressed=" + pressed + " " + scored.replace('\n', '|') + " // " + again.replace('\n', '|'));

        // a job that throws is reported and does not leave the screen stuck
        final Rig t = rigWithStages(root, "jobs4", 1, -1);
        t.cr.failInsert = true;
        t.tap("Back up stages");
        spin(t);
        Check.ok("ST.UI17 when the backup fails the screen says so and the buttons come back", t.screen().contains("The backup was not saved (") && t.enabled("Back up stages"), t.screen().replace('\n', '|'));
    }

    private static boolean shareOk(Rig r, FakeContentResolver.Row row) {
        r.tap("Share the last backup");
        final Intent chooser = r.act.startedActivities.get(r.act.startedActivities.size() - 1);
        final Object inner = chooser.getExtra("android.intent.extra.INTENT");
        if (!(inner instanceof Intent)) return false;
        final Intent send = (Intent) inner;
        return Intent.ACTION_SEND.equals(send.getAction()) && "application/zip".equals(send.getType()) && send.getExtra(Intent.EXTRA_STREAM) != null
                && send.getExtra(Intent.EXTRA_STREAM).toString().endsWith("/" + row.id) && (send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
    }

    /** Lets a job finish: the worker hands its result to the screen through runOnUiThread, which a test must pump. */
    static void spin(Rig r) throws InterruptedException {
        final long end = System.nanoTime() + 60000L * 1000000L;
        while (r.act.jobRunning && System.nanoTime() < end) {
            r.act.pumpUi();
            Thread.sleep(2);
        }
        r.act.pumpUi();
        r.refresh();
    }

    // ------------------------------------------------------------------ rename, delete

    static void editing(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "edit", 3, 1);
        r.tap("Fraser to Bentong");
        r.tap("Rename");
        final AlertDialog d = AlertDialog.last();
        final EditText et = (EditText) d.view;
        final boolean prefilled = et.getText().toString().equals("Fraser to Bentong");
        et.setText("Fraser to Bentong, via the pass; again");
        d.click(DialogInterface.BUTTON_POSITIVE);
        r.refresh();
        final Stage renamed = r.store.load("st_a");
        Check.ok("ST.UI18 RENAME: the dialog starts with the old name; the new name is saved (commas and semicolons become spaces), the title changes, the road and its 3 runs are untouched",
                prefilled && renamed != null && renamed.name().equals("Fraser to Bentong via the pass again") && r.store.runs("st_a").size() == 3
                        && StageBundle.sameRoad(renamed, StageFiles.parseStage(StageFiles.formatStage(renamed))) && r.has("Fraser to Bentong via the pass again"), renamed == null ? "-" : renamed.name());

        // delete a run by holding it, and keep it by saying no
        final List<RunResult> runs = r.store.runs("st_a");
        final RunResult victim = runs.get(1);
        final TextView row = r.find(StageText.runLine(victim, Compare.best(runs), true));
        AlertDialog.SHOWN.clear();
        final boolean held = row.hold();
        final AlertDialog ask = AlertDialog.last();
        ask.click(DialogInterface.BUTTON_NEGATIVE);
        final boolean keptOnNo = r.store.runs("st_a").size() == 3;
        row.hold();
        AlertDialog.last().click(DialogInterface.BUTTON_POSITIVE);
        r.refresh();
        final boolean gone = r.store.runs("st_a").size() == 2 && !new File(new File(r.store.root(), "st_a"), "run_" + victim.runId + ".txt").exists();
        Check.ok("ST.UI19 holding a run asks before deleting it ('Keep it' keeps it); 'Delete' removes that run's file and only that one, and the page shows the two left",
                held && ask != null && ask.title.equals("Delete this run?") && keptOnNo && gone && r.has("Tap a run to compare it"), "");

        // delete the stage
        r.tap("Delete this stage");
        final AlertDialog del = AlertDialog.last();
        final boolean text = del.title.startsWith("Delete Fraser to Bentong") && del.message.contains("its 2 runs are");
        del.click(DialogInterface.BUTTON_POSITIVE);
        r.refresh();
        Check.ok("ST.UI20 DELETE THIS STAGE asks first (name and number of runs), then removes the stage and its runs, returns to the list and says so; the other stage stays",
                text && r.store.load("st_a") == null && !new File(r.store.root(), "st_a").exists() && r.store.load("st_b") != null && r.has("Deleted Fraser to Bentong")
                        && r.has("KKB to Fraser"), r.screen().replace('\n', '|'));
    }

    // ------------------------------------------------------------------ life of the screen

    static void lifecycle(File root) throws Exception {
        reset();
        final Rig r = rigWithStages(root, "life", 2, -1);
        final Looper l = Looper.getMainLooper();
        r.act.onResume();
        final int afterResume = l.queued();
        r.act.onPause();
        final int afterPause = l.queued();
        // back: list -> finish; stage -> list; comparison -> stage
        r.tap("Fraser to Bentong");
        final List<RunResult> runs = r.store.runs("st_a");
        r.find(StageText.runLine(runs.get(0), Compare.best(runs), true)).tap();
        final boolean onCompare = r.has("This run");
        r.act.onBackPressed();
        final boolean onStage = r.has("Arm this stage");
        r.act.onBackPressed();
        final boolean onList = r.has("YOUR STAGES") && !r.act.finished;
        r.act.onBackPressed();
        Check.ok("ST.UI21 the refresh loop is queued when the screen resumes and removed when it pauses; Back goes comparison > stage > list > out of the screen",
                afterResume == 1 && afterPause == 0 && onCompare && onStage && onList && r.act.finished, afterResume + " " + afterPause + " " + onCompare + onStage + onList + r.act.finished);

        // a stage that is deleted under the screen (restore, another screen) is not a crash
        final Rig g = rigWithStages(root, "gone", 2, 1);
        g.tap("Fraser to Bentong");
        g.store.delete("st_a");
        g.act.onResume();
        g.refresh();
        g.act.onPause();
        g.refresh();
        Check.ok("ST.UI22 a stage that disappears while its page is open sends the screen back to the list with a sentence instead of failing", g.has("not on the phone any more") && g.has("KKB to Fraser"),
                g.screen().replace('\n', '|'));

        // a state the screen does not know about must not break it
        final Rig odd = rigWithStages(root, "odd", 1, -1);
        live(99, false);
        StageLive.stageName = "";
        odd.refresh();
        live(-1, true);
        odd.refresh();
        Check.ok("ST.UI23 a state number the screen does not know shows nothing odd and raises nothing", !odd.has("Screen problem"), odd.screen().replace('\n', '|'));
        reset();
    }

    // ------------------------------------------------------------------ a random walk through both screens

    static void walk(File root) throws Exception {
        reset();
        final Random rnd = new Random(20261013L);
        final Rig r = rigWithStages(root, "walk", 4, 3);
        r.prefs.edit().putBoolean("stage_ack", true).apply();
        final List<RunResult> runs = r.store.runs("st_a");
        int steps = 0, shownScreens = 0, taps = 0, hidden = 0;
        final StringBuilder bad = new StringBuilder();
        final String[] avoid = {"Delete", "Rename"};          // dialogs would need answering; they have their own tests
        for (int n = 0; n < 4000 && bad.length() == 0; n++) {
            // the world changes: the state, the car moving or still, errors, results
            if (rnd.nextInt(3) == 0) {
                final int pick = rnd.nextInt(11);
                reset();
                StageLive.state = pick <= 8 ? pick : (pick == 9 ? 99 : -3);
                StageLive.stopped = rnd.nextBoolean();
                StageLive.canMark = rnd.nextBoolean();
                StageLive.stageName = "Mountain road";
                StageLive.markHint = rnd.nextBoolean() ? "Keep still while the GPS settles." : "";
                StageLive.message = rnd.nextBoolean() ? "Run finished. Saving." : "";
                StageLive.error = rnd.nextInt(4) == 0 ? "Something went wrong." : "";
                if (rnd.nextInt(3) == 0) { StageLive.lastRun = runs.get(rnd.nextInt(runs.size())); StageLive.lastRunSeq++; }
                if (rnd.nextInt(10) == 0) Recorder.LIVE.recording = !Recorder.LIVE.recording;
            }
            // a random press on something that can be pressed
            final List<View> hit = new ArrayList<View>();
            for (View v : r.views()) {
                if (!(v instanceof TextView) || !v.shown() || !v.isEnabled() || !v.clickable()) continue;
                final String t = ((TextView) v).getText().toString();
                boolean skip = false;
                for (String a : avoid) if (t.startsWith(a)) skip = true;
                if (t.startsWith("Back up") || t.startsWith("Score") || t.startsWith("Restore")) skip = true;       // jobs: tested above
                if (!skip) hit.add(v);
            }
            if (!hit.isEmpty() && rnd.nextInt(4) != 0) {
                hit.get(rnd.nextInt(hit.size())).tap();
                taps++;
            } else if (rnd.nextInt(6) == 0) {
                r.act.onBackPressed();
                if (r.act.finished) r.act.finished = false;
            }
            AlertDialog.SHOWN.clear();
            r.refresh();
            steps++;
            final String s = r.screen();
            if (s.contains("Screen problem")) bad.append("step ").append(n).append(": ").append(s.replace('\n', '|'));
            if (!StageLive.numbersAllowed() && leak(s).length() > 0) bad.append("step ").append(n).append(" state=").append(StageLive.state).append(": ").append(leak(s));
            if (StageLive.numbersAllowed() && leak(s).length() > 0) shownScreens++;
            if (!StageLive.numbersAllowed()) hidden++;
            if (Recorder.LIVE.recording && StageLive.state == StageLive.IDLE) { /* a plain recording: nothing to check beyond the above */ }
        }
        Check.ok("ST.UI24 a random walk of " + steps + " steps (" + taps + " presses on whatever could be pressed, back presses, and the world changing under the screen: any state, moving or still, errors, results, an ordinary recording): "
                        + "never a failure of the screen, and not once a time on screen while moving (" + hidden + " screens with the car moving, " + shownScreens + " with times because it was still or idle)",
                bad.length() == 0 && hidden > 500 && shownScreens > 200, bad.toString());
        reset();
    }

    // ------------------------------------------------------------------ the main screen

    static void mainScreen(File root) throws Exception {
        reset();
        final File dir = Files.createTempDirectory(root.toPath(), "ui_main_").toFile();
        final MainRig main = new MainRig(StageAppTests.context(dir, new FakeContentResolver(), new FakePrefs()));
        final MainActivity act = main.act;
        main.refresh();
        final boolean idle = main.has("Stages") && main.has("READY") && main.has("-- km/h") && !main.has(StageText.ROAD_REMINDER);
        main.tap("Stages");
        final Intent open = act.startedActivities.isEmpty() ? null : act.startedActivities.get(0);

        // with a stage armed, recording or being marked: words, no numbers, no recording clock, no lap clock, and the button ends the stage
        final int[] states = {StageLive.ARMED, StageLive.RECORDING, StageLive.RUNNING, StageLive.FINISHING, StageLive.SAVING,
                StageLive.MARK_START, StageLive.MARK_DRIVE, StageLive.MARK_SAVING};
        final StringBuilder bad = new StringBuilder();
        for (int s : states) {
            live(s, false);
            // the recorder is recording at speed, with laps timed: all that the screen would normally show
            Recorder.LIVE.recording = s != StageLive.ARMED && s != StageLive.SAVING && s != StageLive.MARK_SAVING;
            Recorder.LIVE.haveFix = true;
            Recorder.LIVE.speedMs = 31.0f;
            Recorder.LIVE.calDone = true;
            Recorder.LIVE.calOk = true;
            Recorder.LIVE.startElapsedNs = android.os.SystemClock.elapsedRealtimeNanos() - 754_000_000_000L;
            Recorder.LIVE.lapStartNs = android.os.SystemClock.elapsedRealtimeNanos() - 61_000_000_000L;
            Recorder.LIVE.lapNo = 2;
            Recorder.LIVE.lastLapS = 151.3;
            Recorder.LIVE.bestLapS = 150.2;
            Recorder.LIVE.bestLapNo = 1;
            Recorder.LIVE.lapText = "1  2:30.2\n2  2:31.3";
            Recorder.LIVE.horizG = 0.8f;
            main.refresh();
            final String screen = main.screen();
            if (!StageLive.numbersAllowed() && (screen.matches("(?s).*\\d+ km/h.*") || screen.matches("(?s).*\\d+:\\d\\d.*") || screen.contains("G    ")
                    || screen.contains("LAP") || screen.contains("GPS  fix") || leak(screen).length() > 0)) {
                bad.append(' ').append(s).append(':').append(screen.replace('\n', '|'));
            }
            if (!main.exact(StageText.stateWord(s)) || !main.has(StageText.ROAD_REMINDER)) bad.append(" nobanner").append(s);
            if (main.enabled("Start recording")) bad.append(" startOn").append(s);
        }
        // the Stop button: Disarm, and it sends DISARM (or MARK_CANCEL), never STOP
        live(StageLive.ARMED, false);
        Recorder.LIVE.recording = false;
        act.startedServices.clear();
        main.refresh();
        final boolean disarmLabel = main.has("Disarm") && main.enabled("Disarm");
        main.tap("Disarm");
        final Intent d1 = act.startedServices.get(0);
        live(StageLive.MARK_DRIVE, false);
        act.startedServices.clear();
        main.refresh();
        main.tap("Cancel marking");
        final Intent d2 = act.startedServices.get(0);
        // and everything is back when the stage is over
        reset();
        Recorder.LIVE.recording = false;
        Recorder.LIVE.speedMs = 0.0f;
        main.refresh();
        final boolean back = main.has("READY") && main.has("-- km/h") && main.has("Stop & save") && !main.has(StageText.ROAD_REMINDER) && main.enabled("Start recording");
        Check.ok("ST.UI25 the main screen: a Stages button that opens the stages screen; with a stage armed, recording or being marked it shows the state in words and the road reminder, "
                        + "and no speed, no recording clock, no lap clock, no G and no GPS line; START is off; the red button is Disarm or Cancel marking and sends DISARM or MARK_CANCEL; "
                        + "when the stage is over everything is back",
                idle && open != null && open.getTarget() == StagesActivity.class && bad.length() == 0 && disarmLabel && RecorderService.ACTION_DISARM.equals(d1.getAction())
                        && RecorderService.ACTION_MARK_CANCEL.equals(d2.getAction()) && back, bad + " idle=" + idle + " back=" + back);
        Recorder.LIVE.lapNo = 0;
        Recorder.LIVE.lapText = "";
        Recorder.LIVE.lapStartNs = 0L;
        Recorder.LIVE.haveFix = false;
        Recorder.LIVE.speedMs = 0.0f;
    }

    // ------------------------------------------------------------------ the screen and the real service together

    static void integration(File root) throws Exception {
        reset();
        final ServiceHost h = new ServiceHost(root, "ui_int");
        try {
            final StageStore store = ScenarioSTService.storeOf(h);
            StageBundleTests.storeWith(store.root(), 71, 2, "st_a", "Fraser to Bentong");
            h.prefs.edit().putBoolean("stage_ack", true).apply();
            final Rig r = new Rig(h.root, h.cr, h.prefs, h.app);
            r.act.granted.add("android.permission.ACCESS_FINE_LOCATION");
            r.tap("Fraser to Bentong");
            r.tap("Arm this stage");
            for (Intent i : r.act.startedForegroundServices) h.startService(i);
            final boolean armed = h.awaitTrue(new java.util.function.BooleanSupplier() {
                @Override public boolean getAsBoolean() { return StageLive.state == StageLive.ARMED; }
            }, 3000);
            r.refresh();
            final boolean showsArmed = r.exact("ARMED") && r.has("Armed for Fraser to Bentong.") && r.has("Disarm") && !r.enabled("Arm this stage") && h.current != null && h.current.foreground;
            r.act.startedServices.clear();
            r.tap("Disarm");
            for (Intent i : r.act.startedServices) h.startService(i);
            final boolean idle = ScenarioSTService.idle(h, 4000);
            r.refresh();
            final boolean showsIdle = !r.exact("ARMED") && r.enabled("Arm this stage") && StageLive.state == StageLive.IDLE;
            Check.ok("ST.UI26 the screen and the real service together: ARM from the screen arms the service (foreground, ARMED, the screen shows it and turns ARM off), DISARM from the screen disarms it and the service stops",
                    armed && showsArmed && idle && showsIdle && h.quiet() && h.leaks().isEmpty(),
                    "armed=" + armed + " shows=" + showsArmed + " idle=" + idle + " showsIdle=" + showsIdle + " state=" + StageLive.state + " armEnabled=" + r.enabled("Arm this stage") + " hasARMED=" + r.exact("ARMED") + " rec=" + Recorder.LIVE.recording + " saving=" + Recorder.LIVE.saving + " job=" + r.act.jobRunning + " leaks=" + h.leaks() + " " + h.quietDetail()
                            + " screen=" + r.screen().replace('\n', '|'));

            // marking: MARK_NEW from the screen, then CANCEL from the screen
            r.act.startedServices.clear();
            r.act.startedForegroundServices.clear();
            r.act.onBackPressed();
            r.act.finished = false;
            r.tap("Add a stage");
            r.tap("Mark it by parking at both ends");
            for (Intent i : r.act.startedForegroundServices) h.startService(i);
            final boolean marking = h.awaitRecording(3000) && StageLive.state == StageLive.MARK_START;
            r.refresh();
            final boolean onMark = r.has("Step 1 of 2") && r.has("Cancel marking") && !r.enabled("Mark start");
            r.tap("Cancel marking");
            for (Intent i : r.act.startedServices) h.startService(i);
            final boolean idle2 = ScenarioSTService.idle(h, 6000);
            r.refresh();
            Check.ok("ST.UI27 ADD A STAGE and MARK IT BY PARKING AT BOTH ENDS from the screen start the service recording in marking mode (the screen follows it to the marking page and MARK START waits for a still car); CANCEL from the screen ends it as an ordinary recording and the screen is back at the list",
                    marking && onMark && idle2 && r.has("YOUR STAGES") && !r.has("Step 1 of 2") && h.quiet(), "marking=" + marking + " onMark=" + onMark + " idle=" + idle2 + " " + r.screen().replace('\n', '|'));
        } finally {
            h.close();
        }
    }
}
