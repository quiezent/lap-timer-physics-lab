package app.tracklog;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A planned stage on disk (release 1.5): the plan file, the store's handling of it next to stages, and the backup bundle, which does not
 * carry plans. Plain Java; the screens that use plans are in PinUiTests, the first drive that turns a plan into a stage in PlanDriveTests.
 */
final class PlanTests {

    private PlanTests() { }

    private static boolean throwsOnParse(String text) {
        try {
            StageFiles.parsePlan(text);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    static void run(File root) {
        System.out.println("\n===== PN.PL: planned stages on disk =====");
        try {
            file();
            refused();
            store(root);
            bundle(root);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Check.ok("PN.PL the plan tests ran without an exception", false, t.toString());
        }
    }

    // ------------------------------------------------------------------ the file

    static void file() {
        final Plan p = new Plan("st20261014_101500", "  Fraser, to; Bentong  ", 1791965700000L, 3.5683219, 101.7461234, -3.5214530, -101.9087640);
        final String text = StageFiles.formatPlan(p);
        final Plan q = StageFiles.parsePlan(text);
        final boolean round = q.id.equals(p.id) && q.name().equals("Fraser to Bentong") && q.createdUtcMs == p.createdUtcMs
                && Math.abs(q.startLat - 3.5683219) < 1e-9 && Math.abs(q.startLon - 101.7461234) < 1e-9
                && Math.abs(q.finishLat + 3.5214530) < 1e-9 && Math.abs(q.finishLon + 101.9087640) < 1e-9;
        final String[] lines = text.split("\n");
        final boolean shape = lines[0].equals("TRACKLOGGER-PLAN 1") && lines[lines.length - 1].equals("end") && text.endsWith("end\n")
                && text.contains("\nstart_pin 3.5683219 101.7461234\n") && text.contains("\nfinish_pin -3.5214530 -101.9087640\n") && text.indexOf('\r') < 0;
        // a later version may add lines, and a Windows editor may change the line ends: both still read
        final Plan later = StageFiles.parsePlan(text.replace("\nend\n", "\nsurface tarmac\nend\n").replace("\n", "\r\n"));
        Check.ok("PN.PL1 a plan file: the magic line, id, cleaned name, time, the two pins to seven decimals (about 1 cm), and 'end'; it reads back to the same plan; an unknown line or Windows line ends do not matter",
                round && shape && later.id.equals(p.id) && later.name().equals("Fraser to Bentong") && Math.abs(later.finishLon + 101.908764) < 1e-9,
                round + " " + shape + "\n" + text);
        Check.ok("PN.PL1b the straight-line distance of a plan is the distance between its two places, and renaming cleans the name",
                Math.abs(p.straightM() - Geo.distance(3.5683219, 101.7461234, -3.5214530, -101.9087640)) < 1e-6 && cleanRename(),
                p.straightM() + "");
    }

    private static boolean cleanRename() {
        final Plan p = new Plan("a", "x", 0L, 1.0, 1.0, 1.5, 1.5);
        p.rename("  Dash, Highway; north ");
        final boolean one = p.name().equals("Dash Highway north");
        p.rename("");
        return one && p.name().length() > 0;
    }

    static void refused() {
        final String good = StageFiles.formatPlan(new Plan("st1", "A", 5L, 3.5, 101.5, 3.6, 101.6));
        final String[] bad = {
            good.replace("TRACKLOGGER-PLAN 1", "TRACKLOGGER-PLAN 2"),                     // another version
            good.replace("TRACKLOGGER-PLAN 1", "TRACKLOGGER-STAGE 1"),                    // another kind of file
            good.substring(0, good.length() - 4),                                          // cut short: no 'end'
            good.substring(0, good.length() - 4) + "en",                                   // cut inside the last line
            good.replace("id st1", "id ../etc"),                                           // an id that is a path
            good.replace("id st1", "id "),                                                 // an empty id
            good.replace("start_pin 3.5000000 101.5000000\n", ""),                         // a pin missing
            good.replace("finish_pin 3.6000000 101.6000000\n", ""),
            good.replace("start_pin 3.5000000 101.5000000", "start_pin 91.0000000 101.5000000"),    // not on the earth
            good.replace("finish_pin 3.6000000 101.6000000", "finish_pin 3.6000000 181.0000000"),
            good.replace("start_pin 3.5000000 101.5000000", "start_pin 3.5 east"),         // not numbers
            good.replace("created_utc_ms 5", "created_utc_ms five"),
            "", "end\n", "TRACKLOGGER-PLAN 1\nend\n"};
        int refused = 0;
        final StringBuilder missed = new StringBuilder();
        for (int i = 0; i < bad.length; i++) {
            if (throwsOnParse(bad[i])) refused++; else missed.append(i).append(' ');
        }
        Check.ok("PN.PL2 a plan file that is another version or kind, cut short, has an id that is a path or empty, lacks a pin, has a pin that is not on the earth or not numbers, or is empty is refused whole (never half-read): "
                + refused + " of " + bad.length, refused == bad.length, "read when it should not be: " + missed);
    }

    // ------------------------------------------------------------------ the store

    static void store(File root) throws Exception {
        final File dir = Files.createTempDirectory(root.toPath(), "pl_store_").toFile();
        final StageStore store = new StageStore(new File(dir, "stages"));
        store.savePlan(new Plan("st_b", "kkb to Fraser", 1L, 3.5, 101.5, 3.6, 101.6));
        store.savePlan(new Plan("st_a", "Fraser to Bentong", 2L, 3.7, 101.7, 3.8, 101.8));
        store.savePlan(new Plan("st_c", "Dash Highway", 3L, 3.9, 101.9, 3.95, 101.95));
        final List<Plan> plans = store.listPlans();
        final String order = plans.size() == 3 ? plans.get(0).name() + "|" + plans.get(1).name() + "|" + plans.get(2).name() : "?";
        final File folder = new File(dir, "stages/st_a");
        final String[] names = folder.list();
        final boolean noTmp = names != null && names.length == 1 && names[0].equals("plan.txt");
        Check.ok("PN.PL3 the store keeps each plan as plan.txt in its own folder (nothing else left behind), lists plans by name ignoring case, and lists none of them as stages",
                order.equals("Dash Highway|Fraser to Bentong|kkb to Fraser") && noTmp && store.list().isEmpty() && store.loadPlan("st_a").name().equals("Fraser to Bentong")
                        && store.load("st_a") == null && store.loadPlan("st_zz") == null && store.loadPlan("../st_a") == null,
                order + " " + java.util.Arrays.toString(names));

        // a plan file that cannot be read hides only itself
        StageStore.writeAtomic(new File(dir, "stages/st_b/plan.txt"), "TRACKLOGGER-PLAN 1\nid st_b\nname x\nstart_pin 3.5 101.5\n");           // cut short
        final List<Plan> left = store.listPlans();
        // a plan whose id says another folder is not a plan of this one
        StageStore.writeAtomic(new File(dir, "stages/st_c/plan.txt"), StageFiles.formatPlan(new Plan("st_other", "Dash Highway", 3L, 3.9, 101.9, 3.95, 101.95)));
        final List<Plan> left2 = store.listPlans();
        Check.ok("PN.PL4 a plan file that is cut short is skipped without hiding the others, and a plan whose id does not match its folder is not loaded",
                left.size() == 2 && left.get(0).id.equals("st_c") && left.get(1).id.equals("st_a") && left2.size() == 1 && left2.get(0).id.equals("st_a")
                        && store.loadPlan("st_b") == null && store.loadPlan("st_c") == null,
                left.size() + " " + left2.size());

        // the plan becomes a stage under the same id: stage.txt is written first, then plan.txt goes
        final StageStore other = StageBundleTests.storeWith(new File(dir, "other"), 71, 0, "st_made", "Made");
        final Stage made = other.load("st_made");
        store.savePlan(new Plan("st_made", "Made", 9L, 3.1, 101.1, 3.2, 101.2));
        final boolean before = store.loadPlan("st_made") != null && store.load("st_made") == null;
        store.save(made);
        final String[] after = new File(dir, "stages/st_made").list();
        java.util.Arrays.sort(after);
        Check.ok("PN.PL5 saving a stage under the id of a plan replaces the plan: the folder then holds stage.txt and no plan.txt, the plan is gone from the plans and the stage is in the stages",
                before && java.util.Arrays.equals(after, new String[]{"stage.txt"}) && store.loadPlan("st_made") == null && store.load("st_made") != null
                        && store.list().size() == 1 && store.listPlans().size() == 1,
                java.util.Arrays.toString(after));

        // a plan cannot be saved on top of a stage; and if a kill left both files, the stage wins and the next save of it tidies up
        boolean refusedSave = false;
        try {
            store.savePlan(new Plan("st_made", "Again", 10L, 3.1, 101.1, 3.2, 101.2));
        } catch (IOException e) {
            refusedSave = true;
        }
        StageStore.writeAtomic(new File(dir, "stages/st_made/plan.txt"), StageFiles.formatPlan(new Plan("st_made", "Left over", 10L, 3.1, 101.1, 3.2, 101.2)));
        final boolean stageWins = store.loadPlan("st_made") == null && store.listPlans().size() == 1 && store.load("st_made") != null && store.list().size() == 1;
        store.save(made);
        final boolean tidied = !new File(dir, "stages/st_made/plan.txt").exists();
        Check.ok("PN.PL6 a plan cannot be saved on top of a stage; with both files in one folder (a kill between the two steps) the stage wins and the plan is not listed, and the next save of the stage removes the plan file",
                refusedSave && stageWins && tidied, refusedSave + " " + stageWins + " " + tidied);

        // delete removes the plan's folder
        store.delete("st_a");
        Check.ok("PN.PL7 deleting by id removes a plan's folder", !new File(dir, "stages/st_a").exists() && store.loadPlan("st_a") == null, "");
    }

    // ------------------------------------------------------------------ backups do not carry plans

    private static List<String> entries(byte[] zip) throws IOException {
        final List<String> out = new ArrayList<String>();
        final ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip));
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) out.add(e.getName());
        return out;
    }

    static void bundle(File root) throws Exception {
        final File dir = Files.createTempDirectory(root.toPath(), "pl_bundle_").toFile();
        final StageStore store = StageBundleTests.storeWith(new File(dir, "stages"), 72, 1, "st_road", "Road");
        store.savePlan(new Plan("st_plan", "Only planned", 5L, 3.5, 101.5, 3.6, 101.6));
        final byte[] bytes = StageBundle.exportBytes(store);
        final List<String> names = entries(bytes);
        boolean anyPlan = false;
        for (String n : names) if (n.contains("plan") && !n.contains("st_road")) anyPlan = true;
        // a bundle that someone put a plan file in: it is skipped, nothing is created from it
        final byte[] sneaky = StageBundleTests.zip(new String[][]{
            {"tracklogger-stages/st_sneaky/plan.txt", StageFiles.formatPlan(new Plan("st_sneaky", "Sneaky", 5L, 3.5, 101.5, 3.6, 101.6))}});
        final StageStore into = new StageStore(new File(dir, "into"));
        final StageBundle.Report rep = StageBundle.importFrom(new ByteArrayInputStream(sneaky), into, 1791890000000L);
        final boolean skipped = rep.skipped == 1 && into.list().isEmpty() && into.listPlans().isEmpty() && !new File(dir, "into/st_sneaky").exists();
        // restoring a real bundle into a phone that has a plan with the same id: the stage replaces the plan
        into.savePlan(new Plan("st_road", "Planned earlier", 5L, 3.5, 101.5, 3.6, 101.6));
        final StageBundle.Report rep2 = StageBundle.importFrom(new ByteArrayInputStream(bytes), into, 1791890000000L);
        final boolean replaced = into.load("st_road") != null && into.loadPlan("st_road") == null && into.listPlans().isEmpty()
                && !new File(dir, "into/st_road/plan.txt").exists() && rep2.stagesAdded == 1 && into.runs("st_road").size() == 1;
        Check.ok("PN.PL8 a backup holds stages and their runs, never plans (a plan has no road to back up); a plan file put in a bundle is skipped and creates nothing; restoring a stage onto a phone that holds a plan with that id replaces the plan",
                names.size() > 0 && !anyPlan && skipped && replaced, names + " " + skipped + " " + replaced);
    }
}
