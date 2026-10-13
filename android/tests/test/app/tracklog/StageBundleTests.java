package app.tracklog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The stage bundle (export and import of stages and runs as one zip file): what comes out of one phone goes into another unchanged,
 * a second import changes nothing, and a hostile or damaged file cannot write outside the store, fill the disk or half-import.
 * Pure Java, run as part of mode "st".
 */
final class StageBundleTests {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private StageBundleTests() { }

    static File tmp(String what) throws IOException {
        return Files.createTempDirectory("st_bundle_" + what + "_").toFile();
    }

    /** A store with a stage built from a marking drive on a synthetic road, and some runs of it. */
    static StageStore storeWith(File root, long seed, int runs, String id, String name) throws IOException {
        final StageStore store = new StageStore(root);
        final SynthRoad road = SynthRoad.mountain();
        final StageTests.Marking m = StageTests.mark(road, 2.0, seed);
        final Stage s0 = m.stage;
        final Stage s = new Stage(id, name, s0.kind, 1791885600000L, s0.geo, s0.start, s0.finish, s0.route,
                s0.startMarkLat, s0.startMarkLon, s0.finishMarkLat, s0.finishMarkLon);
        store.save(s);
        final Stage st = store.load(id);
        for (int i = 0; i < runs; i++) {
            final SynthRoad.Drive d = road.drive(-600.0, road.total + 300.0, 18.0, 15.0, 30.0, 5.5, 3.0, 5.0, 0.95 + 0.03 * i, null);
            final List<Fix> fx = road.fixes(d, 5.0, 0.0, 5.0 + d.duration(), 2.5, 0.7, 0.0, new Random(seed * 100 + i));
            for (RunResult r : Scorer.scoreAll(st, fx, "session_20261013_1" + i + "0000.csv", 1791885600000.0 + i * 600000.0)) store.saveRun(r);
        }
        return store;
    }

    /** Every file under a store: relative path to text. */
    static Map<String, String> snapshot(File root) throws IOException {
        final Map<String, String> out = new TreeMap<String, String>();
        walk(root, root, out);
        return out;
    }

    private static void walk(File base, File f, Map<String, String> out) throws IOException {
        final File[] fs = f.listFiles();
        if (fs == null) return;
        for (File x : fs) {
            if (x.isDirectory()) walk(base, x, out);
            else out.put(base.toURI().relativize(x.toURI()).getPath(), new String(Files.readAllBytes(x.toPath()), UTF8));
        }
    }

    /**
     * A zip with the given entries (name, text). A name may appear twice, as in a damaged or hand-made file: ZipOutputStream refuses that,
     * so the second one is written under a stand-in name of the same length and the real name is put back in the bytes afterwards
     * (the name sits in the entry's header and in the directory at the end, nowhere else).
     */
    static byte[] zip(String[][] entries) throws IOException {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final ZipOutputStream z = new ZipOutputStream(bo);
        final Set<String> used = new HashSet<String>();
        final List<String[]> stand = new ArrayList<String[]>();           // {stand-in name, real name}
        for (String[] e : entries) {
            String name = e[0];
            if (used.contains(name)) {
                String alias = null;
                for (char c = 'a'; c <= 'z' && alias == null; c++) {
                    final String cand = name.substring(0, name.length() - 1) + c;
                    if (!used.contains(cand)) alias = cand;
                }
                if (alias == null) throw new IOException("no stand-in name for " + name);
                stand.add(new String[]{alias, name});
                name = alias;
            }
            used.add(name);
            z.putNextEntry(new ZipEntry(name));
            if (!name.endsWith("/")) z.write(e[1].getBytes(UTF8));
            z.closeEntry();
        }
        z.close();
        final byte[] out = bo.toByteArray();
        for (String[] s : stand) {
            final byte[] from = s[0].getBytes(UTF8), to = s[1].getBytes(UTF8);
            if (from.length != to.length) throw new IOException("stand-in name changes the length: " + s[1]);
            int at = 0, hits = 0;
            for (int i = 0; i + from.length <= out.length; i++) {
                boolean same = true;
                for (int k = 0; k < from.length && same; k++) same = out[i + k] == from[k];
                if (same) { System.arraycopy(to, 0, out, i, to.length); hits++; i += from.length - 1; }
            }
            if (hits != 2) throw new IOException("expected the stand-in name twice (header and directory), found " + hits + ": " + s[1]);
        }
        return out;
    }

    static StageBundle.Report imp(byte[] zip, StageStore store) throws IOException {
        return StageBundle.importFrom(new ByteArrayInputStream(zip), store, 1791890000000L);
    }

