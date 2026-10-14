package app.tracklog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Release 1.5 on the stages screen, the real classes against fakes of the few Android widgets they use (as StageUiTests): the three ways
 * to add a stage, the form for a planned stage (coordinates typed or pasted, Google Maps buttons), the planned stage's own page, the
 * page that picks a drive, the pin page (a drive drawn as a line, two pins moved by touch, slider and buttons or put at coordinates), and
 * completing a plan from a drive.
 *
 * What this cannot show: how the pictures look, whether a finger can hit what it should, what Google Maps does with the intent it is
 * given. That needs a phone. The engine behind the pin page (reading a place, the track, the pins to a stage) is checked by PinTests.
 */
final class PinUiTests {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String REC = "session_20261013_111500.csv";          // a clean rolling run over the whole mountain road
    private static final String REC2 = "session_20261013_100000.csv";         // the same road driven twice, joined by a straight line

    private static Map<String, String> inputs;
    private static SynthRoad road;
    private static Track track, track2;

    private PinUiTests() { }

    private static void prepare() throws Exception {
        if (inputs != null) return;
        inputs = StageFixtures.inputs();
        road = SynthRoad.mountain();
        track = Track.of(read(REC, "road1/" + REC).fixes);
        track2 = Track.of(read(REC2, "road1/" + REC2).fixes);
    }

    private static LogReader.Log read(String name, String key) throws Exception {
        return LogReader.read(new ByteArrayInputStream(inputs.get(key).getBytes(UTF8)), name);
    }

    // ------------------------------------------------------------------ helpers

    private static StageUiTests.Rig phone(File root, String tag) throws Exception {
        final StageUiTests.Rig r = StageUiTests.rigWithStages(root, "pn_" + tag, -1, -1);
        r.prefs.edit().putBoolean("stage_ack", true).apply();
        StageAppTests.CLIP.clip = null;
        StageAppTests.CLIP.refuse = null;
        return r;
    }

    private static void record(StageUiTests.Rig r, String key) throws Exception {
        StageAppTests.write(new File(r.dir, key.substring(key.indexOf('/') + 1)), inputs.get(key));
    }

    private static List<EditText> edits(StageUiTests.Screen s) {
        final List<EditText> out = new ArrayList<EditText>();
        for (View v : s.views()) if (v instanceof EditText && v.shown()) out.add((EditText) v);
        return out;
    }

    private static List<TrackView> pictures(StageUiTests.Screen s) {
        final List<TrackView> out = new ArrayList<TrackView>();
        for (View v : s.views()) if (v instanceof TrackView && v.shown()) out.add((TrackView) v);
        return out;
    }

    private static SeekBar slider(StageUiTests.Screen s) {
        for (View v : s.views()) if (v instanceof SeekBar && v.shown()) return (SeekBar) v;
        return null;
    }

    private static Intent lastActivity(StageUiTests.Rig r) {
        return r.act.startedActivities.isEmpty() ? null : r.act.startedActivities.get(r.act.startedActivities.size() - 1);
    }

    /** From the list of stages to the pin page, with the drive of that recording (written to the phone first). */
    private static StageUiTests.Rig onPinPage(File root, String tag, String key) throws Exception {
        final StageUiTests.Rig r = phone(root, tag);
        record(r, key);
        r.tap("Add a stage");
        r.tap(StageText.WAY_PIN_TITLE);
        r.tap(key.substring(key.indexOf('/') + 1));
        StageUiTests.spin(r);
        return r;
    }

    private static String ops(Canvas cv) {
        final StringBuilder b = new StringBuilder();
        for (String o : cv.ops) b.append(o).append(' ');
        return b.toString();
    }

    private static int count(Canvas cv, String prefix) {
        int n = 0;
        for (String o : cv.ops) if (o.startsWith(prefix)) n++;
        return n;
    }

    private static Canvas draw(TrackView v) {
        final Canvas cv = new Canvas();
        v.drawInto(cv);
        return cv;
    }

    private static double near(double a, double b) {
        return Math.abs(a - b);
    }

    /** A place as the app has it after it has been written out and read back: six decimals, as the boxes and the dialog show it. */
    private static double[] six(double[] ll) {
        final Coords.Result r = Coords.parse(Coords.format(ll[0], ll[1]));
        return new double[]{r.lat, r.lon};
    }

    private static android.content.Context ctx() {
        return StageAppTests.context(new File("."), new FakeContentResolver(), new FakePrefs());
    }

    // ------------------------------------------------------------------ all of it

    static void run(File root) {
        System.out.println("\n===== PN.UI: adding a stage by pins or by coordinates, on fake widgets =====");
        final Looper keep = Looper.getMainLooper();
        Looper.setMainLooper(Looper.newDetached());
        try {
            prepare();
            StageUiTests.reset();
            chooser(root);
            planForm(root);
            planDraft(root);
            plannedStage(root);
            picker(root);
            pinPage(root);
            pinCoordinates(root);
            pinSave(root);
            completePlan(root);
            busyRule(root);
            keepsState(root);
            pictureTouch();
            walk(root);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Check.ok("PN.UI the screen tests ran without an exception", false, t.toString());
        } finally {
            StageUiTests.reset();
            StageAppTests.CLIP.clip = null;
            StageAppTests.CLIP.refuse = null;
            Looper.setMainLooper(keep);
        }
    }

    // ------------------------------------------------------------------ ADD A STAGE: the three ways

    static void chooser(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = phone(root, "choose");
        final boolean homeButton = r.enabled("Add a stage") && !r.has("Mark a new stage");
        r.tap("Add a stage");
        final boolean page = r.exact("ADD A STAGE") && r.has(StageText.ADD_INTRO) && r.has(StageText.WAY_PIN_BODY) && r.has(StageText.WAY_PLAN_BODY)
                && r.has(StageText.WAY_PARK_BODY) && r.enabled(StageText.WAY_PIN_TITLE) && r.enabled(StageText.WAY_PLAN_TITLE)
                && r.enabled(StageText.WAY_PARK_TITLE);
        r.act.onBackPressed();
        final boolean back = r.exact("STAGES") && r.has("Add a stage");
        Check.ok("PN.UI1a ADD A STAGE opens a page with the three ways (drive first and pin afterwards, plan from map coordinates, mark by parking at both ends), each with a sentence saying what it is, and Back returns to the list",
                homeButton && page && back, r.screen().replace('\n', '|'));

        // not while a stage is armed or a recording is running: the three are off and press nothing
        r.tap("Add a stage");
        StageUiTests.live(StageLive.ARMED, true);
        r.refresh();
        final boolean armedOff = !r.enabled(StageText.WAY_PIN_TITLE) && !r.enabled(StageText.WAY_PLAN_TITLE) && !r.enabled(StageText.WAY_PARK_TITLE);
        final boolean pressed = r.tap(StageText.WAY_PIN_TITLE) || r.tap(StageText.WAY_PLAN_TITLE);
        StageLive.reset();
        Recorder.LIVE.recording = true;
        r.refresh();
        final boolean recOff = !r.enabled(StageText.WAY_PIN_TITLE) && !r.enabled(StageText.WAY_PLAN_TITLE) && !r.enabled(StageText.WAY_PARK_TITLE);
        Recorder.LIVE.recording = false;
        r.refresh();
        final boolean back2 = r.enabled(StageText.WAY_PIN_TITLE) && r.enabled(StageText.WAY_PLAN_TITLE) && r.enabled(StageText.WAY_PARK_TITLE);
        Check.ok("PN.UI1b with a stage armed or an ordinary recording running the three ways are off and pressing them does nothing; they come back when it is over",
                armedOff && !pressed && recOff && back2 && r.exact("ADD A STAGE") && r.allServiceIntents().isEmpty(), armedOff + " " + pressed + " " + recOff + " " + back2);
    }

    // ------------------------------------------------------------------ PLAN: coordinates typed or pasted

