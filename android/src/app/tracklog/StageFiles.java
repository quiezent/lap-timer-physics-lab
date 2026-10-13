package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text form of a stage and of a run. Plain Java with no Android classes, and plain text on purpose: a person can read it, and
 * the Python tools in analysis/ read the same files. The formats are described in docs/stage-format.md.
 *
 * Every file starts with a line naming the kind and the version, and ends with a line "end". A file that does not end with "end"
 * (a write cut short) is rejected as a whole, never half-read.
 */
final class StageFiles {

    static final String STAGE_MAGIC = "TRACKLOGGER-STAGE 1";
    static final String RUN_MAGIC = "TRACKLOGGER-RUN 1";
    private static final int MAX_ROUTE_POINTS = 200000;
    private static final int MAX_PROFILE_POINTS = 100000;

    private StageFiles() { }

    // ------------------------------------------------------------------ stage

    static String formatStage(Stage s) {
        final StringBuilder b = new StringBuilder(64 * 1024);
        b.append(STAGE_MAGIC).append('\n');
        b.append("id ").append(s.id).append('\n');
        b.append("name ").append(s.name()).append('\n');
        b.append("kind ").append(s.kind).append('\n');
        b.append("created_utc_ms ").append(s.createdUtcMs).append('\n');
        b.append(String.format(Locale.US, "origin %.7f %.7f%n", s.geo.lat0, s.geo.lon0));
        b.append(String.format(Locale.US, "start_mark %.7f %.7f%n", s.startMarkLat, s.startMarkLon));
        b.append(String.format(Locale.US, "finish_mark %.7f %.7f%n", s.finishMarkLat, s.finishMarkLon));
        b.append(String.format(Locale.US, "start_gate %.3f %.3f %.3f %.1f%n", s.start.cx, s.start.cy, s.start.bearingDeg, s.start.half));
        b.append(String.format(Locale.US, "finish_gate %.3f %.3f %.3f %.1f%n", s.finish.cx, s.finish.cy, s.finish.bearingDeg, s.finish.half));
        b.append("route ").append(s.route.n).append('\n');
        for (int i = 0; i < s.route.n; i++) b.append(String.format(Locale.US, "r %.3f %.3f%n", s.route.x[i], s.route.y[i]));
        b.append("end\n");
        return b.toString();
    }

    static Stage parseStage(String text) {
        final String[] lines = text.split("\r?\n");
        if (lines.length < 3 || !lines[0].trim().equals(STAGE_MAGIC)) throw new IllegalArgumentException("not a stage file");
        if (!lines[lines.length - 1].trim().equals("end")) throw new IllegalArgumentException("stage file is cut short");
        String id = null, name = "Stage", kind = Stage.KIND_ROAD;
        long created = 0L;
        double[] origin = null, sm = null, fm = null, sg = null, fg = null;
        int declared = -1;
        final List<double[]> pts = new ArrayList<double[]>();
        for (int i = 1; i < lines.length - 1; i++) {
            final String ln = lines[i];
            if (ln.length() == 0) continue;
            final int sp = ln.indexOf(' ');
            final String key = sp < 0 ? ln : ln.substring(0, sp);
            final String rest = sp < 0 ? "" : ln.substring(sp + 1);
            if (key.equals("r")) {
                final double[] v = nums(rest, 2);
                if (pts.size() >= MAX_ROUTE_POINTS) throw new IllegalArgumentException("route too long");
                pts.add(v);
            } else if (key.equals("id")) {
                id = rest.trim();
            } else if (key.equals("name")) {
                name = rest;
            } else if (key.equals("kind")) {
                kind = rest.trim();
            } else if (key.equals("created_utc_ms")) {
                created = Long.parseLong(rest.trim());
            } else if (key.equals("origin")) {
                origin = nums(rest, 2);
            } else if (key.equals("start_mark")) {
                sm = nums(rest, 2);
            } else if (key.equals("finish_mark")) {
                fm = nums(rest, 2);
            } else if (key.equals("start_gate")) {
                sg = nums(rest, 4);
            } else if (key.equals("finish_gate")) {
                fg = nums(rest, 4);
            } else if (key.equals("route")) {
                declared = Integer.parseInt(rest.trim());
            }
            // unknown keys are skipped, so a later version can add lines
        }
        if (id == null || id.length() == 0 || !safeId(id)) throw new IllegalArgumentException("bad stage id");
        if (origin == null || sm == null || fm == null || sg == null || fg == null) throw new IllegalArgumentException("stage file is missing lines");
        if (declared != pts.size() || pts.size() < 2) throw new IllegalArgumentException("route length does not match");
        if (!kind.equals(Stage.KIND_ROAD)) throw new IllegalArgumentException("unknown stage kind: " + kind);
        final double[] xs = new double[pts.size()], ys = new double[pts.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = pts.get(i)[0];
            ys[i] = pts.get(i)[1];
        }
        return new Stage(id, name, kind, created, new Geo(origin[0], origin[1]),
                new Gate(sg[0], sg[1], sg[2], sg[3]), new Gate(fg[0], fg[1], fg[2], fg[3]), new Route(xs, ys),
                sm[0], sm[1], fm[0], fm[1]);
    }

    // ------------------------------------------------------------------ run

