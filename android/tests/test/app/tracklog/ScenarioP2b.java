package app.tracklog;

import java.io.File;
import java.util.List;

/** Part 2, scenarios d-g: export failures, onDestroy, Recorder.start() failure, startForeground failure. */
final class ScenarioP2b {

    static void run(File root) throws Exception {
        d(root);
        e(root);
        f(root);
        g(root);
    }

    // ------------------------------------------------------------------ d: export failures
    static void d(File root) throws Exception {
        final String[] names = {"insert returns null", "write throws", "close throws"};
        for (int m = 0; m < 3; m++) {
            final String t = "P2d" + (m + 1);
            System.out.println("\n===== " + t + ": export failure, " + names[m] + " =====");
            final ServiceHost h = new ServiceHost(root, "p2d" + (m + 1));
            h.startService(h.startIntent());
            h.awaitRecording(5000);
            h.feed(2.0);
            if (m == 0) h.cr.failInsert = true; else if (m == 1) h.cr.failWrite = true; else h.cr.failClose = true;
            h.startService(h.stopIntent());
            final boolean idle = h.awaitIdle(10_000);
            final RecorderService s = h.instances.get(0);
            final File f = ScenarioP2.theFile(h);
            Check.ok(t + ".01 exportText starts with \"EXPORT FAILED\"; LIVE.saving false afterwards",
                    idle && Recorder.LIVE.exportText.startsWith("EXPORT FAILED") && !Recorder.LIVE.saving, "idle=" + idle + " exportText='" + Recorder.LIVE.exportText + "'");
            Check.ok(t + ".02 no MediaStore row left, in particular no pending row (the half-written one was deleted)",
                    h.cr.rows().isEmpty() && h.cr.pendingCount() == 0 && h.cr.insertCalls == 1 && h.cr.deleteCalls == (m == 0 ? 0 : 1),
                    "rows=" + h.cr.rows().size() + " pending=" + h.cr.pendingCount() + " insertCalls=" + h.cr.insertCalls + " deleteCalls=" + h.cr.deleteCalls + " openCalls=" + h.cr.openCalls);
            Check.ok(t + ".03 the service still stops cleanly: stopForeground once, stopSelf once, instance destroyed, wake lock released once",
                    s.stopForegroundCalls == 1 && s.stopSelfCalls == 1 && h.destroyedCount == 1 && h.acquires() == 1 && h.releases() == 1 && !h.anyHeld(),
                    "stopForeground=" + s.stopForegroundCalls + " stopSelf=" + s.stopSelfCalls + " destroyed=" + h.destroyedCount + " acquires=" + h.acquires() + " releases=" + h.releases());
            final String fp = ScenarioP2.fileProblems(f, 190, 210);
            Check.ok(t + ".04 the recording file is still intact (well-formed, START first, STOP last, ~200 A rows)", fp.isEmpty(), fp);
            Check.ok(t + ".05 LIVE.lastExportUri and the saved preference were not updated by the failed export",
                    Recorder.LIVE.lastExportUri.isEmpty() && h.prefs.getString(Exporter.KEY_URI, "").isEmpty(), "uri='" + Recorder.LIVE.lastExportUri + "'");
            h.cr.failInsert = false; h.cr.failWrite = false; h.cr.failClose = false;
            final String again = Exporter.exportToDownloads(h.app, Exporter.newestSession(h.app));
            final List<FakeContentResolver.Row> rows = h.cr.rows();
            Check.ok(t + ".06 \"Re-export last session\" works once the fault is gone: one non-pending row equal to the file",
                    again.startsWith("Saved:") && rows.size() == 1 && f != null && ScenarioP2.rowMatches(rows.get(0), f), again);
            Check.ok(t + ".07 nothing leaked (threads, listeners, wake lock, live instance) and nothing thrown", h.leaks().isEmpty() && h.quiet(), "leaks='" + h.leaks() + "' " + h.quietDetail());
            h.close();
        }
    }