    static void planForm(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = phone(root, "plan");
        r.tap("Add a stage");
        final boolean opened = r.tap(StageText.WAY_PLAN_TITLE);
        List<EditText> e = edits(r);
        final boolean empty = opened && r.exact("PLAN A STAGE") && e.size() == 3 && e.get(0).getText().toString().startsWith("Stage ")
                && e.get(1).getText().length() == 0 && e.get(2).getText().length() == 0 && r.has(StageText.PLAN_HOW) && r.has(StageText.PLAN_NEXT)
                && r.has("Paste or type the place where the stage starts.") && r.has("Paste or type the place where the stage ends.")
                && !r.enabled("Save the plan") && !r.enabled("Show the start on a map") && !r.enabled("Show the finish on a map")
                && r.enabled("Paste the start") && r.enabled("Paste the finish") && r.enabled("Open Google Maps") && r.has("Swap start and finish");
        Check.ok("PN.UI2a the planning page: how to copy a place from Google Maps, a name box with a default name, an empty start box and finish box, each saying what to do; SAVE and the two map buttons are off until there is something to use",
                empty, r.screen().replace('\n', '|'));

        // two places typed as Google Maps writes them: each is read back in words, the distance is shown, SAVE comes on
        e.get(1).setText("3.5683210, 101.7461230");
        e.get(2).setText("3.5214530, 101.9087640");
        r.refresh();
        final double apart = Geo.distance(3.568321, 101.746123, 3.521453, 101.908764);
        final boolean read = r.has("Start: 3.568321, 101.746123") && r.has("Finish: 3.521453, 101.908764") && r.has(StageText.planApart(apart))
                && r.enabled("Save the plan") && r.enabled("Show the start on a map") && r.enabled("Show the finish on a map");
        // what is typed stays while the page refreshes
        r.refresh();
        r.refresh();
        final boolean kept = edits(r).get(1) == e.get(1) && e.get(1).getText().toString().equals("3.5683210, 101.7461230");
        Check.ok("PN.UI2b a place typed as 3.5683210, 101.7461230 is read back as 'Start: 3.568321, 101.746123' (and the finish likewise), the straight-line distance is shown, SAVE and the map buttons come on, and what is typed is not lost when the page refreshes",
                read && kept, r.screen().replace('\n', '|'));

        // a text that is not a place says why; two numbers the wrong way round are refused, never swapped; a place too near the other is refused
        e.get(1).setText("hello");
        r.refresh();
        final Coords.Result hello = Coords.parse("hello");
        final boolean words = !hello.ok && r.has("Start: " + hello.message) && !r.enabled("Save the plan") && !r.enabled("Show the start on a map");
        e.get(1).setText("101.7461230, 3.5683210");
        r.refresh();
        final Coords.Result swapped = Coords.parse("101.7461230, 3.5683210");
        final boolean noSwap = !swapped.ok && r.has("Start: " + swapped.message) && !r.enabled("Save the plan");
        e.get(1).setText("3.5683210, 101.7461230");
        e.get(2).setText("3.5692210, 101.7461230");
        r.refresh();
        final boolean tooClose = !r.enabled("Save the plan") && r.has("only 100 m apart");
        Check.ok("PN.UI2c a text that is not a place is answered with the reason (and SAVE stays off); a longitude where the latitude should be is refused, not swapped; two places 100 m apart are refused (a stage needs at least 150 m)",
                words && noSwap && tooClose, hello.message + " // " + swapped.message + " // " + r.screen().replace('\n', '|'));

        // PASTE reads the clipboard into the box; an empty clipboard, or one the phone will not let us read, says so and changes nothing
        e.get(2).setText("");
        StageAppTests.CLIP.clip = ClipData.newPlainText("", "  3.5214530, 101.9087640\n");
        r.tap("Paste the finish");
        final boolean pasted = e.get(2).getText().toString().equals("3.5214530, 101.9087640") && r.has("Finish: 3.521453, 101.908764");
        Toast.SHOWN.clear();
        StageAppTests.CLIP.clip = null;
        r.tap("Paste the start");
        final boolean emptyClip = Toast.SHOWN.contains(StageText.PASTE_EMPTY) && e.get(1).getText().toString().equals("3.5683210, 101.7461230");
        Toast.SHOWN.clear();
        StageAppTests.CLIP.clip = ClipData.newPlainText("", "3.1, 101.1");
        StageAppTests.CLIP.refuse = new SecurityException("not allowed (injected)");
        r.tap("Paste the start");
        final boolean refusedClip = Toast.SHOWN.contains(StageText.PASTE_EMPTY) && e.get(1).getText().toString().equals("3.5683210, 101.7461230");
        StageAppTests.CLIP.refuse = null;
        StageAppTests.CLIP.clip = ClipData.newPlainText("", "https://www.google.com/maps/place/Dash+Highway/@3.57,101.75,15z/data=!3m1!4b1!4m6!3m5!1s0x31cc:0xabc!8m2!3d3.5683210!4d101.7461230");
        r.tap("Paste the start");
        final boolean link = e.get(1).getText().toString().startsWith("https://www.google.com/maps/place/") && r.has("Start: 3.568321, 101.746123");
        Check.ok("PN.UI2d PASTE puts the clipboard into the box (trimmed); an empty clipboard, or one that throws, gives a toast and changes nothing; a pasted Google Maps link is read at the place it names, not at the middle of the map it opens on",
                pasted && emptyClip && refusedClip && link, pasted + " " + emptyClip + " " + refusedClip + " " + link);

        // the Google Maps buttons
        e.get(1).setText("3.5683210, 101.7461230");
        r.refresh();
        r.tap("Open Google Maps");
        final Intent gm = lastActivity(r);
        r.tap("Show the start on a map");
        final Intent st = lastActivity(r);
        r.tap("Show the finish on a map");
        final Intent fin = lastActivity(r);
        Check.ok("PN.UI2e OPEN GOOGLE MAPS opens https://www.google.com/maps; SHOW THE START ON A MAP and SHOW THE FINISH ON A MAP open a geo: link at the typed place with the label Start or Finish",
                gm != null && Intent.ACTION_VIEW.equals(gm.getAction()) && "https://www.google.com/maps".equals(String.valueOf(gm.getData()))
                        && st != null && String.valueOf(st.getData()).equals("geo:3.568321,101.746123?q=3.568321,101.746123(Start)")
                        && fin != null && String.valueOf(fin.getData()).equals("geo:3.521453,101.908764?q=3.521453,101.908764(Finish)"),
                (gm == null ? "-" : gm.getData()) + " " + (st == null ? "-" : st.getData()) + " " + (fin == null ? "-" : fin.getData()));

        // SWAP
        r.tap("Swap start and finish");
        final boolean swappedOk = e.get(1).getText().toString().equals("3.5214530, 101.9087640") && e.get(2).getText().toString().equals("3.5683210, 101.7461230")
                && r.has("Start: 3.521453, 101.908764");
        r.tap("Swap start and finish");

        // SAVE: the name is cleaned like a stage's, the plan is stored, the planned stage's page opens
        e.get(0).setText("Fraser, to; Bentong");
        r.tap("Save the plan");
        final List<Plan> plans = r.store.listPlans();
        final Plan p = plans.isEmpty() ? null : plans.get(0);
        final File folder = p == null ? null : new File(r.dir, "stages/" + p.id);
        Check.ok("PN.UI2f SWAP START AND FINISH swaps the boxes; SAVE THE PLAN stores one plan (name cleaned to Fraser to Bentong, both places, the time), as plan.txt without a stage.txt, and opens the planned stage's page with a notice",
                swappedOk && plans.size() == 1 && p.name().equals("Fraser to Bentong") && near(p.startLat, 3.568321) < 1e-7 && near(p.startLon, 101.746123) < 1e-7
                        && near(p.finishLat, 3.521453) < 1e-7 && near(p.finishLon, 101.908764) < 1e-7 && near(p.createdUtcMs, System.currentTimeMillis()) < 120000
                        && new File(folder, "plan.txt").isFile() && !new File(folder, "stage.txt").exists() && r.exact("Fraser to Bentong")
                        && r.has("Planned, not driven yet.") && r.has("Planned. Arm it when you are ready to drive the road once."),
                swappedOk + " " + plans.size() + " " + r.screen().replace('\n', '|'));

        // the list shows it
        r.act.onBackPressed();
        final String home = r.screen();
        final boolean listed = r.exact("PLANNED STAGES") && home.contains(StageText.planRow(p)) && !r.exact("YOUR STAGES") && !home.contains("No stages yet.");
        Check.ok("PN.UI2g the list of stages shows the plan under PLANNED STAGES, with its name and the distance, and no longer says there are no stages; BACK UP stays off (a plan is not in a backup)",
                listed && !r.enabled("Back up stages"), home.replace('\n', '|'));
    }

