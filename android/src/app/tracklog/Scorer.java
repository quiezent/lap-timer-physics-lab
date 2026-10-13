package app.tracklog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Scores recorded GPS fixes against a stage, offline: every run of the stage found in them, in order. Plain Java with no Android
 * classes. It runs the same StageRun that runs live in the recorder, with the live failsafes switched off, so a recording scored here
 * gives the numbers it gave on the phone (the tests check this), and an old recording can be scored against a stage made later.
 */
final class Scorer {

    private Scorer() { }

    /**
     * @param clockToUtcMs added to (clock seconds * 1000) to give wall-clock milliseconds; 0 if unknown
     * @return one RunResult per run: finished runs with a time, and, if the fixes end while a run is open, one that did not finish
     */
    static List<RunResult> scoreAll(Stage stage, List<Fix> fixes, String csvName, double clockToUtcMs) {
        final List<Fix> sorted = new ArrayList<Fix>();
        for (Fix f : fixes) if (f != null && f.usable()) sorted.add(f);
        Collections.sort(sorted, new Comparator<Fix>() {
            @Override
            public int compare(Fix a, Fix b) {
                return Double.compare(a.t, b.t);
            }
        });
        final String base = idBase(csvName);
        final List<RunResult> out = new ArrayList<RunResult>();
        StageRun run = new StageRun(stage, false);
        Fix lastFix = null;
        for (Fix f : sorted) {
            if (lastFix != null && !(f.t > lastFix.t)) continue;
            run.onFix(f);
            lastFix = f;
            if (run.done()) {
                final RunResult r = run.result(runId(base, out.size() + 1), csvName, clockToUtcMs);
                if (r != null) out.add(r);
                run = new StageRun(stage, false);
            }
        }
        if (run.phase() == StageRun.RUNNING || run.phase() == StageRun.POST) {
            run.endNow("the recording ended before the finish line");
            final RunResult r = run.result(runId(base, out.size() + 1), csvName, clockToUtcMs);
            if (r != null) out.add(r);
        }
        return out;
    }

    private static String runId(String base, int n) {
        return (base.length() == 0 ? "run" : base) + String.format(Locale.US, "_%d", n);
    }

    /** The id the n-th run found in a session file gets, for a live run that must be named as a later scoring would name it. */
    static String runIdFor(String csvName, int n) {
        return runId(idBase(csvName), n);
    }

    /**
     * What a recording's name contributes to its runs' ids: the name without "session_" and ".csv". The recorder's own names
     * (session_20261013_095638.csv) pass through unchanged, so scoring the same recording again, from the app's folder or from a copy in
     * Downloads, gives the same ids. A name from somewhere else may hold spaces, brackets or letters that cannot go into a file name
     * (StageFiles.safeId): those become underscores, and a short fingerprint of the original name is added so that two different names
     * that look alike afterwards ("a b.csv", "a_b.csv") do not share ids. At most 57 characters, which leaves room for "_" and the run number.
     */
    static String idBase(String csvName) {
        String n = csvName == null ? "" : csvName;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("\\.csv$", "").replaceAll("^session_", "");
        if (n.length() <= 56 && (n.length() == 0 || StageFiles.safeId(n))) return n;
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length() && b.length() < 48; i++) {
            final char c = n.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
            b.append(ok ? c : '_');
        }
        int h = 0x811C9DC5;                                     // FNV-1a over the UTF-16 code units of the whole original name
        for (int i = 0; i < n.length(); i++) {
            h ^= n.charAt(i);
            h *= 0x01000193;
        }
        return b.append('-').append(String.format(Locale.US, "%08x", h)).toString();
    }
}