    static String formatRun(RunResult r) {
        final StringBuilder b = new StringBuilder(8 * 1024);
        b.append(RUN_MAGIC).append('\n');
        b.append("stage ").append(r.stageId).append('\n');
        b.append("run ").append(r.runId).append('\n');
        b.append("csv ").append(r.csvName).append('\n');
        b.append("utc_ms ").append(r.startUtcMs).append('\n');
        b.append("finished ").append(r.finished ? 1 : 0).append('\n');
        b.append("status ").append(r.statusText).append('\n');
        if (r.finished) b.append(String.format(Locale.US, "total_s %.3f%n", r.totalS));
        b.append(String.format(Locale.US, "length_m %.2f%n", r.lengthM));
        b.append(String.format(Locale.US, "step_m %.1f%n", r.stepM));
        b.append(String.format(Locale.US, "quality %d %.3f %.1f %.1f %.1f %.3f %.3f%n",
                r.fixes, r.maxGapS, r.maxGapAtM, r.maxAccM, r.offRouteMaxM, r.startGapS, r.finishGapS));
        b.append(String.format(Locale.US, "line_speeds %.2f %.2f%n", r.startSpeedMs, r.finishSpeedMs));
        if (r.finished) {
            b.append("n ").append(r.t.length).append('\n');
            for (int i = 0; i < r.t.length; i += 20) {
                b.append('p');
                for (int j = i; j < Math.min(r.t.length, i + 20); j++) b.append(String.format(Locale.US, " %.3f", r.t[j]));
                b.append('\n');
            }
        }
        b.append("end\n");
        return b.toString();
    }

    static RunResult parseRun(String text) {
        final String[] lines = text.split("\r?\n");
        if (lines.length < 3 || !lines[0].trim().equals(RUN_MAGIC)) throw new IllegalArgumentException("not a run file");
        if (!lines[lines.length - 1].trim().equals("end")) throw new IllegalArgumentException("run file is cut short");
        String stage = null, run = null, csv = "", status = "";
        long utc = 0L;
        boolean finished = false;
        double total = Double.NaN, length = Double.NaN, step = Double.NaN;
        double[] q = null, ls = null;
        int n = -1;
        final List<Double> prof = new ArrayList<Double>();
        for (int i = 1; i < lines.length - 1; i++) {
            final String ln = lines[i];
            if (ln.length() == 0) continue;
            final int sp = ln.indexOf(' ');
            final String key = sp < 0 ? ln : ln.substring(0, sp);
            final String rest = sp < 0 ? "" : ln.substring(sp + 1);
            if (key.equals("p")) {
                for (String tok : rest.trim().split(" +")) {
                    if (tok.length() == 0) continue;
                    if (prof.size() >= MAX_PROFILE_POINTS) throw new IllegalArgumentException("profile too long");
                    prof.add(Double.parseDouble(tok));
                }
            } else if (key.equals("stage")) {
                stage = rest.trim();
            } else if (key.equals("run")) {
                run = rest.trim();
            } else if (key.equals("csv")) {
                csv = rest;
            } else if (key.equals("utc_ms")) {
                utc = Long.parseLong(rest.trim());
            } else if (key.equals("finished")) {
                finished = rest.trim().equals("1");
            } else if (key.equals("status")) {
                status = rest;
            } else if (key.equals("total_s")) {
                total = Double.parseDouble(rest.trim());
            } else if (key.equals("length_m")) {
                length = Double.parseDouble(rest.trim());
            } else if (key.equals("step_m")) {
                step = Double.parseDouble(rest.trim());
            } else if (key.equals("quality")) {
                q = nums(rest, 7);
            } else if (key.equals("line_speeds")) {
                ls = nums(rest, 2);
            } else if (key.equals("n")) {
                n = Integer.parseInt(rest.trim());
            }
        }
        if (stage == null || run == null || !safeId(stage) || !safeId(run)) throw new IllegalArgumentException("bad run ids");
        if (q == null || Double.isNaN(length) || Double.isNaN(step)) throw new IllegalArgumentException("run file is missing lines");
        double[] t = null;
        if (finished) {
            if (Double.isNaN(total) || n != prof.size() || n < 2) throw new IllegalArgumentException("profile length does not match");
            t = new double[n];
            for (int i = 0; i < n; i++) t[i] = prof.get(i);
        }
        return new RunResult(stage, run, csv, utc, finished, status, finished ? total : Double.NaN, length, step, t,
                (int) q[0], q[1], q[2], q[3], q[4], q[5], q[6], ls == null ? -1.0 : ls[0], ls == null ? -1.0 : ls[1]);
    }

    // ------------------------------------------------------------------ helpers

    /** An id that is safe to use in a file name: letters, digits, underscore and hyphen, at most 64 characters. */
    static boolean safeId(String s) {
        if (s == null || s.length() == 0 || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_' && c != '-') return false;
        }
        return true;
    }

    /** Exactly n finite numbers separated by spaces. */
    private static double[] nums(String s, int n) {
        final String[] p = s.trim().split(" +");
        if (p.length != n) throw new IllegalArgumentException("expected " + n + " numbers");
        final double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = Double.parseDouble(p[i]);
            if (Double.isNaN(v[i]) || Double.isInfinite(v[i])) throw new IllegalArgumentException("not a finite number");
        }
        return v;
    }
}