    static String refusedWith(byte[] zip, StageStore store) {
        try {
            imp(zip, store);
            return null;
        } catch (IOException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        }
    }

    /** The run file with its 'run' line given another id. */
    static String withRunLine(String text, String id) {
        return text.replaceAll("(?m)^run .*$", "run " + id);
    }

    static void run() {
        System.out.println("\n===== ST.BN: stage bundle (export and import) =====");
        try {
            roundTrip();
            merging();
            hostile();
            damaged();
        } catch (Exception e) {
            e.printStackTrace(System.out);
            Check.ok("ST.BN the bundle tests ran without an exception", false, e.toString());
        }
    }

    // ------------------------------------------------------------------ out and back in

    static void roundTrip() throws Exception {
        final File a = tmp("a"), b = tmp("b");
        storeWith(new File(a, "store"), 11, 3, "st_a", "Fraser to Bentong");
        final StageStore from2 = storeWith(new File(a, "store"), 12, 2, "st_b", "Dash Highway north");
        final byte[] zip = StageBundle.exportBytes(from2);
        final byte[] zip2 = StageBundle.exportBytes(from2);
        Check.ok("ST.BN1 export: the same stages give the same bytes every time; the zip holds 2 stage files and 5 run files under tracklogger-stages/",
                Arrays.equals(zip, zip2) && countEntries(zip, "/stage.txt") == 2 && countEntries(zip, "/run_") == 5 && zip.length > 1000,
                "bytes=" + zip.length + " stages=" + countEntries(zip, "/stage.txt") + " runs=" + countEntries(zip, "/run_"));

        final StageStore into = new StageStore(new File(b, "store"));
        final StageBundle.Report r = imp(zip, into);
        final Map<String, String> sa = snapshot(from2.root()), sb = snapshot(into.root());
        Check.ok("ST.BN2 import into an empty phone: 2 stages and 5 runs added, and every file is byte for byte the file that was exported (" + sb.size() + " files)",
                r.stagesAdded == 2 && r.runsAdded == 5 && r.skipped == 0 && sa.equals(sb) && sb.size() == 7, r.text() + " " + sa.keySet() + " vs " + sb.keySet());

        final StageBundle.Report again = imp(zip, into);
        Check.ok("ST.BN3 the same bundle imported again changes nothing: every stage and run is 'already here'; no file changed",
                again.stagesAdded == 0 && again.stagesSame == 2 && again.runsAdded == 0 && again.runsAlready == 5 && again.skipped == 0 && snapshot(into.root()).equals(sb), again.text());

        // the same file with the top folder left off, and with directory entries, is read the same way
        final List<String[]> flat = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : sa.entrySet()) flat.add(new String[]{e.getKey(), e.getValue()});
        flat.add(0, new String[]{"st_a/", ""});
        final StageStore into2 = new StageStore(new File(b, "store2"));
        final StageBundle.Report fr = imp(zip(flat.toArray(new String[0][])), into2);
        Check.ok("ST.BN4 a bundle without the top folder, with folder entries in it, is accepted too", fr.stagesAdded == 2 && fr.runsAdded == 5 && snapshot(into2.root()).equals(sa), fr.text());

        // an empty phone exports an empty bundle, which imports as 'nothing'
        final byte[] empty = StageBundle.exportBytes(new StageStore(new File(b, "nothing")));
        final StageBundle.Report er = imp(empty, new StageStore(new File(b, "store3")));
        Check.ok("ST.BN5 a phone with no stages exports a valid empty bundle; importing it says there is nothing to import", !er.anythingFound() && er.text().startsWith("Nothing to import"), er.text());