    static void planDraft(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = phone(root, "draft");
        r.tap("Add a stage");
        r.tap(StageText.WAY_PLAN_TITLE);
        List<EditText> e = edits(r);
        e.get(0).setText("KKB to Fraser");
        e.get(1).setText("3.5683210, 101.7461230");
        e.get(2).setText("3.52");
        // the person goes to Google Maps and comes back: the page is built again and the boxes are as they were
        r.act.onPause();
        r.act.onResume();
        r.refresh();
        e = edits(r);
        final boolean same = e.size() == 3 && e.get(0).getText().toString().equals("KKB to Fraser") && e.get(1).getText().toString().equals("3.5683210, 101.7461230")
                && e.get(2).getText().toString().equals("3.52") && r.exact("PLAN A STAGE");
        // or the system takes the app away meanwhile: a new screen on the same phone offers the draft again
        final StageUiTests.Rig again = new StageUiTests.Rig(r.dir, r.cr, r.prefs);
        again.tap("Add a stage");
        again.tap(StageText.WAY_PLAN_TITLE);
        final List<EditText> e2 = edits(again);
        final boolean draft = e2.size() == 3 && e2.get(0).getText().toString().equals("KKB to Fraser") && e2.get(1).getText().toString().equals("3.5683210, 101.7461230")
                && e2.get(2).getText().toString().equals("3.52");
        // leaving by Back keeps it too; saving a plan clears it
        again.act.onBackPressed();
        again.act.onBackPressed();
        again.tap("Add a stage");
        again.tap(StageText.WAY_PLAN_TITLE);
        final boolean afterBack = edits(again).get(1).getText().toString().equals("3.5683210, 101.7461230");
        edits(again).get(2).setText("3.5214530, 101.9087640");
        again.refresh();
        again.tap("Save the plan");
        again.act.onBackPressed();
        again.tap("Add a stage");
        again.tap(StageText.WAY_PLAN_TITLE);
        final List<EditText> e3 = edits(again);
        final boolean cleared = e3.get(1).getText().length() == 0 && e3.get(2).getText().length() == 0 && e3.get(0).getText().toString().startsWith("Stage ");
        Check.ok("PN.UI3 what is typed on the planning page survives the screen being paused and resumed (a trip to Google Maps), survives the app being taken away (a new screen on the same phone offers it again) and Back, and is gone once the plan is saved",
                same && draft && afterBack && cleared, same + " " + draft + " " + afterBack + " " + cleared);
    }

    // ------------------------------------------------------------------ a planned stage

    static void plannedStage(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r0 = phone(root, "planned");
        r0.store.savePlan(new Plan("st_plan1", "Fraser to Bentong", StageFixtures.CREATED_MS, 3.568321, 101.746123, 3.521453, 101.908764));
        final StageUiTests.Rig r = new StageUiTests.Rig(r0.dir, r0.cr, r0.prefs);
        r.prefs.edit().putBoolean("stage_ack", true).apply();
        final boolean row = r.tap("Fraser to Bentong");
        final String s = r.screen();
        final boolean page = row && r.exact("Fraser to Bentong") && s.contains("Planned, not driven yet.") && s.contains("Start   3.568321, 101.746123")
                && s.contains("Finish  3.521453, 101.908764") && s.contains(StageText.dist(Geo.distance(3.568321, 101.746123, 3.521453, 101.908764)) + " apart in a straight line")
                && r.has(StageText.PLAN_DRIVE_NOTE) && !r.has("Arm this planned stage") && r.enabled("Make the stage from a recording") && r.enabled("Change the places") && r.enabled("Rename")
                && r.enabled("Delete this plan") && !r.has("RUNS") && !r.has("Score the recordings") && StageUiTests.leak(s).isEmpty();
        Check.ok("PN.UI4a a planned stage's page: both places and the distance, what to do after driving it, MAKE THE STAGE FROM A RECORDING, CHANGE THE PLACES, map buttons, rename and delete; no runs, no scoring and nothing to arm (it has no road yet)",
                page, s.replace('\n', '|'));

        // MAKE THE STAGE FROM A RECORDING goes to the list of drives, for this plan; nothing is sent to the service
        r.tap("Make the stage from a recording");
        final boolean toList = r.exact("PICK A DRIVE") && r.act.startedForegroundServices.isEmpty() && r.act.startedServices.isEmpty();
        r.act.onBackPressed();
        Check.ok("PN.UI4b MAKE THE STAGE FROM A RECORDING opens the list of drives; a planned stage cannot be armed in this release, so nothing is sent to the service, and Back returns to the plan",
                toList && r.exact("Fraser to Bentong"), toList + " " + r.screen().replace('\n', '|'));

        // the map buttons
        r.tap("Show the start on a map");
        final Intent st = lastActivity(r);
        r.tap("Show the finish on a map");
        final Intent fin = lastActivity(r);
        Check.ok("PN.UI4c the map buttons open geo: links at the two places, labelled with the plan's name",
                st != null && String.valueOf(st.getData()).equals("geo:3.568321,101.746123?q=3.568321,101.746123(Fraser%20to%20Bentong%20start)")
                        && fin != null && String.valueOf(fin.getData()).equals("geo:3.521453,101.908764?q=3.521453,101.908764(Fraser%20to%20Bentong%20finish)"),
                (st == null ? "-" : st.getData()) + " " + (fin == null ? "-" : fin.getData()));

        // CHANGE THE PLACES: the page opens with the places in the boxes; saving keeps the id, the name and the time
        StageLive.reset();
        r.tap("Change the places");
        List<EditText> e = edits(r);
        final boolean prefilled = r.exact("CHANGE THE PLACES") && e.size() == 3 && e.get(0).getText().toString().equals("Fraser to Bentong")
                && e.get(1).getText().toString().equals("3.568321, 101.746123") && e.get(2).getText().toString().equals("3.521453, 101.908764")
                && r.enabled("Save the new places");
        e.get(2).setText("3.5200000, 101.9100000");
        e.get(0).setText("Fraser to Bentong via the pass");
        r.refresh();
        r.tap("Save the new places");
        final Plan after = r.store.loadPlan("st_plan1");
        final boolean saved = after != null && after.name().equals("Fraser to Bentong via the pass") && after.createdUtcMs == StageFixtures.CREATED_MS
                && near(after.startLat, 3.568321) < 1e-7 && near(after.finishLat, 3.52) < 1e-7 && near(after.finishLon, 101.91) < 1e-7
                && r.store.listPlans().size() == 1 && r.has("The places are saved.") && r.exact("Fraser to Bentong via the pass");
        Check.ok("PN.UI4d CHANGE THE PLACES opens the form with the plan's name and places, SAVE THE NEW PLACES rewrites the same plan (same id and time, new finish and name) and returns to its page",
                prefilled && saved, prefilled + " " + saved + " " + r.screen().replace('\n', '|'));

        // rename and delete
        r.tap("Rename");
        final AlertDialog d = AlertDialog.last();
        final EditText et = (EditText) d.view;
        final boolean start = et.getText().toString().equals("Fraser to Bentong via the pass");
        et.setText("Dash, Highway; north");
        d.click(DialogInterface.BUTTON_POSITIVE);
        r.refresh();
        final Plan renamed = r.store.loadPlan("st_plan1");
        final boolean rn = start && renamed != null && renamed.name().equals("Dash Highway north") && r.exact("Dash Highway north");
        r.tap("Delete this plan");
        final AlertDialog dd = AlertDialog.last();
        final boolean asked = dd.title.contains("Dash Highway north") && r.store.loadPlan("st_plan1") != null;
        dd.click(DialogInterface.BUTTON_NEGATIVE);
        final boolean kept = r.store.loadPlan("st_plan1") != null;
        r.tap("Delete this plan");
        AlertDialog.last().click(DialogInterface.BUTTON_POSITIVE);
        r.refresh();
        final boolean gone = r.store.loadPlan("st_plan1") == null && !new File(r.dir, "stages/st_plan1").exists() && r.exact("STAGES")
                && r.has("Deleted the plan Dash Highway north.") && r.has("No stages yet.");
        Check.ok("PN.UI4e RENAME cleans the name; DELETE THIS PLAN asks first (naming it), 'Keep it' keeps it, 'Delete' removes the folder and the list says so",
                rn && asked && kept && gone, rn + " " + asked + " " + kept + " " + gone + " " + r.screen().replace('\n', '|'));

        // while a stage is armed nothing about a plan can be changed
        r.store.savePlan(new Plan("st_plan2", "Dash Highway north", StageFixtures.CREATED_MS, 3.5, 101.5, 3.51, 101.52));
        final StageUiTests.Rig busy = new StageUiTests.Rig(r.dir, r.cr, r.prefs);
        busy.tap("Dash Highway north");
        StageUiTests.live(StageLive.ARMED, true);
        busy.refresh();
        final boolean off = !busy.enabled("Make the stage from a recording") && !busy.enabled("Change the places")
                && !busy.enabled("Rename") && !busy.enabled("Delete this plan");
        StageLive.reset();
        Check.ok("PN.UI4f while a stage is armed, MAKE THE STAGE FROM A RECORDING, CHANGE THE PLACES, RENAME and DELETE on a planned stage are all off", off, "");
    }

    // ------------------------------------------------------------------ PICK: which drive

    static String parkedCsv() {
        final List<Fix> f = new ArrayList<Fix>();
        for (int i = 0; i < 90; i++) f.add(new Fix(i, 3.5700, 101.6700 + (i % 3) * 1e-6, 0.1, -1.0, 4.0));
        return StageFixtures.csv(StageFixtures.CREATED_MS, "parked, no drive", f, new ArrayList<String>());
    }

