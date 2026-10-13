package app.tracklog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Stages and their runs as one zip file, to carry them from the phone to a computer or to another phone (the format is in
 * docs/stage-format.md). Plain Java (java.io and java.util.zip only), so it is tested on a computer.
 *
 *   tracklogger-stages/<stage id>/stage.txt
 *   tracklogger-stages/<stage id>/run_<run id>.txt
 *
 * A bundle comes from outside, so import is careful. Only entries whose names are exactly one of the two patterns above are read (no
 * other name ever reaches the disk, so a path with "..", an absolute path or a backslash does nothing); the size of the zip, the number
 * of entries and the size of each entry are capped; the whole bundle is read and every file parsed with the strict readers of
 * StageFiles before anything is written; a bad file is skipped and counted, never half-imported.
 */
final class StageBundle {

    static final String TOP = "tracklogger-stages/";
    static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;          // the zip itself
    static final int MAX_ENTRIES = 5000;
    static final long MAX_ENTRY_BYTES = 5L * 1024 * 1024;         // one file, expanded
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;        // all files, expanded
    private static final long FIXED_TIME = 315532800000L;         // 1 Jan 1980: the same bytes for the same stages, every time
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern ENTRY = Pattern.compile("^(?:tracklogger-stages/)?([A-Za-z0-9_-]{1,64})/(stage\\.txt|run_([A-Za-z0-9_-]{1,64})\\.txt)$");

    private StageBundle() { }

