package app.tracklog;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The parts of release 1.4 that sit between the stage code and the screens, and that need no screen: the log reader, the scoring of
 * old recordings, and the words the screens show. Pure Java, run as part of mode "st".
 */
final class StageAppTests {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** A time with tenths, as the screens print one: 31:12.4 or 1:05:12.4. */
    static final Pattern TIME = Pattern.compile("\\d+:\\d\\d\\.\\d");

    private StageAppTests() { }

    static void run(File root) {
        System.out.println("\n===== ST.AP: log reader, scoring of old recordings, words on the screens =====");
        try {
            logReader();
            rescore();
            words();
            exporter();
            displayNames();
        } catch (Exception e) {
            e.printStackTrace(System.out);
            Check.ok("ST.AP the tests ran without an exception", false, e.toString());
        }
    }

    // ------------------------------------------------------------------ the log reader

    /** The plain way: readLine, the same row rules, and the same bound on the length of a line. */
    static LogReader.Log reference(BufferedReader br, String name) throws IOException {
        final LogReader.Log log = new LogReader.Log();
        log.name = name;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.length() > LogReader.MAX_LINE) continue;
            if (line.startsWith("L,")) {
                if (log.fixes.size() >= LogReader.MAX_FIXES) { log.truncated = true; continue; }
                final String[] p = line.split(",", -1);
                if (p.length != 12) continue;
                try {
                    final Fix f = new Fix(Long.parseLong(p[1]) / 1e6, Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Double.parseDouble(p[5]), Double.parseDouble(p[6]), Double.parseDouble(p[7]));
                    if (f.usable()) log.fixes.add(f);
                } catch (NumberFormatException e) {
                    // skipped
                }
            } else if (line.startsWith("# start_utc_ms,")) {
                try {
                    log.startUtcMs = Long.parseLong(line.substring("# start_utc_ms,".length()).trim());
                } catch (NumberFormatException ignored) {
                    // none
                }
            }
        }
        return log;
    }

    static String same(LogReader.Log a, LogReader.Log b) {
        if (a.fixes.size() != b.fixes.size()) return "fix count " + a.fixes.size() + " vs " + b.fixes.size();
        if (a.startUtcMs != b.startUtcMs) return "start " + a.startUtcMs + " vs " + b.startUtcMs;
        if (a.truncated != b.truncated) return "truncated " + a.truncated + " vs " + b.truncated;
        for (int i = 0; i < a.fixes.size(); i++) {
            final Fix x = a.fixes.get(i), y = b.fixes.get(i);
            if (x.t != y.t || x.lat != y.lat || x.lon != y.lon || x.speed != y.speed || x.bearing != y.bearing || x.hAcc != y.hAcc) {
                return "fix " + i + " differs";
            }
        }
        return "";
    }

    private static String lrow(long tUs, double lat, double lon, double speed, double bearing, double acc) {
        return "L," + tUs + "," + lat + "," + lon + ",40.0," + speed + "," + bearing + "," + acc + ",1.0,2.0," + (1791885600000L + tUs / 1000) + ",12";
    }

    /** Pads a row with spaces inside its last field until the line is n characters long. */
    private static String padTo(String row, int n) {
        final StringBuilder b = new StringBuilder(row);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    /** A recording-like text: header, sensor rows (most of it), GPS rows, events, damaged rows, blank lines, very long lines. */
    static String synthetic(long seed, boolean crlf, boolean finalNewline) {
        final Random rnd = new Random(seed);
        final String nl = crlf ? "\r\n" : "\n";
        final List<String> lines = new ArrayList<String>();
        lines.add("# TrackLogger 1.4 é");
        lines.add("# start_utc_ms,1791885600123");
        lines.add("# rows: L,t_us,lat,lon,alt,speed,bearing,hAcc,sAcc,bAcc,utc_ms,lag_ms | A,t_us,x,y,z | G,t_us,x,y,z | E,t_us,NAME");
        long t = 1000000L;
        for (int i = 0; i < 400; i++) {
            final int kind = rnd.nextInt(12);
            t += 1000 + rnd.nextInt(5000);
            switch (kind) {
                case 0: case 1: case 2:
                    lines.add(lrow(t, 3.1 + rnd.nextDouble() * 0.01, 101.7 + rnd.nextDouble() * 0.01, rnd.nextDouble() * 30.0, rnd.nextDouble() * 360.0, 1.0 + rnd.nextDouble() * 5.0));
                    break;
                case 3: lines.add("A," + t + "," + rnd.nextGaussian() + "," + rnd.nextGaussian() + "," + rnd.nextGaussian()); break;
                case 4: lines.add("G," + t + "," + rnd.nextGaussian() + "," + rnd.nextGaussian() + "," + rnd.nextGaussian()); break;
                case 5: lines.add("E," + t + ",STAGE_START"); break;
                case 6: lines.add("L," + t + ",3.1,101.7,only,five"); break;                      // too few fields
                case 7: lines.add(lrow(t, 3.1, 101.7, 1.0, 2.0, 3.0).replace("40.0", "not-a-number")); break;   // bad number in a field we do not use
                case 8: lines.add(lrow(t, 3.1, 101.7, 1.0, 2.0, 3.0).replace(",1.0,2.0,3.0,", ",x,2.0,3.0,")); break;   // bad speed
                case 9: lines.add(""); break;
                case 10: lines.add(padTo(lrow(t, 3.11, 101.71, 5.0, 90.0, 3.0), LogReader.MAX_LINE - rnd.nextInt(2))); break;       // 599 or 600 long: kept
                default: lines.add(padTo(lrow(t, 3.12, 101.72, 5.0, 90.0, 3.0), LogReader.MAX_LINE + 1 + rnd.nextInt(30))); break;  // too long: skipped
            }
            if (rnd.nextInt(40) == 0) lines.add("A," + t + "," + pad(rnd, 900));            // a very long sensor row
            if (rnd.nextInt(60) == 0) lines.add("# a very long comment " + pad(rnd, 1300));
        }
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            b.append(lines.get(i));
            if (i < lines.size() - 1 || finalNewline) b.append(nl);
        }
        return b.toString();
    }

    private static String pad(Random rnd, int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append((char) ('a' + rnd.nextInt(26)));
        return b.toString();
    }

    /** n valid GPS rows, made as they are asked for: a recording too big to hold in memory is not needed for the truncation test. */
    static final class RowStream extends InputStream {
        private final int rows;
        private int row = -1, at;
        private byte[] cur = "# start_utc_ms,1791885600000\n".getBytes(UTF8);

        RowStream(int rows) { this.rows = rows; }

        @Override public int read() {
            while (at >= cur.length) {
                row++;
                if (row >= rows) return -1;
                cur = (lrow(1000000L + row * 1000000L, 3.1 + row * 1e-7, 101.7, 5.0, 90.0, 3.0) + "\n").getBytes(UTF8);
                at = 0;
            }
            return cur[at++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) {
            int n = 0;
            while (n < len) {
                final int c = read();
                if (c < 0) break;
                b[off + n++] = (byte) c;
            }
            return n == 0 && len > 0 ? -1 : n;
        }
    }

    static void logReader() throws Exception {
        // the same answer as a plain reader, for text of every shape and a read buffer of every awkward size
        final int[] sizes = {1, 2, 3, 5, 7, 16, 64, 1000, 65536};
        int cases = 0;
        String bad = "";
        for (long seed = 1; seed <= 12 && bad.length() == 0; seed++) {
            for (int variant = 0; variant < 4 && bad.length() == 0; variant++) {
                final boolean crlf = (variant & 1) != 0, finalNl = (variant & 2) != 0;
                final String text = synthetic(seed * 10 + variant, crlf, finalNl);
                final byte[] bytes = text.getBytes(UTF8);
                final LogReader.Log want = reference(new BufferedReader(new java.io.StringReader(text)), "x");
                for (int size : sizes) {
                    final LogReader.Log got = LogReader.read(new ByteArrayInputStream(bytes), "x", size);
                    final String d = same(want, got);
                    cases++;
                    if (d.length() > 0) { bad = "seed " + seed + " variant " + variant + " buffer " + size + ": " + d; break; }
                }
            }
        }
        final LogReader.Log sample = LogReader.read(new ByteArrayInputStream(synthetic(5, false, true).getBytes(UTF8)), "x");
        Check.ok("ST.LR1 the log reader gives the same fixes and start time as a plain line-by-line reader on " + cases
                        + " texts (LF and CRLF line ends, with and without a last line end, read buffers of 1 to 65536 characters, blank, damaged and over-long lines)",
                bad.length() == 0 && sample.fixes.size() > 50 && sample.startUtcMs == 1791885600123L, bad + " fixes in one sample=" + sample.fixes.size());

        // lines of exactly the limit are read, one character more is not
        final String keep = padTo(lrow(2000000L, 3.2, 101.8, 5.0, 90.0, 3.0), LogReader.MAX_LINE);
        final String drop = padTo(lrow(3000000L, 3.3, 101.9, 5.0, 90.0, 3.0), LogReader.MAX_LINE + 1);
        final LogReader.Log lim = LogReader.read(new ByteArrayInputStream((keep + "\n" + drop + "\n" + lrow(4000000L, 3.4, 102.0, 5.0, 90.0, 3.0)).getBytes(UTF8)), "x");
        Check.ok("ST.LR2 a row of exactly " + LogReader.MAX_LINE + " characters is read, one of " + (LogReader.MAX_LINE + 1) + " is skipped, and the row after it is read",
                lim.fixes.size() == 2 && lim.fixes.get(0).t == 2.0 && lim.fixes.get(1).t == 4.0, "fixes=" + lim.fixes.size());

        // more rows than the bound: the first MAX_FIXES are used, and it says so
        final long t0 = System.nanoTime();
        final LogReader.Log big = LogReader.read(new RowStream(LogReader.MAX_FIXES + 25), "big.csv");
        final double secs = (System.nanoTime() - t0) / 1e9;
        Check.ok(String.format(Locale.US, "ST.LR3 a recording with %d GPS rows is cut at %d fixes and marked truncated (%.1f s on this computer)", LogReader.MAX_FIXES + 25, LogReader.MAX_FIXES, secs),
                big.fixes.size() == LogReader.MAX_FIXES && big.truncated && big.startUtcMs == 1791885600000L && big.fixes.get(LogReader.MAX_FIXES - 1).t == LogReader.MAX_FIXES, "fixes=" + big.fixes.size());

        // nothing, and nothing useful
        final LogReader.Log none1 = LogReader.read(new ByteArrayInputStream(new byte[0]), "empty.csv");
        final LogReader.Log none2 = LogReader.read(new ByteArrayInputStream("hello\nworld".getBytes(UTF8)), "text.csv");
        final LogReader.Log none3 = LogReader.read(new ByteArrayInputStream(new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0xFE, '\n', 'L', ','}), "bin.csv");
        Check.ok("ST.LR4 an empty file, a text file and a binary file give no fixes and no exception",
                none1.fixes.isEmpty() && none2.fixes.isEmpty() && none3.fixes.isEmpty() && none1.name.equals("empty.csv"), "");

        // the real recording, if it is there: the same fixes as the plain reader, in a time the phone can bear
        final String realPath = System.getProperty("real.log");
        if (realPath != null && new File(realPath).isFile()) {
            final File real = new File(realPath);
            long a0 = System.nanoTime();
            final LogReader.Log fast = LogReader.read(real);
            final double fastS = (System.nanoTime() - a0) / 1e9;
            a0 = System.nanoTime();
            final BufferedReader br = new BufferedReader(new FileReader(real), 1 << 16);
            final LogReader.Log slow;
            try { slow = reference(br, real.getName()); } finally { br.close(); }
            final double slowS = (System.nanoTime() - a0) / 1e9;
            final String d = same(slow, fast);
            Check.ok(String.format(Locale.US, "ST.LR5 the Sepang recording (%.0f MB) gives the same %d fixes as the plain reader; %.2f s here, the plain reader %.2f s",
                    real.length() / 1048576.0, fast.fixes.size(), fastS, slowS), d.length() == 0 && fast.fixes.size() > 1000, d);
        } else {
            System.out.println("(ST.LR5 skipped: no -Dreal.log)");
        }
    }

    // ------------------------------------------------------------------ scoring of old recordings

    static File tmp(String what) throws IOException {
        return Files.createTempDirectory("st_app_" + what + "_").toFile();
    }

    static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(UTF8));
    }

    static void rescore() throws Exception {
        final File base = tmp("rescore");
        final Map<String, String> in = StageFixtures.inputs();
        final File rec = new File(base, "rec");
        final List<File> files = new ArrayList<File>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (!e.getKey().startsWith("road1/session_")) continue;
            final File f = new File(rec, e.getKey().substring("road1/".length()));
            write(f, e.getValue());
            files.add(f);
        }
        Collections.sort(files);
        final File marking = new File(base, "marking.csv");
        write(marking, in.get("road1/marking.csv"));

        final StageStore store = new StageStore(new File(base, "stages"));
        store.save(StageFiles.parseStage(StageFiles.formatStage(StageFixtures.rebuild(marking, "st_road1", "Mountain road"))));
        final Stage stage = store.load("st_road1");

        final List<String> steps = new ArrayList<String>();
        final StageRescore.Report r1 = StageRescore.scoreFiles(store, stage, files, new StageRescore.Progress() {
            @Override public void step(int done, int total, String name) { steps.add(done + "/" + total); }
        });
        final List<RunResult> saved = store.runs(stage.id);
        Check.ok("ST.RS1 five old recordings of the mountain road: 7 runs found (6 finished, 1 cut short by the end of its recording), all new, all saved, progress reported 0/5 to 5/5",
                r1.files == 5 && r1.found == 7 && r1.finished == 6 && r1.notFinished == 1 && r1.added == 7 && r1.alreadySaved == 0
                        && r1.unreadable == 0 && saved.size() == 7 && steps.size() == 6 && steps.get(0).equals("0/5") && steps.get(5).equals("5/5"),
                r1.text() + " saved=" + saved.size() + " steps=" + steps);

        // what is saved is what the scorer gives for the same file, byte for byte
        String diff = "";
        for (File f : files) {
            final LogReader.Log log = LogReader.read(f);
            for (RunResult rr : Scorer.scoreAll(stage, log.fixes, f.getName(), (double) log.startUtcMs)) {
                final File rf = new File(new File(store.root(), stage.id), "run_" + rr.runId + ".txt");
                if (!rf.isFile() || !StageStore.read(rf).equals(StageFiles.formatRun(rr))) diff += " " + rr.runId;
            }
        }
        Check.ok("ST.RS2 every saved run file is exactly what scoring that recording gives (run ids named after the recording)", diff.length() == 0, diff);

        // the second time: nothing new, nothing changed
        final Map<String, String> before = StageBundleTests.snapshot(store.root());
        final StageRescore.Report r2 = StageRescore.scoreFiles(store, stage, files, null);
        final Map<String, String> after = StageBundleTests.snapshot(store.root());
        Check.ok("ST.RS3 scoring the same recordings again adds nothing: 7 found, all already saved, no file changes, no half-written file left",
                r2.found == 7 && r2.added == 0 && r2.alreadySaved == 7 && before.equals(after) && !hasTmp(after), r2.text());

        // a file that cannot be read is counted and does not stop the others
        final List<File> withBad = new ArrayList<File>(files);
        withBad.add(0, new File(base, "no_such_file.csv"));
        withBad.add(new File(base, "stages"));            // a folder
        final StageRescore.Report r3 = StageRescore.scoreFiles(store, stage, withBad, null);
        Check.ok("ST.RS4 a missing file and a folder in the list are counted as unreadable, and the five good recordings are still scored",
                r3.unreadable == 2 && r3.files == 5 && r3.found == 7 && r3.alreadySaved == 7 && r3.text().contains("2 files could not be read"), r3.text());

        // one picked file, read from a stream, gives what the same file gives from disk
        final StageStore store2 = new StageStore(new File(base, "stages2"));
        store2.save(stage);
        final File one = files.get(2);
        final StageRescore.Report r4 = StageRescore.scoreStream(store2, store2.load(stage.id), new FileInputStream(one), one.getName());
        final List<RunResult> got = store2.runs(stage.id);
        boolean eq = !got.isEmpty();
        for (RunResult rr : got) eq &= new File(new File(store.root(), stage.id), "run_" + rr.runId + ".txt").isFile()
                && StageStore.read(new File(new File(store.root(), stage.id), "run_" + rr.runId + ".txt")).equals(StageFiles.formatRun(rr));
        Check.ok("ST.RS5 a recording picked from the phone's files (a stream) gives the same runs as the same file read from disk",
                r4.files == 1 && r4.found == got.size() && eq && r4.added == got.size(), r4.text());

        // a recording of somewhere else: no runs, and it says so
        final StageStore store3 = new StageStore(new File(base, "stages3"));
        store3.save(stage);
        final File other = new File(rec, "session_20261013_120000.csv");
        write(other, in.get("road2/session_20261013_120000.csv"));
        final StageRescore.Report r5 = StageRescore.scoreFiles(store3, store3.load(stage.id), Collections.singletonList(other), null);
        Check.ok("ST.RS6 a recording of another road finds no run of this stage, saves nothing, and the sentence says it plainly",
                r5.found == 0 && store3.runs(stage.id).isEmpty() && r5.text().equals("Read 1 recording. None of them goes through Mountain road from the start line to the finish line."),
                r5.text());

        // a store that cannot be written to: the runs are found, not saved, and the sentence says so
        final File notAFolder = new File(base, "afile");
        write(notAFolder, "x");
        final StageRescore.Report r6 = StageRescore.scoreFiles(new StageStore(notAFolder), stage, files.subList(0, 1), null);
        Check.ok("ST.RS7 when the runs cannot be written the report counts them as not saved instead of as new",
                r6.found == 2 && r6.saveFailed == 2 && r6.added == 0 && r6.text().contains("2 runs could not be saved"), r6.text());

        // the sentences
        final StageRescore.Report s = new StageRescore.Report();
        s.stageName = "Fraser to Bentong";
        s.files = 1;
        s.found = 1; s.finished = 1; s.added = 1;
        final String one1 = s.text();
        s.files = 3; s.found = 4; s.finished = 3; s.notFinished = 1; s.added = 1; s.alreadySaved = 3; s.unreadable = 1; s.truncated = 1;
        final String many = s.text();
        Check.ok("ST.RS8 the sentences read as plain words (singular and plural, new and already saved, did not finish, unreadable, truncated)",
                one1.equals("Read 1 recording. Found 1 run of Fraser to Bentong: 1 new.")
                        && many.equals("Read 3 recordings. Found 4 runs of Fraser to Bentong (3 finished, 1 did not finish): 1 new, 3 already saved. 1 file could not be read. "
                        + "One recording has more GPS rows than the app reads; only the first 600000 of each were used."), one1 + " | " + many);

        // the names of recordings: the recorder's own pass through; anything else is made safe for a file name and cannot clash with another
        final String[] odd = {"My drive (1).csv", "Sepang test.csv", "a b.csv", "a_b.csv", "a  b.csv", "A B.csv", "Sepang \u2013 \u00f6l.csv", "x;y,z.csv", "..csv", ".csv",
                "Download/TrackLogger/session_20261013_100000.csv", "C:\\Users\\me\\log.csv", "session_.csv", "SESSION_20261013_100000.CSV"};
        boolean allSafe = true;
        final java.util.Set<String> distinct = new java.util.HashSet<String>();
        String bad = "";
        for (String n : odd) {
            final String id = Scorer.runIdFor(n, 7);
            if (!StageFiles.safeId(id) || id.length() > 64 || !id.equals(Scorer.runIdFor(n, 7))) { allSafe = false; bad += " [" + n + " -> " + id + "]"; }
            distinct.add(Scorer.idBase(n));
        }
        final StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append((char) ('a' + i % 26));
        final String longId = Scorer.runIdFor(longName + ".csv", 999999);
        Check.ok("ST.RS9 run ids: the recorder's names pass through unchanged; a name with spaces, brackets, a folder, capitals, accents or 300 letters gives a safe id of at most 64 characters, the same one every time; "
                        + "names that look alike once cleaned (a b, a_b, a  b, A B) still differ",
                Scorer.idBase("session_20261013_095638.csv").equals("20261013_095638") && Scorer.runIdFor("session_20261013_095638.csv", 3).equals("20261013_095638_3")
                        && Scorer.runIdFor(null, 1).equals("run_1") && Scorer.runIdFor("", 2).equals("run_2") && Scorer.runIdFor(".csv", 4).equals("run_4")
                        && Scorer.idBase("Download/TrackLogger/session_20261013_100000.csv").equals("20261013_100000") && allSafe
                        && StageFiles.safeId(longId) && longId.length() <= 64
                        && !Scorer.idBase("a b.csv").equals(Scorer.idBase("a_b.csv")) && !Scorer.idBase("a b.csv").equals(Scorer.idBase("a  b.csv")) && !Scorer.idBase("a b.csv").equals(Scorer.idBase("A B.csv"))
                        && distinct.size() >= odd.length - 2, bad + " distinct=" + distinct.size() + " long=" + longId);

        // a recording picked under a name the file system would not take as an id is still scored and saved, and again it is "already saved"
        final StageStore store4 = new StageStore(new File(base, "stages4"));
        store4.save(stage);
        final byte[] bytes = Files.readAllBytes(files.get(2).toPath());
        final StageRescore.Report o1 = StageRescore.scoreStream(store4, store4.load(stage.id), new ByteArrayInputStream(bytes), "My drive (1).csv");
        final StageRescore.Report o2 = StageRescore.scoreStream(store4, store4.load(stage.id), new ByteArrayInputStream(bytes), "My drive (1).csv");
        final StageRescore.Report o3 = StageRescore.scoreStream(store4, store4.load(stage.id), new ByteArrayInputStream(bytes), "My drive (2).csv");
        Check.ok("ST.RS10 a recording picked as 'My drive (1).csv' is scored and its runs saved (none lost as 'bad id'); picked again they are already saved; under another name they are separate runs",
                o1.found > 0 && o1.saveFailed == 0 && o1.added == o1.found && o2.added == 0 && o2.alreadySaved == o1.found && o3.added == o1.found && o3.saveFailed == 0
                        && store4.runs(stage.id).size() == 2 * o1.found, o1.text() + " | " + o2.text() + " | " + o3.text());
        STAGE = stage;
        RUNS = saved;
    }

    private static boolean hasTmp(Map<String, String> snap) {
        for (String k : snap.keySet()) if (k.endsWith(".tmp")) return true;
        return false;
    }

    // ------------------------------------------------------------------ the words on the screens

    private static Stage STAGE;                    // the mountain road, from the scoring tests
    private static List<RunResult> RUNS;

    static boolean hasTime(String s) {
        return TIME.matcher(s).find();
    }

    static void words() {
        final Stage st = STAGE;
        final List<RunResult> runs = RUNS;
        final int[] states = {StageLive.ARMED, StageLive.RECORDING, StageLive.RUNNING, StageLive.FINISHING, StageLive.SAVING,
                StageLive.MARK_START, StageLive.MARK_DRIVE, StageLive.MARK_SAVING};
        boolean wordsOk = StageText.stateWord(StageLive.IDLE).length() == 0 && StageText.guide(StageLive.IDLE, "x", "", "").length() == 0;
        String why = "";
        for (int s : states) {
            final String w = StageText.stateWord(s), g = StageText.guide(s, "Mountain road", "", "");
            if (w.length() == 0 || g.length() == 0) { wordsOk = false; why += " empty:" + s; }
            if (g.matches("(?s).*\\d.*") || w.matches("(?s).*\\d.*")) { wordsOk = false; why += " digit:" + s; }
        }
        Check.ok("ST.TX1 every stage state has a word and a sentence, and none of them holds a digit (so none can hold a time, a speed or a distance of the run)", wordsOk, why);

        boolean allRuns = true;
        final RunResult best = Compare.best(runs);
        final StringBuilder leaks = new StringBuilder();
        for (RunResult r : runs) {
            final String hidden = StageText.runLine(r, best, false);
            if (hasTime(hidden)) { allRuns = false; leaks.append(" run:").append(r.runId); }
            if (r.finished && !StageText.runLine(r, best, true).matches("(?s).*\\d+:\\d\\d\\.\\d.*")) { allRuns = false; leaks.append(" nolineshown:").append(r.runId); }
        }
        final String rowHidden = StageText.stageRow(st, runs, false), rowShown = StageText.stageRow(st, runs, true);
        Check.ok("ST.TX2 with numbers not allowed, a run line and a stage row hold no time (a dash stands where it would be); with numbers allowed they do",
                allRuns && !hasTime(rowHidden) && hasTime(rowShown) && rowHidden.contains(StageText.HIDDEN_TIME)
                        && rowShown.startsWith("Mountain road\n4.7 km   6 runs   best ") && rowHidden.startsWith("Mountain road\n4.7 km   6 runs   best --:--.-"),
                leaks + " row=" + rowHidden.replace('\n', '|') + " / " + rowShown.replace('\n', '|'));

        // the line after a run
        final List<RunResult> fin = new ArrayList<RunResult>();
        for (RunResult r : runs) if (r.finished) fin.add(r);
        final RunResult last = fin.get(fin.size() - 1);
        final List<RunResult> others = new ArrayList<RunResult>(fin);
        others.remove(last);
        final String l1 = StageText.lastRunLine(last, others), l0 = StageText.lastRunLine(last, new ArrayList<RunResult>());
        RunResult dnf = null;
        for (RunResult r : runs) if (!r.finished) dnf = r;
        final String ld = StageText.lastRunLine(dnf, others);
        Check.ok("ST.TX3 the line after a run: the time and how it compares with the best of the others; 'first finished run' when there is none; 'did not finish' with the reason",
                l1.startsWith("That run: " + StageText.time(last.totalS) + ". ") && l1.contains("your best") && hasTime(l1)
                        && l0.equals("That run: " + StageText.time(last.totalS) + ". It is the first finished run of this stage.")
                        && ld.startsWith("That run did not finish (") && !hasTime(ld) && StageText.lastRunLine(null, others).length() == 0,
                l1 + " | " + l0 + " | " + ld);

        Check.ok("ST.TX4 the road reminder and the first-use text are there, short enough to read, and say the things that matter (road rules, no times while moving, your responsibility)",
                StageText.ROAD_REMINDER.length() < 80 && StageText.FIRST_USE_BODY.contains("Obey the road rules")
                        && StageText.FIRST_USE_BODY.contains("shows no times") && StageText.FIRST_USE_BODY.contains("responsible for how you drive")
                        && StageText.FIRST_USE_BODY.length() < 1200, "reminder=" + StageText.ROAD_REMINDER.length() + " body=" + StageText.FIRST_USE_BODY.length());
    }

    // ------------------------------------------------------------------ backing up and restoring stages

    static final long NOW = 1791890000000L;

    static Context context(final File dir, final FakeContentResolver cr, final FakePrefs prefs) {
        return new Context() {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String n) { return null; }
            @Override public File getExternalFilesDir(String t) { return dir; }
            @Override public File getFilesDir() { return dir; }
            @Override public ContentResolver getContentResolver() { return cr; }
            @Override public SharedPreferences getSharedPreferences(String n, int m) { return prefs; }
        };
    }

    static void displayNames() throws Exception {
        final File base = tmp("names");
        final FakeContentResolver cr = new FakeContentResolver();
        final Context ctx = context(base, cr, new FakePrefs());
        cr.displayNames.put("content://media/external/downloads/1234", "session_20261013_100000.csv");
        cr.displayNames.put("content://docs/folder", "Download/TrackLogger/session_20261013_110000.csv");
        cr.displayNames.put("content://docs/blank", "  ");
        final String a = StageExporter.displayName(ctx, Uri.parse("content://media/external/downloads/1234"));
        final String b = StageExporter.displayName(ctx, Uri.parse("content://docs/folder"));
        final String c = StageExporter.displayName(ctx, Uri.parse("content://docs/unknown/my%20drive.csv"));
        final String d = StageExporter.displayName(ctx, Uri.parse("content://docs/blank"));
        cr.failQuery = new SecurityException("not allowed (injected)");
        final String e = StageExporter.displayName(ctx, Uri.parse("content://media/external/downloads/1234"));
        cr.failQuery = null;
        final String f = StageExporter.displayName(ctx, Uri.parse(""));
        Check.ok("ST.EX5 the name of a picked file is the one its provider gives (not the number at the end of a Downloads address); without one, the last part of the address; a refusing provider, a blank name or an empty address never raise anything",
                a.equals("session_20261013_100000.csv") && b.equals("session_20261013_110000.csv") && c.equals("my%20drive.csv") && d.equals("blank") && e.equals("1234") && f.equals("picked.csv"),
                a + " | " + b + " | " + c + " | " + d + " | " + e + " | " + f);
    }

    static void exporter() throws Exception {
        final File base = tmp("exporter");
        final File root = new File(base, "stages");
        StageBundleTests.storeWith(root, 61, 3, "st_a", "Fraser to Bentong");
        final StageStore store = StageBundleTests.storeWith(root, 62, 2, "st_b", "KKB to Fraser");
        final FakeContentResolver cr = new FakeContentResolver();
        final FakePrefs prefs = new FakePrefs();
        final Context ctx = context(base, cr, prefs);

        final StageExporter.Result r = StageExporter.backUp(ctx, store, NOW);
        final List<FakeContentResolver.Row> rows = cr.rows();
        final FakeContentResolver.Row row = rows.isEmpty() ? null : rows.get(0);
        final StageStore into = new StageStore(new File(base, "into"));
        final StageBundle.Report back = row == null || row.data == null ? null : StageBundleTests.imp(row.data, into);
        Check.ok("ST.EX1 backing up 2 stages and 5 runs writes one visible zip into Downloads/TrackLogger (name, type, folder), tells how many, remembers it for sharing, and the file imports back whole",
                r.ok && rows.size() == 1 && cr.pendingCount() == 0 && row.mime.equals("application/zip") && row.path.equals("Download/TrackLogger")
                        && row.name.matches("TrackLogger-stages-\\d{8}-\\d{6}\\.zip") && r.message.contains("2 stages, 5 runs") && r.message.contains("uninstall")
                        && prefs.getString(StageExporter.KEY_URI, "").equals(r.uri) && prefs.getString(StageExporter.KEY_NAME, "").equals(row.name)
                        && back != null && back.stagesAdded == 2 && back.runsAdded == 5,
                r.message + " rows=" + rows.size());

        final FakeContentResolver cr0 = new FakeContentResolver();
        final StageExporter.Result none = StageExporter.backUp(context(base, cr0, new FakePrefs()), new StageStore(new File(base, "nothing")), NOW);
        Check.ok("ST.EX2 with no stages there is nothing to back up: it says so and does not touch the phone's file store",
                !none.ok && none.message.equals("There are no stages to back up yet.") && cr0.insertCalls == 0, none.message);

        // every way the write can go wrong ends with a sentence and no half-written file left in Downloads
        final String[] how = {"the file store refuses", "the disk fills while writing", "the last flush fails on close"};
        final boolean[] cleaned = new boolean[3];
        final boolean[] said = new boolean[3];
        for (int i = 0; i < 3; i++) {
            final FakeContentResolver c = new FakeContentResolver();
            if (i == 0) c.failInsert = true;
            if (i == 1) c.failWrite = true;
            if (i == 2) c.failClose = true;
            final FakePrefs p = new FakePrefs();
            final StageExporter.Result f = StageExporter.backUp(context(base, c, p), store, NOW);
            cleaned[i] = !f.ok && c.rows().isEmpty() && f.uri.length() == 0 && p.getString(StageExporter.KEY_URI, "").length() == 0;
            said[i] = f.message.startsWith("The backup was not saved (") && f.message.endsWith("Free some space and try again.");
        }
        Check.ok("ST.EX3 when the file store refuses, the disk fills, or the last flush fails, the backup is reported as not saved, the unfinished file is removed, and nothing is remembered for sharing",
                cleaned[0] && cleaned[1] && cleaned[2] && said[0] && said[1] && said[2], Arrays.toString(cleaned) + Arrays.toString(said) + " " + Arrays.toString(how));

        // restoring
        final byte[] bundle = StageBundle.exportBytes(store);
        cr.documents.put("content://docs/bundle", bundle);
        cr.documents.put("content://docs/text", "just some words".getBytes(UTF8));
        cr.documents.put("content://docs/cut", Arrays.copyOf(bundle, bundle.length / 2));
        final StageStore phone = new StageStore(new File(base, "phone"));
        final String first = StageExporter.restore(ctx, Uri.parse("content://docs/bundle"), phone, NOW);
        final String again = StageExporter.restore(ctx, Uri.parse("content://docs/bundle"), phone, NOW);
        final String text = StageExporter.restore(ctx, Uri.parse("content://docs/text"), phone, NOW);
        final String cut = StageExporter.restore(ctx, Uri.parse("content://docs/cut"), phone, NOW);
        final String missing = StageExporter.restore(ctx, Uri.parse("content://docs/gone"), phone, NOW);
        cr.failOpenInput = new SecurityException("permission denied");
        final String denied = StageExporter.restore(ctx, Uri.parse("content://docs/bundle"), phone, NOW);
        cr.failOpenInput = null;
        Check.ok("ST.EX4 restoring: a bundle is merged in ('2 stages added and 5 runs added'), again it is 'already here', a text file holds no stages, a cut-short zip, a missing file and a refused file each get a sentence",
                first.equals("2 stages added and 5 runs added.") && again.equals("2 stages already here and 5 runs already here.")
                        && text.equals("Nothing to import: that file holds no stages.") && cut.startsWith("That file is not a readable zip file (") && cut.endsWith(").")
                        && missing.startsWith("That file could not be read (no such document") && denied.equals("That file could not be opened (permission denied)."),
                first + " | " + again + " | " + text + " | " + cut + " | " + missing + " | " + denied);
    }
}
