package app.tracklog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the GPS fixes (the L rows) out of a recorded session file, so an old recording can be scored against a stage. Plain Java.
 * Rows it does not understand are skipped, like the reader in analysis/ does; the sensor rows (most of a file) are skipped without
 * being copied. (On a computer the 27 MB Sepang recording takes under a tenth of a second either way; the point of reading it this way
 * is that no line, however long, and no file, however large, can make it use more memory than a bounded number of fixes.)
 *
 * It is also used on a file the person picked, which could be anything, so it is bounded: a line longer than MAX_LINE characters is
 * skipped without being kept, and at most MAX_FIXES fixes are read (the rest are not, and Log.truncated says so).
 */
final class LogReader {

    /** A GPS row is about 100 characters; the header lines are shorter than this too. */
    static final int MAX_LINE = 600;
    /** About 7 days at 1 Hz, or 17 hours at 10 Hz: far more than a drive. */
    static final int MAX_FIXES = 600000;

    /** What the stage code needs from a session file. */
    static final class Log {
        final List<Fix> fixes = new ArrayList<Fix>();
        long startUtcMs;            // from the header, 0 if absent
        String name = "";
        boolean truncated;          // more GPS rows than MAX_FIXES: the rest were not read
    }

    private LogReader() { }

    static Log read(File csv) throws IOException {
        return read(new FileInputStream(csv), csv.getName());
    }

    /** Reads and closes the stream. */
    static Log read(InputStream in, String name) throws IOException {
        return read(in, name, 1 << 16);
    }

    /** As above with the size of the read buffer given: the tests use tiny ones to put line ends on every kind of boundary. */
    static Log read(InputStream in, String name, int bufferChars) throws IOException {
        final Log log = new Log();
        log.name = name == null ? "" : name;
        final Reader rd = new InputStreamReader(in, Charset.forName("UTF-8"));
        try {
            final Lines lines = new Lines(rd, bufferChars);
            String line;
            while ((line = lines.next()) != null) {
                if (line.startsWith("L,")) {
                    if (log.fixes.size() >= MAX_FIXES) {
                        log.truncated = true;
                        continue;
                    }
                    final String[] p = line.split(",", -1);
                    if (p.length != 12) continue;
                    try {
                        final double t = Long.parseLong(p[1]) / 1e6;
                        final Fix f = new Fix(t, Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[5]),
                                Double.parseDouble(p[6]), Double.parseDouble(p[7]));
                        if (f.usable()) log.fixes.add(f);
                    } catch (NumberFormatException e) {
                        // a damaged row: skipped
                    }
                } else if (line.startsWith("# start_utc_ms,")) {
                    try {
                        log.startUtcMs = Long.parseLong(line.substring("# start_utc_ms,".length()).trim());
                    } catch (NumberFormatException ignored) {
                        // no start time
                    }
                }
            }
        } finally {
            try { rd.close(); } catch (IOException ignored) { }
        }
        return log;
    }

    /**
     * The lines of a text that begin with 'L' or '#', one at a time, found by scanning a buffer for the line end: every other line
     * (the A and G sensor rows, the E rows) is passed over without being copied.
     */
    private static final class Lines {
        private final Reader r;
        private final char[] buf;
        private int pos, lim;
        private boolean eof;
        private final StringBuilder sb = new StringBuilder(160);

        Lines(Reader r, int bufferChars) {
            this.r = r;
            this.buf = new char[Math.max(1, bufferChars)];
        }

        private boolean fill() throws IOException {
            if (eof) return false;
            final int n = r.read(buf, 0, buf.length);
            if (n < 0) {
                eof = true;
                return false;
            }
            pos = 0;
            lim = n;
            return n > 0 || fill();
        }

        /** The next line that starts with 'L' or '#' and is at most MAX_LINE characters (no line end), or null at the end. */
        String next() throws IOException {
            for (;;) {
                if (pos >= lim && !fill()) return null;
                final char first = buf[pos];
                final boolean keep = first == 'L' || first == '#';
                sb.setLength(0);
                boolean over = false;
                boolean ended = false;                          // a line end was found (false: the text ended inside the line)
                for (;;) {
                    if (pos >= lim && !fill()) break;
                    int i = pos;
                    while (i < lim && buf[i] != '\n') i++;
                    if (keep && !over) {
                        final int room = MAX_LINE + 1 - sb.length();      // one more than the limit: the last one may be the CR of a CRLF
                        final int n = i - pos;
                        if (n > room) {
                            over = true;
                        } else {
                            sb.append(buf, pos, n);
                        }
                    }
                    if (i < lim) {
                        pos = i + 1;
                        ended = true;
                        break;
                    }
                    pos = lim;
                }
                if (!keep || over) {
                    if (!ended) return null;                    // the text ended inside a line that is not wanted
                    continue;
                }
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(--len);
                if (len > MAX_LINE) continue;                   // the extra character was not a CR: the line is too long
                return sb.toString();
            }
        }
    }
}