        Check.ok("ST.BN6 the sentences: 'Imported' text for new, same, copied, runs and skipped parts",
                sentence(2, 0, 0, 5, 0, 0).equals("2 stages added and 5 runs added.")
                        && sentence(1, 1, 0, 3, 2, 1).equals("1 stage added, 1 stage already here, 3 runs added and 2 runs already here. 1 file skipped (not valid).")
                        && sentence(0, 0, 1, 2, 0, 0).startsWith("1 stage added as a copy") && sentence(0, 0, 0, 0, 0, 2).equals("Nothing new. 2 files skipped (not valid).") ,
                sentence(1, 1, 0, 3, 2, 1));
    }

    static String sentence(int added, int same, int copied, int runs, int already, int skipped) {
        final StageBundle.Report r = new StageBundle.Report();
        r.stagesAdded = added;
        r.stagesSame = same;
        r.stagesCopied = copied;
        r.runsAdded = runs;
        r.runsAlready = already;
        r.skipped = skipped;
        return r.text();
    }

    static int countEntries(byte[] zip, String part) throws IOException {
        int n = 0;
        final java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip));
        for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) if (e.getName().contains(part)) n++;
        return n;
    }

    // ------------------------------------------------------------------ merging

    static void merging() throws Exception {
        final File a = tmp("m1"), b = tmp("m2");
        final StageStore phoneA = storeWith(new File(a, "store"), 21, 4, "st_x", "Fraser to Bentong");
        final byte[] zip = StageBundle.exportBytes(phoneA);

        // phone B has the same stage (renamed there) with 2 of the 4 runs
        final StageStore phoneB = storeWith(new File(b, "store"), 21, 2, "st_x", "FtB");
        final Map<String, String> before = snapshot(phoneB.root());
        final StageBundle.Report r = imp(zip, phoneB);
        final Map<String, String> after = snapshot(phoneB.root());
        boolean untouched = true;
        for (Map.Entry<String, String> e : before.entrySet()) untouched &= e.getValue().equals(after.get(e.getKey()));
        Check.ok("ST.BN7 the same stage on another phone is merged: it keeps its own name ('FtB'), gets the 2 runs it did not have, and the 2 it had are not touched",
                r.stagesSame == 1 && r.stagesAdded == 0 && r.runsAdded == 2 && r.runsAlready == 2 && untouched && phoneB.load("st_x").name().equals("FtB")
                        && phoneB.runs("st_x").size() == 4, r.text());

        // the same id on a different road is a different stage: added as a copy under a new id, the phone's own stage left alone
        final File c = tmp("m3");
        final StageStore phoneC = storeWith(new File(c, "store"), 99, 1, "st_x", "Some other road");
        final Map<String, String> beforeC = snapshot(phoneC.root());
        final StageBundle.Report rc = imp(zip, phoneC);
        final List<Stage> all = phoneC.list();
        Stage copy = null;
        for (Stage s : all) if (!s.id.equals("st_x")) copy = s;
        boolean ownUntouched = true;
        final Map<String, String> afterC = snapshot(phoneC.root());
        for (Map.Entry<String, String> e : beforeC.entrySet()) ownUntouched &= e.getValue().equals(afterC.get(e.getKey()));
        final List<RunResult> copyRuns = copy == null ? new ArrayList<RunResult>() : phoneC.runs(copy.id);
        boolean pointed = !copyRuns.isEmpty();
        for (RunResult x : copyRuns) pointed &= x.stageId.equals(copy.id);
        boolean comparable = copyRuns.size() >= 2 && copyRuns.get(0).finished && copyRuns.get(1).finished && copyRuns.get(0).comparableWith(copyRuns.get(1));
        Check.ok("ST.BN8 the same id on a different road: added as a copy under a new id '... (imported)' with its 4 runs pointed at it (and still comparable); the phone's own stage and run are untouched",
                rc.stagesCopied == 1 && rc.stagesAdded == 0 && copy != null && copy.name().endsWith("(imported)") && copy.name().startsWith("Fraser to Bentong")
                        && copyRuns.size() == 4 && pointed && comparable && ownUntouched && phoneC.runs("st_x").size() == 1 && rc.notes.size() == 1,
                rc.text() + " copy=" + (copy == null ? "none" : copy.id + " '" + copy.name() + "' runs=" + copyRuns.size()));

        // the same bundle again: the copy made the first time is recognised as that road; no third stage
        final StageBundle.Report rc2 = imp(zip, phoneC);
        Check.ok("ST.BN9 importing it again is recognised: the copy made the first time is the same road, so its runs are 'already here' and no second copy is made",
                rc2.stagesCopied == 0 && rc2.stagesAdded == 0 && rc2.stagesSame == 1 && rc2.runsAdded == 0 && rc2.runsAlready == 4 && phoneC.list().size() == 2,
                rc2.text() + " stages=" + phoneC.list().size());

        // runs for a stage that is on the phone, in a bundle that has no stage file for it
        final File d = tmp("m4");
        final StageStore phoneD = storeWith(new File(d, "store"), 21, 0, "st_x", "Fraser to Bentong");
        final Map<String, String> src = snapshot(phoneA.root());
        final List<String[]> onlyRuns = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : src.entrySet()) if (e.getKey().contains("/run_")) onlyRuns.add(new String[]{StageBundle.TOP + e.getKey(), e.getValue()});
        final StageBundle.Report ro = imp(zip(onlyRuns.toArray(new String[0][])), phoneD);
        Check.ok("ST.BN10 runs whose stage file is not in the bundle are added when the phone has that stage", ro.runsAdded == 4 && ro.skipped == 0 && phoneD.runs("st_x").size() == 4, ro.text());
        final File e = tmp("m5");
        final StageStore phoneE = new StageStore(new File(e, "store"));
        final StageBundle.Report rn = imp(zip(onlyRuns.toArray(new String[0][])), phoneE);
        Check.ok("ST.BN11 ... and skipped, counted, when it does not", rn.runsAdded == 0 && rn.skipped == 4 && phoneE.list().isEmpty(), rn.text());
    }

    // ------------------------------------------------------------------ hostile files

    static void hostile() throws Exception {
        final File base = tmp("h");
        final File canary = new File(base, "canary");
        canary.mkdirs();
        Files.write(new File(canary, "keep.txt").toPath(), "x".getBytes(UTF8));
        final File storeDir = new File(base, "store/inner");
        final StageStore good = storeWith(new File(base, "good"), 31, 2, "st_good", "Good stage");
        final Map<String, String> g = snapshot(good.root());
        final String stageText = g.get("st_good/stage.txt");
        final String runName = "st_good/" + g.keySet().toArray(new String[0])[1].substring("st_good/".length());
        final String runText = g.get(runName);

        final String long65 = "a123456789012345678901234567890123456789012345678901234567890123x";   // 65 characters
        final List<String[]> es = new ArrayList<String[]>();
        es.add(new String[]{"../evil.txt", "x"});
        es.add(new String[]{"/etc/st_evil/stage.txt", stageText});
        es.add(new String[]{"st_back\\stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/../../evil/stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/st_good/../../../evil/stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/a/b/stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/st_good/run_../x.txt", runText});
        es.add(new String[]{"tracklogger-stages/st good/stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/" + long65 + "/stage.txt", stageText});
        es.add(new String[]{"TRACKLOGGER-STAGES/st_up/stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/st_x/stage.txt.tmp", stageText});
        es.add(new String[]{"tracklogger-stages/st_x/other.txt", stageText});
        es.add(new String[]{"stage.txt", stageText});
        es.add(new String[]{"tracklogger-stages/", ""});
        es.add(new String[]{StageBundle.TOP + "st_good/stage.txt", stageText});
        es.add(new String[]{StageBundle.TOP + runName, runText});
        final StageStore into = new StageStore(storeDir);
        final StageBundle.Report r = imp(zip(es.toArray(new String[0][])), into);
        final Map<String, String> outside = snapshot(base);
        boolean clean = true;
        for (String k : outside.keySet()) clean &= k.startsWith("canary/") || k.startsWith("store/inner/st_good/") || k.startsWith("good/");
        Check.ok("ST.BN12 hostile entry names ('..', absolute, backslash, spaces, a 65-character id, wrong case, extra folders, other file names, 'stage.txt' at the top): 13 skipped and counted, only the one honest stage and its run imported, nothing written anywhere else",
                r.stagesAdded == 1 && r.runsAdded == 1 && r.skipped == 13 && clean && outside.get("canary/keep.txt").equals("x") && !new File(base, "evil.txt").exists()
                        && !new File(base, "store/evil").exists(),
                r.text() + " files=" + outside.keySet());

        // a file that expands without end, too many files, too much in all
        final byte[] big = new byte[6 * 1024 * 1024];
        final StageStore h1 = new StageStore(new File(base, "h1"));
        final String bomb = refusedWith(zip(new String[][]{{StageBundle.TOP + "st_a/stage.txt", new String(big, UTF8)}}), h1);
        Check.ok("ST.BN13 a 6 MB file inside (a few KB zipped) is refused at 5 MB with a sentence, and nothing is written", bomb != null && bomb.contains("5 MB") && snapshot(h1.root()).isEmpty(), "" + bomb);

        final List<String[]> many = new ArrayList<String[]>();
        for (int i = 0; i < 5001; i++) many.add(new String[]{"junk/" + i + ".txt", "x"});
        final String tooMany = refusedWith(zip(many.toArray(new String[0][])), h1);
        Check.ok("ST.BN14 5001 entries are refused", tooMany != null && tooMany.contains("5000 entries") && snapshot(h1.root()).isEmpty(), "" + tooMany);

        final List<String[]> wide = new ArrayList<String[]>();
        final String chunk = new String(new byte[4 * 1024 * 1024], UTF8);
        for (int i = 0; i < 9; i++) wide.add(new String[]{StageBundle.TOP + "st_a/run_r" + i + ".txt", chunk});
        final String tooWide = refusedWith(zip(wide.toArray(new String[0][])), h1);
        Check.ok("ST.BN15 nine files of 4 MB (36 MB in all, each within the limit) are refused at 32 MB in all", tooWide != null && tooWide.contains("32 MB") && snapshot(h1.root()).isEmpty(), "" + tooWide);

        // not a zip, empty, cut short
        final StageBundle.Report junk = imp("this is not a zip file at all, just text\n".getBytes(UTF8), h1);
        final byte[] full = StageBundle.exportBytes(good);
        final byte[] cut = Arrays.copyOf(full, full.length * 6 / 10);
        final String cutMsg = refusedWith(cut, h1);
        final StageBundle.Report emptyIn = imp(new byte[0], h1);
        Check.ok("ST.BN16 a text file and an empty file hold no stages ('nothing to import'); a zip cut short is refused in words; none of them writes anything",
                !junk.anythingFound() && !emptyIn.anythingFound() && cutMsg != null && cutMsg.startsWith("that file is not a readable zip") && snapshot(h1.root()).isEmpty(),
                "junk=" + junk.text() + " cut=" + cutMsg + " empty=" + emptyIn.text());
    }

    // ------------------------------------------------------------------ damaged files inside a good bundle

    static void damaged() throws Exception {
        final File base = tmp("d");
        final StageStore good = storeWith(new File(base, "good"), 41, 2, "st_good", "Good stage");
        final StageStore other = storeWith(new File(base, "other"), 42, 1, "st_other", "Other stage");
        final Map<String, String> g = snapshot(good.root()), o = snapshot(other.root());
        final List<String[]> es = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : g.entrySet()) es.add(new String[]{StageBundle.TOP + e.getKey(), e.getValue()});
        // the other stage's file is cut short; its run is therefore an orphan
        for (Map.Entry<String, String> e : o.entrySet()) {
            es.add(new String[]{StageBundle.TOP + e.getKey(), e.getKey().endsWith("stage.txt") ? e.getValue().substring(0, e.getValue().length() / 2) : e.getValue()});
        }
        // a run of the good stage that says it belongs to another stage, one that says another id than its file name, one cut short,
        // one of another length, and one that is fine but given twice
        final String runText = g.get(g.keySet().toArray(new String[0])[1]);
        es.add(new String[]{StageBundle.TOP + "st_good/run_wrongstage.txt", withRunLine(runText, "wrongstage").replace("stage st_good", "stage st_nope")});
        es.add(new String[]{StageBundle.TOP + "st_good/run_wrongid.txt", runText});
        es.add(new String[]{StageBundle.TOP + "st_good/run_cut.txt", withRunLine(runText, "cut").substring(0, runText.length() / 3)});
        es.add(new String[]{StageBundle.TOP + "st_good/run_otherlen.txt", withRunLine(runText, "otherlen").replaceAll("length_m [0-9.]+", "length_m 99999.00")});
        es.add(new String[]{StageBundle.TOP + "st_good/run_dup.txt", withRunLine(runText, "dup")});
        es.add(new String[]{StageBundle.TOP + "st_good/run_dup.txt", withRunLine(runText, "dup")});         // the same name twice
        final StageStore into = new StageStore(new File(base, "into"));
        final StageBundle.Report r = imp(zip(es.toArray(new String[0][])), into);
        // st_good with its 2 runs and 'dup' imported; skipped: the cut stage file, its orphan run, wrongstage, wrongid, cut, otherlen, and the second 'dup'
        final List<RunResult> runs = into.runs("st_good");
        Check.ok("ST.BN17 damaged files inside a good bundle are skipped one by one and counted (a cut-short stage and the run that depends on it, runs that name another stage or id, a cut-short run, a run of another length, a name twice); the good stage, its 2 runs and a good extra run come through",
                r.stagesAdded == 1 && r.runsAdded == 3 && runs.size() == 3 && r.skipped == 7 && into.load("st_other") == null && !new File(into.root(), "st_other").exists(),
                r.text() + " runs=" + runs.size() + " skipped=" + r.skipped);
        // nothing half-written is left behind: no .tmp files
        boolean noTmp = true;
        for (String k : snapshot(into.root()).keySet()) noTmp &= !k.endsWith(".tmp");
        Check.ok("ST.BN18 no half-written (.tmp) file is left in the store after an import", noTmp, "");

        final String fn = StageBundle.fileName(1791885600000L);
        Check.ok("ST.BN19 the bundle's file name: TrackLogger-stages-YYYYMMDD-HHMMSS.zip", fn.matches("TrackLogger-stages-\\d{8}-\\d{6}\\.zip"), fn);
    }
}
