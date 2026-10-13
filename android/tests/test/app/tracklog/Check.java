package app.tracklog;

import java.util.ArrayList;
import java.util.List;

/** Tiny PASS/FAIL recorder. */
final class Check {
    static final List<String> LINES = new ArrayList<>();
    static int pass, fail;

    static synchronized boolean ok(String name, boolean cond, String detail) {
        String s = (cond ? "PASS  " : "FAIL  ") + name + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]");
        System.out.println(s);
        LINES.add(s);
        if (cond) pass++; else fail++;
        return cond;
    }

    static boolean ok(String name, boolean cond) { return ok(name, cond, ""); }

    /** Extra probes beyond the requested checks (resource cleanup after a failure); counted separately. */
    static int defectFail;

    static synchronized boolean defect(String name, boolean cond, String detail) {
        String s = (cond ? "PASS  " : "FAIL  ") + name + (detail == null || detail.isEmpty() ? "" : "   [" + detail + "]");
        System.out.println(s);
        LINES.add(s);
        if (!cond) defectFail++;
        return cond;
    }
}