    // ------------------------------------------------------------------ e: onDestroy
    static void e(File root) throws Exception {
        // ---- e1: onDestroy while recording
        System.out.println("\n===== P2e1: system destroys the service while recording =====");
        ServiceHost h = new ServiceHost(root, "p2e1");
        h.startService(h.startIntent());
        h.awaitRecording(5000);
        h.feed(1.5);
        final long t0 = System.nanoTime();
        h.stopService();
        final ServiceHost h1 = h;
        boolean gone = h.awaitTrue(() -> h1.destroyedCount == 1, 5000);
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        File f = ScenarioP2.theFile(h);
        String fp = ScenarioP2.fileProblems(f, 140, 160);
        Check.ok("P2e1.01 onDestroy stopped the Recorder: LIVE.recording false, listeners removed, threads ended, file closed with STOP last (took " + ms + " ms)",
                gone && !Recorder.LIVE.recording && h.sm.regs.isEmpty() && h.lm.listener == null && h.lm.gnss == null && fp.isEmpty(),
                "gone=" + gone + " fileProblems='" + fp + "' leaks='" + h.leaks() + "'");
        Check.ok("P2e1.02 wake lock released once; no export started (no rows, LIVE.saving false); no stopSelf/stopForeground needed",
                h.acquires() == 1 && h.releases() == 1 && !h.anyHeld() && h.cr.rows().isEmpty() && h.cr.insertCalls == 0 && !Recorder.LIVE.saving
                        && h.instances.get(0).stopSelfCalls == 0, "acquires=" + h.acquires() + " releases=" + h.releases() + " insertCalls=" + h.cr.insertCalls);
        Check.ok("P2e1.03 nothing leaked or thrown", h.leaks().isEmpty() && h.quiet(), "leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();

        // ---- e2: onDestroy during the save, then the late finishStop arrives on the main looper
        System.out.println("\n===== P2e2: system destroys the service 0.3 s into a 1.5 s save =====");
        h = new ServiceHost(root, "p2e2");
        h.startService(h.startIntent());
        h.awaitRecording(5000);
        h.feed(1.5);
        h.cr.insertDelayMs = 1500;
        h.startService(h.stopIntent());
        Thread.sleep(300);
        final ServiceHost h2 = h;
        h.stopService();
        gone = h.awaitTrue(() -> h2.destroyedCount == 1, 5000);
        Thread.sleep(100);
        final boolean stillSaving = Recorder.LIVE.saving;
        final int stopSelfAtDestroy = h.instances.get(0).stopSelfCalls;
        Check.ok("P2e2.01 right after onDestroy (save still running): Recorder stopped, wake lock released once, listeners gone, no exception",
                gone && !Recorder.LIVE.recording && h.acquires() == 1 && h.releases() == 1 && !h.anyHeld() && h.sm.regs.isEmpty() && h.lm.listener == null && h.quiet(),
                "gone=" + gone + " saving=" + stillSaving + " acquires=" + h.acquires() + " releases=" + h.releases() + " " + h.quietDetail());
        boolean idle = h.awaitIdle(10_000);
        final RecorderService old = h.instances.get(0);
        f = ScenarioP2.theFile(h);
        List<FakeContentResolver.Row> rows = h.cr.rows();
        Check.ok("P2e2.02 the save still completes after onDestroy: one non-pending row equal to the file, LIVE.saving false at the end",
                idle && rows.size() == 1 && f != null && ScenarioP2.rowMatches(rows.get(0), f) && !Recorder.LIVE.saving && Recorder.LIVE.exportText.startsWith("Saved:"),
                "idle=" + idle + " rows=" + rows.size() + " exportText='" + Recorder.LIVE.exportText + "'");
        Check.ok("P2e2.03 the late finishStop is harmless: it runs on main after onDestroy (stopForeground/stopSelf on the dead instance are no-ops), no second wake-lock release, no live instance created, nothing thrown",
                old.stopSelfCalls == stopSelfAtDestroy + 1 && old.stopForegroundCalls == 1 && h.log.stream().anyMatch(x -> x.startsWith("stopSelf") && x.contains("[instance already destroyed]"))
                        && h.releases() == 1 && h.pm.locks.get(0).ignoredReleases == 0 && h.instances.size() == 1 && h.current == null && h.destroyedCount == 1 && h.quiet() && h.leaks().isEmpty(),
                "log=" + h.log + " leaks='" + h.leaks() + "' " + h.quietDetail());
        h.startService(h.startIntent());                 // a fresh START afterwards
        boolean rec = h.awaitRecording(5000);
        h.feed(1.0);
        h.startService(h.stopIntent());
        idle = h.awaitIdle(10_000);
        Check.ok("P2e2.04 afterwards a fresh START works on a new instance and saves (2 rows, 2 wake locks each released once)",
                rec && idle && h.instances.size() == 2 && h.cr.rows().size() == 2 && h.cr.pendingCount() == 0 && h.acquires() == 2 && h.releases() == 2 && h.leaks().isEmpty() && h.quiet(),
                "instances=" + h.instances.size() + " rows=" + h.cr.rows().size() + " leaks='" + h.leaks() + "'");
        h.close();

        // ---- e3: the late finishStop of the destroyed instance vs. a NEWER session that is saving
        System.out.println("\n===== P2e3: instance A destroyed mid-save, session B started and stopped; A's late finishStop arrives while B is saving =====");
        h = new ServiceHost(root, "p2e3");
        h.startService(h.startIntent());
        h.awaitRecording(5000);
        h.feed(1.2);
        h.cr.insertDelayMs = 2500;
        final long tA = System.nanoTime();
        h.startService(h.stopIntent());                  // save A: ends at about tA + 2.5 s
        Thread.sleep(300);
        h.stopService();                                 // A destroyed during its save
        Thread.sleep(200);
        h.startService(h.startIntent());                 // session B on a new instance
        h.awaitRecording(5000);
        h.feed(0.6);
        final long tB = System.nanoTime();
        h.startService(h.stopIntent());                  // save B: ends at about tB + 2.5 s
        final long sampleAt = tA + 3_000_000_000L;       // after A's save ended (~2.5 s) and before B's (~tB + 2.5 s)
        long wait = (sampleAt - System.nanoTime()) / 1_000_000L;
        if (wait > 0) Thread.sleep(wait);
        final boolean savingNow = Recorder.LIVE.saving;
        final long bRemainingMs = (tB + 2_500_000_000L - System.nanoTime()) / 1_000_000L;
        final String seqSoFar = h.probe.seq();
        idle = h.awaitIdle(10_000);
        Check.ok("P2e3.01 LIVE.saving stays true while session B is still being saved, even after A's late finishStop (sampled " + bRemainingMs + " ms before B's save ends)",
                bRemainingMs > 300 && savingNow, "LIVE.saving=" + savingNow + " bRemainingMs=" + bRemainingMs + " states=" + seqSoFar);
        Check.ok("P2e3.02 both sessions end up saved (2 non-pending rows), both wake locks released once, LIVE.saving false at the very end, nothing leaked or thrown",
                idle && h.cr.rows().size() == 2 && h.cr.pendingCount() == 0 && h.acquires() == 2 && h.releases() == 2 && !Recorder.LIVE.saving && h.leaks().isEmpty() && h.quiet(),
                "rows=" + h.cr.rows().size() + " acquires=" + h.acquires() + " releases=" + h.releases() + " leaks='" + h.leaks() + "'");
        h.close();
    }

    // ------------------------------------------------------------------ f: Recorder.start() fails
    static void f(File root) throws Exception {
        System.out.println("\n===== P2f: Recorder.start() fails (files dir is under a regular file) =====");
        final ServiceHost h = new ServiceHost(root, "p2f");
        final File blocker = new File(h.root, "blocker");
        blocker.createNewFile();
        h.ext = new File(blocker, "files");
        h.startService(h.startIntent());
        final boolean gone = h.awaitTrue(() -> h.destroyedCount == 1, 5000);
        final RecorderService s = h.instances.isEmpty() ? null : h.instances.get(0);
        Check.ok("P2f.01 wake lock acquired once and released once (not held)",
                gone && h.pm.locks.size() == 1 && h.acquires() == 1 && h.releases() == 1 && !h.anyHeld(), "locks=" + h.pm.locks.size() + " acquires=" + h.acquires() + " releases=" + h.releases());
        Check.ok("P2f.02 startForeground once, then stopForeground once (REMOVE) and stopSelf once; instance destroyed",
                s != null && s.startForegroundCalls == 1 && s.stopForegroundCalls == 1 && s.stopSelfCalls == 1 && h.log.contains("stopForeground(1) @main") && h.destroyedCount == 1, "log=" + h.log);
        Check.ok("P2f.03 LIVE.failed true with a \"Cannot create file\" error; LIVE.recording and LIVE.saving false",
                Recorder.LIVE.failed && Recorder.LIVE.error.startsWith("Cannot create file") && !Recorder.LIVE.recording && !Recorder.LIVE.saving, "error='" + Recorder.LIVE.error + "'");
        Check.ok("P2f.04 no Recorder side effects: no sensor/GPS registration, no threads, no export, nothing thrown",
                h.sm.registerCalls == 0 && h.lm.requestCalls == 0 && h.cr.insertCalls == 0 && h.leaks().isEmpty() && h.quiet(), "leaks='" + h.leaks() + "' " + h.quietDetail());
        h.ext = new File(h.root, "Android/data/app.tracklog/files");       // the problem is fixed: a new START must work
        h.startService(h.startIntent());
        final boolean rec = h.awaitRecording(5000);
        final boolean cleared = !Recorder.LIVE.failed;
        h.feed(1.0);
        h.startService(h.stopIntent());
        final boolean idle = h.awaitIdle(10_000);
        Check.ok("P2f.05 after the problem is fixed a new START records and saves normally (LIVE.failed cleared, 1 row, 2nd wake lock released)",
                rec && cleared && idle && h.instances.size() == 2 && h.cr.rows().size() == 1 && h.acquires() == 2 && h.releases() == 2 && h.leaks().isEmpty() && h.quiet(),
                "rec=" + rec + " cleared=" + cleared + " rows=" + h.cr.rows().size() + " leaks='" + h.leaks() + "'");
        h.close();
    }

    // ------------------------------------------------------------------ g: startForeground throws (extra)
    static void g(File root) throws Exception {
        System.out.println("\n===== P2g (extra): startForeground throws SecurityException =====");
        final ServiceHost h = new ServiceHost(root, "p2g");
        h.startForegroundFailure = new SecurityException("injected: foreground service not allowed");
        h.startService(h.startIntent());
        final boolean gone = h.awaitTrue(() -> h.destroyedCount == 1, 5000);
        final RecorderService s = h.instances.isEmpty() ? null : h.instances.get(0);
        Check.ok("P2g.01 startForeground failure: LIVE.failed with \"Cannot run in background\", stopSelf once, no wake lock, no Recorder, no threads, nothing thrown",
                gone && Recorder.LIVE.failed && Recorder.LIVE.error.startsWith("Cannot run in background") && s != null && s.stopSelfCalls == 1 && h.pm.locks.isEmpty()
                        && h.sm.registerCalls == 0 && h.leaks().isEmpty() && h.quiet(), "error='" + Recorder.LIVE.error + "' leaks='" + h.leaks() + "' " + h.quietDetail());
        h.startForegroundFailure = null;
        h.startService(h.startIntent());
        final boolean rec = h.awaitRecording(5000);
        h.feed(1.0);
        h.startService(h.stopIntent());
        final boolean idle = h.awaitIdle(10_000);
        Check.ok("P2g.02 once startForeground works again, a new START records and saves", rec && idle && h.cr.rows().size() == 1 && h.leaks().isEmpty() && h.quiet(),
                "rec=" + rec + " rows=" + h.cr.rows().size());
        h.close();
    }
}
