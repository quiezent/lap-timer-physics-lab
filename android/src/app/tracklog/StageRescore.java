package app.tracklog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scores old recordings against a stage and keeps the runs found: the recordings made before the stage existed, or on a phone that
 * was not armed. Plain Java, so it is tested on a computer.
 *
 * A run is named after the recording it was found in (Scorer), so scoring a recording again gives the same run ids and the same
 * numbers: it is safe to do twice, and a run that is already saved is simply written again.
 */
final class StageRescore {

    interface Progress {
        /** @param done recordings finished so far, @param total how many there are, @param name the one being read now */
        void step(int done, int total, String name);
    }

    /** What the scoring found, for one sentence on the screen. */
    static final class Report {
        int files;                 // recordings read
        int unreadable;            // recordings that could not be read at all
        int found, finished, notFinished;
        int added, alreadySaved;
        int saveFailed;            // runs found but not written (disk full, ...)
        int truncated;             // recordings with more GPS rows than the app reads
        String stageName = "";

        String text() {
            final StringBuilder b = new StringBuilder();
            b.append("Read ").append(plural(files, "recording")).append(". ");
            if (found == 0) {
                b.append("None of them goes through ").append(stageName).append(" from the start line to the finish line.");
            } else {
                b.append("Found ").append(plural(found, "run")).append(" of ").append(stageName);
                if (notFinished > 0) b.append(" (").append(finished).append(" finished, ").append(notFinished).append(" did not finish)");
                b.append(": ");
                if (added == 0) b.append("all ").append(found == 1 ? "was" : "were").append(" already saved.");
                else if (alreadySaved == 0) b.append(added).append(" new.");
                else b.append(added).append(" new, ").append(alreadySaved).append(" already saved.");
            }
            if (unreadable > 0) b.append(' ').append(plural(unreadable, "file")).append(" could not be read.");
            if (saveFailed > 0) b.append(' ').append(plural(saveFailed, "run")).append(" could not be saved (is the phone full?).");
            if (truncated > 0) {
                b.append(' ').append(truncated == 1 ? "One recording has" : truncated + " recordings have")
                        .append(" more GPS rows than the app reads; only the first ").append(LogReader.MAX_FIXES).append(" of each were used.");
            }
            return b.toString();
        }

        private static String plural(int n, String what) {
            return n + " " + what + (n == 1 ? "" : "s");
        }
    }

    private StageRescore() { }

    /** Every file in order; a file that cannot be read is counted and the others go on. @param progress may be null */
    static Report scoreFiles(StageStore store, Stage stage, List<File> files, Progress progress) {
        final Report rep = new Report();
        rep.stageName = stage.name();
        final Set<String> have = savedIds(store, stage);
        int done = 0;
        for (File f : files) {
            if (progress != null) progress.step(done, files.size(), f.getName());
            try {
                final LogReader.Log log = LogReader.read(f);
                add(store, stage, log, have, rep);
            } catch (IOException e) {
                rep.unreadable++;
            } catch (RuntimeException e) {
                rep.unreadable++;
            }
            done++;
        }
        if (progress != null) progress.step(done, files.size(), "");
        return rep;
    }

    /** One recording that was picked rather than found (the stream is read and closed). */
    static Report scoreStream(StageStore store, Stage stage, InputStream in, String name) {
        final Report rep = new Report();
        rep.stageName = stage.name();
        final Set<String> have = savedIds(store, stage);
        try {
            add(store, stage, LogReader.read(in, name), have, rep);
        } catch (IOException e) {
            rep.unreadable++;
        } catch (RuntimeException e) {
            rep.unreadable++;
        }
        return rep;
    }

    private static Set<String> savedIds(StageStore store, Stage stage) {
        final Set<String> have = new HashSet<String>();
        for (RunResult r : store.runs(stage.id)) have.add(r.runId);
        return have;
    }

    private static void add(StageStore store, Stage stage, LogReader.Log log, Set<String> have, Report rep) {
        rep.files++;
        if (log.truncated) rep.truncated++;
        for (RunResult r : Scorer.scoreAll(stage, log.fixes, log.name, (double) log.startUtcMs)) {
            rep.found++;
            if (r.finished) rep.finished++; else rep.notFinished++;
            try {
                store.saveRun(r);
                if (have.add(r.runId)) rep.added++; else rep.alreadySaved++;
            } catch (IOException e) {
                rep.saveFailed++;
            }
        }
    }
}
