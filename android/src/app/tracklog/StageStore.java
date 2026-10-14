package app.tracklog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Stages and their runs on disk. Plain Java (java.io only), so it is tested on a computer.
 *
 *   <root>/<stage id>/stage.txt            the stage (StageFiles)
 *   <root>/<stage id>/run_<run id>.txt     one finished or unfinished run
 *   <root>/<stage id>/plan.txt             a planned stage that has not been driven yet (instead of stage.txt; release 1.5)
 *
 * Writes go to a temporary file in the same folder and are then renamed over the real one, so a kill in the middle of a write leaves
 * the old file intact, never half a new one. A file that cannot be read back (cut short, edited by hand, a later format) is skipped
 * when listing, never fatal: one bad file must not hide the others.
 */
final class StageStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PLAN_FILE = "plan.txt";
    private final File root;

    StageStore(File root) {
        this.root = root;
    }

    File root() {
        return root;
    }

    // ------------------------------------------------------------------ stages

    /** A new unused id such as st20261013_182233 (or st20261013_182233b if that exists). */
    String newId(long utcMs) {
        final String base = "st" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(utcMs));
        String id = base;
        for (char c = 'b'; new File(root, id).exists() && c <= 'z'; c++) id = base + c;
        return id;
    }

    List<Stage> list() {
        final List<Stage> out = new ArrayList<Stage>();
        final File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory() || !StageFiles.safeId(d.getName())) continue;
            final Stage s = load(d.getName());
            if (s != null) out.add(s);
        }
        Collections.sort(out, new Comparator<Stage>() {
            @Override
            public int compare(Stage a, Stage b) {
                final int c = a.name().compareToIgnoreCase(b.name());
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /** The stage, or null if there is none with that id or its file cannot be read. */
    Stage load(String id) {
        if (!StageFiles.safeId(id)) return null;
        final File f = new File(new File(root, id), "stage.txt");
        try {
            if (!f.isFile()) return null;
            final Stage s = StageFiles.parseStage(read(f));
            return s.id.equals(id) ? s : null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Writes the stage. If the folder holds the plan this stage was made from, the plan goes (after the stage is safely on disk). */
    void save(Stage s) throws IOException {
        if (!StageFiles.safeId(s.id)) throw new IOException("bad stage id");
        final File dir = new File(root, s.id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        writeAtomic(new File(dir, "stage.txt"), StageFiles.formatStage(s));
        new File(dir, PLAN_FILE).delete();
    }

    /** Deletes the stage and all its runs. Not the recorded session files: those belong to the person. */
    void delete(String id) {
        if (!StageFiles.safeId(id)) return;
        final File dir = new File(root, id);
        final File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }

    // ------------------------------------------------------------------ plans

    /** Planned stages, by name: the folders that hold a plan.txt that can be read and no stage.txt (a stage wins over a plan beside it). */
    List<Plan> listPlans() {
        final List<Plan> out = new ArrayList<Plan>();
        final File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory() || !StageFiles.safeId(d.getName())) continue;
            final Plan p = loadPlan(d.getName());
            if (p != null) out.add(p);
        }
        Collections.sort(out, new Comparator<Plan>() {
            @Override
            public int compare(Plan a, Plan b) {
                final int c = a.name().compareToIgnoreCase(b.name());
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /** The plan, or null if there is none with that id, its file cannot be read, or the folder already holds the stage. */
    Plan loadPlan(String id) {
        if (!StageFiles.safeId(id)) return null;
        final File dir = new File(root, id);
        final File f = new File(dir, PLAN_FILE);
        try {
            if (!f.isFile() || new File(dir, "stage.txt").exists()) return null;
            final Plan p = StageFiles.parsePlan(read(f));
            return p.id.equals(id) ? p : null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    void savePlan(Plan p) throws IOException {
        if (!StageFiles.safeId(p.id)) throw new IOException("bad plan id");
        final File dir = new File(root, p.id);
        if (new File(dir, "stage.txt").exists()) throw new IOException("there is already a stage with that id");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        writeAtomic(new File(dir, PLAN_FILE), StageFiles.formatPlan(p));
    }

    // ------------------------------------------------------------------ runs

    /** Runs of a stage, oldest first (by start time, then by id). */
    List<RunResult> runs(String stageId) {
        final List<RunResult> out = new ArrayList<RunResult>();
        if (!StageFiles.safeId(stageId)) return out;
        final File[] fs = new File(root, stageId).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            final String n = f.getName();
            if (!n.startsWith("run_") || !n.endsWith(".txt")) continue;
            try {
                final RunResult r = StageFiles.parseRun(read(f));
                if (r.stageId.equals(stageId)) out.add(r);
            } catch (IOException e) {
                // skipped
            } catch (RuntimeException e) {
                // skipped
            }
        }
        Collections.sort(out, new Comparator<RunResult>() {
            @Override
            public int compare(RunResult a, RunResult b) {
                final int c = Long.compare(a.startUtcMs, b.startUtcMs);
                return c != 0 ? c : a.runId.compareTo(b.runId);
            }
        });
        return out;
    }

    void saveRun(RunResult r) throws IOException {
        if (!StageFiles.safeId(r.stageId) || !StageFiles.safeId(r.runId)) throw new IOException("bad id");
        final File dir = new File(root, r.stageId);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        writeAtomic(new File(dir, "run_" + r.runId + ".txt"), StageFiles.formatRun(r));
    }

    boolean deleteRun(String stageId, String runId) {
        if (!StageFiles.safeId(stageId) || !StageFiles.safeId(runId)) return false;
        return new File(new File(root, stageId), "run_" + runId + ".txt").delete();
    }

    // ------------------------------------------------------------------ files

    static String read(File f) throws IOException {
        final FileInputStream in = new FileInputStream(f);
        try {
            final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) Math.min(f.length() + 16, 32L * 1024 * 1024));
            final byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 32L * 1024 * 1024) throw new IOException("file too large");
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), UTF8);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    static void writeAtomic(File target, String text) throws IOException {
        final File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = null;
        boolean ok = false;
        try {
            out = new FileOutputStream(tmp);
            out.write(text.getBytes(UTF8));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(target)) {
                // rename(2) replaces an existing file in one step on the phone's file systems; if this one refuses, delete and retry
                if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
                if (!tmp.renameTo(target)) throw new IOException("cannot rename to " + target);
            }
            ok = true;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            if (!ok) tmp.delete();
        }
    }
}