    /** The bundle is refused, for a reason that can be said in a sentence. */
    static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(String why) {
            super(why);
        }
    }

    // ------------------------------------------------------------------ what an import did

    static final class Report {
        int stagesAdded;           // not on the phone before
        int stagesSame;            // on the phone already (same id, same road): runs merged
        int stagesCopied;          // same id but a different road: added as a new stage under a new id
        int runsAdded;
        int runsAlready;
        int skipped;               // files that were not valid, or not what they said, or not named as a bundle names them
        final List<String> notes = new ArrayList<String>();

        boolean anythingFound() {
            return stagesAdded + stagesSame + stagesCopied + runsAdded + runsAlready + skipped > 0;
        }

        /** One or two sentences for the screen. */
        String text() {
            if (!anythingFound()) return "Nothing to import: that file holds no stages.";
            final StringBuilder b = new StringBuilder();
            final List<String> parts = new ArrayList<String>();
            if (stagesAdded > 0) parts.add(plural(stagesAdded, "stage") + " added");
            if (stagesSame > 0) parts.add(plural(stagesSame, "stage") + " already here");
            if (stagesCopied > 0) parts.add(plural(stagesCopied, "stage") + " added as a copy (the same id but a different road)");
            if (runsAdded > 0) parts.add(plural(runsAdded, "run") + " added");
            if (runsAlready > 0) parts.add(plural(runsAlready, "run") + " already here");
            if (parts.isEmpty()) parts.add("nothing new");
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) b.append(i == parts.size() - 1 ? " and " : ", ");
                b.append(parts.get(i));
            }
            b.append('.');
            if (skipped > 0) b.append(' ').append(plural(skipped, "file")).append(" skipped (not valid).");
            return Character.toUpperCase(b.charAt(0)) + b.substring(1);
        }

        private static String plural(int n, String w) {
            return n + " " + w + (n == 1 ? "" : "s");
        }
    }

    // ------------------------------------------------------------------ export

    /** Writes every stage of the store, with its runs, as a bundle. Stages that cannot be read back are left out. */
    static int export(StageStore store, OutputStream out) throws IOException {
        final ZipOutputStream zip = new ZipOutputStream(out);
        int stages = 0;
        boolean done = false;
        try {
            final List<Stage> list = store.list();
            Collections.sort(list, new java.util.Comparator<Stage>() {
                @Override public int compare(Stage a, Stage b) {
                    return a.id.compareTo(b.id);
                }
            });
            for (Stage s : list) {
                final File dir = new File(store.root(), s.id);
                put(zip, TOP + s.id + "/stage.txt", StageStore.read(new File(dir, "stage.txt")));
                stages++;
                final List<RunResult> runs = store.runs(s.id);
                Collections.sort(runs, new java.util.Comparator<RunResult>() {
                    @Override public int compare(RunResult a, RunResult b) {
                        return a.runId.compareTo(b.runId);
                    }
                });
                for (RunResult r : runs) {
                    final File f = new File(dir, "run_" + r.runId + ".txt");
                    if (f.isFile()) put(zip, TOP + s.id + "/run_" + r.runId + ".txt", StageStore.read(f));
                }
            }
            zip.finish();
            zip.flush();
            done = true;
        } finally {
            if (done) {
                zip.close();            // the last bytes reach the storage here: an error now is an error of the export
            } else {
                try { zip.close(); } catch (IOException ignored) { }
            }
        }
        return stages;
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        final ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        zip.putNextEntry(e);
        zip.write(text.getBytes(UTF8));
        zip.closeEntry();
    }

    static byte[] exportBytes(StageStore store) throws IOException {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        export(store, bo);
        return bo.toByteArray();
    }

    // ------------------------------------------------------------------ import

    /** One file of a bundle, read and not yet trusted. */
    private static final class Item {
        String stageId;
        String runId;               // null for the stage file
        String text;
    }

    /** An input stream that gives up when more than a limit has been read from it. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override public int read() throws IOException {
            final int c = super.read();
            if (c >= 0 && --left < 0) throw new Refused("that file is larger than 50 MB");
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            final int n = super.read(b, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) throw new Refused("that file is larger than 50 MB");
            }
            return n;
        }
    }

    /**
     * Reads a bundle and adds its stages and runs to the store.
     *
     * @param nowUtcMs used to name a stage that has to be added under a new id
     * @throws IOException with a sentence for the driver if the bundle cannot be used at all; nothing is written in that case
     */
    static Report importFrom(InputStream in, StageStore store, long nowUtcMs) throws IOException {
        final Report rep = new Report();
        final Map<String, Item> items = new LinkedHashMap<String, Item>();
        long total = 0L;
        int entries = 0;
        final ZipInputStream zin = new ZipInputStream(new Limited(in, MAX_ZIP_BYTES));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new Refused("that file holds more than " + MAX_ENTRIES + " entries, which is not a stage bundle");
                final String name = e.getName();
                if (e.isDirectory()) continue;
                final Matcher m = ENTRY.matcher(name == null ? "" : name);
                if (!m.matches()) {
                    rep.skipped++;
                    continue;
                }
                final byte[] data = readCapped(zin);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new Refused("that file expands to more than " + (MAX_TOTAL_BYTES >> 20) + " MB, which is not a stage bundle");
                final Item it = new Item();
                it.stageId = m.group(1);
                it.runId = m.group(3);
                it.text = new String(data, UTF8);
                final String key = it.stageId + "/" + (it.runId == null ? "stage.txt" : "run_" + it.runId + ".txt");
                if (items.containsKey(key)) {
                    rep.skipped++;                                   // the same name twice: the first one counts
                    continue;
                }
                items.put(key, it);
            }
        } catch (Refused ex) {
            throw ex;
        } catch (IOException ex) {
            throw new Refused("that file is not a readable zip file (" + (ex.getMessage() == null ? "cut short" : ex.getMessage()) + ")");
        } catch (RuntimeException ex) {
            throw new Refused("that file is not a readable zip file (" + ex.getClass().getSimpleName() + ")");
        } finally {
            try { zin.close(); } catch (IOException ignored) { }
        }

        // everything is read and nothing is trusted yet: parse, then write
        final Map<String, Stage> stages = new LinkedHashMap<String, Stage>();
        final Set<String> dirs = new HashSet<String>();
        for (Item it : items.values()) dirs.add(it.stageId);
        final List<String> dirList = new ArrayList<String>(dirs);
        Collections.sort(dirList);
        for (String id : dirList) {
            final Item si = items.get(id + "/stage.txt");
            if (si == null) continue;
            try {
                final Stage s = StageFiles.parseStage(si.text);
                if (!s.id.equals(id)) throw new IllegalArgumentException("id does not match the folder");
                stages.put(id, s);
            } catch (RuntimeException ex) {
                rep.skipped++;
            }
        }

        for (String id : dirList) {
            final Stage incoming = stages.get(id);
            final Stage local = store.load(id);
            Stage target;                                            // the stage the runs of this folder go to
            if (incoming == null) {
                target = local;                                      // runs of a stage that is on the phone but not in the bundle
            } else if (sameRoad(local, incoming)) {
                rep.stagesSame++;                                    // the same id and the same road: merge
                target = local;
            } else if ((target = sameRoadUnderAnotherId(store, incoming)) != null) {
                rep.stagesSame++;                                    // this road came in before, as a copy: merge into that, never a second copy
            } else if (local == null) {
                store.save(incoming);
                rep.stagesAdded++;
                target = incoming;
            } else {
                target = copyUnderNewId(incoming, store, nowUtcMs);
                store.save(target);
                rep.stagesCopied++;
                rep.notes.add("'" + incoming.name() + "' is already here with the same id but a different road; added as '" + target.name() + "'.");
            }
            for (Item it : items.values()) {
                if (!it.stageId.equals(id) || it.runId == null) continue;
                if (target == null) {
                    rep.skipped++;                                   // runs of a stage nobody has
                    continue;
                }
                final RunResult r;
                try {
                    r = StageFiles.parseRun(it.text);
                    if (!r.stageId.equals(id) || !r.runId.equals(it.runId)) throw new IllegalArgumentException("ids do not match the file");
                    if (Math.abs(r.lengthM - target.lengthM()) >= 0.5) throw new IllegalArgumentException("not a run of this road");
                } catch (RuntimeException ex) {
                    rep.skipped++;
                    continue;
                }
                if (new File(new File(store.root(), target.id), "run_" + r.runId + ".txt").exists()) {
                    rep.runsAlready++;
                    continue;
                }
                store.saveRun(target.id.equals(r.stageId) ? r : withStage(r, target.id));
                rep.runsAdded++;
            }
        }
        return rep;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream(16 * 1024);
        final byte[] buf = new byte[16 * 1024];
        long n = 0;
        int k;
        while ((k = in.read(buf)) > 0) {
            n += k;
            if (n > MAX_ENTRY_BYTES) throw new Refused("a file inside expands to more than " + (MAX_ENTRY_BYTES >> 20) + " MB, which is not a stage bundle");
            bo.write(buf, 0, k);
        }
        return bo.toByteArray();
    }

    /** Same id and the same road: origin, both lines and every vertex of the route agree to a millimetre and a bit. */
    static boolean sameRoad(Stage a, Stage b) {
        if (a == null || b == null) return false;
        final double tol = 0.0015;
        if (Math.abs(a.geo.lat0 - b.geo.lat0) > 2e-7 || Math.abs(a.geo.lon0 - b.geo.lon0) > 2e-7) return false;
        if (!sameGate(a.start, b.start, tol) || !sameGate(a.finish, b.finish, tol)) return false;
        if (a.route.n != b.route.n) return false;
        for (int i = 0; i < a.route.n; i++) {
            if (Math.abs(a.route.x[i] - b.route.x[i]) > tol || Math.abs(a.route.y[i] - b.route.y[i]) > tol) return false;
        }
        return true;
    }

    /** A stage already on the phone, under another id, that is the same road as this one; null if none. */
    private static Stage sameRoadUnderAnotherId(StageStore store, Stage incoming) {
        for (Stage s : store.list()) if (sameRoad(s, incoming)) return s;
        return null;
    }

    private static boolean sameGate(Gate a, Gate b, double tol) {
        return Math.abs(a.cx - b.cx) <= tol && Math.abs(a.cy - b.cy) <= tol && Math.abs(a.bearingDeg - b.bearingDeg) <= tol && Math.abs(a.half - b.half) <= 0.05;
    }

    private static Stage copyUnderNewId(Stage s, StageStore store, long nowUtcMs) {
        final String id = store.newId(nowUtcMs);
        String base = s.name();
        if (base.length() > Stage.NAME_MAX - 11) base = base.substring(0, Stage.NAME_MAX - 11);
        return new Stage(id, base + " (imported)", s.kind, s.createdUtcMs, s.geo, s.start, s.finish, s.route,
                s.startMarkLat, s.startMarkLon, s.finishMarkLat, s.finishMarkLon);
    }

    private static RunResult withStage(RunResult r, String stageId) {
        return new RunResult(stageId, r.runId, r.csvName, r.startUtcMs, r.finished, r.statusText, r.totalS, r.lengthM, r.stepM,
                r.finished ? r.t : null, r.fixes, r.maxGapS, r.maxGapAtM, r.maxAccM, r.offRouteMaxM, r.startGapS, r.finishGapS,
                r.startSpeedMs, r.finishSpeedMs);
    }

    /** A name for the bundle file: TrackLogger-stages-20261013-182233.zip (the phone's local time). */
    static String fileName(long utcMs) {
        return "TrackLogger-stages-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date(utcMs)) + ".zip";
    }
}
