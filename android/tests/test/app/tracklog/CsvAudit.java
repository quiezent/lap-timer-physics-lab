package app.tracklog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Parses the session CSV and checks it against the format documented at the top of Recorder.java. */
final class CsvAudit {
    final File file;
    final List<String> header = new ArrayList<>();
    final List<String> data = new ArrayList<>();
    final Map<String, List<String[]>> rows = new TreeMap<>();
    boolean endsWithNewline, blankLine, headerAfterData;

    CsvAudit(File f) throws IOException {
        file = f;
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        endsWithNewline = text.endsWith("\n");
        String[] ls = text.split("\n", -1);
        int n = endsWithNewline ? ls.length - 1 : ls.length;
        for (int i = 0; i < n; i++) {
            String ln = ls[i];
            if (ln.isEmpty()) { blankLine = true; continue; }
            if (ln.charAt(0) == '#') {
                if (!data.isEmpty()) headerAfterData = true;
                header.add(ln);
            } else {
                data.add(ln);
                String[] fl = ln.split(",", -1);
                rows.computeIfAbsent(fl[0], k -> new ArrayList<>()).add(fl);
            }
        }
    }

    List<String[]> kind(String k) { return rows.getOrDefault(k, new ArrayList<>()); }

    static boolean isLong(String s) { try { Long.parseLong(s); return true; } catch (Exception e) { return false; } }

    static boolean fin(String s) {
        try { double d = Double.parseDouble(s); return !Double.isNaN(d) && !Double.isInfinite(d); } catch (Exception e) { return false; }
    }

    static boolean allFin(String[] f, int from, int to) {
        for (int i = from; i <= to; i++) if (!fin(f[i])) return false;
        return true;
    }

    static final java.util.Set<String> KNOWN_E = new java.util.HashSet<>(java.util.Arrays.asList(
            "START", "STOP", "CAL", "CALFAIL", "LAP", "STAGE", "STAGE_START", "STAGE_FINISH", "STAGE_DNF", "STAGE_ABORT", "MARK_START", "MARK_FINISH"));

    /** Documented field counts: A,G=5  L=12  S=4  K=4  E>=3 (START/STOP=3, CAL=11 per the emit() in finishCalibration, LAP=5). */
    List<String> badRows() {
        List<String> bad = new ArrayList<>();
        for (String ln : data) {
            String[] f = ln.split(",", -1);
            String why = null;
            switch (f[0]) {
                case "A": case "G":
                    if (f.length != 5) why = "fields=" + f.length; else if (!isLong(f[1]) || !allFin(f, 2, 4)) why = "non-numeric";
                    break;
                case "L":
                    if (f.length != 12) why = "fields=" + f.length; else if (!isLong(f[1]) || !allFin(f, 2, 11)) why = "non-numeric";
                    break;
                case "S":
                    if (f.length != 4) why = "fields=" + f.length; else if (!isLong(f[1]) || !isLong(f[2]) || !isLong(f[3])) why = "non-numeric";
                    break;
                case "K":
                    if (f.length != 4) why = "fields=" + f.length; else if (!isLong(f[1]) || !(f[2].equals("A") || f[2].equals("G")) || !isLong(f[3])) why = "bad values";
                    break;
                case "E":
                    if (f.length < 3 || !isLong(f[1])) why = "bad E row";
                    else if ((f[2].equals("START") || f[2].equals("STOP")) && f.length != 3) why = "fields=" + f.length;
                    else if (f[2].equals("CAL") && (f.length != 11 || !allFin(f, 3, 10))) why = "CAL fields=" + f.length;
                    else if (f[2].equals("CALFAIL") && f.length != 3) why = "fields=" + f.length;
                    else if (f[2].equals("LAP") && (f.length != 5 || !isLong(f[3]) || !fin(f[4]))) why = "LAP fields=" + f.length;
                    else if (f[2].equals("STAGE") && (f.length != 4 || f[3].isEmpty())) why = "STAGE fields=" + f.length;
                    else if (f[2].equals("STAGE_START") && f.length != 3) why = "STAGE_START fields=" + f.length;
                    else if (f[2].equals("STAGE_FINISH") && (f.length != 4 || !fin(f[3]))) why = "STAGE_FINISH fields=" + f.length;
                    else if ((f[2].equals("STAGE_DNF") || f[2].equals("STAGE_ABORT")) && f.length != 4) why = f[2] + " fields=" + f.length;
                    else if ((f[2].equals("MARK_START") || f[2].equals("MARK_FINISH")) && (f.length != 6 || !allFin(f, 3, 5))) why = f[2] + " fields=" + f.length;
                    else if (!KNOWN_E.contains(f[2])) why = "unknown event " + f[2];
                    break;
                default:
                    why = "unknown row kind";
            }
            if (why != null) bad.add(why + " :: " + ln);
        }
        return bad;
    }

    /** null if t_us (column 1) is monotonic, else a description of the first violation. */
    static String monotonic(List<String[]> rs, boolean strict) {
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < rs.size(); i++) {
            long t = Long.parseLong(rs.get(i)[1]);
            if (strict ? t <= prev : t < prev) return "row " + i + " t_us=" + t + " after " + prev;
            prev = t;
        }
        return null;
    }

    List<String[]> kStream(String which) {
        List<String[]> out = new ArrayList<>();
        for (String[] f : kind("K")) if (f[2].equals(which)) out.add(f);
        return out;
    }

    /** First n lines of the file (header lines included). */
    String firstLines(int n) {
        List<String> all = new ArrayList<>(header);
        all.addAll(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, all.size()); i++) sb.append(all.get(i)).append('\n');
        return sb.toString();
    }

    /** Three sample rows of one kind: first, middle, last (fewer if fewer exist). */
    List<String> samples(String k) {
        List<String> ls = new ArrayList<>();
        for (String ln : data) if (ln.startsWith(k + ",")) ls.add(ln);
        if (ls.size() <= 3) return ls;
        List<String> out = new ArrayList<>();
        out.add(ls.get(0)); out.add(ls.get(ls.size() / 2)); out.add(ls.get(ls.size() - 1));
        return out;
    }
}
