package app.tracklog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JVM harness for app.tracklog.Recorder with fake android.* classes.
 * usage: java -cp <classes> app.tracklog.Harness [all|s1|s2|s3|p1|p2|l1] [outDir]
 */
public final class Harness {
    static final ByteArrayOutputStream ERR = new ByteArrayOutputStream();
    static final List<Throwable> UNCAUGHT_THREADS = new CopyOnWriteArrayList<>();

    static int errBytes() { synchronized (ERR) { return ERR.size(); } }

    private static final class Tee extends OutputStream {
        private final OutputStream a, b;
        Tee(OutputStream a, OutputStream b) { this.a = a; this.b = b; }
        @Override public void write(int c) throws IOException { a.write(c); synchronized (b) { b.write(c); } }
        @Override public void write(byte[] buf, int off, int len) throws IOException { a.write(buf, off, len); synchronized (b) { b.write(buf, off, len); } }
        @Override public void flush() throws IOException { a.flush(); }
    }

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "all";
        final File root = new File(args.length > 1 ? args[1] : "out").getAbsoluteFile();
        root.mkdirs();

        // anything Java code prints to stderr from now on counts as "an exception was printed"
        System.setErr(new PrintStream(new Tee(System.err, ERR), true));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            UNCAUGHT_THREADS.add(e);
            System.out.println("UNCAUGHT in thread " + t.getName() + ": " + e);
        });

        // whole-run watchdog
        Thread gw = new Thread(() -> {
            try { Thread.sleep(150_000); } catch (InterruptedException e) { return; }
            System.out.println("FAIL  global watchdog: harness still running after 150 s");
            System.out.flush();
            Runtime.getRuntime().halt(4);
        }, "watchdog-global");
        gw.setDaemon(true);
        gw.start();

        System.out.println("java " + System.getProperty("java.version") + "; mode=" + mode + "; out=" + root);
        System.out.println("android.os.Handler comes from: " + android.os.Handler.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("Recorder comes from:           " + Recorder.class.getProtectionDomain().getCodeSource().getLocation());

        if (mode.equals("all") || mode.equals("s1")) Scenario1.run(root);
        if (mode.equals("all") || mode.equals("s2")) Scenario2.run(root);
        if (mode.equals("all") || mode.equals("s3")) Scenario3.run(root);
        if (mode.equals("all") || mode.equals("p1")) ScenarioP1.run(root);
        if (mode.equals("all") || mode.equals("p2")) ScenarioP2.run(root);
        if (mode.equals("all") || mode.equals("l1")) ScenarioL1.run(root);

        System.out.println("\n===== SUMMARY =====");
        System.out.println("required checks: " + Check.pass + " PASS, " + Check.fail + " FAIL;   extra cleanup probes failing: " + Check.defectFail);
        System.out.flush();
        System.exit(Check.fail == 0 && Check.defectFail == 0 ? 0 : 1);
    }
}
