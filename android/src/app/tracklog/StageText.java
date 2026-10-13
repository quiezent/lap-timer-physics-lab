package app.tracklog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The words and numbers the stage screens show, made by plain functions so they can be tested. Plain Java with no Android classes.
 *
 * Times are shown to a tenth of a second: phone GPS cannot do better, and a hundredth would claim a precision the method does not
 * have (see docs/stages.md).
 */
final class StageText {

    private StageText() { }

    /** 31:12.4, or 1:05:12.4 from an hour up. Rounded once to a tenth. */
    static String time(double s) {
        if (Double.isNaN(s) || s < 0.0 || s > 359999.0) return "--:--.-";
        final long ds = Math.round(s * 10.0);
        final long tenths = ds % 10, sec = (ds / 10) % 60, min = (ds / 600) % 60, hr = ds / 36000;
        if (hr > 0) return String.format(Locale.US, "%d:%02d:%02d.%d", hr, min, sec, tenths);
        return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths);
    }

    /** +24.3 s, -0.4 s, 0.0 s */
    static String delta(double s) {
        if (Double.isNaN(s)) return "";
        final double r = Math.round(s * 10.0) / 10.0;
        if (r == 0.0) return "0.0 s";
        return String.format(Locale.US, "%+.1f s", r);
    }

    /** 38.4 km, 850 m */
    static String dist(double m) {
        if (m >= 1000.0) return String.format(Locale.US, "%.1f km", m / 1000.0);
        return String.format(Locale.US, "%.0f m", m);
    }

    /** 13 Oct 18:22 (the phone's time zone) */
    static String when(long utcMs) {
        if (utcMs <= 0L) return "unknown time";
        return new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date(utcMs));
    }

    /** One line about a run, for a list: "13 Oct 18:22   31:12.4   +24.3 s" (the delta against the best, blank for the best itself). */
    static String runLine(RunResult r, RunResult best) {
        return runLine(r, best, true);
    }

    /**
     * As above; with numbers false (the car is moving, see StageLive.numbersAllowed) the line says when and whether the run finished
     * and nothing about how long it took.
     */
    static String runLine(RunResult r, RunResult best, boolean numbers) {
        final StringBuilder b = new StringBuilder();
        b.append(when(r.startUtcMs)).append("   ");
        if (!r.finished) {
            b.append("did not finish");
            if (r.statusText.length() > 0) b.append(" (").append(r.statusText).append(")");
            return b.toString();
        }
        if (!numbers) return b.append(HIDDEN_TIME).toString();
        b.append(time(r.totalS));
        if (best != null && r != best && best.finished) b.append("   ").append(delta(r.totalS - best.totalS));
        else if (r == best) b.append("   best");
        return b.toString();
    }

    /** Shown where a time would be while the car is moving. */
    static final String HIDDEN_TIME = "--:--.-";
    static final String HIDDEN_NOTE = "Times are hidden while the car is moving. They come back when you have stopped.";

    /** Three lines for the stage list: the name; the length and how many runs; the best time (only when numbers are allowed). */
    static String stageRow(Stage s, List<RunResult> runs, boolean numbers) {
        int finished = 0;
        for (RunResult r : runs) if (r.finished) finished++;
        final StringBuilder b = new StringBuilder();
        b.append(s.name()).append('\n');
        b.append(dist(s.lengthM())).append("   ");
        b.append(finished == 0 ? "no finished runs yet" : finished == 1 ? "1 run" : finished + " runs");
        final RunResult best = Compare.best(runs);
        if (best != null) b.append("   best ").append(numbers ? time(best.totalS) : HIDDEN_TIME);
        return b.toString();
    }

    /**
     * The last run, against the best of the others, for the panel that shows the result once the car has stopped. Numbers only.
     * @param others the stage's other finished runs (may be empty)
     */
    static String lastRunLine(RunResult last, List<RunResult> others) {
        if (last == null) return "";
        if (!last.finished) {
            return "That run did not finish" + (last.statusText.length() > 0 ? " (" + last.statusText + ")." : ".");
        }
        final RunResult best = Compare.best(others);
        final StringBuilder b = new StringBuilder("That run: ").append(time(last.totalS)).append(". ");
        if (best == null) return b.append("It is the first finished run of this stage.").toString();
        if (!last.comparableWith(best)) return b.append("Best before: ").append(time(best.totalS)).append(".").toString();
        final Compare c = Compare.of(last, best);
        return b.append(cap(verdict(c.totalDeltaS, c.sameBandS(), "your best"))).append(" (").append(time(best.totalS)).append(").").toString();
    }

    // ------------------------------------------------------------------ axes of the comparison graph

    /** The smallest of 1, 2, 5, 10, 20, 50, ... that is at least v (and at least 1). */
    static double niceCeil(double v) {
        if (Double.isNaN(v) || v <= 1.0) return 1.0;
        double mag = 1.0;
        while (mag * 10.0 < v) mag *= 10.0;
        final double[] steps = {1.0, 2.0, 5.0, 10.0};
        for (double s : steps) if (s * mag >= v) return s * mag;
        return 10.0 * mag;
    }

    /** Distance between tick marks along the route, in metres: 250 m, 500 m, 1, 2, 5, 10, 20 km, whichever gives at most 8 marks. */
    static double tickStepM(double lengthM) {
        final double[] steps = {250.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0};
        for (double s : steps) if (lengthM / s <= 8.0) return s;
        return 50000.0;
    }

    // ------------------------------------------------------------------ what the stage state means, in words

    /** The big word on the status line while a stage is armed, recording or being marked. */
    static String stateWord(int state) {
        switch (state) {
            case StageLive.ARMED: return "ARMED";
            case StageLive.RECORDING: return "RECORDING";
            case StageLive.RUNNING: return "ON STAGE";
            case StageLive.FINISHING: return "FINISHED";
            case StageLive.SAVING: return "SAVING";
            case StageLive.MARK_START: return "MARKING";
            case StageLive.MARK_DRIVE: return "MARKING";
            case StageLive.MARK_SAVING: return "SAVING";
            default: return "";
        }
    }

    /**
     * What is going on and what to do, in plain words. None of these sentences holds a time, a speed or a distance about the run.
     * @param markHint StageLive.markHint, @param message StageLive.message
     */
    static String guide(int state, String stageName, String markHint, String message) {
        switch (state) {
            case StageLive.ARMED:
                return "Armed for " + stageName + ". Recording starts by itself as you come to the start line and the run ends after the "
                        + "finish line. Leave the phone alone while you drive.";
            case StageLive.RECORDING:
                return "Recording. Waiting for the start line.";
            case StageLive.RUNNING:
                return "On the stage. Nothing is shown until you have stopped.";
            case StageLive.FINISHING:
                return "Finish line crossed. Recording a few seconds more.";
            case StageLive.SAVING:
                return message.length() > 0 ? message : "Saving.";
            case StageLive.MARK_START:
                return markHint.length() > 0 ? markHint : "Park at the start of the stage.";
            case StageLive.MARK_DRIVE:
                return markHint.length() > 0 ? markHint : "Start marked. Drive the stage, then park at the finish.";
            case StageLive.MARK_SAVING:
                return "Stage marked. Saving.";
            default:
                return "";
        }
    }

    /** Kept on the screen the whole time a stage is armed or recording. */
    static final String ROAD_REMINDER = "Drive legally and safely. This app never asks you to go faster.";

    static final String FIRST_USE_TITLE = "Before you time a drive";

    static final String FIRST_USE_BODY =
            "Stages records a drive between a start and a finish you choose, and lets you compare it with your other drives of the same "
                    + "road afterwards.\n\n"
                    + "• Obey the road rules and the speed limits. A better time is never a reason to break them.\n"
                    + "• Where timing yourself is allowed (a closed road, a permitted event, a track) the times mean what they say. On an "
                    + "ordinary road, use the comparison to understand how you drove, not to beat a record.\n"
                    + "• While the car is moving the app shows no times and no comparison. Put the phone in a mount before you set off, "
                    + "and touch it only when you are parked somewhere safe.\n"
                    + "• You are responsible for how you drive.";

    /**
     * What to say about a difference between two runs.
     *
     * @param bandS differences smaller than this are "about the same" (Compare.sameBandS)
     */
    static String verdict(double deltaS, double bandS, String against) {
        if (Math.abs(deltaS) < bandS) return "about the same as " + against;
        return delta(deltaS) + (deltaS > 0 ? " slower than " : " faster than ") + against;
    }

    /**
     * The comparison as text: the headline, then the sections. A and B as in Compare.
     *
     * @param bName what B is called ("best", "previous")
     */
    static String compareText(Stage stage, Compare c, String bName) {
        final StringBuilder b = new StringBuilder();
        b.append(stage.name()).append("   ").append(dist(stage.lengthM())).append('\n');
        b.append("This run   ").append(time(c.a.totalS)).append("   ").append(when(c.a.startUtcMs)).append('\n');
        b.append(cap(bName)).append("   ").append(time(c.b.totalS)).append("   ").append(when(c.b.startUtcMs)).append('\n');
        final double band = c.sameBandS();
        b.append(verdict(c.totalDeltaS, band, bName)).append('\n');
        b.append("\nWhere the difference came from, every ").append(dist(c.sectionM)).append(":\n");
        final Compare.Section lost = c.mostLost(), gained = c.mostGained();
        for (Compare.Section s : c.sections) {
            final String span = c.sectionM < 1000.0
                    ? String.format(Locale.US, "%5.2f-%5.2f km", s.fromM / 1000.0, s.toM / 1000.0)
                    : String.format(Locale.US, "%5.1f-%5.1f km", s.fromM / 1000.0, s.toM / 1000.0);
            b.append(' ').append(span).append(String.format(Locale.US, "  %8s", Math.abs(s.deltaS()) < 0.05 ? "0.0 s" : delta(s.deltaS())));
            if (s == lost && s.deltaS() >= band) b.append("   slowest part");
            else if (s == gained && s.deltaS() <= -band) b.append("   fastest part");
            b.append('\n');
        }
        appendWarnings(b, c.a, "This run");
        appendWarnings(b, c.b, cap(bName));
        b.append("\nFor these two runs a difference under about ").append(String.format(Locale.US, "%.1f", band))
                .append(" s is inside what phone GPS can tell apart.");
        return b.toString();
    }

    private static void appendWarnings(StringBuilder b, RunResult r, String label) {
        final List<String> w = r.warnings();
        for (String x : w) b.append("\nNote, ").append(label.toLowerCase(Locale.US)).append(": ").append(x);
    }

    static String cap(String s) {
        return s.length() == 0 ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
