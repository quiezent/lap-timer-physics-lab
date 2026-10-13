package app.tracklog;

import android.os.PowerManager;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Part 2, scenarios a-c: the real RecorderService + Exporter + Recorder inside the fake Android system (ServiceHost). */
final class ScenarioP2 {

    static void run(File root) throws Exception {
        a(root);
        b(root);
        c(root);
        ScenarioP2b.run(root);          // d, e, f, g
    }

    // ------------------------------------------------------------------ shared helpers
    /** From the first state with S (saving) to the last one, every state has S, and the sequence ends idle ("--"). */
    static boolean savingContinuous(List<String> st) {
        int first = -1, last = -1;
        for (int i = 0; i < st.size(); i++) if (st.get(i).charAt(1) == 'S') { if (first < 0) first = i; last = i; }
        if (first < 0) return false;
        for (int i = first; i <= last; i++) if (st.get(i).charAt(1) != 'S') return false;
        return st.get(st.size() - 1).equals("--");
    }

    static int indexOf(List<String> log, String prefix) {
        for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith(prefix)) return i;
        return -1;
    }

    static File theFile(ServiceHost h) { File[] fs = h.sessionFiles(); return fs.length == 1 ? fs[0] : null; }

    static boolean rowMatches(FakeContentResolver.Row r, File src) throws Exception {
        return r != null && r.pending == 0 && r.data != null && Arrays.equals(r.data, Files.readAllBytes(src.toPath()))
                && r.name.equals(src.getName()) && "text/csv".equals(r.mime) && "Download/TrackLogger".equals(r.path);
    }

    static String fileProblems(File f, int minA, int maxA) throws Exception {
        if (f == null) return "no single session file";
        CsvAudit a = new CsvAudit(f);
        List<String> bad = a.badRows();
        StringBuilder b = new StringBuilder();
        if (!bad.isEmpty()) b.append(" badRows=").append(bad.size()).append(" first:").append(bad.get(0));
        if (a.data.isEmpty() || !a.data.get(0).equals("E,0,START")) b.append(" noStartFirst");
        if (a.data.isEmpty() || !a.data.get(a.data.size() - 1).matches("E,\\d+,STOP")) b.append(" noStopLast");
        int n = a.kind("A").size();
        if (n < minA || n > maxA) b.append(" A=").append(n).append("(wanted ").append(minA).append("..").append(maxA).append(")");
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ a: normal
    static void a(File root) throws Exception {
        final String t = "P2a";
        System.out.println("\n===== P2a: normal START, record 3 s, STOP, let the export finish =====");
        final ServiceHost h = new ServiceHost(root, "p2a");
        h.startService(h.startIntent());
        boolean recording = h.awaitRecording(5000);
        final RecorderService svc = h.instances.isEmpty() ? null : h.instances.get(0);
        final PowerManager.WakeLock wl = h.pm.locks.isEmpty() ? null : h.pm.locks.get(0);
        Check.ok(t + ".01 after START: one service instance, startForeground once with type LOCATION (8), channel created, one wake lock acquired and held, one Recorder registered (2 sensors + GPS + GNSS)",
                recording && h.instances.size() == 1 && svc.startForegroundCalls == 1 && svc.lastForegroundType == 8 && h.nm.channels.contains("recording")
                        && h.pm.locks.size() == 1 && wl.acquireCalls == 1 && wl.isHeld() && h.sm.registerCalls == 2 && h.lm.requestCalls == 1 && h.lm.gnssRegCalls == 1,
                "recording=" + recording + " instances=" + h.instances.size() + " startForeground=" + (svc == null ? -1 : svc.startForegroundCalls) + " type=" + (svc == null ? -1 : svc.lastForegroundType)
                        + " locks=" + h.pm.locks.size() + " acquires=" + h.acquires() + " sensorRegs=" + h.sm.registerCalls);
        final Feeder fd = h.feed(3.0);
        h.startService(h.stopIntent());
        final boolean idle = h.awaitIdle(10_000);
        Check.ok(t + ".02 STOP: service went idle within 10 s (save finished, instance destroyed)", idle && h.destroyedCount == 1 && fd.error == null,
                "idle=" + idle + " destroyed=" + h.destroyedCount + " exportText='" + Recorder.LIVE.exportText + "'");
        Check.ok(t + ".03 wake lock released exactly once and not held",
                wl.acquireCalls == 1 && wl.effectiveReleases == 1 && wl.ignoredReleases == 0 && !wl.isHeld() && h.pm.locks.size() == 1,
                "acquire=" + wl.acquireCalls + " release=" + wl.effectiveReleases + " ignoredReleases=" + wl.ignoredReleases + " held=" + wl.isHeld());
        int iFg = indexOf(h.log, "startForeground"), iStopFg = indexOf(h.log, "stopForeground"), iSelf = indexOf(h.log, "stopSelf"), iDestroy = indexOf(h.log, "-- system: onDestroy");
        Check.ok(t + ".04 startForeground once, stopForeground once (REMOVE), stopSelf once, in that order, all on the main thread, then onDestroy",
                svc.startForegroundCalls == 1 && svc.stopForegroundCalls == 1 && svc.stopSelfCalls == 1 && h.log.contains("stopForeground(1) @main")
                        && iFg < iStopFg && iStopFg < iSelf && iSelf < iDestroy && h.serviceCallsAllOnMain(),
                "log=" + h.log);
        Check.ok(t + ".05 LIVE.saving false and LIVE.recording false at the end; saving was observed true and never dropped before the save ended",
                !Recorder.LIVE.saving && !Recorder.LIVE.recording && savingContinuous(h.probe.states), "state sequence (R=recording,S=saving): " + h.probe.seq());
        final File f = theFile(h);
        final List<FakeContentResolver.Row> rows = h.cr.rows();
        Check.ok(t + ".06 exactly one non-pending MediaStore row, no pending rows; its bytes equal the source file; name/mime/path right",
                f != null && rows.size() == 1 && h.cr.pendingCount() == 0 && h.cr.visibleCount() == 1 && rowMatches(rows.get(0), f),
                "rows=" + rows.size() + " pending=" + h.cr.pendingCount() + (rows.isEmpty() || f == null ? "" : " rowBytes=" + (rows.get(0).data == null ? -1 : rows.get(0).data.length) + " fileBytes=" + f.length()));
        Check.ok(t + ".07 export message \"Saved: ...\", LIVE.lastExportUri and the saved preference point at the new row",
                Recorder.LIVE.exportText.startsWith("Saved: Downloads/TrackLogger/") && !rows.isEmpty()
                        && Recorder.LIVE.lastExportUri.endsWith("/" + rows.get(0).id) && Recorder.LIVE.lastExportUri.equals(h.prefs.getString(Exporter.KEY_URI, "")),
                "exportText='" + Recorder.LIVE.exportText + "' uri=" + Recorder.LIVE.lastExportUri);
        final String fp = fileProblems(f, 290, 310);
        Check.ok(t + ".08 the recording file is intact: well-formed rows, START first, STOP last, ~300 A rows", fp.isEmpty(), fp);
        Check.ok(t + ".09 nothing leaked (no recorder/stop threads, no listeners, no wake lock, no live service) and nothing thrown", h.leaks().isEmpty() && h.quiet(),
                "leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();
    }

    // ------------------------------------------------------------------ b: START during the save
    static void b(File root) throws Exception {
        final String t = "P2b";
        System.out.println("\n===== P2b: START pressed 0.3 s into a 1.5 s save =====");
        final ServiceHost h = new ServiceHost(root, "p2b");
        h.startService(h.startIntent());
        h.awaitRecording(5000);
        h.feed(2.0);
        h.cr.insertDelayMs = 1500;                       // the export takes about 1.5 s
        final long tStop = System.nanoTime();
        h.startService(h.stopIntent());
        Thread.sleep(300);
        final boolean saving03 = Recorder.LIVE.saving;
        h.startService(h.startIntent());                 // START during the save
        Thread.sleep(300);
        final RecorderService first = h.instances.get(0);
        final boolean saving06 = Recorder.LIVE.saving;
        Check.ok(t + ".01 LIVE.saving is true at +0.3 s and +0.6 s into the save, LIVE.recording false",
                saving03 && saving06 && !Recorder.LIVE.recording, "saving@0.3=" + saving03 + " saving@0.6=" + saving06 + " recording=" + Recorder.LIVE.recording);
        Check.ok(t + ".02 the second START was ignored: no second instance, no second startForeground, no second wake lock, no second Recorder (no new sensor/GPS registration, no second file)",
                h.instances.size() == 1 && first.startForegroundCalls == 1 && h.pm.locks.size() == 1 && h.pm.locks.get(0).acquireCalls == 1
                        && h.sm.registerCalls == 2 && h.lm.requestCalls == 1 && h.sessionFiles().length == 1 && first.stopSelfCalls == 0 && first.stopForegroundCalls == 0,
                "instances=" + h.instances.size() + " startForeground=" + first.startForegroundCalls + " locks=" + h.pm.locks.size() + " acquires=" + h.acquires()
                        + " sensorRegs=" + h.sm.registerCalls + " gpsRequests=" + h.lm.requestCalls + " files=" + h.sessionFiles().length);
        final boolean idle = h.awaitIdle(10_000);
        final long saveMs = (System.nanoTime() - tStop) / 1_000_000L;
        final int mark = h.probe.states.size();
        final File f1 = theFile(h);
        final List<FakeContentResolver.Row> rows1 = h.cr.rows();
        Check.ok(t + ".03 the first save finished (took >= 1.4 s): one non-pending row equal to the file, wake lock released once, stopForeground once, stopSelf once, instance destroyed",
                idle && saveMs >= 1400 && rows1.size() == 1 && f1 != null && rowMatches(rows1.get(0), f1)
                        && h.acquires() == 1 && h.releases() == 1 && !h.anyHeld() && first.stopForegroundCalls == 1 && first.stopSelfCalls == 1 && h.destroyedCount == 1,
                "idle=" + idle + " saveMs=" + saveMs + " rows=" + rows1.size() + " acquires=" + h.acquires() + " releases=" + h.releases() + " destroyed=" + h.destroyedCount);
        Check.ok(t + ".04 LIVE.saving stayed true for the whole save and is false afterwards",
                !Recorder.LIVE.saving && savingContinuous(h.probe.states.subList(0, mark)), "state sequence: " + h.probe.seq());

        // a fresh START now goes to a NEW service instance
        h.cr.insertDelayMs = 0;
        h.startService(h.startIntent());
        final boolean rec2 = h.awaitRecording(5000);
        final RecorderService second = h.instances.size() > 1 ? h.instances.get(1) : null;
        Check.ok(t + ".05 a fresh START after the save creates a NEW service instance that records normally (foreground, new wake lock held, new Recorder registered)",
                rec2 && second != null && second != first && h.instances.size() == 2 && second.startForegroundCalls == 1 && h.pm.locks.size() == 2
                        && h.pm.locks.get(1).isHeld() && h.sm.registerCalls == 4 && Recorder.LIVE.recording && !Recorder.LIVE.saving,
                "rec2=" + rec2 + " instances=" + h.instances.size() + " locks=" + h.pm.locks.size() + " sensorRegs=" + h.sm.registerCalls);
        h.feed(1.5);
        h.startService(h.stopIntent());
        final boolean idle2 = h.awaitIdle(10_000);
        final List<FakeContentResolver.Row> rows2 = h.cr.rows();
        Check.ok(t + ".06 second session saved too: 2 non-pending rows, no pending, 2 wake locks each acquired and released once, stopSelf/stopForeground once per instance, nothing leaked or thrown",
                idle2 && rows2.size() == 2 && h.cr.pendingCount() == 0 && h.acquires() == 2 && h.releases() == 2 && !h.anyHeld()
                        && second != null && second.stopSelfCalls == 1 && second.stopForegroundCalls == 1 && h.destroyedCount == 2 && h.leaks().isEmpty() && h.quiet(),
                "idle2=" + idle2 + " rows=" + rows2.size() + " leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();
    }

    // ------------------------------------------------------------------ c: double STOP, STOP when idle, double START
    static void c(File root) throws Exception {
        final String t = "P2c";
        // ---- c1: STOP when idle
        System.out.println("\n===== P2c1: STOP when nothing is running =====");
        ServiceHost h = new ServiceHost(root, "p2c1");
        h.startService(h.stopIntent());
        final ServiceHost hc1 = h;
        boolean gone = h.awaitTrue(() -> hc1.destroyedCount == 1, 3000);
        RecorderService s = h.instances.isEmpty() ? null : h.instances.get(0);
        Check.ok(t + "1.01 idle STOP: the system makes one instance, it calls stopForeground once and stopSelf once, no startForeground, no wake lock, no Recorder, no export, instance destroyed, nothing thrown",
                gone && s != null && s.stopSelfCalls == 1 && s.stopForegroundCalls == 1 && s.startForegroundCalls == 0 && h.pm.locks.isEmpty()
                        && h.sm.registerCalls == 0 && h.cr.rows().isEmpty() && h.leaks().isEmpty() && h.quiet(),
                "gone=" + gone + " log=" + h.log + " leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();

        // ---- c2: double START (+ a third one while recording)
        System.out.println("\n===== P2c2: START twice in a row (and a third time while recording) =====");
        h = new ServiceHost(root, "p2c2");
        h.startService(h.startIntent());
        h.startService(h.startIntent());
        boolean rec = h.awaitRecording(5000);
        Thread.sleep(300);
        h.startService(h.startIntent());
        Thread.sleep(300);
        s = h.instances.isEmpty() ? null : h.instances.get(0);
        Check.ok(t + "2.01 double/triple START: one instance, startForeground once, one wake lock acquired once, one Recorder (2 sensor registrations, one GPS request), one session file",
                rec && h.instances.size() == 1 && s.startForegroundCalls == 1 && h.pm.locks.size() == 1 && h.pm.locks.get(0).acquireCalls == 1 && h.sm.registerCalls == 2
                        && h.lm.requestCalls == 1 && h.sessionFiles().length == 1,
                "instances=" + h.instances.size() + " startForeground=" + s.startForegroundCalls + " locks=" + h.pm.locks.size() + " acquires=" + h.acquires() + " sensorRegs=" + h.sm.registerCalls + " files=" + h.sessionFiles().length);
        h.feed(1.0);
        h.startService(h.stopIntent());
        boolean idle = h.awaitIdle(10_000);
        File f = theFile(h);
        List<FakeContentResolver.Row> rows = h.cr.rows();
        Check.ok(t + "2.02 after STOP: one saved row equal to the file, wake lock released once, stopSelf/stopForeground once, nothing leaked or thrown",
                idle && rows.size() == 1 && f != null && rowMatches(rows.get(0), f) && h.acquires() == 1 && h.releases() == 1 && s.stopSelfCalls == 1 && s.stopForegroundCalls == 1
                        && h.leaks().isEmpty() && h.quiet(), "idle=" + idle + " rows=" + rows.size() + " leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();

        // ---- c3: double/triple STOP
        System.out.println("\n===== P2c3: STOP, STOP at once, STOP again during the save, then STOP after everything ended =====");
        h = new ServiceHost(root, "p2c3");
        h.startService(h.startIntent());
        h.awaitRecording(5000);
        h.feed(1.5);
        h.cr.insertDelayMs = 800;
        h.startService(h.stopIntent());
        h.startService(h.stopIntent());
        Thread.sleep(400);
        h.startService(h.stopIntent());
        idle = h.awaitIdle(10_000);
        s = h.instances.isEmpty() ? null : h.instances.get(0);
        f = theFile(h);
        rows = h.cr.rows();
        Check.ok(t + "3.01 three STOPs: exactly one export (insert called once, one row equal to the file), wake lock released once, stopSelf/stopForeground once, one instance",
                idle && h.instances.size() == 1 && h.cr.insertCalls == 1 && rows.size() == 1 && f != null && rowMatches(rows.get(0), f) && h.acquires() == 1 && h.releases() == 1
                        && s.stopSelfCalls == 1 && s.stopForegroundCalls == 1 && h.destroyedCount == 1,
                "instances=" + h.instances.size() + " insertCalls=" + h.cr.insertCalls + " rows=" + rows.size() + " log=" + h.log);
        h.startService(h.stopIntent());                  // STOP once everything is over -> a fresh instance handles it as an idle STOP
        final ServiceHost hc3 = h;
        boolean gone3 = h.awaitTrue(() -> hc3.destroyedCount == 2, 3000);
        Check.ok(t + "3.02 a STOP after the session ended is harmless: new instance stops itself, no new wake lock/Recorder/export, state unchanged",
                gone3 && h.instances.size() == 2 && h.instances.get(1).stopSelfCalls == 1 && h.pm.locks.size() == 1 && h.sm.registerCalls == 2 && h.cr.insertCalls == 1
                        && h.cr.rows().size() == 1 && h.leaks().isEmpty() && h.quiet(), "leaks='" + h.leaks() + "' " + h.quietDetail());
        h.close();
    }
}