    static void picker(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = phone(root, "pick");
        r.tap("Add a stage");
        r.tap(StageText.WAY_PIN_TITLE);
        final boolean none = r.exact("PICK A DRIVE") && r.has(StageText.PICK_INTRO) && r.has("There are no recordings on this phone yet.") && r.enabled("Pick a recording file");
        r.tap("Pick a recording file");
        final Intent pick = lastActivity(r);
        Check.ok("PN.UI5a PICK A DRIVE with no recording says so, and PICK A RECORDING FILE opens the document picker (any type, request 24)",
                none && pick != null && Intent.ACTION_OPEN_DOCUMENT.equals(pick.getAction()) && "*/*".equals(pick.getType()) && r.act.resultRequests.contains(24),
                r.screen().replace('\n', '|'));

        // three recordings, newest first, each with when and how big
        record(r, "road1/" + REC2);
        record(r, "road1/" + REC);
        StageAppTests.write(new File(r.dir, "session_20261013_113000.csv"), inputs.get("road1/session_20261013_113000.csv"));
        r.act.onBackPressed();
        r.tap("Plan from map coordinates");      // somewhere else, then back, so the page is built again
        r.act.onBackPressed();
        r.tap(StageText.WAY_PIN_TITLE);
        final String s = r.screen();
        final int a = s.indexOf("session_20261013_113000.csv"), b = s.indexOf(REC), c = s.indexOf(REC2);
        final long size = new File(r.dir, REC).length();
        final boolean order = a > 0 && b > a && c > b && s.contains(StageText.recordingRow(REC, size)) && s.contains("13 Oct 11:30   ") && s.contains("13 Oct 10:00   ");
        Check.ok("PN.UI5b the recordings on the phone are listed newest first, each with when it was made (from its name), its size and its name; the first line of each row is the date", order,
                s.replace('\n', '|'));

        // a recording with no drive in it is refused in words and the page stays
        StageAppTests.write(new File(r.dir, "session_20261014_090000.csv"), parkedCsv());
        r.act.onBackPressed();
        r.tap(StageText.WAY_PIN_TITLE);
        r.tap("session_20261014_090000.csv");
        final boolean reading = r.has("Reading the drive...");
        StageUiTests.spin(r);
        final boolean refused = reading && r.exact("PICK A DRIVE") && r.has("There is no drive in this recording") && r.enabled("Pick a recording file");
        Check.ok("PN.UI5c a recording in which the car hardly moved is read on a worker thread and refused with 'There is no drive in this recording', and the page for picking stays", refused,
                r.screen().replace('\n', '|'));

        // a drive picked from a file: the name comes from the provider
        r.cr.documents.put("content://docs/one", inputs.get("road1/" + REC).getBytes(UTF8));
        r.cr.displayNames.put("content://docs/one", "My Drive " + REC);
        r.act.onActivityResult(24, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/one")));
        StageUiTests.spin(r);
        final boolean file = r.exact("PLACE THE PINS") && r.has("Drive: My Drive " + REC + ", " + StageText.dist(track.length) + " long.");
        r.act.onBackPressed();
        final boolean backToPick = r.exact("PICK A DRIVE");
        // a file that cannot be opened, or is not a recording at all
        r.act.onActivityResult(24, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/missing")));
        StageUiTests.spin(r);
        final boolean missing = r.exact("PICK A DRIVE") && r.has("That file could not be read.");
        r.cr.documents.put("content://docs/junk", "this is not a recording\n".getBytes(UTF8));
        r.act.onActivityResult(24, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/junk")));
        StageUiTests.spin(r);
        final boolean junk = r.exact("PICK A DRIVE") && r.has("There is no drive in this recording");
        Check.ok("PN.UI5d a recording file picked with the document picker opens the pin page under the provider's name; Back returns to the list; a file that cannot be opened or holds no GPS rows is refused in words",
                file && backToPick && missing && junk, file + " " + backToPick + " " + missing + " " + junk + " " + r.screen().replace('\n', '|'));

        // while reading, nothing else can be pressed
        r.tap(REC);
        final boolean busyOff = !r.enabled("Pick a recording file") && !r.enabled(REC);
        StageUiTests.spin(r);
        Check.ok("PN.UI5e while a drive is being read the list and the file button are off", busyOff && r.exact("PLACE THE PINS"), busyOff + " " + r.screen().replace('\n', '|'));
    }

    // ------------------------------------------------------------------ PIN: the drive and the two pins

    static void pinPage(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = onPinPage(root, "pin", "road1/" + REC);
        final double len = track.length;
        final List<TrackView> pics = pictures(r);
        final SeekBar sb = slider(r);
        final TrackView ov = pics.size() == 2 ? pics.get(0) : null, cu = pics.size() == 2 ? pics.get(1) : null;
        final PinBuilder.Result base = PinBuilder.build("x", "x", 0L, track, PinBuilder.Pin.atPosition(20.0), PinBuilder.Pin.atPosition(len - 20.0));
        final double[] ll0 = track.latLonAt(20.0);
        final boolean first = r.exact("PLACE THE PINS") && ov != null && sb != null && r.has("Drive: " + REC + ", " + StageText.dist(len) + " long.")
                && r.has(StageText.PIN_HELP) && near(ov.startPin(), 20.0) < 1e-9 && near(ov.finishPin(), len - 20.0) < 1e-6 && ov.activePin() == 0
                && near(cu.startPin(), 20.0) < 1e-9 && sb.getMax() == (int) Math.floor(len) && sb.getProgress() == 20
                && r.has(StageText.pinPosition(0, 20.0, len, ll0[0], ll0[1])) && r.has(StageText.pinCheck(base)) && r.enabled("Save the stage")
                && r.enabled("Start pin") && r.enabled("Finish pin") && r.has("Open in Google Maps") && r.has("Set from coordinates");
        Check.ok("PN.UI6a the pin page for a drive: its name and length, two pictures, a slider as long as the drive, the start pin at 20 m and the finish pin 20 m from the end, the start pin active with its place in latitude and longitude, 'These pins make a stage of ...', SAVE on",
                first, r.screen().replace('\n', '|'));

        // the pictures: the drive in two lines (before and after, and between the pins), the pins; the close-up has the timing line and a scale
        final Canvas c1 = draw(ov), c2 = draw(cu);
        final boolean drawn = count(c1, "path:") == 2 && count(c1, "circle") == 3 && count(c1, "line") == 0
                && count(c2, "line") == 3 && count(c2, "circle") == 2 && c2.ops.contains("text:50 m") && count(c2, "path:") == 2;
        Check.ok("PN.UI6b the overview draws the drive as two lines (outside the stage, and between the pins) and the pins (3 circles: the active one ringed); the close-up draws the timing line across the road, a stroke forward, a scale of 50 m, and only the active pin (2 circles)",
                drawn, ops(c1) + " // " + ops(c2));

        // a touch on the line in the overview puts the active (start) pin there; a touch far from the line does nothing
        final float[] px = ov.pixelOf(2000.0);
        ov.touch(px[0], px[1]);
        final double s1 = ov.startPin();
        float fx = -1f, fy = -1f;
        for (int gx = 5; gx < 1000 && fx < 0f; gx += 45) for (int gy = 5; gy < 400; gy += 40) if (Double.isNaN(ov.sAtPixel(gx, gy))) { fx = gx; fy = gy; break; }
        final boolean farFound = fx >= 0f;
        ov.touch(fx, fy);
        final boolean touched = near(s1, 2000.0) < 1.0 && near(ov.startPin(), s1) < 1e-9 && farFound && near(cu.startPin(), s1) < 1e-9 && sb.getProgress() == Math.round(s1);
        r.refresh();
        final double[] ll1 = track.latLonAt(ov.startPin());
        Check.ok("PN.UI6c a touch on the line in the overview puts the start pin there (2000 m, within 1 m) in both pictures and on the slider and in the words; a touch far from the line changes nothing",
                touched && r.has(StageText.pinPosition(0, ov.startPin(), len, ll1[0], ll1[1])), "s1=" + s1 + " far=" + fx + "," + fy);

        // a touch on the close-up: the last metres
        final float[] cp = cu.pixelOf(s1 + 30.0);
        cu.touch(cp[0], cp[1]);
        Check.ok("PN.UI6d a touch on the close-up, 30 m further along the road, moves the pin by 30 m (to within 0.5 m)", near(ov.startPin(), s1 + 30.0) < 0.5, "s=" + ov.startPin());

        // the slider and the nudge buttons
        sb.userSeek(3000);
        final boolean seeked = near(ov.startPin(), 3000.0) < 1e-9 && near(cu.startPin(), 3000.0) < 1e-9;
        r.tap("+50 m");
        r.tap("-5 m");
        r.tap("+500 m");
        r.tap("-50 m");
        r.tap("+5 m");
        r.tap("-500 m");
        final boolean nudged = near(ov.startPin(), 3000.0 + 50 - 5 + 500 - 50 + 5 - 500) < 1e-9 && sb.getProgress() == 3000;
        r.tap("-500 m");
        r.tap("-500 m");
        r.tap("-500 m");
        r.tap("-500 m");
        r.tap("-500 m");
        r.tap("-500 m");
        r.tap("-500 m");
        final boolean clampedLow = ov.startPin() == 0.0 && sb.getProgress() == 0;
        r.refresh();
        final boolean nearEnd = !r.enabled("Save the stage") && r.has("The recording has to show the car arriving at the start");
        Check.ok("PN.UI6e the slider moves the active pin; the six nudge buttons add -500, -50, -5, +5, +50 and +500 m; a pin cannot go before the start of the drive; at 0 m the start is refused in words (the recording must show the car arriving) and SAVE is off",
                seeked && nudged && clampedLow && nearEnd, seeked + " " + nudged + " " + clampedLow + " " + nearEnd + " " + r.screen().replace('\n', '|'));

        // the finish pin: the same controls; a finish before the start, and a stage under 300 m, are refused in words
        r.tap("Finish pin");
        final boolean finishActive = ov.activePin() == 1 && sb.getProgress() == Math.round(ov.finishPin()) && r.has("FINISH PIN   ");
        sb.userSeek(1000);
        sb.userSeek(900);
        r.tap("Start pin");
        sb.userSeek(1500);
        r.refresh();
        final boolean wrongWay = !r.enabled("Save the stage") && r.has("The finish comes before the start on this drive.");
        sb.userSeek(800);
        r.tap("Finish pin");
        sb.userSeek(1000);
        r.refresh();
        final boolean tooShort = !r.enabled("Save the stage") && r.has("The road between the two pins is only 200 m. A stage needs at least 300 m.");
        sb.userSeek(4300);
        r.refresh();
        final boolean good = r.enabled("Save the stage") && r.has("These pins make a stage of 3.5 km.");
        Check.ok("PN.UI6f the finish pin has the same controls; a finish before the start and a stage under 300 m are refused with the reason and SAVE off; a good pair turns SAVE on and says how long the stage is",
                finishActive && wrongWay && tooShort && good, finishActive + " " + wrongWay + " " + tooShort + " " + good + " " + r.screen().replace('\n', '|'));

        // OPEN IN GOOGLE MAPS: the active pin's place
        r.tap("Start pin");
        r.tap("Open in Google Maps");
        final Intent m1 = lastActivity(r);
        r.tap("Finish pin");
        r.tap("Open in Google Maps");
        final Intent m2 = lastActivity(r);
        final double[] a = track.latLonAt(800.0), b = track.latLonAt(4300.0);
        Check.ok("PN.UI6g OPEN IN GOOGLE MAPS opens a geo: link at the place of the active pin, labelled Start pin or Finish pin",
                m1 != null && String.valueOf(m1.getData()).equals(String.format(java.util.Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Start%%20pin)", a[0], a[1], a[0], a[1]))
                        && m2 != null && String.valueOf(m2.getData()).equals(String.format(java.util.Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Finish%%20pin)", b[0], b[1], b[0], b[1])),
                (m1 == null ? "-" : m1.getData()) + " " + (m2 == null ? "-" : m2.getData()));
    }

    static void pinCoordinates(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = onPinPage(root, "pincoord", "road1/" + REC);
        final TrackView ov = pictures(r).get(0);
        final double len = track.length;

        // the dialog starts with the pin's own place; a place from the clipboard if there is one on it
        r.tap("Set from coordinates");
        AlertDialog d = AlertDialog.last();
        EditText et = (EditText) d.view;
        final double[] cur = track.latLonAt(20.0);
        final boolean ownPlace = et.getText().toString().equals(Coords.format(cur[0], cur[1])) && d.title.equals("Start pin from coordinates") && d.positive.equals("Set");
        d.click(DialogInterface.BUTTON_NEGATIVE);
        final double[] p1500 = PinTests.place(road, 1500.0, 12.0);          // 12 m to the left of the road, 14 m from the lane the car was in
        StageAppTests.CLIP.clip = ClipData.newPlainText("", Coords.format(p1500[0], p1500[1]));
        r.tap("Set from coordinates");
        d = AlertDialog.last();
        et = (EditText) d.view;
        final boolean clipPlace = et.getText().toString().equals(Coords.format(p1500[0], p1500[1]));
        d.click(DialogInterface.BUTTON_POSITIVE);
        final double[] q1500 = six(p1500);
        final List<Track.Pass> ps = track.passes(q1500[0], q1500[1]);
        final boolean moved = ps.size() == 1 && near(ov.startPin(), ps.get(0).s) < 1e-6 && ps.get(0).offsetM > 5.0
                && r.has("That place is " + StageText.dist(ps.get(0).offsetM) + " from the road you drove. The pin is put on the road, at the nearest point.");
        Check.ok("PN.UI7a SET FROM COORDINATES: the dialog starts with the pin's own place, or the place on the clipboard; SET puts the pin at the nearest point of the drive and says when the place was more than 5 m from the road",
                ownPlace && clipPlace && moved, ownPlace + " " + clipPlace + " " + moved + " " + r.screen().replace('\n', '|'));

        // a text that is not a place, and a place the drive never goes near: refused in words, the pin stays
        final double was = ov.startPin();
        StageAppTests.CLIP.clip = null;
        Toast.SHOWN.clear();
        r.tap("Set from coordinates");
        d = AlertDialog.last();
        ((EditText) d.view).setText("banana");
        d.click(DialogInterface.BUTTON_POSITIVE);
        final boolean bad = Toast.SHOWN.contains(Coords.parse("banana").message) && ov.startPin() == was;
        final double[] far = PinTests.place(road, 1500.0, 900.0);
        Toast.SHOWN.clear();
        r.tap("Set from coordinates");
        d = AlertDialog.last();
        ((EditText) d.view).setText(Coords.format(far[0], far[1]));
        d.click(DialogInterface.BUTTON_POSITIVE);
        final boolean away = !Toast.SHOWN.isEmpty() && Toast.SHOWN.get(0).startsWith("That place is not on this drive: the road you drove comes no closer than ")
                && ov.startPin() == was;
        Check.ok("PN.UI7b a text that is not a place, and a place 900 m from the road, are refused in words (toast) and the pin does not move", bad && away, Toast.SHOWN + " " + was + " " + ov.startPin());

        // the finish pin
        r.tap("Finish pin");
        final double[] p3000 = PinTests.place(road, 3000.0, 0.0);
        r.tap("Set from coordinates");
        d = AlertDialog.last();
        final boolean title = d.title.equals("Finish pin from coordinates");
        ((EditText) d.view).setText(Coords.format(p3000[0], p3000[1]));
        d.click(DialogInterface.BUTTON_POSITIVE);
        final double[] q3000 = six(p3000);
        final List<Track.Pass> pf = track.passes(q3000[0], q3000[1]);
        r.refresh();
        Check.ok("PN.UI7c the finish pin is set the same way (and the stage they make is checked at once)", title && pf.size() == 1 && near(ov.finishPin(), pf.get(0).s) < 1e-6 && !r.has("That place is ")
                && r.has("These pins make a stage of "), r.screen().replace('\n', '|'));

        // a road driven twice: the start pin takes the first pass; the finish pin takes the first pass after the start
        final StageUiTests.Rig t = onPinPage(root, "pintwice", "road1/" + REC2);
        final TrackView ov2 = pictures(t).get(0);
        final double[] p1000 = PinTests.place(road, 1000.0, 0.0), p3000b = PinTests.place(road, 3000.0, 0.0);
        t.tap("Set from coordinates");
        d = AlertDialog.last();
        ((EditText) d.view).setText(Coords.format(p3000b[0], p3000b[1]));
        d.click(DialogInterface.BUTTON_POSITIVE);
        final double[] q3000b = six(p3000b), q1000 = six(p1000);
        final List<Track.Pass> a3 = track2.passes(q3000b[0], q3000b[1]);
        final boolean startFirst = a3.size() == 2 && near(ov2.startPin(), a3.get(0).s) < 1e-6;
        t.tap("Finish pin");
        t.tap("Set from coordinates");
        d = AlertDialog.last();
        ((EditText) d.view).setText(Coords.format(p1000[0], p1000[1]));
        d.click(DialogInterface.BUTTON_POSITIVE);
        final List<Track.Pass> a1 = track2.passes(q1000[0], q1000[1]);
        final boolean finishAfter = a1.size() == 2 && a1.get(0).s < a3.get(0).s && a1.get(1).s > a3.get(0).s && near(ov2.finishPin(), a1.get(1).s) < 1e-6;
        Check.ok("PN.UI7d on a road driven twice the start pin takes the first pass of the place, and the finish pin the first pass after the start pin (here the second drive, because the first passes the place before the start)",
                startFirst && finishAfter, startFirst + " " + finishAfter + " " + a3.size() + " " + a1.size());
    }

    // ------------------------------------------------------------------ SAVE

    static void pinSave(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = onPinPage(root, "pinsave", "road1/" + REC);
        final TrackView ov = pictures(r).get(0);
        final SeekBar sb = slider(r);
        final double len = track.length;
        sb.userSeek(900);
        r.tap("Finish pin");
        sb.userSeek(4300);
        r.refresh();
        final EditText name = edits(r).get(0);
        final boolean startsWithDefault = name.getText().toString().startsWith("Stage ");
        name.setText("Fraser, to; Bentong");
        r.tap("Save the stage");
        final boolean saving = r.has("Saving the stage...");
        StageUiTests.spin(r);
        final List<Stage> stages = r.store.list();
        final Stage st = stages.isEmpty() ? null : stages.get(0);
        final PinBuilder.Result want = PinBuilder.build(st == null ? "x" : st.id, "Fraser to Bentong", st == null ? 0L : st.createdUtcMs, track,
                PinBuilder.Pin.atPosition(900.0), PinBuilder.Pin.atPosition(4300.0));
        final boolean same = st != null && StageFiles.formatStage(st).equals(StageFiles.formatStage(want.stage));
        final List<RunResult> runs = st == null ? new ArrayList<RunResult>() : r.store.runs(st.id);
        final List<RunResult> offline = st == null ? new ArrayList<RunResult>() : Scorer.scoreAll(st, read(REC, "road1/" + REC).fixes, REC, 0.0);
        final boolean scored = runs.size() == 1 && !offline.isEmpty() && runs.get(0).finished && near(runs.get(0).totalS, offline.get(0).totalS) < 0.05;
        final String s = r.screen();
        Check.ok("PN.UI8a SAVE THE STAGE with the pins at 900 m and 4300 m: a worker thread saves it, the stage is exactly what PinBuilder makes from those pins (name cleaned to Fraser to Bentong), the drive is scored as its first run, the stage's page opens with 'Saved ... Found 1 run of ... 1 new.'",
                startsWithDefault && saving && stages.size() == 1 && same && scored && r.exact("Fraser to Bentong")
                        && s.contains("Saved Fraser to Bentong (" + StageText.dist(want.stage.lengthM()) + "). Read 1 recording. Found 1 run of Fraser to Bentong: 1 new.")
                        && s.contains("Arm this stage") && s.contains(StageText.runLine(runs.get(0), runs.get(0), true)),
                startsWithDefault + " " + saving + " " + stages.size() + " " + same + " " + scored + " " + s.replace('\n', '|'));

        // the lines are exactly at the pins: the stage's start line is where the 900 m pin was
        final double[] a = track.latLonAt(900.0), b = track.latLonAt(4300.0);
        final boolean marks = st != null && near(st.startMarkLat, a[0]) < 1e-6 && near(st.startMarkLon, a[1]) < 1e-6 && near(st.finishMarkLat, b[0]) < 1e-6
                && near(st.finishMarkLon, b[1]) < 1e-6;
        final String list;
        r.act.onBackPressed();
        list = r.screen();
        Check.ok("PN.UI8b the stage's marks are the places of the pins; it appears in the list with its length and one run; the drive picked has no stage left behind it (the picking pages are gone)",
                marks && list.contains("Fraser to Bentong\n" + StageText.dist(want.stage.lengthM()) + "   1 run   best ") && !list.contains("PLANNED STAGES"), list.replace('\n', '|'));

        // the drive of a file picked from the document picker is read again to be scored
        final StageUiTests.Rig p = phone(root, "pinsave2");
        p.cr.documents.put("content://docs/one", inputs.get("road1/" + REC).getBytes(UTF8));
        p.cr.displayNames.put("content://docs/one", REC);
        p.tap("Add a stage");
        p.tap(StageText.WAY_PIN_TITLE);
        p.act.onActivityResult(24, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/one")));
        StageUiTests.spin(p);
        p.tap("Save the stage");
        StageUiTests.spin(p);
        final List<Stage> ps = p.store.list();
        final boolean viaUri = ps.size() == 1 && p.store.runs(ps.get(0).id).size() == 1 && p.screen().contains("Read 1 recording. Found 1 run of ");
        // ... and if it cannot be read again, the stage is still saved and the page says what to do
        final StageUiTests.Rig q = phone(root, "pinsave3");
        q.cr.documents.put("content://docs/two", inputs.get("road1/" + REC).getBytes(UTF8));
        q.cr.displayNames.put("content://docs/two", REC);
        q.tap("Add a stage");
        q.tap(StageText.WAY_PIN_TITLE);
        q.act.onActivityResult(24, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://docs/two")));
        StageUiTests.spin(q);
        q.cr.documents.clear();
        q.tap("Save the stage");
        StageUiTests.spin(q);
        final boolean gone = q.store.list().size() == 1 && q.store.runs(q.store.list().get(0).id).isEmpty()
                && q.screen().contains("The drive could not be read again to score it. Use Score a recording file on the stage page.");
        Check.ok("PN.UI8c a drive picked from the document picker is read again and scored on SAVE; if the provider no longer gives it, the stage is still saved and the page says to use Score a recording file",
                viaUri && gone, viaUri + " " + gone + " " + q.screen().replace('\n', '|'));
    }

    // ------------------------------------------------------------------ completing a plan from a drive

    static void completePlan(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r0 = phone(root, "complete");
        final double[] a = PinTests.place(road, 300.0, 3.0), b = PinTests.place(road, 4300.0, -3.0);
        r0.store.savePlan(new Plan("st_plan7", "Mountain pass", StageFixtures.CREATED_MS, a[0], a[1], b[0], b[1]));
        record(r0, "road1/" + REC);
        final StageUiTests.Rig r = new StageUiTests.Rig(r0.dir, r0.cr, r0.prefs);
        r.prefs.edit().putBoolean("stage_ack", true).apply();
        r.tap("Mountain pass");
        r.tap("Make the stage from a recording");
        final boolean pick = r.exact("PICK A DRIVE") && r.has("Making Mountain pass from a drive. " + StageText.PICK_INTRO);
        r.tap(REC);
        StageUiTests.spin(r);
        final TrackView ov = pictures(r).get(0);
        final Plan stored = r.store.loadPlan("st_plan7");
        final List<Track.Pass> pa = track.passes(stored.startLat, stored.startLon), pb = track.passes(stored.finishLat, stored.finishLon);
        final boolean pins = r.exact("PLACE THE PINS") && pa.size() == 1 && pb.size() == 1 && near(ov.startPin(), pa.get(0).s) < 1e-6 && near(ov.finishPin(), pb.get(0).s) < 1e-6
                && edits(r).get(0).getText().toString().equals("Mountain pass") && r.enabled("Save the stage");
        Check.ok("PN.UI9a MAKE THE STAGE FROM A RECORDING on a planned stage: the drive is picked, and the pin page opens with the pins at the plan's places (the nearest points of the drive) and the plan's name",
                pick && pins, pick + " " + pins + " " + r.screen().replace('\n', '|'));

        r.tap("Save the stage");
        StageUiTests.spin(r);
        final Stage st = r.store.load("st_plan7");
        final boolean made = st != null && st.name().equals("Mountain pass") && r.store.loadPlan("st_plan7") == null && r.store.listPlans().isEmpty()
                && !new File(r.dir, "stages/st_plan7/plan.txt").exists() && new File(r.dir, "stages/st_plan7/stage.txt").isFile() && r.store.runs("st_plan7").size() == 1
                && r.exact("Mountain pass") && r.has("Arm this stage") && !r.has("Arm this planned stage");
        r.act.onBackPressed();
        final boolean list = r.screen().contains("YOUR STAGES") && !r.screen().contains("PLANNED STAGES");
        Check.ok("PN.UI9b SAVE THE STAGE makes the stage under the plan's id: stage.txt is there, plan.txt is gone, the plan is no longer listed, the drive is its first run, and the page is an ordinary stage's page",
                made && list, made + " " + list + " " + r.screen().replace('\n', '|'));

        // a plan whose places are not on the drive: the pins start at the ends of the drive and the page says so
        final StageUiTests.Rig q0 = phone(root, "complete2");
        q0.store.savePlan(new Plan("st_plan8", "Elsewhere", StageFixtures.CREATED_MS, 5.0, 100.0, 5.1, 100.1));
        record(q0, "road1/" + REC);
        final StageUiTests.Rig q = new StageUiTests.Rig(q0.dir, q0.cr, q0.prefs);
        q.tap("Elsewhere");
        q.tap("Make the stage from a recording");
        q.tap(REC);
        StageUiTests.spin(q);
        final TrackView ov2 = pictures(q).get(0);
        final boolean ends = near(ov2.startPin(), 20.0) < 1e-9 && near(ov2.finishPin(), track.length - 20.0) < 1e-6
                && q.has("The start place of the plan is not on this drive, so the start pin is at the beginning of it.")
                && q.has("The finish place of the plan is not on this drive, so the finish pin is at the end of it.");
        // Back from the pin page returns to the picker of the plan, Back from there to the plan
        q.act.onBackPressed();
        final boolean toPick = q.exact("PICK A DRIVE") && q.has("Making Elsewhere from a drive.");
        q.act.onBackPressed();
        final boolean toPlan = q.exact("Elsewhere") && q.has("Planned, not driven yet.");
        Check.ok("PN.UI9c a plan whose places are not on the drive starts the pins at the two ends of the drive and says so; Back goes pin page, picker, planned stage",
                ends && toPick && toPlan, ends + " " + toPick + " " + toPlan);
    }

    // ------------------------------------------------------------------ the rule for open roads, and busy phones

    static void busyRule(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = onPinPage(root, "busy", "road1/" + REC);
        final String idle = r.screen();
        final boolean noNumbers = StageUiTests.leak(idle).isEmpty();
        // the phone is armed and the car is moving: SAVE is off, the pages show no time of a run
        StageUiTests.live(StageLive.RUNNING, false);
        r.refresh();
        final boolean off = !r.enabled("Save the stage") && StageUiTests.leak(r.screen()).isEmpty();
        r.tap("Save the stage");
        final boolean nothing = r.store.list().isEmpty() && r.allServiceIntents().isEmpty();
        StageLive.reset();
        r.refresh();
        final boolean back = r.enabled("Save the stage");
        Recorder.LIVE.recording = true;
        r.refresh();
        final boolean recOff = !r.enabled("Save the stage");
        Recorder.LIVE.recording = false;
        r.refresh();
        Check.ok("PN.UI10a the pin page shows no time of any run, and SAVE THE STAGE is off (and pressing it does nothing) while a stage is armed or being recorded and while an ordinary recording runs; it comes back afterwards",
                noNumbers && off && nothing && back && recOff && r.enabled("Save the stage"), noNumbers + " " + off + " " + nothing + " " + back + " " + recOff);

        // the same for the planning page and the planned stage's page, in every state of the phone
        final StageUiTests.Rig p = phone(root, "busy2");
        p.store.savePlan(new Plan("st_plan9", "Dash Highway", StageFixtures.CREATED_MS, 3.5, 101.5, 3.51, 101.52));
        final StageUiTests.Rig q = new StageUiTests.Rig(p.dir, p.cr, p.prefs);
        q.tap("Add a stage");
        q.tap(StageText.WAY_PLAN_TITLE);
        List<EditText> e = edits(q);
        e.get(1).setText("3.5683210, 101.7461230");
        e.get(2).setText("3.5214530, 101.9087640");
        q.refresh();
        boolean ok = q.enabled("Save the plan");
        final int[] states = {StageLive.ARMED, StageLive.RECORDING, StageLive.RUNNING, StageLive.FINISHING, StageLive.SAVING, StageLive.MARK_START, StageLive.MARK_DRIVE};
        final StringBuilder bad = new StringBuilder();
        for (int st : states) {
            StageUiTests.live(st, false);
            q.refresh();
            final String sc = q.screen();
            // a marking drive takes the screen to the marking page; the others leave the planning page where it is, with SAVE off
            final boolean marking = st == StageLive.MARK_START || st == StageLive.MARK_DRIVE;
            if (!StageUiTests.leak(sc).isEmpty()) bad.append("leak in ").append(st).append(' ');
            if (!marking && q.enabled("Save the plan")) bad.append("save on in ").append(st).append(' ');
            if (marking && !q.has("Step ")) bad.append("no marking page in ").append(st).append(' ');
            StageLive.reset();
            q.refresh();
            if (marking) {
                // back to the planning page for the next state
                q.act.onBackPressed();
                q.tap("Add a stage");
                q.tap(StageText.WAY_PLAN_TITLE);
                e = edits(q);
                e.get(1).setText("3.5683210, 101.7461230");
                e.get(2).setText("3.5214530, 101.9087640");
                q.refresh();
            }
        }
        ok &= bad.length() == 0;
        Check.ok("PN.UI10b the planning page shows no number about a run in any state of the phone, and SAVE THE PLAN is off while a stage is armed, recording or being saved", ok, bad.toString());
    }

    // ------------------------------------------------------------------ the pin page keeps its state

    static void keepsState(File root) throws Exception {
        StageUiTests.reset();
        final StageUiTests.Rig r = onPinPage(root, "keeps", "road1/" + REC);
        slider(r).userSeek(2500);
        r.tap("Finish pin");
        slider(r).userSeek(4100);
        edits(r).get(0).setText("Dash Highway north");
        r.act.onPause();
        r.act.onResume();
        r.refresh();
        final List<TrackView> pics = pictures(r);
        final boolean kept = pics.size() == 2 && near(pics.get(0).startPin(), 2500.0) < 1e-9 && near(pics.get(0).finishPin(), 4100.0) < 1e-9
                && pics.get(0).activePin() == 1 && slider(r).getProgress() == 4100 && edits(r).get(0).getText().toString().equals("Dash Highway north")
                && r.exact("PLACE THE PINS") && r.enabled("Save the stage");
        // refreshing every 300 ms does not rebuild the page
        final TextView before = r.find("FINISH PIN");
        for (int i = 0; i < 5; i++) r.refresh();
        final boolean stable = before == r.find("FINISH PIN") && pictures(r).get(0) == pics.get(0);
        Check.ok("PN.UI11 the pin page keeps the pins, the one being moved and the name when the screen is paused and resumed (a trip to Google Maps), and does not rebuild itself on every refresh",
                kept && stable, kept + " " + stable);
    }

    // ------------------------------------------------------------------ the picture alone

    /** A drive out along a straight road and back along the same road: every place on it is passed twice. */
    static Track outAndBack() {
        final Geo g = new Geo(3.5, 101.5);
        final List<Fix> f = new ArrayList<Fix>();
        double t = 0.0;
        for (int i = 0; i <= 200; i++) f.add(new Fix(t++, g.lat(0.0), g.lon(i * 5.0), 10.0, 90.0, 3.0));
        for (int i = 199; i >= 0; i--) f.add(new Fix(t++, g.lat(0.0), g.lon(i * 5.0), 10.0, 270.0, 3.0));
        return Track.of(f);
    }

    static void pictureTouch() {
        final Track t = outAndBack();
        final TrackView v = new TrackView(ctx(), TrackView.OVERVIEW, 2f);
        final List<Double> picked = new ArrayList<Double>();
        v.setListener(new TrackView.Listener() {
            @Override public void onPick(double s) { picked.add(s); }
        });
        v.set(t);
        v.setPins(100.0, 1900.0, 0);
        final float[] out = v.pixelOf(500.0), back = v.pixelOf(1500.0);
        final boolean samePlace = Math.abs(out[0] - back[0]) < 0.5f && Math.abs(out[1] - back[1]) < 0.5f;
        v.touch(out[0], out[1]);
        v.touch(back[0], back[1]);
        final boolean firstPass = picked.size() == 2 && near(picked.get(0), 500.0) < 1.0 && near(picked.get(1), 500.0) < 1.0;
        picked.clear();
        final boolean tooFar = !v.touch(500f, 5f) || picked.isEmpty();                  // 195 px from the line: nothing picked
        final boolean ignored = picked.isEmpty() && Double.isNaN(v.sAtPixel(500f, 5f));
        // the pins are drawn where they are; with no drive, or no size, nothing is drawn and nothing is raised
        final Canvas cv = draw(v);
        final TrackView none = new TrackView(ctx(), TrackView.CLOSEUP, 2f);
        none.setPins(1.0, 2.0, 0);
        final Canvas c0 = draw(none);
        v.width = 0;
        final Canvas c1 = draw(v);
        final boolean noTouch = !v.touch(10f, 10f);
        Check.ok("PN.UI12 the picture alone: on a road driven out and back both passes are drawn at the same place and a touch there picks the first; a touch 195 px from the line picks nothing; with no drive, or no width, nothing is drawn, nothing is picked and nothing is raised",
                samePlace && firstPass && tooFar && ignored && count(cv, "circle") == 3 && c0.ops.isEmpty() && c1.ops.isEmpty() && noTouch,
                samePlace + " " + firstPass + " " + tooFar + " " + ignored + " " + ops(cv) + " " + c0.ops + " " + c1.ops + " " + noTouch);
    }

    // ------------------------------------------------------------------ a random walk over the new screens

    /** What a person might type, paste or get wrong in a box that wants a place or a name. */
    private static final String[] TEXTS = {
        "", "hello", "3.5683210, 101.7461230", "3.5214530, 101.9087640", "101.7461230, 3.5683210", "3.5683210 N, 101.7461230 E",
        "3\u00b034'05.9\"N 101\u00b044'46.0\"E", "geo:3.568321,101.746123", "https://goo.gl/maps/abc", "0, 0", "91, 10",
        "https://www.google.com/maps/@3.5683,101.7461,15z", "3.5683210, 101.7461230, 5", "  3.5, 101.7  ", "3.5683210 101.7461230",
        "Dash Highway", "Fraser, to; Bentong", "x", "\u00e9\u00e8 \u2212", "3.5,101.5", "3.5214530, 101.9087640\n",
        "a very long name that goes on and on and on and on and on and on and on and on and on and on and on and on and on and on"};

    static void walk(File root) throws Exception {
        StageUiTests.reset();
        final Random rnd = new Random(20261014L);
        final StageUiTests.Rig r = phone(root, "pnwalk");
        record(r, "road1/" + REC);
        record(r, "road1/" + REC2);
        StageAppTests.write(new File(r.dir, "session_20261014_090000.csv"), parkedCsv());
        r.store.savePlan(new Plan("st_plan3", "Dash Highway north", StageFixtures.CREATED_MS, 3.5683210, 101.7461230, 3.5214530, 101.9087640));
        r.refresh();
        final String[] skip = {"Back up", "Score", "Restore", "Share", "Battery", "Beeps"};      // jobs and settings that have their own tests
        final String[] pages = {"ADD A STAGE", "PLAN A STAGE", "CHANGE THE PLACES", "PICK A DRIVE", "PLACE THE PINS"};
        final int[] seen = new int[pages.length];
        int steps = 0, taps = 0, typed = 0, touches = 0, seeks = 0, answered = 0, hidden = 0, planned = 0, mostStages = 0, mostPlans = 0, saved = 0;
        final StringBuilder bad = new StringBuilder();
        for (int n = 0; n < 4000 && bad.length() == 0; n++) {
            if (rnd.nextInt(14) == 0) {                                                 // the world changes under the screen (half the time to a quiet phone)
                final int pick = rnd.nextBoolean() ? 0 : rnd.nextInt(11);
                StageUiTests.reset();
                StageLive.state = pick <= 8 ? pick : (pick == 9 ? 99 : -3);
                StageLive.stopped = rnd.nextBoolean();
                StageLive.canMark = rnd.nextBoolean();
                StageLive.stageName = "Mountain road";
                StageLive.markHint = rnd.nextBoolean() ? "Keep still while the GPS settles." : "";
                StageLive.message = rnd.nextBoolean() ? "Run finished. Saving." : "";
                StageLive.error = rnd.nextInt(4) == 0 ? "Something went wrong." : "";
                if (rnd.nextInt(10) == 0) Recorder.LIVE.recording = !Recorder.LIVE.recording;
            }
            if (r.store.listPlans().isEmpty() && rnd.nextInt(6) == 0) {
                r.store.savePlan(new Plan("st_plan" + (4 + n), "Dash Highway " + n, StageFixtures.CREATED_MS, 3.5683210, 101.7461230, 3.5214530, 101.9087640));
            }
            final int what = rnd.nextInt(20);
            final boolean leave = rnd.nextInt(6) == 0;                                  // people stay on a page for a while before they go back
            final List<View> hit = new ArrayList<View>();
            for (View v : r.views()) {
                if (!(v instanceof TextView) || !v.shown() || !v.isEnabled() || !v.clickable()) continue;
                final String t = ((TextView) v).getText().toString();
                boolean no = !leave && (t.equals("Back") || t.equals("Track Logger"));
                for (String a : skip) if (t.startsWith(a)) no = true;
                if (!no) hit.add(v);
            }
            if (what <= 9 && !hit.isEmpty()) {
                hit.get(rnd.nextInt(hit.size())).tap();
                taps++;
            } else if (what == 10 || what == 11) {
                final List<EditText> e = edits(r);
                if (!e.isEmpty()) {
                    e.get(rnd.nextInt(e.size())).setText(TEXTS[rnd.nextInt(TEXTS.length)]);
                    typed++;
                }
            } else if (what == 12 || what == 13) {
                final SeekBar sb = slider(r);
                if (sb != null) {
                    sb.userSeek(rnd.nextInt(sb.getMax() + 1));
                    seeks++;
                }
            } else if (what >= 14 && what <= 16) {
                final List<TrackView> t = pictures(r);
                if (!t.isEmpty()) {
                    final TrackView v = t.get(rnd.nextInt(t.size()));
                    if (rnd.nextBoolean() && v.startPin() >= 0.0) {
                        final float[] px = v.pixelOf(rnd.nextBoolean() ? v.startPin() : v.finishPin());
                        v.touch(px[0] + rnd.nextInt(41) - 20, px[1] + rnd.nextInt(41) - 20);
                    } else {
                        v.touch(rnd.nextInt(1000), rnd.nextInt(400));
                    }
                    touches++;
                }
            } else if (what == 17) {
                if (leave) r.act.onBackPressed();
                if (r.act.finished) r.act.finished = false;
            } else if (what == 18) {
                r.act.onPause();
                r.act.onResume();
            } else {
                final String clip = TEXTS[rnd.nextInt(TEXTS.length + 1) % TEXTS.length];
                StageAppTests.CLIP.clip = rnd.nextInt(5) == 0 ? null : ClipData.newPlainText("", clip);
                StageAppTests.CLIP.refuse = rnd.nextInt(8) == 0 ? new SecurityException("not allowed (injected)") : null;
            }
            // a dialog that was opened is answered, sometimes with something typed in it
            final AlertDialog dlg = AlertDialog.last();
            if (dlg != null && !dlg.dismissed) {
                if (dlg.view instanceof EditText && rnd.nextBoolean()) ((EditText) dlg.view).setText(TEXTS[rnd.nextInt(TEXTS.length)]);
                dlg.click(rnd.nextInt(3) == 0 ? DialogInterface.BUTTON_NEGATIVE : DialogInterface.BUTTON_POSITIVE);
                answered++;
            }
            AlertDialog.SHOWN.clear();
            Toast.SHOWN.clear();
            StageUiTests.spin(r);
            steps++;
            final String sc = r.screen();
            if (sc.contains("Screen problem")) bad.append("step ").append(n).append(": ").append(sc.replace('\n', '|'));
            if (!StageLive.numbersAllowed()) {
                hidden++;
                if (StageUiTests.leak(sc).length() > 0) bad.append("step ").append(n).append(" state=").append(StageLive.state).append(": ").append(StageUiTests.leak(sc));
            }
            for (int k = 0; k < pages.length; k++) if (r.exact(pages[k])) seen[k]++;
            if (r.has("Planned, not driven yet.")) planned++;
            // a folder holds a plan or a stage, never both; pins stay on the drive; a stage that was made is a stage that can be read
            final File[] dirs = new File(r.dir, "stages").listFiles();
            if (dirs != null) for (File d : dirs) {
                if (new File(d, "plan.txt").isFile() && new File(d, "stage.txt").isFile()) bad.append("step ").append(n).append(": plan and stage in ").append(d.getName());
            }
            final SeekBar sb = slider(r);
            for (TrackView v : pictures(r)) {
                if (sb != null && (v.startPin() < 0.0 || v.finishPin() < 0.0 || v.startPin() > sb.getMax() + 1.0 || v.finishPin() > sb.getMax() + 1.0)) {
                    bad.append("step ").append(n).append(": a pin is off the drive ").append(v.startPin()).append(' ').append(v.finishPin());
                }
            }
            mostStages = Math.max(mostStages, r.store.list().size());
            mostPlans = Math.max(mostPlans, r.store.listPlans().size());
            if (r.exact("Saved") || r.has("Saved ")) saved++;
        }
        boolean allPages = true;
        for (int k = 0; k < pages.length; k++) allPages &= seen[k] >= 30;
        Check.ok("PN.UI13 a random walk of " + steps + " steps over the new screens (presses on whatever could be pressed, good and bad text typed, pasted and refused by the clipboard, touches near the pins and far from them, the slider, Back, pause and resume, dialogs answered, and the world changing under the screen): "
                        + "never a failure of the screen, no time on screen while moving (" + hidden + " such screens), no folder with both a plan and a stage, no pin off the drive; "
                        + "every page was visited at least 30 times (" + java.util.Arrays.toString(seen) + "), a planned stage's page " + planned + " times, " + saved + " saved notices, most stages at once " + mostStages
                        + ", most plans at once " + mostPlans,
                bad.length() == 0 && allPages && planned >= 30 && taps > 500 && typed > 200 && touches > 100 && seeks > 50 && answered > 20 && hidden > 300,
                bad.toString() + " taps=" + taps + " typed=" + typed + " touches=" + touches + " seeks=" + seeks + " answered=" + answered);
        StageUiTests.reset();
        StageAppTests.CLIP.clip = null;
        StageAppTests.CLIP.refuse = null;
    }
}
